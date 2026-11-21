package com.example.ui

import android.graphics.BitmapFactory
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.FastForward
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.engine.FilamentGameplayController
import com.example.engine.GameEngine
import com.example.model.BombState
import com.example.model.ControlWidgetId
import com.example.model.GameMode
import com.example.model.GameSettings
import com.example.model.HudSnapshot
import com.example.model.ModelAssetRegistry
import com.example.model.TeamSide
import com.example.model.WeaponCategory
import com.example.model.WeaponSlot
import com.example.model.WeaponType
import com.example.ui.theme.BorderSteel
import com.example.ui.theme.ElevatedGunmetal
import com.example.ui.theme.IceCyan
import com.example.ui.theme.LocalUiMetrics
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
    modelRegistry: ModelAssetRegistry,
    settings: GameSettings,
    isPaused: Boolean,
    onSetPaused: (Boolean) -> Unit,
    onRestart: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenControlEditor: () -> Unit,
    onExitToMenu: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val m = LocalUiMetrics.current

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

    val filamentController = remember(engine, modelRegistry) {
        FilamentGameplayController(
            context = context,
            gameEngine = engine,
            registry = modelRegistry
        )
    }

    val surfaceView = remember(filamentController) {
        SurfaceView(context).also { sv ->
            filamentController.attachTo(sv)
        }
    }

    DisposableEffect(lifecycleOwner, filamentController) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> filamentController.start()
                Lifecycle.Event.ON_PAUSE -> {
                    engine.isFiring = false
                    engine.isBombActionHeld = false
                    engine.moveInputX = 0f
                    engine.moveInputY = 0f
                    filamentController.stop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        filamentController.start()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            engine.isFiring = false
            engine.isBombActionHeld = false
            engine.moveInputX = 0f
            engine.moveInputY = 0f
            filamentController.destroy()
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

        // 1. Live AAA Google Filament PBR 3D Viewport
        AndroidView(
            factory = { surfaceView },
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

        // 3. Blinding White-Orange C4 Explosion Flash Overlay
        if (hud.explosionFlashAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFFFF4DC).copy(alpha = (hud.explosionFlashAlpha * 0.92f).coerceIn(0f, 0.95f)),
                                Color(0xFFFF8822).copy(alpha = (hud.explosionFlashAlpha * 0.58f).coerceIn(0f, 0.72f))
                            )
                        )
                    )
            )
        }

        // 4. Damage Vignette Flash
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

        // 5. AWP 4x Optical Scope Overlay OR Dynamic Precision Crosshair
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

        // 6. Top-Left Circular CS2 Map Radar + $ Money + Callout + FPS
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .safeDrawingPadding()
                .padding(start = m.s(10f), top = m.s(6f)),
            verticalArrangement = Arrangement.spacedBy(m.s(4f))
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(m.s(7f))
            ) {
                TacticalRadar(hud = hud)
                Column(verticalArrangement = Arrangement.spacedBy(m.s(4f))) {
                    Surface(
                        color = Color(0xB80A111A),
                        shape = RoundedCornerShape(m.s(6f)),
                        border = BorderStroke(1.dp, RtxEmerald.copy(alpha = 0.55f))
                    ) {
                        Text(
                            text = "$${hud.money}",
                            fontSize = m.sp(12.5f),
                            fontWeight = FontWeight.Black,
                            color = RtxEmerald,
                            modifier = Modifier.padding(horizontal = m.s(8f), vertical = m.s(3f))
                        )
                    }
                    if (hud.calloutName.isNotEmpty()) {
                        Surface(
                            color = Color(0xA60A1018),
                            shape = RoundedCornerShape(m.s(5f))
                        ) {
                            Text(
                                text = hud.calloutName,
                                fontSize = m.sp(9.5f),
                                fontWeight = FontWeight.Bold,
                                color = TacticalAmber,
                                modifier = Modifier.padding(horizontal = m.s(7f), vertical = m.s(2f))
                            )
                        }
                    }
                    if (settings.graphics.showFps) {
                        Text(
                            text = "${hud.fps} FPS • ${settings.graphics.preset.titleRu}",
                            fontSize = m.sp(9f),
                            color = Color.White.copy(alpha = 0.78f)
                        )
                    }
                }
            }
        }

        // 7. Top-Center CS2 Round Scoreboard + Warmup Skip Banner + Animated Match Banners / Killfeed
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(top = m.s(6f)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(m.s(4f))
        ) {
            Cs2ScoreboardBar(hud = hud)

            AnimatedVisibility(
                visible = hud.isWarmup && !isPaused && !hud.isMatchFinished,
                enter = fadeIn(tween(220)) + expandVertically(),
                exit = fadeOut(tween(180)) + shrinkVertically()
            ) {
                val secsLeft = hud.warmupTimeSec.roundToInt().coerceAtLeast(0)
                val warmupTimeFormatted = String.format("0:%02d", secsLeft)
                Surface(
                    color = Color(0xDC0C1622),
                    shape = CutCornerShape(topStart = m.s(9f), bottomEnd = m.s(9f)),
                    border = BorderStroke(1.2.dp, TacticalAmber.copy(alpha = 0.85f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = m.s(10f), vertical = m.s(4f)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(m.s(10f))
                    ) {
                        Text(
                            text = "РАЗМИНКА • $warmupTimeFormatted • $16 000",
                            fontSize = m.sp(10.5f),
                            fontWeight = FontWeight.ExtraBold,
                            color = TacticalAmber
                        )
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(m.s(6f)))
                                .background(RtxEmerald)
                                .clickable { engine.skipWarmup() }
                                .padding(horizontal = m.s(9f), vertical = m.s(4f))
                                .testTag("warmup_skip_button"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(m.s(3f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.FastForward,
                                contentDescription = "Пропустить разминку",
                                tint = ObsidianVoid,
                                modifier = Modifier.size(m.s(13f))
                            )
                            Text(
                                text = "ПРОПУСТИТЬ",
                                fontSize = m.sp(9.5f),
                                fontWeight = FontWeight.Black,
                                color = ObsidianVoid
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = hud.bannerText.isNotEmpty() && !hud.isWarmup,
                enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.94f, animationSpec = tween(200)),
                exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.96f, animationSpec = tween(200))
            ) {
                Surface(
                    color = Color(0xD10A111A),
                    shape = CutCornerShape(topStart = m.s(8f), bottomEnd = m.s(8f)),
                    border = BorderStroke(1.dp, TacticalAmber.copy(alpha = 0.78f))
                ) {
                    Text(
                        text = hud.bannerText,
                        fontSize = m.sp(11f),
                        fontWeight = FontWeight.ExtraBold,
                        color = TacticalAmber,
                        modifier = Modifier.padding(horizontal = m.s(13f), vertical = m.s(4f))
                    )
                }
            }

            AnimatedVisibility(
                visible = hud.killFeedText.isNotEmpty(),
                enter = fadeIn(tween(180)) + expandVertically(),
                exit = fadeOut(tween(220)) + shrinkVertically()
            ) {
                Surface(
                    color = Color(0xAD14090C),
                    shape = RoundedCornerShape(m.s(5f)),
                    border = BorderStroke(1.dp, ThreatCrimson.copy(alpha = 0.50f))
                ) {
                    Text(
                        text = hud.killFeedText,
                        fontSize = m.sp(9.5f),
                        color = TextPrimary,
                        modifier = Modifier.padding(horizontal = m.s(9f), vertical = m.s(2f))
                    )
                }
            }
        }

        // 8. Bottom-Left Compact Vitals Strip (Smooth animated HP + Armor + Defuse Kit)
        val smoothHp by animateIntAsState(targetValue = hud.hp, animationSpec = tween(180), label = "hp_anim")
        val smoothArmor by animateIntAsState(targetValue = hud.armor, animationSpec = tween(180), label = "armor_anim")
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .safeDrawingPadding()
                .padding(start = m.s(12f), bottom = m.s(8f)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.s(7f))
        ) {
            Surface(
                color = Color(0xB80B111A),
                shape = RoundedCornerShape(m.s(8f)),
                border = BorderStroke(1.dp, BorderSteel.copy(alpha = 0.75f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = m.s(10f), vertical = m.s(5f)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(m.s(9f))
                ) {
                    val hpColor = when {
                        smoothHp > 50 -> RtxEmerald
                        smoothHp > 25 -> TacticalAmber
                        else -> ThreatCrimson
                    }
                    Text(
                        text = "+ $smoothHp",
                        fontSize = m.sp(13f),
                        fontWeight = FontWeight.Black,
                        color = hpColor
                    )
                    Text(
                        text = "БР $smoothArmor",
                        fontSize = m.sp(11f),
                        fontWeight = FontWeight.Bold,
                        color = IceCyan
                    )
                    if (hud.hasDefuseKit) {
                        Text(
                            text = "САПЁР",
                            fontSize = m.sp(9.5f),
                            fontWeight = FontWeight.Bold,
                            color = RtxEmerald
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = hud.isReloading,
                enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.92f, animationSpec = tween(150)),
                exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.92f, animationSpec = tween(150))
            ) {
                Surface(
                    color = Color(0xCC0D1420),
                    shape = RoundedCornerShape(m.s(6f)),
                    border = BorderStroke(1.dp, TacticalAmber.copy(alpha = 0.65f))
                ) {
                    Column(
                        modifier = Modifier
                            .width(m.s(114f))
                            .padding(horizontal = m.s(8f), vertical = m.s(4f))
                    ) {
                        Text(
                            text = "ПЕРЕЗАРЯДКА",
                            fontSize = m.sp(8.5f),
                            fontWeight = FontWeight.Bold,
                            color = TacticalAmber
                        )
                        LinearProgressIndicator(
                            progress = { hud.reloadProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(m.s(4f))
                                .clip(RoundedCornerShape(2.dp)),
                            color = TacticalAmber,
                            trackColor = ElevatedGunmetal
                        )
                    }
                }
            }
        }

        // 9. Contextual C4 Plant / Defuse Hold Button
        AnimatedVisibility(
            visible = !isPaused && !isShopOpen && !hud.isPlayerDead && (hud.canPlantBomb || hud.canDefuseBomb),
            enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.92f, animationSpec = tween(180)),
            exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.92f, animationSpec = tween(150)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(bottom = m.s(16f))
        ) {
            BombActionHoldButton(
                isPlant = hud.canPlantBomb,
                siteLabel = if (hud.canPlantBomb) hud.inPlantZoneLabel else hud.bombSiteLabel,
                progress = hud.bombActionProgress,
                onHeldChange = { held -> engine.isBombActionHeld = held }
            )
        }

        // 10. Dead Spectator Banner
        AnimatedVisibility(
            visible = hud.isPlayerDead && !hud.isMatchFinished && !isPaused,
            enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.92f, animationSpec = tween(220)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                color = Color(0xCC12090D),
                shape = CutCornerShape(topStart = m.s(10f), bottomEnd = m.s(10f)),
                border = BorderStroke(1.dp, ThreatCrimson.copy(alpha = 0.72f))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = m.s(20f), vertical = m.s(11f)),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "ВЫ УБИТЫ",
                        fontSize = m.sp(15f),
                        fontWeight = FontWeight.Black,
                        color = ThreatCrimson
                    )
                    Text(
                        text = when {
                            hud.isWarmup -> "Возрождение (разминка)..."
                            hud.mode == GameMode.BOMB_DEFUSAL -> "Ожидание завершения раунда (CT: ${hud.ctAlive} • T: ${hud.tAlive})"
                            else -> "Возрождение..."
                        },
                        fontSize = m.sp(10.5f),
                        color = TextSecondary
                    )
                }
            }
        }

        // 11. Adaptive Customizable Landscape Controls + Single Wide Swipe Weapon Button + Shop Button
        if (!isPaused && !hud.isMatchFinished && !isShopOpen) {
            val layout = settings.controlLayout

            val joyCfg = layout.get(ControlWidgetId.JOYSTICK)
            if (joyCfg.visible) {
                val sizeDp = widgetBaseWidthDp(ControlWidgetId.JOYSTICK) * joyCfg.scale * m.scale
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

            listOf(ControlWidgetId.FIRE_RIGHT, ControlWidgetId.FIRE_LEFT).forEach { fireId ->
                val cfg = layout.get(fireId)
                if (cfg.visible) {
                    val sizeDp = (widgetBaseWidthDp(fireId) * cfg.scale * m.scale).coerceAtLeast(48.dp)
                    val sizePx = with(density) { sizeDp.toPx() }
                    HoldActionButton(
                        id = fireId,
                        sizeDp = sizeDp,
                        opacity = cfg.opacity * if (hud.isEquipping) 0.42f else 1f,
                        accent = if (hud.isEquipping) BorderSteel else ThreatCrimson,
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
                    val sizeDp = (widgetBaseWidthDp(id) * cfg.scale * m.scale).coerceAtLeast(44.dp)
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

            val swipeCfg = layout.get(ControlWidgetId.WEAPON_SWIPE)
            if (swipeCfg.visible) {
                val widthDp = widgetBaseWidthDp(ControlWidgetId.WEAPON_SWIPE) * swipeCfg.scale * m.scale
                val heightDp = widgetBaseHeightDp(ControlWidgetId.WEAPON_SWIPE) * swipeCfg.scale * m.scale
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

        // 12. Compact In-Game Buy Menu Overlay (Smooth scale + fade)
        AnimatedVisibility(
            visible = isShopOpen && !isPaused && !hud.isMatchFinished,
            enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.94f, animationSpec = tween(180)),
            exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.95f, animationSpec = tween(150))
        ) {
            CompactBuyMenuOverlay(
                hud = hud,
                onBuyWeapon = { weapon -> engine.buyWeapon(weapon) },
                onBuyArmor = { engine.buyArmor() },
                onBuyDefuseKit = { engine.buyDefuseKit() },
                onClose = { isShopOpen = false }
            )
        }

        // 13. Pause Overlay (Smooth fade + scale, clean without 3D-models viewer)
        AnimatedVisibility(
            visible = isPaused && !hud.isMatchFinished,
            enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.94f, animationSpec = tween(200)),
            exit = fadeOut(tween(160)) + scaleOut(targetScale = 0.95f, animationSpec = tween(160))
        ) {
            PauseOverlay(
                onResume = { onSetPaused(false) },
                onRestart = onRestart,
                onSettings = onOpenSettings,
                onControls = onOpenControlEditor,
                onExit = onExitToMenu
            )
        }

        // 14. Match Finished Summary Overlay
        AnimatedVisibility(
            visible = hud.isMatchFinished,
            enter = fadeIn(tween(260)) + scaleIn(initialScale = 0.92f, animationSpec = tween(260)),
            exit = fadeOut(tween(180))
        ) {
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
    val m = LocalUiMetrics.current
    val timeSecInt = when {
        hud.isWarmup -> hud.warmupTimeSec.roundToInt().coerceAtLeast(0)
        hud.bombState == BombState.PLANTED -> hud.bombCountdownSec.roundToInt().coerceAtLeast(0)
        else -> hud.roundTimeSec.roundToInt().coerceAtLeast(0)
    }
    val mins = timeSecInt / 60
    val secs = timeSecInt % 60
    val timeText = String.format("%d:%02d", mins, secs)

    Surface(
        color = Color(0xC20A101A),
        shape = CutCornerShape(topStart = m.s(10f), bottomEnd = m.s(10f)),
        border = BorderStroke(
            width = 1.dp,
            color = when {
                hud.isWarmup -> TacticalAmber.copy(alpha = 0.85f)
                hud.bombState == BombState.PLANTED -> ThreatCrimson
                else -> Color.White.copy(alpha = 0.16f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = m.s(11f), vertical = m.s(4f)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.s(11f))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(m.s(5f))
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("CT", fontSize = m.sp(8.5f), fontWeight = FontWeight.Bold, color = IceCyan)
                    Text("ЖИВ: ${hud.ctAlive}", fontSize = m.sp(7.5f), color = TextSecondary)
                }
                Text(
                    text = "${hud.ctScore}",
                    fontSize = m.sp(15f),
                    fontWeight = FontWeight.Black,
                    color = IceCyan
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (hud.isWarmup) {
                    Text(
                        text = timeText,
                        fontSize = m.sp(12.5f),
                        fontWeight = FontWeight.Black,
                        color = TacticalAmber
                    )
                    Text(
                        text = "РАЗМИНКА",
                        fontSize = m.sp(7.5f),
                        fontWeight = FontWeight.Bold,
                        color = TacticalAmber
                    )
                } else if (hud.bombState == BombState.PLANTED) {
                    Text(
                        text = "C4 ${hud.bombSiteLabel} • $timeText",
                        fontSize = m.sp(11.5f),
                        fontWeight = FontWeight.Black,
                        color = ThreatCrimson
                    )
                } else {
                    Text(
                        text = timeText,
                        fontSize = m.sp(12.5f),
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = if (hud.mode == GameMode.BOMB_DEFUSAL) "Р${hud.roundNumber} • ДО ${hud.roundsToWin}" else "БОЙ НАСМЕРТЬ",
                        fontSize = m.sp(7.5f),
                        color = TextSecondary
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(m.s(5f))
            ) {
                Text(
                    text = "${hud.tScore}",
                    fontSize = m.sp(15f),
                    fontWeight = FontWeight.Black,
                    color = TacticalAmber
                )
                Column(horizontalAlignment = Alignment.Start) {
                    Text("T", fontSize = m.sp(8.5f), fontWeight = FontWeight.Bold, color = TacticalAmber)
                    Text("ЖИВ: ${hud.tAlive}", fontSize = m.sp(7.5f), color = TextSecondary)
                }
            }
        }
    }
}

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
    val m = LocalUiMetrics.current
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { m.s(20f).toPx() }
    val shape = RoundedCornerShape(m.s(11f))
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
            .padding(horizontal = m.s(8f), vertical = m.s(4f))
    ) {
        if (hud.isEquipping) {
            LinearProgressIndicator(
                progress = { hud.equipProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(m.s(2.5f))
                    .align(Alignment.TopCenter)
                    .clip(RoundedCornerShape(2.dp)),
                color = accentColor,
                trackColor = Color.White.copy(alpha = 0.14f)
            )
        } else if (hud.mode == GameMode.BOMB_DEFUSAL && hud.hasBomb) {
            Text(
                text = "▲ C4",
                fontSize = m.sp(7.5f),
                fontWeight = FontWeight.Bold,
                color = ThreatCrimson.copy(alpha = 0.92f),
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = m.s(4f), bottom = m.s(7f)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            WeaponSilhouetteIcon(
                weapon = hud.activeWeapon,
                tint = if (hud.isEquipping) TextPrimary.copy(alpha = 0.65f) else TextPrimary,
                modifier = Modifier
                    .width(widthDp * 0.44f)
                    .height(heightDp * 0.56f)
            )

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (swipeFeedback.isNotEmpty()) swipeFeedback else hud.activeWeapon.shortLabel,
                    fontSize = m.sp(11f),
                    fontWeight = FontWeight.Black,
                    color = accentColor,
                    maxLines = 1
                )
                val ammoLabel = when {
                    hud.isEquipping -> "ДОСТАЁТ..."
                    hud.activeWeapon == WeaponType.KNIFE -> "БЛИЖНИЙ"
                    hud.activeWeapon == WeaponType.C4_BOMB -> "ЗАРЯД C4"
                    else -> "${hud.ammo} / ${hud.reserveAmmo}"
                }
                Text(
                    text = ammoLabel,
                    fontSize = m.sp(9.5f),
                    color = if (hud.isEquipping) TacticalAmber else TextPrimary
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("◄ НОЖ", fontSize = m.sp(7.5f), color = TextSecondary)
            Text(
                text = if (hud.primaryWeapon != null) "▼ ${hud.primaryWeapon.shortLabel}" else "▼ ОСН",
                fontSize = m.sp(7.5f),
                color = if (hud.primaryWeapon != null) RtxEmerald.copy(alpha = 0.85f) else TextSecondary.copy(alpha = 0.5f)
            )
            Text("${hud.pistolWeapon.shortLabel} ►", fontSize = m.sp(7.5f), color = TacticalAmber.copy(alpha = 0.85f))
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
                drawRect(tint, Offset(w * 0.20f, h * 0.26f), Size(w * if (weapon.isSuppressed) 0.70f else 0.52f, h * 0.22f))
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
                drawRect(tint, Offset(w * 0.34f, h * 0.14f), Size(w * 0.30f, h * 0.14f))
                drawRect(tint, Offset(w * 0.06f, h * 0.36f), Size(w * 0.88f, h * 0.16f))
                drawRect(tint, Offset(w * 0.06f, h * 0.48f), Size(w * 0.22f, h * 0.26f))
                drawRect(tint, Offset(w * 0.44f, h * 0.52f), Size(w * 0.10f, h * 0.22f))
            }
            else -> {
                drawRect(tint, Offset(w * 0.06f, h * 0.34f), Size(w * 0.86f, h * 0.19f))
                drawRect(tint, Offset(w * 0.06f, h * 0.46f), Size(w * 0.20f, h * 0.26f))
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
    val m = LocalUiMetrics.current
    val isFreeTdm = hud.isWarmup || hud.mode == GameMode.TEAM_DEATHMATCH
    val pistols = WeaponType.entries.filter { it.category == WeaponCategory.PISTOLS }
    val smgs = WeaponType.entries.filter { it.category == WeaponCategory.SMGS }
    val rifles = WeaponType.entries.filter { it.category == WeaponCategory.RIFLES }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x9E060A11))
            .clickable(onClick = onClose)
            .safeDrawingPadding()
            .padding(m.s(12f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Color(0xEC0D1420),
            shape = CutCornerShape(topStart = m.s(14f), bottomEnd = m.s(14f)),
            border = BorderStroke(1.2.dp, RtxEmerald.copy(alpha = 0.6f)),
            modifier = Modifier
                .widthIn(max = m.s(710f))
                .fillMaxWidth()
                .clickable(enabled = false) {}
                .testTag("compact_buy_menu")
        ) {
            Column(
                modifier = Modifier.padding(m.s(12f)),
                verticalArrangement = Arrangement.spacedBy(m.s(9f))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(m.s(9f))
                    ) {
                        Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = RtxEmerald, modifier = Modifier.size(m.s(19f)))
                        Text(
                            text = "МАГАЗИН ОРУЖИЯ",
                            fontSize = m.sp(14f),
                            fontWeight = FontWeight.Black,
                            color = TextPrimary
                        )
                        Surface(
                            color = RtxEmerald.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(m.s(6f)),
                            border = BorderStroke(1.dp, RtxEmerald.copy(alpha = 0.6f))
                        ) {
                            Text(
                                text = when {
                                    hud.isWarmup -> "РАЗМИНКА • БЕСПЛАТНО"
                                    isFreeTdm -> "БЕСПЛАТНО (DM)"
                                    else -> "$${hud.money}"
                                },
                                fontSize = m.sp(12f),
                                fontWeight = FontWeight.Black,
                                color = RtxEmerald,
                                modifier = Modifier.padding(horizontal = m.s(9f), vertical = m.s(3f))
                            )
                        }
                    }

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(m.s(34f))
                            .testTag("buy_menu_close")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть магазин", tint = TextPrimary)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(m.s(7f))
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
    val m = LocalUiMetrics.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(m.s(5f))
    ) {
        Text(
            text = title,
            fontSize = m.sp(9.5f),
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
    val m = LocalUiMetrics.current
    val shape = RoundedCornerShape(m.s(8f))
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
            .padding(horizontal = m.s(8f), vertical = m.s(8f))
            .testTag(tag)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = m.sp(10.5f),
                color = if (enabled || owned) TextPrimary else TextSecondary.copy(alpha = 0.45f),
                maxLines = 1
            )
            Text(
                text = priceText,
                fontSize = m.sp(9.5f),
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
    val m = LocalUiMetrics.current
    val accent = if (isPlant) ThreatCrimson else IceCyan
    val label = if (isPlant) "УДЕРЖ. ЗАЛОЖИТЬ C4 ($siteLabel)" else "УДЕРЖ. РАЗМИНИРОВАТЬ C4 ($siteLabel)"
    Surface(
        color = Color(0xD90E141F),
        shape = CutCornerShape(topStart = m.s(10f), bottomEnd = m.s(10f)),
        border = BorderStroke(1.8.dp, accent),
        modifier = modifier
            .width(m.s(236f))
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
            modifier = Modifier.padding(horizontal = m.s(11f), vertical = m.s(7f)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(m.s(5f))
        ) {
            Text(
                text = label,
                fontSize = m.sp(11f),
                fontWeight = FontWeight.Black,
                color = accent
            )
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(m.s(6f))
                    .clip(RoundedCornerShape(3.dp)),
                color = accent,
                trackColor = ElevatedGunmetal
            )
        }
    }
}

@Composable
private fun TacticalRadar(hud: HudSnapshot) {
    val context = LocalContext.current
    val m = LocalUiMetrics.current
    val radarSize = m.s(84f)

    // Load real CS2 radar PNG from bundled assets
    val radarBitmap: ImageBitmap? = remember(hud.mapId) {
        runCatching {
            context.assets.open(hud.mapId.radarAssetPath).use { stream ->
                BitmapFactory.decodeStream(stream)?.asImageBitmap()
            }
        }.getOrNull()
    }

    Box(
        modifier = Modifier
            .size(radarSize)
            .clip(CircleShape)
            .background(Color(0xB8080E16), CircleShape)
            .border(1.5.dp, RtxEmerald.copy(alpha = 0.62f), CircleShape)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = Offset(size.width * 0.5f, size.height * 0.5f)
            val r = size.minDimension * 0.48f

            // Real CS2 radar map backdrop rotated with player heading
            if (radarBitmap != null) {
                rotate(degrees = hud.playerYawDeg, pivot = c) {
                    drawImage(
                        image = radarBitmap,
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        alpha = 0.68f
                    )
                }
            }

            drawCircle(Color.White.copy(alpha = 0.16f), r * 0.52f, c, style = Stroke(1f))
            drawLine(RtxEmerald.copy(alpha = 0.45f), c, Offset(c.x - r * 0.48f, c.y - r * 0.84f), 1.5f)
            drawLine(RtxEmerald.copy(alpha = 0.45f), c, Offset(c.x + r * 0.48f, c.y - r * 0.84f), 1.5f)

            // Player center marker
            drawCircle(Color.White, 3.4f, c)

            // Allies, Enemies & Planted C4
            val yawRad = Math.toRadians(hud.playerYawDeg.toDouble()).toFloat()
            val cosY = cos(yawRad)
            val sinY = sin(yawRad)
            for (blip in hud.radarBlips) {
                val localX = blip.relX * cosY - blip.relZ * sinY
                val localZ = blip.relX * sinY + blip.relZ * cosY
                val nx = (localX / 44f).coerceIn(-0.92f, 0.92f)
                val nz = (localZ / 44f).coerceIn(-0.92f, 0.92f)
                val bx = c.x + nx * r
                val by = c.y + nz * r
                val color = when {
                    blip.isBomb -> TacticalAmber
                    blip.isAlly -> RtxEmerald
                    else -> ThreatCrimson
                }
                drawCircle(color, if (blip.isBomb) 5.0f else 3.8f, Offset(bx, by))
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
    val m = LocalUiMetrics.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xBF05070B))
            .safeDrawingPadding()
            .padding(m.s(16f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = TacticalSurface,
            shape = CutCornerShape(topStart = m.s(14f), bottomEnd = m.s(14f)),
            border = BorderStroke(1.dp, BorderSteel),
            modifier = Modifier.width(m.s(350f))
        ) {
            Column(
                modifier = Modifier.padding(m.s(16f)),
                verticalArrangement = Arrangement.spacedBy(m.s(8f)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("ПАУЗА", fontSize = m.sp(18f), fontWeight = FontWeight.Black, color = TextPrimary)

                Button(
                    onClick = onResume,
                    modifier = Modifier.fillMaxWidth().height(m.s(44f).coerceAtLeast(48.dp)).testTag("pause_resume_button"),
                    shape = CutCornerShape(topStart = m.s(8f), bottomEnd = m.s(8f)),
                    colors = ButtonDefaults.buttonColors(containerColor = RtxEmerald, contentColor = ObsidianVoid)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(m.s(5f)))
                    Text("ПРОДОЛЖИТЬ", fontSize = m.sp(12.5f), fontWeight = FontWeight.Black)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.s(8f))) {
                    OutlinedButton(
                        onClick = onSettings,
                        modifier = Modifier.weight(1f).height(m.s(42f).coerceAtLeast(44.dp)).testTag("pause_settings_button"),
                        shape = CutCornerShape(topStart = m.s(8f), bottomEnd = m.s(8f)),
                        border = BorderStroke(1.dp, BorderSteel)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(m.s(16f)))
                        Spacer(modifier = Modifier.width(m.s(4f)))
                        Text("НАСТРОЙКИ", color = TextPrimary, fontSize = m.sp(10.5f))
                    }
                    OutlinedButton(
                        onClick = onControls,
                        modifier = Modifier.weight(1f).height(m.s(42f).coerceAtLeast(44.dp)).testTag("pause_controls_button"),
                        shape = CutCornerShape(topStart = m.s(8f), bottomEnd = m.s(8f)),
                        border = BorderStroke(1.dp, TacticalAmber.copy(alpha = 0.7f))
                    ) {
                        Icon(Icons.Default.Gamepad, contentDescription = null, tint = TacticalAmber, modifier = Modifier.size(m.s(16f)))
                        Spacer(modifier = Modifier.width(m.s(4f)))
                        Text("УПРАВЛЕНИЕ", color = TacticalAmber, fontSize = m.sp(10.5f))
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.s(8f))) {
                    OutlinedButton(
                        onClick = onRestart,
                        modifier = Modifier.weight(1f).height(m.s(42f).coerceAtLeast(44.dp)).testTag("pause_restart_button"),
                        shape = CutCornerShape(topStart = m.s(8f), bottomEnd = m.s(8f)),
                        border = BorderStroke(1.dp, BorderSteel)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(m.s(16f)))
                        Spacer(modifier = Modifier.width(m.s(4f)))
                        Text("ЗАНОВО", color = TextPrimary, fontSize = m.sp(10.5f))
                    }
                    OutlinedButton(
                        onClick = onExit,
                        modifier = Modifier.weight(1f).height(m.s(42f).coerceAtLeast(44.dp)).testTag("pause_exit_button"),
                        shape = CutCornerShape(topStart = m.s(8f), bottomEnd = m.s(8f)),
                        border = BorderStroke(1.dp, ThreatCrimson.copy(alpha = 0.65f))
                    ) {
                        Icon(Icons.Default.Home, contentDescription = null, tint = ThreatCrimson, modifier = Modifier.size(m.s(16f)))
                        Spacer(modifier = Modifier.width(m.s(4f)))
                        Text("МЕНЮ", color = ThreatCrimson, fontSize = m.sp(10.5f))
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
    val m = LocalUiMetrics.current
    val accent = if (hud.isPlayerTeamWinner) RtxEmerald else ThreatCrimson
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xD405070B))
            .safeDrawingPadding()
            .padding(m.s(18f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = TacticalSurface,
            shape = CutCornerShape(topStart = m.s(16f), bottomEnd = m.s(16f)),
            border = BorderStroke(1.5.dp, accent),
            modifier = Modifier.width(m.s(410f))
        ) {
            Column(
                modifier = Modifier.padding(m.s(18f)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(m.s(10f))
            ) {
                Text(
                    text = if (hud.isPlayerTeamWinner) "ПОБЕДА КОМАНДЫ" else "ПОРАЖЕНИЕ",
                    fontSize = m.sp(20f),
                    fontWeight = FontWeight.Black,
                    color = accent
                )
                Text(
                    text = "CT ${hud.ctScore}  :  ${hud.tScore} T",
                    fontSize = m.sp(17f),
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
                    horizontalArrangement = Arrangement.spacedBy(m.s(10f))
                ) {
                    Button(
                        onClick = onReplay,
                        modifier = Modifier.weight(1f).height(m.s(44f).coerceAtLeast(48.dp)).testTag("gameover_replay_button"),
                        shape = CutCornerShape(topStart = m.s(8f), bottomEnd = m.s(8f)),
                        colors = ButtonDefaults.buttonColors(containerColor = RtxEmerald, contentColor = ObsidianVoid)
                    ) {
                        Text("НОВЫЙ МАТЧ", fontSize = m.sp(12f), fontWeight = FontWeight.Black)
                    }
                    OutlinedButton(
                        onClick = onExit,
                        modifier = Modifier.weight(1f).height(m.s(44f).coerceAtLeast(48.dp)).testTag("gameover_menu_button"),
                        shape = CutCornerShape(topStart = m.s(8f), bottomEnd = m.s(8f)),
                        border = BorderStroke(1.dp, BorderSteel)
                    ) {
                        Text("В МЕНЮ", color = TextPrimary, fontSize = m.sp(12f), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun EndStat(label: String, value: String, color: Color) {
    val m = LocalUiMetrics.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = m.sp(15f), fontWeight = FontWeight.Black, color = color)
        Text(label, fontSize = m.sp(9.5f), color = TextSecondary)
    }
}
