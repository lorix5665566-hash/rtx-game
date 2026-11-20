package com.example.ui

import android.opengl.GLSurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.engine.LobbyGlRenderer
import com.example.model.ArenaMapId
import com.example.model.GameMode
import com.example.model.GameSettings
import com.example.model.MatchConfig
import com.example.model.PlayerStats
import com.example.model.TeamSide
import com.example.model.WeaponType
import com.example.ui.theme.BorderSteel
import com.example.ui.theme.ElevatedGunmetal
import com.example.ui.theme.IceCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.RtxEmerald
import com.example.ui.theme.TacticalAmber
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

private enum class MenuTab { HOME, LOADOUT, PLAY }

@Composable
fun MainMenuScreen(
    settings: GameSettings,
    stats: PlayerStats,
    matchConfig: MatchConfig,
    onUpdateMatchConfig: ((MatchConfig) -> MatchConfig) -> Unit,
    onPlay: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenControls: () -> Unit
) {
    val context = LocalContext.current
    var activeTab by remember { mutableStateOf(MenuTab.HOME) }
    var showcaseWeapon by remember { mutableStateOf(WeaponType.KNIFE) }

    val lobbyRenderer = remember {
        LobbyGlRenderer(getGraphics = { settings.graphics })
    }

    LaunchedEffect(matchConfig.playerSide, showcaseWeapon) {
        lobbyRenderer.showcaseSide = matchConfig.playerSide
        lobbyRenderer.showcaseWeapon = showcaseWeapon
    }

    val glView = remember {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 24, 0)
            preserveEGLContextOnPause = true
            setRenderer(lobbyRenderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    DisposableEffect(glView) {
        glView.onResume()
        onDispose { glView.onPause() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
    ) {
        // 1. Live Full-Screen 3D CS2 Mirage Courtyard + Interactive 3D Character (T / CT)
        AndroidView(
            factory = { glView },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, dragAmount ->
                        change.consume()
                        lobbyRenderer.characterYawDeg =
                            (lobbyRenderer.characterYawDeg + dragAmount * 0.42f) % 360f
                    }
                }
        )

        // Very subtle top/bottom atmospheric vignette (leaves sunlit courtyard & character crisp!)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xB8090D14), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xBD090D14))
                    )
                )
        )

        // 2. Minimalist CS2 Top Bar (Settings gear in top-left corner, clean center tabs, minimal right stats)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color(0x9910151F))
                .border(0.5.dp, Color.White.copy(alpha = 0.12f))
                .safeDrawingPadding()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Top-Left Corner: Settings Gear + Quick Faction Preview Toggle (T / CT)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .testTag("menu_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Настройки",
                        tint = TextPrimary,
                        modifier = Modifier.size(21.dp)
                    )
                }

                // Compact Faction Switcher (Террорист / Спецназ)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x990C1119))
                        .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
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

            // Center CS2-Style Clean Navigation Tabs
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Cs2NavTab(
                    label = "ГЛАВНАЯ",
                    selected = activeTab == MenuTab.HOME,
                    onClick = { activeTab = MenuTab.HOME },
                    tag = "nav_home"
                )
                Cs2NavTab(
                    label = "СНАРЯЖЕНИЕ",
                    selected = activeTab == MenuTab.LOADOUT,
                    onClick = { activeTab = if (activeTab == MenuTab.LOADOUT) MenuTab.HOME else MenuTab.LOADOUT },
                    tag = "nav_loadout"
                )
                Cs2NavTab(
                    label = "ИГРАТЬ",
                    selected = activeTab == MenuTab.PLAY,
                    highlight = true,
                    onClick = { activeTab = if (activeTab == MenuTab.PLAY) MenuTab.HOME else MenuTab.PLAY },
                    tag = "nav_play"
                )
                Cs2NavTab(
                    label = "УПРАВЛЕНИЕ",
                    selected = false,
                    onClick = onOpenControls,
                    tag = "nav_controls"
                )
            }

            // Top-Right Compact Stats Pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x800B1018))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "УБ ${stats.totalKills}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary
                )
                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Text(
                    text = "ХШ ${stats.totalHeadshots}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TacticalAmber
                )
                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Text(
                    text = settings.graphics.preset.titleRu,
                    style = MaterialTheme.typography.labelSmall,
                    color = RtxEmerald
                )
            }
        }

        // 3. HOME Bottom Bar (Minimalist CS2 footer — leaves center 3D operative completely unobstructed!)
        AnimatedVisibility(
            visible = activeTab == MenuTab.HOME,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Bottom-Left subtle operative badge
                Column {
                    Text(
                        text = if (matchConfig.playerSide == TeamSide.T) "ТЕРРОРИСТ • MIRAGE" else "СПЕЦНАЗ • MIRAGE",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (matchConfig.playerSide == TeamSide.T) TacticalAmber else IceCyan,
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        text = "Потяните персонажа для обзора 360°",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }

                // Bottom-Right Quick Match Summary + Launch CTA
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        color = Color(0xB30D131D),
                        shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                        modifier = Modifier
                            .clickable { activeTab = MenuTab.PLAY }
                            .testTag("menu_match_summary_pill")
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text(
                                text = "${matchConfig.map.titleRu}  •  ${matchConfig.mode.titleRu}",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "${matchConfig.playerTeamSize} vs ${matchConfig.enemyTeamSize}  •  до ${matchConfig.roundsToWin} раундов  •  Изменить",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }

                    Button(
                        onClick = onPlay,
                        shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RtxEmerald,
                            contentColor = ObsidianVoid
                        ),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("menu_quick_play_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "ИГРАТЬ",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }

        // 4. LOADOUT Inspection Bar (Bottom floating bar to inspect models & showcase weapons on the 3D operative)
        AnimatedVisibility(
            visible = activeTab == MenuTab.LOADOUT,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            Surface(
                color = Color(0xCC0D131E),
                shape = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "ОСМОТР ОРУЖИЯ:",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    val showcaseList = listOf(
                        WeaponType.KNIFE,
                        WeaponType.GLOCK_18,
                        WeaponType.DESERT_EAGLE,
                        WeaponType.AK47,
                        WeaponType.M4A1_S,
                        WeaponType.AWP
                    )
                    showcaseList.forEach { w ->
                        val active = showcaseWeapon == w
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (active) RtxEmerald.copy(alpha = 0.24f) else ElevatedGunmetal.copy(alpha = 0.7f))
                                .border(
                                    1.dp,
                                    if (active) RtxEmerald else BorderSteel,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable { showcaseWeapon = w }
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                                .testTag("showcase_weapon_${w.name.lowercase()}")
                        ) {
                            Text(
                                text = w.shortLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (active) RtxEmerald else TextPrimary
                            )
                        }
                    }
                }
            }
        }

        // 5. PLAY Match Configuration Side Drawer (Slides neatly from the right, keeping 3D Mirage operative visible on left!)
        AnimatedVisibility(
            visible = activeTab == MenuTab.PLAY,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .safeDrawingPadding()
                .padding(top = 52.dp, bottom = 10.dp, end = 12.dp)
        ) {
            MatchSetupDrawer(
                config = matchConfig,
                onUpdate = onUpdateMatchConfig,
                onStartMatch = onPlay,
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
    onClose: () -> Unit
) {
    Surface(
        color = Color(0xE00C121D),
        shape = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp),
        border = BorderStroke(1.dp, RtxEmerald.copy(alpha = 0.45f)),
        modifier = Modifier
            .width(385.dp)
            .fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Drawer Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "НАСТРОЙКА МАТЧА",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "Выберите режим, карту и состав команд",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = TextSecondary)
                }
            }

            // Scrollable Options
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Mode Selector
                DrawerSectionLabel("РЕЖИМ ИГРЫ")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GameMode.entries.forEach { mode ->
                        val active = config.mode == mode
                        DrawerChip(
                            title = mode.titleRu,
                            selected = active,
                            accent = RtxEmerald,
                            onClick = { onUpdate { it.copy(mode = mode) } },
                            modifier = Modifier.weight(1f),
                            tag = "mode_${mode.name.lowercase()}"
                        )
                    }
                }

                // Map Selector
                DrawerSectionLabel("КАРТА")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ArenaMapId.entries.forEach { map ->
                        val active = config.map == map
                        DrawerChip(
                            title = map.titleRu,
                            selected = active,
                            accent = TacticalAmber,
                            onClick = { onUpdate { it.copy(map = map) } },
                            modifier = Modifier.weight(1f),
                            tag = "map_${map.name.lowercase()}"
                        )
                    }
                }

                // Side Selector (Террористы / Спецназ)
                DrawerSectionLabel("СТОРОНА (МОДЕЛЬ ПЕРСОНАЖА)")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
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

                // Team Size Steppers (1..5 vs 1..5) + Quick Presets (5v5, 1v2, 3v3, 1v5)
                DrawerSectionLabel("КОЛИЧЕСТВО ИГРОКОВ (${config.playerTeamSize} vs ${config.enemyTeamSize})")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
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

                // Rounds to win (default 8)
                if (config.mode == GameMode.BOMB_DEFUSAL) {
                    DrawerSectionLabel("ПОБЕДА ДО РАУНДОВ")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
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

            // Launch Button
            Button(
                onClick = onStartMatch,
                shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RtxEmerald,
                    contentColor = ObsidianVoid
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("drawer_start_match_button")
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "НАЧАТЬ МАТЧ (${config.playerTeamSize}v${config.enemyTeamSize})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

@Composable
private fun Cs2NavTab(
    label: String,
    selected: Boolean,
    highlight: Boolean = false,
    onClick: () -> Unit,
    tag: String
) {
    val textColor = when {
        selected -> RtxEmerald
        highlight -> Color(0xFFB5FFCC)
        else -> Color.White.copy(alpha = 0.85f)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    selected -> RtxEmerald.copy(alpha = 0.18f)
                    highlight -> RtxEmerald.copy(alpha = 0.10f)
                    else -> Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected || highlight) FontWeight.ExtraBold else FontWeight.SemiBold,
            letterSpacing = 1.1.sp,
            color = textColor
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
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) accent.copy(alpha = 0.28f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (selected) accent else TextSecondary
        )
    }
}

@Composable
private fun DrawerSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
        letterSpacing = 0.9.sp
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
    val shape = RoundedCornerShape(6.dp)
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
            .padding(horizontal = 6.dp, vertical = 8.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) accent else TextPrimary,
            maxLines = 1
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
    Surface(
        color = ElevatedGunmetal.copy(alpha = 0.72f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, BorderSteel),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                fontSize = 9.sp
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.10f))
                        .clickable(onClick = onDec),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Меньше", tint = TextPrimary, modifier = Modifier.size(16.dp))
                }
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = accent
                )
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.10f))
                        .clickable(onClick = onInc),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Больше", tint = TextPrimary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
