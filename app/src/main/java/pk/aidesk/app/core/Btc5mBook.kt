package pk.aidesk.app.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Record of every BTC 5m call + paper / live bets, settled from Binance SPOT 1m bars
 * (the market's "Price to Beat" is the Binance spot 1m open of the round: checked 2026-11-21).
 *
 * - calls: one row per round per checkpoint (1:00..4:00), confirmed or not
 * - bets: PAPER (auto on the first confirmed call of a round, or added by hand) or LIVE (a bet you placed in Binance
 *   yourself and recorded here). Binance Prediction has no order API for API keys, so the app never places live bets.
 * Share prices are not available to the app: a paper bet uses the price you typed, else the assumed price from settings.
 */
class Btc5mBook(private val store: Store) {
    data class Cfg(
        val record: Boolean = true,        // background recorder (service, every minute)
        val mode: String = "paper",        // paper | live
        val stake: Double = 1.0,           // USDT per bet
        val assumedCents: Double = 85.0,   // paper fill price when you did not type one
        val alerts: Boolean = true,        // notification on every confirmed call
    ) {
        fun json() = JSONObject().put("record", record).put("mode", mode).put("stake", stake).put("assumedCents", assumedCents).put("alerts", alerts)
        companion object {
            fun of(o: JSONObject?) = if (o == null) Cfg() else Cfg(o.optBoolean("record", true), o.optString("mode", "paper"),
                o.optDouble("stake", 1.0), o.optDouble("assumedCents", 85.0), o.optBoolean("alerts", true))
        }
    }

    class CallRow(val at: Long, val T: Long, val e: Int, val side: String, val conf: Double, val threshold: Double?, val confirmed: Boolean,
                  val histWin: Double?, val open: Double, val price: Double, val up: Int?)   // up: 1 / 0 once settled
    { val win get() = up?.let { (it == 1) == (side == "UP") } }

    class Bet(val id: Long, val mode: String, val T: Long, val e: Int, val side: String, val share: Double, val stake: Double,
              val assumed: Boolean, val auto: Boolean, val up: Int?) {
        val win get() = up?.let { (it == 1) == (side == "UP") }
        /** profit in USDT: a share pays 1 USDT if it wins (fees not included) */
        val pnl get() = win?.let { if (it) stake * (1 / share - 1) else -stake }
    }

    class Stats(val n: Int, val wins: Int, val pnl: Double, val staked: Double, val open: Int) {
        val rate get() = if (n > 0) wins.toDouble() / n else 0.0
    }

    fun cfg() = Cfg.of(store.readObj("btc5m_cfg.json"))
    fun saveCfg(c: Cfg) = store.writeObj("btc5m_cfg.json", c.json())

    @Synchronized fun calls(): List<CallRow> = (store.readArr("btc5m_calls.json") ?: JSONArray()).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            CallRow(it.getLong("t"), it.getLong("T"), it.getInt("e"), it.getString("side"), it.getDouble("conf"),
                if (it.isNull("th")) null else it.getDouble("th"), it.getBoolean("ok"),
                if (it.isNull("hw")) null else it.getDouble("hw"), it.getDouble("open"), it.getDouble("price"),
                if (it.has("up")) it.getInt("up") else null)
        }
    }

    @Synchronized fun bets(): List<Bet> = (store.readArr("btc5m_bets.json") ?: JSONArray()).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Bet(it.getLong("id"), it.getString("mode"), it.getLong("T"), it.getInt("e"), it.getString("side"), it.getDouble("share"),
                it.getDouble("stake"), it.optBoolean("assumed"), it.optBoolean("auto"), if (it.has("up")) it.getInt("up") else null)
        }
    }

    private fun saveCalls(l: List<JSONObject>) = store.writeArr("btc5m_calls.json", JSONArray(l.takeLast(5000)))
    private fun saveBets(l: List<JSONObject>) = store.writeArr("btc5m_bets.json", JSONArray(l.takeLast(3000)))
    private fun rawCalls() = (store.readArr("btc5m_calls.json") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun rawBets() = (store.readArr("btc5m_bets.json") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    /** Record this checkpoint once. Returns true if it is a NEW confirmed call (for the alert). In paper mode the first
     *  confirmed call of a round also gets an automatic paper bet. */
    @Synchronized fun record(c: Btc5m.Call, typedCents: Double? = null): Boolean {
        if (c.e < 1 || c.pUp == null) return false
        val all = rawCalls().toMutableList()
        if (all.any { it.getLong("T") == c.roundStart && it.getInt("e") == c.e }) return false
        all += JSONObject().put("t", c.now).put("T", c.roundStart).put("e", c.e).put("side", c.side).put("conf", c.conf)
            .put("th", c.threshold ?: JSONObject.NULL).put("ok", c.confirmed).put("hw", c.histWin ?: JSONObject.NULL)
            .put("open", c.open).put("price", c.price)
        saveCalls(all)
        if (!c.confirmed) return false
        val firstConfirmed = all.count { it.getLong("T") == c.roundStart && it.getBoolean("ok") } == 1
        val cfg = cfg()
        if (firstConfirmed && cfg.mode == "paper" && rawBets().none { it.getLong("T") == c.roundStart && it.getString("mode") == "paper" })
            addBet("paper", c.roundStart, c.e, c.side, typedCents, cfg.stake, auto = true)
        return firstConfirmed
    }

    /** Add a bet. share in cents (null = paper with the assumed price). */
    @Synchronized fun addBet(mode: String, T: Long, e: Int, side: String, cents: Double?, stake: Double, auto: Boolean = false) {
        val cfg = cfg()
        val share = ((cents ?: cfg.assumedCents) / 100).coerceIn(0.01, 0.99)
        val l = rawBets().toMutableList()
        l += JSONObject().put("id", System.currentTimeMillis()).put("mode", mode).put("T", T).put("e", e).put("side", side)
            .put("share", share).put("stake", stake).put("assumed", cents == null).put("auto", auto)
        saveBets(l)
    }

    @Synchronized fun deleteBet(id: Long) = saveBets(rawBets().filter { it.getLong("id") != id })

    @Synchronized fun clear(what: String) {   // "calls" | "paper" | "live"
        if (what == "calls") saveCalls(emptyList()) else saveBets(rawBets().filter { it.getString("mode") != what })
    }

    /** Settle every finished round: Up if the close of the round's last 1m bar >= the open of its first bar (Binance spot). */
    fun settle(api: Binance, now: Long = api.nowMs()) {
        val pending = (rawCalls().filter { !it.has("up") }.map { it.getLong("T") } + rawBets().filter { !it.has("up") }.map { it.getLong("T") })
            .filter { it + Btc5m.ROUND + 2_000 <= now }.toSortedSet()
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
            saveCalls(rawCalls().onEach { o -> if (!o.has("up")) res[o.getLong("T")]?.let { o.put("up", it) } })
            saveBets(rawBets().onEach { o -> if (!o.has("up")) res[o.getLong("T")]?.let { o.put("up", it) } })
        }
    }

    fun stats(mode: String): Stats {
        val b = bets().filter { it.mode == mode }
        val done = b.filter { it.win != null }
        return Stats(done.size, done.count { it.win == true }, done.sumOf { it.pnl ?: 0.0 }, done.sumOf { it.stake }, b.size - done.size)
    }

    /** Win rate of recorded calls: (confirmed only?) -> (settled, wins) */
    fun callStats(confirmedOnly: Boolean): Pair<Int, Int> {
        val c = calls().filter { it.up != null && (!confirmedOnly || it.confirmed) }
        return c.size to c.count { it.win == true }
    }

    /** First confirmed call per round: the one-bet-per-round live record. */
    fun firstConfirmedStats(): Pair<Int, Int> {
        val c = calls().filter { it.up != null && it.confirmed }.groupBy { it.T }.values.map { it.minBy { r -> r.e } }
        return c.size to c.count { it.win == true }
    }

    /** CSV of all calls and bets, for Excel. */
    fun csv(): String {
        val sb = StringBuilder("type,mode,round_utc_ms,checkpoint_min,side,conf_or_share,threshold,confirmed,backtest_win,open,price_at_call,stake,result,pnl\n")
        for (c in calls()) sb.append("call,,${c.T},${c.e},${c.side},${String.format(java.util.Locale.US, "%.4f", c.conf)},${c.threshold ?: ""},${c.confirmed},${c.histWin ?: ""},${c.open},${c.price},,${c.win?.let { if (it) "WIN" else "LOSS" } ?: ""},\n")
        for (b in bets()) sb.append("bet,${b.mode},${b.T},${b.e},${b.side},${b.share},,,,,,${b.stake},${b.win?.let { if (it) "WIN" else "LOSS" } ?: ""},${b.pnl?.let { String.format(java.util.Locale.US, "%.4f", it) } ?: ""}\n")
        return sb.toString()
    }
}
