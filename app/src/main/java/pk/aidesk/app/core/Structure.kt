package pk.aidesk.app.core

import kotlin.math.abs
import kotlin.math.max

/**
 * Market structure 15m (HH / HL / LH / LL), same rules as the laptop backtest AI Desk/structure/btc_structure.py.
 *
 * Swing high (low) = a 15m bar whose high (low) is the highest (lowest) of k bars on each side; known k bars later.
 * Uptrend = last two swing highs AND lows rising (HH + HL); downtrend = both falling (LH + LL).
 *  BOS   continuation: uptrend + first close above the last swing high -> LONG, stop = last HL;
 *                      downtrend + first close below the last swing low -> SHORT, stop = last LH.
 *  CHOCH reversal:     downtrend + first close above the last LH -> LONG, stop = last LL;
 *                      uptrend + first close below the last HL -> SHORT, stop = last HH.
 *  PULL  pullback:     new HL confirmed while highs rise -> LONG, stop = HL - 0.1 ATR; new LH while lows fall -> SHORT.
 * Entry next bar open; stop 0.15%..5% from the entry; time exit after 96 bars (24 h). Optional 4h EMA50/200 trend filter.
 * BTC backtest (Nov 23 - Nov 26, after costs): close-all at 1R loses 0.08-0.32R per trade for every setup with k=5;
 * only CHOCH SHORT, k=10, 4h downtrend, TP 2R was positive in both periods (+0.04R / +0.07R, 74 / 44 trades).
 */
object Structure {
    const val M15 = 900_000L
    const val H4 = 14_400_000L
    const val HOLD = 96
    const val MIN_RISK = 0.0015
    const val MAX_RISK = 0.05
    const val BACKTEST = "BTC 15m backtest Nov 23–Nov 26 after costs: close-all at 1R loses −0.08 to −0.32R per trade for every setup (k=5). " +
        "108 variants: only CHOCH SHORT · k=10 · 4h downtrend · TP 2R positive in both periods (+0.04R / +0.07R per trade). Not proven."

    class Sig(val i: Int, val side: Int, val setup: String, val sl: Double)

    class Entry(val symbol: String, val sig: Sig, val time: Long, val entry: Double, val sl: Double, val tps: List<Double>,
                val result: String?, val tpHit: Int, val trend: Int) {
        val side get() = sig.side
        val sideName get() = if (sig.side == 1) "LONG" else "SHORT"
        val id get() = "ms-$symbol-$time-${sig.side}"
    }

    /** Wilder ATR as pandas ewm(alpha=1/n, adjust=False) over the true range (first TR = high - low). */
    fun atr(h: DoubleArray, l: DoubleArray, c: DoubleArray, n: Int = 14): DoubleArray {
        val out = DoubleArray(c.size)
        val a = 1.0 / n
        for (i in c.indices) {
            val tr = if (i == 0) h[0] - l[0] else max(h[i] - l[i], max(abs(h[i] - c[i - 1]), abs(l[i] - c[i - 1])))
            out[i] = if (i == 0) tr else (1 - a) * out[i - 1] + a * tr
        }
        return out
    }

    /** All setups on CLOSED candles (btc_structure.signals). */
    fun signals(c: Candles, k: Int): List<Sig> {
        val h = c.high; val l = c.low; val cl = c.close; val n = c.size
        val a = atr(h, l, cl)
        val highs = ArrayList<Pair<Int, Double>>(); val lows = ArrayList<Pair<Int, Double>>()
        val out = ArrayList<Sig>()
        var brokeHi = -1; var brokeLo = -1
        for (i in 2 * k until n) {
            val p = i - k
            var newHigh = false; var newLow = false
            var rH = Double.NEGATIVE_INFINITY; var rL = Double.POSITIVE_INFINITY
            for (q in p - k..i) { rH = max(rH, h[q]); rL = minOf(rL, l[q]) }
            var lH = Double.NEGATIVE_INFINITY; var lL = Double.POSITIVE_INFINITY
            for (q in p - k until p) { lH = max(lH, h[q]); lL = minOf(lL, l[q]) }
            if (h[p] == rH && h[p] > lH) { highs += p to h[p]; newHigh = true }
            if (l[p] == rL && l[p] < lL) { lows += p to l[p]; newLow = true }
            if (highs.size < 2 || lows.size < 2) continue
            val h0 = highs[highs.size - 2].second; val h1 = highs.last().second
            val l0 = lows[lows.size - 2].second; val l1 = lows.last().second
            val up = h1 > h0 && l1 > l0
            val dn = h1 < h0 && l1 < l0
            if (cl[i] > h1 && cl[i - 1] <= h1 && brokeHi != highs.last().first) {
                brokeHi = highs.last().first
                if (up) out += Sig(i, 1, "BOS", l1) else if (dn) out += Sig(i, 1, "CHOCH", l1)
            }
            if (cl[i] < l1 && cl[i - 1] >= l1 && brokeLo != lows.last().first) {
                brokeLo = lows.last().first
                if (dn) out += Sig(i, -1, "BOS", h1) else if (up) out += Sig(i, -1, "CHOCH", h1)
            }
            if (newLow && l1 > l0 && h1 > h0 && cl[i] > l1) out += Sig(i, 1, "PULL", l1 - 0.1 * a[i])
            if (newHigh && h1 < h0 && l1 < l0 && cl[i] < h1) out += Sig(i, -1, "PULL", h1 + 0.1 * a[i])
        }
        return out
    }

    private fun ema(x: DoubleArray, span: Int): DoubleArray {
        val a = 2.0 / (span + 1); val out = DoubleArray(x.size)
        for (i in x.indices) out[i] = if (i == 0) x[0] else (1 - a) * out[i - 1] + a * x[i]
        return out
    }

    /** 4h EMA50/200 regime per 4h bar: +1 close>EMA200 & EMA50>EMA200, -1 both below, else 0 (first 205 bars 0). */
    fun regime4h(h4: Candles): IntArray {
        val c = h4.close; val e50 = ema(c, 50); val e200 = ema(c, 200)
        return IntArray(c.size) { i ->
            if (i < 205) 0 else if (c[i] > e200[i] && e50[i] > e200[i]) 1 else if (c[i] < e200[i] && e50[i] < e200[i]) -1 else 0
        }
    }

    /** Regime known at the close of a 15m bar that opened at t15: last 4h bar that closed at or before it. */
    fun regimeAt(h4: Candles, reg: IntArray, t15: Long): Int {
        val close = t15 + M15
        var j = -1
        for (q in h4.openTime.indices) if (h4.openTime[q] + H4 <= close) j = q else break
        return if (j >= 0) reg[j] else 0
    }

    /** Follow an entry on CLOSED bars: stop first, targets 1R..5R, time exit after HOLD bars. */
    private fun follow(c: Candles, closedN: Int, i: Int, side: Int, sl: Double, tps: List<Double>): Pair<String?, Int> {
        var hit = 0
        val last = minOf(closedN - 1, i + HOLD)
        for (j in i + 1..last) {
            if (if (side == 1) c.low[j] <= sl else c.high[j] >= sl) return "SL" to hit
            val fav = if (side == 1) c.high[j] else c.low[j]
            while (hit < 5 && (fav - tps[hit]) * side >= 0) hit++
            if (hit == 5) return "TP5" to 5
        }
        if (i + HOLD <= closedN - 1) return "TIME" to hit
        return null to hit
    }

    /**
     * Entries for one coin. all = 15m candles incl. the forming one (its open is the entry of a fresh signal);
     * signals only on closed bars; since = earliest entry time to return; h4 = 4h candles for the trend filter (null = off).
     */
    fun entries(symbol: String, all: Candles, now: Long, k: Int, setups: Set<String>, long: Boolean, short: Boolean,
                h4: Candles?, since: Long): List<Entry> {
        val closedN = all.closeTime.count { it < now }
        if (closedN < 4 * k + 20) return emptyList()
        val closed = Advisor.slice(all, 0, closedN)
        val reg = h4?.let { regime4h(it) }
        val out = ArrayList<Entry>()
        for (s in signals(closed, k)) {
            if (s.setup !in setups || (s.side == 1 && !long) || (s.side == -1 && !short)) continue
            if (s.i + 1 >= all.size) continue
            val t = all.openTime[s.i + 1]
            if (t < since) continue
            val trend = if (h4 != null && reg != null) regimeAt(h4, reg, all.openTime[s.i]) else 0
            if (h4 != null && trend != s.side) continue
            val e = all.open[s.i + 1]
            val risk = (e - s.sl) * s.side
            if (risk < MIN_RISK * e || risk > MAX_RISK * e) continue
            val tps = Targets.ladder(if (s.side == 1) "LONG" else "SHORT", e, s.sl)
            val (res, hit) = follow(all, closedN, s.i, s.side, s.sl, tps)
            out += Entry(symbol, s, t, e, s.sl, tps, res, hit, trend)
        }
        return out
    }
}
