package pk.aidesk.app.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * BTC "Up or Down 5m" rounds (Binance Prediction): CONFIRMED calls only.
 *
 * Port of the laptop engine in Documents/BTC 5m Signal (engine.py / live.py):
 *  - rounds on the 5-minute UTC grid, Up wins if the round-end price >= round-open price (Binance BTCUSDT SPOT 1m bars)
 *  - at 1:00, 2:00, 3:00, 4:00 into the round (closed 1m bars only) a LightGBM model gives P(up)
 *  - CONFIRMED when the model's confidence passes that minute's threshold (picked so the backtest wins >= 90%)
 *  - before the round: never (51% in the backtest = no edge)
 * Edge only exists if the share price is below the backtest win rate: the market sees the same move.
 */
object Btc5m {
    const val SYMBOL = "BTCUSDT"
    const val MIN = 60_000L
    const val ROUND = 300_000L

    class Tree(val root: Int, val f: IntArray, val t: DoubleArray, val l: IntArray, val r: IntArray, val v: DoubleArray) {
        fun eval(x: DoubleArray): Double {
            var n = root
            while (n >= 0) n = if (x[f[n]] <= t[n]) l[n] else r[n]
            return v[n.inv()]
        }
    }

    class Model(
        val trees: List<Tree>,
        val thresholds: Map<Int, Double?>,
        val win: Map<Int, List<Pair<Double?, Int>>>,  // e -> conf 0.50..1.00 step 0.01 -> (backtest win rate, cases)
        val allWin: Map<Int, Double>,
        val oneBetWin: Double, val oneBetShare: Double, val days: Int, val built: String,
    ) {
        fun pUp(x: DoubleArray): Double = 1.0 / (1.0 + exp(-trees.sumOf { it.eval(x) }))
        fun histWin(e: Int, conf: Double): Pair<Double?, Int> {
            val row = win[e] ?: return null to 0
            val k = Math.round((conf - 0.5) * 100).toInt().coerceIn(0, row.size - 1)
            return row[k]
        }
    }

    fun load(json: String): Model {
        val o = JSONObject(json)
        fun ints(a: JSONArray) = IntArray(a.length()) { a.getInt(it) }
        fun dbls(a: JSONArray) = DoubleArray(a.length()) { a.getDouble(it) }
        val ta = o.getJSONArray("trees")
        val trees = (0 until ta.length()).map { k ->
            val t = ta.getJSONObject(k)
            Tree(t.getInt("root"), ints(t.getJSONArray("f")), dbls(t.getJSONArray("t")), ints(t.getJSONArray("l")),
                ints(t.getJSONArray("r")), dbls(t.getJSONArray("v")))
        }
        val th = o.getJSONObject("thresholds")
        val w = o.getJSONObject("win")
        val aw = o.getJSONObject("all_win")
        val ob = o.getJSONObject("one_bet")
        return Model(trees,
            (0..4).associateWith { e -> th.opt(e.toString()).let { if (it == null || it == JSONObject.NULL) null else (it as Number).toDouble() } },
            (0..4).associateWith { e ->
                val a = w.getJSONArray(e.toString())
                (0 until a.length()).map { k -> a.getJSONArray(k).let { (if (it.isNull(0)) null else it.getDouble(0)) to it.getInt(1) } }
            },
            (0..4).associateWith { aw.getDouble(it.toString()) },
            ob.getDouble("win"), ob.getDouble("bets") / ob.getDouble("rounds"), o.getInt("days"), o.getString("built"))
    }

    /** Same 17 features as engine.py build_rows, in the same order. c = closed 1m bars (contiguous), T = round start. */
    fun features(c: Candles, T: Long, e: Int): DoubleArray? {
        val t0 = c.openTime[0]
        val iT = ((T - t0) / MIN).toInt()
        val j = iT + e - 1
        if (iT < 241 || j >= c.size || j < 60) return null
        val o = c.open; val h = c.high; val l = c.low; val cl = c.close; val v = c.volume; val tb = c.takerBuyBase
        val O = if (iT < c.size) o[iT] else cl[iT - 1]
        val p = cl[j]
        var rs = 0.0; var vsum = 0.0
        for (k in j - 59..j) { rs += h[k] - l[k]; vsum += v[k] }
        val atr = max(rs / 60, 1e-9); val vmean = vsum / 60
        val hi: Double; val lo: Double; val a: Int; val b: Int
        if (e >= 1) {
            var mx = h[iT]; var mn = l[iT]
            for (k in iT..j) { if (h[k] > mx) mx = h[k]; if (l[k] < mn) mn = l[k] }
            hi = mx; lo = mn; a = iT; b = j + 1
        } else { hi = O; lo = O; a = j - 2; b = j + 1 }
        var vs = 0.0; var tbs = 0.0
        for (k in a until b) { vs += v[k]; tbs += tb[k] }
        val hour = ((T / 3_600_000L) % 24).toDouble()
        return doubleArrayOf(
            e.toDouble(), (p - O) / atr, (p / O - 1) * 1e4, (p - O) / (atr * sqrt(5.0 - e)),
            (cl[j] - cl[j - 1]) / atr, (cl[j] - cl[j - 2]) / atr,
            (O - cl[iT - 6]) / atr, (O - cl[iT - 16]) / atr, (O - cl[iT - 61]) / atr, (O - cl[iT - 241]) / atr,
            atr / O * 1e4, (vs / (b - a)) / max(vmean, 1e-9), if (vs > 0) tbs / vs else 0.5,
            (hi - p) / atr, (p - lo) / atr, sin(2 * PI * hour / 24), cos(2 * PI * hour / 24),
        )
    }

    class Call(
        val now: Long, val roundStart: Long, val e: Int,     // e = closed minutes in the round (0..4)
        val open: Double, val price: Double,
        val pUp: Double?, val threshold: Double?,
        val histWin: Double?, val histN: Int,
    ) {
        val leftMs get() = roundStart + ROUND - now
        val side get() = if ((pUp ?: 0.5) >= 0.5) "UP" else "DOWN"
        val conf get() = pUp?.let { if (it >= 0.5) it else 1 - it }
        val confirmed get() = e >= 1 && pUp != null && threshold != null && conf!! >= threshold
        val nextCheck get() = roundStart + (e + 1) * MIN
        val moveBps get() = (price / open - 1) * 1e4
        /** share price (0..1) -> edge in points vs the backtest win rate */
        fun edge(share: Double): Double? = histWin?.let { (it - share) * 100 }
    }

    /** Evaluate the current round on closed 1m bars. Pure (testable). */
    fun evaluate(m: Model, c: Candles, now: Long, livePrice: Double): Call {
        val T = now / ROUND * ROUND
        val e = c.openTime.count { it >= T }.coerceAtMost(4)
        val iT = ((T - c.openTime[0]) / MIN).toInt()
        val open = if (iT < c.size) c.open[iT] else c.close.last()
        if (e == 0) return Call(now, T, 0, open, livePrice, null, null, null, 0)
        val x = features(c, T, e) ?: return Call(now, T, e, open, livePrice, null, null, null, 0)
        val p = m.pUp(x)
        val conf = if (p >= 0.5) p else 1 - p
        val (hw, hn) = m.histWin(e, conf)
        return Call(now, T, e, open, livePrice, p, m.thresholds[e], hw, hn)
    }

    /** Fetch Binance SPOT 1m bars and evaluate. Blocking: call off the main thread. */
    fun scan(api: Binance, m: Model): Call {
        val now = api.nowMs()
        val a = api.request("GET", "/api/v3/klines", mapOf("symbol" to SYMBOL, "interval" to "1m", "limit" to 300), host = Binance.SPOT) as JSONArray
        val rows = (0 until a.length()).map { a.getJSONArray(it) }.filter { it.getLong(0) + MIN <= now }   // closed bars only
        val c = Candles(
            LongArray(rows.size) { rows[it].getLong(0) },
            DoubleArray(rows.size) { rows[it].getString(1).toDouble() },
            DoubleArray(rows.size) { rows[it].getString(2).toDouble() },
            DoubleArray(rows.size) { rows[it].getString(3).toDouble() },
            DoubleArray(rows.size) { rows[it].getString(4).toDouble() },
            DoubleArray(rows.size) { rows[it].getString(5).toDouble() },
            DoubleArray(rows.size) { rows[it].getString(9).toDouble() },
            LongArray(rows.size) { rows[it].getLong(6) },
        )
        for (k in 1 until c.size) if (c.openTime[k] - c.openTime[k - 1] != MIN) throw IllegalStateException("gap in BTC 1m bars")
        val px = (api.request("GET", "/api/v3/ticker/price", mapOf("symbol" to SYMBOL), host = Binance.SPOT) as JSONObject)
            .getString("price").toDouble()
        return evaluate(m, c, now, px)
    }
}
