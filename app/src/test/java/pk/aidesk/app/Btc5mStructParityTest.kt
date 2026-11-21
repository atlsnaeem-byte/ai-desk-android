package pk.aidesk.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import pk.aidesk.app.core.Btc5mStruct
import kotlin.math.abs

/** btc5m_struct_fixture.json comes from Documents/BTC Structure 5m AI/export_app.py: same features and model outputs as Python. */
class Btc5mStructParityTest {
    private fun res(n: String) = javaClass.classLoader!!.getResource(n)!!.readText()

    @Test
    fun structMatchesPython() {
        val m = Btc5mStruct.load(res("btc5m_struct.json"))
        val cases = JSONArray(res("btc5m_struct_fixture.json"))
        val bad = mutableListOf<String>()
        for (k in 0 until cases.length()) {
            val cs = cases.getJSONObject(k)
            val T = cs.getLong("T")
            val bo = cs.getJSONObject("bars")
            val tf = Btc5mStruct.TFS.associate { (n, mins) ->
                val b = bo.getJSONObject(n)
                fun d(x: String) = b.getJSONArray(x).let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
                val t = b.getJSONArray("t").let { a -> LongArray(a.length()) { a.getLong(it) } }
                n to Btc5mStruct.Bars(t, d("o"), d("h"), d("l"), d("c"), mins)
            }
            val (x, _) = Btc5mStruct.featureVector(tf, T, m.k)
            val want = cs.getJSONArray("feats").let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
            if (x.size != want.size) { bad += "case $k: ${x.size} features vs ${want.size}"; continue }
            for (i in x.indices) if (abs(x[i] - want[i]) > 1e-6 * maxOf(1.0, abs(want[i]))) bad += "case $k feat $i: ${x[i]} vs ${want[i]}"
            val out: JSONObject = cs.getJSONObject("out")
            val got = mapOf("up" to m.pUp(x), "y_close" to m.yClose.raw(x), "y_high" to m.yHigh.raw(x), "y_low" to m.yLow.raw(x))
            for ((n, v) in got) if (abs(v - out.getDouble(n)) > 1e-6) bad += "case $k $n: $v vs ${out.getDouble(n)}"
            // evaluate() a few seconds into the round must give the same P(UP) (forming bars are ignored)
            val call = Btc5mStruct.evaluate(m, tf, T + 3_000, tf.getValue("m1").c.last())
            if (abs(call.pUp - out.getDouble("up")) > 1e-9) bad += "case $k evaluate p: ${call.pUp}"
        }
        assertTrue(bad.take(30).joinToString("\n"), bad.isEmpty())
    }
}
