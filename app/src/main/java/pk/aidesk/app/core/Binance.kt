package pk.aidesk.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.round

class BinanceException(val status: Int, val code: Int?, val msg: String) :
    Exception("Binance $status ${code ?: ""} $msg")

data class Filters(val step: Double, val minQty: Double, val tick: Double, val minNotional: Double)

/** Port of binance_api.py: USDT-M futures REST client (public + signed). */
class Binance(private val key: String? = null, private val secret: String? = null, var base: String = MAINNET) {
    companion object {
        const val MAINNET = "https://fapi.binance.com"
        const val TESTNET = "https://testnet.binancefuture.com"
        const val DEMO = "https://demo-fapi.binance.com"
        const val SPOT = "https://api.binance.com"

        fun baseFor(mode: String) = if (mode == "testnet") TESTNET else MAINNET
    }

    @Volatile var offsetMs = 0.0
    @Volatile private var syncedAt = 0L
    private var info: Map<String, JSONObject>? = null

    // ------------------------------------------------------------------ core
    private fun enc(p: Map<String, Any?>) = p.entries.filter { it.value != null }
        .joinToString("&") { it.key + "=" + URLEncoder.encode(it.value.toString(), "UTF-8") }

    private fun sign(q: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret!!.toByteArray(), "HmacSHA256"))
        return mac.doFinal(q.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Timestamp for signed calls: re-sync every 5 min and stay 500 ms behind server time (-1021 fix). */
    private fun ts(): Long {
        if (System.currentTimeMillis() - syncedAt > 300_000) runCatching { syncTime() }
        return (System.currentTimeMillis() + offsetMs - 500).toLong()
    }

    fun nowMs(): Long = (System.currentTimeMillis() + offsetMs).toLong()

    private fun http(method: String, url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "ai-desk-android/1.0")
        if (key != null) c.setRequestProperty("X-MBX-APIKEY", key)
        try {
            val code = c.responseCode
            val body = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return code to body
        } finally {
            c.disconnect()
        }
    }

    fun request(method: String, path: String, params: Map<String, Any?> = emptyMap(), signed: Boolean = false,
                host: String = base, retry1021: Boolean = true): Any {
        var q = enc(params)
        if (signed) {
            if (key.isNullOrBlank() || secret.isNullOrBlank()) throw BinanceException(0, null, "API key and secret are not set")
            q = (if (q.isEmpty()) "" else "$q&") + "timestamp=${ts()}&recvWindow=5000"
            q += "&signature=" + sign(q)
        }
        val url = host + path + (if (q.isNotEmpty()) "?$q" else "")
        // Only GETs are retried: re-sending an order after a network error could double it.
        val tries = if (method == "GET") 3 else 1
        var res: Pair<Int, String>? = null
        for (attempt in 0 until tries) {
            try {
                res = http(method, url)
            } catch (e: IOException) {
                if (attempt == tries - 1) throw e
                Thread.sleep(1000L * (attempt + 1)); continue
            }
            val st = res.first
            if ((st == 418 || st == 429 || st >= 500) && attempt < tries - 1) {
                Thread.sleep(2000L * (attempt + 1)); continue
            }
            break
        }
        val (status, body) = res!!
        val data: Any = try {
            if (body.trimStart().startsWith("[")) JSONArray(body) else JSONObject(body)
        } catch (e: Exception) {
            JSONObject().put("code", status).put("msg", body.take(300))
        }
        if (status >= 400) {
            val o = data as? JSONObject
            val code = o?.optInt("code")
            if (signed && code == -1021 && retry1021) {
                // Timestamp outside the window: rejected before processing, so re-sync + retry once is safe.
                syncedAt = 0
                return request(method, path, params, true, host, false)
            }
            throw BinanceException(status, code, o?.optString("msg") ?: body.take(200))
        }
        return data
    }

    fun get(path: String, p: Map<String, Any?> = emptyMap()) = request("GET", path, p)
    fun signed(method: String, path: String, p: Map<String, Any?> = emptyMap()) = request(method, path, p, true)

    fun syncTime(): Double {
        val t0 = System.currentTimeMillis()
        val st = (request("GET", "/fapi/v1/time") as JSONObject).getLong("serverTime")
        val t1 = System.currentTimeMillis()
        offsetMs = st - (t0 + t1) / 2.0
        syncedAt = System.currentTimeMillis()
        return offsetMs
    }

    // ---------------------------------------------------------------- market
    /** Last `bars` CLOSED candles (same as data.fetch_recent). */
    fun recent(symbol: String, interval: String = "15m", bars: Int = 450): Candles {
        val a = get("/fapi/v1/klines", mapOf("symbol" to symbol, "interval" to interval, "limit" to minOf(1500, bars + 1))) as JSONArray
        val now = nowMs()
        val rows = (0 until a.length()).map { a.getJSONArray(it) }.filter { it.getLong(6) < now }
        return Candles(
            LongArray(rows.size) { rows[it].getLong(0) },
            DoubleArray(rows.size) { rows[it].getString(1).toDouble() },
            DoubleArray(rows.size) { rows[it].getString(2).toDouble() },
            DoubleArray(rows.size) { rows[it].getString(3).toDouble() },
            DoubleArray(rows.size) { rows[it].getString(4).toDouble() },
            DoubleArray(rows.size) { rows[it].getString(5).toDouble() },
            DoubleArray(rows.size) { rows[it].getString(9).toDouble() },
            LongArray(rows.size) { rows[it].getLong(6) },
        )
    }

    /** Last `bars` candles INCLUDING the one still forming (for the live chart). */
    fun live(symbol: String, bars: Int = 60): Candles {
        val a = get("/fapi/v1/klines", mapOf("symbol" to symbol, "interval" to "15m", "limit" to bars)) as JSONArray
        val rows = (0 until a.length()).map { a.getJSONArray(it) }
        return Candles(
            LongArray(rows.size) { rows[it].getLong(0) },
            DoubleArray(rows.size) { rows[it].getString(1).toDouble() },
            DoubleArray(rows.size) { rows[it].getString(2).toDouble() },
            DoubleArray(rows.size) { rows[it].getString(3).toDouble() },
            DoubleArray(rows.size) { rows[it].getString(4).toDouble() },
            DoubleArray(rows.size) { rows[it].getString(5).toDouble() },
            DoubleArray(rows.size) { rows[it].getString(9).toDouble() },
            LongArray(rows.size) { rows[it].getLong(6) },
        )
    }

    fun markPrices(): Map<String, Double> {
        val a = get("/fapi/v1/premiumIndex") as JSONArray
        return (0 until a.length()).associate { a.getJSONObject(it).let { o -> o.getString("symbol") to o.getString("markPrice").toDouble() } }
    }

    fun markPrice(symbol: String) =
        (get("/fapi/v1/premiumIndex", mapOf("symbol" to symbol)) as JSONObject).getString("markPrice").toDouble()

    /** symbol -> exchangeInfo entry (cached; fresh = re-download, e.g. to see new listings). */
    fun exchangeInfo(fresh: Boolean = false): Map<String, JSONObject> {
        if (!fresh) info?.let { return it }
        val a = (get("/fapi/v1/exchangeInfo") as JSONObject).getJSONArray("symbols")
        return (0 until a.length()).associate { a.getJSONObject(it).let { o -> o.getString("symbol") to o } }.also { info = it }
    }

    fun klines(symbol: String, interval: String, start: Long? = null, limit: Int = 500): JSONArray =
        get("/fapi/v1/klines", mapOf("symbol" to symbol, "interval" to interval, "startTime" to start, "limit" to limit)) as JSONArray

    fun ticker24h(): JSONArray = get("/fapi/v1/ticker/24hr") as JSONArray

    fun premiumAll(): JSONArray = get("/fapi/v1/premiumIndex") as JSONArray

    /** Sum of funding rates paid between t0 and t1 (a short RECEIVES +rate x notional). */
    fun fundingSum(symbol: String, t0: Long, t1: Long): Double = runCatching {
        val a = get("/fapi/v1/fundingRate", mapOf("symbol" to symbol, "startTime" to t0, "endTime" to t1, "limit" to 1000)) as JSONArray
        (0 until a.length()).sumOf { a.getJSONObject(it).getString("fundingRate").toDouble() }
    }.getOrDefault(0.0)

    fun filters(symbol: String): Filters {
        val m = exchangeInfo()
        val fs = m[symbol]?.getJSONArray("filters") ?: throw BinanceException(0, null, "Unknown symbol $symbol")
        val f = (0 until fs.length()).associate { fs.getJSONObject(it).let { o -> o.getString("filterType") to o } }
        val lot = f["MARKET_LOT_SIZE"] ?: f.getValue("LOT_SIZE")
        val step = lot.getString("stepSize").toDouble().takeIf { it > 0 } ?: f.getValue("LOT_SIZE").getString("stepSize").toDouble()
        return Filters(
            step = step,
            minQty = max(lot.getString("minQty").toDouble(), f.getValue("LOT_SIZE").getString("minQty").toDouble()),
            tick = f.getValue("PRICE_FILTER").getString("tickSize").toDouble(),
            minNotional = f["MIN_NOTIONAL"]?.optString("notional")?.toDoubleOrNull() ?: 5.0,
        )
    }

    private fun roundStep(x: Double, step: Double, floorMode: Boolean): Pair<Double, String> {
        val n = if (floorMode) floor(x / step + 1e-9) else round(x / step)
        val dec = if (step < 1) max(0, -floor(log10(step)).toInt()) else 0
        val s = String.format(Locale.US, "%.${dec}f", n * step)
        return s.toDouble() to s
    }

    fun fmtQty(symbol: String, qty: Double) = roundStep(qty, filters(symbol).step, true)
    fun fmtPrice(symbol: String, price: Double) = roundStep(price, filters(symbol).tick, false)

    // --------------------------------------------------------------- account
    fun account(): JSONObject = try {
        signed("GET", "/fapi/v2/account") as JSONObject
    } catch (e: BinanceException) {
        if (e.status == 401 || e.code == -2015 || e.code == -2014) throw e
        signed("GET", "/fapi/v3/account") as JSONObject
    }

    /** (margin balance, available) */
    fun equity(): Pair<Double, Double> = account().let {
        it.getString("totalMarginBalance").toDouble() to it.getString("availableBalance").toDouble()
    }

    fun positions(): Map<String, JSONObject> {
        val a = try {
            signed("GET", "/fapi/v2/positionRisk") as JSONArray
        } catch (e: BinanceException) {
            signed("GET", "/fapi/v3/positionRisk") as JSONArray
        }
        return (0 until a.length()).map { a.getJSONObject(it) }
            .filter { kotlin.math.abs(it.getString("positionAmt").toDouble()) > 0 }
            .associateBy { it.getString("symbol") }
    }

    fun apiRestrictions(): JSONObject = request("GET", "/sapi/v1/account/apiRestrictions", emptyMap(), true, SPOT) as JSONObject

    fun isHedgeMode() = (signed("GET", "/fapi/v1/positionSide/dual") as JSONObject).getBoolean("dualSidePosition")

    fun setOneWay() = try {
        signed("POST", "/fapi/v1/positionSide/dual", mapOf("dualSidePosition" to "false"))
    } catch (e: BinanceException) {
        if (e.code != -4059) throw e else null
    }

    fun setLeverage(symbol: String, lev: Int) = signed("POST", "/fapi/v1/leverage", mapOf("symbol" to symbol, "leverage" to lev))

    fun setMarginType(symbol: String, type: String) = try {
        signed("POST", "/fapi/v1/marginType", mapOf("symbol" to symbol, "marginType" to type))
    } catch (e: BinanceException) {
        if (e.code != -4046) throw e else null
    }

    fun realizedPnlSince(symbol: String, startMs: Long): Double {
        val a = signed("GET", "/fapi/v1/income", mapOf("symbol" to symbol, "startTime" to startMs, "limit" to 1000)) as JSONArray
        var s = 0.0
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.getString("incomeType") in setOf("REALIZED_PNL", "COMMISSION")) s += o.getString("income").toDouble()
        }
        return s
    }

    /** Realized PnL + commission + funding since startMs (everything a closed bot trade earned or paid). */
    fun incomeSince(symbol: String, startMs: Long): Double {
        val a = signed("GET", "/fapi/v1/income", mapOf("symbol" to symbol, "startTime" to startMs, "limit" to 1000)) as JSONArray
        var s = 0.0
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.getString("incomeType") in setOf("REALIZED_PNL", "COMMISSION", "FUNDING_FEE")) s += o.getString("income").toDouble()
        }
        return s
    }

    // ---------------------------------------------------------------- orders
    fun marketOrder(symbol: String, side: String, qty: String, reduceOnly: Boolean = false) =
        signed("POST", "/fapi/v1/order", mapOf("symbol" to symbol, "side" to side, "type" to "MARKET", "quantity" to qty,
            "reduceOnly" to (if (reduceOnly) "true" else null), "newOrderRespType" to "RESULT")) as JSONObject

    /** STOP_MARKET / TAKE_PROFIT_MARKET closing the whole position (Algo endpoint, legacy fallback). */
    fun closeTrigger(symbol: String, side: String, kind: String, trigger: String): Any {
        val b = mapOf("symbol" to symbol, "side" to side, "type" to kind, "closePosition" to "true", "workingType" to "MARK_PRICE")
        return try {
            signed("POST", "/fapi/v1/algoOrder", b + mapOf("algoType" to "CONDITIONAL", "triggerPrice" to trigger))
        } catch (e1: BinanceException) {
            try {
                signed("POST", "/fapi/v1/order", b + mapOf("stopPrice" to trigger))
            } catch (e2: BinanceException) {
                throw BinanceException(e2.status, e2.code, "algo: ${e1.msg} | legacy: ${e2.msg}")
            }
        }
    }

    fun cancelAll(symbol: String) {
        for (p in listOf("/fapi/v1/allOpenOrders", "/fapi/v1/algoOpenOrders")) {
            runCatching { signed("DELETE", p, mapOf("symbol" to symbol)) }
        }
    }

    // ------------------------------------------------------- manual trading
    /** Candles of any interval INCLUDING the one still forming (last element). */
    fun candles(symbol: String, interval: String, limit: Int): Candles {
        val a = get("/fapi/v1/klines", mapOf("symbol" to symbol, "interval" to interval, "limit" to limit.coerceIn(2, 1500))) as JSONArray
        val rows = (0 until a.length()).map { a.getJSONArray(it) }
        return Candles(
            LongArray(rows.size) { rows[it].getLong(0) },
            DoubleArray(rows.size) { rows[it].getString(1).toDouble() },
            DoubleArray(rows.size) { rows[it].getString(2).toDouble() },
            DoubleArray(rows.size) { rows[it].getString(3).toDouble() },
            DoubleArray(rows.size) { rows[it].getString(4).toDouble() },
            DoubleArray(rows.size) { rows[it].getString(5).toDouble() },
            DoubleArray(rows.size) { rows[it].getString(9).toDouble() },
            LongArray(rows.size) { rows[it].getLong(6) },
        )
    }

    /** GTC limit order (entry or reduce-only exit). */
    fun limitOrder(symbol: String, side: String, qty: String, price: String, reduceOnly: Boolean = false) =
        signed("POST", "/fapi/v1/order", mapOf("symbol" to symbol, "side" to side, "type" to "LIMIT", "timeInForce" to "GTC",
            "quantity" to qty, "price" to price, "reduceOnly" to (if (reduceOnly) "true" else null), "newOrderRespType" to "RESULT")) as JSONObject

    /** Breakout entry: STOP_MARKET that OPENS a position when the mark price crosses `trigger` (Algo endpoint, legacy fallback). */
    fun stopEntry(symbol: String, side: String, qty: String, trigger: String): Any {
        val b = mapOf("symbol" to symbol, "side" to side, "type" to "STOP_MARKET", "quantity" to qty, "workingType" to "MARK_PRICE")
        return try {
            signed("POST", "/fapi/v1/algoOrder", b + mapOf("algoType" to "CONDITIONAL", "triggerPrice" to trigger))
        } catch (e1: BinanceException) {
            try {
                signed("POST", "/fapi/v1/order", b + mapOf("stopPrice" to trigger))
            } catch (e2: BinanceException) {
                throw BinanceException(e2.status, e2.code, "algo: ${e1.msg} | legacy: ${e2.msg}")
            }
        }
    }

    private fun arr(x: Any?): List<JSONObject> = when (x) {
        is JSONArray -> (0 until x.length()).map { x.getJSONObject(it) }
        is JSONObject -> (x.optJSONArray("orders") ?: x.optJSONArray("rows") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        else -> emptyList()
    }

    /** All open orders for a symbol: normal + algo (conditional) orders. */
    fun openOrdersAll(symbol: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        out += arr(runCatching { signed("GET", "/fapi/v1/openOrders", mapOf("symbol" to symbol)) }.getOrNull())
        out += arr(runCatching { signed("GET", "/fapi/v1/openAlgoOrders", mapOf("symbol" to symbol)) }.getOrNull())
        return out
    }

    /** Which protective orders exist for a symbol: "SL" (STOP_MARKET close) and/or "TP" (TAKE_PROFIT_MARKET close). */
    fun protections(symbol: String): Set<String> {
        val s = HashSet<String>()
        for (o in openOrdersAll(symbol)) {
            val t = (o.optString("orderType").ifEmpty { o.optString("type") }).uppercase()
            val closing = o.optBoolean("closePosition") || o.optString("closePosition") == "true" || o.optBoolean("reduceOnly")
            if (!closing) continue
            if (t.startsWith("STOP")) s += "SL"
            if (t.startsWith("TAKE_PROFIT")) s += "TP"
        }
        return s
    }

    /** Is there still an entry (non-closing) order waiting for this symbol? */
    fun hasEntryOrder(symbol: String): Boolean = openOrdersAll(symbol).any { o ->
        !(o.optBoolean("closePosition") || o.optString("closePosition") == "true" || o.optBoolean("reduceOnly"))
    }

    /** Positioning data (public "futures/data" endpoints, 1h period). Missing values are null. */
    fun positioning(symbol: String): Map<String, Double?> {
        fun last(path: String, field: String, limit: Int = 1): List<Double> = runCatching {
            val a = get(path, mapOf("symbol" to symbol, "period" to "1h", "limit" to limit)) as JSONArray
            (0 until a.length()).map { a.getJSONObject(it).getString(field).toDouble() }
        }.getOrDefault(emptyList())
        val oi = last("/futures/data/openInterestHist", "sumOpenInterest", 25)
        val oiUsd = last("/futures/data/openInterestHist", "sumOpenInterestValue", 1)
        val fr = runCatching { (get("/fapi/v1/premiumIndex", mapOf("symbol" to symbol)) as JSONObject).getString("lastFundingRate").toDouble() * 100 }.getOrNull()
        return mapOf(
            "funding_pct" to fr,
            "oi_usd" to oiUsd.lastOrNull(),
            "oi_chg_24h_pct" to if (oi.size >= 2 && oi.first() > 0) (oi.last() / oi.first() - 1) * 100 else null,
            "ls_accounts" to last("/futures/data/globalLongShortAccountRatio", "longShortRatio").lastOrNull(),
            "ls_top" to last("/futures/data/topLongShortPositionRatio", "longShortRatio").lastOrNull(),
            "taker_bs" to last("/futures/data/takerlongshortRatio", "buySellRatio").lastOrNull(),
        )
    }

    /** USDT perpetual coins that are trading, with 24h stats, sorted by volume. */
    fun usdtPerps(): List<CoinRow> {
        val info = exchangeInfo(fresh = true).filterValues {
            it.optString("quoteAsset") == "USDT" && it.optString("contractType") == "PERPETUAL" && it.optString("status") == "TRADING"
        }
        val t = ticker24h()
        return (0 until t.length()).map { t.getJSONObject(it) }.filter { it.getString("symbol") in info }.map {
            CoinRow(it.getString("symbol"), it.getString("lastPrice").toDouble(), it.getString("priceChangePercent").toDouble(),
                it.getString("quoteVolume").toDouble())
        }.sortedByDescending { it.volume }
    }
}

class CoinRow(val symbol: String, val price: Double, val changePct: Double, val volume: Double)
