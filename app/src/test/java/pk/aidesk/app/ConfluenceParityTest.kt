package pk.aidesk.app

import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import pk.aidesk.app.core.Advisor
import pk.aidesk.app.core.Candles
import pk.aidesk.app.core.Confluence
import pk.aidesk.app.core.Patterns
import kotlin.math.abs
import kotlin.math.max

/**
 * confluence_fixture.json comes from the laptop's confluence/make_fixture.py (backtest.py rules) on real 4h candles.
 * Confluence.evaluate on candles[0..i] must give the same setup (side, kinds, score, SL, TP) at every bar.
 */
class ConfluenceParityTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private fun close(a: Double, b: Double) = abs(a - b) <= 1e-6 * max(1.0, abs(b))

    @Test
    fun setupsMatchPython() {
        val cases = JSONArray(res("confluence_fixture.json"))
        val problems = mutableListOf<String>()
        var checked = 0
        for (k in 0 until cases.length()) {
            val cs = cases.getJSONObject(k)
            fun d(key: String) = cs.getJSONArray(key).let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
            val t = cs.getJSONArray("open_time").let { a -> LongArray(a.length()) { a.getLong(it) } }
            val n = t.size
            val all = Candles(t, d("o"), d("h"), d("l"), d("c"), d("v"), DoubleArray(n), LongArray(n) { t[it] + 1 })
            val trend = cs.getJSONArray("trend").let { a -> IntArray(a.length()) { a.getInt(it) } }
            val exp = cs.getJSONArray("expected").let { a -> (0 until a.length()).associate { a.getJSONObject(it).let { o -> o.getInt("i") to o } } }
            for (i in 250 until n) {
                val cd = Advisor.slice(all, 0, i + 1)
                val got = Confluence.evaluate(cd, trend[i], Patterns.scan(cd).active)
                val e = exp[i]
                val tag = "${cs.getString("symbol")} bar $i"
                if (e == null && got == null) continue
                checked++
                if (e == null) { problems += "$tag: kotlin ${got!!.sideName} ${got.kinds} s${got.score}, python none"; continue }
                if (got == null) { problems += "$tag: kotlin none, python $e"; continue }
                if (e.getInt("side") != got.side || e.getString("kinds") != got.kinds.joinToString("+") || e.getInt("score") != got.score ||
                    !close(e.getDouble("sl"), got.sl) || !close(e.getDouble("tp"), got.tp))
                    problems += "$tag: kotlin ${got.side} ${got.kinds} s${got.score} sl ${got.sl} tp ${got.tp} / python $e"
            }
        }
        assertTrue("no setups in fixture", checked > 10)
        assertTrue(problems.take(15).joinToString("\n") + "\n(${problems.size} problems, $checked setups)", problems.isEmpty())
    }
}
