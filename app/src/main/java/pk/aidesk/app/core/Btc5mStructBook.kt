package pk.aidesk.app.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Record + auto trader for the structure AI (one call per round, made at the round start).
 *
 * Decision path, the same for PAPER and LIVE (taken from the user's PredictAI V7.3.0):
 *   call (P(UP) past the call threshold) -> backtest win rate p for that confidence -> executable Binance quote
 *   -> EV gate: p >= min p AND (p * shares - cost) / cost >= min edge (cost = amountIn + 2 % fee)
 *   -> hard risk limits (kill switch, daily loss, max trades/day, loss-streak pause) -> execute.
 * Only the last step differs: LIVE places a Binance Prediction order, PAPER records the same fill without ordering.
 * Without an API key, PAPER prices the fill at the "paper price" + 2 % fee.
 * LIVE safety: the round is marked as traded BEFORE the order is sent; a definite 4xx rejection unmarks it;
 * an unknown outcome keeps it marked, so a round can never be bought twice.
 * Settlement: Binance spot 1m bars (proxy for the market's Chainlink price): UP if close > open, DOWN if close < open,
 * a tie pays 0.5 per share.
 */
class Btc5mStructBook(private val store: Store, val mk: Btc5mStruct.Mk = Btc5mStruct.BTC5) {
    data class Cfg(
        val auto: Boolean = true,          // background loop: call + auto bet every round
        val mode: String = "paper",        // paper | live
        val stake: Double = 1.0,           // USDT per bet (LIVE minimum 1.5)
        val thrPct: Double = 60.0,         // call when P(UP) >= 60% -> UP, <= 40% -> DOWN
        val paperCents: Double = 50.0,     // paper fill price when no Binance quote is available
        val alerts: Boolean = true,
        val minP: Double = 0.0,            // min backtest win rate of the called side (0 = off)
        val minEdgePct: Double = 3.0,      // min EV / cost after the fee (-100 = off)
        val windowSec: Int = 20,           // enter only in the first N seconds of the round
        val useQuotes: Boolean = true,     // PAPER uses real Binance quotes when an API key is saved
        val armed: Boolean = false,        // LIVE orders allowed
        val killSwitch: Boolean = false,
        val dailyLossUsdt: Double = 0.0,   // 0 = off
        val maxTradesDay: Int = 0,         // 0 = off
        val lossStreak: Int = 0,           // pause after N losses in a row, 0 = off
        val pauseMin: Int = 60,
        val early: Boolean = false,        // call BEFORE the round starts (from the still-forming candles)
        val leadSec: Int = 30,             // ... this many seconds before (10 or 30)
        val hoursMask: Int = ALL_HOURS,    // hour clusters (PKT) to bet in: bits 0-5 = weekday 4h blocks 00-04..20-24, bits 6-11 = weekend
        val maxPriceCents: Double = 0.0,   // hard cap: never pay more than this many cents per share (0 = off)
        val stakeMode: String = "flat",    // flat | edge | tiers | kelly (rules in Staking.kt; LIVE floor 1.5)
        val edgeUnitPct: Double = 10.0,
        val stakeCapX: Double = 3.0,
        val orderTries: Int = 3,           // LIVE: a killed (not filled) FOK order is re-quoted and sent again, up to this many orders per round
        val kellyFrac: Double = 0.25,      // kelly mode: fraction of the full Kelly stake (0.25 = quarter-Kelly)
        val kellyCapPct: Double = 4.0,     // kelly mode: one bet is at most this % of the bankroll
        val bankroll: Double = 100.0,      // kelly mode, PAPER: bankroll in USDT (LIVE uses the real Prediction balance)
    ) {
        val thr get() = thrPct / 100
        /** Is the round starting at T inside the selected hour clusters? (PKT: Mon-Fri = weekday, Sat-Sun = weekend) */
        fun inHours(T: Long) = hoursMask and (1 shl bit(T)) != 0
        val allHours get() = hoursMask and ALL_HOURS == ALL_HOURS
        fun json() = JSONObject().put("v", 5).put("auto", auto).put("mode", mode).put("stake", stake).put("thrPct", thrPct)
            .put("paperCents", paperCents).put("alerts", alerts).put("minP", minP).put("minEdgePct", minEdgePct)
            .put("windowSec", windowSec).put("useQuotes", useQuotes).put("armed", armed).put("killSwitch", killSwitch)
            .put("dailyLossUsdt", dailyLossUsdt).put("maxTradesDay", maxTradesDay).put("lossStreak", lossStreak).put("pauseMin", pauseMin).put("early", early).put("leadSec", leadSec).put("hoursMask", hoursMask).put("maxPriceCents", maxPriceCents)
            .put("stakeMode", stakeMode).put("edgeUnitPct", edgeUnitPct).put("stakeCapX", stakeCapX).put("orderTries", orderTries)
            .put("kellyFrac", kellyFrac).put("kellyCapPct", kellyCapPct).put("bankroll", bankroll)
        companion object {
            const val ALL_HOURS = 0xFFF
            /** 180-day BTC 5m scan: weekday 08-12 and 20-24 PKT were weakest on BTC and ETH in both halves */
            const val SKIP_WEAK = 0xFDB        // all bits except weekday 08-12 (bit 2) and 20-24 (bit 5)
            /** only 00-04 on weekdays, all day on weekends */
            const val NIGHT_WEEKEND = 0xFC1    // bit 0 + bits 6-11
            val BLOCKS = listOf("00-04", "04-08", "08-12", "12-16", "16-20", "20-24")
            /** bit index of the round start T: 4h block in PKT, +6 on Sat/Sun */
            fun bit(T: Long): Int {
                val z = java.time.Instant.ofEpochMilli(T).atZone(java.time.ZoneOffset.ofHours(5))
                val wk = z.dayOfWeek == java.time.DayOfWeek.SATURDAY || z.dayOfWeek == java.time.DayOfWeek.SUNDAY
                return z.hour / 4 + if (wk) 6 else 0
            }
            fun label(T: Long) = (if (bit(T) >= 6) "weekend " else "weekday ") + BLOCKS[bit(T) % 6] + " PKT"
            fun of(o: JSONObject?): Cfg {
                if (o == null) return Cfg()
                val d = Cfg()
                val c = Cfg(o.optBoolean("auto", d.auto), o.optString("mode", d.mode), o.optDouble("stake", d.stake), o.optDouble("thrPct", d.thrPct),
                    o.optDouble("paperCents", d.paperCents), o.optBoolean("alerts", d.alerts), o.optDouble("minP", d.minP),
                    o.optDouble("minEdgePct", d.minEdgePct), o.optInt("windowSec", d.windowSec), o.optBoolean("useQuotes", d.useQuotes),
                    o.optBoolean("armed", d.armed), o.optBoolean("killSwitch", d.killSwitch), o.optDouble("dailyLossUsdt", d.dailyLossUsdt),
                    o.optInt("maxTradesDay", d.maxTradesDay), o.optInt("lossStreak", d.lossStreak), o.optInt("pauseMin", d.pauseMin), o.optBoolean("early", d.early), o.optInt("leadSec", 10), o.optInt("hoursMask", ALL_HOURS), o.optDouble("maxPriceCents", d.maxPriceCents),
                    o.optString("stakeMode", d.stakeMode), o.optDouble("edgeUnitPct", d.edgeUnitPct), o.optDouble("stakeCapX", d.stakeCapX), o.optInt("orderTries", d.orderTries),
                    o.optDouble("kellyFrac", d.kellyFrac), o.optDouble("kellyCapPct", d.kellyCapPct), o.optDouble("bankroll", d.bankroll))
                // v2 (2026-11-22): one rule only, P(UP) >= 52% -> UP, <= 48% -> DOWN, every call bet; edge gate off
                // v3 (2026-11-22): exactly the 1-second backtest that made +$322 in 60 days: 52% rule, call + buy 10 s before the
                // round start, every call bet ($1, no price check), no daily / streak / trade-count limits, no kill switch
                return if (o.optInt("v", 1) < 3) c.copy(minP = 0.0, minEdgePct = -100.0, early = true, stake = if (c.mode == "live") maxOf(c.stake, 1.5) else 1.0,
                    paperCents = 50.0, dailyLossUsdt = 0.0, maxTradesDay = 0, lossStreak = 0, killSwitch = false, thrPct = 60.0, leadSec = 30).copy(early = false, windowSec = 20, minEdgePct = 3.0, useQuotes = true)
                    // v4 (2026-11-23): order-flow model; 56% rule (the only one profitable at the ~54c real price), call 30 s before
                    else if (o.optInt("v", 1) < 4) c.copy(killSwitch = false, thrPct = 60.0, early = false, windowSec = 20, minEdgePct = 3.0, minP = 0.0, useQuotes = true)
                    // v5 (2026-11-25): TWAP model. The price to beat is the 60 s average BEFORE the start, so the call is made AT the
                    // start (bar ending at T closed); 60% rule; bet only if the calibrated win rate beats the real quote by >= 3%
                    else if (o.optInt("v", 1) < 5) c.copy(killSwitch = false, thrPct = 60.0, early = false, windowSec = 20, minEdgePct = 3.0, minP = 0.0, useQuotes = true)
                    else c.copy(killSwitch = false)
            }
        }
    }

    class RoundRow(val T: Long, val p: Double, val side: String, val open: Double, val projClose: Double, val up: Int?,
                   val winP: Double?, val price: Double?, val edge: Double?, val note: String, val diag: String = "") {
        val win get() = if (side == "NONE" || up == null || up == 2) null else (up == 1) == (side == "UP")
    }

    /** share = effective price per share incl. fee (cost / shares); stake = cost. up: 1 / 0 / 2 = tie. */
    class Bet(val id: Long, val mode: String, val T: Long, val side: String, val share: Double, val stake: Double,
              val assumed: Boolean, val auto: Boolean, val up: Int?, val orderId: String?, val status: String, val winP: Double?) {
        /** false = the exchange killed / failed the order: no shares were bought, so no result and no P&L (see reconcileFills) */
        val filled get() = status != "NOT FILLED"
        val shares get() = stake / share
        val win get() = if (!filled || up == null || up == 2) null else (up == 1) == (side == "UP")
        val pnl get() = if (!filled) null else when (up) { null -> null; 2 -> 0.5 * shares - stake; else -> if (win == true) shares - stake else -stake }
    }

    class Stats(val n: Int, val wins: Int, val pnl: Double, val staked: Double, val open: Int, val maxDd: Double) {
        val rate get() = if (n > 0) wins.toDouble() / n else 0.0
    }

    private val CFG = "${mk.prefix}_cfg.json"; private val ROUNDS = "${mk.prefix}_rounds.json"; private val BETS = "${mk.prefix}_bets.json"
    private val STATUS = "${mk.prefix}_status.json"
    /** a market never set up starts with auto OFF (only BTC 5m keeps its old default: on) */
    fun cfg(): Cfg {
        val c = store.readObj(CFG)?.let { Cfg.of(it) } ?: Cfg(auto = mk.id == Btc5mStruct.BTC5.id, windowSec = if (mk.s1) 45 else 20)
        return if (mk.s1 && c.early) c.copy(early = false) else c        // the 1-second model calls 10 s AFTER the start
    }
    fun saveCfg(c: Cfg) = store.writeObj(CFG, c.json())

    fun status(): Pair<Long, String> = store.readObj(STATUS)?.let { it.optLong("t") to it.optString("s") } ?: (0L to "")
    fun setStatus(s: String) = store.writeObj(STATUS, JSONObject().put("t", System.currentTimeMillis()).put("s", s))

    private fun raw(f: String) = (store.readArr(f) ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun up(o: JSONObject) = if (o.has("up")) o.getInt("up") else null
    private fun dOpt(o: JSONObject, k: String) = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null

    @Synchronized fun rounds(): List<RoundRow> = raw(ROUNDS).map {
        RoundRow(it.getLong("T"), it.getDouble("p"), it.getString("side"), it.getDouble("open"), it.optDouble("pc", 0.0), up(it),
            dOpt(it, "wp"), dOpt(it, "px"), dOpt(it, "edge"), it.optString("note"), it.optString("mkDiag"))
    }
    @Synchronized fun bets(): List<Bet> = raw(BETS).map {
        Bet(it.getLong("id"), it.getString("mode"), it.getLong("T"), it.getString("side"), it.getDouble("share"), it.getDouble("stake"),
            it.optBoolean("assumed"), it.optBoolean("auto"), up(it), it.optString("orderId").ifBlank { null }, it.optString("status", "OK"), dOpt(it, "wp"))
    }
    private fun saveRounds(l: List<JSONObject>) = store.writeArr(ROUNDS, JSONArray(l.takeLast(20_000)))
    private fun saveBets(l: List<JSONObject>) = store.writeArr(BETS, JSONArray(l.takeLast(20_000)))

    /** Record the round once (call made at the round start). Returns true if it is a NEW call (UP/DOWN). */
    @Synchronized fun recordRound(c: Btc5mStruct.Call): Boolean {
        val all = raw(ROUNDS).toMutableList()
        if (all.any { it.getLong("T") == c.roundStart }) return false
        all += JSONObject().put("T", c.roundStart).put("t", c.now).put("p", c.pUp).put("side", c.side).put("open", c.open)
            .put("pc", c.projClose).put("wp", c.winP).put("note", if (c.side == "NONE") "no call" else "")
            .also { o -> pendingDiag.remove(c.roundStart)?.let { (d, _) -> o.put("mkDiag", "1 min before: $d") } }
        saveRounds(all)
        return c.side != "NONE"
    }

    @Synchronized private fun noteRound(T: Long, note: String, price: Double? = null, edge: Double? = null) {
        saveRounds(raw(ROUNDS).onEach { o ->
            if (o.getLong("T") == T) { o.put("note", note); price?.let { o.put("px", it) }; edge?.let { o.put("edge", it) } }
        })
    }

    /** First time the round's market was seen open (seconds vs the round start) and its listed price: early-call check. */
    @Synchronized private fun noteMarket(T: Long, now: Long, px: Double?) {
        saveRounds(raw(ROUNDS).onEach { o ->
            if (o.getLong("T") == T && !o.has("mkAt")) { o.put("mkAt", (now - T) / 1000); px?.let { o.put("mkPx", it) } }
        })
    }
    /** How the round's market was found (or why not): list / timeline / search / tradingStatus. */
    @Synchronized fun noteDiag(T: Long, d: String) {
        if (d.isBlank()) return
        saveRounds(raw(ROUNDS).onEach { o -> if (o.getLong("T") == T && !o.optString("mkDiag").startsWith("found")) o.put("mkDiag", d) })
    }

    /** Look the market up early (1 min before): records when it was first listed + how. No quote, no bet. */
    fun prefetchMarket(T: Long, api: PredictionApi, now: Long) {
        val m = try { api.market(T, mk) } catch (e: Exception) { noteDiagAny(T, api.lastDiag.ifBlank { e.message ?: "error" }); return }
        noteDiagAny(T, api.lastDiag)
        synchronized(this) {
            val l = raw(ROUNDS).toMutableList()
            if (l.none { it.getLong("T") == T }) return       // round row is created at the call; keep the result for then
            saveRounds(l.onEach { o -> if (o.getLong("T") == T && !o.has("mkAt")) { o.put("mkAt", (now - T) / 1000); m.up.price?.let { o.put("mkPx", it) } } })
        }
    }
    private val pendingDiag = HashMap<Long, Pair<String, Long>>()
    @Synchronized private fun noteDiagAny(T: Long, d: String) { pendingDiag[T] = d to System.currentTimeMillis(); noteDiag(T, d) }

    fun marketSeen(T: Long): Pair<Long, Double?>? = raw(ROUNDS).firstOrNull { it.getLong("T") == T && it.has("mkAt") }
        ?.let { it.getLong("mkAt") to dOpt(it, "mkPx") }

    /** Early-call check: (rounds checked, market already open before the round start, average listed price before start). */
    fun marketStats(): Triple<Int, Int, Double?> {
        val r = raw(ROUNDS).filter { it.has("mkAt") }
        val before = r.filter { it.getLong("mkAt") <= 0 }
        val px = before.mapNotNull { dOpt(it, "mkPx") }
        return Triple(r.size, before.size, if (px.isEmpty()) null else px.average())
    }

    @Synchronized fun hasBet(T: Long, mode: String) = raw(BETS).any { it.getLong("T") == T && it.getString("mode") == mode }

    /** Bet with an effective price per share (cents, fee included) or null = paper price + fee. */
    @Synchronized fun addBet(mode: String, T: Long, side: String, cents: Double?, stake: Double, auto: Boolean = false,
                             orderId: String? = null, status: String = "OK", winP: Double? = null) {
        val share = ((cents ?: (cfg().paperCents / (1 - PredictionApi.FEE_BPS / 10_000.0))) / 100).coerceIn(0.01, 0.99)
        val l = raw(BETS).toMutableList()
        l += JSONObject().put("id", System.currentTimeMillis()).put("mode", mode).put("T", T).put("side", side)
            .put("share", share).put("stake", stake).put("assumed", cents == null).put("auto", auto)
            .put("orderId", orderId ?: "").put("status", status).put("wp", winP ?: JSONObject.NULL)
            .put("dt", (System.currentTimeMillis() - T) / 1000)
        saveBets(l)
    }

    @Synchronized fun deleteBet(id: Long) = saveBets(raw(BETS).filter { it.getLong("id") != id })

    @Synchronized fun clear(what: String) {   // "rounds" | "paper" | "live"
        if (what == "rounds") saveRounds(emptyList()) else saveBets(raw(BETS).filter { it.getString("mode") != what })
    }

    // ------------------------------------------------------------------ LIVE per-round mark (S1)
    private fun markKey() = "${mk.prefix}_mark.json"
    @Synchronized fun marked(T: Long) = store.readObj(markKey())?.optLong("T") == T
    @Synchronized fun mark(T: Long) = store.writeObj(markKey(), JSONObject().put("T", T))
    @Synchronized fun unmark(T: Long) { if (marked(T)) store.writeObj(markKey(), JSONObject()) }

    // ------------------------------------------------------------------ settlement
    /**
     * Settlement (2026-11-25): the REAL market result first (Polymarket event "<coin>-updown-<n>m-<start s>", public, no key);
     * if it is not out yet 3 min after the round end, the rule computed from Binance spot 1m bars:
     *  - rounds since 2026-08-07: TWAP rule, avg (h+l+c)/3 of the round's last minute >= that of the minute before the start
     *    (matches the real result ~94% of rounds)
     *  - older rounds: end close >= start open
     * Polymarket Up wins ties (">="), so there is no tie any more.
     */
    fun settle(api: Binance, now: Long = api.nowMs()) {
        val pending = (raw(ROUNDS).filter { !it.has("up") }.map { it.getLong("T") } + raw(BETS).filter { !it.has("up") }.map { it.getLong("T") })
            .filter { it + mk.round + 5_000 <= now }.toSortedSet()
        if (pending.isEmpty()) return
        val res = HashMap<Long, Int>(); val src = HashMap<Long, String>()
        for (T in pending.toList().takeLast(200)) {
            val real = realResult(T)
            if (real != null) { res[T] = real; src[T] = "market"; continue }
            if (now < T + mk.round + 180_000) continue           // give the market 3 min to publish its result
            val twap = T >= Btc5mStruct.TWAP_FROM
            val a = api.request("GET", "/api/v3/klines", mapOf("symbol" to mk.symbol, "interval" to "1m",
                "startTime" to (if (twap) T - Btc5mStruct.MIN else T), "limit" to mk.roundMin + (if (twap) 1 else 0)), host = Binance.SPOT) as JSONArray
            val n = mk.roundMin + (if (twap) 1 else 0)
            if (a.length() < n) continue
            fun px(i: Int, k: Int) = a.getJSONArray(i).getString(k).toDouble()
            if (twap) {
                if (a.getJSONArray(0).getLong(0) != T - Btc5mStruct.MIN) continue
                val start = (px(0, 2) + px(0, 3) + px(0, 4)) / 3; val end = (px(n - 1, 2) + px(n - 1, 3) + px(n - 1, 4)) / 3
                res[T] = if (end >= start) 1 else 0
            } else {
                if (a.getJSONArray(0).getLong(0) != T) continue
                res[T] = if (px(n - 1, 4) >= px(0, 1)) 1 else 0
            }
            src[T] = if (twap) "binance TWAP calc" else "binance close"
        }
        synchronized(this) {
            saveRounds(raw(ROUNDS).onEach { o -> if (!o.has("up")) res[o.getLong("T")]?.let { o.put("up", it); o.put("src", src[o.getLong("T")]) } })
            saveBets(raw(BETS).onEach { o -> if (!o.has("up")) res[o.getLong("T")]?.let { o.put("up", it); o.put("src", src[o.getLong("T")]) } })
        }
    }

    /** Real result of the round from Polymarket's public API: 1 = Up, 0 = Down, null = not settled yet / not found. */
    private fun realResult(T: Long): Int? = try {
        val slug = "${mk.coin.lowercase()}-updown-${mk.roundMin}m-${T / 1000}"
        val c = java.net.URL("https://gamma-api.polymarket.com/events?slug=$slug").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 8_000; c.setRequestProperty("User-Agent", "Mozilla/5.0")
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val ev = JSONArray(body)
        if (ev.length() == 0) null else {
            val m = ev.getJSONObject(0).getJSONArray("markets").getJSONObject(0)
            val outs = JSONArray(m.optString("outcomes", "[]")); val prices = JSONArray(m.optString("outcomePrices", "[]"))
            if (!m.optBoolean("closed") || prices.length() != outs.length() || prices.length() == 0) null else {
                var best = 0
                for (i in 0 until prices.length()) if (prices.getString(i).toDouble() > prices.getString(best).toDouble()) best = i
                if (prices.getString(best).toDouble() < 0.99) null else if (outs.getString(best).equals("Up", true)) 1 else 0
            }
        }
    } catch (e: Exception) { null }

    // ------------------------------------------------------------------ risk (PredictAI RiskManager, from the bet record)
    private fun dayStart(now: Long) = now - (now + 5 * 3_600_000L) % 86_400_000L     // midnight PKT

    /** null = allowed, else the reason it is blocked. */
    fun riskBlock(cfg: Cfg, mode: String, now: Long): String? {
        val b = bets().filter { it.mode == mode && it.filled }
        val today = b.filter { it.T >= dayStart(now) }
        if (cfg.maxTradesDay > 0 && today.size >= cfg.maxTradesDay) return "max ${cfg.maxTradesDay} trades today reached"
        val dayPnl = today.sumOf { it.pnl ?: 0.0 }
        if (cfg.dailyLossUsdt > 0 && dayPnl <= -cfg.dailyLossUsdt) return "daily loss limit ${String.format(java.util.Locale.US, "%.2f", cfg.dailyLossUsdt)} USDT reached"
        if (cfg.lossStreak > 0) {
            val done = b.filter { it.pnl != null }.sortedBy { it.T }
            val tail = done.takeLastWhile { (it.pnl ?: 0.0) < 0 }
            if (tail.size >= cfg.lossStreak) {
                val until = tail.last().T + mk.round + cfg.pauseMin * 60_000L
                if (now < until) return "${tail.size} losses in a row: paused for ${(until - now) / 60_000 + 1} min"
            }
        }
        return null
    }

    // ------------------------------------------------------------------ the shared entry path
    /** killed (not filled) orders per round, for the retry limit (in memory: it only matters inside the entry window) */
    private val failedTries = HashMap<Long, Int>()

    /**
     * Stake in USDT (rules in Staking.kt). flat: the base stake. edge / tiers / kelly: sized from the calibrated win rate against the
     * share price; floor 1.5 in LIVE (the exchange minimum), 0.1 in PAPER. 0 = no edge at this price: no bet.
     * No price known: the base stake. bankroll = kelly mode only: LIVE = the real Prediction balance, PAPER = cfg.bankroll.
     */
    fun sizedStake(cfg: Cfg, p: Double, price: Double?, mode: String, bankroll: Double = cfg.bankroll): Double =
        Staking.sized(cfg.stakeMode, cfg.stake, p, price, mode == "live", cfg.edgeUnitPct, cfg.stakeCapX, cfg.kellyFrac, cfg.kellyCapPct, bankroll)

    /** exchange order status -> "NOT FILLED" / "PARTIAL" / "FILLED"; null = still pending or unknown wording */
    fun classify(status: String?): String? {
        val s = status?.lowercase() ?: return null
        return when {
            listOf("fail", "cancel", "reject", "expire", "kill", "unfill", "refund").any { s.contains(it) } -> "NOT FILLED"
            s.contains("partial") -> "PARTIAL"
            s.contains("fill") -> "FILLED"
            else -> null
        }
    }

    /** Watch a just-placed order for a moment: "NOT FILLED" / "FILLED" / "PARTIAL", or null = not visible or still pending. */
    private fun confirmFill(api: PredictionApi, w: PredictionApi.Wallet, id: String): String? {
        for (i in 0 until 4) {
            Thread.sleep(if (i == 0) 1_000L else 1_500L)
            val s = runCatching { api.orders(w).firstOrNull { it.orderId == id }?.status }.getOrNull()
            classify(s)?.let { return it }
        }
        return null
    }

    /** quiet = skipped on purpose (outside the selected hours): no alert */
    class Step(val done: Boolean, val text: String, val placed: Boolean = false, val quiet: Boolean = false)

    /**
     * One attempt to enter the round of call c. done = stop trying this round. Blocking network calls.
     * api = Prediction API (null without a saved key: PAPER then uses the paper price).
     */
    fun tryEnter(c: Btc5mStruct.Call, cfg: Cfg, api: PredictionApi?, now: Long): Step {
        val T = c.roundStart
        val mode = cfg.mode
        val us = java.util.Locale.US
        fun skip(note: String, price: Double? = null, edge: Double? = null, done: Boolean = true): Step {
            noteRound(T, note, price, edge); return Step(done, note)
        }
        if (c.side == "NONE") return Step(true, "no call this round")
        if (hasBet(T, mode) || (mode == "live" && marked(T))) return Step(true, "round already traded")
        if (!cfg.inHours(T)) { noteRound(T, "outside selected hours (${Cfg.label(T)}): not bet"); return Step(true, "outside selected hours (${Cfg.label(T)})", quiet = true) }
        // early mode: buy only BEFORE the round starts (the call was made from the candles at T-10 s); no entry window
        if (cfg.early && now >= T) return skip("round started before an early buy was possible: skipped")
        if (!cfg.early && (now - T) / 1000 > cfg.windowSec) return skip("entry window (${cfg.windowSec}s) passed")
        if (mode == "live" && !cfg.armed) return skip("LIVE not armed")
        if (mode == "live" && api == null) return skip("LIVE needs a Prediction API key")
        if (mode == "live" && cfg.stake < 1.5) return skip("LIVE stake must be at least 1.5 USDT")
        riskBlock(cfg, mode, now)?.let { return skip("risk: $it") }
        val p = c.winP
        if (p < cfg.minP) return skip("win rate ${String.format(us, "%.1f", p * 100)}% < min ${String.format(us, "%.1f", cfg.minP * 100)}%")

        // executable price
        val fee = PredictionApi.FEE_BPS / 10_000.0
        var cost: Double; var shares: Double; var source: String
        var q: PredictionApi.Quote? = null
        var wallet: PredictionApi.Wallet? = null
        var plan: PredictionApi.Funding? = null
        var market: PredictionApi.Market? = null
        var stake = cfg.stake
        if (api != null && (mode == "live" || cfg.useQuotes)) {
            try {
                wallet = api.wallet()
                val bal = api.balances()
                market = try { api.market(T, mk) } catch (e: UserError) {
                    if (cfg.early) { noteDiag(T, api.lastDiag); return Step(false, "next round market not listed yet (${(now - T) / 1000}s)") } else throw e
                }
                noteDiag(T, api.lastDiag)
                if (cfg.early) noteMarket(T, now, market.let { if (c.side == "UP") it.up.price else it.down.price })
                val tok = if (c.side == "UP") market.up else market.down
                // stake by edge: size the bet from the calibrated win rate against the listed price (the real quote is checked again below)
                val wb = runCatching { api.walletBalance(wallet) }.getOrNull()
                stake = sizedStake(cfg, p, tok.price, mode, if (mode == "live") api.bankroll(bal, wb) else cfg.bankroll)
                if (stake <= 0.0) return skip("stake rule (${Staking.title(cfg.stakeMode)}): no bet at the listed price ${String.format(us, "%.1f", (tok.price ?: 0.0) * 100)} cents", done = false)
                plan = api.funding(bal, wb, stake)
                if (plan == null && mode == "live") return skip("not enough Prediction balance for ${String.format(us, "%.2f", stake)} USDT")
                q = api.quote(wallet, tok.tokenId, stake, minOf(market.slippageBps, 200), plan?.source ?: "MPC")
                if (q.expireAt > 0 && q.expireAt <= api.nowMs()) return Step(false, "quote expired, retrying")
                if (!(q.shares > 0)) return Step(false, "quote returned no shares, retrying")
                cost = q.cost; shares = q.shares; source = "quote"
            } catch (e: Exception) {
                val m = (e as? BinanceException)?.msg ?: e.message ?: e.javaClass.simpleName
                if (mode == "live") return Step(false, "quote failed: $m")
                // paper falls back to the paper price
                q = null
                stake = sizedStake(cfg, p, cfg.paperCents / 100, mode)
                if (stake <= 0.0) return skip("stake rule (${Staking.title(cfg.stakeMode)}): no bet at the paper price", done = false)
                cost = stake; shares = stake * (1 - fee) / (cfg.paperCents / 100); source = "paper price (quote failed: ${m.take(60)})"
            }
        } else {
            stake = sizedStake(cfg, p, cfg.paperCents / 100, mode)
            if (stake <= 0.0) return skip("stake rule (${Staking.title(cfg.stakeMode)}): no bet at the paper price")
            cost = stake; shares = stake * (1 - fee) / (cfg.paperCents / 100); source = "paper price"
        }
        val price = cost / shares
        val edge = (p * shares - cost) / cost * 100
        if (cfg.maxPriceCents > 0 && price * 100 > cfg.maxPriceCents) {
            // price cap: the share can still get cheaper inside the window, so retry with a fresh quote (not for the paper price)
            return skip("price ${String.format(us, "%.1f", price * 100)} cents > cap ${String.format(us, "%.0f", cfg.maxPriceCents)} cents",
                price, edge, done = q == null)
        }
        if (edge < cfg.minEdgePct) {
            // the share price can still drop inside the window: retry with a fresh quote unless it is the paper price
            return skip("edge ${String.format(us, "%+.1f", edge)}% < ${cfg.minEdgePct}% at ${String.format(us, "%.1f", price * 100)}¢ (break-even ${String.format(us, "%.1f", p * 100)}¢)",
                price, edge, done = q == null)
        }

        if (mode == "paper") {
            addBet("paper", T, c.side, price * 100, cost, auto = true, winP = p)
            noteRound(T, "PAPER ${c.side} at ${(now - T) / 1000}s · ${String.format(us, "%.1f", price * 100)}¢ · edge ${String.format(us, "%+.1f", edge)}% · $source", price, edge)
            return Step(true, "paper bet ${c.side} ${String.format(us, "%.2f", cost)} USDT at ${String.format(us, "%.1f", price * 100)}¢ (edge ${String.format(us, "%+.1f", edge)}%)", placed = true)
        }

        // LIVE: mark first (S1), then order once
        val quote = q ?: return Step(false, "no quote")
        val w = wallet!!; val pl = plan!!; val mkt = market!!
        if (marked(T)) return Step(true, "round already traded")
        mark(T)
        return try {
            val id = api!!.placeOrder(w, quote, pl.accountType, minOf(mkt.slippageBps, 200), pl.source)
            // a FOK order that the exchange kills still returns an id: look the order up before it counts as a bet
            val fill = confirmFill(api, w, id)
            if (fill == "NOT FILLED") {
                unmark(T)
                val n = (failedTries[T] ?: 0) + 1; failedTries[T] = n
                if (failedTries.size > 50) failedTries.keys.filter { it < T - 3_600_000L }.forEach { failedTries.remove(it) }
                noteRound(T, "order $id killed by the exchange (not filled), order $n of ${cfg.orderTries}", price, edge)
                return if (n >= cfg.orderTries) Step(true, "order not filled $n times: round skipped, no money spent")
                       else Step(false, "order not filled (killed), re-quoting: order $n of ${cfg.orderTries}")
            }
            addBet("live", T, c.side, price * 100, cost, auto = true, orderId = id, winP = p, status = fill ?: "OK")
            noteRound(T, "LIVE ${c.side} at ${(now - T) / 1000}s · order $id · ${String.format(us, "%.1f", price * 100)}¢ · edge ${String.format(us, "%+.1f", edge)}%", price, edge)
            Step(true, "LIVE ${c.side} ${String.format(us, "%.2f", cost)} USDT at ${String.format(us, "%.1f", price * 100)}¢ · order $id", placed = true)
        } catch (e: BinanceException) {
            if (e.status in 400..499) {
                unmark(T)
                Step(false, "order rejected: ${e.msg}")
            } else {
                addBet("live", T, c.side, price * 100, cost, auto = true, status = "UNKNOWN", winP = p)
                skip("LIVE order outcome UNKNOWN (${e.msg}): not resent, check Binance", price, edge)
            }
        } catch (e: Exception) {
            addBet("live", T, c.side, price * 100, cost, auto = true, status = "UNKNOWN", winP = p)
            skip("LIVE order outcome UNKNOWN (${e.message}): not resent, check Binance", price, edge)
        }
    }

    /**
     * LIVE fill check (2026-11-27). place-order returns an order id for a FOK order even when the exchange then kills it
     * (order history: "Failed 0.00%"), so an order id is NOT proof of a fill. Every live bet still "OK" is looked up in the
     * wallet's order list: Failed / cancelled / rejected / expired -> "NOT FILLED" (no shares, no payout: left out of results
     * and stats); Filled -> "FILLED"; partly filled -> "PARTIAL"; not found or still pending -> unchanged, checked again next loop.
     */
    fun reconcileFills(api: PredictionApi, now: Long) {
        val pend = raw(BETS).filter { it.getString("mode") == "live" && it.optString("orderId").isNotBlank() &&
            it.optString("status", "OK") == "OK" && now - it.getLong("id") > 15_000 }
        if (pend.isEmpty()) return
        val byId = api.orders(api.wallet()).associateBy { it.orderId }
        val upd = HashMap<Long, String>()
        for (o in pend) {
            val ns = classify(byId[o.optString("orderId")]?.status) ?: continue
            upd[o.getLong("id")] = ns
        }
        if (upd.isEmpty()) return
        synchronized(this) { saveBets(raw(BETS).onEach { o -> upd[o.getLong("id")]?.let { o.put("status", it) } }) }
    }

    // ------------------------------------------------------------------ stats
    fun stats(mode: String, since: Long = 0L): Stats {
        val b = bets().filter { it.mode == mode && it.T >= since && it.filled }
        val done = b.filter { it.pnl != null }.sortedBy { it.T }
        var eq = 0.0; var peak = 0.0; var dd = 0.0
        for (x in done) { eq += x.pnl ?: 0.0; peak = maxOf(peak, eq); dd = maxOf(dd, peak - eq) }
        return Stats(done.size, done.count { it.win == true }, eq, done.sumOf { it.stake }, b.size - done.size, dd)
    }

    fun callStats(): Pair<Int, Int> {
        val c = rounds().filter { it.win != null }
        return c.size to c.count { it.win == true }
    }

    fun csv(): String {
        val us = java.util.Locale.US
        val sb = StringBuilder("type,mode,round_utc_ms,side,p_up,backtest_win_p,price_per_share,edge_pct,open,projected_close,stake,order_id,status,result,pnl,note,market_open_at_s,market_listed_price,bet_at_s,market_diag\n")
        val mk = raw(ROUNDS).filter { it.has("mkAt") }.associate { it.getLong("T") to (it.getLong("mkAt") to dOpt(it, "mkPx")) }
        val dg = raw(ROUNDS).filter { it.has("mkDiag") }.associate { it.getLong("T") to it.getString("mkDiag").replace("\"", "'").replace(",", ";") }
        val dts = raw(BETS).associate { it.getLong("id") to (if (it.has("dt")) it.getLong("dt").toString() else "") }
        for (r in rounds()) sb.append("round,,${r.T},${r.side},${String.format(us, "%.4f", r.p)},${r.winP ?: ""},${r.price ?: ""},${r.edge?.let { String.format(us, "%.2f", it) } ?: ""}," +
            "${r.open},${String.format(us, "%.2f", r.projClose)},,,,${r.win?.let { if (it) "WIN" else "LOSS" } ?: if (r.up == 2) "TIE" else ""},,\"${r.note.replace("\"", "'")}\",${mk[r.T]?.first ?: ""},${mk[r.T]?.second ?: ""},,\"${dg[r.T] ?: ""}\"\n")
        for (b in bets()) sb.append("bet,${b.mode},${b.T},${b.side},,${b.winP ?: ""},${String.format(us, "%.4f", b.share)},,,,${b.stake},${b.orderId ?: ""},${b.status}," +
            "${b.win?.let { if (it) "WIN" else "LOSS" } ?: if (b.up == 2) "TIE" else ""},${b.pnl?.let { String.format(us, "%.4f", it) } ?: ""},,,,${dts[b.id] ?: ""},\n")
        return sb.toString()
    }
}
