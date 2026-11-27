package pk.aidesk.app.core

/**
 * Stake sizing rules for the Predict tab (2026-11-27). Same formulas as the Python backtest (exp6.py schemes S3, S4, S5), so the
 * November / 2-year backtest numbers describe exactly what the app does.
 *
 *   edge  = p x (1 - fee) / price - 1            p = calibrated win rate of the called side, price = cost of one share (0..1)
 *   b     = (1 - fee) / price - 1                net profit per 1 USDT if the call wins
 *
 *   flat   : the base stake                                                   (S0-S2: flat 1 USD, with or without a min-edge rule)
 *   edge   : base x edge / edgeUnit, at most capX x base                      (S5: 1 per 10% edge, cap 3)
 *   tiers  : edge < 0: no bet, 0-5%: 0.5 x base, 5-10%: 1 x base, >= 10%: 2 x base      (S3)
 *   kelly  : bankroll x kellyFrac x edge / b, at most bankroll x kellyCapPct%  (S4: quarter-Kelly, cap 4% of the bankroll)
 *
 * raw() returns USDT before the exchange floor; 0 = no bet. The floor (LIVE minimum 1.5 USDT) is applied by the caller.
 */
object Staking {
    const val FEE = PredictionApi.FEE_BPS / 10_000.0
    val MODES = listOf("flat", "edge", "tiers", "kelly")

    fun edge(p: Double, price: Double) = p * (1 - FEE) / price - 1

    fun raw(mode: String, base: Double, p: Double, price: Double, edgeUnitPct: Double, capX: Double,
            kellyFrac: Double, kellyCapPct: Double, bankroll: Double): Double {
        if (!(price > 0.0) || !(price < 1.0) || p.isNaN()) return if (mode == "flat") base else 0.0
        val e = edge(p, price)
        return when (mode) {
            "edge" -> if (e <= 0.0) 0.0 else minOf(base * e * 100 / edgeUnitPct, base * capX)
            "tiers" -> if (e < 0.0) 0.0 else base * (if (e < 0.05) 0.5 else if (e < 0.10) 1.0 else 2.0)
            "kelly" -> {
                val b = (1 - FEE) / price - 1
                if (e <= 0.0 || !(b > 0.0) || !(bankroll > 0.0)) 0.0
                else minOf(bankroll * kellyFrac * (e / b), bankroll * kellyCapPct / 100)
            }
            else -> base
        }
    }

    /** raw() plus the exchange floor (LIVE 1.5 USDT, PAPER 0.1). flat / unknown mode / no price: the base stake. 0 = no bet. */
    fun sized(mode: String, base: Double, p: Double, price: Double?, live: Boolean, edgeUnitPct: Double, capX: Double,
              kellyFrac: Double, kellyCapPct: Double, bankroll: Double): Double {
        if (mode == "flat" || mode !in MODES || price == null || !(price > 0.0)) return base
        val r = raw(mode, base, p, price, edgeUnitPct, capX, kellyFrac, kellyCapPct, bankroll)
        if (r <= 0.0) return 0.0
        return maxOf(if (live) 1.5 else 0.1, r)
    }

    fun title(mode: String) = when (mode) {
        "edge" -> "By edge"
        "tiers" -> "Edge tiers"
        "kelly" -> "Quarter-Kelly"
        else -> "Flat"
    }
}
