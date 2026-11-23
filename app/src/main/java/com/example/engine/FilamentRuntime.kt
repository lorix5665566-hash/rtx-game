package com.example.engine

import android.opengl.Matrix
import com.example.model.ArenaMapId
import com.example.model.GraphicsPreset
import com.example.model.GraphicsSettings
import com.example.model.WeaponType
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.Texture
import com.google.android.filament.View
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.FilamentInstance
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

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
 * Holds a loaded Google Filament glTF 2.0 / .GLB instance (standalone or GPU-instanced)
 * along with its auto-computed bounding box center, dimensions, and skeletal animation indices.
 */
class LoadedGlbModel(
    val asset: FilamentAsset,
    val instance: FilamentInstance = asset.instance,
    val ownsAsset: Boolean = true,
    val centerX: Float,
    val centerY: Float,
    val centerZ: Float,
    val minY: Float,
    val sizeX: Float,
    val sizeY: Float,
    val sizeZ: Float,
    val idleAnimIdx: Int,
    val walkAnimIdx: Int,
    val runAnimIdx: Int,
    val deathAnimIdx: Int = -1
) {
    val rootEntity: Int = instance.root
    val entities: IntArray = instance.entities
    val maxExtent: Float = maxOf(sizeX, maxOf(sizeY, sizeZ)).coerceAtLeast(0.001f)
    val rigHandles: OperativeRigHandles = OperativeRigHandles(asset, instance)
    val boltEntity: Int = resolveInstanceEntityByName(asset, instance, "Bolt")
    val magEntity: Int = resolveInstanceEntityByName(asset, instance, "Magazine")
    var inScene: Boolean = false
    var deathPoseSettled: Boolean = false
}

data class SkyboxRig(
    val skybox: Skybox,
    val cubemapTexture: Texture?
)

object FilamentSceneHelpers {

    private fun extractAnimationIndices(instance: FilamentInstance): IntArray {
        val animator = instance.animator
        var idleIdx = -1
        var walkIdx = -1
        var runIdx = -1
        var deathIdx = -1
        val count = animator.animationCount
        for (i in 0 until count) {
            val n = animator.getAnimationName(i).lowercase()
            when {
                n.contains("death") || n.contains("die") || n.contains("fall") -> deathIdx = i
                n.contains("idle") -> idleIdx = i
                n.contains("walk") -> walkIdx = i
                n.contains("run") || n.contains("sprint") -> runIdx = i
            }
        }
        if (idleIdx < 0 && count > 0) idleIdx = 0
        if (walkIdx < 0) walkIdx = idleIdx
        if (runIdx < 0) runIdx = walkIdx
        return intArrayOf(idleIdx, walkIdx, runIdx, deathIdx)
    }

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
            val inst = asset.instance
            val anims = extractAnimationIndices(inst)

            LoadedGlbModel(
                asset = asset,
                instance = inst,
                ownsAsset = true,
                centerX = c[0],
                centerY = c[1],
                centerZ = c[2],
                minY = c[1] - h[1],
                sizeX = (h[0] * 2f).coerceAtLeast(0.001f),
                sizeY = (h[1] * 2f).coerceAtLeast(0.001f),
                sizeZ = (h[2] * 2f).coerceAtLeast(0.001f),
                idleAnimIdx = anims[0],
                walkAnimIdx = anims[1],
                runAnimIdx = anims[2],
                deathAnimIdx = anims[3]
            )
        }.getOrElse {
            runCatching { assetLoader.destroyAsset(asset) }
            null
        }
    }

    /**
     * Loads a single .GLB into ONE shared GPU FilamentAsset (sharing all textures, vertex buffers,
     * index buffers, and compiled materials) and instantiates [count] independent FilamentInstances
     * with separate transforms, visibility, and skeletal animators. Cuts VRAM & load time ~5-10x!
     */
    fun loadInstancedGlb(
        assetLoader: AssetLoader,
        resourceLoader: ResourceLoader,
        buffer: ByteBuffer?,
        count: Int
    ): List<LoadedGlbModel> {
        if (buffer == null || count <= 0) return emptyList()
        if (count == 1) {
            return loadGlb(assetLoader, resourceLoader, buffer)?.let { listOf(it) } ?: emptyList()
        }
        val rawInstances = arrayOfNulls<FilamentInstance>(count)
        val asset = runCatching { assetLoader.createInstancedAsset(buffer, rawInstances) }.getOrNull()
            ?: return emptyList()
        return runCatching {
            resourceLoader.loadResources(asset)
            asset.releaseSourceData()
            val bb = asset.boundingBox
            val c = bb.center
            val h = bb.halfExtent
            val firstInst = rawInstances[0] ?: asset.instance
            val anims = extractAnimationIndices(firstInst)

            val out = ArrayList<LoadedGlbModel>(count)
            for (i in 0 until count) {
                val inst = rawInstances[i] ?: continue
                out.add(
                    LoadedGlbModel(
                        asset = asset,
                        instance = inst,
                        ownsAsset = (i == 0),
                        centerX = c[0],
                        centerY = c[1],
                        centerZ = c[2],
                        minY = c[1] - h[1],
                        sizeX = (h[0] * 2f).coerceAtLeast(0.001f),
                        sizeY = (h[1] * 2f).coerceAtLeast(0.001f),
                        sizeZ = (h[2] * 2f).coerceAtLeast(0.001f),
                        idleAnimIdx = anims[0],
                        walkAnimIdx = anims[1],
                        runAnimIdx = anims[2],
                        deathAnimIdx = anims[3]
                    )
                )
            }
            if (out.isEmpty()) {
                runCatching { assetLoader.destroyAsset(asset) }
            }
            out
        }.getOrElse {
            runCatching { assetLoader.destroyAsset(asset) }
            emptyList()
        }
    }

    private val partScratchMat = FloatArray(16)

    fun setAssetVisible(scene: Scene, model: LoadedGlbModel?, visible: Boolean) {
        if (model == null) return
        if (visible && !model.inScene) {
            scene.addEntities(model.entities)
            model.inScene = true
        } else if (!visible && model.inScene) {
            scene.removeEntities(model.entities)
            model.inScene = false
            model.deathPoseSettled = false
        }
    }

    /**
     * Configures first-person viewmodel weapons, hands, and muzzle flash renderables so they never
     * cast miniature wall shadows.
     */
    fun configureViewmodelRenderables(engine: Engine, model: LoadedGlbModel?) {
        if (model == null) return
        val rm = engine.renderableManager
        for (entity in model.entities) {
            val inst = rm.getInstance(entity)
            if (inst != 0) {
                rm.setCastShadows(inst, false)
                rm.setFogEnabled(inst, false)
            }
        }
    }

    /**
     * Disables shadow casting on small secondary props (such as 3rd-person rifles/pistols) to cut
     * shadow-pass draw calls while keeping full 2K character & architecture shadows crisp.
     */
    fun disableShadowCasting(engine: Engine, model: LoadedGlbModel?) {
        if (model == null) return
        val rm = engine.renderableManager
        for (entity in model.entities) {
            val inst = rm.getInstance(entity)
            if (inst != 0) {
                rm.setCastShadows(inst, false)
            }
        }
    }

    /**
     * Drives the physical 3D `"Bolt"` (recoil blowback + charging handle / slide rack) and
     * `"Magazine"` (reload detachment & re-insertion) child nodes inside bundled weapon GLBs.
     */
    fun applyWeaponPartOffsets(
        engine: Engine,
        model: LoadedGlbModel,
        boltOffsetZ: Float,
        magTx: Float,
        magTy: Float,
        magTz: Float,
        magPitchDeg: Float
    ) {
        val tm = engine.transformManager
        if (model.boltEntity != 0) {
            val inst = tm.getInstance(model.boltEntity)
            if (inst != 0) {
                Matrix.setIdentityM(partScratchMat, 0)
                if (boltOffsetZ != 0f) {
                    Matrix.translateM(partScratchMat, 0, 0f, 0f, boltOffsetZ)
                }
                tm.setTransform(inst, partScratchMat)
            }
        }
        if (model.magEntity != 0) {
            val inst = tm.getInstance(model.magEntity)
            if (inst != 0) {
                Matrix.setIdentityM(partScratchMat, 0)
                if (magTx != 0f || magTy != 0f || magTz != 0f) {
                    Matrix.translateM(partScratchMat, 0, magTx, magTy, magTz)
                }
                if (magPitchDeg != 0f) {
                    Matrix.rotateM(partScratchMat, 0, magPitchDeg, 1f, 0f, 0f)
                }
                tm.setTransform(inst, partScratchMat)
            }
        }
    }

    /**
     * Generates a rich procedural 6-face skybox cubemap with Rayleigh sky gradient,
     * sunlit layered cumulus clouds, horizon haze, and solar corona tailored to Mirage or Dust 2.
     */
    fun createProceduralSkyRig(engine: Engine, mapId: ArenaMapId): SkyboxRig {
        return runCatching {
            val size = 128
            val facePixels = size * size
            val faceBytes = facePixels * 4
            val buffer = ByteBuffer.allocateDirect(faceBytes * 6).order(ByteOrder.nativeOrder())

            // Sun sits opposite the directional sun travel vector (-0.46, -0.82, -0.34)
            val sunLen = sqrt(0.46f * 0.46f + 0.82f * 0.82f + 0.34f * 0.34f)
            val sx = 0.46f / sunLen
            val sy = 0.82f / sunLen
            val sz = 0.34f / sunLen

            val isDust = mapId == ArenaMapId.DUST2
            // Mirage: vivid Mediterranean azure + crisp cream clouds
            // Dust 2: golden sunlit desert sky + warm amber horizon
            val zenR = if (isDust) 0.14f else 0.10f
            val zenG = if (isDust) 0.36f else 0.34f
            val zenB = if (isDust) 0.68f else 0.76f

            val midR = if (isDust) 0.44f else 0.34f
            val midG = if (isDust) 0.66f else 0.62f
            val midB = if (isDust) 0.86f else 0.90f

            val horR = if (isDust) 0.96f else 0.84f
            val horG = if (isDust) 0.85f else 0.88f
            val horB = if (isDust) 0.70f else 0.92f

            val gndR = if (isDust) 0.68f else 0.56f
            val gndG = if (isDust) 0.56f else 0.51f
            val gndB = if (isDust) 0.42f else 0.44f

            val cloudLitR = if (isDust) 1.00f else 1.00f
            val cloudLitG = if (isDust) 0.95f else 0.98f
            val cloudLitB = if (isDust) 0.86f else 0.94f

            val cloudShdR = if (isDust) 0.76f else 0.68f
            val cloudShdG = if (isDust) 0.74f else 0.76f
            val cloudShdB = if (isDust) 0.78f else 0.86f

            val invSize = 2.0f / size.toFloat()

            for (face in 0 until 6) {
                for (py in 0 until size) {
                    val v = (py + 0.5f) * invSize - 1.0f
                    for (px in 0 until size) {
                        val u = (px + 0.5f) * invSize - 1.0f
                        var dx: Float
                        var dy: Float
                        var dz: Float
                        when (face) {
                            0 -> { dx = 1f; dy = -v; dz = -u }   // +X
                            1 -> { dx = -1f; dy = -v; dz = u }   // -X
                            2 -> { dx = u; dy = 1f; dz = v }     // +Y
                            3 -> { dx = u; dy = -1f; dz = -v }   // -Y
                            4 -> { dx = u; dy = -v; dz = 1f }    // +Z
                            else -> { dx = -u; dy = -v; dz = -1f } // -Z
                        }
                        val invLen = 1.0f / sqrt(dx * dx + dy * dy + dz * dz)
                        dx *= invLen
                        dy *= invLen
                        dz *= invLen

                        var r: Float
                        var g: Float
                        var b: Float
                        if (dy >= 0f) {
                            // Smooth atmospheric curve: horizon -> mid-sky -> deep zenith
                            val h = dy.coerceIn(0f, 1f)
                            val tLow = (h / 0.28f).coerceIn(0f, 1f)
                            val sLow = tLow * tLow * (3f - 2f * tLow)
                            val tHigh = ((h - 0.14f) / 0.86f).coerceIn(0f, 1f)
                            val sHigh = tHigh.pow(0.75f)

                            val baseR = lerpF(horR, midR, sLow)
                            val baseG = lerpF(horG, midG, sLow)
                            val baseB = lerpF(horB, midB, sLow)
                            r = lerpF(baseR, zenR, sHigh)
                            g = lerpF(baseG, zenG, sHigh)
                            b = lerpF(baseB, zenB, sHigh)

                            // Sun disc + warm atmospheric Mie scattering corona
                            val sunDot = (dx * sx + dy * sy + dz * sz).coerceIn(-1f, 1f)
                            if (sunDot > 0f) {
                                val halo = sunDot.pow(7f) * (if (isDust) 0.42f else 0.30f)
                                val tightGlow = sunDot.pow(48f) * 0.65f
                                val disc = if (sunDot > 0.9975f) {
                                    ((sunDot - 0.9975f) / 0.0025f).coerceIn(0f, 1f)
                                } else 0f
                                r += halo * 1.0f + tightGlow * 1.0f + disc * 0.9f
                                g += halo * 0.86f + tightGlow * 0.94f + disc * 0.88f
                                b += halo * 0.62f + tightGlow * 0.78f + disc * 0.72f
                            }

                            // Volumetric-style sunlit cumulus clouds projected onto curved sky plane
                            if (h > 0.035f) {
                                val proj = 1.0f / (h + 0.18f)
                                val cx = dx * proj * 1.15f + (if (isDust) 4.2f else 1.3f)
                                val cz = dz * proj * 1.15f + (if (isDust) 2.7f else -0.8f)
                                val n = fbm2d(cx, cz)
                                val threshold = if (isDust) 0.56f else 0.51f
                                val coverage = ((n - threshold) / (0.86f - threshold)).coerceIn(0f, 1f)
                                val horizonFade = ((h - 0.035f) / 0.14f).coerceIn(0f, 1f)
                                val cloudAlpha = (coverage * coverage * (3f - 2f * coverage) * horizonFade * 0.88f)
                                    .coerceIn(0f, 0.92f)
                                if (cloudAlpha > 0.005f) {
                                    // Fake self-shadowing: sample slightly toward sun for sunlit rims!
                                    val nSun = fbm2d(cx + sx * 0.16f, cz + sz * 0.16f)
                                    val litFactor = (0.58f + (n - nSun) * 1.8f + max(0f, sunDot) * 0.35f)
                                        .coerceIn(0.18f, 1.0f)
                                    val cr = lerpF(cloudShdR, cloudLitR, litFactor)
                                    val cg = lerpF(cloudShdG, cloudLitG, litFactor)
                                    val cb = lerpF(cloudShdB, cloudLitB, litFactor)
                                    r = lerpF(r, cr, cloudAlpha)
                                    g = lerpF(g, cg, cloudAlpha)
                                    b = lerpF(b, cb, cloudAlpha)
                                }
                            }
                        } else {
                            val tDown = (-dy / 0.32f).coerceIn(0f, 1f)
                            val sDown = tDown * tDown * (3f - 2f * tDown)
                            r = lerpF(horR, gndR, sDown)
                            g = lerpF(horG, gndG, sDown)
                            b = lerpF(horB, gndB, sDown)
                        }

                        buffer.put((r.coerceIn(0f, 1f) * 255f).toInt().toByte())
                        buffer.put((g.coerceIn(0f, 1f) * 255f).toInt().toByte())
                        buffer.put((b.coerceIn(0f, 1f) * 255f).toInt().toByte())
                        buffer.put(255.toByte())
                    }
                }
            }
            buffer.flip()

            val tex = Texture.Builder()
                .width(size)
                .height(size)
                .levels(1)
                .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
                .format(Texture.InternalFormat.SRGB8_A8)
                .build(engine)

            val offsets = intArrayOf(
                0,
                faceBytes,
                faceBytes * 2,
                faceBytes * 3,
                faceBytes * 4,
                faceBytes * 5
            )
            val pbd = Texture.PixelBufferDescriptor(
                buffer,
                Texture.Format.RGBA,
                Texture.Type.UBYTE
            )
            tex.setImage(engine, 0, pbd, offsets)

            val skybox = Skybox.Builder()
                .environment(tex)
                .intensity(26_000f)
                .build(engine)
            SkyboxRig(skybox, tex)
        }.getOrElse {
            val fallback = Skybox.Builder()
                .color(0.48f, 0.72f, 0.94f, 1.0f)
                .build(engine)
            SkyboxRig(fallback, null)
        }
    }

    fun destroySkyRig(engine: Engine, scene: Scene, rig: SkyboxRig?) {
        if (rig == null) return
        if (scene.skybox == rig.skybox) {
            scene.skybox = null
        }
        runCatching { engine.destroySkybox(rig.skybox) }
        rig.cubemapTexture?.let { tex ->
            runCatching { engine.destroyTexture(tex) }
        }
    }

    private fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun hash2d(ix: Int, iz: Int): Float {
        var n = ix * 374761393 + iz * 668265263
        n = (n xor (n ushr 13)) * 1274126177
        return ((n xor (n ushr 16)) and 0x7fffffff).toFloat() / 2147483647.0f
    }

    private fun valueNoise2d(x: Float, z: Float): Float {
        val ix = floor(x).toInt()
        val iz = floor(z).toInt()
        val fx = x - ix
        val fz = z - iz
        val ux = fx * fx * (3f - 2f * fx)
        val uz = fz * fz * (3f - 2f * fz)
        val a = hash2d(ix, iz)
        val b = hash2d(ix + 1, iz)
        val c = hash2d(ix, iz + 1)
        val d = hash2d(ix + 1, iz + 1)
        return lerpF(lerpF(a, b, ux), lerpF(c, d, ux), uz)
    }

    private fun fbm2d(x: Float, z: Float): Float {
        var amp = 0.52f
        var freq = 1.0f
        var sum = 0f
        var norm = 0f
        for (i in 0 until 4) {
            sum += amp * valueNoise2d(x * freq, z * freq)
            norm += amp
            freq *= 2.07f
            amp *= 0.5f
        }
        return sum / norm
    }

    /**
     * Creates CS2 Mirage / Dust 2 cinematic lighting:
     * - Procedural Rayleigh + cumulus skybox
     * - Contrasting Hemisphere 3-band SH IBL (cool Mediterranean azure sky above, warm sandstone bounce below)
     * - Warm shadow-casting Directional Sun with soft 2K 2-cascade shadow maps (zero overexposure!)
     * - Dynamic PointLight for gunfire muzzle flash & C4 detonation
     */
    fun setupPbrLighting(
        engine: Engine,
        scene: Scene,
        mapId: ArenaMapId = ArenaMapId.MIRAGE
    ): Triple<Int, Int, SkyboxRig> {
        val skyRig = createProceduralSkyRig(engine, mapId)
        scene.skybox = skyRig.skybox

        // Contrasting CS2 Mirage Hemisphere SH IBL: cool azure sky from +Y, warm sandstone bounce from -Y
        val isDust = mapId == ArenaMapId.DUST2
        val shBands = floatArrayOf(
            // L0 ambient base (calibrated below 1.0 so characters & pale sandstone NEVER blow out white)
            if (isDust) 0.88f else 0.84f, if (isDust) 0.84f else 0.83f, if (isDust) 0.76f else 0.82f,
            // L1_y vertical hemisphere dipole: cool blue sky top (+Y) vs warm desert sand bounce bottom (-Y)
            -0.14f, 0.04f, 0.24f,
            // L1_z directional sun scatter
            0.20f, 0.16f, 0.11f,
            // L1_x cross fill
            -0.09f, -0.08f, -0.06f,
            // L2 subtle directional rim & warm bounce harmonics
            0.07f, 0.06f, 0.05f,
            -0.03f, -0.02f, -0.02f,
            0.09f, 0.07f, 0.04f,
            -0.03f, -0.02f, -0.01f,
            0.06f, 0.05f, 0.04f
        )
        val ibl = IndirectLight.Builder()
            .irradiance(3, shBands)
            .intensity(32_000f)
            .build(engine)
        scene.indirectLight = ibl

        // Warm CS2 Mirage Directional Sun with crisp 2K 2-cascade shadows
        val sunEntity = EntityManager.get().create()
        val shadowOpts = LightManager.ShadowOptions().apply {
            mapSize = 2048
            shadowCascades = 2
            stable = true
            normalBias = 1.2f
        }
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, if (isDust) 0.91f else 0.93f, if (isDust) 0.76f else 0.80f)
            .intensity(96_000f)
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

        return Triple(sunEntity, fxLightEntity, skyRig)
    }

    fun applyGraphicsSettings(
        engine: Engine,
        view: View,
        gfx: GraphicsSettings,
        mapId: ArenaMapId = ArenaMapId.MIRAGE
    ) {
        view.antiAliasing = if (gfx.preset == GraphicsPreset.LOW) {
            View.AntiAliasing.NONE
        } else {
            View.AntiAliasing.FXAA
        }

        val bloom = View.BloomOptions().apply {
            enabled = gfx.bloomAndAces && gfx.preset != GraphicsPreset.LOW
            strength = if (gfx.preset == GraphicsPreset.ULTRA_RTX) 0.10f else 0.06f
            threshold = true
        }
        view.bloomOptions = bloom

        // Disabled per-frame screen-space AO pass for smooth mobile FPS while preserving
        // full 2K 2-cascade sun shadows, hemisphere IBL, PBR textures, ACES & bloom!
        val ao = View.AmbientOcclusionOptions().apply {
            enabled = false
        }
        view.ambientOcclusionOptions = ao

        // Atmospheric distance fog disabled for crystal-clear Mirage & Dust 2 visibility
        val fog = View.FogOptions().apply {
            enabled = false
        }
        view.fogOptions = fog

        // Preserve full 2048px 2-cascade dynamic sun shadows
        view.setShadowingEnabled(gfx.dynamicLighting && gfx.preset != GraphicsPreset.LOW)

        // Adaptive dynamic resolution scaling keeps FPS smooth on all phones without losing clarity
        val targetMax = gfx.resolutionScale.coerceIn(0.60f, 1.0f)
        val targetMin = (targetMax * 0.78f).coerceIn(0.60f, targetMax)
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
            .contrast(if (gfx.bloomAndAces) 1.09f else 1.0f)
            .saturation(if (gfx.bloomAndAces) 1.07f else 1.0f)
            .build(engine)
        view.colorGrading = grading
        if (prevGrading != null && prevGrading != grading) {
            runCatching { engine.destroyColorGrading(prevGrading) }
        }
    }

    /**
     * Builds a transform matrix that places a loaded character .GLB standing at (x, y, z)
     * with exact real human height (1.76m), feet anchored cleanly on floor y, facing yawDeg (+Z forward).
     * When the model has a dedicated skeletal Death clip (deathAnimIdx >= 0), death collapse is
     * driven smoothly by the skeleton (knees buckling, limp arms) rather than a stiff root tilt.
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
        val useSkeletalDeath = !isCustomImported && model.deathAnimIdx >= 0
        Matrix.setIdentityM(out, 0)
        val liftY = if (!useSkeletalDeath && deathProgress > 0.001f) deathProgress * 0.14f else 0f
        Matrix.translateM(out, 0, x, y + liftY, z)
        // Bundled anatomical human operatives face +Z natively (aligned 1:1 with atan2(dx, dz))
        Matrix.rotateM(out, 0, yawDeg, 0f, 1f, 0f)
        if (!useSkeletalDeath && deathProgress > 0.001f) {
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
     * Positions a 3rd-person equipped weapon .GLB cleanly in a standing/moving operative's hands
     * (facing character +Z) and smoothly drops it flat onto the floor when the operative dies.
     */
    fun buildThirdPersonWeaponMatrix(
        out: FloatArray,
        weapon: WeaponType,
        charX: Float,
        charY: Float,
        charZ: Float,
        charYawDeg: Float,
        deathProgress: Float,
        isShooting: Boolean = false
    ) {
        val dp = deathProgress.coerceIn(0f, 1f)
        val drop = dp * dp * (3f - 2f * dp)

        // Character-local hold offset (+Z forward, +Y up, -X right hand)
        val baseX: Float
        val baseY: Float
        val baseZ: Float
        val baseScale: Float
        when (weapon) {
            WeaponType.AWP -> {
                baseX = -0.15f; baseY = 1.24f; baseZ = 0.28f; baseScale = 1.06f
            }
            WeaponType.AK47, WeaponType.M4A1_S -> {
                baseX = -0.15f; baseY = 1.24f; baseZ = 0.30f; baseScale = 1.08f
            }
            WeaponType.P90, WeaponType.MP9 -> {
                baseX = -0.14f; baseY = 1.23f; baseZ = 0.32f; baseScale = 1.06f
            }
            WeaponType.USP_S, WeaponType.GLOCK_18, WeaponType.DESERT_EAGLE -> {
                baseX = -0.15f; baseY = 1.25f; baseZ = 0.40f; baseScale = 1.10f
            }
            WeaponType.KNIFE -> {
                baseX = -0.17f; baseY = 1.20f; baseZ = 0.38f; baseScale = 1.05f
            }
            WeaponType.C4_BOMB -> {
                baseX = 0.0f; baseY = 1.18f; baseZ = 0.35f; baseScale = 0.90f
            }
        }

        val recoilBack = if (isShooting && dp < 0.05f) -0.035f else 0f
        val localX = lerpF(baseX, -0.44f, drop)
        val localY = lerpF(baseY, 0.06f, drop)
        val localZ = lerpF(baseZ + recoilBack, 0.14f, drop)

        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, charX, charY, charZ)
        Matrix.rotateM(out, 0, charYawDeg, 0f, 1f, 0f)
        Matrix.translateM(out, 0, localX, localY, localZ)
        // Bundled weapons have barrel along -Z; rotate 180° around Y to point straight along character +Z!
        Matrix.rotateM(out, 0, 180f + drop * 34f, 0f, 1f, 0f)
        val pitchKick = if (isShooting && dp < 0.05f) 4.5f else 0f
        Matrix.rotateM(out, 0, lerpF(-2.5f + pitchKick, 0f, drop), 1f, 0f, 0f)
        Matrix.rotateM(out, 0, lerpF(4f, 88f, drop), 0f, 0f, 1f)
        Matrix.scaleM(out, 0, baseScale, baseScale, baseScale)
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
        scale: Float = 1.0f,
        weaponOnlyYawDeg: Float = 0f,
        weaponOnlyPitchDeg: Float = 0f,
        weaponOnlyRollDeg: Float = 0f
    ) {
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, tx, ty, tz)
        if (yawDeg != 0f) Matrix.rotateM(out, 0, yawDeg, 0f, 1f, 0f)
        if (pitchDeg != 0f) Matrix.rotateM(out, 0, pitchDeg, 1f, 0f, 0f)
        if (rollDeg != 0f) Matrix.rotateM(out, 0, rollDeg, 0f, 0f, 1f)
        // Local grip-pivot spin applied strictly to the weapon (never the player's hand!)
        if (weaponOnlyYawDeg != 0f) Matrix.rotateM(out, 0, weaponOnlyYawDeg, 0f, 1f, 0f)
        if (weaponOnlyPitchDeg != 0f) Matrix.rotateM(out, 0, weaponOnlyPitchDeg, 1f, 0f, 0f)
        if (weaponOnlyRollDeg != 0f) Matrix.rotateM(out, 0, weaponOnlyRollDeg, 0f, 0f, 1f)
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
