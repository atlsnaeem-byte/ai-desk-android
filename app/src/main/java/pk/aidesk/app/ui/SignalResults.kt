package pk.aidesk.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pk.aidesk.app.core.SigRec

/** Every signal the scanner produced, followed to TP / SL / time exit, taken or not. */
@Composable
fun SignalResults(vm: AppVM) {
    val all by vm.sigLog.collectAsState()
    val cfg by vm.config.collectAsState()
    var strength by remember { mutableStateOf("strong") }   // strong | weak | all
    var taken by remember { mutableStateOf("all") }         // all | taken | skipped
    var ask by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadSigLog() }

    val shown = all.filter {
        (strength == "all" || (strength == "strong") == it.strong) &&
            (taken == "all" || (taken == "taken") == it.taken)
    }
    val done = shown.filter { it.resolved }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((k, l) in listOf("strong" to "Strong", "weak" to "Weak", "all" to "All"))
                    Pill(l, if (strength == k) C.Blue else C.Muted, filled = strength == k) { strength = k }
            }
            Gap(6.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((k, l) in listOf("all" to "Taken or not", "taken" to "Taken", "skipped" to "Not taken"))
                    Pill(l, if (taken == k) C.Amber else C.Muted, filled = taken == k) { taken = k }
            }
            Gap(10.dp)
            Stats(done)
            Gap(8.dp)
            // taken vs not taken side by side (same strength filter)
            val base = all.filter { (strength == "all" || (strength == "strong") == it.strong) && it.resolved }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((label, rows) in listOf("Taken" to base.filter { it.taken }, "Not taken" to base.filter { !it.taken })) {
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(C.Panel).padding(8.dp)) {
                        T(label, 11.sp, C.Muted)
                        if (rows.isEmpty()) T("-", 14.sp, C.Txt, FontWeight.Bold)
                        else {
                            val avg = rows.mapNotNull { it.r }.average()
                            T("${rows.size} · ${rows.count { it.win } * 100 / rows.size}% win", 13.sp, C.Txt, FontWeight.Bold)
                            T("avg ${sgn2(avg)}R · total ${sgn2(rows.sumOf { it.r ?: 0.0 })}R", 11.sp, if (avg >= 0) C.Green else C.Red)
                        }
                    }
                }
            }
            Gap(6.dp)
            // by token
            if (done.size >= 5) {
                T("By token (resolved, current filter)", 11.sp, C.Muted)
                done.groupBy { it.symbol }.entries.sortedByDescending { e -> e.value.sumOf { it.r ?: 0.0 } }.forEach { (s, rows) ->
                    val tot = rows.sumOf { it.r ?: 0.0 }
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        T(s, 11.sp, C.Txt, modifier = Modifier.weight(1f))
                        T("${rows.size}", 11.sp, C.Muted, modifier = Modifier.weight(0.4f))
                        T("${rows.count { it.win } * 100 / rows.size}%", 11.sp, C.Muted, modifier = Modifier.weight(0.5f))
                        T("${sgn2(tot)}R", 11.sp, if (tot >= 0) C.Green else C.Red, FontWeight.SemiBold)
                    }
                }
                Gap(6.dp)
            }
            T("R = result in units of the stop distance, after fees + slippage (TP ≈ +2R, SL ≈ −1R, or the move after ${cfg.horizonBars} bars). " +
                "Each signal is counted once, from the bar it first appeared, using the same TP/SL/time rules as a paper trade. Recording started with this app version.",
                10.sp, C.Dim)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                T("${shown.size} signals · ${shown.count { !it.resolved && it.outcome == null }} still running", 11.sp, C.Muted, modifier = Modifier.weight(1f))
                T("Clear", 11.sp, C.Red, modifier = Modifier.clickable { ask = true })
            }
        }
        items(shown.take(400), key = { it.id }) { r -> SigRow(r); Gap(4.dp) }
        item { Gap(20.dp) }
    }
    if (ask) AlertDialog(onDismissRequest = { ask = false },
        confirmButton = { TextButton({ vm.clearSigLog(); ask = false }) { Text("Clear", color = C.Red) } },
        dismissButton = { TextButton({ ask = false }) { Text("Cancel") } },
        title = { Text("Clear signal results?") }, text = { Text("Deletes the whole signal history on this phone.") }, containerColor = C.Panel)
}

@Composable
private fun Stats(done: List<SigRec>) {
    val rs = done.mapNotNull { it.r }
    val avg = if (rs.isEmpty()) null else rs.average()
    val tot = rs.sum()
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((k, v, c) in listOf(
            Triple("Resolved", "${done.size}", C.Txt),
            Triple("Win rate", if (done.isEmpty()) "-" else "${done.count { it.win } * 100 / done.size}%", C.Txt),
            Triple("Avg R", avg?.let { sgn2(it) } ?: "-", if ((avg ?: 0.0) >= 0) C.Green else C.Red),
            Triple("Total R", sgn2(tot), if (tot >= 0) C.Green else C.Red),
        )) {
            Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(C.Panel).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                T(k, 11.sp, C.Muted); T(v, 15.sp, c, FontWeight.Bold)
            }
        }
    }
    if (done.isNotEmpty()) {
        val tp = done.count { it.outcome == "TP" }; val sl = done.count { it.outcome == "SL" }; val tm = done.count { it.outcome == "TIME" }
        T("TP $tp · SL $sl · time exit $tm", 11.sp, C.Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun SigRow(r: SigRec) {
    val outCol: Color = when {
        r.outcome == null -> C.Blue
        r.outcome == "EXPIRED" -> C.Dim
        r.win -> C.Green
        else -> C.Red
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(r.symbol, 13.sp, C.Txt, FontWeight.Bold); HGap(6.dp)
                    T(r.side, 11.sp, C.side(r.side), FontWeight.Bold); HGap(6.dp)
                    T(if (r.strong) "strong" else "weak", 11.sp, if (r.strong) C.Amber else C.Dim); HGap(6.dp)
                    if (r.taken) Pill("TAKEN", C.Green) else T("not taken", 10.sp, C.Dim)
                }
                T("${dayHhmm(r.since)} · win ${pct0(r.prob)} · EV ${sgn2(r.evR)}R · ${px(r.entry)}", 11.sp, C.Muted)
            }
            Column(horizontalAlignment = Alignment.End) {
                T(when (r.outcome) { null -> "running"; "EXPIRED" -> "n/a"; else -> r.outcome!! }, 11.sp, outCol, FontWeight.Bold)
                r.r?.let { T("${sgn2(it)}R", 14.sp, outCol, FontWeight.Bold) }
                if (r.outcome == null) T("${r.bars} bars", 10.sp, C.Dim)
            }
        }
    }
}
