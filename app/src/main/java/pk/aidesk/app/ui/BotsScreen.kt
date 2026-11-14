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
            Pill("Pump hunter", if (sub == "pumps") C.Blue else C.Muted, filled = sub == "pumps") { vm.botTab.value = "pumps" }
            Pill("Copy trade", if (sub == "copy") C.Blue else C.Muted, filled = sub == "copy") { vm.botTab.value = "copy" }
        }
        Gap(6.dp)
        when (sub) {
            "copy" -> CopyPane(vm, act)
            "pumps" -> PumpsPane(vm, act)
            else -> ShortsPane(vm, act)
        }
    }
}

// ================================================================== Pump hunter (LONG)
@Composable
private fun PumpsPane(vm: AppVM, act: MainActivity) {
    val cfgApp by vm.config.collectAsState()
    val v by vm.pumps.collectAsState()
    val err by vm.pumpsErr.collectAsState()
    val loading by vm.pumpsLoading.collectAsState()
    LaunchedEffect(cfgApp.mode) { vm.loadPumps(true) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); vm.loadPumps(false) } }
    LaunchedEffect(Unit) { while (true) { vm.refreshMarks(); delay(15_000) } }
    val marks by vm.marks.collectAsState()
    val c = v?.cfg ?: vm.pumpHunter.cfg()
    val mode = cfgApp.mode
    fun save(n: pk.aidesk.app.core.PumpCfg, msg: String? = null) { vm.savePumpCfg(n); msg?.let { vm.toast.value = it } }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            T("LONG bot for pumps (${mode.uppercase()}). Base breakouts are checked once a day after the 05:00 PKT daily close; " +
                "listing news is checked every 15 s while a news rule is on. Own wallet and journal, separate from the Short hunter.", 11.sp, C.Muted)
            Gap(6.dp)
            EnableRow("Pump hunter", if (c.enabled) "ON · max ${c.maxPositions} longs · ${pctTxt(c.marginPct)}% each × ${c.leverage}x" else "OFF · signals only",
                c.enabled, mode, act) { save(c.copy(enabled = it), if (it) "Pump hunter on (${mode.uppercase()})" else "Pump hunter off. Open longs keep their SL/TP.") }
            Gap(6.dp)
            EnableRow("1 · Base breakout", "${c.boBaseDays}-day base ≤ ${pctTxt(c.boRangePct)}% range, close above it +${pctTxt(c.boMinDayPct)}–${pctTxt(c.boMaxDayPct)}% " +
                "on ${pctTxt(c.boVolX)}× volume · SL −${pctTxt(c.boSlPct)}% · TP +${pctTxt(c.boTpPct)}% · ${c.boHoldDays} days", c.breakout, mode, act) {
                save(c.copy(breakout = it))
            }
            Gap(6.dp)
            EnableRow("2 · Upbit KRW listing", "Long the Binance perp seconds after Upbit adds the coin to KRW · SL −${pctTxt(c.newsSlPct)}% · TP +${pctTxt(c.newsTpPct)}% · ${c.newsHoldH} h",
                c.upbit, mode, act) { save(c.copy(upbit = it)) }
            Gap(6.dp)
            EnableRow("3 · Binance spot listing", "Long the perp after Binance announces a spot listing (perp already trading) · same SL / TP",
                c.binanceSpot, mode, act) { save(c.copy(binanceSpot = it)) }
            Gap(8.dp)
            val st = v?.state; val jr = v?.journal ?: emptyList()
            val pnl = jr.sumOf { it.pnl }
            val openPnl = st?.open?.values?.sumOf { p -> marks[p.symbol]?.let { (it - p.entry) * p.qty } ?: 0.0 } ?: 0.0
            Kpis(listOf(
                Triple(if (mode == "paper") "Paper equity" else "Mode", if (mode == "paper") st?.let { f2(it.paperEquity) } ?: "-" else mode.uppercase(), C.Txt),
                Triple("Open", "${st?.open?.size ?: 0}/${c.maxPositions}", C.Txt),
                Triple("Open P&L", sgn2(openPnl), pnlColor(openPnl)),
                Triple("Closed P&L", sgn2(pnl), pnlColor(pnl)),
            ))
            LiqWarnings(listOfNotNull(
                pk.aidesk.app.core.Liq.warn(c.boSlPct, c.leverage, "Base breakout"),
                pk.aidesk.app.core.Liq.warn(c.newsSlPct, c.leverage, "Listing news")))
            err?.let { Gap(6.dp); T(it, 12.sp, C.Amber) }
            Gap(6.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(v?.let { "Updated ${hhmm(it.at)}" + if (loading) " · loading…" else "" } ?: "Loading…", 11.sp, C.Dim, modifier = Modifier.weight(1f))
                T("Refresh", 12.sp, C.Blue, FontWeight.SemiBold, modifier = Modifier.clickable { vm.loadPumps(true) })
            }
            SectionTitle("Open longs")
        }
        val open = v?.state?.open?.values?.toList() ?: emptyList()
        if (open.isEmpty()) item { T("No open longs.", 13.sp, C.Muted) }
        for (p in open) item(key = "po" + p.symbol) {
            val mk = marks[p.symbol]
            val upnl = mk?.let { (it - p.entry) * p.qty }
            var ask by remember { mutableStateOf(false) }
            var edit by remember { mutableStateOf(false) }
            Card(border = if (upnl == null) C.Border else pnlColor(upnl), onClick = { vm.showChart(p.symbol) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(p.symbol, 14.sp, C.Txt, FontWeight.Bold); HGap(8.dp); Pill("LONG", C.Green, filled = true); Box(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        T(upnl?.let { (if (it >= 0) "Profit " else "Loss ") + "${sgn2(it)} USDT" } ?: "-", 15.sp, pnlColor(upnl), FontWeight.Bold)
                        upnl?.let { T("${sgn1(it / p.margin * 100)}% of ${f2(p.margin)} margin", 11.sp, pnlColor(it)) }
                    }
                }
                T("Entry ${px(p.entry)} · now ${px(mk)} · SL ${px(p.sl)} · TP ${px(p.tp)}", 11.sp, C.Muted, mono = true)
                Gap(2.dp)
                TpSlAmounts("LONG", p.entry, p.qty, p.sl, p.tp)
                T("Opened ${dayHhmm(p.opened)} · ${p.rule} · time exit ${dayHhmm(p.exitAt)}", 11.sp, C.Muted)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Box(Modifier.weight(1f))
                    T("Edit SL / TP", 13.sp, C.Blue, FontWeight.Bold, modifier = Modifier.clickable { edit = true })
                    HGap(14.dp)
                    T("Close now", 13.sp, C.Red, FontWeight.Bold, modifier = Modifier.clickable { ask = true })
                }
            }
            if (edit) EditExitsDialog(vm, act, mode, p.symbol, "LONG", p.entry, p.qty, p.sl, p.tp, mk) { edit = false }
            if (ask) AlertDialog(onDismissRequest = { ask = false },
                confirmButton = { TextButton({ ask = false
                    if (mode == "live") act.confirmUser("Close LIVE long ${p.symbol}") { vm.pumpClose(p.symbol) } else vm.pumpClose(p.symbol)
                }) { Text("Close at market", color = C.Red) } },
                dismissButton = { TextButton({ ask = false }) { Text("Keep") } },
                title = { Text("Close ${p.symbol} long?") }, text = { Text("Sells the long at market and removes its SL/TP.") },
                containerColor = C.Panel)
            Gap(6.dp)
        }
        item {
            val sc = v?.scan
            SectionTitle("Base breakouts (last daily close)")
            T(sc?.let { "Scanned ${dayHhmm(it.at)} · ${it.checked} coins checked · ${it.list.size} breakout(s)" } ?: "Not scanned yet today.", 11.sp, C.Dim)
            T("Scan now", 12.sp, C.Blue, FontWeight.SemiBold, modifier = Modifier.clickable { vm.loadBreakouts(true) }.padding(vertical = 4.dp))
        }
        val bos = v?.scan?.list ?: emptyList()
        if (bos.isEmpty()) item { T("No base breakout on the last daily close.", 13.sp, C.Muted) }
        for (b in bos) item(key = "bo" + b.symbol) {
            val isOpen = v?.state?.open?.containsKey(b.symbol) == true
            Card(border = C.Green) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(b.symbol, 14.sp, C.Txt, FontWeight.Bold, modifier = Modifier.weight(1f))
                    Pill("${sgn1(b.dayPct)}% day", C.Green); HGap(6.dp); Pill(String.format(Locale.US, "%.1fx vol", b.volX), C.Amber)
                }
                T("Base ${px(b.baseLow)} – ${px(b.baseHigh)} (${pctTxt(Math.round(b.rangePct).toDouble())}% range) · close ${px(b.close)}", 11.sp, C.Muted, mono = true)
                T("Plan: long ~${px(marks[b.symbol] ?: b.close)} · SL −${pctTxt(c.boSlPct)}% · TP +${pctTxt(c.boTpPct)}%", 11.sp, C.Muted)
                if (!isOpen) {
                    Gap(6.dp)
                    var ask by remember { mutableStateOf(false) }
                    Btn("Open long now", C.Green, height = 40.dp) { ask = true }
                    if (ask) AlertDialog(onDismissRequest = { ask = false },
                        confirmButton = { TextButton({ ask = false
                            if (mode == "live") act.confirmUser("Open LIVE long ${b.symbol}") { vm.pumpOpen(b.symbol) } else vm.pumpOpen(b.symbol)
                        }) { Text("Open long", color = C.Green) } },
                        dismissButton = { TextButton({ ask = false }) { Text("Cancel") } },
                        title = { Text("Long ${b.symbol} (${mode.uppercase()})?") },
                        text = { Text("Market buy, ${pctTxt(c.marginPct)}% of equity as margin × ${c.leverage}x. SL −${pctTxt(c.boSlPct)}% / TP +${pctTxt(c.boTpPct)}%, " +
                            "closes after ${c.boHoldDays} days. Stop and target are placed on Binance.") },
                        containerColor = C.Panel)
                }
            }
            Gap(6.dp)
        }
        item { SectionTitle("Latest listing news (Upbit KRW / Binance spot)") }
        val nw = v?.news ?: emptyList()
        if (nw.isEmpty()) item { T("No recent listing post naming a Binance futures coin.", 13.sp, C.Muted) }
        for (n in nw.take(15)) item(key = "n" + n.key) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("${n.symbol} · ${n.source}", 12.sp, C.Txt, FontWeight.SemiBold)
                    T("${dayHhmm(n.time)} · ${n.title}", 10.sp, C.Muted, maxLines = 2)
                }
                val fresh = System.currentTimeMillis() - n.time < c.newsMaxAgeMin * 60_000L
                Pill(if (fresh) "NEW" else "old", if (fresh) C.Green else C.Muted)
            }
        }
        item { SectionTitle("Closed longs") }
        val jr = v?.journal ?: emptyList()
        if (jr.isEmpty()) item { T("No closed trades yet.", 13.sp, C.Muted) }
        for ((i, t) in jr.take(150).withIndex()) item(key = "pj$i") {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T(t.symbol + if (t.rule.isNotEmpty()) " · ${t.rule}" else "", 13.sp, C.Txt, FontWeight.SemiBold, maxLines = 1)
                    T("${dayHhmm(t.opened)} → ${dayHhmm(t.closed)} · ${t.reason}", 11.sp, C.Muted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    T((if (t.pnl >= 0) "Profit " else "Loss ") + "${sgn2(t.pnl)} USDT", 13.sp, pnlColor(t.pnl), FontWeight.Bold)
                    t.retPct?.let { T("${sgn1(it)}% margin", 11.sp, pnlColor(it)) }
                }
            }
        }
        item {
            SectionTitle("Size and risk (tap a number to type any value)")
            Stepper("Margin per long", "${pctTxt(c.marginPct)}% of equity", "Binance needs 5+ USDT per position",
                onMinus = { save(c.copy(marginPct = (c.marginPct - 1).coerceAtLeast(0.5))) },
                onPlus = { save(c.copy(marginPct = (c.marginPct + 1).coerceAtMost(100.0))) },
                onSet = { save(c.copy(marginPct = it.coerceIn(0.1, 100.0))) })
            Stepper("Leverage", "${c.leverage}x", "At 5x a −20% stop costs the whole margin",
                onMinus = { save(c.copy(leverage = (c.leverage - 1).coerceAtLeast(1))) },
                onPlus = { save(c.copy(leverage = (c.leverage + 1).coerceAtMost(125))) },
                onSet = { save(c.copy(leverage = it.toInt().coerceIn(1, 125))) })
            Stepper("Max open longs", "${c.maxPositions}",
                onMinus = { save(c.copy(maxPositions = (c.maxPositions - 1).coerceAtLeast(1))) },
                onPlus = { save(c.copy(maxPositions = c.maxPositions + 1)) },
                onSet = { save(c.copy(maxPositions = it.toInt().coerceAtLeast(1))) })
            SectionTitle("Rule settings")
            Stepper("Breakout: base days", "${c.boBaseDays}", onMinus = { save(c.copy(boBaseDays = (c.boBaseDays - 5).coerceAtLeast(10))) },
                onPlus = { save(c.copy(boBaseDays = (c.boBaseDays + 5).coerceAtMost(200))) }, onSet = { save(c.copy(boBaseDays = it.toInt().coerceIn(10, 200))) })
            Stepper("Breakout: max base range", "${pctTxt(c.boRangePct)}%", onMinus = { save(c.copy(boRangePct = (c.boRangePct - 5).coerceAtLeast(10.0))) },
                onPlus = { save(c.copy(boRangePct = c.boRangePct + 5)) }, onSet = { save(c.copy(boRangePct = it.coerceAtLeast(5.0))) })
            Stepper("Breakout: min volume", "${pctTxt(c.boVolX)}×", onMinus = { save(c.copy(boVolX = (c.boVolX - 0.5).coerceAtLeast(1.0))) },
                onPlus = { save(c.copy(boVolX = c.boVolX + 0.5)) }, onSet = { save(c.copy(boVolX = it.coerceAtLeast(1.0))) })
            Stepper("Breakout: stop-loss", "−${pctTxt(c.boSlPct)}%", onMinus = { save(c.copy(boSlPct = (c.boSlPct - 5).coerceAtLeast(5.0))) },
                onPlus = { save(c.copy(boSlPct = (c.boSlPct + 5).coerceAtMost(90.0))) }, onSet = { save(c.copy(boSlPct = it.coerceIn(1.0, 95.0))) })
            Stepper("Breakout: take-profit", "+${pctTxt(c.boTpPct)}%", onMinus = { save(c.copy(boTpPct = (c.boTpPct - 10).coerceAtLeast(5.0))) },
                onPlus = { save(c.copy(boTpPct = c.boTpPct + 10)) }, onSet = { save(c.copy(boTpPct = it.coerceAtLeast(1.0))) })
            Stepper("Breakout: hold days", "${c.boHoldDays}", onMinus = { save(c.copy(boHoldDays = (c.boHoldDays - 1).coerceAtLeast(1))) },
                onPlus = { save(c.copy(boHoldDays = c.boHoldDays + 1)) }, onSet = { save(c.copy(boHoldDays = it.toInt().coerceAtLeast(1))) })
            Stepper("Listing news: stop-loss", "−${pctTxt(c.newsSlPct)}%", onMinus = { save(c.copy(newsSlPct = (c.newsSlPct - 5).coerceAtLeast(5.0))) },
                onPlus = { save(c.copy(newsSlPct = (c.newsSlPct + 5).coerceAtMost(90.0))) }, onSet = { save(c.copy(newsSlPct = it.coerceIn(1.0, 95.0))) })
            Stepper("Listing news: take-profit", "+${pctTxt(c.newsTpPct)}%", onMinus = { save(c.copy(newsTpPct = (c.newsTpPct - 5).coerceAtLeast(5.0))) },
                onPlus = { save(c.copy(newsTpPct = c.newsTpPct + 5)) }, onSet = { save(c.copy(newsTpPct = it.coerceAtLeast(1.0))) })
            Stepper("Listing news: hold hours", "${c.newsHoldH}", onMinus = { save(c.copy(newsHoldH = (c.newsHoldH - 4).coerceAtLeast(1))) },
                onPlus = { save(c.copy(newsHoldH = c.newsHoldH + 4)) }, onSet = { save(c.copy(newsHoldH = it.toInt().coerceAtLeast(1))) })
            val need = kotlin.math.ceil(5.0 / (c.marginPct / 100 * c.leverage)).toInt()
            Gap(6.dp)
            T("Each long = ${pctTxt(c.marginPct)}% of equity × ${c.leverage}x. You need at least $need USDT equity for Binance's 5 USDT minimum.", 11.sp, C.Muted)
            SectionTitle("Backtest (2021 – Nov 2026, after fees and funding)")
            T("Base breakout (60-day base): +12.1% per trade in 2021-25 but −5.4% in 2025 and −0.2% in 2026 (243 trades). " +
                "Upbit KRW listing: −1.7% (2021-25), +4.7% (2026, 38 trades); one minute after the post the price is already up 17-29% on average. " +
                "Binance spot listing: −2.3% / +1.0% (only 36 events). No rule was positive in both periods, so keep size small. Past results are not a promise.",
                12.sp, C.Muted)
            Gap(24.dp)
        }
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

/** Red box: stops that sit beyond the liquidation price at the chosen leverage. */
@Composable
private fun LiqWarnings(w: List<String>) {
    if (w.isEmpty()) return
    Gap(6.dp)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.RedBg).padding(10.dp)) {
        T("Liquidation risk", 13.sp, C.Red, FontWeight.Bold)
        for (x in w) { Gap(2.dp); T(x, 11.sp, C.Txt) }
        Gap(2.dp)
        T("Paper mode now simulates this: a liquidated trade loses its whole margin.", 10.sp, C.Muted)
    }
}
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
    fun save(n: pk.aidesk.app.core.ShortCfg, msg: String? = null) { vm.saveShortCfg(n); msg?.let { vm.toast.value = it } }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            T("All rules trade automatically (${mode.uppercase()}), checked every 15 minutes in the background. Paper mode needs no API key.",
                11.sp, C.Muted)
            Gap(6.dp)
            EnableRow("1 · New-listing short", "Short every new coin on day ${c.entryDay} · SL +${c.slPct.toInt()}% · TP −${c.tpPct.toInt()}% · ${c.holdDays} days",
                c.enabled, mode, act) { save(c.copy(enabled = it), if (it) "New-listing shorts on" else "New-listing shorts off") }
            Gap(6.dp)
            EnableRow("2 · Pump short", "+50% in 1 day / +100% in 3 days" +
                (if (c.fundingFilterOn) " · only if funding ≥ +${String.format(Locale.US, "%.2f", c.pumpMinFunding)}%" else " · no funding filter") +
                " · SL +${c.pumpSlPct.toInt()}% · TP −${c.pumpTpPct.toInt()}% · ${c.pumpHoldDays} days", c.pumpAuto, mode, act) {
                save(c.copy(pumpAuto = it), if (it) "Pump shorts on" else "Pump shorts off")
            }
            Gap(6.dp)
            EnableRow("3 · Scale-in on pump shorts", "Half now, other half if it rises +${c.scaleAddPct.toInt()}%, one stop +${c.scaleStopPct.toInt()}% above the first entry",
                c.scaleIn, mode, act) { save(c.copy(scaleIn = it)) }
            Gap(6.dp)
            EnableRow("4 · Delisting / monitoring-tag short", "Short after a Binance announcement · SL +${c.annSlPct.toInt()}% · TP −${c.annTpPct.toInt()}% · ${c.annHoldDays} days",
                c.annAuto, mode, act) { save(c.copy(annAuto = it), if (it) "Announcement shorts on" else "Announcement shorts off") }
            Gap(6.dp)
            EnableRow("5 · BTC hedge bot", "Every short gets a BTC long of the same size, closed together (uses 2× margin)",
                c.btcHedge, mode, act) { save(c.copy(btcHedge = it), if (it) "BTC hedge on for new shorts" else "BTC hedge off for new shorts") }
            Gap(8.dp)
            val st = v?.state; val jr = v?.journal ?: emptyList()
            val pnl = jr.sumOf { it.pnl }
            val openPnl = st?.open?.values?.sumOf { p -> marks[p.symbol]?.let { (p.entry - it) * p.qty } ?: 0.0 } ?: 0.0
            val hedgePnl = st?.hedge()?.let { (q, e) -> marks["BTCUSDT"]?.let { q * (it - e) } } ?: 0.0
            Kpis(listOf(
                Triple(if (mode == "paper") "Paper equity" else "Mode", if (mode == "paper") st?.let { f2(it.paperEquity) } ?: "-" else mode.uppercase(), C.Txt),
                Triple("Open", "${st?.open?.size ?: 0}/${c.maxPositions}", C.Txt),
                Triple("Open P&L", sgn2(openPnl + hedgePnl), pnlColor(openPnl + hedgePnl)),
                Triple("Closed P&L", sgn2(pnl), pnlColor(pnl)),
            ))
            LiqWarnings(listOfNotNull(
                pk.aidesk.app.core.Liq.warn(c.slPct, c.leverage, "Listing short"),
                pk.aidesk.app.core.Liq.warn(if (c.scaleIn) c.scaleStopPct else c.pumpSlPct, c.leverage, "Pump short"),
                pk.aidesk.app.core.Liq.warn(c.annSlPct, c.leverage, "Announcement short")))
            err?.let { Gap(6.dp); T(it, 12.sp, C.Amber) }
            Gap(6.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(v?.let { "Updated ${hhmm(it.at)}" + if (loading) " · loading…" else "" } ?: "Loading Binance listings…", 11.sp, C.Dim, modifier = Modifier.weight(1f))
                T("Refresh", 12.sp, C.Blue, FontWeight.SemiBold, modifier = Modifier.clickable { vm.loadShorts(true) })
            }
            SectionTitle("Open shorts")
        }
        val open = v?.state?.open?.values?.toList() ?: emptyList()
        if (open.isEmpty()) item { T("No open shorts.", 13.sp, C.Muted) }
        for (p in open) item(key = "o" + p.symbol) {
            val mk = marks[p.symbol]
            val upnl = mk?.let { (p.entry - it) * p.qty }
            val hp = if (p.hedgeQty > 0) marks["BTCUSDT"]?.let { p.hedgeQty * (it - p.hedgeEntry) } else null
            Card(border = if (upnl == null) C.Border else pnlColor(upnl + (hp ?: 0.0)), onClick = { vm.showChart(p.symbol) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(p.symbol, 14.sp, C.Txt, FontWeight.Bold); HGap(8.dp); Pill("SHORT", C.Red, filled = true); Box(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        T(upnl?.let { (if (it >= 0) "Profit " else "Loss ") + "${sgn2(it)} USDT" } ?: "-", 15.sp, pnlColor(upnl), FontWeight.Bold)
                        upnl?.let { T("${sgn1(it / p.margin * 100)}% of ${f2(p.margin)} margin", 11.sp, pnlColor(it)) }
                    }
                }
                T("Entry ${px(p.entry)} · now ${px(mk)} · SL ${px(p.sl)} · TP ${px(p.tp)}", 11.sp, C.Muted, mono = true)
                Gap(2.dp)
                TpSlAmounts("SHORT", p.entry, p.qty, p.sl, p.tp)
                p.addPrice?.let { T("Scale-in: adds ${px(p.addQty)} more at ${px(it)}", 11.sp, C.Amber) }
                if (p.hedgeQty > 0) T("BTC hedge long ${px(p.hedgeQty)} @ ${px(p.hedgeEntry)} · " + (hp?.let { "${sgn2(it)} USDT" } ?: "-"), 11.sp, pnlColor(hp))
                T("Opened ${dayHhmm(p.opened)} · ${p.rule} · time exit ${dayHhmm(p.exitAt)}", 11.sp, C.Muted)
                var ask by remember { mutableStateOf(false) }
                var edit by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Box(Modifier.weight(1f))
                    T("Edit SL / TP", 13.sp, C.Blue, FontWeight.Bold, modifier = Modifier.clickable { edit = true })
                    HGap(14.dp)
                    T("Close now", 13.sp, C.Red, FontWeight.Bold, modifier = Modifier.clickable { ask = true })
                }
                if (edit) EditExitsDialog(vm, act, mode, p.symbol, "SHORT", p.entry, p.qty, p.sl, p.tp, mk) { edit = false }
                if (ask) AlertDialog(onDismissRequest = { ask = false },
                    confirmButton = { TextButton({ ask = false
                        if (mode == "live") act.confirmUser("Close LIVE short ${p.symbol}") { vm.shortClose(p.symbol) } else vm.shortClose(p.symbol)
                    }) { Text("Close at market", color = C.Red) } },
                    dismissButton = { TextButton({ ask = false }) { Text("Keep") } },
                    title = { Text("Close ${p.symbol} short?") }, text = { Text("Buys back the short at market, removes its SL/TP and closes its BTC hedge.") },
                    containerColor = C.Panel)
            }
            Gap(6.dp)
        }
        item { SectionTitle("New listings") }
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
        item { SectionTitle("Pumped coins") }
        val pumps = v?.pumps ?: emptyList()
        if (pumps.isEmpty()) item { T("-", 13.sp, C.Muted) }
        for (p in pumps) item(key = "p" + p.symbol) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                T(p.symbol, 12.sp, C.Txt, FontWeight.SemiBold, modifier = Modifier.weight(1.3f))
                T("${sgn1(p.ch24)}%", 12.sp, pnlColor(p.ch24), modifier = Modifier.weight(0.8f))
                T("1d ${sgn1(p.d1)}%", 11.sp, C.Muted, modifier = Modifier.weight(0.9f))
                T("3d ${sgn1(p.d3)}%", 11.sp, C.Muted, modifier = Modifier.weight(0.9f))
                val fv = p.fundingAvgPct ?: p.fundingPct
                T(String.format(Locale.US, "%+.3f%%", fv), 11.sp, if (fv < 0) C.Red else C.Muted, modifier = Modifier.weight(0.9f))
                if (p.status != "-") Pill(p.status, when (p.status) { "SIGNAL" -> C.Amber; "LOW FUNDING" -> C.Red; else -> C.Muted })
            }
            val isOpen = v?.state?.open?.containsKey(p.symbol) == true
            if (p.status != "-" && !isOpen) Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Box(Modifier.weight(1f)); ShortButton(vm, act, p.symbol, mode, "Short ${p.symbol.removeSuffix("USDT")}", small = true)
            }
        }
        item {
            T("SIGNAL = pumped +50% in a day or +100% in 3 days, listed 30+ days, and (filter on) the last 9 funding rates average at least " +
                "+${String.format(Locale.US, "%.2f", c.pumpMinFunding)}% so longs pay the short. LOW FUNDING = pumped but skipped.", 11.sp, C.Dim)
            SectionTitle("Binance announcements (last ones)")
        }
        val ann = v?.announcements ?: emptyList()
        if (ann.isEmpty()) item { T("No delisting / monitoring-tag announcement naming a futures coin right now.", 13.sp, C.Muted) }
        for (a in ann.take(15)) item(key = "a" + a.kind + a.symbol) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("${a.symbol} · ${a.kind}", 12.sp, C.Txt, FontWeight.SemiBold)
                    T("${dayHhmm(a.time)} · ${a.title}", 10.sp, C.Muted, maxLines = 2)
                }
                val fresh = System.currentTimeMillis() - a.time < 86_400_000L
                Pill(if (fresh) "≤24h" else "old", if (fresh) C.Amber else C.Muted)
            }
        }
        item { SectionTitle("Closed shorts and hedges") }
        val jr = v?.journal ?: emptyList()
        if (jr.isEmpty()) item { T("No closed trades yet.", 13.sp, C.Muted) }
        for ((i, t) in jr.take(150).withIndex()) item(key = "j$i") {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T(t.symbol + if (t.rule.isNotEmpty()) " · ${t.rule}" else "", 13.sp, C.Txt, FontWeight.SemiBold, maxLines = 1)
                    T("${dayHhmm(t.opened)} → ${dayHhmm(t.closed)} · ${t.reason}", 11.sp, C.Muted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    T((if (t.pnl >= 0) "Profit " else "Loss ") + "${sgn2(t.pnl)} USDT", 13.sp, pnlColor(t.pnl), FontWeight.Bold)
                    t.retPct?.let { T("${sgn1(it)}% margin", 11.sp, pnlColor(it)) }
                }
            }
        }
        item {
            SectionTitle("Size and risk (tap a number to type any value)")
            Stepper("Margin per short", "${pctTxt(c.marginPct)}% of equity", "Binance needs 5+ USDT per position (100+ USDT for a BTC hedge)",
                onMinus = { save(c.copy(marginPct = (c.marginPct - 1).coerceAtLeast(0.5))) },
                onPlus = { save(c.copy(marginPct = (c.marginPct + 1).coerceAtMost(100.0))) },
                onSet = { save(c.copy(marginPct = it.coerceIn(0.1, 100.0))) })
            Stepper("Leverage", "${c.leverage}x", "At 1x a +40% squeeze costs 40% of the margin; at 3x it liquidates",
                onMinus = { save(c.copy(leverage = (c.leverage - 1).coerceAtLeast(1))) },
                onPlus = { save(c.copy(leverage = (c.leverage + 1).coerceAtMost(125))) },
                onSet = { save(c.copy(leverage = it.toInt().coerceIn(1, 125))) })
            Stepper("Max open shorts", "${c.maxPositions}",
                onMinus = { save(c.copy(maxPositions = (c.maxPositions - 1).coerceAtLeast(1))) },
                onPlus = { save(c.copy(maxPositions = c.maxPositions + 1)) },
                onSet = { save(c.copy(maxPositions = it.toInt().coerceAtLeast(1))) })
            SectionTitle("Rule settings")
            Stepper("Listing: short on day", "${c.entryDay}", onMinus = { save(c.copy(entryDay = (c.entryDay - 1).coerceAtLeast(1))) },
                onPlus = { save(c.copy(entryDay = c.entryDay + 1)) }, onSet = { save(c.copy(entryDay = it.toInt().coerceAtLeast(1))) })
            Stepper("Listing: stop-loss", "+${pctTxt(c.slPct)}%", onMinus = { save(c.copy(slPct = (c.slPct - 5).coerceAtLeast(5.0))) },
                onPlus = { save(c.copy(slPct = c.slPct + 5)) }, onSet = { save(c.copy(slPct = it.coerceAtLeast(1.0))) })
            Stepper("Listing: take-profit", "−${pctTxt(c.tpPct)}%", onMinus = { save(c.copy(tpPct = (c.tpPct - 5).coerceAtLeast(5.0))) },
                onPlus = { save(c.copy(tpPct = (c.tpPct + 5).coerceAtMost(95.0))) }, onSet = { save(c.copy(tpPct = it.coerceIn(1.0, 99.0))) })
            Stepper("Listing: hold days", "${c.holdDays}", onMinus = { save(c.copy(holdDays = (c.holdDays - 1).coerceAtLeast(1))) },
                onPlus = { save(c.copy(holdDays = c.holdDays + 1)) }, onSet = { save(c.copy(holdDays = it.toInt().coerceAtLeast(1))) })
            Stepper("Pump: min avg funding", if (c.fundingFilterOn) "+${String.format(Locale.US, "%.2f", c.pumpMinFunding)}%" else "off",
                "Backtest: +0.03% kept the pump short positive in 2026. Type -1 to switch the filter off.",
                onMinus = { save(c.copy(pumpMinFunding = Math.round((c.pumpMinFunding - 0.01) * 100) / 100.0)) },
                onPlus = { save(c.copy(pumpMinFunding = Math.round((c.pumpMinFunding + 0.01) * 100) / 100.0)) },
                onSet = { save(c.copy(pumpMinFunding = it)) })
            Stepper("Pump: stop-loss", "+${pctTxt(c.pumpSlPct)}%", onMinus = { save(c.copy(pumpSlPct = (c.pumpSlPct - 5).coerceAtLeast(5.0))) },
                onPlus = { save(c.copy(pumpSlPct = c.pumpSlPct + 5)) }, onSet = { save(c.copy(pumpSlPct = it.coerceAtLeast(1.0))) })
            Stepper("Pump: take-profit", "−${pctTxt(c.pumpTpPct)}%", onMinus = { save(c.copy(pumpTpPct = (c.pumpTpPct - 5).coerceAtLeast(5.0))) },
                onPlus = { save(c.copy(pumpTpPct = (c.pumpTpPct + 5).coerceAtMost(95.0))) }, onSet = { save(c.copy(pumpTpPct = it.coerceIn(1.0, 99.0))) })
            Stepper("Scale-in: add 2nd half at", "+${pctTxt(c.scaleAddPct)}%", onMinus = { save(c.copy(scaleAddPct = (c.scaleAddPct - 5).coerceAtLeast(5.0))) },
                onPlus = { save(c.copy(scaleAddPct = c.scaleAddPct + 5)) }, onSet = { save(c.copy(scaleAddPct = it.coerceAtLeast(1.0))) })
            Stepper("Scale-in: stop for both halves", "+${pctTxt(c.scaleStopPct)}%", onMinus = { save(c.copy(scaleStopPct = (c.scaleStopPct - 5).coerceAtLeast(10.0))) },
                onPlus = { save(c.copy(scaleStopPct = c.scaleStopPct + 5)) }, onSet = { save(c.copy(scaleStopPct = it.coerceAtLeast(2.0))) })
            Stepper("Announcement: stop-loss", "+${pctTxt(c.annSlPct)}%", onMinus = { save(c.copy(annSlPct = (c.annSlPct - 5).coerceAtLeast(5.0))) },
                onPlus = { save(c.copy(annSlPct = c.annSlPct + 5)) }, onSet = { save(c.copy(annSlPct = it.coerceAtLeast(1.0))) })
            Stepper("Announcement: take-profit", "−${pctTxt(c.annTpPct)}%", onMinus = { save(c.copy(annTpPct = (c.annTpPct - 5).coerceAtLeast(5.0))) },
                onPlus = { save(c.copy(annTpPct = (c.annTpPct + 5).coerceAtMost(95.0))) }, onSet = { save(c.copy(annTpPct = it.coerceIn(1.0, 99.0))) })
            Stepper("Announcement: hold days", "${c.annHoldDays}", onMinus = { save(c.copy(annHoldDays = (c.annHoldDays - 1).coerceAtLeast(1))) },
                onPlus = { save(c.copy(annHoldDays = c.annHoldDays + 1)) }, onSet = { save(c.copy(annHoldDays = it.toInt().coerceAtLeast(1))) })
            val eq = if (mode == "paper") v?.state?.paperEquity ?: c.paperStart else null
            val need = kotlin.math.ceil(5.0 / (c.marginPct / 100 * c.leverage)).toInt()
            Gap(6.dp)
            T("Each short = ${pctTxt(c.marginPct)}% of equity × ${c.leverage}x (half of it first when scale-in is on). You need at least $need USDT equity" +
                (eq?.let { " (paper: ${f2(it)})" } ?: "") + "; the BTC hedge needs about 100+ USDT per short on Binance.", 11.sp, C.Muted)
            SectionTitle("Backtest (2021 – Nov 2026, after fees and funding)")
            T("Listing short day 3 / SL 50 / TP 40 / 21 d: +4.0% per trade in 2021-25 and in 2026. Pump short with funding ≥ +0.03%: +4.5% (2021-25), +3.2% (2026) but −7.3% in 2024. " +
                "Scale-in, BTC hedge and announcement shorts were positive before 2026 but NEGATIVE in 2026 (−1.0%, −0.4%, −2.8% per trade). " +
                "10% per short with max 3 open: about +26% a year with a worst drop of −41%. More open positions or more leverage raise both profit and drawdown. Past results are not a promise.",
                12.sp, C.Muted)
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
            LiqWarnings(listOfNotNull(pk.aidesk.app.core.Liq.warn(c.stopPct, c.maxLev, "Copy emergency stop (at max leverage)")))
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
            onPlus = { save(c.copy(sizeMult = ((c.sizeMult * 10 + 1) / 10).coerceAtMost(100.0))) })
        Stepper("Max size per copy", "${c.maxNotional.toInt()} USDT", "Position value cap",
            onMinus = { save(c.copy(maxNotional = (c.maxNotional - 5).coerceAtLeast(6.0))) },
            onPlus = { save(c.copy(maxNotional = (c.maxNotional + 5))) })
        Stepper("Max leverage", "${c.maxLev}x", onMinus = { save(c.copy(maxLev = (c.maxLev - 1).coerceAtLeast(1))) },
            onPlus = { save(c.copy(maxLev = (c.maxLev + 1).coerceAtMost(125))) })
        Stepper("Max open copies", "${c.maxPositions}", "Up to 25 at a time; total exposure cap below still applies",
            onMinus = { save(c.copy(maxPositions = (c.maxPositions - 1).coerceAtLeast(1))) },
            onPlus = { save(c.copy(maxPositions = (c.maxPositions + 1).coerceAtMost(500))) })
        Stepper("Max total exposure", String.format(Locale.US, "%.1f× equity", c.maxExposureX),
            onMinus = { save(c.copy(maxExposureX = (c.maxExposureX - 0.5).coerceAtLeast(0.5))) },
            onPlus = { save(c.copy(maxExposureX = (c.maxExposureX + 0.5).coerceAtMost(500.0))) })
        Stepper("Emergency stop", "${c.stopPct.toInt()}% move", "Placed on Binance in live, so it works offline", C.Red,
            onMinus = { save(c.copy(stopPct = (c.stopPct - 5).coerceAtLeast(5.0))) },
            onPlus = { save(c.copy(stopPct = (c.stopPct + 5).coerceAtMost(100.0))) })
        Stepper("Daily loss limit", "${c.dailyLossPct.toInt()}%", "No new copies for the rest of the UTC day", C.Red,
            onMinus = { save(c.copy(dailyLossPct = (c.dailyLossPct - 1).coerceAtLeast(2.0))) },
            onPlus = { save(c.copy(dailyLossPct = (c.dailyLossPct + 1).coerceAtMost(100.0))) })
        Stepper("Check every", "${c.pollSec} s", "Faster = smaller delay, more battery",
            onMinus = { save(c.copy(pollSec = (c.pollSec - 5).coerceAtLeast(5))) },
            onPlus = { save(c.copy(pollSec = (c.pollSec + 5).coerceAtMost(60))) })
        Gap(6.dp)
        T("Size = leader's trade × (your money ÷ their money) × multiplier, raised to Binance's ~5 USDT minimum when needed. " +
            "Copying needs the app running in the background: set Battery to Unrestricted in Settings.", 11.sp, C.Muted)
        Gap(24.dp)
    }
}
