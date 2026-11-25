package com.example.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.EspBox
import com.example.model.LevelProgression
import com.example.model.ScoreboardRow
import com.example.model.SkinCatalog
import com.example.model.TeamSide
import com.example.model.WeaponType
import com.example.online.ADMIN_SHORT_ID
import com.example.online.BannedDeviceEntry
import com.example.online.ClientAccessStatus
import com.example.online.PlayerMatchState
import com.example.online.PlayerProfile
import com.example.online.isValidPlayerShortId
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

/**
 * Touch-transparent 2D Wallhack ESP Canvas exclusively rendered for Admin ID "developer".
 * Draws corner-bracketed bounding boxes, head dots, HP bars, subtle snaplines, and name/distance/weapon tags.
 */
@Composable
fun AdminEspOverlay(
    boxes: List<EspBox>,
    modifier: Modifier = Modifier
) {
    if (boxes.isEmpty()) return

    val namePaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            setShadowLayer(4f, 0f, 1.5f, android.graphics.Color.BLACK)
        }
    }
    val subPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 21f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            setShadowLayer(3f, 0f, 1f, android.graphics.Color.BLACK)
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val topMidX = w * 0.5f

        for (box in boxes) {
            val left = box.leftFrac * w
            val top = box.topFrac * h
            val right = box.rightFrac * w
            val bottom = box.bottomFrac * h
            val bw = (right - left).coerceAtLeast(18f)
            val bh = (bottom - top).coerceAtLeast(28f)
            val cx = (left + right) * 0.5f
            val headY = box.headYFrac * h

            val boxColor = if (box.hasLineOfSight) Color(0xFF00F5A0) else Color(0xFFFF3358)
            val argbColor = if (box.hasLineOfSight) 0xFF00F5A0.toInt() else 0xFFFF4D6D.toInt()

            // Subtle snapline from top-center to target
            drawLine(
                color = boxColor.copy(alpha = 0.28f),
                start = Offset(topMidX, 0f),
                end = Offset(cx, top),
                strokeWidth = 1.6f
            )

            // Subtle translucent fill
            drawRect(
                color = boxColor.copy(alpha = 0.08f),
                topLeft = Offset(left, top),
                size = Size(bw, bh)
            )

            // Crisp tactical corner brackets
            val cornerW = (bw * 0.26f).coerceIn(6f, 24f)
            val cornerH = (bh * 0.22f).coerceIn(8f, 30f)
            val sw = 3.2f
            // Top-left
            drawLine(boxColor, Offset(left, top), Offset(left + cornerW, top), sw)
            drawLine(boxColor, Offset(left, top), Offset(left, top + cornerH), sw)
            // Top-right
            drawLine(boxColor, Offset(right, top), Offset(right - cornerW, top), sw)
            drawLine(boxColor, Offset(right, top), Offset(right, top + cornerH), sw)
            // Bottom-left
            drawLine(boxColor, Offset(left, bottom), Offset(left + cornerW, bottom), sw)
            drawLine(boxColor, Offset(left, bottom), Offset(left, bottom - cornerH), sw)
            // Bottom-right
            drawLine(boxColor, Offset(right, bottom), Offset(right - cornerW, bottom), sw)
            drawLine(boxColor, Offset(right, bottom), Offset(right, bottom - cornerH), sw)

            // Head dot indicator
            drawCircle(
                color = Color(0xFFFFD166),
                radius = (bw * 0.085f).coerceIn(3f, 7f),
                center = Offset(cx, headY)
            )

            // Vertical HP bar along left edge
            val hpFrac = (box.hp / 100f).coerceIn(0f, 1f)
            val barX = left - 8f
            drawRect(
                color = Color(0xCC06090F),
                topLeft = Offset(barX, top),
                size = Size(5f, bh)
            )
            val hpColor = when {
                hpFrac > 0.6f -> Color(0xFF00E676)
                hpFrac > 0.3f -> Color(0xFFFFB703)
                else -> Color(0xFFFF3358)
            }
            val filledH = bh * hpFrac
            drawRect(
                color = hpColor,
                topLeft = Offset(barX, bottom - filledH),
                size = Size(5f, filledH)
            )

            // Name + Distance + Weapon text
            namePaint.color = argbColor
            subPaint.color = android.graphics.Color.WHITE
            drawContext.canvas.nativeCanvas.drawText(
                "${box.name} [${box.hp} HP]",
                cx,
                (top - 10f).coerceAtLeast(24f),
                namePaint
            )
            drawContext.canvas.nativeCanvas.drawText(
                "${box.weaponLabel} • ${box.distanceMeters}м",
                cx,
                (bottom + 24f).coerceAtMost(h - 8f),
                subPaint
            )
        }
    }
}

/**
 * Exclusive Admin Control Panel rendered ONLY for player with ID "developer".
 */
@Composable
fun AdminPanelOverlay(
    inMatch: Boolean,
    currentUserId: String,
    myProfile: PlayerProfile?,
    espEnabled: Boolean,
    autoAimEnabled: Boolean,
    onToggleEsp: (Boolean) -> Unit,
    onToggleAutoAim: (Boolean) -> Unit,
    lobbyMembers: List<PlayerMatchState>,
    scoreboardRows: List<ScoreboardRow>,
    bannedAccounts: List<PlayerProfile>,
    bannedDevices: List<BannedDeviceEntry> = emptyList(),
    isBusy: Boolean,
    statusMessage: String?,
    onRefreshBanned: () -> Unit,
    onKickHuman: (targetUid: String, targetShortId: String, targetName: String) -> Unit,
    onKickBot: (botId: Int) -> Unit,
    onBanAccount: (shortId: String, reason: String, targetUid: String) -> Unit,
    onUnbanAccount: (shortId: String) -> Unit,
    onSetDeviceBanByAccount: (shortId: String, banned: Boolean) -> Unit = { _, _ -> },
    onUnbanDeviceHash: (deviceHash: String) -> Unit = {},
    onGrantXp: (targetShortId: String, xpAmount: Int) -> Unit,
    onGrantSkins: (targetShortId: String, skinIds: List<String>) -> Unit,
    onClearStatus: () -> Unit,
    onClose: () -> Unit
) {
    BackHandler { onClose() }

    val m = LocalUiMetrics.current
    var selectedTab by remember { mutableIntStateOf(if (inMatch) 0 else 1) }
    var targetIdInput by remember { mutableStateOf("") }
    var banReasonInput by remember { mutableStateOf("") }

    // Tab 1 (EXP & Skins) states
    var grantTargetId by remember { mutableStateOf(ADMIN_SHORT_ID) }
    var customXpInput by remember { mutableStateOf("1000") }
    var selectedSkinWeapon by remember { mutableStateOf(WeaponType.AK47) }

    LaunchedEffect(Unit) {
        onRefreshBanned()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xD905080E))
            .clickable(enabled = true, onClick = {})
            .safeDrawingPadding()
            .padding(horizontal = m.s(14f), vertical = m.s(10f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = TacticalSurface,
            shape = CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp),
            border = BorderStroke(1.5.dp, ThreatCrimson.copy(alpha = 0.85f)),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp)
                .fillMaxHeight(0.92f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CutCornerShape(6.dp))
                                .background(ThreatCrimson.copy(alpha = 0.22f))
                                .border(1.dp, ThreatCrimson, CutCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "Админ-панель",
                                tint = ThreatCrimson,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "АДМИН-ПАНЕЛЬ",
                                fontSize = m.sp(14.5f),
                                fontWeight = FontWeight.Black,
                                color = TextPrimary
                            )
                            Text(
                                text = "Доступ подтверждён • ID $ADMIN_SHORT_ID",
                                fontSize = m.sp(9.5f),
                                fontWeight = FontWeight.Bold,
                                color = TacticalAmber
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AdminTabChip(
                            label = "БОЙ И КИК",
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            tag = "admin_tab_combat"
                        )
                        AdminTabChip(
                            label = "EXP И СКИНЫ",
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            tag = "admin_tab_grants"
                        )
                        AdminTabChip(
                            label = "БАН / РАЗБАН",
                            selected = selectedTab == 2,
                            onClick = {
                                selectedTab = 2
                                onRefreshBanned()
                            },
                            tag = "admin_tab_bans"
                        )
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("admin_panel_close")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Закрыть админ-панель",
                                tint = TextPrimary
                            )
                        }
                    }
                }

                // Status feedback banner
                if (!statusMessage.isNullOrBlank()) {
                    Surface(
                        color = ElevatedGunmetal,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, TacticalAmber.copy(alpha = 0.7f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = statusMessage,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TacticalAmber,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = onClearStatus,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Скрыть",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // Scrollable Body
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (selectedTab == 0) {
                        // Combat toggles: ESP + Auto-Aim
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AdminToggleCard(
                                title = "ESP (WALLHACK)",
                                subtitle = "Рамки врагов сквозь стены, HP, оружие и дистанция",
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.Visibility,
                                        contentDescription = null,
                                        tint = if (espEnabled) RtxEmerald else TextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                checked = espEnabled,
                                onCheckedChange = onToggleEsp,
                                tag = "admin_toggle_esp",
                                modifier = Modifier.weight(1f)
                            )
                            AdminToggleCard(
                                title = "АВТОНАВОДКА",
                                subtitle = "Точное наведение прицела в голову видимых врагов",
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.GpsFixed,
                                        contentDescription = null,
                                        tint = if (autoAimEnabled) ThreatCrimson else TextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                checked = autoAimEnabled,
                                onCheckedChange = onToggleAutoAim,
                                tag = "admin_toggle_autoaim",
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Live Players in Lobby / Match for instant Kick & Ban
                        Surface(
                            color = ObsidianVoid.copy(alpha = 0.72f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BorderSteel),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "ИГРОКИ В МАТЧЕ / ЛОББИ",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Black,
                                    color = IceCyan
                                )

                                val humanTargets = lobbyMembers.filter {
                                    it.userId != currentUserId && it.shortId != ADMIN_SHORT_ID
                                }
                                val botTargets = scoreboardRows.filter { it.isBot }

                                if (humanTargets.isEmpty() && botTargets.isEmpty()) {
                                    Text(
                                        text = "Сейчас нет других игроков в текущем матче или лобби. Чтобы забанить игрока по ID — откройте вкладку «БАН / РАЗБАН».",
                                        fontSize = 11.5.sp,
                                        color = TextSecondary
                                    )
                                }

                                humanTargets.forEach { member ->
                                    val sideColor = if (member.team == TeamSide.CT.name) IceCyan else TacticalAmber
                                    Surface(
                                        color = ElevatedGunmetal,
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, BorderSteel.copy(alpha = 0.8f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = member.displayName.ifBlank { "Игрок" },
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "LVL ${member.level.coerceAtLeast(1)} • ID ${member.shortId.ifBlank { "—" }} • ${member.team} • ${member.hp} HP",
                                                    fontSize = 10.5.sp,
                                                    color = sideColor
                                                )
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                OutlinedButton(
                                                    onClick = {
                                                        onKickHuman(
                                                            member.userId,
                                                            member.shortId,
                                                            member.displayName.ifBlank { "Игрок" }
                                                        )
                                                    },
                                                    border = BorderStroke(1.dp, TacticalAmber),
                                                    shape = RoundedCornerShape(6.dp),
                                                    modifier = Modifier.testTag("admin_kick_${member.shortId}")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.PersonRemove,
                                                        contentDescription = "Кик",
                                                        tint = TacticalAmber,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(Modifier.width(4.dp))
                                                    Text("КИК", color = TacticalAmber, fontSize = 11.sp, fontWeight = FontWeight.Black)
                                                }
                                                if (member.shortId.length == 6) {
                                                    Button(
                                                        onClick = {
                                                            onBanAccount(
                                                                member.shortId,
                                                                "Забанен в матче администратором",
                                                                member.userId
                                                            )
                                                        },
                                                        enabled = !isBusy,
                                                        colors = ButtonDefaults.buttonColors(containerColor = ThreatCrimson),
                                                        shape = RoundedCornerShape(6.dp),
                                                        modifier = Modifier.testTag("admin_ban_live_${member.shortId}")
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Block,
                                                            contentDescription = "Бан",
                                                            tint = Color.White,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(Modifier.width(4.dp))
                                                        Text("БАН", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                botTargets.forEach { botRow ->
                                    val numericBotId = botRow.id.removePrefix("bot_").toIntOrNull()
                                    if (numericBotId != null) {
                                        Surface(
                                            color = ElevatedGunmetal.copy(alpha = 0.72f),
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, BorderSteel.copy(alpha = 0.55f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "${botRow.name} [БОТ]",
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = TextPrimary
                                                    )
                                                    Text(
                                                        text = "${botRow.team.shortRu} • K:${botRow.kills} / D:${botRow.deaths}",
                                                        fontSize = 10.sp,
                                                        color = TextSecondary
                                                    )
                                                }
                                                OutlinedButton(
                                                    onClick = { onKickBot(numericBotId) },
                                                    border = BorderStroke(1.dp, TacticalAmber.copy(alpha = 0.8f)),
                                                    shape = RoundedCornerShape(6.dp),
                                                    modifier = Modifier.testTag("admin_kick_bot_$numericBotId")
                                                ) {
                                                    Text("КИК БОТА", color = TacticalAmber, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else if (selectedTab == 1) {
                        // TAB 1: EXP Booster & Skin Granter (Self or Any Player)
                        val validTarget = isValidPlayerShortId(grantTargetId)
                        val myProgress = myProfile?.let { LevelProgression.computeFromTotalXp(it.xp) }

                        Surface(
                            color = ObsidianVoid.copy(alpha = 0.76f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, IceCyan.copy(alpha = 0.55f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "ЦЕЛЕВОЙ ИГРОК (СЕБЕ ИЛИ ПО ID)",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Black,
                                        color = IceCyan
                                    )
                                    if (myProfile != null && myProgress != null) {
                                        Text(
                                            text = "Вы: LVL ${myProfile.level} • ${myProfile.coins} 🪙 • Скинов: ${myProfile.ownedSkins.size}",
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TacticalAmber
                                        )
                                    }
                                }

                                // Quick target selector chips (Self + Lobby members)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val selfSelected = grantTargetId.equals(ADMIN_SHORT_ID, ignoreCase = true)
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (selfSelected) RtxEmerald.copy(alpha = 0.24f) else ElevatedGunmetal)
                                            .border(1.dp, if (selfSelected) RtxEmerald else BorderSteel, RoundedCornerShape(6.dp))
                                            .clickable { grantTargetId = ADMIN_SHORT_ID }
                                            .padding(horizontal = 10.dp, vertical = 7.dp)
                                            .testTag("admin_grant_target_self")
                                    ) {
                                        Text(
                                            text = "СЕБЕ ($ADMIN_SHORT_ID)",
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Black,
                                            color = if (selfSelected) RtxEmerald else TextPrimary
                                        )
                                    }
                                    lobbyMembers
                                        .filter { it.shortId.isNotBlank() && !it.shortId.equals(ADMIN_SHORT_ID, ignoreCase = true) }
                                        .distinctBy { it.shortId }
                                        .forEach { member ->
                                            val sel = grantTargetId.equals(member.shortId, ignoreCase = true)
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(if (sel) IceCyan.copy(alpha = 0.24f) else ElevatedGunmetal)
                                                    .border(1.dp, if (sel) IceCyan else BorderSteel, RoundedCornerShape(6.dp))
                                                    .clickable { grantTargetId = member.shortId }
                                                    .padding(horizontal = 10.dp, vertical = 7.dp)
                                                    .testTag("admin_grant_target_${member.shortId}")
                                            ) {
                                                Text(
                                                    text = "${member.displayName.ifBlank { "Игрок" }} (${member.shortId})",
                                                    fontSize = 10.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (sel) IceCyan else TextPrimary
                                                )
                                            }
                                        }
                                }

                                OutlinedTextField(
                                    value = grantTargetId,
                                    onValueChange = { raw ->
                                        grantTargetId = raw.filter { it.isLetterOrDigit() }.lowercase().take(9)
                                    },
                                    label = { Text("ID получателя (developer или 6 цифр)", fontSize = 11.sp) },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = IceCyan,
                                        unfocusedBorderColor = BorderSteel,
                                        focusedTextColor = TextPrimary,
                                        unfocusedTextColor = TextPrimary
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("admin_grant_target_input")
                                )
                            }
                        }

                        // EXP Booster Card
                        Surface(
                            color = ObsidianVoid.copy(alpha = 0.76f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, TacticalAmber.copy(alpha = 0.55f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Default.Bolt, contentDescription = null, tint = TacticalAmber, modifier = Modifier.size(18.dp))
                                    Text(
                                        text = "НАКРУТЧИК EXP (+50 МОНЕТ ЗА КАЖДЫЙ УРОВЕНЬ)",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Black,
                                        color = TacticalAmber
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(500, 2500, 10000, 50000).forEach { preset ->
                                        OutlinedButton(
                                            onClick = { onGrantXp(grantTargetId, preset) },
                                            enabled = !isBusy && validTarget,
                                            border = BorderStroke(1.dp, TacticalAmber.copy(alpha = 0.8f)),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(44.dp)
                                                .testTag("admin_xp_preset_$preset")
                                        ) {
                                            Text(
                                                text = "+$preset",
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Black,
                                                color = TacticalAmber,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = customXpInput,
                                        onValueChange = { raw ->
                                            customXpInput = raw.filter { it.isDigit() }.take(7)
                                        },
                                        label = { Text("Своё количество EXP", fontSize = 11.sp) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = TacticalAmber,
                                            unfocusedBorderColor = BorderSteel,
                                            focusedTextColor = TextPrimary,
                                            unfocusedTextColor = TextPrimary
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("admin_custom_xp_input")
                                    )
                                    val parsedXp = customXpInput.toIntOrNull() ?: 0
                                    Button(
                                        onClick = { if (parsedXp > 0) onGrantXp(grantTargetId, parsedXp) },
                                        enabled = !isBusy && validTarget && parsedXp > 0,
                                        colors = ButtonDefaults.buttonColors(containerColor = TacticalAmber, contentColor = ObsidianVoid),
                                        shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                                        modifier = Modifier
                                            .height(50.dp)
                                            .testTag("admin_grant_custom_xp_button")
                                    ) {
                                        Text("НАКРУТИТЬ EXP", fontWeight = FontWeight.Black, fontSize = 11.5.sp)
                                    }
                                }
                            }
                        }

                        // Skin Granter Card
                        Surface(
                            color = ObsidianVoid.copy(alpha = 0.76f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, RtxEmerald.copy(alpha = 0.55f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = RtxEmerald, modifier = Modifier.size(18.dp))
                                        Text(
                                            text = "ВЫДАЧА СКИНОВ (${SkinCatalog.allSkins.size})",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Black,
                                            color = RtxEmerald
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            onGrantSkins(grantTargetId, SkinCatalog.allSkins.map { it.id })
                                        },
                                        enabled = !isBusy && validTarget,
                                        colors = ButtonDefaults.buttonColors(containerColor = RtxEmerald, contentColor = ObsidianVoid),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier
                                            .height(40.dp)
                                            .testTag("admin_grant_all_skins_button")
                                    ) {
                                        Text("ВЫДАТЬ ВСЕ СКИНЫ", fontWeight = FontWeight.Black, fontSize = 10.5.sp)
                                    }
                                }

                                // Weapon filter strip
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    SkinCatalog.skinnableWeapons.forEach { w ->
                                        val selected = selectedSkinWeapon == w
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (selected) IceCyan.copy(alpha = 0.22f) else ElevatedGunmetal)
                                                .border(1.dp, if (selected) IceCyan else BorderSteel, RoundedCornerShape(6.dp))
                                                .clickable { selectedSkinWeapon = w }
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                                .testTag("admin_skin_filter_${w.name.lowercase()}")
                                        ) {
                                            Text(
                                                text = w.shortLabel,
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (selected) IceCyan else TextPrimary
                                            )
                                        }
                                    }
                                }

                                val weaponSkins = remember(selectedSkinWeapon) {
                                    SkinCatalog.forWeapon(selectedSkinWeapon)
                                }
                                weaponSkins.forEach { skin ->
                                    val alreadyOwnedBySelf = grantTargetId.equals(ADMIN_SHORT_ID, ignoreCase = true) &&
                                        myProfile?.ownedSkins?.contains(skin.id) == true
                                    Surface(
                                        color = ElevatedGunmetal,
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, skin.rarity.accentColor.copy(alpha = 0.65f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                // 3-color material preview strip
                                                Row(
                                                    modifier = Modifier
                                                        .size(width = 36.dp, height = 24.dp)
                                                        .clip(RoundedCornerShape(5.dp))
                                                        .border(1.dp, skin.rarity.accentColor, RoundedCornerShape(5.dp))
                                                ) {
                                                    Box(Modifier.weight(1f).fillMaxHeight().background(skin.primaryColor))
                                                    Box(Modifier.weight(1f).fillMaxHeight().background(skin.secondaryColor))
                                                    Box(Modifier.weight(0.6f).fillMaxHeight().background(skin.accentColor))
                                                }
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = skin.nameRu,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = TextPrimary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = "${skin.rarity.displayRu} • ${skin.subtitleRu}",
                                                        fontSize = 9.5.sp,
                                                        color = skin.rarity.accentColor,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                            Button(
                                                onClick = { onGrantSkins(grantTargetId, listOf(skin.id)) },
                                                enabled = !isBusy && validTarget,
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (alreadyOwnedBySelf) BorderSteel else skin.rarity.accentColor,
                                                    contentColor = if (alreadyOwnedBySelf) TextPrimary else ObsidianVoid
                                                ),
                                                shape = RoundedCornerShape(6.dp),
                                                modifier = Modifier
                                                    .height(40.dp)
                                                    .testTag("admin_grant_skin_${skin.id}")
                                            ) {
                                                Text(
                                                    text = if (alreadyOwnedBySelf) "ЕСТЬ" else "ВЫДАТЬ",
                                                    fontSize = 10.5.sp,
                                                    fontWeight = FontWeight.Black
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // TAB 2: Ban / Unban by 6-digit ID + Banned Accounts List
                        Surface(
                            color = ObsidianVoid.copy(alpha = 0.72f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, ThreatCrimson.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "БЛОКИРОВКА И РАЗБЛОКИРОВКА ПО 6-ЗНАЧНОМУ ID",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Black,
                                    color = ThreatCrimson
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = targetIdInput,
                                        onValueChange = { raw ->
                                            targetIdInput = raw.filter { it.isDigit() }.take(6)
                                        },
                                        label = { Text("ID игрока (6 цифр)", fontSize = 11.sp) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = ThreatCrimson,
                                            unfocusedBorderColor = BorderSteel,
                                            focusedTextColor = TextPrimary,
                                            unfocusedTextColor = TextPrimary
                                        ),
                                        modifier = Modifier
                                            .weight(0.42f)
                                            .testTag("admin_ban_id_input")
                                    )
                                    OutlinedTextField(
                                        value = banReasonInput,
                                        onValueChange = { banReasonInput = it.take(120) },
                                        label = { Text("Причина бана (необязательно)", fontSize = 11.sp) },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = TacticalAmber,
                                            unfocusedBorderColor = BorderSteel,
                                            focusedTextColor = TextPrimary,
                                            unfocusedTextColor = TextPrimary
                                        ),
                                        modifier = Modifier
                                            .weight(0.58f)
                                            .testTag("admin_ban_reason_input")
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            if (targetIdInput.length == 6) {
                                                onBanAccount(targetIdInput, banReasonInput, "")
                                            }
                                        },
                                        enabled = !isBusy && targetIdInput.length == 6 && targetIdInput != ADMIN_SHORT_ID,
                                        colors = ButtonDefaults.buttonColors(containerColor = ThreatCrimson),
                                        shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("admin_ban_submit")
                                    ) {
                                        Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("ЗАБАНИТЬ АККАУНТ", fontWeight = FontWeight.Black, fontSize = 12.sp)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            if (targetIdInput.length == 6) {
                                                onUnbanAccount(targetIdInput)
                                            }
                                        },
                                        enabled = !isBusy && targetIdInput.length == 6,
                                        border = BorderStroke(1.5.dp, RtxEmerald),
                                        shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("admin_unban_submit")
                                    ) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = RtxEmerald,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text("РАЗБАНИТЬ", color = RtxEmerald, fontWeight = FontWeight.Black, fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        // Banned Accounts Roster
                        Surface(
                            color = ObsidianVoid.copy(alpha = 0.72f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BorderSteel),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "ЗАБАНЕННЫЕ АККАУНТЫ (${bannedAccounts.size})",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Black,
                                        color = TacticalAmber
                                    )
                                    OutlinedButton(
                                        onClick = onRefreshBanned,
                                        enabled = !isBusy,
                                        border = BorderStroke(1.dp, BorderSteel),
                                        modifier = Modifier.testTag("admin_refresh_banned")
                                    ) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = "Обновить список",
                                            tint = TextPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("ОБНОВИТЬ", color = TextPrimary, fontSize = 10.5.sp)
                                    }
                                }

                                if (bannedAccounts.isEmpty()) {
                                    Text(
                                        text = "Список забаненных аккаунтов пуст.",
                                        fontSize = 11.5.sp,
                                        color = TextSecondary
                                    )
                                } else {
                                    bannedAccounts.forEach { acc ->
                                        Surface(
                                            color = ElevatedGunmetal,
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, ThreatCrimson.copy(alpha = 0.45f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                    Text(
                                                        text = "${acc.displayName} • ID ${acc.shortId}",
                                                        fontSize = 12.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = TextPrimary
                                                    )
                                                    Text(
                                                        text = acc.banReason.ifBlank { "Забанен администратором" },
                                                        fontSize = 10.5.sp,
                                                        color = ThreatCrimson,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    val deviceHint = when {
                                                        acc.deviceBanned -> "Устройство: ЗАБЛОКИРОВАНО (${acc.lastDeviceHash.take(8)}…)"
                                                        acc.lastDeviceHash.isNotBlank() -> "Устройство: ${acc.lastDeviceHash.take(8)}…"
                                                        else -> "Устройство ещё не зафиксировано"
                                                    }
                                                    Text(
                                                        text = deviceHint,
                                                        fontSize = 9.5.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = if (acc.deviceBanned) TacticalAmber else TextSecondary
                                                    )
                                                }
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Button(
                                                        onClick = {
                                                            onSetDeviceBanByAccount(acc.shortId, !acc.deviceBanned)
                                                        },
                                                        enabled = !isBusy && (acc.deviceBanned || acc.lastDeviceHash.isNotBlank()),
                                                        colors = ButtonDefaults.buttonColors(
                                                            containerColor = if (acc.deviceBanned) TacticalAmber else ThreatCrimson,
                                                            contentColor = if (acc.deviceBanned) ObsidianVoid else Color.White
                                                        ),
                                                        shape = RoundedCornerShape(6.dp),
                                                        modifier = Modifier
                                                            .height(48.dp)
                                                            .testTag("admin_device_ban_row_${acc.shortId}")
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Smartphone,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(15.dp)
                                                        )
                                                        Spacer(Modifier.width(4.dp))
                                                        Text(
                                                            text = if (acc.deviceBanned) "РАЗБАН УСТР." else "БАН УСТР.",
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Black
                                                        )
                                                    }

                                                    Button(
                                                        onClick = { onUnbanAccount(acc.shortId) },
                                                        enabled = !isBusy,
                                                        colors = ButtonDefaults.buttonColors(containerColor = RtxEmerald),
                                                        shape = RoundedCornerShape(6.dp),
                                                        modifier = Modifier
                                                            .height(48.dp)
                                                            .testTag("admin_unban_row_${acc.shortId}")
                                                    ) {
                                                        Text(
                                                            text = "РАЗБАНИТЬ",
                                                            color = Color(0xFF04140E),
                                                            fontSize = 10.5.sp,
                                                            fontWeight = FontWeight.Black
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Banned Devices Roster
                        Surface(
                            color = ObsidianVoid.copy(alpha = 0.72f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, TacticalAmber.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "ЗАБЛОКИРОВАННЫЕ УСТРОЙСТВА (${bannedDevices.size})",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Black,
                                    color = TacticalAmber
                                )
                                if (bannedDevices.isEmpty()) {
                                    Text(
                                        text = "Заблокированных устройств пока нет. Забаньте устройство рядом с забаненным аккаунтом выше.",
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                } else {
                                    bannedDevices.forEach { dev ->
                                        Surface(
                                            color = ElevatedGunmetal,
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, TacticalAmber.copy(alpha = 0.45f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                    Text(
                                                        text = "Устройство #${dev.deviceHash.take(12)}… • ID ${dev.bannedShortId.ifBlank { "—" }}",
                                                        fontSize = 11.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = TextPrimary
                                                    )
                                                    Text(
                                                        text = dev.reason.ifBlank { "Бан устройства администратором" },
                                                        fontSize = 10.sp,
                                                        color = ThreatCrimson,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                OutlinedButton(
                                                    onClick = { onUnbanDeviceHash(dev.deviceHash) },
                                                    enabled = !isBusy,
                                                    border = BorderStroke(1.dp, RtxEmerald),
                                                    shape = RoundedCornerShape(6.dp),
                                                    modifier = Modifier
                                                        .height(48.dp)
                                                        .testTag("admin_unban_device_${dev.deviceHash.take(8)}")
                                                ) {
                                                    Text(
                                                        text = "РАЗБАН УСТР.",
                                                        color = RtxEmerald,
                                                        fontSize = 10.5.sp,
                                                        fontWeight = FontWeight.Black
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminTabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String
) {
    val shape = CutCornerShape(topStart = 7.dp, bottomEnd = 7.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) ThreatCrimson.copy(alpha = 0.25f) else ElevatedGunmetal)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) ThreatCrimson else BorderSteel,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            color = if (selected) Color.White else TextSecondary
        )
    }
}

@Composable
private fun AdminToggleCard(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (checked) ElevatedGunmetal else ObsidianVoid.copy(alpha = 0.72f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            width = if (checked) 1.5.dp else 1.dp,
            color = if (checked) RtxEmerald else BorderSteel
        ),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            icon()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    color = TextPrimary
                )
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF04140E),
                    checkedTrackColor = RtxEmerald
                ),
                modifier = Modifier.testTag(tag)
            )
        }
    }
}

/**
 * Full-screen lockout displayed when the authenticated player account is banned.
 */
@Composable
fun BannedAccountScreen(
    shortId: String,
    banReason: String,
    onCheckStatus: () -> Unit,
    onSignOut: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
            .safeDrawingPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = TacticalSurface,
            shape = CutCornerShape(topStart = 18.dp, bottomEnd = 18.dp),
            border = BorderStroke(2.dp, ThreatCrimson),
            modifier = Modifier.widthIn(max = 460.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Block,
                    contentDescription = "Аккаунт заблокирован",
                    tint = ThreatCrimson,
                    modifier = Modifier.size(52.dp)
                )
                Text(
                    text = "АККАУНТ ЗАБЛОКИРОВАН",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    color = ThreatCrimson,
                    textAlign = TextAlign.Center
                )
                if (shortId.isNotBlank()) {
                    Text(
                        text = "Ваш ID: $shortId",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TacticalAmber
                    )
                }
                Text(
                    text = banReason.ifBlank { "Доступ к игре ограничен администратором сервера." },
                    fontSize = 13.sp,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onCheckStatus,
                        border = BorderStroke(1.dp, RtxEmerald),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("banned_check_status")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = RtxEmerald, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("ПРОВЕРИТЬ", color = RtxEmerald, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Button(
                        onClick = onSignOut,
                        colors = ButtonDefaults.buttonColors(containerColor = ThreatCrimson),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("banned_sign_out")
                    ) {
                        Icon(Icons.Default.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("ВЫЙТИ", fontWeight = FontWeight.Black, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/**
 * Full-screen mandatory update blocker when the client build is older than server `min_version_code`.
 */
@Composable
fun UpdateRequiredScreen(
    accessStatus: ClientAccessStatus,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
            .safeDrawingPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = TacticalSurface,
            shape = CutCornerShape(topStart = 18.dp, bottomEnd = 18.dp),
            border = BorderStroke(2.dp, TacticalAmber),
            modifier = Modifier.widthIn(max = 480.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SystemUpdate,
                    contentDescription = "Требуется обновление",
                    tint = TacticalAmber,
                    modifier = Modifier.size(54.dp)
                )
                Text(
                    text = "ТРЕБУЕТСЯ ОБНОВЛЕНИЕ",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    color = TacticalAmber,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Ваша версия: v${com.example.BuildConfig.VERSION_NAME} (сборка ${com.example.BuildConfig.VERSION_CODE})\nАктуальная версия: v${accessStatus.latestVersionName} (сборка ${accessStatus.minVersionCode})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = IceCyan,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = accessStatus.updateMessage.ifBlank {
                        "Для входа в игру и онлайн-матчи необходимо установить самую последнюю версию Vanguard RTX."
                    },
                    fontSize = 13.sp,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = TacticalAmber, contentColor = ObsidianVoid),
                    shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("update_required_retry")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("ПРОВЕРИТЬ ВЕРСИЮ", fontWeight = FontWeight.Black, fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * Full-screen hardware/device ban lockout preventing login and new account creation.
 */
@Composable
fun DeviceBannedScreen(
    banReason: String,
    onRetry: () -> Unit,
    onSignOut: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
            .safeDrawingPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = TacticalSurface,
            shape = CutCornerShape(topStart = 18.dp, bottomEnd = 18.dp),
            border = BorderStroke(2.dp, ThreatCrimson),
            modifier = Modifier.widthIn(max = 480.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Smartphone,
                    contentDescription = "Устройство заблокировано",
                    tint = ThreatCrimson,
                    modifier = Modifier.size(54.dp)
                )
                Text(
                    text = "УСТРОЙСТВО ЗАБЛОКИРОВАНО",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    color = ThreatCrimson,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = banReason.ifBlank { "Доступ с этого устройства заблокирован администратором сервера." },
                    fontSize = 13.sp,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Вход в существующие аккаунты и создание новых аккаунтов на этом устройстве невозможны.",
                    fontSize = 11.5.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onRetry,
                        border = BorderStroke(1.dp, RtxEmerald),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("device_banned_retry")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = RtxEmerald, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("ПРОВЕРИТЬ", color = RtxEmerald, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    if (onSignOut != null) {
                        Button(
                            onClick = onSignOut,
                            colors = ButtonDefaults.buttonColors(containerColor = ThreatCrimson),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("device_banned_sign_out")
                        ) {
                            Icon(Icons.Default.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("ВЫЙТИ", fontWeight = FontWeight.Black, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

