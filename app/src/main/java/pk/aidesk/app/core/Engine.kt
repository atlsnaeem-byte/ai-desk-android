package pk.aidesk.app.core

import android.content.Context
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

class Event(val kind: String, val title: String, val text: String, val loud: Boolean, val symbol: String? = null)

class Position(
    val symbol: String, val side: String, val qty: Double, val entry: Double, val mark: Double?, val upnl: Double,
    val sl: Double?, val tp: Double?, val openedMs: Long?, val tracked: Boolean, val origin: String,
)

class Account(
    val mode: String, val equity: Double, val available: Double, val upnl: Double,
    val positions: List<Position>, val dayStart: Double?, val halted: String?,
) {
    val dayPnlPct: Double? get() = dayStart?.takeIf { it > 0 }?.let { (equity / it - 1) * 100 }
}

class Preview(
    val symbol: String, val side: String, val price: Double, val qty: Double, val notional: Double, val margin: Double,
    val leverage: Int, val sl: Double, val tp: Double, val riskUsdt: Double, val rewardUsdt: Double, val riskPct: Double,
    val prob: Double, val evR: Double, val equity: Double, val block: String?, val warnings: List<String>,
)

class UserError(msg: String) : Exception(msg)

/** Port of bot.py (Bot.cycle / open_trade / manage_*) for the phone. All calls are blocking: run off the main thread. */
class Engine(context: Context) {
    private val ctx = context.applicationContext
    val store = Store(ctx)

    companion object {
        private val LOCK = Any()
        @Volatile private var cachedModel: SignalModel? = null
        val PKT: ZoneId = ZoneId.of("Asia/Karachi")
        fun fmt(v: Double): String = when {
            v == 0.0 -> "0"
            abs(v) >= 1000 -> String.format(Locale.US, "%,.1f", v)
            abs(v) >= 1 -> String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
            else -> String.format(Locale.US, "%.6g", v)
        }
    }

    val model: SignalModel
        get() = cachedModel ?: synchronized(LOCK) {
            cachedModel ?: SignalModel.load(ctx.assets.open("model.json").bufferedReader().use { it.readText() }).also { cachedModel = it }
        }

    fun cfg(): Config {
        val m = model.meta
        return store.config().copy(
            tpAtr = m.optDouble("tp_atr", 2.0), slAtr = m.optDouble("sl_atr", 1.0), horizonBars = m.optInt("horizon_bars", 16),
        )
    }

    private fun trading(c: Config) = c.mode == "live" || c.mode == "testnet"

    fun client(c: Config): Binance {
        if (!trading(c)) return Binance()
        val (k, s) = store.keys(c.mode) ?: throw UserError("No ${c.mode} API key. Add it in Settings > API key.")
        val base = if (c.mode == "testnet") ctx.getSharedPreferences("aidesk", 0).getString("testnetBase", Binance.TESTNET)!! else Binance.MAINNET
        return Binance(k, s, base)
    }

    // ------------------------------------------------------------------ scan
    private class Scanned(val signal: Signal, val candles: Candles, val row: Map<String, Double>)

    private fun scanSymbols(c: Config, api: Binance, symbols: List<String>): Map<String, Scanned> {
        val all = (symbols + "BTCUSDT").distinct()
        val raw = all.associateWith { api.recent(it) }
        val btc = Features.btcContext(raw.getValue("BTCUSDT"))
        val out = LinkedHashMap<String, Scanned>()
        for (s in symbols) {
            val df = raw.getValue(s)
            if (df.size < 260) continue
            val r = Features.indicators(df)
            val i = df.size - 1
            val row = Features.row(r, df, i, btc)
            val (pl, ps) = model.predict(row)
            val price = df.close[i]
            val atr = r.atr[i]
            val dec = Strategy.decide(pl, ps, price, atr, c)
            val lv = dec ?: Strategy.decide(pl, ps, price, atr, c, minEv = -99.0) ?: continue
            val drv = model.drivers(row, lv.side, 6).map { it.label to it.impact }
            val pats = Features.patternsAt(r, i).map { Features.PATTERNS.getValue(it) }
            val from = maxOf(0, df.size - 60)
            out[s] = Scanned(Signal(
                s, df.openTime[i], price, lv.side, dec != null, lv.prob, pl, ps, lv.evR, price, lv.tp, lv.sl, atr, pats, drv,
                df.open.slice(from until df.size), df.high.slice(from until df.size),
                df.low.slice(from until df.size), df.close.slice(from until df.size),
            ), df, row)
        }
        return out
    }

    // ------------------------------------------------------------------ cycle
    fun cycle(): List<Event> = synchronized(LOCK) {
        val c = cfg()
        val events = mutableListOf<Event>()
        val api = client(c)
        api.syncTime()
        val sc = try {
            scanSymbols(c, api, c.symbols)
        } catch (e: Exception) {
            store.scan()?.let { store.saveScan(it.copy(error = "Binance did not respond (${e.javaClass.simpleName}); showing the last scan")) }
            throw e
        }
        // Keep the original start time while the same side + strength repeats on consecutive bars.
        val prev = store.scan()?.signals?.associateBy { it.symbol } ?: emptyMap()
        val tracked = sc.values.map { x ->
            val s = x.signal
            val p = prev[s.symbol]
            val continues = p != null && p.side == s.side && p.strong == s.strong &&
                s.barTime - p.barTime in 0..Config.BAR_MS
            if (continues) s.copy(since = p!!.since, origEntry = p.origEntry, origTp = p.origTp, origSl = p.origSl) else s
        }
        store.saveScan(Scan(api.nowMs(), tracked))

        val st = store.state(c.mode, c.paperStartEquity)
        var foreign = emptyList<String>()
        // Coins held by the Short Hunter / Copy bots: not counted as "external" slots, never traded by the AI.
        val botHeld = if (trading(c)) store.botSymbols(c.mode).keys else store.botSymbols(c.mode).filterValues { it == "manual" }.keys
        if (trading(c)) {
            prepare(c, api)
            foreign = manageLive(c, api, st, events).filter { it !in botHeld }
        } else managePaper(c, api, st, sc, events)

        val eq = equity(c, api, st)
        val wasHalted = st.halted
        checkDay(c, st, eq)
        if (st.halted != null && wasHalted == null) {
            events += Event("halt", "Auto paused for today", st.halted!!, false)
            store.log("WARN", "Entries paused for today: ${st.halted}")
        }

        val strong = sc.values.map { it.signal }.filter { it.strong }
        if (c.auto) {
            if (st.halted == null) {
                val slots = c.maxOpen - st.open.size - foreign.size
                val cands = strong.filter { it.symbol !in st.open && it.symbol !in foreign && it.symbol !in botHeld }.sortedByDescending { it.evR }
                for (sig in cands.take(maxOf(0, slots))) {
                    val res = openTrade(c, api, st, sig, eq, "auto", c.riskPct)
                    events += res
                }
            }
        } else {
            for (sig in strong) {
                val k = "${sig.symbol}@${sig.barTime}"
                if (store.lastAlert.contains(k)) continue
                store.lastAlert = (store.lastAlert.split(",").takeLast(30) + k).joinToString(",")
                val loud = sig.prob * 100 >= c.soundMinProb
                events += Event("signal", "${sig.side} ${sig.symbol}  -  win ${pct(sig.prob)}",
                    "EV ${"%+.2f".format(Locale.US, sig.evR)}R  -  entry ${fmt(sig.entry)}  TP ${fmt(sig.tp)}  SL ${fmt(sig.sl)}", loud, sig.symbol)
            }
        }
        store.saveState(c.mode, st)
        try {   // record every signal's result, taken or not (never let this break trading)
            SignalLog.update(store, c, tracked, sc.mapValues { it.value.candles }, st, api.nowMs())
        } catch (e: Exception) {
            store.log("WARN", "signal results: ${e.message ?: e.javaClass.simpleName}")
        }
        store.lastCycle = System.currentTimeMillis()
        store.lastError = null
        events
    }

    private fun pct(p: Double) = "${(p * 100).toInt()}%"

    private fun prepare(c: Config, api: Binance) {
        val sig = "${c.mode}|${c.leverage}|${c.marginType}|${c.symbols.joinToString()}|${LocalDayKey()}"
        val prefs = ctx.getSharedPreferences("aidesk", 0)
        if (prefs.getString("prepared", "") == sig) return
        if (api.isHedgeMode()) throw UserError("Your Binance account is in Hedge mode. Switch to One-way mode (Settings > Connection).")
        for (s in c.symbols) {
            try {
                api.setMarginType(s, c.marginType)
                api.setLeverage(s, c.leverage)
            } catch (e: BinanceException) {
                store.log("WARN", "$s setup: ${e.msg}")
            }
        }
        prefs.edit().putString("prepared", sig).apply()
    }

    private fun LocalDayKey() = Instant.now().atZone(PKT).toLocalDate().toString()

    private fun equity(c: Config, api: Binance, st: ModeState) = if (trading(c)) api.equity().first else st.equity

    /** New PKT day resets the start equity; daily loss limit / profit target pause new entries. */
    private fun checkDay(c: Config, st: ModeState, eq: Double): Double {
        val day = LocalDayKey()
        if (st.day != day) {
            st.day = day; st.dayStart = eq; st.halted = null
            store.log("INFO", "New day $day, start equity ${fmt(eq)}")
        }
        val chg = st.dayStart?.takeIf { it > 0 }?.let { (eq / it - 1) * 100 } ?: 0.0
        val usd = st.dayStart?.let { eq - it } ?: 0.0
        if (st.halted == null) {
            if (chg <= -c.dailyLossPct) st.halted = "daily loss limit hit (${"%.2f".format(Locale.US, chg)}%)"
            else if (c.dailyLossUsdt > 0 && usd <= -c.dailyLossUsdt)
                st.halted = "daily loss limit hit (${"%.2f".format(Locale.US, usd)} USDT, limit -${fmt(c.dailyLossUsdt)})"
            else if (chg >= c.dailyProfitPct) st.halted = "daily profit target reached (${"%.2f".format(Locale.US, chg)}%)"
            else if (c.dailyProfitUsdt > 0 && usd >= c.dailyProfitUsdt)
                st.halted = "daily profit target reached (${"%+.2f".format(Locale.US, usd)} USDT, target +${fmt(c.dailyProfitUsdt)})"
        }
        return chg
    }

    // ------------------------------------------------------------------ manage open trades
    private fun managePaper(c: Config, api: Binance, st: ModeState, sc: Map<String, Scanned>, ev: MutableList<Event>) {
        for ((s, tr) in st.open.toMap()) {
            val df = sc[s]?.candles ?: api.recent(s, bars = 60)
            val dr = if (tr.side == "LONG") 1 else -1
            var exitP: Double? = null; var reason: String? = null; var exitT = 0L
            var n = 0
            val lev = c.leverage.toDouble()
            val liqFirst = Liq.beforeStop(tr.side, tr.entry, lev, tr.sl)
            val liq = Liq.trigger(tr.side, tr.entry, lev)
            for (i in 0 until df.size) {
                if (df.openTime[i] < tr.openedMs) continue
                if (n >= c.horizonBars) break
                exitT = df.openTime[i] + Config.BAR_MS
                if (liqFirst && (if (dr == 1) df.low[i] <= liq else df.high[i] >= liq)) {
                    exitP = Liq.bankrupt(tr.side, tr.entry, lev); reason = "LIQUIDATED"; break
                }
                val hitSl = if (dr == 1) df.low[i] <= tr.sl else df.high[i] >= tr.sl
                val hitTp = if (dr == 1) df.high[i] >= tr.tp else df.low[i] <= tr.tp
                if (hitSl) { exitP = tr.sl; reason = "SL"; break }
                if (hitTp) { exitP = tr.tp; reason = "TP"; break }
                n++
                if (n == c.horizonBars) { exitP = df.close[i]; reason = "TIME"; break }
            }
            if (exitP != null) ev += paperClose(c, st, tr, exitP, reason!!, exitT)
        }
    }

    private fun paperClose(c: Config, st: ModeState, tr: Trade, exit0: Double, reason: String, exitT: Long): Event {
        val dr = if (tr.side == "LONG") 1 else -1
        val slD = abs(tr.entry - tr.sl)
        val exit = exit0 * (1 - dr * c.slippagePct / 100)
        val pnl = tr.qty * (dr * (exit - tr.entry) - c.feeRate * (tr.entry + exit))
        st.equity += pnl
        st.open.remove(tr.symbol)
        val r = if (slD > 0 && tr.qty > 0) pnl / (tr.qty * slD) else null
        store.journalUpsert(tr.copy(status = "closed", exit = exit, exitMs = exitT, reason = reason, pnl = pnl, r = r))
        hist(c, tr, exit, exitT, pnl, reason)
        store.log("INFO", "PAPER CLOSE ${tr.symbol} ${tr.side} $reason @ ${fmt(exit)} pnl ${"%+.2f".format(Locale.US, pnl)}")
        return closedEvent(tr, reason, pnl, r)
    }

    /** Trade history row (Settings > Data > Export) for an AI-engine trade. */
    private fun hist(c: Config, tr: Trade, exit: Double?, exitT: Long, pnl: Double?, reason: String) =
        History.record(store, History.Rec(c.mode, "AI ${tr.origin}", tr.patterns.joinToString("; "), tr.symbol, tr.side, tr.entry, exit ?: tr.entry,
            tr.qty, tr.qty * tr.entry / c.leverage, c.leverage.toDouble(), tr.sl, tr.tp, tr.openedMs, exitT, pnl, reason))

    private fun closedEvent(tr: Trade, reason: String, pnl: Double?, r: Double?) = Event(
        "close", "Closed ${tr.symbol} ${tr.side}  -  $reason" + (r?.let { "  ${"%+.2f".format(Locale.US, it)}R" } ?: ""),
        pnl?.let { "${"%+.4f".format(Locale.US, it)} USDT" } ?: "", false, tr.symbol)

    /** Returns symbols with positions the app does not track (left alone, but they use a slot). */
    private fun manageLive(c: Config, api: Binance, st: ModeState, ev: MutableList<Event>): List<String> {
        val pos = api.positions()
        for ((s, tr) in st.open.toMap()) {
            val p = pos[s]
            if (p == null) { // closed by the exchange TP/SL (or by hand in the Binance app)
                api.cancelAll(s)
                val pnl = runCatching { api.realizedPnlSince(s, tr.openedMs) }.getOrNull()
                st.open.remove(s)
                val dr = if (tr.side == "LONG") 1 else -1
                val slD = abs(tr.entry - tr.sl)
                val ok = pnl != null && tr.qty > 0
                val reason = if (ok) (if (pnl!! > 0) "TP" else "SL") else "CLOSED"
                val r = if (ok && slD > 0) pnl!! / (tr.qty * slD) else null
                store.journalUpsert(tr.copy(status = "closed", exit = if (ok) tr.entry + dr * pnl!! / tr.qty else null,
                    exitMs = api.nowMs(), reason = reason, pnl = pnl, r = r))
                hist(c, tr, if (ok) tr.entry + dr * pnl!! / tr.qty else null, api.nowMs(), pnl, reason)
                store.log("INFO", "CLOSED $s ${tr.side} (exchange) pnl ${pnl ?: "?"}")
                ev += closedEvent(tr, reason, pnl, r)
                continue
            }
            val amt = p.getString("positionAmt").toDouble()
            val mark = p.optString("markPrice").toDoubleOrNull() ?: api.markPrice(s)
            val dr = if (tr.side == "LONG") 1 else -1
            val held = (api.nowMs() - tr.openedMs) / Config.BAR_MS
            val hitSl = if (dr == 1) mark <= tr.sl else mark >= tr.sl
            val hitTp = if (dr == 1) mark >= tr.tp else mark <= tr.tp
            val reason = when {
                held >= c.horizonBars -> "TIME"
                hitSl -> "SL_GUARD"   // backup in case the exchange stop did not fire
                hitTp -> "TP_GUARD"
                else -> null
            }
            if (reason != null) closeLive(c, api, st, s, amt, reason)?.let { ev += it }
        }
        return pos.keys.filter { it !in st.open }
    }

    private fun closeLive(c: Config, api: Binance, st: ModeState, s: String, amt: Double, reason: String): Event? {
        val (_, q) = api.fmtQty(s, abs(amt))
        val r = try {
            api.marketOrder(s, if (amt > 0) "SELL" else "BUY", q, reduceOnly = true)
        } catch (e: BinanceException) {
            store.log("ERROR", "CLOSE FAILED $s: ${e.msg}")
            throw UserError("Close failed: ${e.msg}")
        }
        api.cancelAll(s)
        val tr = st.open.remove(s) ?: return null
        val dr = if (tr.side == "LONG") 1 else -1
        val ex = r.optString("avgPrice").toDoubleOrNull()?.takeIf { it > 0 }
        val slD = abs(tr.entry - tr.sl)
        val pnl = ex?.let { tr.qty * (dr * (it - tr.entry) - c.feeRate * (tr.entry + it)) }
        val rr = if (pnl != null && slD > 0) pnl / (tr.qty * slD) else null
        store.journalUpsert(tr.copy(status = "closed", exit = ex, exitMs = api.nowMs(), reason = reason, pnl = pnl, r = rr))
        hist(c, tr, ex, api.nowMs(), pnl, reason)
        store.log("INFO", "CLOSE $s $reason qty $q avg ${ex ?: "?"}")
        return closedEvent(tr, reason, pnl, rr)
    }

    // ------------------------------------------------------------------ entries
    private fun openTrade(c: Config, api: Binance, st: ModeState, sig: Signal, eq: Double, origin: String, riskPct: Double,
                          d0: Decision? = null): Event {
        val d = d0 ?: Decision(sig.side, sig.prob, sig.evR, sig.price, sig.sl, sig.tp, abs(sig.price - sig.sl), abs(sig.tp - sig.price))
        val s = sig.symbol
        val dr = if (d.side == "LONG") 1 else -1
        val tid = if (origin == "auto") "${c.mode}-$s-${sig.barTime}" else "${c.mode}-$s-m${api.nowMs()}"
        if (!trading(c)) {
            val entry = d.entry * (1 + dr * c.slippagePct / 100)
            val qty = Strategy.positionSize(eq, entry, d.slDist, c, riskPct)
            val opened = if (origin == "auto") sig.barTime + Config.BAR_MS else (api.nowMs() / Config.BAR_MS) * Config.BAR_MS
            val tr = Trade(tid, c.mode, s, d.side, entry, qty, entry - dr * d.slDist, entry + dr * d.tpDist, opened, d.prob, d.evR,
                origin, eq, sig.patterns, sig.drivers)
            st.open[s] = tr
            store.journalUpsert(tr)
            store.log("INFO", "PAPER OPEN ($origin) $s ${d.side} qty ${fmt(qty)} @ ${fmt(entry)}")
            return openedEvent(c, tr, origin)
        }
        val f = api.filters(s)
        val price = api.markPrice(s)
        val (qty, q) = api.fmtQty(s, Strategy.positionSize(eq, price, d.slDist, c, riskPct))
        if (qty < f.minQty || qty * price < f.minNotional) {
            val msg = "$s: order size ${"%.2f".format(Locale.US, qty * price)} USDT is below Binance's minimum ${"%.0f".format(Locale.US, f.minNotional)} USDT"
            store.log("INFO", "$msg, skipped")
            if (origin != "auto") throw UserError("$msg. Raise risk % or add funds.")
            return Event("skip", "Skipped $s", msg, false, s)
        }
        val side = if (d.side == "LONG") "BUY" else "SELL"
        val exitSide = if (side == "BUY") "SELL" else "BUY"
        val r = try {
            api.marketOrder(s, side, q)
        } catch (e: BinanceException) {
            store.log("ERROR", "ENTRY FAILED $s: ${e.msg}")
            if (origin != "auto") throw UserError("Entry rejected by Binance: ${e.msg}")
            return Event("error", "Entry failed: $s", e.msg, false, s)
        }
        val fill = r.optString("avgPrice").toDoubleOrNull()?.takeIf { it > 0 } ?: price
        val (_, slS) = api.fmtPrice(s, fill - dr * d.slDist)
        val (_, tpS) = api.fmtPrice(s, fill + dr * d.tpDist)
        val tr = Trade(tid, c.mode, s, d.side, fill, qty, slS.toDouble(), tpS.toDouble(), api.nowMs(), d.prob, d.evR, origin, eq,
            sig.patterns, sig.drivers)
        st.open[s] = tr
        store.saveState(c.mode, st)
        store.journalUpsert(tr)
        var stopOk = true
        for ((kind, trig) in listOf("STOP_MARKET" to slS, "TAKE_PROFIT_MARKET" to tpS)) {
            try {
                api.closeTrigger(s, exitSide, kind, trig)
            } catch (e: BinanceException) {
                store.log("ERROR", "$s $kind order failed: ${e.msg}")
                if (kind == "STOP_MARKET") stopOk = false
            }
        }
        if (!stopOk) { // never hold a position without a stop
            closeLive(c, api, st, s, qty * dr, "NO_STOP")
            store.saveState(c.mode, st)
            if (origin != "auto") throw UserError("Stop-loss order was rejected, so the position was closed immediately.")
            return Event("error", "$s closed: no stop-loss", "Binance rejected the stop order, so the position was closed.", true, s)
        }
        store.log("INFO", "OPEN ($origin) $s ${d.side} qty $q @ ${fmt(fill)} SL $slS TP $tpS")
        return openedEvent(c, tr, origin)
    }

    private fun openedEvent(c: Config, tr: Trade, origin: String) = Event(
        "open", "Opened ${tr.side} ${tr.symbol}" + if (origin == "auto") "" else " (manual)",
        "${fmt(tr.qty)} @ ${fmt(tr.entry)}  -  win ${pct(tr.prob)}  -  TP ${fmt(tr.tp)}  SL ${fmt(tr.sl)}",
        origin == "auto" && tr.prob * 100 >= c.soundMinProb, tr.symbol)

    // ------------------------------------------------------------------ manual actions (UI)
    private fun freshDecision(c: Config, api: Binance, s: String, price: Double): Pair<Signal, Decision> {
        val x = scanSymbols(c, api, listOf(s))[s] ?: throw UserError("Not enough data for $s")
        val sig = x.signal
        val p = if (sig.side == "LONG") sig.pLong else sig.pShort
        val tpD = c.tpAtr * sig.atr; val slD = c.slAtr * sig.atr
        val costR = (2 * c.feeRate + 2 * c.slippagePct / 100) * price / slD
        val dr = if (sig.side == "LONG") 1 else -1
        return sig to Decision(sig.side, p, p * (tpD / slD) - (1 - p) - costR, price, price - dr * slD, price + dr * tpD, slD, tpD)
    }

    fun preview(symbol: String, riskPct: Double): Preview = synchronized(LOCK) {
        val c = cfg()
        val api = client(c)
        api.syncTime()
        val price = api.markPrice(symbol)
        val (sig, d) = freshDecision(c, api, symbol, price)
        val st = store.state(c.mode, c.paperStartEquity)
        val eq = equity(c, api, st)
        var qty = Strategy.positionSize(eq, price, d.slDist, c, riskPct)
        var block: String? = null
        val warn = mutableListOf<String>()
        if (trading(c)) {
            val f = api.filters(symbol)
            qty = api.fmtQty(symbol, qty).first
            if (qty < f.minQty || qty * price < f.minNotional)
                block = "Order size ${"%.2f".format(Locale.US, qty * price)} USDT is below Binance's minimum of ${"%.0f".format(Locale.US, f.minNotional)} USDT for $symbol. Add funds or raise risk %."
            if (symbol in api.positions()) block = "There is already an open $symbol position. Close it first."
        } else if (symbol in st.open) block = "There is already an open paper $symbol position."
        if (!sig.strong) warn += "This is a weak signal (EV ${"%+.2f".format(Locale.US, d.evR)}R, below your minimum)."
        st.halted?.let { warn += "Auto entries are paused today: $it." }
        Preview(symbol, d.side, price, qty, qty * price, qty * price / c.leverage, c.leverage, d.sl, d.tp, qty * d.slDist,
            qty * d.tpDist, riskPct, d.prob, d.evR, eq, block, warn)
    }

    fun manualOpen(symbol: String, riskPct: Double): String = synchronized(LOCK) {
        val c = cfg()
        val api = client(c)
        api.syncTime()
        val st = store.state(c.mode, c.paperStartEquity)
        if (trading(c)) {
            prepare(c, api)
            if (symbol in api.positions()) throw UserError("There is already an open $symbol position.")
        } else if (symbol in st.open) throw UserError("There is already an open paper $symbol position.")
        val price = api.markPrice(symbol)
        val (sig, d) = freshDecision(c, api, symbol, price)
        val eq = equity(c, api, st)
        val ev = openTrade(c, api, st, sig, eq, "manual", riskPct, d)
        store.saveState(c.mode, st)
        if (ev.kind != "open") throw UserError(ev.text)
        ev.title + "\n" + ev.text
    }

    fun manualClose(symbol: String): String = synchronized(LOCK) {
        val c = cfg()
        val api = client(c)
        api.syncTime()
        ManualDesk(store).closeSymbol(c, api, symbol)?.let { return@synchronized it }   // trade opened from the Trade tab
        val st = store.state(c.mode, c.paperStartEquity)
        if (symbol !in st.open) {      // positions of the Short hunter / Copy bots: close through the bot so it records the result
            val sh = ShortHunter(store)
            if (symbol in sh.state(c.mode).open) return@synchronized sh.closeManual(c.mode, api, symbol)
            val ph = PumpHunter(store)
            if (symbol in ph.state(c.mode).open) return@synchronized ph.closeManual(c.mode, api, symbol)
            val cb = CopyBot(store)
            cb.state(c.mode).copies.entries.firstOrNull { it.value.symbol == symbol }?.let {
                return@synchronized cb.closeManual(c.mode, api, Binance(), it.key)
            }
        }
        val msg = if (trading(c)) {
            val p = api.positions()[symbol] ?: throw UserError("No open $symbol position on Binance.")
            val ev = closeLive(c, api, st, symbol, p.getString("positionAmt").toDouble(), "MANUAL")
            ev?.let { it.title + " " + it.text } ?: "Closed $symbol (it was not opened by the app)"
        } else {
            val tr = st.open[symbol] ?: throw UserError("No open paper $symbol position.")
            paperClose(c, st, tr, api.markPrice(symbol), "MANUAL", api.nowMs()).let { it.title + " " + it.text }
        }
        store.saveState(c.mode, st)
        msg
    }

    /**
     * Positions tab > Edit SL / TP: new stop and target for an open position of any bot (or one opened outside the app).
     * Live/testnet: the Binance SL/TP orders are replaced (old ones restored if the new stop is rejected).
     */
    fun editExits(symbol: String, sl: Double, tp: Double): String = synchronized(LOCK) {
        synchronized(ManualDesk.LOCK) {
            synchronized(PumpHunter.STEP_LOCK) {
                val c = cfg()
                val api = client(c)
                api.syncTime()
                val mode = c.mode
                val st = store.state(mode, c.paperStartEquity)
                val sh = ShortHunter(store); val ph = PumpHunter(store); val desk = ManualDesk(store)
                val shortPos = sh.state(mode).open[symbol]
                val pumpPos = ph.state(mode).open[symbol]
                val manPos = desk.active(mode).find { it.symbol == symbol && it.status == "open" }
                val aiPos = st.open[symbol]
                if (shortPos == null && pumpPos == null && manPos == null && aiPos == null &&
                    CopyBot(store).state(mode).copies.values.any { it.symbol == symbol })
                    throw UserError("Copy trades close when the leader closes, so they have no SL / TP to edit.")
                val live = trading(c)
                val livePos = if (live) api.positions()[symbol] else null
                val side = when {
                    shortPos != null -> "SHORT"; pumpPos != null -> "LONG"; manPos != null -> manPos.side; aiPos != null -> aiPos.side
                    livePos != null -> if (livePos.getString("positionAmt").toDouble() > 0) "LONG" else "SHORT"
                    else -> throw UserError("No open $symbol position.")
                }
                val dr = if (side == "LONG") 1 else -1
                val mark = api.markPrice(symbol)
                if ((mark - sl) * dr <= 0) throw UserError("Stop-loss must be ${if (dr == 1) "below" else "above"} the current price ${fmt(mark)} for a $side.")
                if ((tp - mark) * dr <= 0) throw UserError("Take-profit must be ${if (dr == 1) "above" else "below"} the current price ${fmt(mark)} for a $side.")
                var newSl = sl; var newTp = tp
                var note = ""
                if (live) {
                    if (livePos == null) throw UserError("No open $symbol position on Binance.")
                    newSl = api.fmtPrice(symbol, sl).first; newTp = api.fmtPrice(symbol, tp).first
                    val exitSide = if (dr == 1) "SELL" else "BUY"
                    val oldSl = shortPos?.sl ?: pumpPos?.sl ?: manPos?.sl ?: aiPos?.sl
                    val oldTp = shortPos?.tp ?: pumpPos?.tp ?: manPos?.tp ?: aiPos?.tp
                    api.cancelAll(symbol)
                    try {
                        api.closeTrigger(symbol, exitSide, "STOP_MARKET", api.fmtPrice(symbol, newSl).second)
                    } catch (e: BinanceException) {
                        oldSl?.let { o -> runCatching { api.closeTrigger(symbol, exitSide, "STOP_MARKET", api.fmtPrice(symbol, o).second) } }
                        oldTp?.let { o -> runCatching { api.closeTrigger(symbol, exitSide, "TAKE_PROFIT_MARKET", api.fmtPrice(symbol, o).second) } }
                        store.log("ERROR", "Edit SL/TP $symbol: new stop rejected (${e.msg}), old orders put back")
                        throw UserError("Binance rejected the new stop-loss (${e.msg}). The old SL / TP were put back.")
                    }
                    try {
                        api.closeTrigger(symbol, exitSide, "TAKE_PROFIT_MARKET", api.fmtPrice(symbol, newTp).second)
                    } catch (e: BinanceException) {
                        note = "\nTake-profit order rejected by Binance (${e.msg}); the bot watches the target itself."
                        store.log("WARN", "Edit SL/TP $symbol: take-profit rejected (${e.msg})")
                    }
                }
                val owner = when {
                    shortPos != null -> { sh.setExits(mode, symbol, newSl, newTp); "Short hunter" }
                    pumpPos != null -> { ph.setExits(mode, symbol, newSl, newTp); "Pump hunter" }
                    manPos != null -> { desk.setExits(mode, symbol, newSl, newTp); "Trade tab" }
                    aiPos != null -> {
                        val t = aiPos.copy(sl = newSl, tp = newTp)
                        st.open[symbol] = t; store.saveState(mode, st); store.journalUpsert(t); "AI bot"
                    }
                    else -> "Binance"
                }
                store.log("INFO", "Edit SL/TP $symbol ($owner, ${mode.uppercase()}): SL ${fmt(newSl)}  TP ${fmt(newTp)}")
                "$symbol $side: new SL ${fmt(newSl)}, TP ${fmt(newTp)}" + (if (live) " (orders replaced on Binance)" else "") + note
            }
        }
    }

    fun account(): Account {
        val c = cfg()
        val api = client(c)
        val st = store.state(c.mode, c.paperStartEquity)
        if (!trading(c)) {
            val man = ManualDesk(store).paperOpen(c.mode)
            val shSt = ShortHunter(store).state(c.mode)
            val shorts = shSt.open.values.toList()
            val (hq, he) = shSt.hedge()
            val copies = CopyBot(store).state(c.mode).copies.values.toList()
            val longs = PumpHunter(store).state(c.mode).open.values.toList()
            val any = st.open.isNotEmpty() || man.isNotEmpty() || shorts.isNotEmpty() || copies.isNotEmpty() || longs.isNotEmpty()
            val marks = if (any) runCatching { api.markPrices() }.getOrDefault(emptyMap()) else emptyMap()
            // bot positions use their own paper wallets: listed with live P&L, not added to this equity
            val botPos = shorts.map { p ->
                val mk = marks[p.symbol]
                Position(p.symbol, "SHORT", p.qty, p.entry, mk, if (mk != null) p.qty * (p.entry - mk) else 0.0, p.sl, p.tp, p.opened, true,
                    "short hunter · " + p.rule)
            } + (if (hq > 0) listOf(marks[ShortHunter.HEDGE].let { mk ->
                Position(ShortHunter.HEDGE, "LONG", hq, he, mk, if (mk != null) hq * (mk - he) else 0.0, null, null,
                    shorts.filter { it.hedgeQty > 0 }.minOfOrNull { it.opened }, true, "btc hedge")
            }) else emptyList()) + copies.map { p ->
                val mk = marks[p.symbol]
                val dr = if (p.side == "LONG") 1 else -1
                Position(p.symbol, p.side, p.qty, p.entry, mk, if (mk != null) p.qty * dr * (mk - p.entry) else 0.0, null, null, p.opened, true, "copy")
            } + longs.map { p ->
                val mk = marks[p.symbol]
                Position(p.symbol, "LONG", p.qty, p.entry, mk, if (mk != null) p.qty * (mk - p.entry) else 0.0, p.sl, p.tp, p.opened, true,
                    "pump hunter · " + p.rule)
            }
            var upnl = 0.0
            val ps = st.open.values.map { tr ->
                val dr = if (tr.side == "LONG") 1 else -1
                val mk = marks[tr.symbol]
                val u = if (mk != null) tr.qty * dr * (mk - tr.entry) else 0.0
                upnl += u
                Position(tr.symbol, tr.side, tr.qty, tr.entry, mk, u, tr.sl, tr.tp, tr.openedMs, true, tr.origin)
            } + man.map { p ->
                val mk = marks[p.symbol]
                val fill = p.fill ?: p.entry
                val u = if (mk != null) p.qty * p.dir * (mk - fill) else 0.0
                upnl += u
                Position(p.symbol, p.side, p.qty, fill, mk, u, p.sl, p.tp, p.filledMs, true, "manual")
            } + botPos
            return Account(c.mode, st.equity + upnl, st.equity, upnl, ps, st.dayStart, st.halted)
        }
        val acc = api.account()
        val owners = store.botSymbols(c.mode)
        val man = ManualDesk(store).active(c.mode).filter { it.status == "open" }.associateBy { it.symbol }
        val sh = ShortHunter(store).state(c.mode).open
        val ph = PumpHunter(store).state(c.mode).open
        val ps = api.positions().map { (s, p) ->
            val amt = p.getString("positionAmt").toDouble()
            val tr = st.open[s]
            val mp = man[s]
            val sp = sh[s]
            val pp = ph[s]
            Position(s, if (amt > 0) "LONG" else "SHORT", abs(amt), p.getString("entryPrice").toDouble(),
                p.optString("markPrice").toDoubleOrNull(), p.optString("unRealizedProfit").toDoubleOrNull() ?: 0.0,
                tr?.sl ?: mp?.sl ?: sp?.sl ?: pp?.sl, tr?.tp ?: mp?.tp ?: sp?.tp ?: pp?.tp, tr?.openedMs ?: mp?.filledMs ?: sp?.opened ?: pp?.opened,
                tr != null || mp != null || sp != null || pp != null, tr?.origin ?: owners[s] ?: "external")
        }
        return Account(c.mode, acc.getString("totalMarginBalance").toDouble(), acc.getString("availableBalance").toDouble(),
            acc.getString("totalUnrealizedProfit").toDouble(), ps, st.dayStart, st.halted)
    }

    /** Settings > API key > Test: checks keys, environment, position mode and key permissions. */
    fun testConnection(mode: String): List<String> {
        val (k, s) = store.keys(mode) ?: throw UserError("No $mode API key saved.")
        val out = mutableListOf<String>()
        val bases = if (mode == "live") listOf(Binance.MAINNET) else listOf(Binance.TESTNET, Binance.DEMO)
        var found: Binance? = null
        var err: Exception? = null
        for (b in bases) {
            val a = Binance(k, s, b)
            try { a.syncTime(); a.account(); found = a; break } catch (e: Exception) { err = e }
        }
        val api = found ?: run {
            val be = err as? BinanceException
            val msg = be?.msg ?: err?.message ?: "unknown error"
            val hint = if (be?.code == -2015)
                " This usually means the key is IP-restricted and this phone's IP is not on the list, or Futures is not enabled on the key." else ""
            throw UserError("Connection failed: $msg.$hint")
        }
        if (mode == "testnet") ctx.getSharedPreferences("aidesk", 0).edit().putString("testnetBase", api.base).apply()
        val (eq, av) = api.equity()
        out += "Connected: " + if (mode == "live") "Binance Futures LIVE" else api.base
        out += "Equity ${"%.2f".format(Locale.US, eq)} USDT, available ${"%.2f".format(Locale.US, av)}"
        out += if (api.isHedgeMode()) "WARNING: account is in Hedge mode; tap 'Switch to One-way'." else "Position mode: One-way (OK)"
        if (mode == "live") runCatching { api.apiRestrictions() }.onSuccess { r: JSONObject ->
            if (r.optBoolean("enableWithdrawals")) out += "WARNING: this key can WITHDRAW funds. Turn withdrawals off in Binance."
            if (!r.optBoolean("enableFutures")) out += "WARNING: Futures trading is not enabled on this key."
            out += if (r.optBoolean("ipRestrict")) "IP restricted: yes (the phone's current IP must be on the list)" else "IP restricted: no"
        }
        return out
    }

    fun setOneWay(): String {
        val c = cfg()
        val api = client(c)
        api.syncTime()
        if (api.positions().isNotEmpty()) throw UserError("Close all open positions first.")
        api.setOneWay()
        return "Position mode is now One-way"
    }

    fun journal(mode: String) = store.journal(mode)

    fun barTimeText(ms: Long): String = Instant.ofEpochMilli(ms).atZone(PKT).toLocalTime().toString().take(5)
}
