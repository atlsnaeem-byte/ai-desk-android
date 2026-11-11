package pk.aidesk.app.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Manual-trading analyst (no AI service): chart patterns + trend + BTC + positioning -> plain-language read and
 * concrete trade plans (side, order type, entry, stop-loss, take-profit) ranked by a transparent score.
 *
 * Research behind the rules (laptop backtests, 20 coins, 2023-2026): pattern breakouts alone had no reliable edge;
 * taking only the side that matches the higher-timeframe trend and entering on a retest was the least-bad variant.
 * The score therefore rewards trend agreement, retests, clean patterns and good reward/risk, and penalises crowded positioning.
 */
object Advisor {
    val TFS = listOf("5m", "15m", "1h", "4h")
    val HIGHER = mapOf("5m" to "1h", "15m" to "4h", "1h" to "4h", "4h" to "1d")
    val TF_MS = mapOf("5m" to 300_000L, "15m" to 900_000L, "1h" to 3_600_000L, "4h" to 14_400_000L, "1d" to 86_400_000L)

    class Plan(
        val side: String,            // LONG | SHORT
        val type: String,            // LIMIT | STOP | MARKET
        val entry: Double, val sl: Double, val tp: Double,
        val setup: String,           // short title, e.g. "Ascending triangle breakout"
        val why: List<String>,       // reasons (+) and warnings (-)
        val score: Int,
    ) {
        val rr: Double get() = abs(tp - entry) / max(abs(entry - sl), 1e-12)
        val grade: String get() = when {
            score >= 5 -> "Strong"
            score >= 3 -> "Good"
            score >= 1 -> "Weak"
            else -> "Avoid"
        }
    }

    class Analysis(
        val symbol: String, val tf: String, val price: Double,
        val closed: Candles, val forming: Candles?,              // forming = last (unfinished) candle, may be null
        val scan: Patterns.Result, val ema20: DoubleArray, val ema50: DoubleArray,
        val trendHigher: Int, val trendLocal: Int, val btcTrend: Int?, val positioning: Map<String, Double?>,
        val plans: List<Plan>, val summary: List<String>, val time: Long, val conf: Confluence.Setup? = null,
    ) {
        val best: Plan? get() = plans.firstOrNull()
        val atr: Double get() = scan.atr.lastOrNull() ?: 0.0
    }

    fun f(v: Double): String = when {                      // same as Engine.fmt (kept here so this file has no Android deps)
        v == 0.0 -> "0"
        abs(v) >= 1000 -> String.format(Locale.US, "%,.1f", v)
        abs(v) >= 1 -> String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
        else -> String.format(Locale.US, "%.6g", v)
    }
    private fun p1(v: Double) = String.format(Locale.US, "%+.1f", v)
    fun trendWord(t: Int?) = when (t) { 1 -> "up"; -1 -> "down"; 0 -> "sideways"; else -> "-" }

    /** Fetch everything and analyse. Blocking: call off the main thread. */
    fun load(api: Binance, symbol: String, tf: String, bars: Int = 500): Analysis {
        val all = api.candles(symbol, tf, bars + 1)
        val n = all.size
        val closed = slice(all, 0, n - 1)
        val forming = slice(all, n - 1, n)
        val hi = api.candles(symbol, HIGHER.getValue(tf), 260).let { slice(it, 0, it.size - 1) }
        val btc = if (symbol == "BTCUSDT") null else runCatching {
            api.candles("BTCUSDT", HIGHER.getValue(tf), 260).let { slice(it, 0, it.size - 1) }
        }.getOrNull()
        val pos = runCatching { api.positioning(symbol) }.getOrDefault(emptyMap())
        return analyze(symbol, tf, closed, forming, Patterns.regime(hi.close), btc?.let { Patterns.regime(it.close) }, pos)
    }

    fun slice(c: Candles, from: Int, to: Int): Candles = Candles(
        c.openTime.copyOfRange(from, to), c.open.copyOfRange(from, to), c.high.copyOfRange(from, to), c.low.copyOfRange(from, to),
        c.close.copyOfRange(from, to), c.volume.copyOfRange(from, to), c.takerBuyBase.copyOfRange(from, to), c.closeTime.copyOfRange(from, to))

    /** Pure analysis (testable). */
    fun analyze(symbol: String, tf: String, closed: Candles, forming: Candles?, trendHigher: Int, btcTrend: Int?,
                pos: Map<String, Double?>): Analysis {
        val res = Patterns.scan(closed)
        val c = closed.close
        val e20 = Patterns.ema(c, 20); val e50 = Patterns.ema(c, 50)
        val last = c.size - 1
        val price = forming?.close?.lastOrNull() ?: c[last]
        val a = res.atr[last]
        val local = Patterns.regime(c)
        val cand = ArrayList<Plan>()
        val minRisk = price * 0.003                    // keep the stop at least 0.3% away (fees ~0.1% round trip)
        val nx = last + 1                              // line values for the next (forming) bar

        fun plan(side: Int, type: String, entry0: Double, sl0: Double, tp0: Double, setup: String, base: List<String>,
                 classic: Boolean, touches: Int, retest: Boolean): Plan? {
            var entry = entry0; var sl = sl0; var tp = tp0
            if (type == "MARKET") entry = price
            if ((entry - sl) * side < minRisk) sl = entry - side * minRisk
            if ((tp - entry) * side <= 0) return null
            val why = ArrayList(base)
            var s = 0
            when {
                trendHigher == side -> { s += 2; why += "+ ${HIGHER[tf]} trend is ${trendWord(trendHigher)} (with the trade)" }
                trendHigher == -side -> { s -= 2; why += "- ${HIGHER[tf]} trend is ${trendWord(trendHigher)} (against the trade)" }
                else -> why += "· ${HIGHER[tf]} trend is sideways"
            }
            if (local == side) { s += 1; why += "+ $tf EMA50/200 trend agrees" }
            if (btcTrend != null) {
                if (btcTrend == side) { s += 1; why += "+ BTC ${HIGHER[tf]} trend agrees" }
                else if (btcTrend == -side) { s -= 1; why += "- BTC ${HIGHER[tf]} trend is against it" }
            }
            if (classic) { s += 1; why += "+ breaks in the pattern's usual direction" }
            if (touches >= 5) { s += 1; why += "+ clean pattern ($touches touches)" }
            if (retest) { s += 1; why += "+ retest entry (better price, tighter stop)" }
            val fr = pos["funding_pct"]; val ls = pos["ls_accounts"]; val oi = pos["oi_chg_24h_pct"]
            if (fr != null && side == 1 && fr > 0.05) { s -= 1; why += "- funding ${String.format(Locale.US, "%+.3f", fr)}%: longs crowded" }
            if (fr != null && side == -1 && fr < -0.05) { s -= 1; why += "- funding ${String.format(Locale.US, "%+.3f", fr)}%: shorts crowded" }
            if (ls != null && side == 1 && ls > 2.5) { s -= 1; why += "- ${f2(ls)} long accounts per short: crowded long" }
            if (ls != null && side == -1 && ls < 0.7) { s -= 1; why += "- ${f2(ls)} long/short accounts: crowded short" }
            if (oi != null && abs(oi) >= 10) why += "· open interest ${p1(oi)}% in 24h"
            val rr = abs(tp - entry) / abs(entry - sl)
            if (rr >= 2) { s += 1; why += "+ reward/risk ${f2(rr)}" } else if (rr < 1.2) { s -= 2; why += "- reward/risk only ${f2(rr)}" }
            return Plan(if (side == 1) "LONG" else "SHORT", type, entry, sl, tp, setup, why, s)
        }

        // 1) patterns waiting for a breakout: stop order just beyond the line
        for (p in res.active) {
            for ((side, line) in listOf(1 to p.up, -1 to p.dn)) {
                line ?: continue
                if (p.bias != 0 && p.bias != side) continue           // e.g. no long off a double top's neckline
                val lv = line.at(nx)
                if ((price - lv) * side >= 0) continue                 // already through the line: handled as breakout
                val entry = lv + side * 0.1 * a
                plan(side, "STOP", entry, lv - side * 1.0 * a, lv + side * p.height,
                    "${p.name} ${if (side == 1) "breakout" else "breakdown"}",
                    listOf("· $tf ${p.name}: ${if (side == 1) "buy" else "sell"} if price crosses ${f(entry)} (line ${f(lv)})",
                        "· measured move = pattern height ${f(p.height)}"), p.bias == side, p.touches, false)?.let { cand += it }
            }
        }
        // 2) fresh breakouts (last 8 candles): limit order on the retest of the broken line
        for (b in res.breakouts.filter { it.i >= last - 8 }) {
            val side = b.side
            val lineNow = (if (side == 1) b.pat.up else b.pat.dn)?.at(nx) ?: b.level
            val tp = b.level + side * b.height
            if ((price - tp) * side >= 0) continue                     // target already reached
            if ((price - (lineNow - side * 0.5 * a)) * side < 0) continue   // fell back inside: failed breakout
            val entry = lineNow + side * 0.25 * a
            val type = if ((price - entry) * side <= 0) "MARKET" else "LIMIT"
            plan(side, type, entry, lineNow - side * 1.0 * a, tp,
                "${b.pat.name} ${if (side == 1) "breakout" else "breakdown"} retest",
                listOf("· broke ${if (side == 1) "up" else "down"} ${last - b.i + 1} candle(s) ago (volume ${f2(b.volRatio)}× average)",
                    "· wait for price to come back to ${f(entry)}"), b.pat.bias == side || b.pat.bias == 0 && side == trendHigher,
                b.pat.touches, true)?.let { cand += it }
        }
        // 3) no pattern: pullback to EMA20 in the direction of the higher trend
        if (trendHigher != 0) {
            val side = trendHigher
            val e = e20[last]
            if ((price - e) * side > 0) {
                val sl = e - side * 1.5 * a
                plan(side, "LIMIT", e, sl, e + side * 2 * abs(e - sl), "Trend pullback to EMA20",
                    listOf("· ${HIGHER[tf]} trend ${trendWord(side)}: buy the dip / sell the rally at the 20-EMA ${f(e)}"),
                    false, 0, true)?.let { cand += it }
            }
        }
        // 4) TA-course confluence setup on the last closed candle (RSI divergence / S-R bounce / range fade)
        val conf = Confluence.evaluate(closed, trendHigher, res.active)
        val confPlan = conf?.let { s ->
            val side = s.side
            if ((price - s.sl) * side <= 0 || (s.tp - price) * side <= 0) null     // price already beyond a level
            else Plan(s.sideName, "MARKET", price, s.sl, s.tp, "Course setup: ${s.title}",
                s.why + Confluence.backtestNote(tf, s.kinds), s.score)
        }
        val plans = (listOfNotNull(confPlan) +
            cand.sortedWith(compareByDescending<Plan> { it.score }.thenByDescending { it.rr }).distinctBy { it.setup + it.side }).take(4)

        // plain-language summary
        val sum = ArrayList<String>()
        sum += "$symbol ${f(price)} · $tf trend ${trendWord(local)}, ${HIGHER[tf]} trend ${trendWord(trendHigher)}" +
            (btcTrend?.let { ", BTC ${trendWord(it)}" } ?: "")
        if (res.active.isEmpty()) sum += "No chart pattern forming on $tf right now."
        for (p in res.active) {
            val bits = ArrayList<String>()
            p.up?.let { bits += "breakout above ${f(it.at(nx))} (${p1((it.at(nx) / price - 1) * 100)}%)" }
            p.dn?.let { bits += "breakdown below ${f(it.at(nx))} (${p1((it.at(nx) / price - 1) * 100)}%)" }
            sum += "${p.name} (${p.touches} touches): ${bits.joinToString(", ")}"
        }
        res.breakouts.lastOrNull()?.let { b ->
            sum += "Last breakout: ${b.pat.name} ${if (b.side == 1) "up" else "down"} ${last - b.i + 1} candle(s) ago at ${f(b.level)}"
        }
        val fr = pos["funding_pct"]; val oi = pos["oi_chg_24h_pct"]; val ls = pos["ls_accounts"]
        val pb = ArrayList<String>()
        fr?.let { pb += "funding ${String.format(Locale.US, "%+.4f", it)}%" }
        oi?.let { pb += "OI ${p1(it)}% 24h" }
        ls?.let { pb += "long/short accounts ${f2(it)}" }
        pos["taker_bs"]?.let { pb += "taker buy/sell ${f2(it)}" }
        if (pb.isNotEmpty()) sum += "Positioning: " + pb.joinToString(", ")
        val rsiNow = Confluence.rsi(c).last()
        sum += "RSI ${String.format(Locale.US, "%.0f", rsiNow)}" + when { rsiNow > 70 -> " (overbought)"; rsiNow < 30 -> " (oversold)"; else -> "" } +
            " · course setup on the last $tf candle: " +
            (conf?.let { "${it.sideName} ${it.title}, confluence score ${it.score}" } ?: "none")
        sum += when {
            confPlan != null && conf != null && tf in Confluence.TFS && conf.kinds.any { it in Confluence.SIGNAL_KINDS } ->
                "Verdict: course signal ${confPlan.side} — ${conf.title} (score ${conf.score})."
            plans.isEmpty() -> "Verdict: no clean setup. Wait."
            plans[0].score >= 3 -> "Verdict: ${plans[0].grade.lowercase()} ${plans[0].side} setup — ${plans[0].setup}."
            plans[0].score >= 1 -> "Verdict: only weak setups. Small size or wait."
            else -> "Verdict: setups fight the trend. Avoid."
        }
        return Analysis(symbol, tf, price, closed, forming, res, e20, e50, trendHigher, local, btcTrend, pos, plans, sum,
            System.currentTimeMillis(), conf)
    }

    private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
}
