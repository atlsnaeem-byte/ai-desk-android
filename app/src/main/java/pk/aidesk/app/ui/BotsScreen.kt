package pk.aidesk.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pk.aidesk.app.MainActivity
import pk.aidesk.app.TopBar
import java.util.Locale

@Composable
fun BotsScreen(vm: AppVM, act: MainActivity) {
    val sub by vm.botTab.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar(vm, "Bots")
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("Short hunter", if (sub == "shorts") C.Blue else C.Muted, filled = sub == "shorts") { vm.botTab.value = "shorts" }
            Pill("Copy trade", if (sub == "copy") C.Blue else C.Muted, filled = sub == "copy") { vm.botTab.value = "copy" }
        }
        Gap(6.dp)
        if (sub == "copy") CopyPane(vm, act) else ShortsPane(vm, act)
    }
}

@Composable
private fun Kpis(items: List<Triple<String, String, Color>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((k, v, c) in items) {
            Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(C.Panel).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                T(k, 10.sp, C.Muted); T(v, 14.sp, c, FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

private fun pnlColor(v: Double?) = if ((v ?: 0.0) >= 0) C.Green else C.Red
private fun sgn1(v: Double?) = v?.let { String.format(Locale.US, "%+.1f", it) } ?: "-"

@Composable
private fun EnableRow(label: String, sub: String, on: Boolean, mode: String, act: MainActivity, onChange: (Boolean) -> Unit) {
    Card(border = if (on) C.Green else C.Border) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { T(label, 15.sp, C.Txt, FontWeight.Bold); T(sub, 11.sp, C.Muted) }
            Switch(on, { v -> if (v && mode == "live") act.confirmUser("Start LIVE $label") { onChange(true) } else onChange(v) },
                colors = SwitchDefaults.colors(checkedTrackColor = C.Green, checkedThumbColor = C.Txt))
        }
    }
}

// ================================================================== Short hunter
@Composable
private fun ShortsPane(vm: AppVM, act: MainActivity) {
    val cfgApp by vm.config.collectAsState()
    val v by vm.shorts.collectAsState()
    val err by vm.shortsErr.collectAsState()
    val loading by vm.shortsLoading.collectAsState()
    LaunchedEffect(cfgApp.mode) { vm.loadShorts(true) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); vm.loadShorts(false) } }
    LaunchedEffect(Unit) { while (true) { vm.refreshMarks(); delay(15_000) } }
    val marks by vm.marks.collectAsState()
    val c = v?.cfg ?: vm.shortHunter.cfg()
    val mode = cfgApp.mode

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            EnableRow("Auto-short new listings", if (c.enabled) "Shorts every new coin on day ${c.entryDay} (${mode.uppercase()})" else "Off: shortlist only",
                c.enabled, mode, act) { vm.saveShortCfg(c.copy(enabled = it)); vm.toast.value = if (it) "Short hunter on (${mode.uppercase()})" else "Short hunter off. Open shorts keep their SL/TP." }
            Gap(6.dp)
            EnableRow("Auto-short pump signals", if (c.pumpAuto) "Shorts coins up +50% in a day / +100% in 3 days · SL +${c.pumpSlPct.toInt()}% TP −${c.pumpTpPct.toInt()}% (${mode.uppercase()})"
                else "Off · this rule lost 2–3% per trade in the 2026 backtest", c.pumpAuto, mode, act) {
                vm.saveShortCfg(c.copy(pumpAuto = it))
                vm.toast.value = if (it) "Pump shorts on (${mode.uppercase()}): checked every 15 min" else "Pump shorts off"
            }
            Gap(8.dp)
            val st = v?.state; val jr = v?.journal ?: emptyList()
            val pnl = jr.sumOf { it.pnl }
            Kpis(listOf(
                Triple(if (mode == "paper") "Paper equity" else "Mode", if (mode == "paper") st?.let { f2(it.paperEquity) } ?: "-" else mode.uppercase(), C.Txt),
                Triple("Open", "${st?.open?.size ?: 0}/${c.maxPositions}", C.Txt),
                Triple("Closed", "${jr.size}" + if (jr.isNotEmpty()) " · ${jr.count { it.pnl > 0 } * 100 / jr.size}%" else "", C.Txt),
                Triple("P&L", sgn2(pnl), pnlColor(pnl)),
            ))
            err?.let { Gap(6.dp); T(it, 12.sp, C.Amber) }
            Gap(6.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(v?.let { "Updated ${hhmm(it.at)}" + if (loading) " · loading…" else "" } ?: "Loading Binance listings…", 11.sp, C.Dim, modifier = Modifier.weight(1f))
                T("Refresh", 12.sp, C.Blue, FontWeight.SemiBold, modifier = Modifier.clickable { vm.loadShorts(true) })
            }
            SectionTitle("New listings")
        }
        val lst = v?.listings ?: emptyList()
        if (lst.isEmpty()) item { T("No coins listed in the last ${c.entryDay + c.holdDays} days.", 13.sp, C.Muted) }
        for (l in lst) item(key = "l" + l.symbol) {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(l.symbol, 14.sp, C.Txt, FontWeight.Bold, modifier = Modifier.weight(1f))
                    Pill(l.status, when (l.status) { "SHORT NOW" -> C.Red; "WAIT" -> C.Amber; else -> C.Muted }, filled = l.status == "SHORT NOW")
                }
                T("Listed ${dayHhmm(l.listed)} · day ${l.day} · short at ${dayHhmm(l.entryTime)} · ${l.note}", 11.sp, C.Muted)
                val isOpen = v?.state?.open?.containsKey(l.symbol) == true
                if (l.status != "WAIT" && !isOpen) {
                    Gap(6.dp)
                    ShortButton(vm, act, l.symbol, mode, if (l.status == "SHORT NOW") "Open short now" else "Open short anyway (late)")
                }
            }
            Gap(6.dp)
        }
        item { SectionTitle("Open shorts") }
        val open = v?.state?.open?.values?.toList() ?: emptyList()
        if (open.isEmpty()) item { T("No open shorts.", 13.sp, C.Muted) }
        for (p in open) item(key = "o" + p.symbol) {
            val mk = marks[p.symbol]
            val upnl = mk?.let { (p.entry - it) * p.qty }
            Card(border = if (upnl == null) C.Border else pnlColor(upnl), onClick = { vm.showChart(p.symbol) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(p.symbol, 14.sp, C.Txt, FontWeight.Bold); HGap(8.dp); Pill("SHORT", C.Red, filled = true); Box(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        T(upnl?.let { "${sgn2(it)} USDT" } ?: "-", 15.sp, pnlColor(upnl), FontWeight.Bold)
                        upnl?.let { T("${sgn1(it / p.margin * 100)}% of ${f2(p.margin)} margin", 11.sp, pnlColor(it)) }
                    }
                }
                T("Entry ${px(p.entry)} · now ${px(mk)} · SL ${px(p.sl)} · TP ${px(p.tp)}", 11.sp, C.Muted, mono = true)
                T("Opened ${dayHhmm(p.opened)} · ${p.rule} · time exit ${dayHhmm(p.exitAt)}", 11.sp, C.Muted)
                var ask by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Box(Modifier.weight(1f))
                    T("Close now", 13.sp, C.Red, FontWeight.Bold, modifier = Modifier.clickable { ask = true })
                }
                if (ask) AlertDialog(onDismissRequest = { ask = false },
                    confirmButton = { TextButton({ ask = false
                        if (mode == "live") act.confirmUser("Close LIVE short ${p.symbol}") { vm.shortClose(p.symbol) } else vm.shortClose(p.symbol)
                    }) { Text("Close at market", color = C.Red) } },
                    dismissButton = { TextButton({ ask = false }) { Text("Keep") } },
                    title = { Text("Close ${p.symbol} short?") }, text = { Text("Buys back the short at market and removes its SL/TP.") },
                    containerColor = C.Panel)
            }
            Gap(6.dp)
        }
        item { SectionTitle(if (c.pumpAuto) "Pumped coins (auto-shorted on SIGNAL)" else "Pumped coins (tap Short to open)") }
        val pumps = v?.pumps ?: emptyList()
        if (pumps.isEmpty()) item { T("-", 13.sp, C.Muted) }
        for (p in pumps) item(key = "p" + p.symbol) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                T(p.symbol, 12.sp, C.Txt, FontWeight.SemiBold, modifier = Modifier.weight(1.3f))
                T("${sgn1(p.ch24)}%", 12.sp, pnlColor(p.ch24), modifier = Modifier.weight(0.8f))
                T("1d ${sgn1(p.d1)}%", 11.sp, C.Muted, modifier = Modifier.weight(0.9f))
                T("3d ${sgn1(p.d3)}%", 11.sp, C.Muted, modifier = Modifier.weight(0.9f))
                T(String.format(Locale.US, "%+.3f%%", p.fundingPct), 11.sp, if (p.fundingPct < 0) C.Red else C.Muted, modifier = Modifier.weight(0.9f))
                if (p.status != "-") Pill(p.status, if (p.status == "SIGNAL") C.Amber else C.Muted)
            }
            val isOpen = v?.state?.open?.containsKey(p.symbol) == true
            if (p.status != "-" && !isOpen) Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Box(Modifier.weight(1f)); ShortButton(vm, act, p.symbol, mode, "Short ${p.symbol.removeSuffix("USDT")}", small = true)
            }
        }
        item {
            T("Negative funding = shorts pay longs. SIGNAL = pumped +50% in a day or +100% in 3 days (and listed 30+ days). " +
                "Pump shorts use SL +${c.pumpSlPct.toInt()}%, TP −${c.pumpTpPct.toInt()}%, ${c.pumpHoldDays}-day hold. Warning: this rule lost 2–3% per trade in the 2026 backtest, with single trades down 70%+.",
                11.sp, C.Dim)
            SectionTitle("Closed shorts")
        }
        val jr = v?.journal ?: emptyList()
        if (jr.isEmpty()) item { T("No closed shorts yet.", 13.sp, C.Muted) }
        for ((i, t) in jr.take(100).withIndex()) item(key = "j$i") {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T(t.symbol, 13.sp, C.Txt, FontWeight.SemiBold)
                    T("${dayHhmm(t.opened)} → ${dayHhmm(t.closed)} · ${t.reason}", 11.sp, C.Muted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    T(sgn2(t.pnl), 13.sp, pnlColor(t.pnl), FontWeight.Bold)
                    t.retPct?.let { T("${sgn1(it)}% margin", 11.sp, pnlColor(it)) }
                }
            }
        }
        item {
            SectionTitle("Settings")
            Stepper("Margin per short", "${c.marginPct.toInt()}% of equity", "Binance needs 5+ USDT per position",
                onMinus = { vm.saveShortCfg(c.copy(marginPct = (c.marginPct - 1).coerceAtLeast(1.0))) },
                onPlus = { vm.saveShortCfg(c.copy(marginPct = (c.marginPct + 1).coerceAtMost(50.0))) })
            Stepper("Leverage", "${c.leverage}x", "Keep 1x: a +40% squeeze liquidates 3x",
                onMinus = { vm.saveShortCfg(c.copy(leverage = (c.leverage - 1).coerceAtLeast(1))) },
                onPlus = { vm.saveShortCfg(c.copy(leverage = (c.leverage + 1).coerceAtMost(3))) })
            Stepper("Max open shorts", "${c.maxPositions}",
                onMinus = { vm.saveShortCfg(c.copy(maxPositions = (c.maxPositions - 1).coerceAtLeast(1))) },
                onPlus = { vm.saveShortCfg(c.copy(maxPositions = (c.maxPositions + 1).coerceAtMost(20))) })
            val eq = if (mode == "paper") v?.state?.paperEquity ?: c.paperStart else null
            val need = kotlin.math.ceil(5.0 / (c.marginPct / 100 * c.leverage)).toInt()
            Gap(6.dp)
            T("Each short = ${c.marginPct.toInt()}% of equity × ${c.leverage}x, SL +${c.slPct.toInt()}%, TP −${c.tpPct.toInt()}%, closed after ${c.holdDays} days. " +
                "You need at least $need USDT equity" + (eq?.let { " (paper: ${f2(it)})" } ?: "") + ". The rule is checked every 15 minutes in the background.", 11.sp, C.Muted)
            SectionTitle("When does it make money?")
            T("Backtest on 262 new Binance coins (Mar 2025 – Oct 2026), after fees and funding: +4.3% per trade on average, 64% winners, " +
                "+78% in 19 months at 10% per trade and 1x, worst drop −36%. It earns when launch hype fades and loses when a new coin is squeezed +40% " +
                "(about 1 in 3 trades) or in an alt-coin season. New listings slowed in late 2026, so expect few trades. Past results are not a promise.", 12.sp, C.Muted)
            Gap(24.dp)
        }
    }
}

@Composable
private fun ShortButton(vm: AppVM, act: MainActivity, symbol: String, mode: String, label: String, small: Boolean = false) {
    var ask by remember { mutableStateOf(false) }
    if (small) T(label, 13.sp, C.Red, FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(C.RedBg)
        .clickable { ask = true }.padding(horizontal = 12.dp, vertical = 6.dp))
    else Btn(label, C.Red, height = 40.dp) { ask = true }
    if (ask) {
        val c = vm.shortHunter.cfg()
        AlertDialog(onDismissRequest = { ask = false },
            confirmButton = { TextButton({ ask = false
                if (mode == "live") act.confirmUser("Open LIVE short $symbol") { vm.shortOpen(symbol) } else vm.shortOpen(symbol)
            }) { Text("Open short", color = C.Red) } },
            dismissButton = { TextButton({ ask = false }) { Text("Cancel") } },
            title = { Text("Short $symbol (${mode.uppercase()})?") },
            text = { Text("Market sell, ${c.marginPct.toInt()}% of equity as margin × ${c.leverage}x. New listings: SL +${c.slPct.toInt()}% / TP −${c.tpPct.toInt()}%. " +
                "Other coins: SL +${c.pumpSlPct.toInt()}% / TP −${c.pumpTpPct.toInt()}%. Stop and target are placed on Binance.") },
            containerColor = C.Panel)
    }
}

// ================================================================== Copy trade
@Composable
private fun CopyPane(vm: AppVM, act: MainActivity) {
    val cfgApp by vm.config.collectAsState()
    val v by vm.copy.collectAsState()
    val err by vm.copyErr.collectAsState()
    var addOpen by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(cfgApp.mode) { vm.loadCopy(true) }
    LaunchedEffect(Unit) { var n = 0; while (true) { delay(10_000); vm.loadCopy(++n % 3 == 0) } }
    val c = v?.cfg ?: vm.copyBot.cfg()
    val mode = cfgApp.mode
    val jr = v?.journal ?: emptyList()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            EnableRow("Copy trading", if (c.enabled) "Following ${c.leaders.count { it.enabled }} trader(s), checks every ${c.pollSec} s (${mode.uppercase()})" else "Off",
                c.enabled, mode, act) { vm.saveCopyCfg(c.copy(enabled = it)); vm.toast.value = if (it) "Copy trading on (${mode.uppercase()})" else "Copy trading off. Open copies still follow the trader's exit." }
            Gap(8.dp)
            val st = v?.state
            val pnl = jr.sumOf { it.pnl }
            val upnl = v?.open?.sumOf { it.second ?: 0.0 } ?: 0.0
            Kpis(listOf(
                Triple(if (mode == "paper") "Paper equity" else "Mode", if (mode == "paper") st?.let { f2(it.paperEquity) } ?: "-" else mode.uppercase(), C.Txt),
                Triple("Open", "${st?.copies?.size ?: 0}/${c.maxPositions}", C.Txt),
                Triple("Open P&L", sgn2(upnl), pnlColor(upnl)),
                Triple("Closed P&L", sgn2(pnl), pnlColor(pnl)),
            ))
            Gap(6.dp)
            val sl = jr.mapNotNull { it.slipPct }
            val ds = jr.mapNotNull { it.delayS }.sorted()
            Kpis(listOf(
                Triple("Closed", "${jr.size}" + if (jr.isNotEmpty()) " · ${jr.count { it.pnl > 0 } * 100 / jr.size}%" else "", C.Txt),
                Triple("Price vs leader", if (sl.isEmpty()) "-" else String.format(Locale.US, "%+.2f%%", sl.average()),
                    if (sl.isEmpty()) C.Txt else if (sl.average() > 0.3) C.Red else C.Green),
                Triple("Delay", if (ds.isEmpty()) "-" else "${ds[ds.size / 2].toInt()} s", C.Txt),
            ))
            T("Price vs leader = how much worse our entry is than theirs. Above ~0.3% copying the fast scalpers is not worth it.", 10.sp, C.Dim)
            err?.let { Gap(6.dp); T(it, 12.sp, C.Amber) }
            if (mode != "paper") { Gap(6.dp); T("Do not run the laptop copy bot in live mode at the same time, or every trade is copied twice.", 12.sp, C.Amber) }
            SectionTitle("Lead traders")
        }
        for (lv in v?.leaders ?: emptyList()) item(key = "L" + lv.leader.id) {
            val l = lv.leader
            Card(border = if (l.enabled) C.Green else C.Border) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T(l.name, 14.sp, C.Txt, FontWeight.Bold)
                        val feed = when { lv.positions != null -> "public"; lv.detail?.show == false -> "HIDDEN"; else -> "no data" }
                        T("Feed $feed · capital ${lv.detail?.margin?.let { "$" + f2(it) } ?: "-"} · slots ${lv.detail?.slots ?: "-"}", 11.sp,
                            if (feed == "public") C.Muted else C.Amber)
                        if (l.note.isNotEmpty()) T(l.note, 11.sp, C.Dim)
                    }
                    Switch(l.enabled, { on ->
                        vm.saveCopyCfg(c.copy(leaders = c.leaders.map { if (it.id == l.id) it.with(on) else it }))
                    }, colors = SwitchDefaults.colors(checkedTrackColor = C.Green, checkedThumbColor = C.Txt))
                }
                val ps = lv.positions?.values?.toList() ?: emptyList()
                if (ps.isEmpty()) T("No open positions now", 11.sp, C.Dim)
                ps.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                        Pill(p.side, C.side(p.side)); HGap(6.dp)
                        T("${p.symbol} ${p.lev}x · $${f2(p.notional)} @ ${px(p.entry)}", 11.sp, C.Muted, mono = true)
                    }
                }
                val mine = jr.filter { it.leader == l.name }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(if (mine.isEmpty()) "Your result: -" else "Your result: ${sgn2(mine.sumOf { it.pnl })} USDT · ${mine.size} trades", 11.sp,
                        if (mine.isEmpty()) C.Dim else pnlColor(mine.sumOf { it.pnl }), modifier = Modifier.weight(1f))
                    T("Remove", 11.sp, C.Red, modifier = Modifier.clickable { remove = l.id })
                }
            }
            Gap(6.dp)
        }
        item {
            Btn("+ Add a trader (link or ID)", C.Blue, outline = true, height = 40.dp) { addOpen = true }
            T("Switching a trader on skips the positions they already hold; only new trades are copied. Switching off stops new copies, " +
                "but copies already open still close when the trader closes.", 11.sp, C.Dim, modifier = Modifier.padding(top = 4.dp))
            SectionTitle("Open copies")
        }
        val open = v?.open ?: emptyList()
        if (open.isEmpty()) item { T("No open copies. One opens the moment a followed trader opens a new position.", 13.sp, C.Muted) }
        for ((cp, u) in open) item(key = "c" + cp.leaderId + cp.key) {
            Card(border = if (u == null) C.Border else pnlColor(u), onClick = { vm.showChart(cp.symbol) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(cp.symbol, 14.sp, C.Txt, FontWeight.Bold); HGap(8.dp); Pill(cp.side, C.side(cp.side), filled = true); HGap(6.dp)
                    T("${cp.lev}x", 11.sp, C.Muted); Box(Modifier.weight(1f))
                    T(u?.let { "${sgn2(it)} USDT" } ?: "-", 14.sp, pnlColor(u), FontWeight.Bold)
                }
                T("From ${cp.leader} · ${dayHhmm(cp.opened)}", 11.sp, C.Muted)
                T("Leader ${px(cp.leaderEntry)} · ours ${px(cp.entry)}" + (cp.slipPct?.let { " (${String.format(Locale.US, "%+.2f", it)}%)" } ?: "") +
                    " · ${f2(cp.qty * cp.entry)} USDT", 11.sp, C.Muted, mono = true)
                var ask by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("Closes by itself when ${cp.leader} closes", 10.sp, C.Dim, modifier = Modifier.weight(1f))
                    T("Close now", 13.sp, C.Red, FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(C.RedBg)
                        .clickable { ask = true }.padding(horizontal = 12.dp, vertical = 6.dp))
                }
                if (ask) AlertDialog(onDismissRequest = { ask = false },
                    confirmButton = { TextButton({ ask = false
                        val ck = cp.leaderId + "|" + cp.key
                        if (mode == "live") act.confirmUser("Close LIVE copy ${cp.symbol}") { vm.copyClose(ck) } else vm.copyClose(ck)
                    }) { Text("Close at market", color = C.Red) } },
                    dismissButton = { TextButton({ ask = false }) { Text("Keep") } },
                    title = { Text("Close ${cp.symbol} ${cp.side}?") },
                    text = { Text("Closes your copy at market now. The leader's position is not affected, and the bot will not re-open this trade.") },
                    containerColor = C.Panel)
            }
            Gap(6.dp)
        }
        item { SectionTitle("Closed copies") }
        if (jr.isEmpty()) item { T("No closed copies yet.", 13.sp, C.Muted) }
        for ((i, t) in jr.take(150).withIndex()) item(key = "cj$i") {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row { T(t.symbol, 13.sp, C.Txt, FontWeight.SemiBold); HGap(6.dp); T(t.side, 11.sp, C.side(t.side), FontWeight.Bold) }
                    T("${t.leader} · ${dayHhmm(t.opened)} → ${hhmm(t.closed)} · ${t.reason}", 11.sp, C.Muted)
                    T((t.slipPct?.let { String.format(Locale.US, "%+.2f%% vs leader", it) } ?: "") + (t.delayS?.let { " · ${it.toInt()} s late" } ?: ""), 10.sp, C.Dim)
                }
                Column(horizontalAlignment = Alignment.End) {
                    T(sgn2(t.pnl), 13.sp, pnlColor(t.pnl), FontWeight.Bold)
                    T("${sgn1(t.retPct)}% margin", 11.sp, pnlColor(t.retPct))
                }
            }
        }
        item { copySettings(vm, c) }
    }

    if (addOpen) {
        var text by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { addOpen = false },
            confirmButton = { TextButton({ vm.addLeader(text); addOpen = false }) { Text("Add") } },
            dismissButton = { TextButton({ addOpen = false }) { Text("Cancel") } },
            title = { Text("Add a lead trader") },
            text = {
                Column {
                    Text("Paste the trader's Binance Copy Trading link or portfolio ID. Only traders who show their positions publicly can be copied.", color = C.Muted)
                    Gap(8.dp)
                    OutlinedTextField(text, { text = it }, singleLine = true, colors = fieldColors(), placeholder = { Text("https://www.binance.com/…/lead-details/…") })
                }
            }, containerColor = C.Panel)
    }
    remove?.let { id ->
        AlertDialog(onDismissRequest = { remove = null },
            confirmButton = { TextButton({ vm.removeLeader(id); remove = null }) { Text("Remove", color = C.Red) } },
            dismissButton = { TextButton({ remove = null }) { Text("Cancel") } },
            title = { Text("Remove this trader?") },
            text = { Text("Copies already open from them keep following their exit.") }, containerColor = C.Panel)
    }
}

@Composable
private fun copySettings(vm: AppVM, c: pk.aidesk.app.core.CopyCfg) {
    Column {
        SectionTitle("Settings")
        fun save(n: pk.aidesk.app.core.CopyCfg) = vm.saveCopyCfg(n)
        Stepper("Size multiplier", String.format(Locale.US, "%.1fx", c.sizeMult), "1 = same % of money as the leader",
            onMinus = { save(c.copy(sizeMult = ((c.sizeMult * 10 - 1) / 10).coerceAtLeast(0.1))) },
            onPlus = { save(c.copy(sizeMult = ((c.sizeMult * 10 + 1) / 10).coerceAtMost(5.0))) })
        Stepper("Max size per copy", "${c.maxNotional.toInt()} USDT", "Position value cap",
            onMinus = { save(c.copy(maxNotional = (c.maxNotional - 5).coerceAtLeast(6.0))) },
            onPlus = { save(c.copy(maxNotional = (c.maxNotional + 5).coerceAtMost(500.0))) })
        Stepper("Max leverage", "${c.maxLev}x", onMinus = { save(c.copy(maxLev = (c.maxLev - 1).coerceAtLeast(1))) },
            onPlus = { save(c.copy(maxLev = (c.maxLev + 1).coerceAtMost(20))) })
        Stepper("Max open copies", "${c.maxPositions}", "Up to 25 at a time; total exposure cap below still applies",
            onMinus = { save(c.copy(maxPositions = (c.maxPositions - 1).coerceAtLeast(1))) },
            onPlus = { save(c.copy(maxPositions = (c.maxPositions + 1).coerceAtMost(25))) })
        Stepper("Max total exposure", String.format(Locale.US, "%.1f× equity", c.maxExposureX),
            onMinus = { save(c.copy(maxExposureX = (c.maxExposureX - 0.5).coerceAtLeast(0.5))) },
            onPlus = { save(c.copy(maxExposureX = (c.maxExposureX + 0.5).coerceAtMost(10.0))) })
        Stepper("Emergency stop", "${c.stopPct.toInt()}% move", "Placed on Binance in live, so it works offline", C.Red,
            onMinus = { save(c.copy(stopPct = (c.stopPct - 5).coerceAtLeast(5.0))) },
            onPlus = { save(c.copy(stopPct = (c.stopPct + 5).coerceAtMost(50.0))) })
        Stepper("Daily loss limit", "${c.dailyLossPct.toInt()}%", "No new copies for the rest of the UTC day", C.Red,
            onMinus = { save(c.copy(dailyLossPct = (c.dailyLossPct - 1).coerceAtLeast(2.0))) },
            onPlus = { save(c.copy(dailyLossPct = (c.dailyLossPct + 1).coerceAtMost(50.0))) })
        Stepper("Check every", "${c.pollSec} s", "Faster = smaller delay, more battery",
            onMinus = { save(c.copy(pollSec = (c.pollSec - 5).coerceAtLeast(5))) },
            onPlus = { save(c.copy(pollSec = (c.pollSec + 5).coerceAtMost(60))) })
        Gap(6.dp)
        T("Size = leader's trade × (your money ÷ their money) × multiplier, raised to Binance's ~5 USDT minimum when needed. " +
            "Copying needs the app running in the background: set Battery to Unrestricted in Settings.", 11.sp, C.Muted)
        Gap(24.dp)
    }
}
