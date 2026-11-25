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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.ArenaMapId
import com.example.model.GameMode
import com.example.model.MatchConfig
import com.example.model.TeamSide
import com.example.online.SupabaseAuthManager
import com.example.online.SupabaseOnlineRepository
import com.example.online.SupabaseSession
import com.example.ui.AdminPanelOverlay
import com.example.ui.AppScreen
import com.example.ui.AuthScreen
import com.example.ui.BannedAccountScreen
import com.example.ui.ControlEditorScreen
import com.example.ui.DeviceBannedScreen
import com.example.ui.FriendsOverlay
import com.example.ui.GameViewModel
import com.example.ui.GameplayScreen
import com.example.ui.InventoryAndCasesOverlay
import com.example.ui.MainMenuScreen
import com.example.ui.MultiplayerLobbyScreen
import com.example.ui.OnlineUiState
import com.example.ui.OnlineViewModel
import com.example.ui.ProfileCustomizationOverlay
import com.example.ui.SettingsScreen
import com.example.ui.UpdateRequiredScreen
import com.example.ui.theme.ResponsiveUiProvider
import com.example.ui.theme.TacticalAmber
import com.example.ui.theme.TacticalSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.ThreatCrimson
import com.example.ui.theme.VanguardTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private var currentTargetFps: Int = 120
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
                    VanguardAuthRoot(
                        onTargetFpsChanged = { fps -> applyPreferredRefreshRate(fps) }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveFullscreen()
        applyPreferredRefreshRate(currentTargetFps)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveFullscreen()
            applyPreferredRefreshRate(currentTargetFps)
        }
    }

    /**
     * Requests 60 / 90 / 120+ Hz display refresh mode across all Android devices (API 24..36).
     */
    fun applyPreferredRefreshRate(targetFps: Int) {
        currentTargetFps = targetFps
        runCatching {
            val activeDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            }
            val attrs = window.attributes
            attrs.preferredRefreshRate = targetFps.toFloat()

            if (activeDisplay != null) {
                val activeMode = activeDisplay.mode
                val sameResModes = activeDisplay.supportedModes.filter {
                    it.physicalWidth == activeMode.physicalWidth &&
                        it.physicalHeight == activeMode.physicalHeight
                }.ifEmpty { activeDisplay.supportedModes.toList() }

                val chosenMode = if (targetFps >= 120) {
                    sameResModes.maxByOrNull { it.refreshRate }
                } else {
                    sameResModes.minByOrNull { abs(it.refreshRate - targetFps.toFloat()) }
                }
                if (chosenMode != null) {
                    attrs.preferredDisplayModeId = chosenMode.modeId
                }
            }
            window.attributes = attrs
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
private fun VanguardAuthRoot(
    onTargetFpsChanged: (Int) -> Unit
) {
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
                authManager = authManager,
                onTargetFpsChanged = onTargetFpsChanged
            )
        }
    }
}

@Composable
fun VanguardRtxApp(
    session: SupabaseSession,
    authManager: SupabaseAuthManager,
    onTargetFpsChanged: (Int) -> Unit,
    vm: GameViewModel = viewModel()
) {
    val scope = rememberCoroutineScope()
    val currentUserId = session.userId
    val onlineRepo = remember(authManager) { SupabaseOnlineRepository(authManager) }
    val onlineVm: OnlineViewModel = viewModel(
        key = currentUserId,
        factory = OnlineViewModel.factory(currentUserId, session.loginName, onlineRepo, authManager)
    )

    val lifecycleOwner = LocalLifecycleOwner.current

    // Accurate Online/Offline presence: immediately set offline when app goes to background or closes
    DisposableEffect(lifecycleOwner, onlineVm) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> onlineVm.setAppForeground(true)
                Lifecycle.Event.ON_STOP -> onlineVm.setAppForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onlineVm.setAppForeground(true)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onlineVm.setAppForeground(false)
        }
    }

    // Wire zero-latency WebSocket broadcast sinks & kill EXP rewards directly into GameEngine
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
            },
            onMatchSync = { ev ->
                if (vm.engine.matchConfig.isMultiplayer) {
                    vm.engine.enqueueRemoteMatchSync(ev)
                }
            },
            onKillFeed = { ev ->
                if (vm.engine.matchConfig.isMultiplayer) {
                    vm.engine.enqueueRemoteKillFeed(ev)
                }
            }
        )
        vm.engine.onTeamChanged = { side ->
            if (vm.engine.matchConfig.isMultiplayer) {
                onlineVm.switchMyTeam(side)
            }
        }
        vm.engine.onPlayerKillAwarded = { isHeadshot, _ ->
            onlineVm.onLocalKillEarned(headshot = isHeadshot) { toast ->
                vm.engine.showXpToast(toast)
            }
        }
        onDispose {
            onlineVm.attachDirectMatchSinks(null, null, null, null)
            vm.engine.onTeamChanged = null
            vm.engine.onPlayerKillAwarded = null
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
    val bannedAccounts by onlineVm.bannedAccounts.collectAsStateWithLifecycle()
    val bannedDevices by onlineVm.bannedDevices.collectAsStateWithLifecycle()
    val clientAccessStatus by onlineVm.clientAccessStatus.collectAsStateWithLifecycle()
    val adminBusy by onlineVm.adminBusy.collectAsStateWithLifecycle()
    val adminStatus by onlineVm.adminStatus.collectAsStateWithLifecycle()
    val kickedMessage by onlineVm.kickedMessage.collectAsStateWithLifecycle()
    val localBanNotice by onlineVm.localBanNotice.collectAsStateWithLifecycle()
    val isOpeningCase by onlineVm.isOpeningCase.collectAsStateWithLifecycle()
    val isUpdatingSkin by onlineVm.isUpdatingSkin.collectAsStateWithLifecycle()

    val isAdmin = playerProfile?.isAdmin == true
    val isBanned = (playerProfile?.banned == true) || !localBanNotice.isNullOrBlank()
    val isLockedOut = isBanned || clientAccessStatus.updateRequired || clientAccessStatus.deviceBanned

    var isFriendsOpen by rememberSaveable { mutableStateOf(false) }
    var isProfileOpen by rememberSaveable { mutableStateOf(false) }
    var isAdminPanelOpen by rememberSaveable { mutableStateOf(false) }
    var inventoryOverlayTab by rememberSaveable { mutableStateOf<Int?>(null) }
    var espUiEnabled by rememberSaveable { mutableStateOf(false) }
    var autoAimUiEnabled by rememberSaveable { mutableStateOf(false) }

    // Apply target FPS (60 / 90 / 120 FPS) to Android display mode immediately on change
    LaunchedEffect(settings.graphics.targetFps) {
        onTargetFpsChanged(settings.graphics.targetFps.fps)
    }

    // Keep engine's local level & equipped 3D weapon skins synced live with cloud profile
    LaunchedEffect(playerProfile?.level, playerProfile?.equippedSkins) {
        if (playerProfile != null) {
            vm.engine.localLevel = playerProfile.level.coerceAtLeast(1)
            vm.engine.localEquippedSkins = playerProfile.equippedSkins
        }
    }

    // Keep engine admin flags synced and strictly disabled for non-admin accounts
    LaunchedEffect(isAdmin, espUiEnabled, autoAimUiEnabled) {
        vm.engine.adminEspEnabled = isAdmin && espUiEnabled
        vm.engine.adminAutoAimEnabled = isAdmin && autoAimUiEnabled
        if (!isAdmin) {
            isAdminPanelOpen = false
        }
    }

    // Force exit to menu immediately if kicked by admin during match or lobby
    LaunchedEffect(kickedMessage) {
        if (!kickedMessage.isNullOrBlank() && (screen == AppScreen.Playing || screen == AppScreen.MultiplayerLobby)) {
            vm.exitToMainMenu()
        }
    }

    // Force exit and lock out immediately if account/device is banned or update is required
    LaunchedEffect(isLockedOut) {
        if (isLockedOut) {
            isFriendsOpen = false
            isProfileOpen = false
            isAdminPanelOpen = false
            inventoryOverlayTab = null
            if (screen != AppScreen.Menu) {
                vm.exitToMainMenu()
            }
        }
    }

    if (clientAccessStatus.updateRequired) {
        UpdateRequiredScreen(
            accessStatus = clientAccessStatus,
            onRetry = onlineVm::checkClientAccessNow
        )
        return
    }

    if (clientAccessStatus.deviceBanned) {
        DeviceBannedScreen(
            banReason = clientAccessStatus.banReason.ifBlank { localBanNotice.orEmpty() },
            onRetry = onlineVm::checkClientAccessNow,
            onSignOut = {
                scope.launch {
                    onlineVm.setOfflineAndLeaveBeforeSignOut()
                    authManager.logout()
                }
            }
        )
        return
    }

    if (isBanned) {
        BannedAccountScreen(
            shortId = playerProfile?.shortId.orEmpty(),
            banReason = playerProfile?.banReason?.ifBlank { localBanNotice.orEmpty() }
                ?: localBanNotice.orEmpty(),
            onCheckStatus = onlineVm::refreshBanStatusNow,
            onSignOut = {
                scope.launch {
                    onlineVm.setOfflineAndLeaveBeforeSignOut()
                    authManager.logout()
                }
            }
        )
        return
    }

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
                isMatchHost = lobby.hostUid == currentUserId,
                localUserId = currentUserId,
                localDisplayName = playerProfile?.displayName ?: session.loginName,
                localShortId = playerProfile?.shortId ?: "",
                localLevel = playerProfile?.level ?: 1,
                localEquippedSkins = playerProfile?.equippedSkins ?: emptyMap()
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
                isAdmin = isAdmin,
                onOpenAdminPanel = {
                    if (isAdmin) {
                        isFriendsOpen = false
                        isProfileOpen = false
                        inventoryOverlayTab = null
                        isAdminPanelOpen = true
                        onlineVm.refreshBannedAccounts()
                    }
                },
                onOpenInventory = {
                    isFriendsOpen = false
                    isProfileOpen = false
                    isAdminPanelOpen = false
                    inventoryOverlayTab = 0
                },
                onOpenCases = {
                    isFriendsOpen = false
                    isProfileOpen = false
                    isAdminPanelOpen = false
                    inventoryOverlayTab = 1
                },
                onUpdateMatchConfig = vm::updateMatchConfig,
                onPlay = {
                    val p = playerProfile
                    vm.startGame(
                        localUserId = p?.userId ?: currentUserId,
                        localDisplayName = p?.displayName ?: session.loginName,
                        localShortId = p?.shortId ?: "",
                        localLevel = p?.level ?: 1,
                        localEquippedSkins = p?.equippedSkins ?: emptyMap()
                    )
                },
                onCreateLobby = {
                    inventoryOverlayTab = null
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
                    inventoryOverlayTab = null
                    isFriendsOpen = true
                    onlineVm.refreshSocialData()
                },
                onOpenProfile = {
                    isFriendsOpen = false
                    inventoryOverlayTab = null
                    isProfileOpen = true
                    if (profileState is OnlineUiState.Error) {
                        onlineVm.retryLoadProfile()
                    }
                },
                onAcceptInvite = { invite ->
                    inventoryOverlayTab = null
                    onlineVm.acceptLobbyInvite(invite, matchConfig.playerSide) {
                        vm.openMultiplayerLobby()
                    }
                },
                onDeclineInvite = onlineVm::declineLobbyInvite,
                onOpenSettings = {
                    isFriendsOpen = false
                    isProfileOpen = false
                    inventoryOverlayTab = null
                    vm.openSettings(fromGame = false)
                },
                onOpenControls = {
                    isFriendsOpen = false
                    isProfileOpen = false
                    inventoryOverlayTab = null
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
                onPublishMatchSync = onlineVm::publishMatchSync,
                onPublishKillFeed = onlineVm::publishKillFeedSync,
                isAdmin = isAdmin,
                onOpenAdminPanel = {
                    if (isAdmin) {
                        isAdminPanelOpen = true
                        onlineVm.refreshBannedAccounts()
                    }
                },
                onSetPaused = vm::setPaused,
                onRestart = vm::restartMatch,
                onOpenSettings = { vm.openSettings(fromGame = true) },
                onOpenControlEditor = { vm.openControlEditor(fromGame = true) },
                onExitToMenu = {
                    isAdminPanelOpen = false
                    if (!activeLobbyId.isNullOrBlank()) {
                        onlineVm.leaveActiveLobby()
                    }
                    vm.exitToMainMenu()
                }
            )

            is AppScreen.Settings -> SettingsScreen(
                settings = settings,
                isAdmin = isAdmin,
                onOpenAdminPanel = {
                    if (isAdmin) {
                        isAdminPanelOpen = true
                        onlineVm.refreshBannedAccounts()
                    }
                },
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

        // Skin Inventory & 100-Coin Cases Overlay
        AnimatedVisibility(
            visible = inventoryOverlayTab != null && screen == AppScreen.Menu,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(160))
        ) {
            InventoryAndCasesOverlay(
                initialTab = inventoryOverlayTab ?: 0,
                profile = playerProfile,
                isOpeningCase = isOpeningCase,
                isUpdatingSkin = isUpdatingSkin,
                statusBanner = statusBanner,
                onOpenCase = onlineVm::openSkinCase,
                onEquipSkin = onlineVm::equipWeaponSkin,
                onClearBanner = onlineVm::clearBanner,
                onClose = {
                    inventoryOverlayTab = null
                    onlineVm.clearBanner()
                }
            )
        }

        // Exclusive Admin Panel Overlay (strictly gated to player with ID "developer")
        AnimatedVisibility(
            visible = isAdmin && isAdminPanelOpen,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(140))
        ) {
            AdminPanelOverlay(
                inMatch = screen == AppScreen.Playing,
                currentUserId = currentUserId,
                myProfile = playerProfile,
                espEnabled = espUiEnabled,
                autoAimEnabled = autoAimUiEnabled,
                onToggleEsp = { enabled ->
                    espUiEnabled = enabled
                    vm.engine.adminEspEnabled = isAdmin && enabled
                },
                onToggleAutoAim = { enabled ->
                    autoAimUiEnabled = enabled
                    vm.engine.adminAutoAimEnabled = isAdmin && enabled
                },
                lobbyMembers = lobbyMemberStates,
                scoreboardRows = vm.engine.latestHud.scoreboardRows,
                bannedAccounts = bannedAccounts,
                bannedDevices = bannedDevices,
                isBusy = adminBusy,
                statusMessage = adminStatus,
                onRefreshBanned = onlineVm::refreshBannedAccounts,
                onKickHuman = onlineVm::adminKickPlayer,
                onKickBot = { botId ->
                    vm.engine.requestKickBot(botId)
                },
                onBanAccount = onlineVm::adminBanAccount,
                onUnbanAccount = onlineVm::adminUnbanAccount,
                onSetDeviceBanByAccount = { shortId, banned ->
                    onlineVm.adminSetDeviceBanByAccount(shortId, banned)
                },
                onUnbanDeviceHash = onlineVm::adminUnbanDeviceHash,
                onGrantXp = onlineVm::adminGrantXp,
                onGrantSkins = onlineVm::adminGrantSkins,
                onClearStatus = onlineVm::clearAdminStatus,
                onClose = {
                    isAdminPanelOpen = false
                    onlineVm.clearAdminStatus()
                }
            )
        }

        // Kicked from match notice banner in Main Menu
        if (!kickedMessage.isNullOrBlank() && screen == AppScreen.Menu) {
            Surface(
                color = TacticalSurface,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.5.dp, ThreatCrimson),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(bottom = 14.dp, start = 16.dp, end = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = kickedMessage.orEmpty(),
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onlineVm::clearKickedMessage) {
                        Text("ОК", color = TacticalAmber, fontWeight = FontWeight.Black)
                    }
                }
            }
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
