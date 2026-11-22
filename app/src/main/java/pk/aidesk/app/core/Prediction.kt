package pk.aidesk.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Binance Prediction Trading (BTC "Up or Down 5m") client, ported from the user's PredictAI V7.3.0 BinanceClient:
 * same /sapi/v1/w3w/wallet/prediction/... endpoints, canonical form-body signing, and order safety:
 *  - place-order has no client order id, so it is NEVER retried; an ambiguous outcome (timeout / IO / 5xx) is reconciled
 *    against the wallet's orders, and if still unknown, new orders on that side are blocked for 90 s (process-wide)
 *  - quotes and GETs are retried; -1021 (timestamp) re-syncs and retries once (the request was not executed)
 * Amounts are 18-decimal "wei" strings. Fee rate 200 bps (2 %) per trade.
 */
class PredictionApi(private val key: String, private val secret: String) {
    companion object {
        const val BASE = "https://api.binance.com"
        const val FEE_BPS = 200
        private val placeLock = Any()
        private val unknownUntil = ConcurrentHashMap<String, Long>()
        private val topicCache = ConcurrentHashMap<Long, Long>()
        @Volatile private var offsetMs = 0L
        @Volatile private var syncedAt = 0L
    }

    class Wallet(val address: String, val id: String, val registered: Long)
    class Balance(val accountType: String, val balance: Double, val enabled: Boolean)
    class Token(val tokenId: String, val label: String, val price: Double?)
    class Market(val topicId: Long, val title: String, val start: Long, val end: Long, val slippageBps: Int, val up: Token, val down: Token)
    class Position(val tokenId: String, val outcome: String, val status: String, val canClaim: Boolean, val totalCost: Double,
                   val pnl: Double, val endDate: Long)
    class Order(val orderId: String, val side: String, val status: String, val createTime: Long, val outcome: String)
    class Quote(val raw: JSONObject, val amountIn: Double, val amountOut: Double, val fee: Double, val expireAt: Long) {
        /** conservative: fee counted once as extra cost (PredictAI EdgeGate.quoteBuyCost) */
        val cost get() = amountIn + fee.coerceAtLeast(0.0)
        val shares get() = amountOut
        val price get() = if (shares > 0) cost / shares else Double.NaN
    }
    class Funding(val label: String, val source: String, val accountType: String, val available: Double)

    class OutcomeUnknown(msg: String) : Exception(msg)

    fun nowMs() = System.currentTimeMillis() + offsetMs

    // ------------------------------------------------------------------ http
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun pairs(m: Map<String, Any?>) = m.entries.flatMap { (k, v) ->
        when (v) {
            null -> emptyList()
            is Iterable<*> -> v.filterNotNull().map { "${enc(k)}=${enc(it.toString())}" }
            else -> if (v.toString().isEmpty()) emptyList() else listOf("${enc(k)}=${enc(v.toString())}")
        }
    }.joinToString("&")

    private fun hmac(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun http(method: String, url: String, body: String?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("X-MBX-APIKEY", key)
        c.setRequestProperty("User-Agent", "ai-desk-android/1.0")
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally { c.disconnect() }
    }

    fun syncTime() {
        val t0 = System.currentTimeMillis()
        val (_, txt) = http("GET", "$BASE/api/v3/time", null)
        val t1 = System.currentTimeMillis()
        offsetMs = JSONObject(txt).getLong("serverTime") - (t0 + (t1 - t0) / 2)
        syncedAt = t1
    }

    private fun parse(status: Int, txt: String, path: String): String {
        if (status < 400) return txt
        val o = runCatching { JSONObject(txt) }.getOrNull()
        throw BinanceException(status, o?.let { if (it.has("code")) it.optInt("code") else null }, o?.optString("msg")?.ifBlank { null } ?: "$path: ${txt.take(200)}")
    }

    /** Signed call. retryable = may be re-sent on IO / 5xx (never for place-order). */
    private fun signed(method: String, path: String, params: Map<String, Any?>, retryable: Boolean): String {
        if (System.currentTimeMillis() - syncedAt > 600_000) runCatching { syncTime() }
        var resynced = false
        var attempt = 0
        while (true) {
            attempt++
            val q = pairs(LinkedHashMap(params).apply { put("timestamp", nowMs()); put("recvWindow", 5000) })
            val all = "$q&signature=${hmac(q)}"
            try {
                val (st, txt) = if (method == "GET") http("GET", "$BASE$path?$all", null) else http("POST", "$BASE$path", all)
                return parse(st, txt, path)
            } catch (e: BinanceException) {
                if (e.code == -1021 && !resynced) { resynced = true; runCatching { syncTime() }; continue }
                if (!retryable || e.status < 500 || attempt >= 3) throw e
            } catch (e: IOException) {
                if (!retryable || attempt >= 3) throw e
            }
            Thread.sleep(400L shl (attempt - 1))
        }
    }

    private fun get(path: String, p: Map<String, Any?> = emptyMap()) = JSONObject(signed("GET", path, p, true))

    // ------------------------------------------------------------------ account
    fun apiRestrictions(): JSONObject = get("/sapi/v1/account/apiRestrictions")

    fun wallet(): Wallet {
        val a = get("/sapi/v1/w3w/wallet/prediction/wallet/list").optJSONArray("wallets") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { Wallet(it.getString("walletAddress"), it.getString("walletId"), it.optLong("registeredTime")) }
            .maxByOrNull { it.registered } ?: throw UserError("No Binance Prediction wallet on this account (open Prediction in the Binance app once).")
    }

    fun balances(): List<Balance> {
        val a = get("/sapi/v1/w3w/wallet/prediction/balance/payment-options").optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Balance(it.optString("accountType"), it.optString("availableBalanceDisplay", "0").toDoubleOrNull() ?: 0.0, it.optBoolean("enabled", true))
        }
    }

    /** (summary json, positions) for tab ONGOING | ENDED */
    fun positions(w: Wallet, tab: String): Pair<JSONObject, List<Position>> {
        val all = ArrayList<Position>(); var off = 0; var first: JSONObject? = null
        while (true) {
            val o = get("/sapi/v1/w3w/wallet/prediction/position/list", linkedMapOf("walletAddress" to w.address, "tab" to tab, "offset" to off, "limit" to 100))
            if (first == null) first = o
            val a = o.optJSONArray("positions") ?: JSONArray()
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                all += Position(it.optString("tokenId"), it.optString("outcomeName"), it.optString("positionStatus"), it.optBoolean("canClaim"),
                    it.optString("totalCost", "0").toDoubleOrNull() ?: 0.0, it.optString("pnl", "0").toDoubleOrNull() ?: 0.0, it.optLong("endDate"))
            }
            off += a.length()
            if (a.length() == 0 || off >= o.optInt("total", all.size) || off >= 500) break
        }
        return (first ?: JSONObject()) to all
    }

    fun walletBalance(w: Wallet): Double? =
        positions(w, "ONGOING").first.optJSONObject("summary")?.optString("walletBalance")?.toDoubleOrNull()

    /** Funding route (PredictAI core/Funding.kt): CeDeFi/MPC first, then SPOT, then FUNDING. */
    fun funding(bal: List<Balance>, walletBalance: Double?, amount: Double): Funding? {
        val rows = bal.filter { it.enabled }
        fun isT(b: Balance, vararg n: String) = n.any { b.accountType.equals(it, true) }
        val mpc = maxOf(rows.firstOrNull { isT(it, "CeDeFi", "MPC", "WEB3", "PREDICTION") }?.balance ?: 0.0, walletBalance ?: 0.0)
        val acct = if (rows.any { isT(it, "SPOT") }) "SPOT" else if (rows.any { isT(it, "FUNDING") }) "FUNDING" else "SPOT"
        if (mpc >= amount) return Funding("CeDeFi/MPC", "MPC", acct, mpc)
        rows.firstOrNull { isT(it, "SPOT") }?.let { if (it.balance >= amount) return Funding("SPOT", "CEX", "SPOT", it.balance) }
        rows.firstOrNull { isT(it, "FUNDING") }?.let { if (it.balance >= amount) return Funding("FUNDING", "CEX", "FUNDING", it.balance) }
        return null
    }

    // ------------------------------------------------------------------ markets
    private val upW = Regex("\\b(UP|HIGHER|GREEN|ABOVE)\\b")
    private val dnW = Regex("\\b(DOWN|LOWER|RED|BELOW)\\b")
    private fun sideOf(t: String): Int { val u = upW.containsMatchIn(t); val d = dnW.containsMatchIn(t); return if (u && !d) 1 else if (d && !u) -1 else 0 }

    /** PredictAI parseAlignedBtc5mMarket: the BTCUSDT 5m Up/Down market whose window is [T, T+5m]; fail closed. */
    fun parseMarket(d: JSONObject, T: Long): Market? {
        val start = d.optLong("startDate"); val end = d.optLong("endDate")
        val sym = d.optString("symbol")
        if ((sym.isNotEmpty() && sym != "BTCUSDT") || (sym.isEmpty() && !isBtc5m(d)) || d.optString("chartType") != "CRYPTO_UP_DOWN") return null
        if (end - start !in 240_000L..360_000L) return null
        if (abs(start - T) > 65_000L || abs(end - (T + 300_000L)) > 65_000L) return null
        val ms = d.optJSONArray("markets") ?: JSONArray()
        val ups = ArrayList<Pair<Int, Token>>(); val dns = ArrayList<Pair<Int, Token>>()
        for (i in 0 until ms.length()) {
            val m = ms.getJSONObject(i)
            if (!m.optString("tradingStatus", "OPEN").equals("OPEN", true)) continue
            val mt = (m.optString("title") + " " + m.optString("question")).uppercase()
            val outs = m.optJSONArray("outcomes") ?: JSONArray()
            for (j in 0 until outs.length()) {
                val x = outs.getJSONObject(j)
                val on = x.optString("name").trim().uppercase()
                val tok = Token(x.optString("tokenId"), "$mt / $on", x.optString("price").toDoubleOrNull() ?: x.optString("chance").toDoubleOrNull())
                if (tok.tokenId.isBlank()) continue
                val named = sideOf(on)
                val (side, prio) = when { named != 0 -> named to 0; on == "YES" -> sideOf(mt) to 1; on == "NO" -> -sideOf(mt) to 2; else -> 0 to 9 }
                if (side == 1) ups += prio to tok else if (side == -1) dns += prio to tok
            }
        }
        fun pick(xs: List<Pair<Int, Token>>): Token? {
            val best = xs.minOfOrNull { it.first } ?: return null
            return xs.filter { it.first == best }.map { it.second }.distinctBy { it.tokenId }.singleOrNull()
        }
        val up = pick(ups) ?: return null; val dn = pick(dns) ?: return null
        if (up.tokenId == dn.tokenId) return null
        return Market(d.optLong("marketTopicId"), d.optString("title"), start, end, d.optInt("slippageBps", 1200).coerceIn(1, 10_000), up, dn)
    }

    /** Why the last market() call found / did not find the round (shown in the app and the CSV). */
    @Volatile var lastDiag: String = ""

    private fun isBtc5m(t: JSONObject): Boolean {
        val s = t.optLong("startDate"); val e = t.optLong("endDate")
        val text = (t.optString("symbol") + " " + t.optString("slug") + " " + t.optString("title") + " " + t.optString("question")).lowercase()
        return (t.optString("chartType").isEmpty() || t.optString("chartType") == "CRYPTO_UP_DOWN") &&
            (text.contains("btc") || text.contains("bitcoin")) && (e - s in 240_000L..360_000L || Regex("\\b5\\s*(m|min|minute)").containsMatchIn(text))
    }

    private fun detail(topic: Long) = get("/sapi/v1/w3w/wallet/prediction/market/detail", linkedMapOf("marketTopicId" to topic))

    /** tradingStatus values of a detail, for the diagnosis */
    private fun statuses(d: JSONObject): String {
        val ms = d.optJSONArray("markets") ?: return "no markets"
        return (0 until ms.length()).joinToString("/") { ms.getJSONObject(it).optString("tradingStatus", "?") }
    }

    /**
     * The BTC 5m Up/Down market for the round starting at T, also BEFORE it starts. Routes, in order:
     *  1. cached topic id;  2. market/list (crypto / up-down, newest first and soonest-ending first, 100 each), which lists
     *     rounds created in advance;  3. the "timeline" of the round before (adjacent rounds are separate topics);
     *  4. semantic search (only finds some rounds: the reason early buys failed before).
     * Trading is allowed only when markets[].tradingStatus == OPEN (Binance docs); lastDiag says what was seen.
     */
    fun market(T: Long): Market {
        val seen = ArrayList<String>()
        fun tryTopic(topic: Long, via: String): Market? {
            val d = runCatching { detail(topic) }.getOrElse { seen += "$via $topic: ${it.message}"; return null }
            val m = parseMarket(d, T)
            if (m == null) seen += "$via topic $topic start ${(d.optLong("startDate") - T) / 1000}s tradingStatus ${statuses(d)}"
            else {
                topicCache[T] = topic
                if (topicCache.size > 64) topicCache.keys.filter { k -> k < T - 3_600_000L }.forEach { k -> topicCache.remove(k) }
                lastDiag = "found via $via (topic $topic) at ${(nowMs() - T) / 1000}s"
            }
            return m
        }
        fun near(t: JSONObject) = isBtc5m(t) && abs(t.optLong("startDate") - T) <= 65_000L
        topicCache[T]?.let { tryTopic(it, "cache")?.let { m -> return m }; topicCache.remove(T) }
        // 2. category list, two sort orders
        for ((sort, order) in listOf("CREATED_TIME" to "DESC", "END_DATE" to "ASC")) {
            val o = runCatching { get("/sapi/v1/w3w/wallet/prediction/market/list", linkedMapOf("l1Category" to "crypto", "l2Category" to "up-down",
                "sortBy" to sort, "orderBy" to order, "offset" to 0, "limit" to 100)) }.getOrElse { seen += "list $sort: ${it.message}"; null } ?: continue
            val a = o.optJSONArray("marketTopics") ?: JSONArray()
            val hits = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { near(it) }
            if (hits.isEmpty()) seen += "list $sort: ${a.length()} topics, none for this round"
            for (t in hits.sortedBy { abs(it.optLong("startDate") - T) }.take(3)) tryTopic(t.optLong("marketTopicId"), "list")?.let { return it }
        }
        // 3. timeline of the previous round
        topicCache[T - 300_000L]?.let { prev ->
            runCatching { detail(prev) }.getOrNull()?.optJSONArray("timeline")?.let { tl ->
                for (i in 0 until tl.length()) tl.optJSONObject(i)?.let { x ->
                    if (abs(x.optLong("startDate") - T) <= 65_000L) tryTopic(x.optLong("marketTopicId"), "timeline")?.let { return it }
                }
            }
        }
        // 4. search
        val found = runCatching { JSONArray(signed("GET", "/sapi/v1/w3w/wallet/prediction/market/search",
            linkedMapOf("query" to "BTC 5m up or down", "topK" to 50), true)) }.getOrElse { seen += "search: ${it.message}"; JSONArray() }
        val cands = (0 until found.length()).mapNotNull { found.optJSONObject(it) }.filter { near(it) }
        if (cands.isEmpty()) seen += "search: ${found.length()} topics, none for this round"
        for (t in cands.sortedBy { abs(it.optLong("startDate") - T) }.take(5)) tryTopic(t.optLong("marketTopicId"), "search")?.let { return it }
        lastDiag = "not found at ${(nowMs() - T) / 1000}s: " + seen.joinToString(" | ").take(400)
        throw UserError("No open Binance BTC 5m Up/Down market for this round yet.")
    }
    // ------------------------------------------------------------------ trading
    private fun wei(x: Double): String = BigDecimal.valueOf(x).multiply(BigDecimal.TEN.pow(18)).setScale(0, RoundingMode.DOWN).toBigIntegerExact().toString()
    private fun fromWei(q: JSONObject, n: String) = q.optString(n, "0").toBigDecimalOrNull()?.movePointLeft(18)?.toDouble() ?: 0.0

    fun quote(w: Wallet, tokenId: String, usdt: Double, slippageBps: Int, fundingSource: String): Quote {
        val o = JSONObject(signed("POST", "/sapi/v1/w3w/wallet/prediction/trade/get-quote", linkedMapOf(
            "walletAddress" to w.address, "tokenId" to tokenId, "side" to "BUY", "amountIn" to wei(usdt), "orderType" to "MARKET",
            "slippageBps" to slippageBps.coerceIn(1, 10_000), "chainId" to "56", "feeRateBps" to FEE_BPS, "fundingSource" to fundingSource), true))
        return Quote(o, fromWei(o, "amountIn").takeIf { it > 0 } ?: usdt, fromWei(o, "amountOut"), fromWei(o, "feeAmount"), o.optLong("expireAt"))
    }

    fun orders(w: Wallet): List<Order> {
        val out = ArrayList<Order>()
        fun parse(o: JSONObject) { val a = o.optJSONArray("orders") ?: JSONArray(); for (i in 0 until a.length()) a.getJSONObject(i).let {
            out += Order(it.optString("orderId"), it.optString("side"), it.optString("status"), it.optLong("createTime"), it.optString("outcome")) } }
        runCatching { parse(get("/sapi/v1/w3w/wallet/prediction/order/list", linkedMapOf("walletAddress" to w.address, "offset" to 0, "limit" to 100))) }
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val now = nowMs()
        runCatching { parse(get("/sapi/v1/w3w/wallet/prediction/order/history", linkedMapOf("walletAddress" to w.address, "offset" to 0, "limit" to 100,
            "startDate" to day.format(java.util.Date(now - 86_400_000L)), "endDate" to day.format(java.util.Date(now + 86_400_000L))))) }
        return out
    }

    /** Market BUY/SELL from a quote. Never retried. Throws OutcomeUnknown when it may or may not have been placed. */
    fun placeOrder(w: Wallet, q: Quote, accountType: String, slippageBps: Int, fundingSource: String): String {
        val side = q.raw.optString("side", "BUY").uppercase()
        val guard = "${w.address}|$side"
        synchronized(placeLock) {
            val blocked = (unknownUntil[guard] ?: 0L) - System.currentTimeMillis()
            if (blocked > 0) throw OutcomeUnknown("An earlier $side order has an unknown outcome; new orders blocked for ${blocked / 1000 + 1}s.")
            val sentAt = nowMs()
            try {
                return JSONObject(signed("POST", "/sapi/v1/w3w/wallet/prediction/trade/place-order-bundle", linkedMapOf(
                    "walletAddress" to w.address, "walletId" to w.id, "quoteId" to q.raw.getString("quoteId"), "timeInForce" to "FOK",
                    "accountType" to accountType, "orderType" to "MARKET", "slippageBps" to slippageBps.coerceIn(1, 10_000),
                    "fundingSource" to fundingSource), false)).getString("orderId")
            } catch (e: Exception) {
                val ambiguous = e is IOException || (e is BinanceException && e.status >= 500)
                if (!ambiguous) throw e
                repeat(3) { i ->
                    Thread.sleep(1_500L * (i + 1))
                    orders(w).filter { it.side.equals(side, true) && it.createTime >= sentAt - 5_000L }.maxByOrNull { it.createTime }?.let { return it.orderId }
                }
                unknownUntil[guard] = System.currentTimeMillis() + 90_000L
                throw OutcomeUnknown("Order outcome unknown (${e.message}). Not resent: check Binance.")
            }
        }
    }

    /** Claim every claimable ended position. Returns how many tokens were claimed. */
    fun claimAll(w: Wallet): Int {
        val ids = positions(w, "ENDED").second.filter { it.canClaim }.map { it.tokenId }.distinct()
        if (ids.isEmpty()) return 0
        signed("POST", "/sapi/v1/w3w/wallet/prediction/batch-redeem", linkedMapOf("walletAddress" to w.address, "walletId" to w.id,
            "tokenIds" to ids, "chainId" to "56"), false)
        return ids.size
    }
}
