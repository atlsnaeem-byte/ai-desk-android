package pk.aidesk.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs

/**
 * Short Hunter: automatic short rules from the laptop's short lab (shortlab/lab.py, 2021-2026 backtest):
 *  - new listings: short on day 3 after listing, SL +50%, TP -40%, close after 21 days (best 2021-25 setting, +4.0%/trade in 2026)
 *  - pumps: +50% in a day or +100% in 3 days, ONLY when the last 9 funding rates average >= +0.03% (longs pay shorts)
 *  - scale-in (pump shorts): half now, the other half if price rises +30%, one stop +60% above the first entry
 *  - announcements: short after a Binance monitoring-tag / delisting announcement, SL +30%, TP -30%, 7 days
 *  - BTC hedge: each short gets a BTC long of the same size, closed together with the short
 */
data class ShortCfg(
    val enabled: Boolean = true,
    val entryDay: Int = 3,
    val holdDays: Int = 21,
    val slPct: Double = 50.0,
    val tpPct: Double = 40.0,
    val marginPct: Double = 10.0,
    val leverage: Int = 1,
    val maxPositions: Int = 10,
    val entryWindowH: Int = 6,
    val paperStart: Double = 100.0,
    val pumpAuto: Boolean = true,
    val pumpSlPct: Double = 50.0,
    val pumpTpPct: Double = 35.0,
    val pumpHoldDays: Int = 14,
    val pumpCooldownDays: Int = 5,
    val pumpMinFunding: Double = 0.03,     // % per funding period (average of the last 9); <= -1 = filter off
    val scaleIn: Boolean = true,
    val scaleAddPct: Double = 30.0,
    val scaleStopPct: Double = 60.0,
    val annAuto: Boolean = true,
    val annSlPct: Double = 30.0,
    val annTpPct: Double = 30.0,
    val annHoldDays: Int = 7,
    val btcHedge: Boolean = true,
) {
    val fundingFilterOn: Boolean get() = pumpMinFunding > -1

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", VERSION)
        put("enabled", enabled); put("entryDay", entryDay); put("holdDays", holdDays); put("slPct", slPct); put("tpPct", tpPct)
        put("marginPct", marginPct); put("leverage", leverage); put("maxPositions", maxPositions); put("entryWindowH", entryWindowH)
        put("paperStart", paperStart); put("pumpAuto", pumpAuto); put("pumpSlPct", pumpSlPct); put("pumpTpPct", pumpTpPct)
        put("pumpHoldDays", pumpHoldDays); put("pumpCooldownDays", pumpCooldownDays); put("pumpMinFunding", pumpMinFunding)
        put("scaleIn", scaleIn); put("scaleAddPct", scaleAddPct); put("scaleStopPct", scaleStopPct)
        put("annAuto", annAuto); put("annSlPct", annSlPct); put("annTpPct", annTpPct); put("annHoldDays", annHoldDays); put("btcHedge", btcHedge)
    }

    companion object {
        const val VERSION = 7
        fun from(o: JSONObject?): ShortCfg {
            val d = ShortCfg(); o ?: return d
            // saved before v7: keep money settings, take the new backtested rules and turn every rule on (one time)
            if (o.optInt("v", 1) < VERSION) return d.copy(marginPct = o.optDouble("marginPct", d.marginPct),
                leverage = o.optInt("leverage", d.leverage), paperStart = o.optDouble("paperStart", d.paperStart),
                maxPositions = maxOf(d.maxPositions, o.optInt("maxPositions", d.maxPositions)))
            return ShortCfg(o.optBoolean("enabled", d.enabled), o.optInt("entryDay", d.entryDay), o.optInt("holdDays", d.holdDays),
                o.optDouble("slPct", d.slPct), o.optDouble("tpPct", d.tpPct), o.optDouble("marginPct", d.marginPct),
                o.optInt("leverage", d.leverage), o.optInt("maxPositions", d.maxPositions), o.optInt("entryWindowH", d.entryWindowH),
                o.optDouble("paperStart", d.paperStart), o.optBoolean("pumpAuto", d.pumpAuto), o.optDouble("pumpSlPct", d.pumpSlPct),
                o.optDouble("pumpTpPct", d.pumpTpPct), o.optInt("pumpHoldDays", d.pumpHoldDays), o.optInt("pumpCooldownDays", d.pumpCooldownDays),
                o.optDouble("pumpMinFunding", d.pumpMinFunding), o.optBoolean("scaleIn", d.scaleIn), o.optDouble("scaleAddPct", d.scaleAddPct),
                o.optDouble("scaleStopPct", d.scaleStopPct), o.optBoolean("annAuto", d.annAuto), o.optDouble("annSlPct", d.annSlPct),
                o.optDouble("annTpPct", d.annTpPct), o.optInt("annHoldDays", d.annHoldDays), o.optBoolean("btcHedge", d.btcHedge))
        }
    }
}

/** One short (plus its optional pending scale-in half and BTC hedge). Prices/qty change when the second half is added. */
class ShortPos(
    val symbol: String, var entry: Double, var qty: Double, val opened: Long, val exitAt: Long, val margin: Double,
    var sl: Double, var tp: Double, var entryFee: Double, var lastCheck: Long, val rule: String = "listing",
    var addPrice: Double? = null, var addQty: Double = 0.0, val tpPct: Double = 0.0,
    var hedgeQty: Double = 0.0, var hedgeEntry: Double = 0.0, var hedgeFee: Double = 0.0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("symbol", symbol); put("entry", entry); put("qty", qty); put("opened", opened); put("exitAt", exitAt); put("margin", margin)
        put("sl", sl); put("tp", tp); put("entryFee", entryFee); put("lastCheck", lastCheck); put("rule", rule)
        addPrice?.let { put("addPrice", it) }; put("addQty", addQty); put("tpPct", tpPct)
        put("hedgeQty", hedgeQty); put("hedgeEntry", hedgeEntry); put("hedgeFee", hedgeFee)
    }

    companion object {
        fun from(o: JSONObject) = ShortPos(o.getString("symbol"), o.getDouble("entry"), o.getDouble("qty"), o.getLong("opened"),
            o.getLong("exitAt"), o.getDouble("margin"), o.getDouble("sl"), o.getDouble("tp"), o.optDouble("entryFee", 0.0),
            o.optLong("lastCheck", o.getLong("opened")), o.optString("rule", "listing"),
            if (o.has("addPrice")) o.getDouble("addPrice") else null, o.optDouble("addQty", 0.0), o.optDouble("tpPct", 0.0),
            o.optDouble("hedgeQty", 0.0), o.optDouble("hedgeEntry", 0.0), o.optDouble("hedgeFee", 0.0))
    }
}

class ShortState(var paperEquity: Double, val open: MutableMap<String, ShortPos> = linkedMapOf(), val done: MutableList<String> = mutableListOf(),
                 val pumpCool: MutableMap<String, Long> = linkedMapOf()) {
    /** Total BTC hedge (qty, average entry) over all open shorts. */
    fun hedge(): Pair<Double, Double> {
        val q = open.values.sumOf { it.hedgeQty }
        return q to if (q > 0) open.values.sumOf { it.hedgeQty * it.hedgeEntry } / q else 0.0
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("paperEquity", paperEquity)
        put("pumpCool", JSONObject().also { o -> pumpCool.forEach { (k, v) -> o.put(k, v) } })
        put("open", JSONObject().also { o -> open.forEach { (k, v) -> o.put(k, v.toJson()) } })
        put("done", JSONArray(done.takeLast(3000)))
        put("hedgeQty", hedge().first)
    }

    companion object {
        fun from(o: JSONObject?, start: Double): ShortState {
            o ?: return ShortState(start)
            val op = o.optJSONObject("open")
            return ShortState(o.optDouble("paperEquity", start),
                op?.keys()?.asSequence()?.associateWith { ShortPos.from(op.getJSONObject(it)) }?.toMutableMap() ?: linkedMapOf(),
                o.optJSONArray("done")?.let { a -> (0 until a.length()).map { a.getString(it) }.toMutableList() } ?: mutableListOf(),
                o.optJSONObject("pumpCool")?.let { pc -> pc.keys().asSequence().associateWith { pc.getLong(it) }.toMutableMap() } ?: linkedMapOf())
        }
    }
}

class ShortDone(val symbol: String, val entry: Double, val exit: Double, val reason: String, val opened: Long, val closed: Long,
                val pnl: Double, val retPct: Double?, val rule: String = "") {
    fun toJson(): JSONObject = JSONObject().apply {
        put("symbol", symbol); put("entry", entry); put("exit", exit); put("reason", reason); put("opened", opened); put("closed", closed)
        put("pnl", pnl); retPct?.let { put("retPct", it) }; put("rule", rule)
    }

    companion object {
        fun from(o: JSONObject) = ShortDone(o.getString("symbol"), o.getDouble("entry"), o.getDouble("exit"), o.getString("reason"),
            o.getLong("opened"), o.getLong("closed"), o.getDouble("pnl"), if (o.has("retPct")) o.getDouble("retPct") else null, o.optString("rule"))
    }
}

class Listing(val symbol: String, val listed: Long, val day: Int, val entryTime: Long, val status: String, val note: String)

class Pump(val symbol: String, val ch24: Double, val d1: Double?, val d3: Double?, val volume: Double, val price: Double,
           val fundingPct: Double, val ageDays: Int, val status: String, val fundingAvgPct: Double? = null)

/** A Binance announcement that names a coin with a USDT perpetual. */
class Announcement(val kind: String, val symbol: String, val time: Long, val title: String)

class ShortHunter(private val store: Store) {
    companion object {
        const val DAY = 86_400_000L
        const val HEDGE = "BTCUSDT"
        private const val FEE = 0.0005
        private const val SLIP = 0.05
        private val ANN_LOCK = Any()
        @Volatile private var annCache: Pair<Long, List<Announcement>>? = null
    }

    fun cfg(): ShortCfg {
        val o = store.readObj("shorts_cfg.json")
        val c = ShortCfg.from(o)
        if (o != null && o.optInt("v", 1) < ShortCfg.VERSION) saveCfg(c)        // persist the one-time upgrade
        return c
    }
    fun saveCfg(c: ShortCfg) = store.writeObj("shorts_cfg.json", c.toJson())
    fun state(mode: String): ShortState = ShortState.from(store.readObj("shorts_state_$mode.json"), cfg().paperStart)
    private fun saveState(mode: String, s: ShortState) = store.writeObj("shorts_state_$mode.json", s.toJson())
    fun journal(mode: String): List<ShortDone> = store.readArr("shorts_journal_$mode.json")?.let { a ->
        (0 until a.length()).mapNotNull { runCatching { ShortDone.from(a.getJSONObject(it)) }.getOrNull() }
    } ?: emptyList()
    private fun addJournal(mode: String, d: ShortDone) =
        store.writeArr("shorts_journal_$mode.json", JSONArray((journal(mode) + d).takeLast(2000).map { it.toJson() }))

    private fun coins(api: Binance): Map<String, JSONObject> = api.exchangeInfo(fresh = true).filterValues {
        it.optString("quoteAsset") == "USDT" && it.optString("contractType") == "PERPETUAL" &&
            it.optString("status") == "TRADING" && it.optString("underlyingType", "COIN") == "COIN"
    }

    // ------------------------------------------------------------------ shortlists (UI + bot)
    fun listings(api: Binance, now: Long = api.nowMs()): List<Listing> {
        val c = cfg()
        val out = mutableListOf<Listing>()
        for ((s, x) in coins(api)) {
            val day0 = x.getLong("onboardDate") / DAY * DAY
            val d = ((now - day0) / DAY).toInt()
            if (d < 0 || d > c.entryDay + c.holdDays) continue
            val entry = day0 + c.entryDay * DAY
            val (st, note) = when {
                d < c.entryDay -> "WAIT" to "short in ${c.entryDay - d} day${if (c.entryDay - d > 1) "s" else ""}"
                d == c.entryDay && now < entry + c.entryWindowH * 3_600_000L -> "SHORT NOW" to "entry window open"
                else -> "MISSED" to "entry window passed"
            }
            out += Listing(s, x.getLong("onboardDate"), d, entry, st, note)
        }
        return out.sortedByDescending { it.listed }
    }

    /** Top 24h gainers with closed-daily-candle 1d / 3d change and the funding filter. */
    fun pumps(api: Binance, top: Int = 20, now: Long = api.nowMs()): List<Pump> {
        val c = cfg()
        val cs = coins(api)
        val t = api.ticker24h()
        val prem = api.premiumAll().let { a -> (0 until a.length()).associate { a.getJSONObject(it).let { o -> o.getString("symbol") to o } } }
        val rows = (0 until t.length()).map { t.getJSONObject(it) }
            .filter { it.getString("symbol") in cs && it.getString("symbol") !in setOf("BTCUSDT", "ETHUSDT") }
            .sortedByDescending { it.getString("priceChangePercent").toDouble() }.take(top)
        return rows.mapNotNull { r ->
            val s = r.getString("symbol")
            runCatching {
                val k = api.klines(s, "1d", limit = 6)
                val closed = (0 until k.length()).map { k.getJSONArray(it) }.filter { it.getLong(6) < now }.map { it.getString(4).toDouble() }
                val d1 = if (closed.size >= 2) (closed.last() / closed[closed.size - 2] - 1) * 100 else null
                val d3 = if (closed.size >= 4) (closed.last() / closed[closed.size - 4] - 1) * 100 else null
                val ch = r.getString("priceChangePercent").toDouble()
                val age = ((now - cs.getValue(s).getLong("onboardDate")) / DAY).toInt()
                val fr = prem[s]?.optString("lastFundingRate")?.toDoubleOrNull() ?: 0.0
                val sig = ((d1 != null && d1 >= 50) || (d3 != null && d3 >= 100)) && age >= 30
                val favg = if (sig) api.fundingAvg(s)?.let { it * 100 } else null
                val status = when {
                    !sig -> if (ch >= 20) "WATCH" else "-"
                    c.fundingFilterOn && (favg == null || favg < c.pumpMinFunding) -> "LOW FUNDING"
                    else -> "SIGNAL"
                }
                Pump(s, ch, d1, d3, r.getString("quoteVolume").toDouble(), r.getString("lastPrice").toDouble(), fr * 100, age, status, favg)
            }.getOrNull()
        }
    }

    /** Recent Binance delisting / monitoring-tag announcements mapped to USDT perps (cached 10 min). */
    fun announcements(api: Binance, now: Long = api.nowMs()): List<Announcement> {
        synchronized(ANN_LOCK) {
            annCache?.let { if (now - it.first < 600_000) return it.second }
            val cs = coins(api).keys
            val out = ArrayList<Announcement>()
            val stop = setOf("USDT", "USD", "BTC", "ETH", "BNB", "FDUSD", "TRY", "EUR", "AND", "ON", "THE", "M", "TO", "TAG", "OF", "ALPHA")
            for (cat in listOf(161, 49)) {
                val j = http("https://www.binance.com/bapi/composite/v1/public/cms/article/list/query?type=1&catalogId=$cat&pageNo=1&pageSize=30") ?: continue
                val arts = j.optJSONObject("data")?.optJSONArray("catalogs")?.optJSONObject(0)?.optJSONArray("articles") ?: continue
                for (i in 0 until arts.length()) {
                    val a = arts.getJSONObject(i)
                    val title = a.optString("title"); val t = a.optLong("releaseDate")
                    var kind: String? = null; var coins = emptyList<String>()
                    when {
                        title.contains("Delist") && Regex("Futures Will Delist|Perpetual Contract").containsMatchIn(title) -> {
                            kind = "futures delist"; coins = Regex("\\b([A-Z0-9]{2,15})USDT\\b").findAll(title).map { it.groupValues[1] }.toList()
                        }
                        title.contains("Monitoring Tag") -> {
                            kind = "monitoring tag"
                            Regex("(?:Include|Includes|to)\\s+(.+)$").find(title)?.let { m ->
                                coins = Regex("\\b([A-Z0-9]{2,12})\\b").findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
                            }
                        }
                        title.contains("Will Delist") -> {
                            kind = "spot delist"
                            Regex("Delist\\s+(.+?)(?:\\s+on\\s+\\d|\\s*$)").find(title)?.let { m ->
                                coins = Regex("\\b([A-Z0-9]{2,12})\\b").findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
                            }
                        }
                    }
                    kind ?: continue
                    for (cn in coins) {
                        if (cn in stop) continue
                        val sym = listOf("${cn}USDT", "1000${cn}USDT").firstOrNull { it in cs } ?: continue
                        out += Announcement(kind, sym, t, title)
                    }
                }
            }
            val l = out.distinctBy { it.kind + it.symbol }.sortedByDescending { it.time }
            annCache = now to l
            return l
        }
    }

    private fun http(url: String): JSONObject? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        try { if (c.responseCode != 200) null else JSONObject(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
    } catch (e: Exception) { null }

    // ------------------------------------------------------------------ bot
    /** One pass: manage exits (and scale-in adds), then open new shorts for every rule that is on. */
    fun step(mode: String, api: Binance, equityOf: () -> Pair<Double, Double>): List<Event> {
        val c = cfg()
        val live = mode != "paper"
        val st = state(mode)
        val ev = mutableListOf<Event>()
        val now = api.nowMs()
        val pos = if (live && st.open.isNotEmpty()) api.positions() else emptyMap()
        for ((s, p) in st.open.toMap()) {
            try {
                if (live) checkLive(mode, api, st, s, p, now, pos, c, ev) else checkPaper(mode, api, st, s, p, now, c, ev)
            } catch (e: Exception) {
                store.log("WARN", "Short hunter $s exit check: ${(e as? BinanceException)?.msg ?: e.message ?: e.javaClass.simpleName}")
            }
        }
        val held by lazy { if (live) api.positions().keys else emptySet() }
        fun tryOpen(s: String, exitAt: Long, sl: Double, tp: Double, rule: String, scale: Boolean) {
            try {
                openShort(mode, api, st, s, now, exitAt, c, equityOf, sl, tp, rule, scale)?.let { ev += it }
            } catch (e: Exception) {
                store.log("ERROR", "Short hunter $s entry: ${(e as? BinanceException)?.msg ?: e.message}")
                ev += Event("error", "Short hunter: $s entry failed", (e as? BinanceException)?.msg ?: e.message ?: "", false, s)
            }
        }
        // 1) new listings
        if (c.enabled) {
            for ((s, x) in coins(api)) {
                val day0 = x.getLong("onboardDate") / DAY * DAY
                val start = day0 + c.entryDay * DAY
                if (now < start || now >= start + c.entryWindowH * 3_600_000L) continue
                if (s in st.open || s in st.done) continue
                st.done += s
                if (s in held) { store.log("INFO", "Short hunter $s: skip, the account already holds $s"); continue }
                tryOpen(s, day0 + (c.entryDay + c.holdDays + 1) * DAY, c.slPct, c.tpPct, "listing day ${c.entryDay}", false)
            }
        }
        // 2) pumps (funding filter inside pumps())
        if (c.pumpAuto) {
            try {
                for (pm in pumps(api, 25, now)) {
                    val s = pm.symbol
                    if (pm.status != "SIGNAL" || s in st.open || s in held) continue
                    if (now - (st.pumpCool[s] ?: 0L) < c.pumpCooldownDays * DAY) continue
                    if (s in store.botSymbols(mode)) continue
                    st.pumpCool[s] = now
                    tryOpen(s, now + c.pumpHoldDays * DAY, c.pumpSlPct, c.pumpTpPct,
                        "pump ${pm.d1?.let { String.format(Locale.US, "%+.0f%% 1d", it) } ?: ""}".trim() +
                            (pm.fundingAvgPct?.let { String.format(Locale.US, ", funding %+.3f%%", it) } ?: ""), c.scaleIn)
                }
            } catch (e: Exception) {
                store.log("WARN", "Short hunter pump scan: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        // 3) delisting / monitoring-tag announcements of the last 24 h
        if (c.annAuto) {
            try {
                for (a in announcements(api, now)) {
                    if (now - a.time > DAY || now < a.time) continue
                    val key = "ann:${a.kind}:${a.symbol}"
                    if (key in st.done || a.symbol in st.open || a.symbol in held || a.symbol in store.botSymbols(mode)) continue
                    st.done += key
                    tryOpen(a.symbol, now + c.annHoldDays * DAY, c.annSlPct, c.annTpPct, a.kind, false)
                }
            } catch (e: Exception) {
                store.log("WARN", "Short hunter announcements: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        saveState(mode, st)
        return ev
    }

    private fun openShort(mode: String, api: Binance, st: ShortState, s: String, now: Long, exitAt: Long, c: ShortCfg,
                          equityOf: () -> Pair<Double, Double>, slPct: Double, tpPct: Double, rule: String, scale: Boolean): Event? {
        if (st.open.size >= c.maxPositions) { store.log("INFO", "Short hunter $s: skip, ${c.maxPositions} shorts already open"); return null }
        val live = mode != "paper"
        val usedMargin = st.open.values.sumOf { it.margin } + st.hedge().let { it.first * it.second } / c.leverage
        val (eq, avail) = if (live) equityOf() else st.paperEquity to st.paperEquity - usedMargin
        val margin = eq * c.marginPct / 100
        val need = margin * (if (c.btcHedge) 2 else 1)
        if (need > avail) { store.log("INFO", "Short hunter $s: skip, free margin ${f2(avail)} < ${f2(need)}"); return null }
        val price = api.markPrice(s)
        val f = api.filters(s)
        val fullQty = margin * c.leverage / price
        val (qty, q) = api.fmtQty(s, if (scale) fullQty / 2 else fullQty)
        if (qty < f.minQty || qty * price < f.minNotional) {
            val msg = "position ${f2(qty * price)} USDT is below Binance's minimum ${f2(f.minNotional)} USDT (equity ${f2(eq)} x ${c.marginPct}% x ${c.leverage}x" +
                (if (scale) ", half size for scale-in" else "") + ")"
            store.log("INFO", "Short hunter $s: skip, $msg")
            return Event("skip", "Short hunter skipped $s", msg, false, s)
        }
        val stopPct = if (scale) c.scaleStopPct else slPct
        val entry: Double; val fee: Double
        if (!live) {
            entry = price * (1 - SLIP / 100); fee = qty * entry * FEE
            st.paperEquity -= fee
        } else {
            api.setMarginType(s, "ISOLATED"); api.setLeverage(s, c.leverage)
            val r = api.marketOrder(s, "SELL", q)
            entry = r.optString("avgPrice").toDoubleOrNull()?.takeIf { it > 0 } ?: price
            fee = qty * entry * FEE
            if (!placeExits(api, s, entry * (1 + stopPct / 100), entry * (1 - tpPct / 100))) {
                api.marketOrder(s, "BUY", q, reduceOnly = true)
                return Event("error", "Short hunter: $s closed", "Binance rejected the stop order, so the short was closed.", true, s)
            }
        }
        val p = ShortPos(s, entry, qty, now, exitAt, margin, entry * (1 + stopPct / 100), entry * (1 - tpPct / 100), fee, now, rule,
            if (scale) entry * (1 + c.scaleAddPct / 100) else null, if (scale) api.fmtQty(s, fullQty / 2).first else 0.0, tpPct)
        st.open[s] = p
        store.log("INFO", "Short hunter OPEN SHORT [$rule] $s qty $q @ ${Engine.fmt(entry)} (${mode.uppercase()})")
        val hedgeMsg = if (c.btcHedge) addHedge(mode, api, st, p, qty * entry, c) else ""
        val days = ((exitAt - now) / DAY).toInt()
        return Event("botopen", "Short hunter: SHORT $s ($rule)", "${Engine.fmt(qty)} @ ${Engine.fmt(entry)}  -  SL +${stopPct.toInt()}%  TP −${tpPct.toInt()}%" +
            (if (scale) "  -  adds 2nd half at +${c.scaleAddPct.toInt()}%" else "") + "  -  up to $days days$hedgeMsg", true, s)
    }

    /** SL + TP trigger orders closing the whole short; false if the stop was rejected (caller must close). */
    private fun placeExits(api: Binance, s: String, sl: Double, tp: Double): Boolean {
        try {
            api.closeTrigger(s, "BUY", "STOP_MARKET", api.fmtPrice(s, sl).second)
        } catch (e: BinanceException) {
            store.log("ERROR", "Short hunter $s: stop rejected (${e.msg}), closing for safety"); api.cancelAll(s); return false
        }
        try {
            api.closeTrigger(s, "BUY", "TAKE_PROFIT_MARKET", api.fmtPrice(s, tp).second)
        } catch (e: BinanceException) {
            store.log("WARN", "Short hunter $s: take-profit rejected (${e.msg}); the bot will watch the target itself")
        }
        return true
    }

    /** BTC long of [notional] USDT for this short. Returns a text for the notification. */
    private fun addHedge(mode: String, api: Binance, st: ShortState, p: ShortPos, notional: Double, c: ShortCfg): String {
        return try {
            val px = api.markPrice(HEDGE)
            val f = api.filters(HEDGE)
            val (bq, bqs) = api.fmtQty(HEDGE, notional / px)
            if (bq < f.minQty || bq * px < f.minNotional) {
                store.log("INFO", "BTC hedge for ${p.symbol}: skip, ${f2(bq * px)} USDT is below Binance's BTC minimum ${f2(f.minNotional)} USDT")
                return "  -  no BTC hedge (below BTC minimum ${f2(f.minNotional)} USDT)"
            }
            val fill = if (mode == "paper") px * (1 + SLIP / 100) else {
                if (p.hedgeQty == 0.0 && st.open.values.none { it.hedgeQty > 0 && it.symbol != p.symbol }) {
                    runCatching { api.setLeverage(HEDGE, c.leverage) }
                }
                api.marketOrder(HEDGE, "BUY", bqs).optString("avgPrice").toDoubleOrNull()?.takeIf { it > 0 } ?: px
            }
            val fee = bq * fill * FEE
            if (mode == "paper") st.paperEquity -= fee
            p.hedgeEntry = if (p.hedgeQty > 0) (p.hedgeEntry * p.hedgeQty + fill * bq) / (p.hedgeQty + bq) else fill
            p.hedgeQty += bq; p.hedgeFee += fee
            store.log("INFO", "BTC hedge LONG ${Engine.fmt(bq)} BTC @ ${Engine.fmt(fill)} for ${p.symbol} (${mode.uppercase()})")
            "  -  BTC hedge long ${Engine.fmt(bq)} BTC"
        } catch (e: Exception) {
            store.log("ERROR", "BTC hedge for ${p.symbol} failed: ${(e as? BinanceException)?.msg ?: e.message}")
            "  -  BTC hedge failed"
        }
    }

    /** Close this short's BTC hedge; returns its P&L (journalled separately). */
    private fun closeHedge(mode: String, api: Binance, st: ShortState, p: ShortPos, now: Long, ev: MutableList<Event>) {
        if (p.hedgeQty <= 0) return
        try {
            val px = api.markPrice(HEDGE)
            val exit = if (mode == "paper") px * (1 - SLIP / 100) else {
                val q = api.fmtQty(HEDGE, p.hedgeQty).second
                api.marketOrder(HEDGE, "SELL", q, reduceOnly = true).optString("avgPrice").toDoubleOrNull()?.takeIf { it > 0 } ?: px
            }
            val fee = p.hedgeQty * exit * FEE
            val fund = api.fundingSum(HEDGE, p.opened, now) * p.hedgeEntry * p.hedgeQty     // a long pays positive funding
            val pnl = p.hedgeQty * (exit - p.hedgeEntry) - fee - p.hedgeFee - fund
            if (mode == "paper") st.paperEquity += p.hedgeQty * (exit - p.hedgeEntry) - fee - fund
            addJournal(mode, ShortDone(HEDGE, p.hedgeEntry, exit, "BTC hedge for ${p.symbol}", p.opened, now, pnl,
                if (p.margin > 0) pnl / p.margin * 100 else null, "btc hedge"))
            store.log("INFO", "BTC hedge CLOSE for ${p.symbol} @ ${Engine.fmt(exit)} pnl ${String.format(Locale.US, "%+.4f", pnl)}")
            ev += Event("botclose", "BTC hedge closed (${p.symbol})", "${String.format(Locale.US, "%+.4f", pnl)} USDT", false, HEDGE)
        } catch (e: Exception) {
            store.log("ERROR", "BTC hedge close for ${p.symbol} failed: ${(e as? BinanceException)?.msg ?: e.message}. Close ${Engine.fmt(p.hedgeQty)} BTC by hand.")
            ev += Event("error", "BTC hedge close failed", "Close ${Engine.fmt(p.hedgeQty)} BTC long by hand", true, HEDGE)
        }
        p.hedgeQty = 0.0
    }

    // ------------------------------------------------------------------ manual (UI buttons)
    /** Short a coin now with the rule that fits it (new listing -> listing SL/TP, otherwise pump SL/TP + scale-in). */
    fun openManual(mode: String, api: Binance, s: String, equityOf: () -> Pair<Double, Double>): String {
        val c = cfg()
        val st = state(mode)
        if (s in st.open) throw UserError("$s is already shorted.")
        if (mode != "paper" && s in api.positions()) throw UserError("The account already holds a $s position.")
        val now = api.nowMs()
        val info = coins(api)[s] ?: throw UserError("$s is not a tradable USDT perpetual.")
        val day0 = info.getLong("onboardDate") / DAY * DAY
        val isListing = (now - day0) / DAY <= c.entryDay + c.holdDays
        val ev = if (isListing)
            openShort(mode, api, st, s, now, maxOf(now + DAY, day0 + (c.entryDay + c.holdDays + 1) * DAY), c, equityOf, c.slPct, c.tpPct, "manual listing", false)
        else
            openShort(mode, api, st, s, now, now + c.pumpHoldDays * DAY, c, equityOf, c.pumpSlPct, c.pumpTpPct, "manual pump", c.scaleIn)
        if (!isListing) st.pumpCool[s] = now
        st.done += s
        saveState(mode, st)
        return when (ev?.kind) {
            "botopen" -> ev.title + "\n" + ev.text
            null -> throw UserError("Not opened: max ${c.maxPositions} shorts or not enough free margin (see Logs).")
            else -> throw UserError(ev.text)
        }
    }

    /** Close an open short (and its BTC hedge) now at market. */
    fun closeManual(mode: String, api: Binance, s: String): String {
        val st = state(mode)
        val p = st.open[s] ?: throw UserError("No open short on $s.")
        val ev = ArrayList<Event>()
        val now = api.nowMs()
        if (mode == "paper") {
            val px = api.markPrice(s) * (1 + SLIP / 100)
            paperClose(mode, api, st, p, px, "closed by you", now, ev)
        } else {
            api.cancelAll(s)
            val pos = api.positions()[s]
            if (pos != null) {
                val amt = abs(pos.getString("positionAmt").toDouble())
                api.marketOrder(s, "BUY", api.fmtQty(s, amt).second, reduceOnly = true)
                Thread.sleep(1500)
            }
            record(mode, st, p, api.markPrice(s), "closed by you", api.incomeSince(s, p.opened - 1000), now, ev)
            closeHedge(mode, api, st, p, now, ev)
        }
        saveState(mode, st)
        return ev.joinToString("\n") { it.title + "  " + it.text }.ifEmpty { "Closed $s" }
    }

    private fun record(mode: String, st: ShortState, p: ShortPos, exit: Double, reason: String, pnl: Double, now: Long, ev: MutableList<Event>) {
        addJournal(mode, ShortDone(p.symbol, p.entry, exit, reason, p.opened, now, pnl, if (p.margin > 0) pnl / p.margin * 100 else null, p.rule))
        st.open.remove(p.symbol)
        store.log("INFO", "Short hunter CLOSE ${p.symbol} $reason @ ${Engine.fmt(exit)} pnl ${String.format(Locale.US, "%+.4f", pnl)}")
        ev += Event("botclose", "Short hunter closed ${p.symbol}  -  $reason", "${String.format(Locale.US, "%+.4f", pnl)} USDT", false, p.symbol)
    }

    private fun paperClose(mode: String, api: Binance, st: ShortState, p: ShortPos, px: Double, why: String, now: Long, ev: MutableList<Event>) {
        val gross = (p.entry - px) * p.qty
        val fee = px * p.qty * FEE
        val fund = api.fundingSum(p.symbol, p.opened, now) * p.entry * p.qty
        st.paperEquity += gross - fee + fund
        record(mode, st, p, px, why, gross - fee - p.entryFee + fund, now, ev)
        closeHedge(mode, api, st, p, now, ev)
    }

    /** Add the second half of a scale-in short at [px]; TP moves to the new average entry. */
    private fun addHalf(mode: String, api: Binance, st: ShortState, p: ShortPos, px: Double, c: ShortCfg) {
        val q = p.addQty
        val fill = if (mode == "paper") px * (1 - SLIP / 100) else {
            api.marketOrder(p.symbol, "SELL", api.fmtQty(p.symbol, q).second).optString("avgPrice").toDoubleOrNull()?.takeIf { it > 0 } ?: px
        }
        val fee = q * fill * FEE
        if (mode == "paper") st.paperEquity -= fee
        p.entry = (p.entry * p.qty + fill * q) / (p.qty + q)
        p.qty += q; p.entryFee += fee; p.addPrice = null; p.addQty = 0.0
        p.tp = p.entry * (1 - p.tpPct / 100)
        if (mode != "paper") { api.cancelAll(p.symbol); placeExits(api, p.symbol, p.sl, p.tp) }
        store.log("INFO", "Short hunter ${p.symbol}: added 2nd half ${Engine.fmt(q)} @ ${Engine.fmt(fill)}, avg ${Engine.fmt(p.entry)}, TP ${Engine.fmt(p.tp)}")
        if (c.btcHedge) addHedge(mode, api, st, p, q * fill, c)
    }

    private fun checkPaper(mode: String, api: Binance, st: ShortState, s: String, p: ShortPos, now: Long, c: ShortCfg, ev: MutableList<Event>) {
        val k = api.klines(s, "15m", start = p.lastCheck, limit = 1500)
        var hit: String? = null; var px = 0.0
        for (i in 0 until k.length()) {
            val b = k.getJSONArray(i)
            if (b.getLong(0) > minOf(now, p.exitAt)) break
            val hi = b.getString(2).toDouble(); val lo = b.getString(3).toDouble()
            p.addPrice?.let { ap -> if (hi >= ap) addHalf(mode, api, st, p, ap, c) }
            if (hi >= p.sl) { hit = "stop-loss"; px = p.sl * (1 + SLIP / 100); break }
            if (lo <= p.tp) { hit = "take-profit"; px = p.tp; break }
        }
        if (k.length() > 0) p.lastCheck = k.getJSONArray(k.length() - 1).getLong(0)
        if (hit == null && now >= p.exitAt) { hit = "time exit"; px = api.markPrice(s) * (1 + SLIP / 100) }
        hit ?: return
        paperClose(mode, api, st, p, px, hit, now, ev)
    }

    private fun checkLive(mode: String, api: Binance, st: ShortState, s: String, p: ShortPos, now: Long,
                          pos: Map<String, JSONObject>, c: ShortCfg, ev: MutableList<Event>) {
        if (s !in pos) {      // SL or TP order on Binance closed it (or closed by hand)
            api.cancelAll(s)
            val pnl = api.incomeSince(s, p.opened - 1000)
            val px = api.markPrice(s)
            record(mode, st, p, px, if (px > p.entry) "stop-loss" else "take-profit", pnl, now, ev)
            closeHedge(mode, api, st, p, now, ev)
            return
        }
        val mark = api.markPrice(s)
        p.addPrice?.let { ap -> if (mark >= ap && mark < p.sl) addHalf(mode, api, st, p, mark, c) }
        val tpHit = mark <= p.tp                    // backup when the exchange take-profit was rejected
        if (now >= p.exitAt || tpHit) {
            api.cancelAll(s)
            val amt = abs(pos.getValue(s).getString("positionAmt").toDouble())
            api.marketOrder(s, "BUY", api.fmtQty(s, amt).second, reduceOnly = true)
            Thread.sleep(2000)
            record(mode, st, p, api.markPrice(s), if (tpHit) "take-profit" else "time exit", api.incomeSince(s, p.opened - 1000), now, ev)
            closeHedge(mode, api, st, p, now, ev)
        }
    }

    private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
}
