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
        // Warm CS2 Mirage daylight sky
        val skybox = Skybox.Builder()
            .color(0.46f, 0.67f, 0.89f, 1.0f)
            .build(engine)
        scene.skybox = skybox

        // 3-band Spherical Harmonics IBL (9 RGB triplets = 27 floats): bright sky top + warm sand bounce
        val shBands = floatArrayOf(
            0.88f, 0.84f, 0.78f,
            0.22f, 0.28f, 0.36f,
            0.16f, 0.14f, 0.11f,
            -0.12f, -0.10f, -0.08f,
            0.06f, 0.05f, 0.04f,
            -0.05f, -0.04f, -0.03f,
            0.08f, 0.07f, 0.06f,
            -0.04f, -0.03f, -0.02f,
            0.05f, 0.05f, 0.04f
        )
        val ibl = IndirectLight.Builder()
            .irradiance(3, shBands)
            .intensity(36_000f)
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
            .color(1.0f, 0.94f, 0.84f)
            .intensity(105_000f)
            .direction(-0.48f, -0.82f, -0.32f)
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
            enabled = gfx.bloomAndAces
            strength = if (gfx.preset == GraphicsPreset.ULTRA_RTX) 0.14f else 0.08f
            threshold = true
        }
        view.bloomOptions = bloom

        val ao = View.AmbientOcclusionOptions().apply {
            enabled = gfx.preset == GraphicsPreset.ULTRA_RTX && gfx.rtxReflections
            radius = 0.45f
            intensity = 1.15f
        }
        view.ambientOcclusionOptions = ao

        view.setShadowingEnabled(gfx.dynamicLighting && gfx.preset != GraphicsPreset.LOW)

        val dynRes = View.DynamicResolutionOptions().apply {
            enabled = gfx.resolutionScale < 0.99f
            minScale = gfx.resolutionScale.coerceIn(0.55f, 1.0f)
            maxScale = gfx.resolutionScale.coerceIn(0.55f, 1.0f)
        }
        view.dynamicResolutionOptions = dynRes

        val prevGrading = view.colorGrading
        val grading = ColorGrading.Builder()
            .toneMapping(
                if (gfx.bloomAndAces) ColorGrading.ToneMapping.ACES
                else ColorGrading.ToneMapping.LINEAR
            )
            .contrast(if (gfx.bloomAndAces) 1.08f else 1.0f)
            .saturation(if (gfx.bloomAndAces) 1.07f else 1.0f)
            .build(engine)
        view.colorGrading = grading
        if (prevGrading != null && prevGrading != grading) {
            runCatching { engine.destroyColorGrading(prevGrading) }
        }
    }

    /**
     * Builds a transform matrix that places a loaded character .GLB standing at (x, y, z)
     * with exact 1.78m real human height, bottom anchored cleanly at y, facing yawDeg,
     * with optional backward death fall tilt.
     */
    fun buildCharacterMatrix(
        out: FloatArray,
        model: LoadedGlbModel,
        x: Float,
        y: Float,
        z: Float,
        yawDeg: Float,
        deathProgress: Float = 0f,
        targetHeight: Float = 1.78f
    ) {
        val scale = targetHeight / model.sizeY.coerceAtLeast(0.01f)
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, x, y + deathProgress * 0.14f, z)
        Matrix.rotateM(out, 0, yawDeg, 0f, 1f, 0f)
        if (deathProgress > 0.001f) {
            Matrix.rotateM(out, 0, -deathProgress * 86f, 1f, 0f, 0f)
        }
        Matrix.scaleM(out, 0, scale, scale, scale)
        Matrix.translateM(out, 0, -model.centerX, -model.minY, -model.centerZ)
    }

    /**
     * Builds a normalized transform matrix for any object/weapon/prop .GLB centered at its bounding box.
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
