package com.example

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.AppScreen
import com.example.ui.ControlEditorScreen
import com.example.ui.GameViewModel
import com.example.ui.GameplayScreen
import com.example.ui.MainMenuScreen
import com.example.ui.SettingsScreen
import com.example.ui.theme.ResponsiveUiProvider
import com.example.ui.theme.VanguardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            VanguardTheme {
                ResponsiveUiProvider {
                    VanguardRtxApp()
                }
            }
        }
    }
}

@Composable
fun VanguardRtxApp(vm: GameViewModel = viewModel()) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val matchConfig by vm.matchConfig.collectAsStateWithLifecycle()
    val isPaused by vm.isPauseOverlayOpen.collectAsStateWithLifecycle()

    // Smooth non-overlapping fade-in veil: guarantees only ONE Google Filament Engine / SurfaceView
    // is ever alive at a time while giving every screen switch a crisp cinematic fade transition!
    val transitionAlpha = remember { Animatable(0f) }
    LaunchedEffect(screen) {
        transitionAlpha.snapTo(0.85f)
        transitionAlpha.animateTo(0f, animationSpec = tween(durationMillis = 260))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val current = screen) {
            AppScreen.Menu -> MainMenuScreen(
                settings = settings,
                stats = stats,
                matchConfig = matchConfig,
                modelRegistry = vm.modelRegistry,
                onUpdateMatchConfig = vm::updateMatchConfig,
                onPlay = vm::startGame,
                onOpenSettings = { vm.openSettings(fromGame = false) },
                onOpenControls = { vm.openControlEditor(fromGame = false) }
            )

            AppScreen.Playing -> GameplayScreen(
                engine = vm.engine,
                modelRegistry = vm.modelRegistry,
                settings = settings,
                isPaused = isPaused,
                onSetPaused = vm::setPaused,
                onRestart = vm::restartMatch,
                onOpenSettings = { vm.openSettings(fromGame = true) },
                onOpenControlEditor = { vm.openControlEditor(fromGame = true) },
                onExitToMenu = vm::exitToMainMenu
            )

            is AppScreen.Settings -> SettingsScreen(
                settings = settings,
                onApplyPreset = vm::applyGraphicsPreset,
                onUpdateGraphics = vm::updateGraphics,
                onUpdateSensitivity = vm::updateSensitivity,
                onUpdateAudio = vm::updateAudio,
                onOpenControlEditor = { vm.openControlEditor(fromGame = current.returnToGame) },
                onResetAll = vm::resetAllSettings,
                onBack = { vm.navigateBackToOrigin(current.returnToGame) }
            )

            is AppScreen.ControlEditor -> ControlEditorScreen(
                initialLayout = settings.controlLayout,
                onSave = { layout ->
                    vm.updateControlLayout(layout)
                    vm.navigateBackToOrigin(current.returnToGame)
                },
                onCancel = { vm.navigateBackToOrigin(current.returnToGame) }
            )
        }

        val veil = transitionAlpha.value
        if (veil > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ComposeColor(0xFF070B12).copy(alpha = veil))
            )
        }
    }
}
