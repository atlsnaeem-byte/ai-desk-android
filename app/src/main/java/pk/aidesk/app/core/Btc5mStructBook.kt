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
class Btc5mStructBook(private val store: Store) {
    data class Cfg(
        val auto: Boolean = true,          // background loop: call + auto bet every round
        val mode: String = "paper",        // paper | live
        val stake: Double = 1.0,           // USDT per bet (LIVE minimum 1.5)
        val thrPct: Double = 52.0,         // call when P(UP) >= 52% -> UP, <= 48% -> DOWN
        val paperCents: Double = 50.0,     // paper fill price when no Binance quote is available
        val alerts: Boolean = true,
        val minP: Double = 0.0,            // min backtest win rate of the called side (0 = off)
        val minEdgePct: Double = -100.0,   // min EV / cost after the fee (-100 = off)
        val windowSec: Int = 45,           // enter only in the first N seconds of the round
        val useQuotes: Boolean = true,     // PAPER uses real Binance quotes when an API key is saved
        val armed: Boolean = false,        // LIVE orders allowed
        val killSwitch: Boolean = false,
        val dailyLossUsdt: Double = 0.0,   // 0 = off
        val maxTradesDay: Int = 0,         // 0 = off
        val lossStreak: Int = 0,           // pause after N losses in a row, 0 = off
        val pauseMin: Int = 60,
        val early: Boolean = true,         // call 10 s BEFORE the round starts (from the still-forming candles)
    ) {
        val thr get() = thrPct / 100
        fun json() = JSONObject().put("v", 3).put("auto", auto).put("mode", mode).put("stake", stake).put("thrPct", thrPct)
            .put("paperCents", paperCents).put("alerts", alerts).put("minP", minP).put("minEdgePct", minEdgePct)
            .put("windowSec", windowSec).put("useQuotes", useQuotes).put("armed", armed).put("killSwitch", killSwitch)
            .put("dailyLossUsdt", dailyLossUsdt).put("maxTradesDay", maxTradesDay).put("lossStreak", lossStreak).put("pauseMin", pauseMin).put("early", early)
        companion object {
            fun of(o: JSONObject?): Cfg {
                if (o == null) return Cfg()
                val d = Cfg()
                val c = Cfg(o.optBoolean("auto", d.auto), o.optString("mode", d.mode), o.optDouble("stake", d.stake), o.optDouble("thrPct", d.thrPct),
                    o.optDouble("paperCents", d.paperCents), o.optBoolean("alerts", d.alerts), o.optDouble("minP", d.minP),
                    o.optDouble("minEdgePct", d.minEdgePct), o.optInt("windowSec", d.windowSec), o.optBoolean("useQuotes", d.useQuotes),
                    o.optBoolean("armed", d.armed), o.optBoolean("killSwitch", d.killSwitch), o.optDouble("dailyLossUsdt", d.dailyLossUsdt),
                    o.optInt("maxTradesDay", d.maxTradesDay), o.optInt("lossStreak", d.lossStreak), o.optInt("pauseMin", d.pauseMin), o.optBoolean("early", d.early))
                // v2 (2026-11-22): one rule only, P(UP) >= 52% -> UP, <= 48% -> DOWN, every call bet; edge gate off
                // v3 (2026-11-22): exactly the 1-second backtest that made +$322 in 60 days: 52% rule, call + buy 10 s before the
                // round start, every call bet ($1, no price check), no daily / streak / trade-count limits, no kill switch
                return if (o.optInt("v", 1) < 3) c.copy(thrPct = 52.0, minP = 0.0, minEdgePct = -100.0, early = true, stake = if (c.mode == "live") maxOf(c.stake, 1.5) else 1.0,
                    paperCents = 50.0, dailyLossUsdt = 0.0, maxTradesDay = 0, lossStreak = 0, killSwitch = false) else c.copy(killSwitch = false)
            }
        }
    }

    class RoundRow(val T: Long, val p: Double, val side: String, val open: Double, val projClose: Double, val up: Int?,
                   val winP: Double?, val price: Double?, val edge: Double?, val note: String) {
        val win get() = if (side == "NONE" || up == null || up == 2) null else (up == 1) == (side == "UP")
    }

    /** share = effective price per share incl. fee (cost / shares); stake = cost. up: 1 / 0 / 2 = tie. */
    class Bet(val id: Long, val mode: String, val T: Long, val side: String, val share: Double, val stake: Double,
              val assumed: Boolean, val auto: Boolean, val up: Int?, val orderId: String?, val status: String, val winP: Double?) {
        val shares get() = stake / share
        val win get() = if (up == null || up == 2) null else (up == 1) == (side == "UP")
        val pnl get() = when (up) { null -> null; 2 -> 0.5 * shares - stake; else -> if (win == true) shares - stake else -stake }
    }

    class Stats(val n: Int, val wins: Int, val pnl: Double, val staked: Double, val open: Int, val maxDd: Double) {
        val rate get() = if (n > 0) wins.toDouble() / n else 0.0
    }

    private val CFG = "btc5ms_cfg.json"; private val ROUNDS = "btc5ms_rounds.json"; private val BETS = "btc5ms_bets.json"
    private val STATUS = "btc5ms_status.json"
    fun cfg() = Cfg.of(store.readObj(CFG))
    fun saveCfg(c: Cfg) = store.writeObj(CFG, c.json())

    fun status(): Pair<Long, String> = store.readObj(STATUS)?.let { it.optLong("t") to it.optString("s") } ?: (0L to "")
    fun setStatus(s: String) = store.writeObj(STATUS, JSONObject().put("t", System.currentTimeMillis()).put("s", s))

    private fun raw(f: String) = (store.readArr(f) ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun up(o: JSONObject) = if (o.has("up")) o.getInt("up") else null
    private fun dOpt(o: JSONObject, k: String) = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null

    @Synchronized fun rounds(): List<RoundRow> = raw(ROUNDS).map {
        RoundRow(it.getLong("T"), it.getDouble("p"), it.getString("side"), it.getDouble("open"), it.optDouble("pc", 0.0), up(it),
            dOpt(it, "wp"), dOpt(it, "px"), dOpt(it, "edge"), it.optString("note"))
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
    private fun markKey() = "btc5ms_mark.json"
    @Synchronized fun marked(T: Long) = store.readObj(markKey())?.optLong("T") == T
    @Synchronized fun mark(T: Long) = store.writeObj(markKey(), JSONObject().put("T", T))
    @Synchronized fun unmark(T: Long) { if (marked(T)) store.writeObj(markKey(), JSONObject()) }

    // ------------------------------------------------------------------ settlement
    fun settle(api: Binance, now: Long = api.nowMs()) {
        val pending = (raw(ROUNDS).filter { !it.has("up") }.map { it.getLong("T") } + raw(BETS).filter { !it.has("up") }.map { it.getLong("T") })
            .filter { it + Btc5mStruct.ROUND + 5_000 <= now }.toSortedSet()
        if (pending.isEmpty()) return
        val res = HashMap<Long, Int>()
        for (T in pending.toList().takeLast(200)) {
            val a = api.request("GET", "/api/v3/klines", mapOf("symbol" to Btc5m.SYMBOL, "interval" to "1m", "startTime" to T, "limit" to 5),
                host = Binance.SPOT) as JSONArray
            if (a.length() < 5 || a.getJSONArray(0).getLong(0) != T) continue
            val open = a.getJSONArray(0).getString(1).toDouble()
            val close = a.getJSONArray(4).getString(4).toDouble()
            res[T] = if (close > open) 1 else if (close < open) 0 else 2
        }
        synchronized(this) {
            saveRounds(raw(ROUNDS).onEach { o -> if (!o.has("up")) res[o.getLong("T")]?.let { o.put("up", it) } })
            saveBets(raw(BETS).onEach { o -> if (!o.has("up")) res[o.getLong("T")]?.let { o.put("up", it) } })
        }
    }

    // ------------------------------------------------------------------ risk (PredictAI RiskManager, from the bet record)
    private fun dayStart(now: Long) = now - (now + 5 * 3_600_000L) % 86_400_000L     // midnight PKT

    /** null = allowed, else the reason it is blocked. */
    fun riskBlock(cfg: Cfg, mode: String, now: Long): String? {
        val b = bets().filter { it.mode == mode }
        val today = b.filter { it.T >= dayStart(now) }
        if (cfg.maxTradesDay > 0 && today.size >= cfg.maxTradesDay) return "max ${cfg.maxTradesDay} trades today reached"
        val dayPnl = today.sumOf { it.pnl ?: 0.0 }
        if (cfg.dailyLossUsdt > 0 && dayPnl <= -cfg.dailyLossUsdt) return "daily loss limit ${String.format(java.util.Locale.US, "%.2f", cfg.dailyLossUsdt)} USDT reached"
        if (cfg.lossStreak > 0) {
            val done = b.filter { it.pnl != null }.sortedBy { it.T }
            val tail = done.takeLastWhile { (it.pnl ?: 0.0) < 0 }
            if (tail.size >= cfg.lossStreak) {
                val until = tail.last().T + Btc5mStruct.ROUND + cfg.pauseMin * 60_000L
                if (now < until) return "${tail.size} losses in a row: paused for ${(until - now) / 60_000 + 1} min"
            }
        }
        return null
    }

    // ------------------------------------------------------------------ the shared entry path
    class Step(val done: Boolean, val text: String, val placed: Boolean = false)

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
        if (api != null && (mode == "live" || cfg.useQuotes)) {
            try {
                wallet = api.wallet()
                val bal = api.balances()
                plan = api.funding(bal, runCatching { api.walletBalance(wallet) }.getOrNull(), cfg.stake)
                if (plan == null && mode == "live") return skip("not enough Prediction balance for ${cfg.stake} USDT")
                market = try { api.market(T) } catch (e: UserError) {
                    if (cfg.early) return Step(false, "next round market not listed yet (${(now - T) / 1000}s)") else throw e
                }
                if (cfg.early) noteMarket(T, now, market.let { if (c.side == "UP") it.up.price else it.down.price })
                val tok = if (c.side == "UP") market.up else market.down
                q = api.quote(wallet, tok.tokenId, cfg.stake, minOf(market.slippageBps, 200), plan?.source ?: "MPC")
                if (q.expireAt > 0 && q.expireAt <= api.nowMs()) return Step(false, "quote expired, retrying")
                if (!(q.shares > 0)) return Step(false, "quote returned no shares, retrying")
                cost = q.cost; shares = q.shares; source = "quote"
            } catch (e: Exception) {
                val m = (e as? BinanceException)?.msg ?: e.message ?: e.javaClass.simpleName
                if (mode == "live") return Step(false, "quote failed: $m")
                // paper falls back to the paper price
                q = null
                cost = cfg.stake; shares = cfg.stake * (1 - fee) / (cfg.paperCents / 100); source = "paper price (quote failed: ${m.take(60)})"
            }
        } else {
            cost = cfg.stake; shares = cfg.stake * (1 - fee) / (cfg.paperCents / 100); source = "paper price"
        }
        val price = cost / shares
        val edge = (p * shares - cost) / cost * 100
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
        val w = wallet!!; val pl = plan!!; val mk = market!!
        if (marked(T)) return Step(true, "round already traded")
        mark(T)
        return try {
            val id = api!!.placeOrder(w, quote, pl.accountType, minOf(mk.slippageBps, 200), pl.source)
            addBet("live", T, c.side, price * 100, cost, auto = true, orderId = id, winP = p)
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

    // ------------------------------------------------------------------ stats
    fun stats(mode: String, since: Long = 0L): Stats {
        val b = bets().filter { it.mode == mode && it.T >= since }
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
        val sb = StringBuilder("type,mode,round_utc_ms,side,p_up,backtest_win_p,price_per_share,edge_pct,open,projected_close,stake,order_id,status,result,pnl,note,market_open_at_s,market_listed_price,bet_at_s\n")
        val mk = raw(ROUNDS).filter { it.has("mkAt") }.associate { it.getLong("T") to (it.getLong("mkAt") to dOpt(it, "mkPx")) }
        val dts = raw(BETS).associate { it.getLong("id") to (if (it.has("dt")) it.getLong("dt").toString() else "") }
        for (r in rounds()) sb.append("round,,${r.T},${r.side},${String.format(us, "%.4f", r.p)},${r.winP ?: ""},${r.price ?: ""},${r.edge?.let { String.format(us, "%.2f", it) } ?: ""}," +
            "${r.open},${String.format(us, "%.2f", r.projClose)},,,,${r.win?.let { if (it) "WIN" else "LOSS" } ?: if (r.up == 2) "TIE" else ""},,\"${r.note.replace("\"", "'")}\",${mk[r.T]?.first ?: ""},${mk[r.T]?.second ?: ""},\n")
        for (b in bets()) sb.append("bet,${b.mode},${b.T},${b.side},,${b.winP ?: ""},${String.format(us, "%.4f", b.share)},,,,${b.stake},${b.orderId ?: ""},${b.status}," +
            "${b.win?.let { if (it) "WIN" else "LOSS" } ?: if (b.up == 2) "TIE" else ""},${b.pnl?.let { String.format(us, "%.4f", it) } ?: ""},,,,${dts[b.id] ?: ""}\n")
        return sb.toString()
    }
}
