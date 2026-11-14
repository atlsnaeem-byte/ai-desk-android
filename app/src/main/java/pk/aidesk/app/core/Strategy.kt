package pk.aidesk.app.core

import kotlin.math.max
import kotlin.math.min

/** Settings shared by the engine and the UI (stored in Store). */
data class Config(
    val mode: String = "paper",            // paper | testnet | live
    val auto: Boolean = false,
    val symbols: List<String> = DEFAULT_SYMBOLS,
    val leverage: Int = 10,
    val marginType: String = "ISOLATED",
    val riskPct: Double = 20.0,            // 1..50 % of equity lost at the stop
    val maxOpen: Int = 3,
    val dailyLossPct: Double = 6.0,
    val dailyProfitPct: Double = 100.0,
    val dailyLossUsdt: Double = 0.0,       // absolute daily loss limit in USDT, 0 = off
    val dailyProfitUsdt: Double = 0.0,     // absolute daily profit target in USDT, 0 = off
    val minEvR: Double = 0.10,
    val soundMinProb: Double = 60.0,       // alert sound only when win chance >= this
    val quietStart: Int = -1,              // hour 0-23 PKT, -1 = off
    val quietEnd: Int = -1,
    val feeRate: Double = 0.0005,
    val slippagePct: Double = 0.02,
    val paperStartEquity: Double = 1000.0,
    // fixed by the trained model
    val tpAtr: Double = 2.0,
    val slAtr: Double = 1.0,
    val horizonBars: Int = 16,
) {
    companion object {
        val DEFAULT_SYMBOLS_V1 = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT", "DOGEUSDT",
            "ADAUSDT", "LINKUSDT", "AVAXUSDT", "SUIUSDT")
        val DEFAULT_SYMBOLS = DEFAULT_SYMBOLS_V1
        const val BAR_MS = 900_000L
    }
}

/**
 * Isolated-margin liquidation, used by every PAPER simulation (live positions are liquidated by Binance itself).
 * Binance liquidates when the loss eats the margin down to the maintenance margin (~0.5-2.5% of the position on
 * small coins; 1% used here). The whole margin is lost: the trade is booked at the bankruptcy price.
 */
object Liq {
    const val MMR = 0.01

    /** Price move against the position (%) that liquidates it at this leverage. */
    fun distPct(lev: Double): Double = (100.0 / lev.coerceAtLeast(1.0) - MMR * 100).coerceAtLeast(0.1)

    /** Liquidation trigger price. */
    fun trigger(side: String, entry: Double, lev: Double): Double {
        val dr = if (side == "LONG") 1 else -1
        return entry * (1 - dr * distPct(lev) / 100)
    }

    /** Price at which the full margin is gone (where a liquidated paper trade is booked). */
    fun bankrupt(side: String, entry: Double, lev: Double): Double {
        val dr = if (side == "LONG") 1 else -1
        return entry * (1 - dr / lev.coerceAtLeast(1.0))
    }

    /** True if the liquidation price is reached before the stop-loss (stop is beyond liquidation). */
    fun beforeStop(side: String, entry: Double, lev: Double, sl: Double?): Boolean {
        val liq = trigger(side, entry, lev)
        return sl == null || (if (side == "LONG") liq > sl else liq < sl)
    }

    /** Warning text when a stop of [slPct]% is beyond the liquidation distance at [lev]x, else null. */
    fun warn(slPct: Double, lev: Int, what: String): String? {
        val d = distPct(lev.toDouble())
        return if (slPct >= d) String.format(java.util.Locale.US,
            "%s: at %dx the position is LIQUIDATED after about %.1f%% against you, before the %.0f%% stop. You lose the whole margin. Use %dx or less, or a tighter stop.",
            what, lev, d, slPct, maxOf(1, (100.0 / (slPct + MMR * 100 + 1)).toInt())) else null
    }
}

data class Decision(
    val side: String, val prob: Double, val evR: Double, val entry: Double,
    val sl: Double, val tp: Double, val slDist: Double, val tpDist: Double,
)

object Strategy {
    /** strategy.decide(): EV in R = p*(TP/SL) - (1-p) - costs. Returns null below minEv. */
    fun decide(pLong: Double, pShort: Double, price: Double, atr: Double, cfg: Config, minEv: Double = cfg.minEvR): Decision? {
        if (!(atr > 0 && price > 0)) return null
        val tpD = cfg.tpAtr * atr
        val slD = cfg.slAtr * atr
        val costR = (2 * cfg.feeRate + 2 * cfg.slippagePct / 100) * price / slD
        val rr = tpD / slD
        val evL = pLong * rr - (1 - pLong) - costR
        val evS = pShort * rr - (1 - pShort) - costR
        val long = evL >= evS
        val ev = if (long) evL else evS
        val p = if (long) pLong else pShort
        if (ev < minEv) return null
        return if (long) Decision("LONG", p, ev, price, price - slD, price + tpD, slD, tpD)
        else Decision("SHORT", p, ev, price, price + slD, price - tpD, slD, tpD)
    }

    /** strategy.position_size() in "risk" mode, with risk % given explicitly. */
    fun positionSize(equity: Double, entry: Double, slDist: Double, cfg: Config, riskPct: Double = cfg.riskPct): Double {
        val qty = equity * riskPct / 100 / slDist
        val cap = equity * cfg.leverage / max(1, cfg.maxOpen) / entry
        return max(0.0, min(qty, cap))
    }
}
