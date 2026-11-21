package com.example.ui

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import com.example.audio.SoundEngine
import com.example.engine.GameEngine
import com.example.model.AudioSettings
import com.example.model.ControlLayout
import com.example.model.GameSettings
import com.example.model.GraphicsPreset
import com.example.model.GraphicsSettings
import com.example.model.MatchConfig
import com.example.model.ModelAssetRegistry
import com.example.model.PlayerStats
import com.example.model.SensitivitySettings
import com.example.model.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface AppScreen {
    data object Menu : AppScreen
    data object MultiplayerLobby : AppScreen
    data object Playing : AppScreen
    data class Settings(val returnToGame: Boolean) : AppScreen
    data class ControlEditor(val returnToGame: Boolean) : AppScreen
}

class GameViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application)
    val modelRegistry = ModelAssetRegistry(application)
    private val soundEngine = SoundEngine()
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val mgr = application.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        mgr?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        application.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val _settings = MutableStateFlow(repository.loadSettings())
    val settings: StateFlow<GameSettings> = _settings.asStateFlow()

    private val _stats = MutableStateFlow(repository.loadStats())
    val stats: StateFlow<PlayerStats> = _stats.asStateFlow()

    private val _matchConfig = MutableStateFlow(MatchConfig())
    val matchConfig: StateFlow<MatchConfig> = _matchConfig.asStateFlow()

    private val _screen = MutableStateFlow<AppScreen>(AppScreen.Menu)
    val screen: StateFlow<AppScreen> = _screen.asStateFlow()

    private val _isPauseOverlayOpen = MutableStateFlow(false)
    val isPauseOverlayOpen: StateFlow<Boolean> = _isPauseOverlayOpen.asStateFlow()

    val engine: GameEngine = GameEngine(
        appContext = application,
        soundEngine = soundEngine,
        onHapticPulse = { isHeavy -> triggerHaptic(isHeavy) },
        onMatchFinished = { score, kills, headshots ->
            val updated = repository.recordMatch(score, kills, headshots)
            _stats.value = updated
        }
    ).also {
        it.settings = _settings.value
    }

    fun updateMatchConfig(transform: (MatchConfig) -> MatchConfig) {
        _matchConfig.value = transform(_matchConfig.value)
    }

    fun startGame() {
        _isPauseOverlayOpen.value = false
        engine.settings = _settings.value
        engine.startNewMatch(_matchConfig.value.copy(isMultiplayer = false, lobbyId = null, allowBots = true))
        _screen.value = AppScreen.Playing
    }

    fun openMultiplayerLobby() {
        _isPauseOverlayOpen.value = false
        _screen.value = AppScreen.MultiplayerLobby
    }

    fun startMultiplayerMatch(config: MatchConfig) {
        _matchConfig.value = config
        _isPauseOverlayOpen.value = false
        engine.settings = _settings.value
        engine.startNewMatch(config)
        _screen.value = AppScreen.Playing
    }

    fun restartMatch() {
        _isPauseOverlayOpen.value = false
        engine.requestReset()
    }

    fun setPaused(paused: Boolean) {
        engine.isPaused = paused
        _isPauseOverlayOpen.value = paused
    }

    fun openSettings(fromGame: Boolean) {
        if (fromGame) engine.isPaused = true
        _screen.value = AppScreen.Settings(returnToGame = fromGame)
    }

    fun openControlEditor(fromGame: Boolean) {
        if (fromGame) engine.isPaused = true
        _screen.value = AppScreen.ControlEditor(returnToGame = fromGame)
    }

    fun navigateBackToOrigin(returnToGame: Boolean) {
        if (returnToGame) {
            _isPauseOverlayOpen.value = true
            engine.isPaused = true
            _screen.value = AppScreen.Playing
        } else {
            _screen.value = AppScreen.Menu
        }
    }

    fun exitToMainMenu() {
        engine.isPaused = true
        engine.isFiring = false
        engine.isBombActionHeld = false
        _isPauseOverlayOpen.value = false
        _screen.value = AppScreen.Menu
    }

    fun applyGraphicsPreset(preset: GraphicsPreset) {
        val current = _settings.value.graphics
        val next = GraphicsSettings.forPreset(preset).copy(
            fov = current.fov,
            showFps = current.showFps
        )
        updateGraphics(next)
    }

    fun updateGraphics(graphics: GraphicsSettings) {
        updateSettings { it.copy(graphics = graphics) }
    }

    fun updateSensitivity(sensitivity: SensitivitySettings) {
        updateSettings { it.copy(sensitivity = sensitivity) }
    }

    fun updateAudio(audio: AudioSettings) {
        updateSettings { it.copy(audio = audio) }
    }

    fun updateControlLayout(layout: ControlLayout) {
        updateSettings { it.copy(controlLayout = layout) }
    }

    fun resetAllSettings() {
        val defaults = GameSettings()
        _settings.value = defaults
        engine.settings = defaults
        repository.saveSettings(defaults)
    }

    private fun updateSettings(transform: (GameSettings) -> GameSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        engine.settings = next
        repository.saveSettings(next)
    }

    private fun triggerHaptic(isHeavy: Boolean) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            val ms = if (isHeavy) 28L else 12L
            val amp = if (isHeavy) 165 else 75
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, amp))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        soundEngine.release()
    }
}
