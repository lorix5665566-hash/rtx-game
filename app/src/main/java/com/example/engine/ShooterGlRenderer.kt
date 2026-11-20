package com.example.engine

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.example.model.ArenaMapId
import com.example.model.BombState
import com.example.model.GraphicsSettings
import com.example.model.TeamSide
import com.example.model.WeaponType
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

class ShooterGlRenderer(
    private val engine: GameEngine
) : GLSurfaceView.Renderer {

    private var shader: PbrShader? = null
    private var textures: ProceduralTextures? = null
    private var soldierModel: HumanSoldierModel? = null
    private var weaponModel: WeaponViewModel? = null
    private var unitBox: GlMesh? = null
    private var floorQuad: GlMesh? = null
    private var skyCube: GlMesh? = null
    private var sparkMesh: GlMesh? = null
    private var plantedC4Mesh: GlMesh? = null
    private var plantedC4Led: GlMesh? = null

    private var surfaceWidth: Int = 1
    private var surfaceHeight: Int = 1
    private var lastFrameNs: Long = 0L

    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val skyViewMatrix = FloatArray(16)
    private val weaponProjMatrix = FloatArray(16)

    private val lightPositions = FloatArray(12)
    private val lightColors = FloatArray(12)
    private val lightRadii = FloatArray(4)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.03f, 0.045f, 0.075f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)

        shader = PbrShader()
        textures = ProceduralTextures().also { it.initAll() }
        soldierModel = HumanSoldierModel()
        weaponModel = WeaponViewModel()
        unitBox = MeshBuilder.createBox(1f, 1f, 1f)
        floorQuad = MeshBuilder.createFloorQuad(84f, 78f)
        skyCube = MeshBuilder.createBox(2f, 2f, 2f)
        sparkMesh = MeshBuilder.createEllipsoid(0.06f, 0.06f, 0.06f, 6, 8)
        plantedC4Mesh = MeshBuilder.createBeveledBox(0.24f, 0.08f, 0.17f, 0.18f)
        plantedC4Led = MeshBuilder.createEllipsoid(0.022f, 0.022f, 0.022f, 8, 8)
        lastFrameNs = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastFrameNs) / 1_000_000_000.0f).coerceIn(0.001f, 0.06f)
        lastFrameNs = now

        engine.update(dt)

        val sh = shader ?: return
        val tex = textures ?: return
        val gfx: GraphicsSettings = engine.settings.graphics
        val world = engine.world
        val atm = world.atmosphere

        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val aspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        val baseFov = gfx.fov.coerceIn(65f, 105f)
        val zoomTarget = engine.activeWeapon.adsZoomFactor
        val liveFov = baseFov * (1f - engine.adsBlend * (1f - zoomTarget))
        Matrix.perspectiveM(projMatrix, 0, liveFov, aspect, 0.08f, 180f)

        val camX = engine.playerX
        val camY = engine.playerY + engine.eyeHeight
        val camZ = engine.playerZ
        val cosP = cos(engine.playerPitchRad)
        val lookX = camX - sin(engine.playerYawRad) * cosP
        val lookY = camY + sin(engine.playerPitchRad)
        val lookZ = camZ - cos(engine.playerYawRad) * cosP

        Matrix.setLookAtM(viewMatrix, 0, camX, camY, camZ, lookX, lookY, lookZ, 0f, 1f, 0f)
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        // 1. Skybox
        drawSkybox(sh, atm, gfx, engine.timeSec)

        // 2. Setup PBR Shader & Dynamic Muzzle Point Light
        GLES30.glUseProgram(sh.programId)
        GLES30.glUniform3f(sh.uCamPos, camX, camY, camZ)
        GLES30.glUniform1f(sh.uTime, engine.timeSec)
        GLES30.glUniform3fv(sh.uSunDir, 1, atm.sunDir, 0)
        GLES30.glUniform3fv(sh.uSunColor, 1, atm.sunColor, 0)
        GLES30.glUniform3fv(sh.uFogColor, 1, atm.fogColor, 0)
        GLES30.glUniform1f(sh.uFogDensity, atm.fogDensity)

        System.arraycopy(atm.lightPositions, 0, lightPositions, 0, 12)
        System.arraycopy(atm.lightColors, 0, lightColors, 0, 12)
        System.arraycopy(atm.lightRadii, 0, lightRadii, 0, 4)

        val flashIntensity = engine.muzzleFlashTimer.coerceIn(0f, 1f)
        lightPositions[9] = camX - sin(engine.playerYawRad) * 1.0f
        lightPositions[10] = camY - 0.1f
        lightPositions[11] = camZ - cos(engine.playerYawRad) * 1.0f
        lightColors[9] = 4.5f * flashIntensity
        lightColors[10] = 2.4f * flashIntensity
        lightColors[11] = 0.72f * flashIntensity
        lightRadii[3] = if (flashIntensity > 0.02f) 12.0f else 0.1f

        GLES30.glUniform3fv(sh.uLightPos, 4, lightPositions, 0)
        GLES30.glUniform3fv(sh.uLightColor, 4, lightColors, 0)
        GLES30.glUniform1fv(sh.uLightRadius, 4, lightRadii, 0)
        GLES30.glUniform1i(sh.uQualityLevel, gfx.preset.level)
        GLES30.glUniform1i(sh.uRtxReflections, if (gfx.rtxReflections) 1 else 0)
        GLES30.glUniform1i(sh.uDynamicLights, if (gfx.dynamicLighting) 1 else 0)
        GLES30.glUniform1i(sh.uHdTextures, if (gfx.hdTextures) 1 else 0)
        GLES30.glUniform1i(sh.uBloomAces, if (gfx.bloomAndAces) 1 else 0)
        GLES30.glUniform1i(sh.uAlbedoTex, 0)
        GLES30.glUniform1i(sh.uNormalTex, 1)

        // 3. Floor
        Matrix.setIdentityM(modelMatrix, 0)
        drawMesh(
            sh, tex, floorQuad ?: return, modelMatrix, vpMatrix,
            atm.floorTexture, atm.floorColor[0], atm.floorColor[1], atm.floorColor[2],
            0.08f, atm.floorRoughness, 0f, 0f, 0f, atm.floorReflectivity,
            22f, 20f
        )

        // 4. Map Geometry
        val box = unitBox ?: return
        for (b in world.blocks) {
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, b.cx, b.cy, b.cz)
            Matrix.scaleM(modelMatrix, 0, b.sx, b.sy, b.sz)
            val uvX = (maxOf(b.sx, b.sz) / 3.0f).coerceAtLeast(1f)
            val uvY = (b.sy / 3.0f).coerceAtLeast(1f)
            drawMesh(
                sh, tex, box, modelMatrix, vpMatrix, b.texture,
                b.r, b.g, b.b, b.metallic, b.roughness,
                b.emissiveR, b.emissiveG, b.emissiveB, b.reflectivity,
                uvX, uvY
            )
        }

        // 5. Planted C4 Explosive on Floor (if planted!)
        if (engine.bombState == BombState.PLANTED) {
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, engine.plantedBombX, 0.05f, engine.plantedBombZ)
            drawMesh(sh, tex, plantedC4Mesh ?: return, modelMatrix, vpMatrix, MaterialTexture.C4_PACK, 1f, 1f, 1f, 0.15f, 0.65f, 0f, 0f, 0f, 0.08f, 1f, 1f)
            val pulse = if (((engine.timeSec * 4.5f).toInt() % 2) == 0) 3.2f else 0.15f
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, engine.plantedBombX + 0.05f, 0.105f, engine.plantedBombZ)
            drawMesh(sh, tex, plantedC4Led ?: return, modelMatrix, vpMatrix, MaterialTexture.VISOR_GLASS, 1f, 0.15f, 0.10f, 0f, 1f, pulse, 0.12f, 0.06f, 0f, 1f, 1f)
        }

        // 6. All CT & T Human Operatives + Deferred Volumetric Gunfire Flames
        val pendingBotFlames = ArrayList<Triple<GlMesh, FloatArray, Float>>(4)
        val soldier = soldierModel ?: return
        for (bot in engine.bots) {
            soldier.draw(
                bot = bot,
                timeSec = engine.timeSec,
                isLobbyPose = false,
                drawPart = { mesh, mat, matTex, r, g, b, met, rough, er, eg, eb, refl ->
                    drawMesh(sh, tex, mesh, mat, vpMatrix, matTex, r, g, b, met, rough, er, eg, eb, refl, 1f, 1f)
                },
                drawFlame = { flameMesh, mat, intensity ->
                    pendingBotFlames.add(Triple(flameMesh, mat.copyOf(), intensity))
                }
            )
        }

        // 7. Bullet Impact Sparks
        val spark = sparkMesh ?: return
        for (fx in engine.impacts) {
            val s = (fx.life * 4.5f).coerceIn(0.2f, 1.3f)
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, fx.x, fx.y, fx.z)
            Matrix.scaleM(modelMatrix, 0, s, s, s)
            drawMesh(sh, tex, spark, modelMatrix, vpMatrix, MaterialTexture.VISOR_GLASS, 1f, 0.78f, 0.35f, 0f, 1f, 2.8f, 1.8f, 0.65f, 0f, 1f, 1f)
        }

        // Render additive world muzzle flames (bots)
        if (pendingBotFlames.isNotEmpty()) {
            for ((fMesh, fMat, fInt) in pendingBotFlames) {
                drawVolumetricFlame(sh, tex, fMesh, fMat, vpMatrix, fInt)
            }
            GLES30.glUseProgram(sh.programId)
        }

        // 8. First-Person CS2 Weapon + Additive Microsecond Fire Muzzle Flash
        if (!engine.latestHud.isPlayerDead) {
            GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
            Matrix.perspectiveM(weaponProjMatrix, 0, 62f, aspect, 0.03f, 10f)
            GLES30.glUniform3f(sh.uCamPos, 0f, 0f, 0f)
            val hud = engine.latestHud
            var playerFlame: Triple<GlMesh, FloatArray, Float>? = null
            weaponModel?.draw(
                weapon = engine.activeWeapon,
                playerSide = engine.matchConfig.playerSide,
                adsBlend = engine.adsBlend,
                walkBobPhase = engine.walkBobPhase,
                moveSpeedRatio = engine.moveSpeedRatio,
                recoilKick = engine.recoilKick,
                reloadProgress = hud.reloadProgress,
                isReloading = hud.isReloading,
                muzzleFlash = engine.muzzleFlashTimer,
                muzzleFlashRoll = engine.muzzleFlashRoll,
                boltProgress = engine.boltProgress,
                knifeSwingProgress = engine.knifeSwingProgress,
                bombPlantProgress = engine.bombPlantProgress,
                timeSec = engine.timeSec,
                drawPart = { mesh, mat, matTex, r, g, b, met, rough, er, eg, eb, refl ->
                    drawMesh(sh, tex, mesh, mat, weaponProjMatrix, matTex, r, g, b, met, rough, er, eg, eb, refl, 1f, 1f)
                },
                drawFlame = { fMesh, fMat, fInt ->
                    playerFlame = Triple(fMesh, fMat.copyOf(), fInt)
                }
            )
            playerFlame?.let { (fMesh, fMat, fInt) ->
                drawVolumetricFlame(sh, tex, fMesh, fMat, weaponProjMatrix, fInt)
            }
        }
    }

    private fun drawSkybox(sh: PbrShader, atm: MapAtmosphere, gfx: GraphicsSettings, timeSec: Float) {
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glUseProgram(sh.skyProgramId)
        System.arraycopy(viewMatrix, 0, skyViewMatrix, 0, 16)
        skyViewMatrix[12] = 0f; skyViewMatrix[13] = 0f; skyViewMatrix[14] = 0f
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, skyViewMatrix, 0)
        GLES30.glUniformMatrix4fv(sh.skyUMvp, 1, false, mvpMatrix, 0)
        GLES30.glUniform1f(sh.skyUTime, timeSec)
        GLES30.glUniform1i(sh.skyUBloomAces, if (gfx.bloomAndAces) 1 else 0)
        GLES30.glUniform3fv(sh.skyUTopColor, 1, atm.skyTopColor, 0)
        GLES30.glUniform3fv(sh.skyUHorizonColor, 1, atm.skyHorizonColor, 0)
        GLES30.glUniform3fv(sh.skyUSunDir, 1, atm.sunDir, 0)
        GLES30.glUniform3fv(sh.skyUSunGlow, 1, atm.skySunGlow, 0)
        skyCube?.draw()
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glDepthMask(true)
    }

    private fun drawVolumetricFlame(
        sh: PbrShader,
        tex: ProceduralTextures,
        mesh: GlMesh,
        model: FloatArray,
        vp: FloatArray,
        intensity: Float
    ) {
        GLES30.glUseProgram(sh.flashProgramId)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        val flameTex = tex.get(MaterialTexture.MUZZLE_FIRE)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, flameTex.albedoId)
        GLES30.glUniform1i(sh.flashUTex, 0)

        Matrix.multiplyMM(mvpMatrix, 0, vp, 0, model, 0)
        GLES30.glUniformMatrix4fv(sh.flashUMvp, 1, false, mvpMatrix, 0)
        GLES30.glUniform1f(sh.flashUIntensity, intensity.coerceIn(0f, 1f))
        GLES30.glUniform3f(sh.flashUColorTint, 1.15f, 0.88f, 0.52f)
        mesh.draw()

        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawMesh(
        sh: PbrShader,
        tex: ProceduralTextures,
        mesh: GlMesh,
        model: FloatArray,
        vp: FloatArray,
        material: MaterialTexture,
        r: Float, g: Float, b: Float,
        metallic: Float, roughness: Float,
        er: Float, eg: Float, eb: Float,
        reflectivity: Float,
        uvScaleX: Float, uvScaleY: Float
    ) {
        val pair = tex.get(material)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pair.albedoId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pair.normalId)

        Matrix.multiplyMM(mvpMatrix, 0, vp, 0, model, 0)
        GLES30.glUniformMatrix4fv(sh.uMvp, 1, false, mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(sh.uModel, 1, false, model, 0)
        GLES30.glUniform3f(sh.uBaseColor, r, g, b)
        GLES30.glUniform1f(sh.uMetallic, metallic)
        GLES30.glUniform1f(sh.uRoughness, roughness)
        GLES30.glUniform3f(sh.uEmissive, er, eg, eb)
        GLES30.glUniform1f(sh.uReflectivity, reflectivity)
        GLES30.glUniform2f(sh.uUvScale, uvScaleX, uvScaleY)
        mesh.draw()
    }
}

/**
 * Dedicated Live 3D CS2 Lobby Backdrop Renderer:
 * Renders the sunlit Moroccan Mirage courtyard with iconic blue-and-gold tile towers
 * and the standing 3D Terrorist or Counter-Terrorist operative holding a knife/weapon
 * right in the center — matching the user's attached CS2 menu reference screenshot!
 */
class LobbyGlRenderer(
    private val getGraphics: () -> GraphicsSettings
) : GLSurfaceView.Renderer {

    @Volatile var showcaseSide: TeamSide = TeamSide.T
    @Volatile var showcaseWeapon: WeaponType = WeaponType.KNIFE
    @Volatile var characterYawDeg: Float = 12f

    private var shader: PbrShader? = null
    private var textures: ProceduralTextures? = null
    private var soldierModel: HumanSoldierModel? = null
    private var unitBox: GlMesh? = null
    private var floorQuad: GlMesh? = null
    private var skyCube: GlMesh? = null

    private val mirageWorld = ArenaWorld(ArenaMapId.MIRAGE)
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var timeSec = 0f
    private var lastNs = 0L

    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val skyViewMatrix = FloatArray(16)

    private val showcaseBot = BotOperative(
        id = 999,
        team = TeamSide.T,
        weapon = WeaponType.KNIFE,
        x = 0f,
        z = 26.8f,
        yawDeg = 12f
    )

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.55f, 0.50f, 0.44f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)

        shader = PbrShader()
        textures = ProceduralTextures().also { it.initAll() }
        soldierModel = HumanSoldierModel()
        unitBox = MeshBuilder.createBox(1f, 1f, 1f)
        floorQuad = MeshBuilder.createFloorQuad(84f, 78f)
        skyCube = MeshBuilder.createBox(2f, 2f, 2f)
        lastNs = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastNs) / 1_000_000_000.0f).coerceIn(0.001f, 0.05f)
        lastNs = now
        timeSec += dt

        val sh = shader ?: return
        val tex = textures ?: return
        val gfx = getGraphics()
        val atm = mirageWorld.atmosphere

        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val aspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        Matrix.perspectiveM(projMatrix, 0, 42f, aspect, 0.1f, 160f)

        // Subtle cinematic CS2 lobby breathing camera framed on courtyard + character!
        val sway = sin(timeSec * 0.45f) * 0.10f
        val camX = sway
        val camY = 1.24f
        val camZ = 31.05f
        Matrix.setLookAtM(viewMatrix, 0, camX, camY, camZ, 0f, 1.04f, 25.0f, 0f, 1f, 0f)
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        // 1. Sunlit Mirage Sky
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glUseProgram(sh.skyProgramId)
        System.arraycopy(viewMatrix, 0, skyViewMatrix, 0, 16)
        skyViewMatrix[12] = 0f; skyViewMatrix[13] = 0f; skyViewMatrix[14] = 0f
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, skyViewMatrix, 0)
        GLES30.glUniformMatrix4fv(sh.skyUMvp, 1, false, mvpMatrix, 0)
        GLES30.glUniform1f(sh.skyUTime, timeSec)
        GLES30.glUniform1i(sh.skyUBloomAces, if (gfx.bloomAndAces) 1 else 0)
        GLES30.glUniform3fv(sh.skyUTopColor, 1, atm.skyTopColor, 0)
        GLES30.glUniform3fv(sh.skyUHorizonColor, 1, atm.skyHorizonColor, 0)
        GLES30.glUniform3fv(sh.skyUSunDir, 1, atm.sunDir, 0)
        GLES30.glUniform3fv(sh.skyUSunGlow, 1, atm.skySunGlow, 0)
        skyCube?.draw()
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glDepthMask(true)

        // 2. PBR Setup
        GLES30.glUseProgram(sh.programId)
        GLES30.glUniform3f(sh.uCamPos, camX, camY, camZ)
        GLES30.glUniform1f(sh.uTime, timeSec)
        GLES30.glUniform3fv(sh.uSunDir, 1, atm.sunDir, 0)
        GLES30.glUniform3fv(sh.uSunColor, 1, atm.sunColor, 0)
        GLES30.glUniform3fv(sh.uFogColor, 1, atm.fogColor, 0)
        GLES30.glUniform1f(sh.uFogDensity, 0.00010f)
        GLES30.glUniform3fv(sh.uLightPos, 4, floatArrayOf(1.5f, 3.0f, 30.0f, -2.5f, 2.5f, 28.0f, 0f, 4f, 22f, 0f, 2f, 26f), 0)
        GLES30.glUniform3fv(sh.uLightColor, 4, floatArrayOf(1.3f, 1.15f, 0.92f, 0.85f, 0.95f, 1.15f, 1.0f, 0.9f, 0.7f, 0f, 0f, 0f), 0)
        GLES30.glUniform1fv(sh.uLightRadius, 4, floatArrayOf(18f, 16f, 20f, 0.1f), 0)
        GLES30.glUniform1i(sh.uQualityLevel, gfx.preset.level)
        GLES30.glUniform1i(sh.uRtxReflections, if (gfx.rtxReflections) 1 else 0)
        GLES30.glUniform1i(sh.uDynamicLights, 1)
        GLES30.glUniform1i(sh.uHdTextures, 1)
        GLES30.glUniform1i(sh.uBloomAces, if (gfx.bloomAndAces) 1 else 0)
        GLES30.glUniform1i(sh.uAlbedoTex, 0)
        GLES30.glUniform1i(sh.uNormalTex, 1)

        // 3. Sunlit Mirage Courtyard Floor & Moroccan Tile Towers
        Matrix.setIdentityM(modelMatrix, 0)
        drawMesh(sh, tex, floorQuad ?: return, modelMatrix, atm.floorTexture, atm.floorColor[0], atm.floorColor[1], atm.floorColor[2], 0.08f, atm.floorRoughness, 0.14f, 22f, 20f)

        val box = unitBox ?: return
        for (b in mirageWorld.blocks) {
            if (b.cz < -10f) continue
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, b.cx, b.cy, b.cz)
            Matrix.scaleM(modelMatrix, 0, b.sx, b.sy, b.sz)
            drawMesh(sh, tex, box, modelMatrix, b.texture, b.r, b.g, b.b, b.metallic, b.roughness, b.reflectivity, (maxOf(b.sx, b.sz) / 3.0f).coerceAtLeast(1f), (b.sy / 3.0f).coerceAtLeast(1f))
        }

        // 4. Center 3D Character (Terrorist / Спецназ standing in Mirage courtyard!)
        val activeBot = showcaseBot.copy(
            team = showcaseSide,
            weapon = showcaseWeapon,
            x = 0f,
            z = 26.8f,
            yawDeg = characterYawDeg
        )
        soldierModel?.draw(
            bot = activeBot,
            timeSec = timeSec,
            isLobbyPose = true,
            drawPart = { mesh, mat, matTex, r, g, b, met, rough, _, _, _, refl ->
                drawMesh(sh, tex, mesh, mat, matTex, r, g, b, met, rough, refl, 1f, 1f)
            }
        )
    }

    private fun drawMesh(
        sh: PbrShader, tex: ProceduralTextures, mesh: GlMesh, model: FloatArray,
        material: MaterialTexture, r: Float, g: Float, b: Float,
        metallic: Float, roughness: Float, reflectivity: Float, uvX: Float, uvY: Float
    ) {
        val pair = tex.get(material)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pair.albedoId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pair.normalId)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, model, 0)
        GLES30.glUniformMatrix4fv(sh.uMvp, 1, false, mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(sh.uModel, 1, false, model, 0)
        GLES30.glUniform3f(sh.uBaseColor, r, g, b)
        GLES30.glUniform1f(sh.uMetallic, metallic)
        GLES30.glUniform1f(sh.uRoughness, roughness)
        GLES30.glUniform3f(sh.uEmissive, 0f, 0f, 0f)
        GLES30.glUniform1f(sh.uReflectivity, reflectivity)
        GLES30.glUniform2f(sh.uUvScale, uvX, uvY)
        mesh.draw()
    }
}
