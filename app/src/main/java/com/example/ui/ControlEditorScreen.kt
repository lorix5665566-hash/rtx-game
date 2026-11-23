package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.ControlLayout
import com.example.model.ControlWidgetConfig
import com.example.model.ControlWidgetId
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
import kotlin.math.roundToInt

/**
 * Non-obstructive Landscape Control Layout Editor:
 * - Supports rectangular wide swipe widget (WEAPON_SWIPE) & SHOP button
 * - Inspector toolbar docks TOP <-> BOTTOM and collapses to 42dp
 * - Auto-fades translucent while actively dragging any control
 */
@Composable
fun ControlEditorScreen(
    initialLayout: ControlLayout,
    onSave: (ControlLayout) -> Unit,
    onCancel: () -> Unit
) {
    BackHandler { onCancel() }

    var draftLayout by remember(initialLayout) { mutableStateOf(initialLayout) }
    var selectedId by remember { mutableStateOf(ControlWidgetId.WEAPON_SWIPE) }
    var dockAtTop by remember { mutableStateOf(true) }
    var isPanelExpanded by remember { mutableStateOf(false) }
    var isDraggingAny by remember { mutableStateOf(false) }
    val selectedWidget = draftLayout.get(selectedId)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
    ) {
        val density = LocalDensity.current
        val containerWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val containerHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)

        Image(
            painter = painterResource(id = R.drawable.img_editor_backdrop),
            contentDescription = "Фон редактора управления",
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.55f),
            contentScale = ContentScale.Crop
        )

        // Landscape Alignment Grid + Crosshair Guides
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cols = 16
            val rows = 9
            for (c in 1 until cols) {
                val x = size.width * c / cols
                drawLine(Color.White.copy(alpha = 0.08f), Offset(x, 0f), Offset(x, size.height), 1f)
            }
            for (r in 1 until rows) {
                val y = size.height * r / rows
                drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, y), Offset(size.width, y), 1f)
            }
            val gx = selectedWidget.xFraction * size.width
            val gy = selectedWidget.yFraction * size.height
            drawLine(RtxEmerald.copy(alpha = 0.50f), Offset(gx, 0f), Offset(gx, size.height), 1.5f)
            drawLine(RtxEmerald.copy(alpha = 0.50f), Offset(0f, gy), Offset(size.width, gy), 1.5f)
        }

        // Draggable Control Widgets
        ControlWidgetId.entries.forEach { id ->
            val cfg = draftLayout.get(id)
            val scaledWidthDp = widgetBaseWidthDp(id) * cfg.scale
            val scaledHeightDp = widgetBaseHeightDp(id) * cfg.scale
            val widthPx = with(density) { scaledWidthDp.toPx() }
            val heightPx = with(density) { scaledHeightDp.toPx() }
            val centerX = cfg.xFraction * containerWidthPx
            val centerY = cfg.yFraction * containerHeightPx
            val isSelected = id == selectedId

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (centerX - widthPx * 0.5f).roundToInt(),
                            (centerY - heightPx * 0.5f).roundToInt()
                        )
                    }
                    .size(width = scaledWidthDp, height = scaledHeightDp)
                    .alpha(if (cfg.visible) cfg.opacity else 0.25f)
                    .pointerInput(id, containerWidthPx, containerHeightPx) {
                        detectTapGestures { selectedId = id }
                    }
                    .pointerInput(id, containerWidthPx, containerHeightPx) {
                        detectDragGestures(
                            onDragStart = {
                                selectedId = id
                                isDraggingAny = true
                            },
                            onDragEnd = { isDraggingAny = false },
                            onDragCancel = { isDraggingAny = false },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val current = draftLayout.get(id)
                                val nx = (current.xFraction + dragAmount.x / containerWidthPx)
                                    .coerceIn(0.04f, 0.96f)
                                val ny = (current.yFraction + dragAmount.y / containerHeightPx)
                                    .coerceIn(0.06f, 0.94f)
                                draftLayout = draftLayout.updateWidget(
                                    current.copy(xFraction = nx, yFraction = ny)
                                )
                            }
                        )
                    }
                    .testTag("editor_widget_${id.name.lowercase()}"),
                contentAlignment = Alignment.Center
            ) {
                EditorWidgetVisual(
                    config = cfg,
                    heightDp = scaledHeightDp,
                    isSelected = isSelected
                )
            }
        }

        // Compact, Collapsible & Relocatable (Top <-> Bottom) Inspector Toolbar
        Box(
            modifier = Modifier
                .align(if (dockAtTop) Alignment.TopCenter else Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .alpha(if (isDraggingAny) 0.14f else 1.0f)
        ) {
            Surface(
                color = TacticalSurface.copy(alpha = 0.93f),
                shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
                border = BorderStroke(1.dp, if (isPanelExpanded) RtxEmerald.copy(alpha = 0.7f) else BorderSteel)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onCancel,
                                modifier = Modifier
                                    .size(34.dp)
                                    .testTag("editor_cancel_button")
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Отмена",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                text = selectedWidget.id.labelRu.uppercase(),
                                style = MaterialTheme.typography.labelLarge,
                                color = RtxEmerald
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            OutlinedButton(
                                onClick = { dockAtTop = !dockAtTop },
                                shape = CutCornerShape(6.dp),
                                border = BorderStroke(1.dp, IceCyan.copy(alpha = 0.7f)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .height(32.dp)
                                    .testTag("editor_dock_toggle")
                            ) {
                                Icon(
                                    Icons.Default.SwapVert,
                                    contentDescription = "Переместить панель",
                                    tint = IceCyan,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = if (dockAtTop) "ВНИЗ" else "ВВЕРХ",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = IceCyan
                                )
                            }

                            OutlinedButton(
                                onClick = { isPanelExpanded = !isPanelExpanded },
                                shape = CutCornerShape(6.dp),
                                border = BorderStroke(1.dp, TacticalAmber.copy(alpha = 0.75f)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .height(32.dp)
                                    .testTag("editor_expand_toggle")
                            ) {
                                Icon(
                                    if (isPanelExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = "Настройки кнопки",
                                    tint = TacticalAmber,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (isPanelExpanded) "СКРЫТЬ" else "ОПЦИИ",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TacticalAmber
                                )
                            }

                            Button(
                                onClick = { onSave(draftLayout) },
                                shape = CutCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = RtxEmerald,
                                    contentColor = ObsidianVoid
                                ),
                                modifier = Modifier
                                    .height(32.dp)
                                    .testTag("editor_save_button")
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("ОК", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }

                    if (isPanelExpanded) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = { draftLayout = ControlLayout.defaultTwoFinger() },
                                    shape = CutCornerShape(6.dp),
                                    border = BorderStroke(1.dp, BorderSteel),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(30.dp).testTag("editor_preset_2finger")
                                ) {
                                    Text("2 ПАЛЬЦА", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                                }
                                OutlinedButton(
                                    onClick = { draftLayout = ControlLayout.fourFingerClaw() },
                                    shape = CutCornerShape(6.dp),
                                    border = BorderStroke(1.dp, TacticalAmber.copy(alpha = 0.7f)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(30.dp).testTag("editor_preset_4finger")
                                ) {
                                    Text("4 ПАЛЬЦА", style = MaterialTheme.typography.labelSmall, color = TacticalAmber)
                                }
                            }

                            if (selectedWidget.id.canHide) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (selectedWidget.visible) "ВИДИМА" else "СКРЫТА",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selectedWidget.visible) TextPrimary else ThreatCrimson
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Switch(
                                        checked = selectedWidget.visible,
                                        onCheckedChange = { vis ->
                                            draftLayout = draftLayout.updateWidget(selectedWidget.copy(visible = vis))
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = ObsidianVoid,
                                            checkedTrackColor = RtxEmerald
                                        )
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Размер", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                    Text("${(selectedWidget.scale * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = RtxEmerald)
                                }
                                Slider(
                                    value = selectedWidget.scale,
                                    onValueChange = { s ->
                                        draftLayout = draftLayout.updateWidget(selectedWidget.copy(scale = s))
                                    },
                                    valueRange = 0.65f..1.55f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = RtxEmerald,
                                        activeTrackColor = RtxEmerald,
                                        inactiveTrackColor = ElevatedGunmetal
                                    )
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Прозрачность", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                    Text("${(selectedWidget.opacity * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = TacticalAmber)
                                }
                                Slider(
                                    value = selectedWidget.opacity,
                                    onValueChange = { o ->
                                        draftLayout = draftLayout.updateWidget(selectedWidget.copy(opacity = o))
                                    },
                                    valueRange = 0.25f..1.0f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = TacticalAmber,
                                        activeTrackColor = TacticalAmber,
                                        inactiveTrackColor = ElevatedGunmetal
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorWidgetVisual(
    config: ControlWidgetConfig,
    heightDp: Dp,
    isSelected: Boolean
) {
    val borderColor = when {
        isSelected -> RtxEmerald
        config.id == ControlWidgetId.FIRE_RIGHT || config.id == ControlWidgetId.FIRE_LEFT -> ThreatCrimson
        config.id == ControlWidgetId.ADS -> TacticalAmber
        config.id == ControlWidgetId.WEAPON_SWIPE -> IceCyan
        config.id == ControlWidgetId.SHOP -> TacticalAmber
        config.id == ControlWidgetId.KILLFEED -> ThreatCrimson.copy(alpha = 0.85f)
        else -> Color.White.copy(alpha = 0.55f)
    }

    if (config.id == ControlWidgetId.KILLFEED) {
        val shape = RoundedCornerShape(8.dp)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    color = if (isSelected) RtxEmerald.copy(alpha = 0.22f) else ObsidianVoid.copy(alpha = 0.80f),
                    shape = shape
                )
                .border(
                    width = if (isSelected) 2.2.dp else 1.4.dp,
                    color = borderColor,
                    shape = shape
                )
                .padding(horizontal = 7.dp, vertical = 5.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = "КИЛФИД • ТАБЛИЦА УБИЙСТВ",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) RtxEmerald else TacticalAmber,
                    fontSize = 8.sp
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x88141C2B), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Игрок", color = TacticalAmber, fontSize = 8.5.sp)
                    Text("[AK-47]", color = TextPrimary, fontSize = 8.sp)
                    Text("Бот CT", color = IceCyan, fontSize = 8.5.sp)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x88141C2B), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Союзник", color = IceCyan, fontSize = 8.5.sp)
                    Text("[AWP ☠]", color = ThreatCrimson, fontSize = 8.sp)
                    Text("Бот T", color = TacticalAmber, fontSize = 8.5.sp)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x66141C2B), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Игрок", color = TacticalAmber, fontSize = 8.5.sp)
                    Text("[НОЖ]", color = RtxEmerald, fontSize = 8.sp)
                    Text("Соперник", color = IceCyan, fontSize = 8.5.sp)
                }
            }
        }
        return
    }

    if (config.id == ControlWidgetId.WEAPON_SWIPE) {
        val shape = RoundedCornerShape(10.dp)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    color = if (isSelected) RtxEmerald.copy(alpha = 0.24f) else ObsidianVoid.copy(alpha = 0.78f),
                    shape = shape
                )
                .border(
                    width = if (isSelected) 2.2.dp else 1.5.dp,
                    color = borderColor,
                    shape = shape
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "▲ C4  •  ОРУЖИЕ (СВАЙП)",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) RtxEmerald else IceCyan,
                    fontSize = 9.sp
                )
                Text(
                    text = "◄ НОЖ  |  ▼ ОСН  |  ПИСТ ►",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary,
                    fontSize = 9.sp
                )
            }
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                color = if (isSelected) RtxEmerald.copy(alpha = 0.26f) else ObsidianVoid.copy(alpha = 0.68f),
                shape = CircleShape
            )
            .border(
                width = if (isSelected) 2.5.dp else 1.5.dp,
                color = borderColor,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (config.id == ControlWidgetId.JOYSTICK) {
            Box(
                modifier = Modifier
                    .size(heightDp * 0.44f)
                    .background(RtxEmerald.copy(alpha = 0.45f), CircleShape)
                    .border(1.5.dp, RtxEmerald, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.OpenWith,
                    contentDescription = config.id.labelRu,
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = widgetIcon(config.id),
                    contentDescription = config.id.labelRu,
                    tint = if (isSelected) RtxEmerald else TextPrimary,
                    modifier = Modifier.size((heightDp * 0.36f).coerceIn(16.dp, 28.dp))
                )
                Text(
                    text = widgetShortTag(config.id),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary
                )
            }
        }
    }
}

fun widgetBaseWidthDp(id: ControlWidgetId): Dp = when (id) {
    ControlWidgetId.JOYSTICK -> 118.dp
    ControlWidgetId.FIRE_RIGHT -> 74.dp
    ControlWidgetId.FIRE_LEFT -> 64.dp
    ControlWidgetId.ADS -> 56.dp
    ControlWidgetId.RELOAD -> 54.dp
    ControlWidgetId.CROUCH -> 50.dp
    ControlWidgetId.JUMP -> 52.dp
    ControlWidgetId.WEAPON_SWIPE -> 174.dp
    ControlWidgetId.SHOP -> 54.dp
    ControlWidgetId.PAUSE -> 46.dp
    ControlWidgetId.KILLFEED -> 232.dp
}

fun widgetBaseHeightDp(id: ControlWidgetId): Dp = when (id) {
    ControlWidgetId.WEAPON_SWIPE -> 56.dp
    ControlWidgetId.KILLFEED -> 92.dp
    else -> widgetBaseWidthDp(id)
}

fun widgetIcon(id: ControlWidgetId): ImageVector = when (id) {
    ControlWidgetId.JOYSTICK -> Icons.Default.OpenWith
    ControlWidgetId.FIRE_RIGHT, ControlWidgetId.FIRE_LEFT -> Icons.Default.GpsFixed
    ControlWidgetId.ADS -> Icons.Default.CenterFocusStrong
    ControlWidgetId.RELOAD -> Icons.Default.Refresh
    ControlWidgetId.CROUCH -> Icons.Default.ArrowDownward
    ControlWidgetId.JUMP -> Icons.Default.ArrowUpward
    ControlWidgetId.WEAPON_SWIPE -> Icons.Default.SwapHoriz
    ControlWidgetId.SHOP -> Icons.Default.ShoppingCart
    ControlWidgetId.PAUSE -> Icons.Default.Pause
    ControlWidgetId.KILLFEED -> Icons.Default.Leaderboard
}

fun widgetShortTag(id: ControlWidgetId): String = when (id) {
    ControlWidgetId.JOYSTICK -> "ХОД"
    ControlWidgetId.FIRE_RIGHT, ControlWidgetId.FIRE_LEFT -> "ОГОНЬ"
    ControlWidgetId.ADS -> "ADS"
    ControlWidgetId.RELOAD -> "R"
    ControlWidgetId.CROUCH -> "С"
    ControlWidgetId.JUMP -> "ПР"
    ControlWidgetId.WEAPON_SWIPE -> "ОРУЖ"
    ControlWidgetId.SHOP -> "$"
    ControlWidgetId.PAUSE -> "||"
    ControlWidgetId.KILLFEED -> "КИЛ"
}
