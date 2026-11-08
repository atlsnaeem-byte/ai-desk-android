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
        // Dispatchers.Main (not .immediate): run after the constructor, when all properties below exist.
        viewModelScope.launch(Dispatchers.Main) { TraderService.ticks.collect { reload(); if (journalView.value == "signals") loadSigLog() } }
        viewModelScope.launch(Dispatchers.Main) { TraderService.copyTicks.collect { if (tab.value == "bots" && botTab.value == "copy") loadCopy(false) } }
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

    // ------------------------------------------------------------------ bots tab
    val botTab = MutableStateFlow("shorts")                       // shorts | copy
    val shortHunter = pk.aidesk.app.core.ShortHunter(store)
    val copyBot = pk.aidesk.app.core.CopyBot(store)               // reads/settings only; the service runs the trading one

    class ShortsView(val cfg: pk.aidesk.app.core.ShortCfg, val listings: List<pk.aidesk.app.core.Listing>,
                     val pumps: List<pk.aidesk.app.core.Pump>, val state: pk.aidesk.app.core.ShortState,
                     val journal: List<pk.aidesk.app.core.ShortDone>, val at: Long)
    val shorts = MutableStateFlow<ShortsView?>(null)
    val shortsErr = MutableStateFlow<String?>(null)
    val shortsLoading = MutableStateFlow(false)

    fun loadShorts(market: Boolean = true) = viewModelScope.launch {
        shortsLoading.value = true
        try {
            val mode = config.value.mode
            val old = shorts.value
            val v = withContext(Dispatchers.IO) {
                val lst = if (market || old == null) shortHunter.listings(pub) else old.listings
                val pm = if (market || old == null) runCatching { shortHunter.pumps(pub) }.getOrDefault(old?.pumps ?: emptyList()) else old.pumps
                ShortsView(shortHunter.cfg(), lst, pm, shortHunter.state(mode), shortHunter.journal(mode).reversed(), System.currentTimeMillis())
            }
            shorts.value = v; shortsErr.value = null
        } catch (e: Exception) {
            shortsErr.value = e.message ?: e.javaClass.simpleName
        } finally { shortsLoading.value = false }
    }

    fun saveShortCfg(c: pk.aidesk.app.core.ShortCfg) {
        shortHunter.saveCfg(c)
        shorts.value?.let { shorts.value = ShortsView(c, it.listings, it.pumps, it.state, it.journal, it.at) }
        if (c.enabled) TraderService.start(getApplication())
    }

    class LeaderView(val leader: pk.aidesk.app.core.Leader, val detail: pk.aidesk.app.core.LeadDetail?,
                     val positions: Map<String, pk.aidesk.app.core.LeadPos>?)
    class CopyView(val cfg: pk.aidesk.app.core.CopyCfg, val state: pk.aidesk.app.core.CopyState,
                   val open: List<Pair<pk.aidesk.app.core.CopyPos, Double?>>, val journal: List<pk.aidesk.app.core.CopyDone>,
                   val leaders: List<LeaderView>, val at: Long)
    val copy = MutableStateFlow<CopyView?>(null)
    val copyErr = MutableStateFlow<String?>(null)

    fun loadCopy(withLeaders: Boolean = true) = viewModelScope.launch {
        try {
            val mode = config.value.mode
            val old = copy.value
            val v = withContext(Dispatchers.IO) {
                val cfg = copyBot.cfg()
                val lv = if (withLeaders || old == null) cfg.leaders.map { l ->
                    LeaderView(l, pk.aidesk.app.core.Bapi.detail(l.id), pk.aidesk.app.core.Bapi.positions(l.id))
                } else cfg.leaders.map { l -> LeaderView(l, old.leaders.find { it.leader.id == l.id }?.detail, old.leaders.find { it.leader.id == l.id }?.positions) }
                CopyView(cfg, copyBot.state(mode), copyBot.openWithMarks(mode, pub), copyBot.journal(mode).reversed(), lv, System.currentTimeMillis())
            }
            copy.value = v; copyErr.value = null
        } catch (e: Exception) {
            copyErr.value = e.message ?: e.javaClass.simpleName
        }
    }

    fun saveCopyCfg(c: pk.aidesk.app.core.CopyCfg, reload: Boolean = false) {
        copyBot.saveCfg(c)
        copy.value?.let { v -> copy.value = CopyView(c, v.state, v.open, v.journal,
            c.leaders.map { l -> LeaderView(l, v.leaders.find { it.leader.id == l.id }?.detail, v.leaders.find { it.leader.id == l.id }?.positions) }, v.at) }
        TraderService.start(getApplication(), TraderService.ACTION_COPY)
        if (reload) loadCopy(true)
    }

    fun addLeader(text: String) = act {
        val id = pk.aidesk.app.core.Bapi.parseId(text) ?: throw pk.aidesk.app.core.UserError("Paste a Binance lead-trader link or portfolio ID.")
        val c = copyBot.cfg()
        if (c.leaders.any { it.id == id }) throw pk.aidesk.app.core.UserError("Already in the list.")
        val d = withContext(Dispatchers.IO) { pk.aidesk.app.core.Bapi.detail(id) } ?: throw pk.aidesk.app.core.UserError("Binance has no lead trader $id.")
        val note = if (d.show) "added by you" else "positions HIDDEN: cannot be copied"
        saveCopyCfg(c.copy(leaders = c.leaders + pk.aidesk.app.core.Leader(id, d.name, false, note)), reload = true)
        if (d.show) "Added ${d.name}. Switch it on to follow." else "Added ${d.name}, but they hide their positions, so the bot cannot copy them."
    }

    fun removeLeader(id: String) {
        val c = copyBot.cfg()
        saveCopyCfg(c.copy(leaders = c.leaders.filter { it.id != id }))
    }

    // ------------------------------------------------------------------ signal results
    val sigLog = MutableStateFlow<List<pk.aidesk.app.core.SigRec>>(emptyList())
    val journalView = MutableStateFlow("trades")                   // trades | signals

    fun loadSigLog() = viewModelScope.launch { sigLog.value = withContext(Dispatchers.IO) { store.signalLog() }.sortedByDescending { it.since } }

    fun clearSigLog() { store.clearSignalLog(); sigLog.value = emptyList() }

    fun finishSetup() {
        store.setupDone = true
        setupDone.value = true
        TraderService.start(getApplication())
    }
}
