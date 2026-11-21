package pk.aidesk.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pk.aidesk.app.core.ExitCfg
import pk.aidesk.app.core.Targets
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/** What one signal card shows (strategy signals, course setups). */
class SigView(val symbol: String, val side: String, val entry: Double, val sl: Double, val time: Long,
              /** targets reached so far on the 1R..5R record (null = unknown) */ val hit1R: Int?, val result: String?, val traded: Boolean = false)

private fun agoTxt(ms: Long, now: Long): String {
    val m = ((now - ms) / 60_000L).coerceAtLeast(0)
    return when {
        m < 60 -> "$m mins ago"
        m < 48 * 60 -> "${m / 60} hr${if (m / 60 == 1L) "" else "s"} ago"
        else -> "${m / 1440} days ago"
    }
}

/**
 * Open-signal card in the Crypto Signals AI layout: symbol, Buy/Sell xLEV pill with live P&L at that leverage, time and
 * status pill; SL · Entry · T1..T5 track with the live price marker (the stop sits two target-steps behind the entry);
 * "All details" opens Entry / Stop boxes and the five targets with their % at leverage (tap a price to copy it).
 */
@Composable
fun SignalCard(s: SigView, mark: Double?, lev: Int, ex: ExitCfg, now: Long, onTrade: (() -> Unit)? = null, onChart: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    val clip = LocalClipboardManager.current
    val dir = if (s.side == "LONG") 1 else -1
    val r = abs(s.entry - s.sl)
    val tps = Targets.ladder(s.side, s.entry, s.sl, ex.step)
    val byPrice = mark?.let { m -> tps.count { (m - it) * dir >= 0 } } ?: 0
    val byRecord = s.hit1R?.let { floor(it / ex.step).toInt().coerceAtMost(5) } ?: 0
    val reached = maxOf(byPrice, byRecord)
    val stopped = s.result == "SL"
    val pnl = mark?.let { dir * (it - s.entry) / s.entry * lev * 100 }
    Card {
        // header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(s.symbol, 17.sp, C.Txt, FontWeight.Bold)
                Gap(4.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(C.side(s.side)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                        T((if (dir == 1) "↑ Buy" else "↓ Sell") + " x$lev", 13.sp, Color.White, FontWeight.SemiBold)
                    }
                    pnl?.let { HGap(8.dp); T(String.format(Locale.US, "(%+.2f%%)", it), 14.sp, if (it >= 0) C.Green else C.Red, FontWeight.SemiBold) }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                T(agoTxt(s.time, now), 12.sp, C.Muted)
                Gap(4.dp)
                val (txt, col, filled) = when {
                    stopped && reached == 0 -> Triple("Stop hit", C.Red, true)
                    s.result == "TIME" && reached == 0 -> Triple("Time exit", C.Muted, false)
                    reached > 0 -> Triple("Target $reached ✓", C.Green, true)
                    else -> Triple("In progress ◔", C.Txt, false)
                }
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(if (filled) col else C.Panel2).border(1.dp, col, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)) { T(txt, 12.sp, if (filled) Color.White else col, FontWeight.SemiBold) }
            }
        }
        Gap(10.dp)
        TargetTrack(s, tps, r, ex.step, mark, reached, stopped)
        if (ex.tp1Only) T("Exit setting: whole position closes at T1 ${px(tps[0])}", 10.sp, C.Amber)
        Gap(4.dp)
        Row(Modifier.fillMaxWidth().clickable { open = !open }, horizontalArrangement = Arrangement.Center) {
            T(if (open) "All Details ▴" else "All Details ▾", 11.sp, C.Muted)
        }
        if (open) {
            Gap(6.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(C.Panel2).clickable { clip.setText(AnnotatedString(px(s.entry))) }
                    .padding(10.dp)) {
                    Row { T("Entry", 14.sp, C.Txt, modifier = Modifier.weight(1f)); T("⧉ ${px(s.entry)}", 14.sp, C.Green, FontWeight.SemiBold, mono = true) }
                }
                Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(C.Panel2).clickable { clip.setText(AnnotatedString(px(s.sl))) }
                    .padding(10.dp)) {
                    Row { T("Stop", 14.sp, C.Txt, modifier = Modifier.weight(1f)); T("⧉ ${px(s.sl)}", 14.sp, C.Red, FontWeight.SemiBold, mono = true) }
                }
            }
            for (k in 0 until 5) {
                Gap(5.dp)
                val done = reached > k
                val pct = (k + 1) * ex.step * r / s.entry * lev * 100
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (done) C.Green else C.Panel2)
                    .clickable { clip.setText(AnnotatedString(px(tps[k]))) }.padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    T("Target ${k + 1}" + if (ex.tp1Only && k == 0) " · close all" else "", 14.sp, if (done) Color.White else C.Txt,
                        modifier = Modifier.weight(1.2f))
                    T(String.format(Locale.US, "%.2f%%", pct), 13.sp, if (done) Color.White else C.Muted, modifier = Modifier.weight(1f))
                    T("⧉ ${px(tps[k])}", 14.sp, if (done) Color.White else C.Txt, FontWeight.SemiBold, mono = true)
                }
            }
            Gap(4.dp)
            T("% = price move × ${lev}x leverage. Targets ${if (ex.step == 0.5) "0.5R…2.5R" else "1R…5R"} (R = entry-to-stop).", 10.sp, C.Dim)
        }
        if (onTrade != null || onChart != null) {
            Gap(8.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onTrade?.let { Btn("Trade ${s.side}", C.side(s.side), Modifier.weight(1f), height = 40.dp, onClick = it) }
                onChart?.let { Btn("Chart", C.Blue, Modifier.weight(1f), outline = true, height = 40.dp, onClick = it) }
            }
        }
        if (s.traded) T("traded", 10.sp, C.Amber)
    }
}

/** SL · (gap) · Entry · T1..T5 on 8 equal cells; live price marker with its value underneath. */
@Composable
private fun TargetTrack(s: SigView, tps: List<Double>, r: Double, step: Double, mark: Double?, reached: Int, stopped: Boolean) {
    val dir = if (s.side == "LONG") 1 else -1
    val labels = listOf("SL", "", "Entry", "T1", "T2", "T3", "T4", "T5")
    Row(Modifier.fillMaxWidth()) {
        for (l in labels) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { T(l, 12.sp, C.Txt, FontWeight.SemiBold) }
    }
    Gap(4.dp)
    // position in cells: SL 0, entry 2, Tk 2+k
    val u = mark?.let { m ->
        val move = dir * (m - s.entry)
        if (move < 0) (2 + 2 * move / r).coerceAtLeast(0.0) else (2 + move / (step * r)).coerceAtMost(7.0)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wDp = maxWidth
        Column {
            Canvas(Modifier.fillMaxWidth().height(18.dp)) {
                val cell = size.width / 8f
                val y = size.height / 2
                fun x(c: Double) = ((c + 0.5) * cell).toFloat()
                val th = 9.dp.toPx()
                drawLine(Color(0xFF3A4556), Offset(x(0.0), y), Offset(x(7.0), y), th, StrokeCap.Round)
                if (u != null) {
                    val col = if (u >= 2) Color(0xFF16C784) else Color(0xFFEA3943)
                    drawLine(col.copy(alpha = 0.75f), Offset(x(2.0), y), Offset(x(u), y), th, StrokeCap.Butt)
                    drawLine(if (u >= 2) Color(0xFF16C784) else Color(0xFFFF3B6B), Offset(x(u), 0f), Offset(x(u), size.height), 2.5.dp.toPx())
                }
                val dot = 4.5.dp.toPx()
                drawCircle(if (stopped) Color(0xFFEA3943) else Color(0xFF6B7686), dot, Offset(x(0.0), y))
                drawCircle(Color.White, dot, Offset(x(2.0), y))
                for (k in 1..5) drawCircle(if (reached >= k) Color(0xFF16C784) else Color(0xFF6B7686), dot, Offset(x(2.0 + k), y))
            }
            if (u != null && mark != null) {
                val col = if (u >= 2) C.Green else C.Red
                val left = wDp * ((u + 0.5) / 8.0).toFloat() - 34.dp
                Box(Modifier.offset(x = left.coerceAtLeast(0.dp).coerceAtMost(wDp - 70.dp)).padding(top = 2.dp)
                    .clip(RoundedCornerShape(10.dp)).background(C.Panel2).border(1.dp, col, RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 1.dp)) { T(px(mark), 12.sp, C.Txt, FontWeight.SemiBold, mono = true) }
            }
        }
    }
}

/** Market structure settings + scan status + Scan now. */
@Composable
fun StructPanel(vm: AppVM) {
    val sc by vm.structCfg.collectAsState()
    val last by vm.structLast.collectAsState()
    val busy by vm.structBusy.collectAsState()
    fun save(n: pk.aidesk.app.core.StructCfg) = vm.saveStructCfg(n)
    Card {
        T("Scanner settings", 14.sp, C.Txt, FontWeight.Bold)
        Gap(6.dp)
        T("Coins", 12.sp, C.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((n, l) in listOf(0 to "All", 100 to "Top 100", 50 to "Top 50", 20 to "Top 20"))
                Pill(l, if (sc.top == n) C.Green else C.Muted, filled = sc.top == n) { save(sc.copy(top = n)) }
        }
        Gap(6.dp)
        T("Swing size k (bars each side)", 12.sp, C.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (k in listOf(3, 5, 10)) Pill("k = $k", if (sc.k == k) C.Green else C.Muted, filled = sc.k == k) { save(sc.copy(k = k)) }
        }
        Gap(6.dp)
        T("Setups", 12.sp, C.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((s, l) in listOf("BOS" to "BOS", "CHOCH" to "CHOCH", "PULL" to "Pullback")) {
                val on = s in sc.setups
                Pill(l, if (on) C.Green else C.Muted, filled = on) {
                    val n = if (on) sc.setups - s else sc.setups + s
                    if (n.isNotEmpty()) save(sc.copy(setups = n))
                }
            }
        }
        Gap(6.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("Buy", if (sc.long) C.Green else C.Muted, filled = sc.long) { if (sc.short || !sc.long) save(sc.copy(long = !sc.long)) }
            Pill("Short sell", if (sc.short) C.Red else C.Muted, filled = sc.short) { if (sc.long || !sc.short) save(sc.copy(short = !sc.short)) }
            Box(Modifier.weight(1f))
            T("4h trend filter", 12.sp, C.Txt); HGap(6.dp)
            Switch(sc.filter4h, { save(sc.copy(filter4h = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = C.Green, checkedThumbColor = C.Txt))
        }
        Gap(8.dp)
        val l = last
        T(if (l == null) "Not scanned yet. The background scanner runs after every 15m close while the app's service is on."
            else "Last scan ${dayHhmm(l.optLong("time"))}: ${l.optInt("coins")} coins · ${l.optInt("signals")} signals in 24 h · " +
                "${l.optInt("open")} open" + (if (l.optInt("errors") > 0) " · ${l.optInt("errors")} coins failed" else ""), 11.sp, C.Muted)
        Gap(6.dp)
        Btn(if (busy) "Scanning…" else "Scan now", C.Blue, enabled = !busy, height = 40.dp) { vm.scanStruct() }
        T("Defaults (k=10, BOS + CHOCH, 4h filter) are the least-bad BTC backtest settings. Settings apply from the next scan; " +
            "auto-trade opens at most 'max open' (Settings) structure trades at a time, one per coin.", 10.sp, C.Dim)
    }
    Gap(10.dp)
}

/** Strategy-tab setting: how strategy / course trades exit. */
@Composable
fun ExitSettingsCard(ex: ExitCfg, onChange: (ExitCfg) -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("Close all at TP1", 14.sp, C.Txt, FontWeight.Bold)
                T(if (ex.tp1Only) "ON: the whole position closes at T1 (one take-profit order), stop unchanged."
                    else "OFF: 5 targets, 1/5 of the position closes at each.", 11.sp, if (ex.tp1Only) C.Green else C.Muted)
            }
            Switch(ex.tp1Only, { onChange(ex.copy(tp1Only = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = C.Green, checkedThumbColor = C.Txt))
        }
        Gap(6.dp)
        T("Target spacing", 12.sp, C.Txt, FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("T1 = 1R (app)", if (ex.step == 1.0) C.Green else C.Muted, filled = ex.step == 1.0) { onChange(ex.copy(step = 1.0)) }
            Pill("T1 = 0.5R (Signals AI style)", if (ex.step == 0.5) C.Green else C.Muted, filled = ex.step == 0.5) { onChange(ex.copy(step = 0.5)) }
        }
        Gap(4.dp)
        T("Applies to new trades from the Strategy tab, strategy auto-trades and course auto-trades. Backtest, close all at T1, 50 USDT, " +
            "20% risk, 20x: only Gold Asian-range at T1 = 1R ended up in 2026 (+52%, but −90% on 2024-25 gold); every other strategy and the " +
            "0.5R spacing lost most of the account, mainly fees.", 10.sp, C.Amber)
    }
}
