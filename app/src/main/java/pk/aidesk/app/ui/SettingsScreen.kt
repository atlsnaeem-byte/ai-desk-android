package pk.aidesk.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import pk.aidesk.app.MainActivity
import pk.aidesk.app.TopBar
import pk.aidesk.app.core.Config
import pk.aidesk.app.service.TraderService
import java.util.Locale

// ------------------------------------------------------------------ rows
@Composable
internal fun RowBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 1.dp).background(C.Panel).padding(horizontal = 12.dp, vertical = 10.dp)) { content() }
}

@Composable
internal fun SwitchRow(label: String, sub: String? = null, on: Boolean, onChange: (Boolean) -> Unit) = RowBox {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { T(label, 14.sp); sub?.let { T(it, 11.sp, C.Muted) } }
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.Green, checkedThumbColor = C.Txt))
    }
}

@Composable
internal fun LinkRow(label: String, value: String, color: Color = C.Txt, onClick: () -> Unit) = RowBox {
    Row(Modifier.fillMaxWidth().clickable { onClick() }, verticalAlignment = Alignment.CenterVertically) {
        T(label, 14.sp, modifier = Modifier.weight(1f)); T("$value  >", 13.sp, color, FontWeight.SemiBold)
    }
}

/** − value + row. With [onSet], tapping the value lets you type any number (validated by the caller). */
@Composable
internal fun Stepper(label: String, value: String, sub: String? = null, color: Color = C.Txt, onMinus: () -> Unit, onPlus: () -> Unit,
                     onSet: ((Double) -> Unit)? = null) = RowBox {
    var edit by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { T(label, 14.sp); sub?.let { T(it, 11.sp, C.Muted) } }
        Pill("  -  ", C.Muted, onClick = onMinus); HGap(10.dp)
        T(value, 14.sp, color, FontWeight.Bold, modifier = if (onSet != null) Modifier.clickable { edit = true } else Modifier); HGap(10.dp)
        Pill("  +  ", C.Muted, onClick = onPlus)
    }
    if (edit && onSet != null) {
        var txt by remember { mutableStateOf(value.filter { it.isDigit() || it == '.' }) }
        AlertDialog(onDismissRequest = { edit = false }, containerColor = C.Panel,
            title = { Text(label) },
            text = { OutlinedTextField(txt, { txt = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true, colors = fieldColors(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)) },
            confirmButton = { TextButton({ txt.toDoubleOrNull()?.let { onSet(it) }; edit = false }, enabled = txt.toDoubleOrNull() != null) { Text("Set") } },
            dismissButton = { TextButton({ edit = false }) { Text("Cancel") } })
    }
}

@Composable
internal fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, text: (Float) -> String,
                      color: Color = C.Amber, sub: String? = null, onDone: (Float) -> Unit) = RowBox {
    var v by remember(value) { mutableFloatStateOf(value) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(label, 14.sp, modifier = Modifier.weight(1f)); T(text(v), 14.sp, color, FontWeight.Bold)
        }
        Slider(v, { v = it }, valueRange = range, steps = steps, onValueChangeFinished = { onDone(v) },
            colors = SliderDefaults.colors(thumbColor = C.Txt, activeTrackColor = color, inactiveTrackColor = C.Panel2))
        sub?.let { T(it, 11.sp, C.Muted) }
    }
}

// ------------------------------------------------------------------ settings
@Composable
fun SettingsScreen(vm: AppVM, act: MainActivity) {
    val cfg by vm.config.collectAsState()
    val running by TraderService.running.collectAsState()
    var liveAsk by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val meta = vm.engine.model.meta
    val oos = meta.optJSONObject("oos_metrics")

    fun save(c: Config) = vm.saveConfig(c)

    Column(Modifier.fillMaxSize()) {
        TopBar(vm, "Settings")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            SectionTitle("Mode")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (m in listOf("paper", "testnet", "live")) {
                    val on = cfg.mode == m
                    Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(8.dp)).background(if (on) C.mode(m) else C.Panel)
                        .clickable {
                            if (m == cfg.mode) return@clickable
                            if (m == "live") liveAsk = true
                            else save(cfg.copy(mode = m, auto = false)).also { vm.toast.value = "Mode: ${m.uppercase()}. Auto is off; turn it on when ready." }
                        }, contentAlignment = Alignment.Center) {
                        T(m.replaceFirstChar { it.uppercase() }, 14.sp, if (on) C.Bg else C.Muted, FontWeight.Bold)
                    }
                }
            }
            T("Changing mode turns auto off. Open trades in the old mode keep their TP/SL on Binance.", 11.sp, C.Muted,
                modifier = Modifier.padding(top = 4.dp))

            SectionTitle("Auto-trading")
            SwitchRow("Auto-trade", if (cfg.auto) "The app opens trades by itself (${cfg.mode.uppercase()})" else "Off: alerts only, you tap Take", cfg.auto) { on ->
                if (on && cfg.mode == "live") act.confirmUser("Start LIVE auto-trading") { save(vm.config.value.copy(auto = true)) }
                else save(cfg.copy(auto = on))
            }

            SectionTitle("Alerts")
            SliderRow("Sound only when win chance >=", cfg.soundMinProb.toFloat(), 50f..80f, 29, { "${it.toInt()}%" }, C.Green,
                "Below this, notifications are silent.") { save(vm.config.value.copy(soundMinProb = it.toInt().toDouble())) }
            val qOn = cfg.quietStart >= 0
            SwitchRow("Quiet hours", if (qOn) "No sound ${"%02d".format(cfg.quietStart)}:00 - ${"%02d".format(cfg.quietEnd)}:00 PKT" else "Off", qOn) {
                save(cfg.copy(quietStart = if (it) 1 else -1, quietEnd = if (it) 8 else -1))
            }
            if (qOn) {
                Stepper("Quiet from", "%02d:00".format(cfg.quietStart), onMinus = { save(cfg.copy(quietStart = (cfg.quietStart + 23) % 24)) },
                    onPlus = { save(cfg.copy(quietStart = (cfg.quietStart + 1) % 24)) })
                Stepper("Quiet until", "%02d:00".format(cfg.quietEnd), onMinus = { save(cfg.copy(quietEnd = (cfg.quietEnd + 23) % 24)) },
                    onPlus = { save(cfg.copy(quietEnd = (cfg.quietEnd + 1) % 24)) })
            }

            SectionTitle("Trading")
            SliderRow("Min EV (signal strength)", cfg.minEvR.toFloat(), 0f..1f, 19, { String.format(Locale.US, "%+.2fR", it) }, C.Blue,
                "A signal is STRONG (and auto-traded) only when its expected value is at least this. " +
                    "Default +0.15R (backtested). Lower = more trades with a thinner edge; higher = fewer, stronger trades.") {
                save(vm.config.value.copy(minEvR = Math.round(it * 20) / 20.0))
            }
            Stepper("Risk per trade", "${pctTxt(cfg.riskPct)}%", "Share of equity lost if the stop-loss is hit. Tap the number to type any value." +
                if (cfg.riskPct > cfg.dailyLossPct) " Above your daily loss limit: one loss pauses auto for the day." else "", C.Amber,
                onMinus = { save(cfg.copy(riskPct = (cfg.riskPct - 1).coerceAtLeast(0.1))) },
                onPlus = { save(cfg.copy(riskPct = (cfg.riskPct + 1).coerceAtMost(100.0))) },
                onSet = { save(vm.config.value.copy(riskPct = it.coerceIn(0.1, 100.0))) })
            Stepper("Leverage", "${cfg.leverage}x", "Applied on Binance at the next scan (Binance max is 125x, less on small coins)",
                onMinus = { save(cfg.copy(leverage = (cfg.leverage - 1).coerceAtLeast(1))) },
                onPlus = { save(cfg.copy(leverage = (cfg.leverage + 1).coerceAtMost(125))) },
                onSet = { save(vm.config.value.copy(leverage = it.toInt().coerceIn(1, 125))) })
            RowBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("Margin type", 14.sp, modifier = Modifier.weight(1f))
                    for (t in listOf("ISOLATED", "CROSSED")) {
                        Pill(t.lowercase(), if (cfg.marginType == t) C.Blue else C.Muted, filled = cfg.marginType == t) { save(cfg.copy(marginType = t)) }
                        HGap(6.dp)
                    }
                }
            }
            Stepper("Max open positions", "${cfg.maxOpen}", onMinus = { save(cfg.copy(maxOpen = (cfg.maxOpen - 1).coerceAtLeast(1))) },
                onPlus = { save(cfg.copy(maxOpen = cfg.maxOpen + 1)) }, onSet = { save(vm.config.value.copy(maxOpen = it.toInt().coerceAtLeast(1))) })
            Stepper("Daily loss limit", "-${cfg.dailyLossPct}%", "Pauses auto entries until midnight PKT", C.Red,
                onMinus = { save(cfg.copy(dailyLossPct = (cfg.dailyLossPct - 1).coerceAtLeast(1.0))) },
                onPlus = { save(cfg.copy(dailyLossPct = (cfg.dailyLossPct + 1).coerceAtMost(100.0))) },
                onSet = { save(vm.config.value.copy(dailyLossPct = it.coerceIn(0.5, 100.0))) })
            Stepper("Daily profit target", "+${cfg.dailyProfitPct}%", "Pauses auto entries when reached", C.Green,
                onMinus = { save(cfg.copy(dailyProfitPct = (cfg.dailyProfitPct - 1).coerceAtLeast(1.0))) },
                onPlus = { save(cfg.copy(dailyProfitPct = cfg.dailyProfitPct + 1)) },
                onSet = { save(vm.config.value.copy(dailyProfitPct = it.coerceAtLeast(0.5))) })

            SectionTitle("Binance")
            LinkRow("API key", vm.engine.store.keyHint("live")?.let { "live $it" } ?: "not set", if (vm.engine.store.keyHint("live") != null) C.Green else C.Amber) { vm.page.value = "apikey" }
            LinkRow("Switch account to One-way mode", "") { vm.oneWay() }

            SectionTitle("System")
            LinkRow("Tokens", "${cfg.symbols.size} of ${Config.DEFAULT_SYMBOLS.size}") { vm.page.value = "symbols" }
            RowBox {
                Column {
                    T("Model", 14.sp)
                    T("Trained ${meta.optString("trained_at").take(10)}  -  AUC ${String.format(Locale.US, "%.2f", oos?.optDouble("auc_long") ?: 0.0)}" +
                        "  -  backtest ${String.format(Locale.US, "%+.1f", oos?.optDouble("return_pct") ?: 0.0)}% / max DD ${String.format(Locale.US, "%.1f", oos?.optDouble("max_dd_pct") ?: 0.0)}%",
                        11.sp, C.Muted)
                    T("The edge is small. Results are not guaranteed.", 11.sp, C.Muted)
                }
            }
            LinkRow("Activity log", "") { vm.loadLogs(); vm.page.value = "logs" }
            LinkRow("Scanner service", if (running) "running" else "stopped", if (running) C.Green else C.Red) {
                if (running) TraderService.stop(ctx) else TraderService.start(ctx)
            }
            LinkRow("Battery: unrestricted", if (batteryOk(ctx)) "OK" else "fix", if (batteryOk(ctx)) C.Green else C.Amber) { openBattery(ctx) }
            LinkRow("Exact alarms", if (alarmsOk(ctx)) "OK" else "fix", if (alarmsOk(ctx)) C.Green else C.Amber) { openAlarms(ctx) }
            Gap(24.dp)
        }
    }

    if (liveAsk) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { liveAsk = false }, containerColor = C.Panel,
            title = { Text("Switch to LIVE (real money)?") },
            text = {
                Column {
                    Text("Trades will use your real Binance balance. Type LIVE to confirm.")
                    Gap(8.dp)
                    OutlinedTextField(typed, { typed = it }, singleLine = true, colors = fieldColors())
                }
            },
            confirmButton = {
                TextButton({
                    if (typed.trim().uppercase() == "LIVE") act.confirmUser("Switch to LIVE trading") {
                        save(vm.config.value.copy(mode = "live", auto = false))
                        vm.toast.value = "LIVE mode. Turn on Auto-trade when you are ready."
                        liveAsk = false
                    }
                }, enabled = typed.trim().uppercase() == "LIVE") { Text("Switch to LIVE", color = C.Red) }
            },
            dismissButton = { TextButton({ liveAsk = false }) { Text("Cancel") } })
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(focusedTextColor = C.Txt, unfocusedTextColor = C.Txt,
    focusedBorderColor = C.Blue, unfocusedBorderColor = C.Border, cursorColor = C.Txt)

fun batteryOk(ctx: Context) = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
fun alarmsOk(ctx: Context) = Build.VERSION.SDK_INT < 31 || (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
fun notifOk(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

@SuppressLint("BatteryLife")
fun openBattery(ctx: Context) {
    runCatching {
        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
    }.onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
}

fun openAlarms(ctx: Context) {
    if (Build.VERSION.SDK_INT >= 31) runCatching {
        ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
    }
}

// ------------------------------------------------------------------ sub pages
@Composable
fun ApiKeyPage(vm: AppVM) {
    var mode by remember { mutableStateOf("live") }
    var key by remember(mode) { mutableStateOf("") }
    var secret by remember(mode) { mutableStateOf("") }
    val busy by vm.busy.collectAsState()
    val toast by vm.toast.collectAsState()
    var result by remember { mutableStateOf<String?>(null) }
    if (toast != null && !busy) result = toast
    val hint = remember(mode, busy) { vm.engine.store.keyHint(mode) }
    Column(Modifier.fillMaxSize()) {
        TopBar(vm, "API key") { vm.page.value = null }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("Live", if (mode == "live") C.Red else C.Muted, filled = mode == "live") { mode = "live"; result = null }
                Pill("Testnet", if (mode == "testnet") C.Amber else C.Muted, filled = mode == "testnet") { mode = "testnet"; result = null }
            }
            Gap(10.dp)
            Card {
                KV("Saved key", hint ?: "none", if (hint != null) C.Green else C.Amber)
                T("Stored encrypted with the Android Keystore. The secret is never shown again.", 11.sp, C.Muted)
            }
            Gap(10.dp)
            OutlinedTextField(key, { key = it }, label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
            Gap(6.dp)
            OutlinedTextField(secret, { secret = it }, label = { Text("Secret key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), colors = fieldColors())
            Gap(10.dp)
            Btn(if (busy) "Checking..." else "Save and test", C.Green, enabled = !busy && key.isNotBlank() && secret.isNotBlank()) {
                result = null; vm.toast.value = null
                vm.saveKeys(mode, key, secret); key = ""; secret = ""
            }
            Gap(8.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Test saved key", C.Blue, Modifier.weight(1f), enabled = hint != null && !busy, outline = true) { result = null; vm.toast.value = null; vm.testKeys(mode) }
                Btn("Remove", C.Red, Modifier.weight(1f), enabled = hint != null && !busy, outline = true) {
                    vm.engine.store.clearKeys(mode); result = "Key removed"; vm.toast.value = null
                }
            }
            result?.let { Gap(10.dp); Card { T(it, 13.sp, if (it.contains("fail", true) || it.contains("WARNING")) C.Amber else C.Txt) } }
            Gap(12.dp)
            T("Binance API Management:\n- Enable Futures. Keep Withdrawals OFF.\n- If the key is IP-restricted, add the IP this phone uses (office Wi-Fi, home Wi-Fi, mobile data all differ), otherwise Binance rejects it.\n- Account must be in One-way position mode.",
                12.sp, C.Muted)
            Gap(24.dp)
        }
    }
}

@Composable
fun LogsPage(vm: AppVM) {
    val logs by vm.logs.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar(vm, "Activity log") { vm.page.value = null }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            if (logs.isEmpty()) T("Nothing yet.", 13.sp, C.Muted)
            logs.forEach { l ->
                T("${dayHhmm(l.t)}  ${l.msg}", 11.sp, when (l.level) { "ERROR" -> C.Red; "WARN" -> C.Amber; else -> C.Muted }, mono = true,
                    modifier = Modifier.padding(vertical = 2.dp))
            }
            Gap(24.dp)
        }
    }
}

@Composable
fun SymbolsPage(vm: AppVM) {
    val cfg by vm.config.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar(vm, "Tokens") { vm.page.value = null }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            T("The model was trained on these 10 tokens.", 12.sp, C.Muted)
            Gap(6.dp)
            for (s in Config.DEFAULT_SYMBOLS) {
                SwitchRow(s, null, s in cfg.symbols) { on ->
                    val next = if (on) Config.DEFAULT_SYMBOLS.filter { it in cfg.symbols || it == s } else cfg.symbols - s
                    if (next.isNotEmpty()) vm.saveConfig(cfg.copy(symbols = next))
                }
            }
            Gap(24.dp)
        }
    }
}

// ------------------------------------------------------------------ first run
@Composable
fun SetupScreen(vm: AppVM, act: MainActivity) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val t = tick // re-read state after returning from system screens
    val steps = listOf(
        Triple("Notifications allowed", "needed for alerts and trade reports", notifOk(ctx)),
        Triple("Battery: unrestricted", "so Android does not stop the scanner", batteryOk(ctx)),
        Triple("Exact alarms", "wake at each 15-minute close", alarmsOk(ctx)),
    )
    var keyPage by remember { mutableStateOf(false) }
    if (keyPage) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { ApiKeyPage(vm) }
            Btn("Done", C.Blue, Modifier.padding(12.dp)) { keyPage = false; tick++ }
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        T("Set up AI Desk", 22.sp, C.Txt, FontWeight.Bold)
        T("${steps.count { it.third }} of ${steps.size} done" + if (t < 0) "" else "", 12.sp, C.Muted)
        Gap(16.dp)
        steps.forEachIndexed { i, (title, sub, ok) ->
            val firstTodo = steps.indexOfFirst { !it.third } == i
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(C.Panel)
                .then(if (firstTodo) Modifier.border(1.dp, C.Blue, RoundedCornerShape(10.dp)) else Modifier)
                .clickable {
                    when (i) {
                        0 -> if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        1 -> openBattery(ctx)
                        2 -> openAlarms(ctx)
                        3 -> keyPage = true
                    }
                }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(if (ok) C.Green else if (firstTodo) C.Blue else C.Dim), contentAlignment = Alignment.Center) {
                    T(if (ok) "✓" else "${i + 1}", 13.sp, C.Bg, FontWeight.Bold)
                }
                HGap(12.dp)
                Column(Modifier.weight(1f)) { T(title, 15.sp, if (ok || firstTodo) C.Txt else C.Muted, FontWeight.Bold); T(sub, 12.sp, C.Muted) }
                if (!ok) T("Fix", 13.sp, C.Blue, FontWeight.Bold)
            }
        }
        Box(Modifier.weight(1f))
        T("You start in PAPER mode: no API key needed. Add your Binance API key later in Settings > API key, only when you switch to LIVE.", 12.sp, C.Muted)
        Gap(8.dp)
        Btn("Start AI Desk", C.Green, enabled = steps[0].third || Build.VERSION.SDK_INT < 33) { vm.finishSetup() }
        Gap(4.dp)
        if (!steps[1].third || !steps[2].third) T("Without unrestricted battery and exact alarms, scans can be late or skipped.", 11.sp, C.Amber)
    }
}

internal fun pctTxt(v: Double): String = if (v == Math.floor(v)) v.toInt().toString() else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
