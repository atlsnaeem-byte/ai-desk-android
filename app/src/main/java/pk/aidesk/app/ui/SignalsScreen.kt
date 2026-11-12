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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pk.aidesk.app.MainActivity
import pk.aidesk.app.TopBar
import pk.aidesk.app.core.Config
import pk.aidesk.app.core.Signal
import pk.aidesk.app.core.TaSignal
import pk.aidesk.app.service.TraderService
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    return now
}

fun mmss(ms: Long): String { val s = max(0, ms / 1000); return "%02d:%02d".format(s / 60, s % 60) }

@Composable
fun SignalsScreen(vm: AppVM, act: MainActivity) {
    val cfg by vm.config.collectAsState()
    val scan by vm.scan.collectAsState()
    val acc by vm.account.collectAsState()
    val accErr by vm.accountError.collectAsState()
    val running by TraderService.running.collectAsState()
    val next by vm.nextCycle.collectAsState()
    val last by vm.lastCycle.collectAsState()
    val err by vm.lastError.collectAsState()
    var filter by remember { mutableStateOf("all") }
    val now = rememberNow()

    val log by vm.sigLog.collectAsState()
    val marks by vm.marks.collectAsState()
    val ta by vm.taSignals.collectAsState()
    val ticks by TraderService.ticks.collectAsState()
    LaunchedEffect(scan?.time) { vm.loadSigLog() }
    LaunchedEffect(ticks) { vm.loadTa() }
    LaunchedEffect(Unit) { while (true) { vm.refreshMarks(); delay(15_000) } }
    // A signal is closed once its stop-loss or target was hit (bar check by the scanner, or the live price since then).
    fun closedWhy(s: Signal): String? {
        log.firstOrNull { it.symbol == s.symbol && it.side == s.side && it.since == s.since }?.outcome
            ?.takeIf { it == "SL" || it == "TP" || it == "TIME" }?.let { return it }
        val m = marks[s.symbol] ?: return null
        val dr = if (s.side == "LONG") 1 else -1
        return when {
            (m - s.origSl) * dr <= 0 -> "SL"
            (m - s.origTp) * dr >= 0 -> "TP"
            else -> null
        }
    }
    val all = (scan?.signals ?: emptyList()).sortedWith(compareByDescending<Signal> { it.strong }.thenByDescending { it.evR })
    val closedMap = all.associate { it.symbol to closedWhy(it) }
    val sigs = all.filter { closedMap[it.symbol] == null }
    val closedSigs = all.filter { closedMap[it.symbol] != null }
    val shown = (if (filter == "closed") closedSigs else sigs).filter {
        when (filter) { "strong" -> it.strong; "long" -> it.side == "LONG"; "short" -> it.side == "SHORT"; else -> true }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(vm, "AI Desk")
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            item {
                // status strip
                val (bg, fg, text) = when {
                    !running -> Triple(C.RedBg, C.Red, "Scanner stopped  -  tap to start")
                    cfg.auto -> Triple(C.GreenBg, C.Green, "AUTO on  -  next bar ${vm.engine.barTimeText(next)} (in ${mmss(next - now)})")
                    else -> Triple(Color(0xFF13233A), C.Blue, "Scanner on, AUTO off  -  next ${vm.engine.barTimeText(next)} (${mmss(next - now)})")
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg)
                    .clickable(enabled = !running) { TraderService.start(act) }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(fg))
                    HGap(8.dp)
                    T(text, 12.sp, fg, FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (running) {
                        if (cfg.auto) Pill("STOP", C.Red) { vm.saveConfig(cfg.copy(auto = false)); vm.toast.value = "Auto-trading stopped. Open trades keep their TP/SL." }
                        else Pill("START AUTO", C.Green) {
                            val go = { vm.saveConfig(vm.config.value.copy(auto = true)); vm.toast.value = "Auto-trading started (${cfg.mode.uppercase()})" }
                            if (cfg.mode == "live") act.confirmUser("Start LIVE auto-trading") { go() } else go()
                        }
                    }
                }
                err?.let { Gap(6.dp); T("Last error: $it", 12.sp, C.Amber) }
                Gap(10.dp)
                // equity card
                Card {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        T("Equity", 11.sp, C.Muted); T("Today", 11.sp, C.Muted)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        T(acc?.let { "${f2(it.equity)} USDT" } ?: "-", 22.sp, C.Txt, FontWeight.Bold)
                        val d = acc?.dayPnlPct
                        T(d?.let { "${sgn2(it)}%" } ?: "-", 18.sp, if ((d ?: 0.0) >= 0) C.Green else C.Red, FontWeight.Bold)
                    }
                    T("Open ${acc?.positions?.size ?: 0}/${cfg.maxOpen}   Limit -${cfg.dailyLossPct}%   Target +${cfg.dailyProfitPct}%   Risk ${cfg.riskPct.toInt()}%",
                        11.sp, C.Muted)
                    acc?.halted?.let { T("Paused today: $it", 12.sp, C.Amber) }
                    accErr?.let { T(it, 12.sp, C.Amber) }
                }
                Gap(10.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    for ((k, l) in listOf("all" to "Active ${sigs.size}", "strong" to "Strong ${sigs.count { it.strong }}", "long" to "Long",
                            "short" to "Short", "closed" to "Closed ${closedSigs.size}"))
                        Pill(l, if (filter == k) C.Blue else C.Muted, filled = filter == k) { filter = k }
                    Box(Modifier.weight(1f))
                    T("Scan now", 12.sp, C.Blue, FontWeight.SemiBold, modifier = Modifier.clickable { vm.scanNow() })
                }
                Gap(4.dp)
                T(scan?.let { "Signals for the ${hhmm(it.signals.firstOrNull()?.signalTime ?: it.time)} PKT bar close  -  scanned ${hhmm(last)}" }
                    ?: "No scan yet. It runs at every 15-minute close.", 11.sp, C.Dim)
                scan?.error?.let { T(it, 12.sp, C.Amber) }
                Gap(8.dp)
            }
            // ---- TA-course setups (4h RSI divergence / range fade), from TaScanner
            fun taHit(s: TaSignal): String? {
                val m = marks[s.symbol] ?: return null
                return when { (m - s.sl) * s.dir <= 0 -> "SL"; (m - s.tp) * s.dir >= 0 -> "TP"; else -> null }
            }
            val taShown = if (filter == "closed") ta.filter { !it.active }.take(20)
                else ta.filter { it.active && taHit(it) == null }.filter {
                    when (filter) { "long" -> it.side == "LONG"; "short" -> it.side == "SHORT"; else -> true }
                }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("Course setups · 4h", 14.sp, C.Txt, FontWeight.Bold)
                    HGap(6.dp); T("RSI divergence · range fade · S/R confluence", 11.sp, C.Dim, modifier = Modifier.weight(1f), maxLines = 1)
                    if (filter == "closed" && taShown.isNotEmpty()) T("Clear", 12.sp, C.Blue, FontWeight.SemiBold, modifier = Modifier.clickable { vm.clearTaClosed() })
                }
                val taAuto by vm.taAuto.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(if (taAuto) "Auto-trade ON (${cfg.mode.uppercase()}): market order with the signal's SL/TP, risk ${pctTxt(cfg.riskPct)}% · ${cfg.leverage}x"
                        else "Auto-trade OFF: alerts only", 11.sp, if (taAuto) C.Green else C.Muted, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(taAuto, { on ->
                        if (on && cfg.mode == "live") act.confirmUser("Auto-trade course setups LIVE") { vm.setTaAuto(true) } else vm.setTaAuto(on)
                    }, colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = C.Green, checkedThumbColor = C.Txt))
                }
                vm.taScanner.stats()?.let { T("Results so far: $it", 11.sp, C.Muted) }
                if (taShown.isEmpty()) T(if (filter == "closed") "No closed course setups yet." else
                    "No course setup active. The coins are checked at every 4h close (05:00, 09:00, 13:00 … PKT).", 12.sp, C.Muted,
                    modifier = Modifier.padding(vertical = 4.dp))
                Gap(6.dp)
            }
            items(taShown, key = { "ta" + it.id }) { s ->
                TaCard(s, marks[s.symbol], taHit(s)) { vm.openTaSignal(s.symbol, s.tf) }
                Gap(8.dp)
            }
            item {
                Gap(4.dp)
                T("AI model signals · 15m", 14.sp, C.Txt, FontWeight.Bold)
                Gap(6.dp)
            }
            if (shown.isEmpty()) item {
                T(if (filter == "closed") "No closed signals in this scan." else "No active signals: every signal of this scan already hit its stop or target. New ones come at the next 15-minute close.",
                    13.sp, C.Muted, modifier = Modifier.padding(8.dp))
            }
            items(shown, key = { it.symbol }) { s ->
                val why = closedMap[s.symbol]
                if (why != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill(when (why) { "SL" -> "STOP-LOSS HIT"; "TP" -> "TARGET HIT"; else -> "TIME EXIT" }, if (why == "TP") C.Green else C.Red, filled = true)
                        HGap(6.dp); T("closed signal", 11.sp, C.Dim)
                    }
                    Gap(4.dp)
                }
                SignalCard(s) { vm.detail.value = s.symbol }
                Gap(8.dp)
            }
            item { Gap(20.dp) }
        }
    }
}

@Composable
fun SignalCard(s: Signal, onClick: () -> Unit) {
    val col = C.side(s.side)
    Card(border = if (s.strong) col else C.Border, faded = !s.strong, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(s.symbol, 15.sp, C.Txt, FontWeight.Bold)
            HGap(8.dp)
            Pill(s.side, col, filled = s.strong)
            Box(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                T(hhmm(s.since), 13.sp, C.Txt, FontWeight.SemiBold, mono = true)
                val bars = ((s.signalTime - s.since) / Config.BAR_MS + 1).coerceAtLeast(1)
                T(if (bars > 1) "active $bars bars" else "new", 10.sp, if (bars > 1) C.Muted else C.Amber)
            }
        }
        Gap(6.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Win ${pct0(s.prob)}", 13.sp, C.Txt, FontWeight.SemiBold)
            HGap(14.dp)
            T("EV ${sgn2(s.evR)}R", 13.sp, if (s.evR > 0) col else C.Muted, FontWeight.SemiBold)
            Box(Modifier.weight(1f))
            T(if (s.strong) "STRONG" else "WEAK", 11.sp, if (s.strong) col else C.Muted, FontWeight.Bold)
        }
        Gap(6.dp)
        Bar(s.prob.toFloat(), col)
        Gap(8.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            T("E ${px(s.entry)}", 11.sp, C.Muted, mono = true)
            T("TP ${px(s.tp)}", 11.sp, C.Green, mono = true)
            T("SL ${px(s.sl)}", 11.sp, C.Red, mono = true)
        }
    }
}

@Composable
fun TaCard(s: TaSignal, mark: Double?, hit: String?, onClick: () -> Unit) {
    val col = C.side(s.side)
    Card(border = if (s.active) col else C.Border, faded = !s.active, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(s.symbol, 15.sp, C.Txt, FontWeight.Bold)
            HGap(8.dp)
            Pill(s.side, col, filled = s.active); HGap(6.dp)
            Pill(s.tf, C.Blue); HGap(6.dp)
            Pill("score ${s.score}", if (s.score >= 4) C.Green else if (s.score >= 2) C.Amber else C.Muted)
            Box(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                T(hhmm(s.since), 13.sp, C.Txt, FontWeight.SemiBold, mono = true)
                T(when (s.status) { "TP" -> "target hit"; "SL" -> "stop hit"; "TIME" -> "time exit"; else -> "4h close" }, 10.sp,
                    if (s.status == "TP") C.Green else if (s.status == "SL") C.Red else C.Muted)
            }
        }
        Gap(4.dp)
        T(s.title, 13.sp, C.Txt, FontWeight.SemiBold)
        s.why.firstOrNull()?.let { T(it.removePrefix("+ "), 11.sp, C.Muted, maxLines = 2) }
        Gap(6.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            T("E ${px(s.entry)}", 11.sp, C.Muted, mono = true)
            T("TP ${px(s.tp)}", 11.sp, C.Green, mono = true)
            T("SL ${px(s.sl)}", 11.sp, C.Red, mono = true)
            T("R:R ${f2(s.rr)}", 11.sp, C.Amber, mono = true)
        }
        val r = s.r ?: mark?.let { s.dir * (it - s.entry) / abs(s.entry - s.sl) }
        r?.let {
            Gap(4.dp)
            T((if (s.active) "now ${px(mark)} · " else "") + "${sgn2(it)}R" + (if (hit != null) " · ${if (hit == "TP") "target" else "stop"} touched" else ""),
                11.sp, if (it >= 0) C.Green else C.Red, FontWeight.SemiBold)
        }
        if (s.active) T("Tap to open the 4h chart and order ticket", 10.sp, C.Dim)
    }
}

// ------------------------------------------------------------------ detail
@Composable
fun DetailScreen(vm: AppVM, symbol: String) {
    val scan by vm.scan.collectAsState()
    val cfg by vm.config.collectAsState()
    val acc by vm.account.collectAsState()
    val s = scan?.signals?.firstOrNull { it.symbol == symbol }
    val now = rememberNow()
    val live by vm.live.collectAsState()
    val liveError by vm.liveError.collectAsState()
    DisposableEffect(symbol) {
        vm.startLive(symbol)
        onDispose { vm.stopLive() }
    }
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar(vm, symbol) { vm.detail.value = null }
        if (s == null) { T("No data for $symbol yet.", 14.sp, C.Muted, modifier = Modifier.padding(16.dp)); return@Column }
        val col = C.side(s.side)
        val expired = now > s.barTime + 2 * Config.BAR_MS + 60_000
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(s.side, col, filled = true); HGap(6.dp)
                Pill(if (s.strong) "STRONG" else "WEAK", if (s.strong) col else C.Muted)
                Box(Modifier.weight(1f))
                T("signal since ${hhmm(s.since)} PKT  -  bar ${hhmm(s.signalTime)}", 11.sp, C.Muted)
            }
            Gap(8.dp)
            // ---- live chart: forming candle + live price vs the signal's original entry / TP / SL
            val lv = live?.takeIf { it.symbol == symbol }
            val e0 = s.origEntry; val tp0 = s.origTp; val sl0 = s.origSl
            if (lv != null) {
                val k = lv.candles
                CandleChart(k.open.toList(), k.high.toList(), k.low.toList(), k.close.toList(), k.openTime.toList(),
                    e0, tp0, sl0, s.since, lv.price, Modifier.fillMaxWidth().height(240.dp))
            } else {
                val n = s.c.size
                CandleChart(s.o, s.h, s.l, s.c, List(n) { s.barTime - (n - 1 - it) * Config.BAR_MS },
                    e0, tp0, sl0, s.since, null, Modifier.fillMaxWidth().height(240.dp))
            }
            Gap(6.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val blink = (now / 1000) % 2 == 0L
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (lv != null && blink) C.Red else C.Dim))
                HGap(6.dp)
                if (lv != null) {
                    val dir = if (s.side == "LONG") 1 else -1
                    val movePct = (lv.price / e0 - 1) * 100
                    val moveR = dir * (lv.price - e0) / abs(e0 - sl0)
                    val good = moveR >= 0
                    T("LIVE  ", 11.sp, C.Red, FontWeight.Bold)
                    T("Now ${px(lv.price)}", 13.sp, C.Blue, FontWeight.Bold, mono = true)
                    HGap(8.dp)
                    T("${String.format(java.util.Locale.US, "%+.2f", movePct)}% / ${sgn2(moveR)}R from entry", 12.sp,
                        if (good) C.Green else C.Red, FontWeight.SemiBold)
                } else T(liveError ?: "Connecting to live price...", 11.sp, if (liveError != null) C.Amber else C.Muted)
            }
            Gap(8.dp)
            Card {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for ((k, v, c) in listOf(Triple("Entry @${hhmm(s.since)}", px(e0), C.Txt), Triple("TP", px(tp0), C.Green),
                        Triple("SL", px(sl0), C.Red), Triple("R:R", f2(abs(tp0 - e0) / abs(e0 - sl0)), C.Amber))) {
                        Column { T(k, 11.sp, C.Muted); T(v, 13.sp, c, mono = true) }
                    }
                }
                if (abs(s.entry - e0) > 1e-12) {
                    Gap(4.dp)
                    T("Latest bar levels: entry ${px(s.entry)}  TP ${px(s.tp)}  SL ${px(s.sl)}. Take uses the live price.", 11.sp, C.Muted)
                }
                Gap(6.dp)
                T("Win ${pct0(s.prob)}   EV ${sgn2(s.evR)}R   Long ${pct0(s.pLong)} / Short ${pct0(s.pShort)}   max hold ${cfg.horizonBars} bars",
                    11.sp, C.Muted)
            }
            Gap(10.dp)
            T("Why the AI leans ${s.side}", 14.sp, C.Txt, FontWeight.Bold)
            Gap(6.dp)
            val mx = max(0.5, s.drivers.maxOfOrNull { abs(it.second) } ?: 1.0)
            for ((label, imp) in s.drivers) {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(label, 12.sp, C.Muted, modifier = Modifier.width(150.dp), maxLines = 1)
                    Box(Modifier.weight(1f).height(10.dp)) {
                        val frac = (abs(imp) / mx).toFloat().coerceIn(0.02f, 1f)
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                                if (imp < 0) Box(Modifier.fillMaxWidth(frac).height(8.dp).clip(RoundedCornerShape(2.dp)).background(C.Red))
                            }
                            Box(Modifier.width(1.dp).fillMaxSize().background(C.Dim))
                            Box(Modifier.weight(1f)) {
                                if (imp > 0) Box(Modifier.fillMaxWidth(frac).height(8.dp).clip(RoundedCornerShape(2.dp)).background(C.Green))
                            }
                        }
                    }
                    T(sgn2(imp), 11.sp, if (imp >= 0) C.Green else C.Red, mono = true, modifier = Modifier.padding(start = 6.dp))
                }
            }
            if (s.patterns.isNotEmpty()) {
                Gap(8.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { s.patterns.take(3).forEach { Pill(it, C.Amber) } }
            }
            Gap(10.dp)
            acc?.let { a ->
                val qty = pk.aidesk.app.core.Strategy.positionSize(a.equity, s.entry, abs(s.entry - s.sl), cfg)
                Card {
                    T("Size about ${px(qty)} ${s.symbol.removeSuffix("USDT")}  -  margin ${f2(qty * s.entry / cfg.leverage)}  -  risk ${f2(qty * abs(s.entry - s.sl))} USDT", 12.sp, C.Txt)
                    T("${cfg.leverage}x ${cfg.marginType.lowercase()}  -  risk ${cfg.riskPct.toInt()}% of equity. Exact size on the next screen.", 11.sp, C.Muted)
                }
            }
            Gap(12.dp)
        }
        Column(Modifier.padding(12.dp)) {
            if (expired) T("This signal is from an older bar. Wait for the next scan.", 12.sp, C.Amber)
            Btn("Take ${s.side}", col, enabled = !expired) { vm.take.value = s.symbol }
            Gap(6.dp)
            T("Skip this signal", 13.sp, C.Muted, FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterHorizontally).clickable { vm.detail.value = null }.padding(6.dp))
        }
    }
}

private fun argb(c: Color) = android.graphics.Color.argb((c.alpha * 255).toInt(), (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())

/**
 * Candles with the signal's entry / TP / SL. The TP/SL zones start at the candle where the signal
 * began (`since` = close of its first bar). `livePrice` draws a blue line with a price tag.
 */
@Composable
fun CandleChart(o: List<Double>, h: List<Double>, l: List<Double>, c: List<Double>, times: List<Long>,
                entry: Double, tp: Double, sl: Double, since: Long, livePrice: Double?, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(10.dp)).background(C.Panel)) {
        val n = c.size
        if (n == 0) return@Canvas
        var lo = minOf(l.min(), sl, tp, entry); var hi = maxOf(h.max(), sl, tp, entry)
        val pad = (hi - lo) * 0.06; lo -= pad; hi += pad
        val left = 6f; val right = size.width - 120f
        val cw = (right - left) / (n + 2)
        fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
        fun x(i: Int) = left + cw * i + cw / 2
        // entry candle = first candle opening at / after the signal time
        val ei = times.indexOfFirst { it >= since }.let { if (it < 0) n - 1 else it }
        val zx = x(ei) - cw / 2
        val zw = right - zx
        drawRect(C.Green.copy(alpha = 0.18f), Offset(zx, min(y(tp), y(entry))), Size(zw, abs(y(tp) - y(entry))))
        drawRect(C.Red.copy(alpha = 0.18f), Offset(zx, min(y(sl), y(entry))), Size(zw, abs(y(sl) - y(entry))))
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        drawLine(C.Amber.copy(alpha = 0.7f), Offset(zx, 0f), Offset(zx, size.height), 1.5f, pathEffect = dash)
        drawLine(C.Txt, Offset(left, y(entry)), Offset(right, y(entry)), 2f, pathEffect = dash)
        for ((v, col) in listOf(tp to C.Green, sl to C.Red)) drawLine(col, Offset(left, y(v)), Offset(right, y(v)), 5f)
        for (i in 0 until n) {
            val col = if (c[i] >= o[i]) C.Green else C.Red
            val cx = x(i)
            drawLine(col, Offset(cx, y(h[i])), Offset(cx, y(l[i])), 1.5f)
            val top = y(max(o[i], c[i])); val bot = y(min(o[i], c[i]))
            drawRect(col, Offset(cx - cw * 0.32f, top), Size(cw * 0.64f, max(1.5f, bot - top)))
        }
        val nc = drawContext.canvas.nativeCanvas
        val paint = android.graphics.Paint().apply { textSize = 24f; isAntiAlias = true; typeface = android.graphics.Typeface.MONOSPACE }
        paint.textSize = 20f; paint.isFakeBoldText = true
        for ((v, col, lab) in listOf(Triple(tp, C.Green, "TP"), Triple(entry, C.Txt, "Entry"), Triple(sl, C.Red, "SL"))) {
            paint.color = argb(col)
            nc.drawText(lab, right + 6f, y(v) - 2f, paint)
            nc.drawText(px(v), right + 6f, y(v) + 20f, paint)
        }
        paint.color = argb(C.Amber); paint.textSize = 20f
        nc.drawText("signal", zx + 4f, 22f, paint)
        livePrice?.let { p ->
            val cyan = Color(0xFF22D3EE)
            val ly = y(p.coerceIn(lo, hi))
            drawLine(cyan, Offset(left, ly), Offset(right, ly), 3f)
            drawRect(cyan, Offset(right + 2f, ly - 18f), Size(size.width - right - 2f, 36f))
            paint.color = argb(C.Bg); paint.textSize = 22f
            nc.drawText(px(p), right + 6f, ly + 8f, paint)
        }
    }
}

// ------------------------------------------------------------------ take sheet
@Composable
fun TakeSheet(vm: AppVM, act: MainActivity, symbol: String) {
    val cfg by vm.config.collectAsState()
    val pv by vm.preview.collectAsState()
    val pErr by vm.previewError.collectAsState()
    val busy by vm.busy.collectAsState()
    var risk by remember { mutableFloatStateOf(cfg.riskPct.toFloat()) }
    LaunchedEffect(symbol) { vm.loadPreview(symbol, risk.toDouble()) }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { vm.take.value = null }) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(C.Panel)
            .pointerInput(Unit) { detectTapGestures { } }.padding(16.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.Dim))
            Gap(12.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Confirm order", 18.sp, C.Txt, FontWeight.Bold, modifier = Modifier.weight(1f))
                pv?.let { Pill(it.side, C.side(it.side), filled = true) }
            }
            Gap(8.dp)
            val p = pv
            when {
                pErr != null -> T(pErr!!, 13.sp, C.Red)
                p == null -> T("Loading price and size...", 13.sp, C.Muted)
                else -> {
                    val dPct = { v: Double -> String.format(java.util.Locale.US, "%+.2f%%", (v / p.price - 1) * 100) }
                    KV("Symbol", "${p.symbol} perp")
                    KV("Entry", "market ~${px(p.price)}")
                    KV("Take profit", "${px(p.tp)}  (${dPct(p.tp)})", C.Green)
                    KV("Stop loss", "${px(p.sl)}  (${dPct(p.sl)})", C.Red)
                    KV("Quantity", px(p.qty))
                    KV("Margin", "${f2(p.margin)} USDT  (${p.leverage}x)")
                    KV("Max loss at SL", "${f2(p.riskUsdt)} USDT", C.Red)
                    KV("Win / EV", "${pct0(p.prob)}  /  ${sgn2(p.evR)}R")
                    p.warnings.forEach { T(it, 12.sp, C.Amber) }
                    p.block?.let { T(it, 13.sp, C.Red) }
                }
            }
            Gap(10.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Risk per trade", 13.sp, C.Muted, modifier = Modifier.weight(1f))
                T("${risk.toInt()}%", 14.sp, C.Amber, FontWeight.Bold)
            }
            Slider(risk, { risk = it.toInt().toFloat() }, valueRange = 1f..100f, steps = 98,
                onValueChangeFinished = { vm.loadPreview(symbol, risk.toDouble()) },
                colors = SliderDefaults.colors(thumbColor = C.Txt, activeTrackColor = C.Amber, inactiveTrackColor = C.Panel2))
            if (risk > cfg.dailyLossPct) T("Risk is above your daily loss limit (${cfg.dailyLossPct}%): one loss pauses auto for the day.", 11.sp, C.Amber)
            Gap(8.dp)
            val live = cfg.mode == "live"
            val ok = p != null && p.block == null && !busy
            Btn(if (busy) "Placing..." else if (live) "Place LIVE order" else "Place ${cfg.mode.uppercase()} order", C.side(p?.side ?: "LONG"), enabled = ok) {
                val go = { vm.placeOrder(symbol, risk.toDouble()) { vm.take.value = null; vm.detail.value = null; vm.tab.value = "positions" } }
                if (live) act.confirmUser("Place LIVE ${p?.side} $symbol") { go() } else go()
            }
            Gap(6.dp)
            T(if (live) "Fingerprint or screen lock required in LIVE mode" else "TP and SL are placed together with the entry",
                11.sp, C.Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}
