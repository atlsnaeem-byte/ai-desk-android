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
        T("P(UP) ${String.format(US, "%.1f", c.pUp * 100)}% · needs ≥ ${cfg.thrPct.toInt()}% for UP or ≤ ${(100 - cfg.thrPct).toInt()}% for DOWN",
            12.sp, C.Muted)
        T("Price to beat ${px(c.open)} → now ${px(c.price)} (${String.format(US, "%+.1f", c.moveBps)} bps)",
            12.sp, if (c.price >= c.open) C.Green else C.Red)
        Gap(6.dp)
        StructChart(c)
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
        T("Projected 5m candle: close ${px(c.projClose)} · high ${px(c.projHigh)} · low ${px(c.projLow)}", 12.sp, C.Txt)
        // betting
        if (c.side != "NONE") {
            Gap(6.dp)
            val already = bv?.bets?.any { it.T == c.roundStart && it.mode == cfg.mode } == true
            if (cfg.mode == "paper") {
                T(if (already) "Paper bet placed: ${c.side} ${f2(cfg.stake)} USDT at ${cfg.paperCents.toInt()}¢ (auto)."
                  else if (cfg.auto) "Auto paper bet happens at the round start (within 30 s)." else "Auto is off: no paper bet this round.",
                    11.sp, if (already) C.Green else C.Muted)
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
private fun StructChart(c: Btc5mStruct.Call) {
    Canvas(Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(10.dp)).background(C.Bg)) { drawStruct(c) }
}

private fun DrawScope.drawStruct(c: Btc5mStruct.Call) {
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
    val f = c.forming5
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
    if (f != null) candle(x(n), f[0], f[1], f[2], f[3], 0.75f)
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
    drawLine(C.Txt.copy(alpha = 0.35f), Offset(0f, y(c.price)), Offset(W, y(c.price)), 1f)
    // AI projected candle (dashed) in the next slot
    val xp = x(n + 1)
    val pcol = when (c.side) { "UP" -> C.Green; "DOWN" -> C.Red; else -> C.Blue }
    drawLine(pcol.copy(alpha = 0.8f), Offset(xp, y(c.projHigh)), Offset(xp, y(c.projLow)), 1.3f, pathEffect = dash)
    val top = y(max(c.open, c.projClose)); val bot = y(min(c.open, c.projClose))
    drawRect(pcol.copy(alpha = 0.25f), Offset(xp - bw / 2, top), Size(bw, max(2f, bot - top)))
    drawRect(pcol, Offset(xp - bw / 2, top), Size(bw, max(2f, bot - top)), style = Stroke(1.2f, pathEffect = dash))
    text("AI", xp, padT - 3.dp.toPx(), pcol, bold = true, center = true)
}

/** Settings + paper / live record for the structure AI. */
@Composable
fun StructRecordCard(vm: AppVM, act: MainActivity) {
    val v by vm.structView.collectAsState()
    val ticks by TraderService.structTicks.collectAsState()
    val running by TraderService.running.collectAsState()
    LaunchedEffect(ticks) { vm.loadStructBook() }
    LaunchedEffect(Unit) { while (true) { delay(20_000); vm.loadStructBook() } }
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
                    !cfg.auto -> "OFF: no calls are recorded and no paper bets are placed."
                    !running -> "Needs the scanner running: tap the red bar on the Signals tab."
                    cfg.mode == "paper" -> "ON · PAPER: each call is bet automatically (${f2(cfg.stake)} USDT at ${cfg.paperCents.toInt()}¢)."
                    else -> "ON · LIVE: alert with sound on each call. You bet in Binance and tap \"I bet\": the app cannot bet for you."
                }, 11.sp, if (cfg.auto && running) C.Green else C.Amber)
            }
            Switch(cfg.auto, { vm.saveStructCfg(cfg.copy(auto = it)) },
                colors = SwitchDefaults.colors(checkedTrackColor = C.Green, checkedThumbColor = C.Txt))
        }
        Gap(6.dp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            T("Mode", 12.sp, C.Muted)
            Pill("PAPER", C.Blue, filled = cfg.mode == "paper") { vm.saveStructCfg(cfg.copy(mode = "paper")) }
            Pill("LIVE", C.Green, filled = cfg.mode == "live") {
                if (cfg.mode != "live") act.confirmUser("Switch BTC 5m structure AI to LIVE alerts") { vm.saveStructCfg(cfg.copy(mode = "live")) }
            }
            Box(Modifier.weight(1f))
            Pill(if (cfg.alerts) "Alerts on" else "Alerts off", if (cfg.alerts) C.Green else C.Muted) { vm.saveStructCfg(cfg.copy(alerts = !cfg.alerts)) }
        }
        Gap(6.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("Stake ${f2(cfg.stake)} USDT", C.Blue) { edit = "stake" to f2(cfg.stake) }
            Pill("Paper price ${cfg.paperCents.toInt()}¢", C.Blue) { edit = "cents" to cfg.paperCents.toInt().toString() }
        }
        Gap(8.dp)
        T("Call threshold (backtest ${m?.testStart ?: ""} → ${m?.testEnd ?: ""}, ${m?.days ?: 180} days, $1 per call)", 12.sp, C.Txt, FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in listOf(52.0, 54.0, 56.0, 58.0, 60.0)) Pill("${p.toInt()}%", C.Blue, filled = cfg.thrPct == p) { vm.saveStructCfg(cfg.copy(thrPct = p)) }
        }
        m?.bt(cfg.thr)?.let { b ->
            T("≈ ${b.perDay.toInt()} calls/day · won ${String.format(US, "%.1f", b.win * 100)}% · $1 each: " +
                "${String.format(US, "%+.0f", b.pnl50)} USDT at 50¢, ${String.format(US, "%+.0f", b.pnl52)} at 52¢, ${String.format(US, "%+.0f", b.pnl55)} at 55¢ · max drop ${b.dd.toInt()}",
                11.sp, C.Muted)
        }
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
        stat("Live bets", bv.live)
        KV("All calls (rounds)", if (bv.calls.first > 0) "${bv.calls.second}/${bv.calls.first} right (${String.format(us, "%.1f", 100.0 * bv.calls.second / bv.calls.first)}%)" else "none yet")
        T("Break-even win rate = share price. Paper bets use the paper price, so type the real price you see in Binance into " +
            "\"Paper price\" for a fair test. Fees are not included.", 10.sp, C.Dim)
        Gap(8.dp)
        val bets = if (showAll) bv.bets.take(300) else bv.bets.take(10)
        if (bets.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Bets", 12.sp, C.Txt, FontWeight.Bold, modifier = Modifier.weight(1f))
                if (bv.bets.size > 10) T(if (showAll) "Less" else "All ${bv.bets.size}", 12.sp, C.Blue, FontWeight.SemiBold, modifier = Modifier.clickable { showAll = !showAll })
            }
            for (b in bets) Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                T(hhmm(b.T), 11.sp, C.Muted, mono = true); HGap(6.dp)
                T(b.mode.uppercase(), 10.sp, if (b.mode == "live") C.Green else C.Blue, FontWeight.Bold); HGap(6.dp)
                T("${b.side} @${(b.share * 100).toInt()}¢ · ${f2(b.stake)}", 11.sp, sideCol(b.side), mono = true, modifier = Modifier.weight(1f))
                val p = b.pnl
                T(p?.let { String.format(us, "%+.2f", it) } ?: "open", 11.sp, when { p == null -> C.Muted; p >= 0 -> C.Green; else -> C.Red }, mono = true)
                HGap(8.dp)
                T("✕", 11.sp, C.Dim, modifier = Modifier.clickable { vm.deleteStructBet(b.id) })
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
                    if (what == "stake" && x > 0) vm.saveStructCfg(cfg.copy(stake = x))
                    if (what == "cents" && x in 1.0..99.0) vm.saveStructCfg(cfg.copy(paperCents = x))
                }
                edit = null
            }) { Text("Save", color = C.Blue) } },
            dismissButton = { TextButton({ edit = null }) { Text("Cancel", color = C.Muted) } },
            title = { Text(if (what == "stake") "Stake per bet (USDT)" else "Paper share price (¢)") },
            text = {
                OutlinedTextField(t, { t = it.filter { ch -> ch.isDigit() || ch == '.' }.take(6) }, singleLine = true, colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            })
    }
}
