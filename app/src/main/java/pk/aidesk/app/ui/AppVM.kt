package pk.aidesk.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pk.aidesk.app.core.Account
import pk.aidesk.app.core.Config
import pk.aidesk.app.core.Engine
import pk.aidesk.app.core.LogLine
import pk.aidesk.app.core.Preview
import pk.aidesk.app.core.Scan
import pk.aidesk.app.core.Trade
import pk.aidesk.app.service.TraderService

/** Screen state for the whole app. */
class AppVM(app: Application) : AndroidViewModel(app) {
    val engine = Engine(app)
    private val store get() = engine.store

    // navigation
    val tab = MutableStateFlow("signals")
    val detail = MutableStateFlow<String?>(null)       // symbol for Signal detail
    val take = MutableStateFlow<String?>(null)         // symbol for the Take sheet
    val page = MutableStateFlow<String?>(null)         // settings sub-page: apikey | logs | symbols
    val setupDone = MutableStateFlow(store.setupDone)

    // data
    val config = MutableStateFlow(engine.cfg())
    val scan = MutableStateFlow<Scan?>(store.scan())
    val account = MutableStateFlow<Account?>(null)
    val accountError = MutableStateFlow<String?>(null)
    val journalMode = MutableStateFlow(config.value.mode)
    val journal = MutableStateFlow<List<Trade>>(emptyList())
    val logs = MutableStateFlow<List<LogLine>>(emptyList())
    val lastCycle = MutableStateFlow(store.lastCycle)
    val nextCycle = MutableStateFlow(store.nextCycle)
    val lastError = MutableStateFlow(store.lastError)
    val toast = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val preview = MutableStateFlow<Preview?>(null)
    val previewError = MutableStateFlow<String?>(null)
    private var poll: Job? = null

    /** Live chart data for the open Signal detail (refreshed every 2 s). */
    class Live(val symbol: String, val candles: pk.aidesk.app.core.Candles, val t: Long) {
        val price: Double get() = candles.close.last()
    }
    val live = MutableStateFlow<Live?>(null)
    val liveError = MutableStateFlow<String?>(null)
    private var liveJob: Job? = null
    private val pub = pk.aidesk.app.core.Binance()

    fun startLive(symbol: String) {
        liveJob?.cancel()
        if (live.value?.symbol != symbol) live.value = null
        liveJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val c = withContext(Dispatchers.IO) { pub.live(symbol, 60) }
                    live.value = Live(symbol, c, System.currentTimeMillis())
                    liveError.value = null
                } catch (e: Exception) {
                    liveError.value = "Live price paused: ${e.message ?: e.javaClass.simpleName}"
                }
                delay(2000)
            }
        }
    }

    fun stopLive() { liveJob?.cancel(); liveJob = null }

    init {
        viewModelScope.launch { TraderService.ticks.collect { reload() } }
        loadJournal()
    }

    fun reload() {
        config.value = engine.cfg()
        scan.value = store.scan()
        lastCycle.value = store.lastCycle
        nextCycle.value = store.nextCycle
        lastError.value = store.lastError
        loadJournal()
        refreshAccount()
    }

    /** Poll the account every 10 s while the app is in the foreground. */
    fun startPolling() {
        poll?.cancel()
        poll = viewModelScope.launch {
            while (isActive) {
                refreshAccount()
                lastCycle.value = store.lastCycle; nextCycle.value = store.nextCycle; lastError.value = store.lastError
                delay(10_000)
            }
        }
    }

    fun stopPolling() { poll?.cancel() }

    fun refreshAccount() = viewModelScope.launch {
        try {
            account.value = withContext(Dispatchers.IO) { engine.account() }
            accountError.value = null
        } catch (e: Exception) {
            accountError.value = e.message ?: e.javaClass.simpleName
        }
    }

    fun loadJournal() = viewModelScope.launch {
        journal.value = withContext(Dispatchers.IO) { engine.journal(journalMode.value) }.sortedByDescending { it.openedMs }
    }

    fun loadLogs() = viewModelScope.launch { logs.value = withContext(Dispatchers.IO) { store.logs() } }

    fun saveConfig(c: Config) {
        store.saveConfig(c)
        config.value = engine.cfg()
        TraderService.instance?.let { TraderService.start(getApplication(), TraderService.ACTION_REFRESH) }
    }

    fun scanNow() {
        toast.value = "Scanning..."
        TraderService.start(getApplication(), TraderService.ACTION_CYCLE)
    }

    private fun act(okMsg: ((String) -> String)? = null, block: suspend () -> String) = viewModelScope.launch {
        busy.value = true
        try {
            val r = block()
            toast.value = okMsg?.invoke(r) ?: r
        } catch (e: Exception) {
            toast.value = e.message ?: e.javaClass.simpleName
        } finally {
            busy.value = false
            reload()
        }
    }

    fun loadPreview(symbol: String, riskPct: Double) = viewModelScope.launch {
        previewError.value = null
        preview.value = null
        try {
            preview.value = withContext(Dispatchers.IO) { engine.preview(symbol, riskPct) }
        } catch (e: Exception) {
            previewError.value = e.message ?: e.javaClass.simpleName
        }
    }

    fun placeOrder(symbol: String, riskPct: Double, done: () -> Unit) = act {
        val r = withContext(Dispatchers.IO) { engine.manualOpen(symbol, riskPct) }
        done(); r
    }

    fun close(symbol: String) = act { withContext(Dispatchers.IO) { engine.manualClose(symbol) } }

    fun saveKeys(mode: String, key: String, secret: String) = act {
        store.saveKeys(mode, key, secret)
        withContext(Dispatchers.IO) { engine.testConnection(mode) }.joinToString("\n")
    }

    fun testKeys(mode: String) = act { withContext(Dispatchers.IO) { engine.testConnection(mode) }.joinToString("\n") }

    fun oneWay() = act { withContext(Dispatchers.IO) { engine.setOneWay() } }

    fun finishSetup() {
        store.setupDone = true
        setupDone.value = true
        TraderService.start(getApplication())
    }
}
