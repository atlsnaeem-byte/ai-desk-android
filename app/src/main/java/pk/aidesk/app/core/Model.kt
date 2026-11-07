package pk.aidesk.app.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tanh

/** Port of model.py inference: logistic regression + 1-hidden-layer tanh MLP per side, averaged. */
class Net(private val hidden: Int, private val w1: Array<DoubleArray>?, private val b1: DoubleArray?,
          private val w2: DoubleArray, private val b2: Double) {

    fun predict(x: DoubleArray): Double {
        if (hidden == 0) {
            var z = b2
            for (i in x.indices) z += x[i] * w2[i]
            return sigmoid(z)
        }
        val a = DoubleArray(hidden)
        for (j in 0 until hidden) {
            var s = b1!![j]
            for (i in x.indices) s += x[i] * w1!![i][j]
            a[j] = tanh(s)
        }
        var z = b2
        for (j in 0 until hidden) z += a[j] * w2[j]
        return sigmoid(z)
    }

    companion object {
        fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0)))

        private fun vec(a: JSONArray) = DoubleArray(a.length()) { a.getDouble(it) }
        private fun col(a: JSONArray) = DoubleArray(a.length()) { a.getJSONArray(it).getDouble(0) }

        fun from(o: JSONObject): Net {
            val hidden = o.getInt("hidden")
            val w2 = col(o.getJSONArray("W2"))
            val b2 = o.getJSONArray("b2").getDouble(0)
            if (hidden == 0) return Net(0, null, null, w2, b2)
            val w1j = o.getJSONArray("W1")
            val w1 = Array(w1j.length()) { vec(w1j.getJSONArray(it)) }
            return Net(hidden, w1, vec(o.getJSONArray("b1")), w2, b2)
        }
    }
}

class Driver(val feature: String, val label: String, val impact: Double)

class SignalModel(
    val features: List<String>,
    private val mu: DoubleArray,
    private val sd: DoubleArray,
    private val heads: Map<String, List<Net>>,
    val meta: JSONObject,
) {
    fun standardize(row: Map<String, Double>): DoubleArray = DoubleArray(features.size) { i ->
        val v = row[features[i]] ?: Double.NaN
        val z = (v - mu[i]) / sd[i]
        // np.nan_to_num(nan=0) then clip(-5, 5): +-inf ends up at +-5
        if (z.isNaN()) 0.0 else z.coerceIn(-5.0, 5.0)
    }

    private fun p(x: DoubleArray, side: String) = heads.getValue(side).map { it.predict(x) }.average()

    /** (pLong, pShort) */
    fun predict(row: Map<String, Double>): Pair<Double, Double> {
        val x = standardize(row)
        return p(x, "long") to p(x, "short")
    }

    /** explain.drivers(): change in P(side) when a feature is set to its training average. Impact in % points. */
    fun drivers(row: Map<String, Double>, side: String, top: Int = 6): List<Driver> {
        val x = standardize(row)
        val key = if (side == "LONG") "long" else "short"
        val base = p(x, key)
        val imp = LinkedHashMap<String, Double>()
        for (j in features.indices) {
            val m = x.copyOf(); m[j] = 0.0
            imp[features[j]] = base - p(m, key)
        }
        if ("hour_sin" in imp && "hour_cos" in imp) imp["hour_sin"] = imp.getValue("hour_sin") + imp.remove("hour_cos")!!
        return imp.entries.sortedByDescending { abs(it.value) }.take(top)
            .map { Driver(it.key, label(it.key), it.value * 100) }
    }

    companion object {
        fun load(json: String): SignalModel {
            val o = JSONObject(json)
            val fa = o.getJSONArray("features")
            val feats = (0 until fa.length()).map { fa.getString(it) }
            val mu = o.getJSONArray("mu").let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
            val sd = o.getJSONArray("sd").let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
            val h = o.getJSONObject("heads")
            val heads = listOf("long", "short").associateWith { s ->
                val arr = h.getJSONArray(s)
                (0 until arr.length()).map { Net.from(arr.getJSONObject(it)) }
            }
            return SignalModel(feats, mu, sd, heads, o.getJSONObject("meta"))
        }

        private val LABELS = mapOf(
            "ret_1" to "Return, last bar", "ret_3" to "Return, 3 bars", "ret_6" to "Return, 6 bars",
            "ret_12" to "Return, 12 bars", "ret_24" to "Return, 24 bars", "ret_48" to "Return, 48 bars",
            "c_e20" to "Price vs EMA20", "e20_e50" to "EMA20 vs EMA50", "e50_e200" to "EMA50 vs EMA200",
            "rsi" to "RSI (14)", "macd_h" to "MACD histogram", "atr_pct" to "Volatility (ATR)",
            "atr_ratio" to "Volatility vs 24h", "bb_b" to "Bollinger position", "bb_w" to "Bollinger width",
            "adx" to "ADX trend strength", "di_diff" to "DI+ minus DI-", "stoch" to "Stochastic %K",
            "range_pos" to "50-bar range position", "vol_z" to "Volume vs normal",
            "taker_buy" to "Taker buys (bar)", "taker_buy_6" to "Taker buys (6 bars)",
            "body" to "Candle body", "upper_wick" to "Upper wick", "lower_wick" to "Lower wick",
            "hour_sin" to "Time of day", "hour_cos" to "Time of day",
            "btc_ret_6" to "BTC return, 6 bars", "btc_ret_24" to "BTC return, 24 bars",
            "btc_e20_e50" to "BTC trend (EMA20/50)", "btc_rsi" to "BTC RSI",
        )

        fun label(f: String): String =
            LABELS[f] ?: if (f.startsWith("pat_")) "Pattern: " + (Features.PATTERNS[f.removePrefix("pat_")] ?: f) else f
    }
}
