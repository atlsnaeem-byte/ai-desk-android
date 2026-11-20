package pk.aidesk.app

import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import pk.aidesk.app.core.Btc5m
import pk.aidesk.app.core.Candles
import kotlin.math.abs

/** btc5m_fixture.json comes from the laptop's BTC 5m Signal/export_app.py: same features and P(up) as LightGBM in Python. */
class Btc5mParityTest {
    private fun res(n: String) = javaClass.classLoader!!.getResource(n)!!.readText()

    @Test
    fun btc5mMatchesPython() {
        val m = Btc5m.load(res("btc5m.json"))
        val cases = JSONArray(res("btc5m_fixture.json"))
        val bad = mutableListOf<String>()
        for (k in 0 until cases.length()) {
            val cs = cases.getJSONObject(k)
            fun d(n: String) = cs.getJSONArray(n).let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
            val t = cs.getJSONArray("t").let { a -> LongArray(a.length()) { a.getLong(it) } }
            val c = Candles(t, d("o"), d("h"), d("l"), d("c"), d("v"), d("tb"), LongArray(t.size) { t[it] + 59_999 })
            val T = cs.getLong("T"); val e = cs.getInt("e")
            val x = Btc5m.features(c, T, e)
            if (x == null) { bad += "case $k: no features"; continue }
            val want = d("feats")
            for (i in x.indices) if (abs(x[i] - want[i]) > 1e-6 * maxOf(1.0, abs(want[i]))) bad += "case $k feat $i: ${x[i]} vs ${want[i]}"
            val p = m.pUp(x)
            if (abs(p - cs.getDouble("p_up")) > 1e-6) bad += "case $k p_up: $p vs ${cs.getDouble("p_up")}"
            // evaluate() at 1s into the next minute must find the same checkpoint
            val call = Btc5m.evaluate(m, c, T + e * 60_000L + 1_000, c.close.last())
            if (call.e != e) bad += "case $k: evaluate e=${call.e} vs $e"
            if (e == 0 && call.confirmed) bad += "case $k: confirmed before the round"
        }
        assertTrue(bad.take(30).joinToString("\n"), bad.isEmpty())
    }
}
