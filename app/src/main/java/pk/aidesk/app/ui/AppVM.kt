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
        viewModelScope.launch(Dispatchers.Main) { TraderService.manualTicks.collect { if (tab.value == "trade") loadManual() } }
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
                     val journal: List<pk.aidesk.app.core.ShortDone>, val at: Long,
                     val announcements: List<pk.aidesk.app.core.Announcement> = emptyList())
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
                val an = if (market || old == null) runCatching { shortHunter.announcements(pub) }.getOrDefault(old?.announcements ?: emptyList())
                    else old.announcements
                ShortsView(shortHunter.cfg(), lst, pm, shortHunter.state(mode), shortHunter.journal(mode).reversed(), System.currentTimeMillis(), an)
            }
            shorts.value = v; shortsErr.value = null
        } catch (e: Exception) {
            shortsErr.value = e.message ?: e.javaClass.simpleName
        } finally { shortsLoading.value = false }
    }

    fun saveShortCfg(c: pk.aidesk.app.core.ShortCfg) {
        shortHunter.saveCfg(c)
        shorts.value?.let { shorts.value = ShortsView(c, it.listings, it.pumps, it.state, it.journal, it.at, it.announcements) }
        if (c.enabled || c.pumpAuto || c.annAuto) TraderService.start(getApplication())
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

    // ------------------------------------------------------------------ bot buttons (short hunter / copy)
    fun shortOpen(symbol: String) = act {
        val r = withContext(Dispatchers.IO) {
            val c = engine.cfg(); val api = engine.client(c); api.syncTime()
            shortHunter.openManual(c.mode, api, symbol) { api.equity() }
        }
        loadShorts(false); r
    }

    fun shortClose(symbol: String) = act {
        val r = withContext(Dispatchers.IO) {
            val c = engine.cfg(); val api = engine.client(c); api.syncTime()
            shortHunter.closeManual(c.mode, api, symbol)
        }
        loadShorts(false); r
    }

    // ------------------------------------------------------------------ pump hunter
    val pumpHunter = pk.aidesk.app.core.PumpHunter(store)
    class PumpsView(val cfg: pk.aidesk.app.core.PumpCfg, val scan: pk.aidesk.app.core.BreakoutScan?, val news: List<pk.aidesk.app.core.News>,
                    val state: pk.aidesk.app.core.PumpState, val journal: List<pk.aidesk.app.core.PumpDone>, val at: Long)
    val pumps = MutableStateFlow<PumpsView?>(null)
    val pumpsErr = MutableStateFlow<String?>(null)
    val pumpsLoading = MutableStateFlow(false)
    /** Daily base-breakout list for the Signals tab (cached scan, refreshed by the service once a day). */
    val breakouts = MutableStateFlow(pumpHunter.lastScan())

    fun loadBreakouts(force: Boolean = false) = viewModelScope.launch {
        if (force) toast.value = "Scanning all coins' daily candles (about a minute)…"
        try {
            breakouts.value = withContext(Dispatchers.IO) {
                if (force) pumpHunter.breakouts(pub.also { it.syncTime() }, force = true) else pumpHunter.lastScan() ?: pumpHunter.breakouts(pub.also { it.syncTime() })
            }
            if (force) toast.value = "Breakout scan done: ${breakouts.value?.list?.size ?: 0} found"
        } catch (e: Exception) {
            if (force) toast.value = "Breakout scan failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ------------------------------------------------------------------ Strategies tab
    val stratBook by lazy { pk.aidesk.app.core.StratBook(store) }
    val strat = MutableStateFlow(pk.aidesk.app.core.StratBook.RBF)
    val stratAuto = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val stratSigs = MutableStateFlow<List<pk.aidesk.app.core.StratSig>>(emptyList())
    val rbf = MutableStateFlow<pk.aidesk.app.core.Rbf.View?>(null)
    val rbfErr = MutableStateFlow<String?>(null)

    val structCfg = MutableStateFlow(pk.aidesk.app.core.StructCfg())
    val structLast = MutableStateFlow<org.json.JSONObject?>(null)
    val structBusy = MutableStateFlow(false)
    fun saveStructCfg(s: pk.aidesk.app.core.StructCfg) = viewModelScope.launch {
        withContext(Dispatchers.IO) { stratBook.saveStructCfg(s) }
        structCfg.value = s
    }
    /** Scan all structure coins now (takes ~30-90 s for every USDT perp). */
    fun scanStruct() {
        if (structBusy.value) return
        structBusy.value = true
        viewModelScope.launch {
            try {
                val n = withContext(Dispatchers.IO) { stratBook.structScan(pub).size }
                toast.value = "Market structure: $n signals in the last 24 h"
            } catch (e: Exception) { toast.value = "Scan failed: ${e.message ?: e.javaClass.simpleName}" }
            structBusy.value = false
            loadStrats()
        }
    }

    val exitCfg = MutableStateFlow(pk.aidesk.app.core.ExitCfg())
    fun saveExitCfg(e: pk.aidesk.app.core.ExitCfg) = viewModelScope.launch {
        withContext(Dispatchers.IO) { pk.aidesk.app.core.ExitCfg.save(stratBook.store, e) }
        exitCfg.value = e; toast.value = "Strategy exits: ${e.label}"
    }

    fun loadStrats() = viewModelScope.launch {
        exitCfg.value = withContext(Dispatchers.IO) { pk.aidesk.app.core.ExitCfg.load(stratBook.store) }
        structCfg.value = withContext(Dispatchers.IO) { stratBook.structCfg() }
        structLast.value = withContext(Dispatchers.IO) { stratBook.structLast() }
        stratAuto.value = withContext(Dispatchers.IO) { pk.aidesk.app.core.StratBook.ALL.associate { it.id to stratBook.auto(it.id) } }
        stratSigs.value = withContext(Dispatchers.IO) { stratBook.signals().sortedByDescending { it.time } }
    }

    fun setStratAuto(id: String, on: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { stratBook.setAuto(id, on) }
            if (id == pk.aidesk.app.core.StratBook.COURSE) taAuto.value = on
            if (on) TraderService.start(getApplication())
            toast.value = if (on) "${pk.aidesk.app.core.StratBook.info(id).name}: auto-trade ON (${config.value.mode.uppercase()})" else "Auto-trade off"
            loadStrats()
        }
    }

    fun loadRbf() = viewModelScope.launch {
        try {
            val v = withContext(Dispatchers.IO) { pk.aidesk.app.core.Rbf.scan(pub) }
            rbf.value = v; rbfErr.value = null
            withContext(Dispatchers.IO) { stratBook.record(stratBook.rbfSignals(v)) }
            loadStrats()
        } catch (e: Exception) { rbfErr.value = e.message ?: e.javaClass.simpleName }
    }

    /** Manual trade from a strategy / signal card: MARKET now, the signal's stop, 5 targets (1/5 at each). */
    fun stratTrade(symbol: String, side: String, sl: Double, setup: String, sigId: String?) = act {
        val r = withContext(Dispatchers.IO) {
            val c = engine.cfg(); val api = engine.client(c); api.syncTime()
            desk.place(c, api, pk.aidesk.app.core.Ticket(symbol, side, "MARKET", 0.0, sl, 0.0, c.riskPct, c.leverage, 24, setup, ladder = true))
        }
        sigId?.let { withContext(Dispatchers.IO) { stratBook.markTraded(it) } }
        TraderService.start(getApplication(), TraderService.ACTION_MANUAL)
        loadManual(); loadStrats(); r
    }

    // position tools (trades opened from the app: Trade tab, strategies, course auto trades)
    fun closePart(symbol: String, pct: Double, done: () -> Unit = {}) = act {
        val r = withContext(Dispatchers.IO) { val c = engine.cfg(); val api = engine.client(c); api.syncTime(); desk.closePart(c, api, symbol, pct) }
        loadManual(); done(); r
    }
    fun dca(symbol: String, usdt: Double, done: () -> Unit = {}) = act {
        val r = withContext(Dispatchers.IO) { val c = engine.cfg(); val api = engine.client(c); api.syncTime(); desk.addToPosition(c, api, symbol, usdt) }
        loadManual(); done(); r
    }
    fun addMargin(symbol: String, usdt: Double, done: () -> Unit = {}) = act {
        val r = withContext(Dispatchers.IO) { val c = engine.cfg(); val api = engine.client(c); api.syncTime(); desk.addMargin(c, api, symbol, usdt) }
        loadManual(); done(); r
    }

    /** Gold (XAUUSDT) Asian-range breakout signal for its own section in the Signals tab. */
    val gold = MutableStateFlow<pk.aidesk.app.core.Gold.Sig?>(null)
    val goldErr = MutableStateFlow<String?>(null)
    fun loadGold() = viewModelScope.launch {
        try {
            val g = withContext(Dispatchers.IO) { pk.aidesk.app.core.Gold.scan(pub) }
            gold.value = g; goldErr.value = null
            // record the Asian-range signal here too (the background scanner may not have run yet)
            withContext(Dispatchers.IO) { stratBook.asiaSignal(g, g.price)?.let { stratBook.record(listOf(it)) } }
            loadStrats()
        } catch (e: Exception) {
            goldErr.value = e.message ?: e.javaClass.simpleName
        }
    }

    /** BTC Up/Down 5m (Binance Prediction rounds): confirmed calls only, refreshed every few seconds by the Signals tab. */
    val btc5m = MutableStateFlow<pk.aidesk.app.core.Btc5m.Call?>(null)
    val btc5mErr = MutableStateFlow<String?>(null)
    var btc5mModel: pk.aidesk.app.core.Btc5m.Model? = null
        private set
    private var btc5mSynced = 0L
    private var btc5mBusy = false
    fun loadBtc5m() {
        if (btc5mBusy) return
        btc5mBusy = true
        viewModelScope.launch {
            try {
                btc5m.value = withContext(Dispatchers.IO) {
                    val m = btc5mModel ?: pk.aidesk.app.core.Btc5m.load(
                        getApplication<Application>().assets.open("btc5m.json").bufferedReader().use { it.readText() }).also { btc5mModel = it }
                    if (System.currentTimeMillis() - btc5mSynced > 600_000) { pub.syncTime(); btc5mSynced = System.currentTimeMillis() }
                    pk.aidesk.app.core.Btc5m.scan(pub, m)
                }
                btc5mErr.value = null
            } catch (e: Exception) {
                btc5mErr.value = e.message ?: e.javaClass.simpleName
            } finally {
                btc5mBusy = false
            }
        }
    }

    // BTC 5m paper / live record (the service records every minute; the UI reads and adds bets)
    val btcBook by lazy { pk.aidesk.app.core.Btc5mBook(store) }
    class BtcView(val cfg: pk.aidesk.app.core.Btc5mBook.Cfg, val bets: List<pk.aidesk.app.core.Btc5mBook.Bet>,
                  val calls: List<pk.aidesk.app.core.Btc5mBook.CallRow>,
                  val paper: pk.aidesk.app.core.Btc5mBook.Stats, val live: pk.aidesk.app.core.Btc5mBook.Stats,
                  val allCalls: Pair<Int, Int>, val confirmed: Pair<Int, Int>, val firstConfirmed: Pair<Int, Int>)
    val btcView = MutableStateFlow<BtcView?>(null)
    fun loadBtcBook() = viewModelScope.launch {
        btcView.value = withContext(Dispatchers.IO) {
            val b = btcBook
            BtcView(b.cfg(), b.bets().reversed(), b.calls().reversed(), b.stats("paper"), b.stats("live"),
                b.callStats(false), b.callStats(true), b.firstConfirmedStats())
        }
    }
    fun saveBtcCfg(c: pk.aidesk.app.core.Btc5mBook.Cfg) = viewModelScope.launch {
        withContext(Dispatchers.IO) { btcBook.saveCfg(c) }
        if (c.record) TraderService.start(getApplication())
        loadBtcBook()
    }
    fun addBtcBet(mode: String, call: pk.aidesk.app.core.Btc5m.Call, cents: Double?, stake: Double) = viewModelScope.launch {
        withContext(Dispatchers.IO) { btcBook.addBet(mode, call.roundStart, call.e, call.side, cents, stake) }
        toast.value = "${if (mode == "live") "Live" else "Paper"} bet recorded: ${call.side} ${cents?.let { "at ${it.toInt()}¢" } ?: "at the assumed price"}, ${stake} USDT"
        loadBtcBook()
    }
    fun deleteBtcBet(id: Long) = viewModelScope.launch { withContext(Dispatchers.IO) { btcBook.deleteBet(id) }; loadBtcBook() }
    fun clearBtc(what: String) = viewModelScope.launch { withContext(Dispatchers.IO) { btcBook.clear(what) }; loadBtcBook() }

    // BTC 5m structure AI (HH/HL/LH/LL on 1m/5m/15m -> one call at each round start) + its paper / live record
    val structCall = MutableStateFlow<pk.aidesk.app.core.Btc5mStruct.Call?>(null)
    val structErr = MutableStateFlow<String?>(null)
    var structModel: pk.aidesk.app.core.Btc5mStruct.Model? = null
        private set
    private var s5Busy = false
    private var structSynced = 0L
    fun loadStruct() {
        if (s5Busy) return
        s5Busy = true
        viewModelScope.launch {
            try {
                structCall.value = withContext(Dispatchers.IO) {
                    val m = structModel ?: pk.aidesk.app.core.Btc5mStruct.load(
                        getApplication<Application>().assets.open("btc5m_struct.json").bufferedReader().use { it.readText() }).also { structModel = it }
                    if (System.currentTimeMillis() - structSynced > 600_000) { pub.syncTime(); structSynced = System.currentTimeMillis() }
                    pk.aidesk.app.core.Btc5mStruct.scan(pub, m, structBook.cfg().thr)
                }
                structErr.value = null
            } catch (e: Exception) {
                structErr.value = e.message ?: e.javaClass.simpleName
            } finally {
                s5Busy = false
            }
        }
    }
    val structBook by lazy { pk.aidesk.app.core.Btc5mStructBook(store) }
    class StructView(val cfg: pk.aidesk.app.core.Btc5mStructBook.Cfg, val bets: List<pk.aidesk.app.core.Btc5mStructBook.Bet>,
                     val rounds: List<pk.aidesk.app.core.Btc5mStructBook.RoundRow>,
                     val paper: pk.aidesk.app.core.Btc5mStructBook.Stats, val paperToday: pk.aidesk.app.core.Btc5mStructBook.Stats,
                     val live: pk.aidesk.app.core.Btc5mStructBook.Stats, val calls: Pair<Int, Int>,
                     val liveToday: pk.aidesk.app.core.Btc5mStructBook.Stats, val status: Pair<Long, String>, val keyHint: String?,
                     val risk: String?)
    val structView = MutableStateFlow<StructView?>(null)
    fun loadStructBook() = viewModelScope.launch {
        structView.value = withContext(Dispatchers.IO) {
            val b = structBook
            val day = System.currentTimeMillis().let { it - (it + 5 * 3_600_000L) % 86_400_000L }     // midnight PKT
            val cfg = b.cfg()
            StructView(cfg, b.bets().reversed(), b.rounds().reversed(), b.stats("paper"), b.stats("paper", day), b.stats("live"), b.callStats(),
                b.stats("live", day), b.status(), store.keyHint("prediction"), b.riskBlock(cfg, cfg.mode, System.currentTimeMillis()))
        }
    }
    /** Binance Prediction API (separate key: Reading + Prediction Trading, withdrawals off). */
    val predInfo = MutableStateFlow<String?>(null)
    fun savePredKeys(key: String, secret: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { store.saveKeys("prediction", key, secret) }
        loadStructBook(); testPred()
    }
    fun clearPredKeys() = viewModelScope.launch { withContext(Dispatchers.IO) { store.clearKeys("prediction") }; predInfo.value = null; loadStructBook() }
    fun testPred() = viewModelScope.launch {
        predInfo.value = "Checking…"
        predInfo.value = withContext(Dispatchers.IO) {
            runCatching {
                val (k, s) = store.keys("prediction") ?: return@runCatching "No Prediction API key saved."
                val api = pk.aidesk.app.core.PredictionApi(k, s)
                api.syncTime()
                val w = api.wallet()
                val bal = api.balances()
                val wb = runCatching { api.walletBalance(w) }.getOrNull()
                val r = runCatching { api.apiRestrictions() }.getOrNull()
                val warn = r?.let { if (it.optBoolean("enableWithdrawals")) " · WARNING: withdrawals are ENABLED on this key" else "" } ?: ""
                "Connected · wallet ${w.address.take(6)}…${w.address.takeLast(4)} · prediction ${wb?.let { f2(it) } ?: "-"} USDT · " +
                    bal.filter { it.enabled }.joinToString(" · ") { "${it.accountType} ${f2(it.balance)}" } + warn
            }.getOrElse { e -> "Failed: ${(e as? pk.aidesk.app.core.BinanceException)?.msg ?: e.message}" }
        }
    }
    fun saveStructCfg(c: pk.aidesk.app.core.Btc5mStructBook.Cfg) = viewModelScope.launch {
        withContext(Dispatchers.IO) { structBook.saveCfg(c) }
        if (c.auto) TraderService.start(getApplication())
        loadStructBook()
        loadStruct()
    }
    fun addStructBet(mode: String, call: pk.aidesk.app.core.Btc5mStruct.Call, cents: Double?, stake: Double) = viewModelScope.launch {
        withContext(Dispatchers.IO) { structBook.addBet(mode, call.roundStart, call.side, cents, stake) }
        toast.value = "${if (mode == "live") "Live" else "Paper"} bet recorded: ${call.side} ${cents?.let { "at ${it.toInt()}¢" } ?: "at the paper price"}, $stake USDT"
        loadStructBook()
    }
    fun deleteStructBet(id: Long) = viewModelScope.launch { withContext(Dispatchers.IO) { structBook.deleteBet(id) }; loadStructBook() }
    fun clearStruct(what: String) = viewModelScope.launch { withContext(Dispatchers.IO) { structBook.clear(what) }; loadStructBook() }

    fun loadPumps(market: Boolean = true) = viewModelScope.launch {
        pumpsLoading.value = true
        try {
            val mode = config.value.mode
            val old = pumps.value
            val v = withContext(Dispatchers.IO) {
                val nw = if (market || old == null) runCatching { pumpHunter.news(pub) }.getOrDefault(old?.news ?: emptyList()) else old.news
                PumpsView(pumpHunter.cfg(), pumpHunter.lastScan(), nw, pumpHunter.state(mode), pumpHunter.journal(mode).reversed(), System.currentTimeMillis())
            }
            pumps.value = v; pumpsErr.value = null
            breakouts.value = v.scan
        } catch (e: Exception) {
            pumpsErr.value = e.message ?: e.javaClass.simpleName
        } finally { pumpsLoading.value = false }
    }

    fun savePumpCfg(c: pk.aidesk.app.core.PumpCfg) {
        pumpHunter.saveCfg(c)
        pumps.value?.let { pumps.value = PumpsView(c, it.scan, it.news, it.state, it.journal, it.at) }
        if (c.enabled) TraderService.start(getApplication(), TraderService.ACTION_PUMPS)
    }

    fun pumpOpen(symbol: String) = act {
        val r = withContext(Dispatchers.IO) {
            val c = engine.cfg(); val api = engine.client(c); api.syncTime()
            pumpHunter.openManual(c.mode, api, symbol) { api.equity() }
        }
        loadPumps(false); r
    }

    fun pumpClose(symbol: String) = act {
        val r = withContext(Dispatchers.IO) {
            val c = engine.cfg(); val api = engine.client(c); api.syncTime()
            pumpHunter.closeManual(c.mode, api, symbol)
        }
        loadPumps(false); r
    }

    /** New stop-loss / take-profit for an open position (any bot). */
    fun editExits(symbol: String, sl: Double, tp: Double, done: () -> Unit = {}) = act {
        val r = withContext(Dispatchers.IO) { engine.editExits(symbol, sl, tp) }
        openPos.value = withContext(Dispatchers.IO) { findPosition(symbol) }
        loadShorts(false); loadPumps(false); loadManual()
        done(); r
    }

    fun copyClose(ck: String) = act {
        val r = withContext(Dispatchers.IO) {
            val c = engine.cfg(); val api = engine.client(c); api.syncTime()
            copyBot.closeManual(c.mode, api, pub, ck)
        }
        loadCopy(false); r
    }

    // ------------------------------------------------------------------ trade tab (manual trading)
    val desk = pk.aidesk.app.core.ManualDesk(store)
    val coins = MutableStateFlow<List<pk.aidesk.app.core.CoinRow>>(emptyList())
    val coinsErr = MutableStateFlow<String?>(null)
    val tradeSym = MutableStateFlow<String?>(null)
    val tradeTf = MutableStateFlow("15m")
    val analysis = MutableStateFlow<pk.aidesk.app.core.Advisor.Analysis?>(null)
    val analysisErr = MutableStateFlow<String?>(null)
    val analysisLoading = MutableStateFlow(false)
    val manual = MutableStateFlow<List<pk.aidesk.app.core.ManualPlan>>(emptyList())
    val quote = MutableStateFlow<pk.aidesk.app.core.Quote?>(null)
    val quoteErr = MutableStateFlow<String?>(null)
    private var anaJob: Job? = null

    fun loadCoins() = viewModelScope.launch {
        try {
            coins.value = withContext(Dispatchers.IO) { pub.usdtPerps() }
            coinsErr.value = null
        } catch (e: Exception) {
            coinsErr.value = e.message ?: e.javaClass.simpleName
        }
    }

    fun openCoin(symbol: String) {
        tradeSym.value = symbol
        analysis.value = null
        quote.value = null
        startAnalysis()
    }

    fun closeCoin() {
        stopAnalysis()
        tradeSym.value = null
        analysis.value = null
        openPos.value = null
    }

    // ------------------------------------------------------------------ closed trades of every bot (Positions tab)
    class ClosedRow(val symbol: String, val side: String, val source: String, val closed: Long, val pnl: Double, val reason: String)
    val closedAll = MutableStateFlow<List<ClosedRow>>(emptyList())

    fun loadClosedAll() = viewModelScope.launch {
        val mode = config.value.mode
        closedAll.value = withContext(Dispatchers.IO) {
            val a = store.journal(mode).filter { it.status == "closed" && it.pnl != null }.map {
                ClosedRow(it.symbol, it.side, if (it.origin == "manual") "manual" else "AI ${it.origin}", it.exitMs ?: it.openedMs, it.pnl!!, it.reason ?: "")
            }
            val b = shortHunter.journal(mode).map {
                ClosedRow(it.symbol, if (it.rule == "btc hedge") "LONG" else "SHORT", if (it.rule == "btc hedge") "btc hedge" else "short hunter",
                    it.closed, it.pnl, it.reason)
            }
            val c = copyBot.journal(mode).map { ClosedRow(it.symbol, it.side, "copy ${it.leader}", it.closed, it.pnl, it.reason) }
            val d = pumpHunter.journal(mode).map { ClosedRow(it.symbol, "LONG", "pump hunter", it.closed, it.pnl, it.reason) }
            (a + b + c + d).sortedByDescending { it.closed }
        }
    }

    // ------------------------------------------------------------------ TA-course signals (4h divergence / range fade)
    val taScanner = pk.aidesk.app.core.TaScanner(store)
    val taSignals = MutableStateFlow<List<pk.aidesk.app.core.TaSignal>>(emptyList())
    val taAuto = MutableStateFlow(taScanner.auto)
    fun setTaAuto(on: Boolean) { taScanner.auto = on; taAuto.value = on; if (on) TraderService.start(getApplication()) }

    fun loadTa() = viewModelScope.launch { taSignals.value = withContext(Dispatchers.IO) { taScanner.all() }.sortedByDescending { it.since } }

    fun clearTaClosed() = viewModelScope.launch { withContext(Dispatchers.IO) { taScanner.clearClosed() }; loadTa() }

    /** Open a coin in the Trade tab on the signal's timeframe (Signals card / notification). */
    fun openTaSignal(symbol: String, tf: String) {
        tab.value = "trade"; detail.value = null
        tradeTf.value = tf
        openCoin(symbol)
    }

    fun setTf(tf: String) {
        if (tf == tradeTf.value) return
        tradeTf.value = tf
        analysis.value = null
        startAnalysis()
    }

    /** Live price (every 3 s) and the open position on this coin, for the chart + running P&L. */
    val livePx = MutableStateFlow<Double?>(null)
    val openPos = MutableStateFlow<PosLine?>(null)
    private var pxJob: Job? = null

    /** An open position on a coin, from whichever bot / tab opened it. */
    class PosLine(val source: String, val side: String, val entry: Double, val qty: Double, val sl: Double?, val tp: Double?,
                  val opened: Long?, val margin: Double?) {
        fun pnl(px: Double) = qty * (px - entry) * (if (side == "LONG") 1 else -1)
    }

    /** Find the open position on `symbol` (short hunter, copy, manual trade tab, AI engine or exchange). */
    fun findPosition(symbol: String): PosLine? {
        val mode = config.value.mode
        shortHunter.state(mode).open[symbol]?.let { return PosLine("Short hunter", "SHORT", it.entry, it.qty, it.sl, it.tp, it.opened, it.margin) }
        pumpHunter.state(mode).open[symbol]?.let { return PosLine("Pump hunter · ${it.rule}", "LONG", it.entry, it.qty, it.sl, it.tp, it.opened, it.margin) }
        copyBot.state(mode).copies.values.firstOrNull { it.symbol == symbol }?.let {
            return PosLine("Copy · ${it.leader}", it.side, it.entry, it.qty, null, null, it.opened, it.qty * it.entry / it.lev)
        }
        desk.active(mode).firstOrNull { it.symbol == symbol && it.status == "open" }?.let {
            return PosLine("Trade tab", it.side, it.fill ?: it.entry, it.qty, it.sl, it.tp, it.filledMs, null)
        }
        account.value?.positions?.firstOrNull { it.symbol == symbol }?.let {
            return PosLine(if (it.origin == "external") "Binance (not from the app)" else it.origin, it.side, it.entry, it.qty, it.sl, it.tp, it.openedMs, null)
        }
        return null
    }

    /** Open the live chart of a coin in the Trade tab (from Positions / Bots). */
    fun showChart(symbol: String) {
        tab.value = "trade"; page.value = null; detail.value = null
        openCoin(symbol)
    }

    /** Re-analyse the open coin every 10 s and refresh its price every 3 s while the Trade screen shows it. */
    fun startAnalysis() {
        anaJob?.cancel()
        anaJob = viewModelScope.launch {
            while (isActive) {
                val s = tradeSym.value ?: break
                val tf = tradeTf.value
                analysisLoading.value = true
                try {
                    val a = withContext(Dispatchers.IO) { pk.aidesk.app.core.Advisor.load(pub, s, tf, 800) }
                    if (s == tradeSym.value && tf == tradeTf.value) { analysis.value = a; analysisErr.value = null }
                    openPos.value = withContext(Dispatchers.IO) { findPosition(s) }
                } catch (e: Exception) {
                    analysisErr.value = "Could not load $s: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    analysisLoading.value = false
                }
                delay(10_000)
            }
        }
        pxJob?.cancel()
        livePx.value = null
        pxJob = viewModelScope.launch {
            while (isActive) {
                val s = tradeSym.value ?: break
                runCatching { withContext(Dispatchers.IO) { pub.markPrice(s) } }.onSuccess { if (s == tradeSym.value) livePx.value = it }
                delay(3_000)
            }
        }
    }

    fun stopAnalysis() { anaJob?.cancel(); anaJob = null; pxJob?.cancel(); pxJob = null }

    // ------------------------------------------------------------------ live marks (signals / bots)
    val marks = MutableStateFlow<Map<String, Double>>(emptyMap())
    fun refreshMarks() = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { pub.markPrices() } }.onSuccess { marks.value = it }
    }

    fun loadManual() = viewModelScope.launch {
        manual.value = withContext(Dispatchers.IO) { desk.plans(config.value.mode) }.sortedByDescending { it.created }
    }

    fun quoteTicket(t: pk.aidesk.app.core.Ticket) = viewModelScope.launch {
        try {
            quote.value = withContext(Dispatchers.IO) { val c = engine.cfg(); desk.quote(c, engine.client(c), t) }
            quoteErr.value = null
        } catch (e: Exception) {
            quote.value = null
            quoteErr.value = e.message ?: e.javaClass.simpleName
        }
    }

    fun placeManual(t: pk.aidesk.app.core.Ticket, done: () -> Unit) = act {
        val r = withContext(Dispatchers.IO) { val c = engine.cfg(); val api = engine.client(c); api.syncTime(); desk.place(c, api, t) }
        TraderService.start(getApplication(), TraderService.ACTION_MANUAL)
        loadManual(); done(); r
    }

    fun cancelManual(id: String) = act {
        val r = withContext(Dispatchers.IO) { val c = engine.cfg(); val api = engine.client(c); api.syncTime(); desk.cancel(c, api, id) }
        loadManual(); r
    }

    // ------------------------------------------------------------------ data export (Settings > Data)
    val historyCount = MutableStateFlow(0)
    fun loadHistoryCount() = viewModelScope.launch { historyCount.value = withContext(Dispatchers.IO) { pk.aidesk.app.core.History.all(store).size } }

    /** Write the chosen CSV to the file the user picked. UTF-8 with BOM so Excel opens it correctly. */
    fun writeExport(uri: android.net.Uri, kind: String) = act {
        withContext(Dispatchers.IO) {
            val (text, n) = when (kind) {
                "btc5m" -> btcBook.csv().let { it to (it.count { ch -> ch == '\n' } - 1) }
                "btc5ms" -> structBook.csv().let { it to (it.count { ch -> ch == '\n' } - 1) }
                "signals" ->store.signalLog().let { l -> pk.aidesk.app.core.History.csvOf(l.map { it.toJson() }) to l.size }
                else -> pk.aidesk.app.core.History.csv(store)
            }
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: throw pk.aidesk.app.core.UserError("Could not open the file for writing.")
            "Exported $n ${when (kind) { "signals" -> "signal results"; "btc5m" -> "BTC 5m calls and bets"; "btc5ms" -> "BTC 5m structure rounds and bets"; else -> "trades" }}. Open it in Excel, or send it to the laptop for the AI to learn from."
        }
    }

    fun finishSetup() {
        store.setupDone = true
        setupDone.value = true
        TraderService.start(getApplication())
    }
}
