package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.AudioSettings
import com.example.model.GameSettings
import com.example.model.GraphicsPreset
import com.example.model.GraphicsSettings
import com.example.model.SensitivitySettings
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
fun SettingsScreen(
    settings: GameSettings,
    onApplyPreset: (GraphicsPreset) -> Unit,
    onUpdateGraphics: (GraphicsSettings) -> Unit,
    onUpdateSensitivity: (SensitivitySettings) -> Unit,
    onUpdateAudio: (AudioSettings) -> Unit,
    onOpenControlEditor: () -> Unit,
    onResetAll: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("ГРАФИКА RTX", "ЧУВСТВИТЕЛЬНОСТЬ", "ЗВУК И УПРАВЛЕНИЕ")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
            .safeDrawingPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 820.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            // Compact landscape header + tabs
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = TextPrimary
                        )
                    }
                    Column {
                        Text(
                            text = "НАСТРОЙКИ",
                            style = MaterialTheme.typography.headlineSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = "Применяются к 3D-движку мгновенно • Без помощи в прицеливании",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tabs.forEachIndexed { idx, label ->
                        val active = selectedTab == idx
                        val shape = CutCornerShape(topStart = 7.dp, bottomEnd = 7.dp)
                        Box(
                            modifier = Modifier
                                .clip(shape)
                                .background(if (active) RtxEmerald.copy(alpha = 0.22f) else TacticalSurface)
                                .border(
                                    width = if (active) 1.5.dp else 1.dp,
                                    color = if (active) RtxEmerald else BorderSteel,
                                    shape = shape
                                )
                                .clickable { selectedTab = idx }
                                .padding(horizontal = 14.dp, vertical = 9.dp)
                                .testTag("settings_tab_$idx"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (active) RtxEmerald else TextSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Scrollable Tab Content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (selectedTab) {
                    0 -> GraphicsSettingsSection(
                        graphics = settings.graphics,
                        onApplyPreset = onApplyPreset,
                        onUpdate = onUpdateGraphics
                    )
                    1 -> SensitivitySettingsSection(
                        sensitivity = settings.sensitivity,
                        onUpdate = onUpdateSensitivity
                    )
                    else -> AudioAndHudSection(
                        audio = settings.audio,
                        onUpdateAudio = onUpdateAudio,
                        onOpenControlEditor = onOpenControlEditor,
                        onResetAll = onResetAll
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
            }
        }
    }
}

@Composable
private fun GraphicsSettingsSection(
    graphics: GraphicsSettings,
    onApplyPreset: (GraphicsPreset) -> Unit,
    onUpdate: (GraphicsSettings) -> Unit
) {
    SettingsCard(title = "ПРЕСЕТ КАЧЕСТВА ГРАФИКИ", icon = {
        Icon(Icons.Default.Visibility, contentDescription = null, tint = RtxEmerald, modifier = Modifier.size(18.dp))
    }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GraphicsPreset.entries.forEach { preset ->
                PresetChip(
                    preset = preset,
                    selected = graphics.preset == preset,
                    onClick = { onApplyPreset(preset) },
                    modifier = Modifier.weight(1f),
                    isRtx = preset == GraphicsPreset.ULTRA_RTX
                )
            }
        }
        Text(
            text = graphics.preset.subtitleRu,
            style = MaterialTheme.typography.bodyMedium,
            color = RtxEmerald
        )
    }

    SettingsCard(title = "ТОНКАЯ НАСТРОЙКА RTX & PBR") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                SettingsSliderRow(
                    label = "Масштаб разрешения 3D",
                    valueText = "${(graphics.resolutionScale * 100).roundToInt()}%",
                    value = graphics.resolutionScale,
                    valueRange = 0.60f..1.00f,
                    onValueChange = { onUpdate(graphics.copy(resolutionScale = it)) }
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                SettingsSliderRow(
                    label = "Угол обзора (FOV)",
                    valueText = "${graphics.fov.roundToInt()}°",
                    value = graphics.fov,
                    valueRange = 65f..105f,
                    onValueChange = { onUpdate(graphics.copy(fov = it)) }
                )
            }
        }

        SettingsToggleRow(
            title = "RTX-отражения на стали, асфальте и броне",
            subtitle = "Зеркальные блики окружения и металла в реальном времени",
            checked = graphics.rtxReflections,
            onCheckedChange = { onUpdate(graphics.copy(rtxReflections = it)) },
            tag = "toggle_rtx_reflections"
        )

        SettingsToggleRow(
            title = "Динамический свет и вспышки выстрелов",
            subtitle = "4 точечных источника света + мгновенное освещение выстрелом",
            checked = graphics.dynamicLighting,
            onCheckedChange = { onUpdate(graphics.copy(dynamicLighting = it)) },
            tag = "toggle_dynamic_lighting"
        )

        SettingsToggleRow(
            title = "Супер-текстуры HD и карты нормалей",
            subtitle = "Воронёная сталь, полимер, ткань формы и марокканская плитка",
            checked = graphics.hdTextures,
            onCheckedChange = { onUpdate(graphics.copy(hdTextures = it)) },
            tag = "toggle_hd_textures"
        )

        SettingsToggleRow(
            title = "ACES Filmic Tone Mapping & Bloom",
            subtitle = "Кинематографический HDR-контраст и мягкое свечение",
            checked = graphics.bloomAndAces,
            onCheckedChange = { onUpdate(graphics.copy(bloomAndAces = it)) },
            tag = "toggle_bloom_aces"
        )

        SettingsToggleRow(
            title = "Показывать счётчик FPS в бою",
            subtitle = "Мониторинг производительности OpenGL ES 3.0",
            checked = graphics.showFps,
            onCheckedChange = { onUpdate(graphics.copy(showFps = it)) },
            tag = "toggle_show_fps"
        )
    }
}

@Composable
private fun SensitivitySettingsSection(
    sensitivity: SensitivitySettings,
    onUpdate: (SensitivitySettings) -> Unit
) {
    SettingsCard(title = "ЧУВСТВИТЕЛЬНОСТЬ ПРИЦЕЛИВАНИЯ (ЧИСТЫЙ СКИЛЛ • БЕЗ АИМ-АССИСТА)", icon = {
        Icon(Icons.Default.Tune, contentDescription = null, tint = TacticalAmber, modifier = Modifier.size(18.dp))
    }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                SettingsSliderRow(
                    label = "Общая чувствительность обзора",
                    valueText = String.format("%.2fx", sensitivity.lookSensitivity),
                    value = sensitivity.lookSensitivity,
                    valueRange = 0.2f..3.0f,
                    onValueChange = { onUpdate(sensitivity.copy(lookSensitivity = it)) }
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                SettingsSliderRow(
                    label = "Чувствительность в прицеле (ADS / AWP)",
                    valueText = String.format("%.2fx", sensitivity.adsSensitivity),
                    value = sensitivity.adsSensitivity,
                    valueRange = 0.2f..2.0f,
                    onValueChange = { onUpdate(sensitivity.copy(adsSensitivity = it)) }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                SettingsSliderRow(
                    label = "Горизонтальная ось (X)",
                    valueText = String.format("%.2fx", sensitivity.xAxisMultiplier),
                    value = sensitivity.xAxisMultiplier,
                    valueRange = 0.5f..2.0f,
                    onValueChange = { onUpdate(sensitivity.copy(xAxisMultiplier = it)) }
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                SettingsSliderRow(
                    label = "Вертикальная ось (Y)",
                    valueText = String.format("%.2fx", sensitivity.yAxisMultiplier),
                    value = sensitivity.yAxisMultiplier,
                    valueRange = 0.5f..2.0f,
                    onValueChange = { onUpdate(sensitivity.copy(yAxisMultiplier = it)) }
                )
            }
        }

        SettingsToggleRow(
            title = "Инверсия вертикальной оси Y",
            subtitle = "Свайп вверх опускает прицел вниз",
            checked = sensitivity.invertY,
            onCheckedChange = { onUpdate(sensitivity.copy(invertY = it)) },
            tag = "toggle_invert_y"
        )
    }
}

@Composable
private fun AudioAndHudSection(
    audio: AudioSettings,
    onUpdateAudio: (AudioSettings) -> Unit,
    onOpenControlEditor: () -> Unit,
    onResetAll: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            SettingsCard(title = "ЗВУК И ВИБРООТКЛИК", icon = {
                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = RtxEmerald, modifier = Modifier.size(18.dp))
            }) {
                SettingsSliderRow(
                    label = "Громкость стрельбы и эффектов",
                    valueText = "${(audio.sfxVolume * 100).roundToInt()}%",
                    value = audio.sfxVolume,
                    valueRange = 0f..1f,
                    onValueChange = { onUpdateAudio(audio.copy(sfxVolume = it)) }
                )
                SettingsToggleRow(
                    title = "Тактильная вибрация",
                    subtitle = "Отдача при выстрелах, хедшотах и уроне",
                    checked = audio.hapticsEnabled,
                    onCheckedChange = { onUpdateAudio(audio.copy(hapticsEnabled = it)) },
                    tag = "toggle_haptics"
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            SettingsCard(title = "РАСКЛАДКА УПРАВЛЕНИЯ (HUD)", icon = {
                Icon(Icons.Default.Gamepad, contentDescription = null, tint = TacticalAmber, modifier = Modifier.size(18.dp))
            }) {
                Text(
                    text = "Перетаскивайте кнопки (включая широкую кнопку свайпа оружия и магазин), меняйте размер и прозрачность.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Button(
                    onClick = onOpenControlEditor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("settings_open_control_editor"),
                    shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TacticalAmber,
                        contentColor = ObsidianVoid
                    )
                ) {
                    Icon(Icons.Default.Gamepad, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("РЕДАКТОР УПРАВЛЕНИЯ", style = MaterialTheme.typography.labelLarge)
                }

                OutlinedButton(
                    onClick = onResetAll,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("settings_reset_all"),
                    shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                    border = BorderStroke(1.dp, ThreatCrimson.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ThreatCrimson)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("СБРОСИТЬ ВСЕ НАСТРОЙКИ", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun PresetChip(
    preset: GraphicsPreset,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isRtx: Boolean = false
) {
    val accent = if (isRtx) RtxEmerald else TacticalAmber
    val shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.22f) else ElevatedGunmetal)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) accent else BorderSteel,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp)
            .testTag("preset_${preset.name.lowercase()}"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = preset.titleRu,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) accent else TextPrimary
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    icon: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Surface(
        color = TacticalSurface,
        shape = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp),
        border = BorderStroke(1.dp, BorderSteel),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                icon?.invoke()
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary
                )
            }
            content()
        }
    }
}

@Composable
private fun SettingsSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            Text(text = valueText, style = MaterialTheme.typography.labelLarge, color = RtxEmerald)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = RtxEmerald,
                activeTrackColor = RtxEmerald,
                inactiveTrackColor = ElevatedGunmetal
            )
        )
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag),
            colors = SwitchDefaults.colors(
                checkedThumbColor = ObsidianVoid,
                checkedTrackColor = RtxEmerald,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = ElevatedGunmetal,
                uncheckedBorderColor = BorderSteel
            )
        )
    }
}
