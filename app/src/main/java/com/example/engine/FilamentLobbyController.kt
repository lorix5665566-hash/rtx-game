package com.example.engine

import android.content.Context
import android.opengl.Matrix
import android.os.Build
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.example.model.ArenaMapId
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
import kotlin.math.exp
import kotlin.math.sin

/**
 * Live 3D Google Filament Main Menu Renderer:
 * Dynamically renders an unobstructed sunlit open courtyard of the selected map (Mirage or Dust 2)
 * with the rigged Terrorist (T) or Спецназ (CT) operative standing cleanly facing the camera,
 * smoothly animated and rotatable 360° with inertial damping.
 */
class FilamentLobbyController(
    private val context: Context,
    private val registry: ModelAssetRegistry,
    private val getGraphics: () -> GraphicsSettings
) : Choreographer.FrameCallback {

    @Volatile var showcaseSide: TeamSide = TeamSide.T
    @Volatile var showcaseMap: ArenaMapId = ArenaMapId.MIRAGE
    @Volatile var characterYawDeg: Float = 0f
    @Volatile var equippedSkins: Map<String, String> = emptyMap()
    @Volatile private var yawVelocityDegPerSec: Float = 0f

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

    private var loadedMap: ArenaMapId? = null
    private var activeWorld: ArenaWorld = ArenaWorld(context, ArenaMapId.MIRAGE)
    private var mapModel: LoadedGlbModel? = null
    private var tCharModel: LoadedGlbModel? = null
    private var ctCharModel: LoadedGlbModel? = null
    private var ak47WeaponModel: LoadedGlbModel? = null
    private var m4WeaponModel: LoadedGlbModel? = null
    private val mirageStairPool = ArrayList<LoadedGlbModel>(11)
    private val mirageGratePool = ArrayList<LoadedGlbModel>(12)
    private var miragePropsMap: ArenaMapId? = null

    private var lastGraphics: GraphicsSettings? = null
    private var activeSurface: Surface? = null
    private var appliedSurfaceFps: Int = 0
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var timeSec = 0f
    private var lastNs = 0L
    private var lastRenderedFrameNs = 0L
    private var isRunning = false
    private var isDestroyed = false

    private val tmpMat = FloatArray(16)
    private val weaponMat = FloatArray(16)
    private val rigAnimator = OperativeRigAnimator()
    private val turnShuffleState = TurnShuffleState()

    fun addSpinDrag(deltaPixels: Float) {
        val deltaDeg = deltaPixels * 0.40f
        characterYawDeg = (characterYawDeg + deltaDeg) % 360f
        yawVelocityDegPerSec = (deltaDeg * 18f).coerceIn(-260f, 260f)
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

        val (sun, fx, rig) = FilamentSceneHelpers.setupPbrLighting(engine, scene, showcaseMap)
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
                applySurfaceFrameRate(surface, getGraphics().targetFps.fps)
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
                view.viewport = Viewport(0, 0, surfaceWidth, surfaceHeight)
            }
        }
        uiHelper.attachTo(surfaceView)

        loadCharacters()
        ensureSelectedMapLoaded()
        lastNs = System.nanoTime()
    }

    private fun loadCharacters() {
        destroyModel(tCharModel); tCharModel = null
        destroyModel(ctCharModel); ctCharModel = null
        destroyModel(ak47WeaponModel); ak47WeaponModel = null
        destroyModel(m4WeaponModel); m4WeaponModel = null

        tCharModel = FilamentSceneHelpers.loadGlb(
            assetLoader, resourceLoader,
            registry.loadBundledAssetBuffer(ModelSlot.CHARACTER_T.bundledAssetPath)
        )
        ctCharModel = FilamentSceneHelpers.loadGlb(
            assetLoader, resourceLoader,
            registry.loadBundledAssetBuffer(ModelSlot.CHARACTER_CT.bundledAssetPath)
        )
        ak47WeaponModel = FilamentSceneHelpers.loadGlb(
            assetLoader, resourceLoader,
            registry.loadBundledAssetBuffer(ModelSlot.WEAPON_AK47.bundledAssetPath)
        )
        m4WeaponModel = FilamentSceneHelpers.loadGlb(
            assetLoader, resourceLoader,
            registry.loadBundledAssetBuffer(ModelSlot.WEAPON_M4A1_S.bundledAssetPath)
        )

        val stairBuf = FilamentSceneHelpers.createUnitBoxGlbBuffer(
            baseR = 0.78f, baseG = 0.68f, baseB = 0.54f,
            metallic = 0.0f, roughness = 0.88f
        )
        repeat(11) {
            stairBuf.rewind()
            FilamentSceneHelpers.loadGlb(assetLoader, resourceLoader, stairBuf)?.let {
                mirageStairPool.add(it)
            }
        }
        val grateBuf = FilamentSceneHelpers.createUnitBoxGlbBuffer(
            baseR = 0.24f, baseG = 0.27f, baseB = 0.30f,
            metallic = 0.85f, roughness = 0.36f
        )
        repeat(12) {
            grateBuf.rewind()
            FilamentSceneHelpers.loadGlb(assetLoader, resourceLoader, grateBuf)?.let {
                mirageGratePool.add(it)
            }
        }
    }

    private fun ensureSelectedMapLoaded() {
        val targetMap = showcaseMap
        if (loadedMap == targetMap && mapModel != null) return

        destroyModel(mapModel)
        mapModel = null

        if (activeWorld.mapId != targetMap) {
            activeWorld = ArenaWorld(context, targetMap)
        }
        val slot = ModelSlot.forMap(targetMap)
        mapModel = FilamentSceneHelpers.loadGlb(
            assetLoader, resourceLoader,
            registry.loadBundledAssetBuffer(slot.bundledAssetPath)
        )
        mapModel?.let { m ->
            FilamentSceneHelpers.setAssetVisible(scene, m, true)
            val tm = engine.transformManager
            Matrix.setIdentityM(tmpMat, 0)
            tm.setTransform(tm.getInstance(m.asset.root), tmpMat)
        }
        // Swap procedural skybox to match Mirage / Dust 2 lighting mood
        val prevRig = skyRig
        val newRig = FilamentSceneHelpers.createProceduralSkyRig(engine, targetMap)
        scene.skybox = newRig.skybox
        skyRig = newRig
        FilamentSceneHelpers.destroySkyRig(engine, scene, prevRig)

        loadedMap = targetMap
        miragePropsMap = null
        lastGraphics = null
        characterYawDeg = activeWorld.lobbyDefaultCharYawDeg
        yawVelocityDegPerSec = 0f
        turnShuffleState.reset()
        updateMirageProps()
    }

    private fun updateMirageProps() {
        val isMirage = loadedMap == ArenaMapId.MIRAGE
        if (miragePropsMap == loadedMap) return
        miragePropsMap = loadedMap
        val tm = engine.transformManager
        for (i in 0 until mirageStairPool.size) {
            val m = mirageStairPool[i]
            FilamentSceneHelpers.setAssetVisible(scene, m, isMirage)
            if (isMirage) {
                val t = i.toFloat() / (mirageStairPool.size - 1).coerceAtLeast(1)
                val sx = 8.65f + t * 4.35f
                val stepTop = -0.20f + ((i + 1).toFloat() / mirageStairPool.size) * 2.62f
                val height = (stepTop - (-0.30f)).coerceAtLeast(0.24f)
                Matrix.setIdentityM(tmpMat, 0)
                Matrix.translateM(tmpMat, 0, sx, -0.30f + height * 0.5f, 23.6f)
                Matrix.scaleM(tmpMat, 0, 0.46f, height, 1.85f)
                tm.setTransform(tm.getInstance(m.rootEntity), tmpMat)
            }
        }
        val barrier = activeWorld.breakableBarriers.firstOrNull { it.id == "mirage_ct_mid_grate" }
        for (i in 0 until mirageGratePool.size) {
            val m = mirageGratePool[i]
            FilamentSceneHelpers.setAssetVisible(scene, m, isMirage && barrier != null)
            if (isMirage && barrier != null) {
                val w = barrier.maxX - barrier.minX
                val h = barrier.maxY - barrier.minY
                val zc = barrier.centerZ
                Matrix.setIdentityM(tmpMat, 0)
                when {
                    i < 9 -> {
                        val bx = barrier.minX + 0.16f + (i / 8f) * (w - 0.32f)
                        Matrix.translateM(tmpMat, 0, bx, barrier.centerY, zc)
                        Matrix.scaleM(tmpMat, 0, 0.08f, h, 0.08f)
                    }
                    i == 9 -> {
                        Matrix.translateM(tmpMat, 0, barrier.centerX, barrier.maxY - 0.08f, zc)
                        Matrix.scaleM(tmpMat, 0, w, 0.12f, 0.14f)
                    }
                    i == 10 -> {
                        Matrix.translateM(tmpMat, 0, barrier.centerX, barrier.centerY, zc)
                        Matrix.scaleM(tmpMat, 0, w, 0.10f, 0.12f)
                    }
                    else -> {
                        Matrix.translateM(tmpMat, 0, barrier.centerX, barrier.minY + 0.06f, zc)
                        Matrix.scaleM(tmpMat, 0, w, 0.12f, 0.14f)
                    }
                }
                tm.setTransform(tm.getInstance(m.rootEntity), tmpMat)
            }
        }
    }

    fun start() {
        if (!isRunning && !isDestroyed) {
            isRunning = true
            lastNs = System.nanoTime()
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
        destroyModel(tCharModel); tCharModel = null
        destroyModel(ctCharModel); ctCharModel = null
        destroyModel(ak47WeaponModel); ak47WeaponModel = null
        destroyModel(m4WeaponModel); m4WeaponModel = null
        mirageStairPool.asReversed().forEach { destroyModel(it) }; mirageStairPool.clear()
        mirageGratePool.asReversed().forEach { destroyModel(it) }; mirageGratePool.clear()

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
        runCatching { assetLoader.destroyAsset(model.asset) }
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

        val gfx = getGraphics()
        val targetFps = gfx.targetFps.fps
        if (appliedSurfaceFps != targetFps) {
            applySurfaceFrameRate(activeSurface, targetFps)
        }
        if (targetFps < 120 && lastRenderedFrameNs > 0L) {
            val minIntervalNs = (1_000_000_000L / targetFps) - 900_000L
            if (frameTimeNanos - lastRenderedFrameNs < minIntervalNs) {
                return
            }
        }
        lastRenderedFrameNs = frameTimeNanos

        val dt = ((frameTimeNanos - lastNs) / 1_000_000_000.0f).coerceIn(0.001f, 0.05f)
        lastNs = frameTimeNanos
        timeSec += dt

        ensureSelectedMapLoaded()

        // Smooth inertial spin deceleration after user swipes character
        if (kotlin.math.abs(yawVelocityDegPerSec) > 0.15f) {
            characterYawDeg = (characterYawDeg + yawVelocityDegPerSec * dt) % 360f
            yawVelocityDegPerSec *= exp(-dt * 5.2f)
        } else {
            yawVelocityDegPerSec = 0f
        }

        if (gfx != lastGraphics) {
            FilamentSceneHelpers.applyGraphicsSettings(engine, view, gfx, showcaseMap)
            lastGraphics = gfx
        }

        if (surfaceWidth <= 1 || surfaceHeight <= 1) return

        val aspect = surfaceWidth.toDouble() / surfaceHeight.toDouble().coerceAtLeast(1.0)
        camera.setProjection(38.0, aspect, 0.12, 200.0, Camera.Fov.VERTICAL)

        val (sx, sy, sz) = activeWorld.lobbyStandPos
        val (baseCamX, baseCamY, baseCamZ) = activeWorld.lobbyCamPos
        val (lookX, lookY, lookZ) = activeWorld.lobbyLookTarget

        // Subtle handheld sway orthogonal to sightline (never tilts perspective or distorts framing)
        val fwdX = lookX - baseCamX
        val fwdZ = lookZ - baseCamZ
        val fwdLen = kotlin.math.sqrt(fwdX * fwdX + fwdZ * fwdZ).coerceAtLeast(0.001f)
        val rightX = -fwdZ / fwdLen
        val rightZ = fwdX / fwdLen
        val swayA = sin(timeSec * 0.55f) * 0.045f
        val swayY = cos(timeSec * 0.42f) * 0.018f
        camera.lookAt(
            (baseCamX + rightX * swayA).toDouble(),
            (baseCamY + swayY).toDouble(),
            (baseCamZ + rightZ * swayA).toDouble(),
            lookX.toDouble(), lookY.toDouble(), lookZ.toDouble(),
            0.0, 1.0, 0.0
        )

        val tm = engine.transformManager
        val isT = showcaseSide == TeamSide.T
        val showcaseWeapon = if (isT) WeaponType.AK47 else WeaponType.M4A1_S
        FilamentSceneHelpers.setAssetVisible(scene, tCharModel, isT)
        FilamentSceneHelpers.setAssetVisible(scene, ctCharModel, !isT)
        FilamentSceneHelpers.setAssetVisible(scene, ak47WeaponModel, isT)
        FilamentSceneHelpers.setAssetVisible(scene, m4WeaponModel, !isT)

        turnShuffleState.update(characterYawDeg, dt, isMoving = false, isDead = false)

        val activeChar = if (isT) tCharModel else ctCharModel
        if (activeChar != null) {
            FilamentSceneHelpers.buildCharacterMatrix(
                out = tmpMat,
                model = activeChar,
                x = sx,
                y = sy,
                z = sz,
                yawDeg = characterYawDeg,
                deathProgress = 0f,
                targetHeight = 1.76f,
                isCustomImported = false
            )
            tm.setTransform(tm.getInstance(activeChar.asset.root), tmpMat)

            val anim = activeChar.asset.instance.animator
            if (anim.animationCount > 0 && activeChar.idleAnimIdx >= 0) {
                val dur = anim.getAnimationDuration(activeChar.idleAnimIdx).coerceAtLeast(0.1f)
                anim.applyAnimation(activeChar.idleAnimIdx, timeSec % dur)
                rigAnimator.applyProceduralPose(
                    engine = engine,
                    handles = activeChar.rigHandles,
                    weapon = showcaseWeapon,
                    turnState = turnShuffleState,
                    timeSec = timeSec,
                    isMoving = false,
                    isDead = false,
                    deathProgress = 0f,
                    isShooting = false
                )
                anim.updateBoneMatrices()
            }
        }

        val activeWeaponModel = if (isT) ak47WeaponModel else m4WeaponModel
        if (activeWeaponModel != null) {
            FilamentSceneHelpers.applyWeaponSkin(activeWeaponModel, equippedSkins[showcaseWeapon.name])
            rigAnimator.buildHeldWeaponMatrix(
                out = weaponMat,
                weapon = showcaseWeapon,
                charX = sx,
                charY = sy,
                charZ = sz,
                charYawDeg = characterYawDeg,
                timeSec = timeSec,
                isMoving = false,
                deathProgress = 0f,
                isShooting = false
            )
            tm.setTransform(tm.getInstance(activeWeaponModel.asset.root), weaponMat)
        }

        val sc = swapChain ?: return
        if (uiHelper.isReadyToRender && renderer.beginFrame(sc, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }
}
