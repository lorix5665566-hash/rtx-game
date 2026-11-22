package com.example.engine

import android.content.Context
import android.opengl.Matrix
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.example.model.ArenaMapId
import com.example.model.BombState
import com.example.model.GraphicsSettings
import com.example.model.ModelAssetRegistry
import com.example.model.ModelSlot
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

    private var loadedMapId: ArenaMapId? = null
    private var loadedRegistryRevision: Int = -1
    private var mapModel: LoadedGlbModel? = null
    private var isCustomMap: Boolean = false

    // Character pools (5 CT + 5 T rigged skeletal .GLB instances!)
    private val ctPool = ArrayList<LoadedGlbModel>(5)
    private val tPool = ArrayList<LoadedGlbModel>(5)

    // First-person weapon models mapped by ModelSlot
    private val fpWeapons = HashMap<ModelSlot, LoadedGlbModel>()

    // Faction first-person tactical hands & gloves (.GLB) aligned 1:1 with bundled CS2 weapons
    private var handsTRifle: LoadedGlbModel? = null
    private var handsTPistol: LoadedGlbModel? = null
    private var handsTKnife: LoadedGlbModel? = null
    private var handsCtRifle: LoadedGlbModel? = null
    private var handsCtPistol: LoadedGlbModel? = null
    private var handsCtKnife: LoadedGlbModel? = null

    private var muzzleFlashModel: LoadedGlbModel? = null
    private var plantedBombModel: LoadedGlbModel? = null
    private var explosionFireball: LoadedGlbModel? = null
    private var explosionSmoke: LoadedGlbModel? = null
    private var explosionShockwave: LoadedGlbModel? = null

    private var lastGraphics: GraphicsSettings? = null
    private var surfaceWidth: Int = 1
    private var surfaceHeight: Int = 1
    private var lastFrameNs: Long = 0L
    private var isRunning: Boolean = false
    private var isDestroyed: Boolean = false

    private val tmpMat = FloatArray(16)
    private val camMat = FloatArray(16)
    private val localMat = FloatArray(16)
    private val finalMat = FloatArray(16)

    fun attachTo(surfaceView: SurfaceView) {
        FilamentBootstrap.ensureInitialized()

        engine = Engine.create()
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()
        camera = engine.createCamera(engine.entityManager.create())
        // Bright crisp CS2 daylight exposure
        camera.setExposure(16.0f, 1.0f / 100.0f, 100.0f)
        view.scene = scene
        view.camera = camera

        materialProvider = UbershaderProvider(engine)
        assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
        resourceLoader = ResourceLoader(engine)

        val (sun, fx) = FilamentSceneHelpers.setupPbrLighting(engine, scene)
        sunEntity = sun
        fxLightEntity = fx

        displayHelper = DisplayHelper(context)
        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                if (isDestroyed) return
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface)
                surfaceView.display?.let { displayHelper.attach(renderer, it) }
            }

            override fun onDetachedFromSurface() {
                if (isDestroyed) return
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
        reloadDynamicSlots()
    }

    private fun reloadDynamicSlots() {
        // Clear old dynamic assets cleanly
        destroyModel(plantedBombModel); plantedBombModel = null
        fpWeapons.values.forEach { destroyModel(it) }; fpWeapons.clear()
        ctPool.forEach { destroyModel(it) }; ctPool.clear()
        tPool.forEach { destroyModel(it) }; tPool.clear()

        // Planted C4 bomb .GLB
        plantedBombModel = FilamentSceneHelpers.loadGlb(
            assetLoader, resourceLoader, registry.loadGlbBuffer(ModelSlot.BOMB_C4)
        )

        // All 10 Distinct CS2 First-Person Weapon .GLB slots
        val weaponSlots = listOf(
            ModelSlot.WEAPON_AK47,
            ModelSlot.WEAPON_M4A1_S,
            ModelSlot.WEAPON_AWP,
            ModelSlot.WEAPON_P90,
            ModelSlot.WEAPON_MP9,
            ModelSlot.WEAPON_USP_S,
            ModelSlot.WEAPON_GLOCK_18,
            ModelSlot.WEAPON_DEAGLE,
            ModelSlot.WEAPON_KNIFE,
            ModelSlot.BOMB_C4
        )
        for (slot in weaponSlots) {
            FilamentSceneHelpers.loadGlb(assetLoader, resourceLoader, registry.loadGlbBuffer(slot))?.let {
                fpWeapons[slot] = it
            }
        }

        // Character pools (up to 5 CT + 5 T rigged skeletal .GLB bots!)
        val ctBuf = registry.loadGlbBuffer(ModelSlot.CHARACTER_CT)
        val tBuf = registry.loadGlbBuffer(ModelSlot.CHARACTER_T)
        for (i in 0 until 5) {
            ctBuf?.rewind()
            FilamentSceneHelpers.loadGlb(assetLoader, resourceLoader, ctBuf)?.let { ctPool.add(it) }
            tBuf?.rewind()
            FilamentSceneHelpers.loadGlb(assetLoader, resourceLoader, tBuf)?.let { tPool.add(it) }
        }
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
            loadedMapId = targetMap
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
        fpWeapons.values.forEach { destroyModel(it) }; fpWeapons.clear()
        ctPool.forEach { destroyModel(it) }; ctPool.clear()
        tPool.forEach { destroyModel(it) }; tPool.clear()

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
        runCatching { assetLoader.destroyAsset(model.asset) }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning || isDestroyed) return
        Choreographer.getInstance().postFrameCallback(this)

        val dt = ((frameTimeNanos - lastFrameNs) / 1_000_000_000.0f).coerceIn(0.001f, 0.05f)
        lastFrameNs = frameTimeNanos

        gameEngine.update(dt)
        ensureMapAndRegistryCurrent()

        val gfx = gameEngine.settings.graphics
        if (gfx != lastGraphics) {
            FilamentSceneHelpers.applyGraphicsSettings(engine, view, gfx)
            lastGraphics = gfx
        }

        updateCamera()
        updateBots()
        updateFirstPersonWeaponAndMuzzle()
        updatePlantedBombAndExplosion()

        val sc = swapChain ?: return
        if (uiHelper.isReadyToRender && renderer.beginFrame(sc, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    private fun updateCamera() {
        val gfx = gameEngine.settings.graphics
        val aspect = surfaceWidth.toDouble() / surfaceHeight.toDouble().coerceAtLeast(1.0)
        val baseFov = gfx.fov.coerceIn(65f, 105f)
        val zoomTarget = gameEngine.activeWeapon.adsZoomFactor
        val liveFov = (baseFov * (1f - gameEngine.adsBlend * (1f - zoomTarget))).toDouble()
        camera.setProjection(liveFov, aspect, 0.04, 240.0, Camera.Fov.VERTICAL)

        // Apply dramatic C4 explosion camera shake!
        val shake = gameEngine.cameraShake
        val shakeX = if (shake > 0.01f) sin(gameEngine.timeSec * 58f) * 0.24f * shake else 0f
        val shakeY = if (shake > 0.01f) cos(gameEngine.timeSec * 49f) * 0.20f * shake else 0f

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

    private fun updateBots() {
        val tm = engine.transformManager
        var ctIdx = 0
        var tIdx = 0
        val px = gameEngine.playerX
        val pz = gameEngine.playerZ

        // 1. Render remote human players FIRST so friends/opponents always get priority in character pools!
        if (gameEngine.matchConfig.isMultiplayer) {
            val remotes = gameEngine.getRemotePlayersSnapshot()
            for ((idx, rp) in remotes.withIndex()) {
                val pool = if (rp.team == TeamSide.CT) ctPool else tPool
                val slotIdx = if (rp.team == TeamSide.CT) ctIdx++ else tIdx++
                if (slotIdx >= pool.size) continue
                val model = pool[slotIdx]
                val charSlot = if (rp.team == TeamSide.CT) ModelSlot.CHARACTER_CT else ModelSlot.CHARACTER_T
                FilamentSceneHelpers.setAssetVisible(scene, model, true)
                FilamentSceneHelpers.buildCharacterMatrix(
                    out = tmpMat,
                    model = model,
                    x = rp.x,
                    y = rp.y,
                    z = rp.z,
                    yawDeg = rp.yawDeg,
                    deathProgress = rp.deathProgress,
                    targetHeight = 1.76f,
                    isCustomImported = registry.hasCustomFile(charSlot)
                )
                tm.setTransform(tm.getInstance(model.asset.root), tmpMat)

                val animator = model.asset.instance.animator
                if (animator.animationCount > 0 && !rp.isDead) {
                    val animIdx = if (rp.isMoving) model.runAnimIdx else model.idleAnimIdx
                    if (animIdx >= 0) {
                        val dur = animator.getAnimationDuration(animIdx).coerceAtLeast(0.1f)
                        animator.applyAnimation(animIdx, (gameEngine.timeSec + idx * 0.41f) % dur)
                        animator.updateBoneMatrices()
                    }
                }
            }
        }

        // 2. Render AI bots (skipping skeletal skinning on dead or distant > 38m bots for max FPS!)
        for (b in gameEngine.bots) {
            val pool = if (b.team == TeamSide.CT) ctPool else tPool
            val slotIdx = if (b.team == TeamSide.CT) ctIdx++ else tIdx++
            if (slotIdx >= pool.size) continue
            val model = pool[slotIdx]

            val charSlot = if (b.team == TeamSide.CT) ModelSlot.CHARACTER_CT else ModelSlot.CHARACTER_T
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
                isCustomImported = registry.hasCustomFile(charSlot)
            )
            tm.setTransform(tm.getInstance(model.asset.root), tmpMat)

            val dx = b.x - px
            val dz = b.z - pz
            val distSq = dx * dx + dz * dz
            val animator = model.asset.instance.animator
            if (animator.animationCount > 0 && !b.isDead && distSq < 38f * 38f) {
                val animIdx = when {
                    !b.isMoving -> model.idleAnimIdx
                    kotlin.math.abs(b.moveForwardRatio) > 0.65f -> model.runAnimIdx
                    else -> model.walkAnimIdx
                }
                if (animIdx >= 0) {
                    val dur = animator.getAnimationDuration(animIdx).coerceAtLeast(0.1f)
                    val animTime = (gameEngine.timeSec + b.id * 0.37f) % dur
                    animator.applyAnimation(animIdx, animTime)
                    animator.updateBoneMatrices()
                }
            }
        }

        // Hide unused pool instances
        for (i in ctIdx until ctPool.size) FilamentSceneHelpers.setAssetVisible(scene, ctPool[i], false)
        for (i in tIdx until tPool.size) FilamentSceneHelpers.setAssetVisible(scene, tPool[i], false)
    }

    private fun updateFirstPersonWeaponAndMuzzle() {
        val tm = engine.transformManager
        val hud = gameEngine.latestHud
        val w = gameEngine.activeWeapon
        val hideWeapon = hud.isPlayerDead || (w.hasSniperScope && gameEngine.adsBlend > 0.52f)
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

        if (u > 0.001f) {
            when (w) {
                WeaponType.KNIFE -> {
                    eqX = 0.08f * ease
                    eqY = -0.26f * ease
                    eqZ = 0.07f * ease
                    eqYaw = -22f * ease
                    eqPitch = -35f * ease
                    eqRoll = 210f * ease
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
        val reloadDip = if (hud.isReloading) sin(hud.reloadProgress * Math.PI).toFloat() * 0.16f else 0f
        val knifeSwing = if (w == WeaponType.KNIFE && gameEngine.knifeSwingProgress > 0f) {
            sin(gameEngine.knifeSwingProgress * Math.PI).toFloat()
        } else 0f
        val boltTilt = if (w == WeaponType.AWP && gameEngine.boltProgress > 0f) {
            sin(gameEngine.boltProgress * Math.PI).toFloat()
        } else 0f

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

        val offX = hipX * (1f - ads) + bobX + gameEngine.weaponSwayX - knifeSwing * 0.22f + eqX
        val offY = (hipY * (1f - ads) + adsY * ads) - bobY + gameEngine.weaponSwayY - gameEngine.landingDip - reloadDip + knifeSwing * 0.05f + eqY
        val offZ = hipZ + recoilZ - knifeSwing * 0.14f + eqZ

        val yawDeg = eqYaw + gameEngine.weaponSwayX * 145f + knifeSwing * 46f + boltTilt * 6f
        val pitchDeg = recoilPitch + gameEngine.weaponSwayY * 130f - gameEngine.landingDip * 90f - reloadDip * 78f + eqPitch + boltTilt * 10f
        val rollDeg = (if (hud.isReloading) -26f else -knifeSwing * 36f) + gameEngine.strafeRoll + eqRoll + boltTilt * 22f

        for ((slot, model) in fpWeapons) {
            val visible = !hideWeapon && slot == activeSlot
            FilamentSceneHelpers.setAssetVisible(scene, model, visible)
            if (!visible) continue

            if (!isCustomWeapon) {
                // Exact 1:1 calibrated CS2 PBR weapon model (grip at origin, barrel along -Z)
                FilamentSceneHelpers.buildDirectWeaponMatrix(
                    out = localMat,
                    tx = offX,
                    ty = offY,
                    tz = offZ,
                    yawDeg = yawDeg,
                    pitchDeg = pitchDeg,
                    rollDeg = rollDeg,
                    scale = 1.0f
                )
            } else {
                // Auto-normalized fallback for user-imported Sketchfab weapon .GLBs
                val targetSpan = when (w) {
                    WeaponType.AWP -> 0.78f
                    WeaponType.AK47, WeaponType.M4A1_S -> 0.64f
                    WeaponType.MP9, WeaponType.P90 -> 0.46f
                    WeaponType.USP_S, WeaponType.GLOCK_18, WeaponType.DESERT_EAGLE -> 0.28f
                    WeaponType.KNIFE -> 0.28f
                    WeaponType.C4_BOMB -> 0.24f
                }
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
                    tx = offX,
                    ty = offY,
                    tz = offZ - 0.08f,
                    yawDeg = autoYaw + yawDeg,
                    pitchDeg = pitchDeg,
                    rollDeg = rollDeg,
                    anchorBottom = false
                )
            }
            Matrix.multiplyMM(finalMat, 0, camMat, 0, localMat, 0)
            tm.setTransform(tm.getInstance(model.asset.root), finalMat)
        }

        // Synced Faction First-Person Tactical Hands & Gloves (T vs CT; Rifle / Pistol / Knife poses)
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
        val allHands = listOf(handsTRifle, handsTPistol, handsTKnife, handsCtRifle, handsCtPistol, handsCtKnife)
        for (h in allHands) {
            val vis = (h != null && h === activeHands)
            FilamentSceneHelpers.setAssetVisible(scene, h, vis)
            if (vis && h != null) {
                // Hands share the EXACT same localMat as our calibrated weapon model!
                Matrix.multiplyMM(finalMat, 0, camMat, 0, localMat, 0)
                tm.setTransform(tm.getInstance(h.asset.root), finalMat)
            }
        }

        // Crisp 45ms 3D Muzzle Flash (.GLB) anchored to exact weapon barrel tip!
        val flashAmt = gameEngine.muzzleFlashTimer
        val showFlash = !hideWeapon && !gameEngine.isEquipping && flashAmt > 0.08f &&
            w != WeaponType.KNIFE && w != WeaponType.C4_BOMB
        FilamentSceneHelpers.setAssetVisible(scene, muzzleFlashModel, showFlash)
        if (showFlash && muzzleFlashModel != null) {
            val fxX = offX
            val fxY = offY + 0.072f
            val fxZ = offZ + w.muzzleOffsetZ
            val flashSize = (0.18f + flashAmt * 0.24f) * if (w.isSuppressed) 0.42f else 1.0f
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

    private fun updatePlantedBombAndExplosion() {
        val tm = engine.transformManager
        val lm = engine.lightManager
        val fxLightInst = lm.getInstance(fxLightEntity)

        // 1. Planted C4 physical .GLB model on the ground
        val showC4 = gameEngine.bombState == BombState.PLANTED
        FilamentSceneHelpers.setAssetVisible(scene, plantedBombModel, showC4)
        if (showC4 && plantedBombModel != null) {
            FilamentSceneHelpers.buildNormalizedPropMatrix(
                out = tmpMat,
                model = plantedBombModel!!,
                targetMaxExtent = 0.36f,
                tx = gameEngine.plantedBombX,
                ty = gameEngine.plantedBombY + 0.01f,
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
            // Pulsing red C4 LED light on planted bomb
            val blink = if (((gameEngine.timeSec * 4.5f).toInt() % 2) == 0) 14_000f else 1_200f
            lm.setColor(fxLightInst, 1.0f, 0.12f, 0.08f)
            lm.setIntensity(fxLightInst, blink)
            lm.setFalloff(fxLightInst, 4.5f)
            lm.setPosition(fxLightInst, gameEngine.plantedBombX, gameEngine.plantedBombY + 0.35f, gameEngine.plantedBombZ)
        } else {
            lm.setIntensity(fxLightInst, 0f)
        }
    }
}
