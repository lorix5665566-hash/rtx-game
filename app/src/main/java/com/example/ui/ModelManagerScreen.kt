package com.example.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.ModelAssetRegistry
import com.example.model.ModelSlot
import com.example.model.SlotAssetInfo
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

private enum class ModelCategoryFilter(val labelRu: String, val categoryKey: String?) {
    ALL("ВСЕ", null),
    MAPS("КАРТЫ", "КАРТЫ"),
    CHARS("ПЕРСОНАЖИ", "ПЕРСОНАЖИ"),
    WEAPONS("ОРУЖИЕ", "ОРУЖИЕ"),
    GEAR("СНАРЯЖЕНИЕ", "СНАРЯЖЕНИЕ")
}

@Composable
fun ModelManagerScreen(
    registry: ModelAssetRegistry,
    onImportGlb: (ModelSlot, Uri, String?) -> Result<String>,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val m = LocalUiMetrics.current
    val slotStates by registry.slotStates.collectAsStateWithLifecycle()

    var activeFilter by remember { mutableStateOf(ModelCategoryFilter.ALL) }
    var pendingSlot by remember { mutableStateOf<ModelSlot?>(null) }
    var bannerMessage by remember { mutableStateOf<String?>(null) }
    var bannerIsError by remember { mutableStateOf(false) }

    // Zero-permission Android Storage Access Framework (SAF) .GLB document picker
    val glbPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val slot = pendingSlot
        pendingSlot = null
        if (uri != null && slot != null) {
            val friendlyName = resolveDisplayName(context, uri)
            val result = onImportGlb(slot, uri, friendlyName)
            result.onSuccess { msg ->
                bannerMessage = msg
                bannerIsError = false
            }.onFailure { err ->
                bannerMessage = err.message ?: "Ошибка импорта .GLB файла"
                bannerIsError = true
            }
        }
    }

    val filteredSlots = remember(slotStates, activeFilter) {
        val key = activeFilter.categoryKey
        if (key == null) slotStates else slotStates.filter { it.slot.categoryRu == key }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF0A0F18), Color(0xFF101927), Color(0xFF0A0E15))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = m.s(14f), vertical = m.s(8f)),
            verticalArrangement = Arrangement.spacedBy(m.s(8f))
        ) {
            // Top CS2 Frosted Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(m.s(10f)))
                    .background(Color(0xCC111926))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(m.s(10f)))
                    .padding(horizontal = m.s(10f), vertical = m.s(6f)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(m.s(8f))
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(m.s(40f).coerceAtLeast(48.dp))
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .testTag("models_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = TextPrimary,
                            modifier = Modifier.size(m.s(20f))
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.ViewInAr,
                        contentDescription = null,
                        tint = RtxEmerald,
                        modifier = Modifier.size(m.s(22f))
                    )

                    Column {
                        Text(
                            text = "3D-МОДЕЛИ • GOOGLE FILAMENT PBR",
                            fontSize = m.sp(14f),
                            fontWeight = FontWeight.Black,
                            color = TextPrimary
                        )
                        Text(
                            text = "Встроенные CS2 .GLB + импорт любых моделей со Sketchfab (glTF 2.0)",
                            fontSize = m.sp(10f),
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(m.s(8f))
                ) {
                    Button(
                        onClick = {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ModelAssetRegistry.SKETCHFAB_CS2_URL))
                                context.startActivity(intent)
                            }
                        },
                        shape = CutCornerShape(topStart = m.s(8f), bottomEnd = m.s(8f)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = IceCyan,
                            contentColor = ObsidianVoid
                        ),
                        contentPadding = PaddingValues(horizontal = m.s(12f), vertical = m.s(6f)),
                        modifier = Modifier
                            .height(m.s(38f).coerceAtLeast(40.dp))
                            .testTag("open_sketchfab_cs2_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(m.s(16f))
                        )
                        Spacer(modifier = Modifier.width(m.s(5f)))
                        Text(
                            text = "SKETCHFAB CS2",
                            fontSize = m.sp(11f),
                            fontWeight = FontWeight.Black
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            registry.resetAllToBundled()
                            bannerMessage = "Все слоты возвращены к встроенным CS2 .GLB моделям"
                            bannerIsError = false
                        },
                        shape = CutCornerShape(topStart = m.s(8f), bottomEnd = m.s(8f)),
                        border = BorderStroke(1.dp, TacticalAmber.copy(alpha = 0.65f)),
                        contentPadding = PaddingValues(horizontal = m.s(10f), vertical = m.s(6f)),
                        modifier = Modifier
                            .height(m.s(38f).coerceAtLeast(40.dp))
                            .testTag("models_reset_all_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Сбросить все",
                            tint = TacticalAmber,
                            modifier = Modifier.size(m.s(16f))
                        )
                        Spacer(modifier = Modifier.width(m.s(4f)))
                        Text(
                            text = "СБРОС ВСЕХ",
                            fontSize = m.sp(10f),
                            fontWeight = FontWeight.Bold,
                            color = TacticalAmber
                        )
                    }
                }
            }

            // Category Filter Chips + Quick Instruction
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(m.s(6f)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ModelCategoryFilter.entries.forEach { filter ->
                        val selected = activeFilter == filter
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(m.s(6f)))
                                .background(
                                    if (selected) RtxEmerald.copy(alpha = 0.22f) else ElevatedGunmetal.copy(alpha = 0.72f)
                                )
                                .border(
                                    width = if (selected) 1.3.dp else 1.dp,
                                    color = if (selected) RtxEmerald else BorderSteel,
                                    shape = RoundedCornerShape(m.s(6f))
                                )
                                .clickable { activeFilter = filter }
                                .padding(horizontal = m.s(11f), vertical = m.s(6f))
                                .testTag("filter_${filter.name.lowercase()}")
                        ) {
                            Text(
                                text = filter.labelRu,
                                fontSize = m.sp(10.5f),
                                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                                color = if (selected) RtxEmerald else TextPrimary
                            )
                        }
                    }
                }

                Text(
                    text = "Скачайте .GLB (glTF) со Sketchfab и нажмите «ИМПОРТ .GLB»",
                    fontSize = m.sp(10f),
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Status feedback toast banner
            bannerMessage?.let { msg ->
                val accent = if (bannerIsError) ThreatCrimson else RtxEmerald
                Surface(
                    color = accent.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(m.s(8f)),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.75f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = m.s(10f), vertical = m.s(5f)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = msg,
                            fontSize = m.sp(11f),
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { bannerMessage = null },
                            modifier = Modifier.size(m.s(26f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Закрыть",
                                tint = TextPrimary,
                                modifier = Modifier.size(m.s(15f))
                            )
                        }
                    }
                }
            }

            // 2-Column Adaptive Grid of All 3D Model Slots
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (m.isWideScreen) 3 else 2),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(m.s(8f)),
                verticalArrangement = Arrangement.spacedBy(m.s(8f)),
                contentPadding = PaddingValues(bottom = m.s(8f))
            ) {
                items(filteredSlots, key = { it.slot.name }) { info ->
                    ModelSlotCard(
                        info = info,
                        onImportClick = {
                            pendingSlot = info.slot
                            glbPicker.launch(arrayOf("*/*"))
                        },
                        onSearchSketchfab = {
                            runCatching {
                                val q = Uri.encode(info.slot.sketchfabQuery)
                                val url = "https://sketchfab.com/search?q=$q&type=models&features=downloadable"
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        },
                        onResetSlot = {
                            registry.resetSlotToBundled(info.slot)
                            bannerMessage = "Слот «${info.slot.titleRu}» сброшен к встроенной CS2 модели"
                            bannerIsError = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelSlotCard(
    info: SlotAssetInfo,
    onImportClick: () -> Unit,
    onSearchSketchfab: () -> Unit,
    onResetSlot: () -> Unit
) {
    val m = LocalUiMetrics.current
    val accent = when {
        info.isCustomImported -> IceCyan
        info.slot.categoryRu == "КАРТЫ" -> TacticalAmber
        info.slot.categoryRu == "ПЕРСОНАЖИ" -> RtxEmerald
        info.slot.categoryRu == "СНАРЯЖЕНИЕ" -> ThreatCrimson
        else -> Color(0xFF93C5FD)
    }
    val sizeText = if (info.fileSizeKb >= 1024L) {
        String.format("%.1f МБ", info.fileSizeKb / 1024.0)
    } else {
        "${info.fileSizeKb} КБ"
    }

    Surface(
        color = Color(0xD9111926),
        shape = CutCornerShape(topStart = m.s(10f), bottomEnd = m.s(10f)),
        border = BorderStroke(
            width = if (info.isCustomImported) 1.4.dp else 1.dp,
            color = if (info.isCustomImported) IceCyan.copy(alpha = 0.8f) else BorderSteel
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_card_${info.slot.name.lowercase()}")
    ) {
        Column(
            modifier = Modifier.padding(m.s(10f)),
            verticalArrangement = Arrangement.spacedBy(m.s(6f))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = info.slot.titleRu,
                    fontSize = m.sp(12f),
                    fontWeight = FontWeight.Black,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(m.s(4f)))
                        .background(accent.copy(alpha = 0.20f))
                        .padding(horizontal = m.s(6f), vertical = m.s(2f))
                ) {
                    Text(
                        text = if (info.isCustomImported) "SKETCHFAB" else info.slot.categoryRu,
                        fontSize = m.sp(8.5f),
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                }
            }

            Text(
                text = "${info.displayName}  •  $sizeText",
                fontSize = m.sp(10f),
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(m.s(6f)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onImportClick,
                    shape = RoundedCornerShape(m.s(6f)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RtxEmerald.copy(alpha = 0.22f),
                        contentColor = RtxEmerald
                    ),
                    border = BorderStroke(1.dp, RtxEmerald.copy(alpha = 0.7f)),
                    contentPadding = PaddingValues(horizontal = m.s(8f), vertical = m.s(4f)),
                    modifier = Modifier
                        .weight(1f)
                        .height(m.s(34f).coerceAtLeast(36.dp))
                        .testTag("import_slot_${info.slot.name.lowercase()}")
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(m.s(14f))
                    )
                    Spacer(modifier = Modifier.width(m.s(4f)))
                    Text(
                        text = "ИМПОРТ .GLB",
                        fontSize = m.sp(10f),
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1
                    )
                }

                IconButton(
                    onClick = onSearchSketchfab,
                    modifier = Modifier
                        .size(m.s(34f).coerceAtLeast(36.dp))
                        .clip(RoundedCornerShape(m.s(6f)))
                        .background(IceCyan.copy(alpha = 0.16f))
                        .border(1.dp, IceCyan.copy(alpha = 0.5f), RoundedCornerShape(m.s(6f)))
                        .testTag("search_slot_${info.slot.name.lowercase()}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Найти на Sketchfab",
                        tint = IceCyan,
                        modifier = Modifier.size(m.s(16f))
                    )
                }

                if (info.isCustomImported) {
                    IconButton(
                        onClick = onResetSlot,
                        modifier = Modifier
                            .size(m.s(34f).coerceAtLeast(36.dp))
                            .clip(RoundedCornerShape(m.s(6f)))
                            .background(ThreatCrimson.copy(alpha = 0.16f))
                            .border(1.dp, ThreatCrimson.copy(alpha = 0.55f), RoundedCornerShape(m.s(6f)))
                            .testTag("reset_slot_${info.slot.name.lowercase()}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Вернуть встроенную модель",
                            tint = ThreatCrimson,
                            modifier = Modifier.size(m.s(16f))
                        )
                    }
                }
            }
        }
    }
}

private fun resolveDisplayName(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    }.getOrNull() ?: uri.lastPathSegment
}
