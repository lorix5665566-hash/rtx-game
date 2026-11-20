package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.GameMode
import com.example.model.GameSettings
import com.example.model.PlayerStats
import com.example.ui.theme.BorderSteel
import com.example.ui.theme.ElevatedGunmetal
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.RtxEmerald
import com.example.ui.theme.TacticalAmber
import com.example.ui.theme.TacticalSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun MainMenuScreen(
    settings: GameSettings,
    stats: PlayerStats,
    selectedMode: GameMode,
    onSelectMode: (GameMode) -> Unit,
    onPlay: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenControls: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
    ) {
        // Hyper-realistic RTX operative hero artwork
        Image(
            painter = painterResource(id = R.drawable.img_menu_hero),
            contentDescription = "Фон главного меню Vanguard RTX",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Cinematic dark gradient scrim for crisp AAA readability
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to ObsidianVoid.copy(alpha = 0.72f),
                        0.32f to ObsidianVoid.copy(alpha = 0.25f),
                        0.62f to ObsidianVoid.copy(alpha = 0.78f),
                        1.0f to ObsidianVoid.copy(alpha = 0.96f)
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .widthIn(max = 560.dp)
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Header: RTX Status Pill + Title Lockup
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = ObsidianVoid.copy(alpha = 0.75f),
                        shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                        border = BorderStroke(1.dp, RtxEmerald.copy(alpha = 0.75f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(RtxEmerald, shape = CutCornerShape(2.dp))
                            )
                            Text(
                                text = if (settings.graphics.rtxReflections) "RTX ON • ${settings.graphics.preset.titleRu}" else settings.graphics.preset.titleRu,
                                style = MaterialTheme.typography.labelMedium,
                                color = RtxEmerald
                            )
                        }
                    }

                    Surface(
                        color = ObsidianVoid.copy(alpha = 0.70f),
                        shape = CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp),
                        border = BorderStroke(1.dp, BorderSteel)
                    ) {
                        Text(
                            text = "FOV ${settings.graphics.fov.toInt()}° • ${(settings.graphics.resolutionScale * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(48.dp)
                            .background(RtxEmerald)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "VANGUARD",
                            style = MaterialTheme.typography.displayLarge,
                            color = TextPrimary
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "RTX",
                                style = MaterialTheme.typography.headlineMedium,
                                color = RtxEmerald
                            )
                            Text(
                                text = stringResource(R.string.subtitle_rtx_engine),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(140.dp))

            // Bottom Tactical Command Deck
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Combat Record Strip
                Surface(
                    color = TacticalSurface.copy(alpha = 0.84f),
                    shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
                    border = BorderStroke(1.dp, BorderSteel.copy(alpha = 0.85f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatBadge(label = "РЕКОРД", value = stats.bestScore.toString(), accent = TacticalAmber)
                        StatBadge(label = "ЛИКВИДАЦИЙ", value = stats.totalKills.toString(), accent = TextPrimary)
                        StatBadge(label = "ХЕДШОТЫ", value = stats.totalHeadshots.toString(), accent = RtxEmerald)
                    }
                }

                // Mission / Mode Selector
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "РЕЖИМ ОПЕРАЦИИ",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GameMode.entries.forEach { mode ->
                            val active = mode == selectedMode
                            val shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(shape)
                                    .background(
                                        if (active) RtxEmerald.copy(alpha = 0.18f)
                                        else TacticalSurface.copy(alpha = 0.82f)
                                    )
                                    .border(
                                        width = if (active) 1.5.dp else 1.dp,
                                        color = if (active) RtxEmerald else BorderSteel,
                                        shape = shape
                                    )
                                    .clickable { onSelectMode(mode) }
                                    .padding(vertical = 10.dp, horizontal = 8.dp)
                                    .testTag("mode_${mode.name.lowercase()}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = mode.titleRu,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (active) RtxEmerald else TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Text(
                        text = selectedMode.subtitleRu,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }

                // Primary CTA: В БОЙ
                Button(
                    onClick = onPlay,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .testTag("play_button"),
                    shape = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RtxEmerald,
                        contentColor = ObsidianVoid
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.btn_play),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                }

                // Secondary Actions: НАСТРОЙКИ + УПРАВЛЕНИЕ
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("settings_button"),
                        shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
                        border = BorderStroke(1.dp, BorderSteel),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = ElevatedGunmetal.copy(alpha = 0.85f),
                            contentColor = TextPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = RtxEmerald,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.btn_settings),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    OutlinedButton(
                        onClick = onOpenControls,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("controls_editor_button"),
                        shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
                        border = BorderStroke(1.dp, TacticalAmber.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = ElevatedGunmetal.copy(alpha = 0.85f),
                            contentColor = TextPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Gamepad,
                            contentDescription = null,
                            tint = TacticalAmber,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.btn_controls),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                // Engine capability footnote
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Модели бойцов 1:1 (1.80м) • PBR Нормали • RTX Отражения",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun StatBadge(label: String, value: String, accent: Color) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = accent
        )
    }
}
