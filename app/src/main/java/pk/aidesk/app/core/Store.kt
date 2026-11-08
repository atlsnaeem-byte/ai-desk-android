package pk.aidesk.app.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// ------------------------------------------------------------------ data
data class Trade(
    val id: String, val mode: String, val symbol: String, val side: String,
    val entry: Double, val qty: Double, val sl: Double, val tp: Double,
    val openedMs: Long, val prob: Double, val evR: Double, val origin: String, val equityBefore: Double,
    val patterns: List<String> = emptyList(), val drivers: List<Pair<String, Double>> = emptyList(),
    val status: String = "open", val exit: Double? = null, val exitMs: Long? = null,
    val reason: String? = null, val pnl: Double? = null, val r: Double? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("mode", mode); put("symbol", symbol); put("side", side); put("entry", entry); put("qty", qty)
        put("sl", sl); put("tp", tp); put("openedMs", openedMs); put("prob", prob); put("evR", evR); put("origin", origin)
        put("equityBefore", equityBefore); put("status", status)
        put("patterns", JSONArray(patterns))
        put("drivers", JSONArray(drivers.map { JSONArray().put(it.first).put(it.second) }))
        exit?.let { put("exit", it) }; exitMs?.let { put("exitMs", it) }; reason?.let { put("reason", it) }
        pnl?.takeIf { !it.isNaN() }?.let { put("pnl", it) }; r?.takeIf { !it.isNaN() }?.let { put("r", it) }
    }

    companion object {
        fun from(o: JSONObject) = Trade(
            o.getString("id"), o.getString("mode"), o.getString("symbol"), o.getString("side"),
            o.getDouble("entry"), o.getDouble("qty"), o.getDouble("sl"), o.getDouble("tp"),
            o.getLong("openedMs"), o.optDouble("prob", 0.0), o.optDouble("evR", 0.0), o.optString("origin", "auto"),
            o.optDouble("equityBefore", 0.0),
            o.optJSONArray("patterns")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            o.optJSONArray("drivers")?.let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getDouble(1) } } } ?: emptyList(),
            o.optString("status", "open"),
            if (o.has("exit")) o.getDouble("exit") else null,
            if (o.has("exitMs")) o.getLong("exitMs") else null,
            if (o.has("reason")) o.getString("reason") else null,
            if (o.has("pnl")) o.getDouble("pnl") else null,
            if (o.has("r")) o.getDouble("r") else null,
        )
    }
}

data class Signal(
    val symbol: String, val barTime: Long, val price: Double, val side: String, val strong: Boolean,
    val prob: Double, val pLong: Double, val pShort: Double, val evR: Double,
    val entry: Double, val tp: Double, val sl: Double, val atr: Double,
    val patterns: List<String>, val drivers: List<Pair<String, Double>>,
    val o: List<Double>, val h: List<Double>, val l: List<Double>, val c: List<Double>,
    /** When this signal (same side + strength) first appeared: the close time of its first bar. */
    val since: Long = barTime + Config.BAR_MS,
    /** Entry / TP / SL from when the signal first appeared (stay fixed while it repeats). */
    val origEntry: Double = entry, val origTp: Double = tp, val origSl: Double = sl,
) {
    /** Close time of the bar this scan is based on. */
    val signalTime: Long get() = barTime + Config.BAR_MS

    fun toJson(): JSONObject = JSONObject().apply {
        put("since", since); put("origEntry", origEntry); put("origTp", origTp); put("origSl", origSl)
        put("symbol", symbol); put("barTime", barTime); put("price", price); put("side", side); put("strong", strong)
        put("prob", prob); put("pLong", pLong); put("pShort", pShort); put("evR", evR); put("entry", entry)
        put("tp", tp); put("sl", sl); put("atr", atr); put("patterns", JSONArray(patterns))
        put("drivers", JSONArray(drivers.map { JSONArray().put(it.first).put(it.second) }))
        put("o", JSONArray(o)); put("h", JSONArray(h)); put("l", JSONArray(l)); put("c", JSONArray(c))
    }

    companion object {
        private fun dl(a: JSONArray) = (0 until a.length()).map { a.getDouble(it) }
        fun from(o: JSONObject) = Signal(
            o.getString("symbol"), o.getLong("barTime"), o.getDouble("price"), o.getString("side"), o.getBoolean("strong"),
            o.getDouble("prob"), o.getDouble("pLong"), o.getDouble("pShort"), o.getDouble("evR"), o.getDouble("entry"),
            o.getDouble("tp"), o.getDouble("sl"), o.getDouble("atr"),
            o.getJSONArray("patterns").let { a -> (0 until a.length()).map { a.getString(it) } },
            o.getJSONArray("drivers").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getDouble(1) } } },
            dl(o.getJSONArray("o")), dl(o.getJSONArray("h")), dl(o.getJSONArray("l")), dl(o.getJSONArray("c")),
            o.optLong("since", o.getLong("barTime") + Config.BAR_MS),
            o.optDouble("origEntry", o.getDouble("entry")), o.optDouble("origTp", o.getDouble("tp")),
            o.optDouble("origSl", o.getDouble("sl")),
        )
    }
}

class ModeState(
    val open: MutableMap<String, Trade> = linkedMapOf(),
    var equity: Double = 1000.0,       // paper only
    var day: String? = null,
    var dayStart: Double? = null,
    var halted: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("open", JSONObject().also { o -> open.forEach { (k, v) -> o.put(k, v.toJson()) } })
        put("equity", equity); put("day", day ?: JSONObject.NULL); put("dayStart", dayStart ?: JSONObject.NULL)
        put("halted", halted ?: JSONObject.NULL)
    }

    companion object {
        fun from(o: JSONObject) = ModeState(
            o.optJSONObject("open")?.let { j -> j.keys().asSequence().associateWith { Trade.from(j.getJSONObject(it)) }.toMutableMap() } ?: linkedMapOf(),
            o.optDouble("equity", 1000.0),
            o.optString("day").takeIf { it.isNotEmpty() && it != "null" },
            if (o.isNull("dayStart")) null else o.optDouble("dayStart"),
            o.optString("halted").takeIf { it.isNotEmpty() && it != "null" },
        )
    }
}

data class Scan(val time: Long, val signals: List<Signal>, val error: String? = null)

data class LogLine(val t: Long, val level: String, val msg: String)

// ------------------------------------------------------------------ store
class Store(ctx: Context) {
    private val app = ctx.applicationContext
    private val prefs = app.getSharedPreferences("aidesk", Context.MODE_PRIVATE)
    private val dir = app.filesDir

    // ---- config
    fun config(): Config {
        val c = readConfig()
        // One-time upgrade of saved settings to new defaults chosen by the user (v2: 7 Nov 2026).
        if (prefs.getInt("defaultsVersion", 1) < 2) {
            val d = Config()
            val up = if (prefs.contains("config")) c.copy(leverage = d.leverage, riskPct = d.riskPct, dailyLossPct = d.dailyLossPct,
                dailyProfitPct = d.dailyProfitPct, minEvR = d.minEvR,
                symbols = (c.symbols + d.symbols.filter { it !in Config.DEFAULT_SYMBOLS_V1 }).distinct()) else c
            saveConfig(up)
            prefs.edit().putInt("defaultsVersion", 2).apply()
            return up
        }
        return c
    }

    private fun readConfig(): Config {
        val o = prefs.getString("config", null)?.let { JSONObject(it) } ?: return Config()
        val d = Config()
        return Config(
            mode = o.optString("mode", d.mode),
            auto = o.optBoolean("auto", d.auto),
            symbols = o.optJSONArray("symbols")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: d.symbols,
            leverage = o.optInt("leverage", d.leverage),
            marginType = o.optString("marginType", d.marginType),
            riskPct = o.optDouble("riskPct", d.riskPct),
            maxOpen = o.optInt("maxOpen", d.maxOpen),
            dailyLossPct = o.optDouble("dailyLossPct", d.dailyLossPct),
            dailyProfitPct = o.optDouble("dailyProfitPct", d.dailyProfitPct),
            minEvR = o.optDouble("minEvR", d.minEvR),
            soundMinProb = o.optDouble("soundMinProb", d.soundMinProb),
            quietStart = o.optInt("quietStart", d.quietStart),
            quietEnd = o.optInt("quietEnd", d.quietEnd),
        )
    }

    fun saveConfig(c: Config) {
        val o = JSONObject().apply {
            put("mode", c.mode); put("auto", c.auto); put("symbols", JSONArray(c.symbols)); put("leverage", c.leverage)
            put("marginType", c.marginType); put("riskPct", c.riskPct); put("maxOpen", c.maxOpen)
            put("dailyLossPct", c.dailyLossPct); put("dailyProfitPct", c.dailyProfitPct); put("minEvR", c.minEvR)
            put("soundMinProb", c.soundMinProb); put("quietStart", c.quietStart); put("quietEnd", c.quietEnd)
        }
        prefs.edit().putString("config", o.toString()).apply()
    }

    var setupDone: Boolean
        get() = prefs.getBoolean("setupDone", false)
        set(v) = prefs.edit().putBoolean("setupDone", v).apply()

    var lastAlert: String
        get() = prefs.getString("lastAlert", "") ?: ""
        set(v) = prefs.edit().putString("lastAlert", v).apply()

    // ---- API keys (AES-GCM with a key that never leaves the Android Keystore)
    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry("aidesk_keys", null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder("aidesk_keys", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    private fun encrypt(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(s.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(s: String): String? = runCatching {
        val (iv, ct) = s.split(":")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)))
    }.getOrNull()

    /** mode "live" or "testnet" -> (key, secret) */
    fun keys(mode: String): Pair<String, String>? {
        val k = prefs.getString("k_$mode", null)?.let { decrypt(it) } ?: return null
        val s = prefs.getString("s_$mode", null)?.let { decrypt(it) } ?: return null
        return k to s
    }

    fun saveKeys(mode: String, key: String, secret: String) {
        prefs.edit().putString("k_$mode", encrypt(key.trim())).putString("s_$mode", encrypt(secret.trim())).apply()
    }

    fun clearKeys(mode: String) = prefs.edit().remove("k_$mode").remove("s_$mode").apply()

    fun keyHint(mode: String): String? = keys(mode)?.first?.let { if (it.length > 8) it.take(4) + "..." + it.takeLast(4) else "set" }

    // ---- files
    private fun read(name: String): String? = File(dir, name).takeIf { it.exists() }?.readText()
    private fun write(name: String, s: String) {
        val tmp = File(dir, "$name.tmp"); tmp.writeText(s); tmp.renameTo(File(dir, name))
    }

    @Synchronized fun state(mode: String, paperStart: Double = 1000.0): ModeState =
        read("state_$mode.json")?.let { runCatching { ModeState.from(JSONObject(it)) }.getOrNull() }
            ?: ModeState(equity = paperStart)

    @Synchronized fun saveState(mode: String, st: ModeState) = write("state_$mode.json", st.toJson().toString())

    @Synchronized fun journal(mode: String): List<Trade> =
        read("journal_$mode.json")?.let { s -> runCatching { JSONArray(s).let { a -> (0 until a.length()).map { Trade.from(a.getJSONObject(it)) } } }.getOrNull() }
            ?: emptyList()

    @Synchronized fun journalUpsert(t: Trade) {
        val j = journal(t.mode).toMutableList()
        val i = j.indexOfFirst { it.id == t.id }
        if (i >= 0) j[i] = t else j.add(t)
        write("journal_${t.mode}.json", JSONArray(j.takeLast(1000).map { it.toJson() }).toString())
    }

    @Synchronized fun scan(): Scan? = read("scan.json")?.let { s ->
        runCatching {
            val o = JSONObject(s)
            val a = o.getJSONArray("signals")
            Scan(o.getLong("time"), (0 until a.length()).map { Signal.from(a.getJSONObject(it)) }, o.optString("error").ifEmpty { null })
        }.getOrNull()
    }

    @Synchronized fun saveScan(sc: Scan) = write("scan.json", JSONObject().apply {
        put("time", sc.time); put("signals", JSONArray(sc.signals.map { it.toJson() })); sc.error?.let { put("error", it) }
    }.toString())

    // ---- generic JSON files (bots)
    @Synchronized fun readObj(name: String): JSONObject? = read(name)?.let { runCatching { JSONObject(it) }.getOrNull() }
    @Synchronized fun writeObj(name: String, o: JSONObject) = write(name, o.toString())
    @Synchronized fun readArr(name: String): JSONArray? = read(name)?.let { runCatching { JSONArray(it) }.getOrNull() }
    @Synchronized fun writeArr(name: String, a: JSONArray) = write(name, a.toString())

    /** Symbols the Short Hunter / Copy bots hold in this mode -> owner label. The AI engine leaves them alone. */
    fun botSymbols(mode: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        readObj("shorts_state_$mode.json")?.optJSONObject("open")?.keys()?.forEach { out[it] = "short hunter" }
        readObj("copy_state_$mode.json")?.optJSONObject("copies")?.let { c ->
            c.keys().forEach { k -> c.optJSONObject(k)?.optString("symbol")?.takeIf { it.isNotEmpty() }?.let { out[it] = "copy" } }
        }
        return out
    }

    // ---- signal results (every signal, taken or not)
    @Synchronized fun signalLog(): List<SigRec> = readArr("signal_log.json")?.let { a ->
        (0 until a.length()).mapNotNull { runCatching { SigRec.from(a.getJSONObject(it)) }.getOrNull() }
    } ?: emptyList()

    @Synchronized fun saveSignalLog(l: List<SigRec>) = writeArr("signal_log.json", JSONArray(l.takeLast(4000).map { it.toJson() }))

    @Synchronized fun clearSignalLog() = writeArr("signal_log.json", JSONArray())

    // ---- status + log
    var lastCycle: Long
        get() = prefs.getLong("lastCycle", 0)
        set(v) = prefs.edit().putLong("lastCycle", v).apply()
    var nextCycle: Long
        get() = prefs.getLong("nextCycle", 0)
        set(v) = prefs.edit().putLong("nextCycle", v).apply()
    var lastError: String?
        get() = prefs.getString("lastError", null)
        set(v) = prefs.edit().putString("lastError", v).apply()

    @Synchronized fun log(level: String, msg: String) {
        val a = read("log.json")?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
        a.put(JSONObject().put("t", System.currentTimeMillis()).put("level", level).put("msg", msg))
        val start = maxOf(0, a.length() - 300)
        write("log.json", JSONArray((start until a.length()).map { a.get(it) }).toString())
    }

    @Synchronized fun logs(): List<LogLine> = read("log.json")?.let { s ->
        runCatching { JSONArray(s).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> LogLine(o.getLong("t"), o.getString("level"), o.getString("msg")) } } } }.getOrNull()
    }?.reversed() ?: emptyList()
}
