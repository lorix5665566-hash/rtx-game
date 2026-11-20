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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.engine.GameEngine
import com.example.engine.ShooterGlRenderer
import com.example.model.ControlWidgetConfig
import com.example.model.ControlWidgetId
import com.example.model.GameSettings
import com.example.model.HudSnapshot
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
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
    BackHandler {
        onSetPaused(!isPaused)
    }

    var hud by remember { mutableStateOf(engine.latestHud) }
    LaunchedEffect(engine) {
        while (true) {
            hud = engine.latestHud
            delay(33L)
        }
    }

    var glViewRef by remember { mutableStateOf<GLSurfaceView?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, glViewRef) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> glViewRef?.onResume()
                Lifecycle.Event.ON_PAUSE -> {
                    engine.isFiring = false
                    engine.moveInputX = 0f
                    engine.moveInputY = 0f
                    glViewRef?.onPause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            engine.isFiring = false
            engine.moveInputX = 0f
            engine.moveInputY = 0f
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val heightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)

        LaunchedEffect(glViewRef, settings.graphics.resolutionScale, widthPx, heightPx) {
            val view = glViewRef ?: return@LaunchedEffect
            val scale = settings.graphics.resolutionScale.coerceIn(0.6f, 1.0f)
            if (scale >= 0.99f) {
                view.holder.setSizeFromLayout()
            } else {
                val targetW = (widthPx * scale).roundToInt().coerceAtLeast(320)
                val targetH = (heightPx * scale).roundToInt().coerceAtLeast(320)
                view.holder.setFixedSize(targetW, targetH)
            }
        }

        // --- 1. LIVE OPENGL ES 3.0 PBR/RTX 3D ENGINE VIEWPORT ---
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    setEGLConfigChooser(8, 8, 8, 8, 24, 0)
                    preserveEGLContextOnPause = true
                    setRenderer(ShooterGlRenderer(engine))
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    glViewRef = this
                }
            }
        )

        // --- 2. FULL-SCREEN MULTI-TOUCH CAMERA LOOK LAYER ---
        if (!isPaused && !hud.isGameOver && !hud.isVictory) {
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

        // --- 3. 4X OPTICAL SNIPER SCOPE OVERLAY (AWM .338 ADS) ---
        if (!isPaused && !hud.isGameOver && !hud.isVictory && hud.weapon.hasSniperScope && hud.adsBlend > 0.72f) {
            SniperScopeOverlay(modifier = Modifier.fillMaxSize())
        }

        // --- 4. DAMAGE FLASH VIGNETTE ---
        if (hud.damageFlashAlpha > 0.02f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            0.45f to Color.Transparent,
                            1.0f to ThreatCrimson.copy(alpha = hud.damageFlashAlpha * 0.65f)
                        )
                    )
            )
        }

        // --- 5. DYNAMIC TACTICAL CROSSHAIR & HITMARKER ---
        if (!isPaused && !hud.isGameOver && !hud.isVictory) {
            CrosshairOverlay(hud = hud, modifier = Modifier.fillMaxSize())
        }

        // --- 6. TOP TACTICAL HUD: RADAR, WAVE, SCORE, FPS ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TacticalRadar(hud = hud)
                Surface(
                    color = ObsidianVoid.copy(alpha = 0.72f),
                    shape = CutCornerShape(6.dp),
                    border = BorderStroke(1.dp, BorderSteel)
                ) {
                    Text(
                        text = if (hud.totalWaves > 0) {
                            "В.${hud.wave}/${hud.totalWaves} • ЦЕЛИ: ${hud.enemiesAlive}"
                        } else {
                            "ВОЛНА ${hud.wave} • ЦЕЛИ: ${hud.enemiesAlive}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TacticalAmber,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    color = ObsidianVoid.copy(alpha = 0.74f),
                    shape = CutCornerShape(8.dp),
                    border = BorderStroke(1.dp, RtxEmerald.copy(alpha = 0.55f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("СЧЁТ ${hud.score}", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                        Text("УБ. ${hud.kills}", style = MaterialTheme.typography.labelMedium, color = RtxEmerald)
                    }
                }
                if (settings.graphics.showFps) {
                    Surface(
                        color = ObsidianVoid.copy(alpha = 0.65f),
                        shape = CutCornerShape(4.dp)
                    ) {
                        Text(
                            text = "${hud.fps} FPS • ${settings.graphics.preset.titleRu}",
                            style = MaterialTheme.typography.labelSmall,
                            color = RtxEmerald,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(56.dp))
        }

        // --- 7. WAVE / EVENT BANNER ---
        if (hud.bannerText.isNotEmpty() && !hud.isGameOver && !hud.isVictory) {
            Surface(
                color = ObsidianVoid.copy(alpha = 0.80f),
                shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
                border = BorderStroke(1.dp, RtxEmerald),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .safeDrawingPadding()
                    .padding(top = 74.dp)
            ) {
                Text(
                    text = hud.bannerText,
                    style = MaterialTheme.typography.titleMedium,
                    color = RtxEmerald,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }

        // --- 8. BOTTOM TELEMETRY: 1-TAP WEAPON STRIP + HP / ARMOR / AMMO ---
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .widthIn(max = 330.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // 1-Tap Arsenal Switcher Strip: M4A1 | AK-47 | AWM
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                WeaponType.entries.forEach { w ->
                    val active = hud.weapon == w
                    val shape = CutCornerShape(6.dp)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(shape)
                            .background(
                                if (active) TacticalAmber.copy(alpha = 0.28f)
                                else ObsidianVoid.copy(alpha = 0.65f)
                            )
                            .border(
                                width = if (active) 1.5.dp else 1.dp,
                                color = if (active) TacticalAmber else BorderSteel.copy(alpha = 0.7f),
                                shape = shape
                            )
                            .clickable { engine.selectWeapon(w) }
                            .padding(vertical = 4.dp)
                            .testTag("hud_weapon_${w.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = w.shortLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) TacticalAmber else TextSecondary
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Surface(
                    color = ObsidianVoid.copy(alpha = 0.72f),
                    shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                    border = BorderStroke(1.dp, BorderSteel),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        StatusBarRow(label = "БР", value = hud.armor, color = IceCyan)
                        StatusBarRow(
                            label = "HP",
                            value = hud.hp,
                            color = if (hud.hp <= 30) ThreatCrimson else RtxEmerald
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    color = ObsidianVoid.copy(alpha = 0.78f),
                    shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                    border = BorderStroke(
                        1.dp,
                        if (hud.ammo <= maxOf(1, hud.magSize / 4)) ThreatCrimson else RtxEmerald.copy(alpha = 0.6f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = if (hud.isReloading) "ПЕРЕЗАРЯДКА" else hud.weapon.shortLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hud.isReloading) TacticalAmber else TextSecondary
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = hud.ammo.toString().padStart(2, '0'),
                                style = MaterialTheme.typography.headlineMedium,
                                color = if (hud.ammo <= maxOf(1, hud.magSize / 4)) ThreatCrimson else TextPrimary
                            )
                            Text(
                                text = " / ${if (hud.reserveAmmo >= 999) "∞" else hud.reserveAmmo}",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary,
                                modifier = Modifier.padding(bottom = 3.dp)
                            )
                        }
                    }
                }
            }
        }

        // --- 9. CUSTOMIZABLE MULTI-TOUCH GAMEPLAY CONTROLS ---
        if (!isPaused && !hud.isGameOver && !hud.isVictory) {
            val layout = settings.controlLayout
            ControlWidgetId.entries.forEach { id ->
                val cfg = layout.get(id)
                if (!cfg.visible) return@forEach
                val baseDp = widgetBaseSizeDp(id)
                val scaledDp = baseDp * cfg.scale
                val sizePx = with(density) { scaledDp.toPx() }
                val cx = cfg.xFraction * widthPx
                val cy = cfg.yFraction * heightPx

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (cx - sizePx * 0.5f).roundToInt(),
                                (cy - sizePx * 0.5f).roundToInt()
                            )
                        }
                        .size(scaledDp)
                        .alpha(cfg.opacity)
                ) {
                    when (id) {
                        ControlWidgetId.JOYSTICK -> VirtualJoystick(
                            sizeDp = scaledDp,
                            onMove = { mx, my ->
                                engine.moveInputX = mx
                                engine.moveInputY = my
                            }
                        )
                        ControlWidgetId.FIRE_RIGHT, ControlWidgetId.FIRE_LEFT -> FireAimButton(
                            config = cfg,
                            sizeDp = scaledDp,
                            onFiringChanged = { engine.isFiring = it },
                            onLookDelta = { dx, dy -> engine.addLookDelta(dx, dy) }
                        )
                        ControlWidgetId.ADS -> ActionHoldTapButton(
                            config = cfg,
                            sizeDp = scaledDp,
                            isActive = hud.isAds,
                            accent = TacticalAmber,
                            onTap = { engine.toggleAds() }
                        )
                        ControlWidgetId.RELOAD -> ReloadActionButton(
                            config = cfg,
                            sizeDp = scaledDp,
                            reloadProgress = hud.reloadProgress,
                            isReloading = hud.isReloading,
                            onTap = { engine.triggerReload() }
                        )
                        ControlWidgetId.CROUCH -> ActionHoldTapButton(
                            config = cfg,
                            sizeDp = scaledDp,
                            isActive = hud.isCrouching,
                            accent = IceCyan,
                            onTap = { engine.toggleCrouch() }
                        )
                        ControlWidgetId.JUMP -> ActionHoldTapButton(
                            config = cfg,
                            sizeDp = scaledDp,
                            isActive = false,
                            accent = RtxEmerald,
                            onTap = { engine.triggerJump() }
                        )
                        ControlWidgetId.WEAPON_SWITCH -> ActionHoldTapButton(
                            config = cfg,
                            sizeDp = scaledDp,
                            isActive = false,
                            accent = IceCyan,
                            onTap = { engine.cycleWeapon() }
                        )
                        ControlWidgetId.PAUSE -> ActionHoldTapButton(
                            config = cfg,
                            sizeDp = scaledDp,
                            isActive = false,
                            accent = TextPrimary,
                            onTap = { onSetPaused(true) }
                        )
                    }
                }
            }
        }

        // --- 10. PAUSE OVERLAY ---
        if (isPaused && !hud.isGameOver && !hud.isVictory) {
            PauseOverlay(
                onResume = { onSetPaused(false) },
                onSettings = onOpenSettings,
                onControls = onOpenControlEditor,
                onRestart = onRestart,
                onExit = onExitToMenu
            )
        }

        // --- 11. VICTORY / GAME OVER DEBRIEF OVERLAY ---
        if (hud.isGameOver || hud.isVictory) {
            MatchEndOverlay(
                hud = hud,
                onRestart = onRestart,
                onExit = onExitToMenu
            )
        }
    }
}

@Composable
private fun SniperScopeOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val radius = size.minDimension * 0.44f

        // Pitch-black outer scope aperture using EvenOdd path
        val maskPath = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, size.width, size.height))
            addOval(Rect(cx - radius, cy - radius, cx + radius, cy + radius))
        }
        drawPath(maskPath, color = Color.Black.copy(alpha = 0.96f))

        // Optical glass inner edge ring
        drawCircle(
            color = Color.Black,
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = 6.dp.toPx())
        )

        // Razor-thin mil-dot reticle crosshairs
        val lineCol = Color.Black.copy(alpha = 0.92f)
        drawLine(lineCol, Offset(cx - radius, cy), Offset(cx + radius, cy), 1.4.dp.toPx())
        drawLine(lineCol, Offset(cx, cy - radius), Offset(cx, cy + radius), 1.4.dp.toPx())

        // Thick outer stadia bars
        val stadiaStart = radius * 0.56f
        val thick = 3.5.dp.toPx()
        drawLine(Color.Black, Offset(cx - radius, cy), Offset(cx - stadiaStart, cy), thick)
        drawLine(Color.Black, Offset(cx + stadiaStart, cy), Offset(cx + radius, cy), thick)
        drawLine(Color.Black, Offset(cx, cy + stadiaStart), Offset(cx, cy + radius), thick)

        // Mil-dot elevation & windage ticks
        val step = radius * 0.12f
        val tick = 4.dp.toPx()
        for (i in 1..4) {
            drawLine(lineCol, Offset(cx - tick, cy + step * i), Offset(cx + tick, cy + step * i), 1.5.dp.toPx())
            drawLine(lineCol, Offset(cx - tick, cy - step * i), Offset(cx + tick, cy - step * i), 1.5.dp.toPx())
            drawLine(lineCol, Offset(cx - step * i, cy - tick), Offset(cx - step * i, cy + tick), 1.5.dp.toPx())
            drawLine(lineCol, Offset(cx + step * i, cy - tick), Offset(cx + step * i, cy + tick), 1.5.dp.toPx())
        }

        // Illuminated center sniper dot
        drawCircle(ThreatCrimson, radius = 2.6.dp.toPx(), center = Offset(cx, cy))
    }
}

@Composable
private fun VirtualJoystick(
    sizeDp: Dp,
    onMove: (Float, Float) -> Unit
) {
    var knobOffset by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val maxRadiusPx = with(density) { (sizeDp * 0.34f).toPx() }.coerceAtLeast(1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid.copy(alpha = 0.42f), CircleShape)
            .border(1.5.dp, RtxEmerald.copy(alpha = 0.6f), CircleShape)
            .pointerInput(maxRadiusPx) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val center = Offset(size.width * 0.5f, size.height * 0.5f)

                    fun updateFromPos(pos: Offset) {
                        val delta = pos - center
                        val dist = sqrt(delta.x * delta.x + delta.y * delta.y)
                        val clamped = if (dist > maxRadiusPx) {
                            Offset(delta.x / dist * maxRadiusPx, delta.y / dist * maxRadiusPx)
                        } else {
                            delta
                        }
                        knobOffset = clamped
                        onMove(clamped.x / maxRadiusPx, -clamped.y / maxRadiusPx)
                    }

                    updateFromPos(down.position)
                    down.consume()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        updateFromPos(change.position)
                        change.consume()
                    }
                    knobOffset = Offset.Zero
                    onMove(0f, 0f)
                }
            }
            .testTag("hud_joystick"),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(knobOffset.x.roundToInt(), knobOffset.y.roundToInt()) }
                .size(sizeDp * 0.42f)
                .background(RtxEmerald.copy(alpha = 0.55f), CircleShape)
                .border(2.dp, RtxEmerald, CircleShape)
        )
    }
}

@Composable
private fun FireAimButton(
    config: ControlWidgetConfig,
    sizeDp: Dp,
    onFiringChanged: (Boolean) -> Unit,
    onLookDelta: (Float, Float) -> Unit
) {
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (pressed) ThreatCrimson.copy(alpha = 0.72f) else ThreatCrimson.copy(alpha = 0.38f),
                CircleShape
            )
            .border(2.dp, if (pressed) Color.White else ThreatCrimson, CircleShape)
            .pointerInput(config.id) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pressed = true
                    onFiringChanged(true)
                    down.consume()
                    var prev = down.position

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        val delta = change.position - prev
                        prev = change.position
                        if (delta.x != 0f || delta.y != 0f) {
                            onLookDelta(delta.x, delta.y)
                        }
                        change.consume()
                    }
                    pressed = false
                    onFiringChanged(false)
                }
            }
            .testTag("hud_${config.id.name.lowercase()}"),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = widgetIcon(config.id),
            contentDescription = config.id.labelRu,
            tint = TextPrimary,
            modifier = Modifier.size((sizeDp * 0.44f).coerceIn(22.dp, 40.dp))
        )
    }
}

@Composable
private fun ActionHoldTapButton(
    config: ControlWidgetConfig,
    sizeDp: Dp,
    isActive: Boolean,
    accent: Color,
    onTap: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (isActive) accent.copy(alpha = 0.45f) else ObsidianVoid.copy(alpha = 0.58f),
                CircleShape
            )
            .border(1.5.dp, if (isActive) accent else BorderSteel, CircleShape)
            .pointerInput(config.id) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    onTap()
                }
            }
            .testTag("hud_${config.id.name.lowercase()}"),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = widgetIcon(config.id),
            contentDescription = config.id.labelRu,
            tint = if (isActive) ObsidianVoid else accent,
            modifier = Modifier.size((sizeDp * 0.44f).coerceIn(18.dp, 30.dp))
        )
    }
}

@Composable
private fun ReloadActionButton(
    config: ControlWidgetConfig,
    sizeDp: Dp,
    reloadProgress: Float,
    isReloading: Boolean,
    onTap: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid.copy(alpha = 0.62f), CircleShape)
            .border(1.5.dp, if (isReloading) TacticalAmber else BorderSteel, CircleShape)
            .pointerInput(config.id) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    onTap()
                }
            }
            .testTag("hud_reload"),
        contentAlignment = Alignment.Center
    ) {
        if (isReloading) {
            Canvas(modifier = Modifier.fillMaxSize().padding(3.dp)) {
                drawArc(
                    color = TacticalAmber,
                    startAngle = -90f,
                    sweepAngle = 360f * reloadProgress,
                    useCenter = false,
                    style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
        Icon(
            imageVector = widgetIcon(config.id),
            contentDescription = config.id.labelRu,
            tint = if (isReloading) TacticalAmber else TextPrimary,
            modifier = Modifier.size((sizeDp * 0.44f).coerceIn(18.dp, 30.dp))
        )
    }
}

@Composable
private fun CrosshairOverlay(hud: HudSnapshot, modifier: Modifier = Modifier) {
    val inSniperScope = hud.weapon.hasSniperScope && hud.adsBlend > 0.72f
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(110.dp)) {
            val c = Offset(size.width * 0.5f, size.height * 0.5f)
            if (!inSniperScope) {
                if (hud.isAds) {
                    val retCol = if (hud.weapon == WeaponType.AK47) TacticalAmber else RtxEmerald
                    drawCircle(
                        color = retCol.copy(alpha = 0.82f),
                        radius = 20.dp.toPx(),
                        center = c,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                    drawCircle(color = retCol, radius = 2.4.dp.toPx(), center = c)
                    val tick = 6.dp.toPx()
                    val r = 20.dp.toPx()
                    drawLine(retCol, Offset(c.x - r - tick, c.y), Offset(c.x - r + 2f, c.y), 2.dp.toPx())
                    drawLine(retCol, Offset(c.x + r - 2f, c.y), Offset(c.x + r + tick, c.y), 2.dp.toPx())
                    drawLine(retCol, Offset(c.x, c.y - r - tick), Offset(c.x, c.y - r + 2f), 2.dp.toPx())
                    drawLine(retCol, Offset(c.x, c.y + r - 2f), Offset(c.x, c.y + r + tick), 2.dp.toPx())
                } else {
                    val gap = (8f + hud.crosshairSpread).dp.toPx()
                    val arm = 10.dp.toPx()
                    val col = Color.White.copy(alpha = 0.88f)
                    val stroke = 2.dp.toPx()
                    drawLine(col, Offset(c.x - gap - arm, c.y), Offset(c.x - gap, c.y), stroke, StrokeCap.Round)
                    drawLine(col, Offset(c.x + gap, c.y), Offset(c.x + gap + arm, c.y), stroke, StrokeCap.Round)
                    drawLine(col, Offset(c.x, c.y - gap - arm), Offset(c.x, c.y - gap), stroke, StrokeCap.Round)
                    drawLine(col, Offset(c.x, c.y + gap), Offset(c.x, c.y + gap + arm), stroke, StrokeCap.Round)
                    drawCircle(RtxEmerald, radius = 1.8.dp.toPx(), center = c)
                }
            }

            if (hud.hitMarkerAlpha > 0.02f) {
                val hmCol = (if (hud.isHeadshotHit) ThreatCrimson else Color.White)
                    .copy(alpha = hud.hitMarkerAlpha)
                val inR = 7.dp.toPx()
                val outR = (if (hud.isHeadshotHit) 18.dp else 14.dp).toPx()
                val sw = (if (hud.isHeadshotHit) 3.dp else 2.2.dp).toPx()
                drawLine(hmCol, Offset(c.x - inR, c.y - inR), Offset(c.x - outR, c.y - outR), sw, StrokeCap.Round)
                drawLine(hmCol, Offset(c.x + inR, c.y - inR), Offset(c.x + outR, c.y - outR), sw, StrokeCap.Round)
                drawLine(hmCol, Offset(c.x - inR, c.y + inR), Offset(c.x - outR, c.y + outR), sw, StrokeCap.Round)
                drawLine(hmCol, Offset(c.x + inR, c.y + inR), Offset(c.x + outR, c.y + outR), sw, StrokeCap.Round)
            }
        }

        if (hud.hitMarkerAlpha > 0.2f && hud.isHeadshotHit) {
            Text(
                text = "ГОЛОВА +250",
                style = MaterialTheme.typography.labelMedium,
                color = ThreatCrimson.copy(alpha = hud.hitMarkerAlpha),
                modifier = Modifier.offset(y = 34.dp)
            )
        }
    }
}

@Composable
private fun TacticalRadar(hud: HudSnapshot) {
    Canvas(
        modifier = Modifier
            .size(72.dp)
            .background(ObsidianVoid.copy(alpha = 0.72f), CircleShape)
            .border(1.2.dp, RtxEmerald.copy(alpha = 0.65f), CircleShape)
    ) {
        val c = Offset(size.width * 0.5f, size.height * 0.5f)
        val r = size.minDimension * 0.46f
        drawCircle(RtxEmerald.copy(alpha = 0.22f), r * 0.55f, c, style = Stroke(1f))
        drawLine(RtxEmerald.copy(alpha = 0.22f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
        drawLine(RtxEmerald.copy(alpha = 0.22f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
        drawLine(RtxEmerald.copy(alpha = 0.45f), c, Offset(c.x - r * 0.45f, c.y - r * 0.82f), 1.5f)
        drawLine(RtxEmerald.copy(alpha = 0.45f), c, Offset(c.x + r * 0.45f, c.y - r * 0.82f), 1.5f)
        drawCircle(RtxEmerald, 3.dp.toPx(), c)

        for (blip in hud.radarBlips) {
            val bx = c.x + blip.relX * r
            val by = c.y - blip.relZ * r
            drawCircle(
                color = if (blip.isElite) TacticalAmber else ThreatCrimson,
                radius = (if (blip.isElite) 3.4.dp else 2.6.dp).toPx(),
                center = Offset(bx, by)
            )
        }
    }
}

@Composable
private fun StatusBarRow(label: String, value: Int, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextSecondary, modifier = Modifier.width(20.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(7.dp)
                .background(ElevatedGunmetal, CutCornerShape(2.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((value / 100f).coerceIn(0f, 1f))
                    .height(7.dp)
                    .background(color, CutCornerShape(2.dp))
            )
        }
        Text(text = value.toString(), style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.width(26.dp))
    }
}

@Composable
private fun PauseOverlay(
    onResume: () -> Unit,
    onSettings: () -> Unit,
    onControls: () -> Unit,
    onRestart: () -> Unit,
    onExit: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid.copy(alpha = 0.80f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = TacticalSurface,
            shape = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp),
            border = BorderStroke(1.dp, RtxEmerald.copy(alpha = 0.6f)),
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 360.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("ПАУЗА", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = onResume,
                    modifier = Modifier.fillMaxWidth().height(50.dp).testTag("pause_resume_button"),
                    shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RtxEmerald, contentColor = ObsidianVoid)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("ПРОДОЛЖИТЬ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onSettings,
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("pause_settings_button"),
                    shape = CutCornerShape(8.dp),
                    border = BorderStroke(1.dp, BorderSteel)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = RtxEmerald, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("НАСТРОЙКИ ГРАФИКИ И СЕНСЫ", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                }

                OutlinedButton(
                    onClick = onControls,
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("pause_controls_button"),
                    shape = CutCornerShape(8.dp),
                    border = BorderStroke(1.dp, TacticalAmber.copy(alpha = 0.7f))
                ) {
                    Icon(Icons.Default.Gamepad, contentDescription = null, tint = TacticalAmber, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("РЕДАКТОР УПРАВЛЕНИЯ", style = MaterialTheme.typography.labelLarge, color = TacticalAmber)
                }

                OutlinedButton(
                    onClick = onRestart,
                    modifier = Modifier.fillMaxWidth().height(46.dp).testTag("pause_restart_button"),
                    shape = CutCornerShape(8.dp),
                    border = BorderStroke(1.dp, BorderSteel)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("НАЧАТЬ ЗАНОВО", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                }

                OutlinedButton(
                    onClick = onExit,
                    modifier = Modifier.fillMaxWidth().height(46.dp).testTag("pause_exit_button"),
                    shape = CutCornerShape(8.dp),
                    border = BorderStroke(1.dp, ThreatCrimson.copy(alpha = 0.6f))
                ) {
                    Text("В ГЛАВНОЕ МЕНЮ", style = MaterialTheme.typography.labelLarge, color = ThreatCrimson)
                }
            }
        }
    }
}

@Composable
private fun MatchEndOverlay(
    hud: HudSnapshot,
    onRestart: () -> Unit,
    onExit: () -> Unit
) {
    val accent = if (hud.isVictory) RtxEmerald else ThreatCrimson
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid.copy(alpha = 0.84f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = TacticalSurface,
            shape = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp),
            border = BorderStroke(1.5.dp, accent),
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 380.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (hud.isVictory) "ОПЕРАЦИЯ ВЫПОЛНЕНА" else "БОЕЦ ПОВЕРЖЕН",
                    style = MaterialTheme.typography.headlineMedium,
                    color = accent
                )

                Surface(
                    color = ElevatedGunmetal,
                    shape = CutCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DebriefRow("СЧЁТ", hud.score.toString(), TacticalAmber)
                        DebriefRow("ЛИКВИДАЦИЙ", hud.kills.toString(), TextPrimary)
                        DebriefRow("В ГОЛОВУ", hud.headshots.toString(), RtxEmerald)
                        DebriefRow("ТОЧНОСТЬ", "${hud.accuracyPercent}%", IceCyan)
                        DebriefRow("ВОЛНА", hud.wave.toString(), TextPrimary)
                    }
                }

                Button(
                    onClick = onRestart,
                    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("end_restart_button"),
                    shape = CutCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RtxEmerald, contentColor = ObsidianVoid)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("ИГРАТЬ СНОВА", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onExit,
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("end_menu_button"),
                    shape = CutCornerShape(8.dp),
                    border = BorderStroke(1.dp, BorderSteel)
                ) {
                    Text("В ГЛАВНОЕ МЕНЮ", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun DebriefRow(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Text(text = value, style = MaterialTheme.typography.labelLarge, color = color)
    }
}
