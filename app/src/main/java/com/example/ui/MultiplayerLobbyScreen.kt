package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.ArenaMapId
import com.example.model.GameMode
import com.example.model.TeamSide
import com.example.online.FriendEntry
import com.example.online.MultiplayerLobby
import com.example.online.PlayerMatchState
import com.example.ui.theme.BorderSteel
import com.example.ui.theme.ElevatedGunmetal
import com.example.ui.theme.IceCyan
import com.example.ui.theme.LocalUiMetrics
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.RtxEmerald
import com.example.ui.theme.TacticalAmber
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun MultiplayerLobbyScreen(
    currentUserId: String,
    lobby: MultiplayerLobby,
    memberStates: List<PlayerMatchState>,
    friends: List<FriendEntry>,
    statusBanner: String?,
    onUpdateConfig: (ArenaMapId, GameMode, Int, Boolean) -> Unit,
    onInviteFriend: (FriendEntry) -> Unit,
    onStartMatch: () -> Unit,
    onLeaveLobby: () -> Unit
) {
    BackHandler { onLeaveLobby() }

    val m = LocalUiMetrics.current
    val isHost = lobby.hostUid == currentUserId
    val map = ArenaMapId.entries.firstOrNull { it.name == lobby.mapId } ?: ArenaMapId.MIRAGE
    val mode = GameMode.entries.firstOrNull { it.name == lobby.mode } ?: GameMode.BOMB_DEFUSAL

    var showInvitePanel by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF070B14), Color(0xFF0E1727), Color(0xFF080D17))
                )
            )
            .safeDrawingPadding()
            .padding(horizontal = m.s(14f), vertical = m.s(8f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(m.s(8f))
        ) {
            // Top Lobby Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(m.s(10f)))
                    .background(Color(0xCC0D1421))
                    .border(1.dp, BorderSteel, RoundedCornerShape(m.s(10f)))
                    .padding(horizontal = m.s(10f), vertical = m.s(6f)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(m.s(8f))
                ) {
                    IconButton(
                        onClick = onLeaveLobby,
                        modifier = Modifier
                            .size(m.s(36f).coerceAtLeast(48.dp))
                            .testTag("lobby_leave_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Покинуть лобби",
                            tint = TextPrimary
                        )
                    }
                    Column {
                        Text(
                            text = "ОНЛАЙН-ЛОББИ • ${map.titleRu} • ${mode.titleRu}",
                            fontSize = m.sp(13f),
                            fontWeight = FontWeight.Black,
                            color = TextPrimary
                        )
                        Text(
                            text = "Хост: ${lobby.hostName}  •  Игроков: ${lobby.memberUids.size}/10  •  ${if (lobby.allowBots) "С ботами" else "1 на 1 / Только друзья"}",
                            fontSize = m.sp(9.5f),
                            color = TextSecondary
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(m.s(8f))
                ) {
                    Button(
                        onClick = { showInvitePanel = !showInvitePanel },
                        shape = RoundedCornerShape(m.s(7f)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (showInvitePanel) IceCyan else ElevatedGunmetal,
                            contentColor = if (showInvitePanel) ObsidianVoid else IceCyan
                        ),
                        border = BorderStroke(1.dp, IceCyan.copy(alpha = 0.65f)),
                        contentPadding = PaddingValues(horizontal = m.s(12f), vertical = m.s(6f)),
                        modifier = Modifier
                            .height(m.s(38f).coerceAtLeast(48.dp))
                            .testTag("lobby_toggle_invite_friends")
                    ) {
                        Icon(Icons.Default.GroupAdd, contentDescription = null, modifier = Modifier.size(m.s(16f)))
                        Spacer(Modifier.width(m.s(5f)))
                        Text("ДОБАВИТЬ ДРУГА В ЛОББИ", fontSize = m.sp(9.8f), fontWeight = FontWeight.Black)
                    }

                    if (isHost) {
                        Button(
                            onClick = onStartMatch,
                            shape = CutCornerShape(topStart = m.s(8f), bottomEnd = m.s(8f)),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = RtxEmerald,
                                contentColor = ObsidianVoid
                            ),
                            contentPadding = PaddingValues(horizontal = m.s(16f), vertical = m.s(6f)),
                            modifier = Modifier
                                .height(m.s(38f).coerceAtLeast(48.dp))
                                .testTag("lobby_start_match_button")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(m.s(18f)))
                            Spacer(Modifier.width(m.s(4f)))
                            Text("НАЧАТЬ МАТЧ", fontSize = m.sp(11f), fontWeight = FontWeight.Black)
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(m.s(7f)))
                                .background(TacticalAmber.copy(alpha = 0.16f))
                                .border(1.dp, TacticalAmber.copy(alpha = 0.55f), RoundedCornerShape(m.s(7f)))
                                .padding(horizontal = m.s(12f), vertical = m.s(8f))
                        ) {
                            Text(
                                text = "ОЖИДАНИЕ СТАРТА ХОСТА...",
                                fontSize = m.sp(10f),
                                fontWeight = FontWeight.Bold,
                                color = TacticalAmber
                            )
                        }
                    }
                }
            }

            if (!statusBanner.isNullOrBlank()) {
                Text(
                    text = statusBanner,
                    fontSize = m.sp(10f),
                    fontWeight = FontWeight.SemiBold,
                    color = RtxEmerald,
                    modifier = Modifier.padding(horizontal = m.s(6f))
                )
            }

            // Main Row: Unified Lobby Roster | Friends Quick-Invite Panel OR Host Settings
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(m.s(10f))
            ) {
                UnifiedLobbyRosterPanel(
                    players = memberStates,
                    hostUid = lobby.hostUid,
                    currentUserId = currentUserId,
                    allowBots = lobby.allowBots,
                    modifier = Modifier.weight(1.35f).fillMaxHeight()
                )

                // Right Column: Friends Quick-Invite OR Host Match Settings
                Surface(
                    color = Color(0xD90E1624),
                    shape = RoundedCornerShape(m.s(10f)),
                    border = BorderStroke(1.dp, if (showInvitePanel) IceCyan.copy(alpha = 0.55f) else BorderSteel),
                    modifier = Modifier
                        .weight(0.95f)
                        .fillMaxHeight()
                ) {
                    if (showInvitePanel) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(m.s(10f)),
                            verticalArrangement = Arrangement.spacedBy(m.s(7f))
                        ) {
                            Text(
                                text = "ДОБАВИТЬ ДРУГА В ЛОББИ",
                                fontSize = m.sp(11f),
                                fontWeight = FontWeight.Black,
                                color = IceCyan
                            )
                            if (friends.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "Список друзей пуст. Добавьте друга по 6-значному ID через кнопку «ДРУЗЬЯ» в главном меню.",
                                        fontSize = m.sp(10f),
                                        color = TextSecondary
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(m.s(6f))
                                ) {
                                    items(friends, key = { it.friendUid }) { f ->
                                        val alreadyIn = lobby.memberUids.contains(f.friendUid)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(m.s(7f)))
                                                .background(ElevatedGunmetal.copy(alpha = 0.72f))
                                                .padding(horizontal = m.s(8f), vertical = m.s(6f)),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(m.s(7f)),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                PlayerAvatar(
                                                    avatarData = f.friendAvatarData,
                                                    size = m.s(30f),
                                                    borderColor = if (f.online) RtxEmerald else BorderSteel
                                                )
                                                Column {
                                                    Text(
                                                        text = f.friendName,
                                                        fontSize = m.sp(10.8f),
                                                        fontWeight = FontWeight.Bold,
                                                        color = TextPrimary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = "ID: ${f.friendShortId}",
                                                        fontSize = m.sp(9.2f),
                                                        color = TacticalAmber
                                                    )
                                                }
                                            }
                                            if (alreadyIn) {
                                                Text("В ЛОББИ", fontSize = m.sp(9.5f), fontWeight = FontWeight.Bold, color = RtxEmerald)
                                            } else {
                                                Button(
                                                    onClick = { onInviteFriend(f) },
                                                    colors = ButtonDefaults.buttonColors(containerColor = RtxEmerald, contentColor = ObsidianVoid),
                                                    contentPadding = PaddingValues(horizontal = m.s(10f), vertical = m.s(4f)),
                                                    modifier = Modifier
                                                        .height(m.s(32f).coerceAtLeast(48.dp))
                                                        .testTag("lobby_invite_${f.friendShortId}")
                                                ) {
                                                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(m.s(13f)))
                                                    Spacer(Modifier.width(m.s(4f)))
                                                    Text("ЗВАТЬ", fontSize = m.sp(9.5f), fontWeight = FontWeight.Black)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(m.s(10f)),
                            verticalArrangement = Arrangement.spacedBy(m.s(8f))
                        ) {
                            Text(
                                text = if (isHost) "НАСТРОЙКИ ХОСТА" else "ПАРАМЕТРЫ МАТЧА",
                                fontSize = m.sp(11f),
                                fontWeight = FontWeight.Black,
                                color = RtxEmerald
                            )

                            // Map
                            Text("КАРТА", fontSize = m.sp(9f), fontWeight = FontWeight.Bold, color = TextSecondary)
                            Row(horizontalArrangement = Arrangement.spacedBy(m.s(6f))) {
                                ArenaMapId.entries.forEach { candidate ->
                                    LobbyChoiceChip(
                                        label = candidate.titleRu,
                                        selected = map == candidate,
                                        enabled = isHost,
                                        accent = TacticalAmber,
                                        onClick = { onUpdateConfig(candidate, mode, lobby.roundsToWin, lobby.allowBots) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            // Mode
                            Text("РЕЖИМ", fontSize = m.sp(9f), fontWeight = FontWeight.Bold, color = TextSecondary)
                            Row(horizontalArrangement = Arrangement.spacedBy(m.s(6f))) {
                                GameMode.entries.forEach { candidate ->
                                    LobbyChoiceChip(
                                        label = if (candidate == GameMode.BOMB_DEFUSAL) "C4 БОМБА" else "БОЙ НАСМЕРТЬ",
                                        selected = mode == candidate,
                                        enabled = isHost,
                                        accent = RtxEmerald,
                                        onClick = { onUpdateConfig(map, candidate, lobby.roundsToWin, lobby.allowBots) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            // Host Toggle: 1v1 / Friends-only vs Fill with Bots
                            Text("СОСТАВ КОМАНД (БОТЫ)", fontSize = m.sp(9f), fontWeight = FontWeight.Bold, color = TextSecondary)
                            Row(horizontalArrangement = Arrangement.spacedBy(m.s(6f))) {
                                LobbyChoiceChip(
                                    label = "1v1 / ДРУЗЬЯ",
                                    selected = !lobby.allowBots,
                                    enabled = isHost,
                                    accent = IceCyan,
                                    onClick = { onUpdateConfig(map, mode, lobby.roundsToWin, false) },
                                    modifier = Modifier.weight(1f),
                                    tag = "lobby_bots_off"
                                )
                                LobbyChoiceChip(
                                    label = "С БОТАМИ",
                                    selected = lobby.allowBots,
                                    enabled = isHost,
                                    accent = TacticalAmber,
                                    onClick = { onUpdateConfig(map, mode, lobby.roundsToWin, true) },
                                    modifier = Modifier.weight(1f),
                                    tag = "lobby_bots_on"
                                )
                            }

                            // Rounds
                            if (mode == GameMode.BOMB_DEFUSAL) {
                                Text("РАУНДЫ ДО ПОБЕДЫ", fontSize = m.sp(9f), fontWeight = FontWeight.Bold, color = TextSecondary)
                                Row(horizontalArrangement = Arrangement.spacedBy(m.s(6f))) {
                                    listOf(5, 8, 13).forEach { r ->
                                        LobbyChoiceChip(
                                            label = "ДО $r",
                                            selected = lobby.roundsToWin == r,
                                            enabled = isHost,
                                            accent = RtxEmerald,
                                            onClick = { onUpdateConfig(map, mode, r, lobby.allowBots) },
                                            modifier = Modifier.weight(1f)
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

@Composable
private fun UnifiedLobbyRosterPanel(
    players: List<PlayerMatchState>,
    hostUid: String,
    currentUserId: String,
    allowBots: Boolean,
    modifier: Modifier = Modifier
) {
    val m = LocalUiMetrics.current
    Surface(
        color = Color(0xD90E1522),
        shape = RoundedCornerShape(m.s(10f)),
        border = BorderStroke(1.2.dp, RtxEmerald.copy(alpha = 0.55f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(m.s(10f)),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(m.s(6f)), modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "УЧАСТНИКИ ЛОББИ",
                        fontSize = m.sp(11.5f),
                        fontWeight = FontWeight.Black,
                        color = RtxEmerald
                    )
                    Text(
                        text = "${players.size}/10",
                        fontSize = m.sp(10.5f),
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary
                    )
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(m.s(5f))
                ) {
                    items(players, key = { it.userId }) { p ->
                        val isMe = p.userId == currentUserId
                        val isHostMember = p.userId == hostUid
                        val accent = if (isMe) RtxEmerald else IceCyan
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(m.s(7f)))
                                .background(if (isMe) RtxEmerald.copy(alpha = 0.16f) else ElevatedGunmetal.copy(alpha = 0.72f))
                                .border(1.dp, if (isMe) RtxEmerald else BorderSteel, RoundedCornerShape(m.s(7f)))
                                .padding(horizontal = m.s(9f), vertical = m.s(6f)),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(m.s(8f)),
                                modifier = Modifier.weight(1f)
                            ) {
                                PlayerAvatar(
                                    avatarData = p.avatarData,
                                    size = m.s(30f),
                                    borderColor = accent
                                )
                                Column {
                                    Text(
                                        text = p.displayName + (if (isMe) " (ВЫ)" else "") + (if (isHostMember) " ★" else ""),
                                        fontSize = m.sp(11f),
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "ID: ${p.shortId}",
                                        fontSize = m.sp(9.5f),
                                        color = TacticalAmber,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .size(m.s(8f))
                                    .clip(CircleShape)
                                    .background(RtxEmerald)
                            )
                        }
                    }

                    if (allowBots && players.size < 10) {
                        val botsCount = (10 - players.size).coerceAtLeast(0)
                        items(botsCount) { idx ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(m.s(7f)))
                                    .background(ElevatedGunmetal.copy(alpha = 0.36f))
                                    .border(0.8.dp, BorderSteel.copy(alpha = 0.5f), RoundedCornerShape(m.s(7f)))
                                    .padding(horizontal = m.s(9f), vertical = m.s(5f)),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(m.s(6f))
                            ) {
                                Icon(Icons.Default.SmartToy, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(m.s(14f)))
                                Text(
                                    text = "БОТ #${idx + 1}",
                                    fontSize = m.sp(10f),
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(m.s(7f)))
                    .background(IceCyan.copy(alpha = 0.12f))
                    .border(1.dp, IceCyan.copy(alpha = 0.40f), RoundedCornerShape(m.s(7f)))
                    .padding(horizontal = m.s(10f), vertical = m.s(7f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "ВЫБОР КОМАНДЫ (CT / T) ОТКРОЕТСЯ В НАЧАЛЕ МАТЧА",
                    fontSize = m.sp(9.6f),
                    fontWeight = FontWeight.Bold,
                    color = IceCyan
                )
            }
        }
    }
}

@Composable
private fun LobbyChoiceChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null
) {
    val m = LocalUiMetrics.current
    val shape = RoundedCornerShape(m.s(6f))
    val baseMod = modifier
        .clip(shape)
        .background(if (selected) accent.copy(alpha = 0.24f) else ElevatedGunmetal.copy(alpha = 0.68f))
        .border(if (selected) 1.3.dp else 1.dp, if (selected) accent else BorderSteel, shape)
        .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = m.s(6f), vertical = m.s(7f))
        .then(if (tag != null) Modifier.testTag(tag) else Modifier)

    Box(modifier = baseMod, contentAlignment = Alignment.Center) {
        Text(
            text = label,
            fontSize = m.sp(9.5f),
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
            color = if (selected) accent else TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
