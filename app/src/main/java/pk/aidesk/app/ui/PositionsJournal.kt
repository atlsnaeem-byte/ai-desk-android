package pk.aidesk.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pk.aidesk.app.TopBar
import pk.aidesk.app.core.Config
import pk.aidesk.app.core.Position
import pk.aidesk.app.core.Trade
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
fun PositionsScreen(vm: AppVM) {
    val cfg by vm.config.collectAsState()
    val acc by vm.account.collectAsState()
    val err by vm.accountError.collectAsState()
    val logs by vm.logs.collectAsState()
    var confirm by remember { mutableStateOf<String?>(null) }
    val closed by vm.closedAll.collectAsState()
    LaunchedEffect(Unit) { vm.loadLogs(); vm.refreshAccount(); vm.loadClosedAll() }
    LaunchedEffect(acc) { vm.loadClosedAll() }
    val now = rememberNow()

    Column(Modifier.fillMaxSize()) {
        TopBar(vm, "Positions")
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            item {
                Card {
                    val d = acc?.dayPnlPct ?: 0.0
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        T("Today", 12.sp, C.Muted)
                        T("${sgn2(d)}%" + (acc?.let { a -> a.dayStart?.let { "  (${sgn2(a.equity - it)} USDT)" } } ?: ""),
                            14.sp, if (d >= 0) C.Green else C.Red, FontWeight.Bold)
                    }
                    Gap(10.dp)
                    // bar from -loss limit to +profit target, zero marked
                    val lo = -cfg.dailyLossPct; val hi = cfg.dailyProfitPct
                    BoxWithConstraints(Modifier.fillMaxWidth().height(10.dp)) {
                        val w = maxWidth
                        val zero = ((0 - lo) / (hi - lo)).toFloat()
                        val pos = ((d.coerceIn(lo, hi) - lo) / (hi - lo)).toFloat()
                        Box(Modifier.fillMaxWidth().height(6.dp).align(Alignment.Center).clip(RoundedCornerShape(3.dp)).background(C.Panel2))
                        val a = minOf(zero, pos); val b = maxOf(zero, pos)
                        Box(Modifier.offset(x = w * a).width(w * (b - a)).height(6.dp).align(Alignment.CenterStart)
                            .background(if (d >= 0) C.Green else C.Red))
                        Box(Modifier.offset(x = w * zero).width(2.dp).height(10.dp).background(C.Txt))
                    }
                    Gap(4.dp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        T("-${cfg.dailyLossPct}% stop", 11.sp, C.Red); T("+${cfg.dailyProfitPct}% target", 11.sp, C.Green)
                    }
                    acc?.halted?.let { T("New entries paused: $it", 12.sp, C.Amber) }
                }
                err?.let { Gap(8.dp); T(it, 13.sp, C.Amber) }
                Gap(10.dp)
                // absolute totals: open (unrealised) and closed (realised) across every bot
                val ps = acc?.positions ?: emptyList()
                val openPnl = ps.sumOf { it.upnl }
                val dayStart = java.time.LocalDate.now(pk.aidesk.app.core.Engine.PKT).atStartOfDay(pk.aidesk.app.core.Engine.PKT).toInstant().toEpochMilli()
                val today = closed.filter { it.closed >= dayStart }.sumOf { it.pnl }
                val allClosed = closed.sumOf { it.pnl }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((k, v) in listOf("Open P&L" to openPnl, "Closed today" to today, "Closed total" to allClosed)) {
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(C.Panel).padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            T(k, 10.sp, C.Muted)
                            T("${sgn2(v)} USDT", 14.sp, if (v >= 0) C.Green else C.Red, FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
                Gap(10.dp)
                T("Open positions (${ps.size})", 14.sp, C.Txt, FontWeight.Bold)
                Gap(4.dp)
                if (acc != null && ps.isEmpty()) T("No open positions.", 14.sp, C.Muted, modifier = Modifier.padding(8.dp))
            }
            items(acc?.positions ?: emptyList(), key = { it.symbol + "|" + it.origin }) { p ->
                PositionCard(p, cfg, now, onOpen = { vm.showChart(p.symbol) }) { confirm = p.symbol }
                Gap(8.dp)
            }
            item {
                Gap(8.dp)
                T("Closed trades (${closed.size}) · ${closed.count { it.pnl > 0 }} profit / ${closed.count { it.pnl <= 0 }} loss", 14.sp, C.Txt, FontWeight.Bold)
                Gap(4.dp)
                if (closed.isEmpty()) T("No closed trades yet.", 13.sp, C.Muted)
            }
            items(closed.take(100)) { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            T(t.symbol, 13.sp, C.Txt, FontWeight.SemiBold); HGap(6.dp); Pill(t.side, C.side(t.side))
                        }
                        T("${t.source} · ${dayHhmm(t.closed)} · ${t.reason}", 11.sp, C.Muted, maxLines = 1)
                    }
                    T((if (t.pnl >= 0) "Profit " else "Loss ") + "${sgn2(t.pnl)} USDT", 13.sp, if (t.pnl >= 0) C.Green else C.Red, FontWeight.Bold)
                }
            }
            item {
                Gap(8.dp)
                T("Recent activity", 14.sp, C.Txt, FontWeight.Bold)
                Gap(4.dp)
                logs.take(12).forEach { l ->
                    T("${hhmm(l.t)}  ${l.msg}", 11.sp, if (l.level == "ERROR") C.Red else if (l.level == "WARN") C.Amber else C.Muted, mono = true)
                }
                Gap(20.dp)
            }
        }
    }
    confirm?.let { sym ->
        AlertDialog(onDismissRequest = { confirm = null },
            confirmButton = { TextButton({ vm.close(sym); confirm = null }) { Text("Close now", color = C.Red) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } },
            title = { Text("Close $sym?") },
            text = { Text("Market order (reduce-only). Its TP/SL orders are cancelled.") },
            containerColor = C.Panel)
    }
}

fun hhmm(ms: Long): String = DateTimeFormatter.ofPattern("HH:mm").withZone(pk.aidesk.app.core.Engine.PKT).format(Instant.ofEpochMilli(ms))
fun dayHhmm(ms: Long): String = DateTimeFormatter.ofPattern("EEE HH:mm").withZone(pk.aidesk.app.core.Engine.PKT).format(Instant.ofEpochMilli(ms))

@Composable
private fun PositionCard(p: Position, cfg: Config, now: Long, onOpen: () -> Unit, onClose: () -> Unit) {
    val col = C.side(p.side)
    val slD = p.sl?.let { abs(p.entry - it) }
    val r = if (slD != null && slD > 0) p.upnl / (p.qty * slD) else null
    Card(border = C.Border, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(p.symbol, 15.sp, C.Txt, FontWeight.Bold); HGap(8.dp); Pill(p.side, col, filled = true)
            if (p.origin != "auto") { HGap(6.dp); Pill(p.origin, if (p.origin == "external") C.Muted else C.Amber) }
            Box(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                T((if (p.upnl >= 0) "Profit " else "Loss ") + "${sgn2(p.upnl)} USDT", 15.sp, if (p.upnl >= 0) C.Green else C.Red, FontWeight.Bold)
                r?.let { T("${sgn2(it)}R", 12.sp, if (it >= 0) C.Green else C.Red, FontWeight.SemiBold) }
            }
        }
        T("Entry ${px(p.entry)}   Mark ${px(p.mark)}   Qty ${px(p.qty)}", 11.sp, C.Muted, mono = true)
        if (p.sl != null && p.tp != null && p.mark != null) {
            Gap(10.dp)
            // SL ... entry ... TP track; entry sits at 1/3 because TP is 2x the stop distance
            val dir = if (p.side == "LONG") 1 else -1
            val prog = ((dir * (p.mark - p.sl)) / (dir * (p.tp - p.sl))).toFloat().coerceIn(0f, 1f)
            val entryAt = ((dir * (p.entry - p.sl)) / (dir * (p.tp - p.sl))).toFloat().coerceIn(0f, 1f)
            BoxWithConstraints(Modifier.fillMaxWidth().height(12.dp)) {
                val w = maxWidth
                Row(Modifier.fillMaxWidth().height(6.dp).align(Alignment.Center)) {
                    Box(Modifier.weight(entryAt.coerceAtLeast(0.01f)).fillMaxSize().background(C.RedBg))
                    Box(Modifier.weight((1 - entryAt).coerceAtLeast(0.01f)).fillMaxSize().background(C.GreenBg))
                }
                Box(Modifier.offset(x = w * entryAt).width(1.dp).height(12.dp).background(C.Muted))
                Box(Modifier.offset(x = w * prog - 6.dp).size(12.dp).clip(CircleShape).background(C.Txt))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                T("SL ${px(p.sl)}", 11.sp, C.Red, mono = true); T("TP ${px(p.tp)}", 11.sp, C.Green, mono = true)
            }
        }
        Gap(8.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val opened = p.openedMs
            val txt = when {
                opened == null -> "not opened by the app  ·  tap for chart"
                p.origin == "auto" ->
                    "held ${((now - opened) / Config.BAR_MS).coerceAtLeast(0)} of ${cfg.horizonBars} bars  -  auto-exit ${hhmm(opened + cfg.horizonBars * Config.BAR_MS)}"
                else -> "opened ${dayHhmm(opened)}  ·  tap for chart"
            }
            T(txt, 11.sp, C.Muted, modifier = Modifier.weight(1f))
            Pill("Close", C.Red, onClick = onClose)
        }
    }
}

// ------------------------------------------------------------------ journal
@Composable
fun JournalScreen(vm: AppVM) {
    val mode by vm.journalMode.collectAsState()
    val all by vm.journal.collectAsState()
    var open by remember { mutableStateOf<String?>(null) }
    val closed = all.filter { it.status == "closed" && it.pnl != null }
    val view by vm.journalView.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar(vm, "Journal")
        Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("My trades", if (view == "trades") C.Blue else C.Muted, filled = view == "trades") { vm.journalView.value = "trades" }
            Pill("Signal results", if (view == "signals") C.Blue else C.Muted, filled = view == "signals") { vm.journalView.value = "signals"; vm.loadSigLog() }
        }
        if (view == "signals") SignalResults(vm) else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in listOf("live", "testnet", "paper"))
                        Pill(m.replaceFirstChar { it.uppercase() }, if (m == mode) C.Blue else C.Muted, filled = m == mode) {
                            vm.journalMode.value = m; vm.loadJournal()
                        }
                }
                Gap(10.dp)
                val wins = closed.count { (it.pnl ?: 0.0) > 0 }
                val rs = closed.mapNotNull { it.r }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((k, v, c) in listOf(
                        Triple("Trades", "${closed.size}", C.Txt),
                        Triple("Win rate", if (closed.isEmpty()) "-" else "${wins * 100 / closed.size}%", C.Txt),
                        Triple("Avg R", if (rs.isEmpty()) "-" else sgn2(rs.average()), if ((rs.average().takeIf { !it.isNaN() } ?: 0.0) >= 0) C.Green else C.Red),
                        Triple("P&L", sgn2(closed.sumOf { it.pnl ?: 0.0 }), if (closed.sumOf { it.pnl ?: 0.0 } >= 0) C.Green else C.Red),
                    )) {
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(C.Panel).padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            T(k, 11.sp, C.Muted); T(v, 15.sp, c, FontWeight.Bold)
                        }
                    }
                }
                Gap(10.dp)
                if (closed.size >= 2) {
                    val eq = closed.sortedBy { it.exitMs ?: it.openedMs }.runningFold(0.0) { a, t -> a + (t.pnl ?: 0.0) }
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Panel).padding(10.dp)) {
                        T("P&L curve (USDT)", 11.sp, C.Muted)
                        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                            val mn = eq.min(); val mx = eq.max().coerceAtLeast(mn + 1e-9)
                            val path = Path()
                            eq.forEachIndexed { i, v ->
                                val x = size.width * i / (eq.size - 1)
                                val y = (size.height * (1 - (v - mn) / (mx - mn))).toFloat()
                                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(path, if (eq.last() >= 0) C.Green else C.Red, style = Stroke(3f))
                            val zy = (size.height * (1 - (0 - mn) / (mx - mn))).toFloat()
                            drawLine(C.Dim, Offset(0f, zy), Offset(size.width, zy), 1f)
                        }
                    }
                    Gap(10.dp)
                }
                if (all.isEmpty()) T("No trades in $mode yet.", 14.sp, C.Muted, modifier = Modifier.padding(8.dp))
            }
            items(all, key = { it.id }) { t ->
                TradeRow(t, open == t.id) { open = if (open == t.id) null else t.id }
                Gap(6.dp)
            }
            item { Gap(20.dp) }
        }
    }
}

@Composable
private fun TradeRow(t: Trade, expanded: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(t.symbol, 14.sp, C.Txt, FontWeight.Bold); HGap(8.dp)
                    T(t.side, 11.sp, C.side(t.side), FontWeight.Bold); HGap(8.dp)
                    if (t.origin != "auto") T(t.origin, 11.sp, C.Muted)
                }
                T("${dayHhmm(t.openedMs)}  -  " + if (t.status == "open") "open" else "exit: ${t.reason ?: "?"}", 11.sp, C.Muted)
            }
            when {
                t.status == "open" -> Pill("OPEN", C.Blue)
                t.r != null -> T("${sgn2(t.r)}R", 15.sp, if (t.r >= 0) C.Green else C.Red, FontWeight.Bold)
                t.pnl != null -> T(sgn2(t.pnl), 15.sp, if (t.pnl >= 0) C.Green else C.Red, FontWeight.Bold)
            }
        }
        if (expanded) {
            Gap(8.dp)
            KV("Entry / exit", "${px(t.entry)} / ${px(t.exit)}")
            KV("TP / SL", "${px(t.tp)} / ${px(t.sl)}")
            KV("Qty", px(t.qty))
            t.pnl?.let { KV("P&L", "${sgn2(it)} USDT", if (it >= 0) C.Green else C.Red) }
            KV("Win chance / EV", "${pct0(t.prob)} / ${sgn2(t.evR)}R")
            if (t.patterns.isNotEmpty()) T("Patterns: " + t.patterns.joinToString(), 12.sp, C.Amber)
            t.drivers.take(4).forEach { (l, v) -> T("$l  ${sgn2(v)}", 11.sp, if (v >= 0) C.Green else C.Red) }
        }
    }
}
