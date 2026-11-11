package pl.radar.spolek.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.radar.spolek.data.AppSettings
import pl.radar.spolek.data.Repository
import pl.radar.spolek.data.ResultStore
import pl.radar.spolek.data.SettingsStore
import pl.radar.spolek.domain.Analysis
import pl.radar.spolek.domain.Bar
import pl.radar.spolek.domain.FundamentalScore
import pl.radar.spolek.domain.Fundamentals
import pl.radar.spolek.domain.GroupMeter
import pl.radar.spolek.domain.ScoredStock
import pl.radar.spolek.domain.TrendStock
import pl.radar.spolek.domain.BounceSignal
import pl.radar.spolek.domain.Recommendation
import pl.radar.spolek.domain.ScreenResult
import pl.radar.spolek.domain.ScreenerEngine
import pl.radar.spolek.domain.ThresholdHint
import pl.radar.spolek.domain.TrendResult
import pl.radar.spolek.domain.withParam
import pl.radar.spolek.work.Scheduler
import pl.radar.spolek.work.ScreeningWorker
import java.time.ZonedDateTime

data class HomeState(
    val result: ScreenResult? = null,
    val running: Boolean = false,
    val progress: Float = 0f,
    val progressMsg: String = "",
    val error: String? = null,
)

data class DetailState(
    val symbol: String = "",
    val name: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val bars: List<Bar> = emptyList(),
    /** Liczba świec poprzedzających okno wykresu (potrzebne do SMA50). */
    val chartStart: Int = 0,
    val pe: Double? = null,
    val recommendation: Recommendation? = null,
    val trend: TrendResult? = null,
    val bounce: BounceSignal? = null,
)

/** Indeks na ekranie głównym. */
enum class MarketIndex(val symbol: String, val label: String) {
    SP500("^GSPC", "S&P 500"),
    RUSSELL2000("^RUT", "Russell 2000"),
}

data class IndexState(
    val bars: List<Bar> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val updatedAt: Long = 0L,
)

/** Szczegóły branży (lub sektora): trendomierz grupy i spółki w trendzie z oceną fundamentalną. */
data class GroupState(
    val level: String = "",
    val name: String = "",
    val meter: GroupMeter? = null,
    val stocks: List<TrendStock> = emptyList(),
    val scored: List<ScoredStock> = emptyList(),
    val loading: Boolean = false,
    val progress: String = "",
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settingsStore = SettingsStore(app)
    private val resultStore = ResultStore(app)

    private val _home = MutableStateFlow(HomeState(result = resultStore.load()))
    val home: StateFlow<HomeState> = _home.asStateFlow()

    private val _detail = MutableStateFlow(DetailState())
    val detail: StateFlow<DetailState> = _detail.asStateFlow()

    private val _indices = MutableStateFlow(MarketIndex.entries.associateWith { IndexState() })
    val indices: StateFlow<Map<MarketIndex, IndexState>> = _indices.asStateFlow()

    private val _group = MutableStateFlow(GroupState())
    val group: StateFlow<GroupState> = _group.asStateFlow()

    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        loadIndices()
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(Scheduler.NOW).collect { infos ->
                val info = infos.firstOrNull() ?: return@collect
                when (info.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> _home.update {
                        it.copy(
                            running = true, error = null,
                            progress = info.progress.getFloat(ScreeningWorker.KEY_PROGRESS, it.progress),
                            progressMsg = info.progress.getString(ScreeningWorker.KEY_MSG)
                                ?: if (info.state == WorkInfo.State.ENQUEUED) "Oczekiwanie na sieć…" else it.progressMsg,
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        _home.update { it.copy(running = false, result = resultStore.load(), error = null) }
                        loadIndices()
                    }
                    WorkInfo.State.FAILED -> _home.update {
                        it.copy(running = false, error = info.outputData.getString(ScreeningWorker.KEY_ERROR) ?: "Błąd skanowania")
                    }
                    WorkInfo.State.CANCELLED -> _home.update { it.copy(running = false) }
                }
            }
        }
        // wynik mógł się zmienić w tle (zadanie cotygodniowe)
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(Scheduler.WEEKLY).collect {
                _home.update { s -> if (s.running) s else s.copy(result = resultStore.load()) }
            }
        }
    }

    /** Pobiera notowania S&P 500 i Russell 2000 (niezależnie od skanu). */
    fun loadIndices(force: Boolean = false) {
        for (idx in MarketIndex.entries) {
            val cur = _indices.value.getValue(idx)
            if (cur.loading) continue
            if (!force && cur.bars.isNotEmpty() && System.currentTimeMillis() - cur.updatedAt < 15 * 60_000L) continue
            _indices.update { it + (idx to cur.copy(loading = true, error = null)) }
            viewModelScope.launch {
                val repo = Repository(getApplication(), _settings.value.fmpKey, _settings.value.universe)
                val next = try {
                    val bars = repo.indexBars(idx.symbol)
                    IndexState(bars = bars, updatedAt = System.currentTimeMillis(),
                        error = if (bars.isEmpty()) "Brak notowań" else null)
                } catch (e: Exception) {
                    _indices.value.getValue(idx).copy(loading = false, error = e.message ?: "Błąd pobierania")
                }
                _indices.update { it + (idx to next) }
            }
        }
    }

    fun runNow() {
        _home.update { it.copy(running = true, progress = 0f, progressMsg = "Start…", error = null) }
        Scheduler.runNow(getApplication())
    }

    fun saveSettings(s: AppSettings) {
        settingsStore.save(s)
        _settings.value = s
        if (s.weeklyEnabled) Scheduler.scheduleWeekly(getApplication(), replace = true)
        else Scheduler.cancelWeekly(getApplication())
    }

    /** Ustawia próg z podpowiedzi diagnozy (pozostałe parametry bez zmian) i zapisuje ustawienia. */
    fun applyHint(h: ThresholdHint) {
        val v = h.required ?: return
        val s = _settings.value
        saveSettings(s.copy(config = s.config.withParam(h.key, h.rounded(v))))
    }

    /** [level] = "industry" albo "sector". */
    fun loadGroup(level: String, name: String) {
        val cur = _group.value
        if (cur.level == level && cur.name == name && (cur.loading || cur.scored.isNotEmpty())) return
        val r = _home.value.result
        val meter = (if (level == "sector") r?.sectorMeters else r?.industryMeters)?.firstOrNull { it.name == name }
        val stocks = r?.uptrendStocks.orEmpty().filter { (if (level == "sector") it.sector else it.industry) == name }
        _group.value = GroupState(level, name, meter, stocks, loading = stocks.isNotEmpty())
        if (stocks.isEmpty()) return
        viewModelScope.launch {
            val repo = Repository(getApplication(), _settings.value.fmpKey, _settings.value.universe)
            try {
                val f = repo.fundamentals(stocks.map { it.symbol }) { d, t ->
                    _group.update { it.copy(progress = "Wskaźniki fundamentalne: $d / $t") }
                }
                val scored = FundamentalScore.rank(stocks.map { f[it.symbol] ?: Fundamentals(it.symbol) })
                val missing = stocks.count { it.symbol !in f }
                _group.update {
                    it.copy(
                        scored = scored, loading = false,
                        error = if (missing > 0) "Nie pobrano wskaźników dla $missing spółek." else null,
                    )
                }
            } catch (e: Exception) {
                _group.update {
                    it.copy(loading = false, scored = FundamentalScore.rank(stocks.map { s -> Fundamentals(s.symbol) }),
                        error = e.message ?: "Błąd pobierania wskaźników")
                }
            }
        }
    }

    fun loadDetail(symbol: String) {
        val sym = symbol.trim().uppercase()
        if (_detail.value.symbol == sym && !_detail.value.loading && _detail.value.error == null) return
        val rec = _home.value.result?.recommendations?.firstOrNull { it.symbol == sym }
        _detail.value = DetailState(symbol = sym, name = rec?.name ?: "", recommendation = rec)
        viewModelScope.launch {
            val repo = Repository(getApplication(), _settings.value.fmpKey, _settings.value.universe)
            try {
                val bars = ScreenerEngine.dropIncomplete(repo.bars(sym), ZonedDateTime.now(ScreenerEngine.NY))
                if (bars.isEmpty()) throw IllegalStateException("Brak notowań dla $sym")
                val name = rec?.name ?: repo.company(sym)?.name ?: ""
                val cfg = _settings.value.config
                _detail.update {
                    it.copy(
                        name = name, bars = bars, loading = false,
                        chartStart = (bars.size - 126).coerceAtLeast(0),
                        trend = Analysis.trend(bars, cfg), bounce = Analysis.findBounce(bars, cfg),
                    )
                }
                val pe = runCatching { repo.peRatios(listOf(sym))[sym] }.getOrNull()
                _detail.update { it.copy(pe = pe ?: rec?.pe) }
            } catch (e: Exception) {
                _detail.update { it.copy(loading = false, error = e.message ?: "Błąd pobierania danych") }
            }
        }
    }
}
