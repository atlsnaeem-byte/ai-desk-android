package pk.aidesk.app.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Port of features.py (pandas) to plain Kotlin. NaN plays the role of pandas' missing value, and
 * Kotlin's NaN comparisons are false, the same as pandas boolean comparisons with NaN.
 */
class Candles(
    val openTime: LongArray,
    val open: DoubleArray,
    val high: DoubleArray,
    val low: DoubleArray,
    val close: DoubleArray,
    val volume: DoubleArray,
    val takerBuyBase: DoubleArray,
    val closeTime: LongArray,
) {
    val size get() = openTime.size
}

object Features {
    val PATTERNS = linkedMapOf(
        "doji" to "Doji",
        "hammer" to "Hammer (after drop)",
        "shooting_star" to "Shooting star (after rise)",
        "bull_engulf" to "Bullish engulfing",
        "bear_engulf" to "Bearish engulfing",
        "morning_star" to "Morning star",
        "evening_star" to "Evening star",
        "inside_bar" to "Inside bar",
        "three_soldiers" to "Three white soldiers",
        "three_crows" to "Three black crows",
        "breakout_up" to "Breakout above 20-bar high",
        "breakout_down" to "Breakdown below 20-bar low",
        "struct_up" to "Higher highs + higher lows",
        "struct_down" to "Lower highs + lower lows",
        "squeeze" to "Bollinger squeeze",
    )
    val BTC_CTX = listOf("ret_6", "ret_24", "e20_e50", "rsi")
    private val NaN = Double.NaN

    // ---------------------------------------------------------------- pandas helpers
    /** Series.ewm(alpha=a, adjust=False).mean() with ignore_na=False. */
    fun ewmAlpha(x: DoubleArray, alpha: Double): DoubleArray {
        val out = DoubleArray(x.size) { NaN }
        var weighted = NaN
        var oldWt = 1.0
        val f = 1.0 - alpha
        for (i in x.indices) {
            val cur = x[i]
            val obs = !cur.isNaN()
            if (weighted.isNaN()) {
                if (obs) weighted = cur
            } else {
                oldWt *= f
                if (obs) {
                    if (weighted != cur) weighted = (oldWt * weighted + alpha * cur) / (oldWt + alpha)
                    oldWt = 1.0
                }
            }
            out[i] = weighted
        }
        return out
    }

    fun ema(x: DoubleArray, span: Int) = ewmAlpha(x, 2.0 / (span + 1))
    fun wilder(x: DoubleArray, n: Int) = ewmAlpha(x, 1.0 / n)

    fun diff(x: DoubleArray, n: Int) = DoubleArray(x.size) { i -> if (i >= n) x[i] - x[i - n] else NaN }
    fun shift(x: DoubleArray, n: Int) = DoubleArray(x.size) { i -> if (i >= n) x[i - n] else NaN }

    private inline fun rolling(x: DoubleArray, w: Int, fn: (DoubleArray) -> Double): DoubleArray {
        val out = DoubleArray(x.size) { NaN }
        val buf = DoubleArray(w)
        for (i in w - 1 until x.size) {
            var ok = true
            for (k in 0 until w) {
                val v = x[i - w + 1 + k]
                if (v.isNaN()) { ok = false; break }
                buf[k] = v
            }
            if (ok) out[i] = fn(buf)
        }
        return out
    }

    fun rollMean(x: DoubleArray, w: Int) = rolling(x, w) { b -> b.sum() / w }
    fun rollStd(x: DoubleArray, w: Int) = rolling(x, w) { b ->
        val m = b.sum() / w
        var s = 0.0
        for (v in b) s += (v - m) * (v - m)
        sqrt(s / (w - 1))
    }
    fun rollMin(x: DoubleArray, w: Int) = rolling(x, w) { b -> b.min() }
    fun rollMax(x: DoubleArray, w: Int) = rolling(x, w) { b -> b.max() }
    fun rollQuantile(x: DoubleArray, w: Int, q: Double) = rolling(x, w) { b ->
        val s = b.copyOf().also { it.sort() }
        val idx = q * (w - 1)
        val lo = floor(idx).toInt()
        val hi = min(lo + 1, w - 1)
        s[lo] + (idx - lo) * (s[hi] - s[lo])
    }

    private fun nz(v: Double) = if (v == 0.0) NaN else v

    // ---------------------------------------------------------------- indicators
    class Result(val f: LinkedHashMap<String, DoubleArray>, val atr: DoubleArray)

    fun indicators(df: Candles): Result {
        val n = df.size
        val o = df.open; val h = df.high; val l = df.low; val c = df.close; val v = df.volume
        val f = LinkedHashMap<String, DoubleArray>()
        val lc = DoubleArray(n) { ln(c[it]) }
        for (k in intArrayOf(1, 3, 6, 12, 24, 48)) f["ret_$k"] = diff(lc, k)

        val e20 = ema(c, 20); val e50 = ema(c, 50); val e200 = ema(c, 200)
        f["c_e20"] = DoubleArray(n) { c[it] / e20[it] - 1 }
        f["e20_e50"] = DoubleArray(n) { e20[it] / e50[it] - 1 }
        f["e50_e200"] = DoubleArray(n) { e50[it] / e200[it] - 1 }

        val d = diff(c, 1)
        val up = wilder(DoubleArray(n) { if (d[it].isNaN()) NaN else max(d[it], 0.0) }, 14)
        val dn = wilder(DoubleArray(n) { if (d[it].isNaN()) NaN else max(-d[it], 0.0) }, 14)
        f["rsi"] = DoubleArray(n) {
            val rsi = 100 - 100 / (1 + up[it] / nz(dn[it]))
            ((if (rsi.isNaN()) 50.0 else rsi) - 50) / 50
        }

        val e12 = ema(c, 12); val e26 = ema(c, 26)
        val macd = DoubleArray(n) { e12[it] - e26[it] }
        val sig = ema(macd, 9)
        f["macd_h"] = DoubleArray(n) { (macd[it] - sig[it]) / c[it] }

        val tr = DoubleArray(n) {
            val a = h[it] - l[it]
            if (it == 0) a else max(a, max(abs(h[it] - c[it - 1]), abs(l[it] - c[it - 1])))
        }
        val atr = wilder(tr, 14)
        f["atr_pct"] = DoubleArray(n) { atr[it] / c[it] }
        val atrM = rollMean(atr, 96)
        f["atr_ratio"] = DoubleArray(n) { atr[it] / atrM[it] - 1 }

        val m20 = rollMean(c, 20); val s20 = rollStd(c, 20)
        f["bb_b"] = DoubleArray(n) { (c[it] - m20[it]) / (2 * s20[it]) }
        val bw = DoubleArray(n) { 4 * s20[it] / m20[it] }
        f["bb_w"] = bw

        val upm = diff(h, 1)
        val dnm = DoubleArray(n) { if (it == 0) NaN else -(l[it] - l[it - 1]) }
        val pdm = DoubleArray(n) { if (upm[it] > dnm[it] && upm[it] > 0) upm[it] else 0.0 }
        val ndm = DoubleArray(n) { if (dnm[it] > upm[it] && dnm[it] > 0) dnm[it] else 0.0 }
        val wp = wilder(pdm, 14); val wn = wilder(ndm, 14)
        val pdi = DoubleArray(n) { 100 * wp[it] / atr[it] }
        val ndi = DoubleArray(n) { 100 * wn[it] / atr[it] }
        val dx = DoubleArray(n) {
            val x = 100 * abs(pdi[it] - ndi[it]) / nz(pdi[it] + ndi[it])
            if (x.isNaN()) 0.0 else x
        }
        val adx = wilder(dx, 14)
        f["adx"] = DoubleArray(n) { adx[it] / 50 }
        f["di_diff"] = DoubleArray(n) { (pdi[it] - ndi[it]) / 50 }

        val lo14 = rollMin(l, 14); val hi14 = rollMax(h, 14)
        f["stoch"] = DoubleArray(n) { (c[it] - lo14[it]) / nz(hi14[it] - lo14[it]) - 0.5 }
        val lo50 = rollMin(l, 50); val hi50 = rollMax(h, 50)
        f["range_pos"] = DoubleArray(n) { (c[it] - lo50[it]) / nz(hi50[it] - lo50[it]) - 0.5 }

        val lv = DoubleArray(n) { ln(nz(v[it])) }
        val lvM = rollMean(lv, 48); val lvS = rollStd(lv, 48)
        f["vol_z"] = DoubleArray(n) { (lv[it] - lvM[it]) / lvS[it] }
        val tb = DoubleArray(n) { df.takerBuyBase[it] / nz(v[it]) }
        f["taker_buy"] = DoubleArray(n) { tb[it] - 0.5 }
        val tb6 = rollMean(tb, 6)
        f["taker_buy_6"] = DoubleArray(n) { tb6[it] - 0.5 }

        val body = DoubleArray(n) { c[it] - o[it] }
        f["body"] = DoubleArray(n) { body[it] / nz(h[it] - l[it]) }
        f["upper_wick"] = DoubleArray(n) { (h[it] - max(o[it], c[it])) / nz(h[it] - l[it]) }
        f["lower_wick"] = DoubleArray(n) { (min(o[it], c[it]) - l[it]) / nz(h[it] - l[it]) }

        val hr = DoubleArray(n) {
            val msOfDay = Math.floorMod(df.openTime[it], 86_400_000L)
            val hh = msOfDay / 3_600_000L
            val mm = (msOfDay % 3_600_000L) / 60_000L
            hh + mm / 60.0
        }
        f["hour_sin"] = DoubleArray(n) { sin(2 * Math.PI * hr[it] / 24) }
        f["hour_cos"] = DoubleArray(n) { cos(2 * Math.PI * hr[it] / 24) }

        // ---- candlestick patterns
        val ab = DoubleArray(n) { abs(body[it]) }
        val pb = shift(body, 1); val pb2 = shift(body, 2)
        val po = shift(o, 1); val pcl = shift(c, 1)
        val rng0 = DoubleArray(n) { h[it] - l[it] }
        val upper = DoubleArray(n) { h[it] - max(o[it], c[it]) }
        val lower = DoubleArray(n) { min(o[it], c[it]) - l[it] }
        val d5 = shift(diff(lc, 5), 1)
        val rng1 = shift(rng0, 1); val rng2 = shift(rng0, 2)
        val o2 = shift(o, 2); val c2 = shift(c, 2)
        val h1 = shift(h, 1); val l1 = shift(l, 1)
        val bull = BooleanArray(n) { body[it] > 0.5 * rng0[it] }
        val bear = BooleanArray(n) { body[it] < -0.5 * rng0[it] }
        val hmax20 = shift(rollMax(h, 20), 1); val lmin20 = shift(rollMin(l, 20), 1)
        val hiA = rollMax(h, 10); val hiB = shift(hiA, 10)
        val loA = rollMin(l, 10); val loB = shift(loA, 10)
        val bwQ = rollQuantile(bw, 120, 0.1)

        val P = LinkedHashMap<String, BooleanArray>()
        P["doji"] = BooleanArray(n) { ab[it] <= 0.1 * rng0[it] }
        P["hammer"] = BooleanArray(n) { lower[it] >= 2 * ab[it] && upper[it] <= 0.25 * rng0[it] && d5[it] < 0 }
        P["shooting_star"] = BooleanArray(n) { upper[it] >= 2 * ab[it] && lower[it] <= 0.25 * rng0[it] && d5[it] > 0 }
        P["bull_engulf"] = BooleanArray(n) { pb[it] < 0 && body[it] > 0 && o[it] <= pcl[it] && c[it] >= po[it] && ab[it] > abs(pb[it]) }
        P["bear_engulf"] = BooleanArray(n) { pb[it] > 0 && body[it] < 0 && o[it] >= pcl[it] && c[it] <= po[it] && ab[it] > abs(pb[it]) }
        P["morning_star"] = BooleanArray(n) {
            val mid2 = (o2[it] + c2[it]) / 2
            pb2[it] < 0 && abs(pb2[it]) > 0.6 * rng2[it] && abs(pb[it]) < 0.3 * rng1[it] && body[it] > 0 && c[it] > mid2
        }
        P["evening_star"] = BooleanArray(n) {
            val mid2 = (o2[it] + c2[it]) / 2
            pb2[it] > 0 && abs(pb2[it]) > 0.6 * rng2[it] && abs(pb[it]) < 0.3 * rng1[it] && body[it] < 0 && c[it] < mid2
        }
        P["inside_bar"] = BooleanArray(n) { h[it] < h1[it] && l[it] > l1[it] }
        P["three_soldiers"] = BooleanArray(n) {
            it >= 2 && bull[it] && bull[it - 1] && bull[it - 2] && c[it] > pcl[it] && pcl[it] > c2[it]
        }
        P["three_crows"] = BooleanArray(n) {
            it >= 2 && bear[it] && bear[it - 1] && bear[it - 2] && c[it] < pcl[it] && pcl[it] < c2[it]
        }
        P["breakout_up"] = BooleanArray(n) { c[it] > hmax20[it] }
        P["breakout_down"] = BooleanArray(n) { c[it] < lmin20[it] }
        P["struct_up"] = BooleanArray(n) { hiA[it] > hiB[it] && loA[it] > loB[it] }
        P["struct_down"] = BooleanArray(n) { hiA[it] < hiB[it] && loA[it] < loB[it] }
        P["squeeze"] = BooleanArray(n) { bw[it] <= bwQ[it] }
        for (k in PATTERNS.keys) {
            val b = P.getValue(k)
            f["pat_$k"] = DoubleArray(n) { if (b[it]) 1.0 else 0.0 }
        }
        return Result(f, atr)
    }

    /** BTC context keyed by bar open time, like btc_context() + the left merge in build_symbol(). */
    fun btcContext(btc: Candles): Map<Long, DoubleArray> {
        val r = indicators(btc)
        val cols = BTC_CTX.map { r.f.getValue(it) }
        return (0 until btc.size).associate { i -> btc.openTime[i] to DoubleArray(cols.size) { cols[it][i] } }
    }

    /** Feature values for row i, by name (includes btc_* when ctx is given). */
    fun row(r: Result, df: Candles, i: Int, ctx: Map<Long, DoubleArray>?): Map<String, Double> {
        val m = HashMap<String, Double>()
        for ((k, a) in r.f) m[k] = a[i]
        if (ctx != null) {
            val b = ctx[df.openTime[i]]
            BTC_CTX.forEachIndexed { j, k -> m["btc_$k"] = b?.get(j) ?: NaN }
        }
        return m
    }

    fun patternsAt(r: Result, i: Int): List<String> =
        PATTERNS.keys.filter { (r.f.getValue("pat_$it")[i]) > 0 }
}
