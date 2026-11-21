package pk.aidesk.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import pk.aidesk.app.core.Candles
import pk.aidesk.app.core.Structure
import kotlin.math.abs

/** structure_fixture.json comes from the laptop's AI Desk/structure/make_struct_fixture.py (btc_structure.py rules). */
class StructureParityTest {
    private fun candles(o: JSONObject, ms: Long): Candles {
        fun d(k: String) = o.getJSONArray(k).let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
        val t = o.getJSONArray("open_time").let { a -> LongArray(a.length()) { a.getLong(it) } }
        return Candles(t, d("open"), d("high"), d("low"), d("close"), d("volume"), DoubleArray(t.size), LongArray(t.size) { t[it] + ms - 1 })
    }

    @Test
    fun structureMatchesPython() {
        val cases = JSONArray(javaClass.classLoader!!.getResource("structure_fixture.json")!!.readText())
        val bad = mutableListOf<String>()
        var n = 0
        for (w in 0 until cases.length()) {
            val cs = cases.getJSONObject(w)
            val c15 = candles(cs.getJSONObject("c15"), Structure.M15)
            val h4 = candles(cs.getJSONObject("h4"), Structure.H4)
            val reg = Structure.regime4h(h4)
            for (k in listOf(3, 5, 10)) {
                val got = Structure.signals(c15, k)
                val exp = cs.getJSONObject("expected").getJSONArray(k.toString())
                if (got.size != exp.length()) { bad += "window $w k=$k: kotlin ${got.size} signals, python ${exp.length()}"; continue }
                for (i in got.indices) {
                    val e = exp.getJSONObject(i); val g = got[i]; n++
                    val tr = Structure.regimeAt(h4, reg, c15.openTime[g.i])
                    if (g.i != e.getInt("i") || g.side != e.getInt("side") || g.setup != e.getString("setup") ||
                        abs(g.sl - e.getDouble("sl")) > 1e-6 * maxOf(1.0, abs(g.sl)) || tr != e.getInt("trend"))
                        bad += "window $w k=$k #$i: kotlin ${g.i}/${g.side}/${g.setup}/${g.sl}/$tr vs python $e"
                }
            }
        }
        assertTrue(bad.take(20).joinToString("\n"), bad.isEmpty() && n > 0)
    }
}
