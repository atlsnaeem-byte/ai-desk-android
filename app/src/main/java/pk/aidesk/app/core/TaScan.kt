package pk.aidesk.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/** One TA-course signal (Confluence setup) on a coin + timeframe, kept until its SL / TP / time exit. */
data class TaSignal(
    val id: String, val symbol: String, val tf: String, val side: String, val score: Int, val title: String,
    val why: List<String>, val barTime: Long, val since: Long, val entry: Double, val sl: Double, val tp: Double,
    val status: String = "active", val closedMs: Long? = null, val exit: Double? = null, val r: Double? = null,
) {
    val active: Boolean get() = status == "active"
    val dir: Int get() = if (side == "LONG") 1 else -1
    val rr: Double get() = abs(tp - entry) / maxOf(abs(entry - sl), 1e-12)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("symbol", symbol); put("tf", tf); put("side", side); put("score", score); put("title", title)
        put("why", JSONArray(why)); put("barTime", barTime); put("since", since); put("entry", entry); put("sl", sl); put("tp", tp)
        put("status", status); closedMs?.let { put("closedMs", it) }; exit?.let { put("exit", it) }; r?.let { put("r", it) }
    }

    companion object {
        fun from(o: JSONObject) = TaSignal(
            o.getString("id"), o.getString("symbol"), o.getString("tf"), o.getString("side"), o.getInt("score"), o.getString("title"),
            o.optJSONArray("why")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            o.getLong("barTime"), o.getLong("since"), o.getDouble("entry"), o.getDouble("sl"), o.getDouble("tp"),
            o.optString("status", "active"), if (o.has("closedMs")) o.getLong("closedMs") else null,
            if (o.has("exit")) o.getDouble("exit") else null, if (o.has("r")) o.getDouble("r") else null,
        )
    }
}

/**
 * Scans the configured coins after every 4h close for TA-course setups (RSI divergence, range fade; see Confluence.TFS).
 * Signals are listed on the Signals screen and notified; they are resolved bar by bar
 * (stop first when both levels fall in one bar, the same as the backtest).
 */
class TaScanner(private val store: Store) {
    companion object {
        const val FILE = "ta_signals.json"
        private const val COST = 0.0007          // fee + slippage per side (backtest.py)
        private val LOCK = Any()
    }

    fun all(): List<TaSignal> = synchronized(LOCK) {
        store.readArr(FILE)?.let { a -> (0 until a.length()).mapNotNull { runCatching { TaSignal.from(a.getJSONObject(it)) }.getOrNull() } }
            ?: emptyList()
    }

    private fun save(l: List<TaSignal>) = synchronized(LOCK) {
        store.writeArr(FILE, JSONArray(l.sortedBy { it.since }.takeLast(500).map { it.toJson() }))
    }

    fun clearClosed() = save(all().filter { it.active })

    private fun closed(c: Candles, now: Long): Candles {
        var n = c.size
        while (n > 0 && c.closeTime[n - 1] >= now) n--
        return Advisor.slice(c, 0, n)
    }

    fun step(api: Binance, symbols: List<String>): List<Event> {
        val now = api.nowMs()
        val events = ArrayList<Event>()
        val list = all().toMutableList()
        var changed = false
        for (tf in Confluence.TFS) {
            val ms = Advisor.TF_MS.getValue(tf)
            val lastOpen = (now / ms) * ms - ms                       // open time of the last closed bar
            val key = "taLast_$tf"
            if (store.readObj("ta_state.json")?.optLong(key) == lastOpen) continue
            for (s in symbols) {
                try {
                    val cd = closed(api.candles(s, tf, 501), now)
                    if (cd.size < 260) continue
                    // resolve this coin's open signals on this timeframe
                    for ((k, sg) in list.withIndex()) {
                        if (!sg.active || sg.symbol != s || sg.tf != tf) continue
                        resolve(sg, cd)?.let { list[k] = it; changed = true }
                    }
                    if (list.any { it.active && it.symbol == s && it.tf == tf }) continue
                    val hi = closed(api.candles(s, Advisor.HIGHER.getValue(tf), 260), now)
                    val setup = Confluence.evaluate(cd, Patterns.regime(hi.close), Patterns.scan(cd).active) ?: continue
                    if (setup.kinds.none { it in Confluence.SIGNAL_KINDS } || setup.barTime != cd.openTime[cd.size - 1]) continue
                    val sg = TaSignal("$s-$tf-${setup.barTime}", s, tf, setup.sideName, setup.score, setup.title,
                        setup.why + Confluence.backtestNote(tf, setup.kinds), setup.barTime, setup.barTime + ms, setup.entry, setup.sl, setup.tp)
                    if (list.any { it.id == sg.id }) continue
                    list += sg; changed = true
                    events += Event("tasignal", "Course setup: ${sg.side} $s $tf  -  score ${sg.score}",
                        "${sg.title}  -  entry ${Advisor.f(sg.entry)}  TP ${Advisor.f(sg.tp)}  SL ${Advisor.f(sg.sl)}", true, s)
                } catch (e: Exception) {
                    store.log("WARN", "TA scan $s $tf: ${(e as? BinanceException)?.msg ?: e.message ?: e.javaClass.simpleName}")
                }
            }
            val st = store.readObj("ta_state.json") ?: JSONObject()
            store.writeObj("ta_state.json", st.put(key, lastOpen))
        }
        if (changed) save(list)
        return events
    }

    /** Walk the bars after the signal bar: SL first, then TP, else time exit after HOLD bars. */
    private fun resolve(sg: TaSignal, cd: Candles): TaSignal? {
        var n = 0
        val ms = Advisor.TF_MS.getValue(sg.tf)
        for (j in 0 until cd.size) {
            if (cd.openTime[j] <= sg.barTime) continue
            n++
            val hitSl = if (sg.dir == 1) cd.low[j] <= sg.sl else cd.high[j] >= sg.sl
            val hitTp = if (sg.dir == 1) cd.high[j] >= sg.tp else cd.low[j] <= sg.tp
            val (st, px) = when {
                hitSl -> "SL" to sg.sl
                hitTp -> "TP" to sg.tp
                n >= Confluence.HOLD -> "TIME" to cd.close[j]
                else -> continue
            }
            return close(sg, st, px, cd.openTime[j] + ms)
        }
        return null
    }

    fun close(sg: TaSignal, status: String, px: Double, at: Long): TaSignal {
        val risk = abs(sg.entry - sg.sl)
        val r = (sg.dir * (px - sg.entry) - COST * (sg.entry + px)) / risk
        return sg.copy(status = status, closedMs = at, exit = px, r = r)
    }

    fun stats(): String? {
        val done = all().filter { !it.active && it.r != null }
        if (done.isEmpty()) return null
        val w = done.count { it.r!! > 0 }
        return String.format(Locale.US, "%d closed: %d won, avg %+.2fR", done.size, w, done.sumOf { it.r!! } / done.size)
    }
}
