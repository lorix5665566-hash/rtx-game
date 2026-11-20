package com.example.ui

import android.opengl.GLSurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.engine.GameEngine
import com.example.engine.ShooterGlRenderer
import com.example.model.BombState
import com.example.model.ControlWidgetId
import com.example.model.GameMode
import com.example.model.GameSettings
import com.example.model.HudSnapshot
import com.example.model.TeamSide
import com.example.model.WeaponCategory
import com.example.model.WeaponSlot
import com.example.model.WeaponType
import com.example.ui.theme.BorderSteel
import com.example.ui.theme.ElevatedGunmetal
import com.example.ui.theme.IceCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.RtxEmerald
import com.example.ui.theme.TacticalAmber
import com.example.ui.theme.TacticalSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.ThreatCrimson
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun GameplayScreen(
    engine: GameEngine,
    settings: GameSettings,
    isPaused: Boolean,
    onSetPaused: (Boolean) -> Unit,
    onRestart: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenControlEditor: () -> Unit,
    onExitToMenu: () -> Unit
) {
    val context = LocalContext.current
    var hud by remember { mutableStateOf(engine.latestHud) }
    var isShopOpen by remember { mutableStateOf(false) }
    var swipeFeedback by remember { mutableStateOf("") }

    BackHandler {
        when {
            isShopOpen -> isShopOpen = false
            !isPaused -> onSetPaused(true)
            else -> onSetPaused(false)
        }
    }

    LaunchedEffect(engine) {
        while (true) {
            hud = engine.latestHud
            if (!hud.canBuy && isShopOpen) {
                isShopOpen = false
            }
            delay(33L)
        }
    }

    LaunchedEffect(swipeFeedback) {
        if (swipeFeedback.isNotEmpty()) {
            delay(750L)
            swipeFeedback = ""
        }
    }

    val glSurfaceView = remember(engine) {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 24, 0)
            preserveEGLContextOnPause = true
            setRenderer(ShooterGlRenderer(engine))
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    DisposableEffect(glSurfaceView) {
        glSurfaceView.onResume()
        onDispose {
            engine.isFiring = false
            engine.isBombActionHeld = false
            engine.moveInputX = 0f
            engine.moveInputY = 0f
            glSurfaceView.onPause()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
    ) {
        val density = LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val screenHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)

        // 1. Live OpenGL ES 3.0 PBR / RTX 3D Viewport
        AndroidView(
            factory = { glSurfaceView },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Full-screen Aim Touch Layer (Raw Manual Aim — NO Aim Assist)
        if (!isPaused && !hud.isMatchFinished && !isShopOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            engine.addLookDelta(dragAmount.x, dragAmount.y)
                        }
                    }
            )
        }

        // 3. Damage Vignette Flash
        if (hud.damageFlashAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color.Transparent,
                                ThreatCrimson.copy(alpha = hud.damageFlashAlpha * 0.62f)
                            )
                        )
                    )
            )
        }

        // 4. AWP 4x Optical Scope Overlay OR Dynamic Precision Crosshair
        val showSniperScope = hud.activeWeapon.hasSniperScope && hud.adsBlend > 0.55f && !hud.isPlayerDead
        if (showSniperScope) {
            SniperScopeOverlay(
                hitMarkerAlpha = hud.hitMarkerAlpha,
                isHeadshot = hud.isHeadshotHit
            )
        } else if (!hud.isPlayerDead && !hud.isMatchFinished) {
            TacticalCrosshair(
                spread = hud.crosshairSpread,
                isAds = hud.isAds,
                isKnifeOrBomb = hud.activeWeapon == WeaponType.KNIFE || hud.activeWeapon == WeaponType.C4_BOMB,
                hitMarkerAlpha = hud.hitMarkerAlpha,
                isHeadshot = hud.isHeadshotHit
            )
        }

        // 5. Top-Left Circular Tactical Radar + $ Money + FPS
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .safeDrawingPadding()
                .padding(start = 10.dp, top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TacticalRadar(hud = hud)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // CS2 Green Dollar Balance
                    Surface(
                        color = Color(0xB80A111A),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, RtxEmerald.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "$${hud.money}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Black,
                            color = RtxEmerald,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                        )
                    }
                    if (hud.calloutName.isNotEmpty()) {
                        Surface(
                            color = Color(0xA60A1018),
                            shape = RoundedCornerShape(5.dp)
                        ) {
                            Text(
                                text = hud.calloutName,
                                style = MaterialTheme.typography.labelSmall,
                                color = TacticalAmber,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (settings.graphics.showFps) {
                        Text(
                            text = "${hud.fps} FPS • ${settings.graphics.preset.titleRu}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        // 6. Top-Center CS2 Round Scoreboard (CT vs T + Round Timer / C4 Countdown + Alive Counts)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(top = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Cs2ScoreboardBar(hud = hud)

            if (hud.bannerText.isNotEmpty()) {
                Surface(
                    color = Color(0xCC0A111A),
                    shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                    border = BorderStroke(1.dp, TacticalAmber.copy(alpha = 0.7f))
                ) {
                    Text(
                        text = hud.bannerText,
                        style = MaterialTheme.typography.labelLarge,
                        color = TacticalAmber,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                    )
                }
            }

            if (hud.killFeedText.isNotEmpty()) {
                Surface(
                    color = Color(0xA614090C),
                    shape = RoundedCornerShape(5.dp),
                    border = BorderStroke(1.dp, ThreatCrimson.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = hud.killFeedText,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // 7. Bottom-Left Compact Vitals Strip (HP + Armor + Defuse Kit)
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .safeDrawingPadding()
                .padding(start = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                color = Color(0xB80B111A),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, BorderSteel.copy(alpha = 0.7f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val hpColor = when {
                        hud.hp > 50 -> RtxEmerald
                        hud.hp > 25 -> TacticalAmber
                        else -> ThreatCrimson
                    }
                    Text(
                        text = "+ ${hud.hp}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Black,
                        color = hpColor
                    )
                    Text(
                        text = "БР ${hud.armor}",
                        style = MaterialTheme.typography.labelLarge,
                        color = IceCyan
                    )
                    if (hud.hasDefuseKit) {
                        Text(
                            text = "САПЁР",
                            style = MaterialTheme.typography.labelSmall,
                            color = RtxEmerald
                        )
                    }
                }
            }

            if (hud.isReloading) {
                Surface(
                    color = Color(0xCC0D1420),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, TacticalAmber.copy(alpha = 0.6f))
                ) {
                    Column(
                        modifier = Modifier
                            .width(120.dp)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "ПЕРЕЗАРЯДКА",
                            style = MaterialTheme.typography.labelSmall,
                            color = TacticalAmber,
                            fontSize = 9.sp
                        )
                        LinearProgressIndicator(
                            progress = { hud.reloadProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = TacticalAmber,
                            trackColor = ElevatedGunmetal
                        )
                    }
                }
            }
        }

        // 8. Contextual C4 Plant / Defuse Hold Button (Lower-Center when in Bomb Zone!)
        if (!isPaused && !isShopOpen && !hud.isPlayerDead && (hud.canPlantBomb || hud.canDefuseBomb)) {
            BombActionHoldButton(
                isPlant = hud.canPlantBomb,
                siteLabel = if (hud.canPlantBomb) hud.inPlantZoneLabel else hud.bombSiteLabel,
                progress = hud.bombActionProgress,
                onHeldChange = { held -> engine.isBombActionHeld = held },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(bottom = 16.dp)
            )
        }

        // 9. Dead Spectator Banner (during Bomb Defusal round)
        if (hud.isPlayerDead && !hud.isMatchFinished && !isPaused) {
            Surface(
                color = Color(0xCC12090D),
                shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
                border = BorderStroke(1.dp, ThreatCrimson.copy(alpha = 0.7f)),
                modifier = Modifier
                    .align(Alignment.Center)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "ВЫ УБИТЫ",
                        style = MaterialTheme.typography.titleMedium,
                        color = ThreatCrimson
                    )
                    Text(
                        text = if (hud.mode == GameMode.BOMB_DEFUSAL) {
                            "Ожидание завершения раунда (CT: ${hud.ctAlive} • T: ${hud.tAlive})"
                        } else {
                            "Возрождение..."
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                }
            }
        }

        // 10. Customizable Landscape Controls + Single Wide Swipe Weapon Button + Shop Button
        if (!isPaused && !hud.isMatchFinished && !isShopOpen) {
            val layout = settings.controlLayout

            // Movement Joystick
            val joyCfg = layout.get(ControlWidgetId.JOYSTICK)
            if (joyCfg.visible) {
                val sizeDp = widgetBaseWidthDp(ControlWidgetId.JOYSTICK) * joyCfg.scale
                val sizePx = with(density) { sizeDp.toPx() }
                VirtualJoystick(
                    sizeDp = sizeDp,
                    opacity = joyCfg.opacity,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (joyCfg.xFraction * screenWidthPx - sizePx * 0.5f).roundToInt(),
                                (joyCfg.yFraction * screenHeightPx - sizePx * 0.5f).roundToInt()
                            )
                        }
                        .testTag("hud_joystick"),
                    onMove = { mx, my ->
                        engine.moveInputX = mx
                        engine.moveInputY = my
                    }
                )
            }

            // Fire Buttons (Right & optional Left)
            listOf(ControlWidgetId.FIRE_RIGHT, ControlWidgetId.FIRE_LEFT).forEach { fireId ->
                val cfg = layout.get(fireId)
                if (cfg.visible) {
                    val sizeDp = widgetBaseWidthDp(fireId) * cfg.scale
                    val sizePx = with(density) { sizeDp.toPx() }
                    HoldActionButton(
                        id = fireId,
                        sizeDp = sizeDp,
                        opacity = cfg.opacity,
                        accent = ThreatCrimson,
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (cfg.xFraction * screenWidthPx - sizePx * 0.5f).roundToInt(),
                                    (cfg.yFraction * screenHeightPx - sizePx * 0.5f).roundToInt()
                                )
                            }
                            .testTag("hud_${fireId.name.lowercase()}"),
                        onPressedChange = { pressed -> engine.isFiring = pressed }
                    )
                }
            }

            // ADS, Reload, Crouch, Jump, Shop, Pause
            listOf(
                ControlWidgetId.ADS,
                ControlWidgetId.RELOAD,
                ControlWidgetId.CROUCH,
                ControlWidgetId.JUMP,
                ControlWidgetId.SHOP,
                ControlWidgetId.PAUSE
            ).forEach { id ->
                val cfg = layout.get(id)
                if (cfg.visible) {
                    val sizeDp = widgetBaseWidthDp(id) * cfg.scale
                    val sizePx = with(density) { sizeDp.toPx() }
                    val activeState = when (id) {
                        ControlWidgetId.ADS -> hud.isAds
                        ControlWidgetId.CROUCH -> hud.isCrouching
                        ControlWidgetId.SHOP -> hud.canBuy
                        else -> false
                    }
                    val accent = when (id) {
                        ControlWidgetId.ADS -> TacticalAmber
                        ControlWidgetId.SHOP -> if (hud.canBuy) RtxEmerald else BorderSteel
                        ControlWidgetId.RELOAD -> IceCyan
                        else -> RtxEmerald
                    }
                    TapActionButton(
                        id = id,
                        sizeDp = sizeDp,
                        opacity = cfg.opacity,
                        accent = accent,
                        isActive = activeState,
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (cfg.xFraction * screenWidthPx - sizePx * 0.5f).roundToInt(),
                                    (cfg.yFraction * screenHeightPx - sizePx * 0.5f).roundToInt()
                                )
                            }
                            .testTag("hud_${id.name.lowercase()}"),
                        onClick = {
                            when (id) {
                                ControlWidgetId.ADS -> engine.toggleAds()
                                ControlWidgetId.RELOAD -> engine.triggerReload()
                                ControlWidgetId.CROUCH -> engine.toggleCrouch()
                                ControlWidgetId.JUMP -> engine.triggerJump()
                                ControlWidgetId.SHOP -> {
                                    if (hud.canBuy) {
                                        engine.isFiring = false
                                        isShopOpen = true
                                    }
                                }
                                ControlWidgetId.PAUSE -> {
                                    engine.isFiring = false
                                    onSetPaused(true)
                                }
                                else -> Unit
                            }
                        }
                    )
                }
            }

            // SINGLE WIDE SWIPE WEAPON BUTTON (Left=Knife, Right=Pistol, Down=Primary, Up=Bomb C4, Tap=Quick Switch)
            val swipeCfg = layout.get(ControlWidgetId.WEAPON_SWIPE)
            if (swipeCfg.visible) {
                val widthDp = widgetBaseWidthDp(ControlWidgetId.WEAPON_SWIPE) * swipeCfg.scale
                val heightDp = widgetBaseHeightDp(ControlWidgetId.WEAPON_SWIPE) * swipeCfg.scale
                val widthPx = with(density) { widthDp.toPx() }
                val heightPx = with(density) { heightDp.toPx() }
                WeaponSwipeControl(
                    hud = hud,
                    widthDp = widthDp,
                    heightDp = heightDp,
                    opacity = swipeCfg.opacity,
                    swipeFeedback = swipeFeedback,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (swipeCfg.xFraction * screenWidthPx - widthPx * 0.5f).roundToInt(),
                                (swipeCfg.yFraction * screenHeightPx - heightPx * 0.5f).roundToInt()
                            )
                        }
                        .testTag("hud_weapon_swipe"),
                    onSelectSlot = { slot, label ->
                        engine.selectSlot(slot)
                        swipeFeedback = label
                    },
                    onQuickSwitch = {
                        engine.quickSwitchWeapon()
                        swipeFeedback = "СМЕНА"
                    }
                )
            }
        }

        // 11. Compact In-Game Buy Menu (Shop) Overlay
        if (isShopOpen && !isPaused && !hud.isMatchFinished) {
            CompactBuyMenuOverlay(
                hud = hud,
                onBuyWeapon = { weapon ->
                    engine.buyWeapon(weapon)
                },
                onBuyArmor = { engine.buyArmor() },
                onBuyDefuseKit = { engine.buyDefuseKit() },
                onClose = { isShopOpen = false }
            )
        }

        // 12. Pause Overlay
        if (isPaused && !hud.isMatchFinished) {
            PauseOverlay(
                onResume = { onSetPaused(false) },
                onRestart = onRestart,
                onSettings = onOpenSettings,
                onControls = onOpenControlEditor,
                onExit = onExitToMenu
            )
        }

        // 13. Match Finished Summary Overlay
        if (hud.isMatchFinished) {
            MatchEndOverlay(
                hud = hud,
                onReplay = onRestart,
                onExit = onExitToMenu
            )
        }
    }
}

@Composable
private fun Cs2ScoreboardBar(hud: HudSnapshot) {
    val timeSecInt = if (hud.bombState == BombState.PLANTED) {
        hud.bombCountdownSec.roundToInt().coerceAtLeast(0)
    } else {
        hud.roundTimeSec.roundToInt().coerceAtLeast(0)
    }
    val mins = timeSecInt / 60
    val secs = timeSecInt % 60
    val timeText = String.format("%d:%02d", mins, secs)

    Surface(
        color = Color(0xC20A101A),
        shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (hud.bombState == BombState.PLANTED) ThreatCrimson else Color.White.copy(alpha = 0.16f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // CT Score & Alive
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("CT", style = MaterialTheme.typography.labelSmall, color = IceCyan, fontSize = 9.sp)
                    Text("ЖИВ: ${hud.ctAlive}", style = MaterialTheme.typography.labelSmall, color = TextSecondary, fontSize = 8.sp)
                }
                Text(
                    text = "${hud.ctScore}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = IceCyan
                )
            }

            // Center Timer / C4 Countdown
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (hud.bombState == BombState.PLANTED) {
                    Text(
                        text = "C4 ${hud.bombSiteLabel} • $timeText",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Black,
                        color = ThreatCrimson
                    )
                } else {
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = if (hud.mode == GameMode.BOMB_DEFUSAL) "Р${hud.roundNumber} • ДО ${hud.roundsToWin}" else "БОЙ НАСМЕРТЬ",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontSize = 8.sp
                    )
                }
            }

            // T Score & Alive
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "${hud.tScore}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = TacticalAmber
                )
                Column(horizontalAlignment = Alignment.Start) {
                    Text("T", style = MaterialTheme.typography.labelSmall, color = TacticalAmber, fontSize = 9.sp)
                    Text("ЖИВ: ${hud.tAlive}", style = MaterialTheme.typography.labelSmall, color = TextSecondary, fontSize = 8.sp)
                }
            }
        }
    }
}

/**
 * Single Wide Swipe Weapon Switcher Button:
 * Displays currently held weapon silhouette + name + ammo.
 * Swipe Left = Knife | Swipe Right = Pistol | Swipe Down = Primary | Swipe Up = Bomb C4 | Tap = Quick Switch
 */
@Composable
private fun WeaponSwipeControl(
    hud: HudSnapshot,
    widthDp: Dp,
    heightDp: Dp,
    opacity: Float,
    swipeFeedback: String,
    modifier: Modifier = Modifier,
    onSelectSlot: (WeaponSlot, String) -> Unit,
    onQuickSwitch: () -> Unit
) {
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 22.dp.toPx() }
    val shape = RoundedCornerShape(11.dp)
    val accentColor = when (hud.activeWeapon.slot) {
        WeaponSlot.BOMB -> ThreatCrimson
        WeaponSlot.KNIFE -> IceCyan
        WeaponSlot.PISTOL -> TacticalAmber
        WeaponSlot.PRIMARY -> RtxEmerald
    }

    Box(
        modifier = modifier
            .size(width = widthDp, height = heightDp)
            .alpha(opacity)
            .clip(shape)
            .background(Color(0xC90B121D))
            .border(1.5.dp, accentColor.copy(alpha = 0.85f), shape)
            .pointerInput(hud.mode, swipeThresholdPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startPos = down.position
                    var lastPos = startPos
                    var swiped = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (!swiped) {
                                onQuickSwitch()
                            }
                            break
                        }
                        lastPos = change.position
                        val dx = lastPos.x - startPos.x
                        val dy = lastPos.y - startPos.y
                        if (!swiped && (abs(dx) > swipeThresholdPx || abs(dy) > swipeThresholdPx)) {
                            swiped = true
                            change.consume()
                            if (abs(dx) >= abs(dy)) {
                                if (dx < 0f) {
                                    onSelectSlot(WeaponSlot.KNIFE, "◄ НОЖ")
                                } else {
                                    onSelectSlot(WeaponSlot.PISTOL, "ПИСТОЛЕТ ►")
                                }
                            } else {
                                if (dy > 0f) {
                                    onSelectSlot(WeaponSlot.PRIMARY, "▼ ОСНОВНОЕ")
                                } else if (hud.mode == GameMode.BOMB_DEFUSAL) {
                                    onSelectSlot(WeaponSlot.BOMB, "▲ БОМБА C4")
                                } else {
                                    onSelectSlot(WeaponSlot.PRIMARY, "ОСНОВНОЕ")
                                }
                            }
                        }
                    }
                }
            }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        // Top micro-hint (Bomb C4 swipe up when in Bomb Defusal)
        if (hud.mode == GameMode.BOMB_DEFUSAL && hud.hasBomb) {
            Text(
                text = "▲ C4",
                style = MaterialTheme.typography.labelSmall,
                color = ThreatCrimson.copy(alpha = 0.9f),
                fontSize = 8.sp,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        // Main Center Row: Current Weapon Silhouette + Name & Ammo
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 4.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            WeaponSilhouetteIcon(
                weapon = hud.activeWeapon,
                tint = TextPrimary,
                modifier = Modifier
                    .width(widthDp * 0.44f)
                    .height(heightDp * 0.56f)
            )

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (swipeFeedback.isNotEmpty()) swipeFeedback else hud.activeWeapon.shortLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black,
                    color = accentColor,
                    maxLines = 1
                )
                val ammoLabel = when (hud.activeWeapon) {
                    WeaponType.KNIFE -> "БЛИЖНИЙ"
                    WeaponType.C4_BOMB -> "ЗАРЯД C4"
                    else -> "${hud.ammo} / ${hud.reserveAmmo}"
                }
                Text(
                    text = ammoLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary,
                    fontSize = 10.sp
                )
            }
        }

        // Bottom directional legend
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("◄ НОЖ", fontSize = 8.sp, color = TextSecondary)
            Text(
                text = if (hud.primaryWeapon != null) "▼ ${hud.primaryWeapon.shortLabel}" else "▼ ОСН",
                fontSize = 8.sp,
                color = if (hud.primaryWeapon != null) RtxEmerald.copy(alpha = 0.85f) else TextSecondary.copy(alpha = 0.5f)
            )
            Text("${hud.pistolWeapon.shortLabel} ►", fontSize = 8.sp, color = TacticalAmber.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun WeaponSilhouetteIcon(
    weapon: WeaponType,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        when (weapon) {
            WeaponType.KNIFE -> {
                val p = Path().apply {
                    moveTo(w * 0.10f, h * 0.65f)
                    lineTo(w * 0.34f, h * 0.50f)
                    lineTo(w * 0.90f, h * 0.28f)
                    lineTo(w * 0.82f, h * 0.52f)
                    lineTo(w * 0.36f, h * 0.66f)
                    lineTo(w * 0.14f, h * 0.78f)
                    close()
                }
                drawPath(p, color = tint)
            }
            WeaponType.C4_BOMB -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.22f, h * 0.18f),
                    size = Size(w * 0.56f, h * 0.64f),
                    cornerRadius = CornerRadius(6f, 6f)
                )
                drawCircle(
                    color = ThreatCrimson,
                    radius = h * 0.11f,
                    center = Offset(w * 0.65f, h * 0.35f)
                )
            }
            WeaponType.USP_S, WeaponType.GLOCK_18, WeaponType.DESERT_EAGLE -> {
                // Slide
                drawRect(tint, Offset(w * 0.20f, h * 0.26f), Size(w * if (weapon.isSuppressed) 0.70f else 0.52f, h * 0.22f))
                // Grip
                val grip = Path().apply {
                    moveTo(w * 0.22f, h * 0.46f)
                    lineTo(w * 0.38f, h * 0.46f)
                    lineTo(w * 0.32f, h * 0.84f)
                    lineTo(w * 0.16f, h * 0.84f)
                    close()
                }
                drawPath(grip, color = tint)
            }
            WeaponType.AWP -> {
                // Scope
                drawRect(tint, Offset(w * 0.34f, h * 0.14f), Size(w * 0.30f, h * 0.14f))
                // Long Barrel + Chassis + Stock
                drawRect(tint, Offset(w * 0.06f, h * 0.36f), Size(w * 0.88f, h * 0.16f))
                drawRect(tint, Offset(w * 0.06f, h * 0.48f), Size(w * 0.22f, h * 0.26f))
                drawRect(tint, Offset(w * 0.44f, h * 0.52f), Size(w * 0.10f, h * 0.22f))
            }
            else -> {
                // Rifle / SMG silhouette
                drawRect(tint, Offset(w * 0.06f, h * 0.34f), Size(w * 0.86f, h * 0.19f))
                drawRect(tint, Offset(w * 0.06f, h * 0.46f), Size(w * 0.20f, h * 0.26f))
                // Magazine
                val mag = Path().apply {
                    moveTo(w * 0.48f, h * 0.52f)
                    lineTo(w * 0.60f, h * 0.52f)
                    lineTo(w * 0.64f, h * 0.86f)
                    lineTo(w * 0.52f, h * 0.86f)
                    close()
                }
                drawPath(mag, color = tint)
            }
        }
    }
}

@Composable
private fun CompactBuyMenuOverlay(
    hud: HudSnapshot,
    onBuyWeapon: (WeaponType) -> Unit,
    onBuyArmor: () -> Unit,
    onBuyDefuseKit: () -> Unit,
    onClose: () -> Unit
) {
    val isFreeTdm = hud.mode == GameMode.TEAM_DEATHMATCH
    val pistols = WeaponType.entries.filter { it.category == WeaponCategory.PISTOLS }
    val smgs = WeaponType.entries.filter { it.category == WeaponCategory.SMGS }
    val rifles = WeaponType.entries.filter { it.category == WeaponCategory.RIFLES }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x9E060A11))
            .clickable(onClick = onClose)
            .safeDrawingPadding()
            .padding(14.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Color(0xEC0D1420),
            shape = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp),
            border = BorderStroke(1.2.dp, RtxEmerald.copy(alpha = 0.6f)),
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .clickable(enabled = false) {}
                .testTag("compact_buy_menu")
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = RtxEmerald, modifier = Modifier.size(20.dp))
                        Text(
                            text = "МАГАЗИН ОРУЖИЯ",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Surface(
                            color = RtxEmerald.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, RtxEmerald.copy(alpha = 0.6f))
                        ) {
                            Text(
                                text = if (isFreeTdm) "БЕСПЛАТНО (DM)" else "$${hud.money}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Black,
                                color = RtxEmerald,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("buy_menu_close")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть магазин", tint = TextPrimary)
                    }
                }

                // 4 Compact Columns: Pistols | SMGs | Rifles | Gear
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BuyColumn(title = "ПИСТОЛЕТЫ", accent = TacticalAmber, modifier = Modifier.weight(1f)) {
                        pistols.forEach { w ->
                            val cost = if (isFreeTdm) 0 else w.price
                            val owned = hud.pistolWeapon == w
                            BuyItemButton(
                                title = w.shortLabel,
                                priceText = if (owned) "В РУКАХ" else "$$cost",
                                enabled = owned || hud.money >= cost,
                                owned = owned,
                                accent = TacticalAmber,
                                onClick = { onBuyWeapon(w) },
                                tag = "buy_${w.name.lowercase()}"
                            )
                        }
                    }

                    BuyColumn(title = "ПП", accent = IceCyan, modifier = Modifier.weight(1f)) {
                        smgs.forEach { w ->
                            val cost = if (isFreeTdm) 0 else w.price
                            val owned = hud.primaryWeapon == w
                            BuyItemButton(
                                title = w.shortLabel,
                                priceText = if (owned) "КУПЛЕНО" else "$$cost",
                                enabled = owned || hud.money >= cost,
                                owned = owned,
                                accent = IceCyan,
                                onClick = { onBuyWeapon(w) },
                                tag = "buy_${w.name.lowercase()}"
                            )
                        }
                    }

                    BuyColumn(title = "ВИНТОВКИ", accent = RtxEmerald, modifier = Modifier.weight(1f)) {
                        rifles.forEach { w ->
                            val cost = if (isFreeTdm) 0 else w.price
                            val owned = hud.primaryWeapon == w
                            BuyItemButton(
                                title = w.shortLabel,
                                priceText = if (owned) "КУПЛЕНО" else "$$cost",
                                enabled = owned || hud.money >= cost,
                                owned = owned,
                                accent = RtxEmerald,
                                onClick = { onBuyWeapon(w) },
                                tag = "buy_${w.name.lowercase()}"
                            )
                        }
                    }

                    BuyColumn(title = "СНАРЯЖЕНИЕ", accent = Color(0xFFB39DDB), modifier = Modifier.weight(1f)) {
                        val armorCost = if (isFreeTdm) 0 else 1000
                        val fullArmor = hud.armor >= 100
                        BuyItemButton(
                            title = "Броня + Шлем",
                            priceText = if (fullArmor) "100%" else "$$armorCost",
                            enabled = !fullArmor && hud.money >= armorCost,
                            owned = fullArmor,
                            accent = IceCyan,
                            onClick = onBuyArmor,
                            tag = "buy_armor"
                        )

                        if (hud.playerSide == TeamSide.CT && hud.mode == GameMode.BOMB_DEFUSAL) {
                            val kitCost = 400
                            BuyItemButton(
                                title = "Набор сапёра",
                                priceText = if (hud.hasDefuseKit) "ЕСТЬ" else "$$kitCost",
                                enabled = !hud.hasDefuseKit && hud.money >= kitCost,
                                owned = hud.hasDefuseKit,
                                accent = RtxEmerald,
                                onClick = onBuyDefuseKit,
                                tag = "buy_defuse_kit"
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BuyColumn(
    title: String,
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.Bold
        )
        content()
    }
}

@Composable
private fun BuyItemButton(
    title: String,
    priceText: String,
    enabled: Boolean,
    owned: Boolean,
    accent: Color,
    onClick: () -> Unit,
    tag: String
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                when {
                    owned -> accent.copy(alpha = 0.22f)
                    enabled -> ElevatedGunmetal.copy(alpha = 0.85f)
                    else -> Color(0x66121722)
                }
            )
            .border(
                width = if (owned) 1.5.dp else 1.dp,
                color = if (owned) accent else if (enabled) BorderSteel else BorderSteel.copy(alpha = 0.35f),
                shape = shape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 9.dp)
            .testTag(tag)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled || owned) TextPrimary else TextSecondary.copy(alpha = 0.45f),
                maxLines = 1
            )
            Text(
                text = priceText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = when {
                    owned -> accent
                    enabled -> RtxEmerald
                    else -> ThreatCrimson.copy(alpha = 0.55f)
                }
            )
        }
    }
}

@Composable
private fun BombActionHoldButton(
    isPlant: Boolean,
    siteLabel: String,
    progress: Float,
    onHeldChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = if (isPlant) ThreatCrimson else IceCyan
    val label = if (isPlant) "УДЕРЖ. ЗАЛОЖИТЬ C4 ($siteLabel)" else "УДЕРЖ. РАЗМИНИРОВАТЬ C4 ($siteLabel)"
    Surface(
        color = Color(0xD90E141F),
        shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
        border = BorderStroke(1.8.dp, accent),
        modifier = modifier
            .width(240.dp)
            .pointerInput(isPlant) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onHeldChange(true)
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                    }
                    onHeldChange(false)
                }
            }
            .testTag("hud_bomb_action_button")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
                color = accent
            )
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = accent,
                trackColor = ElevatedGunmetal
            )
        }
    }
}

@Composable
private fun TacticalRadar(hud: HudSnapshot) {
    val radarSize = 86.dp
    Box(
        modifier = Modifier
            .size(radarSize)
            .background(Color(0xA6080E16), CircleShape)
            .border(1.5.dp, RtxEmerald.copy(alpha = 0.55f), CircleShape)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = Offset(size.width * 0.5f, size.height * 0.5f)
            val r = size.minDimension * 0.45f

            drawCircle(Color.White.copy(alpha = 0.14f), r * 0.5f, c, style = Stroke(1f))
            drawLine(Color.White.copy(alpha = 0.10f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
            drawLine(Color.White.copy(alpha = 0.10f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)

            // FOV Cone
            drawLine(RtxEmerald.copy(alpha = 0.35f), c, Offset(c.x - r * 0.50f, c.y - r * 0.86f), 1.5f)
            drawLine(RtxEmerald.copy(alpha = 0.35f), c, Offset(c.x + r * 0.50f, c.y - r * 0.86f), 1.5f)

            // Player center
            drawCircle(Color.White, 3.2f, c)

            // Allies, Enemies & Planted Bomb
            val yawRad = Math.toRadians(hud.playerYawDeg.toDouble()).toFloat()
            val cosY = cos(yawRad)
            val sinY = sin(yawRad)
            for (blip in hud.radarBlips) {
                val localX = blip.relX * cosY - blip.relZ * sinY
                val localZ = blip.relX * sinY + blip.relZ * cosY
                val nx = (localX / 36f).coerceIn(-0.92f, 0.92f)
                val nz = (localZ / 36f).coerceIn(-0.92f, 0.92f)
                val bx = c.x + nx * r
                val by = c.y + nz * r
                val color = when {
                    blip.isBomb -> TacticalAmber
                    blip.isAlly -> RtxEmerald
                    else -> ThreatCrimson
                }
                drawCircle(color, if (blip.isBomb) 4.8f else 3.6f, Offset(bx, by))
            }
        }
    }
}

@Composable
private fun SniperScopeOverlay(
    hitMarkerAlpha: Float,
    isHeadshot: Boolean
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val c = Offset(size.width * 0.5f, size.height * 0.5f)
        val radius = size.minDimension * 0.44f

        val outerPath = Path().apply {
            addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
            addOval(androidx.compose.ui.geometry.Rect(c.x - radius, c.y - radius, c.x + radius, c.y + radius))
            fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
        }
        drawPath(outerPath, color = Color(0xF6020408))

        drawCircle(
            color = Color(0xFF1F2A38),
            radius = radius,
            center = c,
            style = Stroke(width = 7f)
        )

        drawLine(Color.Black.copy(alpha = 0.92f), Offset(c.x - radius, c.y), Offset(c.x + radius, c.y), 2.2f)
        drawLine(Color.Black.copy(alpha = 0.92f), Offset(c.x, c.y - radius), Offset(c.x, c.y + radius), 2.2f)

        for (i in 1..4) {
            val d = radius * 0.16f * i
            val tick = if (i % 2 == 0) 11f else 6f
            drawLine(Color.Black, Offset(c.x - d, c.y - tick), Offset(c.x - d, c.y + tick), 2f)
            drawLine(Color.Black, Offset(c.x + d, c.y - tick), Offset(c.x + d, c.y + tick), 2f)
            drawLine(Color.Black, Offset(c.x - tick, c.y + d), Offset(c.x + tick, c.y + d), 2f)
        }

        drawCircle(ThreatCrimson, 3.2f, c)

        if (hitMarkerAlpha > 0.02f) {
            val hmColor = if (isHeadshot) ThreatCrimson else Color.White
            val inner = 12f
            val outer = 26f
            drawLine(hmColor.copy(alpha = hitMarkerAlpha), Offset(c.x - outer, c.y - outer), Offset(c.x - inner, c.y - inner), 3.4f, StrokeCap.Round)
            drawLine(hmColor.copy(alpha = hitMarkerAlpha), Offset(c.x + outer, c.y - outer), Offset(c.x + inner, c.y - inner), 3.4f, StrokeCap.Round)
            drawLine(hmColor.copy(alpha = hitMarkerAlpha), Offset(c.x - outer, c.y + outer), Offset(c.x - inner, c.y + inner), 3.4f, StrokeCap.Round)
            drawLine(hmColor.copy(alpha = hitMarkerAlpha), Offset(c.x + outer, c.y + outer), Offset(c.x + inner, c.y + inner), 3.4f, StrokeCap.Round)
        }
    }
}

@Composable
private fun TacticalCrosshair(
    spread: Float,
    isAds: Boolean,
    isKnifeOrBomb: Boolean,
    hitMarkerAlpha: Float,
    isHeadshot: Boolean
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        if (isKnifeOrBomb) {
            drawCircle(RtxEmerald.copy(alpha = 0.85f), 3.2f, Offset(cx, cy))
        } else {
            val gap = (if (isAds) 6f else 11f) + spread * 42f
            val len = if (isAds) 8f else 12f
            val stroke = 2.4f
            val color = RtxEmerald.copy(alpha = 0.92f)

            drawLine(color, Offset(cx - gap - len, cy), Offset(cx - gap, cy), stroke, StrokeCap.Round)
            drawLine(color, Offset(cx + gap, cy), Offset(cx + gap + len, cy), stroke, StrokeCap.Round)
            drawLine(color, Offset(cx, cy - gap - len), Offset(cx, cy - gap), stroke, StrokeCap.Round)
            drawLine(color, Offset(cx, cy + gap), Offset(cx, cy + gap + len), stroke, StrokeCap.Round)
            drawCircle(color, 2.2f, Offset(cx, cy))
        }

        if (hitMarkerAlpha > 0.02f) {
            val hmColor = if (isHeadshot) ThreatCrimson else Color.White
            val inner = 8f
            val outer = 20f
            drawLine(hmColor.copy(alpha = hitMarkerAlpha), Offset(cx - outer, cy - outer), Offset(cx - inner, cy - inner), 3.2f, StrokeCap.Round)
            drawLine(hmColor.copy(alpha = hitMarkerAlpha), Offset(cx + outer, cy - outer), Offset(cx + inner, cy - inner), 3.2f, StrokeCap.Round)
            drawLine(hmColor.copy(alpha = hitMarkerAlpha), Offset(cx - outer, cy + outer), Offset(cx - inner, cy + inner), 3.2f, StrokeCap.Round)
            drawLine(hmColor.copy(alpha = hitMarkerAlpha), Offset(cx + outer, cy + outer), Offset(cx + inner, cy + inner), 3.2f, StrokeCap.Round)
        }
    }
}

@Composable
private fun VirtualJoystick(
    sizeDp: Dp,
    opacity: Float,
    modifier: Modifier = Modifier,
    onMove: (Float, Float) -> Unit
) {
    var knobOffset by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val maxRadiusPx = with(density) { (sizeDp * 0.34f).toPx() }

    Box(
        modifier = modifier
            .size(sizeDp)
            .alpha(opacity)
            .background(ObsidianVoid.copy(alpha = 0.42f), CircleShape)
            .border(1.5.dp, Color.White.copy(alpha = 0.36f), CircleShape)
            .pointerInput(maxRadiusPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val center = Offset(size.width * 0.5f, size.height * 0.5f)
                    fun updateFrom(pos: Offset) {
                        val dx = pos.x - center.x
                        val dy = pos.y - center.y
                        val dist = hypot(dx, dy)
                        val clamped = if (dist > maxRadiusPx && dist > 0.001f) {
                            Offset(dx / dist * maxRadiusPx, dy / dist * maxRadiusPx)
                        } else {
                            Offset(dx, dy)
                        }
                        knobOffset = clamped
                        onMove(
                            (clamped.x / maxRadiusPx).coerceIn(-1f, 1f),
                            (-clamped.y / maxRadiusPx).coerceIn(-1f, 1f)
                        )
                    }
                    updateFrom(down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        updateFrom(change.position)
                    }
                    knobOffset = Offset.Zero
                    onMove(0f, 0f)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(knobOffset.x.roundToInt(), knobOffset.y.roundToInt()) }
                .size(sizeDp * 0.40f)
                .background(RtxEmerald.copy(alpha = 0.55f), CircleShape)
                .border(1.5.dp, RtxEmerald, CircleShape)
        )
    }
}

@Composable
private fun HoldActionButton(
    id: ControlWidgetId,
    sizeDp: Dp,
    opacity: Float,
    accent: Color,
    modifier: Modifier = Modifier,
    onPressedChange: (Boolean) -> Unit
) {
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(sizeDp)
            .alpha(opacity)
            .background(
                if (pressed) accent.copy(alpha = 0.52f) else ObsidianVoid.copy(alpha = 0.56f),
                CircleShape
            )
            .border(if (pressed) 2.5.dp else 1.8.dp, accent, CircleShape)
            .pointerInput(id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    onPressedChange(true)
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                    }
                    pressed = false
                    onPressedChange(false)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = widgetIcon(id),
                contentDescription = id.labelRu,
                tint = TextPrimary,
                modifier = Modifier.size((sizeDp * 0.36f).coerceIn(18.dp, 30.dp))
            )
            Text(
                text = widgetShortTag(id),
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary
            )
        }
    }
}

@Composable
private fun TapActionButton(
    id: ControlWidgetId,
    sizeDp: Dp,
    opacity: Float,
    accent: Color,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .size(sizeDp)
            .alpha(opacity)
            .background(
                if (isActive) accent.copy(alpha = 0.38f) else ObsidianVoid.copy(alpha = 0.56f),
                CircleShape
            )
            .border(
                width = if (isActive) 2.dp else 1.4.dp,
                color = if (isActive) accent else Color.White.copy(alpha = 0.44f),
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = widgetIcon(id),
                contentDescription = id.labelRu,
                tint = if (isActive) accent else TextPrimary,
                modifier = Modifier.size((sizeDp * 0.38f).coerceIn(16.dp, 26.dp))
            )
            Text(
                text = widgetShortTag(id),
                style = MaterialTheme.typography.labelSmall,
                color = if (isActive) accent else TextPrimary
            )
        }
    }
}

@Composable
private fun PauseOverlay(
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onSettings: () -> Unit,
    onControls: () -> Unit,
    onExit: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xBF05070B))
            .safeDrawingPadding()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = TacticalSurface,
            shape = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp),
            border = BorderStroke(1.dp, BorderSteel),
            modifier = Modifier.width(340.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("ПАУЗА", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)

                Button(
                    onClick = onResume,
                    modifier = Modifier.fillMaxWidth().height(46.dp).testTag("pause_resume_button"),
                    shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RtxEmerald, contentColor = ObsidianVoid)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("ПРОДОЛЖИТЬ", style = MaterialTheme.typography.labelLarge)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onSettings,
                        modifier = Modifier.weight(1f).height(44.dp).testTag("pause_settings_button"),
                        shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                        border = BorderStroke(1.dp, BorderSteel)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("НАСТР.", color = TextPrimary, style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = onControls,
                        modifier = Modifier.weight(1f).height(44.dp).testTag("pause_controls_button"),
                        shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                        border = BorderStroke(1.dp, TacticalAmber.copy(alpha = 0.7f))
                    ) {
                        Icon(Icons.Default.Gamepad, contentDescription = null, tint = TacticalAmber, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("HUD", color = TacticalAmber, style = MaterialTheme.typography.labelSmall)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onRestart,
                        modifier = Modifier.weight(1f).height(44.dp).testTag("pause_restart_button"),
                        shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                        border = BorderStroke(1.dp, BorderSteel)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("ЗАНОВО", color = TextPrimary, style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = onExit,
                        modifier = Modifier.weight(1f).height(44.dp).testTag("pause_exit_button"),
                        shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                        border = BorderStroke(1.dp, ThreatCrimson.copy(alpha = 0.65f))
                    ) {
                        Icon(Icons.Default.Home, contentDescription = null, tint = ThreatCrimson, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("МЕНЮ", color = ThreatCrimson, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchEndOverlay(
    hud: HudSnapshot,
    onReplay: () -> Unit,
    onExit: () -> Unit
) {
    val accent = if (hud.isPlayerTeamWinner) RtxEmerald else ThreatCrimson
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xD405070B))
            .safeDrawingPadding()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = TacticalSurface,
            shape = CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp),
            border = BorderStroke(1.5.dp, accent),
            modifier = Modifier.width(420.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (hud.isPlayerTeamWinner) "ПОБЕДА КОМАНДЫ" else "ПОРАЖЕНИЕ",
                    style = MaterialTheme.typography.headlineMedium,
                    color = accent
                )
                Text(
                    text = "CT ${hud.ctScore}  :  ${hud.tScore} T",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = TextPrimary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    EndStat("УБИЙСТВА", "${hud.kills}", RtxEmerald)
                    EndStat("СМЕРТИ", "${hud.deaths}", ThreatCrimson)
                    EndStat("ХЕДШОТЫ", "${hud.headshots}", TacticalAmber)
                    EndStat("ТОЧНОСТЬ", "${hud.accuracyPercent}%", IceCyan)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onReplay,
                        modifier = Modifier.weight(1f).height(46.dp).testTag("gameover_replay_button"),
                        shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RtxEmerald, contentColor = ObsidianVoid)
                    ) {
                        Text("НОВЫЙ МАТЧ", style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(
                        onClick = onExit,
                        modifier = Modifier.weight(1f).height(46.dp).testTag("gameover_menu_button"),
                        shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                        border = BorderStroke(1.dp, BorderSteel)
                    ) {
                        Text("В МЕНЮ", color = TextPrimary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun EndStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}
