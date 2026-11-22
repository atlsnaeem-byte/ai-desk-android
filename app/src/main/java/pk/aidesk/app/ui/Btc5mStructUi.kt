package pk.aidesk.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pk.aidesk.app.MainActivity
import pk.aidesk.app.core.Btc5mStruct
import pk.aidesk.app.core.Btc5mStructBook
import pk.aidesk.app.service.TraderService
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private val US = Locale.US
private fun sideCol(side: String) = when (side) { "UP" -> C.Green; "DOWN" -> C.Red; else -> C.Muted }

/** Live 5m chart + the structure AI's call for the current round (refreshes every 3 s while shown). */
@Composable
fun StructCallCard(vm: AppVM, act: MainActivity, now: Long) {
    val call by vm.structCall.collectAsState()
    val err by vm.structErr.collectAsState()
    val bv by vm.structView.collectAsState()
    LaunchedEffect(Unit) { while (true) { vm.loadStruct(); delay(3_000) } }
    LaunchedEffect(Unit) { while (true) { vm.loadStructPx(); delay(1_000) } }
    val livePx by vm.structPx.collectAsState()
    val c = call
    val cfg = bv?.cfg ?: Btc5mStructBook.Cfg()
    Card(border = if (c != null && c.side != "NONE") sideCol(c.side) else C.Border) {
        if (c == null) { T(err?.let { "BTC data error: $it" } ?: "Loading BTC 1m / 5m / 15m…", 12.sp, if (err != null) C.Amber else C.Muted); return@Card }
        val left = c.roundStart + Btc5mStruct.ROUND - now
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("${hhmm(c.roundStart)}–${hhmm(c.roundStart + Btc5mStruct.ROUND)}", 15.sp, C.Txt, FontWeight.Bold, mono = true)
            HGap(8.dp)
            when (c.side) {
                "UP" -> Pill("CALL UP", C.Green, filled = true)
                "DOWN" -> Pill("CALL DOWN", C.Red, filled = true)
                else -> Pill("NO CALL", C.Muted)
            }
            Box(Modifier.weight(1f))
            T(mmss(left), 15.sp, if (left < 60_000) C.Amber else C.Txt, FontWeight.SemiBold, mono = true)
        }
        Gap(4.dp)
        T("P(UP) ${String.format(US, "%.1f", c.pUp * 100)}% · " + (if (cfg.thrPct <= 50.0) "every round: ≥ 50% → UP, else DOWN" else "≥ ${cfg.thrPct.toInt()}% → UP, ≤ ${(100 - cfg.thrPct).toInt()}% → DOWN"),
            12.sp, C.Muted)
        T("Price to beat ${px(c.open)} → now ${px(c.price)} (${String.format(US, "%+.1f", c.moveBps)} bps)",
            12.sp, if (c.price >= c.open) C.Green else C.Red)
        Gap(6.dp)
        StructChart(c, livePx)
        Gap(6.dp)
        // structure on each timeframe
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((n, lab) in listOf("m1" to "1m", "m5" to "5m", "m15" to "15m")) {
                val s = c.tf.getValue(n)
                val col = when (s.trend) { 1 -> C.Green; -1 -> C.Red; else -> C.Muted }
                Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(C.Panel2).padding(6.dp)) {
                    T("$lab ${s.trendName}", 11.sp, col, FontWeight.Bold)
                    T(s.text(), 11.sp, C.Txt, mono = true)
                    T("last ${s.evName}" + when (s.brk) { 1 -> " · broke ↑"; -1 -> " · broke ↓"; else -> "" }, 10.sp, C.Muted)
                }
            }
        }
        Gap(6.dp)
        val nx by vm.structNext.collectAsState()
        nx?.let { n ->
            T("Next round ${hhmm(n.roundStart)} (call ${cfg.leadSec} s before start): ${if (n.side == "NONE") "NO CALL" else n.side} · P(UP) ${String.format(US, "%.1f", n.pUp * 100)}% · " +
                "starts in ${mmss(n.roundStart - now)}", 12.sp, sideCol(n.side), FontWeight.Bold)
        }
        T("Projected 5m candle: close ${px(c.projClose)} · high ${px(c.projHigh)} · low ${px(c.projLow)}", 12.sp, C.Txt)
        // betting
        if (c.side != "NONE") {
            Gap(6.dp)
            val already = bv?.bets?.any { it.T == c.roundStart && it.mode == cfg.mode } == true
            val maxPx = c.winP / (1.02 * (1 + cfg.minEdgePct / 100))
            if (cfg.minEdgePct > -100.0) T("Backtest win rate at this confidence ${String.format(US, "%.1f", c.winP * 100)}% · buys only if the ${c.side} share costs " +
                "≤ ${String.format(US, "%.1f", maxPx * 100)}¢ (2% fee, edge ≥ ${cfg.minEdgePct}%)" +
                (if (c.winP < cfg.minP) " · below min win rate ${String.format(US, "%.1f", cfg.minP * 100)}%: skipped" else ""), 11.sp, C.Muted)
            bv?.rounds?.firstOrNull { it.T == c.roundStart }?.note?.takeIf { it.isNotBlank() }?.let {
                T("This round: $it", 11.sp, if (already) C.Green else C.Amber)
            }
            if (cfg.early) bv?.rounds?.firstOrNull { it.T == c.roundStart }?.diag?.takeIf { it.isNotBlank() }?.let {
                T("Market: $it", 10.sp, if (it.contains("found")) C.Green else C.Amber)
            }
            if (cfg.mode == "paper" || cfg.armed) {
                if (!already && !cfg.auto) T("Auto is off: no bet this round.", 11.sp, C.Muted)
            } else {
                var shareTxt by remember(c.roundStart) { mutableStateOf("") }
                val ctx = LocalContext.current
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(shareTxt, { shareTxt = it.filter { ch -> ch.isDigit() || ch == '.' }.take(5) },
                        singleLine = true, colors = fieldColors(), modifier = Modifier.width(120.dp),
                        label = { Text("${c.side} ¢ paid") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    HGap(8.dp)
                    val cents = shareTxt.toDoubleOrNull()?.let { if (it <= 1) it * 100 else it }?.takeIf { it in 1.0..99.0 }
                    Column(Modifier.weight(1f)) {
                        Btn("Open Binance", C.Amber, outline = true, height = 36.dp) {
                            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://www.binance.com/en/prediction/market-detail/btc-up-or-down-5m"))) }
                        }
                        Gap(4.dp)
                        Btn(if (already) "Recorded" else "I bet ${f2(cfg.stake)} USDT", C.Green, enabled = !already && cents != null, height = 36.dp) {
                            vm.addStructBet("live", c, cents, cfg.stake)
                        }
                    }
                }
                T("Bet only if the ${c.side} share costs 52¢ or less: above that the backtest loses.", 11.sp, C.Amber)
            }
        }
        if (err != null) T("Last refresh failed: $err", 10.sp, C.Amber)
    }
}

@Composable
private fun StructChart(c: Btc5mStruct.Call, livePx: Double?) {
    // the forming candle follows the 1 s live price; the high/low only widen
    var hi by remember(c.roundStart) { mutableStateOf(c.forming5?.get(1) ?: Double.NaN) }
    var lo by remember(c.roundStart) { mutableStateOf(c.forming5?.get(2) ?: Double.NaN) }
    val px = livePx ?: c.price
    c.forming5?.let { f ->
        hi = maxOf(if (hi.isNaN()) f[1] else hi, f[1], px)
        lo = minOf(if (lo.isNaN()) f[2] else lo, f[2], px)
    }
    val form = c.forming5?.let { doubleArrayOf(it[0], hi, lo, px) }
    Canvas(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(10.dp)).background(C.Bg)) { drawStruct(c, form, px) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        T("━ beat ${px(c.open)}", 10.sp, C.Amber)
        T("▮ live 5m candle", 10.sp, C.Muted)
        T("▮ AI projected 5m: ${if (c.projClose >= c.open) "UP" else "DOWN"} ${px(c.projClose)}", 10.sp, if (c.projClose >= c.open) C.Green else C.Red)
    }
}

private fun DrawScope.drawStruct(c: Btc5mStruct.Call, form: DoubleArray?, livePx: Double) {
    val b = c.bars5
    val n = b.size
    if (n < 5) return
    val show = min(36, n)
    val start = n - show
    val slots = show + 2                                     // + forming candle + AI projection
    val axisW = 58.dp.toPx(); val padT = 14.dp.toPx(); val padB = 16.dp.toPx()
    val W = size.width - axisW; val H = size.height - padT - padB
    val step = W / slots
    fun x(i: Int) = ((i - start) + 0.5f) * step
    val f = form
    var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
    for (i in start until n) { lo = min(lo, b.l[i]); hi = max(hi, b.h[i]) }
    f?.let { lo = min(lo, it[2]); hi = max(hi, it[1]) }
    lo = min(lo, c.projLow); hi = max(hi, c.projHigh)
    val pad = (hi - lo) * 0.08 + 1e-9; lo -= pad; hi += pad
    fun y(v: Double) = (padT + (1 - (v - lo) / (hi - lo)) * H).toFloat()
    val paint = android.graphics.Paint().apply { isAntiAlias = true; textSize = 9.sp.toPx() }
    fun text(s: String, xx: Float, yy: Float, col: Color, bold: Boolean = false, center: Boolean = false) {
        paint.color = col.toArgb(); paint.isFakeBoldText = bold
        paint.textAlign = if (center) android.graphics.Paint.Align.CENTER else android.graphics.Paint.Align.LEFT
        drawIntoCanvas { it.nativeCanvas.drawText(s, xx, yy, paint) }
    }
    for (k in 0..4) {
        val v = lo + (hi - lo) * k / 4
        drawLine(C.Border.copy(alpha = 0.45f), Offset(0f, y(v)), Offset(W, y(v)), 1f)
        text(px(v), W + 4.dp.toPx(), y(v) + 3.dp.toPx(), C.Dim)
    }
    for (i in start until n) if ((b.t[i] / 300_000L) % 6 == 0L) text(hhmm(b.t[i]), x(i), size.height - 3.dp.toPx(), C.Dim, center = true)
    val bw = max(2f, step * 0.62f)
    fun candle(xc: Float, o: Double, h: Double, l: Double, cl: Double, alpha: Float) {
        val col = (if (cl >= o) C.Green else C.Red).copy(alpha = alpha)
        drawLine(col, Offset(xc, y(h)), Offset(xc, y(l)), 1.3f)
        val top = y(max(o, cl)); val bot = y(min(o, cl))
        drawRect(col, Offset(xc - bw / 2, top), Size(bw, max(1.5f, bot - top)))
    }
    for (i in start until n) candle(x(i), b.o[i], b.h[i], b.l[i], b.c[i], 1f)
    if (f != null) candle(x(n), f[0], f[1], f[2], f[3], 1f)
    // swings with HH / HL / LH / LL labels (5m)
    for (s in c.tf.getValue("m5").swings) {
        if (s.i < start) continue
        val col = when (s.label) { "HH", "HL" -> C.Green; "LH", "LL" -> C.Red; else -> C.Muted }
        val yy = y(s.price)
        drawCircle(col, 2.5.dp.toPx(), Offset(x(s.i), if (s.high) yy - 3.dp.toPx() else yy + 3.dp.toPx()))
        if (s.label.isNotEmpty()) text(s.label, x(s.i), if (s.high) yy - 7.dp.toPx() else yy + 13.dp.toPx(), col, bold = true, center = true)
    }
    // price to beat + live price
    val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
    drawLine(C.Amber, Offset(0f, y(c.open)), Offset(W, y(c.open)), 1.2.dp.toPx(), pathEffect = dash)
    text("beat ${px(c.open)}", 4.dp.toPx(), y(c.open) - 3.dp.toPx(), C.Amber, bold = true)
    // live price line + tag on the axis (moves every second)
    val yl = y(livePx)
    drawLine(C.Txt.copy(alpha = 0.45f), Offset(0f, yl), Offset(W, yl), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
    val lcol = if (livePx >= c.open) C.Green else C.Red
    drawRect(lcol, Offset(W + 1.dp.toPx(), yl - 7.dp.toPx()), Size(axisW - 2.dp.toPx(), 14.dp.toPx()))
    text(px(livePx), W + 4.dp.toPx(), yl + 3.dp.toPx(), C.Bg, bold = true)
    // AI projected 5m candle: solid, coloured by its projected direction (green up / red down), min body so the colour shows
    val xp = x(n + 1)
    val pcol = if (c.projClose >= c.open) C.Green else C.Red
    val pw = bw * 1.15f
    drawRect(pcol.copy(alpha = 0.12f), Offset(xp - step / 2, padT), Size(step, H))
    drawLine(pcol, Offset(xp, y(c.projHigh)), Offset(xp, y(c.projLow)), 2f)
    var top = y(max(c.open, c.projClose)); var bot = y(min(c.open, c.projClose))
    val minH = 8.dp.toPx()
    if (bot - top < minH) { val mid = (top + bot) / 2; top = mid - minH / 2; bot = mid + minH / 2 }
    drawRect(pcol.copy(alpha = 0.85f), Offset(xp - pw / 2, top), Size(pw, bot - top))
    drawRect(C.Txt, Offset(xp - pw / 2, top), Size(pw, bot - top), style = Stroke(1.2f, pathEffect = dash))
    text(if (c.projClose >= c.open) "AI ▲" else "AI ▼", xp, padT - 3.dp.toPx(), pcol, bold = true, center = true)
}
/** Settings + paper / live record for the structure AI. */
@Composable
fun StructRecordCard(vm: AppVM, act: MainActivity) {
    val v by vm.structView.collectAsState()
    val ticks by TraderService.structTicks.collectAsState()
    val running by TraderService.running.collectAsState()
    LaunchedEffect(ticks) { vm.loadStructBook() }
    LaunchedEffect(Unit) { while (true) { delay(3_000); vm.loadStructBook() } }
    var showAll by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<Pair<String, String>?>(null) }
    val bv = v ?: run { Card { T("Loading record…", 12.sp, C.Muted) }; return }
    val cfg = bv.cfg
    val m = vm.structModel
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("Auto (every round)", 14.sp, C.Txt, FontWeight.Bold)
                T(when {
                    !cfg.auto -> "OFF: no calls are recorded and no bets are placed."
                    !running -> "Needs the scanner running: tap the red bar on the Signals tab."
                    cfg.mode == "paper" -> "ON · PAPER: each call goes through the same quote + edge check as LIVE; no order is sent."
                    cfg.armed -> "ON · LIVE ARMED: the app places real Binance Prediction orders when the edge check passes."
                    else -> "ON · LIVE NOT ARMED: alerts only. Tap ARM LIVE below to let the app place orders."
                }, 11.sp, when { !cfg.auto || !running -> C.Amber; cfg.mode == "live" && cfg.armed -> C.Red; else -> C.Green })
            }
            Switch(cfg.auto, { vm.saveStructCfg(cfg.copy(auto = it)) },
                colors = SwitchDefaults.colors(checkedTrackColor = C.Green, checkedThumbColor = C.Txt))
        }
        bv.status.takeIf { it.second.isNotBlank() }?.let { (t, s) -> T("Last: ${hhmm(t)} · $s", 11.sp, C.Txt) }
        bv.risk?.let { T("Blocked: $it", 11.sp, C.Red, FontWeight.Bold) }
        Gap(6.dp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            T("Mode", 12.sp, C.Muted)
            Pill("PAPER", C.Blue, filled = cfg.mode == "paper") { vm.saveStructCfg(cfg.copy(mode = "paper", armed = false)) }
            Pill("LIVE", C.Green, filled = cfg.mode == "live") {
                if (cfg.mode != "live") act.confirmUser("Switch BTC 5m structure AI to LIVE (still needs ARM LIVE to place orders)") { vm.saveStructCfg(cfg.copy(mode = "live")) }
            }
            Box(Modifier.weight(1f))
            Pill(if (cfg.alerts) "Alerts on" else "Alerts off", if (cfg.alerts) C.Green else C.Muted) { vm.saveStructCfg(cfg.copy(alerts = !cfg.alerts)) }
        }
        if (cfg.mode == "live") {
            Gap(6.dp)
            Btn(if (cfg.armed) "DISARM LIVE" else "ARM LIVE · real orders", if (cfg.armed) C.Red else C.Green, height = 40.dp,
                enabled = cfg.armed || bv.keyHint != null) {
                if (cfg.armed) vm.saveStructCfg(cfg.copy(armed = false))
                else act.confirmUser("ARM LIVE: the app will place real Binance Prediction orders of ${f2(maxOf(cfg.stake, 1.5))} USDT") {
                    vm.saveStructCfg(cfg.copy(armed = true, stake = maxOf(cfg.stake, 1.5)))
                }
            }
            if (bv.keyHint == null) T("Save a Prediction API key first (below).", 11.sp, C.Amber)
        }
        Gap(6.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("Stake ${f2(cfg.stake)} USDT", C.Blue) { edit = "stake" to f2(cfg.stake) }
            Pill("Paper price ${cfg.paperCents.toInt()}¢", C.Blue) { edit = "cents" to cfg.paperCents.toInt().toString() }
            Pill(if (cfg.useQuotes) "Paper: real quotes" else "Paper: paper price", C.Blue) { vm.saveStructCfg(cfg.copy(useQuotes = !cfg.useQuotes)) }
        }
        Gap(8.dp)
        // ---- edge gate (PredictAI V7.3.0)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            T("Rule", 11.sp, C.Muted)
            for ((lab, thr) in listOf("52%" to 52.0, "54%" to 54.0, "56%" to 56.0, "Every round" to 50.0))
                Pill(lab, C.Blue, filled = cfg.thrPct == thr) { vm.saveStructCfg(cfg.copy(thrPct = thr)) }
        }
        Gap(4.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            T("Call timing", 11.sp, C.Muted)
            Pill("At start", C.Blue, filled = !cfg.early) { vm.saveStructCfg(cfg.copy(early = false)) }
            Pill("10 s before", C.Blue, filled = cfg.early && cfg.leadSec == 10) { vm.saveStructCfg(cfg.copy(early = true, leadSec = 10)) }
            Pill("30 s before", C.Blue, filled = cfg.early && cfg.leadSec == 30) { vm.saveStructCfg(cfg.copy(early = true, leadSec = 30)) }
        }
        bv.market.let { (n, before, px) ->
            if (n > 0) T("Market check: next round's market was open before its start on $before of $n rounds" +
                (px?.let { " · listed price then ≈ ${String.format(US, "%.1f", it * 100)}¢" } ?: ""), 11.sp, if (before > 0) C.Green else C.Amber)
            else if (cfg.early) T("Market check: no rounds checked yet (needs the Prediction key).", 11.sp, C.Muted)
        }
        Gap(6.dp)
        T("Price check (optional, same for PAPER and LIVE)", 12.sp, C.Txt, FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            T("Min edge", 11.sp, C.Muted)
            Pill("Off · bet every call", C.Blue, filled = cfg.minEdgePct <= -100.0) { vm.saveStructCfg(cfg.copy(minEdgePct = -100.0, minP = 0.0)) }
            for (e in listOf(0.0, 2.0, 5.0)) Pill("${e.toInt()}%", C.Blue, filled = cfg.minEdgePct == e) { vm.saveStructCfg(cfg.copy(minEdgePct = e, minP = 0.0)) }
        }
        Gap(4.dp)
        if (cfg.early) T("Buys at ${cfg.leadSec} s before the round starts (e.g. 12:54:${60 - cfg.leadSec} for the 12:55 round). If the buy can't be made " +
            "before the start, that round is skipped: no buying after the start.", 11.sp, C.Muted)
        else Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            T("Entry window", 11.sp, C.Muted)
            for (s in listOf(20, 30, 45, 60, 90)) Pill(String.format(US, "%d:%02d", s / 60, s % 60), C.Blue, filled = cfg.windowSec == s) {
                vm.saveStructCfg(cfg.copy(windowSec = s))
            }
        }
        T(if (cfg.minEdgePct <= -100.0) "Off: every call is bet at the Binance quote, whatever the price. " +
            "With the 2% fee this rule loses money if shares cost more than about 51¢."
          else "On: a call is bet only if backtest win rate × shares − cost ≥ ${cfg.minEdgePct.toInt()}% of cost at the real quote (2% fee included); " +
            (if (cfg.early) "re-checked every 1.5 s until the round starts." else "re-checked every 8 s until the window closes."), 10.sp, C.Dim)
        Gap(8.dp)
        // ---- hard limits
        T("Optional limits (off = same as the backtest)", 12.sp, C.Txt, FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("Daily loss ${if (cfg.dailyLossUsdt > 0) f2(cfg.dailyLossUsdt) + " USDT" else "off"}", C.Amber) { edit = "loss" to f2(cfg.dailyLossUsdt) }
            Pill("Max ${if (cfg.maxTradesDay > 0) cfg.maxTradesDay.toString() else "∞"}/day", C.Amber) { edit = "trades" to cfg.maxTradesDay.toString() }
            Pill("Pause after ${if (cfg.lossStreak > 0) cfg.lossStreak.toString() else "-"} losses", C.Amber) { edit = "streak" to cfg.lossStreak.toString() }
        }
        Gap(8.dp)
        // ---- Prediction API key
        PredKeyBox(vm, bv.keyHint)
        Gap(8.dp)
        T("Backtest · structure AI + order flow (${m?.testStart ?: ""} → ${m?.testEnd ?: ""}, ${m?.days ?: 180} days walk-forward, \$1 per bet, 2% fee)", 12.sp, C.Txt, FontWeight.Bold)
        for (b in (m?.backtest ?: emptyList()).sortedByDescending { it.thr }) {
            val on = kotlin.math.abs(cfg.thrPct / 100 - b.thr) < 1e-6
            val lab = if (b.thr <= 0.5) "Every round" else "${(b.thr * 100).toInt()}% rule"
            T("${if (on) "▶ " else "   "}$lab: ${b.perDay.toInt()}/day · won ${String.format(US, "%.1f", b.win * 100)}% · " +
                "at 50¢ ${String.format(US, "%+.0f", b.pnl50)} · at 54¢ ${if (b.pnl54.isNaN()) "-" else String.format(US, "%+.0f", b.pnl54)} · max drop ${b.dd.toInt()}",
                11.sp, if (on) C.Txt else C.Muted, if (on) FontWeight.Bold else FontWeight.Normal)
        }
        T("You pay about 54¢ per share with the fee on real Binance quotes, so the \"at 54¢\" column is the realistic one. " +
            "Calling 10 or 30 s before the start tested as accurate as at the start (1-second data, 60 days).", 10.sp, C.Dim)
        Gap(10.dp)
        val us = US
        @Composable fun stat(label: String, s: Btc5mStructBook.Stats) {
            if (s.n == 0 && s.open == 0) { KV(label, "none yet"); return }
            KV(label, "${s.wins}/${s.n} won (${if (s.n > 0) String.format(us, "%.1f", s.rate * 100) else "-"}%) · ${String.format(us, "%+.2f", s.pnl)} USDT" +
                (if (s.open > 0) " · ${s.open} open" else ""), if (s.pnl >= 0) C.Green else C.Red)
        }
        stat("Paper today", bv.paperToday)
        stat("Paper all", bv.paper)
        if (bv.paper.n > 0) KV("Paper max drop", "${f2(bv.paper.maxDd)} USDT", C.Muted)
        stat("Live today", bv.liveToday)
        stat("Live all", bv.live)
        KV("All calls (rounds)", if (bv.calls.first > 0) "${bv.calls.second}/${bv.calls.first} right (${String.format(us, "%.1f", 100.0 * bv.calls.second / bv.calls.first)}%)" else "none yet")
        T("Prices include the 2% fee. Break-even win rate = price paid per share. Paper uses real Binance quotes when a " +
            "Prediction key is saved (no order is sent), else the paper price. Settled from Binance spot (the market uses Chainlink; " +
            "near-ties can differ); a tie pays half.", 10.sp, C.Dim)
        Gap(8.dp)
        val bets = if (showAll) bv.bets.take(300) else bv.bets.take(10)
        if (bets.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Bets", 12.sp, C.Txt, FontWeight.Bold, modifier = Modifier.weight(1f))
                if (bv.bets.size > 10) T(if (showAll) "Less" else "All ${bv.bets.size}", 12.sp, C.Blue, FontWeight.SemiBold, modifier = Modifier.clickable { showAll = !showAll })
            }
            val nowMs = System.currentTimeMillis()
            for (b in bets) Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("${hhmm(b.T)}–${hhmm(b.T + Btc5mStruct.ROUND)}", 11.sp, C.Txt, mono = true); HGap(6.dp)
                    T(b.mode.uppercase(), 10.sp, if (b.mode == "live") C.Green else C.Blue, FontWeight.Bold); HGap(6.dp)
                    T("${b.side} @${String.format(us, "%.1f", b.share * 100)}¢ · ${f2(b.stake)}" + (if (b.status != "OK") " · ${b.status}" else ""),
                        11.sp, if (b.status != "OK") C.Amber else sideCol(b.side), mono = true, modifier = Modifier.weight(1f))
                    val p = b.pnl
                    T(when { p != null -> String.format(us, "%+.2f", p); b.up == null && nowMs < b.T + Btc5mStruct.ROUND -> "OPEN"; else -> "settling…" },
                        11.sp, when { p == null -> C.Amber; p >= 0 -> C.Green; else -> C.Red }, FontWeight.Bold, mono = true)
                    HGap(8.dp)
                    T("✕", 11.sp, C.Dim, modifier = Modifier.clickable { vm.deleteStructBet(b.id) })
                }
                val placed = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(pk.aidesk.app.core.Engine.PKT)
                    .format(java.time.Instant.ofEpochMilli(b.id))
                T("bet placed $placed · " + when {
                    b.pnl != null -> "${if (b.up == 2) "TIE" else if (b.win == true) "WIN" else "LOSS"} · settled"
                    nowMs < b.T -> "round starts in ${mmss(b.T - nowMs)}"
                    nowMs < b.T + Btc5mStruct.ROUND -> "result in ${mmss(b.T + Btc5mStruct.ROUND - nowMs)}"
                    else -> "round ended, result in a few seconds"
                }, 10.sp, C.Muted)
            }
            Gap(6.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            T("Export CSV", 12.sp, C.Blue, FontWeight.SemiBold, modifier = Modifier.clickable { act.exportCsv("btc5ms") })
            T("Clear paper", 12.sp, C.Amber, FontWeight.SemiBold, modifier = Modifier.clickable { act.confirmUser("Delete all structure paper bets") { vm.clearStruct("paper") } })
            T("Clear rounds", 12.sp, C.Amber, FontWeight.SemiBold, modifier = Modifier.clickable { act.confirmUser("Delete all recorded structure rounds") { vm.clearStruct("rounds") } })
        }
    }
    edit?.let { (what, init) ->
        var t by remember(what) { mutableStateOf(init) }
        AlertDialog(onDismissRequest = { edit = null },
            confirmButton = { TextButton({
                t.toDoubleOrNull()?.let { x ->
                    when {
                        what == "stake" && x > 0 -> vm.saveStructCfg(cfg.copy(stake = if (cfg.mode == "live") maxOf(x, 1.5) else x))
                        what == "cents" && x in 1.0..99.0 -> vm.saveStructCfg(cfg.copy(paperCents = x))
                        what == "loss" && x >= 0 -> vm.saveStructCfg(cfg.copy(dailyLossUsdt = x))
                        what == "trades" && x >= 0 -> vm.saveStructCfg(cfg.copy(maxTradesDay = x.toInt()))
                        what == "streak" && x >= 0 -> vm.saveStructCfg(cfg.copy(lossStreak = x.toInt()))
                    }
                }
                edit = null
            }) { Text("Save", color = C.Blue) } },
            dismissButton = { TextButton({ edit = null }) { Text("Cancel", color = C.Muted) } },
            title = { Text(when (what) { "stake" -> "Stake per bet (USDT, LIVE min 1.5)"; "cents" -> "Paper share price (¢)"
                "loss" -> "Daily loss limit (USDT, 0 = off)"; "trades" -> "Max bets per day (0 = off)"; else -> "Pause 60 min after N losses in a row (0 = off)" }) },
            text = {
                OutlinedTextField(t, { t = it.filter { ch -> ch.isDigit() || ch == '.' }.take(6) }, singleLine = true, colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            })
    }
}

/** Binance Prediction API key (separate from the futures key): Reading + Prediction Trading, withdrawals OFF. */
@Composable
private fun PredKeyBox(vm: AppVM, hint: String?) {
    val info by vm.predInfo.collectAsState()
    var open by remember { mutableStateOf(false) }
    var k by remember { mutableStateOf("") }
    var s by remember { mutableStateOf("") }
    LaunchedEffect(hint) { if (hint != null && info == null) vm.testPred() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        T("Prediction API key", 12.sp, C.Txt, FontWeight.Bold, modifier = Modifier.weight(1f))
        T(hint ?: "not set", 12.sp, if (hint != null) C.Green else C.Amber, mono = true)
        HGap(10.dp)
        T(if (open) "Close" else if (hint == null) "Add" else "Change", 12.sp, C.Blue, FontWeight.SemiBold, modifier = Modifier.clickable { open = !open })
    }
    info?.let { T(it, 11.sp, if (it.startsWith("Connected")) C.Green else if (it.startsWith("Checking")) C.Muted else C.Red) }
    if (open) {
        T("Use a NEW Binance API key with only Reading + Prediction Trading enabled and withdrawals OFF. It is stored encrypted " +
            "on this phone. An IP-restricted key works only from that IP.", 10.sp, C.Amber)
        OutlinedTextField(k, { k = it.trim() }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth(), label = { Text("API key") })
        OutlinedTextField(s, { s = it.trim() }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth(), label = { Text("Secret") },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        Gap(4.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Save + test", C.Green, Modifier.weight(1f), enabled = k.length > 10 && s.length > 10, height = 38.dp) {
                vm.savePredKeys(k, s); k = ""; s = ""; open = false
            }
            if (hint != null) Btn("Remove", C.Red, Modifier.weight(1f), outline = true, height = 38.dp) { vm.clearPredKeys(); open = false }
        }
    } else if (hint != null) {
        T("Test connection", 11.sp, C.Blue, FontWeight.SemiBold, modifier = Modifier.clickable { vm.testPred() })
    }
}
