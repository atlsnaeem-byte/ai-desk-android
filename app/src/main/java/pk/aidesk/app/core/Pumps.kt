package pk.aidesk.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs

/**
 * Pump Hunter (LONG side), from the laptop's pump lab (AI Desk/pumplab, 2021-2026 backtest):
 *  - base breakout: 60 quiet days (high/low range <= 60%), then a daily close ABOVE the range, +8..35% on the day with
 *    quote volume >= 3x the base average -> long in the first hours of the next day, SL -20%, TP +60%, 30 days.
 *    Backtest: +12.1%/trade in 2021-25, -0.2% in 2026.
 *  - Upbit KRW listing: Upbit announces a coin for its KRW market and it has a Binance perp -> long at once, SL -15%, TP +30%, 72 h.
 *    Backtest: -1.7% (2021-25), +4.7% (2026). The price is usually already +17-29% one minute after the post.
 *  - Binance spot listing: Binance announces a spot listing of a coin whose perp already trades -> same exits. -2.3% / +1.0%.
 */
data class PumpCfg(
    val enabled: Boolean = false,
    val breakout: Boolean = true,
    val upbit: Boolean = false,
    val binanceSpot: Boolean = false,
    val marginPct: Double = 10.0,
    val leverage: Int = 1,
    val maxPositions: Int = 5,
    val paperStart: Double = 100.0,
    val boBaseDays: Int = 60,
    val boRangePct: Double = 60.0,
    val boMinDayPct: Double = 8.0,
    val boMaxDayPct: Double = 35.0,
    val boVolX: Double = 3.0,
    val boSlPct: Double = 20.0,
    val boTpPct: Double = 60.0,
    val boHoldDays: Int = 30,
    val boEntryWindowH: Int = 6,
    val newsSlPct: Double = 15.0,
    val newsTpPct: Double = 30.0,
    val newsHoldH: Int = 72,
    val newsMaxAgeMin: Int = 10,
) {
    val newsOn: Boolean get() = enabled && (upbit || binanceSpot)

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", VERSION)
        put("enabled", enabled); put("breakout", breakout); put("upbit", upbit); put("binanceSpot", binanceSpot)
        put("marginPct", marginPct); put("leverage", leverage); put("maxPositions", maxPositions); put("paperStart", paperStart)
        put("boBaseDays", boBaseDays); put("boRangePct", boRangePct); put("boMinDayPct", boMinDayPct); put("boMaxDayPct", boMaxDayPct)
        put("boVolX", boVolX); put("boSlPct", boSlPct); put("boTpPct", boTpPct); put("boHoldDays", boHoldDays); put("boEntryWindowH", boEntryWindowH)
        put("newsSlPct", newsSlPct); put("newsTpPct", newsTpPct); put("newsHoldH", newsHoldH); put("newsMaxAgeMin", newsMaxAgeMin)
    }

    companion object {
        const val VERSION = 1
        fun from(o: JSONObject?): PumpCfg {
            val d = PumpCfg(); o ?: return d
            return PumpCfg(o.optBoolean("enabled", d.enabled), o.optBoolean("breakout", d.breakout), o.optBoolean("upbit", d.upbit),
                o.optBoolean("binanceSpot", d.binanceSpot), o.optDouble("marginPct", d.marginPct), o.optInt("leverage", d.leverage),
                o.optInt("maxPositions", d.maxPositions), o.optDouble("paperStart", d.paperStart), o.optInt("boBaseDays", d.boBaseDays),
                o.optDouble("boRangePct", d.boRangePct), o.optDouble("boMinDayPct", d.boMinDayPct), o.optDouble("boMaxDayPct", d.boMaxDayPct),
                o.optDouble("boVolX", d.boVolX), o.optDouble("boSlPct", d.boSlPct), o.optDouble("boTpPct", d.boTpPct),
                o.optInt("boHoldDays", d.boHoldDays), o.optInt("boEntryWindowH", d.boEntryWindowH), o.optDouble("newsSlPct", d.newsSlPct),
                o.optDouble("newsTpPct", d.newsTpPct), o.optInt("newsHoldH", d.newsHoldH), o.optInt("newsMaxAgeMin", d.newsMaxAgeMin))
        }
    }
}

class PumpPos(
    val symbol: String, val entry: Double, val qty: Double, val opened: Long, val exitAt: Long, val margin: Double,
    var sl: Double, var tp: Double, val entryFee: Double, var lastCheck: Long, val rule: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("symbol", symbol); put("entry", entry); put("qty", qty); put("opened", opened); put("exitAt", exitAt); put("margin", margin)
        put("sl", sl); put("tp", tp); put("entryFee", entryFee); put("lastCheck", lastCheck); put("rule", rule)
    }

    companion object {
        fun from(o: JSONObject) = PumpPos(o.getString("symbol"), o.getDouble("entry"), o.getDouble("qty"), o.getLong("opened"),
            o.getLong("exitAt"), o.getDouble("margin"), o.getDouble("sl"), o.getDouble("tp"), o.optDouble("entryFee", 0.0),
            o.optLong("lastCheck", o.getLong("opened")), o.optString("rule", "breakout"))
    }
}

class PumpState(var paperEquity: Double, val open: MutableMap<String, PumpPos> = linkedMapOf(), val done: MutableList<String> = mutableListOf()) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("paperEquity", paperEquity)
        put("open", JSONObject().also { o -> open.forEach { (k, v) -> o.put(k, v.toJson()) } })
        put("done", JSONArray(done.takeLast(3000)))
    }

    companion object {
        fun from(o: JSONObject?, start: Double): PumpState {
            o ?: return PumpState(start)
            val op = o.optJSONObject("open")
            return PumpState(o.optDouble("paperEquity", start),
                op?.keys()?.asSequence()?.associateWith { PumpPos.from(op.getJSONObject(it)) }?.toMutableMap() ?: linkedMapOf(),
                o.optJSONArray("done")?.let { a -> (0 until a.length()).map { a.getString(it) }.toMutableList() } ?: mutableListOf())
        }
    }
}

class PumpDone(val symbol: String, val entry: Double, val exit: Double, val reason: String, val opened: Long, val closed: Long,
               val pnl: Double, val retPct: Double?, val rule: String) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("symbol", symbol); put("entry", entry); put("exit", exit); put("reason", reason); put("opened", opened); put("closed", closed)
        put("pnl", pnl); retPct?.let { put("retPct", it) }; put("rule", rule)
    }

    companion object {
        fun from(o: JSONObject) = PumpDone(o.getString("symbol"), o.getDouble("entry"), o.getDouble("exit"), o.getString("reason"),
            o.getLong("opened"), o.getLong("closed"), o.getDouble("pnl"), if (o.has("retPct")) o.getDouble("retPct") else null, o.optString("rule"))
    }
}

/** A base breakout found on the last closed daily candle. day = open time (UTC) of the breakout candle. */
class Breakout(val symbol: String, val day: Long, val close: Double, val dayPct: Double, val baseHigh: Double, val baseLow: Double,
               val rangePct: Double, val volX: Double, val slPct: Double, val tpPct: Double) {
    val entryFrom: Long get() = day + PumpHunter.DAY
    fun toJson(): JSONObject = JSONObject().apply {
        put("symbol", symbol); put("day", day); put("close", close); put("dayPct", dayPct); put("baseHigh", baseHigh); put("baseLow", baseLow)
        put("rangePct", rangePct); put("volX", volX); put("slPct", slPct); put("tpPct", tpPct)
    }

    companion object {
        fun from(o: JSONObject) = Breakout(o.getString("symbol"), o.getLong("day"), o.getDouble("close"), o.getDouble("dayPct"),
            o.getDouble("baseHigh"), o.getDouble("baseLow"), o.getDouble("rangePct"), o.getDouble("volX"), o.optDouble("slPct", 20.0), o.optDouble("tpPct", 60.0))
    }
}

class BreakoutScan(val day: Long, val at: Long, val checked: Int, val list: List<Breakout>)

/** A listing announcement (Upbit KRW market or Binance spot) naming a coin that has a Binance USDT perp. */
class News(val source: String, val symbol: String, val time: Long, val title: String) {
    val key: String get() = "news:$source:$symbol:$time"
}

class PumpHunter(private val store: Store) {
    companion object {
        const val DAY = 86_400_000L
        private const val FEE = 0.0005
        private const val SLIP = 0.05
        private val SCAN_LOCK = Any()
        val STEP_LOCK = Any()
        private val SKIP = setOf("BTCUSDT", "ETHUSDT", "USDCUSDT", "BTCDOMUSDT")
        private val STOP_T = setOf("KRW", "BTC", "USDT", "USD", "ETH", "BNB", "FDUSD", "TRY", "EUR", "NFT", "AI", "DEX")
    }

    fun cfg(): PumpCfg = PumpCfg.from(store.readObj("pumps_cfg.json"))
    fun saveCfg(c: PumpCfg) = store.writeObj("pumps_cfg.json", c.toJson())
    fun state(mode: String): PumpState = PumpState.from(store.readObj("pumps_state_$mode.json"), cfg().paperStart)
    private fun saveState(mode: String, s: PumpState) = store.writeObj("pumps_state_$mode.json", s.toJson())
    fun journal(mode: String): List<PumpDone> = store.readArr("pumps_journal_$mode.json")?.let { a ->
        (0 until a.length()).mapNotNull { runCatching { PumpDone.from(a.getJSONObject(it)) }.getOrNull() }
    } ?: emptyList()
    private fun addJournal(mode: String, d: PumpDone) =
        store.writeArr("pumps_journal_$mode.json", JSONArray((journal(mode) + d).takeLast(2000).map { it.toJson() }))

    private fun coins(api: Binance): Map<String, JSONObject> = api.exchangeInfo(fresh = false).filterValues {
        it.optString("quoteAsset") == "USDT" && it.optString("contractType") == "PERPETUAL" &&
            it.optString("status") == "TRADING" && it.optString("underlyingType", "COIN") == "COIN"
    }

    // ------------------------------------------------------------------ base breakouts (daily scan, cached per UTC day)
    fun lastScan(): BreakoutScan? = store.readObj("pump_breakouts.json")?.let { o ->
        runCatching {
            val a = o.getJSONArray("list")
            BreakoutScan(o.getLong("day"), o.getLong("at"), o.optInt("checked"), (0 until a.length()).map { Breakout.from(a.getJSONObject(it)) })
        }.getOrNull()
    }

    /** Scan every USDT perp's daily candles once per UTC day (after 00:00 UTC = 05:00 PKT). force = scan again now. */
    fun breakouts(api: Binance, now: Long = api.nowMs(), force: Boolean = false): BreakoutScan = synchronized(SCAN_LOCK) {
        val today = now / DAY * DAY
        lastScan()?.let { if (!force && it.day == today) return it }
        val c = cfg()
        val W = c.boBaseDays
        val out = ArrayList<Breakout>()
        var checked = 0
        for (s in coins(api).keys) {
            if (s in SKIP) continue
            val k = runCatching { api.klines(s, "1d", limit = W + 14) }.getOrNull() ?: continue
            val rows = (0 until k.length()).map { k.getJSONArray(it) }.filter { it.getLong(6) < now }
            if (rows.size < W + 11) continue                 // coin must be old enough (same as the backtest)
            checked++
            val i = rows.size - 1
            val h = rows.map { it.getString(2).toDouble() }; val l = rows.map { it.getString(3).toDouble() }
            val cl = rows.map { it.getString(4).toDouble() }; val qv = rows.map { it.getString(7).toDouble() }
            val r1 = (cl[i] / cl[i - 1] - 1) * 100
            if (r1 < c.boMinDayPct || r1 > c.boMaxDayPct) continue
            val hi = h.subList(i - W, i).max(); val lo = l.subList(i - W, i).min()
            val range = (hi / lo - 1) * 100
            if (range > c.boRangePct || cl[i] <= hi) continue
            val avg = qv.subList(i - W, i).average()
            if (avg <= 0 || qv[i] < c.boVolX * avg) continue
            out += Breakout(s, rows[i].getLong(0), cl[i], r1, hi, lo, range, qv[i] / avg, c.boSlPct, c.boTpPct)
        }
        val sc = BreakoutScan(today, now, checked, out.sortedByDescending { it.volX })
        store.writeObj("pump_breakouts.json", JSONObject().apply {
            put("day", sc.day); put("at", sc.at); put("checked", sc.checked); put("list", JSONArray(sc.list.map { it.toJson() }))
        })
        store.log("INFO", "Pump hunter: daily breakout scan checked $checked coins, ${out.size} breakout(s)")
        sc
    }

    // ------------------------------------------------------------------ listing news (Upbit KRW / Binance spot)
    private fun http(url: String): JSONObject? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 8_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        c.setRequestProperty("Accept", "application/json")
        try { if (c.responseCode != 200) null else JSONObject(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
    } catch (e: Exception) { null }

    private fun perpFor(tick: String, cs: Set<String>) = listOf("${tick}USDT", "1000${tick}USDT", "1000000${tick}USDT").firstOrNull { it in cs }

    /** Latest listing announcements that map to a tradable Binance perp (newest first). */
    fun news(api: Binance, upbit: Boolean = true, binance: Boolean = true): List<News> {
        val cs = coins(api).keys
        val out = ArrayList<News>()
        if (upbit) http("https://api-manager.upbit.com/api/v1/announcements?os=web&page=1&per_page=20&category=trade")
            ?.optJSONObject("data")?.optJSONArray("notices")?.let { a ->
                for (i in 0 until a.length()) {
                    val n = a.getJSONObject(i)
                    val title = n.optString("title")
                    if (!title.contains("KRW") || listOf("종료", "유의", "연기", "변경", "재개", "중단", "입출금").any { title.contains(it) }) continue
                    if (listOf("신규 거래지원", "디지털 자산 추가", "상장", "마켓 추가").none { title.contains(it) }) continue
                    val t = runCatching { java.time.OffsetDateTime.parse(n.optString("listed_at").ifEmpty { n.optString("first_listed_at") }).toInstant().toEpochMilli() }
                        .getOrNull() ?: continue
                    val ticks = Regex("\\(([A-Z0-9]{2,12})\\)").findAll(title).map { it.groupValues[1] } +
                        Regex("\\b([A-Z0-9]{2,12})/KRW").findAll(title).map { it.groupValues[1] }
                    for (tk in ticks.distinct()) {
                        if (tk in STOP_T) continue
                        perpFor(tk, cs)?.let { out += News("Upbit KRW", it, t, title) }
                    }
                }
            }
        if (binance) http("https://www.binance.com/bapi/composite/v1/public/cms/article/list/query?type=1&catalogId=48&pageNo=1&pageSize=20")
            ?.optJSONObject("data")?.optJSONArray("catalogs")?.optJSONObject(0)?.optJSONArray("articles")?.let { a ->
                for (i in 0 until a.length()) {
                    val x = a.getJSONObject(i)
                    val title = x.optString("title")
                    if (Regex("Futures|Alpha|Margin|Leveraged|Convert|Earn|Pre-Market|Delist|Suspend|bStocks|TradFi").containsMatchIn(title)) continue
                    if (!Regex("^Binance (Will List|Lists|Will Add)|^Introducing .* on Binance (HODLer Airdrops|Launchpool|Megadrop|Launchpad)").containsMatchIn(title)) continue
                    for (tk in Regex("\\(([A-Z0-9]{2,12})\\)").findAll(title).map { it.groupValues[1] }.distinct()) {
                        if (tk in STOP_T) continue
                        perpFor(tk, cs)?.let { out += News("Binance spot", it, x.optLong("releaseDate"), title) }
                    }
                }
            }
        return out.sortedByDescending { it.time }
    }

    // ------------------------------------------------------------------ bot
    /** Full pass (every 15-minute cycle): exits, base-breakout entries, listing news. */
    fun step(mode: String, api: Binance, equityOf: () -> Pair<Double, Double>): List<Event> = synchronized(STEP_LOCK) {
        val c = cfg()
        val st = state(mode)
        val ev = ArrayList<Event>()
        val now = api.nowMs()
        manageExits(mode, api, st, now, ev)
        if (c.enabled && c.breakout) {
            try {
                val sc = breakouts(api, now)
                for (b in sc.list) {
                    if (now < b.entryFrom || now >= b.entryFrom + c.boEntryWindowH * 3_600_000L) continue
                    val key = "bo:${b.symbol}:${b.day}"
                    if (key in st.done) continue
                    st.done += key
                    tryOpen(mode, api, st, b.symbol, now, now + c.boHoldDays * DAY, c.boSlPct, c.boTpPct,
                        String.format(Locale.US, "breakout %+.0f%% day, %.1fx vol", b.dayPct, b.volX), c, equityOf, ev)
                }
            } catch (e: Exception) {
                store.log("WARN", "Pump hunter breakout scan: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        if (c.newsOn) newsPass(mode, api, st, now, c, equityOf, ev)
        saveState(mode, st)
        ev
    }

    /** Fast pass for listing news only (TraderService calls it every ~15 s while a news rule is on). */
    fun newsStep(mode: String, api: Binance, equityOf: () -> Pair<Double, Double>): List<Event> = synchronized(STEP_LOCK) {
        val c = cfg()
        if (!c.newsOn) return@synchronized emptyList()
        val st = state(mode)
        val ev = ArrayList<Event>()
        newsPass(mode, api, st, api.nowMs(), c, equityOf, ev)
        saveState(mode, st)
        ev
    }

    private fun newsPass(mode: String, api: Binance, st: PumpState, now: Long, c: PumpCfg, equityOf: () -> Pair<Double, Double>, ev: MutableList<Event>) {
        try {
            for (n in news(api, c.upbit, c.binanceSpot)) {
                if (n.key in st.done) continue
                st.done += n.key
                val age = now - n.time
                if (age < -60_000 || age > c.newsMaxAgeMin * 60_000L) continue        // only act on fresh posts
                tryOpen(mode, api, st, n.symbol, now, now + c.newsHoldH * 3_600_000L, c.newsSlPct, c.newsTpPct, n.source + " listing", c, equityOf, ev)
            }
        } catch (e: Exception) {
            store.log("WARN", "Pump hunter news: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun tryOpen(mode: String, api: Binance, st: PumpState, s: String, now: Long, exitAt: Long, slPct: Double, tpPct: Double, rule: String,
                        c: PumpCfg, equityOf: () -> Pair<Double, Double>, ev: MutableList<Event>) {
        try {
            if (s in st.open) return
            if (s in store.botSymbols(mode)) { store.log("INFO", "Pump hunter $s: skip, another bot holds it"); return }
            if (mode != "paper" && s in api.positions()) { store.log("INFO", "Pump hunter $s: skip, the account already holds $s"); return }
            openLong(mode, api, st, s, now, exitAt, c, equityOf, slPct, tpPct, rule)?.let { ev += it }
        } catch (e: Exception) {
            val m = (e as? BinanceException)?.msg ?: e.message ?: e.javaClass.simpleName
            store.log("ERROR", "Pump hunter $s entry: $m")
            ev += Event("error", "Pump hunter: $s entry failed", m, false, s)
        }
    }

    private fun openLong(mode: String, api: Binance, st: PumpState, s: String, now: Long, exitAt: Long, c: PumpCfg,
                         equityOf: () -> Pair<Double, Double>, slPct: Double, tpPct: Double, rule: String): Event? {
        if (st.open.size >= c.maxPositions) { store.log("INFO", "Pump hunter $s: skip, ${c.maxPositions} longs already open"); return null }
        val live = mode != "paper"
        val used = st.open.values.sumOf { it.margin }
        val (eq, avail) = if (live) equityOf() else st.paperEquity to st.paperEquity - used
        val margin = eq * c.marginPct / 100
        if (margin > avail) { store.log("INFO", "Pump hunter $s: skip, free margin ${f2(avail)} < ${f2(margin)}"); return null }
        val price = api.markPrice(s)
        val f = api.filters(s)
        val (qty, q) = api.fmtQty(s, margin * c.leverage / price)
        if (qty < f.minQty || qty * price < f.minNotional) {
            val msg = "position ${f2(qty * price)} USDT is below Binance's minimum ${f2(f.minNotional)} USDT (equity ${f2(eq)} x ${c.marginPct}% x ${c.leverage}x)"
            store.log("INFO", "Pump hunter $s: skip, $msg")
            return Event("skip", "Pump hunter skipped $s", msg, false, s)
        }
        val entry: Double; val fee: Double
        if (!live) {
            entry = price * (1 + SLIP / 100); fee = qty * entry * FEE
            st.paperEquity -= fee
        } else {
            api.setMarginType(s, "ISOLATED"); api.setLeverage(s, c.leverage)
            val r = api.marketOrder(s, "BUY", q)
            entry = r.optString("avgPrice").toDoubleOrNull()?.takeIf { it > 0 } ?: price
            fee = qty * entry * FEE
            if (!placeExits(api, s, entry * (1 - slPct / 100), entry * (1 + tpPct / 100))) {
                api.marketOrder(s, "SELL", q, reduceOnly = true)
                return Event("error", "Pump hunter: $s closed", "Binance rejected the stop order, so the long was closed.", true, s)
            }
        }
        st.open[s] = PumpPos(s, entry, qty, now, exitAt, margin, entry * (1 - slPct / 100), entry * (1 + tpPct / 100), fee, now, rule)
        store.log("INFO", "Pump hunter OPEN LONG [$rule] $s qty $q @ ${Engine.fmt(entry)} (${mode.uppercase()})")
        val hours = (exitAt - now) / 3_600_000L
        return Event("botopen", "Pump hunter: LONG $s ($rule)", "${Engine.fmt(qty)} @ ${Engine.fmt(entry)}  -  SL −${slPct.toInt()}%  TP +${tpPct.toInt()}%  -  up to " +
            (if (hours >= 48) "${hours / 24} days" else "$hours h"), true, s)
    }

    /** SL + TP trigger orders closing the whole long; false if the stop was rejected (caller must close). */
    private fun placeExits(api: Binance, s: String, sl: Double, tp: Double): Boolean {
        try {
            api.closeTrigger(s, "SELL", "STOP_MARKET", api.fmtPrice(s, sl).second)
        } catch (e: BinanceException) {
            store.log("ERROR", "Pump hunter $s: stop rejected (${e.msg}), closing for safety"); api.cancelAll(s); return false
        }
        try {
            api.closeTrigger(s, "SELL", "TAKE_PROFIT_MARKET", api.fmtPrice(s, tp).second)
        } catch (e: BinanceException) {
            store.log("WARN", "Pump hunter $s: take-profit rejected (${e.msg}); the bot will watch the target itself")
        }
        return true
    }

    private fun manageExits(mode: String, api: Binance, st: PumpState, now: Long, ev: MutableList<Event>) {
        if (st.open.isEmpty()) return
        val live = mode != "paper"
        val pos = if (live) api.positions() else emptyMap()
        for ((s, p) in st.open.toMap()) {
            try {
                if (live) checkLive(mode, api, st, s, p, now, pos, ev) else checkPaper(mode, api, st, s, p, now, ev)
            } catch (e: Exception) {
                store.log("WARN", "Pump hunter $s exit check: ${(e as? BinanceException)?.msg ?: e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun record(mode: String, st: PumpState, p: PumpPos, exit: Double, reason: String, pnl: Double, now: Long, ev: MutableList<Event>) {
        addJournal(mode, PumpDone(p.symbol, p.entry, exit, reason, p.opened, now, pnl, if (p.margin > 0) pnl / p.margin * 100 else null, p.rule))
        st.open.remove(p.symbol)
        store.log("INFO", "Pump hunter CLOSE ${p.symbol} $reason @ ${Engine.fmt(exit)} pnl ${String.format(Locale.US, "%+.4f", pnl)}")
        ev += Event("botclose", "Pump hunter closed ${p.symbol}  -  $reason", "${String.format(Locale.US, "%+.4f", pnl)} USDT", false, p.symbol)
    }

    private fun paperClose(mode: String, api: Binance, st: PumpState, p: PumpPos, px: Double, why: String, now: Long, ev: MutableList<Event>) {
        val gross = (px - p.entry) * p.qty
        val fee = px * p.qty * FEE
        val fund = api.fundingSum(p.symbol, p.opened, now) * p.entry * p.qty        // a long pays positive funding
        st.paperEquity += gross - fee - fund
        record(mode, st, p, px, why, gross - fee - p.entryFee - fund, now, ev)
    }

    private fun checkPaper(mode: String, api: Binance, st: PumpState, s: String, p: PumpPos, now: Long, ev: MutableList<Event>) {
        val k = api.klines(s, "15m", start = p.lastCheck, limit = 1500)
        var hit: String? = null; var px = 0.0
        for (i in 0 until k.length()) {
            val b = k.getJSONArray(i)
            if (b.getLong(0) > minOf(now, p.exitAt)) break
            val hi = b.getString(2).toDouble(); val lo = b.getString(3).toDouble()
            if (lo <= p.sl) { hit = "stop-loss"; px = p.sl * (1 - SLIP / 100); break }
            if (hi >= p.tp) { hit = "take-profit"; px = p.tp; break }
        }
        if (k.length() > 0) p.lastCheck = k.getJSONArray(k.length() - 1).getLong(0)
        if (hit == null && now >= p.exitAt) { hit = "time exit"; px = api.markPrice(s) * (1 - SLIP / 100) }
        hit ?: return
        paperClose(mode, api, st, p, px, hit, now, ev)
    }

    private fun checkLive(mode: String, api: Binance, st: PumpState, s: String, p: PumpPos, now: Long, pos: Map<String, JSONObject>, ev: MutableList<Event>) {
        if (s !in pos) {      // SL or TP order on Binance closed it (or closed by hand)
            api.cancelAll(s)
            val px = api.markPrice(s)
            record(mode, st, p, px, if (px < p.entry) "stop-loss" else "take-profit", api.incomeSince(s, p.opened - 1000), now, ev)
            return
        }
        val mark = api.markPrice(s)
        val tpHit = mark >= p.tp                    // backup when the exchange take-profit was rejected
        if (now >= p.exitAt || tpHit) {
            api.cancelAll(s)
            val amt = abs(pos.getValue(s).getString("positionAmt").toDouble())
            api.marketOrder(s, "SELL", api.fmtQty(s, amt).second, reduceOnly = true)
            Thread.sleep(2000)
            record(mode, st, p, api.markPrice(s), if (tpHit) "take-profit" else "time exit", api.incomeSince(s, p.opened - 1000), now, ev)
        }
    }

    // ------------------------------------------------------------------ manual (UI buttons)
    /** Long a breakout coin now with the breakout SL/TP. */
    fun openManual(mode: String, api: Binance, s: String, equityOf: () -> Pair<Double, Double>): String = synchronized(STEP_LOCK) {
        val c = cfg()
        val st = state(mode)
        if (s in st.open) throw UserError("$s is already held by the Pump hunter.")
        if (s in store.botSymbols(mode)) throw UserError("Another bot already holds $s.")
        if (mode != "paper" && s in api.positions()) throw UserError("The account already holds a $s position.")
        val now = api.nowMs()
        val ev = openLong(mode, api, st, s, now, now + c.boHoldDays * DAY, c, equityOf, c.boSlPct, c.boTpPct, "manual breakout")
        saveState(mode, st)
        when (ev?.kind) {
            "botopen" -> ev.title + "\n" + ev.text
            null -> throw UserError("Not opened: max ${c.maxPositions} longs or not enough free margin (see Logs).")
            else -> throw UserError(ev.text)
        }
    }

    fun closeManual(mode: String, api: Binance, s: String): String = synchronized(STEP_LOCK) {
        val st = state(mode)
        val p = st.open[s] ?: throw UserError("No open Pump hunter long on $s.")
        val ev = ArrayList<Event>()
        val now = api.nowMs()
        if (mode == "paper") paperClose(mode, api, st, p, api.markPrice(s) * (1 - SLIP / 100), "closed by you", now, ev)
        else {
            api.cancelAll(s)
            api.positions()[s]?.let { pos ->
                api.marketOrder(s, "SELL", api.fmtQty(s, abs(pos.getString("positionAmt").toDouble())).second, reduceOnly = true)
                Thread.sleep(1500)
            }
            record(mode, st, p, api.markPrice(s), "closed by you", api.incomeSince(s, p.opened - 1000), now, ev)
        }
        saveState(mode, st)
        ev.joinToString("\n") { it.title + "  " + it.text }.ifEmpty { "Closed $s" }
    }

    /** New SL / TP for an open Pump hunter long (the Binance orders are replaced by the caller). */
    fun setExits(mode: String, s: String, sl: Double, tp: Double): Boolean = synchronized(STEP_LOCK) {
        val st = state(mode)
        val p = st.open[s] ?: return@synchronized false
        p.sl = sl; p.tp = tp
        saveState(mode, st)
        true
    }

    private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
}
