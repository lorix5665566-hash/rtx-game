package com.example.engine

import android.content.Context
import android.opengl.Matrix
import android.os.Build
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.example.model.ArenaMapId
import com.example.model.AspectRatioMode
import com.example.model.BombState
import com.example.model.GraphicsSettings
import com.example.model.ModelAssetRegistry
import com.example.model.ModelSlot
import com.example.model.RemoteHumanPlayer
import com.example.model.TeamSide
import com.example.model.WeaponType
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full AAA PBR Google Filament Gameplay Controller:
 * - Renders native CS2 Mirage / Dust 2 .GLB (or user's imported Sketchfab .GLB)
 * - Drives up to 10 skeletal animated CT & T .GLB operatives (Idle / Walk / Run / Death)
 * - Renders real .GLB first-person weapons (AK-47, M4, AWP, P90, Pistol, Knife, C4)
 * - Renders real 3D muzzle_flash.glb + dynamic point light
 * - Renders real bomb_c4.glb + multi-stage 3D C4 Explosion (fireball + smoke + shockwave + flash + camera shake!)
 */
class FilamentGameplayController(
    private val context: Context,
    private val gameEngine: GameEngine,
    private val registry: ModelAssetRegistry
) : Choreographer.FrameCallback {

    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var view: View
    private lateinit var camera: Camera
    private lateinit var uiHelper: UiHelper
    private lateinit var displayHelper: DisplayHelper
    private lateinit var assetLoader: AssetLoader
    private lateinit var materialProvider: UbershaderProvider
    private lateinit var resourceLoader: ResourceLoader

    private var swapChain: SwapChain? = null
    private var sunEntity: Int = 0
    private var fxLightEntity: Int = 0
    private var skyRig: SkyboxRig? = null

    private var loadedMapId: ArenaMapId? = null
    private var loadedRegistryRevision: Int = -1
    private var mapModel: LoadedGlbModel? = null
    private var isCustomMap: Boolean = false

    // Character pools (5 CT + 5 T rigged skeletal .GLB instances!)
    private val ctPool = ArrayList<LoadedGlbModel>(5)
    private val tPool = ArrayList<LoadedGlbModel>(5)

    // Lazy 3rd-person weapon instance pools so EVERY visible enemy & teammate holds their real equipped weapon!
    private val tpWeaponPools = HashMap<WeaponType, MutableList<LoadedGlbModel>>()
    private val tpWeaponUsedCount = HashMap<WeaponType, Int>()

    // First-person weapon models mapped by ModelSlot
    private val fpWeapons = HashMap<ModelSlot, LoadedGlbModel>()

    // Faction first-person tactical hands & gloves (.GLB) aligned 1:1 with bundled CS2 weapons
    private var handsTRifle: LoadedGlbModel? = null
    private var handsTPistol: LoadedGlbModel? = null
    private var handsTKnife: LoadedGlbModel? = null
    private var handsCtRifle: LoadedGlbModel? = null
    private var handsCtPistol: LoadedGlbModel? = null
    private var handsCtKnife: LoadedGlbModel? = null
    private val allHandsSlots = arrayOfNulls<LoadedGlbModel>(6)

    private var muzzleFlashModel: LoadedGlbModel? = null
    private var plantedBombModel: LoadedGlbModel? = null
    private var explosionFireball: LoadedGlbModel? = null
    private var explosionSmoke: LoadedGlbModel? = null
    private var explosionShockwave: LoadedGlbModel? = null

    // GPU-instanced bullet hole decals (48) & brief yellow laser bullet tracers (12)
    private val bulletHolePool = ArrayList<LoadedGlbModel>(48)
    private val bulletTracerPool = ArrayList<LoadedGlbModel>(12)

    private var lastGraphics: GraphicsSettings? = null
    private var activeSurface: Surface? = null
    private var appliedSurfaceFps: Int = 0
    private var surfaceWidth: Int = 1
    private var surfaceHeight: Int = 1
    private var lastFrameNs: Long = 0L
    private var lastRenderedFrameNs: Long = 0L
    private var isRunning: Boolean = false
    private var isDestroyed: Boolean = false

    private val tmpMat = FloatArray(16)
    private val camMat = FloatArray(16)
    private val localMat = FloatArray(16)
    private val handLocalMat = FloatArray(16)
    private val finalMat = FloatArray(16)

    // Procedural skeletal rig animator (2-bone dual-arm IK + bidirectional turn-in-place foot shuffling)
    private val rigAnimator = OperativeRigAnimator()
    private val turnStates = HashMap<String, TurnShuffleState>(16)
    private val activeTurnKeys = HashSet<String>(16)
    private val remoteSnapshotBuf = ArrayList<RemoteHumanPlayer>(10)
    private val botTurnKeys = Array(16) { "bot_$it" }
    private val reloadDelta = ViewmodelPoseDelta()
    private val inspectDelta = ViewmodelPoseDelta()

    companion object {
        // Perspective-invariant miniature viewmodel scale: visually 1:1 identical on screen while keeping
        // even the longest AWP barrel strictly inside the player's 0.36m collision cylinder (ZERO wall clipping!)
        private const val VM_SCALE = 0.24f
    }

    fun attachTo(surfaceView: SurfaceView) {
        FilamentBootstrap.ensureInitialized()

        engine = Engine.create()
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()
        camera = engine.createCamera(engine.entityManager.create())
        // Calibrated CS2 Mirage Sunny-16 daylight exposure (rich contrast, zero white blowout!)
        camera.setExposure(16.0f, 1.0f / 125.0f, 100.0f)
        view.scene = scene
        view.camera = camera

        materialProvider = UbershaderProvider(engine)
        assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
        resourceLoader = ResourceLoader(engine)

        val (sun, fx, rig) = FilamentSceneHelpers.setupPbrLighting(engine, scene, gameEngine.world.mapId)
        sunEntity = sun
        fxLightEntity = fx
        skyRig = rig

        displayHelper = DisplayHelper(context)
        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                if (isDestroyed) return
                activeSurface = surface
                appliedSurfaceFps = 0
                applySurfaceFrameRate(surface, gameEngine.settings.graphics.targetFps.fps)
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface)
                surfaceView.display?.let { displayHelper.attach(renderer, it) }
            }

            override fun onDetachedFromSurface() {
                if (isDestroyed) return
                activeSurface = null
                appliedSurfaceFps = 0
                displayHelper.detach()
                swapChain?.let {
                    engine.destroySwapChain(it)
                    engine.flushAndWait()
                }
                swapChain = null
            }

            override fun onResized(width: Int, height: Int) {
                if (isDestroyed) return
                surfaceWidth = width.coerceAtLeast(1)
                surfaceHeight = height.coerceAtLeast(1)
                gameEngine.viewportAspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
                view.viewport = Viewport(0, 0, surfaceWidth, surfaceHeight)
            }
        }
        uiHelper.attachTo(surfaceView)

        loadStaticVfxAndModels()
        ensureMapAndRegistryCurrent()
        lastFrameNs = System.nanoTime()
    }

    private fun loadStaticVfxAndModels() {
        muzzleFlashModel = FilamentSceneHelpers.loadGlb(
            assetLoader, resourceLoader,
            registry.loadBundledAssetBuffer("models/muzzle_flash.glb")
        )
        explosionFireball = FilamentSceneHelpers.loadGlb(
            assetLoader, resourceLoader,
            registry.loadBundledAssetBuffer("models/vfx_fireball.glb")
        )
        explosionSmoke = FilamentSceneHelpers.loadGlb(
            assetLoader, resourceLoader,
            registry.loadBundledAssetBuffer("models/vfx_smoke.glb")
        )
        explosionShockwave = FilamentSceneHelpers.loadGlb(
            assetLoader, resourceLoader,
            registry.loadBundledAssetBuffer("models/vfx_shockwave.glb")
        )
        handsTRifle = FilamentSceneHelpers.loadGlb(
            assetLoader, resourceLoader,
            registry.loadBundledAssetBuffer("models/fp_hands_t_rifle.glb")
        )
        handsTPistol = FilamentSceneHelpers.loadGlb(
            assetLoader, resourceLoader,
            registry.loadBundledAssetBuffer("models/fp_hands_t_pistol.glb")
        )
        handsTKnife = FilamentSceneHelpers.loadGlb(
            assetLoader, resourceLoader,
            registry.loadBundledAssetBuffer("models/fp_hands_t_knife.glb")
        )
        handsCtRifle = FilamentSceneHelpers.loadGlb(
            assetLoader, resourceLoader,
            registry.loadBundledAssetBuffer("models/fp_hands_ct_rifle.glb")
        )
        handsCtPistol = FilamentSceneHelpers.loadGlb(
            assetLoader, resourceLoader,
            registry.loadBundledAssetBuffer("models/fp_hands_ct_pistol.glb")
        )
        handsCtKnife = FilamentSceneHelpers.loadGlb(
            assetLoader, resourceLoader,
            registry.loadBundledAssetBuffer("models/fp_hands_ct_knife.glb")
        )
        allHandsSlots[0] = handsTRifle
        allHandsSlots[1] = handsTPistol
        allHandsSlots[2] = handsTKnife
        allHandsSlots[3] = handsCtRifle
        allHandsSlots[4] = handsCtPistol
        allHandsSlots[5] = handsCtKnife
        FilamentSceneHelpers.configureViewmodelRenderables(engine, muzzleFlashModel)
        for (h in allHandsSlots) {
            FilamentSceneHelpers.configureViewmodelRenderables(engine, h)
        }

        // Load GPU-instanced bullet impact marks (48) & brief yellow laser tracers (12)
        val holeInstances = FilamentSceneHelpers.loadInstancedGlb(
            assetLoader, resourceLoader,
            registry.loadBundledAssetBuffer("models/vfx_bullet_hole.glb"),
            count = 48
        )
        for (hole in holeInstances) {
            FilamentSceneHelpers.disableShadowCasting(engine, hole)
            bulletHolePool.add(hole)
        }
        val tracerInstances = FilamentSceneHelpers.loadInstancedGlb(
            assetLoader, resourceLoader,
            registry.loadBundledAssetBuffer("models/vfx_bullet_tracer.glb"),
            count = 12
        )
        for (tr in tracerInstances) {
            FilamentSceneHelpers.configureViewmodelRenderables(engine, tr)
            bulletTracerPool.add(tr)
        }
        reloadDynamicSlots()
    }

    private fun reloadDynamicSlots() {
        // Clear old dynamic assets cleanly (secondary GPU instances detach first, owners destroy shared FilamentAsset)
        destroyModel(plantedBombModel); plantedBombModel = null
        tpWeaponPools.values.forEach { list -> list.forEach { destroyModel(it) } }
        tpWeaponPools.clear()
        fpWeapons.values.forEach { destroyModel(it) }; fpWeapons.clear()
        ctPool.asReversed().forEach { destroyModel(it) }; ctPool.clear()
        tPool.asReversed().forEach { destroyModel(it) }; tPool.clear()

        // Planted C4 bomb .GLB
        plantedBombModel = FilamentSceneHelpers.loadGlb(
            assetLoader, resourceLoader, registry.loadGlbBuffer(ModelSlot.BOMB_C4)
        )

        // All 10 Distinct CS2 Weapons: load EACH weapon ONCE into ONE shared GPU asset with 11 instances:
        // instance[0] = First-Person viewmodel, instances[1..10] = 3rd-Person pool (ZERO duplicate GPU textures!)
        for (weapon in WeaponType.entries) {
            val slot = ModelSlot.forWeapon(weapon)
            val instances = FilamentSceneHelpers.loadInstancedGlb(
                assetLoader, resourceLoader, registry.loadGlbBuffer(slot), count = 11
            )
            if (instances.isNotEmpty()) {
                val fpModel = instances[0]
                FilamentSceneHelpers.configureViewmodelRenderables(engine, fpModel)
                fpWeapons[slot] = fpModel
                val tpList = ArrayList<LoadedGlbModel>((instances.size - 1).coerceAtLeast(0))
                for (i in 1 until instances.size) {
                    val tpModel = instances[i]
                    FilamentSceneHelpers.disableShadowCasting(engine, tpModel)
                    tpList.add(tpModel)
                }
                tpWeaponPools[weapon] = tpList
            }
        }

        // Character pools: 5 CT sharing 1 GPU asset + 5 T sharing 1 GPU asset (~5x less VRAM & load time!)
        val ctInstances = FilamentSceneHelpers.loadInstancedGlb(
            assetLoader, resourceLoader, registry.loadGlbBuffer(ModelSlot.CHARACTER_CT), count = 5
        )
        ctPool.addAll(ctInstances)

        val tInstances = FilamentSceneHelpers.loadInstancedGlb(
            assetLoader, resourceLoader, registry.loadGlbBuffer(ModelSlot.CHARACTER_T), count = 5
        )
        tPool.addAll(tInstances)

        loadedRegistryRevision = registry.revision.value
    }

    private fun ensureMapAndRegistryCurrent() {
        val currentRev = registry.revision.value
        val targetMap = gameEngine.world.mapId
        if (currentRev != loadedRegistryRevision) {
            reloadDynamicSlots()
            loadedMapId = null // force reload map too in case user imported Sketchfab map
        }
        if (loadedMapId != targetMap) {
            destroyModel(mapModel)
            val slot = ModelSlot.forMap(targetMap)
            isCustomMap = registry.hasCustomFile(slot)
            mapModel = FilamentSceneHelpers.loadGlb(assetLoader, resourceLoader, registry.loadGlbBuffer(slot))
            mapModel?.let { m ->
                FilamentSceneHelpers.setAssetVisible(scene, m, true)
                val tm = engine.transformManager
                val rootInst = tm.getInstance(m.asset.root)
                if (isCustomMap) {
                    // Auto-normalize any user-imported Sketchfab map cleanly to 84m arena span!
                    val span = maxOf(m.sizeX, m.sizeZ).coerceAtLeast(0.01f)
                    val s = 84f / span
                    Matrix.setIdentityM(tmpMat, 0)
                    Matrix.scaleM(tmpMat, 0, s, s, s)
                    Matrix.translateM(tmpMat, 0, -m.centerX, -m.minY, -m.centerZ)
                    tm.setTransform(rootInst, tmpMat)
                } else {
                    // Native CS2 Mirage / Dust 2 GLB already aligned 1:1 with floors heightmap!
                    Matrix.setIdentityM(tmpMat, 0)
                    tm.setTransform(rootInst, tmpMat)
                }
            }
            // Swap procedural skybox to match Mirage or Dust 2 atmosphere
            val prevRig = skyRig
            val newRig = FilamentSceneHelpers.createProceduralSkyRig(engine, targetMap)
            scene.skybox = newRig.skybox
            skyRig = newRig
            FilamentSceneHelpers.destroySkyRig(engine, scene, prevRig)

            loadedMapId = targetMap
            lastGraphics = null // Re-apply map-matched atmospheric fog tint
        }
    }

    fun start() {
        if (!isRunning && !isDestroyed) {
            isRunning = true
            lastFrameNs = System.nanoTime()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun stop() {
        isRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        stop()
        if (!::engine.isInitialized) return
        runCatching { displayHelper.detach() }
        runCatching { uiHelper.renderCallback = null }
        runCatching { uiHelper.detach() }
        swapChain?.let {
            runCatching {
                engine.destroySwapChain(it)
                engine.flushAndWait()
            }
            swapChain = null
        }

        destroyModel(mapModel); mapModel = null
        destroyModel(muzzleFlashModel); muzzleFlashModel = null
        destroyModel(plantedBombModel); plantedBombModel = null
        destroyModel(explosionFireball); explosionFireball = null
        destroyModel(explosionSmoke); explosionSmoke = null
        destroyModel(explosionShockwave); explosionShockwave = null
        destroyModel(handsTRifle); handsTRifle = null
        destroyModel(handsTPistol); handsTPistol = null
        destroyModel(handsTKnife); handsTKnife = null
        destroyModel(handsCtRifle); handsCtRifle = null
        destroyModel(handsCtPistol); handsCtPistol = null
        destroyModel(handsCtKnife); handsCtKnife = null
        allHandsSlots.fill(null)
        tpWeaponPools.values.forEach { list -> list.forEach { destroyModel(it) } }
        tpWeaponPools.clear()
        fpWeapons.values.forEach { destroyModel(it) }; fpWeapons.clear()
        ctPool.asReversed().forEach { destroyModel(it) }; ctPool.clear()
        tPool.asReversed().forEach { destroyModel(it) }; tPool.clear()
        bulletHolePool.asReversed().forEach { destroyModel(it) }; bulletHolePool.clear()
        bulletTracerPool.asReversed().forEach { destroyModel(it) }; bulletTracerPool.clear()

        runCatching { resourceLoader.destroy() }
        runCatching { assetLoader.destroy() }
        runCatching { materialProvider.destroyMaterials() }
        runCatching { materialProvider.destroy() }

        view.colorGrading?.let {
            runCatching {
                view.colorGrading = null
                engine.destroyColorGrading(it)
            }
        }
        FilamentSceneHelpers.destroySkyRig(engine, scene, skyRig)
        skyRig = null
        scene.skybox?.let {
            runCatching {
                scene.skybox = null
                engine.destroySkybox(it)
            }
        }
        scene.indirectLight?.let {
            runCatching {
                scene.indirectLight = null
                engine.destroyIndirectLight(it)
            }
        }
        runCatching { scene.removeEntity(sunEntity); engine.destroyEntity(sunEntity); EntityManager.get().destroy(sunEntity) }
        runCatching { scene.removeEntity(fxLightEntity); engine.destroyEntity(fxLightEntity); EntityManager.get().destroy(fxLightEntity) }
        runCatching { engine.destroyRenderer(renderer) }
        runCatching { engine.destroyView(view) }
        runCatching { engine.destroyScene(scene) }
        runCatching { engine.destroyCameraComponent(camera.entity) }
        runCatching { EntityManager.get().destroy(camera.entity) }
        runCatching { engine.destroy() }
    }

    private fun destroyModel(model: LoadedGlbModel?) {
        if (model == null) return
        FilamentSceneHelpers.setAssetVisible(scene, model, false)
        if (model.ownsAsset) {
            runCatching { assetLoader.destroyAsset(model.asset) }
        }
    }

    private fun applySurfaceFrameRate(surface: Surface?, targetFps: Int) {
        if (surface == null || !surface.isValid) return
        if (appliedSurfaceFps == targetFps) return
        appliedSurfaceFps = targetFps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                surface.setFrameRate(
                    targetFps.toFloat(),
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS
                )
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                surface.setFrameRate(
                    targetFps.toFloat(),
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                )
            }
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning || isDestroyed) return
        Choreographer.getInstance().postFrameCallback(this)

        val gfx = gameEngine.settings.graphics
        val targetFps = gfx.targetFps.fps
        if (appliedSurfaceFps != targetFps) {
            applySurfaceFrameRate(activeSurface, targetFps)
        }
        // Frame pacing for 60 / 90 FPS caps; 120 FPS runs completely uncapped for maximum smoothness
        if (targetFps < 120 && lastRenderedFrameNs > 0L) {
            val minIntervalNs = (1_000_000_000L / targetFps) - 900_000L
            if (frameTimeNanos - lastRenderedFrameNs < minIntervalNs) {
                return
            }
        }
        lastRenderedFrameNs = frameTimeNanos

        val dt = ((frameTimeNanos - lastFrameNs) / 1_000_000_000.0f).coerceIn(0.001f, 0.05f)
        lastFrameNs = frameTimeNanos

        gameEngine.update(dt)
        ensureMapAndRegistryCurrent()

        if (gfx != lastGraphics) {
            FilamentSceneHelpers.applyGraphicsSettings(engine, view, gfx, gameEngine.world.mapId)
            lastGraphics = gfx
        }

        updateCamera()
        updateBots(dt)
        updateFirstPersonWeaponAndMuzzle()
        updateBulletVfx()
        updatePlantedBombAndExplosion()

        val sc = swapChain ?: return
        if (uiHelper.isReadyToRender && renderer.beginFrame(sc, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    private fun updateCamera() {
        val gfx = gameEngine.settings.graphics
        val nativeAspect = surfaceWidth.toDouble() / surfaceHeight.toDouble().coerceAtLeast(1.0)
        // CS2 4:3 Stretched mode renders a 4:3 frustum stretched cleanly across the full screen!
        val aspect = if (gfx.aspectRatio == AspectRatioMode.RATIO_4_3) 4.0 / 3.0 else nativeAspect
        // Locked default 65° vertical FOV
        val baseFov = GraphicsSettings.FIXED_FOV
        val zoomTarget = gameEngine.activeWeapon.adsZoomFactor
        val liveFov = if (gameEngine.isSpectatingAlly) {
            baseFov.toDouble()
        } else {
            (baseFov * (1f - gameEngine.adsBlend * (1f - zoomTarget))).toDouble()
        }
        // 0.015m near plane paired with VM_SCALE=0.24f guarantees zero first-person weapon wall clipping
        camera.setProjection(liveFov, aspect, 0.015, 220.0, Camera.Fov.VERTICAL)

        // Apply dramatic C4 explosion camera shake!
        val shake = gameEngine.cameraShake
        val shakeX = if (shake > 0.01f) sin(gameEngine.timeSec * 58f) * 0.24f * shake else 0f
        val shakeY = if (shake > 0.01f) cos(gameEngine.timeSec * 49f) * 0.20f * shake else 0f

        if (gameEngine.awaitingTeamSelection) {
            val sway = sin(gameEngine.timeSec * 0.28f) * 3.2f
            val isDust = gameEngine.world.mapId == ArenaMapId.DUST2
            val eyeX = (if (isDust) -12f else -14f) + sway
            val eyeY = if (isDust) 12.5f else 11.8f
            val eyeZ = if (isDust) 16f else 15f
            val lookX = if (isDust) 4f else 2f
            val lookY = if (isDust) 1.8f else 2.0f
            val lookZ = if (isDust) -4f else -2f
            camera.lookAt(
                eyeX.toDouble(), eyeY.toDouble(), eyeZ.toDouble(),
                lookX.toDouble(), lookY.toDouble(), lookZ.toDouble(),
                0.0, 1.0, 0.0
            )
            return
        }

        if (gameEngine.isSpectatingAlly) {
            camera.lookAt(
                (gameEngine.spectateCamX + shakeX).toDouble(),
                (gameEngine.spectateCamY + shakeY).toDouble(),
                gameEngine.spectateCamZ.toDouble(),
                gameEngine.spectateLookX.toDouble(),
                gameEngine.spectateLookY.toDouble(),
                gameEngine.spectateLookZ.toDouble(),
                0.0, 1.0, 0.0
            )
            return
        }

        val camX = gameEngine.playerX + shakeX
        val camY = gameEngine.playerY + gameEngine.eyeHeight + shakeY
        val camZ = gameEngine.playerZ

        val cosP = cos(gameEngine.playerPitchRad)
        val dirX = -sin(gameEngine.playerYawRad) * cosP
        val dirY = sin(gameEngine.playerPitchRad)
        val dirZ = -cos(gameEngine.playerYawRad) * cosP

        camera.lookAt(
            camX.toDouble(), camY.toDouble(), camZ.toDouble(),
            (camX + dirX).toDouble(), (camY + dirY).toDouble(), (camZ + dirZ).toDouble(),
            0.0, 1.0, 0.0
        )

        // Cache camera world transform matrix for attaching the 1st-person weapon .GLB cleanly!
        Matrix.setIdentityM(camMat, 0)
        Matrix.translateM(camMat, 0, camX, camY, camZ)
        Matrix.rotateM(camMat, 0, Math.toDegrees(gameEngine.playerYawRad.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(camMat, 0, Math.toDegrees(gameEngine.playerPitchRad.toDouble()).toFloat(), 1f, 0f, 0f)
    }

    private fun acquireThirdPersonWeapon(weapon: WeaponType): LoadedGlbModel? {
        val pool = tpWeaponPools[weapon] ?: return null
        val used = tpWeaponUsedCount[weapon] ?: 0
        if (used >= pool.size) return null
        tpWeaponUsedCount[weapon] = used + 1
        return pool[used]
    }

    private fun placeThirdPersonWeapon(
        weapon: WeaponType,
        charX: Float,
        charY: Float,
        charZ: Float,
        charYawDeg: Float,
        timeSec: Float,
        isMoving: Boolean,
        deathProgress: Float,
        isShooting: Boolean,
        aimPitchDeg: Float = 0f,
        skinId: String? = null
    ) {
        val wModel = acquireThirdPersonWeapon(weapon) ?: return
        FilamentSceneHelpers.applyWeaponSkin(wModel, skinId)
        FilamentSceneHelpers.setAssetVisible(scene, wModel, true)
        rigAnimator.buildHeldWeaponMatrix(
            out = tmpMat,
            weapon = weapon,
            charX = charX,
            charY = charY,
            charZ = charZ,
            charYawDeg = charYawDeg,
            timeSec = timeSec,
            isMoving = isMoving,
            deathProgress = deathProgress,
            isShooting = isShooting,
            aimPitchDeg = aimPitchDeg
        )
        engine.transformManager.setTransform(engine.transformManager.getInstance(wModel.rootEntity), tmpMat)
    }

    private fun updateBots(dt: Float) {
        val tm = engine.transformManager
        var ctIdx = 0
        var tIdx = 0
        val px = gameEngine.playerX
        val pz = gameEngine.playerZ
        tpWeaponUsedCount.clear()
        activeTurnKeys.clear()

        // 1. Render remote human players FIRST so friends/opponents always get priority in character pools!
        if (gameEngine.matchConfig.isMultiplayer) {
            gameEngine.fillRemotePlayersSnapshot(remoteSnapshotBuf)
            for (idx in 0 until remoteSnapshotBuf.size) {
                val rp = remoteSnapshotBuf[idx]
                val pool = if (rp.team == TeamSide.CT) ctPool else tPool
                val slotIdx = if (rp.team == TeamSide.CT) ctIdx++ else tIdx++
                if (slotIdx >= pool.size) continue
                val model = pool[slotIdx]
                val charSlot = if (rp.team == TeamSide.CT) ModelSlot.CHARACTER_CT else ModelSlot.CHARACTER_T
                val isCustomChar = registry.hasCustomFile(charSlot)
                // Local camera looks along -Z at yaw=0 while rigged character meshes face +Z natively -> +180° aligns eyes directly toward us!
                val visualYawDeg = rp.yawDeg + 180f
                val turnKey = rp.userId
                activeTurnKeys.add(turnKey)
                val turnState = turnStates.getOrPut(turnKey) { TurnShuffleState() }
                turnState.update(visualYawDeg, dt, isMoving = rp.isMoving, isDead = rp.isDead)

                FilamentSceneHelpers.setAssetVisible(scene, model, true)
                FilamentSceneHelpers.buildCharacterMatrix(
                    out = tmpMat,
                    model = model,
                    x = rp.x,
                    y = rp.y,
                    z = rp.z,
                    yawDeg = visualYawDeg,
                    deathProgress = rp.deathProgress,
                    targetHeight = 1.76f,
                    isCustomImported = isCustomChar
                )
                tm.setTransform(tm.getInstance(model.rootEntity), tmpMat)

                val animator = model.instance.animator
                if (animator.animationCount > 0) {
                    val settledCorpse = rp.isDead && rp.deathProgress >= 0.995f
                    if (!settledCorpse || !model.deathPoseSettled) {
                        if (rp.isDead) {
                            if (model.deathAnimIdx >= 0) {
                                val dur = animator.getAnimationDuration(model.deathAnimIdx).coerceAtLeast(0.1f)
                                animator.applyAnimation(model.deathAnimIdx, rp.deathProgress.coerceIn(0f, 0.995f) * dur)
                            }
                            model.deathPoseSettled = settledCorpse
                        } else {
                            model.deathPoseSettled = false
                            val animIdx = if (rp.isMoving) model.runAnimIdx else model.idleAnimIdx
                            if (animIdx >= 0) {
                                val dur = animator.getAnimationDuration(animIdx).coerceAtLeast(0.1f)
                                animator.applyAnimation(animIdx, (gameEngine.timeSec + idx * 0.41f) % dur)
                            }
                        }
                        if (!isCustomChar) {
                            rigAnimator.applyProceduralPose(
                                engine = engine,
                                handles = model.rigHandles,
                                weapon = rp.weapon,
                                turnState = turnState,
                                timeSec = gameEngine.timeSec + idx * 0.41f,
                                isMoving = rp.isMoving,
                                isDead = rp.isDead,
                                deathProgress = rp.deathProgress,
                                isShooting = rp.isShooting,
                                aimPitchDeg = rp.pitchDeg
                            )
                        }
                        animator.updateBoneMatrices()
                    }
                }

                // Render remote player's real equipped 3D weapon & weapon skin locked directly in BOTH hands!
                placeThirdPersonWeapon(
                    weapon = rp.weapon,
                    charX = rp.x,
                    charY = rp.y,
                    charZ = rp.z,
                    charYawDeg = visualYawDeg,
                    timeSec = gameEngine.timeSec + idx * 0.41f,
                    isMoving = rp.isMoving,
                    deathProgress = rp.deathProgress,
                    isShooting = rp.isShooting,
                    aimPitchDeg = rp.pitchDeg,
                    skinId = rp.activeSkinId
                )
            }
        }

        // 2. Render AI bots (with bidirectional turn-in-place foot shuffling, 2-handed weapon IK, aim pitch & skeletal Death!)
        val playerEyeY = gameEngine.playerY + gameEngine.eyeHeight
        val botsList = gameEngine.bots
        for (bi in 0 until botsList.size) {
            val b = botsList[bi]
            val pool = if (b.team == TeamSide.CT) ctPool else tPool
            val slotIdx = if (b.team == TeamSide.CT) ctIdx++ else tIdx++
            if (slotIdx >= pool.size) continue
            val model = pool[slotIdx]

            val charSlot = if (b.team == TeamSide.CT) ModelSlot.CHARACTER_CT else ModelSlot.CHARACTER_T
            val isCustomChar = registry.hasCustomFile(charSlot)
            val turnKey = if (b.id in botTurnKeys.indices) botTurnKeys[b.id] else "bot_${b.id}"
            activeTurnKeys.add(turnKey)
            val turnState = turnStates.getOrPut(turnKey) { TurnShuffleState() }
            turnState.update(b.visualYawDeg, dt, isMoving = b.isMoving, isDead = b.isDead)
            val botShooting = !b.isDead && b.fireCooldown > b.weapon.fireIntervalSec * 0.55f && b.hasLineOfSight
            val botAnimClock = gameEngine.timeSec + b.id * 0.37f

            FilamentSceneHelpers.setAssetVisible(scene, model, true)
            FilamentSceneHelpers.buildCharacterMatrix(
                out = tmpMat,
                model = model,
                x = b.x,
                y = b.y,
                z = b.z,
                yawDeg = b.visualYawDeg,
                deathProgress = b.deathProgress,
                targetHeight = 1.76f,
                isCustomImported = isCustomChar
            )
            tm.setTransform(tm.getInstance(model.rootEntity), tmpMat)

            val dx = b.x - px
            val dz = b.z - pz
            val distSq = dx * dx + dz * dz
            val botAimPitchDeg = if (!b.isDead && b.hasLineOfSight && b.team != gameEngine.matchConfig.playerSide) {
                val horiz = kotlin.math.sqrt(distSq).coerceAtLeast(0.5f)
                val dy = playerEyeY - (b.y + 1.48f)
                Math.toDegrees(kotlin.math.atan2(dy.toDouble(), horiz.toDouble())).toFloat().coerceIn(-38f, 38f)
            } else 0f

            val animator = model.instance.animator
            if (animator.animationCount > 0 && distSq < 44f * 44f) {
                val settledCorpse = b.isDead && b.deathProgress >= 0.995f
                if (!settledCorpse || !model.deathPoseSettled) {
                    if (b.isDead) {
                        if (model.deathAnimIdx >= 0) {
                            val dur = animator.getAnimationDuration(model.deathAnimIdx).coerceAtLeast(0.1f)
                            animator.applyAnimation(model.deathAnimIdx, b.deathProgress.coerceIn(0f, 0.995f) * dur)
                        }
                        model.deathPoseSettled = settledCorpse
                    } else {
                        model.deathPoseSettled = false
                        val animIdx = when {
                            !b.isMoving -> model.idleAnimIdx
                            kotlin.math.abs(b.moveForwardRatio) > 0.65f -> model.runAnimIdx
                            else -> model.walkAnimIdx
                        }
                        if (animIdx >= 0) {
                            val dur = animator.getAnimationDuration(animIdx).coerceAtLeast(0.1f)
                            animator.applyAnimation(animIdx, botAnimClock % dur)
                        }
                    }
                    if (!isCustomChar) {
                        rigAnimator.applyProceduralPose(
                            engine = engine,
                            handles = model.rigHandles,
                            weapon = b.weapon,
                            turnState = turnState,
                            timeSec = botAnimClock,
                            isMoving = b.isMoving,
                            isDead = b.isDead,
                            deathProgress = b.deathProgress,
                            isShooting = botShooting,
                            aimPitchDeg = botAimPitchDeg
                        )
                    }
                    animator.updateBoneMatrices()
                }
            }

            // Render every teammate & enemy bot's real bought/equipped 3D weapon firmly inside BOTH hands!
            if (distSq < 48f * 48f) {
                placeThirdPersonWeapon(
                    weapon = b.weapon,
                    charX = b.x,
                    charY = b.y,
                    charZ = b.z,
                    charYawDeg = b.visualYawDeg,
                    timeSec = botAnimClock,
                    isMoving = b.isMoving,
                    deathProgress = b.deathProgress,
                    isShooting = botShooting,
                    aimPitchDeg = botAimPitchDeg
                )
            }
        }

        // Prune disconnected player/bot turn shuffle states so zero stale entries accumulate
        if (turnStates.size > activeTurnKeys.size + 4) {
            turnStates.keys.retainAll(activeTurnKeys)
        }

        // Hide unused pool instances (characters & 3rd-person weapons)
        for (i in ctIdx until ctPool.size) FilamentSceneHelpers.setAssetVisible(scene, ctPool[i], false)
        for (i in tIdx until tPool.size) FilamentSceneHelpers.setAssetVisible(scene, tPool[i], false)
        for ((wType, list) in tpWeaponPools) {
            val used = tpWeaponUsedCount[wType] ?: 0
            for (i in used until list.size) {
                FilamentSceneHelpers.setAssetVisible(scene, list[i], false)
            }
        }
    }

    private fun updateFirstPersonWeaponAndMuzzle() {
        val tm = engine.transformManager
        val hud = gameEngine.latestHud
        val w = gameEngine.activeWeapon
        val hideWeapon = gameEngine.awaitingTeamSelection || hud.isPlayerDead || (w.hasSniperScope && gameEngine.adsBlend > 0.52f)
        val activeSlot = ModelSlot.forWeapon(w)
        val isCustomWeapon = registry.hasCustomFile(activeSlot)

        // Compute smooth CS2 per-weapon equip/draw animation offsets (u: 1 -> 0 as weapon finishes drawing)
        val ep = gameEngine.equipProgress
        val u = (1f - ep).coerceIn(0f, 1f)
        val ease = u * u * (3f - 2f * u)
        val midPulse = sin(ep * Math.PI).toFloat().coerceAtLeast(0f)

        var eqX = 0f
        var eqY = 0f
        var eqZ = 0f
        var eqYaw = 0f
        var eqPitch = 0f
        var eqRoll = 0f
        var eqWeaponOnlyRoll = 0f

        if (u > 0.001f) {
            when (w) {
                WeaponType.KNIFE -> {
                    eqX = 0.08f * ease
                    eqY = -0.26f * ease
                    eqZ = 0.07f * ease
                    eqYaw = -18f * ease
                    eqPitch = -32f * ease
                    eqRoll = -14f * ease
                    eqWeaponOnlyRoll = 0f
                }
                WeaponType.USP_S, WeaponType.GLOCK_18, WeaponType.DESERT_EAGLE -> {
                    val rackBump = if (ep in 0.42f..0.78f) {
                        sin(((ep - 0.42f) / 0.36f) * Math.PI).toFloat()
                    } else 0f
                    eqX = 0.05f * ease
                    eqY = -0.24f * ease + rackBump * 0.015f
                    eqZ = 0.08f * ease + rackBump * 0.025f
                    eqPitch = -48f * ease + rackBump * 12f
                    eqRoll = -18f * ease + rackBump * 14f
                }
                WeaponType.MP9, WeaponType.P90 -> {
                    eqX = 0.06f * ease
                    eqY = -0.25f * ease
                    eqZ = 0.09f * ease
                    eqPitch = -36f * ease
                    eqRoll = -26f * ease + midPulse * 14f
                }
                WeaponType.AK47, WeaponType.M4A1_S -> {
                    eqX = 0.07f * ease
                    eqY = -0.30f * ease
                    eqZ = 0.11f * ease
                    eqYaw = -10f * ease
                    eqPitch = -38f * ease
                    eqRoll = -14f * ease + midPulse * 28f
                }
                WeaponType.AWP -> {
                    eqX = 0.09f * ease
                    eqY = -0.34f * ease
                    eqZ = 0.13f * ease
                    eqYaw = -12f * ease
                    eqPitch = -44f * ease
                    eqRoll = -16f * ease + midPulse * 24f
                }
                WeaponType.C4_BOMB -> {
                    eqY = -0.26f * ease
                    eqZ = 0.08f * ease
                    eqPitch = -35f * ease
                }
            }
        }

        val ads = gameEngine.adsBlend
        val bobX = sin(gameEngine.walkBobPhase) * 0.011f * gameEngine.moveSpeedRatio * (1f - ads * 0.72f)
        val bobY = kotlin.math.abs(cos(gameEngine.walkBobPhase)) * 0.013f * gameEngine.moveSpeedRatio * (1f - ads * 0.72f)
        val recoilZ = gameEngine.recoilKick * 0.048f
        val recoilPitch = gameEngine.recoilKick * 7.2f
        val knifeSwing = if (w == WeaponType.KNIFE && gameEngine.knifeSwingProgress > 0f) {
            sin(gameEngine.knifeSwingProgress * Math.PI).toFloat()
        } else 0f
        val boltTilt = if (w == WeaponType.AWP && gameEngine.boltProgress > 0f) {
            sin(gameEngine.boltProgress * Math.PI).toFloat()
        } else 0f

        // Evaluate distinct multi-stage per-weapon reload & inspect animations
        if (hud.isReloading) {
            ViewmodelAnimator.computeReloadPose(w, hud.reloadProgress, reloadDelta)
        } else {
            reloadDelta.reset()
        }
        if (hud.isInspecting && !hud.isReloading && !hud.isEquipping) {
            ViewmodelAnimator.computeInspectPose(w, hud.inspectProgress, inspectDelta)
        } else {
            inspectDelta.reset()
        }

        // Calibrated 1:1 CS2 hip offsets for our meter-authored weapons (grip at origin, barrel along -Z)
        val hipX = when (w) {
            WeaponType.C4_BOMB -> 0.04f
            WeaponType.KNIFE -> 0.19f
            WeaponType.USP_S, WeaponType.GLOCK_18, WeaponType.DESERT_EAGLE -> 0.17f
            WeaponType.AWP -> 0.21f
            else -> 0.19f
        }
        val hipY = when (w) {
            WeaponType.C4_BOMB -> -0.18f
            WeaponType.KNIFE -> -0.19f
            WeaponType.USP_S, WeaponType.GLOCK_18, WeaponType.DESERT_EAGLE -> -0.18f
            WeaponType.AWP -> -0.21f
            else -> -0.20f
        }
        val hipZ = when (w) {
            WeaponType.C4_BOMB -> -0.34f
            WeaponType.KNIFE -> -0.32f
            WeaponType.USP_S, WeaponType.GLOCK_18, WeaponType.DESERT_EAGLE -> -0.36f
            WeaponType.MP9, WeaponType.P90 -> -0.35f
            WeaponType.AWP -> -0.36f
            else -> -0.35f
        }
        val adsY = when (w) {
            WeaponType.USP_S, WeaponType.GLOCK_18, WeaponType.DESERT_EAGLE -> -0.115f
            WeaponType.P90, WeaponType.MP9 -> -0.135f
            else -> -0.140f
        }

        // Subtle realistic idle breathing figure-8 sway (dampened smoothly during ADS)
        val idleBreathScale = (1f - ads * 0.78f)
        val breathX = sin(gameEngine.timeSec * 1.15f) * 0.0024f * idleBreathScale
        val breathY = sin(gameEngine.timeSec * 2.30f) * 0.0028f * idleBreathScale

        val offX = hipX * (1f - ads) + bobX + breathX + gameEngine.weaponSwayX - knifeSwing * 0.22f + eqX + reloadDelta.tx + inspectDelta.tx
        val offY = (hipY * (1f - ads) + adsY * ads) - bobY + breathY + gameEngine.weaponSwayY - gameEngine.landingDip + knifeSwing * 0.05f + eqY + reloadDelta.ty + inspectDelta.ty
        val offZ = hipZ + recoilZ - knifeSwing * 0.14f + eqZ + reloadDelta.tz + inspectDelta.tz

        val yawDeg = eqYaw + gameEngine.weaponSwayX * 145f + knifeSwing * 46f + boltTilt * 6f + reloadDelta.yawDeg + inspectDelta.yawDeg
        val pitchDeg = recoilPitch + gameEngine.weaponSwayY * 130f - gameEngine.landingDip * 90f + eqPitch + boltTilt * 10f + reloadDelta.pitchDeg + inspectDelta.pitchDeg
        val rollDeg = (-knifeSwing * 36f) + gameEngine.strafeRoll + eqRoll + boltTilt * 22f + reloadDelta.rollDeg + inspectDelta.rollDeg

        // Apply perspective-invariant VM_SCALE so the weapon renders identically on screen
        // while staying physically inside the 0.36m player collision cylinder (ZERO wall clipping!)
        val vmX = offX * VM_SCALE
        val vmY = offY * VM_SCALE
        val vmZ = offZ * VM_SCALE

        // Physical Bolt/Slide recoil blowback + reload bolt pull + magazine detachment!
        val gunfireBoltZ = when (w) {
            WeaponType.KNIFE, WeaponType.C4_BOMB -> 0f
            WeaponType.AWP -> boltTilt * 0.082f
            WeaponType.USP_S, WeaponType.GLOCK_18, WeaponType.DESERT_EAGLE -> (gameEngine.recoilKick * 0.048f).coerceAtMost(0.046f)
            else -> (gameEngine.recoilKick * 0.062f).coerceAtMost(0.068f)
        }
        val activeBoltZ = maxOf(gunfireBoltZ, reloadDelta.boltOffsetZ)

        val equippedSkinForActive = gameEngine.localEquippedSkins[w.name]
        for ((slot, model) in fpWeapons) {
            val visible = !hideWeapon && slot == activeSlot
            FilamentSceneHelpers.setAssetVisible(scene, model, visible)
            if (!visible) continue
            FilamentSceneHelpers.applyWeaponSkin(model, equippedSkinForActive)

            if (!isCustomWeapon) {
                FilamentSceneHelpers.buildDirectWeaponMatrix(
                    out = localMat,
                    tx = vmX,
                    ty = vmY,
                    tz = vmZ,
                    yawDeg = yawDeg,
                    pitchDeg = pitchDeg,
                    rollDeg = rollDeg,
                    scale = VM_SCALE,
                    weaponOnlyYawDeg = inspectDelta.weaponOnlyYawDeg,
                    weaponOnlyPitchDeg = inspectDelta.weaponOnlyPitchDeg,
                    weaponOnlyRollDeg = inspectDelta.weaponOnlyRollDeg + eqWeaponOnlyRoll
                )
                // Animate physical 3D Bolt/Slide and Magazine child nodes inside the weapon GLB!
                FilamentSceneHelpers.applyWeaponPartOffsets(
                    engine = engine,
                    model = model,
                    boltOffsetZ = activeBoltZ,
                    magTx = reloadDelta.magTx,
                    magTy = reloadDelta.magTy,
                    magTz = reloadDelta.magTz,
                    magPitchDeg = reloadDelta.magPitchDeg
                )
            } else {
                val targetSpan = when (w) {
                    WeaponType.AWP -> 0.78f
                    WeaponType.AK47, WeaponType.M4A1_S -> 0.64f
                    WeaponType.MP9, WeaponType.P90 -> 0.46f
                    WeaponType.USP_S, WeaponType.GLOCK_18, WeaponType.DESERT_EAGLE -> 0.28f
                    WeaponType.KNIFE -> 0.28f
                    WeaponType.C4_BOMB -> 0.24f
                } * VM_SCALE
                val autoYaw = when {
                    w == WeaponType.C4_BOMB -> 180f
                    w == WeaponType.KNIFE -> 165f
                    model.sizeX > model.sizeZ * 1.15f -> 90f
                    else -> 180f
                }
                FilamentSceneHelpers.buildNormalizedPropMatrix(
                    out = localMat,
                    model = model,
                    targetMaxExtent = targetSpan,
                    tx = vmX,
                    ty = vmY,
                    tz = (offZ - 0.08f) * VM_SCALE,
                    yawDeg = autoYaw + yawDeg + inspectDelta.weaponOnlyYawDeg,
                    pitchDeg = pitchDeg + inspectDelta.weaponOnlyPitchDeg,
                    rollDeg = rollDeg + inspectDelta.weaponOnlyRollDeg + eqWeaponOnlyRoll,
                    anchorBottom = false
                )
            }
            Matrix.multiplyMM(finalMat, 0, camMat, 0, localMat, 0)
            tm.setTransform(tm.getInstance(model.rootEntity), finalMat)
        }

        // Synced Faction First-Person Tactical Hands & Gloves (zero per-frame allocs, NEVER inherits weaponOnly spin!)
        val isT = gameEngine.matchConfig.playerSide == TeamSide.T
        val activeHands: LoadedGlbModel? = if (hideWeapon || isCustomWeapon || w == WeaponType.C4_BOMB) {
            null
        } else {
            when (w) {
                WeaponType.KNIFE -> if (isT) handsTKnife else handsCtKnife
                WeaponType.USP_S, WeaponType.GLOCK_18, WeaponType.DESERT_EAGLE -> if (isT) handsTPistol else handsCtPistol
                else -> if (isT) handsTRifle else handsCtRifle
            }
        }
        for (i in 0 until allHandsSlots.size) {
            val h = allHandsSlots[i]
            val vis = (h != null && h === activeHands)
            FilamentSceneHelpers.setAssetVisible(scene, h, vis)
            if (vis && h != null) {
                FilamentSceneHelpers.buildDirectWeaponMatrix(
                    out = handLocalMat,
                    tx = (offX + reloadDelta.handTx) * VM_SCALE,
                    ty = (offY + reloadDelta.handTy) * VM_SCALE,
                    tz = (offZ + reloadDelta.handTz) * VM_SCALE,
                    yawDeg = yawDeg,
                    pitchDeg = pitchDeg + reloadDelta.handPitchDeg,
                    rollDeg = rollDeg + reloadDelta.handRollDeg,
                    scale = VM_SCALE
                )
                Matrix.multiplyMM(finalMat, 0, camMat, 0, handLocalMat, 0)
                tm.setTransform(tm.getInstance(h.rootEntity), finalMat)
            }
        }

        // Crisp 45ms 3D Muzzle Flash (.GLB) anchored to exact weapon barrel tip (scaled in viewmodel space!)
        val flashAmt = gameEngine.muzzleFlashTimer
        val showFlash = !hideWeapon && !gameEngine.isEquipping && flashAmt > 0.08f &&
            w != WeaponType.KNIFE && w != WeaponType.C4_BOMB
        FilamentSceneHelpers.setAssetVisible(scene, muzzleFlashModel, showFlash)
        if (showFlash && muzzleFlashModel != null) {
            val fxX = offX * VM_SCALE
            val fxY = (offY + 0.072f) * VM_SCALE
            val fxZ = (offZ + w.muzzleOffsetZ) * VM_SCALE
            val flashSize = (0.18f + flashAmt * 0.24f) * (if (w.isSuppressed) 0.42f else 1.0f) * VM_SCALE
            FilamentSceneHelpers.buildNormalizedPropMatrix(
                out = localMat,
                model = muzzleFlashModel!!,
                targetMaxExtent = flashSize,
                tx = fxX,
                ty = fxY,
                tz = fxZ,
                yawDeg = 0f,
                pitchDeg = 0f,
                rollDeg = gameEngine.muzzleFlashRoll,
                anchorBottom = false
            )
            Matrix.multiplyMM(finalMat, 0, camMat, 0, localMat, 0)
            tm.setTransform(tm.getInstance(muzzleFlashModel!!.asset.root), finalMat)
        }
    }

    /**
     * Renders persistent 3D bullet impact holes flush on struck walls/floors and
     * micro-flash bright yellow laser bullet tracers shooting out of weapon muzzles.
     */
    private fun updateBulletVfx() {
        val tm = engine.transformManager

        // 1. Bullet Impact Decals (local +Z faces along outward surface normal)
        val impacts = gameEngine.bulletImpacts
        val holeCount = minOf(impacts.size, bulletHolePool.size)
        for (i in 0 until bulletHolePool.size) {
            val model = bulletHolePool[i]
            val vis = i < holeCount
            FilamentSceneHelpers.setAssetVisible(scene, model, vis)
            if (!vis) continue
            val imp = impacts[i]
            // Construct orthonormal basis (Right, Up, Forward=Normal)
            val nzX = imp.nx; val nzY = imp.ny; val nzZ = imp.nz
            val refX: Float; val refY: Float; val refZ: Float
            if (kotlin.math.abs(nzY) > 0.92f) {
                refX = 1f; refY = 0f; refZ = 0f
            } else {
                refX = 0f; refY = 1f; refZ = 0f
            }
            // Right = normalize(ref × normal)
            var rx = refY * nzZ - refZ * nzY
            var ry = refZ * nzX - refX * nzZ
            var rz = refX * nzY - refY * nzX
            val rLen = kotlin.math.sqrt(rx * rx + ry * ry + rz * rz).coerceAtLeast(0.0001f)
            rx /= rLen; ry /= rLen; rz /= rLen
            // Up = normal × Right
            val ux = nzY * rz - nzZ * ry
            val uy = nzZ * rx - nzX * rz
            val uz = nzX * ry - nzY * rx
            // Apply random roll in tangent plane so every bullet hole looks distinct
            val rad = Math.toRadians(imp.rollDeg.toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            val bx = rx * c + ux * s; val by = ry * c + uy * s; val bz = rz * c + uz * s
            val tx = -rx * s + ux * c; val ty = -ry * s + uy * c; val tz = -rz * s + uz * c

            // Offset +0.015m along outward surface normal to eliminate z-fighting cleanly
            tmpMat[0] = bx; tmpMat[1] = by; tmpMat[2] = bz; tmpMat[3] = 0f
            tmpMat[4] = tx; tmpMat[5] = ty; tmpMat[6] = tz; tmpMat[7] = 0f
            tmpMat[8] = nzX; tmpMat[9] = nzY; tmpMat[10] = nzZ; tmpMat[11] = 0f
            tmpMat[12] = imp.x + nzX * 0.015f
            tmpMat[13] = imp.y + nzY * 0.015f
            tmpMat[14] = imp.z + nzZ * 0.015f
            tmpMat[15] = 1f
            tm.setTransform(tm.getInstance(model.rootEntity), tmpMat)
        }

        // 2. Micro-Flash Yellow Laser Bullet Tracers (local -Z spans 0..-1.0m from muzzle to impact!)
        val tracers = gameEngine.bulletTracers
        val tracerCount = minOf(tracers.size, bulletTracerPool.size)
        for (i in 0 until bulletTracerPool.size) {
            val model = bulletTracerPool[i]
            val vis = i < tracerCount
            FilamentSceneHelpers.setAssetVisible(scene, model, vis)
            if (!vis) continue
            val tr = tracers[i]
            val dx = tr.endX - tr.startX
            val dy = tr.endY - tr.startY
            val dz = tr.endZ - tr.startZ
            val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.05f)
            // Mesh extends along -Z, so local +Z points BACKWARD (-dir)
            val fX = dx / len; val fY = dy / len; val fZ = dz / len
            val zX = -fX; val zY = -fY; val zZ = -fZ
            val refX: Float; val refY: Float; val refZ: Float
            if (kotlin.math.abs(zY) > 0.92f) {
                refX = 1f; refY = 0f; refZ = 0f
            } else {
                refX = 0f; refY = 1f; refZ = 0f
            }
            var rx = refY * zZ - refZ * zY
            var ry = refZ * zX - refX * zZ
            var rz = refX * zY - refY * zX
            val rLen = kotlin.math.sqrt(rx * rx + ry * ry + rz * rz).coerceAtLeast(0.0001f)
            rx /= rLen; ry /= rLen; rz /= rLen
            val ux = zY * rz - zZ * ry
            val uy = zZ * rx - zX * rz
            val uz = zX * ry - zY * rx

            val lifeFrac = (tr.lifeSec / tr.maxLifeSec.coerceAtLeast(0.001f)).coerceIn(0.25f, 1.0f)
            val widthScale = 0.75f + 0.55f * lifeFrac
            tmpMat[0] = rx * widthScale; tmpMat[1] = ry * widthScale; tmpMat[2] = rz * widthScale; tmpMat[3] = 0f
            tmpMat[4] = ux * widthScale; tmpMat[5] = uy * widthScale; tmpMat[6] = uz * widthScale; tmpMat[7] = 0f
            tmpMat[8] = zX * len; tmpMat[9] = zY * len; tmpMat[10] = zZ * len; tmpMat[11] = 0f
            tmpMat[12] = tr.startX
            tmpMat[13] = tr.startY
            tmpMat[14] = tr.startZ
            tmpMat[15] = 1f
            tm.setTransform(tm.getInstance(model.rootEntity), tmpMat)
        }
    }

    private fun updatePlantedBombAndExplosion() {
        val tm = engine.transformManager
        val lm = engine.lightManager
        val fxLightInst = lm.getInstance(fxLightEntity)

        // 1. Planted C4 physical .GLB model on the ground (prominent 48cm size so CT spots it immediately!)
        val showC4 = gameEngine.bombState == BombState.PLANTED
        FilamentSceneHelpers.setAssetVisible(scene, plantedBombModel, showC4)
        if (showC4 && plantedBombModel != null) {
            FilamentSceneHelpers.buildNormalizedPropMatrix(
                out = tmpMat,
                model = plantedBombModel!!,
                targetMaxExtent = 0.48f,
                tx = gameEngine.plantedBombX,
                ty = gameEngine.plantedBombY + 0.03f,
                tz = gameEngine.plantedBombZ,
                yawDeg = 35f,
                anchorBottom = true
            )
            tm.setTransform(tm.getInstance(plantedBombModel!!.asset.root), tmpMat)
        }

        // 2. Multi-stage 3D C4 Explosion VFX (Expanding Fireball + Shockwave Ring + Rising Smoke Plume + Blinding Light!)
        val exp = gameEngine.explosion
        val showExplosion = exp.active
        FilamentSceneHelpers.setAssetVisible(scene, explosionFireball, showExplosion)
        FilamentSceneHelpers.setAssetVisible(scene, explosionSmoke, showExplosion)
        FilamentSceneHelpers.setAssetVisible(scene, explosionShockwave, showExplosion)

        if (showExplosion) {
            val p = exp.progress // 0..1 over 4.4s

            // Stage A: Fiery plasma core & fireball expanding fast then dissipating
            explosionFireball?.let { fb ->
                val fireScale = if (p < 0.28f) {
                    1.2f + (p / 0.28f) * 12.5f
                } else {
                    (13.7f * (1f - ((p - 0.28f) / 0.72f) * 0.85f)).coerceAtLeast(0.1f)
                }
                val fireRise = p * 5.8f
                FilamentSceneHelpers.buildNormalizedPropMatrix(
                    out = tmpMat,
                    model = fb,
                    targetMaxExtent = fireScale,
                    tx = exp.x,
                    ty = exp.y + 0.8f + fireRise,
                    tz = exp.z,
                    yawDeg = p * 220f,
                    pitchDeg = p * 95f
                )
                tm.setTransform(tm.getInstance(fb.asset.root), tmpMat)
            }

            // Stage B: Ground-hugging shockwave ring blasting out to 26m!
            explosionShockwave?.let { sw ->
                val ringScale = (1.5f + p * 34.0f).coerceAtMost(28f)
                FilamentSceneHelpers.buildNormalizedPropMatrix(
                    out = tmpMat,
                    model = sw,
                    targetMaxExtent = ringScale,
                    tx = exp.x,
                    ty = exp.y + 0.18f,
                    tz = exp.z,
                    yawDeg = p * 120f
                )
                tm.setTransform(tm.getInstance(sw.asset.root), tmpMat)
            }

            // Stage C: Dark billowing smoke mushroom cloud rising high over bombsite!
            explosionSmoke?.let { sm ->
                val smokeScale = 2.0f + p * 16.5f
                val smokeY = exp.y + 1.5f + p * 10.5f
                FilamentSceneHelpers.buildNormalizedPropMatrix(
                    out = tmpMat,
                    model = sm,
                    targetMaxExtent = smokeScale,
                    tx = exp.x,
                    ty = smokeY,
                    tz = exp.z,
                    yawDeg = -p * 75f
                )
                tm.setTransform(tm.getInstance(sm.asset.root), tmpMat)
            }

            // Blinding dynamic explosion light illuminating Mirage walls!
            val lightPower = ((1f - p * 1.45f).coerceAtLeast(0f)) * 2_200_000f
            lm.setColor(fxLightInst, 1.0f, 0.62f, 0.20f)
            lm.setIntensity(fxLightInst, lightPower)
            lm.setFalloff(fxLightInst, 32f)
            lm.setPosition(fxLightInst, exp.x, exp.y + 2.8f, exp.z)
        } else if (gameEngine.muzzleFlashTimer > 0.08f) {
            // Dynamic gunfire flash light
            lm.setColor(fxLightInst, 1.0f, 0.72f, 0.30f)
            lm.setIntensity(fxLightInst, 120_000f * gameEngine.muzzleFlashTimer)
            lm.setFalloff(fxLightInst, 11f)
            val fx = gameEngine.playerX - sin(gameEngine.playerYawRad) * 0.9f
            val fz = gameEngine.playerZ - cos(gameEngine.playerYawRad) * 0.9f
            lm.setPosition(fxLightInst, fx, gameEngine.playerY + gameEngine.eyeHeight - 0.1f, fz)
        } else if (showC4) {
            // High-visibility pulsing red C4 LED beacon light accelerating as detonation nears
            val urgencyFreq = (2.6f + (1f - (gameEngine.bombCountdownSec / 35f).coerceIn(0f, 1f)) * 7.5f)
            val blinkOn = (((gameEngine.timeSec * urgencyFreq).toInt()) % 2) == 0
            val blinkPower = if (blinkOn) 78_000f else 9_000f
            lm.setColor(fxLightInst, 1.0f, 0.10f, 0.06f)
            lm.setIntensity(fxLightInst, blinkPower)
            lm.setFalloff(fxLightInst, 8.5f)
            lm.setPosition(fxLightInst, gameEngine.plantedBombX, gameEngine.plantedBombY + 0.42f, gameEngine.plantedBombZ)
        } else {
            lm.setIntensity(fxLightInst, 0f)
        }
    }
}
