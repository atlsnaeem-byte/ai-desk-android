package pk.aidesk.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pk.aidesk.app.core.Candles
import pk.aidesk.app.core.Config
import pk.aidesk.app.core.Features
import pk.aidesk.app.core.SignalModel
import pk.aidesk.app.core.Strategy
import kotlin.math.abs
import kotlin.math.max

/**
 * fixture.json was exported from the Python bot (features.py / model.py / explain.py) on real Binance
 * candles. The Kotlin port must give the same features, probabilities, decision and top drivers.
 */
class ParityTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    private fun candles(o: JSONObject): Candles {
        fun d(k: String) = o.getJSONArray(k).let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
        fun l(k: String) = o.getJSONArray(k).let { a -> LongArray(a.length()) { a.getLong(it) } }
        return Candles(l("open_time"), d("open"), d("high"), d("low"), d("close"), d("volume"), d("taker_buy_base"), l("close_time"))
    }

    private fun close(a: Double, b: Double, tol: Double) = abs(a - b) <= tol * max(1.0, abs(b))

    @Test
    fun featuresPredictionsAndDriversMatchPython() {
        val fx = JSONObject(res("fixture.json"))
        val model = SignalModel.load(res("model.json"))
        val cfg = Config()
        val kl = fx.getJSONObject("klines")
        val all = kl.keys().asSequence().associateWith { candles(kl.getJSONObject(it)) }
        val btc = Features.btcContext(all.getValue("BTCUSDT"))
        val rows = fx.getJSONObject("rows")
        val problems = mutableListOf<String>()
        var checked = 0
        for (sym in rows.keys()) {
            val df = all.getValue(sym)
            val r = Features.indicators(df)
            val arr: JSONArray = rows.getJSONArray(sym)
            for (k in 0 until arr.length()) {
                val exp = arr.getJSONObject(k)
                val i = df.openTime.indexOfFirst { it == exp.getLong("open_time") }
                assertTrue("$sym row not found", i >= 0)
                val row = Features.row(r, df, i, btc)
                val ef = exp.getJSONObject("features")
                for (f in model.features) {
                    val got = row[f] ?: Double.NaN
                    val missingGot = got.isNaN() || got.isInfinite()
                    if (ef.isNull(f)) {
                        if (!missingGot) problems += "$sym[$i] $f: expected missing, got $got"
                    } else {
                        val want = ef.getDouble(f)
                        if (missingGot || !close(got, want, 1e-7)) problems += "$sym[$i] $f: expected $want, got $got"
                    }
                }
                assertTrue("ATR mismatch $sym", close(r.atr[i], exp.getDouble("atr"), 1e-8))
                val (pl, ps) = model.predict(row)
                if (!close(pl, exp.getDouble("p_long"), 1e-6)) problems += "$sym[$i] p_long ${exp.getDouble("p_long")} vs $pl"
                if (!close(ps, exp.getDouble("p_short"), 1e-6)) problems += "$sym[$i] p_short ${exp.getDouble("p_short")} vs $ps"
                val d = Strategy.decide(pl, ps, df.close[i], r.atr[i], cfg, minEv = -99.0)!!
                assertEquals("$sym side", exp.getString("side"), d.side)
                assertTrue("$sym ev ${exp.getDouble("ev_r")} vs ${d.evR}", close(d.evR, exp.getDouble("ev_r"), 1e-5))
                val drv = model.drivers(row, d.side, 5)
                val ed = exp.getJSONArray("drivers")
                for (j in 0 until ed.length()) {
                    val e = ed.getJSONArray(j)
                    assertEquals("$sym driver $j", e.getString(0), drv[j].feature)
                    assertTrue("$sym driver impact $j", close(drv[j].impact, e.getDouble(1), 1e-4))
                }
                checked++
            }
        }
        assertTrue("No rows checked", checked > 0)
        assertTrue("Mismatches (${problems.size}):\n" + problems.take(40).joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun sizingMatchesPython() {
        val c = Config(leverage = 5, maxOpen = 3)
        // risk 2% of 1000 with a 10 USDT stop = 2 units, capped by 1000*5/3/price
        assertEquals(2.0, Strategy.positionSize(1000.0, 100.0, 10.0, c, 2.0), 1e-12)
        assertEquals(1000.0 * 5 / 3 / 100.0, Strategy.positionSize(1000.0, 100.0, 0.1, c, 20.0), 1e-9)
    }
}
