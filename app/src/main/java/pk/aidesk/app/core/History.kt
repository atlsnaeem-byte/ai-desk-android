package pk.aidesk.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Trade history for learning: one row per closed trade of EVERY bot (AI model, Trade tab, Short hunter, BTC hedge,
 * Pump hunter, Copy), with what the market looked like BEFORE entry and how far price went for / against the trade
 * while it was open (MFE / MAE). Exported as CSV (opens in Excel) from Settings > Data, for the laptop to study and
 * retrain on.
 *
 * Context features are rebuilt from public Binance candles after the close (no extra work at entry), on a
 * background thread, so recording never slows down trading.
 */
object History {
    private const val FILE = "trade_history.json"
    private const val H = 3_600_000L
    private const val DAY = 86_400_000L

    class Rec(
        val mode: String, val bot: String, val rule: String, val symbol: String, val side: String,
        val entry: Double, val exit: Double, val qty: Double, val margin: Double?, val leverage: Double?,
        val sl: Double?, val tp: Double?, val opened: Long, val closed: Long, val pnl: Double?, val reason: String,
    )

    /** Record a closed trade (features are added in the background). Never throws. */
    fun record(store: Store, r: Rec) {
        Thread {
            val row = runCatching { build(r) }.getOrElse { base(r).put("features", "error: ${it.message ?: it.javaClass.simpleName}") }
            runCatching { append(store, row) }
        }.start()
    }

    @Synchronized private fun append(store: Store, row: JSONObject) {
        val a = store.readArr(FILE) ?: JSONArray()
        a.put(row)
        val start = max(0, a.length() - 5000)
        store.writeArr(FILE, JSONArray((start until a.length()).map { a.get(it) }))
    }

    fun all(store: Store): List<JSONObject> = store.readArr(FILE)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()

    private fun r4(v: Double?) = v?.takeIf { it.isFinite() }?.let { Math.round(it * 10000) / 10000.0 }

    private fun base(r: Rec): JSONObject {
        val dr = if (r.side == "LONG") 1 else -1
        val move = if (r.entry > 0) (r.exit / r.entry - 1) * 100 * dr else null
        val pkt = Instant.ofEpochMilli(r.opened).atZone(Engine.PKT)
        return JSONObject().apply {
            put("mode", r.mode); put("bot", r.bot); put("rule", r.rule); put("symbol", r.symbol); put("side", r.side)
            put("opened_utc", Instant.ofEpochMilli(r.opened).toString()); put("closed_utc", Instant.ofEpochMilli(r.closed).toString())
            put("hold_hours", r4((r.closed - r.opened) / H.toDouble()))
            put("entry_hour_pkt", pkt.hour); put("entry_weekday", pkt.dayOfWeek.value)
            put("entry", r.entry); put("exit", r.exit); put("qty", r.qty)
            r.margin?.let { put("margin_usdt", r4(it)) }; r.leverage?.let { put("leverage", r4(it)) }
            r.sl?.let { put("sl", it); put("sl_pct", r4((it / r.entry - 1) * 100 * dr)) }
            r.tp?.let { put("tp", it); put("tp_pct", r4((it / r.entry - 1) * 100 * dr)) }
            r.pnl?.let { put("pnl_usdt", r4(it)) }
            if (r.pnl != null && r.margin != null && r.margin > 0) put("ret_pct_margin", r4(r.pnl / r.margin * 100))
            move?.let { put("price_move_pct", r4(it)) }
            put("win", if ((r.pnl ?: move ?: 0.0) > 0) 1 else 0)
            put("reason", r.reason)
        }
    }

    private fun rows(a: JSONArray) = (0 until a.length()).map { a.getJSONArray(it) }

    private fun build(r: Rec): JSONObject {
        val o = base(r)
        val pub = Binance()
        val dr = if (r.side == "LONG") 1 else -1
        // ---- before entry: 7 days of 1h candles ending at the entry
        val pre = rows(pub.klines(r.symbol, "1h", start = r.opened - 7 * DAY, limit = 170)).filter { it.getLong(0) < r.opened }
        if (pre.size >= 25) {
            val c = pre.map { it.getString(4).toDouble() }; val h = pre.map { it.getString(2).toDouble() }
            val l = pre.map { it.getString(3).toDouble() }; val qv = pre.map { it.getString(7).toDouble() }
            val last = c.last()
            fun ch(n: Int) = if (c.size > n) (last / c[c.size - 1 - n] - 1) * 100 else null
            o.put("pre_1h_pct", r4(ch(1))); o.put("pre_4h_pct", r4(ch(4))); o.put("pre_24h_pct", r4(ch(24)))
            o.put("pre_3d_pct", r4(ch(72))); o.put("pre_7d_pct", r4(ch(c.size - 1)))
            o.put("pre_7d_range_pct", r4((h.max() / l.min() - 1) * 100))
            o.put("pre_pos_in_7d_range", r4((last - l.min()) / max(1e-12, h.max() - l.min())))
            val v24 = qv.takeLast(24).sum(); val vAvg = qv.sum() / qv.size * 24
            o.put("pre_vol24_usdt", Math.round(v24)); o.put("pre_vol24_vs_7d_avg", r4(if (vAvg > 0) v24 / vAvg else null))
            val trs = (1 until pre.size).map { i -> max(h[i] - l[i], max(abs(h[i] - c[i - 1]), abs(l[i] - c[i - 1]))) }
            o.put("pre_atr1h_pct", r4(trs.takeLast(14).average() / last * 100))
            // RSI(14) on 1h closes
            val d = (1 until c.size).map { c[it] - c[it - 1] }.takeLast(14)
            val up = d.filter { it > 0 }.sum(); val dn = -d.filter { it < 0 }.sum()
            o.put("pre_rsi14_1h", r4(if (up + dn > 0) 100 * up / (up + dn) else 50.0))
            o.put("entry_vs_last_close_pct", r4((r.entry / last - 1) * 100))
        }
        // ---- BTC before entry
        runCatching {
            val b = rows(pub.klines("BTCUSDT", "1h", start = r.opened - 25 * H, limit = 26)).filter { it.getLong(0) < r.opened }.map { it.getString(4).toDouble() }
            if (b.size >= 25) { o.put("btc_pre_24h_pct", r4((b.last() / b[b.size - 25] - 1) * 100)); o.put("btc_pre_4h_pct", r4((b.last() / b[b.size - 5] - 1) * 100)) }
        }
        // ---- funding: average per 8h over the 3 days before entry, and paid / received during the trade (long pays +)
        runCatching { o.put("funding_pre_3d_avg_pct", r4(pub.fundingSum(r.symbol, r.opened - 3 * DAY, r.opened) / 9 * 100)) }
        runCatching { o.put("funding_during_pct", r4(-dr * pub.fundingSum(r.symbol, r.opened, r.closed) * 100)) }
        // ---- coin age at entry
        runCatching { pub.exchangeInfo()[r.symbol]?.optLong("onboardDate")?.takeIf { it > 0 }?.let { o.put("coin_age_days", (r.opened - it) / DAY) } }
        // ---- during the trade: best (MFE) and worst (MAE) move in the trade's favour, 15m candles (1h if longer than ~15 days)
        runCatching {
            val iv = if (r.closed - r.opened <= 15 * DAY) "15m" else "1h"
            val k = rows(pub.klines(r.symbol, iv, start = r.opened, limit = 1500)).filter { it.getLong(0) < r.closed }
            if (k.isNotEmpty()) {
                val hi = k.maxOf { it.getString(2).toDouble() }; val lo = k.minOf { it.getString(3).toDouble() }
                val mfe = if (dr == 1) (hi / r.entry - 1) * 100 else (1 - lo / r.entry) * 100
                val mae = if (dr == 1) (lo / r.entry - 1) * 100 else (1 - hi / r.entry) * 100
                o.put("mfe_pct", r4(mfe)); o.put("mae_pct", r4(min(0.0, mae)))
                // which came first: half of the best move, or half of the worst move
                val firstHi = k.indexOfFirst { it.getString(2).toDouble() >= hi }; val firstLo = k.indexOfFirst { it.getString(3).toDouble() <= lo }
                o.put("best_before_worst", if ((dr == 1 && firstHi < firstLo) || (dr == -1 && firstLo < firstHi)) 1 else 0)
            }
        }
        o.put("features", "ok")
        return o
    }

    // ------------------------------------------------------------------ export
    private val ORDER = listOf("mode", "bot", "rule", "symbol", "side", "opened_utc", "closed_utc", "hold_hours", "entry_hour_pkt", "entry_weekday",
        "entry", "exit", "qty", "margin_usdt", "leverage", "sl", "sl_pct", "tp", "tp_pct", "pnl_usdt", "ret_pct_margin", "price_move_pct", "win", "reason",
        "pre_1h_pct", "pre_4h_pct", "pre_24h_pct", "pre_3d_pct", "pre_7d_pct", "pre_7d_range_pct", "pre_pos_in_7d_range", "pre_vol24_usdt",
        "pre_vol24_vs_7d_avg", "pre_atr1h_pct", "pre_rsi14_1h", "entry_vs_last_close_pct", "btc_pre_24h_pct", "btc_pre_4h_pct",
        "funding_pre_3d_avg_pct", "funding_during_pct", "coin_age_days", "mfe_pct", "mae_pct", "best_before_worst", "features")

    /**
     * CSV of every recorded trade, plus trades closed BEFORE recording started (from each bot's journal, without
     * market features) so nothing is missing. Newest first.
     */
    fun csv(store: Store): Pair<String, Int> {
        val rec = all(store).toMutableList()
        val seen = rec.map { it.optString("bot") + "|" + it.optString("symbol") + "|" + it.optString("opened_utc") + "|" + it.optString("mode") }.toHashSet()
        fun old(r: Rec) {
            val b = base(r).put("features", "none (closed before history recording)")
            if (seen.add(r.bot + "|" + r.symbol + "|" + b.optString("opened_utc") + "|" + r.mode)) rec += b
        }
        for (mode in listOf("paper", "testnet", "live")) {
            store.journal(mode).filter { it.status == "closed" && it.exit != null }.forEach { t ->
                old(Rec(mode, if (t.origin == "manual") "trade tab" else "AI ${t.origin}", t.patterns.firstOrNull() ?: "", t.symbol, t.side, t.entry,
                    t.exit!!, t.qty, null, null, t.sl, t.tp, t.openedMs, t.exitMs ?: t.openedMs, t.pnl, t.reason ?: ""))
            }
            ShortHunter(store).journal(mode).forEach { d ->
                val hedge = d.rule == "btc hedge"
                old(Rec(mode, if (hedge) "btc hedge" else "short hunter", d.rule, d.symbol, if (hedge) "LONG" else "SHORT", d.entry, d.exit, 0.0,
                    d.retPct?.takeIf { it != 0.0 }?.let { d.pnl / it * 100 }, null, null, null, d.opened, d.closed, d.pnl, d.reason))
            }
            PumpHunter(store).journal(mode).forEach { d ->
                old(Rec(mode, "pump hunter", d.rule, d.symbol, "LONG", d.entry, d.exit, 0.0,
                    d.retPct?.takeIf { it != 0.0 }?.let { d.pnl / it * 100 }, null, null, null, d.opened, d.closed, d.pnl, d.reason))
            }
            CopyBot(store).journal(mode).forEach { d ->
                old(Rec(mode, "copy", d.leader, d.symbol, d.side, d.entry, d.exit, 0.0, null, d.lev.toDouble(), null, null, d.opened, d.closed, d.pnl, d.reason))
            }
        }
        rec.sortByDescending { it.optString("closed_utc") }
        val cols = ORDER + rec.flatMap { it.keys().asSequence().toList() }.distinct().filter { it !in ORDER }
        val sb = StringBuilder()
        sb.append(cols.joinToString(",")).append("\r\n")
        for (o in rec) sb.append(cols.joinToString(",") { k -> cell(if (o.has(k) && !o.isNull(k)) o.get(k) else null) }).append("\r\n")
        return sb.toString() to rec.size
    }

    /** Any list of JSON rows (e.g. AI signal results) as CSV. */
    fun csvOf(rows: List<JSONObject>): String {
        val cols = rows.flatMap { it.keys().asSequence().toList() }.distinct()
        val sb = StringBuilder(cols.joinToString(",")).append("\r\n")
        for (o in rows) sb.append(cols.joinToString(",") { k -> cell(if (o.has(k) && !o.isNull(k)) o.get(k) else null) }).append("\r\n")
        return sb.toString()
    }

    private fun cell(v: Any?): String = when (v) {
        null -> ""
        is Number -> if (v is Double || v is Float) String.format(Locale.US, "%.8g", v.toDouble()).let { s ->
            if (s.contains('e') || s.contains('E')) v.toString() else s.trimEnd('0').trimEnd('.') } else v.toString()
        is JSONArray -> q((0 until v.length()).joinToString("; ") { v.opt(it).toString() })
        else -> q(v.toString())
    }

    private fun q(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
