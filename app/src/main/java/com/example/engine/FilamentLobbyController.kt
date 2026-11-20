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
import kotlin.math.sin

/**
 * Live 3D Google Filament CS2 Lobby Renderer:
 * Renders the real sunlit CS2 Mirage .GLB courtyard behind the clean CS2 Main Menu
 * with the real skeletal rigged Terrorist (T) or Спецназ (CT) operative standing in center,
 * smoothly playing its Idle breathing animation and rotatable 360° by touch!
 */
class FilamentLobbyController(
    private val context: Context,
    private val registry: ModelAssetRegistry,
    private val getGraphics: () -> GraphicsSettings
) : Choreographer.FrameCallback {

    @Volatile var showcaseSide: TeamSide = TeamSide.T
    @Volatile var showcaseWeapon: WeaponType = WeaponType.KNIFE
    @Volatile var characterYawDeg: Float = -55f

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

    private val mirageWorld = ArenaWorld(context, ArenaMapId.MIRAGE)
    private var mapModel: LoadedGlbModel? = null
    private var tCharModel: LoadedGlbModel? = null
    private var ctCharModel: LoadedGlbModel? = null
    private val weaponModels = HashMap<ModelSlot, LoadedGlbModel>()

    private var loadedRevision: Int = -1
    private var lastGraphics: GraphicsSettings? = null
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var timeSec = 0f
    private var lastNs = 0L
    private var isRunning = false
    private var isDestroyed = false

    private val tmpMat = FloatArray(16)

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

        reloadLobbyAssets()
        lastNs = System.nanoTime()
    }

    private fun reloadLobbyAssets() {
        destroyModel(mapModel); mapModel = null
        destroyModel(tCharModel); tCharModel = null
        destroyModel(ctCharModel); ctCharModel = null
        weaponModels.values.forEach { destroyModel(it) }; weaponModels.clear()

        val customMap = registry.hasCustomFile(ModelSlot.MAP_MIRAGE)
        mapModel = FilamentSceneHelpers.loadGlb(assetLoader, resourceLoader, registry.loadGlbBuffer(ModelSlot.MAP_MIRAGE))
        mapModel?.let { m ->
            FilamentSceneHelpers.setAssetVisible(scene, m, true)
            val tm = engine.transformManager
            if (customMap) {
                val s = 84f / maxOf(m.sizeX, m.sizeZ).coerceAtLeast(0.01f)
                Matrix.setIdentityM(tmpMat, 0)
                Matrix.scaleM(tmpMat, 0, s, s, s)
                Matrix.translateM(tmpMat, 0, -m.centerX, -m.minY, -m.centerZ)
                tm.setTransform(tm.getInstance(m.asset.root), tmpMat)
            } else {
                Matrix.setIdentityM(tmpMat, 0)
                tm.setTransform(tm.getInstance(m.asset.root), tmpMat)
            }
        }

        tCharModel = FilamentSceneHelpers.loadGlb(assetLoader, resourceLoader, registry.loadGlbBuffer(ModelSlot.CHARACTER_T))
        ctCharModel = FilamentSceneHelpers.loadGlb(assetLoader, resourceLoader, registry.loadGlbBuffer(ModelSlot.CHARACTER_CT))

        listOf(
            ModelSlot.WEAPON_KNIFE,
            ModelSlot.WEAPON_PISTOL,
            ModelSlot.WEAPON_AK47,
            ModelSlot.WEAPON_M4A1_S,
            ModelSlot.WEAPON_AWP,
            ModelSlot.BOMB_C4
        ).forEach { slot ->
            FilamentSceneHelpers.loadGlb(assetLoader, resourceLoader, registry.loadGlbBuffer(slot))?.let {
                weaponModels[slot] = it
            }
        }
        loadedRevision = registry.revision.value
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
        uiHelper.detach()

        destroyModel(mapModel)
        destroyModel(tCharModel)
        destroyModel(ctCharModel)
        weaponModels.values.forEach { destroyModel(it) }

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

        if (registry.revision.value != loadedRevision) {
            reloadLobbyAssets()
        }

        val gfx = getGraphics()
        if (gfx != lastGraphics) {
            FilamentSceneHelpers.applyGraphicsSettings(engine, view, gfx)
            lastGraphics = gfx
        }

        val aspect = surfaceWidth.toDouble() / surfaceHeight.toDouble().coerceAtLeast(1.0)
        camera.setProjection(40.0, aspect, 0.1, 200.0, Camera.Fov.VERTICAL)

        val (sx, sy, sz) = if (registry.hasCustomFile(ModelSlot.MAP_MIRAGE)) {
            Triple(0f, 0f, 0f)
        } else {
            mirageWorld.lobbyStandPos
        }

        val sway = sin(timeSec * 0.45f) * 0.08f
        val camX = sx - 3.35f + sway
        val camY = sy + 1.24f
        val camZ = sz + 1.55f
        camera.lookAt(
            camX.toDouble(), camY.toDouble(), camZ.toDouble(),
            sx.toDouble(), (sy + 0.98f).toDouble(), sz.toDouble(),
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
                targetHeight = 1.80f
            )
            tm.setTransform(tm.getInstance(activeChar.asset.root), tmpMat)

            val anim = activeChar.asset.instance.animator
            if (anim.animationCount > 0 && activeChar.idleAnimIdx >= 0) {
                val dur = anim.getAnimationDuration(activeChar.idleAnimIdx).coerceAtLeast(0.1f)
                anim.applyAnimation(activeChar.idleAnimIdx, timeSec % dur)
                anim.updateBoneMatrices()
            }
        }

        // Floating showcase weapon beside the operative when inspecting Loadout!
        val wSlot = ModelSlot.forWeapon(showcaseWeapon)
        val showFloatingWeapon = showcaseWeapon != WeaponType.KNIFE
        for ((slot, wModel) in weaponModels) {
            val vis = showFloatingWeapon && slot == wSlot
            FilamentSceneHelpers.setAssetVisible(scene, wModel, vis)
            if (vis) {
                val span = if (showcaseWeapon == WeaponType.AWP) 1.05f else 0.85f
                FilamentSceneHelpers.buildNormalizedPropMatrix(
                    out = tmpMat,
                    model = wModel,
                    targetMaxExtent = span,
                    tx = sx - 0.65f,
                    ty = sy + 1.05f + sin(timeSec * 1.8f) * 0.04f,
                    tz = sz + 0.72f,
                    yawDeg = (timeSec * 38f) % 360f,
                    pitchDeg = 6f
                )
                tm.setTransform(tm.getInstance(wModel.asset.root), tmpMat)
            }
        }

        val sc = swapChain ?: return
        if (uiHelper.isReadyToRender && renderer.beginFrame(sc, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }
}
