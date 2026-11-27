package pk.aidesk.app

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import pk.aidesk.app.core.Btc5mS1
import pk.aidesk.app.core.Btc5mStruct
import kotlin.math.abs

/** pred_eth5s_fixture.json comes from Documents/BTC Structure 5m AI/fixture_s1.py: real 1 s windows, the 19 features and the
 *  LightGBM probability computed in Python. */
class S1ParityTest {
    private fun res(n: String) = javaClass.classLoader!!.getResource(n)!!.readText()
    private fun d(o: JSONObject, x: String) = o.getJSONArray(x).let { a -> DoubleArray(a.length()) { a.getDouble(it) } }

    @Test
    fun oneSecondModelMatchesPython() {
        val m = Btc5mStruct.load(res("pred_eth5s.json"))
        assertTrue("s1 flag", m.s1)
        val fx = JSONObject(res("pred_eth5s_fixture.json"))
        val cases = fx.getJSONArray("cases")
        assertTrue("decision second", fx.getInt("decisionSec") == Btc5mS1.D && fx.getInt("lookback") == Btc5mS1.LOOKBACK)
        val bad = mutableListOf<String>()
        for (k in 0 until cases.length()) {
            val cs = cases.getJSONObject(k)
            val sec = Btc5mS1.Sec(cs.getLong("start"), d(cs, "c"), d(cs, "v"), d(cs, "tb"))
            val x = Btc5mS1.features(sec, cs.getLong("T"), cs.getDouble("c1h"))
            if (x == null) { bad += "case $k: features null"; continue }
            val want = d(cs, "features")
            if (x.size != want.size) { bad += "case $k: ${x.size} features vs ${want.size}"; continue }
            for (i in x.indices) if (abs(x[i] - want[i]) > 1e-6 * maxOf(1.0, abs(want[i]))) bad += "case $k feat $i: ${x[i]} vs ${want[i]}"
            val p = m.pUp(x)
            if (abs(p - cs.getDouble("p")) > 1e-6) bad += "case $k p: $p vs ${cs.getDouble("p")}"
        }
        assertTrue(bad.take(30).joinToString("\n"), bad.isEmpty())
    }
}
