package pk.aidesk.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import pk.aidesk.app.MainActivity
import pk.aidesk.app.R
import pk.aidesk.app.core.Engine
import pk.aidesk.app.core.Event
import java.time.Instant
import java.time.ZonedDateTime

object Notifier {
    const val STATUS_ID = 1
    private const val CH_STATUS = "status"
    private const val CH_LOUD = "alerts_sound"
    private const val CH_QUIET = "alerts_silent"
    private var nextId = (((System.currentTimeMillis() / 1000) % 500_000) * 4).toInt() + 100

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Scanner status", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Always-on notification that keeps the scanner running"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_LOUD, "Signals and trades (win 60%+)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Plays a sound for strong signals and auto trades at or above your sound threshold"
            enableVibration(true)
            enableLights(true); lightColor = Color.GREEN
        })
        nm.createNotificationChannel(NotificationChannel(CH_QUIET, "Signals and trades (silent)", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Everything else: closed trades, weaker signals, skipped trades"
            setSound(null, null)
            enableVibration(false)
        })
    }

    private fun openApp(ctx: Context, req: Int, tab: String, symbol: String? = null, take: Boolean = false): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("tab", tab); symbol?.let { putExtra("symbol", it) }; putExtra("take", take)
        }
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(ctx: Context, req: Int, action: String, symbol: String? = null): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java).setAction(action).apply { symbol?.let { putExtra("symbol", it) } }
        return PendingIntent.getBroadcast(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun status(ctx: Context, engine: Engine): Notification {
        val c = engine.store.config()
        val st = engine.store.state(c.mode)
        val last = engine.store.lastCycle
        val err = engine.store.lastError
        val title = (if (c.auto) "AI Desk AUTO running" else "AI Desk scanner running") + "  -  " + c.mode.uppercase()
        val text = buildString {
            append(if (last > 0) "Last scan ${engine.barTimeText(last)}" else "Starting...")
            append("  -  ${st.open.size} open")
            st.halted?.let { append("  -  paused") }
            err?.let { append("  -  error: ${it.take(60)}") }
        }
        val b = NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(ctx, 1, "signals"))
        if (c.auto) b.addAction(0, "Stop auto", action(ctx, 2, ActionReceiver.STOP_AUTO))
        return b.build()
    }

    fun updateStatus(ctx: Context, engine: Engine) {
        runCatching { NotificationManagerCompat.from(ctx).notify(STATUS_ID, status(ctx, engine)) }
    }

    private fun quietNow(engine: Engine): Boolean {
        val c = engine.store.config()
        if (c.quietStart < 0 || c.quietEnd < 0 || c.quietStart == c.quietEnd) return false
        val h = ZonedDateTime.ofInstant(Instant.now(), Engine.PKT).hour
        return if (c.quietStart < c.quietEnd) h in c.quietStart until c.quietEnd else h >= c.quietStart || h < c.quietEnd
    }

    fun event(ctx: Context, engine: Engine, e: Event) {
        val loud = e.loud && !quietNow(engine)
        val id = nextId++
        val sym = e.symbol
        val b = NotificationCompat.Builder(ctx, if (loud) CH_LOUD else CH_QUIET)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(e.title)
            .setContentText(e.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(e.text))
            .setAutoCancel(true)
            .setGroup("aidesk_events")
            .setPriority(if (loud) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setColor(when (e.kind) { "open" -> 0xFF16C784.toInt(); "error" -> 0xFFEA3943.toInt(); else -> 0xFFF0B90B.toInt() })
        when (e.kind) {
            "open" -> {
                b.setContentIntent(openApp(ctx, id, "positions", sym))
                b.addAction(0, "View", openApp(ctx, id + 1, "positions", sym))
                if (sym != null) b.addAction(0, "Close", action(ctx, id + 2, ActionReceiver.CLOSE, sym))
                if (engine.store.config().auto) b.addAction(0, "Stop auto", action(ctx, id + 3, ActionReceiver.STOP_AUTO))
            }
            "signal" -> {
                b.setContentIntent(openApp(ctx, id, "signals", sym))
                b.addAction(0, "Take", openApp(ctx, id + 1, "signals", sym, take = true))
                b.addAction(0, "View", openApp(ctx, id + 2, "signals", sym))
            }
            "close" -> b.setContentIntent(openApp(ctx, id, "journal", sym))
            else -> b.setContentIntent(openApp(ctx, id, "signals"))
        }
        notify(ctx, id, b.build())
    }

    fun problem(ctx: Context, msg: String) = info(ctx, "AI Desk needs attention", msg, loud = true)

    fun info(ctx: Context, title: String, text: String, loud: Boolean = false) {
        val id = nextId++
        val n = NotificationCompat.Builder(ctx, if (loud) CH_LOUD else CH_QUIET)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, id, "signals"))
            .build()
        notify(ctx, id, n)
    }

    private fun notify(ctx: Context, id: Int, n: Notification) {
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) { // notification permission not granted
        }
    }
}
