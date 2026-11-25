package pk.aidesk.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pk.aidesk.app.core.Btc5mStruct
import pk.aidesk.app.core.PredictionApi

/** Market matching (fail closed, as in PredictAI V7.3.0) and the win-rate calibration. */
class PredictionApiTest {
    private val api = PredictionApi("k", "s")
    private val T = 1_795_285_800_000L

    private fun detail(start: Long, outcomes: List<Pair<String, String>>, title: String = "BTC Up or Down 5m") = JSONObject()
        .put("symbol", "BTCUSDT").put("chartType", "CRYPTO_UP_DOWN").put("startDate", start).put("endDate", start + 300_000L)
        .put("marketTopicId", 77).put("title", title).put("slippageBps", 1200)
        .put("markets", JSONArray().put(JSONObject().put("marketId", 1).put("title", title).put("tradingStatus", "OPEN")
            .put("outcomes", JSONArray().apply { outcomes.forEach { (n, id) -> put(JSONObject().put("name", n).put("tokenId", id).put("price", "0.51")) } })))

    @Test
    fun picksUpAndDownTokens() {
        val m = api.parseMarket(detail(T, listOf("Up" to "tUP", "Down" to "tDN")), T)!!
        assertEquals("tUP", m.up.tokenId)
        assertEquals("tDN", m.down.tokenId)
        assertEquals(0.51, m.up.price!!, 1e-9)
    }

    @Test
    fun rejectsWrongRoundAndAmbiguousMarkets() {
        assertNull(api.parseMarket(detail(T + 300_000L, listOf("Up" to "a", "Down" to "b")), T))      // next round
        assertNull(api.parseMarket(detail(T, listOf("Yes" to "a", "No" to "b")), T))                    // "Up or Down" + YES/NO
        assertNull(api.parseMarket(detail(T, listOf("Up" to "a", "Down" to "a")), T))                   // same token
    }

    @Test
    fun calibrationIsMonotoneAndBounded() {
        val m = Btc5mStruct.load(javaClass.classLoader!!.getResource("btc5m_struct.json")!!.readText())
        var last = 0.0
        for (k in 0..50) {
            val p = m.winProb(0.5 + k * 0.01)
            assertTrue("p $p at ${0.5 + k * 0.01}", p >= last - 1e-12 && p in 0.45..0.8)   // TWAP model (2026-11-25) calibrates up to ~72%
            last = p
        }
    }
}
