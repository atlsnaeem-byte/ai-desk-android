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

    /**
     * A Binance Prediction "Up or Down" market the Predict tab can follow (dropdown). Each has its own model asset and its
     * own record / settings files (prefix). Backtests 2026-11-23 (Documents/BTC Structure 5m AI/multi_lab.py, 180 days):
     * BTC/ETH 5m are the usable ones; 15m is weaker; BNB (5m/15m) and all 1h rounds had no edge and are not offered.
     */
    data class Mk(val id: String, val symbol: String, val coin: String, val roundMin: Int, val asset: String, val prefix: String,
                  val weak: Boolean = false) {
        val round get() = roundMin * MIN
        val label get() = "$coin ${roundMin}m"
        /** timeframe whose candles the chart shows / the projected candle is scaled by */
        val tf get() = if (roundMin == 15) "m15" else "m5"
        val webUrl get() = "https://www.binance.com/en/prediction/market-detail/${coin.lowercase()}-up-or-down-${roundMin}m"
    }
    val MARKETS = listOf(
        Mk("btc5", "BTCUSDT", "BTC", 5, "btc5m_struct.json", "btc5ms"),
        Mk("eth5", "ETHUSDT", "ETH", 5, "pred_eth5.json", "pred_eth5"),
            )
    val BTC5 get() = MARKETS[0]
    fun mk(id: String?) = MARKETS.firstOrNull { it.id == id } ?: MARKETS[0]

    class Gbdt(val trees: List<Btc5m.Tree>) {
        fun raw(x: DoubleArray) = trees.sumOf { it.eval(x) }
    }

    class Bt(val thr: Double, val perDay: Double, val win: Double, val pnl50: Double, val pnl52: Double, val pnl55: Double,
             val dd: Double, val trades: Int, val pnl54: Double = Double.NaN)

    class Model(val up: Gbdt, val yClose: Gbdt, val yHigh: Gbdt, val yLow: Gbdt, val threshold: Double, val k: Int,
                val testStart: String, val testEnd: String, val days: Int, val backtest: List<Bt>,
                val calConf: DoubleArray = doubleArrayOf(0.5, 1.0), val calP: DoubleArray = doubleArrayOf(0.5, 0.5),
                val flow: Boolean = false, val atrTf: String = "m5",
                /** TWAP model (2026-11-25): + start gap features, label = Polymarket TWAP rule; call at the round start */
                val gap: Boolean = false, val btPx: IntArray = intArrayOf(50, 52, 54, 55)) {
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

    private val cached = java.util.concurrent.ConcurrentHashMap<String, Model>()
    fun cachedModel(mk: Mk): Model? = cached[mk.asset]
    fun model(ctx: android.content.Context, mk: Mk = BTC5): Model =
        cached[mk.asset] ?: load(ctx.assets.open(mk.asset).bufferedReader().use { it.readText() }).also { cached[mk.asset] = it }

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
                it.getDouble("pnl55"), it.getDouble("dd"), it.getInt("trades"), it.optDouble("pnl54"))
        }
        val cal = o.optJSONObject("calibration")
        return Model(gb("up"), gb("y_close"), gb("y_high"), gb("y_low"), o.getDouble("threshold"), o.getInt("swing_k"),
            o.getString("test_start"), o.getString("test_end"), o.getInt("days"), bt,
            cal?.let { dbls(it.getJSONArray("conf")) } ?: doubleArrayOf(0.5, 1.0), cal?.let { dbls(it.getJSONArray("p")) } ?: doubleArrayOf(0.5, 0.5),
            o.optBoolean("flow", false), o.optString("atr_tf", "m5"), o.optBoolean("gap", false),
            o.optJSONArray("bt_px")?.let { ints(it) } ?: intArrayOf(50, 52, 54, 55))
    }

    /**
     * Polymarket 5m / 15m Up-Down settlement since 2026-08-07 00:00 UTC (Chainlink "TWAP-60s" stream):
     * UP if the average price of the round's LAST 60 s >= the average price of the 60 s BEFORE the round start.
     * Before that date: UP if the end price >= the start price.
     */
    const val TWAP_FROM = 1_786_060_800_000L
    /** typical price of a 1m bar = 60 s average proxy (export_twap.py uses the same) */
    fun tp(b: Bars, i: Int) = (b.h[i] + b.l[i] + b.c[i]) / 3

    /** OHLC bars of one timeframe (open times ascending). */
    class Bars(val t: LongArray, val o: DoubleArray, val h: DoubleArray, val l: DoubleArray, val c: DoubleArray, val tfMin: Int,
               val v: DoubleArray = DoubleArray(t.size), val tb: DoubleArray = DoubleArray(t.size)) {
        val size get() = t.size
        fun closeTime(i: Int) = t[i] + tfMin * MIN
        /** same bars, last one closing at price px (high/low widened) */
        fun withLast(px: Double): Bars {
            val n = size; val h2 = h.copyOf(); val l2 = l.copyOf(); val c2 = c.copyOf()
            c2[n - 1] = px; h2[n - 1] = maxOf(h2[n - 1], px); l2[n - 1] = minOf(l2[n - 1], px)
            return Bars(t, o, h2, l2, c2, tfMin, v, tb)
        }
        /** bars closed by time T */
        fun upTo(T: Long): Bars {
            var n = 0
            while (n < size && closeTime(n) <= T) n++
            return Bars(t.copyOf(n), o.copyOf(n), h.copyOf(n), l.copyOf(n), c.copyOf(n), tfMin, v.copyOf(n), tb.copyOf(n))
        }
        /** last bar's volume scaled to a full minute (early call: the forming bar has only part of its minute) */
        fun scaledLast(fraction: Double): Bars {
            if (size == 0 || fraction <= 0 || fraction >= 1) return this
            val v2 = v.copyOf(); val tb2 = tb.copyOf()
            v2[size - 1] = v2[size - 1] / fraction; tb2[size - 1] = tb2[size - 1] / fraction
            return Bars(t, o, h, l, c, tfMin, v2, tb2)
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
        val bars5: Bars, val forming5: DoubleArray?,          // closed round-timeframe bars (5m or 15m, for the chart) + forming bar o/h/l/c
        val mk: Mk = BTC5,
    ) {
        val roundMs get() = mk.round
        val side get() = if (pUp >= threshold) "UP" else if (pUp <= 1 - threshold) "DOWN" else "NONE"
        val conf get() = if (pUp >= 0.5) pUp else 1 - pUp
        /** backtest win rate for this confidence (set by evaluate) */
        var winP: Double = 0.5
        val leftMs get() = roundStart + roundMs - now
        val moveBps get() = (price / open - 1) * 1e4
    }

    /** Pure evaluation (testable). tf = bars per timeframe (may include the forming bar; it is ignored). */
    fun evaluate(m: Model, tf: Map<String, Bars>, now: Long, livePrice: Double, threshold: Double = m.threshold,
                 round: Long? = null, flow: FlowIn? = null, mk: Mk = BTC5): Call {
        // round = a later round start (early call): bars ending at it are still forming and are used as they are now
        val T = round ?: (now / mk.round * mk.round)
        val (x, st) = featureVector(tf, T, m.k, if (m.flow) (flow ?: throw IllegalStateException("order-flow data missing")) else null, m.gap)
        val p = m.pUp(x)
        val b5 = tf.getValue(mk.tf)
        val fi = b5.t.indexOfLast { it == T }
        val open = if (fi >= 0) b5.o[fi] else tf.getValue("m1").let { b1 -> b1.t.indexOfLast { it == T }.let { if (it >= 0) b1.o[it] else livePrice } }
        val atr = st.getValue(m.atrTf).f[10] / 1e4 * open
        val form = if (fi >= 0) doubleArrayOf(b5.o[fi], maxOf(b5.h[fi], livePrice), minOf(b5.l[fi], livePrice), livePrice) else null
        return Call(now, T, open, livePrice, p, threshold,
            open + m.yClose.raw(x) * atr, open + m.yHigh.raw(x) * atr, open + m.yLow.raw(x) * atr, st, b5.upTo(T), form, mk)
            .also { it.winP = m.winProb(it.conf) }
    }

    /** 36 features for round T from bars of each TF (only bars closed by T are used). Order = structure.FEATURES. */
    fun featureVector(tf: Map<String, Bars>, T: Long, k: Int, flow: FlowIn? = null, gap: Boolean = false): Pair<DoubleArray, Map<String, Tf>> {
        val st = TFS.associate { (n, _) -> n to structure(tf.getValue(n).upTo(T), k) }
        val nf = if (flow != null) NFLOW else 0
        val x = DoubleArray(NF * 3 + 3 + nf + (if (gap) 2 else 0))
        if (flow != null) flowFeatures(tf.getValue("m1").upTo(T), flow.perp, flow.prem).copyInto(x, NF * 3 + 3)
        if (gap) {
            // start gap: price at the start vs the 60 s average before it (= the TWAP "price to beat"), in bps and in 5m ATRs
            val b1 = tf.getValue("m1").upTo(T); val i = b1.size - 1
            val g = if (i >= 0) (b1.c[i] / tp(b1, i) - 1) * 1e4 else 0.0
            val atr = st.getValue("m5").f[10]
            x[NF * 3 + 3 + nf] = if (g.isFinite()) g else 0.0
            x[NF * 3 + 3 + nf + 1] = if (atr > 0 && g.isFinite()) g / atr else 0.0
        }
        TFS.forEachIndexed { q, (n, _) -> st.getValue(n).f.copyInto(x, q * NF) }
        x[NF * 3] = (st.getValue("m1").trend + st.getValue("m5").trend + st.getValue("m15").trend).toDouble()
        val hr = ((T / 3_600_000L) % 24).toDouble()
        x[NF * 3 + 1] = sin(2 * PI * hr / 24)
        x[NF * 3 + 2] = cos(2 * PI * hr / 24)
        return x to st
    }

    private fun bars(api: Binance, sym: String, interval: String, tfMin: Int, path: String = "/api/v3/klines", host: String = Binance.SPOT, limit: Int = 300): Bars {
        val a = api.request("GET", path, mapOf("symbol" to sym, "interval" to interval, "limit" to limit), host = host) as JSONArray
        val r = (0 until a.length()).map { a.getJSONArray(it) }
        fun d(i: Int, k: Int) = r[i].getString(k).toDoubleOrNull() ?: 0.0
        return Bars(LongArray(r.size) { r[it].getLong(0) }, DoubleArray(r.size) { d(it, 1) }, DoubleArray(r.size) { d(it, 2) },
            DoubleArray(r.size) { d(it, 3) }, DoubleArray(r.size) { d(it, 4) }, tfMin, DoubleArray(r.size) { d(it, 5) }, DoubleArray(r.size) { d(it, 9) })
    }

    /** Order flow inputs: BTCUSDT perpetual 1m klines (close, volume, taker buy) + perp premium index 1m (close). */
    class FlowIn(val perp: Bars, val prem: Bars)
    const val NFLOW = 12

    /**
     * flow.py flow_features at the last spot 1m bar j: taker-buy share (spot / perp) over 1/3/5 bars, 5-bar volume vs the
     * 60-bar mean (spot / perp), premium index basis + 5-bar change, perp minus spot return over 1/5 bars (bps).
     * perp / prem are aligned to the spot bars by open time (close carried forward, volume 0 when a minute is missing).
     */
    fun flowFeatures(b: Bars, perp: Bars, prem: Bars): DoubleArray {
        val j = b.size - 1
        val n0 = maxOf(0, j - 69)
        val pi = HashMap<Long, Int>(perp.size * 2).also { m -> for (i in 0 until perp.size) m[perp.t[i]] = i }
        val ri = HashMap<Long, Int>(prem.size * 2).also { m -> for (i in 0 until prem.size) m[prem.t[i]] = i }
        val len = j - n0 + 1
        val pc = DoubleArray(len); val pv = DoubleArray(len); val ptb = DoubleArray(len); val rc = DoubleArray(len)
        var lp = Double.NaN; var lr = Double.NaN
        for (q in 0 until len) {
            val t = b.t[n0 + q]
            pi[t]?.let { lp = perp.c[it]; pv[q] = perp.v[it]; ptb[q] = perp.tb[it] }
            ri[t]?.let { lr = prem.c[it] }
            pc[q] = lp; rc[q] = lr
        }
        val J = len - 1
        val sv = DoubleArray(len) { b.v[n0 + it] }; val stb = DoubleArray(len) { b.tb[n0 + it] }; val sc = DoubleArray(len) { b.c[n0 + it] }
        fun sum(a: DoubleArray, n: Int): Double { var s = 0.0; for (q in J - n + 1..J) s += a[q]; return s }
        fun ratio(v: DoubleArray, tb: DoubleArray, n: Int): Double { val vs = sum(v, n); return if (vs > 0) sum(tb, n) / maxOf(vs, 1e-12) - 0.5 else 0.0 }
        fun vz(v: DoubleArray): Double {
            val m = minOf(60, len)
            var s = 0.0; for (q in J - m + 1..J) s += v[q]
            val mean = if (m >= 30) s / m else 0.0
            return sum(v, 5) / maxOf(mean * 5, 1e-9)
        }
        val f = DoubleArray(NFLOW)
        f[0] = ratio(sv, stb, 1); f[1] = ratio(pv, ptb, 1); f[2] = ratio(sv, stb, 3); f[3] = ratio(pv, ptb, 3)
        f[4] = ratio(sv, stb, 5); f[5] = ratio(pv, ptb, 5); f[6] = vz(sv); f[7] = vz(pv)
        f[8] = rc[J] * 1e4; f[9] = (rc[J] - rc[J - 5]) * 1e4
        f[10] = ((pc[J] / pc[J - 1] - 1) - (sc[J] / sc[J - 1] - 1)) * 1e4
        f[11] = ((pc[J] / pc[J - 5] - 1) - (sc[J] / sc[J - 5] - 1)) * 1e4
        for (i in f.indices) if (!f[i].isFinite()) f[i] = 0.0
        return f
    }

    /** Download 1m / 5m / 15m Binance SPOT candles + live price and evaluate the current round. Blocking. */
    fun scan(api: Binance, m: Model, threshold: Double = m.threshold, round: Long? = null, mk: Mk = BTC5): Call {
        val now = api.nowMs()
        val s = mk.symbol
        val tf = mapOf("m1" to bars(api, s, "1m", 1), "m5" to bars(api, s, "5m", 5), "m15" to bars(api, s, "15m", 15))
        for ((n, b) in tf) for (q in 1 until b.size) if (b.t[q] - b.t[q - 1] != b.tfMin * MIN) throw IllegalStateException("gap in ${mk.coin} $n bars")
        val px = (api.request("GET", "/api/v3/ticker/price", mapOf("symbol" to s), host = Binance.SPOT) as JSONObject)
            .getString("price").toDouble()
        var flow = if (m.flow) FlowIn(bars(api, s, "1m", 1, "/fapi/v1/klines", Binance.MAINNET, 100),
            bars(api, s, "1m", 1, "/fapi/v1/premiumIndexKlines", Binance.MAINNET, 100)) else null
        if (round != null) {
            // early call: the forming 1m bar must be the one that ends at the round start, else the data is stale
            val b1 = tf.getValue("m1")
            if (b1.size == 0 || b1.t.last() != round - MIN) throw IllegalStateException("1m bar ending at the round start not found")
            // forming bars carry the latest trade; use the live price as their close
            val upd = tf.mapValues { (_, b) -> if (b.size > 0 && b.closeTime(b.size - 1) == round) b.withLast(px) else b }.toMutableMap()
            // forming 1m bars hold only part of their minute: scale their volume to a full minute for the volume features
            val frac = ((now - (round - MIN)).toDouble() / MIN).coerceIn(0.05, 1.0)
            upd["m1"] = upd.getValue("m1").scaledLast(frac)
            flow = flow?.let { f -> FlowIn(if (f.perp.size > 0 && f.perp.t.last() == round - MIN) f.perp.scaledLast(frac) else f.perp, f.prem) }
            return evaluate(m, upd, now, px, threshold, round, flow, mk)
        }
        // at the round start: only closed bars count (the forming perp / premium bar is dropped)
        val R = mk.round
        flow = flow?.let { f -> FlowIn(f.perp.upTo(now / R * R), f.prem.upTo(now / R * R)) }
        return evaluate(m, tf, now, px, threshold, null, flow, mk)
    }
}
