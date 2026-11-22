package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.DeathRecapInfo
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
 * Dramatic crimson bloodied screen vignette + edge splatter overlay rendered for several seconds after death.
 */
@Composable
fun BloodiedDeathOverlay(
    alpha: Float,
    modifier: Modifier = Modifier
) {
    val a = alpha.coerceIn(0f, 1f)
    if (a <= 0.01f) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val radius = maxOf(w, h) * 0.78f

        // 1. Deep crimson blood vignette around screen perimeter
        drawRect(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to Color(0xFF3A0004).copy(alpha = a * 0.24f),
                    0.46f to Color(0xFF6B040D).copy(alpha = a * 0.42f),
                    0.78f to Color(0xFF9E0614).copy(alpha = a * 0.72f),
                    1.00f to Color(0xFF420006).copy(alpha = a * 0.92f)
                ),
                center = Offset(w * 0.5f, h * 0.5f),
                radius = radius
            )
        )

        // 2. Organic blood splatters & droplets along the corners and edges
        val splatterSpecs = listOf(
            Triple(0.06f, 0.14f, 0.095f),
            Triple(0.11f, 0.24f, 0.048f),
            Triple(0.04f, 0.78f, 0.110f),
            Triple(0.13f, 0.87f, 0.055f),
            Triple(0.92f, 0.16f, 0.105f),
            Triple(0.86f, 0.26f, 0.045f),
            Triple(0.95f, 0.80f, 0.120f),
            Triple(0.85f, 0.89f, 0.062f),
            Triple(0.24f, 0.06f, 0.052f),
            Triple(0.76f, 0.07f, 0.058f),
            Triple(0.32f, 0.94f, 0.064f),
            Triple(0.68f, 0.92f, 0.054f)
        )
        for ((fx, fy, fr) in splatterSpecs) {
            val cx = w * fx
            val cy = h * fy
            val r = minOf(w, h) * fr
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF8B000D).copy(alpha = a * 0.68f),
                        Color(0xFF540008).copy(alpha = a * 0.34f),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = r
                ),
                radius = r,
                center = Offset(cx, cy)
            )
            // Subtle blood drip streaking downward from each edge splatter
            drawCircle(
                color = Color(0xFF70000A).copy(alpha = a * 0.45f),
                radius = r * 0.22f,
                center = Offset(cx + r * 0.18f, cy + r * 0.72f)
            )
        }
    }
}

/**
 * CS2-style Death Recap Card: shows who killed you, with what weapon,
 * how much damage YOU dealt to them, and how much damage THEY dealt to you.
 */
@Composable
fun DeathRecapCard(
    recap: DeathRecapInfo,
    modifier: Modifier = Modifier
) {
    val m = LocalUiMetrics.current
    val killerColor = if (recap.killerTeam == TeamSide.CT) IceCyan else TacticalAmber

    Surface(
        modifier = modifier
            .widthIn(min = m.s(260f), max = m.s(370f))
            .testTag("death_recap_card"),
        color = Color(0xE014070B),
        shape = CutCornerShape(topStart = m.s(12f), bottomEnd = m.s(12f)),
        border = BorderStroke(1.4.dp, ThreatCrimson.copy(alpha = 0.88f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = m.s(14f), vertical = m.s(9f)),
            verticalArrangement = Arrangement.spacedBy(m.s(6f))
        ) {
            // Killer title row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (recap.killedByBomb) "ПОГИБ ОТ ВЗРЫВА C4" else "ВАС УБИЛ",
                        fontSize = m.sp(8.5f),
                        fontWeight = FontWeight.Black,
                        color = ThreatCrimson
                    )
                    Text(
                        text = recap.killerName.ifBlank { "Противник" },
                        fontSize = m.sp(14f),
                        fontWeight = FontWeight.Black,
                        color = killerColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(m.s(5f))
                ) {
                    if (recap.killerWeapon.isNotBlank()) {
                        Surface(
                            color = Color(0xCC1E1016),
                            shape = RoundedCornerShape(m.s(5f)),
                            border = BorderStroke(1.dp, BorderSteel.copy(alpha = 0.65f))
                        ) {
                            Text(
                                text = recap.killerWeapon,
                                fontSize = m.sp(9.5f),
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary,
                                modifier = Modifier.padding(horizontal = m.s(7f), vertical = m.s(3f))
                            )
                        }
                    }
                    if (recap.wasHeadshot) {
                        Surface(
                            color = ThreatCrimson.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(m.s(5f)),
                            border = BorderStroke(1.dp, ThreatCrimson)
                        ) {
                            Text(
                                text = "В ГОЛОВУ",
                                fontSize = m.sp(8.5f),
                                fontWeight = FontWeight.Black,
                                color = ThreatCrimson,
                                modifier = Modifier.padding(horizontal = m.s(6f), vertical = m.s(3f))
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = ThreatCrimson.copy(alpha = 0.35f))

            // Damage exchange comparison
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(m.s(10f))
            ) {
                // Damage YOU dealt to killer
                Surface(
                    modifier = Modifier.weight(1f),
                    color = Color(0x990D1821),
                    shape = RoundedCornerShape(m.s(7f)),
                    border = BorderStroke(1.dp, RtxEmerald.copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = m.s(9f), vertical = m.s(5f)),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Text(
                            text = "ВЫ НАНЕСЛИ",
                            fontSize = m.sp(8f),
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary
                        )
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(m.s(5f))
                        ) {
                            Text(
                                text = "${recap.damageGiven}",
                                fontSize = m.sp(15f),
                                fontWeight = FontWeight.Black,
                                color = if (recap.damageGiven > 0) RtxEmerald else TextSecondary
                            )
                            Text(
                                text = "урона (${recap.hitsGiven} поп.)",
                                fontSize = m.sp(8.5f),
                                color = TextSecondary,
                                modifier = Modifier.padding(bottom = 1.dp)
                            )
                        }
                    }
                }

                // Damage KILLER dealt to you
                Surface(
                    modifier = Modifier.weight(1f),
                    color = Color(0x991E0B10),
                    shape = RoundedCornerShape(m.s(7f)),
                    border = BorderStroke(1.dp, ThreatCrimson.copy(alpha = 0.55f))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = m.s(9f), vertical = m.s(5f)),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Text(
                            text = "ОН НАНЁС ВАМ",
                            fontSize = m.sp(8f),
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary
                        )
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(m.s(5f))
                        ) {
                            Text(
                                text = "${recap.damageTaken}",
                                fontSize = m.sp(15f),
                                fontWeight = FontWeight.Black,
                                color = ThreatCrimson
                            )
                            Text(
                                text = "урона (${recap.hitsTaken} поп.)",
                                fontSize = m.sp(8.5f),
                                color = TextSecondary,
                                modifier = Modifier.padding(bottom = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Bottom tactical bar while spectating surviving allies after death, with Prev/Next switcher.
 */
@Composable
fun SpectatorControlBar(
    allyName: String,
    allyHp: Int,
    allyArmor: Int,
    allyWeapon: String,
    allyTeam: TeamSide,
    allyIndex: Int,
    allyCount: Int,
    onPrevAlly: () -> Unit,
    onNextAlly: () -> Unit,
    modifier: Modifier = Modifier
) {
    val m = LocalUiMetrics.current
    val teamAccent = if (allyTeam == TeamSide.CT) IceCyan else TacticalAmber

    Surface(
        modifier = modifier
            .widthIn(min = m.s(260f), max = m.s(420f))
            .testTag("spectator_control_bar"),
        color = Color(0xD90A121D),
        shape = CutCornerShape(topStart = m.s(11f), bottomEnd = m.s(11f)),
        border = BorderStroke(1.2.dp, teamAccent.copy(alpha = 0.78f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = m.s(6f), vertical = m.s(4f)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.s(6f))
        ) {
            if (allyCount > 1) {
                IconButton(
                    onClick = onPrevAlly,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("spectate_prev_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Предыдущий союзник",
                        tint = teamAccent
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = m.s(6f), vertical = m.s(3f)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(m.s(5f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = null,
                        tint = teamAccent,
                        modifier = Modifier.size(m.s(13f))
                    )
                    Text(
                        text = "НАБЛЮДЕНИЕ • $allyName",
                        fontSize = m.sp(11.5f),
                        fontWeight = FontWeight.Black,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (allyCount > 1) {
                        Text(
                            text = "(${allyIndex + 1}/$allyCount)",
                            fontSize = m.sp(9.5f),
                            fontWeight = FontWeight.Bold,
                            color = teamAccent
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(m.s(10f))
                ) {
                    val hpColor = when {
                        allyHp > 50 -> RtxEmerald
                        allyHp > 25 -> TacticalAmber
                        else -> ThreatCrimson
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(m.s(4f))
                    ) {
                        Box(
                            modifier = Modifier
                                .size(m.s(6f))
                                .clip(CircleShape)
                                .background(hpColor)
                        )
                        Text(
                            text = "HP $allyHp",
                            fontSize = m.sp(10f),
                            fontWeight = FontWeight.ExtraBold,
                            color = hpColor
                        )
                    }
                    Text(
                        text = "БР $allyArmor",
                        fontSize = m.sp(9.5f),
                        fontWeight = FontWeight.Bold,
                        color = IceCyan
                    )
                    if (allyWeapon.isNotBlank()) {
                        Text(
                            text = allyWeapon,
                            fontSize = m.sp(9.5f),
                            fontWeight = FontWeight.ExtraBold,
                            color = TacticalAmber
                        )
                    }
                }
            }

            if (allyCount > 1) {
                IconButton(
                    onClick = onNextAlly,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("spectate_next_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Следующий союзник",
                        tint = teamAccent
                    )
                }
            }
        }
    }
}
