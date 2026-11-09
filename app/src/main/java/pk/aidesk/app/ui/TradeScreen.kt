package pk.aidesk.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pk.aidesk.app.MainActivity
import pk.aidesk.app.TopBar
import pk.aidesk.app.core.Advisor
import pk.aidesk.app.core.ManualPlan
import pk.aidesk.app.core.Ticket
import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Plain number for text fields (no thousands separator). */
private fun num(v: Double): String = if (v == 0.0) "0" else BigDecimal(v).round(MathContext(7)).stripTrailingZeros().toPlainString()
private fun pctS(v: Double) = String.format(Locale.US, "%+.2f%%", v)

@Composable
fun TradeScreen(vm: AppVM, act: MainActivity) {
    val sym by vm.tradeSym.collectAsState()
    LaunchedEffect(Unit) { vm.loadManual(); if (vm.coins.value.isEmpty()) vm.loadCoins() }
    if (sym == null) CoinPicker(vm) else CoinView(vm, act, sym!!)
}

// ================================================================== coin list
@Composable
private fun CoinPicker(vm: AppVM) {
    val coins by vm.coins.collectAsState()
    val err by vm.coinsErr.collectAsState()
    val plans by vm.manual.collectAsState()
    val cfg by vm.config.collectAsState()
    var q by remember { mutableStateOf("") }
    val act = plans.filter { it.active }
    Column(Modifier.fillMaxSize()) {
        TopBar(vm, "Trade")
        OutlinedTextField(q, { q = it.uppercase().trim() }, singleLine = true, colors = fieldColors(),
            placeholder = { Text("Search any coin, e.g. SOL, PEPE…") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            if (act.isNotEmpty()) {
                item { SectionTitle("My orders (${cfg.mode.uppercase()})") }
                for (p in act) item(key = "m" + p.id) {
                    PlanRow(p, onOpen = { vm.openCoin(p.symbol) }, onCancel = { vm.cancelManual(p.id) })
                    Gap(6.dp)
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(if (q.isEmpty()) "Most traded" else "Results")
                    Box(Modifier.weight(1f))
                    T("Refresh", 12.sp, C.Blue, FontWeight.SemiBold, modifier = Modifier.clickable { vm.loadCoins() })
                }
                err?.let { T(it, 12.sp, C.Amber) }
                if (coins.isEmpty() && err == null) T("Loading coins…", 13.sp, C.Muted)
            }
            val list = if (q.isEmpty()) coins.take(80) else coins.filter { it.symbol.contains(q) }.take(80)
            for (cr in list) item(key = "c" + cr.symbol) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.openCoin(cr.symbol) }.padding(vertical = 9.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T(cr.symbol.removeSuffix("USDT"), 15.sp, C.Txt, FontWeight.Bold)
                        T("Vol ${String.format(Locale.US, "%.1f", cr.volume / 1e6)}M", 11.sp, C.Dim)
                    }
                    T(px(cr.price), 14.sp, C.Txt, mono = true); HGap(10.dp)
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(if (cr.changePct >= 0) C.GreenBg else C.RedBg).padding(horizontal = 8.dp, vertical = 3.dp)) {
                        T(pctS(cr.changePct), 12.sp, if (cr.changePct >= 0) C.Green else C.Red, FontWeight.Bold)
                    }
                }
            }
            item { Gap(24.dp) }
        }
    }
}

@Composable
private fun PlanRow(p: ManualPlan, onOpen: (() -> Unit)?, onCancel: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Card(border = if (p.status == "open") C.side(p.side) else C.Border, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(p.symbol.removeSuffix("USDT"), 14.sp, C.Txt, FontWeight.Bold); HGap(6.dp)
            Pill(p.side, C.side(p.side), filled = true); HGap(6.dp)
            Pill(if (p.status == "pending") "${p.type} WAITING" else "OPEN", if (p.status == "pending") C.Amber else C.Green)
            Box(Modifier.weight(1f))
            T(if (p.status == "pending") "Cancel" else "Close", 13.sp, C.Red, FontWeight.Bold, modifier = Modifier.clickable { confirm = true })
        }
        T("Entry ${px(p.fill ?: p.entry)} · SL ${px(p.sl)} · TP ${px(p.tp)} · qty ${px(p.qty)} · ${p.lev}x", 11.sp, C.Muted, mono = true)
        T((if (p.setup.isNotEmpty()) p.setup + " · " else "") + "placed ${dayHhmm(p.created)}" +
            if (p.status == "pending") " · expires ${dayHhmm(p.expires)}" else "", 11.sp, C.Dim)
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        confirmButton = { TextButton({ confirm = false; onCancel() }) { Text(if (p.status == "pending") "Cancel order" else "Close at market", color = C.Red) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Keep") } },
        title = { Text(if (p.status == "pending") "Cancel ${p.symbol} order?" else "Close ${p.symbol} ${p.side}?") },
        text = { Text(if (p.status == "pending") "The waiting entry order and its SL/TP are removed." else "Closes the position at market price now.") },
        containerColor = C.Panel)
}

// ================================================================== coin view
@Composable
private fun CoinView(vm: AppVM, act: MainActivity, sym: String) {
    val tf by vm.tradeTf.collectAsState()
    val a by vm.analysis.collectAsState()
    val err by vm.analysisErr.collectAsState()
    val loading by vm.analysisLoading.collectAsState()
    val cfg by vm.config.collectAsState()
    val plans by vm.manual.collectAsState()
    var sel by remember(sym, tf) { mutableStateOf(0) }
    var bars by remember { mutableStateOf(120) }
    val ticket = remember(sym) { TicketState(cfg.leverage.coerceIn(1, 20)) }
    DisposableEffect(sym) { onDispose { vm.stopAnalysis() } }
    LaunchedEffect(sym) { vm.loadManual() }

    val an = a?.takeIf { it.symbol == sym && it.tf == tf }
    val plan = an?.plans?.getOrNull(sel)
    // first analysis for this coin/timeframe: pre-fill the ticket with the top suggestion
    LaunchedEffect(an?.time) { if (an != null && !ticket.touched) an.best?.let { ticket.fill(it) } }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                T("←", 22.sp, C.Txt, FontWeight.Bold, modifier = Modifier.clickable { vm.closeCoin() }.padding(end = 12.dp))
                Column(Modifier.weight(1f)) {
                    T(sym.removeSuffix("USDT") + " / USDT", 18.sp, C.Txt, FontWeight.Bold)
                    T(an?.let { px(it.price) } ?: "…", 13.sp, C.Muted, mono = true)
                }
                if (loading) T("updating…", 11.sp, C.Dim)
                HGap(8.dp)
                Pill(cfg.mode.uppercase(), C.mode(cfg.mode), filled = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (t in Advisor.TFS) Pill(t, if (t == tf) C.Blue else C.Muted, filled = t == tf) { vm.setTf(t) }
                Box(Modifier.weight(1f))
                for (b in listOf(60, 120, 240)) Pill("$b", if (b == bars) C.Txt else C.Dim, filled = false) { bars = b }
            }
            Gap(8.dp)
            if (an == null) {
                Box(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(12.dp)).background(C.Panel), contentAlignment = Alignment.Center) {
                    T(err ?: "Loading $tf chart…", 13.sp, if (err != null) C.Amber else C.Muted)
                }
            } else {
                PatternChart(an, bars, ticket.levels())
                Gap(4.dp)
                Legend()
            }
        }
        if (an != null) {
            item {
                SectionTitle("Analysis")
                Card {
                    for ((i, line) in an.summary.withIndex()) {
                        T(line, if (i == an.summary.size - 1) 14.sp else 13.sp, if (i == an.summary.size - 1) C.Txt else C.Muted,
                            if (i == an.summary.size - 1) FontWeight.Bold else FontWeight.Normal)
                        Gap(3.dp)
                    }
                    T("Rule-based read of the chart, not a promise. Laptop backtests found no reliable edge in chart patterns alone: keep size small.", 10.sp, C.Dim)
                }
                SectionTitle("Suggested trades")
                if (an.plans.isEmpty()) T("No clean setup on $tf right now. Try another timeframe or wait.", 13.sp, C.Muted)
            }
            for ((i, p) in an.plans.withIndex()) item(key = "p$i${p.setup}${p.side}") {
                SuggestionCard(p, i == sel, onSelect = { sel = i }, onUse = { sel = i; ticket.fill(p); ticket.touched = true })
                Gap(6.dp)
            }
            item {
                SectionTitle("Order ticket")
                TicketCard(vm, act, sym, ticket, cfg.mode)
            }
        }
        val mine = plans.filter { it.symbol == sym }
        if (mine.isNotEmpty()) {
            item { SectionTitle("Your $sym orders") }
            for (p in mine.take(10)) item(key = "o" + p.id) {
                if (p.active) PlanRow(p, null) { vm.cancelManual(p.id) }
                else Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    T("${p.side} ${p.type.lowercase()} · ${p.status}${p.reason?.let { " · $it" } ?: ""}", 12.sp, C.Muted, modifier = Modifier.weight(1f))
                    p.pnl?.let { T(sgn2(it), 13.sp, if (it >= 0) C.Green else C.Red, FontWeight.Bold) }
                }
                Gap(6.dp)
            }
        }
        item { Gap(32.dp) }
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((c, l) in listOf(C.Red to "resistance", C.Green to "support", C.Amber to "neckline", Color(0xFF3B82F6) to "EMA20",
            Color(0xFFA78BFA) to "EMA50")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.height(3.dp).padding(horizontal = 6.dp).background(c)); T(l, 10.sp, C.Muted)
            }
        }
    }
}

@Composable
private fun SuggestionCard(p: Advisor.Plan, selected: Boolean, onSelect: () -> Unit, onUse: () -> Unit) {
    val gc = when (p.grade) { "Strong" -> C.Green; "Good" -> C.Blue; "Weak" -> C.Amber; else -> C.Red }
    Card(border = if (selected) C.Blue else C.Border, onClick = onSelect) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(p.side, C.side(p.side), filled = true); HGap(6.dp)
            Pill(p.type, C.Muted); HGap(6.dp)
            T(p.setup, 13.sp, C.Txt, FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 2)
            Pill("${p.grade} ${if (p.score >= 0) "+" else ""}${p.score}", gc)
        }
        Gap(4.dp)
        Row {
            KVmini("Entry", px(p.entry), C.Txt); KVmini("Stop", px(p.sl), C.Red); KVmini("Target", px(p.tp), C.Green)
            KVmini("R:R", String.format(Locale.US, "%.2f", p.rr), if (p.rr >= 2) C.Green else if (p.rr < 1.2) C.Red else C.Txt)
        }
        if (selected) {
            Gap(4.dp)
            for (w in p.why) T(w, 11.sp, when { w.startsWith("+") -> C.Green; w.startsWith("-") -> C.Red; else -> C.Muted })
        }
        Gap(6.dp)
        Btn("Use this in the ticket", C.Blue, outline = true, height = 36.dp) { onUse() }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.KVmini(k: String, v: String, c: Color) {
    Column(Modifier.weight(1f)) { T(k, 10.sp, C.Dim); T(v, 12.sp, c, FontWeight.SemiBold, mono = true, maxLines = 1) }
}

// ================================================================== ticket
private class TicketState(defLev: Int) {
    var side by mutableStateOf("LONG")
    var type by mutableStateOf("LIMIT")
    var entry by mutableStateOf("")
    var sl by mutableStateOf("")
    var tp by mutableStateOf("")
    var risk by mutableStateOf(2.0)
    var lev by mutableStateOf(defLev)
    var hours by mutableStateOf(24)
    var setup by mutableStateOf("")
    var touched = false

    fun fill(p: Advisor.Plan) {
        side = p.side; type = p.type; entry = num(p.entry); sl = num(p.sl); tp = num(p.tp); setup = p.setup
    }

    fun ticket(sym: String) = Ticket(sym, side, type, entry.toDoubleOrNull() ?: 0.0, sl.toDoubleOrNull() ?: 0.0, tp.toDoubleOrNull() ?: 0.0,
        risk, lev, hours, setup)

    /** entry, sl, tp, side for drawing on the chart */
    fun levels(): ChartLevels? {
        val e = entry.toDoubleOrNull(); val s = sl.toDoubleOrNull(); val t = tp.toDoubleOrNull()
        return if (s == null && t == null && e == null) null else ChartLevels(e, s, t, side)
    }
}

class ChartLevels(val entry: Double?, val sl: Double?, val tp: Double?, val side: String)

@Composable
private fun TicketCard(vm: AppVM, act: MainActivity, sym: String, t: TicketState, mode: String) {
    val q by vm.quote.collectAsState()
    val qe by vm.quoteErr.collectAsState()
    val busy by vm.busy.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    val key = listOf(t.side, t.type, t.entry, t.sl, t.tp, t.risk, t.lev, sym)
    LaunchedEffect(key) { delay(450); vm.quoteTicket(t.ticket(sym)) }
    Card {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("LONG", C.Green, Modifier.weight(1f), outline = t.side != "LONG", height = 40.dp) { t.side = "LONG"; t.touched = true }
            Btn("SHORT", C.Red, Modifier.weight(1f), outline = t.side != "SHORT", height = 40.dp) { t.side = "SHORT"; t.touched = true }
        }
        Gap(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            T("Order", 12.sp, C.Muted)
            for ((k, l) in listOf("LIMIT" to "Limit", "STOP" to "Stop (breakout)", "MARKET" to "Market"))
                Pill(l, if (t.type == k) C.Blue else C.Muted, filled = t.type == k) { t.type = k; t.touched = true }
        }
        T(when (t.type) {
            "LIMIT" -> "Waits for price to come to your entry (retest / pullback)."
            "STOP" -> "Enters only when price breaks through your entry (breakout)."
            else -> "Enters now at market price."
        }, 11.sp, C.Dim)
        Gap(6.dp)
        if (t.type != "MARKET") PriceField("Entry price", t.entry) { t.entry = it; t.touched = true }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { PriceField("Stop-loss", t.sl) { t.sl = it; t.touched = true } }
            Box(Modifier.weight(1f)) { PriceField("Take-profit", t.tp) { t.tp = it; t.touched = true } }
        }
        Gap(4.dp)
        Stepper("Risk if stopped", String.format(Locale.US, "%.1f%% of equity", t.risk), "Size is worked out from this and the stop distance",
            onMinus = { t.risk = (t.risk - if (t.risk > 2) 1.0 else 0.5).coerceAtLeast(0.5) },
            onPlus = { t.risk = (t.risk + if (t.risk >= 2) 1.0 else 0.5).coerceAtMost(20.0) })
        Stepper("Leverage", "${t.lev}x", "Margin = position ÷ leverage (isolated)",
            onMinus = { t.lev = (t.lev - 1).coerceAtLeast(1) }, onPlus = { t.lev = (t.lev + 1).coerceAtMost(50) })
        if (t.type != "MARKET") Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            T("Valid for", 12.sp, C.Muted)
            for (h in listOf(4, 24, 72)) Pill("${h}h", if (t.hours == h) C.Blue else C.Muted, filled = t.hours == h) { t.hours = h }
        }
        Gap(8.dp)
        val qq = q
        if (qe != null) T(qe!!, 12.sp, C.Amber)
        if (qq != null) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.Panel2).padding(10.dp)) {
                Column {
                    KV("Size", "${px(qq.qty)} ${sym.removeSuffix("USDT")} (${String.format(Locale.US, "%.2f", qq.notional)} USDT)")
                    KV("Margin used", String.format(Locale.US, "%.2f USDT", qq.margin))
                    KV("Loss at stop", String.format(Locale.US, "-%.2f USDT", qq.riskUsdt), C.Red)
                    KV("Profit at target", String.format(Locale.US, "+%.2f USDT", qq.rewardUsdt), C.Green)
                    KV("Reward : risk", String.format(Locale.US, "%.2f", qq.rr))
                    KV("Equity", String.format(Locale.US, "%.2f USDT", qq.equity))
                    for (w in qq.warnings) T("• $w", 11.sp, C.Amber)
                    qq.block?.let { T(it, 12.sp, C.Red, FontWeight.SemiBold) }
                }
            }
        }
        Gap(8.dp)
        val ok = qq != null && qq.block == null && !busy
        Btn(if (mode == "live") "Place LIVE ${t.side} order" else "Place ${mode.uppercase()} ${t.side} order", C.side(t.side), enabled = ok) { confirm = true }
        T("Stop-loss and take-profit are placed on Binance as close-position orders. For limit/stop entries they are added the moment the entry fills (the app checks every 10 s).",
            10.sp, C.Dim, modifier = Modifier.padding(top = 4.dp))
    }
    if (confirm) {
        val tk = t.ticket(sym)
        AlertDialog(onDismissRequest = { confirm = false },
            confirmButton = {
                TextButton({
                    confirm = false
                    val go = { vm.placeManual(tk) { } }
                    if (mode == "live") act.confirmUser("Place LIVE order") { go() } else go()
                }) { Text("Place order", color = C.side(tk.side)) }
            },
            dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
            title = { Text("${tk.side} $sym · ${tk.type.lowercase()} (${mode.uppercase()})") },
            text = {
                Text((if (tk.type == "MARKET") "Entry: market now\n" else "Entry: ${num(tk.entry)} (valid ${tk.validHours} h)\n") +
                    "Stop-loss: ${num(tk.sl)}\nTake-profit: ${num(tk.tp)}\nLeverage: ${tk.lev}x · risk ${tk.riskPct}% of equity" +
                    (q?.let { "\nSize: ${px(it.qty)} (${String.format(Locale.US, "%.2f", it.notional)} USDT)" } ?: ""))
            }, containerColor = C.Panel)
    }
}

@Composable
private fun PriceField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, { v -> onChange(v.filter { it.isDigit() || it == '.' }) }, singleLine = true, colors = fieldColors(),
        label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
}

// ================================================================== chart
private val Ema20 = Color(0xFF3B82F6)
private val Ema50 = Color(0xFFA78BFA)

@Composable
private fun PatternChart(a: Advisor.Analysis, barsWanted: Int, lv: ChartLevels?) {
    Canvas(Modifier.fillMaxWidth().height(340.dp).clip(RoundedCornerShape(12.dp)).background(C.Panel)) {
        drawPatternChart(a, barsWanted, lv)
    }
}

private fun DrawScope.drawPatternChart(a: Advisor.Analysis, barsWanted: Int, lv: ChartLevels?) {
    val cl = a.closed
    val n = cl.size
    if (n < 5) return
    val show = min(barsWanted, n)
    val start = n - show
    val fut = max(6, show / 10)
    val hasForm = a.forming != null && a.forming.size > 0
    val slots = show + (if (hasForm) 1 else 0) + fut
    val axisW = 64.dp.toPx()
    val padT = 8.dp.toPx(); val padB = 18.dp.toPx()
    val W = size.width - axisW
    val H = size.height - padT - padB
    val step = W / slots
    fun x(i: Double) = ((i - start) + 0.5) * step
    fun x(i: Int) = x(i.toDouble())
    val lastIdx = n - 1
    val endIdx = n + fut                        // right edge (index space)

    // price range: candles + visible pattern lines + ticket levels
    var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
    for (i in start until n) { lo = min(lo, cl.low[i]); hi = max(hi, cl.high[i]) }
    if (hasForm) { lo = min(lo, a.forming!!.low[0]); hi = max(hi, a.forming.high[0]) }
    val span0 = hi - lo
    fun incl(v: Double?) { if (v != null && v.isFinite() && v > lo - span0 && v < hi + span0) { lo = min(lo, v); hi = max(hi, v) } }
    for (p in a.scan.active) { p.up?.let { incl(it.at(n)) }; p.dn?.let { incl(it.at(n)) } }
    lv?.let { incl(it.entry); incl(it.sl); incl(it.tp) }
    val pad = (hi - lo) * 0.06 + 1e-12
    lo -= pad; hi += pad
    fun y(v: Double) = (padT + (1 - (v - lo) / (hi - lo)) * H).toFloat()

    val paint = android.graphics.Paint().apply { isAntiAlias = true; textSize = 10.sp.toPx(); color = C.Muted.toArgb() }
    fun text(s: String, xx: Float, yy: Float, col: Color, bold: Boolean = false, alignRight: Boolean = false) {
        paint.color = col.toArgb(); paint.isFakeBoldText = bold
        paint.textAlign = if (alignRight) android.graphics.Paint.Align.RIGHT else android.graphics.Paint.Align.LEFT
        drawIntoCanvas { it.nativeCanvas.drawText(s, xx, yy, paint) }
    }
    fun tag(v: Double, label: String, col: Color) {
        val yy = y(v)
        if (yy < padT || yy > padT + H) return
        drawRect(col, Offset(W + 2f, yy - 8.dp.toPx()), Size(axisW - 4f, 16.dp.toPx()))
        text(label, W + 5.dp.toPx(), yy + 3.5f.dp.toPx(), C.Bg, bold = true)
    }

    // grid + axis
    for (k in 0..4) {
        val v = lo + (hi - lo) * k / 4
        drawLine(C.Border.copy(alpha = 0.5f), Offset(0f, y(v)), Offset(W, y(v)), 1f)
        text(px(v), W + 4.dp.toPx(), y(v) + 3.dp.toPx(), C.Dim)
    }
    // future area
    val futX = (x(n + (if (hasForm) 1 else 0)) - step / 2).toFloat()
    drawRect(C.Panel2.copy(alpha = 0.6f), Offset(futX, padT), Size(W - futX, H))

    // pattern zones (behind candles)
    for (p in a.scan.active) {
        val up = p.up; val dn = p.dn
        if (up != null && dn != null && !p.neck) {
            val s0 = max(p.start, start).toDouble()
            val e0 = min(p.expires.toDouble(), endIdx.toDouble())
            val path = Path().apply {
                moveTo(x(s0).toFloat(), y(up.at(s0))); lineTo(x(e0).toFloat(), y(up.at(e0)))
                lineTo(x(e0).toFloat(), y(dn.at(e0))); lineTo(x(s0).toFloat(), y(dn.at(s0))); close()
            }
            val zc = when (p.bias) { 1 -> C.Green; -1 -> C.Red; else -> C.Blue }
            drawPath(path, zc.copy(alpha = 0.10f))
        }
    }
    // EMAs
    for ((arr, col) in listOf(a.ema20 to Ema20, a.ema50 to Ema50)) {
        val path = Path()
        for (i in start until n) { val px0 = x(i).toFloat(); val py = y(arr[i]); if (i == start) path.moveTo(px0, py) else path.lineTo(px0, py) }
        drawPath(path, col.copy(alpha = 0.7f), style = Stroke(1.2f.dp.toPx()))
    }
    // candles
    val bw = max(1.5f, (step * 0.62f).toFloat())
    fun candle(xc: Float, o: Double, h: Double, l: Double, c: Double, alpha: Float) {
        val col = (if (c >= o) C.Green else C.Red).copy(alpha = alpha)
        drawLine(col, Offset(xc, y(h)), Offset(xc, y(l)), 1.2f)
        val top = y(max(o, c)); val bot = y(min(o, c))
        drawRect(col, Offset(xc - bw / 2, top), Size(bw, max(1.5f, bot - top)))
    }
    for (i in start until n) candle(x(i).toFloat(), cl.open[i], cl.high[i], cl.low[i], cl.close[i], 1f)
    if (hasForm) { val f = a.forming!!; candle(x(n).toFloat(), f.open[0], f.high[0], f.low[0], f.close[0], 0.55f) }

    // pattern lines, points, labels (on top of candles)
    val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
    for (p in a.scan.active) {
        val lines = listOfNotNull(p.up?.let { it to (if (p.neck) C.Amber else C.Red) }, p.dn?.let { it to (if (p.neck) C.Amber else C.Green) })
        for ((ln, col) in lines) {
            val s0 = (if (p.neck) ln.i0 else max(p.start, start)).coerceAtLeast(start).toDouble()
            val nowI = n.toDouble()
            val e0 = min(p.expires.toDouble(), endIdx.toDouble())
            drawLine(col, Offset(x(s0).toFloat(), y(ln.at(s0))), Offset(x(nowI).toFloat(), y(ln.at(nowI))), 2.6f.dp.toPx())
            if (e0 > nowI) drawLine(col, Offset(x(nowI).toFloat(), y(ln.at(nowI))), Offset(x(e0).toFloat(), y(ln.at(e0))), 2f.dp.toPx(), pathEffect = dash)
        }
        if (p.neck) {
            val path = Path()
            p.points.filter { it.i >= start }.forEachIndexed { k, pt -> if (k == 0) path.moveTo(x(pt.i).toFloat(), y(pt.price)) else path.lineTo(x(pt.i).toFloat(), y(pt.price)) }
            drawPath(path, C.Txt.copy(alpha = 0.55f), style = Stroke(1.4f.dp.toPx()))
        }
        for (pt in p.points) {
            if (pt.i < start) continue
            drawCircle(C.Panel, 4.5f.dp.toPx(), Offset(x(pt.i).toFloat(), y(pt.price)))
            drawCircle(C.Txt, 4.5f.dp.toPx(), Offset(x(pt.i).toFloat(), y(pt.price)), style = Stroke(2f.dp.toPx()))
            if (pt.label.isNotEmpty()) text(pt.label, x(pt.i).toFloat() - 10.dp.toPx(), y(pt.price) - 8.dp.toPx(), C.Txt, bold = true)
        }
        val topV = listOfNotNull(p.up?.at(max(p.start, start)), p.points.maxOfOrNull { it.price }).maxOrNull() ?: continue
        val nameX = x(max(p.start, start)).toFloat()
        val nameY = (y(topV) - 10.dp.toPx()).coerceAtLeast(padT + 10.dp.toPx())
        val nc = when (p.bias) { 1 -> C.Green; -1 -> C.Red; else -> C.Blue }
        val label = "${p.name} · ${p.touches} touches"
        paint.textSize = 10.sp.toPx(); paint.isFakeBoldText = true
        val tw = paint.measureText(label)
        drawRect(nc, Offset(nameX - 3.dp.toPx(), nameY - 11.dp.toPx()), Size(tw + 6.dp.toPx(), 15.dp.toPx()))
        text(label, nameX, nameY, Color.White, bold = true)
        // measured-move targets in the future area
        val tx = x(n + fut * 0.6).toFloat()
        p.up?.let { u -> if (p.bias >= 0) { val tv = u.at(n) + p.height; if (tv < hi) {
            drawLine(C.Green, Offset(tx, y(u.at(n))), Offset(tx, y(tv)), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
            drawLine(C.Green, Offset(tx - 10f, y(tv)), Offset(tx + 10f, y(tv)), 3f); text("target", tx - 16.dp.toPx(), y(tv) - 4.dp.toPx(), C.Green) } } }
        p.dn?.let { d -> if (p.bias <= 0) { val tv = d.at(n) - p.height; if (tv > lo) {
            drawLine(C.Red, Offset(tx, y(d.at(n))), Offset(tx, y(tv)), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
            drawLine(C.Red, Offset(tx - 10f, y(tv)), Offset(tx + 10f, y(tv)), 3f); text("target", tx - 16.dp.toPx(), y(tv) + 12.dp.toPx(), C.Red) } } }
    }
    // recent breakouts: arrows
    for (b in a.scan.breakouts.filter { it.i >= start }.takeLast(4)) {
        val xc = x(b.i).toFloat()
        val col = if (b.side == 1) C.Green else C.Red
        val yy = if (b.side == 1) y(cl.low[b.i]) + 12.dp.toPx() else y(cl.high[b.i]) - 12.dp.toPx()
        val s = 6.dp.toPx()
        val path = Path().apply {
            if (b.side == 1) { moveTo(xc, yy - s); lineTo(xc - s, yy + s * 0.8f); lineTo(xc + s, yy + s * 0.8f) }
            else { moveTo(xc, yy + s); lineTo(xc - s, yy - s * 0.8f); lineTo(xc + s, yy - s * 0.8f) }
            close()
        }
        drawPath(path, col)
    }
    // ticket levels
    if (lv != null) {
        lv.entry?.let { drawLine(C.Txt, Offset(0f, y(it)), Offset(W, y(it)), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))); tag(it, "ENTRY", C.Txt) }
        lv.sl?.let { drawLine(C.Red, Offset(0f, y(it)), Offset(W, y(it)), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))); tag(it, "SL " + px(it), C.Red) }
        lv.tp?.let { drawLine(C.Green, Offset(0f, y(it)), Offset(W, y(it)), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))); tag(it, "TP " + px(it), C.Green) }
    }
    // last price
    val last = if (hasForm) a.forming!!.close[0] else cl.close[lastIdx]
    drawLine(C.Txt.copy(alpha = 0.5f), Offset(0f, y(last)), Offset(W, y(last)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 5f)))
    tag(last, px(last), C.Amber)
    // time labels
    val tfMs = Advisor.TF_MS[a.tf] ?: 900_000L
    for (k in 0..3) {
        val i = start + (show - 1) * k / 3
        val t = cl.openTime[i]
        text(if (tfMs >= 3_600_000L) dayHhmm(t) else hhmm(t), x(i).toFloat() - 14.dp.toPx(), size.height - 4.dp.toPx(), C.Dim)
    }
}

