package com.example.model

enum class GraphicsPreset(
    val level: Int,
    val titleRu: String,
    val subtitleRu: String
) {
    LOW(0, "НИЗКИЕ", "Максимум FPS • Упрощённый свет"),
    MEDIUM(1, "СРЕДНИЕ", "Баланс • Базовый PBR"),
    HIGH(2, "ВЫСОКИЕ", "Полный PBR • HD-текстуры"),
    ULTRA_RTX(3, "УЛЬТРА RTX", "Трассировка отражений • Полный RTX")
}

enum class GameMode(
    val titleRu: String,
    val subtitleRu: String
) {
    ASSAULT("ШТУРМ", "5 волн спецназа • Тактический захват"),
    SURVIVAL("ВЫЖИВАНИЕ", "Бесконечные волны • Рекорд очков"),
    RANGE("ПОЛИГОН", "Стрельбище • Отработка хедшотов")
}

data class GraphicsSettings(
    val preset: GraphicsPreset = GraphicsPreset.ULTRA_RTX,
    val resolutionScale: Float = 1.0f,
    val rtxReflections: Boolean = true,
    val dynamicLighting: Boolean = true,
    val hdTextures: Boolean = true,
    val bloomAndAces: Boolean = true,
    val fov: Float = 78f,
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
                fov = 75f,
                showFps = true
            )
            GraphicsPreset.MEDIUM -> GraphicsSettings(
                preset = preset,
                resolutionScale = 0.80f,
                rtxReflections = false,
                dynamicLighting = true,
                hdTextures = true,
                bloomAndAces = false,
                fov = 76f,
                showFps = true
            )
            GraphicsPreset.HIGH -> GraphicsSettings(
                preset = preset,
                resolutionScale = 0.90f,
                rtxReflections = true,
                dynamicLighting = true,
                hdTextures = true,
                bloomAndAces = true,
                fov = 78f,
                showFps = true
            )
            GraphicsPreset.ULTRA_RTX -> GraphicsSettings(
                preset = preset,
                resolutionScale = 1.0f,
                rtxReflections = true,
                dynamicLighting = true,
                hdTextures = true,
                bloomAndAces = true,
                fov = 78f,
                showFps = true
            )
        }
    }
}

data class SensitivitySettings(
    val lookSensitivity: Float = 1.0f,
    val adsSensitivity: Float = 0.62f,
    val xAxisMultiplier: Float = 1.0f,
    val yAxisMultiplier: Float = 0.85f,
    val invertY: Boolean = false,
    val aimAssist: Boolean = true
)

data class AudioSettings(
    val sfxVolume: Float = 0.85f,
    val hapticsEnabled: Boolean = true
)

enum class ControlWidgetId(val labelRu: String, val canHide: Boolean) {
    JOYSTICK("Джойстик", false),
    FIRE_RIGHT("Огонь (прав.)", false),
    FIRE_LEFT("Огонь (лев.)", true),
    ADS("Прицел", false),
    RELOAD("Перезарядка", false),
    CROUCH("Присед", true),
    JUMP("Прыжок", true),
    PAUSE("Пауза", false)
}

data class ControlWidgetConfig(
    val id: ControlWidgetId,
    val xFraction: Float,
    val yFraction: Float,
    val scale: Float = 1.0f,
    val opacity: Float = 0.85f,
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
            ControlWidgetId.JOYSTICK to ControlWidgetConfig(ControlWidgetId.JOYSTICK, 0.16f, 0.78f, 1.0f, 0.82f, true),
            ControlWidgetId.FIRE_RIGHT to ControlWidgetConfig(ControlWidgetId.FIRE_RIGHT, 0.85f, 0.76f, 1.1f, 0.92f, true),
            ControlWidgetId.FIRE_LEFT to ControlWidgetConfig(ControlWidgetId.FIRE_LEFT, 0.14f, 0.44f, 0.9f, 0.78f, false),
            ControlWidgetId.ADS to ControlWidgetConfig(ControlWidgetId.ADS, 0.88f, 0.56f, 1.0f, 0.85f, true),
            ControlWidgetId.RELOAD to ControlWidgetConfig(ControlWidgetId.RELOAD, 0.70f, 0.84f, 0.95f, 0.85f, true),
            ControlWidgetId.CROUCH to ControlWidgetConfig(ControlWidgetId.CROUCH, 0.62f, 0.72f, 0.90f, 0.80f, true),
            ControlWidgetId.JUMP to ControlWidgetConfig(ControlWidgetId.JUMP, 0.74f, 0.64f, 0.92f, 0.82f, true),
            ControlWidgetId.PAUSE to ControlWidgetConfig(ControlWidgetId.PAUSE, 0.92f, 0.08f, 0.95f, 0.88f, true)
        )

        fun fourFingerClawMap(): Map<ControlWidgetId, ControlWidgetConfig> = mapOf(
            ControlWidgetId.JOYSTICK to ControlWidgetConfig(ControlWidgetId.JOYSTICK, 0.16f, 0.78f, 1.0f, 0.80f, true),
            ControlWidgetId.FIRE_RIGHT to ControlWidgetConfig(ControlWidgetId.FIRE_RIGHT, 0.85f, 0.78f, 0.95f, 0.80f, true),
            ControlWidgetId.FIRE_LEFT to ControlWidgetConfig(ControlWidgetId.FIRE_LEFT, 0.14f, 0.24f, 1.15f, 0.92f, true),
            ControlWidgetId.ADS to ControlWidgetConfig(ControlWidgetId.ADS, 0.86f, 0.24f, 1.1f, 0.92f, true),
            ControlWidgetId.RELOAD to ControlWidgetConfig(ControlWidgetId.RELOAD, 0.70f, 0.85f, 0.95f, 0.85f, true),
            ControlWidgetId.CROUCH to ControlWidgetConfig(ControlWidgetId.CROUCH, 0.72f, 0.36f, 0.95f, 0.82f, true),
            ControlWidgetId.JUMP to ControlWidgetConfig(ControlWidgetId.JUMP, 0.86f, 0.42f, 0.95f, 0.82f, true),
            ControlWidgetId.PAUSE to ControlWidgetConfig(ControlWidgetId.PAUSE, 0.50f, 0.07f, 0.90f, 0.85f, true)
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

data class RadarBlip(
    val relX: Float,
    val relZ: Float,
    val isElite: Boolean
)

data class HudSnapshot(
    val hp: Int = 100,
    val maxHp: Int = 100,
    val armor: Int = 100,
    val ammo: Int = 30,
    val magSize: Int = 30,
    val reserveAmmo: Int = 150,
    val isReloading: Boolean = false,
    val reloadProgress: Float = 0f,
    val isAds: Boolean = false,
    val isCrouching: Boolean = false,
    val score: Int = 0,
    val wave: Int = 1,
    val totalWaves: Int = 5,
    val kills: Int = 0,
    val headshots: Int = 0,
    val shotsFired: Int = 0,
    val shotsHit: Int = 0,
    val enemiesAlive: Int = 0,
    val fps: Int = 60,
    val hitMarkerAlpha: Float = 0f,
    val isHeadshotHit: Boolean = false,
    val damageFlashAlpha: Float = 0f,
    val crosshairSpread: Float = 0f,
    val isGameOver: Boolean = false,
    val isVictory: Boolean = false,
    val bannerText: String = "",
    val radarBlips: List<RadarBlip> = emptyList(),
    val playerYawDeg: Float = 0f
) {
    val accuracyPercent: Int
        get() = if (shotsFired <= 0) 0 else ((shotsHit * 100f) / shotsFired).toInt().coerceIn(0, 100)
}
