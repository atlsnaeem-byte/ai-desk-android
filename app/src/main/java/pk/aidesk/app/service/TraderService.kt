package pk.aidesk.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pk.aidesk.app.core.Binance
import pk.aidesk.app.core.BinanceException
import pk.aidesk.app.core.Config
import pk.aidesk.app.core.CopyBot
import pk.aidesk.app.core.Engine
import pk.aidesk.app.core.ManualDesk
import pk.aidesk.app.core.PumpHunter
import pk.aidesk.app.core.ShortHunter
import pk.aidesk.app.core.TaScanner
import pk.aidesk.app.core.UserError
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that keeps the scanner / auto-trader alive. An exact alarm wakes it at every
 * 15-minute bar close + 8 s; each wake runs one Engine.cycle() under a short wake lock.
 */
class TraderService : Service() {
    companion object {
        const val ACTION_START = "pk.aidesk.START"
        const val ACTION_CYCLE = "pk.aidesk.CYCLE"
        const val ACTION_STOP = "pk.aidesk.STOP"
        const val ACTION_REFRESH = "pk.aidesk.REFRESH_NOTIF"

        /** Bumped after every cycle so the UI can reload. */
        val ticks = MutableStateFlow(0L)
        /** Bumped after every copy-trading poll. */
        val copyTicks = MutableStateFlow(0L)
        const val ACTION_COPY = "pk.aidesk.COPY"
        const val ACTION_MANUAL = "pk.aidesk.MANUAL"
        const val ACTION_PUMPS = "pk.aidesk.PUMPS"
        /** Bumped after every manual-order watch. */
        val manualTicks = MutableStateFlow(0L)
        /** Bumped after every BTC 5m recorder step. */
        val btcTicks = MutableStateFlow(0L)
        /** Bumped after every BTC 5m structure-AI round step. */
        val structTicks = MutableStateFlow(0L)
        val running = MutableStateFlow(false)
        @Volatile var instance: TraderService? = null

        fun bgEnabled(ctx: Context) = ctx.getSharedPreferences("aidesk", 0).getBoolean("bgService", true)

        fun setBgEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences("aidesk", 0).edit().putBoolean("bgService", on).putBoolean("bgAsked", true).apply()
            if (on) start(ctx) else if (running.value) stop(ctx)
        }

        /** Starts the background service unless the user chose "No" (then the app only works while it is open). */
        fun start(ctx: Context, action: String = ACTION_START) {
            if (!bgEnabled(ctx)) return
            val i = Intent(ctx, TraderService::class.java).setAction(action)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, TraderService::class.java).setAction(ACTION_STOP))
        }

        fun nextBarTime(now: Long = System.currentTimeMillis()) = (now / Config.BAR_MS + 1) * Config.BAR_MS + 8_000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)
    private lateinit var engine: Engine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        engine = Engine(this)
        Notifier.channels(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.STATUS_ID, Notifier.status(this, engine), type)
        instance = this
        running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                cancelAlarm()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> Notifier.updateStatus(this, engine)
            ACTION_CYCLE -> runCycle()
            ACTION_COPY -> { copyBot.resetBaseline(); ensureCopyLoop() }
            ACTION_MANUAL -> ensureManualLoop()
            ACTION_PUMPS -> { ensurePumpLoop(); runCycle() }
            else -> { // start / restart: catch up if the last scan is from an earlier bar
                val bar = System.currentTimeMillis() / Config.BAR_MS * Config.BAR_MS
                if (engine.store.lastCycle < bar + 8_000) runCycle() else schedule()
            }
        }
        ensureCopyLoop()
        ensureManualLoop()
        ensurePumpLoop()
        ensureStructLoop()
        ensureStratLoop()
        return START_STICKY
    }

    // ------------------------------------------------------------------ manual orders (Trade tab)
    private var manualJob: Job? = null
    private var manualWl: PowerManager.WakeLock? = null

    /** Every 10 s while manual orders are active: fills, missing SL/TP, expiry, closes. Stops when there is nothing to watch. */
    fun ensureManualLoop() {
        if (manualJob?.isActive == true) return
        manualJob = scope.launch {
            val desk = pk.aidesk.app.core.ManualDesk(engine.store)
            var idle = 0
            var lastErr: String? = null
            while (isActive) {
                val c = engine.cfg()
                if (desk.active(c.mode).isEmpty()) {
                    manualWl?.let { if (it.isHeld) it.release() }
                    if (++idle > 3) break
                    delay(20_000); continue
                }
                idle = 0
                val wl = manualWl ?: (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aidesk:manual").apply { setReferenceCounted(false) }.also { manualWl = it }
                wl.acquire(5 * 60_000L)
                try {
                    val api = engine.client(c)
                    val ev = desk.watch(c, api)
                    ev.forEach { Notifier.event(this@TraderService, engine, it, cat = "manual") }
                    if (ev.isNotEmpty()) ticks.value = System.currentTimeMillis()
                    manualTicks.value = System.currentTimeMillis()
                    lastErr = null
                } catch (e: Exception) {
                    val m = (e as? BinanceException)?.msg ?: e.message ?: e.javaClass.simpleName
                    if (m != lastErr) engine.store.log("ERROR", "Manual orders: $m")
                    lastErr = m
                }
                delay(10_000)
            }
            manualWl?.let { if (it.isHeld) it.release() }
        }
    }

    fun runCycle() {
        if (!busy.compareAndSet(false, true)) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aidesk:cycle").apply { acquire(3 * 60_000L) }
        schedule() // schedule first, so a crash in the cycle never stops the loop
        scope.launch {
            try {
                var lastErr: Exception? = null
                for (attempt in 0 until 3) { // offline / Binance hiccup: retry after 30 s, 60 s
                    try {
                        val events = engine.cycle()
                        events.forEach { Notifier.event(this@TraderService, engine, it, cat = "signals") }
                        lastErr = null
                        break
                    } catch (e: UserError) {
                        lastErr = e; break
                    } catch (e: Exception) {
                        lastErr = e
                        Thread.sleep(30_000L * (attempt + 1))
                    }
                }
                lastErr?.let {
                    val msg = it.message ?: it.javaClass.simpleName
                    engine.store.lastError = msg
                    engine.store.log("ERROR", "cycle: $msg")
                    if (it is UserError) Notifier.problem(this@TraderService, msg)
                }
                runShortHunter()
                runPumpHunter()
                runTaScan()
                runGold()
            } finally {
                ticks.value = System.currentTimeMillis()
                Notifier.updateStatus(this@TraderService, engine)
                busy.set(false)
                if (wl.isHeld) wl.release()
            }
        }
    }

    /** Short Hunter: entries + exits once per 15-minute cycle (entry window is 6 h, exits are SL/TP orders on Binance). */
    private fun runShortHunter() {
        try {
            val c = engine.cfg()
            val api = engine.client(c)
            api.syncTime()
            val sh = ShortHunter(engine.store)
            val sc = sh.cfg()
            if (!sc.enabled && !sc.pumpAuto && !sc.annAuto && sh.state(c.mode).open.isEmpty()) return
            sh.step(c.mode, api) { api.equity() }.forEach { Notifier.event(this, engine, it, cat = "short") }
        } catch (e: Exception) {
            engine.store.log("ERROR", "Short hunter: ${(e as? BinanceException)?.msg ?: e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Gold signal (XAUUSDT Asian-range breakout): alert only, once per signal. */
    private fun runGold() {
        try {
            val s = pk.aidesk.app.core.Gold.scan(pub)
            if (s.status != "SIGNAL" || s.result != null) return
            val st = engine.store
            if (st.readObj("gold_notified.json")?.optLong("signalTime") == s.signalTime) return   // one alert per signal
            st.writeObj("gold_notified.json", org.json.JSONObject().put("signalTime", s.signalTime))
            Notifier.infoCat("gold", this, "Gold ${s.sideName} · XAUUSDT 15m",
                    "Asian-range breakout: entry ${Engine.fmt(s.entry)}, stop ${Engine.fmt(s.sl)} (range middle), exit 01:00 PKT. Signal only, not proven.", loud = true)
        } catch (e: Exception) {
            engine.store.log("ERROR", "Gold signal: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Pump Hunter: exits + daily base-breakout scan/entries once per 15-minute cycle (the scan itself runs once a day). */
    private fun runPumpHunter() {
        try {
            val c = engine.cfg()
            val ph = PumpHunter(engine.store)
            val pc = ph.cfg()
            if (!pc.enabled && ph.state(c.mode).open.isEmpty()) {
                // signals only: keep the daily breakout list fresh for the Signals tab
                runCatching { ph.breakouts(Binance().also { it.syncTime() }) }
                return
            }
            val api = engine.client(c)
            api.syncTime()
            ph.step(c.mode, api) { api.equity() }.forEach { Notifier.event(this, engine, it, cat = "pump") }
            ensurePumpLoop()
        } catch (e: Exception) {
            engine.store.log("ERROR", "Pump hunter: ${(e as? BinanceException)?.msg ?: e.message ?: e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ BTC 5m recorder (every closed minute)
    private var btcJob: Job? = null
    private var btcWl: PowerManager.WakeLock? = null

    /** While the BTC 5m recorder is on: 3 s after every 1m close, record the round's checkpoint, auto paper bet on the first
     *  confirmed call, alert, and settle finished rounds. Never places a live bet (Binance Prediction has no API for it). */
    fun ensureBtc5mLoop() {
        if (btcJob?.isActive == true) return
        btcJob = scope.launch {
            val book = pk.aidesk.app.core.Btc5mBook(engine.store)
            val api = Binance()
            var synced = 0L
            var lastErr: String? = null
            while (isActive) {
                val cfg = book.cfg()
                if (!cfg.record) break
                val wl = btcWl ?: (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aidesk:btc5m").apply { setReferenceCounted(false) }.also { btcWl = it }
                wl.acquire(2 * 60_000L)
                try {
                    if (System.currentTimeMillis() - synced > 600_000) { api.syncTime(); synced = System.currentTimeMillis() }
                    val call = pk.aidesk.app.core.Btc5m.scan(api, pk.aidesk.app.core.Btc5m.model(this@TraderService))
                    if (book.record(call) && cfg.alerts) {
                        val hw = call.histWin?.let { "${(it * 100).toInt()}%" } ?: "-"
                        Notifier.infoCat("predict", this@TraderService, "BTC 5m CONFIRMED ${call.side} · ${call.e}:00 in",
                            "Backtest $hw on similar rounds · ${(call.leftMs / 1000)}s left · " +
                                (if (cfg.mode == "paper") "paper bet recorded" else "LIVE: bet in Binance only if the ${call.side} price is below ${call.histWin?.let { (it * 100 - 5).toInt() } ?: "-"}¢"),
                            loud = true)
                    }
                    book.settle(api)
                    btcTicks.value = System.currentTimeMillis()
                    lastErr = null
                } catch (e: Exception) {
                    val m = (e as? BinanceException)?.msg ?: e.message ?: e.javaClass.simpleName
                    if (m != lastErr) engine.store.log("ERROR", "BTC 5m recorder: $m")
                    lastErr = m
                }
                val now = api.nowMs()
                delay((60_000L - now % 60_000L) + 3_000L)     // 3 s after the next minute closes
            }
            btcWl?.let { if (it.isHeld) it.release() }
        }
    }

    // ------------------------------------------------------------------ BTC 5m structure AI (one call at every round start)
    private val structJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val structWls = java.util.concurrent.ConcurrentHashMap<String, PowerManager.WakeLock>()

    /** While auto is on: 3 s after every 5-minute boundary, read HH/HL/LH/LL on 1m/5m/15m, make the round's call, auto paper
     *  bet it (PAPER mode), alert, and settle finished rounds. LIVE mode alerts only: Binance Prediction has no order API. */
    /** one loop per Predict market (BTC 5m, ETH 5m, BTC 15m, ETH 15m) whose Auto is on */
    fun ensureStructLoop() {
        for (mk in pk.aidesk.app.core.Btc5mStruct.MARKETS) {
            if (structJobs[mk.id]?.isActive == true) continue
            if (!pk.aidesk.app.core.Btc5mStructBook(engine.store, mk).cfg().auto) continue
            structJobs[mk.id] = structLoop(mk)
        }
    }

    private fun structLoop(mk: pk.aidesk.app.core.Btc5mStruct.Mk): Job {
        return scope.launch {
            val book = pk.aidesk.app.core.Btc5mStructBook(engine.store, mk)
            val api = Binance()
            var synced = 0L
            var lastErr: String? = null
            while (isActive) {
                val cfg = book.cfg()
                if (!cfg.auto) break
                val wl = structWls.getOrPut(mk.id) { (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aidesk:pred_${mk.id}").apply { setReferenceCounted(false) } }
                wl.acquire(mk.round + 2 * 60_000L)
                try {
                    if (System.currentTimeMillis() - synced > 600_000) { api.syncTime(); synced = System.currentTimeMillis() }
                    val m = pk.aidesk.app.core.Btc5mStruct.model(this@TraderService, mk)
                    val R = mk.round
                    val ph = api.nowMs() % R
                    // early mode: the call for the NEXT round is made 10 s before it starts, from the still-forming candles
                    val pred0 = engine.store.keys("prediction")?.let { (k, s) -> pk.aidesk.app.core.PredictionApi(k, s) }
                    // early mode, 1 min before: find the next round's market now (list / timeline / search), so at -10 s only
                    // the quote + order remain; the result is recorded (when it was listed, how it was found)
                    val lead = cfg.leadSec * 1000L
                    if (cfg.early && ph in (R - 75_000L) until (R - lead - 5_000L)) {
                        if (pred0 != null) runCatching { book.prefetchMarket((api.nowMs() / R + 1) * R, pred0, api.nowMs()) }
                        structTicks.value = System.currentTimeMillis()
                        delay((R - api.nowMs() % R - lead).coerceAtLeast(0L))
                        continue
                    }
                    val nextRound = if (cfg.early && ph >= R - lead - 5_000L) (api.nowMs() / R + 1) * R else null
                    val call = if (cfg.early && nextRound == null) null
                               else pk.aidesk.app.core.Btc5mStruct.scan(api, m, cfg.thr, nextRound, mk)
                    val isNew = call != null && book.recordRound(call)
                    val pred = engine.store.keys("prediction")?.let { (k, s) -> pk.aidesk.app.core.PredictionApi(k, s) }
                    if (isNew && call != null) {
                        // same decision path for PAPER and LIVE: quote -> EV gate -> risk -> execute; re-quote every 8 s in the window
                        // a network error inside an attempt must not drop the round: it is retried like a failed quote
                        fun attempt(c2: pk.aidesk.app.core.Btc5mStructBook.Cfg) = try { book.tryEnter(call, c2, pred, api.nowMs()) }
                            catch (e: Exception) { pk.aidesk.app.core.Btc5mStructBook.Step(false, "error: ${(e as? BinanceException)?.msg ?: e.message}") }
                        var step = attempt(cfg)
                        // early: retry only until the round starts; at round start: until the entry window closes
                        while (!step.done && (if (cfg.early) api.nowMs() < call.roundStart else api.nowMs() - call.roundStart < cfg.windowSec * 1000L)) {
                            book.setStatus(step.text); structTicks.value = System.currentTimeMillis()
                            delay(if (cfg.early) 1_500L else 8_000L)
                            step = attempt(book.cfg())
                        }
                        book.setStatus("${hhmmUtc(call.roundStart)} ${call.side} · ${step.text}")
                        if (cfg.alerts && !step.quiet && (step.placed || cfg.mode == "live"))
                            Notifier.infoCat("predict", this@TraderService, "${mk.label} ${call.side} · win rate ${(call.winP * 100).toInt()}%" +
                                (if (step.placed) " · ${cfg.mode.uppercase()} BET" else " · skipped"), step.text, loud = cfg.mode == "live" && step.placed)
                    }
                    // LIVE: claim winnings of ended rounds once per round (PredictAI 5m maintenance)
                    // settle the round that just ended as soon as its last 1m bar is final (round end + 6 s), not 5 min later
                    run {
                        val R0 = mk.round
                        val wait = (api.nowMs() / R0) * R0 + 6_000L - api.nowMs()
                        if (wait in 1..60_000) { structTicks.value = System.currentTimeMillis(); delay(wait) }
                    }
                    runCatching { book.settle(api) }
                    if (cfg.mode == "live" && pred != null) runCatching { pred.claimAll(pred.wallet()) }
                    structTicks.value = System.currentTimeMillis()
                    lastErr = null
                } catch (e: Exception) {
                    val msg = (e as? BinanceException)?.msg ?: e.message ?: e.javaClass.simpleName
                    if (msg != lastErr) engine.store.log("ERROR", "${mk.label} predict: $msg")
                    lastErr = msg
                    delay(if (cfg.early) 3_000L else 15_000L); continue         // retry within the same round
                }
                val now = api.nowMs()
                val R = mk.round
                val toBoundary = R - now % R
                delay(if (book.cfg().early) (toBoundary - 60_000L).let { if (it < 1_000L) it + R else it } else toBoundary + 3_000L)
            }
            structWls[mk.id]?.let { if (it.isHeld) it.release() }
        }
    }

    private fun hhmmUtc(t: Long) = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(pk.aidesk.app.core.Engine.PKT)
        .format(java.time.Instant.ofEpochMilli(t))

    // ------------------------------------------------------------------ strategies (Strategies tab): auto-trade loop
    private var stratJob: Job? = null
    private var stratWl: PowerManager.WakeLock? = null

    /**
     * Every 5-minute close + 6 s while the service runs: the 15m strategies are scanned and recorded and every new signal
     * gets an alert; strategies with auto-trade on are also traded (exit per the Strategy tab setting); time exits.
     */
    fun ensureStratLoop() {
        if (stratJob?.isActive == true) return
        stratJob = scope.launch {
            val book = pk.aidesk.app.core.StratBook(engine.store)
            val api = Binance()
            var synced = 0L
            while (isActive) {
                val wl = stratWl ?: (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aidesk:strats").apply { setReferenceCounted(false) }.also { stratWl = it }
                wl.acquire(3 * 60_000L)
                try {
                    if (System.currentTimeMillis() - synced > 600_000) { api.syncTime(); synced = System.currentTimeMillis() }
                    val c = engine.cfg()
                    val ev = book.step(c, api) { engine.client(c) }
                    ev.forEach { Notifier.event(this@TraderService, engine, it, cat = "strats") }
                    if (ev.isNotEmpty()) { ticks.value = System.currentTimeMillis(); ensureManualLoop() }
                } catch (e: Exception) {
                    engine.store.log("ERROR", "Strategies: ${(e as? BinanceException)?.msg ?: e.message ?: e.javaClass.simpleName}")
                }
                if (stratWl?.isHeld == true) stratWl?.release()
                val now = api.nowMs()
                delay((300_000L - now % 300_000L) + 6_000L)
            }
        }
    }

    // ------------------------------------------------------------------ pump hunter: listing-news loop
    private var pumpJob: Job? = null
    private var pumpWl: PowerManager.WakeLock? = null

    /** Every 15 s while an Upbit / Binance listing rule is on: a fresh post is traded within seconds, not at the next 15-min cycle. */
    fun ensurePumpLoop() {
        if (pumpJob?.isActive == true) return
        pumpJob = scope.launch {
            val ph = PumpHunter(engine.store)
            var lastErr: String? = null
            var synced = 0L
            while (isActive) {
                if (!ph.cfg().newsOn) break
                val wl = pumpWl ?: (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aidesk:pumps").apply { setReferenceCounted(false) }.also { pumpWl = it }
                wl.acquire(5 * 60_000L)
                try {
                    val c = engine.cfg()
                    val api = engine.client(c)
                    if (System.currentTimeMillis() - synced > 300_000) { api.syncTime(); synced = System.currentTimeMillis() }
                    val ev = ph.newsStep(c.mode, api) { api.equity() }
                    ev.forEach { Notifier.event(this@TraderService, engine, it, cat = "pump") }
                    if (ev.isNotEmpty()) ticks.value = System.currentTimeMillis()
                    lastErr = null
                } catch (e: Exception) {
                    val m = (e as? BinanceException)?.msg ?: e.message ?: e.javaClass.simpleName
                    if (m != lastErr) engine.store.log("ERROR", "Pump hunter news loop: $m")
                    lastErr = m
                }
                delay(15_000)
            }
            pumpWl?.let { if (it.isHeld) it.release() }
        }
    }

    /** TA-course setups (4h RSI divergence / range fade) on the scanner's coins: signals only, never trades by itself. */
    private fun runTaScan() {
        try {
            val c = engine.cfg()
            val api = Binance().also { it.syncTime() }
            val ta = TaScanner(engine.store)
            val res = ta.step(api, c.symbols)
            res.events.forEach { Notifier.event(this, engine, it, cat = "course") }
            if (!ta.auto || (res.opened.isEmpty() && res.timeExits.isEmpty())) return
            val trade = engine.client(c).also { it.syncTime() }
            val desk = ManualDesk(engine.store)
            // time exit after 30 four-hour bars: close the course trade still open on that coin
            for (sg in res.timeExits) {
                if (desk.active(c.mode).none { it.symbol == sg.symbol && it.setup.startsWith("Course") }) continue
                runCatching { desk.closeSymbol(c, trade, sg.symbol) }
                    .onSuccess { msg -> msg?.let { Notifier.event(this, engine, pk.aidesk.app.core.Event("close", "Course trade closed (time exit)", it, false, sg.symbol), cat = "course") } }
                    .onFailure { engine.store.log("ERROR", "Course time exit ${sg.symbol}: ${(it as? BinanceException)?.msg ?: it.message}") }
            }
            // new setups: MARKET order with the signal's SL / TP, sized by Settings > risk per trade and leverage
            val busy = engine.store.botSymbols(c.mode).keys + engine.store.state(c.mode, c.paperStartEquity).open.keys
            for (sg in res.opened) {
                if (sg.symbol in busy) { engine.store.log("INFO", "Course ${sg.symbol}: skip, coin already held by another bot"); continue }
                try {
                    val msg = desk.place(c, trade, pk.aidesk.app.core.Ticket(sg.symbol, sg.side, "MARKET", 0.0, sg.sl, sg.tp, c.riskPct,
                        c.leverage, 24, "Course ${sg.tf}: ${sg.title}", ladder = true))
                    Notifier.event(this, engine, pk.aidesk.app.core.Event("open", "Course trade opened: ${sg.side} ${sg.symbol}", msg, true, sg.symbol), cat = "course")
                } catch (e: Exception) {
                    val m = (e as? BinanceException)?.msg ?: e.message ?: e.javaClass.simpleName
                    engine.store.log("ERROR", "Course trade ${sg.symbol}: $m")
                    Notifier.event(this, engine, pk.aidesk.app.core.Event("error", "Course trade not opened: ${sg.symbol}", m, false, sg.symbol), cat = "course")
                }
            }
            ensureManualLoop()
        } catch (e: Exception) {
            engine.store.log("ERROR", "TA scan: ${(e as? BinanceException)?.msg ?: e.message ?: e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ copy trading loop
    private var copyJob: Job? = null
    private val copyBot by lazy { CopyBot(engine.store) }
    private var copyWl: PowerManager.WakeLock? = null

    /** Started on service start and whenever copy settings change. Polls leaders every pollSec while copying is on. */
    fun ensureCopyLoop() {
        if (copyJob?.isActive == true) return
        copyJob = scope.launch {
            var idle = 0
            while (isActive) {
                val cc = copyBot.cfg()
                val mode = engine.store.config().mode
                val active = cc.enabled || copyBot.state(mode).copies.isNotEmpty()
                if (!active) {
                    copyWl?.let { if (it.isHeld) it.release() }
                    if (++idle > 3) break                 // nothing to do: stop; settings changes restart it
                    delay(30_000); continue
                }
                idle = 0
                holdCopyWakeLock()
                try {
                    val c = engine.cfg()
                    val api = engine.client(c)
                    if (System.currentTimeMillis() - copySynced > 300_000) { api.syncTime(); copySynced = System.currentTimeMillis() }
                    val ev = copyBot.step(c.mode, api, pub)
                    ev.forEach { Notifier.event(this@TraderService, engine, it, cat = "copy") }
                    if (ev.isNotEmpty()) ticks.value = System.currentTimeMillis()
                    copyTicks.value = System.currentTimeMillis()
                } catch (e: Exception) {
                    val m = (e as? BinanceException)?.msg ?: e.message ?: e.javaClass.simpleName
                    if (m != lastCopyErr) engine.store.log("ERROR", "Copy: $m")
                    lastCopyErr = m
                }
                delay(cc.pollSec.coerceIn(5, 120) * 1000L)
            }
            copyWl?.let { if (it.isHeld) it.release() }
        }
    }

    private val pub = Binance()
    private var copySynced = 0L
    private var lastCopyErr: String? = null

    private fun holdCopyWakeLock() {
        val wl = copyWl ?: (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aidesk:copy").apply { setReferenceCounted(false) }.also { copyWl = it }
        wl.acquire(10 * 60_000L)   // renewed every poll; lapses by itself if the loop dies
    }

    fun resetCopyBaseline() = copyBot.resetBaseline()

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        this, 1, Intent(this, AlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun schedule() {
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        val at = nextBarTime()
        engine.store.nextCycle = at
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent())
        else
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent())
    }

    private fun cancelAlarm() {
        (getSystemService(ALARM_SERVICE) as AlarmManager).cancel(alarmIntent())
    }

    override fun onDestroy() {
        copyWl?.let { if (it.isHeld) it.release() }
        manualWl?.let { if (it.isHeld) it.release() }
        pumpWl?.let { if (it.isHeld) it.release() }
        instance = null
        running.value = false
        scope.cancel()
        super.onDestroy()
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val svc = TraderService.instance
        if (svc != null) svc.runCycle() else TraderService.start(context, TraderService.ACTION_CYCLE)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Engine(context).store.setupDone) TraderService.start(context)
    }
}

/** Buttons on notifications: Close a position, Stop auto. */
class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val CLOSE = "pk.aidesk.CLOSE"
        const val STOP_AUTO = "pk.aidesk.STOP_AUTO"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val engine = Engine(context)
        when (intent.action) {
            STOP_AUTO -> {
                engine.store.saveConfig(engine.store.config().copy(auto = false))
                engine.store.log("INFO", "AUTO stopped from notification")
                Notifier.info(context, "Auto-trading stopped", "Open positions keep their TP/SL on Binance.")
                TraderService.ticks.value = System.currentTimeMillis()
                TraderService.instance?.let { Notifier.updateStatus(it, engine) }
            }
            CLOSE -> {
                val sym = intent.getStringExtra("symbol") ?: return
                val pending = goAsync()
                Thread {
                    try {
                        val msg = engine.manualClose(sym)
                        Notifier.info(context, "Closed $sym", msg)
                    } catch (e: Exception) {
                        Notifier.info(context, "Close failed: $sym", e.message ?: e.javaClass.simpleName)
                    } finally {
                        TraderService.ticks.value = System.currentTimeMillis()
                        pending.finish()
                    }
                }.start()
            }
        }
    }
}
