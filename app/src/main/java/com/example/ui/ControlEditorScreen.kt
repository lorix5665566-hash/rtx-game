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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
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
import com.example.R
import com.example.model.ControlLayout
import com.example.model.ControlWidgetConfig
import com.example.model.ControlWidgetId
import com.example.ui.theme.BorderSteel
import com.example.ui.theme.ElevatedGunmetal
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.RtxEmerald
import com.example.ui.theme.TacticalAmber
import com.example.ui.theme.TacticalSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.ThreatCrimson
import kotlin.math.roundToInt

@Composable
fun ControlEditorScreen(
    initialLayout: ControlLayout,
    onSave: (ControlLayout) -> Unit,
    onCancel: () -> Unit
) {
    BackHandler { onCancel() }

    var draftLayout by remember(initialLayout) { mutableStateOf(initialLayout) }
    var selectedId by remember { mutableStateOf(ControlWidgetId.FIRE_RIGHT) }
    val selectedWidget = draftLayout.get(selectedId)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
    ) {
        val density = LocalDensity.current
        val containerWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val containerHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)

        // Realistic RTX Arena Backdrop for spatial HUD alignment
        Image(
            painter = painterResource(id = R.drawable.img_editor_backdrop),
            contentDescription = "Фон редактора управления",
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.65f),
            contentScale = ContentScale.Crop
        )

        // Alignment Grid + Selected Widget Crosshair Guides
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cols = 10
            val rows = 14
            for (c in 1 until cols) {
                val x = size.width * c / cols
                drawLine(
                    color = Color.White.copy(alpha = 0.08f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f
                )
            }
            for (r in 1 until rows) {
                val y = size.height * r / rows
                drawLine(
                    color = Color.White.copy(alpha = 0.08f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f
                )
            }
            // Active alignment guides
            val gx = selectedWidget.xFraction * size.width
            val gy = selectedWidget.yFraction * size.height
            drawLine(
                color = RtxEmerald.copy(alpha = 0.45f),
                start = Offset(gx, 0f),
                end = Offset(gx, size.height),
                strokeWidth = 1.5f
            )
            drawLine(
                color = RtxEmerald.copy(alpha = 0.45f),
                start = Offset(0f, gy),
                end = Offset(size.width, gy),
                strokeWidth = 1.5f
            )
        }

        // Draggable Control Widgets
        ControlWidgetId.entries.forEach { id ->
            val cfg = draftLayout.get(id)
            val baseSizeDp = widgetBaseSizeDp(id)
            val scaledSizeDp = baseSizeDp * cfg.scale
            val sizePx = with(density) { scaledSizeDp.toPx() }
            val centerX = cfg.xFraction * containerWidthPx
            val centerY = cfg.yFraction * containerHeightPx
            val isSelected = id == selectedId

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (centerX - sizePx * 0.5f).roundToInt(),
                            (centerY - sizePx * 0.5f).roundToInt()
                        )
                    }
                    .size(scaledSizeDp)
                    .alpha(if (cfg.visible) cfg.opacity else 0.25f)
                    .pointerInput(id, containerWidthPx, containerHeightPx) {
                        detectTapGestures { selectedId = id }
                    }
                    .pointerInput(id, containerWidthPx, containerHeightPx) {
                        detectDragGestures(
                            onDragStart = { selectedId = id },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val current = draftLayout.get(id)
                                val nx = (current.xFraction + dragAmount.x / containerWidthPx)
                                    .coerceIn(0.06f, 0.94f)
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
                    sizeDp = scaledSizeDp,
                    isSelected = isSelected
                )
            }
        }

        // Top Floating Toolbar + Selected Widget Inspector
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header + Save / Cancel
            Surface(
                color = TacticalSurface.copy(alpha = 0.92f),
                shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
                border = BorderStroke(1.dp, BorderSteel)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                    .size(36.dp)
                                    .testTag("editor_cancel_button")
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Отмена",
                                    tint = TextPrimary
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "РЕДАКТОР HUD",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = { draftLayout = ControlLayout.defaultTwoFinger() },
                                shape = CutCornerShape(6.dp),
                                border = BorderStroke(1.dp, BorderSteel),
                                modifier = Modifier
                                    .height(36.dp)
                                    .testTag("editor_preset_2finger")
                            ) {
                                Text("2 П.", style = MaterialTheme.typography.labelMedium, color = TextPrimary)
                            }
                            OutlinedButton(
                                onClick = { draftLayout = ControlLayout.fourFingerClaw() },
                                shape = CutCornerShape(6.dp),
                                border = BorderStroke(1.dp, TacticalAmber.copy(alpha = 0.7f)),
                                modifier = Modifier
                                    .height(36.dp)
                                    .testTag("editor_preset_4finger")
                            ) {
                                Text("4 П.", style = MaterialTheme.typography.labelMedium, color = TacticalAmber)
                            }
                            Button(
                                onClick = { onSave(draftLayout) },
                                shape = CutCornerShape(6.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = RtxEmerald,
                                    contentColor = ObsidianVoid
                                ),
                                modifier = Modifier
                                    .height(36.dp)
                                    .testTag("editor_save_button")
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("ОК", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }

                    // Selected Element Property Controls
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ВЫБРАНО: ${selectedWidget.id.labelRu.uppercase()}",
                                style = MaterialTheme.typography.labelLarge,
                                color = RtxEmerald
                            )
                            if (selectedWidget.id.canHide) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (selectedWidget.visible) "ВИДИМА" else "СКРЫТА",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selectedWidget.visible) TextPrimary else ThreatCrimson
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Switch(
                                        checked = selectedWidget.visible,
                                        onCheckedChange = { vis ->
                                            draftLayout = draftLayout.updateWidget(
                                                selectedWidget.copy(visible = vis)
                                            )
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
                                    valueRange = 0.6f..1.6f,
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
                                    valueRange = 0.2f..1.0f,
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
    sizeDp: Dp,
    isSelected: Boolean
) {
    val borderColor = when {
        isSelected -> RtxEmerald
        config.id == ControlWidgetId.FIRE_RIGHT || config.id == ControlWidgetId.FIRE_LEFT -> ThreatCrimson
        config.id == ControlWidgetId.ADS -> TacticalAmber
        else -> Color.White.copy(alpha = 0.55f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                color = if (isSelected) RtxEmerald.copy(alpha = 0.24f) else ObsidianVoid.copy(alpha = 0.65f),
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
                    .size(sizeDp * 0.44f)
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
                    modifier = Modifier.size((sizeDp * 0.36f).coerceIn(16.dp, 32.dp))
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

fun widgetBaseSizeDp(id: ControlWidgetId): Dp = when (id) {
    ControlWidgetId.JOYSTICK -> 124.dp
    ControlWidgetId.FIRE_RIGHT -> 76.dp
    ControlWidgetId.FIRE_LEFT -> 68.dp
    ControlWidgetId.ADS -> 58.dp
    ControlWidgetId.RELOAD -> 56.dp
    ControlWidgetId.CROUCH -> 52.dp
    ControlWidgetId.JUMP -> 54.dp
    ControlWidgetId.PAUSE -> 48.dp
}

fun widgetIcon(id: ControlWidgetId): ImageVector = when (id) {
    ControlWidgetId.JOYSTICK -> Icons.Default.OpenWith
    ControlWidgetId.FIRE_RIGHT, ControlWidgetId.FIRE_LEFT -> Icons.Default.GpsFixed
    ControlWidgetId.ADS -> Icons.Default.CenterFocusStrong
    ControlWidgetId.RELOAD -> Icons.Default.Refresh
    ControlWidgetId.CROUCH -> Icons.Default.ArrowDownward
    ControlWidgetId.JUMP -> Icons.Default.ArrowUpward
    ControlWidgetId.PAUSE -> Icons.Default.Pause
}

fun widgetShortTag(id: ControlWidgetId): String = when (id) {
    ControlWidgetId.JOYSTICK -> "ХОД"
    ControlWidgetId.FIRE_RIGHT, ControlWidgetId.FIRE_LEFT -> "ОГОНЬ"
    ControlWidgetId.ADS -> "ADS"
    ControlWidgetId.RELOAD -> "R"
    ControlWidgetId.CROUCH -> "С"
    ControlWidgetId.JUMP -> "ПР"
    ControlWidgetId.PAUSE -> "||"
}
