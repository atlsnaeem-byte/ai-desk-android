package pk.aidesk.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.input.pointer.pointerInput
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
import pk.aidesk.app.core.Staking
import pk.aidesk.app.service.TraderService
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private val US = Locale.US
private fun sideCol(side: String) = when (side) { "UP" -> C.Green; "DOWN" -> C.Red; else -> C.Muted }

/** Market dropdown at the top of the Prediction tab. Backtest notes: 56% rule, 180-day walk-forward, P&L at 54c (2026-11-23). */
private val MK_NOTE = mapOf(
    "btc5" to "56% rule: 55.8% won · +122 at 54¢ · 7/7 months up",
    "eth5" to "56% rule: 56.1% won · +210 at 54¢ · 7/7 months up (best)",
    "eth5s" to "1-second model, calls 10 s after the start: 60% rule won 67.6% (133/day) in 110 days · paper first",
)

@Composable
fun PredictMarketPicker(vm: AppVM) {
    val mk by vm.structMk.collectAsState()
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Panel2).clickable { open = true }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("${mk.label} Up / Down ▾", 15.sp, C.Txt, FontWeight.Bold)
                T(MK_NOTE[mk.id] ?: "", 11.sp, if (mk.weak) C.Amber else C.Green)
            }
        }
        DropdownMenu(open, { open = false }) {
            for (m in Btc5mStruct.MARKETS) DropdownMenuItem(
                text = {
                    Column {
                        T((if (m == mk) "▶ " else "") + "${m.label} Up / Down", 13.sp, C.Txt, FontWeight.Bold)
                        T(MK_NOTE[m.id] ?: "", 10.sp, if (m.weak) C.Amber else C.Green)
                    }
                },
                onClick = { open = false; vm.selectStructMk(m.id) })
        }
    }
    T("Each market has its own Auto switch, settings and record; Auto runs in the background for every market switched on. " +
        "BNB, 15m, 1h and 1d rounds tested with no edge, so they are not offered.", 10.sp, C.Dim)
}

/** Live 5m chart + the structure AI's call for the current round (refreshes every 3 s while shown). */
@Composable
fun StructCallCard(vm: AppVM, act: MainActivity, now: Long) {
    val call by vm.structCall.collectAsState()
    val err by vm.structErr.collectAsState()
    val bv by vm.structView.collectAsState()
    LaunchedEffect(Unit) { while (true) { vm.loadStruct(); delay(3_000) } }
    LaunchedEffect(Unit) { while (true) { vm.loadStructPx(); delay(1_000) } }
    val livePx by vm.structPx.collectAsState()
    val c0mk by vm.structMk.collectAsState()
    val c = call?.takeIf { it.mk == c0mk }
    val cfg = bv?.takeIf { it.mk == c0mk }?.cfg ?: Btc5mStructBook.Cfg()
    Card(border = if (c != null && c.side != "NONE") sideCol(c.side) else C.Border) {
        if (c == null) { T(err?.let { "${c0mk.coin} data error: $it" } ?: "Loading ${c0mk.coin} 1m / 5m / 15m…", 12.sp, if (err != null) C.Amber else C.Muted); return@Card }
        val left = c.roundStart + c.roundMs - now
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("${c.mk.label} · ${hhmm(c.roundStart)}–${hhmm(c.roundStart + c.roundMs)}", 15.sp, C.Txt, FontWeight.Bold, mono = true)
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
        StructChart(c, livePx, bv?.takeIf { it.mk == c0mk }?.rounds ?: emptyList(), bv?.takeIf { it.mk == c0mk }?.bets ?: emptyList())
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
        T("Projected ${c.mk.roundMin}m candle: close ${px(c.projClose)} · high ${px(c.projHigh)} · low ${px(c.projLow)}", 12.sp, C.Txt)
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
                                android.net.Uri.parse(c.mk.webUrl))) }
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
private fun StructChart(c: Btc5mStruct.Call, livePx: Double?, rounds: List<Btc5mStructBook.RoundRow>, bets: List<Btc5mStructBook.Bet>) {
    // the forming candle follows the 1 s live price; the high/low only widen
    var hi by remember(c.roundStart) { mutableStateOf(c.forming5?.get(1) ?: Double.NaN) }
    var lo by remember(c.roundStart) { mutableStateOf(c.forming5?.get(2) ?: Double.NaN) }
    val px = livePx ?: c.price
    c.forming5?.let { f ->
        hi = maxOf(if (hi.isNaN()) f[1] else hi, f[1], px)
        lo = minOf(if (lo.isNaN()) f[2] else lo, f[2], px)
    }
    val form = c.forming5?.let { doubleArrayOf(it[0], hi, lo, px) }
    // how many candles are on screen, and how far the chart is scrolled back (in candles) from the newest one
    var show by remember { mutableStateOf(24) }
    var back by remember(c.mk.id) { mutableStateOf(0f) }
    val n = c.bars5.size
    val maxBack = max(0, n - show).toFloat()
    val backI = back.coerceIn(0f, maxBack).toInt()
    Canvas(Modifier.fillMaxWidth().height(310.dp).clip(RoundedCornerShape(10.dp)).background(C.Bg)
        .pointerInput(show, n) {
            detectHorizontalDragGestures { change, dragAmount ->
                change.consume()
                val stepPx = size.width / (show + 2f)
                back = (back + dragAmount / stepPx).coerceIn(0f, max(0, n - show).toFloat())
            }
        }) { drawStruct(c, form, px, rounds, bets, show, backI) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill("◀ older", C.Blue) { back = (back + show / 2f).coerceAtMost(maxBack) }
        Pill("newer ▶", C.Blue) { back = (back - show / 2f).coerceAtLeast(0f) }
        Pill("Now", C.Amber, filled = backI == 0) { back = 0f }
        Box(Modifier.weight(1f))
        T("candles", 10.sp, C.Muted)
        for (z in listOf(12, 24, 48)) Pill("$z", C.Blue, filled = show == z) { show = z }
    }
    val end = n - backI; val first = max(0, end - min(show, end))
    if (end > 0 && n > 0) {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(pk.aidesk.app.core.Engine.PKT)
        val t0 = c.bars5.t[first]; val t1 = c.bars5.t[end - 1] + c.bars5.tfMin * 60_000L
        T("Showing ${fmt.format(java.time.Instant.ofEpochMilli(t0))} → ${fmt.format(java.time.Instant.ofEpochMilli(t1))} (PKT) · drag the chart sideways to go back", 10.sp, C.Muted)
        val vis = rounds.filter { it.T in t0 until t1 && it.side != "NONE" }
        val vb = bets.filter { it.T in t0 until t1 && it.filled }
        T("In view: bot calls ${vis.count { it.win == true }} won · ${vis.count { it.win == false }} lost" +
            (if (vb.isNotEmpty()) " | bets ${vb.count { it.win == true }} won · ${vb.count { it.win == false }} lost" else ""), 11.sp, C.Txt, FontWeight.SemiBold)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        T("▲▼ bot call", 10.sp, C.Muted)
        T("green = won", 10.sp, C.Green)
        T("red = lost", 10.sp, C.Red)
        T("amber = waiting", 10.sp, C.Amber)
        T("◯ ring = bet (white live, blue paper)", 10.sp, C.Muted)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        T("━ beat ${px(c.open)}", 10.sp, C.Amber)
        T("▮ live ${c.mk.roundMin}m candle ${hhmm(c.roundStart)}", 10.sp, C.Muted)
        T("▮ AI projected for the SAME round: ${if (c.projClose >= c.open) "UP" else "DOWN"} ${px(c.projClose)}", 10.sp, if (c.projClose >= c.open) C.Green else C.Red)
    }
}

private fun DrawScope.drawStruct(c: Btc5mStruct.Call, form: DoubleArray?, livePx: Double, rounds: List<Btc5mStructBook.RoundRow>,
                                 bets: List<Btc5mStructBook.Bet>, showN: Int, back: Int) {
    val b = c.bars5
    val n = b.size
    if (n < 5) return
    val end = n - back                                       // exclusive: the last candle on screen is end - 1
    val show = min(showN, end)
    val start = end - show
    val live = back == 0                                     // newest edge: + forming candle + AI projection of the same round
    val slots = show + (if (live) 2 else 0)
    val axisW = 58.dp.toPx(); val padT = 16.dp.toPx(); val padB = 36.dp.toPx()
    val W = size.width - axisW; val H = size.height - padT - padB
    val step = W / slots
    fun x(i: Int) = ((i - start) + 0.5f) * step
    val f = if (live) form else null
    var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
    for (i in start until end) { lo = min(lo, b.l[i]); hi = max(hi, b.h[i]) }
    f?.let { lo = min(lo, it[2]); hi = max(hi, it[1]) }
    if (live) { lo = min(lo, c.projLow); hi = max(hi, c.projHigh) }
    val pad = (hi - lo) * 0.08 + 1e-9; lo -= pad; hi += pad
    fun y(v: Double) = (padT + (1 - (v - lo) / (hi - lo)) * H).toFloat()
    val paint = android.graphics.Paint().apply { isAntiAlias = true; textSize = 9.sp.toPx() }
    fun text(s: String, xx: Float, yy: Float, col: Color, bold: Boolean = false, center: Boolean = false) {
        paint.color = col.toArgb(); paint.isFakeBoldText = bold
        paint.textAlign = if (center) android.graphics.Paint.Align.CENTER else android.graphics.Paint.Align.LEFT
        drawIntoCanvas { it.nativeCanvas.drawText(s, xx, yy, paint) }
    }
    /** text rotated to read bottom-to-top, starting at (xx, yy) and centred on xx: a time label under a candle */
    fun upText(s: String, xx: Float, yy: Float, col: Color, bold: Boolean = false) {
        paint.color = col.toArgb(); paint.isFakeBoldText = bold; paint.textAlign = android.graphics.Paint.Align.LEFT
        drawIntoCanvas { val cv = it.nativeCanvas; cv.save(); cv.rotate(-90f, xx, yy); cv.drawText(s, xx, yy + 3.5.dp.toPx(), paint); cv.restore() }
    }
    fun tri(xc: Float, yc: Float, r: Float, up: Boolean, col: Color) {
        val p = androidx.compose.ui.graphics.Path()
        if (up) { p.moveTo(xc, yc - r); p.lineTo(xc - r, yc + r); p.lineTo(xc + r, yc + r) } else { p.moveTo(xc, yc + r); p.lineTo(xc - r, yc - r); p.lineTo(xc + r, yc - r) }
        p.close(); drawPath(p, col)
    }
    for (k in 0..4) {
        val v = lo + (hi - lo) * k / 4
        drawLine(C.Border.copy(alpha = 0.45f), Offset(0f, y(v)), Offset(W, y(v)), 1f)
        text(px(v), W + 4.dp.toPx(), y(v) + 3.dp.toPx(), C.Dim)
    }
    // round results: a tinted column behind each candle the bot called (green won / red lost / amber waiting), call arrow + bet ring at the top
    val rmap = rounds.associateBy { it.T }
    val bmap = bets.groupBy { it.T }
    val topY = padT + 8.dp.toPx()
    for (i in start until end) {
        val r = rmap[b.t[i]] ?: continue
        if (r.side == "NONE") continue
        val col = when (r.win) { true -> C.Green; false -> C.Red; null -> C.Amber }
        drawRect(col.copy(alpha = 0.14f), Offset(x(i) - step / 2, padT), Size(step, H))
        tri(x(i), topY, 3.5.dp.toPx(), r.side == "UP", col)
        bmap[b.t[i]]?.let { bl ->
            val ring = if (bl.any { it.mode == "live" && it.filled }) C.Txt else if (bl.any { it.filled }) C.Blue else C.Dim
            drawCircle(ring, 7.dp.toPx(), Offset(x(i), topY), style = Stroke(1.4.dp.toPx()))
        }
        if (step >= 11.dp.toPx() && r.win != null) text(if (r.win == true) "W" else "L", x(i), topY + 19.dp.toPx(), col, bold = true, center = true)
    }
    // a time under EVERY 5 m candle (every k-th one when they are too narrow), newest always labelled
    val k = max(1, kotlin.math.ceil(11.dp.toPx() / step).toInt())
    for (i in start until end) {
        val on = (end - 1 - i) % k == 0
        drawLine(C.Border, Offset(x(i), padT + H), Offset(x(i), padT + H + (if (on) 4 else 2).dp.toPx()), 1f)
        if (on) upText(hhmm(b.t[i]), x(i), size.height - 2.dp.toPx(), C.Dim)
    }
    val bw = max(2f, step * 0.62f)
    fun candle(xc: Float, o: Double, h: Double, l: Double, cl: Double, alpha: Float) {
        val col = (if (cl >= o) C.Green else C.Red).copy(alpha = alpha)
        drawLine(col, Offset(xc, y(h)), Offset(xc, y(l)), 1.3f)
        val top = y(max(o, cl)); val bot = y(min(o, cl))
        drawRect(col, Offset(xc - bw / 2, top), Size(bw, max(1.5f, bot - top)))
    }
    for (i in start until end) candle(x(i), b.o[i], b.h[i], b.l[i], b.c[i], 1f)
    if (f != null) candle(x(n), f[0], f[1], f[2], f[3], 1f)
    // swings with HH / HL / LH / LL labels (5m)
    for (s in c.tf.getValue(c.mk.tf).swings) {
        if (s.i < start || s.i >= end) continue
        val col = when (s.label) { "HH", "HL" -> C.Green; "LH", "LL" -> C.Red; else -> C.Muted }
        val yy = y(s.price)
        drawCircle(col, 2.5.dp.toPx(), Offset(x(s.i), if (s.high) yy - 3.dp.toPx() else yy + 3.dp.toPx()))
        if (s.label.isNotEmpty()) text(s.label, x(s.i), if (s.high) yy - 7.dp.toPx() else yy + 13.dp.toPx(), col, bold = true, center = true)
    }
    if (!live) {
        text("older candles · tap Now to return to the live round", 4.dp.toPx(), padT - 4.dp.toPx(), C.Amber, bold = true)
        return
    }
    // price to beat + live price
    val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
    drawLine(C.Amber, Offset(0f, y(c.open)), Offset(W, y(c.open)), 1.2.dp.toPx(), pathEffect = dash)
    text("beat ${px(c.open)}", 4.dp.toPx(), y(c.open) - 3.dp.toPx(), C.Amber, bold = true)
    val yl = y(livePx)
    drawLine(C.Txt.copy(alpha = 0.45f), Offset(0f, yl), Offset(W, yl), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
    val lcol = if (livePx >= c.open) C.Green else C.Red
    drawRect(lcol, Offset(W + 1.dp.toPx(), yl - 7.dp.toPx()), Size(axisW - 2.dp.toPx(), 14.dp.toPx()))
    text(px(livePx), W + 4.dp.toPx(), yl + 3.dp.toPx(), C.Bg, bold = true)
    // THIS round = the forming candle + the AI projection of the same round, bracketed and labelled with its time
    val xp = x(n + 1)
    val pcol = if (c.projClose >= c.open) C.Green else C.Red
    val pw = bw * 1.15f
    drawRect(C.Amber.copy(alpha = 0.10f), Offset(x(n) - step / 2, padT), Size(step * 2, H))
    drawRect(C.Amber, Offset(x(n) - step / 2, padT), Size(step * 2, H), style = Stroke(1.dp.toPx(), pathEffect = dash))
    text("THIS ROUND ${hhmm(c.roundStart)}–${hhmm(c.roundStart + c.roundMs)}", x(n) + step / 2, padT - 4.dp.toPx(), C.Amber, bold = true, center = true)
    upText("NOW " + hhmm(c.roundStart), x(n), size.height - 2.dp.toPx(), C.Amber, bold = true)
    upText("AI " + hhmm(c.roundStart), xp, size.height - 2.dp.toPx(), pcol, bold = true)
    drawLine(pcol, Offset(xp, y(c.projHigh)), Offset(xp, y(c.projLow)), 2f)
    var top = y(max(c.open, c.projClose)); var bot = y(min(c.open, c.projClose))
    val minH = 8.dp.toPx()
    if (bot - top < minH) { val mid = (top + bot) / 2; top = mid - minH / 2; bot = mid + minH / 2 }
    drawRect(pcol.copy(alpha = 0.85f), Offset(xp - pw / 2, top), Size(pw, bot - top))
    drawRect(C.Txt, Offset(xp - pw / 2, top), Size(pw, bot - top), style = Stroke(1.2f, pathEffect = dash))
    text(if (c.projClose >= c.open) "AI ▲" else "AI ▼", xp, padT + 11.dp.toPx(), pcol, bold = true, center = true)
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
    val selMk by vm.structMk.collectAsState()
    val bv = v?.takeIf { it.mk == selMk } ?: run { Card { T("Loading record…", 12.sp, C.Muted) }; return }
    val rm = bv.mk.round
    val cfg = bv.cfg
    val m = vm.structModel
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("Auto · ${bv.mk.label} (every round)", 14.sp, C.Txt, FontWeight.Bold)
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
                if (cfg.mode != "live") act.confirmUser("Switch ${bv.mk.label} predict to LIVE (still needs ARM LIVE to place orders)") { vm.saveStructCfg(cfg.copy(mode = "live")) }
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
            for ((lab, thr) in listOf("56%" to 56.0, "60%" to 60.0, "65%" to 65.0, "Every" to 50.0))
                Pill(lab, C.Blue, filled = cfg.thrPct == thr) { vm.saveStructCfg(cfg.copy(thrPct = thr)) }
        }
        Gap(6.dp)
        HourClusters(cfg) { vm.saveStructCfg(cfg.copy(hoursMask = it)) }
        Gap(4.dp)
        if (!bv.mk.s1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            T("Max price", 11.sp, C.Muted)
            Pill("Off", C.Blue, filled = cfg.maxPriceCents <= 0.0) { vm.saveStructCfg(cfg.copy(maxPriceCents = 0.0)) }
            for (cp in listOf(50.0, 55.0, 60.0, 65.0)) Pill("${cp.toInt()}c", C.Blue, filled = cfg.maxPriceCents == cp) { vm.saveStructCfg(cfg.copy(maxPriceCents = cp)) }
        }
        T(if (cfg.maxPriceCents > 0.0) "Never buys a share that costs more than ${cfg.maxPriceCents.toInt()} cents, even if the edge check passes; " +
            "the quote is re-checked until the window closes. Early test only (ETH, 5 days of real prices): a 60 cent cap looked better than no cap."
          else "Off: only the min-edge check limits the price you pay.", 10.sp, C.Dim)
        Gap(4.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            T("Stake", 11.sp, C.Muted)
            Pill("Flat", C.Blue, filled = cfg.stakeMode !in Staking.MODES || cfg.stakeMode == "flat") { vm.saveStructCfg(cfg.copy(stakeMode = "flat")) }
            Pill("By edge", C.Blue, filled = cfg.stakeMode == "edge") { vm.saveStructCfg(cfg.copy(stakeMode = "edge")) }
            Pill("Edge tiers", C.Blue, filled = cfg.stakeMode == "tiers") { vm.saveStructCfg(cfg.copy(stakeMode = "tiers")) }
            Pill("Quarter-Kelly", C.Blue, filled = cfg.stakeMode == "kelly") { vm.saveStructCfg(cfg.copy(stakeMode = "kelly")) }
        }
        if (cfg.stakeMode == "kelly") Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            T("Paper bankroll", 11.sp, C.Muted)
            for (b in listOf(50.0, 100.0, 200.0, 500.0)) Pill("${b.toInt()}", C.Blue, filled = cfg.bankroll == b) { vm.saveStructCfg(cfg.copy(bankroll = b)) }
        }
        T(when (cfg.stakeMode) {
            "edge" -> "Stake = base stake x edge / ${cfg.edgeUnitPct.toInt()}%, at most ${cfg.stakeCapX.toInt()}x the base (LIVE minimum 1.5 USDT). " +
                "Edge = backtest win rate x 0.98 / share price - 1, so a cheaper share gets a bigger bet and no edge = no bet. Early backtest only: test it on PAPER first."
            "tiers" -> "Stake by edge size: below 0% no bet, 0-5% half the base stake, 5-10% the base stake, 10% or more double (LIVE minimum 1.5 USDT, so " +
                "use a base of 3 or more for the half tier to be smaller). Real-price test (Mar-Nov 2026 sample): ETH 1s about break-even, ETH 5m and BTC 5m lost money. Paper first."
            "kelly" -> "Stake = ${(cfg.kellyFrac * 100).toInt()}% of the Kelly stake x bankroll, at most ${String.format(US, "%.0f", cfg.kellyCapPct)}% of the bankroll per bet " +
                "(bankroll = your real Prediction balance in LIVE, the paper bankroll above in PAPER; LIVE minimum 1.5 USDT). The base stake is not used. " +
                "It sizes bets, it does not create an edge: on real prices (4,000-6,000 sampled rounds per market, Mar-Nov 2026) ETH 1s was about break-even " +
                "(+0.6% ROI, not significant), ETH 5m -3% and BTC 5m -5%. Biggest swings of all methods. Paper only until real quotes show a profit."
            else -> "Flat: every bet is the base stake."
        }, 10.sp, C.Dim)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            T("Preset", 11.sp, C.Muted)
            Pill("S4 backtest settings", C.Green) { vm.saveStructCfg(Btc5mStructBook.Cfg.s4Backtest(cfg)) }
            Pill("Best ROI mix", C.Green) { vm.saveStructCfg(cfg.copy(minEdgePct = 5.0, minP = 0.0, maxPriceCents = 60.0, stakeMode = "edge")) }
        }
        T("S4 backtest settings (default for the ETH 1-second market): every round is a call (50% rule), bet only when the win rate beats the share price, " +
            "quarter-Kelly stake capped at 4% of the bankroll, no price cap, all hours. Best ROI mix: min edge 5%, max price 60 cents, stake by edge. " +
            "Simulated-price backtests looked profitable, but on REAL Polymarket prices none of these rules beat break-even with confidence " +
            "(ETH 1s about 0%, ETH 5m and BTC 5m negative): keep them on PAPER and watch real results.", 10.sp, C.Dim)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            T("LIVE orders per round", 11.sp, C.Muted)
            for (n in listOf(1, 2, 3, 5)) Pill(if (n == 1) "1 (no retry)" else "$n", C.Blue, filled = cfg.orderTries == n) { vm.saveStructCfg(cfg.copy(orderTries = n)) }
        }
        T("If the exchange kills an order (Failed, nothing bought) the app gets a fresh quote and sends it again, up to this many orders in the entry window. " +
            "Only killed orders are repeated; an order that is pending or filled is never repeated.", 10.sp, C.Dim)
        Gap(4.dp)
        if (cfg.early) T("Buys at ${cfg.leadSec} s before the round starts (e.g. 12:${if (bv.mk.roundMin == 15) "59" else "54"}:${60 - cfg.leadSec} for the ${if (bv.mk.roundMin == 15) "13:00" else "12:55"} round). If the buy can't be made " +
            "before the start, that round is skipped: no buying after the start.", 11.sp, C.Muted)
        else Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            T("Entry window", 11.sp, C.Muted)
            for (s in listOf(20, 30, 45, 60, 90)) Pill(String.format(US, "%d:%02d", s / 60, s % 60), C.Blue, filled = cfg.windowSec == s) {
                vm.saveStructCfg(cfg.copy(windowSec = s))
            }
        }
        T(if (cfg.minEdgePct <= -100.0) "Off: every call is bet at the Binance quote, whatever the price. " +
            "It loses money whenever the share costs more than the win rate (2% fee included)."
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
        T("Backtest · ${bv.mk.label} · structure AI + order flow (${m?.testStart ?: ""} → ${m?.testEnd ?: ""}, ${m?.days ?: 180} days walk-forward, \$1 per bet, 2% fee)", 12.sp, C.Txt, FontWeight.Bold)
        for (b in (m?.backtest ?: emptyList()).sortedByDescending { it.thr }) {
            val on = kotlin.math.abs(cfg.thrPct / 100 - b.thr) < 1e-6
            val lab = if (b.thr <= 0.5) "Every round" else "${(b.thr * 100).toInt()}% rule"
            T("${if (on) "▶ " else "   "}$lab: ${b.perDay.toInt()}/day · won ${String.format(US, "%.1f", b.win * 100)}% · " +
                "at ${m!!.btPx[0]}¢ ${String.format(US, "%+.0f", b.pnl50)} · at ${m.btPx[2]}¢ ${if (b.pnl54.isNaN()) "-" else String.format(US, "%+.0f", b.pnl54)} · max drop ${b.dd.toInt()}",
                11.sp, if (on) C.Txt else C.Muted, if (on) FontWeight.Bold else FontWeight.Normal)
        }
        T(if (m?.gap == true) "Since 7 Aug 2026 the rounds settle on the TWAP rule: Up if the average price of the last 60 s >= the average of the 60 s " +
            "before the start. This model is trained on that rule and scored on the REAL market results. At the start the likely side " +
            "already costs ~62-70¢ (it was ~66¢ for 60% calls on 12-23 Nov), so keep the price check on." else
            "You pay about 54¢ per share with the fee on real Binance quotes, so the \"at 54¢\" column is the realistic one.", 10.sp, C.Dim)
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
        T("Prices include the 2% fee. Break-even win rate = price paid per share. Paper uses a real Binance quote for every bet when a " +
            "Prediction key is saved (no order is sent); if no real quote can be had the paper bet is skipped, never priced at an assumed " +
            "price (without a key bets are marked ASSUMED). Results = the real Polymarket result; if it is late, a Binance estimate is shown " +
            "first and replaced by the real result as soon as it is published (checked for 48 h).", 10.sp, C.Dim)
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
                    T("${hhmm(b.T)}–${hhmm(b.T + rm)}", 11.sp, C.Txt, mono = true); HGap(6.dp)
                    T(b.mode.uppercase(), 10.sp, if (b.mode == "live") C.Green else C.Blue, FontWeight.Bold); HGap(6.dp)
                    T("${b.side} @${String.format(us, "%.1f", b.share * 100)}¢ · ${f2(b.stake)}" + (if (b.status != "OK" && b.status != "FILLED") " · ${b.status}" else ""),
                        11.sp, if (b.status != "OK" && b.status != "FILLED") C.Amber else sideCol(b.side), mono = true, modifier = Modifier.weight(1f))
                    val p = b.pnl
                    T(when { !b.filled -> "not filled"; p != null -> String.format(us, "%+.2f", p); b.up == null && nowMs < b.T + rm -> "OPEN"; else -> "settling…" },
                        11.sp, when { !b.filled -> C.Muted; p == null -> C.Amber; p >= 0 -> C.Green; else -> C.Red }, FontWeight.Bold, mono = true)
                    HGap(8.dp)
                    T("✕", 11.sp, C.Dim, modifier = Modifier.clickable { vm.deleteStructBet(b.id) })
                }
                val placed = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(pk.aidesk.app.core.Engine.PKT)
                    .format(java.time.Instant.ofEpochMilli(b.id))
                T("bet placed $placed · " + when {
                    !b.filled -> "the exchange killed this order (Failed, 0% filled): nothing was bought, no money spent, not counted in the results"
                    b.pnl != null ->"${if (b.up == 2) "TIE" else if (b.win == true) "WIN" else "LOSS"} · settled"
                    nowMs < b.T -> "round starts in ${mmss(b.T - nowMs)}"
                    nowMs < b.T + rm -> "result in ${mmss(b.T + rm - nowMs)}"
                    else -> "round ended, result in a few seconds"
                }, 10.sp, C.Muted)
            }
            Gap(6.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            T("Export CSV", 12.sp, C.Blue, FontWeight.SemiBold, modifier = Modifier.clickable { act.exportCsv("btc5ms") })
            T("Clear paper", 12.sp, C.Amber, FontWeight.SemiBold, modifier = Modifier.clickable { act.confirmUser("Delete all ${bv.mk.label} paper bets") { vm.clearStruct("paper") } })
            T("Clear rounds", 12.sp, C.Amber, FontWeight.SemiBold, modifier = Modifier.clickable { act.confirmUser("Delete all recorded ${bv.mk.label} rounds") { vm.clearStruct("rounds") } })
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

/** Hour clusters (PKT, 4-hour blocks, weekdays and weekend separately): rounds outside are recorded but not bet. */
@Composable
private fun HourClusters(cfg: Btc5mStructBook.Cfg, save: (Int) -> Unit) {
    val M = Btc5mStructBook.Cfg
    val mask = cfg.hoursMask
    T("Hour clusters (Pakistan time)", 12.sp, C.Txt, FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        T("Preset", 11.sp, C.Muted)
        Pill("All hours", C.Blue, filled = mask == M.ALL_HOURS) { save(M.ALL_HOURS) }
        Pill("Skip weak", C.Blue, filled = mask == M.SKIP_WEAK) { save(M.SKIP_WEAK) }
        Pill("Night + weekend", C.Blue, filled = mask == M.NIGHT_WEEKEND) { save(M.NIGHT_WEEKEND) }
    }
    for ((lab, off) in listOf("Mon–Fri" to 0, "Sat–Sun" to 6)) {
        Gap(4.dp)
        T(lab, 11.sp, C.Muted)
        for (half in listOf(0, 3)) Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in half until half + 3) {
                val bit = 1 shl (off + i)
                val on = mask and bit != 0
                Pill(M.BLOCKS[i], if (on) C.Green else C.Muted, filled = on) { save(mask xor bit) }
            }
        }
    }
    val nowOn = cfg.inHours(System.currentTimeMillis())
    T(when {
        mask == 0 -> "No cluster selected: nothing will be bet."
        cfg.allHours -> "Bets in every hour."
        else -> "Now ${M.label(System.currentTimeMillis())}: ${if (nowOn) "ON, bets allowed" else "OFF, calls are recorded but not bet"}."
    }, 11.sp, if (mask == 0 || !nowOn) C.Amber else C.Green)
    T("Hour clusters were picked on the old model (2026-11-24); re-check them on the TWAP model before relying on them.", 10.sp, C.Dim)
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
