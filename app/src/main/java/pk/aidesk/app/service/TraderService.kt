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
import pk.aidesk.app.core.ShortHunter
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
        val running = MutableStateFlow(false)
        @Volatile var instance: TraderService? = null

        fun start(ctx: Context, action: String = ACTION_START) {
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
            else -> { // start / restart: catch up if the last scan is from an earlier bar
                val bar = System.currentTimeMillis() / Config.BAR_MS * Config.BAR_MS
                if (engine.store.lastCycle < bar + 8_000) runCycle() else schedule()
            }
        }
        ensureCopyLoop()
        return START_STICKY
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
                        events.forEach { Notifier.event(this@TraderService, engine, it) }
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
            if (!sh.cfg().enabled && sh.state(c.mode).open.isEmpty()) return
            sh.step(c.mode, api) { api.equity() }.forEach { Notifier.event(this, engine, it) }
        } catch (e: Exception) {
            engine.store.log("ERROR", "Short hunter: ${(e as? BinanceException)?.msg ?: e.message ?: e.javaClass.simpleName}")
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
                    ev.forEach { Notifier.event(this@TraderService, engine, it) }
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
