package com.example

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.ArenaMapId
import com.example.model.GameMode
import com.example.model.MatchConfig
import com.example.model.TeamSide
import com.example.online.SupabaseAuthManager
import com.example.online.SupabaseOnlineRepository
import com.example.online.SupabaseSession
import com.example.ui.AppScreen
import com.example.ui.AuthScreen
import com.example.ui.ControlEditorScreen
import com.example.ui.FriendsOverlay
import com.example.ui.GameViewModel
import com.example.ui.GameplayScreen
import com.example.ui.MainMenuScreen
import com.example.ui.MultiplayerLobbyScreen
import com.example.ui.OnlineUiState
import com.example.ui.OnlineViewModel
import com.example.ui.ProfileCustomizationOverlay
import com.example.ui.SettingsScreen
import com.example.ui.theme.ResponsiveUiProvider
import com.example.ui.theme.VanguardTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        applyImmersiveFullscreen()
        setContent {
            VanguardTheme {
                ResponsiveUiProvider {
                    VanguardAuthRoot()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveFullscreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveFullscreen()
        }
    }

    private fun applyImmersiveFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
private fun VanguardAuthRoot() {
    val context = LocalContext.current
    val authManager = remember(context.applicationContext) {
        SupabaseAuthManager(context.applicationContext)
    }
    val session by authManager.session.collectAsStateWithLifecycle()

    val activeSession = session
    if (activeSession == null) {
        AuthScreen(
            authManager = authManager,
            onAuthSuccess = { /* session StateFlow updates automatically */ }
        )
    } else {
        // Key strictly by userId so every ViewModel and cloud polling loop resets cleanly on account switch
        key(activeSession.userId) {
            VanguardRtxApp(
                session = activeSession,
                authManager = authManager
            )
        }
    }
}

@Composable
fun VanguardRtxApp(
    session: SupabaseSession,
    authManager: SupabaseAuthManager,
    vm: GameViewModel = viewModel()
) {
    val scope = rememberCoroutineScope()
    val currentUserId = session.userId
    val onlineRepo = remember(authManager) { SupabaseOnlineRepository(authManager) }
    val onlineVm: OnlineViewModel = viewModel(
        key = currentUserId,
        factory = OnlineViewModel.factory(currentUserId, session.loginName, onlineRepo, authManager)
    )

    // Wire zero-latency WebSocket broadcast sinks directly into GameEngine during matches
    DisposableEffect(currentUserId, vm.engine, onlineVm) {
        onlineVm.attachDirectMatchSinks(
            onRemoteState = { st ->
                if (vm.engine.matchConfig.isMultiplayer) {
                    vm.engine.applyRemoteSingleState(currentUserId, st)
                }
            },
            onBombSync = { ev ->
                if (vm.engine.matchConfig.isMultiplayer) {
                    vm.engine.enqueueRemoteBombSync(ev)
                }
            }
        )
        onDispose {
            onlineVm.attachDirectMatchSinks(null, null)
        }
    }

    val screen by vm.screen.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val matchConfig by vm.matchConfig.collectAsStateWithLifecycle()
    val isPaused by vm.isPauseOverlayOpen.collectAsStateWithLifecycle()

    val profileState by onlineVm.profileState.collectAsStateWithLifecycle()
    val playerProfile = (profileState as? OnlineUiState.Success)?.data
    val friendsState by onlineVm.friendsState.collectAsStateWithLifecycle()
    val friendsList = (friendsState as? OnlineUiState.Success)?.data.orEmpty()
    val incomingRequests by onlineVm.incomingRequests.collectAsStateWithLifecycle()
    val outgoingRequests by onlineVm.outgoingRequests.collectAsStateWithLifecycle()
    val incomingInvites by onlineVm.incomingInvites.collectAsStateWithLifecycle()
    val searchResult by onlineVm.searchResult.collectAsStateWithLifecycle()
    val isSearching by onlineVm.isSearching.collectAsStateWithLifecycle()
    val isSavingProfile by onlineVm.isSavingProfile.collectAsStateWithLifecycle()
    val statusBanner by onlineVm.statusBanner.collectAsStateWithLifecycle()
    val activeLobbyId by onlineVm.activeLobbyId.collectAsStateWithLifecycle()
    val activeLobby by onlineVm.activeLobby.collectAsStateWithLifecycle()
    val lobbyMemberStates by onlineVm.lobbyMemberStates.collectAsStateWithLifecycle()
    val pingMs by onlineVm.pingMs.collectAsStateWithLifecycle()

    var isFriendsOpen by rememberSaveable { mutableStateOf(false) }
    var isProfileOpen by rememberSaveable { mutableStateOf(false) }

    // Automatically launch match for ALL players in the cloud lobby when Host starts the match
    LaunchedEffect(activeLobby?.status, screen) {
        val lobby = activeLobby ?: return@LaunchedEffect
        if (lobby.status == "playing" && screen == AppScreen.MultiplayerLobby) {
            val myState = lobbyMemberStates.firstOrNull { it.userId == currentUserId }
            val mySide = if (myState?.team == TeamSide.CT.name) TeamSide.CT else TeamSide.T
            val map = ArenaMapId.entries.firstOrNull { it.name == lobby.mapId } ?: ArenaMapId.MIRAGE
            val mode = GameMode.entries.firstOrNull { it.name == lobby.mode } ?: GameMode.BOMB_DEFUSAL
            val mpConfig = MatchConfig(
                mode = mode,
                map = map,
                playerSide = mySide,
                playerTeamSize = if (lobby.allowBots) 5 else lobbyMemberStates.count { it.team == mySide.name }.coerceAtLeast(1),
                enemyTeamSize = if (lobby.allowBots) 5 else lobbyMemberStates.count { it.team != mySide.name }.coerceAtLeast(1),
                roundsToWin = lobby.roundsToWin,
                isMultiplayer = true,
                lobbyId = lobby.id,
                allowBots = lobby.allowBots,
                localUserId = currentUserId,
                localDisplayName = playerProfile?.displayName ?: session.loginName,
                localShortId = playerProfile?.shortId ?: ""
            )
            vm.startMultiplayerMatch(mpConfig)
        }
    }

    val transitionAlpha = remember { Animatable(0f) }
    LaunchedEffect(screen) {
        transitionAlpha.snapTo(0.85f)
        transitionAlpha.animateTo(0f, animationSpec = tween(durationMillis = 240))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val current = screen) {
            AppScreen.Menu -> MainMenuScreen(
                settings = settings,
                stats = stats,
                matchConfig = matchConfig,
                modelRegistry = vm.modelRegistry,
                profileState = profileState,
                fallbackLoginName = session.loginName,
                pendingFriendsCount = incomingRequests.size,
                incomingInvites = incomingInvites,
                onUpdateMatchConfig = vm::updateMatchConfig,
                onPlay = {
                    val p = playerProfile
                    if (p != null) {
                        vm.updateMatchConfig {
                            it.copy(
                                localUserId = p.userId,
                                localDisplayName = p.displayName,
                                localShortId = p.shortId
                            )
                        }
                    }
                    vm.startGame()
                },
                onCreateLobby = {
                    onlineVm.createMultiplayerLobby(
                        map = matchConfig.map,
                        mode = matchConfig.mode,
                        roundsToWin = matchConfig.roundsToWin,
                        allowBots = false, // Default 1v1 / friends-only; Host can toggle Bots ON inside lobby
                        team = matchConfig.playerSide,
                        onCreated = { vm.openMultiplayerLobby() }
                    )
                },
                onOpenFriends = {
                    isProfileOpen = false
                    isFriendsOpen = true
                    onlineVm.refreshSocialData()
                },
                onOpenProfile = {
                    isFriendsOpen = false
                    isProfileOpen = true
                    if (profileState is OnlineUiState.Error) {
                        onlineVm.retryLoadProfile()
                    }
                },
                onAcceptInvite = { invite ->
                    onlineVm.acceptLobbyInvite(invite, matchConfig.playerSide) {
                        vm.openMultiplayerLobby()
                    }
                },
                onDeclineInvite = onlineVm::declineLobbyInvite,
                onOpenSettings = {
                    isFriendsOpen = false
                    isProfileOpen = false
                    vm.openSettings(fromGame = false)
                },
                onOpenControls = {
                    isFriendsOpen = false
                    isProfileOpen = false
                    vm.openControlEditor(fromGame = false)
                }
            )

            AppScreen.MultiplayerLobby -> {
                val lobby = activeLobby
                if (lobby != null) {
                    MultiplayerLobbyScreen(
                        currentUserId = currentUserId,
                        lobby = lobby,
                        memberStates = lobbyMemberStates,
                        friends = friendsList,
                        statusBanner = statusBanner,
                        onSwitchTeam = onlineVm::switchMyTeam,
                        onUpdateConfig = onlineVm::updateActiveLobbyConfig,
                        onInviteFriend = onlineVm::inviteFriendToActiveLobby,
                        onStartMatch = onlineVm::startActiveLobbyMatch,
                        onLeaveLobby = {
                            onlineVm.leaveActiveLobby {
                                vm.exitToMainMenu()
                            }
                        }
                    )
                } else {
                    LaunchedEffect(Unit) {
                        vm.exitToMainMenu()
                    }
                }
            }

            AppScreen.Playing -> GameplayScreen(
                engine = vm.engine,
                modelRegistry = vm.modelRegistry,
                settings = settings,
                isPaused = isPaused,
                currentUserId = currentUserId,
                activeLobbyId = activeLobbyId,
                remoteMemberStates = lobbyMemberStates,
                pingMs = pingMs,
                onPublishLiveState = onlineVm::publishMatchTick,
                onPublishBombSync = onlineVm::publishBombSync,
                onSetPaused = vm::setPaused,
                onRestart = vm::restartMatch,
                onOpenSettings = { vm.openSettings(fromGame = true) },
                onOpenControlEditor = { vm.openControlEditor(fromGame = true) },
                onExitToMenu = {
                    if (!activeLobbyId.isNullOrBlank()) {
                        onlineVm.leaveActiveLobby()
                    }
                    vm.exitToMainMenu()
                }
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

        // Cloud Friends & 6-Digit Player ID Overlay
        AnimatedVisibility(
            visible = isFriendsOpen && screen == AppScreen.Menu,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(160))
        ) {
            FriendsOverlay(
                myProfile = playerProfile,
                friendsState = friendsState,
                incomingRequests = incomingRequests,
                outgoingRequests = outgoingRequests,
                searchResult = searchResult,
                isSearching = isSearching,
                statusBanner = statusBanner,
                onSearchById = onlineVm::searchByShortId,
                onSendFriendRequest = onlineVm::sendFriendRequest,
                onRespondRequest = onlineVm::respondFriendRequest,
                onInviteFriend = { friend ->
                    isFriendsOpen = false
                    if (!activeLobbyId.isNullOrBlank() && activeLobby != null) {
                        onlineVm.inviteFriendToActiveLobby(friend)
                        vm.openMultiplayerLobby()
                    } else {
                        onlineVm.createMultiplayerLobby(
                            map = matchConfig.map,
                            mode = matchConfig.mode,
                            roundsToWin = matchConfig.roundsToWin,
                            allowBots = false,
                            team = matchConfig.playerSide,
                            onCreated = {
                                onlineVm.inviteFriendToActiveLobby(friend)
                                vm.openMultiplayerLobby()
                            }
                        )
                    }
                },
                onRemoveFriend = onlineVm::removeFriend,
                onClearBanner = onlineVm::clearBanner,
                onClose = {
                    isFriendsOpen = false
                    onlineVm.clearSearch()
                    onlineVm.clearBanner()
                }
            )
        }

        // Profile Customization Overlay (ID copy/retry, Nickname editor, Gallery Photo Picker + 8 Tactical Avatars, compact Sign Out)
        AnimatedVisibility(
            visible = isProfileOpen && screen == AppScreen.Menu,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(160))
        ) {
            ProfileCustomizationOverlay(
                profileState = profileState,
                fallbackLoginName = session.loginName,
                isSaving = isSavingProfile,
                statusBanner = statusBanner,
                onRetryLoadProfile = onlineVm::retryLoadProfile,
                onSaveProfile = onlineVm::saveProfileCustomization,
                onSignOut = {
                    isProfileOpen = false
                    scope.launch {
                        onlineVm.setOfflineAndLeaveBeforeSignOut()
                        authManager.logout()
                    }
                },
                onClose = {
                    isProfileOpen = false
                    onlineVm.clearBanner()
                }
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
