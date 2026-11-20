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
        camera.setExposure(16.0f, 1.0f / 125.0f, 100.0f)
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
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface)
                displayHelper.attach(renderer, surfaceView.display)
            }

            override fun onDetachedFromSurface() {
                displayHelper.detach()
                swapChain?.let {
                    engine.destroySwapChain(it)
                    engine.flushAndWait()
                }
                swapChain = null
            }

            override fun onResized(width: Int, height: Int) {
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

        // First-person Weapon .GLB slots
        val weaponSlots = listOf(
            ModelSlot.WEAPON_AK47,
            ModelSlot.WEAPON_M4A1_S,
            ModelSlot.WEAPON_AWP,
            ModelSlot.WEAPON_P90,
            ModelSlot.WEAPON_PISTOL,
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
        uiHelper.detach()

        destroyModel(mapModel)
        destroyModel(muzzleFlashModel)
        destroyModel(plantedBombModel)
        destroyModel(explosionFireball)
        destroyModel(explosionSmoke)
        destroyModel(explosionShockwave)
        fpWeapons.values.forEach { destroyModel(it) }
        ctPool.forEach { destroyModel(it) }
        tPool.forEach { destroyModel(it) }

        resourceLoader.destroy()
        assetLoader.destroy()
        materialProvider.destroyMaterials()
        materialProvider.destroy()

        view.colorGrading?.let { runCatching { engine.destroyColorGrading(it) } }
        scene.skybox?.let { engine.destroySkybox(it) }
        scene.indirectLight?.let { engine.destroyIndirectLight(it) }
        engine.destroyEntity(sunEntity)
        engine.destroyEntity(fxLightEntity)
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(camera.entity)
        EntityManager.get().destroy(camera.entity)
        engine.destroy()
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

        for (b in gameEngine.bots) {
            val pool = if (b.team == TeamSide.CT) ctPool else tPool
            val slotIdx = if (b.team == TeamSide.CT) ctIdx++ else tIdx++
            if (slotIdx >= pool.size) continue
            val model = pool[slotIdx]

            FilamentSceneHelpers.setAssetVisible(scene, model, true)
            FilamentSceneHelpers.buildCharacterMatrix(
                out = tmpMat,
                model = model,
                x = b.x,
                y = b.y,
                z = b.z,
                yawDeg = b.yawDeg,
                deathProgress = b.deathProgress,
                targetHeight = 1.78f
            )
            tm.setTransform(tm.getInstance(model.asset.root), tmpMat)

            // Drive real skeletal glTF animation (Idle / Walk / Run)!
            val animator = model.asset.instance.animator
            if (animator.animationCount > 0 && !b.isDead) {
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
        val hideWeapon = hud.isPlayerDead || (gameEngine.activeWeapon.hasSniperScope && gameEngine.adsBlend > 0.52f)
        val activeSlot = ModelSlot.forWeapon(gameEngine.activeWeapon)

        for ((slot, model) in fpWeapons) {
            val visible = !hideWeapon && slot == activeSlot
            FilamentSceneHelpers.setAssetVisible(scene, model, visible)
            if (!visible) continue

            val w = gameEngine.activeWeapon
            val ads = gameEngine.adsBlend
            val bobX = sin(gameEngine.walkBobPhase) * 0.012f * gameEngine.moveSpeedRatio * (1f - ads * 0.7f)
            val bobY = kotlin.math.abs(cos(gameEngine.walkBobPhase)) * 0.014f * gameEngine.moveSpeedRatio * (1f - ads * 0.7f)
            val recoilZ = gameEngine.recoilKick * 0.055f
            val recoilPitch = gameEngine.recoilKick * 7.5f
            val reloadDip = if (hud.isReloading) sin(hud.reloadProgress * Math.PI).toFloat() * 0.16f else 0f
            val knifeSwing = if (w == WeaponType.KNIFE && gameEngine.knifeSwingProgress > 0f) {
                sin(gameEngine.knifeSwingProgress * Math.PI).toFloat()
            } else 0f

            val targetSpan = when (w) {
                WeaponType.AWP -> 0.76f
                WeaponType.AK47, WeaponType.M4A1_S -> 0.62f
                WeaponType.MP9, WeaponType.P90 -> 0.46f
                WeaponType.USP_S, WeaponType.GLOCK_18, WeaponType.DESERT_EAGLE -> 0.28f
                WeaponType.KNIFE -> 0.28f
                WeaponType.C4_BOMB -> 0.24f
            }

            // Right-hand hip offset lerping cleanly to centered ADS sightline
            val hipX = if (w == WeaponType.C4_BOMB) 0.06f else 0.21f
            val hipY = if (w == WeaponType.C4_BOMB) -0.17f else -0.18f
            val hipZ = if (w == WeaponType.KNIFE || w == WeaponType.C4_BOMB) -0.38f else -0.44f

            val offX = hipX * (1f - ads) + bobX - knifeSwing * 0.22f
            val offY = hipY * (1f - ads * 0.35f) - bobY - reloadDip + knifeSwing * 0.04f
            val offZ = hipZ + recoilZ - knifeSwing * 0.14f

            // Auto-orient weapon long axis along -Z (aiming forward)
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
                tz = offZ,
                yawDeg = autoYaw + knifeSwing * 48f,
                pitchDeg = recoilPitch - reloadDip * 85f,
                rollDeg = if (hud.isReloading) -28f else -knifeSwing * 35f,
                anchorBottom = false
            )
            Matrix.multiplyMM(finalMat, 0, camMat, 0, localMat, 0)
            tm.setTransform(tm.getInstance(model.asset.root), finalMat)
        }

        // Crisp 45ms 3D Muzzle Flash (.GLB) at barrel tip
        val flashAmt = gameEngine.muzzleFlashTimer
        val showFlash = !hideWeapon && flashAmt > 0.08f &&
            gameEngine.activeWeapon != WeaponType.KNIFE &&
            gameEngine.activeWeapon != WeaponType.C4_BOMB
        FilamentSceneHelpers.setAssetVisible(scene, muzzleFlashModel, showFlash)
        if (showFlash && muzzleFlashModel != null) {
            val ads = gameEngine.adsBlend
            val fxX = 0.21f * (1f - ads)
            val fxY = -0.14f * (1f - ads * 0.35f)
            val fxZ = -0.78f
            val flashSize = (0.20f + flashAmt * 0.26f) * if (gameEngine.activeWeapon.isSuppressed) 0.45f else 1.0f
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
