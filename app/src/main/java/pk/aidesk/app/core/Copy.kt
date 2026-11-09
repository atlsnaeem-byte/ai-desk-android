package pk.aidesk.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ------------------------------------------------------------------ Binance copy-trading public data
class LeadPos(val symbol: String, val side: String, val amt: Double, val entry: Double, val mark: Double, val lev: Int, val notional: Double)

class LeadDetail(val name: String, val margin: Double, val show: Boolean, val status: String, val slots: String)

/** Public Binance copy-trading endpoints (the data the Binance web page shows). Every call returns null on failure. */
object Bapi {
    private const val BASE = "https://www.binance.com/bapi/futures/v1/friendly/future/copy-trade/"

    private fun http(method: String, url: String, body: String? = null): JSONObject? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        c.setRequestProperty("Content-Type", "application/json")
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        try {
            if (c.responseCode != 200) null
            else JSONObject(c.inputStream.bufferedReader().use { it.readText() }).takeIf { it.optString("code") == "000000" }
        } finally { c.disconnect() }
    } catch (e: Exception) { null }

    private fun q(pid: String) = "portfolioId=" + URLEncoder.encode(pid, "UTF-8")

    /** "SYM|LONG" -> position, or null if the read failed (never treat a failed read as "no positions"). */
    fun positions(pid: String): Map<String, LeadPos>? {
        val a = http("GET", BASE + "lead-data/positions?" + q(pid))?.optJSONArray("data") ?: return null
        if (a.length() == 0) return null           // a real reply lists every symbol (hundreds of rows)
        val out = LinkedHashMap<String, LeadPos>()
        for (i in 0 until a.length()) {
            val x = a.getJSONObject(i)
            val amt = x.optString("positionAmount").toDoubleOrNull() ?: 0.0
            if (amt == 0.0) continue
            val ps = x.optString("positionSide", "BOTH")
            val side = if (ps == "LONG" || ps == "SHORT") ps else if (amt > 0) "LONG" else "SHORT"
            val sym = x.getString("symbol")
            out["$sym|$side"] = LeadPos(sym, side, abs(amt), x.optString("entryPrice").toDoubleOrNull() ?: 0.0,
                x.optString("markPrice").toDoubleOrNull() ?: 0.0, x.optString("leverage").toDoubleOrNull()?.toInt() ?: 1,
                abs(x.optString("notionalValue").toDoubleOrNull() ?: 0.0))
        }
        return out
    }

    fun detail(pid: String): LeadDetail? {
        val d = http("GET", BASE + "lead-portfolio/detail?" + q(pid))?.optJSONObject("data") ?: return null
        return LeadDetail(d.optString("nickname").ifEmpty { pid.takeLast(6) }, d.optString("marginBalance").toDoubleOrNull() ?: 0.0,
            d.optBoolean("positionShow", false), d.optString("status"),
            "${d.optInt("currentCopyCount")}/${d.optInt("maxCopyCount")}")
    }

    fun history(pid: String): List<JSONObject> {
        val body = JSONObject().put("pageNumber", 1).put("pageSize", 50).put("portfolioId", pid).put("sort", "OPENING").toString()
        val a = http("POST", BASE + "lead-portfolio/position-history", body)?.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    /** Accepts a portfolio id or a Binance lead-details link. */
    fun parseId(s: String): String? = Regex("(\\d{15,22})").find(s.trim())?.groupValues?.get(1)
}

// ------------------------------------------------------------------ config / state
class Leader(val id: String, val name: String, val enabled: Boolean, val note: String = "") {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("enabled", enabled).put("note", note)
    fun with(enabled: Boolean) = Leader(id, name, enabled, note)

    companion object {
        fun from(o: JSONObject) = Leader(o.getString("id"), o.optString("name", o.getString("id")), o.optBoolean("enabled", false), o.optString("note"))
    }
}

data class CopyCfg(
    val enabled: Boolean = false,
    val leaders: List<Leader> = DEFAULT_LEADERS,
    val sizeMult: Double = 1.0,
    val bumpToMin: Boolean = true,
    val maxNotional: Double = 75.0,
    val maxLev: Int = 10,
    val maxPositions: Int = 25,
    val maxExposureX: Double = 3.0,
    val followAdds: Boolean = true,
    val stopPct: Double = 30.0,
    val dailyLossPct: Double = 15.0,
    val pollSec: Int = 10,
    val paperStart: Double = 100.0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled); put("leaders", JSONArray(leaders.map { it.toJson() })); put("sizeMult", sizeMult); put("bumpToMin", bumpToMin)
        put("maxNotional", maxNotional); put("maxLev", maxLev); put("maxPositions", maxPositions); put("maxExposureX", maxExposureX)
        put("followAdds", followAdds); put("stopPct", stopPct); put("dailyLossPct", dailyLossPct); put("pollSec", pollSec)
        put("paperStart", paperStart); put("maxPosV25", true)
    }

    companion object {
        /** Picked 8 Nov 2026 from 2,929 leaders (see "Binance Public Traders - Copy Review"). */
        val DEFAULT_LEADERS = listOf(
            Leader("4734236985151411201", "Nasti-Hamsters", true, "long-only alt scalper, +120%/yr, DD 13%"),
            Leader("4734274187381923585", "Rocky-Hamsters", true, "long-only alt scalper, +261%/yr, DD 12%"),
            Leader("4955446502588956417", "Quantative trading algo", true, "XMR algo, +277%/yr, worst trade -13%"),
            Leader("4751341070898456065", "SatoshiW", false, "80% same trades as Nasti-Hamsters"),
            Leader("4882735096799123968", "京吉", false, "profit factor only 1.26; mostly BTC"),
        )

        fun from(o: JSONObject?): CopyCfg {
            val d = CopyCfg(); o ?: return d
            return CopyCfg(o.optBoolean("enabled", d.enabled),
                o.optJSONArray("leaders")?.let { a -> (0 until a.length()).map { Leader.from(a.getJSONObject(it)) } } ?: d.leaders,
                o.optDouble("sizeMult", d.sizeMult), o.optBoolean("bumpToMin", d.bumpToMin), o.optDouble("maxNotional", d.maxNotional),
                // one-time upgrade (Nov 2026): the user asked for up to 25 copies; after that their own choice is kept
                o.optInt("maxLev", d.maxLev), if (o.optBoolean("maxPosV25")) o.optInt("maxPositions", d.maxPositions) else d.maxPositions,
                o.optDouble("maxExposureX", d.maxExposureX),
                o.optBoolean("followAdds", d.followAdds), o.optDouble("stopPct", d.stopPct), o.optDouble("dailyLossPct", d.dailyLossPct),
                o.optInt("pollSec", d.pollSec), o.optDouble("paperStart", d.paperStart))
        }
    }
}

class CopyPos(
    val key: String, val leaderId: String, val leader: String, val symbol: String, val side: String,
    var qty: Double, var qty0: Double, var entry: Double, val lev: Int, val opened: Long, var entryFee: Double,
    val ratio: Double, val leaderEntry: Double, var leaderAmt: Double, val slipPct: Double?, var realized: Double = 0.0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key); put("leaderId", leaderId); put("leader", leader); put("symbol", symbol); put("side", side); put("qty", qty)
        put("qty0", qty0); put("entry", entry); put("lev", lev); put("opened", opened); put("entryFee", entryFee); put("ratio", ratio)
        put("leaderEntry", leaderEntry); put("leaderAmt", leaderAmt); slipPct?.let { put("slipPct", it) }; put("realized", realized)
    }

    companion object {
        fun from(o: JSONObject) = CopyPos(o.getString("key"), o.getString("leaderId"), o.getString("leader"), o.getString("symbol"),
            o.getString("side"), o.getDouble("qty"), o.optDouble("qty0", o.getDouble("qty")), o.getDouble("entry"), o.getInt("lev"),
            o.getLong("opened"), o.optDouble("entryFee", 0.0), o.getDouble("ratio"), o.optDouble("leaderEntry", 0.0),
            o.optDouble("leaderAmt", 0.0), if (o.has("slipPct")) o.getDouble("slipPct") else null, o.optDouble("realized", 0.0))
    }
}

class CopyState(var paperEquity: Double, val copies: MutableMap<String, CopyPos> = linkedMapOf(),
                var day: String = "", var dayStart: Double? = null, var dayPnl: Double = 0.0) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("paperEquity", paperEquity); put("day", day); put("dayPnl", dayPnl); dayStart?.let { put("dayStart", it) }
        put("copies", JSONObject().also { o -> copies.forEach { (k, v) -> o.put(k, v.toJson()) } })
    }

    companion object {
        fun from(o: JSONObject?, start: Double): CopyState {
            o ?: return CopyState(start)
            val c = o.optJSONObject("copies")
            return CopyState(o.optDouble("paperEquity", start),
                c?.keys()?.asSequence()?.associateWith { CopyPos.from(c.getJSONObject(it)) }?.toMutableMap() ?: linkedMapOf(),
                o.optString("day"), if (o.has("dayStart")) o.getDouble("dayStart") else null, o.optDouble("dayPnl", 0.0))
        }
    }
}

class CopyDone(val leader: String, val symbol: String, val side: String, val entry: Double, val exit: Double, val leaderEntry: Double,
               val slipPct: Double?, val delayS: Double?, val lev: Int, val opened: Long, val closed: Long, val reason: String,
               val pnl: Double, val retPct: Double) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("leader", leader); put("symbol", symbol); put("side", side); put("entry", entry); put("exit", exit); put("leaderEntry", leaderEntry)
        slipPct?.let { put("slipPct", it) }; delayS?.let { put("delayS", it) }; put("lev", lev); put("opened", opened); put("closed", closed)
        put("reason", reason); put("pnl", pnl); put("retPct", retPct)
    }

    companion object {
        fun from(o: JSONObject) = CopyDone(o.getString("leader"), o.getString("symbol"), o.getString("side"), o.getDouble("entry"),
            o.getDouble("exit"), o.optDouble("leaderEntry", 0.0), if (o.has("slipPct")) o.getDouble("slipPct") else null,
            if (o.has("delayS")) o.getDouble("delayS") else null, o.optInt("lev", 1), o.getLong("opened"), o.getLong("closed"),
            o.getString("reason"), o.getDouble("pnl"), o.optDouble("retPct", 0.0))
    }
}

// ------------------------------------------------------------------ bot
/**
 * Port of copy_bot.py. Mirrors leaders' PUBLIC positions: new position -> open the same coin + side; leader reduces ->
 * reduce by the same fraction; leader closes -> close. Positions a leader already held when following started are skipped.
 * Keep ONE instance alive (the baseline lives in memory, like the laptop bot).
 */
class CopyBot(private val store: Store) {
    companion object {
        private const val FEE = 0.0005
        private const val SLIP = 0.05
        private const val DAY = 86_400_000L
        private val STEP_LOCK = Any()
    }

    private val baseline = HashMap<String, MutableSet<String>>()
    private val caps = HashMap<String, Double>()
    private var capsAt = 0L
    private val fails = HashMap<String, Int>()
    private var lastMode: String? = null

    fun cfg(): CopyCfg = CopyCfg.from(store.readObj("copy_cfg.json"))
    fun saveCfg(c: CopyCfg) = store.writeObj("copy_cfg.json", c.toJson())
    fun state(mode: String): CopyState = CopyState.from(store.readObj("copy_state_$mode.json"), cfg().paperStart)
    private fun saveState(mode: String, s: CopyState) = store.writeObj("copy_state_$mode.json", s.toJson())
    fun journal(mode: String): List<CopyDone> = store.readArr("copy_journal_$mode.json")?.let { a ->
        (0 until a.length()).mapNotNull { runCatching { CopyDone.from(a.getJSONObject(it)) }.getOrNull() }
    } ?: emptyList()
    private fun addJournal(mode: String, d: CopyDone) =
        store.writeArr("copy_journal_$mode.json", JSONArray((journal(mode) + d).takeLast(2000).map { it.toJson() }))

    /** Forget which leader positions were "already open" (e.g. after enabling a leader or switching mode). */
    fun resetBaseline(leaderId: String? = null) { if (leaderId == null) baseline.clear() else baseline.remove(leaderId) }

    private fun log(msg: String) = store.log("INFO", "Copy: $msg")

    /** One poll. Shares a lock with closeManual (the UI and the background service use different instances). */
    fun step(mode: String, api: Binance, pub: Binance): List<Event> = synchronized(STEP_LOCK) { stepLocked(mode, api, pub) }

    /** Close one copy now at market (button in the Copy trade screen). ck = key in CopyState.copies. */
    fun closeManual(mode: String, api: Binance, pub: Binance, ck: String): String = synchronized(STEP_LOCK) {
        val c = cfg()
        val st = state(mode)
        val cp = st.copies[ck] ?: throw UserError("That copy is already closed.")
        val ev = ArrayList<Event>()
        reduce(mode, c, st, api, pub, ck, cp, cp.qty, "closed by you", api.nowMs(), ev)
        saveState(mode, st)
        ev.firstOrNull()?.let { it.title + "  " + it.text } ?: "Closed ${cp.symbol}"
    }

    private fun stepLocked(mode: String, api: Binance, pub: Binance): List<Event> {
        val c = cfg()
        val st = state(mode)
        val ev = mutableListOf<Event>()
        if (lastMode != mode) { baseline.clear(); lastMode = mode; capsAt = 0 }
        if (!c.enabled && st.copies.isEmpty()) { baseline.clear(); return ev }
        val live = mode != "paper"
        val now = api.nowMs()
        if (System.currentTimeMillis() - capsAt > 30 * 60_000L) {
            capsAt = System.currentTimeMillis()
            for (l in c.leaders.filter { it.enabled }) Bapi.detail(l.id)?.let { caps[l.id] = it.margin }
        }
        val livePos = if (live) api.positions() else emptyMap()
        emergency(mode, c, st, api, pub, now, livePos, ev)
        for (l in c.leaders) {
            val mine = st.copies.filterValues { it.leaderId == l.id }
            if (!c.enabled || !l.enabled) {
                if (mine.isEmpty()) continue            // switched off and nothing left to manage
            }
            val pos = Bapi.positions(l.id)
            if (pos == null) {
                val n = (fails[l.id] ?: 0) + 1; fails[l.id] = n
                if (n == 3 || n == 60) log("${l.name}: positions feed failing ($n times in a row); holding copies")
                if (n >= 60 && mine.isNotEmpty()) {
                    val d = Bapi.detail(l.id)
                    if (d != null && (!d.show || d.status != "ACTIVE"))
                        for ((ck, cp) in mine) reduce(mode, c, st, api, pub, ck, cp, cp.qty, "leader hid positions / stopped leading", now, ev)
                }
                continue
            }
            fails[l.id] = 0
            val base = baseline[l.id]
            if (base == null) {
                baseline[l.id] = pos.keys.toMutableSet()
                log("${l.name}: ${pos.size} position(s) already open, not copied: ${pos.keys.joinToString().ifEmpty { "-" }}")
                continue
            }
            base.retainAll(pos.keys)                     // after the leader closes a baseline position, a re-open counts
            for ((ck, cp) in mine) {
                if (ck !in st.copies) continue
                val lp = pos[cp.key]
                if (lp == null) { reduce(mode, c, st, api, pub, ck, cp, cp.qty, "leader closed", now, ev); continue }
                val target = lp.amt * cp.ratio
                val step = api.filters(cp.symbol).step
                if (lp.amt < cp.leaderAmt * 0.98 && cp.qty - target >= step)
                    reduce(mode, c, st, api, pub, ck, cp, cp.qty - target, "leader reduced", now, ev)
                else if (lp.amt > cp.leaderAmt * 1.02 && c.followAdds && target - cp.qty >= step)
                    add(mode, c, st, api, pub, cp, target - cp.qty)
                cp.leaderAmt = lp.amt
            }
            if (!c.enabled || !l.enabled) continue        // only exits are followed for switched-off leaders
            val held = st.copies.values.filter { it.leaderId == l.id }.map { it.key }.toSet()
            for ((key, lp) in pos) {
                if (key in base || key in held) continue
                base += key                               // one attempt per leader position, never chase it later
                try {
                    open(mode, c, st, api, pub, l, key, lp, now, livePos)?.let { ev += it }
                } catch (e: Exception) {
                    val m = (e as? BinanceException)?.msg ?: e.message ?: e.javaClass.simpleName
                    store.log("ERROR", "Copy ${l.name} ${lp.symbol}: open failed: $m")
                    ev += Event("error", "Copy: ${lp.symbol} open failed", m, false, lp.symbol)
                }
            }
        }
        saveState(mode, st)
        return ev
    }

    private fun equity(live: Boolean, st: CopyState, api: Binance): Pair<Double, Double> =
        if (live) api.equity() else st.paperEquity to st.paperEquity - st.copies.values.sumOf { it.qty * it.entry / it.lev }

    private fun open(mode: String, c: CopyCfg, st: CopyState, api: Binance, pub: Binance, l: Leader, key: String, lp: LeadPos,
                     now: Long, livePos: Map<String, JSONObject>): Event? {
        val s = lp.symbol; val tag = "${l.name} $s ${lp.side}"
        val live = mode != "paper"
        if (st.copies.values.any { it.symbol == s } || s in livePos) { log("$tag: skip, already holding $s (one-way account)"); return null }
        if (live && s in store.botSymbols(mode)) { log("$tag: skip, $s is used by another bot"); return null }
        if (st.copies.size >= c.maxPositions) { log("$tag: skip, ${c.maxPositions} copies open"); return null }
        val (eq, avail) = equity(live, st, api)
        val today = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        if (st.day != today) { st.day = today; st.dayStart = eq; st.dayPnl = 0.0 }
        if (st.dayPnl <= -c.dailyLossPct / 100 * (st.dayStart ?: eq)) { log("$tag: skip, daily loss limit hit"); return null }
        val info = api.exchangeInfo()[s]
        if (info == null || info.optString("status") != "TRADING") { log("$tag: skip, $s not tradable"); return null }
        val cap = caps[l.id] ?: Bapi.detail(l.id)?.margin?.also { caps[l.id] = it } ?: 0.0
        if (cap <= 0) { log("$tag: skip, leader capital unknown"); return null }
        val price = pub.markPrice(s)
        val f = api.filters(s)
        var notional = (if (lp.notional > 0) lp.notional else lp.amt * price) * eq / cap * c.sizeMult
        val floor = max(f.minNotional, f.minQty * price) * 1.08
        if (notional < floor) {
            if (c.bumpToMin && floor <= c.maxNotional) notional = floor
            else { log("$tag: skip, size ${f2(notional)} USDT below Binance minimum ${f2(floor)}"); return null }
        }
        notional = min(notional, c.maxNotional)
        val openN = st.copies.values.sumOf { it.qty * it.entry }
        if (openN + notional > c.maxExposureX * eq) { log("$tag: skip, total exposure would exceed ${c.maxExposureX}x equity"); return null }
        val lev = lp.lev.coerceIn(1, c.maxLev)
        if (notional / lev > avail) { log("$tag: skip, free margin ${f2(avail)} < ${f2(notional / lev)}"); return null }
        val (qty, q) = api.fmtQty(s, notional / price)
        if (qty <= 0 || qty * price < f.minNotional) { log("$tag: skip, rounded size too small"); return null }
        val buy = lp.side == "LONG"; val dr = if (buy) 1 else -1
        val entry: Double
        if (!live) {
            entry = price * (1 + dr * SLIP / 100)
            st.paperEquity -= qty * entry * FEE
        } else {
            api.setMarginType(s, "ISOLATED"); api.setLeverage(s, lev)
            val r = api.marketOrder(s, if (buy) "BUY" else "SELL", q)
            entry = r.optString("avgPrice").toDoubleOrNull()?.takeIf { it > 0 } ?: price
            val stop = entry * (1 - dr * c.stopPct / 100)
            try {
                api.closeTrigger(s, if (buy) "SELL" else "BUY", "STOP_MARKET", api.fmtPrice(s, stop).second)
            } catch (e: BinanceException) {
                store.log("ERROR", "Copy $tag: emergency stop rejected (${e.msg}), closing for safety")
                api.marketOrder(s, if (buy) "SELL" else "BUY", q, reduceOnly = true)
                return Event("error", "Copy: $s closed", "Binance rejected the emergency stop, so the copy was closed.", true, s)
            }
        }
        val slip = if (lp.entry > 0) (entry / lp.entry - 1) * 100 * dr else null
        st.copies["${l.id}|$key"] = CopyPos(key, l.id, l.name, s, lp.side, qty, qty, entry, lev, now, qty * entry * FEE, qty / lp.amt,
            lp.entry, lp.amt, slip?.let { Math.round(it * 1000) / 1000.0 })
        log("OPEN ${lp.side} $s copy of ${l.name}: qty $q @ ${Engine.fmt(entry)} (${f2(qty * entry)} USDT, ${lev}x)" +
            (slip?.let { ", ${String.format(Locale.US, "%+.2f", it)}% vs leader" } ?: ""))
        return Event("botopen", "Copy: ${lp.side} $s", "Copied ${l.name}  -  ${Engine.fmt(qty)} @ ${Engine.fmt(entry)} (${f2(qty * entry)} USDT, ${lev}x)", false, s)
    }

    private fun reduce(mode: String, c: CopyCfg, st: CopyState, api: Binance, pub: Binance, ck: String, cp: CopyPos, qty0: Double,
                       reason: String, now: Long, ev: MutableList<Event>) {
        val live = mode != "paper"
        val buy = cp.side == "LONG"; val dr = if (buy) 1 else -1
        val full = qty0 >= cp.qty - 1e-12
        val (qty, q) = api.fmtQty(cp.symbol, if (full) cp.qty else qty0)
        if (qty <= 0) return
        val px: Double; val pnl: Double
        if (!live) {
            px = pub.markPrice(cp.symbol) * (1 - dr * SLIP / 100)
            pnl = (px - cp.entry) * qty * dr - px * qty * FEE
            st.paperEquity += pnl
        } else {
            if (full) api.cancelAll(cp.symbol)
            val r = api.marketOrder(cp.symbol, if (buy) "SELL" else "BUY", q, reduceOnly = true)
            px = r.optString("avgPrice").toDoubleOrNull()?.takeIf { it > 0 } ?: pub.markPrice(cp.symbol)
            pnl = (px - cp.entry) * qty * dr - px * qty * FEE
        }
        val partFee = if (cp.qty > 0) cp.entryFee * min(1.0, qty / cp.qty) else 0.0
        cp.realized += pnl - partFee
        cp.entryFee -= partFee
        st.dayPnl += pnl - partFee
        if (full) finish(mode, st, ck, cp, px, reason, now, ev)
        else { cp.qty -= qty; log("REDUCE ${cp.symbol} by $q ($reason), left ${Engine.fmt(cp.qty)}") }
    }

    private fun finish(mode: String, st: CopyState, ck: String, cp: CopyPos, px: Double, reason: String, now: Long, ev: MutableList<Event>) {
        var delay: Double? = null
        for (h in Bapi.history(cp.leaderId)) {
            val opened = h.optLong("opened"); val closed = h.optLong("closed", now).takeIf { it > 0 } ?: now
            if (h.optString("symbol") == cp.symbol && h.optString("side").uppercase() == cp.side && opened <= cp.opened && cp.opened <= closed) {
                delay = (cp.opened - opened) / 1000.0; break
            }
        }
        val margin = cp.entry * cp.qty0 / cp.lev
        addJournal(mode, CopyDone(cp.leader, cp.symbol, cp.side, cp.entry, px, cp.leaderEntry, cp.slipPct, delay, cp.lev, cp.opened, now,
            reason, cp.realized, if (margin > 0) cp.realized / margin * 100 else 0.0))
        st.copies.remove(ck)
        log("CLOSE ${cp.side} ${cp.symbol} (${cp.leader}) $reason, PnL ${String.format(Locale.US, "%+.4f", cp.realized)}")
        ev += Event("botclose", "Copy closed ${cp.symbol} ${cp.side}  -  $reason", "${String.format(Locale.US, "%+.4f", cp.realized)} USDT  -  ${cp.leader}", false, cp.symbol)
    }

    private fun add(mode: String, c: CopyCfg, st: CopyState, api: Binance, pub: Binance, cp: CopyPos, extra: Double) {
        val live = mode != "paper"
        val price = pub.markPrice(cp.symbol)
        val f = api.filters(cp.symbol)
        val (qty, q) = api.fmtQty(cp.symbol, extra)
        if (qty <= 0 || qty * price < f.minNotional) return
        val (eq, avail) = equity(live, st, api)
        val openN = st.copies.values.sumOf { it.qty * it.entry }
        if (openN + qty * price > c.maxExposureX * eq || qty * price / cp.lev > avail || (cp.qty + qty) * price > c.maxNotional * 2) return
        val buy = cp.side == "LONG"
        val fill = if (!live) {
            st.paperEquity -= qty * price * FEE
            price * (1 + (if (buy) 1 else -1) * SLIP / 100)
        } else {
            api.marketOrder(cp.symbol, if (buy) "BUY" else "SELL", q).optString("avgPrice").toDoubleOrNull()?.takeIf { it > 0 } ?: price
        }
        cp.entry = (cp.entry * cp.qty + fill * qty) / (cp.qty + qty)
        cp.qty += qty
        cp.qty0 = max(cp.qty0, cp.qty)
        cp.entryFee += qty * fill * FEE
        log("ADD ${cp.symbol} +$q (leader added), now ${Engine.fmt(cp.qty)}")
    }

    private fun emergency(mode: String, c: CopyCfg, st: CopyState, api: Binance, pub: Binance, now: Long,
                          livePos: Map<String, JSONObject>, ev: MutableList<Event>) {
        if (st.copies.isEmpty()) return
        val marks = if (mode == "paper") runCatching { pub.markPrices() }.getOrDefault(emptyMap()) else emptyMap()
        for ((ck, cp) in st.copies.toMap()) {
            if (mode != "paper") {
                if (cp.symbol !in livePos) {         // emergency stop fired on Binance, or closed by hand
                    api.cancelAll(cp.symbol)
                    val pnl = api.incomeSince(cp.symbol, cp.opened - 1000)
                    st.dayPnl += pnl - cp.realized
                    cp.realized = pnl
                    finish(mode, st, ck, cp, pub.markPrice(cp.symbol), "closed on Binance (emergency stop / manual)", now, ev)
                }
                continue
            }
            val px = marks[cp.symbol] ?: continue
            val move = (px / cp.entry - 1) * 100 * (if (cp.side == "LONG") 1 else -1)
            if (move <= -c.stopPct) reduce(mode, c, st, api, pub, ck, cp, cp.qty, "emergency stop ${String.format(Locale.US, "%.1f", move)}%", now, ev)
        }
    }

    private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)

    /** UI: open copies with live P&L. */
    fun openWithMarks(mode: String, pub: Binance): List<Pair<CopyPos, Double?>> {
        val st = state(mode)
        if (st.copies.isEmpty()) return emptyList()
        val m = runCatching { pub.markPrices() }.getOrDefault(emptyMap())
        return st.copies.values.map { cp -> cp to m[cp.symbol]?.let { (it - cp.entry) * cp.qty * (if (cp.side == "LONG") 1 else -1) } }
    }
}
