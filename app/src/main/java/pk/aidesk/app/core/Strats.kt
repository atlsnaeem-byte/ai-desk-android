package pk.aidesk.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/** How strategy / course trades exit: 5-target ladder (1/5 at each) or the whole position at TP1; target spacing in R. */
data class ExitCfg(val tp1Only: Boolean = false, val step: Double = 1.0) {
    val label get() = (if (tp1Only) "Close all at TP1" else "5 targets, 1/5 at each") + " · TP1 = ${if (step == 0.5) "0.5" else "1"}R"

    companion object {
        fun load(store: Store): ExitCfg = store.readObj("strat_cfg.json")?.optJSONObject("_exit")?.let {
            ExitCfg(it.optBoolean("tp1Only", false), if (it.optDouble("step", 1.0) == 0.5) 0.5 else 1.0)
        } ?: ExitCfg()

        fun save(store: Store, e: ExitCfg) {
            val o = store.readObj("strat_cfg.json") ?: JSONObject()
            o.put("_exit", JSONObject().put("tp1Only", e.tp1Only).put("step", e.step))
            store.writeObj("strat_cfg.json", o)
        }
    }
}

/** Market structure scanner settings (strat_cfg.json "_struct"). top 0 = all USDT perps. */
data class StructCfg(val k: Int = 10, val setups: Set<String> = setOf("BOS", "CHOCH"), val long: Boolean = true, val short: Boolean = true,
                     val filter4h: Boolean = true, val top: Int = 0) {
    fun toJson(): JSONObject = JSONObject().put("k", k).put("setups", JSONArray(setups.toList())).put("long", long).put("short", short)
        .put("filter4h", filter4h).put("top", top)

    companion object {
        fun from(o: JSONObject?): StructCfg {
            if (o == null) return StructCfg()
            val d = StructCfg()
            return StructCfg(o.optInt("k", d.k).takeIf { it in listOf(3, 5, 10) } ?: d.k,
                o.optJSONArray("setups")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: d.setups,
                o.optBoolean("long", true), o.optBoolean("short", true), o.optBoolean("filter4h", true), o.optInt("top", 0))
        }
    }
}

/** One strategy in the Strategies tab (dropdown). */
class StratInfo(val id: String, val name: String, val market: String, val rules: String, val backtest: String, val targets: String)

/** A recorded strategy signal and how far it went: targets hit before the stop (TP1..TP5 = 1R..5R). */
class StratSig(val strat: String, val id: String, val symbol: String, val side: String, val time: Long, val entry: Double, val sl: Double,
               val tps: List<Double>, var tpHit: Int = 0, var result: String? = null, var traded: Boolean = false) {
    fun toJson() = JSONObject().put("strat", strat).put("id", id).put("symbol", symbol).put("side", side).put("time", time).put("entry", entry)
        .put("sl", sl).put("tps", JSONArray(tps)).put("tpHit", tpHit).put("result", result ?: JSONObject.NULL).put("traded", traded)

    companion object {
        fun from(o: JSONObject) = StratSig(o.getString("strat"), o.getString("id"), o.getString("symbol"), o.getString("side"), o.getLong("time"),
            o.getDouble("entry"), o.getDouble("sl"), o.getJSONArray("tps").let { a -> (0 until a.length()).map { a.getDouble(it) } },
            o.optInt("tpHit"), if (o.isNull("result")) null else o.optString("result"), o.optBoolean("traded"))
    }
}

/**
 * Strategies registry, per-strategy auto-trade switch, signal record and the auto-trader.
 * Auto trades go through ManualDesk as MARKET orders with the strategy's stop and a 5-target ladder (1/5 closes at each
 * of 1R..5R); the Positions tab can close part / all, DCA or add margin at any time.
 */
class StratBook(val store: Store) {
    companion object {
        const val RBF = "rbf_gold"; const val ASIA = "asia_gold"; const val COURSE = "course_4h"; const val STRUCT = "structure_15m"
        private val LOCK = Any()
        /** 4h candles per coin for the structure trend filter, refreshed after each 4h close. */
        private val h4Cache = java.util.concurrent.ConcurrentHashMap<String, Candles>()
        private var universeCache: Pair<Long, List<String>>? = null
        @Volatile var lastStructBar = 0L
        val ALL = listOf(
            StratInfo(RBF, "Gold 15m · Retest + Breaker + FVG", "XAUUSDT · 15m setup, 5m entry",
                "Ahmed Tlogs '15m Gold Concept'. A 15m close through a swing low/high turns it into resistance/support (retest). " +
                    "Breaker = last opposite candle at that swing. An FVG in the move near the level is required. When price comes back " +
                    "into the FVG, the first 5m candle in the trade direction = entry. Stop just beyond the FVG candles.",
                Rbf.BACKTEST, "TP1–TP5 hit before the stop (2026): 49% · 31% · 23% · 17% · 13%"),
            StratInfo(ASIA, "Gold 15m · Asian-range breakout", "XAUUSDT · 15m",
                "Range 05:00–12:00 PKT. The first 15m close outside it before 17:00 PKT, in the 4h trend direction. Stop = middle of the range, " +
                    "time exit 01:00 PKT.",
                Gold.BACKTEST, "TP1–TP5 hit before the stop (2026): 57% · 35% · 12% · 7% · 6%"),
            StratInfo(COURSE, "Coins 4h · TA-course setups", "20 scanner coins · 4h",
                "RSI divergence or range fade with S/R confluence from the TA course; stop from the setup, time exit after 30 four-hour bars.",
                "Backtest 2024–26: about 0R per trade after costs (thin edge).", "TP1–TP5 hit before the stop (2024–26): 46% · 25% · 14% · 9% · 6%"),
            StratInfo(STRUCT, "Market structure 15m · HH/HL · LH/LL", "All USDT perps (or top 20/50/100) · 15m",
                "Swing highs/lows (k bars each side). Uptrend = HH + HL, downtrend = LH + LL. BOS: first close through the last swing in " +
                    "the trend direction. CHOCH: first close through the last LH (in a downtrend → long) or HL (in an uptrend → short). " +
                    "PULL: a new HL / LH confirmed in the trend. Stop at the opposite swing, time exit 24 h. Optional 4h EMA50/200 filter.",
                Structure.BACKTEST, "BTC close-all at TP1 (1R), k=5: win 46–56%, −0.08 to −0.32R per trade after costs"),
        )
        fun info(id: String) = ALL.first { it.id == id }
    }

    fun auto(id: String): Boolean = if (id == COURSE) TaScanner(store).auto else store.readObj("strat_cfg.json")?.optJSONObject(id)?.optBoolean("auto", false) ?: false
    fun setAuto(id: String, on: Boolean) {
        if (id == COURSE) { TaScanner(store).auto = on; return }
        val o = store.readObj("strat_cfg.json") ?: JSONObject()
        o.put(id, (o.optJSONObject(id) ?: JSONObject()).put("auto", on))
        store.writeObj("strat_cfg.json", o)
    }
    fun anyAuto() = auto(RBF) || auto(ASIA) || auto(STRUCT)

    fun signals(strat: String? = null): List<StratSig> = synchronized(LOCK) {
        (store.readArr("strat_log.json")?.let { a -> (0 until a.length()).mapNotNull { runCatching { StratSig.from(a.getJSONObject(it)) }.getOrNull() } }
            ?: emptyList()).filter { strat == null || it.strat == strat }
    }
    /** Keeps the newest 600 signals per strategy (the structure scanner makes many; gold records must not be pushed out). */
    private fun saveSignals(l: List<StratSig>) = synchronized(LOCK) {
        val keep = l.groupBy { it.strat }.values.flatMap { g -> g.sortedBy { it.time }.takeLast(600) }
        store.writeArr("strat_log.json", JSONArray(keep.sortedBy { it.time }.map { it.toJson() }))
    }

    // ------------------------------------------------------------------ market structure 15m (many coins)
    fun structCfg(): StructCfg = StructCfg.from(store.readObj("strat_cfg.json")?.optJSONObject("_struct"))
    fun saveStructCfg(s: StructCfg) {
        val o = store.readObj("strat_cfg.json") ?: JSONObject()
        store.writeObj("strat_cfg.json", o.put("_struct", s.toJson()))
    }
    /** Last scan: time, coins, signals in the last 24 h, errors. */
    fun structLast(): JSONObject? = store.readObj("strat_cfg.json")?.optJSONObject("_structLast")

    private fun universe(pub: Binance, top: Int, now: Long): List<String> {
        val u = universeCache?.takeIf { now - it.first < 3_600_000L }?.second
            ?: pub.usdtPerps().sortedByDescending { it.volume }.map { it.symbol }.also { universeCache = now to it }
        return if (top <= 0) u else u.take(top)
    }

    /**
     * Scan every coin of the universe on closed 15m candles: entries of the last 24 h (open ones followed to now) go into the
     * record. 6 parallel requests; 4h candles only for coins with a signal (cached until the next 4h close).
     */
    fun structScan(pub: Binance, now: Long = pub.nowMs()): List<StratSig> {
        val cfg = structCfg()
        val coins = universe(pub, cfg.top, now)
        val since = now - 24 * 3_600_000L
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        var errors = 0
        val found = java.util.Collections.synchronizedList(ArrayList<Structure.Entry>())
        try {
            val jobs = coins.map { sym ->
                pool.submit {
                    try {
                        val c = pub.candles(sym, "15m", 300)
                        var e = Structure.entries(sym, c, now, cfg.k, cfg.setups, cfg.long, cfg.short, null, since)
                        if (e.isNotEmpty() && cfg.filter4h) {
                            val h4 = h4Cache[sym]?.takeIf { it.openTime.last() + Structure.H4 > now }
                                ?: pub.candles(sym, "4h", 400).also { h4Cache[sym] = it }
                            e = Structure.entries(sym, c, now, cfg.k, cfg.setups, cfg.long, cfg.short, h4, since)
                        }
                        found.addAll(e)
                    } catch (x: Exception) { synchronized(found) { errors++ } }
                }
            }
            jobs.forEach { runCatching { it.get(90, java.util.concurrent.TimeUnit.SECONDS) } }
        } finally { pool.shutdownNow() }
        val sigs = found.map { StratSig(STRUCT, it.id, it.symbol, it.sideName, it.time, it.entry, it.sl, it.tps, it.tpHit, it.result) }
        record(sigs)
        val o = store.readObj("strat_cfg.json") ?: JSONObject()
        store.writeObj("strat_cfg.json", o.put("_structLast", JSONObject().put("time", now).put("coins", coins.size)
            .put("signals", sigs.size).put("open", sigs.count { it.result == null }).put("errors", errors)))
        lastStructBar = now / Structure.M15
        return sigs
    }

    /** Live record: signals so far, share that reached each target before the stop. */
    fun stats(strat: String): String? {
        val done = signals(strat).filter { it.result != null }
        if (done.isEmpty()) return null
        val pct = (1..5).joinToString(" · ") { k -> "${(done.count { it.tpHit >= k } * 100 / done.size)}%" }
        return "${done.size} finished signals · TP1–TP5 reached: $pct"
    }

    /** Merge fresh evaluations into the record (new signals added, open ones updated). */
    fun record(fresh: List<StratSig>) = synchronized(LOCK) {
        val l = signals().toMutableList()
        for (s in fresh) {
            val old = l.find { it.id == s.id }
            if (old == null) l += s else if (old.result == null) { old.tpHit = maxOf(old.tpHit, s.tpHit); old.result = s.result }
        }
        saveSignals(l)
    }

    private fun alertedIds(): List<String> = store.readObj("strat_cfg.json")?.optJSONArray("_alerted")
        ?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
    private fun saveAlerted(ids: List<String>) {
        val o = store.readObj("strat_cfg.json") ?: JSONObject()
        store.writeObj("strat_cfg.json", o.put("_alerted", JSONArray(ids)))
    }

    fun markTraded(id: String) = synchronized(LOCK) {
        val l = signals(); l.find { it.id == id }?.traded = true; saveSignals(l)
    }

    fun rbfSignals(v: Rbf.View) = v.entries.map {
        StratSig(RBF, it.id, Rbf.SYMBOL, it.sideName, it.entryTime, it.entry, it.sl, it.tps, it.tpHit, it.result)
    }

    fun asiaSignal(g: Gold.Sig, price: Double): StratSig? {
        if (g.status != "SIGNAL") return null
        val tps = Targets.ladder(g.sideName, g.entry, g.sl)
        val best = g.resultPx ?: price
        val hit = tps.count { (best - it) * g.side >= 0 }.coerceAtMost(5)
        return StratSig(ASIA, "asia-${g.signalTime}", Gold.SYMBOL, g.sideName, g.signalTime, g.entry, g.sl, tps, hit,
            when (g.result) { "SL" -> "SL"; "EXIT" -> "TIME"; else -> null })
    }

    /**
     * Auto-trader step (TraderService, every 5 minutes while a strategy's auto is on): fresh signals -> MARKET order with
     * stop + 5-target ladder; time exits for open strategy trades. Returns notifications.
     */
    fun step(c: Config, pub: Binance, trade: () -> Binance): List<Event> {
        val ev = ArrayList<Event>()
        val now = pub.nowMs()
        val fresh = ArrayList<Pair<String, StratSig>>()
        runCatching {
            val v = Rbf.scan(pub, now)
            val sigs = rbfSignals(v); record(sigs)
            sigs.filter { it.result == null && now - it.time < 10 * 60_000L }.forEach { fresh += RBF to it }
        }.onFailure { store.log("WARN", "Strategy RBF scan: ${it.message}") }
        var gold: Gold.Sig? = null
        runCatching {
            val g = Gold.scan(pub, now); gold = g
            asiaSignal(g, g.price)?.let { s -> record(listOf(s)); if (s.result == null && now - s.time < 20 * 60_000L) fresh += ASIA to s }
        }.onFailure { store.log("WARN", "Strategy Asian range scan: ${it.message}") }
        // market structure: once per 15m close (the loop wakes every 5 min)
        if (now / Structure.M15 != lastStructBar) runCatching {
            structScan(pub, now).filter { it.result == null && now - it.time < 20 * 60_000L }.forEach { fresh += STRUCT to it }
        }.onFailure { store.log("WARN", "Market structure scan: ${it.message}") }
        val desk = ManualDesk(store)
        val done = signals().filter { it.traded }.map { it.id }.toSet()
        var api: Binance? = null
        fun api() = api ?: trade().also { it.syncTime(); api = it }
        // every new signal gets one alert, auto-trade on or off (before 22 Nov the scanner only ran with auto on, so
        // 15m signals never showed up unless the Strategy tab was open)
        val alerted = alertedIds()
        val newOnes = fresh.filter { it.second.id !in alerted }
        if (newOnes.isNotEmpty()) {
            val ex = ExitCfg.load(store)
            for ((id, s) in newOnes) if (!auto(id) && id != STRUCT) {
                val t = Targets.ladder(s.side, s.entry, s.sl, ex.step)
                ev += Event("strat", "${info(id).name}: ${s.side} ${s.symbol}",
                    "Entry ${f(s.entry)} · stop ${f(s.sl)} · T1 ${f(t[0])} … T5 ${f(t[4])}. Auto-trade is off: open the Strategy tab to trade.", true, s.symbol)
            }
            // structure: many coins, so one grouped alert per scan
            val ms = newOnes.filter { it.first == STRUCT && !auto(STRUCT) }.map { it.second }
            if (ms.isNotEmpty())
                ev += Event("strat", "Market structure: ${ms.size} new signal${if (ms.size == 1) "" else "s"}",
                    ms.take(6).joinToString("\n") { "${it.side} ${it.symbol} @ ${f(it.entry)} · stop ${f(it.sl)}" } +
                        (if (ms.size > 6) "\n+${ms.size - 6} more" else "") + "\nAuto-trade is off: open the Strategy tab to trade.",
                    true, ms.first().symbol)
            saveAlerted((alerted + newOnes.map { it.second.id }).takeLast(1500))
        }
        val structOpen = desk.active(c.mode).count { it.setup.startsWith("Strategy Market structure") }
        var structNew = 0
        val held = desk.active(c.mode).map { it.symbol }.toSet()
        for ((id, s) in fresh) {
            if (!auto(id) || s.id in done) continue
            if (id == STRUCT && (s.symbol in held || structOpen + structNew >= c.maxOpen)) continue
            if (id == STRUCT) structNew++
            markTraded(s.id)                          // one attempt per signal, even if Binance refuses it
            val name = info(id).name
            try {
                if ((pub.markPrice(s.symbol) - s.sl) * (if (s.side == "LONG") 1 else -1) <= 0) { store.log("INFO", "$name: price already past the stop, skipped"); continue }
                val msg = desk.place(c, api(), Ticket(s.symbol, s.side, "MARKET", 0.0, s.sl, s.tps.last(), c.riskPct, c.leverage, 24,
                    "Strategy $name", ladder = true))
                ev += Event("open", "$name: ${s.side} ${s.symbol}", msg, true, s.symbol)
            } catch (e: Exception) {
                val m = (e as? BinanceException)?.msg ?: e.message ?: e.javaClass.simpleName
                store.log("ERROR", "$name trade ${s.symbol}: $m")
                ev += Event("error", "$name: trade not opened", m, false, s.symbol)
            }
        }
        // time exits: RBF after 24 h, Asian range at 01:00 PKT (20:00 UTC)
        for (p in desk.active(c.mode).filter { it.status == "open" && it.setup.startsWith("Strategy ") }) {
            val opened = p.filledMs ?: p.created
            val due = when {
                p.setup.contains("Retest") -> now - opened >= Rbf.HOLD5 * Rbf.M5
                p.setup.contains("Asian") -> { val day = opened / 86_400_000L * 86_400_000L; now >= day + Gold.FLAT_H * 3_600_000L }
                p.setup.contains("Market structure") -> now - opened >= Structure.HOLD * Structure.M15
                else -> false
            }
            if (!due) continue
            runCatching { desk.closeSymbol(c, api(), p.symbol) }
                .onSuccess { m -> m?.let { ev += Event("close", "Strategy trade closed (time exit)", it, false, p.symbol) } }
                .onFailure { store.log("ERROR", "Strategy time exit ${p.symbol}: ${(it as? BinanceException)?.msg ?: it.message}") }
        }
        return ev
    }

    fun f(v: Double) = Engine.fmt(v)
    fun r2(v: Double) = String.format(Locale.US, "%+.2f", v)
    fun rAt(s: StratSig, px: Double) = (if (s.side == "LONG") 1 else -1) * (px - s.entry) / abs(s.entry - s.sl)
}
