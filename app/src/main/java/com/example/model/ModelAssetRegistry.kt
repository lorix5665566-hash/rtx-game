package com.example.model

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class ModelSlot(
    val titleRu: String,
    val categoryRu: String,
    val bundledAssetPath: String,
    val defaultSourceLabel: String,
    val sketchfabQuery: String
) {
    MAP_MIRAGE(
        titleRu = "Карта Mirage (CS2)",
        categoryRu = "КАРТЫ",
        bundledAssetPath = "models/map_mirage.glb",
        defaultSourceLabel = "Встроенная CS2 Mirage (.GLB)",
        sketchfabQuery = "cs2 mirage"
    ),
    MAP_DUST2(
        titleRu = "Карта Dust 2 (CS2)",
        categoryRu = "КАРТЫ",
        bundledAssetPath = "models/map_dust2.glb",
        defaultSourceLabel = "Встроенная CS2 Dust 2 (.GLB)",
        sketchfabQuery = "cs2 dust2"
    ),
    CHARACTER_T(
        titleRu = "Террорист (T)",
        categoryRu = "ПЕРСОНАЖИ",
        bundledAssetPath = "models/char_terrorist.glb",
        defaultSourceLabel = "Скелетный 3D-боец T (.GLB)",
        sketchfabQuery = "cs2 terrorist"
    ),
    CHARACTER_CT(
        titleRu = "Спецназ (CT)",
        categoryRu = "ПЕРСОНАЖИ",
        bundledAssetPath = "models/char_ct.glb",
        defaultSourceLabel = "Скелетный 3D-боец CT (.GLB)",
        sketchfabQuery = "cs2 counter terrorist"
    ),
    BOMB_C4(
        titleRu = "Бомба C4",
        categoryRu = "СНАРЯЖЕНИЕ",
        bundledAssetPath = "models/bomb_c4.glb",
        defaultSourceLabel = "Встроенная CS2 C4 (.GLB)",
        sketchfabQuery = "cs2 c4"
    ),
    WEAPON_AK47(
        titleRu = "AK-47",
        categoryRu = "ОРУЖИЕ",
        bundledAssetPath = "models/weapon_ak47.glb",
        defaultSourceLabel = "Встроенный AK-47 (.GLB)",
        sketchfabQuery = "cs2 ak-47"
    ),
    WEAPON_M4A1_S(
        titleRu = "M4A1-S",
        categoryRu = "ОРУЖИЕ",
        bundledAssetPath = "models/weapon_m4.glb",
        defaultSourceLabel = "Встроенная M4A1-S (.GLB)",
        sketchfabQuery = "cs2 m4a1"
    ),
    WEAPON_AWP(
        titleRu = "AWP .338",
        categoryRu = "ОРУЖИЕ",
        bundledAssetPath = "models/weapon_awp.glb",
        defaultSourceLabel = "Встроенная AWP (.GLB)",
        sketchfabQuery = "cs2 awp"
    ),
    WEAPON_P90(
        titleRu = "P90 / MP9",
        categoryRu = "ОРУЖИЕ",
        bundledAssetPath = "models/weapon_p90.glb",
        defaultSourceLabel = "Встроенный P90 (.GLB)",
        sketchfabQuery = "cs2 p90"
    ),
    WEAPON_PISTOL(
        titleRu = "Пистолеты (USP / Glock / Deagle)",
        categoryRu = "ОРУЖИЕ",
        bundledAssetPath = "models/weapon_pistol.glb",
        defaultSourceLabel = "Встроенный пистолет (.GLB)",
        sketchfabQuery = "cs2 deagle"
    ),
    WEAPON_KNIFE(
        titleRu = "Боевой нож",
        categoryRu = "ОРУЖИЕ",
        bundledAssetPath = "models/weapon_knife.glb",
        defaultSourceLabel = "Встроенный нож CS2 (.GLB)",
        sketchfabQuery = "cs2 knife"
    );

    companion object {
        fun forWeapon(weapon: WeaponType): ModelSlot = when (weapon) {
            WeaponType.AK47 -> WEAPON_AK47
            WeaponType.M4A1_S -> WEAPON_M4A1_S
            WeaponType.AWP -> WEAPON_AWP
            WeaponType.MP9, WeaponType.P90 -> WEAPON_P90
            WeaponType.USP_S, WeaponType.GLOCK_18, WeaponType.DESERT_EAGLE -> WEAPON_PISTOL
            WeaponType.KNIFE -> WEAPON_KNIFE
            WeaponType.C4_BOMB -> BOMB_C4
        }

        fun forMap(map: ArenaMapId): ModelSlot = when (map) {
            ArenaMapId.MIRAGE -> MAP_MIRAGE
            ArenaMapId.DUST2 -> MAP_DUST2
        }
    }
}

data class SlotAssetInfo(
    val slot: ModelSlot,
    val isCustomImported: Boolean,
    val displayName: String,
    val fileSizeKb: Long
)

/**
 * Manages bundled offline .glb assets inside APK + user-imported Sketchfab .glb models
 * via zero-permission Android SAF (ActivityResultContracts.OpenDocument).
 */
class ModelAssetRegistry(private val context: Context) {
    private val prefs = context.getSharedPreferences("vanguard_glb_registry", Context.MODE_PRIVATE)
    private val customDir = File(context.filesDir, "custom_models").apply { mkdirs() }

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _slotStates = MutableStateFlow(buildSlotSnapshot())
    val slotStates: StateFlow<List<SlotAssetInfo>> = _slotStates.asStateFlow()

    fun hasCustomFile(slot: ModelSlot): Boolean {
        val f = customFileFor(slot)
        return f.exists() && f.length() > 32L
    }

    /**
     * Reads the active .glb model bytes into a direct ByteBuffer ready for Google Filament gltfio.
     */
    fun loadGlbBuffer(slot: ModelSlot): ByteBuffer? {
        val customFile = customFileFor(slot)
        if (customFile.exists() && customFile.length() > 32L) {
            runCatching {
                val bytes = customFile.readBytes()
                if (isValidGlbHeader(bytes)) {
                    return ByteBuffer.allocateDirect(bytes.size).apply {
                        order(ByteOrder.nativeOrder())
                        put(bytes)
                        flip()
                    }
                }
            }
        }
        return loadBundledAssetBuffer(slot.bundledAssetPath)
    }

    fun loadBundledAssetBuffer(assetPath: String): ByteBuffer? {
        return runCatching {
            context.assets.open(assetPath).use { input ->
                val bytes = input.readBytes()
                ByteBuffer.allocateDirect(bytes.size).apply {
                    order(ByteOrder.nativeOrder())
                    put(bytes)
                    flip()
                }
            }
        }.getOrNull()
    }

    /**
     * Imports a user-selected .glb file (e.g. downloaded from sketchfab.com/tags/cs2) into [slot].
     * Validates binary glTF 2.0 magic header before replacing.
     */
    fun importGlbFromUri(slot: ModelSlot, uri: Uri, friendlyName: String?): Result<String> {
        return runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Не удалось открыть выбранный файл")

            if (!isValidGlbHeader(bytes)) {
                error("Файл не является корректной бинарной 3D-моделью .GLB (glTF 2.0). Скачайте с Sketchfab формат GLB.")
            }
            if (bytes.size > 140 * 1024 * 1024) {
                error("Файл слишком большой (>140 МБ). Выберите оптимизированную .GLB модель.")
            }

            val dest = customFileFor(slot)
            val tmp = File(customDir, "${slot.name.lowercase()}.tmp")
            tmp.writeBytes(bytes)
            if (dest.exists()) dest.delete()
            tmp.renameTo(dest)

            val label = (friendlyName ?: "Sketchfab_${slot.name}.glb").take(42)
            prefs.edit().putString("name_${slot.name}", label).apply()
            refreshState()
            "Модель «$label» (${bytes.size / 1024} КБ) подключена к слоту «${slot.titleRu}»!"
        }
    }

    fun resetSlotToBundled(slot: ModelSlot) {
        val f = customFileFor(slot)
        if (f.exists()) f.delete()
        prefs.edit().remove("name_${slot.name}").apply()
        refreshState()
    }

    fun resetAllToBundled() {
        ModelSlot.entries.forEach { slot ->
            val f = customFileFor(slot)
            if (f.exists()) f.delete()
            prefs.edit().remove("name_${slot.name}").apply()
        }
        refreshState()
    }

    private fun customFileFor(slot: ModelSlot): File =
        File(customDir, "${slot.name.lowercase()}.glb")

    private fun isValidGlbHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 20) return false
        val bb = ByteBuffer.wrap(bytes, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
        val magic = bb.int
        val version = bb.int
        return magic == GLB_MAGIC && version == 2
    }

    private fun refreshState() {
        _slotStates.value = buildSlotSnapshot()
        _revision.value = _revision.value + 1
    }

    private fun buildSlotSnapshot(): List<SlotAssetInfo> {
        return ModelSlot.entries.map { slot ->
            val f = customFileFor(slot)
            if (f.exists() && f.length() > 32L) {
                val label = prefs.getString("name_${slot.name}", f.name) ?: f.name
                SlotAssetInfo(
                    slot = slot,
                    isCustomImported = true,
                    displayName = "Sketchfab / Свой: $label",
                    fileSizeKb = f.length() / 1024L
                )
            } else {
                val bundledSize = runCatching {
                    context.assets.openFd(slot.bundledAssetPath).use { it.length / 1024L }
                }.getOrDefault(0L)
                SlotAssetInfo(
                    slot = slot,
                    isCustomImported = false,
                    displayName = slot.defaultSourceLabel,
                    fileSizeKb = bundledSize
                )
            }
        }
    }

    companion object {
        private const val GLB_MAGIC = 0x46546C67 // "glTF"
        const val SKETCHFAB_CS2_URL = "https://sketchfab.com/tags/cs2"
    }
}
