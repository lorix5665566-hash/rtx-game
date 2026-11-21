package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.online.FriendEntry
import com.example.online.FriendRequest
import com.example.online.PlayerProfile
import com.example.ui.theme.BorderSteel
import com.example.ui.theme.ElevatedGunmetal
import com.example.ui.theme.IceCyan
import com.example.ui.theme.LocalUiMetrics
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.RtxEmerald
import com.example.ui.theme.TacticalAmber
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.ThreatCrimson

private enum class FriendsTab(val labelRu: String) {
    FRIENDS("ДРУЗЬЯ"),
    REQUESTS("ЗАЯВКИ"),
    ADD_BY_ID("ДОБАВИТЬ ПО ID")
}

@Composable
fun FriendsOverlay(
    myProfile: PlayerProfile?,
    friendsState: OnlineUiState<List<FriendEntry>>,
    incomingRequests: List<FriendRequest>,
    outgoingRequests: List<FriendRequest>,
    searchResult: PlayerProfile?,
    isSearching: Boolean,
    statusBanner: String?,
    onSearchById: (String) -> Unit,
    onSendFriendRequest: (PlayerProfile) -> Unit,
    onRespondRequest: (FriendRequest, Boolean) -> Unit,
    onInviteFriend: (FriendEntry) -> Unit,
    onRemoveFriend: (FriendEntry) -> Unit,
    onClearBanner: () -> Unit,
    onClose: () -> Unit
) {
    BackHandler { onClose() }

    val context = LocalContext.current
    val m = LocalUiMetrics.current
    var activeTab by remember { mutableStateOf(FriendsTab.FRIENDS) }
    var searchInput by remember { mutableStateOf("") }
    var copiedNotice by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xC8050911))
            .safeDrawingPadding()
            .padding(horizontal = m.s(18f), vertical = m.s(10f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Color(0xF20D1422),
            shape = CutCornerShape(topStart = m.s(16f), bottomEnd = m.s(16f)),
            border = BorderStroke(1.2.dp, IceCyan.copy(alpha = 0.50f)),
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 820.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(m.s(14f)),
                verticalArrangement = Arrangement.spacedBy(m.s(10f))
            ) {
                // Header: Title + Personal 6-Digit Human ID Badge + Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(m.s(10f))
                    ) {
                        Text(
                            text = "ДРУЗЬЯ И ПРОФИЛЬ",
                            fontSize = m.sp(15f),
                            fontWeight = FontWeight.Black,
                            color = TextPrimary,
                            letterSpacing = m.sp(1.1f)
                        )
                        if (myProfile != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(m.s(6f)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(m.s(7f)))
                                    .background(TacticalAmber.copy(alpha = 0.16f))
                                    .border(1.dp, TacticalAmber.copy(alpha = 0.65f), RoundedCornerShape(m.s(7f)))
                                    .clickable {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        cm?.setPrimaryClip(ClipData.newPlainText("Player ID", myProfile.shortId))
                                        copiedNotice = true
                                    }
                                    .padding(horizontal = m.s(10f), vertical = m.s(5f))
                                    .testTag("copy_my_player_id_button")
                            ) {
                                Text(
                                    text = "${myProfile.displayName}  •  ID: ${myProfile.shortId}",
                                    fontSize = m.sp(10.5f),
                                    fontWeight = FontWeight.ExtraBold,
                                    color = TacticalAmber
                                )
                                Icon(
                                    imageVector = if (copiedNotice) Icons.Default.Check else Icons.Default.ContentCopy,
                                    contentDescription = "Скопировать ID",
                                    tint = if (copiedNotice) RtxEmerald else TacticalAmber,
                                    modifier = Modifier.size(m.s(14f))
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(m.s(36f).coerceAtLeast(48.dp))
                            .testTag("friends_close_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = TextPrimary)
                    }
                }

                // Tab Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(m.s(8f))
                ) {
                    FriendsTab.entries.forEach { tab ->
                        val badge = if (tab == FriendsTab.REQUESTS && incomingRequests.isNotEmpty()) {
                            " (${incomingRequests.size})"
                        } else ""
                        val selected = activeTab == tab
                        val accent = when (tab) {
                            FriendsTab.FRIENDS -> RtxEmerald
                            FriendsTab.REQUESTS -> TacticalAmber
                            FriendsTab.ADD_BY_ID -> IceCyan
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(m.s(7f)))
                                .background(if (selected) accent.copy(alpha = 0.22f) else ElevatedGunmetal.copy(alpha = 0.72f))
                                .border(1.dp, if (selected) accent else BorderSteel, RoundedCornerShape(m.s(7f)))
                                .clickable {
                                    activeTab = tab
                                    onClearBanner()
                                }
                                .padding(vertical = m.s(8f))
                                .testTag("friends_tab_${tab.name.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${tab.labelRu}$badge",
                                fontSize = m.sp(10.5f),
                                fontWeight = FontWeight.ExtraBold,
                                color = if (selected) accent else TextPrimary
                            )
                        }
                    }
                }

                if (!statusBanner.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(m.s(7f)))
                            .background(RtxEmerald.copy(alpha = 0.14f))
                            .border(1.dp, RtxEmerald.copy(alpha = 0.55f), RoundedCornerShape(m.s(7f)))
                            .padding(horizontal = m.s(10f), vertical = m.s(6f)),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = statusBanner,
                            fontSize = m.sp(10f),
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "OK",
                            fontSize = m.sp(10f),
                            fontWeight = FontWeight.Black,
                            color = RtxEmerald,
                            modifier = Modifier
                                .clickable { onClearBanner() }
                                .padding(horizontal = m.s(6f))
                        )
                    }
                }

                // Content Body
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (activeTab) {
                        FriendsTab.FRIENDS -> {
                            when (friendsState) {
                                OnlineUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = RtxEmerald)
                                }
                                is OnlineUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(friendsState.message, color = ThreatCrimson, fontSize = m.sp(11f))
                                }
                                is OnlineUiState.Success -> {
                                    val friends = friendsState.data
                                    if (friends.isEmpty()) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(m.s(8f))
                                            ) {
                                                Text(
                                                    text = "Список друзей пока пуст",
                                                    fontSize = m.sp(13f),
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimary
                                                )
                                                Text(
                                                    text = "Поделитесь вашим ID (${myProfile?.shortId ?: "—"}) или добавьте друга во вкладке «ДОБАВИТЬ ПО ID».",
                                                    fontSize = m.sp(10.5f),
                                                    color = TextSecondary
                                                )
                                                Button(
                                                    onClick = { activeTab = FriendsTab.ADD_BY_ID },
                                                    colors = ButtonDefaults.buttonColors(containerColor = IceCyan, contentColor = ObsidianVoid),
                                                    modifier = Modifier.testTag("empty_go_add_friend_button")
                                                ) {
                                                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(m.s(16f)))
                                                    Spacer(Modifier.width(m.s(6f)))
                                                    Text("НАЙТИ ДРУГА ПО ID", fontWeight = FontWeight.Black, fontSize = m.sp(11f))
                                                }
                                            }
                                        }
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalArrangement = Arrangement.spacedBy(m.s(7f))
                                        ) {
                                            items(friends, key = { it.friendUid }) { friend ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(m.s(8f)))
                                                        .background(ElevatedGunmetal.copy(alpha = 0.78f))
                                                        .border(1.dp, BorderSteel, RoundedCornerShape(m.s(8f)))
                                                        .padding(horizontal = m.s(12f), vertical = m.s(8f)),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(m.s(10f)),
                                                        modifier = Modifier.weight(1f)
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(m.s(10f))
                                                                .clip(CircleShape)
                                                                .background(RtxEmerald)
                                                        )
                                                        Column {
                                                            Text(
                                                                text = friend.friendName,
                                                                fontSize = m.sp(12f),
                                                                fontWeight = FontWeight.Bold,
                                                                color = TextPrimary,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                            Text(
                                                                text = "ID: ${friend.friendShortId}",
                                                                fontSize = m.sp(10f),
                                                                color = TacticalAmber,
                                                                fontWeight = FontWeight.SemiBold
                                                            )
                                                        }
                                                    }

                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(m.s(6f))
                                                    ) {
                                                        Button(
                                                            onClick = { onInviteFriend(friend) },
                                                            shape = CutCornerShape(topStart = m.s(7f), bottomEnd = m.s(7f)),
                                                            colors = ButtonDefaults.buttonColors(
                                                                containerColor = RtxEmerald,
                                                                contentColor = ObsidianVoid
                                                            ),
                                                            contentPadding = PaddingValues(horizontal = m.s(12f), vertical = m.s(6f)),
                                                            modifier = Modifier
                                                                .height(m.s(36f).coerceAtLeast(48.dp))
                                                                .testTag("invite_friend_${friend.friendShortId}")
                                                        ) {
                                                            Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(m.s(14f)))
                                                            Spacer(Modifier.width(m.s(5f)))
                                                            Text("В ЛОББИ", fontSize = m.sp(10f), fontWeight = FontWeight.Black)
                                                        }

                                                        IconButton(
                                                            onClick = { onRemoveFriend(friend) },
                                                            modifier = Modifier.size(m.s(36f).coerceAtLeast(48.dp))
                                                        ) {
                                                            Icon(
                                                                Icons.Default.Delete,
                                                                contentDescription = "Удалить друга",
                                                                tint = ThreatCrimson.copy(alpha = 0.85f),
                                                                modifier = Modifier.size(m.s(18f))
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

                        FriendsTab.REQUESTS -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(m.s(7f))
                            ) {
                                if (incomingRequests.isEmpty() && outgoingRequests.isEmpty()) {
                                    item {
                                        Box(
                                            modifier = Modifier.fillMaxWidth().padding(top = m.s(28f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("Нет активных заявок в друзья", color = TextSecondary, fontSize = m.sp(11.5f))
                                        }
                                    }
                                }
                                if (incomingRequests.isNotEmpty()) {
                                    item {
                                        Text(
                                            text = "ВХОДЯЩИЕ ЗАЯВКИ",
                                            fontSize = m.sp(10f),
                                            fontWeight = FontWeight.Bold,
                                            color = TacticalAmber
                                        )
                                    }
                                    items(incomingRequests, key = { it.id }) { req ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(m.s(8f)))
                                                .background(ElevatedGunmetal.copy(alpha = 0.80f))
                                                .border(1.dp, TacticalAmber.copy(alpha = 0.45f), RoundedCornerShape(m.s(8f)))
                                                .padding(horizontal = m.s(12f), vertical = m.s(8f)),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(req.fromName, fontSize = m.sp(12f), fontWeight = FontWeight.Bold, color = TextPrimary)
                                                Text("ID: ${req.fromShortId}", fontSize = m.sp(10f), color = TacticalAmber)
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(m.s(6f))) {
                                                Button(
                                                    onClick = { onRespondRequest(req, true) },
                                                    colors = ButtonDefaults.buttonColors(containerColor = RtxEmerald, contentColor = ObsidianVoid),
                                                    modifier = Modifier.testTag("accept_friend_req_${req.fromShortId}")
                                                ) {
                                                    Text("ПРИНЯТЬ", fontSize = m.sp(10f), fontWeight = FontWeight.Black)
                                                }
                                                Button(
                                                    onClick = { onRespondRequest(req, false) },
                                                    colors = ButtonDefaults.buttonColors(containerColor = ThreatCrimson.copy(alpha = 0.25f), contentColor = Color(0xFFFFB4B4)),
                                                    modifier = Modifier.testTag("decline_friend_req_${req.fromShortId}")
                                                ) {
                                                    Text("ОТКЛОНИТЬ", fontSize = m.sp(10f), fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                                if (outgoingRequests.isNotEmpty()) {
                                    item {
                                        Spacer(Modifier.height(m.s(4f)))
                                        Text(
                                            text = "ОТПРАВЛЕННЫЕ ЗАЯВКИ",
                                            fontSize = m.sp(10f),
                                            fontWeight = FontWeight.Bold,
                                            color = TextSecondary
                                        )
                                    }
                                    items(outgoingRequests, key = { it.id }) { req ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(m.s(8f)))
                                                .background(ElevatedGunmetal.copy(alpha = 0.55f))
                                                .border(1.dp, BorderSteel, RoundedCornerShape(m.s(8f)))
                                                .padding(horizontal = m.s(12f), vertical = m.s(8f)),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column {
                                                Text(req.toName, fontSize = m.sp(11.5f), fontWeight = FontWeight.Bold, color = TextPrimary)
                                                Text("ID: ${req.toShortId}", fontSize = m.sp(10f), color = TextSecondary)
                                            }
                                            Text("ОЖИДАНИЕ...", fontSize = m.sp(9.5f), fontWeight = FontWeight.Bold, color = TacticalAmber)
                                        }
                                    }
                                }
                            }
                        }

                        FriendsTab.ADD_BY_ID -> {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(m.s(10f))
                            ) {
                                Text(
                                    text = "Введите 6-значный цифровой ID друга (только для реальных игроков):",
                                    fontSize = m.sp(11f),
                                    color = TextSecondary
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(m.s(8f)),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = searchInput,
                                        onValueChange = { raw ->
                                            searchInput = raw.filter { it.isDigit() }.take(6)
                                        },
                                        placeholder = { Text("Например: 482915", color = TextSecondary) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = IceCyan,
                                            unfocusedBorderColor = BorderSteel,
                                            focusedTextColor = TextPrimary,
                                            unfocusedTextColor = TextPrimary,
                                            cursorColor = IceCyan
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("friend_id_input")
                                    )

                                    Button(
                                        onClick = { onSearchById(searchInput) },
                                        enabled = searchInput.length == 6 && !isSearching,
                                        shape = CutCornerShape(topStart = m.s(8f), bottomEnd = m.s(8f)),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = IceCyan,
                                            contentColor = ObsidianVoid
                                        ),
                                        modifier = Modifier
                                            .height(m.s(52f).coerceAtLeast(48.dp))
                                            .testTag("search_friend_id_button")
                                    ) {
                                        if (isSearching) {
                                            CircularProgressIndicator(Modifier.size(m.s(18f)), color = ObsidianVoid, strokeWidth = 2.dp)
                                        } else {
                                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(m.s(18f)))
                                            Spacer(Modifier.width(m.s(5f)))
                                            Text("НАЙТИ", fontWeight = FontWeight.Black, fontSize = m.sp(11.5f))
                                        }
                                    }
                                }

                                if (searchResult != null) {
                                    val isMe = searchResult.userId == myProfile?.userId
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(m.s(10f)))
                                            .background(ElevatedGunmetal.copy(alpha = 0.88f))
                                            .border(1.2.dp, RtxEmerald.copy(alpha = 0.65f), RoundedCornerShape(m.s(10f)))
                                            .padding(horizontal = m.s(14f), vertical = m.s(10f)),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text(
                                                text = searchResult.displayName,
                                                fontSize = m.sp(13f),
                                                fontWeight = FontWeight.Black,
                                                color = TextPrimary
                                            )
                                            Text(
                                                text = "ID: ${searchResult.shortId}  •  Убийств: ${searchResult.totalKills}",
                                                fontSize = m.sp(10f),
                                                color = TacticalAmber
                                            )
                                        }

                                        if (isMe) {
                                            Text("ЭТО ВЫ", fontSize = m.sp(10.5f), fontWeight = FontWeight.Bold, color = TextSecondary)
                                        } else {
                                            Button(
                                                onClick = { onSendFriendRequest(searchResult) },
                                                colors = ButtonDefaults.buttonColors(containerColor = RtxEmerald, contentColor = ObsidianVoid),
                                                modifier = Modifier
                                                    .height(m.s(40f).coerceAtLeast(48.dp))
                                                    .testTag("send_friend_request_button")
                                            ) {
                                                Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(m.s(16f)))
                                                Spacer(Modifier.width(m.s(6f)))
                                                Text("ДОБАВИТЬ", fontWeight = FontWeight.Black, fontSize = m.sp(11f))
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
