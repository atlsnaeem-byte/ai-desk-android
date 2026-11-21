package pk.aidesk.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pk.aidesk.app.MainActivity
import pk.aidesk.app.TopBar
import pk.aidesk.app.core.ManualPlan
import pk.aidesk.app.core.StratBook
import pk.aidesk.app.core.Targets
import pk.aidesk.app.service.TraderService
import java.util.Locale
import kotlin.math.abs

/** TP1..TP5 (1R..5R) with the ones already reached ticked. */
@Composable
fun TargetsRow(side: String, entry: Double, sl: Double, hit: Int? = null, tps: List<Double>? = null) {
    val t = tps ?: Targets.ladder(side, entry, sl)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        T("SL ${px(sl)}", 10.sp, C.Red, mono = true)
        for (k in 0 until 5) {
            val done = hit != null && hit > k
            T((if (done) "✓" else "") + "TP${k + 1} ${px(t[k])}", 10.sp, if (done) C.Green else C.Muted, if (done) FontWeight.Bold else FontWeight.Normal, mono = true)
        }
    }
}

private class PendingTrade(val title: String, val symbol: String, val side: String, val sl: Double, val setup: String, val sigId: String?)

@Composable
private fun TradeConfirm(vm: AppVM, act: MainActivity, title: String, symbol: String, side: String, sl: Double, setup: String, sigId: String?,
                         onDone: () -> Unit) {
    val cfg by vm.config.collectAsState()
    AlertDialog(onDismissRequest = onDone,
        confirmButton = {
            TextButton({
                val go = { vm.stratTrade(symbol, side, sl, setup, sigId); onDone() }
                if (cfg.mode == "live") act.confirmUser("LIVE $side $symbol") { go() } else go()
            }) { Text("Place ${cfg.mode.uppercase()} $side", color = C.side(side)) }
        },
        dismissButton = { TextButton(onDone) { Text("Cancel", color = C.Muted) } },
        title = { Text(title) },
        text = {
            Text("Market $side $symbol now. Stop ${px(sl)}. Five targets at 1R…5R from your fill: 1/5 of the position closes at each, " +
                "the stop stays for the rest. Risk ${pctTxt(cfg.riskPct)}% of equity, ${cfg.leverage}x. You can close part or all, DCA or add margin " +
                "any time in Positions > Manage.")
        }, containerColor = C.Panel)
}

@Composable
fun StrategiesScreen(vm: AppVM, act: MainActivity) {
    val sel by vm.strat.collectAsState()
    val auto by vm.stratAuto.collectAsState()
    val sigs by vm.stratSigs.collectAsState()
    val cfg by vm.config.collectAsState()
    val ticks by TraderService.ticks.collectAsState()
    var menu by remember { mutableStateOf(false) }
    val ta by vm.taSignals.collectAsState()
    val marks by vm.marks.collectAsState()
    var confirm by remember { mutableStateOf<PendingTrade?>(null) }
    LaunchedEffect(ticks) { vm.loadStrats(); vm.loadTa() }
    LaunchedEffect(sel) { while (true) { if (sel == StratBook.RBF) vm.loadRbf() else if (sel == StratBook.ASIA) vm.loadGold(); delay(60_000) } }
    LaunchedEffect(Unit) { while (true) { vm.refreshMarks(); delay(15_000) } }
    val info = StratBook.info(sel)
    val on = auto[sel] == true
    Column(Modifier.fillMaxSize()) {
        TopBar(vm, "Strategies")
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            item {
                // dropdown with every strategy
                Box {
                    Card(border = C.Blue, onClick = { menu = true }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                T(info.name, 15.sp, C.Txt, FontWeight.Bold)
                                T(info.market, 11.sp, C.Muted)
                            }
                            T(if (on) "AUTO" else "manual", 11.sp, if (on) C.Green else C.Muted, FontWeight.Bold); HGap(8.dp)
                            T("▼", 14.sp, C.Blue)
                        }
                    }
                    DropdownMenu(menu, { menu = false }) {
                        for (s in StratBook.ALL) DropdownMenuItem(text = {
                            Column {
                                Text(s.name, color = C.Txt, fontWeight = FontWeight.SemiBold)
                                Text(s.market + if (auto[s.id] == true) " · AUTO on" else "", color = if (auto[s.id] == true) C.Green else C.Muted, fontSize = 11.sp)
                            }
                        }, onClick = { vm.strat.value = s.id; menu = false })
                    }
                }
                Gap(10.dp)
                // auto / manual controls
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            T("Auto-trade", 14.sp, C.Txt, FontWeight.Bold)
                            T(if (on) "ON (${cfg.mode.uppercase()}): every new signal is traded, risk ${pctTxt(cfg.riskPct)}% · ${cfg.leverage}x, 5 targets"
                                else "OFF: signals and alerts only, trade by hand below", 11.sp, if (on) C.Green else C.Muted)
                        }
                        Switch(on, { v ->
                            if (v && cfg.mode == "live") act.confirmUser("Auto-trade ${info.name} LIVE") { vm.setStratAuto(sel, true) } else vm.setStratAuto(sel, v)
                        }, colors = SwitchDefaults.colors(checkedTrackColor = C.Green, checkedThumbColor = C.Txt))
                    }
                    T("Targets: TP1–TP5 at 1R–5R; 1/5 closes at each, the stop stays for the rest. Close all / part, DCA and add margin: Positions > Manage.",
                        10.sp, C.Dim)
                    T(info.backtest, 10.sp, C.Amber)
                    T(info.targets, 10.sp, C.Dim)
                }
                Gap(10.dp)
                SectionTitle("Now")
            }
            when (sel) {
                StratBook.RBF -> item { RbfNow(vm) { e -> confirm = PendingTrade("Trade ${info.name}", "XAUUSDT", e.sideName, e.sl, "Strategy ${info.name}", e.id) } }
                StratBook.ASIA -> item {
                    val g by vm.gold.collectAsState(); val ge by vm.goldErr.collectAsState()
                    GoldCard(g, ge)
                    val gg = g
                    Gap(6.dp)
                    if (gg != null && gg.status == "SIGNAL" && gg.result == null) {
                        TargetsRow(gg.sideName, gg.entry, gg.sl)
                        Gap(6.dp)
                        Btn("Trade manually · ${gg.sideName} XAUUSDT", C.side(gg.sideName), height = 42.dp) {
                            confirm = PendingTrade("Trade ${info.name}", "XAUUSDT", gg.sideName, gg.sl, "Strategy ${info.name}", "asia-${gg.signalTime}")
                        }
                    } else Btn("Trade manually · no signal now", C.Dim, enabled = false, height = 42.dp) {}
                    Gap(4.dp)
                    T("Chart", 12.sp, C.Blue, FontWeight.SemiBold, modifier = Modifier.clickable { vm.showChart("XAUUSDT") })
                }
                else -> {
                    val act0 = ta.filter { it.active }
                    if (act0.isEmpty()) item { T("No course setup active. The coins are checked at every 4h close.", 12.sp, C.Muted) }
                    items(act0, key = { "st" + it.id }) { s ->
                        TaCard(s, marks[s.symbol], null) { vm.openTaSignal(s.symbol, s.tf) }
                        Gap(4.dp)
                        Btn("Trade manually · ${s.side} ${s.symbol}", C.side(s.side), height = 40.dp) {
                            confirm = PendingTrade("Trade course setup", s.symbol, s.side, s.sl, "Course ${s.tf}: ${s.title}", null)
                        }
                        Gap(8.dp)
                    }
                }
            }
            item {
                SectionTitle("Record · targets reached before the stop")
                val mine = sigs.filter { it.strat == sel }
                vm.stratBook.stats(sel)?.let { T(it, 12.sp, C.Txt, FontWeight.SemiBold) }
                    ?: T(if (sel == StratBook.COURSE) "Course setups are recorded on the Signals tab (results line there)." else "No finished signal recorded yet.", 12.sp, C.Muted)
                Gap(6.dp)
                for (s in mine.take(25)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(dayHhmm(s.time), 11.sp, C.Muted, mono = true); HGap(6.dp)
                        T(s.side, 11.sp, C.side(s.side), FontWeight.Bold); HGap(6.dp)
                        T("E ${px(s.entry)}", 11.sp, C.Txt, mono = true, modifier = Modifier.weight(1f))
                        T((1..5).joinToString("") { if (s.tpHit >= it) "●" else "○" }, 12.sp, C.Green); HGap(6.dp)
                        T(s.result ?: "open", 11.sp, when (s.result) { null -> C.Blue; "SL" -> C.Red; else -> C.Muted }, FontWeight.SemiBold)
                        if (s.traded) { HGap(4.dp); T("traded", 10.sp, C.Amber) }
                    }
                }
                Gap(24.dp)
            }
        }
    }
    confirm?.let { p -> TradeConfirm(vm, act, p.title, p.symbol, p.side, p.sl, p.setup, p.sigId) { confirm = null } }
}

@Composable
private fun RbfNow(vm: AppVM, onTrade: (pk.aidesk.app.core.Rbf.Entry) -> Unit) {
    val v by vm.rbf.collectAsState()
    val err by vm.rbfErr.collectAsState()
    val vv = v ?: run { Card { T(err?.let { "Gold data error: $it" } ?: "Loading XAUUSDT 15m + 5m…", 12.sp, if (err != null) C.Amber else C.Muted) }; return }
    val open = vv.entries.firstOrNull { it.result == null }
    Card(border = open?.let { C.side(it.sideName) } ?: C.Border) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("XAUUSDT", 15.sp, C.Txt, FontWeight.Bold); HGap(8.dp)
            when {
                open != null -> Pill("SIGNAL ${open.sideName}", C.side(open.sideName), filled = true)
                vv.waiting.isNotEmpty() -> Pill("${vv.waiting.size} setup(s) waiting", C.Blue)
                else -> Pill("no setup", C.Muted)
            }
            Box(Modifier.weight(1f)); T(px(vv.price), 13.sp, C.Txt, FontWeight.SemiBold, mono = true)
        }
        Gap(4.dp)
        for (s in vv.waiting.takeLast(3).reversed()) {
            val sd = if (s.side == 1) "BUY" else "SELL"
            T("$sd setup · level ${px(s.level)} · FVG ${px(s.fvgLo)}–${px(s.fvgHi)} · stop ${px(s.sl)} · until ${hhmm(s.expire)}", 11.sp, C.Muted)
            T(if (s.side == -1) "Waits for price to come up into the FVG, then the first red 5m candle → sell."
                else "Waits for price to come down into the FVG, then the first green 5m candle → buy.", 10.sp, C.Dim)
        }
        if (open != null) {
            Gap(6.dp)
            T("Entry ${px(open.entry)} at ${hhmm(open.entryTime)} PKT · now ${sgn2(pk.aidesk.app.core.Rbf.rOf(open, vv.price))}R", 12.sp, C.Txt, FontWeight.SemiBold)
            TargetsRow(open.sideName, open.entry, open.sl, open.tpHit, open.tps)
            Gap(6.dp)
            Btn("Trade manually · ${open.sideName} XAUUSDT", C.side(open.sideName), height = 42.dp) { onTrade(open) }
        } else {
            Gap(6.dp); Btn("Trade manually · no signal now", C.Dim, enabled = false, height = 42.dp) {}
        }
        Gap(4.dp)
        T("Chart", 12.sp, C.Blue, FontWeight.SemiBold, modifier = Modifier.clickable { vm.showChart("XAUUSDT") })
    }
    if (vv.entries.isNotEmpty()) {
        Gap(8.dp)
        T("Last signals (24 h)", 12.sp, C.Txt, FontWeight.Bold)
        for (e in vv.entries.take(6)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                T(hhmm(e.entryTime), 11.sp, C.Muted, mono = true); HGap(6.dp)
                T(e.sideName, 11.sp, C.side(e.sideName), FontWeight.Bold); HGap(6.dp)
                T("E ${px(e.entry)}", 11.sp, C.Txt, mono = true, modifier = Modifier.weight(1f))
                T((1..5).joinToString("") { if (e.tpHit >= it) "●" else "○" }, 12.sp, C.Green); HGap(6.dp)
                T(e.result ?: "open", 11.sp, if (e.result == "SL") C.Red else C.Muted)
            }
        }
    }
}

// ------------------------------------------------------------------ Prediction page (BTC Up/Down 5m)
@Composable
fun PredictionScreen(vm: AppVM, act: MainActivity) {
    val now = rememberNow()
    val bv by vm.btcView.collectAsState()
    val running by TraderService.running.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar(vm, "Prediction")
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            item {
                T("BTC Up / Down · 5-minute rounds (Binance Prediction)", 14.sp, C.Txt, FontWeight.Bold)
                Gap(6.dp)
                val cfg = bv?.cfg
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            T("Auto-trade", 14.sp, C.Txt, FontWeight.Bold)
                            T(when {
                                cfg == null -> "…"
                                cfg.mode == "paper" && cfg.record -> "ON (PAPER): the first confirmed call of every round is bet automatically."
                                cfg.mode == "live" -> "LIVE: Binance Prediction has no trading API, so the app cannot bet for you. It alerts; you bet and tap \"I bet\"."
                                else -> "OFF: turn on to record calls and paper-bet them automatically."
                            }, 11.sp, if (cfg?.record == true) C.Green else C.Muted)
                        }
                        if (cfg != null) Switch(cfg.record, { vm.saveBtcCfg(cfg.copy(record = it)) },
                            colors = SwitchDefaults.colors(checkedTrackColor = C.Green, checkedThumbColor = C.Txt))
                    }
                    if (cfg?.record == true && !running) T("Needs the scanner running: tap the red bar on the Signals tab.", 11.sp, C.Amber)
                    T("Manual: when a call is CONFIRMED, use the bet button on the card below.", 10.sp, C.Dim)
                }
                Gap(8.dp)
                Btc5mCard(vm, now)
                Gap(8.dp)
                Btc5mRecordCard(vm, act)
                Gap(24.dp)
            }
        }
    }
}

// ------------------------------------------------------------------ Positions > Manage (partial close, DCA, add margin, targets)
@Composable
fun ManageDialog(vm: AppVM, act: MainActivity, plan: ManualPlan, mark: Double?, onDone: () -> Unit) {
    val cfg by vm.config.collectAsState()
    var dca by remember { mutableStateOf("") }
    var mg by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf("") }
    fun guard(what: String, go: () -> Unit) { if (cfg.mode == "live") act.confirmUser(what) { go() } else go() }
    val sym = plan.symbol
    AlertDialog(onDismissRequest = onDone, confirmButton = { TextButton(onDone) { Text("Done", color = C.Blue) } },
        title = { Text("Manage $sym ${plan.side}") },
        text = {
            Column {
                val fill = plan.fill ?: plan.entry
                T("Entry ${px(fill)} · open ${px(plan.qtyLeft)} of ${px(plan.qty)} · ${String.format(Locale.US, "%.1f", plan.effLev)}x" +
                    (mark?.let { " · now ${sgn2(plan.dir * (it - fill) / abs(fill - plan.sl))}R" } ?: ""), 12.sp, C.Txt)
                if (plan.ladder) { Gap(4.dp); TargetsRow(plan.side, fill, plan.sl, plan.tpDone, plan.tps)
                    T("1/5 closes at each target; ${5 - plan.tpDone} left.", 10.sp, C.Dim) }
                else T("Single target ${px(plan.tp)} · stop ${px(plan.sl)}", 11.sp, C.Muted)
                Gap(10.dp)
                T("Close", 12.sp, C.Txt, FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (p in listOf(25.0, 50.0, 75.0)) Pill("${p.toInt()}%", C.Amber) { guard("Close ${p.toInt()}% of $sym") { vm.closePart(sym, p, onDone) } }
                    Pill("Close all", C.Red, filled = true) { guard("Close all $sym") { vm.close(sym); onDone() } }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(custom, { custom = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, singleLine = true, colors = fieldColors(),
                        label = { Text("other %") }, modifier = Modifier.weight(1f))
                    HGap(8.dp)
                    Pill("Close", C.Amber) { custom.toDoubleOrNull()?.takeIf { it in 1.0..100.0 }?.let { p -> guard("Close ${p}% of $sym") { vm.closePart(sym, p, onDone) } } }
                }
                Gap(10.dp)
                T("DCA (add to the position at market, same stop)", 12.sp, C.Txt, FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(dca, { dca = it.filter { c -> c.isDigit() || c == '.' }.take(8) }, singleLine = true, colors = fieldColors(),
                        label = { Text("margin USDT × ${plan.lev}x") }, modifier = Modifier.weight(1f))
                    HGap(8.dp)
                    Pill("Add", C.Blue) { dca.toDoubleOrNull()?.let { u -> guard("DCA $u USDT into $sym") { vm.dca(sym, u, onDone) } } }
                }
                Gap(10.dp)
                T("Add margin (lowers leverage, moves liquidation away)", 12.sp, C.Txt, FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(mg, { mg = it.filter { c -> c.isDigit() || c == '.' }.take(8) }, singleLine = true, colors = fieldColors(),
                        label = { Text("USDT") }, modifier = Modifier.weight(1f))
                    HGap(8.dp)
                    Pill("Add", C.Green) { mg.toDoubleOrNull()?.let { u -> guard("Add $u USDT margin to $sym") { vm.addMargin(sym, u, onDone) } } }
                }
                if (plan.extraMargin > 0) T("Margin added so far: ${f2(plan.extraMargin)} USDT", 10.sp, C.Dim)
            }
        }, containerColor = C.Panel)
}
