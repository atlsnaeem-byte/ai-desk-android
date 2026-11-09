package pk.aidesk.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pk.aidesk.app.core.Advisor
import pk.aidesk.app.core.Candles
import pk.aidesk.app.core.Patterns
import kotlin.math.abs
import kotlin.math.max

/**
 * patterns_fixture.json was exported from the laptop's patterns/engine2.py (make_kotlin_fixture.py) on real
 * Binance candles (BTC 1h, SOL 4h, ETH 15m, XRP 5m). Patterns.kt must find the same breakouts and active patterns.
 */
class PatternsParityTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private fun close(a: Double, b: Double) = abs(a - b) <= 1e-6 * max(1.0, abs(b))

    private fun candles(o: JSONObject): Candles {
        fun d(k: String) = o.getJSONArray(k).let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
        val t = o.getJSONArray("open_time").let { a -> LongArray(a.length()) { a.getLong(it) } }
        val n = t.size
        return Candles(t, d("open"), d("high"), d("low"), d("close"), d("volume"), DoubleArray(n), LongArray(n) { t[it] + 1 })
    }

    @Test
    fun breakoutsAndActivePatternsMatchPython() {
        val cases = JSONArray(res("patterns_fixture.json"))
        val problems = mutableListOf<String>()
        for (k in 0 until cases.length()) {
            val cs = cases.getJSONObject(k)
            val tag = cs.getString("symbol") + " " + cs.getString("tf")
            val r = Patterns.scan(candles(cs.getJSONObject("candles")))
            val ev = cs.getJSONArray("events")
            if (ev.length() != r.breakouts.size) problems += "$tag: ${r.breakouts.size} breakouts vs python ${ev.length()}"
            for (i in 0 until minOf(ev.length(), r.breakouts.size)) {
                val e = ev.getJSONObject(i); val b = r.breakouts[i]
                if (e.getInt("i") != b.i || e.getString("kind") != b.kind || e.getInt("side") != b.side ||
                    !close(e.getDouble("level"), b.level) || !close(e.getDouble("other"), b.other) || e.getInt("touches") != b.pat.touches) {
                    problems += "$tag breakout #$i: kotlin i=${b.i} ${b.kind} ${b.side} ${b.level} other ${b.other} t${b.pat.touches} / python $e"
                    break
                }
            }
            val act = cs.getJSONArray("active")
            if (act.length() != r.active.size) problems += "$tag: ${r.active.size} active vs python ${act.length()}"
            val last = r.lastIndex
            for (i in 0 until minOf(act.length(), r.active.size)) {
                val a = act.getJSONObject(i); val p = r.active[i]
                val up = if (a.isNull("up")) null else a.getDouble("up")
                val dn = if (a.isNull("dn")) null else a.getDouble("dn")
                val okUp = (up == null && p.up == null) || (up != null && p.up != null && close(up, p.up.at(last)))
                val okDn = (dn == null && p.dn == null) || (dn != null && p.dn != null && close(dn, p.dn.at(last)))
                if (a.getString("kind") != p.kind || !okUp || !okDn) problems += "$tag active #$i: kotlin ${p.kind} / python $a"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun advisorPlansAreConsistent() {
        val cases = JSONArray(res("patterns_fixture.json"))
        for (k in 0 until cases.length()) {
            val cs = cases.getJSONObject(k)
            val c = candles(cs.getJSONObject("candles"))
            for (trend in listOf(-1, 0, 1)) {
                val a = Advisor.analyze(cs.getString("symbol"), cs.getString("tf"), c, null, trend, 1,
                    mapOf("funding_pct" to 0.01, "ls_accounts" to 1.5, "oi_chg_24h_pct" to 3.0))
                assertTrue(a.summary.isNotEmpty())
                for (p in a.plans) {
                    val s = if (p.side == "LONG") 1 else -1
                    assertTrue("${p.setup}: stop on wrong side", (p.entry - p.sl) * s > 0)
                    assertTrue("${p.setup}: target on wrong side", (p.tp - p.entry) * s > 0)
                    assertEquals(true, p.type in setOf("LIMIT", "STOP", "MARKET"))
                }
            }
        }
    }
}
