package pk.aidesk.app.core

import kotlin.math.abs
import kotlin.math.max

/**
 * TA-course setups scored by confluence: RSI divergence (regular / hidden), support-resistance bounce and
 * rectangle range fade, plus higher-timeframe trend, RSI extreme, candle confirmation and chart-pattern bias.
 * Same rules as the laptop's confluence/backtest.py (keep both in step). Pure logic, no Android deps.
 */
object Confluence {
    const val K = 3
    const val HOLD = 30                 // max bars in a trade
    /**
     * Laptop backtest (confluence/backtest.py, 20 coins, Nov 2023 - Nov 2026, after costs, avg R per trade):
     * 4h RSI divergence +0.05 / +0.02 / +0.05 (2024 / 2025 / 2026), 462 trades; 4h range fade +1.4R but only 10 trades;
     * S/R bounce alone -0.05R (11k trades); every 1h setup negative; a higher confluence score did NOT do better.
     * So the scanner signals only 4h divergence / range-fade setups; the score is shown as information.
     */
    val TFS = listOf("4h")
    val SIGNAL_KINDS = setOf("div", "range")
    fun backtestNote(tf: String, kinds: List<String>): String = when {
        tf != "4h" -> "· backtest: every $tf version of these setups lost money; treat as information only"
        "range" in kinds -> "· backtest 4h range fade: +1.4R per trade, but only 10 trades in 3 years (too few to trust)"
        "div" in kinds -> "· backtest 4h RSI divergence: +0.02 to +0.05R per trade after costs, 41-44% wins (thin edge)"
        else -> "· backtest 4h S/R bounce alone: -0.05R per trade (loses); not signalled"
    }

    class Setup(
        val side: Int, val kinds: List<String>, val score: Int, val entry: Double, val sl: Double, val tp: Double,
        val why: List<String>, val barTime: Long,
    ) {
        val sideName: String get() = if (side == 1) "LONG" else "SHORT"
        val rr: Double get() = abs(tp - entry) / max(abs(entry - sl), 1e-12)
        val title: String get() = kinds.joinToString(" + ") { NAMES[it] ?: it }
    }

    val NAMES = mapOf("div" to "RSI divergence", "sr" to "S/R bounce", "range" to "Range fade")

    /** Wilder RSI, first n values = 50 (same as backtest.py). */
    fun rsi(c: DoubleArray, n: Int = 14): DoubleArray {
        val out = DoubleArray(c.size) { 50.0 }
        var au = 0.0; var ad = 0.0
        val a = 1.0 / n
        for (i in 1 until c.size) {
            val d = c[i] - c[i - 1]
            au = (1 - a) * au + a * (if (d > 0) d else 0.0)
            ad = (1 - a) * ad + a * (if (d < 0) -d else 0.0)
            if (i >= n) out[i] = if (ad > 0) 100 - 100 / (1 + au / ad) else 100.0
        }
        return out
    }

    /**
     * Setups at the LAST bar of [cd] (closed candles only). [trend] = higher-timeframe regime (+1/0/-1) known at that close,
     * [active] = Patterns.scan(cd).active. Returns at most one setup (conflicting sides on one bar give none).
     */
    fun evaluate(cd: Candles, trend: Int, active: List<Patterns.Pat>): Setup? {
        val o = cd.open; val h = cd.high; val l = cd.low; val c = cd.close
        val n = c.size
        val i = n - 1
        if (n < 251) return null
        val atr = Patterns.atr(h, l, c, 14)
        val a = atr[i]
        if (!(a > 0)) return null
        val rsi = rsi(c)
        val piv = Patterns.rawPivots(h, l, K).filter { it.conf <= i }
        val rec = piv.filter { it.idx >= i - 200 }

        class Cand { val kinds = ArrayList<String>(); var pts = 0; val why = ArrayList<String>(); var sl: Double? = null; var tp: Double? = null }
        val cands = HashMap<Int, Cand>()
        fun add(side: Int, kind: String, pts: Int, why: String, sl: Double, tp: Double? = null) {
            val d = cands.getOrPut(side) { Cand() }
            d.kinds += kind; d.pts += pts; d.why += "+ $why"
            if (d.sl == null) { d.sl = sl; d.tp = tp }
        }
        fun r1(v: Double) = String.format(java.util.Locale.US, "%.0f", v)

        // 1) RSI divergence on the swing point confirmed at this bar
        for (p in piv.filter { it.conf == i }) {
            val prev = rec.filter { it.typ == p.typ && it.idx < p.idx && p.idx - it.idx in 5..60 }
            val q = prev.lastOrNull() ?: continue
            val j1 = q.idx; val j2 = p.idx
            if (p.typ == -1) {
                if (p.price < q.price && rsi[j2] > rsi[j1] + 1 && rsi[j1] < 40)
                    add(1, "div", 2, "regular bullish RSI divergence (lower low, RSI ${r1(rsi[j1])}→${r1(rsi[j2])})", p.price - 0.25 * a)
                else if (p.price > q.price && rsi[j2] < rsi[j1] - 1 && trend == 1)
                    add(1, "div", 2, "hidden bullish RSI divergence (higher low, RSI ${r1(rsi[j1])}→${r1(rsi[j2])})", p.price - 0.25 * a)
            } else {
                if (p.price > q.price && rsi[j2] < rsi[j1] - 1 && rsi[j1] > 60)
                    add(-1, "div", 2, "regular bearish RSI divergence (higher high, RSI ${r1(rsi[j1])}→${r1(rsi[j2])})", p.price + 0.25 * a)
                else if (p.price < q.price && rsi[j2] > rsi[j1] + 1 && trend == -1)
                    add(-1, "div", 2, "hidden bearish RSI divergence (lower high, RSI ${r1(rsi[j1])}→${r1(rsi[j2])})", p.price + 0.25 * a)
            }
        }

        // 2) support / resistance: 2+ swing points (highs or lows) within 0.5 ATR
        val levels = srLevels(rec.filter { it.conf <= i - 1 }.map { it.price }, a)
        val sup = levels.filter { l[i] <= it + 0.3 * a && c[i] > it && c[i] - it < 1.5 * a }
        val res = levels.filter { h[i] >= it - 0.3 * a && c[i] < it && it - c[i] < 1.5 * a }
        sup.maxOrNull()?.let { add(1, "sr", 2, "bounce off support ${Advisor.f(it)}", it - 0.75 * a) }
        res.minOrNull()?.let { add(-1, "sr", 2, "rejected at resistance ${Advisor.f(it)}", it + 0.75 * a) }

        // 3) rectangle range fade
        for (p in active) {
            if (p.kind != "rectangle") continue
            val up = p.up?.at(i) ?: continue; val dn = p.dn?.at(i) ?: continue
            val mid = (up + dn) / 2
            if (l[i] <= dn + 0.3 * a && c[i] > dn && c[i] < mid)
                add(1, "range", 2, "range fade: buy rectangle support ${Advisor.f(dn)}", dn - 1.0 * a, up - 0.1 * p.height)
            if (h[i] >= up - 0.3 * a && c[i] > mid && c[i] < up)
                add(-1, "range", 2, "range fade: sell rectangle resistance ${Advisor.f(up)}", up + 1.0 * a, dn + 0.1 * p.height)
        }

        if (cands.size != 1) return null
        val (side, d) = cands.entries.first()
        var s = d.pts
        val why = ArrayList(d.why)
        when (trend) {
            side -> { s += 1; why += "+ higher-timeframe trend agrees" }
            -side -> { s -= 2; why += "- higher-timeframe trend is against it" }
            else -> why += "· higher-timeframe trend sideways"
        }
        if ((side == 1 && rsi[i] < 35) || (side == -1 && rsi[i] > 65)) { s += 1; why += "+ RSI ${r1(rsi[i])} (${if (side == 1) "oversold" else "overbought"})" }
        val bodyOk = if (side == 1) c[i] > o[i] && c[i] > (h[i] + l[i]) / 2 else c[i] < o[i] && c[i] < (h[i] + l[i]) / 2
        if (bodyOk) { s += 1; why += "+ ${if (side == 1) "bullish" else "bearish"} confirmation candle" }
        val pb = active.map { it.bias }.filter { it != 0 }
        if (side in pb) { s += 1; why += "+ chart pattern points the same way (${active.first { it.bias == side }.name})" }
        else if (-side in pb) { s -= 1; why += "- chart pattern points the other way (${active.first { it.bias == -side }.name})" }

        val e = c[i]                                   // next bar opens ~here
        val sl = d.sl ?: return null
        val risk = (e - sl) * side
        if (risk < 0.5 * a || risk > 3.5 * a) return null
        val tp = d.tp ?: (e + side * 2 * risk)
        if ((tp - e) * side < 1.5 * risk) return null
        return Setup(side, d.kinds.distinct().sorted(), s, e, sl, tp, why, cd.openTime[i])
    }

    /** Greedy groups of sorted prices within 0.5 ATR of the group's first price; groups of 2+ -> level (mean). */
    fun srLevels(prices0: List<Double>, a: Double): List<Double> {
        val prices = prices0.sorted()
        val out = ArrayList<Double>()
        val g = ArrayList<Double>()
        for (x in prices) {
            if (g.isNotEmpty() && x - g[0] > 0.5 * a) {
                if (g.size >= 2) out += g.average()
                g.clear()
            }
            g += x
        }
        if (g.size >= 2) out += g.average()
        return out
    }
}
