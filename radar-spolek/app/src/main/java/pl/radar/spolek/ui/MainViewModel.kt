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
import pl.radar.spolek.domain.BounceSignal
import pl.radar.spolek.domain.Recommendation
import pl.radar.spolek.domain.ScreenResult
import pl.radar.spolek.domain.ScreenerEngine
import pl.radar.spolek.domain.TrendResult
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

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settingsStore = SettingsStore(app)
    private val resultStore = ResultStore(app)

    private val _home = MutableStateFlow(HomeState(result = resultStore.load()))
    val home: StateFlow<HomeState> = _home.asStateFlow()

    private val _detail = MutableStateFlow(DetailState())
    val detail: StateFlow<DetailState> = _detail.asStateFlow()

    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
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
                    WorkInfo.State.SUCCEEDED -> _home.update {
                        it.copy(running = false, result = resultStore.load(), error = null)
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

    fun loadDetail(symbol: String) {
        val sym = symbol.trim().uppercase()
        if (_detail.value.symbol == sym && !_detail.value.loading && _detail.value.error == null) return
        val rec = _home.value.result?.recommendations?.firstOrNull { it.symbol == sym }
        _detail.value = DetailState(symbol = sym, name = rec?.name ?: "", recommendation = rec)
        viewModelScope.launch {
            val repo = Repository(getApplication(), _settings.value.fmpKey)
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
