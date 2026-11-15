package pk.aidesk.app.core

/**
 * Gold (XAUUSDT) signal, kept apart from the crypto signals: Asian-range breakout into London, 15m candles.
 *
 * Rules (same as the laptop backtest, AI Desk/xaulab/lab.py, the only gold idea that was positive on XAUUSDT):
 *  - Mon-Fri. Range = high/low of the 15m candles 00:00-07:00 UTC (05:00-12:00 PKT).
 *  - 07:00-12:00 UTC: the first 15m CLOSE outside the range in the direction of the 4h trend (EMA50/200 regime) = signal.
 *    A break against the 4h trend is ignored. One signal per day.
 *  - Entry next candle open, stop = middle of the range, no fixed target, exit at 20:00 UTC (01:00 PKT).
 * Backtest after 0.12% round-trip costs: XAUUSDT Dec 2025-Jun 2026 +0.27R/trade (34), Jul-Nov 2026 holdout +0.50R (33);
 * but on PAXG gold 2024-25 the same rule LOST -0.36R/trade (146). Not proven: signals only, no auto-trade.
 */
object Gold {
    const val SYMBOL = "XAUUSDT"
    private const val M15 = 900_000L
    private const val H = 3_600_000L
    const val RANGE_END_H = 7; const val LAST_ENTRY_H = 12; const val FLAT_H = 20
    const val BACKTEST = "Backtest (15m, after costs): XAUUSDT 2026 +0.27R dev / +0.50R holdout per trade, " +
        "but −0.36R on 2024-25 gold (PAXG). Not proven: signals only."

    class Sig(
        val day: Long,               // UTC midnight of the session
        val status: String,          // OFF (weekend) | FORMING | WATCH | SIGNAL | NONE (no signal today)
        val rangeHi: Double, val rangeLo: Double,
        val trend: Int,              // 4h regime now: 1 up, -1 down, 0 sideways
        val side: Int = 0,           // for SIGNAL: 1 long / -1 short
        val signalTime: Long = 0,    // close time of the breakout candle
        val entry: Double = 0.0, val sl: Double = 0.0,
        val exitAt: Long = 0,        // 20:00 UTC
        val result: String? = null,  // null = open, "SL" stop hit, "EXIT" closed at 20:00
        val resultPx: Double? = null,
        val price: Double = 0.0,
    ) {
        val sideName get() = if (side == 1) "LONG" else "SHORT"
        val mid get() = (rangeHi + rangeLo) / 2
        /** profit in R (risk = entry to stop) at price p, before costs */
        fun rAt(p: Double) = if (side == 0 || entry == sl) 0.0 else side * (p - entry) / kotlin.math.abs(entry - sl)
    }

    private fun regimeAt(h4: Candles, t: Long): Int {
        // 4h regime known at time t: closes of the 4h candles that had closed by t
        val n = h4.closeTime.count { it < t }
        return if (n < 205) 0 else Patterns.regime(h4.close.copyOfRange(0, n))
    }

    /** Pure evaluation on closed 15m candles + 4h candles (testable). */
    fun evaluate(c: Candles, h4: Candles, now: Long, price: Double): Sig {
        val day = now / 86_400_000L * 86_400_000L
        val dow = ((day / 86_400_000L) + 3) % 7          // 0 = Monday (1970-01-01 was a Thursday)
        val trendNow = regimeAt(h4, now)
        if (dow >= 5) return Sig(day, "OFF", 0.0, 0.0, trendNow, price = price)
        val rangeEnd = day + RANGE_END_H * H
        val idx = c.openTime.indices.filter { c.openTime[it] >= day }
        val rIdx = idx.filter { c.openTime[it] < rangeEnd }
        if (rIdx.isEmpty() || c.openTime[rIdx.first()] != day)
            return Sig(day, if (now < rangeEnd) "FORMING" else "NONE", 0.0, 0.0, trendNow, price = price)
        val hi = rIdx.maxOf { c.high[it] }; val lo = rIdx.minOf { c.low[it] }
        if (now < rangeEnd || rIdx.size < (RANGE_END_H * 4) * 3 / 4) return Sig(day, if (now < rangeEnd) "FORMING" else "NONE", hi, lo, trendNow, price = price)
        val flat = day + FLAT_H * H
        for (k in idx) {
            val t = c.openTime[k]
            if (t < rangeEnd) continue
            if (t >= day + LAST_ENTRY_H * H) break
            val side = if (c.close[k] > hi) 1 else if (c.close[k] < lo) -1 else 0
            if (side == 0 || regimeAt(h4, t + M15) != side) continue
            val sl = (hi + lo) / 2
            val nx = k + 1
            val entry = if (nx < c.close.size && c.openTime[nx] == t + M15) c.open[nx] else price
            // follow the trade on closed candles after entry
            var result: String? = null; var rpx: Double? = null
            var j = nx
            while (j < c.close.size && c.openTime[j] < flat) {
                if (if (side == 1) c.low[j] <= sl else c.high[j] >= sl) { result = "SL"; rpx = sl; break }
                j++
            }
            if (result == null && now >= flat) {
                val last = (0 until c.close.size).lastOrNull { c.openTime[it] + M15 <= flat }
                result = "EXIT"; rpx = last?.let { c.close[it] } ?: price
            }
            if (result == null && (price - sl) * side <= 0) { result = "SL"; rpx = sl }
            return Sig(day, "SIGNAL", hi, lo, trendNow, side, t + M15, entry, sl, flat, result, rpx, price)
        }
        return Sig(day, if (now < day + LAST_ENTRY_H * H) "WATCH" else "NONE", hi, lo, trendNow, price = price)
    }

    /** Fetch candles and evaluate. Blocking: call off the main thread. */
    fun scan(api: Binance, now: Long = System.currentTimeMillis()): Sig {
        val all = api.candles(SYMBOL, "15m", 200)
        val closedN = all.closeTime.count { it < now }
        val c = Advisor.slice(all, 0, closedN)
        val price = all.close.last()
        val h4 = api.candles(SYMBOL, "4h", 1000)        // long EMA200 warm-up: matches the backtest's 4h trend exactly
        return evaluate(c, h4, now, price)
    }
}
