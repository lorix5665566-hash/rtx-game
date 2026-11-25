package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.model.LevelProgression
import com.example.model.SkinCase
import com.example.model.SkinCatalog
import com.example.model.WeaponSkin
import com.example.model.WeaponType
import com.example.online.PlayerProfile
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
import kotlinx.coroutines.launch
import kotlin.random.Random

private data class CaseDropResult(
    val skin: WeaponSkin,
    val wasDuplicate: Boolean,
    val refundCoins: Int
)

/**
 * Full-screen tactical overlay for Skin Inventory (equipping 3D weapon skins)
 * and Skin Cases (100 coins each, animated roulette spin + duplicate cashback).
 */
@Composable
fun InventoryAndCasesOverlay(
    initialTab: Int,
    profile: PlayerProfile?,
    isOpeningCase: Boolean,
    isUpdatingSkin: Boolean,
    statusBanner: String?,
    onOpenCase: (
        skinCase: SkinCase,
        onRolled: (skin: WeaponSkin, wasDuplicate: Boolean, refundCoins: Int) -> Unit,
        onError: (String) -> Unit
    ) -> Unit,
    onEquipSkin: (WeaponType, String?) -> Unit,
    onClearBanner: () -> Unit,
    onClose: () -> Unit
) {
    val m = LocalUiMetrics.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var activeTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 1)) }
    var selectedWeaponFilter by remember { mutableStateOf<WeaponType?>(null) }
    var expandedCaseId by remember { mutableStateOf<String?>(null) }
    var previewSkin by remember { mutableStateOf<WeaponSkin?>(null) }

    // Animated roulette state
    var spinningCase by remember { mutableStateOf<SkinCase?>(null) }
    var rouletteStrip by remember { mutableStateOf<List<WeaponSkin>>(emptyList()) }
    var isRouletteRunning by remember { mutableStateOf(false) }
    var revealedDrop by remember { mutableStateOf<CaseDropResult?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    val spinOffsetPx = remember { Animatable(0f) }

    BackHandler {
        when {
            previewSkin != null -> previewSkin = null
            !isRouletteRunning -> onClose()
        }
    }

    val coins = profile?.coins ?: 0
    val ownedSkinIds = profile?.ownedSkins ?: emptyList()
    val equippedSkins = profile?.equippedSkins ?: emptyMap()
    val levelInfo = remember(profile?.xp) {
        LevelProgression.computeFromTotalXp(profile?.xp ?: 0L)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xD8050911))
            .clickable(enabled = true, onClick = {})
            .safeDrawingPadding()
            .padding(horizontal = m.s(14f), vertical = m.s(10f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Color(0xF50B111C),
            shape = CutCornerShape(topStart = m.s(16f), bottomEnd = m.s(16f)),
            border = BorderStroke(1.3.dp, TacticalAmber.copy(alpha = 0.65f)),
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 920.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(m.s(12f)),
                verticalArrangement = Arrangement.spacedBy(m.s(9f))
            ) {
                // Header: Title + Tabs + Level/Coins pill + Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(m.s(8f))
                    ) {
                        InventoryTabPill(
                            label = "ИНВЕНТАРЬ (${ownedSkinIds.size})",
                            icon = Icons.Default.Inventory2,
                            selected = activeTab == 0,
                            accent = IceCyan,
                            onClick = { if (!isRouletteRunning) activeTab = 0 },
                            tag = "inventory_tab_skins"
                        )
                        InventoryTabPill(
                            label = "КЕЙСЫ",
                            icon = Icons.Default.Redeem,
                            selected = activeTab == 1,
                            accent = TacticalAmber,
                            onClick = { if (!isRouletteRunning) activeTab = 1 },
                            tag = "inventory_tab_cases"
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(m.s(8f))
                    ) {
                        // Level + Coins balance badge
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(m.s(8f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(m.s(8f)))
                                .background(ElevatedGunmetal)
                                .border(1.dp, TacticalAmber.copy(alpha = 0.6f), RoundedCornerShape(m.s(8f)))
                                .padding(horizontal = m.s(10f), vertical = m.s(5f))
                        ) {
                            Text(
                                text = "LVL ${levelInfo.level}",
                                fontSize = m.sp(10.5f),
                                fontWeight = FontWeight.Black,
                                color = IceCyan
                            )
                            Text(
                                text = "•",
                                fontSize = m.sp(10f),
                                color = TextSecondary
                            )
                            Icon(
                                imageVector = Icons.Default.MonetizationOn,
                                contentDescription = "Монеты",
                                tint = TacticalAmber,
                                modifier = Modifier.size(m.s(15f))
                            )
                            Text(
                                text = "$coins",
                                fontSize = m.sp(12f),
                                fontWeight = FontWeight.Black,
                                color = TacticalAmber,
                                modifier = Modifier.testTag("inventory_coins_balance")
                            )
                        }

                        IconButton(
                            onClick = { if (!isRouletteRunning) onClose() },
                            enabled = !isRouletteRunning,
                            modifier = Modifier
                                .size(m.s(36f).coerceAtLeast(48.dp))
                                .testTag("inventory_close_button")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = TextPrimary)
                        }
                    }
                }

                // Feedback banner / local error
                val bannerMsg = localError ?: statusBanner
                if (!bannerMsg.isNullOrBlank()) {
                    val isErr = localError != null
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(m.s(7f)))
                            .background((if (isErr) ThreatCrimson else RtxEmerald).copy(alpha = 0.16f))
                            .border(1.dp, (if (isErr) ThreatCrimson else RtxEmerald).copy(alpha = 0.65f), RoundedCornerShape(m.s(7f)))
                            .padding(horizontal = m.s(10f), vertical = m.s(6f)),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = bannerMsg,
                            fontSize = m.sp(10.5f),
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "OK",
                            fontSize = m.sp(10f),
                            fontWeight = FontWeight.Black,
                            color = if (isErr) ThreatCrimson else RtxEmerald,
                            modifier = Modifier
                                .clickable {
                                    localError = null
                                    onClearBanner()
                                }
                                .padding(horizontal = m.s(6f))
                        )
                    }
                }

                // Body
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (activeTab == 0) {
                        // === TAB 0: OWNED SKIN INVENTORY ===
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(m.s(8f))
                        ) {
                            // Weapon Filter Strip
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(m.s(6f))
                            ) {
                                WeaponFilterChip(
                                    label = "ВСЕ (${ownedSkinIds.size})",
                                    selected = selectedWeaponFilter == null,
                                    onClick = { selectedWeaponFilter = null },
                                    tag = "inv_filter_all"
                                )
                                SkinCatalog.skinnableWeapons.forEach { w ->
                                    val countForW = ownedSkinIds.count { SkinCatalog.byId(it)?.weapon == w }
                                    WeaponFilterChip(
                                        label = if (countForW > 0) "${w.shortLabel} ($countForW)" else w.shortLabel,
                                        selected = selectedWeaponFilter == w,
                                        onClick = { selectedWeaponFilter = w },
                                        tag = "inv_filter_${w.name.lowercase()}"
                                    )
                                }
                            }

                            val ownedSkins = remember(ownedSkinIds, selectedWeaponFilter) {
                                ownedSkinIds
                                    .mapNotNull { SkinCatalog.byId(it) }
                                    .filter { selectedWeaponFilter == null || it.weapon == selectedWeaponFilter }
                                    .sortedWith(compareByDescending<WeaponSkin> { it.rarity.ordinal }.thenBy { it.weapon.ordinal })
                            }

                            if (ownedSkins.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(m.s(8f))
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = TacticalAmber.copy(alpha = 0.75f),
                                            modifier = Modifier.size(m.s(42f))
                                        )
                                        Text(
                                            text = if (selectedWeaponFilter == null) {
                                                "В инвентаре пока нет скинов"
                                            } else {
                                                "Нет скинов для ${selectedWeaponFilter!!.shortLabel}"
                                            },
                                            fontSize = m.sp(13f),
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = "Повышайте уровень (+50 монет за уровень) и открывайте кейсы по 100 монет. Скины видны от 1-го лица и другим игрокам!",
                                            fontSize = m.sp(10f),
                                            color = TextSecondary,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.widthIn(max = 460.dp)
                                        )
                                        Button(
                                            onClick = { activeTab = 1 },
                                            colors = ButtonDefaults.buttonColors(containerColor = TacticalAmber, contentColor = ObsidianVoid),
                                            shape = CutCornerShape(topStart = m.s(8f), bottomEnd = m.s(8f)),
                                            modifier = Modifier
                                                .height(48.dp)
                                                .testTag("empty_inventory_go_cases")
                                        ) {
                                            Icon(Icons.Default.Redeem, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("ПЕРЕЙТИ К КЕЙСАМ", fontWeight = FontWeight.Black, fontSize = m.sp(11f))
                                        }
                                    }
                                }
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(minSize = 210.dp),
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.spacedBy(m.s(8f)),
                                    verticalArrangement = Arrangement.spacedBy(m.s(8f))
                                ) {
                                    items(ownedSkins, key = { it.id }) { skin ->
                                        val isEquipped = equippedSkins[skin.weapon.name] == skin.id
                                        OwnedSkinCard(
                                            skin = skin,
                                            isEquipped = isEquipped,
                                            isBusy = isUpdatingSkin,
                                            onPreview = { previewSkin = skin },
                                            onToggleEquip = {
                                                onEquipSkin(skin.weapon, if (isEquipped) null else skin.id)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // === TAB 1: SKIN CASES & ROULETTE ===
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(m.s(8f))
                        ) {
                            // Economy helper ribbon
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(m.s(7f)))
                                    .background(ElevatedGunmetal.copy(alpha = 0.72f))
                                    .border(1.dp, BorderSteel, RoundedCornerShape(m.s(7f)))
                                    .padding(horizontal = m.s(10f), vertical = m.s(6f)),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Уровень ${levelInfo.level}: ${levelInfo.xpIntoLevel} / ${levelInfo.xpForNextLevel} EXP • +100 EXP за убийство • +50 монет за новый уровень",
                                    fontSize = m.sp(9.5f),
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "КЕЙС = 100 🪙",
                                    fontSize = m.sp(10f),
                                    fontWeight = FontWeight.Black,
                                    color = TacticalAmber
                                )
                            }

                            // 3 Themed Cases List
                            LazyColumn(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(m.s(8f))
                            ) {
                                items(SkinCatalog.allCases, key = { it.id }) { skinCase ->
                                    val isExpanded = expandedCaseId == skinCase.id
                                    val canAfford = coins >= skinCase.priceCoins
                                    val poolSkins = remember(skinCase.id) { SkinCatalog.skinsInCase(skinCase) }
                                    SkinCaseCard(
                                        skinCase = skinCase,
                                        poolSkins = poolSkins,
                                        ownedSkinIds = ownedSkinIds,
                                        canAfford = canAfford,
                                        isBusy = isOpeningCase || isRouletteRunning,
                                        isExpanded = isExpanded,
                                        onToggleExpand = {
                                            expandedCaseId = if (isExpanded) null else skinCase.id
                                        },
                                        onPreviewSkin = { skin -> previewSkin = skin },
                                        onOpenClick = {
                                            if (isOpeningCase || isRouletteRunning) return@SkinCaseCard
                                            localError = null
                                            onClearBanner()
                                            revealedDrop = null
                                            onOpenCase(
                                                skinCase,
                                                { wonSkin, wasDup, refund ->
                                                    // Build 42-slot roulette strip with winning skin at index 34
                                                    val winIndex = 34
                                                    val pool = SkinCatalog.skinsInCase(skinCase).ifEmpty { SkinCatalog.allSkins }
                                                    val strip = List(42) { idx ->
                                                        if (idx == winIndex) wonSkin
                                                        else pool[Random.nextInt(pool.size)]
                                                    }
                                                    spinningCase = skinCase
                                                    rouletteStrip = strip
                                                    isRouletteRunning = true
                                                    scope.launch {
                                                        val cardStepPx = with(density) { (140.dp + 10.dp).toPx() }
                                                        val jitterPx = (Random.nextFloat() - 0.5f) * cardStepPx * 0.42f
                                                        val targetPx = winIndex * cardStepPx + jitterPx
                                                        spinOffsetPx.snapTo(0f)
                                                        spinOffsetPx.animateTo(
                                                            targetValue = targetPx,
                                                            animationSpec = tween(
                                                                durationMillis = 3600,
                                                                easing = CubicBezierEasing(0.10f, 0.88f, 0.18f, 1.0f)
                                                            )
                                                        )
                                                        isRouletteRunning = false
                                                        revealedDrop = CaseDropResult(wonSkin, wasDup, refund)
                                                    }
                                                },
                                                { err ->
                                                    localError = err
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Prominent Centered Case Opening Roulette & Drop Reveal Modal
        if (spinningCase != null && rouletteStrip.isNotEmpty()) {
            CaseRouletteStage(
                skinCase = spinningCase!!,
                strip = rouletteStrip,
                offsetPx = spinOffsetPx.value,
                isSpinning = isRouletteRunning,
                result = revealedDrop,
                isUpdatingSkin = isUpdatingSkin,
                onPreviewSkin = { skin -> previewSkin = skin },
                onEquipNow = { skin ->
                    onEquipSkin(skin.weapon, skin.id)
                },
                onDismissStage = {
                    if (!isRouletteRunning) {
                        spinningCase = null
                        revealedDrop = null
                        rouletteStrip = emptyList()
                    }
                }
            )
        }

        val inspecting = previewSkin
        if (inspecting != null) {
            val isOwned = ownedSkinIds.contains(inspecting.id)
            val isEquipped = equippedSkins[inspecting.weapon.name] == inspecting.id
            SkinPreviewDialog(
                skin = inspecting,
                isOwned = isOwned,
                isEquipped = isEquipped,
                isBusy = isUpdatingSkin,
                onToggleEquip = {
                    onEquipSkin(inspecting.weapon, if (isEquipped) null else inspecting.id)
                },
                onDismiss = { previewSkin = null }
            )
        }
    }
}

@Composable
private fun InventoryTabPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    tag: String
) {
    val m = LocalUiMetrics.current
    val shape = CutCornerShape(topStart = m.s(8f), bottomEnd = m.s(8f))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.s(6f)),
        modifier = Modifier
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.22f) else ElevatedGunmetal.copy(alpha = 0.78f))
            .border(if (selected) 1.4.dp else 1.dp, if (selected) accent else BorderSteel, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = m.s(12f), vertical = m.s(8f))
            .testTag(tag)
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) accent else TextSecondary, modifier = Modifier.size(m.s(16f)))
        Text(
            text = label,
            fontSize = m.sp(11f),
            fontWeight = FontWeight.Black,
            color = if (selected) Color.White else TextSecondary
        )
    }
}

@Composable
private fun WeaponFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String
) {
    val m = LocalUiMetrics.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(m.s(6f)))
            .background(if (selected) IceCyan.copy(alpha = 0.22f) else ElevatedGunmetal.copy(alpha = 0.75f))
            .border(1.dp, if (selected) IceCyan else BorderSteel, RoundedCornerShape(m.s(6f)))
            .clickable(onClick = onClick)
            .padding(horizontal = m.s(10f), vertical = m.s(6f))
            .testTag(tag)
    ) {
        Text(
            text = label,
            fontSize = m.sp(10f),
            fontWeight = FontWeight.Bold,
            color = if (selected) IceCyan else TextPrimary
        )
    }
}

@Composable
private fun OwnedSkinCard(
    skin: WeaponSkin,
    isEquipped: Boolean,
    isBusy: Boolean,
    onPreview: () -> Unit,
    onToggleEquip: () -> Unit
) {
    val m = LocalUiMetrics.current
    Surface(
        color = ElevatedGunmetal.copy(alpha = 0.90f),
        shape = RoundedCornerShape(m.s(10f)),
        border = BorderStroke(
            width = if (isEquipped) 1.8.dp else 1.dp,
            color = if (isEquipped) RtxEmerald else skin.rarity.accentColor.copy(alpha = 0.65f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPreview)
            .testTag("owned_skin_card_${skin.id}")
    ) {
        Column(
            modifier = Modifier.padding(m.s(10f)),
            verticalArrangement = Arrangement.spacedBy(m.s(7f))
        ) {
            // Weapon silhouette artwork + rarity badge
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(m.s(74f))
                    .clip(RoundedCornerShape(m.s(7f)))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                skin.rarity.badgeBgColor,
                                skin.primaryColor.copy(alpha = 0.36f),
                                Color(0xFF0A101B)
                            )
                        )
                    )
                    .border(1.dp, skin.rarity.accentColor.copy(alpha = 0.8f), RoundedCornerShape(m.s(7f)))
                    .padding(m.s(6f))
            ) {
                WeaponSilhouetteArt(
                    skin = skin,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = m.s(10f), vertical = m.s(4f))
                )
                Text(
                    text = skin.weapon.shortLabel,
                    fontSize = m.sp(9.5f),
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xBB070B12))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Text(
                    text = "ПРЕВЬЮ",
                    fontSize = m.sp(8f),
                    fontWeight = FontWeight.Bold,
                    color = IceCyan,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xBB070B12))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
                Text(
                    text = skin.rarity.displayRu.uppercase(),
                    fontSize = m.sp(8.5f),
                    fontWeight = FontWeight.Black,
                    color = skin.rarity.accentColor,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xCC070B12))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = skin.nameRu,
                    fontSize = m.sp(11.5f),
                    fontWeight = FontWeight.Black,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = skin.subtitleRu,
                    fontSize = m.sp(9f),
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Button(
                onClick = onToggleEquip,
                enabled = !isBusy,
                shape = CutCornerShape(topStart = m.s(6f), bottomEnd = m.s(6f)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isEquipped) RtxEmerald.copy(alpha = 0.22f) else skin.rarity.accentColor,
                    contentColor = if (isEquipped) RtxEmerald else ObsidianVoid
                ),
                border = if (isEquipped) BorderStroke(1.dp, RtxEmerald) else null,
                contentPadding = PaddingValues(horizontal = m.s(10f), vertical = m.s(6f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("equip_skin_${skin.id}")
            ) {
                if (isEquipped) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("НАДЕТО (СНЯТЬ)", fontWeight = FontWeight.Black, fontSize = m.sp(10f))
                } else {
                    Text("ПРИМЕНИТЬ", fontWeight = FontWeight.Black, fontSize = m.sp(10.5f))
                }
            }
        }
    }
}

@Composable
private fun SkinCaseCard(
    skinCase: SkinCase,
    poolSkins: List<WeaponSkin>,
    ownedSkinIds: List<String>,
    canAfford: Boolean,
    isBusy: Boolean,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onPreviewSkin: (WeaponSkin) -> Unit,
    onOpenClick: () -> Unit
) {
    val m = LocalUiMetrics.current
    Surface(
        color = TacticalSurface,
        shape = CutCornerShape(topStart = m.s(12f), bottomEnd = m.s(12f)),
        border = BorderStroke(1.2.dp, skinCase.bannerAccentColor.copy(alpha = 0.65f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                skinCase.bannerPrimaryColor.copy(alpha = 0.85f),
                                Color(0xFF0E1522)
                            )
                        )
                    )
                    .padding(horizontal = m.s(12f), vertical = m.s(10f)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onToggleExpand),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Redeem,
                            contentDescription = null,
                            tint = skinCase.bannerAccentColor,
                            modifier = Modifier.size(m.s(18f))
                        )
                        Text(
                            text = skinCase.nameRu,
                            fontSize = m.sp(13f),
                            fontWeight = FontWeight.Black,
                            color = TextPrimary
                        )
                    }
                    Text(
                        text = "${skinCase.subtitleRu} • ${poolSkins.size} скинов (${if (isExpanded) "скрыть состав" else "показать состав"})",
                        fontSize = m.sp(9.5f),
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.width(m.s(10f)))

                Button(
                    onClick = onOpenClick,
                    enabled = canAfford && !isBusy,
                    shape = CutCornerShape(topStart = m.s(8f), bottomEnd = m.s(8f)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (canAfford) TacticalAmber else ElevatedGunmetal,
                        contentColor = if (canAfford) ObsidianVoid else TextSecondary
                    ),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("open_case_${skinCase.id}")
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ObsidianVoid)
                    } else {
                        Text(
                            text = "ОТКРЫТЬ • ${skinCase.priceCoins} 🪙",
                            fontSize = m.sp(10.5f),
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }

            if (isExpanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = m.s(10f), vertical = m.s(8f)),
                    horizontalArrangement = Arrangement.spacedBy(m.s(6f))
                ) {
                    poolSkins.forEach { skin ->
                        val owned = ownedSkinIds.contains(skin.id)
                        Column(
                            modifier = Modifier
                                .width(132.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(ElevatedGunmetal)
                                .border(1.dp, skin.rarity.accentColor.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                                .clickable { onPreviewSkin(skin) }
                                .padding(7.dp)
                                .testTag("case_pool_skin_${skin.id}"),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(skin.rarity.badgeBgColor, skin.primaryColor.copy(alpha = 0.38f), Color(0xFF0A101B))
                                        )
                                    )
                                    .padding(4.dp)
                            ) {
                                WeaponSilhouetteArt(skin = skin, modifier = Modifier.fillMaxSize())
                            }
                            Text(
                                text = "${skin.weapon.shortLabel} | ${skin.nameRu}",
                                fontSize = m.sp(8.5f),
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (owned) "${skin.rarity.displayRu} • ЕСТЬ" else skin.rarity.displayRu,
                                fontSize = m.sp(8f),
                                fontWeight = FontWeight.SemiBold,
                                color = if (owned) RtxEmerald else skin.rarity.accentColor,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CaseRouletteStage(
    skinCase: SkinCase,
    strip: List<WeaponSkin>,
    offsetPx: Float,
    isSpinning: Boolean,
    result: CaseDropResult?,
    isUpdatingSkin: Boolean,
    onPreviewSkin: (WeaponSkin) -> Unit,
    onEquipNow: (WeaponSkin) -> Unit,
    onDismissStage: () -> Unit
) {
    val m = LocalUiMetrics.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xDE04070E))
            .clickable(enabled = !isSpinning, onClick = onDismissStage)
            .padding(horizontal = m.s(14f), vertical = m.s(12f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Color(0xFA0B121F),
            shape = CutCornerShape(topStart = m.s(14f), bottomEnd = m.s(14f)),
            border = BorderStroke(
                width = 2.dp,
                color = result?.skin?.rarity?.accentColor ?: skinCase.bannerAccentColor
            ),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 780.dp)
                .clickable(enabled = true, onClick = {})
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(m.s(14f)),
                verticalArrangement = Arrangement.spacedBy(m.s(10f))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isSpinning) {
                            "ОТКРЫТИЕ: ${skinCase.nameRu.uppercase()}..."
                        } else {
                            "ВЫПАЛО ИЗ ${skinCase.nameRu.uppercase()}"
                        },
                        fontSize = m.sp(12.5f),
                        fontWeight = FontWeight.Black,
                        color = result?.skin?.rarity?.accentColor ?: TacticalAmber
                    )
                    if (!isSpinning) {
                        IconButton(
                            onClick = onDismissStage,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("case_stage_top_close")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть окно кейса", tint = TextPrimary)
                        }
                    }
                }

                // Horizontal roulette viewport with clearly visible weapon skins + golden center line
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(124.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ObsidianVoid)
                        .border(1.2.dp, BorderSteel, RoundedCornerShape(10.dp))
                        .clipToBounds()
                ) {
                    val centerShiftPx = with(LocalDensity.current) { (maxWidth / 2 - 70.dp).toPx() }
                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .graphicsLayer {
                                translationX = centerShiftPx - offsetPx
                            },
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        strip.forEach { item ->
                            Column(
                                modifier = Modifier
                                    .width(140.dp)
                                    .height(104.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                item.rarity.badgeBgColor,
                                                Color(0xFF0A101B)
                                            )
                                        )
                                    )
                                    .border(1.5.dp, item.rarity.accentColor, RoundedCornerShape(8.dp))
                                    .padding(7.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(54.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(
                                            Brush.linearGradient(
                                                listOf(
                                                    item.primaryColor.copy(alpha = 0.25f),
                                                    item.secondaryColor.copy(alpha = 0.22f),
                                                    Color(0xFF0B111C)
                                                )
                                            )
                                        )
                                        .padding(4.dp)
                                ) {
                                    WeaponSilhouetteArt(skin = item, modifier = Modifier.fillMaxSize())
                                }
                                Text(
                                    text = item.nameRu,
                                    fontSize = m.sp(9f),
                                    fontWeight = FontWeight.Black,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = item.weapon.shortLabel,
                                        fontSize = m.sp(8f),
                                        fontWeight = FontWeight.Bold,
                                        color = item.rarity.accentColor,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = item.rarity.displayRu,
                                        fontSize = m.sp(7.5f),
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextSecondary,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                    // Center indicator beam
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(TacticalAmber)
                    )
                }

                if (result != null && !isSpinning) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(result.skin.rarity.badgeBgColor.copy(alpha = 0.55f))
                            .border(1.2.dp, result.skin.rarity.accentColor, RoundedCornerShape(10.dp))
                            .clickable { onPreviewSkin(result.skin) }
                            .padding(m.s(10f))
                            .testTag("case_drop_preview_card"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 112.dp, height = 64.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(Color(0xCC070B12))
                                .border(1.dp, result.skin.rarity.accentColor.copy(alpha = 0.7f), RoundedCornerShape(7.dp))
                                .padding(6.dp)
                        ) {
                            WeaponSilhouetteArt(skin = result.skin, modifier = Modifier.fillMaxSize())
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "${result.skin.weapon.shortLabel} | ${result.skin.nameRu}",
                                fontSize = m.sp(13f),
                                fontWeight = FontWeight.Black,
                                color = result.skin.rarity.accentColor
                            )
                            Text(
                                text = if (result.wasDuplicate) {
                                    "Дубликат • Возвращено +${result.refundCoins} монет!"
                                } else {
                                    "${result.skin.rarity.displayRu} • Нажмите для детального превью"
                                },
                                fontSize = m.sp(10f),
                                color = if (result.wasDuplicate) TacticalAmber else RtxEmerald
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onEquipNow(result.skin) },
                                enabled = !isUpdatingSkin,
                                colors = ButtonDefaults.buttonColors(containerColor = RtxEmerald, contentColor = ObsidianVoid),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("case_drop_equip_now")
                            ) {
                                Text("ПРИМЕНИТЬ", fontWeight = FontWeight.Black, fontSize = m.sp(10.5f))
                            }
                            OutlinedButton(
                                onClick = onDismissStage,
                                border = BorderStroke(1.dp, BorderSteel),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("case_drop_close")
                            ) {
                                Text("СКРЫТЬ", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = m.sp(10.5f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkinPreviewDialog(
    skin: WeaponSkin,
    isOwned: Boolean,
    isEquipped: Boolean,
    isBusy: Boolean,
    onToggleEquip: () -> Unit,
    onDismiss: () -> Unit
) {
    val m = LocalUiMetrics.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = Color(0xFA0B121F),
            shape = CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp),
            border = BorderStroke(1.8.dp, skin.rarity.accentColor),
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .widthIn(max = 640.dp)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = skin.weapon.shortLabel,
                                fontSize = m.sp(10.5f),
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(ElevatedGunmetal)
                                    .border(1.dp, BorderSteel, RoundedCornerShape(5.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                            Text(
                                text = skin.rarity.displayRu.uppercase(),
                                fontSize = m.sp(10f),
                                fontWeight = FontWeight.Black,
                                color = skin.rarity.accentColor,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(skin.rarity.badgeBgColor)
                                    .border(1.dp, skin.rarity.accentColor, RoundedCornerShape(5.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = skin.nameRu,
                            fontSize = m.sp(17f),
                            fontWeight = FontWeight.Black,
                            color = TextPrimary
                        )
                        Text(
                            text = skin.subtitleRu,
                            fontSize = m.sp(11f),
                            color = TextSecondary
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("skin_preview_close")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть превью", tint = TextPrimary)
                    }
                }

                // Large PBR weapon silhouette showcase
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(164.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    skin.rarity.accentColor.copy(alpha = 0.28f),
                                    skin.rarity.badgeBgColor.copy(alpha = 0.72f),
                                    ObsidianVoid
                                )
                            )
                        )
                        .border(1.2.dp, skin.rarity.accentColor.copy(alpha = 0.75f), RoundedCornerShape(10.dp))
                        .padding(16.dp)
                ) {
                    WeaponSilhouetteArt(skin = skin, modifier = Modifier.fillMaxSize())
                }

                // PBR Material swatches & finish stats
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .height(34.dp)
                            .width(120.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .border(1.dp, BorderSteel, RoundedCornerShape(7.dp))
                    ) {
                        Box(Modifier.weight(1f).fillMaxHeight().background(skin.primaryColor))
                        Box(Modifier.weight(1f).fillMaxHeight().background(skin.secondaryColor))
                        Box(Modifier.weight(0.75f).fillMaxHeight().background(skin.accentColor))
                    }
                    Text(
                        text = "Металличность ${(skin.metallic * 100).toInt()}% • Шероховатость ${(skin.roughness * 100).toInt()}% • Свечение ${(skin.emissiveIntensity * 100).toInt()}%",
                        fontSize = m.sp(9.5f),
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isOwned) {
                        Button(
                            onClick = onToggleEquip,
                            enabled = !isBusy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isEquipped) ElevatedGunmetal else RtxEmerald,
                                contentColor = if (isEquipped) RtxEmerald else ObsidianVoid
                            ),
                            border = if (isEquipped) BorderStroke(1.2.dp, RtxEmerald) else null,
                            shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("skin_preview_equip")
                        ) {
                            Text(
                                text = if (isEquipped) "СНЯТЬ СКИН" else "ПРИМЕНИТЬ В БОЮ",
                                fontWeight = FontWeight.Black,
                                fontSize = m.sp(11f)
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(ElevatedGunmetal)
                                .border(1.dp, BorderSteel, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "ВЫПАДАЕТ ИЗ КЕЙСА",
                                fontWeight = FontWeight.Bold,
                                fontSize = m.sp(10.5f),
                                color = TacticalAmber
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        border = BorderStroke(1.dp, BorderSteel),
                        shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("skin_preview_dismiss")
                    ) {
                        Text("ЗАКРЫТЬ", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = m.sp(11f))
                    }
                }
            }
        }
    }
}

@Composable
fun WeaponSilhouetteArt(
    skin: WeaponSkin,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val primary = skin.primaryColor
        val secondary = skin.secondaryColor
        val accent = skin.accentColor
        val outlineColor = if (skin.emissiveIntensity > 0.1f) {
            Color(skin.emissiveArgb).copy(alpha = 0.85f)
        } else {
            accent.copy(alpha = 0.72f)
        }
        val stroke = Stroke(width = (h * 0.03f).coerceIn(1.5f, 3.5f), cap = StrokeCap.Round, join = StrokeJoin.Round)

        when (skin.weapon) {
            WeaponType.KNIFE -> {
                val blade = Path().apply {
                    moveTo(w * 0.36f, h * 0.56f)
                    lineTo(w * 0.88f, h * 0.34f)
                    quadraticTo(w * 0.76f, h * 0.64f, w * 0.38f, h * 0.68f)
                    close()
                }
                drawPath(blade, brush = Brush.linearGradient(listOf(primary, accent)))
                drawPath(blade, color = outlineColor, style = stroke)
                // Handle
                drawRoundRect(
                    color = secondary,
                    topLeft = Offset(w * 0.12f, h * 0.54f),
                    size = Size(w * 0.25f, h * 0.14f),
                    cornerRadius = CornerRadius(h * 0.06f, h * 0.06f)
                )
                drawCircle(color = accent, radius = h * 0.06f, center = Offset(w * 0.14f, h * 0.61f))
            }

            WeaponType.DESERT_EAGLE, WeaponType.GLOCK_18, WeaponType.USP_S -> {
                // Slide
                drawRoundRect(
                    brush = Brush.horizontalGradient(listOf(secondary, primary, accent)),
                    topLeft = Offset(w * 0.22f, h * 0.24f),
                    size = Size(w * 0.52f, h * 0.22f),
                    cornerRadius = CornerRadius(h * 0.04f, h * 0.04f)
                )
                if (skin.weapon == WeaponType.USP_S) {
                    // Suppressor
                    drawRoundRect(
                        color = secondary,
                        topLeft = Offset(w * 0.74f, h * 0.27f),
                        size = Size(w * 0.18f, h * 0.14f),
                        cornerRadius = CornerRadius(h * 0.03f, h * 0.03f)
                    )
                }
                // Frame & Grip
                val grip = Path().apply {
                    moveTo(w * 0.25f, h * 0.46f)
                    lineTo(w * 0.42f, h * 0.46f)
                    lineTo(w * 0.35f, h * 0.82f)
                    lineTo(w * 0.19f, h * 0.80f)
                    close()
                }
                drawPath(grip, color = secondary)
                drawPath(grip, color = outlineColor, style = stroke)
                // Accent barrel stripe
                drawLine(
                    color = accent,
                    start = Offset(w * 0.24f, h * 0.35f),
                    end = Offset(w * 0.72f, h * 0.35f),
                    strokeWidth = (h * 0.04f).coerceAtLeast(2f)
                )
            }

            WeaponType.AWP -> {
                // Long sniper barrel
                drawRect(
                    color = secondary,
                    topLeft = Offset(w * 0.58f, h * 0.37f),
                    size = Size(w * 0.36f, h * 0.08f)
                )
                // Main body + stock
                val body = Path().apply {
                    moveTo(w * 0.06f, h * 0.44f)
                    lineTo(w * 0.62f, h * 0.38f)
                    lineTo(w * 0.62f, h * 0.56f)
                    lineTo(w * 0.24f, h * 0.58f)
                    lineTo(w * 0.08f, h * 0.70f)
                    close()
                }
                drawPath(body, brush = Brush.horizontalGradient(listOf(primary, secondary, primary)))
                drawPath(body, color = outlineColor, style = stroke)
                // Scope
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(w * 0.34f, h * 0.18f),
                    size = Size(w * 0.28f, h * 0.13f),
                    cornerRadius = CornerRadius(h * 0.04f, h * 0.04f)
                )
            }

            WeaponType.MP9, WeaponType.P90 -> {
                // Compact SMG body
                drawRoundRect(
                    brush = Brush.horizontalGradient(listOf(primary, secondary, accent)),
                    topLeft = Offset(w * 0.16f, h * 0.32f),
                    size = Size(w * 0.60f, h * 0.24f),
                    cornerRadius = CornerRadius(h * 0.06f, h * 0.06f)
                )
                drawRect(
                    color = secondary,
                    topLeft = Offset(w * 0.74f, h * 0.38f),
                    size = Size(w * 0.16f, h * 0.09f)
                )
                // Vertical mag + grip
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(w * 0.46f, h * 0.54f),
                    size = Size(w * 0.10f, h * 0.26f),
                    cornerRadius = CornerRadius(3f, 3f)
                )
                drawRoundRect(
                    color = secondary,
                    topLeft = Offset(w * 0.24f, h * 0.54f),
                    size = Size(w * 0.11f, h * 0.22f),
                    cornerRadius = CornerRadius(3f, 3f)
                )
            }

            else -> {
                // AK-47 / M4A1-S Assault rifle profile
                // Stock
                val stock = Path().apply {
                    moveTo(w * 0.06f, h * 0.42f)
                    lineTo(w * 0.28f, h * 0.38f)
                    lineTo(w * 0.28f, h * 0.54f)
                    lineTo(w * 0.07f, h * 0.65f)
                    close()
                }
                drawPath(stock, color = secondary)
                // Receiver + handguard
                drawRoundRect(
                    brush = Brush.horizontalGradient(listOf(primary, accent, secondary)),
                    topLeft = Offset(w * 0.27f, h * 0.35f),
                    size = Size(w * 0.46f, h * 0.19f),
                    cornerRadius = CornerRadius(h * 0.04f, h * 0.04f)
                )
                // Barrel / Suppressor
                drawRect(
                    color = secondary,
                    topLeft = Offset(w * 0.72f, h * 0.39f),
                    size = Size(w * (if (skin.weapon == WeaponType.M4A1_S) 0.23f else 0.19f), h * 0.09f)
                )
                // Curved mag + grip
                val mag = Path().apply {
                    moveTo(w * 0.49f, h * 0.54f)
                    lineTo(w * 0.59f, h * 0.54f)
                    quadraticTo(w * 0.62f, h * 0.70f, w * 0.66f, h * 0.80f)
                    lineTo(w * 0.55f, h * 0.82f)
                    close()
                }
                drawPath(mag, color = accent)
                drawPath(mag, color = outlineColor, style = stroke)
                drawLine(
                    color = accent,
                    start = Offset(w * 0.30f, h * 0.44f),
                    end = Offset(w * 0.70f, h * 0.44f),
                    strokeWidth = (h * 0.04f).coerceAtLeast(2f)
                )
            }
        }
    }
}

