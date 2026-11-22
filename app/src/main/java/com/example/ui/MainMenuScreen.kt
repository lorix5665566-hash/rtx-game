package com.example.ui

import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.engine.FilamentLobbyController
import com.example.model.ArenaMapId
import com.example.model.GameMode
import com.example.model.GameSettings
import com.example.model.MatchConfig
import com.example.model.ModelAssetRegistry
import com.example.model.PlayerStats
import com.example.model.TeamSide
import com.example.online.LobbyInvite
import com.example.online.PlayerProfile
import com.example.ui.theme.BorderSteel
import com.example.ui.theme.ElevatedGunmetal
import com.example.ui.theme.IceCyan
import com.example.ui.theme.LocalUiMetrics
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.RtxEmerald
import com.example.ui.theme.TacticalAmber
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.ThreatCrimson

private enum class MenuTab { HOME, PLAY }

@Composable
fun MainMenuScreen(
    settings: GameSettings,
    stats: PlayerStats,
    matchConfig: MatchConfig,
    modelRegistry: ModelAssetRegistry,
    profileState: OnlineUiState<PlayerProfile>,
    fallbackLoginName: String,
    pendingFriendsCount: Int,
    incomingInvites: List<LobbyInvite>,
    onUpdateMatchConfig: ((MatchConfig) -> MatchConfig) -> Unit,
    onPlay: () -> Unit,
    onCreateLobby: () -> Unit,
    onOpenFriends: () -> Unit,
    onOpenProfile: () -> Unit,
    onAcceptInvite: (LobbyInvite) -> Unit,
    onDeclineInvite: (LobbyInvite) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenControls: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val m = LocalUiMetrics.current
    val latestGraphics by rememberUpdatedState(settings.graphics)
    val playerProfile = (profileState as? OnlineUiState.Success)?.data
    val isProfileError = profileState is OnlineUiState.Error

    var activeTab by remember { mutableStateOf(MenuTab.HOME) }

    val lobbyController = remember(modelRegistry) {
        FilamentLobbyController(
            context = context,
            registry = modelRegistry,
            getGraphics = { latestGraphics }
        )
    }

    LaunchedEffect(matchConfig.playerSide) {
        lobbyController.showcaseSide = matchConfig.playerSide
    }

    LaunchedEffect(matchConfig.map) {
        lobbyController.showcaseMap = matchConfig.map
    }

    val surfaceView = remember(lobbyController) {
        SurfaceView(context).also { sv ->
            lobbyController.attachTo(sv)
        }
    }

    DisposableEffect(lifecycleOwner, lobbyController) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> lobbyController.start()
                Lifecycle.Event.ON_PAUSE -> lobbyController.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        lobbyController.start()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            lobbyController.destroy()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
    ) {
        // 1. Live Google Filament PBR 3D CS2 Mirage Courtyard + Rigged Interactive 3D Character (T / CT)
        AndroidView(
            factory = { surfaceView },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, dragAmount ->
                        change.consume()
                        lobbyController.addSpinDrag(dragAmount)
                    }
                }
        )

        // Atmospheric top & bottom vignette leaving center sunlit Mirage courtyard & operative crisp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(m.s(76f))
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xB8080D14), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(m.s(86f))
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xC2080D14))
                    )
                )
        )

        // 2. Crisp Adaptive Frosted Top Navigation Bar (top + horizontal cutouts only, no bottom-inset bloat)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xB80C121C), Color(0xA4101826), Color(0xB80C121C))
                    )
                )
                .border(0.6.dp, Color.White.copy(alpha = 0.14f))
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                )
                .padding(horizontal = m.s(10f), vertical = m.s(2f)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Top-Left: Sleek Settings Button (48dp touch area) + Faction Switcher (T / CT)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(m.s(6f))
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(m.s(8f)))
                        .clickable {
                            activeTab = MenuTab.HOME
                            onOpenSettings()
                        }
                        .testTag("menu_settings_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(m.s(32f).coerceAtLeast(30.dp))
                            .clip(RoundedCornerShape(m.s(7f)))
                            .background(Color(0xB8111A29))
                            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(m.s(7f))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Настройки",
                            tint = TextPrimary,
                            modifier = Modifier.size(m.s(18f))
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(m.s(6f)))
                        .background(Color(0xA60B1018))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(m.s(6f)))
                        .padding(m.s(2f)),
                    horizontalArrangement = Arrangement.spacedBy(m.s(2f))
                ) {
                    FactionMiniChip(
                        label = "T",
                        selected = matchConfig.playerSide == TeamSide.T,
                        accent = TacticalAmber,
                        onClick = { onUpdateMatchConfig { it.copy(playerSide = TeamSide.T) } },
                        tag = "menu_side_t"
                    )
                    FactionMiniChip(
                        label = "CT",
                        selected = matchConfig.playerSide == TeamSide.CT,
                        accent = IceCyan,
                        onClick = { onUpdateMatchConfig { it.copy(playerSide = TeamSide.CT) } },
                        tag = "menu_side_ct"
                    )
                }
            }

            // Center CS2 Clean Navigation Tabs (horizontalScroll safety for ultra-narrow phones)
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = m.s(6f)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(m.s(4f))
            ) {
                Cs2NavTab(
                    label = "ГЛАВНАЯ",
                    selected = activeTab == MenuTab.HOME,
                    onClick = { activeTab = MenuTab.HOME },
                    tag = "nav_home"
                )
                Cs2NavTab(
                    label = "ИГРАТЬ",
                    selected = activeTab == MenuTab.PLAY,
                    highlight = true,
                    onClick = { activeTab = if (activeTab == MenuTab.PLAY) MenuTab.HOME else MenuTab.PLAY },
                    tag = "nav_play"
                )
                Cs2NavTab(
                    label = "ЛОББИ",
                    selected = false,
                    accentOverride = IceCyan,
                    onClick = onCreateLobby,
                    tag = "nav_lobby"
                )
                Cs2NavTab(
                    label = if (pendingFriendsCount > 0) "ДРУЗЬЯ ($pendingFriendsCount)" else "ДРУЗЬЯ",
                    selected = false,
                    accentOverride = if (pendingFriendsCount > 0) TacticalAmber else null,
                    onClick = onOpenFriends,
                    tag = "nav_friends"
                )
                Cs2NavTab(
                    label = "ПРОФИЛЬ",
                    selected = false,
                    accentOverride = TacticalAmber,
                    onClick = onOpenProfile,
                    tag = "nav_profile"
                )
                Cs2NavTab(
                    label = "УПРАВЛЕНИЕ",
                    selected = false,
                    onClick = onOpenControls,
                    tag = "nav_controls"
                )
            }

            // Top-Right Avatar + Nickname + Unique 6-Digit Human Player ID Pill (opens Profile Customization)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(m.s(6f)),
                modifier = Modifier
                    .clip(RoundedCornerShape(m.s(8f)))
                    .background(Color(0xA60A1019))
                    .border(
                        1.dp,
                        if (isProfileError) ThreatCrimson.copy(alpha = 0.70f) else TacticalAmber.copy(alpha = 0.50f),
                        RoundedCornerShape(m.s(8f))
                    )
                    .clickable { onOpenProfile() }
                    .padding(horizontal = m.s(8f), vertical = m.s(4f))
                    .testTag("menu_player_id_badge")
            ) {
                if (playerProfile != null) {
                    PlayerAvatar(
                        avatarData = playerProfile.avatarData,
                        size = m.s(25f),
                        borderColor = RtxEmerald,
                        borderWidth = 1.2.dp
                    )
                }
                Column {
                    Text(
                        text = playerProfile?.displayName ?: fallbackLoginName.ifBlank { "Игрок" },
                        fontSize = m.sp(9.8f),
                        fontWeight = FontWeight.Black,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            playerProfile != null -> "ID: ${playerProfile.shortId}"
                            isProfileError -> "ID: ошибка • открыть"
                            else -> "ID: загрузка..."
                        },
                        fontSize = m.sp(8.6f),
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isProfileError) Color(0xFFFF9E9E) else TacticalAmber
                    )
                }
            }
        }

        // Live Incoming Cloud Lobby Invite Banner (with sender Avatar + Name + ID)
        val activeInvite = incomingInvites.firstOrNull()
        AnimatedVisibility(
            visible = activeInvite != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(top = m.s(54f), start = m.s(16f), end = m.s(16f))
        ) {
            if (activeInvite != null) {
                Surface(
                    color = Color(0xEC0D1626),
                    shape = CutCornerShape(topStart = m.s(10f), bottomEnd = m.s(10f)),
                    border = BorderStroke(1.3.dp, IceCyan.copy(alpha = 0.75f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = m.s(12f), vertical = m.s(7f)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(m.s(10f))
                    ) {
                        PlayerAvatar(
                            avatarData = activeInvite.fromAvatarData,
                            size = m.s(34f),
                            borderColor = IceCyan
                        )
                        Column {
                            Text(
                                text = "ПРИГЛАШЕНИЕ В ЛОББИ 1v1 ОТ ${activeInvite.fromName.uppercase()} (ID ${activeInvite.fromShortId})",
                                fontSize = m.sp(10.5f),
                                fontWeight = FontWeight.Black,
                                color = IceCyan
                            )
                            Text(
                                text = "Карта: ${activeInvite.mapId}  •  Нажмите «ПРИНЯТЬ» для входа в лобби",
                                fontSize = m.sp(9f),
                                color = TextSecondary
                            )
                        }
                        Button(
                            onClick = { onAcceptInvite(activeInvite) },
                            colors = ButtonDefaults.buttonColors(containerColor = RtxEmerald, contentColor = ObsidianVoid),
                            contentPadding = PaddingValues(horizontal = m.s(12f), vertical = m.s(4f)),
                            modifier = Modifier
                                .height(m.s(32f).coerceAtLeast(48.dp))
                                .testTag("invite_accept_button")
                        ) {
                            Text("ПРИНЯТЬ", fontSize = m.sp(10f), fontWeight = FontWeight.Black)
                        }
                        Button(
                            onClick = { onDeclineInvite(activeInvite) },
                            colors = ButtonDefaults.buttonColors(containerColor = ThreatCrimson.copy(alpha = 0.25f), contentColor = Color(0xFFFFB4B4)),
                            contentPadding = PaddingValues(horizontal = m.s(10f), vertical = m.s(4f)),
                            modifier = Modifier
                                .height(m.s(32f).coerceAtLeast(48.dp))
                                .testTag("invite_decline_button")
                        ) {
                            Text("НЕТ", fontSize = m.sp(10f), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 3. HOME Minimalist Footer (Center 3D operative remains completely unobstructed)
        AnimatedVisibility(
            visible = activeTab == MenuTab.HOME,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(horizontal = m.s(18f), vertical = m.s(10f))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Bottom-Left Operative Plate
                Column(verticalArrangement = Arrangement.spacedBy(m.s(2f))) {
                    Text(
                        text = if (matchConfig.playerSide == TeamSide.T) {
                            "ТЕРРОРИСТ • ${matchConfig.map.titleRu.uppercase()}"
                        } else {
                            "СПЕЦНАЗ • ${matchConfig.map.titleRu.uppercase()}"
                        },
                        fontSize = m.sp(12.5f),
                        fontWeight = FontWeight.Black,
                        color = if (matchConfig.playerSide == TeamSide.T) TacticalAmber else IceCyan,
                        letterSpacing = m.sp(1.1f)
                    )
                    Text(
                        text = "Потяните бойца для обзора 360° • PBR Filament",
                        fontSize = m.sp(9.5f),
                        color = Color.White.copy(alpha = 0.78f)
                    )
                }

                // Bottom-Right Quick Match Summary + Launch CTA
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(m.s(10f))
                ) {
                    Surface(
                        color = Color(0xBA0C131E),
                        shape = CutCornerShape(topStart = m.s(8f), bottomEnd = m.s(8f)),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
                        modifier = Modifier
                            .clickable { activeTab = MenuTab.PLAY }
                            .testTag("menu_match_summary_pill")
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = m.s(12f), vertical = m.s(6f)),
                            verticalArrangement = Arrangement.spacedBy(m.s(1f))
                        ) {
                            Text(
                                text = "${matchConfig.map.titleRu}  •  ${matchConfig.mode.titleRu}",
                                fontSize = m.sp(11f),
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "${matchConfig.playerTeamSize} vs ${matchConfig.enemyTeamSize}  •  до ${matchConfig.roundsToWin} раундов  •  Настроить",
                                fontSize = m.sp(9.5f),
                                color = TextSecondary
                            )
                        }
                    }

                    Button(
                        onClick = onPlay,
                        shape = CutCornerShape(topStart = m.s(10f), bottomEnd = m.s(10f)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RtxEmerald,
                            contentColor = ObsidianVoid
                        ),
                        contentPadding = PaddingValues(horizontal = m.s(22f), vertical = m.s(10f)),
                        modifier = Modifier
                            .height(m.s(46f).coerceAtLeast(48.dp))
                            .testTag("menu_quick_play_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(m.s(21f))
                        )
                        Spacer(modifier = Modifier.width(m.s(5f)))
                        Text(
                            text = "ИГРАТЬ",
                            fontSize = m.sp(13f),
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }

        // 4. PLAY Match Configuration Side Drawer (Smooth spring slide-in)
        AnimatedVisibility(
            visible = activeTab == MenuTab.PLAY,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
            ) + fadeIn(animationSpec = tween(220)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(220)
            ) + fadeOut(animationSpec = tween(180)),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .safeDrawingPadding()
                .padding(top = m.s(48f), bottom = m.s(8f), end = m.s(10f))
        ) {
            MatchSetupDrawer(
                config = matchConfig,
                onUpdate = onUpdateMatchConfig,
                onStartMatch = onPlay,
                onCreateLobby = onCreateLobby,
                onClose = { activeTab = MenuTab.HOME }
            )
        }
    }
}

@Composable
private fun MatchSetupDrawer(
    config: MatchConfig,
    onUpdate: ((MatchConfig) -> MatchConfig) -> Unit,
    onStartMatch: () -> Unit,
    onCreateLobby: () -> Unit,
    onClose: () -> Unit
) {
    val m = LocalUiMetrics.current
    val drawerWidth = minOf(m.widthDp * 0.48f, m.s(395f)).coerceAtLeast(m.s(300f))

    Surface(
        color = Color(0xE60C121D),
        shape = CutCornerShape(topStart = m.s(14f), bottomEnd = m.s(14f)),
        border = BorderStroke(1.1.dp, RtxEmerald.copy(alpha = 0.50f)),
        modifier = Modifier
            .width(drawerWidth)
            .fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(m.s(12f)),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Drawer Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "НАСТРОЙКА МАТЧА",
                        fontSize = m.sp(13.5f),
                        fontWeight = FontWeight.Black,
                        color = TextPrimary
                    )
                    Text(
                        text = "Режим, карта и состав команд",
                        fontSize = m.sp(9.5f),
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(m.s(32f))
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Закрыть",
                        tint = TextSecondary,
                        modifier = Modifier.size(m.s(18f))
                    )
                }
            }

            // Scrollable Options
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = m.s(6f)),
                verticalArrangement = Arrangement.spacedBy(m.s(8f))
            ) {
                DrawerSectionLabel("РЕЖИМ ИГРЫ")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(m.s(6f))
                ) {
                    GameMode.entries.forEach { mode ->
                        DrawerChip(
                            title = mode.titleRu,
                            selected = config.mode == mode,
                            accent = RtxEmerald,
                            onClick = { onUpdate { it.copy(mode = mode) } },
                            modifier = Modifier.weight(1f),
                            tag = "mode_${mode.name.lowercase()}"
                        )
                    }
                }

                DrawerSectionLabel("КАРТА")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(m.s(6f))
                ) {
                    ArenaMapId.entries.forEach { map ->
                        DrawerChip(
                            title = map.titleRu,
                            selected = config.map == map,
                            accent = TacticalAmber,
                            onClick = { onUpdate { it.copy(map = map) } },
                            modifier = Modifier.weight(1f),
                            tag = "map_${map.name.lowercase()}"
                        )
                    }
                }

                DrawerSectionLabel("СТОРОНА (МОДЕЛЬ БОЙЦА)")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(m.s(6f))
                ) {
                    DrawerChip(
                        title = "ТЕРРОРИСТЫ (T)",
                        selected = config.playerSide == TeamSide.T,
                        accent = TacticalAmber,
                        onClick = { onUpdate { it.copy(playerSide = TeamSide.T) } },
                        modifier = Modifier.weight(1f),
                        tag = "drawer_side_t"
                    )
                    DrawerChip(
                        title = "СПЕЦНАЗ (CT)",
                        selected = config.playerSide == TeamSide.CT,
                        accent = IceCyan,
                        onClick = { onUpdate { it.copy(playerSide = TeamSide.CT) } },
                        modifier = Modifier.weight(1f),
                        tag = "drawer_side_ct"
                    )
                }

                DrawerSectionLabel("КОЛИЧЕСТВО ИГРОКОВ (${config.playerTeamSize} vs ${config.enemyTeamSize})")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(m.s(7f))
                ) {
                    TeamCountStepper(
                        label = "ВАША КОМАНДА",
                        count = config.playerTeamSize,
                        accent = RtxEmerald,
                        onDec = { onUpdate { it.copy(playerTeamSize = (it.playerTeamSize - 1).coerceIn(1, 5)) } },
                        onInc = { onUpdate { it.copy(playerTeamSize = (it.playerTeamSize + 1).coerceIn(1, 5)) } },
                        modifier = Modifier.weight(1f)
                    )
                    TeamCountStepper(
                        label = "ПРОТИВНИКИ",
                        count = config.enemyTeamSize,
                        accent = TacticalAmber,
                        onDec = { onUpdate { it.copy(enemyTeamSize = (it.enemyTeamSize - 1).coerceIn(1, 5)) } },
                        onInc = { onUpdate { it.copy(enemyTeamSize = (it.enemyTeamSize + 1).coerceIn(1, 5)) } },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(m.s(5f))
                ) {
                    val presets = listOf(5 to 5, 1 to 2, 3 to 3, 1 to 5)
                    presets.forEach { (ally, enemy) ->
                        val active = config.playerTeamSize == ally && config.enemyTeamSize == enemy
                        DrawerChip(
                            title = "${ally}v${enemy}",
                            selected = active,
                            accent = IceCyan,
                            onClick = { onUpdate { it.copy(playerTeamSize = ally, enemyTeamSize = enemy) } },
                            modifier = Modifier.weight(1f),
                            tag = "preset_${ally}v${enemy}"
                        )
                    }
                }

                if (config.mode == GameMode.BOMB_DEFUSAL) {
                    DrawerSectionLabel("ПОБЕДА ДО РАУНДОВ")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(m.s(6f))
                    ) {
                        listOf(5, 8, 13).forEach { rounds ->
                            DrawerChip(
                                title = "ДО $rounds",
                                selected = config.roundsToWin == rounds,
                                accent = RtxEmerald,
                                onClick = { onUpdate { it.copy(roundsToWin = rounds) } },
                                modifier = Modifier.weight(1f),
                                tag = "rounds_$rounds"
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(m.s(6f))) {
                Button(
                    onClick = onCreateLobby,
                    shape = CutCornerShape(topStart = m.s(8f), bottomEnd = m.s(8f)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = IceCyan.copy(alpha = 0.22f),
                        contentColor = IceCyan
                    ),
                    border = BorderStroke(1.1.dp, IceCyan.copy(alpha = 0.72f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(m.s(38f).coerceAtLeast(48.dp))
                        .testTag("drawer_create_lobby_button")
                ) {
                    Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(m.s(18f)))
                    Spacer(modifier = Modifier.width(m.s(5f)))
                    Text(
                        text = "СОЗДАТЬ ЛОББИ (ДРУЗЬЯ / 1v1)",
                        fontSize = m.sp(11f),
                        fontWeight = FontWeight.Black
                    )
                }

                Button(
                    onClick = onStartMatch,
                    shape = CutCornerShape(topStart = m.s(10f), bottomEnd = m.s(10f)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RtxEmerald,
                        contentColor = ObsidianVoid
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(m.s(42f).coerceAtLeast(48.dp))
                        .testTag("drawer_start_match_button")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(m.s(20f)))
                    Spacer(modifier = Modifier.width(m.s(5f)))
                    Text(
                        text = "БЫСТРЫЙ МАТЧ С БОТАМИ (${config.playerTeamSize}v${config.enemyTeamSize})",
                        fontSize = m.sp(11.5f),
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}

@Composable
private fun Cs2NavTab(
    label: String,
    selected: Boolean,
    highlight: Boolean = false,
    accentOverride: Color? = null,
    onClick: () -> Unit,
    tag: String
) {
    val m = LocalUiMetrics.current
    val baseAccent = accentOverride ?: RtxEmerald
    val textColor by animateColorAsState(
        targetValue = when {
            selected -> baseAccent
            accentOverride != null -> accentOverride
            highlight -> Color(0xFFB5FFCC)
            else -> Color.White.copy(alpha = 0.88f)
        },
        animationSpec = tween(200),
        label = "nav_tab_text"
    )
    val bgColor by animateColorAsState(
        targetValue = when {
            selected -> baseAccent.copy(alpha = 0.22f)
            accentOverride != null -> accentOverride.copy(alpha = 0.12f)
            highlight -> RtxEmerald.copy(alpha = 0.12f)
            else -> Color.Transparent
        },
        animationSpec = tween(200),
        label = "nav_tab_bg"
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(m.s(6f)))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = m.s(12f), vertical = m.s(6f))
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = m.sp(11f),
            fontWeight = if (selected || highlight || accentOverride != null) FontWeight.ExtraBold else FontWeight.SemiBold,
            letterSpacing = m.sp(0.9f),
            color = textColor,
            maxLines = 1
        )
    }
}

@Composable
private fun FactionMiniChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    tag: String
) {
    val m = LocalUiMetrics.current
    val bgColor by animateColorAsState(
        targetValue = if (selected) accent.copy(alpha = 0.28f) else Color.Transparent,
        animationSpec = tween(200),
        label = "faction_bg"
    )
    val txtColor by animateColorAsState(
        targetValue = if (selected) accent else TextSecondary,
        animationSpec = tween(200),
        label = "faction_txt"
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(m.s(4f)))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = m.s(9f), vertical = m.s(4f))
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = m.sp(10.5f),
            fontWeight = FontWeight.ExtraBold,
            color = txtColor
        )
    }
}

@Composable
private fun DrawerSectionLabel(text: String) {
    val m = LocalUiMetrics.current
    Text(
        text = text,
        fontSize = m.sp(9.5f),
        fontWeight = FontWeight.Bold,
        color = TextSecondary,
        letterSpacing = m.sp(0.8f)
    )
}

@Composable
private fun DrawerChip(
    title: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String
) {
    val m = LocalUiMetrics.current
    val shape = RoundedCornerShape(m.s(6f))
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.22f) else ElevatedGunmetal.copy(alpha = 0.72f))
            .border(
                width = if (selected) 1.4.dp else 1.dp,
                color = if (selected) accent else BorderSteel,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = m.s(6f), vertical = m.s(7f))
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            fontSize = m.sp(10f),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) accent else TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TeamCountStepper(
    label: String,
    count: Int,
    accent: Color,
    onDec: () -> Unit,
    onInc: () -> Unit,
    modifier: Modifier = Modifier
) {
    val m = LocalUiMetrics.current
    Surface(
        color = ElevatedGunmetal.copy(alpha = 0.72f),
        shape = RoundedCornerShape(m.s(8f)),
        border = BorderStroke(1.dp, BorderSteel),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = m.s(7f), vertical = m.s(5f)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(m.s(3f))
        ) {
            Text(
                text = label,
                fontSize = m.sp(8.5f),
                color = TextSecondary,
                maxLines = 1
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(m.s(9f))
            ) {
                Box(
                    modifier = Modifier
                        .size(m.s(28f))
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.11f))
                        .clickable(onClick = onDec),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Меньше", tint = TextPrimary, modifier = Modifier.size(m.s(15f)))
                }
                Text(
                    text = "$count",
                    fontSize = m.sp(14f),
                    fontWeight = FontWeight.Black,
                    color = accent
                )
                Box(
                    modifier = Modifier
                        .size(m.s(28f))
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.11f))
                        .clickable(onClick = onInc),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Больше", tint = TextPrimary, modifier = Modifier.size(m.s(15f)))
                }
            }
        }
    }
}
