package com.example.engine

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.example.model.GraphicsSettings
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
        floorQuad = MeshBuilder.createFloorQuad(44.4f, 44.4f)
        skyCube = MeshBuilder.createBox(2f, 2f, 2f)
        sparkMesh = MeshBuilder.createEllipsoid(0.08f, 0.08f, 0.08f, 6, 8)
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
        Matrix.perspectiveM(projMatrix, 0, liveFov, aspect, 0.08f, 150f)

        val camX = engine.playerX
        val camY = engine.playerY + engine.eyeHeight
        val camZ = engine.playerZ
        val cosP = cos(engine.playerPitchRad)
        val lookX = camX - sin(engine.playerYawRad) * cosP
        val lookY = camY + sin(engine.playerPitchRad)
        val lookZ = camZ - cos(engine.playerYawRad) * cosP

        Matrix.setLookAtM(viewMatrix, 0, camX, camY, camZ, lookX, lookY, lookZ, 0f, 1f, 0f)
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        // --- 1. MAP-SPECIFIC ATMOSPHERIC SKYBOX ---
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glUseProgram(sh.skyProgramId)
        System.arraycopy(viewMatrix, 0, skyViewMatrix, 0, 16)
        skyViewMatrix[12] = 0f; skyViewMatrix[13] = 0f; skyViewMatrix[14] = 0f
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, skyViewMatrix, 0)
        GLES30.glUniformMatrix4fv(sh.skyUMvp, 1, false, mvpMatrix, 0)
        GLES30.glUniform1f(sh.skyUTime, engine.timeSec)
        GLES30.glUniform1i(sh.skyUBloomAces, if (gfx.bloomAndAces) 1 else 0)
        GLES30.glUniform3fv(sh.skyUTopColor, 1, atm.skyTopColor, 0)
        GLES30.glUniform3fv(sh.skyUHorizonColor, 1, atm.skyHorizonColor, 0)
        GLES30.glUniform3fv(sh.skyUSunDir, 1, atm.sunDir, 0)
        GLES30.glUniform3fv(sh.skyUSunGlow, 1, atm.skySunGlow, 0)
        skyCube?.draw()
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glDepthMask(true)

        // --- 2. PBR/RTX SHADER & DYNAMIC LIGHTS ---
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
        lightPositions[9] = camX - sin(engine.playerYawRad) * 1.1f
        lightPositions[10] = camY - 0.1f
        lightPositions[11] = camZ - cos(engine.playerYawRad) * 1.1f
        lightColors[9] = 4.6f * flashIntensity
        lightColors[10] = 2.6f * flashIntensity
        lightColors[11] = 0.85f * flashIntensity
        lightRadii[3] = if (flashIntensity > 0.02f) 14.5f else 0.1f

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

        // --- 3. MAP FLOOR ---
        Matrix.setIdentityM(modelMatrix, 0)
        drawMesh(
            sh = sh, tex = tex, mesh = floorQuad ?: return, model = modelMatrix, vp = vpMatrix,
            material = atm.floorTexture,
            r = atm.floorColor[0], g = atm.floorColor[1], b = atm.floorColor[2],
            metallic = 0.10f, roughness = atm.floorRoughness,
            er = 0f, eg = 0f, eb = 0f, reflectivity = atm.floorReflectivity,
            uvScaleX = 11f, uvScaleY = 11f
        )

        // --- 4. MAP STRUCTURES & COVER ---
        val box = unitBox ?: return
        for (b in world.blocks) {
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, b.cx, b.cy, b.cz)
            Matrix.scaleM(modelMatrix, 0, b.sx, b.sy, b.sz)
            val uvX = (maxOf(b.sx, b.sz) / 2.8f).coerceAtLeast(1f)
            val uvY = (b.sy / 2.6f).coerceAtLeast(1f)
            drawMesh(
                sh, tex, box, modelMatrix, vpMatrix, b.texture,
                b.r, b.g, b.b, b.metallic, b.roughness,
                b.emissiveR, b.emissiveG, b.emissiveB, b.reflectivity,
                uvX, uvY
            )
        }

        // --- 5. REALISTIC 1:1 HUMAN SOLDIERS IN BALLISTIC VESTS ---
        val soldier = soldierModel ?: return
        for (enemy in engine.enemies) {
            soldier.draw(enemy, engine.timeSec) { mesh, mat, matTex, r, g, b, met, rough, er, eg, eb, refl ->
                drawMesh(sh, tex, mesh, mat, vpMatrix, matTex, r, g, b, met, rough, er, eg, eb, refl, 1f, 1f)
            }
        }

        // --- 6. IMPACT SPARKS & TRACERS ---
        val spark = sparkMesh ?: return
        for (fx in engine.impacts) {
            val s = (fx.life * 4.5f).coerceIn(0.2f, 1.4f)
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, fx.x, fx.y, fx.z)
            Matrix.scaleM(modelMatrix, 0, s, s, s)
            if (fx.isEnemy) {
                drawMesh(sh, tex, spark, modelMatrix, vpMatrix, MaterialTexture.VISOR_GLASS, 1f, 0.35f, 0.2f, 0f, 1f, 3.0f, 0.8f, 0.25f, 0f, 1f, 1f)
            } else {
                drawMesh(sh, tex, spark, modelMatrix, vpMatrix, MaterialTexture.VISOR_GLASS, 1f, 0.85f, 0.4f, 0f, 1f, 2.8f, 2.1f, 0.85f, 0f, 1f, 1f)
            }
        }

        // --- 7. FIRST-PERSON 3D FIREARM VIEWMODEL (M4A1 / AK-47 / AWM SNIPER) ---
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        Matrix.perspectiveM(weaponProjMatrix, 0, 62f, aspect, 0.03f, 10f)
        GLES30.glUniform3f(sh.uCamPos, 0f, 0f, 0f)
        val hud = engine.latestHud
        weaponModel?.draw(
            weapon = engine.activeWeapon,
            adsBlend = engine.adsBlend,
            walkBobPhase = engine.walkBobPhase,
            moveSpeedRatio = engine.moveSpeedRatio,
            recoilKick = engine.recoilKick,
            reloadProgress = hud.reloadProgress,
            isReloading = hud.isReloading,
            muzzleFlash = engine.muzzleFlashTimer,
            boltProgress = engine.boltProgress
        ) { mesh, mat, matTex, r, g, b, met, rough, er, eg, eb, refl ->
            drawMesh(sh, tex, mesh, mat, weaponProjMatrix, matTex, r, g, b, met, rough, er, eg, eb, refl, 1f, 1f)
        }
    }

    private fun drawMesh(
        sh: PbrShader,
        tex: ProceduralTextures,
        mesh: GlMesh,
        model: FloatArray,
        vp: FloatArray,
        material: MaterialTexture,
        r: Float,
        g: Float,
        b: Float,
        metallic: Float,
        roughness: Float,
        er: Float,
        eg: Float,
        eb: Float,
        reflectivity: Float,
        uvScaleX: Float,
        uvScaleY: Float
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
