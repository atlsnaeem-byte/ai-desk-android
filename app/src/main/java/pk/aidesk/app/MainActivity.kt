package pk.aidesk.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.delay
import pk.aidesk.app.ui.AppTheme
import pk.aidesk.app.ui.AppVM
import pk.aidesk.app.ui.ApiKeyPage
import pk.aidesk.app.ui.C
import pk.aidesk.app.ui.DetailScreen
import pk.aidesk.app.ui.JournalScreen
import pk.aidesk.app.ui.LogsPage
import pk.aidesk.app.ui.Pill
import pk.aidesk.app.ui.PositionsScreen
import pk.aidesk.app.ui.SettingsScreen
import pk.aidesk.app.ui.SetupScreen
import pk.aidesk.app.ui.SignalsScreen
import pk.aidesk.app.ui.SymbolsPage
import pk.aidesk.app.ui.T
import pk.aidesk.app.ui.TakeSheet

class MainActivity : FragmentActivity() {
    private val vm: AppVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent { AppTheme { App(vm, this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        i ?: return
        i.getStringExtra("tab")?.let { vm.tab.value = it; vm.page.value = null }
        val sym = i.getStringExtra("symbol")
        if (sym != null && i.getStringExtra("tab") == "signals") {
            vm.detail.value = sym
            if (i.getBooleanExtra("take", false)) vm.take.value = sym
        }
    }

    override fun onResume() {
        super.onResume()
        vm.reload()
        vm.startPolling()
    }

    override fun onPause() {
        vm.stopPolling()
        super.onPause()
    }

    /** Fingerprint / screen-lock check before LIVE actions. Skipped if the phone has no screen lock. */
    fun confirmUser(title: String, ok: () -> Unit) {
        val auth = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) { ok(); return }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = ok()
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle("AI Desk").setAllowedAuthenticators(auth).build())
    }
}

@Composable
fun App(vm: AppVM, act: MainActivity) {
    val setup by vm.setupDone.collectAsState()
    val toast by vm.toast.collectAsState()
    Box(Modifier.fillMaxSize().background(C.Bg).systemBarsPadding()) {
        if (!setup) SetupScreen(vm, act) else MainNav(vm, act)
        toast?.let { msg ->
            LaunchedEffect(msg) { delay(5000); if (vm.toast.value == msg) vm.toast.value = null }
            Box(Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 72.dp)
                .clip(RoundedCornerShape(10.dp)).background(C.Panel2).clickable { vm.toast.value = null }.padding(12.dp)) {
                T(msg, 13.sp, C.Txt)
            }
        }
    }
}

@Composable
private fun MainNav(vm: AppVM, act: MainActivity) {
    val tab by vm.tab.collectAsState()
    val detail by vm.detail.collectAsState()
    val take by vm.take.collectAsState()
    val page by vm.page.collectAsState()

    BackHandler(enabled = take != null || detail != null || page != null) {
        when {
            take != null -> vm.take.value = null
            detail != null -> vm.detail.value = null
            else -> vm.page.value = null
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    "positions" -> PositionsScreen(vm)
                    "journal" -> JournalScreen(vm)
                    "settings" -> when (page) {
                        "apikey" -> ApiKeyPage(vm)
                        "logs" -> LogsPage(vm)
                        "symbols" -> SymbolsPage(vm)
                        else -> SettingsScreen(vm, act)
                    }
                    else -> SignalsScreen(vm, act)
                }
            }
            BottomNav(tab) { vm.tab.value = it; vm.page.value = null; vm.detail.value = null }
        }
        detail?.let { DetailScreen(vm, it) }
        take?.let { TakeSheet(vm, act, it) }
    }
}

@Composable
private fun BottomNav(tab: String, onTab: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(C.Panel).padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        for ((k, label) in listOf("signals" to "Signals", "positions" to "Positions", "journal" to "Journal", "settings" to "Settings")) {
            val on = k == tab
            Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { onTab(k) }.padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.clip(RoundedCornerShape(50)).background(if (on) androidx.compose.ui.graphics.Color(0xFF1D3A5C) else C.Panel)
                    .padding(horizontal = 16.dp, vertical = 5.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) C.Txt else C.Muted))
                }
                T(label, 11.sp, if (on) C.Txt else C.Muted, if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
fun TopBar(vm: AppVM, title: String, onBack: (() -> Unit)? = null) {
    val cfg by vm.config.collectAsState()
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            T("←", 22.sp, C.Txt, FontWeight.Bold, modifier = Modifier.clickable { onBack() }.padding(end = 12.dp))
        }
        T(title, 19.sp, C.Txt, FontWeight.Bold, modifier = Modifier.weight(1f))
        Pill(cfg.mode.uppercase(), C.mode(cfg.mode), filled = true)
    }
}
