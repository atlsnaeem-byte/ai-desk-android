package pk.aidesk.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import pk.aidesk.app.core.Candles
import pk.aidesk.app.core.Gold
import kotlin.math.abs

/** gold_fixture.json comes from the laptop's xaulab/make_fixture.py: Gold.evaluate must take the same trades as the backtest. */
class GoldParityTest {
    private fun candles(o: JSONObject, ms: Long): Candles {
        fun d(k: String) = o.getJSONArray(k).let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
        val t = o.getJSONArray("open_time").let { a -> LongArray(a.length()) { a.getLong(it) } }
        return Candles(t, d("open"), d("high"), d("low"), d("close"), d("volume"), DoubleArray(t.size), LongArray(t.size) { t[it] + ms - 1 })
    }

    @Test
    fun goldSignalsMatchPython() {
        val cases = JSONArray(javaClass.classLoader!!.getResource("gold_fixture.json")!!.readText())
        val bad = mutableListOf<String>()
        for (k in 0 until cases.length()) {
            val cs = cases.getJSONObject(k)
            val c = candles(cs.getJSONObject("c15"), 900_000L)
            val s = Gold.evaluate(c, candles(cs.getJSONObject("h4"), 14_400_000L), cs.getLong("now"), c.close.last())
            val e = cs.optJSONObject("expected")
            if (e == null) { if (s.status == "SIGNAL") bad += "day $k: kotlin ${s.sideName} at ${s.signalTime}, python none" ; continue }
            if (s.status != "SIGNAL") { bad += "day $k: python side ${e.getInt("side")}, kotlin ${s.status}"; continue }
            if (s.side != e.getInt("side") || s.signalTime != e.getLong("signalTime") ||
                abs(s.entry - e.getDouble("entry")) > 1e-6 || abs(s.sl - e.getDouble("sl")) > 1e-6)
                bad += "day $k: kotlin ${s.side}/${s.signalTime}/${s.entry}/${s.sl} vs python $e"
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }
}
