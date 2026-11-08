package pk.aidesk.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Short Hunter: port of the laptop's newlist_bot.py + scanner.py.
 * Rule (backtest: 262 new Binance listings, Mar 2025 - Oct 2026, after fees + funding: +4.3% per trade):
 * SHORT every new USDT perpetual at the start of UTC day 7 after listing, SL +40%, TP -30%, close after 14 days.
 * Pumped coins are only shown (the pump rule lost money in 2026).
 */
data class ShortCfg(
    val enabled: Boolean = false,
    val entryDay: Int = 7,
    val holdDays: Int = 14,
    val slPct: Double = 40.0,
    val tpPct: Double = 30.0,
    val marginPct: Double = 10.0,
    val leverage: Int = 1,
    val maxPositions: Int = 10,
    val entryWindowH: Int = 6,
    val paperStart: Double = 100.0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled); put("entryDay", entryDay); put("holdDays", holdDays); put("slPct", slPct); put("tpPct", tpPct)
        put("marginPct", marginPct); put("leverage", leverage); put("maxPositions", maxPositions); put("entryWindowH", entryWindowH)
        put("paperStart", paperStart)
    }

    companion object {
        fun from(o: JSONObject?): ShortCfg {
            val d = ShortCfg(); o ?: return d
            return ShortCfg(o.optBoolean("enabled", d.enabled), o.optInt("entryDay", d.entryDay), o.optInt("holdDays", d.holdDays),
                o.optDouble("slPct", d.slPct), o.optDouble("tpPct", d.tpPct), o.optDouble("marginPct", d.marginPct),
                o.optInt("leverage", d.leverage), o.optInt("maxPositions", d.maxPositions), o.optInt("entryWindowH", d.entryWindowH),
                o.optDouble("paperStart", d.paperStart))
        }
    }
}

class ShortPos(
    val symbol: String, val entry: Double, val qty: Double, val opened: Long, val exitAt: Long, val margin: Double,
    val sl: Double, val tp: Double, val entryFee: Double, var lastCheck: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("symbol", symbol); put("entry", entry); put("qty", qty); put("opened", opened); put("exitAt", exitAt); put("margin", margin)
        put("sl", sl); put("tp", tp); put("entryFee", entryFee); put("lastCheck", lastCheck)
    }

    companion object {
        fun from(o: JSONObject) = ShortPos(o.getString("symbol"), o.getDouble("entry"), o.getDouble("qty"), o.getLong("opened"),
            o.getLong("exitAt"), o.getDouble("margin"), o.getDouble("sl"), o.getDouble("tp"), o.optDouble("entryFee", 0.0),
            o.optLong("lastCheck", o.getLong("opened")))
    }
}

class ShortState(var paperEquity: Double, val open: MutableMap<String, ShortPos> = linkedMapOf(), val done: MutableList<String> = mutableListOf()) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("paperEquity", paperEquity)
        put("open", JSONObject().also { o -> open.forEach { (k, v) -> o.put(k, v.toJson()) } })
        put("done", JSONArray(done.takeLast(2000)))
    }

    companion object {
        fun from(o: JSONObject?, start: Double): ShortState {
            o ?: return ShortState(start)
            val op = o.optJSONObject("open")
            return ShortState(o.optDouble("paperEquity", start),
                op?.keys()?.asSequence()?.associateWith { ShortPos.from(op.getJSONObject(it)) }?.toMutableMap() ?: linkedMapOf(),
                o.optJSONArray("done")?.let { a -> (0 until a.length()).map { a.getString(it) }.toMutableList() } ?: mutableListOf())
        }
    }
}

class ShortDone(val symbol: String, val entry: Double, val exit: Double, val reason: String, val opened: Long, val closed: Long,
                val pnl: Double, val retPct: Double?) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("symbol", symbol); put("entry", entry); put("exit", exit); put("reason", reason); put("opened", opened); put("closed", closed)
        put("pnl", pnl); retPct?.let { put("retPct", it) }
    }

    companion object {
        fun from(o: JSONObject) = ShortDone(o.getString("symbol"), o.getDouble("entry"), o.getDouble("exit"), o.getString("reason"),
            o.getLong("opened"), o.getLong("closed"), o.getDouble("pnl"), if (o.has("retPct")) o.getDouble("retPct") else null)
    }
}

class Listing(val symbol: String, val listed: Long, val day: Int, val entryTime: Long, val status: String, val note: String)

class Pump(val symbol: String, val ch24: Double, val d1: Double?, val d3: Double?, val volume: Double, val price: Double,
           val fundingPct: Double, val ageDays: Int, val status: String)

class ShortHunter(private val store: Store) {
    companion object {
        const val DAY = 86_400_000L
        private const val FEE = 0.0005
        private const val SLIP = 0.05
    }

    fun cfg(): ShortCfg = ShortCfg.from(store.readObj("shorts_cfg.json"))
    fun saveCfg(c: ShortCfg) = store.writeObj("shorts_cfg.json", c.toJson())
    fun state(mode: String): ShortState = ShortState.from(store.readObj("shorts_state_$mode.json"), cfg().paperStart)
    private fun saveState(mode: String, s: ShortState) = store.writeObj("shorts_state_$mode.json", s.toJson())
    fun journal(mode: String): List<ShortDone> = store.readArr("shorts_journal_$mode.json")?.let { a ->
        (0 until a.length()).mapNotNull { runCatching { ShortDone.from(a.getJSONObject(it)) }.getOrNull() }
    } ?: emptyList()
    private fun addJournal(mode: String, d: ShortDone) =
        store.writeArr("shorts_journal_$mode.json", JSONArray((journal(mode) + d).takeLast(1000).map { it.toJson() }))

    private fun coins(api: Binance): Map<String, JSONObject> = api.exchangeInfo(fresh = true).filterValues {
        it.optString("quoteAsset") == "USDT" && it.optString("contractType") == "PERPETUAL" &&
            it.optString("status") == "TRADING" && it.optString("underlyingType", "COIN") == "COIN"
    }

    // ------------------------------------------------------------------ shortlist (UI)
    fun listings(api: Binance, now: Long = api.nowMs()): List<Listing> {
        val c = cfg()
        val out = mutableListOf<Listing>()
        for ((s, x) in coins(api)) {
            val day0 = x.getLong("onboardDate") / DAY * DAY
            val d = ((now - day0) / DAY).toInt()
            if (d < 0 || d > c.entryDay + c.holdDays) continue
            val entry = day0 + c.entryDay * DAY
            val (st, note) = when {
                d < c.entryDay -> "WAIT" to "short in ${c.entryDay - d} day${if (c.entryDay - d > 1) "s" else ""}"
                d == c.entryDay && now < entry + c.entryWindowH * 3_600_000L -> "SHORT NOW" to "entry window open"
                else -> "MISSED" to "entry window passed"
            }
            out += Listing(s, x.getLong("onboardDate"), d, entry, st, note)
        }
        return out.sortedByDescending { it.listed }
    }

    /** Top 24h gainers with closed-daily-candle 1d / 3d change. Shown only; the bot does not trade them. */
    fun pumps(api: Binance, top: Int = 20, now: Long = api.nowMs()): List<Pump> {
        val cs = coins(api)
        val t = api.ticker24h()
        val prem = api.premiumAll().let { a -> (0 until a.length()).associate { a.getJSONObject(it).let { o -> o.getString("symbol") to o } } }
        val rows = (0 until t.length()).map { t.getJSONObject(it) }
            .filter { it.getString("symbol") in cs && it.getString("symbol") !in setOf("BTCUSDT", "ETHUSDT") }
            .sortedByDescending { it.getString("priceChangePercent").toDouble() }.take(top)
        return rows.mapNotNull { r ->
            val s = r.getString("symbol")
            runCatching {
                val k = api.klines(s, "1d", limit = 6)
                val closed = (0 until k.length()).map { k.getJSONArray(it) }.filter { it.getLong(6) < now }.map { it.getString(4).toDouble() }
                val d1 = if (closed.size >= 2) (closed.last() / closed[closed.size - 2] - 1) * 100 else null
                val d3 = if (closed.size >= 4) (closed.last() / closed[closed.size - 4] - 1) * 100 else null
                val ch = r.getString("priceChangePercent").toDouble()
                val age = ((now - cs.getValue(s).getLong("onboardDate")) / DAY).toInt()
                val fr = prem[s]?.optString("lastFundingRate")?.toDoubleOrNull() ?: 0.0
                val sig = (d1 != null && d1 >= 50) || (d3 != null && d3 >= 100)
                Pump(s, ch, d1, d3, r.getString("quoteVolume").toDouble(), r.getString("lastPrice").toDouble(), fr * 100, age,
                    if (sig && age >= 30) "SIGNAL" else if (ch >= 20) "WATCH" else "-")
            }.getOrNull()
        }
    }

    // ------------------------------------------------------------------ bot
    /** One pass: manage exits, then open new-listing shorts if enabled. `api` = the mode's client (keys for testnet/live). */
    fun step(mode: String, api: Binance, equityOf: () -> Pair<Double, Double>): List<Event> {
        val c = cfg()
        val live = mode != "paper"
        val st = state(mode)
        val ev = mutableListOf<Event>()
        val now = api.nowMs()
        val pos = if (live && st.open.isNotEmpty()) api.positions() else emptyMap()
        for ((s, p) in st.open.toMap()) {
            try {
                if (live) checkLive(mode, api, st, s, p, now, pos, ev) else checkPaper(mode, api, st, s, p, now, ev)
            } catch (e: Exception) {
                store.log("WARN", "Short hunter $s exit check: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        if (c.enabled) {
            val held = if (live) api.positions().keys else emptySet()
            for ((s, x) in coins(api)) {
                val day0 = x.getLong("onboardDate") / DAY * DAY
                val start = day0 + c.entryDay * DAY
                if (now < start || now >= start + c.entryWindowH * 3_600_000L) continue
                if (s in st.open || s in st.done) continue
                st.done += s
                if (s in held) { store.log("INFO", "Short hunter $s: skip, the account already holds $s"); continue }
                try {
                    openShort(mode, api, st, s, now, day0 + (c.entryDay + c.holdDays + 1) * DAY, c, equityOf)?.let { ev += it }
                } catch (e: Exception) {
                    store.log("ERROR", "Short hunter $s entry: ${(e as? BinanceException)?.msg ?: e.message}")
                    ev += Event("error", "Short hunter: $s entry failed", (e as? BinanceException)?.msg ?: e.message ?: "", false, s)
                }
            }
        }
        saveState(mode, st)
        return ev
    }

    private fun openShort(mode: String, api: Binance, st: ShortState, s: String, now: Long, exitAt: Long, c: ShortCfg,
                          equityOf: () -> Pair<Double, Double>): Event? {
        if (st.open.size >= c.maxPositions) { store.log("INFO", "Short hunter $s: skip, ${c.maxPositions} shorts already open"); return null }
        val live = mode != "paper"
        val (eq, avail) = if (live) equityOf() else st.paperEquity to st.paperEquity - st.open.values.sumOf { it.margin }
        val margin = eq * c.marginPct / 100
        if (margin > avail) { store.log("INFO", "Short hunter $s: skip, free margin ${f2(avail)} < ${f2(margin)}"); return null }
        val price = api.markPrice(s)
        val f = api.filters(s)
        val (qty, q) = api.fmtQty(s, margin * c.leverage / price)
        if (qty < f.minQty || qty * price < f.minNotional) {
            val msg = "position ${f2(qty * price)} USDT is below Binance's minimum ${f2(f.minNotional)} USDT (equity ${f2(eq)} x ${c.marginPct}% x ${c.leverage}x)"
            store.log("INFO", "Short hunter $s: skip, $msg")
            return Event("skip", "Short hunter skipped $s", msg, false, s)
        }
        val entry: Double; val fee: Double
        if (!live) {
            entry = price * (1 - SLIP / 100); fee = qty * entry * FEE
            st.paperEquity -= fee
        } else {
            api.setMarginType(s, "ISOLATED"); api.setLeverage(s, c.leverage)
            val r = api.marketOrder(s, "SELL", q)
            entry = r.optString("avgPrice").toDoubleOrNull()?.takeIf { it > 0 } ?: price
            fee = qty * entry * FEE
            try {
                api.closeTrigger(s, "BUY", "STOP_MARKET", api.fmtPrice(s, entry * (1 + c.slPct / 100)).second)
                api.closeTrigger(s, "BUY", "TAKE_PROFIT_MARKET", api.fmtPrice(s, entry * (1 - c.tpPct / 100)).second)
            } catch (e: BinanceException) {
                store.log("ERROR", "Short hunter $s: SL/TP rejected (${e.msg}), closing for safety")
                api.cancelAll(s)
                api.marketOrder(s, "BUY", q, reduceOnly = true)
                return Event("error", "Short hunter: $s closed", "Binance rejected the stop order, so the short was closed.", true, s)
            }
        }
        st.open[s] = ShortPos(s, entry, qty, now, exitAt, margin, entry * (1 + c.slPct / 100), entry * (1 - c.tpPct / 100), fee, now)
        store.log("INFO", "Short hunter OPEN SHORT $s qty $q @ ${Engine.fmt(entry)} (${mode.uppercase()})")
        return Event("botopen", "Short hunter: SHORT $s", "${Engine.fmt(qty)} @ ${Engine.fmt(entry)}  -  SL +${c.slPct.toInt()}%  TP -${c.tpPct.toInt()}%  -  holds up to ${c.holdDays} days", true, s)
    }

    private fun record(mode: String, st: ShortState, p: ShortPos, exit: Double, reason: String, pnl: Double, now: Long, ev: MutableList<Event>) {
        addJournal(mode, ShortDone(p.symbol, p.entry, exit, reason, p.opened, now, pnl, if (p.margin > 0) pnl / p.margin * 100 else null))
        st.open.remove(p.symbol)
        store.log("INFO", "Short hunter CLOSE ${p.symbol} $reason @ ${Engine.fmt(exit)} pnl ${String.format(Locale.US, "%+.4f", pnl)}")
        ev += Event("botclose", "Short hunter closed ${p.symbol}  -  $reason", "${String.format(Locale.US, "%+.4f", pnl)} USDT", false, p.symbol)
    }

    private fun checkPaper(mode: String, api: Binance, st: ShortState, s: String, p: ShortPos, now: Long, ev: MutableList<Event>) {
        val k = api.klines(s, "15m", start = p.lastCheck, limit = 1500)
        var hit: String? = null; var px = 0.0
        for (i in 0 until k.length()) {
            val b = k.getJSONArray(i)
            if (b.getLong(0) > minOf(now, p.exitAt)) break
            val hi = b.getString(2).toDouble(); val lo = b.getString(3).toDouble()
            if (hi >= p.sl) { hit = "stop-loss"; px = p.sl * (1 + SLIP / 100); break }
            if (lo <= p.tp) { hit = "take-profit"; px = p.tp; break }
        }
        if (k.length() > 0) p.lastCheck = k.getJSONArray(k.length() - 1).getLong(0)
        if (hit == null && now >= p.exitAt) { hit = "time exit"; px = api.markPrice(s) * (1 + SLIP / 100) }
        hit ?: return
        val gross = (p.entry - px) * p.qty
        val fee = px * p.qty * FEE
        val fund = api.fundingSum(s, p.opened, now) * p.entry * p.qty
        st.paperEquity += gross - fee + fund
        record(mode, st, p, px, hit, gross - fee - p.entryFee + fund, now, ev)
    }

    private fun checkLive(mode: String, api: Binance, st: ShortState, s: String, p: ShortPos, now: Long,
                          pos: Map<String, JSONObject>, ev: MutableList<Event>) {
        if (s !in pos) {      // SL or TP order on Binance closed it (or closed by hand)
            api.cancelAll(s)
            val pnl = api.incomeSince(s, p.opened - 1000)
            val px = api.markPrice(s)
            record(mode, st, p, px, if (px > p.entry) "stop-loss" else "take-profit", pnl, now, ev)
        } else if (now >= p.exitAt) {
            api.cancelAll(s)
            val amt = kotlin.math.abs(pos.getValue(s).getString("positionAmt").toDouble())
            api.marketOrder(s, "BUY", api.fmtQty(s, amt).second, reduceOnly = true)
            Thread.sleep(2000)
            record(mode, st, p, api.markPrice(s), "time exit", api.incomeSince(s, p.opened - 1000), now, ev)
        }
    }

    private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
}
