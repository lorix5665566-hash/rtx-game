package com.example.model

import android.content.Context
import org.json.JSONObject

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadSettings(): GameSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return GameSettings()
        return runCatching {
            val root = JSONObject(raw)
            val gfxObj = root.optJSONObject("graphics")
            val presetName = gfxObj?.optString("preset", GraphicsPreset.ULTRA_RTX.name)
                ?: GraphicsPreset.ULTRA_RTX.name
            val preset = GraphicsPreset.entries.firstOrNull { it.name == presetName }
                ?: GraphicsPreset.ULTRA_RTX
            val aspectName = gfxObj?.optString("aspectRatio", AspectRatioMode.RATIO_16_9.name)
                ?: AspectRatioMode.RATIO_16_9.name
            val aspectRatio = AspectRatioMode.entries.firstOrNull { it.name == aspectName }
                ?: AspectRatioMode.RATIO_16_9
            val targetFpsName = gfxObj?.optString("targetFps", TargetFpsMode.FPS_120.name)
                ?: TargetFpsMode.FPS_120.name
            val targetFps = TargetFpsMode.entries.firstOrNull { it.name == targetFpsName }
                ?: TargetFpsMode.FPS_120
            val graphics = GraphicsSettings(
                preset = preset,
                resolutionScale = (gfxObj?.optDouble("resolutionScale", 1.0) ?: 1.0).toFloat()
                    .coerceIn(0.6f, 1.0f),
                aspectRatio = aspectRatio,
                targetFps = targetFps,
                rtxReflections = gfxObj?.optBoolean("rtxReflections", true) ?: true,
                dynamicLighting = gfxObj?.optBoolean("dynamicLighting", true) ?: true,
                hdTextures = gfxObj?.optBoolean("hdTextures", true) ?: true,
                bloomAndAces = gfxObj?.optBoolean("bloomAndAces", true) ?: true,
                fov = GraphicsSettings.FIXED_FOV,
                showFps = gfxObj?.optBoolean("showFps", true) ?: true
            )

            val sensObj = root.optJSONObject("sensitivity")
            val sensitivity = SensitivitySettings(
                lookSensitivity = (sensObj?.optDouble("lookSensitivity", 1.0) ?: 1.0).toFloat()
                    .coerceIn(0.2f, 3.0f),
                adsSensitivity = (sensObj?.optDouble("adsSensitivity", 0.56) ?: 0.56).toFloat()
                    .coerceIn(0.2f, 2.0f),
                xAxisMultiplier = (sensObj?.optDouble("xAxisMultiplier", 1.0) ?: 1.0).toFloat()
                    .coerceIn(0.5f, 2.0f),
                yAxisMultiplier = (sensObj?.optDouble("yAxisMultiplier", 0.85) ?: 0.85).toFloat()
                    .coerceIn(0.5f, 2.0f),
                invertY = sensObj?.optBoolean("invertY", false) ?: false
            )

            val audioObj = root.optJSONObject("audio")
            val audio = AudioSettings(
                sfxVolume = (audioObj?.optDouble("sfxVolume", 0.92) ?: 0.92).toFloat()
                    .coerceIn(0f, 1f),
                hapticsEnabled = audioObj?.optBoolean("hapticsEnabled", true) ?: true
            )

            val defaults = ControlLayout.defaultTwoFingerMap()
            val layoutObj = root.optJSONObject("controlLayoutV2")
            val widgets = defaults.toMutableMap()
            if (layoutObj != null) {
                for (id in ControlWidgetId.entries) {
                    val item = layoutObj.optJSONObject(id.name) ?: continue
                    val def = defaults.getValue(id)
                    widgets[id] = ControlWidgetConfig(
                        id = id,
                        xFraction = item.optDouble("x", def.xFraction.toDouble()).toFloat()
                            .coerceIn(0.05f, 0.95f),
                        yFraction = item.optDouble("y", def.yFraction.toDouble()).toFloat()
                            .coerceIn(0.05f, 0.95f),
                        scale = item.optDouble("scale", def.scale.toDouble()).toFloat()
                            .coerceIn(0.6f, 1.6f),
                        opacity = item.optDouble("opacity", def.opacity.toDouble()).toFloat()
                            .coerceIn(0.2f, 1.0f),
                        visible = if (id.canHide) item.optBoolean("visible", def.visible) else true
                    )
                }
            }

            GameSettings(
                graphics = graphics,
                sensitivity = sensitivity,
                audio = audio,
                controlLayout = ControlLayout(widgets)
            )
        }.getOrElse { GameSettings() }
    }

    fun saveSettings(settings: GameSettings) {
        val root = JSONObject().apply {
            put("graphics", JSONObject().apply {
                put("preset", settings.graphics.preset.name)
                put("resolutionScale", settings.graphics.resolutionScale.toDouble())
                put("aspectRatio", settings.graphics.aspectRatio.name)
                put("targetFps", settings.graphics.targetFps.name)
                put("rtxReflections", settings.graphics.rtxReflections)
                put("dynamicLighting", settings.graphics.dynamicLighting)
                put("hdTextures", settings.graphics.hdTextures)
                put("bloomAndAces", settings.graphics.bloomAndAces)
                put("fov", GraphicsSettings.FIXED_FOV.toDouble())
                put("showFps", settings.graphics.showFps)
            })
            put("sensitivity", JSONObject().apply {
                put("lookSensitivity", settings.sensitivity.lookSensitivity.toDouble())
                put("adsSensitivity", settings.sensitivity.adsSensitivity.toDouble())
                put("xAxisMultiplier", settings.sensitivity.xAxisMultiplier.toDouble())
                put("yAxisMultiplier", settings.sensitivity.yAxisMultiplier.toDouble())
                put("invertY", settings.sensitivity.invertY)
            })
            put("audio", JSONObject().apply {
                put("sfxVolume", settings.audio.sfxVolume.toDouble())
                put("hapticsEnabled", settings.audio.hapticsEnabled)
            })
            put("controlLayoutV2", JSONObject().apply {
                for ((id, cfg) in settings.controlLayout.widgets) {
                    put(id.name, JSONObject().apply {
                        put("x", cfg.xFraction.toDouble())
                        put("y", cfg.yFraction.toDouble())
                        put("scale", cfg.scale.toDouble())
                        put("opacity", cfg.opacity.toDouble())
                        put("visible", cfg.visible)
                    })
                }
            })
        }
        prefs.edit().putString(KEY_SETTINGS, root.toString()).apply()
    }

    fun loadStats(): PlayerStats {
        return PlayerStats(
            bestScore = prefs.getInt(KEY_BEST_SCORE, 0),
            totalKills = prefs.getInt(KEY_TOTAL_KILLS, 0),
            totalHeadshots = prefs.getInt(KEY_TOTAL_HEADSHOTS, 0),
            gamesPlayed = prefs.getInt(KEY_GAMES_PLAYED, 0)
        )
    }

    fun recordMatch(score: Int, kills: Int, headshots: Int): PlayerStats {
        val current = loadStats()
        val updated = PlayerStats(
            bestScore = maxOf(current.bestScore, score),
            totalKills = current.totalKills + kills,
            totalHeadshots = current.totalHeadshots + headshots,
            gamesPlayed = current.gamesPlayed + 1
        )
        prefs.edit()
            .putInt(KEY_BEST_SCORE, updated.bestScore)
            .putInt(KEY_TOTAL_KILLS, updated.totalKills)
            .putInt(KEY_TOTAL_HEADSHOTS, updated.totalHeadshots)
            .putInt(KEY_GAMES_PLAYED, updated.gamesPlayed)
            .apply()
        return updated
    }

    companion object {
        private const val PREFS_NAME = "vanguard_rtx_prefs"
        private const val KEY_SETTINGS = "settings_json"
        private const val KEY_BEST_SCORE = "best_score"
        private const val KEY_TOTAL_KILLS = "total_kills"
        private const val KEY_TOTAL_HEADSHOTS = "total_headshots"
        private const val KEY_GAMES_PLAYED = "games_played"
    }
}
