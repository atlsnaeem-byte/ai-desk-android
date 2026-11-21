package pk.aidesk.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import pk.aidesk.app.core.Candles
import pk.aidesk.app.core.Rbf
import kotlin.math.abs

/** rbf_fixture.json comes from the laptop's xaulab/make_rbf_fixture.py: Rbf must find the same setups and 5m entries as rbf_lab.py. */
class RbfParityTest {
    private fun candles(o: JSONObject, ms: Long): Candles {
        fun d(k: String) = o.getJSONArray(k).let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
        val t = o.getJSONArray("open_time").let { a -> LongArray(a.length()) { a.getLong(it) } }
        return Candles(t, d("open"), d("high"), d("low"), d("close"), d("volume"), DoubleArray(t.size), LongArray(t.size) { t[it] + ms - 1 })
    }

    @Test
    fun rbfMatchesPython() {
        val cases = JSONArray(javaClass.classLoader!!.getResource("rbf_fixture.json")!!.readText())
        val bad = mutableListOf<String>()
        var n = 0
        for (k in 0 until cases.length()) {
            val cs = cases.getJSONObject(k)
            val c15 = candles(cs.getJSONObject("c15"), Rbf.M15)
            val c5 = candles(cs.getJSONObject("c5"), Rbf.M5)
            val got = Rbf.setups15(c15)
            val exp = cs.getJSONArray("expected")
            if (got.size != exp.length()) { bad += "window $k: kotlin ${got.size} setups, python ${exp.length()}"; continue }
            for (i in got.indices) {
                val e = exp.getJSONObject(i); val g = got[i]; n++
                val et = Rbf.trigger5(c5, g)?.let { c5.openTime[it] }
                val pet = if (e.isNull("entryTime")) null else e.getLong("entryTime")
                if (g.side != e.getInt("side") || g.ready != e.getLong("ready") || abs(g.sl - e.getDouble("sl")) > 1e-6 ||
                    abs(g.fvgLo - e.getDouble("fvgLo")) > 1e-9 || abs(g.fvgHi - e.getDouble("fvgHi")) > 1e-9 || et != pet)
                    bad += "window $k setup $i: kotlin ${g.side}/${g.ready}/${g.sl}/$et vs python $e"
            }
        }
        assertTrue(bad.take(20).joinToString("\n"), bad.isEmpty() && n > 0)
    }
}
