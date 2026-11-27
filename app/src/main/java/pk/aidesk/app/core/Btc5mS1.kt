package pk.aidesk.app.core

import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 1-second model features (market eth5s). Port of Documents/BTC Structure 5m AI: s1_feat.py (table) + export_s1.py (model).
 * The call is made D = 10 s after the round start T from Binance SPOT 1 s klines: the last LOOKBACK s before T and the first D s
 * of the round. Missing seconds (no trade) carry the last close forward with volume 0, like the research data.
 * 19 features, same order as export_s1.py FE. Verified against Python in S1ParityTest.
 */
object Btc5mS1 {
    const val D = 10
    const val LOOKBACK = 1900

    /** per-second arrays for the seconds [start, start + c.size); start in epoch seconds */
    class Sec(val start: Long, val c: DoubleArray, val v: DoubleArray, val tb: DoubleArray)

    /** 1 s klines for [T/1000 - LOOKBACK, T/1000 + D): closes forward-filled, volume / taker-buy 0 for empty seconds. */
    fun fetch(api: Binance, sym: String, T: Long): Sec {
        val s0 = T / 1000 - LOOKBACK
        val n = LOOKBACK + D
        val c = DoubleArray(n) { Double.NaN }; val v = DoubleArray(n); val tb = DoubleArray(n)
        var cur = s0 * 1000
        val end = (T / 1000 + D) * 1000 - 1
        var pages = 0
        while (cur <= end && pages++ < 6) {
            val a = api.request("GET", "/api/v3/klines", mapOf("symbol" to sym, "interval" to "1s", "startTime" to cur, "endTime" to end,
                "limit" to 1000), host = Binance.SPOT) as JSONArray
            if (a.length() == 0) break
            var last = cur
            for (q in 0 until a.length()) {
                val r = a.getJSONArray(q)
                val t = r.getLong(0)
                val i = (t / 1000 - s0).toInt()
                if (i < 0 || i >= n) continue
                c[i] = r.getString(4).toDouble(); v[i] = r.getString(5).toDouble(); tb[i] = r.getString(9).toDouble()
                last = t
            }
            cur = last + 1000
            if (a.length() < 1000) break
        }
        return Sec(s0, fill(c), v, tb)
    }

    /** forward-fill; seconds before the first trade take the first close */
    fun fill(c: DoubleArray): DoubleArray {
        val first = c.indexOfFirst { !it.isNaN() }
        if (first < 0) throw IllegalStateException("no 1 s data")
        for (i in 0 until first) c[i] = c[first]
        for (i in first + 1 until c.size) if (c[i].isNaN()) c[i] = c[i - 1]
        return c
    }

    /** 19 features for the round starting at T (ms); c1h = close of the 1m bar that started T - 3660 s (= price at T - 3601 s). null = data incomplete. */
    fun features(s: Sec, T: Long, c1h: Double): DoubleArray? {
        val ix = (T / 1000 - s.start).toInt()                 // index of the second T; bars ix-1 and before are the pre-round price
        if (ix < LOOKBACK || s.c.size < ix + D) return null
        val c = s.c
        for (i in ix - LOOKBACK until ix + D) if (!(c[i] > 0.0)) return null
        var sum = 0.0
        for (i in ix - 60 until ix) sum += c[i]
        val S = sum / 60                                       // the 60 s average before the start (the TWAP "price to beat")
        val pT = c[ix - 1]; val pN = c[ix + D - 1]
        val gap0 = (pT / S - 1) * 1e4; val gapD = (pN / S - 1) * 1e4; val moveD = (pN / pT - 1) * 1e4
        fun ret(i: Int) = ln(c[i] / c[i - 1]) * 1e4
        fun sd(w: Int): Double {
            var m = 0.0
            for (i in ix - w until ix) m += ret(i)
            m /= w
            var q = 0.0
            for (i in ix - w until ix) { val d = ret(i) - m; q += d * d }
            return sqrt(q / w)
        }
        val v1 = sd(60); val v5 = sd(300); val v30 = sd(1800)
        if (!(v30 > 0.0)) return null
        val need = v5 * sqrt(250.0) + 1e-9
        fun slope(w: Int): Double {
            val mid = (w - 1) / 2.0
            var num = 0.0; var den = 0.0
            for (i in 0 until w) { val k = i - mid; num += k * c[ix - w + i]; den += k * k }
            return num / den / pT * 1e4
        }
        val j = ix + D - 1
        fun imb(w: Int): Double {
            var vs = 0.0; var tbs = 0.0
            for (i in j - w + 1..j) { vs += s.v[i]; tbs += s.tb[i] }
            return if (vs > 0) 2 * tbs / vs - 1 else 0.0
        }
        val x = doubleArrayOf(gap0, gapD, moveD, v1, v5, v30, v1 / v30, v5 / v30, gapD / need, gap0 / need, slope(60), slope(300),
            imb(10), imb(30), imb(60), imb(300), (pT / c[ix - 301] - 1) * 1e4, (pT / c1h - 1) * 1e4, ((T / 3_600_000L) % 24).toDouble())
        for (i in x.indices) if (!x[i].isFinite()) return null
        return x
    }

    /** largest relative difference helper for tests */
    fun maxRel(a: DoubleArray, b: DoubleArray): Double {
        var m = 0.0
        for (i in a.indices) m = maxOf(m, abs(a[i] - b[i]) / maxOf(1.0, abs(b[i])))
        return m
    }
}
