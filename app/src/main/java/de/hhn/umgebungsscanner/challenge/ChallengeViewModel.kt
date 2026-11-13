package de.hhn.umgebungsscanner.challenge

import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.hhn.umgebungsscanner.data.AppSettings
import de.hhn.umgebungsscanner.data.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Zustände der Balance-Challenge als sealed interface. */
sealed interface BalanceState {
    /** Noch nicht gestartet oder beendet. */
    data object Idle : BalanceState
    data object Playing : BalanceState
    data class Won(val seconds: Float, val newBest: Boolean) : BalanceState
    data object NoSensor : BalanceState
}

data class ChallengeUiState(
    val state: BalanceState = BalanceState.Idle,
    val snapshot: BalanceSnapshot = BalanceSnapshot(),
    val config: ChallengeConfig = ChallengeConfig.DEFAULT,
    /** Letztes Ergebnis für die Übersicht auf dem Startbildschirm. */
    val lastResult: String? = null,
)

class ChallengeViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)
    private val sensorManager = application.getSystemService(SensorManager::class.java)

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    @OptIn(ExperimentalCoroutinesApi::class)
    val bestTime: StateFlow<Float?> = settings
        .map { it.matrikel }
        .distinctUntilChanged()
        .flatMapLatest { settingsRepository.bestTime(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _ui = MutableStateFlow(ChallengeUiState())
    val ui: StateFlow<ChallengeUiState> = _ui.asStateFlow()

    private var engine = BalanceEngine(_ui.value.config)

    init {
        // Neue Matrikelnummer = neue Spielwerte. Eine laufende Runde startet dann neu.
        viewModelScope.launch {
            settings.map { ChallengeConfig.fromMatrikel(it.matrikel) }
                .distinctUntilChanged()
                .collect { config ->
                    if (config != null && config != _ui.value.config) {
                        val arena = engine.arenaRadiusDp
                        engine = BalanceEngine(config).also { it.arenaRadiusDp = arena }
                        _ui.update { it.copy(config = config, snapshot = engine.snapshot()) }
                    }
                }
        }
    }

    /**
     * Liefert das Gyroskop oder null, wenn das Gerät keins hat.
     * Der Debug-Schalter in den Einstellungen simuliert ein Gerät ohne Gyroskop.
     */
    fun findGyroscope(): Sensor? {
        if (settings.value.simulateMissingGyro) return null
        return sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    }

    /** Startet eine Runde, falls noch keine läuft (übersteht so auch eine Neuerstellung der Activity). */
    fun ensureStarted() {
        if (_ui.value.state == BalanceState.Idle) restart()
    }

    fun restart() {
        if (findGyroscope() == null) {
            _ui.update { it.copy(state = BalanceState.NoSensor, lastResult = "Kein Gyroskop") }
            return
        }
        engine.reset()
        _ui.update { it.copy(state = BalanceState.Playing, snapshot = engine.snapshot(), lastResult = "Gyroskop aktiv") }
    }

    /** "Challenge beenden": zurück auf Idle. */
    fun stop() {
        _ui.update { current ->
            current.copy(
                state = BalanceState.Idle,
                lastResult = when (current.state) {
                    is BalanceState.Won -> current.lastResult
                    BalanceState.NoSensor -> "Kein Gyroskop"
                    else -> "Beendet"
                },
            )
        }
    }

    fun setArenaRadius(radiusDp: Float, ballRadiusDp: Float) {
        engine.arenaRadiusDp = radiusDp
        engine.ballRadiusDp = ballRadiusDp
    }

    /** Neue Messung des Gyroskops (rad/s), dt in Sekunden aus den Zeitstempeln der Events. */
    fun onGyroscope(omegaX: Float, omegaY: Float, dt: Float) {
        if (_ui.value.state != BalanceState.Playing) return
        engine.onRotation(omegaX, omegaY, dt)
    }

    /** Ein Frame der Spielschleife. */
    fun step(dtSeconds: Float) {
        if (_ui.value.state != BalanceState.Playing) return
        val snapshot = engine.step(dtSeconds)
        if (snapshot.won) {
            val seconds = snapshot.elapsed
            _ui.update {
                it.copy(
                    state = BalanceState.Won(seconds, newBest = false),
                    snapshot = snapshot,
                    lastResult = "Geschafft in ${seconds.formatDe()} s",
                )
            }
            viewModelScope.launch {
                val improved = settingsRepository.submitTime(settings.value.matrikel, seconds)
                if (improved) {
                    _ui.update { current ->
                        val state = current.state
                        if (state is BalanceState.Won) current.copy(state = state.copy(newBest = true)) else current
                    }
                }
            }
        } else {
            _ui.update { it.copy(snapshot = snapshot) }
        }
    }

    fun saveSettings(matrikel: String, simulateMissingGyro: Boolean) {
        viewModelScope.launch {
            settingsRepository.setMatrikel(matrikel)
            settingsRepository.setSimulateMissingGyro(simulateMissingGyro)
        }
    }

    fun resetBestTime() {
        viewModelScope.launch { settingsRepository.resetBestTime(settings.value.matrikel) }
    }
}

fun Float.formatDe(decimals: Int = 1): String = String.format(java.util.Locale.GERMANY, "%.${decimals}f", this)
