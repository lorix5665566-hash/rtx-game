package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.online.AvatarPresets
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
import kotlinx.coroutines.launch

@Composable
fun ProfileCustomizationOverlay(
    profile: PlayerProfile,
    isSaving: Boolean,
    statusBanner: String?,
    onSaveProfile: (displayName: String, avatarData: String, onSaved: () -> Unit) -> Unit,
    onClose: () -> Unit
) {
    BackHandler { onClose() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val m = LocalUiMetrics.current

    var nameInput by remember(profile.userId) { mutableStateOf(profile.displayName) }
    var selectedAvatar by remember(profile.userId) { mutableStateOf(profile.avatarData) }
    var copiedId by remember { mutableStateOf(false) }
    var isProcessingPhoto by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    // Zero-permission Android Photo Picker (Google Play compliant — no storage permission needed)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        localError = null
        isProcessingPhoto = true
        scope.launch {
            val encoded = encodeGalleryPhotoToAvatarData(context, uri)
            isProcessingPhoto = false
            encoded.onSuccess { dataUri ->
                selectedAvatar = dataUri
            }.onFailure { err ->
                localError = err.localizedMessage ?: "Не удалось обработать фото."
            }
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = RtxEmerald,
        unfocusedBorderColor = BorderSteel,
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        cursorColor = RtxEmerald,
        focusedLeadingIconColor = RtxEmerald,
        unfocusedLeadingIconColor = TextSecondary
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC050911))
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = m.s(18f), vertical = m.s(10f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Color(0xF50D1422),
            shape = CutCornerShape(topStart = m.s(16f), bottomEnd = m.s(16f)),
            border = BorderStroke(1.2.dp, RtxEmerald.copy(alpha = 0.58f)),
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(m.s(14f)),
                verticalArrangement = Arrangement.spacedBy(m.s(10f))
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "КАСТОМИЗАЦИЯ ПРОФИЛЯ",
                            fontSize = m.sp(15f),
                            fontWeight = FontWeight.Black,
                            color = TextPrimary,
                            letterSpacing = m.sp(1.1f)
                        )
                        Text(
                            text = "Ваш ID, имя и аватарка отображаются в меню, у друзей и в лобби 1 на 1",
                            fontSize = m.sp(9.5f),
                            color = TextSecondary
                        )
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(m.s(36f).coerceAtLeast(48.dp))
                            .testTag("profile_close_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = TextPrimary)
                    }
                }

                // Body: Left Live Preview & ID | Right Name + Gallery Picker + 8 Tactical Avatars
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(m.s(14f))
                ) {
                    // Left Preview Card
                    Surface(
                        color = Color(0xCC0A111D),
                        shape = RoundedCornerShape(m.s(12f)),
                        border = BorderStroke(1.dp, BorderSteel),
                        modifier = Modifier
                            .weight(0.85f)
                            .fillMaxHeight()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(m.s(12f)),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(m.s(8f))
                        ) {
                            PlayerAvatar(
                                avatarData = selectedAvatar,
                                size = m.s(76f),
                                borderColor = RtxEmerald,
                                borderWidth = 2.2.dp
                            )

                            Text(
                                text = nameInput.trim().ifEmpty { profile.displayName },
                                fontSize = m.sp(14f),
                                fontWeight = FontWeight.Black,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            // Prominent Copyable 6-Digit Player ID
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(m.s(6f)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(m.s(8f)))
                                    .background(TacticalAmber.copy(alpha = 0.16f))
                                    .border(1.2.dp, TacticalAmber.copy(alpha = 0.70f), RoundedCornerShape(m.s(8f)))
                                    .clickable {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        cm?.setPrimaryClip(ClipData.newPlainText("Player ID", profile.shortId))
                                        copiedId = true
                                    }
                                    .padding(horizontal = m.s(12f), vertical = m.s(6f))
                                    .testTag("profile_copy_id_button")
                            ) {
                                Text(
                                    text = if (copiedId) "СКОПИРОВАНО: ${profile.shortId}" else "МОЙ ID: ${profile.shortId}",
                                    fontSize = m.sp(11f),
                                    fontWeight = FontWeight.Black,
                                    color = if (copiedId) RtxEmerald else TacticalAmber
                                )
                                Icon(
                                    imageVector = if (copiedId) Icons.Default.Check else Icons.Default.ContentCopy,
                                    contentDescription = "Скопировать ID",
                                    tint = if (copiedId) RtxEmerald else TacticalAmber,
                                    modifier = Modifier.size(m.s(14f))
                                )
                            }

                            Button(
                                onClick = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                enabled = !isProcessingPhoto && !isSaving,
                                shape = CutCornerShape(topStart = m.s(8f), bottomEnd = m.s(8f)),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = IceCyan.copy(alpha = 0.22f),
                                    contentColor = IceCyan
                                ),
                                border = BorderStroke(1.dp, IceCyan.copy(alpha = 0.72f)),
                                contentPadding = PaddingValues(horizontal = m.s(10f), vertical = m.s(7f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(m.s(40f).coerceAtLeast(48.dp))
                                    .testTag("profile_pick_photo_button")
                            ) {
                                if (isProcessingPhoto) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(m.s(16f)),
                                        color = IceCyan,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(m.s(16f)))
                                    Spacer(Modifier.width(m.s(6f)))
                                    Text("ФОТО ИЗ ГАЛЕРЕИ", fontSize = m.sp(10f), fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    }

                    // Right Editor Column
                    Column(
                        modifier = Modifier
                            .weight(1.35f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(m.s(8f))
                    ) {
                        Text(
                            text = "ИМЯ ИГРОКА (НИКНЕЙМ)",
                            fontSize = m.sp(9.5f),
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary
                        )
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { raw ->
                                nameInput = raw.take(20)
                                localError = null
                            },
                            placeholder = { Text("От 3 до 20 символов", color = TextSecondary) },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(m.s(17f))) },
                            singleLine = true,
                            colors = fieldColors,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("profile_name_input")
                        )

                        Text(
                            text = "ВСТРОЕННЫЕ ТАКТИЧЕСКИЕ АВАТАРКИ",
                            fontSize = m.sp(9.5f),
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary
                        )

                        // 2 rows x 4 columns of built-in tactical avatars
                        val rows = remember { AvatarPresets.all.chunked(4) }
                        rows.forEach { rowPresets ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(m.s(7f))
                            ) {
                                rowPresets.forEach { preset ->
                                    val isSelected = selectedAvatar == preset.key
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(m.s(3f)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(m.s(8f)))
                                            .background(
                                                if (isSelected) RtxEmerald.copy(alpha = 0.20f)
                                                else ElevatedGunmetal.copy(alpha = 0.65f)
                                            )
                                            .border(
                                                width = if (isSelected) 1.5.dp else 1.dp,
                                                color = if (isSelected) RtxEmerald else BorderSteel,
                                                shape = RoundedCornerShape(m.s(8f))
                                            )
                                            .clickable {
                                                selectedAvatar = preset.key
                                                localError = null
                                            }
                                            .padding(vertical = m.s(6f), horizontal = m.s(4f))
                                            .testTag("avatar_${preset.key}")
                                    ) {
                                        PlayerAvatar(
                                            avatarData = preset.key,
                                            size = m.s(36f),
                                            borderColor = if (isSelected) RtxEmerald else BorderSteel,
                                            borderWidth = 1.4.dp
                                        )
                                        Text(
                                            text = preset.labelRu,
                                            fontSize = m.sp(8.2f),
                                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.SemiBold,
                                            color = if (isSelected) RtxEmerald else TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        val displayNotice = localError ?: statusBanner
                        if (!displayNotice.isNullOrBlank()) {
                            Text(
                                text = displayNotice,
                                fontSize = m.sp(9.8f),
                                fontWeight = FontWeight.SemiBold,
                                color = if (localError != null) ThreatCrimson else RtxEmerald
                            )
                        }
                    }
                }

                // Bottom Save / Cancel Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onClose,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElevatedGunmetal,
                            contentColor = TextPrimary
                        ),
                        border = BorderStroke(1.dp, BorderSteel),
                        modifier = Modifier
                            .height(m.s(40f).coerceAtLeast(48.dp))
                            .testTag("profile_cancel_button")
                    ) {
                        Text("ОТМЕНА", fontSize = m.sp(10.5f), fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(m.s(10f)))
                    Button(
                        onClick = {
                            val clean = nameInput.trim()
                            if (clean.length < 3 || clean.length > 20) {
                                localError = "Имя игрока должно содержать от 3 до 20 символов."
                                return@Button
                            }
                            localError = null
                            onSaveProfile(clean, selectedAvatar, onClose)
                        },
                        enabled = !isSaving && !isProcessingPhoto,
                        shape = CutCornerShape(topStart = m.s(9f), bottomEnd = m.s(9f)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RtxEmerald,
                            contentColor = ObsidianVoid
                        ),
                        contentPadding = PaddingValues(horizontal = m.s(18f), vertical = m.s(8f)),
                        modifier = Modifier
                            .height(m.s(40f).coerceAtLeast(48.dp))
                            .testTag("profile_save_button")
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(m.s(17f)),
                                color = ObsidianVoid,
                                strokeWidth = 2.2.dp
                            )
                            Spacer(Modifier.width(m.s(7f)))
                            Text("СОХРАНЕНИЕ...", fontSize = m.sp(11f), fontWeight = FontWeight.Black)
                        } else {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(m.s(17f)))
                            Spacer(Modifier.width(m.s(6f)))
                            Text("СОХРАНИТЬ ПРОФИЛЬ", fontSize = m.sp(11f), fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
    }
}
