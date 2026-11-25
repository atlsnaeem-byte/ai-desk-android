package pk.aidesk.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import pk.aidesk.app.core.Btc5mStruct
import kotlin.math.abs

/** btc5m_struct_fixture.json comes from Documents/BTC Structure 5m AI/export_flow.py: same structure + order-flow features
 *  and the same model outputs as Python (LightGBM). */
class Btc5mStructParityTest {
    private fun res(n: String) = javaClass.classLoader!!.getResource(n)!!.readText()
    private fun d(o: JSONObject, x: String) = o.getJSONArray(x).let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
    private fun l(o: JSONObject, x: String) = o.getJSONArray(x).let { a -> LongArray(a.length()) { a.getLong(it) } }

    @Test
    fun structMatchesPython() = check("btc5m_struct.json", "btc5m_struct_fixture.json", Btc5mStruct.BTC5)

    /** ETH 5m, BTC 15m, ETH 15m (export_multi.py) */
    @Test
    fun otherMarketsMatchPython() {
        for (id in listOf("eth5", "btc15", "eth15")) {
            val mk = Btc5mStruct.mk(id)
            assertTrue(id, mk.id == id)
            val m = Btc5mStruct.load(res(mk.asset))
            assertTrue("$id atr_tf", m.atrTf == mk.tf)
            check(mk.asset, "pred_${id}_fixture.json", mk)
        }
    }

    private fun check(modelRes: String, fixtureRes: String, mk: Btc5mStruct.Mk) {
        val m = Btc5mStruct.load(res(modelRes))
        val cases = JSONArray(res(fixtureRes))
        val bad = mutableListOf<String>()
        for (k in 0 until cases.length()) {
            val cs = cases.getJSONObject(k)
            val T = cs.getLong("T")
            val bo = cs.getJSONObject("bars")
            val tf = Btc5mStruct.TFS.associate { (n, mins) ->
                val b = bo.getJSONObject(n)
                val t = l(b, "t")
                var v = DoubleArray(t.size); var tb = DoubleArray(t.size)
                if (n == "m1" && cs.has("spot")) {
                    // volume / taker-buy of the last 70 bars (all the flow features use)
                    val sp = cs.getJSONObject("spot"); val st = l(sp, "t"); val sv = d(sp, "v"); val stb = d(sp, "tb")
                    val at = t.indices.associateBy { t[it] }
                    for (i in st.indices) at[st[i]]?.let { v[it] = sv[i]; tb[it] = stb[i] }
                }
                n to Btc5mStruct.Bars(t, d(b, "o"), d(b, "h"), d(b, "l"), d(b, "c"), mins, v, tb)
            }
            val flow = if (cs.has("perp")) {
                val p = cs.getJSONObject("perp"); val r = cs.getJSONObject("prem")
                val pt = l(p, "t"); val rt = l(r, "t")
                Btc5mStruct.FlowIn(Btc5mStruct.Bars(pt, d(p, "c"), d(p, "c"), d(p, "c"), d(p, "c"), 1, d(p, "v"), d(p, "tb")),
                    Btc5mStruct.Bars(rt, d(r, "c"), d(r, "c"), d(r, "c"), d(r, "c"), 1))
            } else null
            val (x, _) = Btc5mStruct.featureVector(tf, T, m.k, if (m.flow) flow else null, m.gap)
            val want = d(cs, "feats")
            if (x.size != want.size) { bad += "case $k: ${x.size} features vs ${want.size}"; continue }
            for (i in x.indices) if (abs(x[i] - want[i]) > 1e-6 * maxOf(1.0, abs(want[i]))) bad += "case $k feat $i: ${x[i]} vs ${want[i]}"
            val out = cs.getJSONObject("out")
            val got = mapOf("up" to m.pUp(x), "y_close" to m.yClose.raw(x), "y_high" to m.yHigh.raw(x), "y_low" to m.yLow.raw(x))
            for ((n, v) in got) if (abs(v - out.getDouble(n)) > 1e-6) bad += "case $k $n: $v vs ${out.getDouble(n)}"
            val call = Btc5mStruct.evaluate(m, tf, T + 3_000, tf.getValue("m1").c.last(), flow = flow, mk = mk)
            if (abs(call.pUp - out.getDouble("up")) > 1e-9) bad += "case $k evaluate p: ${call.pUp}"
            if (call.roundStart != T) bad += "case $k round start ${call.roundStart} vs $T"
        }
        assertTrue("${mk.id}: " + bad.take(30).joinToString("\n"), bad.isEmpty())
    }
}

