package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.GameMode
import com.example.model.HudSnapshot
import com.example.model.ScoreboardRow
import com.example.model.TeamSide
import com.example.ui.theme.BorderSteel
import com.example.ui.theme.IceCyan
import com.example.ui.theme.LocalUiMetrics
import com.example.ui.theme.RtxEmerald
import com.example.ui.theme.TacticalAmber
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.ThreatCrimson

/**
 * CS2-style tactical TAB scoreboard overlay.
 * Displays Kills, Deaths, Money (strictly visible ONLY for teammates; enemies show "—"),
 * and live Ping (or BOT tag).
 */
@Composable
fun ScoreboardOverlay(
    hud: HudSnapshot,
    isMultiplayer: Boolean,
    onClose: () -> Unit
) {
    val m = LocalUiMetrics.current
    val ctRows = remember(hud.scoreboardRows) {
        hud.scoreboardRows
            .filter { it.team == TeamSide.CT }
            .sortedWith(compareByDescending<ScoreboardRow> { it.kills }.thenBy { it.deaths }.thenByDescending { it.isLocalPlayer })
    }
    val tRows = remember(hud.scoreboardRows) {
        hud.scoreboardRows
            .filter { it.team == TeamSide.T }
            .sortedWith(compareByDescending<ScoreboardRow> { it.kills }.thenBy { it.deaths }.thenByDescending { it.isLocalPlayer })
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xA805080F))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose
            )
            .safeDrawingPadding()
            .padding(horizontal = m.s(14f), vertical = m.s(10f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 820.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .testTag("scoreboard_overlay_card"),
            color = Color(0xEB0A111B),
            shape = CutCornerShape(topStart = m.s(14f), bottomEnd = m.s(14f)),
            border = BorderStroke(1.2.dp, BorderSteel.copy(alpha = 0.85f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = m.s(14f), vertical = m.s(10f)),
                verticalArrangement = Arrangement.spacedBy(m.s(8f))
            ) {
                // Header: Map + Mode + Score + Live Ping + Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "${hud.mapId.titleRu.uppercase()} • ${if (hud.mode == GameMode.BOMB_DEFUSAL) "ЗАКЛАДКА БОМБЫ" else "БОЙ НАСМЕРТЬ"}",
                            fontSize = m.sp(12.5f),
                            fontWeight = FontWeight.Black,
                            color = TextPrimary
                        )
                        Text(
                            text = if (hud.isWarmup) {
                                "РАЗМИНКА • Ожидание старта"
                            } else {
                                "Раунд ${hud.roundNumber} • До ${hud.roundsToWin} побед"
                            },
                            fontSize = m.sp(9.5f),
                            color = TextSecondary
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(m.s(10f))
                    ) {
                        // Central score pill
                        Surface(
                            color = Color(0xCC0E1826),
                            shape = RoundedCornerShape(m.s(8f)),
                            border = BorderStroke(1.dp, BorderSteel.copy(alpha = 0.6f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = m.s(12f), vertical = m.s(4f)),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(m.s(8f))
                            ) {
                                Text("CT", fontSize = m.sp(9.5f), fontWeight = FontWeight.Bold, color = IceCyan)
                                Text("${hud.ctScore}", fontSize = m.sp(15f), fontWeight = FontWeight.Black, color = IceCyan)
                                Text(":", fontSize = m.sp(13f), fontWeight = FontWeight.Bold, color = TextSecondary)
                                Text("${hud.tScore}", fontSize = m.sp(15f), fontWeight = FontWeight.Black, color = TacticalAmber)
                                Text("T", fontSize = m.sp(9.5f), fontWeight = FontWeight.Bold, color = TacticalAmber)
                            }
                        }

                        // Ping status badge
                        val pingColor = pingQualityColor(hud.pingMs, isMultiplayer)
                        Surface(
                            color = Color(0xCC0E1826),
                            shape = RoundedCornerShape(m.s(8f)),
                            border = BorderStroke(1.dp, pingColor.copy(alpha = 0.65f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = m.s(9f), vertical = m.s(5f)),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(m.s(5f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(m.s(7f))
                                        .clip(CircleShape)
                                        .background(pingColor)
                                )
                                Text(
                                    text = if (isMultiplayer) {
                                        if (hud.pingMs > 0) "ПИНГ ${hud.pingMs} мс" else "ПИНГ ..."
                                    } else {
                                        "ОФЛАЙН • 0 мс"
                                    },
                                    fontSize = m.sp(9.5f),
                                    fontWeight = FontWeight.ExtraBold,
                                    color = pingColor
                                )
                            }
                        }

                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("scoreboard_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Закрыть таблицу счёта",
                                tint = TextPrimary
                            )
                        }
                    }
                }

                HorizontalDivider(color = BorderSteel.copy(alpha = 0.45f))

                // Responsive team tables: side-by-side in landscape, stacked on narrow displays
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    val sideBySide = maxWidth >= 540.dp
                    if (sideBySide) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(m.s(10f))
                        ) {
                            TeamScoreboardSection(
                                title = "СПЕЦНАЗ (CT)",
                                accent = IceCyan,
                                rows = ctRows,
                                isMultiplayer = isMultiplayer,
                                modifier = Modifier.weight(1f)
                            )
                            TeamScoreboardSection(
                                title = "ТЕРРОРИСТЫ (T)",
                                accent = TacticalAmber,
                                rows = tRows,
                                isMultiplayer = isMultiplayer,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(m.s(10f))
                        ) {
                            TeamScoreboardSection(
                                title = "СПЕЦНАЗ (CT)",
                                accent = IceCyan,
                                rows = ctRows,
                                isMultiplayer = isMultiplayer,
                                modifier = Modifier.fillMaxWidth()
                            )
                            TeamScoreboardSection(
                                title = "ТЕРРОРИСТЫ (T)",
                                accent = TacticalAmber,
                                rows = tRows,
                                isMultiplayer = isMultiplayer,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamScoreboardSection(
    title: String,
    accent: Color,
    rows: List<ScoreboardRow>,
    isMultiplayer: Boolean,
    modifier: Modifier = Modifier
) {
    val m = LocalUiMetrics.current
    Surface(
        modifier = modifier,
        color = Color(0x99101926),
        shape = RoundedCornerShape(m.s(9f)),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.38f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Team title + Column headers
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(accent.copy(alpha = 0.14f))
                    .padding(horizontal = m.s(9f), vertical = m.s(5f)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = m.sp(9.5f),
                    fontWeight = FontWeight.Black,
                    color = accent,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "У",
                    fontSize = m.sp(9f),
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(m.s(28f))
                )
                Text(
                    text = "С",
                    fontSize = m.sp(9f),
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(m.s(28f))
                )
                Text(
                    text = "$",
                    fontSize = m.sp(9f),
                    fontWeight = FontWeight.Bold,
                    color = RtxEmerald.copy(alpha = 0.9f),
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(m.s(54f))
                )
                Text(
                    text = "ПИНГ",
                    fontSize = m.sp(8.5f),
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(m.s(46f))
                )
            }

            if (rows.isEmpty()) {
                Text(
                    text = "Нет бойцов",
                    fontSize = m.sp(9.5f),
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = m.s(10f), vertical = m.s(8f))
                )
            } else {
                rows.forEachIndexed { idx, row ->
                    if (idx > 0) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                    }
                    ScoreboardPlayerRow(
                        row = row,
                        accent = accent,
                        isMultiplayer = isMultiplayer
                    )
                }
            }
        }
    }
}

@Composable
private fun ScoreboardPlayerRow(
    row: ScoreboardRow,
    accent: Color,
    isMultiplayer: Boolean
) {
    val m = LocalUiMetrics.current
    val rowBg = when {
        row.isLocalPlayer -> accent.copy(alpha = 0.16f)
        !row.isAlive -> Color(0x44070A10)
        else -> Color.Transparent
    }
    val nameAlpha = if (row.isAlive) 1f else 0.52f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .padding(horizontal = m.s(9f), vertical = m.s(5f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Alive indicator + Name + Ally weapon tag
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.s(6f))
        ) {
            Box(
                modifier = Modifier
                    .size(m.s(7f))
                    .clip(CircleShape)
                    .background(
                        if (row.isAlive) {
                            if (row.isLocalPlayer) RtxEmerald else accent
                        } else {
                            ThreatCrimson.copy(alpha = 0.55f)
                        }
                    )
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(m.s(4f))
                ) {
                    Text(
                        text = row.name,
                        fontSize = m.sp(10f),
                        fontWeight = if (row.isLocalPlayer) FontWeight.Black else FontWeight.SemiBold,
                        color = if (row.isLocalPlayer) Color.White else TextPrimary.copy(alpha = nameAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (row.isLocalPlayer) {
                        Text(
                            text = "ВЫ",
                            fontSize = m.sp(7.5f),
                            fontWeight = FontWeight.Black,
                            color = RtxEmerald
                        )
                    } else if (!row.isAlive) {
                        Text(
                            text = "✕",
                            fontSize = m.sp(8.5f),
                            fontWeight = FontWeight.Bold,
                            color = ThreatCrimson.copy(alpha = 0.78f)
                        )
                    }
                }
                // Show ally equipped weapon only (never leak enemy loadout!)
                if (row.isAlly && row.weaponLabel.isNotBlank() && row.isAlive) {
                    Text(
                        text = row.weaponLabel,
                        fontSize = m.sp(8f),
                        color = TextSecondary.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Kills
        Text(
            text = "${row.kills}",
            fontSize = m.sp(10.5f),
            fontWeight = FontWeight.ExtraBold,
            color = TextPrimary.copy(alpha = nameAlpha),
            textAlign = TextAlign.Center,
            modifier = Modifier.width(m.s(28f))
        )

        // Deaths
        Text(
            text = "${row.deaths}",
            fontSize = m.sp(10f),
            fontWeight = FontWeight.SemiBold,
            color = TextSecondary.copy(alpha = nameAlpha),
            textAlign = TextAlign.Center,
            modifier = Modifier.width(m.s(28f))
        )

        // Money — STRICTLY HIDDEN for enemies!
        val showMoney = row.isAlly && row.money >= 0
        Text(
            text = if (showMoney) "$${row.money}" else "—",
            fontSize = m.sp(10f),
            fontWeight = if (showMoney) FontWeight.Bold else FontWeight.Normal,
            color = if (showMoney) RtxEmerald.copy(alpha = nameAlpha) else TextSecondary.copy(alpha = 0.42f),
            textAlign = TextAlign.End,
            modifier = Modifier.width(m.s(54f))
        )

        // Ping
        val pingText = when {
            row.isBot -> "БОТ"
            !isMultiplayer -> "0 мс"
            row.pingMs > 0 -> "${row.pingMs} мс"
            else -> "..."
        }
        val pingTint = when {
            row.isBot -> TextSecondary.copy(alpha = 0.7f)
            else -> pingQualityColor(row.pingMs, isMultiplayer)
        }
        Text(
            text = pingText,
            fontSize = m.sp(9f),
            fontWeight = FontWeight.Bold,
            color = pingTint,
            textAlign = TextAlign.End,
            modifier = Modifier.width(m.s(46f))
        )
    }
}

fun pingQualityColor(pingMs: Int, isMultiplayer: Boolean): Color {
    if (!isMultiplayer) return RtxEmerald
    return when {
        pingMs <= 0 -> IceCyan
        pingMs < 75 -> RtxEmerald
        pingMs < 145 -> TacticalAmber
        else -> ThreatCrimson
    }
}
