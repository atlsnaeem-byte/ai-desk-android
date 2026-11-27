package pk.aidesk.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pk.aidesk.app.core.Btc5mStructBook
import pk.aidesk.app.core.PredictionApi
import pk.aidesk.app.core.Staking
import kotlin.math.abs

/** stake_fixture.json comes from Documents/BTC Structure 5m AI/mk_stake_fixture.py: units of the Python backtest schemes S3, S4, S5
 *  (exp6.py schemes()) for base stake 1 and bankroll 100. The app must size every bet exactly like the backtest. */
class StakingTest {
    private val fx = JSONObject(javaClass.classLoader!!.getResource("stake_fixture.json")!!.readText()).getJSONArray("cases")

    @Test
    fun matchesPythonBacktestSchemes() {
        val bad = mutableListOf<String>()
        for (i in 0 until fx.length()) {
            val c = fx.getJSONObject(i); val p = c.getDouble("p"); val px = c.getDouble("price")
            assertTrue("edge", abs(Staking.edge(p, px) - c.getDouble("edge")) < 1e-9)
            val t = Staking.raw("tiers", 1.0, p, px, 10.0, 3.0, 0.25, 4.0, 100.0)
            val k = Staking.raw("kelly", 1.0, p, px, 10.0, 3.0, 0.25, 4.0, 100.0)
            val e = Staking.raw("edge", 1.0, p, px, 10.0, 3.0, 0.25, 4.0, 100.0)
            if (abs(t - c.getDouble("s3")) > 1e-9) bad += "case $i tiers $t vs ${c.getDouble("s3")} (p=$p px=$px)"
            if (abs(k - c.getDouble("s4")) > 1e-9) bad += "case $i kelly $k vs ${c.getDouble("s4")} (p=$p px=$px)"
            if (abs(e - c.getDouble("s5")) > 1e-9) bad += "case $i edge $e vs ${c.getDouble("s5")} (p=$p px=$px)"
        }
        assertTrue(bad.take(20).joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun kellyScalesWithBankrollAndIsCapped() {
        // p 0.65 at 50c: edge 27.4%, b 0.96, kelly 0.2854 -> quarter = 7.1% of bankroll -> capped at 4%
        assertEquals(4.0, Staking.raw("kelly", 1.0, 0.65, 0.50, 10.0, 3.0, 0.25, 4.0, 100.0), 1e-9)
        assertEquals(8.0, Staking.raw("kelly", 1.0, 0.65, 0.50, 10.0, 3.0, 0.25, 4.0, 200.0), 1e-9)
        // small edge stays below the cap: p 0.60 at 58c: edge 1.38%, b 0.6897, kelly 0.02, quarter 0.5%
        val want = 100 * 0.25 * (0.60 * 0.98 / 0.58 - 1) / (0.98 / 0.58 - 1)
        assertEquals(want, Staking.raw("kelly", 1.0, 0.60, 0.58, 10.0, 3.0, 0.25, 4.0, 100.0), 1e-9)
        assertTrue(want < 4.0)
        // no balance -> no bet
        assertEquals(0.0, Staking.raw("kelly", 1.0, 0.65, 0.50, 10.0, 3.0, 0.25, 4.0, 0.0), 0.0)
    }

    @Test
    fun noEdgeMeansNoBetAndFlatIgnoresPrice() {
        for (m in listOf("edge", "tiers", "kelly")) assertEquals("$m at 66c", 0.0, Staking.sized(m, 1.5, 0.60, 0.66, true, 10.0, 3.0, 0.25, 4.0, 100.0), 0.0)
        assertEquals(1.5, Staking.sized("flat", 1.5, 0.60, 0.66, true, 10.0, 3.0, 0.25, 4.0, 100.0), 0.0)
        // no price known: the base stake, as before
        for (m in listOf("edge", "tiers", "kelly")) assertEquals(2.0, Staking.sized(m, 2.0, 0.60, null, false, 10.0, 3.0, 0.25, 4.0, 100.0), 0.0)
        assertEquals(1.0, Staking.sized("unknown", 1.0, 0.60, 0.50, false, 10.0, 3.0, 0.25, 4.0, 100.0), 0.0)
    }

    @Test
    fun exchangeFloorAppliesToLiveAndPaper() {
        // tiers half tier on base 1.5: 0.75 -> LIVE floor 1.5, PAPER floor 0.1 keeps 0.75
        val p = 0.62; val px = 0.60                    // edge 1.27% -> half tier
        assertEquals(1.5, Staking.sized("tiers", 1.5, p, px, true, 10.0, 3.0, 0.25, 4.0, 100.0), 1e-9)
        assertEquals(0.75, Staking.sized("tiers", 1.5, p, px, false, 10.0, 3.0, 0.25, 4.0, 100.0), 1e-9)
        // kelly on a 20 USDT bankroll: 4% cap = 0.8 -> LIVE floor 1.5
        assertEquals(1.5, Staking.sized("kelly", 1.5, 0.65, 0.50, true, 10.0, 3.0, 0.25, 4.0, 20.0), 1e-9)
    }

    @Test
    fun editModeUnchangedFromBuild38() {
        // old formula: (stake x edge x 100 / unit) clamped to [lo, max(lo, stake x cap)]
        for (i in 0 until fx.length()) {
            val c = fx.getJSONObject(i); val p = c.getDouble("p"); val px = c.getDouble("price"); val e = c.getDouble("edge")
            for (base in listOf(1.5, 3.0)) for (live in listOf(true, false)) {
                val lo = if (live) 1.5 else 0.1
                val old = if (e <= 0.0) 0.0 else (base * e * 100 / 10.0).coerceIn(lo, maxOf(lo, base * 3.0))
                assertEquals("p=$p px=$px base=$base live=$live", old, Staking.sized("edge", base, p, px, live, 10.0, 3.0, 0.25, 4.0, 100.0), 1e-9)
            }
        }
    }

    @Test
    fun configRoundTripKeepsNewFields() {
        val c = Btc5mStructBook.Cfg(stakeMode = "kelly", kellyFrac = 0.5, kellyCapPct = 6.0, bankroll = 250.0)
        val r = Btc5mStructBook.Cfg.of(c.json())
        assertEquals("kelly", r.stakeMode); assertEquals(0.5, r.kellyFrac, 0.0); assertEquals(6.0, r.kellyCapPct, 0.0); assertEquals(250.0, r.bankroll, 0.0)
        // an old saved config (build 38) has none of the new keys: defaults
        val old = Btc5mStructBook.Cfg.of(JSONObject().put("v", 5).put("stakeMode", "edge"))
        assertEquals("edge", old.stakeMode); assertEquals(0.25, old.kellyFrac, 0.0); assertEquals(4.0, old.kellyCapPct, 0.0); assertEquals(100.0, old.bankroll, 0.0)
    }

    @Test
    fun s4PresetIsTheBacktestRules() {
        val d = Btc5mStructBook.Cfg(thrPct = 60.0, minEdgePct = 5.0, maxPriceCents = 60.0, hoursMask = Btc5mStructBook.Cfg.SKIP_WEAK, early = true, stakeMode = "flat")
        val s = Btc5mStructBook.Cfg.s4Backtest(d)
        assertEquals("kelly", s.stakeMode); assertEquals(0.25, s.kellyFrac, 0.0); assertEquals(4.0, s.kellyCapPct, 0.0); assertEquals(100.0, s.bankroll, 0.0)
        assertEquals(50.0, s.thrPct, 0.0); assertEquals(0.0, s.minEdgePct, 0.0); assertEquals(0.0, s.minP, 0.0); assertEquals(0.0, s.maxPriceCents, 0.0)
        assertTrue(s.allHours); assertTrue(!s.early)
        // a call at 50% confidence is taken (every round), and the stake there is the backtest S4 unit
        assertEquals(50.0, s.thr * 100, 1e-9)
        assertEquals(Staking.raw("kelly", 1.0, 0.62, 0.55, 10.0, 3.0, 0.25, 4.0, 100.0), Staking.sized("kelly", s.stake, 0.62, 0.55, false, s.edgeUnitPct, s.stakeCapX, s.kellyFrac, s.kellyCapPct, s.bankroll), 1e-12)
    }

    @Test
    fun bankrollIsTheLargestSpendableBalance() {
        val api = PredictionApi("k", "s")
        val bal = listOf(PredictionApi.Balance("SPOT", 40.0, true), PredictionApi.Balance("FUNDING", 90.0, true), PredictionApi.Balance("CeDeFi", 12.0, true))
        assertEquals(90.0, api.bankroll(bal, null), 0.0)
        assertEquals(120.0, api.bankroll(bal, 120.0), 0.0)
        assertEquals(40.0, api.bankroll(listOf(PredictionApi.Balance("SPOT", 40.0, true), PredictionApi.Balance("FUNDING", 90.0, false)), null), 0.0)
        assertEquals(0.0, api.bankroll(emptyList(), null), 0.0)
    }
}
