package pk.aidesk.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pk.aidesk.app.MainActivity
import java.util.Locale
import kotlin.math.abs

private const val FEE = 0.0005

/** Net P&L in USDT if the position closes at [px] (after about 0.05% fee on entry and on exit). */
fun exitPnl(side: String, entry: Double, qty: Double, px: Double): Double =
    qty * (px - entry) * (if (side == "LONG") 1 else -1) - FEE * qty * (entry + px)

/** "At TP +x USDT  ·  At SL −y USDT" line for an open position. */
@Composable
fun TpSlAmounts(side: String, entry: Double, qty: Double, sl: Double?, tp: Double?) {
    if (sl == null && tp == null) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        sl?.let { val v = exitPnl(side, entry, qty, it)
            T("At SL ${px(it)}: ${sgn2(v)} USDT", 12.sp, if (v >= 0) C.Green else C.Red, FontWeight.Bold) }
        tp?.let { val v = exitPnl(side, entry, qty, it)
            T("At TP ${px(it)}: ${sgn2(v)} USDT", 12.sp, if (v >= 0) C.Green else C.Red, FontWeight.Bold) }
    }
}

private fun parse(s: String) = s.trim().replace(",", ".").toDoubleOrNull()?.takeIf { it > 0 }

/** Dialog to set a new stop-loss and take-profit for an open position (any bot). */
@Composable
fun EditExitsDialog(vm: AppVM, act: MainActivity, mode: String, symbol: String, side: String, entry: Double, qty: Double,
                    sl: Double?, tp: Double?, mark: Double?, onDismiss: () -> Unit) {
    val dr = if (side == "LONG") 1 else -1
    var slTxt by remember(symbol) { mutableStateOf(sl?.let { px(it).replace(",", "") } ?: "") }
    var tpTxt by remember(symbol) { mutableStateOf(tp?.let { px(it).replace(",", "") } ?: "") }
    val slV = parse(slTxt); val tpV = parse(tpTxt)
    fun pctOf(v: Double) = String.format(Locale.US, "%+.1f%%", (v / entry - 1) * 100)
    val err = when {
        slV == null || tpV == null -> "Type both prices."
        mark != null && (mark - slV) * dr <= 0 -> "Stop-loss must be ${if (dr == 1) "below" else "above"} the price now (${px(mark)})."
        mark != null && (tpV - mark) * dr <= 0 -> "Take-profit must be ${if (dr == 1) "above" else "below"} the price now (${px(mark)})."
        else -> null
    }
    AlertDialog(onDismissRequest = onDismiss,
        confirmButton = { TextButton({
            if (err != null || slV == null || tpV == null) return@TextButton
            val go = { vm.editExits(symbol, slV, tpV) { onDismiss() } }
            if (mode == "live") act.confirmUser("Change LIVE SL / TP of $symbol") { go() } else go()
        }, enabled = err == null) { Text("Save", color = if (err == null) C.Blue else C.Dim) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
        title = { Text("Edit SL / TP · $symbol $side") },
        text = {
            Column {
                T("Entry ${px(entry)} · now ${px(mark)} · qty ${px(qty)}", 12.sp, C.Muted, mono = true)
                Gap(8.dp)
                OutlinedTextField(slTxt, { slTxt = it }, label = { Text("Stop-loss price") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                slV?.let { val v = exitPnl(side, entry, qty, it)
                    T("${pctOf(it)} from entry · ${sgn2(v)} USDT if hit", 12.sp, if (v >= 0) C.Green else C.Red, FontWeight.SemiBold) }
                Gap(6.dp)
                OutlinedTextField(tpTxt, { tpTxt = it }, label = { Text("Take-profit price") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                tpV?.let { val v = exitPnl(side, entry, qty, it)
                    T("${pctOf(it)} from entry · ${sgn2(v)} USDT if hit", 12.sp, if (v >= 0) C.Green else C.Red, FontWeight.SemiBold) }
                Gap(8.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // quick presets: stop to break-even (just past fees), stop/target as % of entry
                    if (mark != null && (mark - entry) * dr > 0)
                        Pill("SL to break-even", C.Amber) { slTxt = px(entry * (1 + dr * 2 * FEE)).replace(",", "") }
                    Pill("SL ${if (dr == 1) "−" else "+"}10%", C.Red) { slTxt = px(entry * (1 - dr * 0.10)).replace(",", "") }
                    Pill("TP ${if (dr == 1) "+" else "−"}20%", C.Green) { tpTxt = px(entry * (1 + dr * 0.20)).replace(",", "") }
                }
                err?.let { Gap(6.dp); T(it, 12.sp, C.Amber) }
                Gap(4.dp)
                T(if (mode == "paper") "Paper: the bot uses the new prices from now on."
                    else "The SL / TP orders on Binance are cancelled and placed again at the new prices.", 11.sp, C.Dim)
                if (sl != null && slV != null && abs(slV - sl) > 0 && (slV - sl) * dr < 0)
                    T("Note: this stop is further away than before, so a loss would be bigger.", 11.sp, C.Amber)
            }
        },
        containerColor = C.Panel)
}
