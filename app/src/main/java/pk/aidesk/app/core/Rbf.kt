package pk.aidesk.app.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Ahmed Tlogs "15m Gold Concept": Retest + Breaker block + FVG on XAUUSDT 15m, entry on the first 5m candle in the trade
 * direction after price comes back into the FVG. Same rules as the laptop backtest AI Desk/xaulab/rbf_lab.py (video
 * variant: breaker required, no fib, no trend filter, stop above/below the 3 FVG candles + 0.1 ATR).
 * Backtest after costs: -0.61R (2024-25 PAXG), -0.57R (Dec 25-Jun 26), -0.64R (Jul-Nov 26 holdout) per trade at 1:3.
 */
/** TP1..TP5 at 1R..5R from the entry (R = entry-to-stop distance): every signal and strategy trade uses these. */
object Targets {
    fun ladder(side: String, entry: Double, sl: Double): List<Double> {
        val d = if (side == "LONG") 1 else -1
        val r = abs(entry - sl)
        return (1..5).map { entry + d * it * r }
    }
}

object Rbf {
    const val SYMBOL = "XAUUSDT"
    const val M5 = 300_000L
    const val M15 = 900_000L
    private const val NPIV = 3
    private const val EXPIRE = 48            // 15m bars a setup waits after it is ready (12 h)
    const val HOLD5 = 288                    // 5m bars max in a trade (24 h)
    const val BACKTEST = "Backtest 15m gold, after costs, 1:3 target: −0.61R (2024-25), −0.57R (Dec 25–Jun 26), −0.64R (Jul–Nov 26) per trade. " +
        "0 of 32 variants positive. TP1 (1R) reached by 49% of signals before the stop."

    class Setup(val side: Int, val ready: Long, val level: Double, val fvgLo: Double, val fvgHi: Double, val sl: Double,
                val legExt: Double, val legEnd: Double, val expire: Long, val i15: Int)

    class Entry(val setup: Setup, val entryTime: Long, val entry: Double, val sl: Double, val tps: List<Double>,
                val result: String?, val tpHit: Int, val exitPx: Double?) {
        val side get() = setup.side
        val sideName get() = if (setup.side == 1) "LONG" else "SHORT"
        val id get() = "rbf-${setup.ready}-${setup.side}"
    }

    class View(val price: Double, val setups: List<Setup>, val waiting: List<Setup>, val entries: List<Entry>, val at: Long)

    /** Complete setups on closed 15m candles (rbf_lab.setups15, video variant). */
    fun setups15(c: Candles): List<Setup> {
        val o = c.open; val h = c.high; val l = c.low; val cl = c.close; val t = c.openTime
        val n = c.size
        val atr = Patterns.atr(h, l, cl, 14)
        val out = ArrayList<Setup>()
        val lows = ArrayList<Pair<Int, Double>>(); val highs = ArrayList<Pair<Int, Double>>()
        for (i in NPIV * 2 until n) {
            val p = i - NPIV
            var lmin = Double.POSITIVE_INFINITY; var hmax = Double.NEGATIVE_INFINITY
            for (q in p - NPIV until p) { lmin = min(lmin, l[q]); hmax = max(hmax, h[q]) }
            var lmin2 = Double.POSITIVE_INFINITY; var hmax2 = Double.NEGATIVE_INFINITY
            for (q in p + 1..i) { lmin2 = min(lmin2, l[q]); hmax2 = max(hmax2, h[q]) }
            if (l[p] < lmin && l[p] <= lmin2) lows += p to l[p]
            if (h[p] > hmax && h[p] >= hmax2) highs += p to h[p]
            val a = atr[i]
            if (!(a > 0)) continue
            for ((side, pts) in listOf(-1 to lows, 1 to highs)) {
                val broken = pts.filter { (cl[i] - it.second) * side > 0 }
                if (broken.isEmpty()) continue
                pts.removeAll(broken.toSet())
                val (pb, lvl) = broken.maxWith(compareBy<Pair<Int, Double>> { it.first }.thenBy { it.second })
                if (pb + 1 > i) continue
                var hb = pb + 1
                for (q in pb + 1..i) if (if (side == -1) h[q] > h[hb] else l[q] < l[hb]) hb = q
                val H = if (side == -1) h[hb] else l[hb]
                if (side == -1 && H - lvl < 0.5 * a) continue
                if (side == 1 && lvl - H < 0.5 * a) continue
                var br: Pair<Double, Double>? = null
                var qb = pb
                while (qb > max(pb - 4, -1)) { if ((cl[qb] - o[qb]) * side > 0) { br = l[qb] to h[qb]; break }; qb-- }
                if (br == null) continue
                var bestKey = Double.NEGATIVE_INFINITY; var bestK = -1; var bz = 0.0 to 0.0
                for (k in hb + 2..min(i + 2, n - 1)) {
                    val z = if (side == -1 && l[k - 2] - h[k] >= 0.1 * a) h[k] to l[k - 2]
                        else if (side == 1 && l[k] - h[k - 2] >= 0.1 * a) h[k - 2] to l[k] else continue
                    val dLvl = max(0.0, max(z.first - lvl, lvl - z.second))
                    if (dLvl > 1.0 * a) continue
                    if (max(0.0, max(z.first - br.second, br.first - z.second)) > 1.0 * a) continue
                    val key = if (side == -1) z.second else -z.first
                    if (bestK < 0 || key > bestKey) { bestKey = key; bestK = k; bz = z }
                }
                if (bestK < 0) continue
                val k = bestK
                val readyI = min(i + 2, n - 1)
                val sl = if (side == -1) max(h[k - 2], max(h[k - 1], h[k])) + 0.1 * a else min(l[k - 2], min(l[k - 1], l[k])) - 0.1 * a
                var ll = if (side == -1) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
                for (q in hb..readyI) ll = if (side == -1) min(ll, l[q]) else max(ll, h[q])
                out += Setup(side, t[readyI] + M15, lvl, bz.first, bz.second, sl, H, ll, t[readyI] + M15 + EXPIRE * M15, readyI)
            }
        }
        return out.sortedBy { it.ready }
    }

    /** rbf_lab.trigger5 (fib off): index of the 5m entry candle, or null. c5 may include the forming candle as its last element. */
    fun trigger5(c5: Candles, s: Setup): Int? {
        var j = c5.openTime.indexOfFirst { it >= s.ready }
        if (j < 0) return null
        var touched = false
        while (j < c5.size - 1 && c5.openTime[j] < s.expire) {
            if (if (s.side == -1) c5.high[j] >= s.sl else c5.low[j] <= s.sl) return null
            if (s.side == -1) {
                if (c5.high[j] >= s.fvgLo) touched = true
                if (touched && c5.close[j] < c5.open[j]) return j + 1
            } else {
                if (c5.low[j] <= s.fvgHi) touched = true
                if (touched && c5.close[j] > c5.open[j]) return j + 1
            }
            j++
        }
        return null
    }

    /** Follow an entry on CLOSED 5m candles: stop first, targets 1R..5R, time exit after HOLD5 candles. */
    private fun follow(c5: Candles, closedN: Int, e: Int, side: Int, entry: Double, sl: Double, tps: List<Double>): Triple<String?, Int, Double?> {
        var hit = 0
        val last = min(closedN - 1, e + HOLD5)
        for (j in e..last) {
            if (if (side == 1) c5.low[j] <= sl else c5.high[j] >= sl) return Triple("SL", hit, sl)
            val fav = if (side == 1) c5.high[j] else c5.low[j]
            while (hit < 5 && (fav - tps[hit]) * side >= 0) hit++
            if (hit == 5) return Triple("TP5", 5, tps[4])
        }
        if (e + HOLD5 <= closedN - 1) return Triple("TIME", hit, c5.close[e + HOLD5])
        return Triple(null, hit, null)
    }

    /** Pure evaluation (testable): 15m closed candles, 5m candles incl. the forming one. */
    fun evaluate(c15: Candles, c5: Candles, now: Long): View {
        val sets = setups15(c15)
        val closedN = c5.closeTime.count { it < now }
        val entries = ArrayList<Entry>(); val waiting = ArrayList<Setup>()
        for (s in sets) {
            val e = trigger5(c5, s)
            if (e == null) {
                val lastT = c5.openTime[max(0, c5.size - 2)]
                // still waiting: not expired and the stop not traded through yet
                if (s.expire > now && c5.openTime.indices.none { c5.openTime[it] >= s.ready && it < c5.size - 1 &&
                        (if (s.side == -1) c5.high[it] >= s.sl else c5.low[it] <= s.sl) } && s.ready <= lastT + M5) waiting += s
                continue
            }
            val entry = c5.open[e]
            if (!((entry - s.sl) * s.side > entry * 0.0005)) continue
            val tps = Targets.ladder(if (s.side == 1) "LONG" else "SHORT", entry, s.sl)
            val (res, hit, px) = follow(c5, closedN, e, s.side, entry, s.sl, tps)
            entries += Entry(s, c5.openTime[e], entry, s.sl, tps, res, hit, px)
        }
        return View(c5.close.last(), sets, waiting, entries.sortedByDescending { it.entryTime }, now)
    }

    /** Fetch candles and evaluate. Blocking: call off the main thread. */
    fun scan(api: Binance, now: Long = api.nowMs()): View {
        val all15 = api.candles(SYMBOL, "15m", 500)
        val c15 = Advisor.slice(all15, 0, all15.closeTime.count { it < now })
        val c5 = api.candles(SYMBOL, "5m", 1000)
        return evaluate(c15, c5, now)
    }

    fun rOf(e: Entry, px: Double) = e.side * (px - e.entry) / abs(e.entry - e.sl)
}
