package pk.aidesk.app.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * BTC "Up or Down 5m" - structure AI, a call at the START of every round.
 *
 * Port of Documents/BTC Structure 5m AI (structure.py + backtest.py):
 *  - HH / HL / LH / LL on 1m, 5m and 15m Binance SPOT candles (k=3 fractal swings, known k bars later: no repaint)
 *  - 35 features (per timeframe: HH/LH, HL/LL, trend, newest swing + age, break of the last swing, distance to the
 *    swings in ATR, last 1/3 bar moves, ATR) -> LightGBM P(UP) + projected 5m close / high / low (in 5m ATRs)
 *  - uses only candles closed by the round start T, so the call is fixed for the whole round
 * 6-month walk-forward backtest: P >= 56% -> ~47 calls/day, 53.8% wins; pays only if the share costs <= ~52c.
 */
object Btc5mStruct {
    const val MIN = 60_000L
    const val ROUND = 300_000L
    val TFS = listOf("m1" to 1, "m5" to 5, "m15" to 15)
    const val NF = 11                    // features per timeframe

    class Gbdt(val trees: List<Btc5m.Tree>) {
        fun raw(x: DoubleArray) = trees.sumOf { it.eval(x) }
    }

    class Bt(val thr: Double, val perDay: Double, val win: Double, val pnl50: Double, val pnl52: Double, val pnl55: Double,
             val dd: Double, val trades: Int)

    class Model(val up: Gbdt, val yClose: Gbdt, val yHigh: Gbdt, val yLow: Gbdt, val threshold: Double, val k: Int,
                val testStart: String, val testEnd: String, val days: Int, val backtest: List<Bt>,
                val calConf: DoubleArray = doubleArrayOf(0.5, 1.0), val calP: DoubleArray = doubleArrayOf(0.5, 0.5)) {
        fun pUp(x: DoubleArray) = 1.0 / (1.0 + exp(-up.raw(x)))
        fun bt(thr: Double) = backtest.minByOrNull { kotlin.math.abs(it.thr - thr) }
        /** Out-of-sample win rate of the called side at this model confidence (isotonic, 6-month backtest). */
        fun winProb(conf: Double): Double {
            if (conf <= calConf.first()) return calP.first()
            if (conf >= calConf.last()) return calP.last()
            var i = 0
            while (i < calConf.size - 2 && conf > calConf[i + 1]) i++
            val t = (conf - calConf[i]) / (calConf[i + 1] - calConf[i])
            return calP[i] + (calP[i + 1] - calP[i]) * t
        }
    }

    @Volatile private var cached: Model? = null
    fun model(ctx: android.content.Context): Model =
        cached ?: load(ctx.assets.open("btc5m_struct.json").bufferedReader().use { it.readText() }).also { cached = it }

    fun load(json: String): Model {
        val o = JSONObject(json)
        fun ints(a: JSONArray) = IntArray(a.length()) { a.getInt(it) }
        fun dbls(a: JSONArray) = DoubleArray(a.length()) { a.getDouble(it) }
        val ms = o.getJSONObject("models")
        fun gb(n: String): Gbdt {
            val ta = ms.getJSONArray(n)
            return Gbdt((0 until ta.length()).map { k ->
                val t = ta.getJSONObject(k)
                Btc5m.Tree(t.getInt("root"), ints(t.getJSONArray("f")), dbls(t.getJSONArray("t")), ints(t.getJSONArray("l")),
                    ints(t.getJSONArray("r")), dbls(t.getJSONArray("v")))
            })
        }
        val ba = o.getJSONArray("backtest")
        val bt = (0 until ba.length()).map { ba.getJSONObject(it) }.map {
            Bt(it.getDouble("thr"), it.getDouble("per_day"), it.getDouble("win"), it.getDouble("pnl50"), it.getDouble("pnl52"),
                it.getDouble("pnl55"), it.getDouble("dd"), it.getInt("trades"))
        }
        val cal = o.optJSONObject("calibration")
        return Model(gb("up"), gb("y_close"), gb("y_high"), gb("y_low"), o.getDouble("threshold"), o.getInt("swing_k"),
            o.getString("test_start"), o.getString("test_end"), o.getInt("days"), bt,
            cal?.let { dbls(it.getJSONArray("conf")) } ?: doubleArrayOf(0.5, 1.0), cal?.let { dbls(it.getJSONArray("p")) } ?: doubleArrayOf(0.5, 0.5))
    }

    /** OHLC bars of one timeframe (open times ascending). */
    class Bars(val t: LongArray, val o: DoubleArray, val h: DoubleArray, val l: DoubleArray, val c: DoubleArray, val tfMin: Int) {
        val size get() = t.size
        fun closeTime(i: Int) = t[i] + tfMin * MIN
        /** bars closed by time T */
        fun upTo(T: Long): Bars {
            var n = 0
            while (n < size && closeTime(n) <= T) n++
            return Bars(t.copyOf(n), o.copyOf(n), h.copyOf(n), l.copyOf(n), c.copyOf(n), tfMin)
        }
    }

    class Swing(val i: Int, val price: Double, val high: Boolean, val label: String)   // label HH/LH/HL/LL or "" (first)

    /** State after the last bar: the 11 features (structure.py order) + swings for the chart. */
    class Tf(val f: DoubleArray, val swings: List<Swing>) {
        val hiLab get() = f[0].toInt(); val loLab get() = f[1].toInt(); val trend get() = f[2].toInt()
        val ev get() = f[3].toInt(); val evAge get() = f[4].toInt(); val brk get() = f[5].toInt()
        fun text(): String {
            val hi = when (hiLab) { 1 -> "HH"; -1 -> "LH"; else -> "?" }
            val lo = when (loLab) { 1 -> "HL"; -1 -> "LL"; else -> "?" }
            return "$hi+$lo"
        }
        val trendName get() = when (trend) { 1 -> "BULL"; -1 -> "BEAR"; else -> "MIXED" }
        val evName get() = when (ev) { 2 -> "HH"; 1 -> "HL"; -1 -> "LH"; -2 -> "LL"; else -> "-" }
    }

    /** structure.py structure(): k-bar fractals, labels from the last two swings, value at the LAST bar. */
    fun structure(b: Bars, k: Int): Tf {
        val n = b.size
        val h = b.h; val l = b.l; val c = b.c
        fun ph(i: Int): Boolean {
            if (i < k || i + k >= n) return false
            for (q in i - k until i) if (!(h[i] > h[q])) return false
            for (q in i + 1..i + k) if (!(h[i] >= h[q])) return false
            return true
        }
        fun pl(i: Int): Boolean {
            if (i < k || i + k >= n) return false
            for (q in i - k until i) if (!(l[i] < l[q])) return false
            for (q in i + 1..i + k) if (!(l[i] <= l[q])) return false
            return true
        }
        var lastH = Double.NaN; var prevH = Double.NaN; var lastL = Double.NaN; var prevL = Double.NaN
        var ev = 0; var evAt = -1_000_000_000
        val sw = ArrayList<Swing>()
        for (j in 0 until n) {
            val i = j - k
            if (i >= 0) {
                if (ph(i)) {
                    prevH = lastH; lastH = h[i]
                    if (!prevH.isNaN()) { ev = if (lastH > prevH) 2 else -1; evAt = j }
                    sw += Swing(i, h[i], true, if (prevH.isNaN()) "" else if (lastH > prevH) "HH" else "LH")
                }
                if (pl(i)) {
                    prevL = lastL; lastL = l[i]
                    if (!prevL.isNaN()) { ev = if (lastL > prevL) 1 else -2; evAt = j }
                    sw += Swing(i, l[i], false, if (prevL.isNaN()) "" else if (lastL > prevL) "HL" else "LL")
                }
            }
        }
        val j = n - 1
        // pandas rolling(14, min_periods=5).mean() of the range
        val m = min(14, j + 1)
        var a = Double.NaN
        if (m >= 5) { var s = 0.0; for (q in j - m + 1..j) s += h[q] - l[q]; a = s / m }
        if (!(a > 0)) a = Double.NaN
        fun z(v: Double) = if (v.isNaN()) 0.0 else v
        val hl = if (prevH.isNaN()) 0 else if (lastH > prevH) 1 else -1
        val ll = if (prevL.isNaN()) 0 else if (lastL > prevL) 1 else -1
        val f = DoubleArray(NF)
        f[0] = hl.toDouble(); f[1] = ll.toDouble()
        f[2] = if (hl == 1 && ll == 1) 1.0 else if (hl == -1 && ll == -1) -1.0 else 0.0
        f[3] = ev.toDouble()
        f[4] = min(j - evAt, 200).toDouble()
        f[5] = if (!lastH.isNaN() && c[j] > lastH) 1.0 else if (!lastL.isNaN() && c[j] < lastL) -1.0 else 0.0
        f[6] = if (lastH.isNaN()) 0.0 else z((c[j] - lastH) / a)
        f[7] = if (lastL.isNaN()) 0.0 else z((c[j] - lastL) / a)
        f[8] = if (j >= 1) z((c[j] - c[j - 1]) / a) else 0.0
        f[9] = if (j >= 3) z((c[j] - c[j - 3]) / a) else 0.0
        f[10] = if (a == a) a / c[j] * 1e4 else 0.0
        return Tf(f, sw)
    }

    class Call(
        val now: Long, val roundStart: Long, val open: Double, val price: Double,
        val pUp: Double, val threshold: Double,
        val projClose: Double, val projHigh: Double, val projLow: Double,
        val tf: Map<String, Tf>,
        val bars5: Bars, val forming5: DoubleArray?,          // closed 5m bars (for the chart) + forming bar o/h/l/c with the live price
    ) {
        val side get() = if (pUp >= threshold) "UP" else if (pUp <= 1 - threshold) "DOWN" else "NONE"
        val conf get() = if (pUp >= 0.5) pUp else 1 - pUp
        /** backtest win rate for this confidence (set by evaluate) */
        var winP: Double = 0.5
        val leftMs get() = roundStart + ROUND - now
        val moveBps get() = (price / open - 1) * 1e4
    }

    /** Pure evaluation (testable). tf = bars per timeframe (may include the forming bar; it is ignored). */
    fun evaluate(m: Model, tf: Map<String, Bars>, now: Long, livePrice: Double, threshold: Double = m.threshold): Call {
        val T = now / ROUND * ROUND
        val (x, st) = featureVector(tf, T, m.k)
        val p = m.pUp(x)
        val b5 = tf.getValue("m5")
        val fi = b5.t.indexOfLast { it == T }
        val open = if (fi >= 0) b5.o[fi] else tf.getValue("m1").let { b1 -> b1.t.indexOfLast { it == T }.let { if (it >= 0) b1.o[it] else livePrice } }
        val atr = st.getValue("m5").f[10] / 1e4 * open
        val form = if (fi >= 0) doubleArrayOf(b5.o[fi], maxOf(b5.h[fi], livePrice), minOf(b5.l[fi], livePrice), livePrice) else null
        return Call(now, T, open, livePrice, p, threshold,
            open + m.yClose.raw(x) * atr, open + m.yHigh.raw(x) * atr, open + m.yLow.raw(x) * atr, st, b5.upTo(T), form)
            .also { it.winP = m.winProb(it.conf) }
    }

    /** 36 features for round T from bars of each TF (only bars closed by T are used). Order = structure.FEATURES. */
    fun featureVector(tf: Map<String, Bars>, T: Long, k: Int): Pair<DoubleArray, Map<String, Tf>> {
        val st = TFS.associate { (n, _) -> n to structure(tf.getValue(n).upTo(T), k) }
        val x = DoubleArray(NF * 3 + 3)
        TFS.forEachIndexed { q, (n, _) -> st.getValue(n).f.copyInto(x, q * NF) }
        x[NF * 3] = (st.getValue("m1").trend + st.getValue("m5").trend + st.getValue("m15").trend).toDouble()
        val hr = ((T / 3_600_000L) % 24).toDouble()
        x[NF * 3 + 1] = sin(2 * PI * hr / 24)
        x[NF * 3 + 2] = cos(2 * PI * hr / 24)
        return x to st
    }

    private fun bars(api: Binance, interval: String, tfMin: Int): Bars {
        val a = api.request("GET", "/api/v3/klines", mapOf("symbol" to Btc5m.SYMBOL, "interval" to interval, "limit" to 300), host = Binance.SPOT) as JSONArray
        val r = (0 until a.length()).map { a.getJSONArray(it) }
        return Bars(LongArray(r.size) { r[it].getLong(0) }, DoubleArray(r.size) { r[it].getString(1).toDouble() },
            DoubleArray(r.size) { r[it].getString(2).toDouble() }, DoubleArray(r.size) { r[it].getString(3).toDouble() },
            DoubleArray(r.size) { r[it].getString(4).toDouble() }, tfMin)
    }

    /** Download 1m / 5m / 15m Binance SPOT candles + live price and evaluate the current round. Blocking. */
    fun scan(api: Binance, m: Model, threshold: Double = m.threshold): Call {
        val now = api.nowMs()
        val tf = mapOf("m1" to bars(api, "1m", 1), "m5" to bars(api, "5m", 5), "m15" to bars(api, "15m", 15))
        for ((n, b) in tf) for (q in 1 until b.size) if (b.t[q] - b.t[q - 1] != b.tfMin * MIN) throw IllegalStateException("gap in BTC $n bars")
        val px = (api.request("GET", "/api/v3/ticker/price", mapOf("symbol" to Btc5m.SYMBOL), host = Binance.SPOT) as JSONObject)
            .getString("price").toDouble()
        return evaluate(m, tf, now, px, threshold)
    }
}
