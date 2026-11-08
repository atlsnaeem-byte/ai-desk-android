package pk.aidesk.app.core

import org.json.JSONObject
import kotlin.math.abs

/**
 * One signal "episode": recorded once, when a side + strength first appears for a symbol (Signal.since),
 * then followed bar by bar to its result exactly like a paper trade would be (TP / SL / time exit),
 * whether or not a trade was taken.
 */
class SigRec(
    val id: String, val mode: String, val symbol: String, val side: String, val strong: Boolean,
    val prob: Double, val evR: Double, val entry: Double, val tp: Double, val sl: Double, val since: Long,
    var taken: Boolean = false, var outcome: String? = null, var exit: Double? = null, var exitMs: Long? = null,
    var r: Double? = null, var bars: Int = 0,
) {
    val resolved: Boolean get() = outcome != null && outcome != "EXPIRED"
    val win: Boolean get() = (r ?: 0.0) > 0

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("mode", mode); put("symbol", symbol); put("side", side); put("strong", strong); put("prob", prob)
        put("evR", evR); put("entry", entry); put("tp", tp); put("sl", sl); put("since", since); put("taken", taken); put("bars", bars)
        outcome?.let { put("outcome", it) }; exit?.let { put("exit", it) }; exitMs?.let { put("exitMs", it) }
        r?.takeIf { !it.isNaN() }?.let { put("r", it) }
    }

    companion object {
        fun from(o: JSONObject) = SigRec(
            o.getString("id"), o.optString("mode", "paper"), o.getString("symbol"), o.getString("side"), o.getBoolean("strong"),
            o.optDouble("prob", 0.0), o.optDouble("evR", 0.0), o.getDouble("entry"), o.getDouble("tp"), o.getDouble("sl"),
            o.getLong("since"), o.optBoolean("taken", false),
            o.optString("outcome").ifEmpty { null }, if (o.has("exit")) o.getDouble("exit") else null,
            if (o.has("exitMs")) o.getLong("exitMs") else null, if (o.has("r")) o.getDouble("r") else null, o.optInt("bars", 0),
        )
    }
}

object SignalLog {
    /**
     * Called by Engine.cycle after every scan. `candles` = the scan's closed 15m bars per symbol.
     * R is net of fees + slippage, so it is comparable with the signal's EV.
     */
    fun update(store: Store, c: Config, signals: List<Signal>, candles: Map<String, Candles>, st: ModeState, now: Long) {
        val log = store.signalLog().toMutableList()
        val ids = log.mapTo(HashSet()) { it.id }
        for (s in signals) {
            val id = "${s.symbol}-${s.side}-${if (s.strong) "S" else "W"}-${s.since}"
            if (id !in ids) {
                log += SigRec(id, c.mode, s.symbol, s.side, s.strong, s.prob, s.evR, s.origEntry, s.origTp, s.origSl, s.since)
                ids += id
            }
        }
        val horizonMs = c.horizonBars * Config.BAR_MS
        val recent = log.filter { now - it.since < horizonMs + 2 * 86_400_000L }
        if (recent.isNotEmpty()) {
            val jr = store.journal(c.mode)
            for (r in recent) {
                if (r.taken) continue
                fun match(t: Trade) = t.symbol == r.symbol && t.side == r.side &&
                    t.openedMs >= r.since - Config.BAR_MS && t.openedMs <= r.since + horizonMs
                r.taken = st.open.values.any(::match) || jr.any(::match)
            }
        }
        for (r in log) {
            if (r.outcome != null) continue
            val df = candles[r.symbol]
            if (df == null) {   // token removed from the scan list
                if (now - r.since > horizonMs + 86_400_000L) r.outcome = "EXPIRED"
                continue
            }
            resolve(r, df, c)
        }
        store.saveSignalLog(log)
    }

    private fun resolve(r: SigRec, df: Candles, c: Config) {
        val dr = if (r.side == "LONG") 1 else -1
        val slD = abs(r.entry - r.sl)
        if (slD <= 0) { r.outcome = "EXPIRED"; return }
        val cost = (2 * c.feeRate + 2 * c.slippagePct / 100) * r.entry / slD
        var n = 0
        for (i in 0 until df.size) {
            if (df.openTime[i] < r.since) continue
            n++
            val hitSl = if (dr == 1) df.low[i] <= r.sl else df.high[i] >= r.sl
            val hitTp = if (dr == 1) df.high[i] >= r.tp else df.low[i] <= r.tp
            val (px, why) = when {
                hitSl -> r.sl to "SL"                      // same bar: assume the stop first (as the paper trader does)
                hitTp -> r.tp to "TP"
                n >= c.horizonBars -> df.close[i] to "TIME"
                else -> null to null
            }
            if (why != null && px != null) {
                r.outcome = why; r.exit = px; r.exitMs = df.openTime[i] + Config.BAR_MS; r.bars = n
                r.r = dr * (px - r.entry) / slD - cost
                return
            }
        }
        r.bars = n
    }
}
