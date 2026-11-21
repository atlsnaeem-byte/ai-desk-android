package pk.aidesk.app.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Record for the structure AI (one call per round, made at the round start):
 * - rounds: every scanned round with P(UP) and the call (UP / DOWN / NONE)
 * - bets: PAPER (placed automatically at the round start when the call passes the threshold, or by hand)
 *   or LIVE (a bet you placed in Binance yourself and recorded here; Binance Prediction has no order API).
 * Settled from Binance SPOT 1m bars: UP if the close of the round's last 1m bar >= the open of its first bar.
 */
class Btc5mStructBook(private val store: Store) {
    data class Cfg(
        val auto: Boolean = true,          // background loop: call + auto paper bet every round
        val mode: String = "paper",        // paper | live
        val stake: Double = 1.0,           // USDT per bet
        val thrPct: Double = 56.0,         // call when P(UP) >= thr or <= 100 - thr
        val paperCents: Double = 50.0,     // paper fill price when you did not type one
        val alerts: Boolean = true,        // notification on every call
    ) {
        val thr get() = thrPct / 100
        fun json() = JSONObject().put("auto", auto).put("mode", mode).put("stake", stake).put("thrPct", thrPct)
            .put("paperCents", paperCents).put("alerts", alerts)
        companion object {
            fun of(o: JSONObject?) = if (o == null) Cfg() else Cfg(o.optBoolean("auto", true), o.optString("mode", "paper"),
                o.optDouble("stake", 1.0), o.optDouble("thrPct", 56.0), o.optDouble("paperCents", 50.0), o.optBoolean("alerts", true))
        }
    }

    class RoundRow(val T: Long, val p: Double, val side: String, val open: Double, val projClose: Double, val up: Int?) {
        val win get() = if (side == "NONE") null else up?.let { (it == 1) == (side == "UP") }
    }

    class Bet(val id: Long, val mode: String, val T: Long, val side: String, val share: Double, val stake: Double,
              val assumed: Boolean, val auto: Boolean, val up: Int?) {
        val win get() = up?.let { (it == 1) == (side == "UP") }
        /** profit in USDT: a share pays 1 USDT if it wins (fees not included) */
        val pnl get() = win?.let { if (it) stake * (1 / share - 1) else -stake }
    }

    class Stats(val n: Int, val wins: Int, val pnl: Double, val staked: Double, val open: Int, val maxDd: Double) {
        val rate get() = if (n > 0) wins.toDouble() / n else 0.0
    }

    private val CFG = "btc5ms_cfg.json"; private val ROUNDS = "btc5ms_rounds.json"; private val BETS = "btc5ms_bets.json"
    fun cfg() = Cfg.of(store.readObj(CFG))
    fun saveCfg(c: Cfg) = store.writeObj(CFG, c.json())

    private fun raw(f: String) = (store.readArr(f) ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun up(o: JSONObject) = if (o.has("up")) o.getInt("up") else null

    @Synchronized fun rounds(): List<RoundRow> = raw(ROUNDS).map {
        RoundRow(it.getLong("T"), it.getDouble("p"), it.getString("side"), it.getDouble("open"), it.optDouble("pc", 0.0), up(it))
    }
    @Synchronized fun bets(): List<Bet> = raw(BETS).map {
        Bet(it.getLong("id"), it.getString("mode"), it.getLong("T"), it.getString("side"), it.getDouble("share"), it.getDouble("stake"),
            it.optBoolean("assumed"), it.optBoolean("auto"), up(it))
    }
    private fun saveRounds(l: List<JSONObject>) = store.writeArr(ROUNDS, JSONArray(l.takeLast(20_000)))
    private fun saveBets(l: List<JSONObject>) = store.writeArr(BETS, JSONArray(l.takeLast(20_000)))

    /** Record the round once. Returns true if it is a NEW call (UP/DOWN) for the alert. In PAPER mode with auto on, the call
     *  is bet automatically, but only if we are still within 30 s of the round start (late = price already moved). */
    @Synchronized fun record(c: Btc5mStruct.Call): Boolean {
        val all = raw(ROUNDS).toMutableList()
        if (all.any { it.getLong("T") == c.roundStart }) return false
        all += JSONObject().put("T", c.roundStart).put("t", c.now).put("p", c.pUp).put("side", c.side).put("open", c.open)
            .put("pc", c.projClose)
        saveRounds(all)
        if (c.side == "NONE") return false
        val cfg = cfg()
        if (cfg.auto && cfg.mode == "paper" && c.now - c.roundStart <= 30_000 &&
            raw(BETS).none { it.getLong("T") == c.roundStart && it.getString("mode") == "paper" })
            addBet("paper", c.roundStart, c.side, null, cfg.stake, auto = true)
        return true
    }

    @Synchronized fun addBet(mode: String, T: Long, side: String, cents: Double?, stake: Double, auto: Boolean = false) {
        val share = ((cents ?: cfg().paperCents) / 100).coerceIn(0.01, 0.99)
        val l = raw(BETS).toMutableList()
        l += JSONObject().put("id", System.currentTimeMillis()).put("mode", mode).put("T", T).put("side", side)
            .put("share", share).put("stake", stake).put("assumed", cents == null).put("auto", auto)
        saveBets(l)
    }

    @Synchronized fun deleteBet(id: Long) = saveBets(raw(BETS).filter { it.getLong("id") != id })

    @Synchronized fun clear(what: String) {   // "rounds" | "paper" | "live"
        if (what == "rounds") saveRounds(emptyList()) else saveBets(raw(BETS).filter { it.getString("mode") != what })
    }

    /** Settle finished rounds from Binance spot 1m bars. */
    fun settle(api: Binance, now: Long = api.nowMs()) {
        val pending = (raw(ROUNDS).filter { !it.has("up") }.map { it.getLong("T") } + raw(BETS).filter { !it.has("up") }.map { it.getLong("T") })
            .filter { it + Btc5mStruct.ROUND + 2_000 <= now }.toSortedSet()
        if (pending.isEmpty()) return
        val res = HashMap<Long, Int>()
        for (T in pending.toList().takeLast(200)) {
            val a = api.request("GET", "/api/v3/klines", mapOf("symbol" to Btc5m.SYMBOL, "interval" to "1m", "startTime" to T, "limit" to 5),
                host = Binance.SPOT) as JSONArray
            if (a.length() < 5 || a.getJSONArray(0).getLong(0) != T) continue
            val open = a.getJSONArray(0).getString(1).toDouble()
            val close = a.getJSONArray(4).getString(4).toDouble()
            res[T] = if (close >= open) 1 else 0
        }
        synchronized(this) {
            saveRounds(raw(ROUNDS).onEach { o -> if (!o.has("up")) res[o.getLong("T")]?.let { o.put("up", it) } })
            saveBets(raw(BETS).onEach { o -> if (!o.has("up")) res[o.getLong("T")]?.let { o.put("up", it) } })
        }
    }

    fun stats(mode: String, since: Long = 0L): Stats {
        val b = bets().filter { it.mode == mode && it.T >= since }
        val done = b.filter { it.win != null }.sortedBy { it.T }
        var eq = 0.0; var peak = 0.0; var dd = 0.0
        for (x in done) { eq += x.pnl ?: 0.0; peak = maxOf(peak, eq); dd = maxOf(dd, peak - eq) }
        return Stats(done.size, done.count { it.win == true }, eq, done.sumOf { it.stake }, b.size - done.size, dd)
    }

    /** Settled calls (UP/DOWN rounds) -> (n, wins) */
    fun callStats(): Pair<Int, Int> {
        val c = rounds().filter { it.win != null }
        return c.size to c.count { it.win == true }
    }

    fun csv(): String {
        val us = java.util.Locale.US
        val sb = StringBuilder("type,mode,round_utc_ms,side,p_up_or_share,open,projected_close,stake,result,pnl\n")
        for (r in rounds()) sb.append("round,,${r.T},${r.side},${String.format(us, "%.4f", r.p)},${r.open},${String.format(us, "%.2f", r.projClose)},,${r.win?.let { if (it) "WIN" else "LOSS" } ?: ""},\n")
        for (b in bets()) sb.append("bet,${b.mode},${b.T},${b.side},${b.share},,,${b.stake},${b.win?.let { if (it) "WIN" else "LOSS" } ?: ""},${b.pnl?.let { String.format(us, "%.4f", it) } ?: ""}\n")
        return sb.toString()
    }
}
