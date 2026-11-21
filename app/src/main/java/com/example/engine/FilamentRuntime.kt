package com.example.engine

import android.opengl.Matrix
import com.example.model.GraphicsPreset
import com.example.model.GraphicsSettings
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.View
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer

object FilamentBootstrap {
    @Volatile
    private var initialized = false

    fun ensureInitialized() {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    Filament.init()
                    Utils.init()
                    initialized = true
                }
            }
        }
    }
}

/**
 * Holds a loaded Google Filament glTF 2.0 / .GLB asset along with its auto-computed
 * bounding box center, dimensions, and skeletal animation indices.
 */
class LoadedGlbModel(
    val asset: FilamentAsset,
    val centerX: Float,
    val centerY: Float,
    val centerZ: Float,
    val minY: Float,
    val sizeX: Float,
    val sizeY: Float,
    val sizeZ: Float,
    val idleAnimIdx: Int,
    val walkAnimIdx: Int,
    val runAnimIdx: Int
) {
    val maxExtent: Float = maxOf(sizeX, maxOf(sizeY, sizeZ)).coerceAtLeast(0.001f)
    var inScene: Boolean = false
}

object FilamentSceneHelpers {

    fun loadGlb(
        assetLoader: AssetLoader,
        resourceLoader: ResourceLoader,
        buffer: ByteBuffer?
    ): LoadedGlbModel? {
        if (buffer == null) return null
        val asset = runCatching { assetLoader.createAsset(buffer) }.getOrNull() ?: return null
        return runCatching {
            resourceLoader.loadResources(asset)
            asset.releaseSourceData()
            val bb = asset.boundingBox
            val c = bb.center
            val h = bb.halfExtent
            val animator = asset.instance.animator
            var idleIdx = -1
            var walkIdx = -1
            var runIdx = -1
            val count = animator.animationCount
            for (i in 0 until count) {
                val n = animator.getAnimationName(i).lowercase()
                when {
                    n.contains("idle") -> idleIdx = i
                    n.contains("walk") -> walkIdx = i
                    n.contains("run") || n.contains("sprint") -> runIdx = i
                }
            }
            if (idleIdx < 0 && count > 0) idleIdx = 0
            if (walkIdx < 0) walkIdx = idleIdx
            if (runIdx < 0) runIdx = walkIdx

            LoadedGlbModel(
                asset = asset,
                centerX = c[0],
                centerY = c[1],
                centerZ = c[2],
                minY = c[1] - h[1],
                sizeX = (h[0] * 2f).coerceAtLeast(0.001f),
                sizeY = (h[1] * 2f).coerceAtLeast(0.001f),
                sizeZ = (h[2] * 2f).coerceAtLeast(0.001f),
                idleAnimIdx = idleIdx,
                walkAnimIdx = walkIdx,
                runAnimIdx = runIdx
            )
        }.getOrElse {
            runCatching { assetLoader.destroyAsset(asset) }
            null
        }
    }

    fun setAssetVisible(scene: Scene, model: LoadedGlbModel?, visible: Boolean) {
        if (model == null) return
        if (visible && !model.inScene) {
            scene.addEntities(model.asset.entities)
            model.inScene = true
        } else if (!visible && model.inScene) {
            scene.removeEntities(model.asset.entities)
            model.inScene = false
        }
    }

    /**
     * Creates warm sunlit Mediterranean skybox + 3-band Spherical Harmonics IndirectLight (IBL)
     * + shadow-casting directional sun + dynamic point light for muzzle flash / C4 explosion.
     */
    fun setupPbrLighting(engine: Engine, scene: Scene): Pair<Int, Int> {
        // Bright sunlit Mediterranean CS2 Mirage daylight sky
        val skybox = Skybox.Builder()
            .color(0.54f, 0.74f, 0.94f, 1.0f)
            .build(engine)
        scene.skybox = skybox

        // 3-band Spherical Harmonics IBL: bright open sky + warm sunlit sandstone bounce so characters & walls never look dark
        val shBands = floatArrayOf(
            1.15f, 1.10f, 1.04f,
            0.26f, 0.32f, 0.40f,
            0.22f, 0.19f, 0.15f,
            -0.10f, -0.09f, -0.07f,
            0.08f, 0.07f, 0.06f,
            -0.04f, -0.03f, -0.02f,
            0.09f, 0.08f, 0.07f,
            -0.03f, -0.02f, -0.02f,
            0.06f, 0.06f, 0.05f
        )
        val ibl = IndirectLight.Builder()
            .irradiance(3, shBands)
            .intensity(54_000f)
            .build(engine)
        scene.indirectLight = ibl

        // Primary Sun DirectionalLight with real-time shadow maps
        val sunEntity = EntityManager.get().create()
        val shadowOpts = LightManager.ShadowOptions().apply {
            mapSize = 2048
            shadowCascades = 2
            stable = true
        }
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.96f, 0.88f)
            .intensity(138_000f)
            .direction(-0.46f, -0.82f, -0.34f)
            .castShadows(true)
            .shadowOptions(shadowOpts)
            .build(engine, sunEntity)
        scene.addEntity(sunEntity)

        // Dynamic PointLight (used for 45ms gunfire muzzle flash & massive C4 detonation!)
        val fxLightEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.POINT)
            .color(1.0f, 0.56f, 0.18f)
            .intensity(0f)
            .falloff(18f)
            .position(0f, -100f, 0f)
            .build(engine, fxLightEntity)
        scene.addEntity(fxLightEntity)

        return sunEntity to fxLightEntity
    }

    fun applyGraphicsSettings(engine: Engine, view: View, gfx: GraphicsSettings) {
        view.antiAliasing = if (gfx.preset == GraphicsPreset.LOW) {
            View.AntiAliasing.NONE
        } else {
            View.AntiAliasing.FXAA
        }

        val bloom = View.BloomOptions().apply {
            enabled = gfx.bloomAndAces && gfx.preset != GraphicsPreset.LOW
            strength = if (gfx.preset == GraphicsPreset.ULTRA_RTX) 0.12f else 0.07f
            threshold = true
        }
        view.bloomOptions = bloom

        // Disable heavy screen-space AO for maximum FPS on mobile GPUs (2K shadows remain active!)
        val ao = View.AmbientOcclusionOptions().apply {
            enabled = false
        }
        view.ambientOcclusionOptions = ao

        // Preserve full 2048px 2-cascade dynamic sun shadows unchanged
        view.setShadowingEnabled(gfx.dynamicLighting && gfx.preset != GraphicsPreset.LOW)

        // Adaptive dynamic resolution scaling keeps FPS smooth on all phones during combat
        val targetMax = gfx.resolutionScale.coerceIn(0.60f, 1.0f)
        val targetMin = (targetMax * 0.72f).coerceIn(0.55f, targetMax)
        val dynRes = View.DynamicResolutionOptions().apply {
            enabled = true
            minScale = targetMin
            maxScale = targetMax
            quality = View.QualityLevel.MEDIUM
        }
        view.dynamicResolutionOptions = dynRes

        val prevGrading = view.colorGrading
        val grading = ColorGrading.Builder()
            .toneMapping(
                if (gfx.bloomAndAces) ColorGrading.ToneMapping.ACES
                else ColorGrading.ToneMapping.LINEAR
            )
            .contrast(if (gfx.bloomAndAces) 1.07f else 1.0f)
            .saturation(if (gfx.bloomAndAces) 1.06f else 1.0f)
            .build(engine)
        view.colorGrading = grading
        if (prevGrading != null && prevGrading != grading) {
            runCatching { engine.destroyColorGrading(prevGrading) }
        }
    }

    /**
     * Builds a transform matrix that places a loaded character .GLB standing at (x, y, z)
     * with exact real human height (1.76m), feet anchored cleanly on floor y, facing yawDeg (+Z forward),
     * with smooth backward death fall tilt.
     */
    fun buildCharacterMatrix(
        out: FloatArray,
        model: LoadedGlbModel,
        x: Float,
        y: Float,
        z: Float,
        yawDeg: Float,
        deathProgress: Float = 0f,
        targetHeight: Float = 1.76f,
        isCustomImported: Boolean = false
    ) {
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, x, y + deathProgress * 0.14f, z)
        // Bundled anatomical human operatives face +Z natively (aligned 1:1 with atan2(dx, dz))
        Matrix.rotateM(out, 0, yawDeg, 0f, 1f, 0f)
        if (deathProgress > 0.001f) {
            Matrix.rotateM(out, 0, -deathProgress * 86f, 1f, 0f, 0f)
        }
        if (!isCustomImported) {
            // Native height of char_terrorist.glb & char_ct.glb is exactly 1.76m with boots at y = 0.0
            val nativeScale = targetHeight / 1.76f
            Matrix.scaleM(out, 0, nativeScale, nativeScale, nativeScale)
        } else {
            // Safe clamped auto-normalization for user-imported Sketchfab characters
            val safeHeight = maxOf(model.sizeY, model.sizeZ).coerceAtLeast(0.45f)
            val scale = (targetHeight / safeHeight).coerceIn(0.02f, 4.0f)
            Matrix.scaleM(out, 0, scale, scale, scale)
            Matrix.translateM(out, 0, -model.centerX, -model.minY, -model.centerZ)
        }
    }

    /**
     * Exact 1:1 local-space weapon & first-person hands transform matrix for our calibrated CS2
     * PBR .GLB weapons (already authored in meters with barrel strictly along -Z and grip at origin).
     */
    fun buildDirectWeaponMatrix(
        out: FloatArray,
        tx: Float,
        ty: Float,
        tz: Float,
        yawDeg: Float = 0f,
        pitchDeg: Float = 0f,
        rollDeg: Float = 0f,
        scale: Float = 1.0f
    ) {
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, tx, ty, tz)
        if (yawDeg != 0f) Matrix.rotateM(out, 0, yawDeg, 0f, 1f, 0f)
        if (pitchDeg != 0f) Matrix.rotateM(out, 0, pitchDeg, 1f, 0f, 0f)
        if (rollDeg != 0f) Matrix.rotateM(out, 0, rollDeg, 0f, 0f, 1f)
        if (scale != 1.0f) Matrix.scaleM(out, 0, scale, scale, scale)
    }

    /**
     * Builds a normalized transform matrix for custom Sketchfab props/weapons/VFX centered at bounding box.
     */
    fun buildNormalizedPropMatrix(
        out: FloatArray,
        model: LoadedGlbModel,
        targetMaxExtent: Float,
        tx: Float,
        ty: Float,
        tz: Float,
        yawDeg: Float = 0f,
        pitchDeg: Float = 0f,
        rollDeg: Float = 0f,
        anchorBottom: Boolean = false
    ) {
        val s = targetMaxExtent / model.maxExtent
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, tx, ty, tz)
        if (yawDeg != 0f) Matrix.rotateM(out, 0, yawDeg, 0f, 1f, 0f)
        if (pitchDeg != 0f) Matrix.rotateM(out, 0, pitchDeg, 1f, 0f, 0f)
        if (rollDeg != 0f) Matrix.rotateM(out, 0, rollDeg, 0f, 0f, 1f)
        Matrix.scaleM(out, 0, s, s, s)
        val yOffset = if (anchorBottom) -model.minY else -model.centerY
        Matrix.translateM(out, 0, -model.centerX, yOffset, -model.centerZ)
    }
}
