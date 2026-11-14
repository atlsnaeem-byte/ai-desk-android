package pk.aidesk.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A manual trade the user placed from the Trade tab (entry order + SL/TP), tracked until it closes. */
class ManualPlan(
    val id: String, val mode: String, val symbol: String, val side: String, val type: String,
    val entry: Double, var sl: Double, var tp: Double, val qty: Double, val lev: Int,
    val created: Long, val expires: Long, val setup: String, val equityBefore: Double,
    var status: String = "pending",              // pending | open | closed | cancelled | failed
    var fill: Double? = null, var filledMs: Long? = null, var exit: Double? = null, var exitMs: Long? = null,
    var reason: String? = null, var pnl: Double? = null, var lastCheck: Long = created, var note: String = "",
) {
    val dir: Int get() = if (side == "LONG") 1 else -1
    val active: Boolean get() = status == "pending" || status == "open"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("mode", mode); put("symbol", symbol); put("side", side); put("type", type); put("entry", entry)
        put("sl", sl); put("tp", tp); put("qty", qty); put("lev", lev); put("created", created); put("expires", expires)
        put("setup", setup); put("equityBefore", equityBefore); put("status", status); put("lastCheck", lastCheck); put("note", note)
        fill?.let { put("fill", it) }; filledMs?.let { put("filledMs", it) }; exit?.let { put("exit", it) }
        exitMs?.let { put("exitMs", it) }; reason?.let { put("reason", it) }; pnl?.let { put("pnl", it) }
    }

    companion object {
        private fun JSONObject.d(k: String) = if (has(k) && !isNull(k)) getDouble(k) else null
        private fun JSONObject.l(k: String) = if (has(k) && !isNull(k)) getLong(k) else null
        fun from(o: JSONObject) = ManualPlan(o.getString("id"), o.getString("mode"), o.getString("symbol"), o.getString("side"),
            o.getString("type"), o.getDouble("entry"), o.getDouble("sl"), o.getDouble("tp"), o.getDouble("qty"), o.optInt("lev", 1),
            o.getLong("created"), o.getLong("expires"), o.optString("setup"), o.optDouble("equityBefore", 0.0),
            o.optString("status", "pending"), o.d("fill"), o.l("filledMs"), o.d("exit"), o.l("exitMs"),
            if (o.has("reason")) o.getString("reason") else null, o.d("pnl"), o.optLong("lastCheck", o.getLong("created")), o.optString("note"))
    }
}

/** What the user filled in on the order ticket. */
data class Ticket(
    val symbol: String, val side: String, val type: String, val entry: Double, val sl: Double, val tp: Double,
    val riskPct: Double, val lev: Int, val validHours: Int, val setup: String,
)

class Quote(
    val qty: Double, val notional: Double, val margin: Double, val riskUsdt: Double, val rewardUsdt: Double, val rr: Double,
    val equity: Double, val price: Double, val block: String?, val warnings: List<String>,
)

/**
 * Manual order desk. Live/testnet: real Binance orders (LIMIT, STOP_MARKET entry or MARKET) + closePosition SL/TP.
 * A watcher (TraderService, every ~10 s while orders are active) adds missing SL/TP after a fill, cancels expired
 * entries and records the result when the position closes. Paper: fills and exits replayed on 1-minute candles.
 */
class ManualDesk(private val store: Store) {
    companion object {
        private const val FEE = 0.0005
        private const val SLIP = 0.02          // % per side for paper market / stop fills
        val LOCK = Any()
    }

    fun plans(mode: String): List<ManualPlan> = store.readArr("manual_$mode.json")?.let { a ->
        (0 until a.length()).mapNotNull { runCatching { ManualPlan.from(a.getJSONObject(it)) }.getOrNull() }
    } ?: emptyList()

    private fun save(mode: String, ps: List<ManualPlan>) {
        val keep = ps.filter { it.active } + ps.filter { !it.active }.takeLast(300)
        store.writeArr("manual_$mode.json", JSONArray(keep.sortedBy { it.created }.map { it.toJson() }))
    }

    fun active(mode: String) = plans(mode).filter { it.active }

    private fun live(c: Config) = c.mode == "live" || c.mode == "testnet"
    private fun f(v: Double) = Engine.fmt(v)
    private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)

    /** (equity, available) for sizing. */
    private fun equity(c: Config, api: Binance): Pair<Double, Double> = if (live(c)) api.equity() else {
        val st = store.state(c.mode, c.paperStartEquity)
        val used = active(c.mode).filter { it.status == "open" }.sumOf { (it.fill ?: it.entry) * it.qty / it.lev }
        st.equity to st.equity - used
    }

    // ------------------------------------------------------------------ quote
    fun quote(c: Config, api: Binance, t: Ticket): Quote {
        val s = if (t.side == "LONG") 1 else -1
        val price = api.markPrice(t.symbol)
        val entry = if (t.type == "MARKET") price else t.entry
        val warn = ArrayList<String>()
        var block: String? = null
        val (eq, avail) = equity(c, api)
        val riskPer = (entry - t.sl) * s
        when {
            t.entry <= 0 && t.type != "MARKET" -> block = "Enter an entry price."
            riskPer <= 0 -> block = "Stop-loss must be ${if (s == 1) "below" else "above"} the entry for a ${t.side}."
            (t.tp - entry) * s <= 0 -> block = "Take-profit must be ${if (s == 1) "above" else "below"} the entry for a ${t.side}."
            t.type == "STOP" && (entry - price) * s <= 0 ->
                block = "Price ${f(price)} is already past the stop trigger ${f(entry)}. Use Market or a Limit instead."
            t.type == "LIMIT" && (entry - price) * s > 0 ->
                warn += "A ${t.side} limit ${if (s == 1) "above" else "below"} the price (${f(price)}) fills immediately, like a market order."
        }
        var qty = if (riskPer > 0) eq * t.riskPct / 100 / riskPer else 0.0
        val cap = max(0.0, avail) * t.lev * 0.95
        if (qty * entry > cap) { qty = cap / entry; warn += "Size capped by available margin × ${t.lev}x leverage." }
        if (live(c)) {
            val fl = api.filters(t.symbol)
            qty = api.fmtQty(t.symbol, qty).first
            if (block == null && (qty < fl.minQty || qty * entry < fl.minNotional))
                block = "Order size ${f2(qty * entry)} USDT is below Binance's minimum ${f2(fl.minNotional)} USDT. Raise risk % or leverage, or add funds."
        }
        if (riskPer > 0 && riskPer / entry < 0.002) warn += "Stop is only ${f2(riskPer / entry * 100)}% away: fees and noise can hit it."
        if (block == null && active(c.mode).any { it.symbol == t.symbol }) block = "You already have an active manual order on ${t.symbol}. Cancel it first."
        val rr = if (riskPer > 0) abs(t.tp - entry) / riskPer else 0.0
        return Quote(qty, qty * entry, qty * entry / t.lev, qty * max(riskPer, 0.0), qty * abs(t.tp - entry), rr, eq, price, block, warn)
    }

    // ------------------------------------------------------------------ place
    fun place(c: Config, api: Binance, t: Ticket): String = synchronized(LOCK) {
        val q = quote(c, api, t)
        q.block?.let { throw UserError(it) }
        val s = if (t.side == "LONG") 1 else -1
        val now = api.nowMs()
        val id = "${c.mode}-${t.symbol}-man${now}"
        val exp = now + t.validHours * 3_600_000L
        if (!live(c)) {
            val p = ManualPlan(id, c.mode, t.symbol, t.side, t.type, if (t.type == "MARKET") q.price else t.entry, t.sl, t.tp, q.qty, t.lev,
                now, exp, t.setup, q.equity)
            if (t.type == "MARKET") {
                p.status = "open"; p.fill = q.price * (1 + s * SLIP / 100); p.filledMs = now
                store.state(c.mode, c.paperStartEquity).let { st -> st.equity -= p.qty * p.fill!! * FEE; store.saveState(c.mode, st) }
                journal(p)
            }
            save(c.mode, plans(c.mode) + p)
            store.log("INFO", "PAPER manual ${t.type} ${t.side} ${t.symbol} qty ${f(q.qty)} @ ${f(p.entry)} SL ${f(t.sl)} TP ${f(t.tp)}")
            return@synchronized "Paper ${t.type.lowercase()} ${t.side} ${t.symbol} placed: ${f(q.qty)} @ ${f(p.entry)}"
        }
        if (api.isHedgeMode()) throw UserError("Your account is in Hedge mode. Switch to One-way (Settings > Connection).")
        if (t.symbol in api.positions()) throw UserError("There is already an open ${t.symbol} position.")
        api.setMarginType(t.symbol, "ISOLATED")
        runCatching { api.setLeverage(t.symbol, t.lev) }.onFailure { throw UserError("Leverage ${t.lev}x rejected for ${t.symbol}: ${(it as? BinanceException)?.msg ?: it.message}") }
        val qs = api.fmtQty(t.symbol, q.qty).second
        val buy = if (s == 1) "BUY" else "SELL"
        val p: ManualPlan
        try {
            when (t.type) {
                "MARKET" -> {
                    val r = api.marketOrder(t.symbol, buy, qs)
                    val fill = r.optString("avgPrice").toDoubleOrNull()?.takeIf { it > 0 } ?: q.price
                    p = ManualPlan(id, c.mode, t.symbol, t.side, t.type, fill, t.sl, t.tp, qs.toDouble(), t.lev, now, exp, t.setup, q.equity,
                        status = "open", fill = fill, filledMs = now)
                }
                "LIMIT" -> {
                    api.limitOrder(t.symbol, buy, qs, api.fmtPrice(t.symbol, t.entry).second)
                    p = ManualPlan(id, c.mode, t.symbol, t.side, t.type, t.entry, t.sl, t.tp, qs.toDouble(), t.lev, now, exp, t.setup, q.equity)
                }
                else -> {
                    api.stopEntry(t.symbol, buy, qs, api.fmtPrice(t.symbol, t.entry).second)
                    p = ManualPlan(id, c.mode, t.symbol, t.side, t.type, t.entry, t.sl, t.tp, qs.toDouble(), t.lev, now, exp, t.setup, q.equity)
                }
            }
        } catch (e: BinanceException) {
            store.log("ERROR", "Manual ${t.type} ${t.symbol} rejected: ${e.msg}")
            throw UserError("Binance rejected the order: ${e.msg}")
        }
        save(c.mode, plans(c.mode) + p)
        val prot = protect(api, p)                        // SL/TP now where Binance accepts them; the watcher adds the rest
        if (p.status == "open") {
            journal(p)
            if ("SL" !in prot) {
                closeNow(c, api, p, "NO_STOP")
                throw UserError("Binance rejected the stop-loss, so the position was closed immediately.")
            }
        }
        store.log("INFO", "Manual ${t.type} ${t.side} ${t.symbol} qty $qs @ ${f(p.entry)} SL ${f(t.sl)} TP ${f(t.tp)} (${c.mode})")
        "${t.type.lowercase().replaceFirstChar { it.uppercase() }} ${t.side} ${t.symbol} placed: $qs @ ${f(p.entry)}, SL ${f(t.sl)}, TP ${f(t.tp)}" +
            if (p.status == "pending" && prot.size < 2) " (missing SL/TP is added when the entry fills)" else ""
    }

    /** Place whichever of SL / TP is missing (closePosition triggers). Returns what exists afterwards. */
    private fun protect(api: Binance, p: ManualPlan): Set<String> {
        val have = runCatching { api.protections(p.symbol) }.getOrDefault(emptySet()).toMutableSet()
        val exitSide = if (p.dir == 1) "SELL" else "BUY"
        for ((tag, kind, px) in listOf(Triple("SL", "STOP_MARKET", p.sl), Triple("TP", "TAKE_PROFIT_MARKET", p.tp))) {
            if (tag in have) continue
            try {
                api.closeTrigger(p.symbol, exitSide, kind, api.fmtPrice(p.symbol, px).second)
                have += tag
            } catch (e: BinanceException) {
                if (p.status == "open") store.log("WARN", "Manual ${p.symbol} $tag not placed yet: ${e.msg}")
            }
        }
        return have
    }

    private fun journal(p: ManualPlan) {
        store.journalUpsert(Trade(p.id, p.mode, p.symbol, p.side, p.fill ?: p.entry, p.qty, p.sl, p.tp, p.filledMs ?: p.created, 0.0, 0.0,
            "manual", p.equityBefore, listOf(p.setup).filter { it.isNotEmpty() }, emptyList(),
            status = if (p.status == "closed") "closed" else "open", exit = p.exit, exitMs = p.exitMs, reason = p.reason, pnl = p.pnl,
            r = p.pnl?.let { pn -> val rk = abs((p.fill ?: p.entry) - p.sl) * p.qty; if (rk > 0) pn / rk else null }))
    }

    private fun closeNow(c: Config, api: Binance, p: ManualPlan, reason: String) {
        val pos = api.positions()[p.symbol]
        api.cancelAll(p.symbol)
        var px = api.markPrice(p.symbol)
        if (pos != null) {
            val amt = abs(pos.getString("positionAmt").toDouble())
            val r = api.marketOrder(p.symbol, if (p.dir == 1) "SELL" else "BUY", api.fmtQty(p.symbol, amt).second, reduceOnly = true)
            px = r.optString("avgPrice").toDoubleOrNull()?.takeIf { it > 0 } ?: px
            Thread.sleep(1500)
        }
        finishLive(api, p, px, reason)
    }

    private fun finishLive(api: Binance, p: ManualPlan, px: Double, reason: String) {
        val pnl = runCatching { api.incomeSince(p.symbol, (p.filledMs ?: p.created) - 1000) }.getOrNull()
        p.status = "closed"; p.exit = px; p.exitMs = api.nowMs(); p.reason = reason; p.pnl = pnl
        journal(p)
        store.log("INFO", "Manual CLOSE ${p.symbol} ${p.side} $reason pnl ${pnl?.let { String.format(Locale.US, "%+.4f", it) } ?: "?"}")
    }

    // ------------------------------------------------------------------ cancel / close (UI)
    fun cancel(c: Config, api: Binance, id: String): String = synchronized(LOCK) {
        val ps = plans(c.mode)
        val p = ps.find { it.id == id && it.active } ?: throw UserError("That order is no longer active.")
        if (p.status == "pending") {
            if (live(c)) api.cancelAll(p.symbol)
            p.status = "cancelled"; p.reason = "cancelled by you"; p.exitMs = api.nowMs()
            save(c.mode, ps)
            return@synchronized "Cancelled the ${p.symbol} ${p.type.lowercase()} order"
        }
        if (live(c)) closeNow(c, api, p, "MANUAL")
        else paperExit(c, p, api.markPrice(p.symbol) * (1 - p.dir * SLIP / 100), "MANUAL", api.nowMs())
        save(c.mode, ps)
        "Closed ${p.symbol} ${p.side}" + (p.pnl?.let { "  ${String.format(Locale.US, "%+.4f", it)} USDT" } ?: "")
    }

    /** New SL / TP for an open manual position (the Binance orders are replaced by the caller). False if none. */
    fun setExits(mode: String, symbol: String, sl: Double, tp: Double): Boolean = synchronized(LOCK) {
        val ps = plans(mode)
        val p = ps.find { it.symbol == symbol && it.status == "open" } ?: return@synchronized false
        p.sl = sl; p.tp = tp
        save(mode, ps)
        journal(p)
        true
    }

    /** Close an open manual position by symbol (Positions tab / notification). Returns null if there is none. */
    fun closeSymbol(c: Config, api: Binance, symbol: String): String? {
        val p = active(c.mode).find { it.symbol == symbol && it.status == "open" } ?: return null
        return cancel(c, api, p.id)
    }

    // ------------------------------------------------------------------ watcher
    fun watch(c: Config, api: Binance): List<Event> = synchronized(LOCK) {
        val ps = plans(c.mode)
        if (ps.none { it.active }) return@synchronized emptyList()
        val ev = ArrayList<Event>()
        val now = api.nowMs()
        if (live(c)) {
            val pos = api.positions()
            for (p in ps.filter { it.active }) {
                try {
                    if (p.status == "pending") {
                        if (p.symbol in pos) {
                            p.status = "open"; p.filledMs = now
                            p.fill = pos.getValue(p.symbol).getString("entryPrice").toDouble()
                            journal(p)
                            ev += Event("open", "Filled: ${p.side} ${p.symbol}", "${f(p.qty)} @ ${f(p.fill!!)}  -  SL ${f(p.sl)}  TP ${f(p.tp)}", true, p.symbol)
                        } else if (now > p.expires && api.positions()[p.symbol] == null) {
                            api.cancelAll(p.symbol)
                            p.status = "cancelled"; p.reason = "expired"; p.exitMs = now
                            ev += Event("info", "Expired: ${p.symbol} ${p.type.lowercase()} order", "Not filled within the time limit.", false, p.symbol)
                        } else if (!api.hasEntryOrder(p.symbol)) {
                            // the entry may have filled between the two calls: re-check before treating it as cancelled
                            val again = api.positions()[p.symbol]
                            if (again != null) {
                                p.status = "open"; p.filledMs = now; p.fill = again.getString("entryPrice").toDouble()
                                journal(p)
                                ev += Event("open", "Filled: ${p.side} ${p.symbol}", "${f(p.qty)} @ ${f(p.fill!!)}  -  SL ${f(p.sl)}  TP ${f(p.tp)}", true, p.symbol)
                            } else {
                                api.cancelAll(p.symbol)
                                p.status = "cancelled"; p.reason = "entry order gone (cancelled on Binance?)"; p.exitMs = now
                                ev += Event("info", "Cancelled: ${p.symbol}", "The entry order is no longer on Binance.", false, p.symbol)
                            }
                        }
                    }
                    if (p.status == "open") {
                        if (p.symbol !in pos) {
                            api.cancelAll(p.symbol)
                            val mk = api.markPrice(p.symbol)
                            val reason = if (abs(mk - p.tp) < abs(mk - p.sl)) "TP" else "SL"
                            finishLive(api, p, mk, reason)
                            ev += Event("close", "Closed ${p.symbol} ${p.side}  -  $reason",
                                p.pnl?.let { String.format(Locale.US, "%+.4f USDT", it) } ?: "", false, p.symbol)
                        } else if (now - p.lastCheck > 45_000) {
                            p.lastCheck = now
                            val have = protect(api, p)
                            if ("SL" !in have) {
                                val mk = api.markPrice(p.symbol)
                                if ((mk - p.sl) * p.dir <= 0) {        // already through the stop and Binance would not take the order
                                    closeNow(c, api, p, "SL")
                                    ev += Event("close", "Stopped out ${p.symbol} ${p.side}", "Closed at market (stop could not be placed).", true, p.symbol)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    store.log("WARN", "Manual watch ${p.symbol}: ${(e as? BinanceException)?.msg ?: e.message}")
                }
            }
        } else {
            for (p in ps.filter { it.active }) {
                try { paperStep(c, api, p, now, ev) } catch (e: Exception) {
                    store.log("WARN", "Manual paper ${p.symbol}: ${e.message}")
                }
            }
        }
        save(c.mode, ps)
        ev
    }

    private fun paperExit(c: Config, p: ManualPlan, px: Double, reason: String, t: Long) {
        val fill = p.fill ?: p.entry
        val pnl = p.qty * (p.dir * (px - fill)) - FEE * px * p.qty
        val st = store.state(c.mode, c.paperStartEquity)
        st.equity += pnl
        store.saveState(c.mode, st)
        val entryFee = FEE * fill * p.qty
        p.status = "closed"; p.exit = px; p.exitMs = t; p.reason = reason; p.pnl = pnl - entryFee
        journal(p)
        store.log("INFO", "PAPER manual CLOSE ${p.symbol} $reason @ ${f(px)} pnl ${String.format(Locale.US, "%+.4f", p.pnl)}")
    }

    /** Replay closed 1-minute candles since the last check: fill the entry, then SL (checked first) / TP. */
    private fun paperStep(c: Config, api: Binance, p: ManualPlan, now: Long, ev: MutableList<Event>) {
        val k = api.klines(p.symbol, "1m", start = p.lastCheck, limit = 1000)
        for (i in 0 until k.length()) {
            val b = k.getJSONArray(i)
            val t = b.getLong(0)
            if (t + 60_000 > now) break                              // still forming
            if (t < p.lastCheck) continue
            val o = b.getString(1).toDouble(); val hi = b.getString(2).toDouble(); val lo = b.getString(3).toDouble()
            p.lastCheck = t + 60_000
            if (p.status == "pending") {
                if (t >= p.expires) { p.status = "cancelled"; p.reason = "expired"; p.exitMs = t
                    ev += Event("info", "Expired: ${p.symbol} paper ${p.type.lowercase()} order", "Not filled in time.", false, p.symbol); return }
                val fillPx: Double? = when (p.type) {
                    "LIMIT" -> if (p.dir == 1 && lo <= p.entry) min(o, p.entry) else if (p.dir == -1 && hi >= p.entry) max(o, p.entry) else null
                    else -> if (p.dir == 1 && hi >= p.entry) max(o, p.entry) * (1 + SLIP / 100)
                            else if (p.dir == -1 && lo <= p.entry) min(o, p.entry) * (1 - SLIP / 100) else null
                }
                if (fillPx == null) continue
                p.status = "open"; p.fill = fillPx; p.filledMs = t
                val st = store.state(c.mode, c.paperStartEquity); st.equity -= FEE * fillPx * p.qty; store.saveState(c.mode, st)
                journal(p)
                ev += Event("open", "Paper fill: ${p.side} ${p.symbol}", "${f(p.qty)} @ ${f(fillPx)}  -  SL ${f(p.sl)}  TP ${f(p.tp)}", false, p.symbol)
            }
            if (p.status == "open") {
                val hitSl = if (p.dir == 1) lo <= p.sl else hi >= p.sl
                val hitTp = if (p.dir == 1) hi >= p.tp else lo <= p.tp
                if (hitSl || hitTp) {
                    val (px, why) = if (hitSl) p.sl * (1 - p.dir * SLIP / 100) to "SL" else p.tp to "TP"
                    paperExit(c, p, px, why, t + 60_000)
                    ev += Event("close", "Paper closed ${p.symbol} ${p.side}  -  $why", String.format(Locale.US, "%+.4f USDT", p.pnl ?: 0.0), false, p.symbol)
                    return
                }
            }
        }
    }

    /** Open paper manual positions with marks (for the Positions tab and account totals). */
    fun paperOpen(mode: String): List<ManualPlan> = active(mode).filter { it.status == "open" }
}
