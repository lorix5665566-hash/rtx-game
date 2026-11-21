package com.example.model

enum class GraphicsPreset(
    val level: Int,
    val titleRu: String,
    val subtitleRu: String
) {
    LOW(0, "НИЗКИЕ", "Максимум FPS • Быстрый Filament PBR"),
    MEDIUM(1, "СРЕДНИЕ", "Баланс • Filament PBR + FXAA"),
    HIGH(2, "ВЫСОКИЕ", "Тени 2K • Bloom • ACES HDR"),
    ULTRA_RTX(3, "УЛЬТРА RTX", "Максимум PBR • Тени • SSAO • IBL-отражения")
}

enum class TeamSide(val shortRu: String, val titleRu: String) {
    CT("СПЕЦНАЗ", "Спецназ (CT)"),
    T("ТЕРРОРИСТЫ", "Террористы (T)")
}

enum class GameMode(
    val titleRu: String,
    val subtitleRu: String
) {
    BOMB_DEFUSAL("ЗАКЛАДКА БОМБЫ", "Классический CS2 • Установка, взрыв и разминирование C4"),
    TEAM_DEATHMATCH("БОЙ НАСМЕРТЬ", "Мгновенное возрождение • Бесплатный магазин")
}

enum class ArenaMapId(
    val titleRu: String,
    val subtitleRu: String,
    val envTagRu: String,
    val floorsAssetPath: String,
    val radarAssetPath: String
) {
    MIRAGE(
        titleRu = "MIRAGE",
        subtitleRu = "Оригинальная 3D-карта CS2 Mirage (.GLB)",
        envTagRu = "CS2 • .GLB",
        floorsAssetPath = "models/mirage-floors.json",
        radarAssetPath = "models/mirage-radar.png"
    ),
    DUST2(
        titleRu = "DUST 2",
        subtitleRu = "Оригинальная 3D-карта CS2 Dust 2 (.GLB)",
        envTagRu = "CS2 • .GLB",
        floorsAssetPath = "models/dust2-floors.json",
        radarAssetPath = "models/dust2-radar.png"
    )
}

data class MatchConfig(
    val mode: GameMode = GameMode.BOMB_DEFUSAL,
    val map: ArenaMapId = ArenaMapId.MIRAGE,
    val playerSide: TeamSide = TeamSide.T,
    val playerTeamSize: Int = 5, // 1..5 (includes player)
    val enemyTeamSize: Int = 5,  // 1..5
    val roundsToWin: Int = 8
)

enum class WeaponSlot {
    PRIMARY,
    PISTOL,
    KNIFE,
    BOMB
}

enum class WeaponCategory(val titleRu: String) {
    PISTOLS("ПИСТОЛЕТЫ"),
    SMGS("ПП"),
    RIFLES("ВИНТОВКИ")
}

enum class WeaponType(
    val slot: WeaponSlot,
    val category: WeaponCategory?,
    val shortLabel: String,
    val titleRu: String,
    val price: Int,
    val magSize: Int,
    val initialReserve: Int,
    val fireIntervalSec: Float,
    val reloadSec: Float,
    val bodyDamage: Float,
    val headDamage: Float,
    val hipSpread: Float,
    val adsSpread: Float,
    val recoilKick: Float,
    val recoilPitch: Float,
    val adsZoomFactor: Float,
    val moveSpeedMult: Float,
    val isAutomatic: Boolean,
    val hasSniperScope: Boolean,
    val isSuppressed: Boolean = false,
    val killReward: Int = 300,
    val equipDurationSec: Float = 0.70f,
    val muzzleOffsetZ: Float = -0.50f
) {
    KNIFE(
        slot = WeaponSlot.KNIFE,
        category = null,
        shortLabel = "НОЖ",
        titleRu = "Боевой нож",
        price = 0,
        magSize = 0,
        initialReserve = 0,
        fireIntervalSec = 0.42f,
        reloadSec = 0f,
        bodyDamage = 55f,
        headDamage = 110f,
        hipSpread = 0f,
        adsSpread = 0f,
        recoilKick = 0.2f,
        recoilPitch = 0f,
        adsZoomFactor = 1.0f,
        moveSpeedMult = 1.15f,
        isAutomatic = false,
        hasSniperScope = false,
        killReward = 1500,
        equipDurationSec = 0.45f,
        muzzleOffsetZ = -0.22f
    ),
    C4_BOMB(
        slot = WeaponSlot.BOMB,
        category = null,
        shortLabel = "C4",
        titleRu = "Бомба C4",
        price = 0,
        magSize = 0,
        initialReserve = 0,
        fireIntervalSec = 0.5f,
        reloadSec = 0f,
        bodyDamage = 0f,
        headDamage = 0f,
        hipSpread = 0f,
        adsSpread = 0f,
        recoilKick = 0f,
        recoilPitch = 0f,
        adsZoomFactor = 1.0f,
        moveSpeedMult = 1.10f,
        isAutomatic = false,
        hasSniperScope = false,
        killReward = 0,
        equipDurationSec = 0.65f,
        muzzleOffsetZ = -0.18f
    ),
    USP_S(
        slot = WeaponSlot.PISTOL,
        category = WeaponCategory.PISTOLS,
        shortLabel = "USP-S",
        titleRu = "USP-S",
        price = 200,
        magSize = 12,
        initialReserve = 24,
        fireIntervalSec = 0.17f,
        reloadSec = 1.45f,
        bodyDamage = 30f,
        headDamage = 102f,
        hipSpread = 0.009f,
        adsSpread = 0.002f,
        recoilKick = 0.28f,
        recoilPitch = 0.009f,
        adsZoomFactor = 0.80f,
        moveSpeedMult = 1.06f,
        isAutomatic = false,
        hasSniperScope = false,
        isSuppressed = true,
        equipDurationSec = 0.60f,
        muzzleOffsetZ = -0.34f
    ),
    GLOCK_18(
        slot = WeaponSlot.PISTOL,
        category = WeaponCategory.PISTOLS,
        shortLabel = "GLOCK-18",
        titleRu = "Glock-18",
        price = 200,
        magSize = 20,
        initialReserve = 120,
        fireIntervalSec = 0.145f,
        reloadSec = 1.45f,
        bodyDamage = 24f,
        headDamage = 92f,
        hipSpread = 0.011f,
        adsSpread = 0.003f,
        recoilKick = 0.26f,
        recoilPitch = 0.008f,
        adsZoomFactor = 0.82f,
        moveSpeedMult = 1.06f,
        isAutomatic = false,
        hasSniperScope = false,
        equipDurationSec = 0.60f,
        muzzleOffsetZ = -0.17f
    ),
    DESERT_EAGLE(
        slot = WeaponSlot.PISTOL,
        category = WeaponCategory.PISTOLS,
        shortLabel = "DEAGLE",
        titleRu = "Desert Eagle",
        price = 700,
        magSize = 7,
        initialReserve = 35,
        fireIntervalSec = 0.26f,
        reloadSec = 1.65f,
        bodyDamage = 58f,
        headDamage = 180f,
        hipSpread = 0.014f,
        adsSpread = 0.0018f,
        recoilKick = 0.92f,
        recoilPitch = 0.028f,
        adsZoomFactor = 0.78f,
        moveSpeedMult = 1.02f,
        isAutomatic = false,
        hasSniperScope = false,
        equipDurationSec = 0.62f,
        muzzleOffsetZ = -0.25f
    ),
    MP9(
        slot = WeaponSlot.PRIMARY,
        category = WeaponCategory.SMGS,
        shortLabel = "MP9",
        titleRu = "MP9",
        price = 1250,
        magSize = 30,
        initialReserve = 120,
        fireIntervalSec = 0.075f,
        reloadSec = 1.50f,
        bodyDamage = 23f,
        headDamage = 64f,
        hipSpread = 0.016f,
        adsSpread = 0.006f,
        recoilKick = 0.30f,
        recoilPitch = 0.008f,
        adsZoomFactor = 0.74f,
        moveSpeedMult = 1.04f,
        isAutomatic = true,
        hasSniperScope = false,
        killReward = 600,
        equipDurationSec = 0.70f,
        muzzleOffsetZ = -0.25f
    ),
    P90(
        slot = WeaponSlot.PRIMARY,
        category = WeaponCategory.SMGS,
        shortLabel = "P90",
        titleRu = "P90",
        price = 2350,
        magSize = 50,
        initialReserve = 100,
        fireIntervalSec = 0.072f,
        reloadSec = 1.95f,
        bodyDamage = 25f,
        headDamage = 70f,
        hipSpread = 0.015f,
        adsSpread = 0.005f,
        recoilKick = 0.32f,
        recoilPitch = 0.0085f,
        adsZoomFactor = 0.72f,
        moveSpeedMult = 1.02f,
        isAutomatic = true,
        hasSniperScope = false,
        killReward = 300,
        equipDurationSec = 0.72f,
        muzzleOffsetZ = -0.29f
    ),
    M4A1_S(
        slot = WeaponSlot.PRIMARY,
        category = WeaponCategory.RIFLES,
        shortLabel = "M4A1-S",
        titleRu = "M4A1-S",
        price = 2900,
        magSize = 25,
        initialReserve = 100,
        fireIntervalSec = 0.100f,
        reloadSec = 1.60f,
        bodyDamage = 35f,
        headDamage = 96f,
        hipSpread = 0.011f,
        adsSpread = 0.0022f,
        recoilKick = 0.38f,
        recoilPitch = 0.010f,
        adsZoomFactor = 0.65f,
        moveSpeedMult = 0.96f,
        isAutomatic = true,
        hasSniperScope = false,
        isSuppressed = true,
        equipDurationSec = 0.85f,
        muzzleOffsetZ = -0.70f
    ),
    AK47(
        slot = WeaponSlot.PRIMARY,
        category = WeaponCategory.RIFLES,
        shortLabel = "AK-47",
        titleRu = "AK-47",
        price = 2700,
        magSize = 30,
        initialReserve = 90,
        fireIntervalSec = 0.105f,
        reloadSec = 1.75f,
        bodyDamage = 39f,
        headDamage = 128f,
        hipSpread = 0.015f,
        adsSpread = 0.0032f,
        recoilKick = 0.62f,
        recoilPitch = 0.016f,
        adsZoomFactor = 0.68f,
        moveSpeedMult = 0.94f,
        isAutomatic = true,
        hasSniperScope = false,
        equipDurationSec = 0.85f,
        muzzleOffsetZ = -0.62f
    ),
    AWP(
        slot = WeaponSlot.PRIMARY,
        category = WeaponCategory.RIFLES,
        shortLabel = "AWP",
        titleRu = "AWP .338",
        price = 4750,
        magSize = 5,
        initialReserve = 30,
        fireIntervalSec = 1.25f,
        reloadSec = 2.30f,
        bodyDamage = 115f,
        headDamage = 320f,
        hipSpread = 0.050f,
        adsSpread = 0.0003f,
        recoilKick = 1.25f,
        recoilPitch = 0.038f,
        adsZoomFactor = 0.24f,
        moveSpeedMult = 0.86f,
        isAutomatic = false,
        hasSniperScope = true,
        killReward = 100,
        equipDurationSec = 0.95f,
        muzzleOffsetZ = -0.85f
    )
}

data class GraphicsSettings(
    val preset: GraphicsPreset = GraphicsPreset.ULTRA_RTX,
    val resolutionScale: Float = 1.0f,
    val rtxReflections: Boolean = true,
    val dynamicLighting: Boolean = true,
    val hdTextures: Boolean = true,
    val bloomAndAces: Boolean = true,
    val fov: Float = 80f,
    val showFps: Boolean = true
) {
    companion object {
        fun forPreset(preset: GraphicsPreset): GraphicsSettings = when (preset) {
            GraphicsPreset.LOW -> GraphicsSettings(
                preset = preset,
                resolutionScale = 0.65f,
                rtxReflections = false,
                dynamicLighting = false,
                hdTextures = false,
                bloomAndAces = false,
                fov = 76f,
                showFps = true
            )
            GraphicsPreset.MEDIUM -> GraphicsSettings(
                preset = preset,
                resolutionScale = 0.80f,
                rtxReflections = false,
                dynamicLighting = true,
                hdTextures = true,
                bloomAndAces = false,
                fov = 78f,
                showFps = true
            )
            GraphicsPreset.HIGH -> GraphicsSettings(
                preset = preset,
                resolutionScale = 0.90f,
                rtxReflections = true,
                dynamicLighting = true,
                hdTextures = true,
                bloomAndAces = true,
                fov = 80f,
                showFps = true
            )
            GraphicsPreset.ULTRA_RTX -> GraphicsSettings(
                preset = preset,
                resolutionScale = 1.0f,
                rtxReflections = true,
                dynamicLighting = true,
                hdTextures = true,
                bloomAndAces = true,
                fov = 80f,
                showFps = true
            )
        }
    }
}

// Pure manual aiming — aim assist completely removed!
data class SensitivitySettings(
    val lookSensitivity: Float = 1.0f,
    val adsSensitivity: Float = 0.56f,
    val xAxisMultiplier: Float = 1.0f,
    val yAxisMultiplier: Float = 0.85f,
    val invertY: Boolean = false
)

data class AudioSettings(
    val sfxVolume: Float = 0.92f,
    val hapticsEnabled: Boolean = true
)

enum class ControlWidgetId(val labelRu: String, val canHide: Boolean, val isWide: Boolean = false) {
    JOYSTICK("Джойстик", false),
    FIRE_RIGHT("Огонь (прав.)", false),
    FIRE_LEFT("Огонь (лев.)", true),
    ADS("Прицел", false),
    RELOAD("Перезарядка", false),
    CROUCH("Присед", true),
    JUMP("Прыжок", true),
    WEAPON_SWIPE("Оружие (свайп)", false, isWide = true),
    SHOP("Магазин", false),
    PAUSE("Пауза", false)
}

data class ControlWidgetConfig(
    val id: ControlWidgetId,
    val xFraction: Float,
    val yFraction: Float,
    val scale: Float = 1.0f,
    val opacity: Float = 0.88f,
    val visible: Boolean = true
)

data class ControlLayout(
    val widgets: Map<ControlWidgetId, ControlWidgetConfig> = defaultTwoFingerMap()
) {
    fun get(id: ControlWidgetId): ControlWidgetConfig =
        widgets[id] ?: defaultTwoFingerMap().getValue(id)

    fun updateWidget(config: ControlWidgetConfig): ControlLayout =
        copy(widgets = widgets + (config.id to config))

    companion object {
        fun defaultTwoFingerMap(): Map<ControlWidgetId, ControlWidgetConfig> = mapOf(
            ControlWidgetId.JOYSTICK to ControlWidgetConfig(ControlWidgetId.JOYSTICK, 0.13f, 0.72f, 1.0f, 0.80f, true),
            ControlWidgetId.FIRE_RIGHT to ControlWidgetConfig(ControlWidgetId.FIRE_RIGHT, 0.87f, 0.68f, 1.08f, 0.92f, true),
            ControlWidgetId.FIRE_LEFT to ControlWidgetConfig(ControlWidgetId.FIRE_LEFT, 0.12f, 0.32f, 0.90f, 0.80f, false),
            ControlWidgetId.ADS to ControlWidgetConfig(ControlWidgetId.ADS, 0.92f, 0.44f, 0.96f, 0.86f, true),
            ControlWidgetId.RELOAD to ControlWidgetConfig(ControlWidgetId.RELOAD, 0.76f, 0.78f, 0.92f, 0.86f, true),
            ControlWidgetId.CROUCH to ControlWidgetConfig(ControlWidgetId.CROUCH, 0.76f, 0.56f, 0.88f, 0.82f, true),
            ControlWidgetId.JUMP to ControlWidgetConfig(ControlWidgetId.JUMP, 0.84f, 0.42f, 0.90f, 0.84f, true),
            ControlWidgetId.WEAPON_SWIPE to ControlWidgetConfig(ControlWidgetId.WEAPON_SWIPE, 0.86f, 0.88f, 1.0f, 0.92f, true),
            ControlWidgetId.SHOP to ControlWidgetConfig(ControlWidgetId.SHOP, 0.90f, 0.14f, 0.95f, 0.92f, true),
            ControlWidgetId.PAUSE to ControlWidgetConfig(ControlWidgetId.PAUSE, 0.96f, 0.09f, 0.86f, 0.86f, true)
        )

        fun fourFingerClawMap(): Map<ControlWidgetId, ControlWidgetConfig> = mapOf(
            ControlWidgetId.JOYSTICK to ControlWidgetConfig(ControlWidgetId.JOYSTICK, 0.13f, 0.74f, 1.0f, 0.78f, true),
            ControlWidgetId.FIRE_RIGHT to ControlWidgetConfig(ControlWidgetId.FIRE_RIGHT, 0.87f, 0.70f, 0.92f, 0.78f, true),
            ControlWidgetId.FIRE_LEFT to ControlWidgetConfig(ControlWidgetId.FIRE_LEFT, 0.13f, 0.22f, 1.15f, 0.92f, true),
            ControlWidgetId.ADS to ControlWidgetConfig(ControlWidgetId.ADS, 0.86f, 0.22f, 1.10f, 0.92f, true),
            ControlWidgetId.RELOAD to ControlWidgetConfig(ControlWidgetId.RELOAD, 0.76f, 0.72f, 0.92f, 0.85f, true),
            ControlWidgetId.CROUCH to ControlWidgetConfig(ControlWidgetId.CROUCH, 0.76f, 0.34f, 0.92f, 0.84f, true),
            ControlWidgetId.JUMP to ControlWidgetConfig(ControlWidgetId.JUMP, 0.88f, 0.44f, 0.94f, 0.84f, true),
            ControlWidgetId.WEAPON_SWIPE to ControlWidgetConfig(ControlWidgetId.WEAPON_SWIPE, 0.86f, 0.88f, 1.0f, 0.92f, true),
            ControlWidgetId.SHOP to ControlWidgetConfig(ControlWidgetId.SHOP, 0.74f, 0.12f, 0.92f, 0.90f, true),
            ControlWidgetId.PAUSE to ControlWidgetConfig(ControlWidgetId.PAUSE, 0.96f, 0.09f, 0.86f, 0.85f, true)
        )

        fun defaultTwoFinger(): ControlLayout = ControlLayout(defaultTwoFingerMap())
        fun fourFingerClaw(): ControlLayout = ControlLayout(fourFingerClawMap())
    }
}

data class GameSettings(
    val graphics: GraphicsSettings = GraphicsSettings(),
    val sensitivity: SensitivitySettings = SensitivitySettings(),
    val audio: AudioSettings = AudioSettings(),
    val controlLayout: ControlLayout = ControlLayout.defaultTwoFinger()
)

data class PlayerStats(
    val bestScore: Int = 0,
    val totalKills: Int = 0,
    val totalHeadshots: Int = 0,
    val gamesPlayed: Int = 0
)

enum class BombState {
    CARRIED,
    PLANTED,
    DEFUSED,
    EXPLODED
}

data class RadarBlip(
    val relX: Float,
    val relZ: Float,
    val isAlly: Boolean,
    val isBomb: Boolean = false
)

data class HudSnapshot(
    val mapId: ArenaMapId = ArenaMapId.MIRAGE,
    val hp: Int = 100,
    val maxHp: Int = 100,
    val armor: Int = 0,
    val hasDefuseKit: Boolean = false,
    val money: Int = 800,
    val mode: GameMode = GameMode.BOMB_DEFUSAL,
    val playerSide: TeamSide = TeamSide.T,
    val activeWeapon: WeaponType = WeaponType.GLOCK_18,
    val primaryWeapon: WeaponType? = null,
    val pistolWeapon: WeaponType = WeaponType.GLOCK_18,
    val hasBomb: Boolean = true,
    val ammo: Int = 20,
    val magSize: Int = 20,
    val reserveAmmo: Int = 120,
    val isReloading: Boolean = false,
    val reloadProgress: Float = 0f,
    val isEquipping: Boolean = false,
    val equipProgress: Float = 1f,
    val isAds: Boolean = false,
    val adsBlend: Float = 0f,
    val isCrouching: Boolean = false,
    val isPlayerDead: Boolean = false,
    val ctScore: Int = 0,
    val tScore: Int = 0,
    val roundsToWin: Int = 8,
    val roundNumber: Int = 1,
    val roundTimeSec: Float = 105f,
    val isWarmup: Boolean = false,
    val warmupTimeSec: Float = 0f,
    val ctAlive: Int = 5,
    val tAlive: Int = 5,
    val bombState: BombState = BombState.CARRIED,
    val bombSiteLabel: String = "",
    val bombCountdownSec: Float = 35f,
    val inPlantZoneLabel: String = "",
    val canPlantBomb: Boolean = false,
    val canDefuseBomb: Boolean = false,
    val bombActionProgress: Float = 0f,
    val explosionFlashAlpha: Float = 0f,
    val canBuy: Boolean = true,
    val calloutName: String = "",
    val score: Int = 0,
    val kills: Int = 0,
    val deaths: Int = 0,
    val headshots: Int = 0,
    val shotsFired: Int = 0,
    val shotsHit: Int = 0,
    val fps: Int = 60,
    val hitMarkerAlpha: Float = 0f,
    val isHeadshotHit: Boolean = false,
    val damageFlashAlpha: Float = 0f,
    val crosshairSpread: Float = 0f,
    val isMatchFinished: Boolean = false,
    val isPlayerTeamWinner: Boolean = false,
    val bannerText: String = "",
    val killFeedText: String = "",
    val radarBlips: List<RadarBlip> = emptyList(),
    val playerYawDeg: Float = 0f
) {
    val accuracyPercent: Int
        get() = if (shotsFired <= 0) 0 else ((shotsHit * 100f) / shotsFired).toInt().coerceIn(0, 100)
}
