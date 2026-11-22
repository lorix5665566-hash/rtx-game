package com.example.engine

import android.content.Context
import android.opengl.Matrix
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.example.model.ArenaMapId
import com.example.model.GraphicsSettings
import com.example.model.ModelAssetRegistry
import com.example.model.ModelSlot
import com.example.model.TeamSide
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

    private var loadedMap: ArenaMapId? = null
    private var activeWorld: ArenaWorld = ArenaWorld(context, ArenaMapId.MIRAGE)
    private var mapModel: LoadedGlbModel? = null
    private var tCharModel: LoadedGlbModel? = null
    private var ctCharModel: LoadedGlbModel? = null

    private var lastGraphics: GraphicsSettings? = null
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var timeSec = 0f
    private var lastNs = 0L
    private var isRunning = false
    private var isDestroyed = false

    private val tmpMat = FloatArray(16)

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
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface)
                surfaceView.display?.let { displayHelper.attach(renderer, it) }
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

        loadCharacters()
        ensureSelectedMapLoaded()
        lastNs = System.nanoTime()
    }

    private fun loadCharacters() {
        destroyModel(tCharModel); tCharModel = null
        destroyModel(ctCharModel); ctCharModel = null

        tCharModel = FilamentSceneHelpers.loadGlb(
            assetLoader, resourceLoader,
            registry.loadBundledAssetBuffer(ModelSlot.CHARACTER_T.bundledAssetPath)
        )
        ctCharModel = FilamentSceneHelpers.loadGlb(
            assetLoader, resourceLoader,
            registry.loadBundledAssetBuffer(ModelSlot.CHARACTER_CT.bundledAssetPath)
        )
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
        loadedMap = targetMap
        characterYawDeg = activeWorld.lobbyDefaultCharYawDeg
        yawVelocityDegPerSec = 0f
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
        uiHelper.detach()

        destroyModel(mapModel)
        destroyModel(tCharModel)
        destroyModel(ctCharModel)

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

        val gfx = getGraphics()
        if (gfx != lastGraphics) {
            FilamentSceneHelpers.applyGraphicsSettings(engine, view, gfx)
            lastGraphics = gfx
        }

        val aspect = surfaceWidth.toDouble() / surfaceHeight.toDouble().coerceAtLeast(1.0)
        camera.setProjection(38.0, aspect, 0.12, 200.0, Camera.Fov.VERTICAL)

        val (sx, sy, sz) = activeWorld.lobbyStandPos
        val (baseCamX, baseCamY, baseCamZ) = activeWorld.lobbyCamPos
        val (lookX, lookY, lookZ) = activeWorld.lobbyLookTarget

        // Subtle handheld sway orthogonal to sightline (stays well inside verified wall-free pocket)
        val swayA = sin(timeSec * 0.55f) * 0.045f
        val swayY = cos(timeSec * 0.42f) * 0.018f
        camera.lookAt(
            (baseCamX + swayA).toDouble(), (baseCamY + swayY).toDouble(), baseCamZ.toDouble(),
            lookX.toDouble(), lookY.toDouble(), lookZ.toDouble(),
            0.0, 1.0, 0.0
        )

        val tm = engine.transformManager
        val isT = showcaseSide == TeamSide.T
        FilamentSceneHelpers.setAssetVisible(scene, tCharModel, isT)
        FilamentSceneHelpers.setAssetVisible(scene, ctCharModel, !isT)

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
                anim.updateBoneMatrices()
            }
        }

        val sc = swapChain ?: return
        if (uiHelper.isReadyToRender && renderer.beginFrame(sc, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }
}
