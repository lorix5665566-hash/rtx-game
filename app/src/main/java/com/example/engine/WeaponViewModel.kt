package com.example.engine

import android.opengl.Matrix
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * First-person 3D tactical assault rifle (M4-X RTX Carbine) with brushed gunmetal PBR,
 * EOTech holographic sight, luminous emerald reticle, dynamic recoil, ADS centering,
 * tactical walk bob, and reload animation.
 */
class WeaponViewModel {
    private val receiver = MeshBuilder.createBox(0.068f, 0.088f, 0.42f)
    private val handguard = MeshBuilder.createBox(0.058f, 0.068f, 0.34f)
    private val topRail = MeshBuilder.createBox(0.032f, 0.016f, 0.56f)
    private val suppressor = MeshBuilder.createTaperedLimb(0.022f, 0.022f, 0.022f, 0.022f, 0.24f, 1.0f, 14, 4)
    private val magazine = MeshBuilder.createBox(0.042f, 0.19f, 0.075f)
    private val opticBase = MeshBuilder.createBox(0.052f, 0.022f, 0.11f)
    private val opticHoodLeft = MeshBuilder.createBox(0.008f, 0.056f, 0.085f)
    private val opticHoodRight = MeshBuilder.createBox(0.008f, 0.056f, 0.085f)
    private val opticHoodTop = MeshBuilder.createBox(0.054f, 0.008f, 0.085f)
    private val reticleDot = MeshBuilder.createEllipsoid(0.0038f, 0.0038f, 0.0038f, 6, 8)
    private val laserBox = MeshBuilder.createBox(0.028f, 0.028f, 0.10f)
    private val ammoStrip = MeshBuilder.createBox(0.006f, 0.016f, 0.09f)
    private val muzzleStarA = MeshBuilder.createBox(0.14f, 0.032f, 0.032f)
    private val muzzleStarB = MeshBuilder.createBox(0.032f, 0.14f, 0.032f)
    private val glovedHand = MeshBuilder.createEllipsoid(0.045f, 0.042f, 0.065f, 10, 12)
    private val forearmSleeve = MeshBuilder.createTaperedLimb(0.048f, 0.048f, 0.058f, 0.058f, 0.32f, 1.04f, 12, 5)

    private val rootMat = FloatArray(16)
    private val partMat = FloatArray(16)
    private val worldMat = FloatArray(16)

    fun draw(
        adsBlend: Float,
        walkBobPhase: Float,
        moveSpeedRatio: Float,
        recoilKick: Float,
        reloadProgress: Float,
        isReloading: Boolean,
        muzzleFlash: Float,
        ammoRatio: Float,
        drawPart: (
            mesh: GlMesh,
            modelMatrix: FloatArray,
            texture: MaterialTexture,
            r: Float,
            g: Float,
            b: Float,
            metallic: Float,
            roughness: Float,
            emissiveR: Float,
            emissiveG: Float,
            emissiveB: Float,
            reflectivity: Float
        ) -> Unit
    ) {
        val bobAmp = (1f - adsBlend * 0.78f) * moveSpeedRatio
        val bobX = cos(walkBobPhase) * 0.014f * bobAmp
        val bobY = sin(walkBobPhase * 2f) * 0.011f * bobAmp

        // Hip-fire vs ADS position in view space (camera at origin looking down -Z)
        val posX = lerp(0.21f, 0.0f, adsBlend) + bobX
        val posY = lerp(-0.205f, -0.142f, adsBlend) + bobY - recoilKick * 0.012f
        val posZ = lerp(-0.46f, -0.35f, adsBlend) + recoilKick * 0.065f

        val reloadTilt = if (isReloading) sin(reloadProgress * PI).toFloat() else 0f

        Matrix.setIdentityM(rootMat, 0)
        Matrix.translateM(rootMat, 0, posX, posY - reloadTilt * 0.09f, posZ)
        Matrix.rotateM(rootMat, 0, recoilKick * 7.5f - reloadTilt * 16f, 1f, 0f, 0f)
        Matrix.rotateM(rootMat, 0, reloadTilt * 28f, 0f, 0f, 1f)
        Matrix.rotateM(rootMat, 0, lerp(3.5f, 0f, adsBlend), 0f, 1f, 0f)

        // Main Receiver
        drawLocal(receiver, 0f, 0f, 0f, 0f, 0f, MaterialTexture.GUNMETAL, 0.62f, 0.66f, 0.72f, 0.86f, 0.24f, 0f, 0f, 0f, 0.60f, drawPart)

        // Handguard
        drawLocal(handguard, 0f, 0.005f, -0.34f, 0f, 0f, MaterialTexture.GUNMETAL, 0.48f, 0.52f, 0.56f, 0.78f, 0.34f, 0f, 0f, 0f, 0.45f, drawPart)

        // Top Picatinny Rail
        drawLocal(topRail, 0f, 0.049f, -0.16f, 0f, 0f, MaterialTexture.GUNMETAL, 0.72f, 0.76f, 0.80f, 0.90f, 0.22f, 0f, 0f, 0f, 0.70f, drawPart)

        // Tactical Suppressor
        drawLocal(suppressor, 0f, 0.008f, -0.61f, 90f, 0f, MaterialTexture.GUNMETAL, 0.36f, 0.39f, 0.44f, 0.88f, 0.28f, 0f, 0f, 0f, 0.58f, drawPart)

        // Curved Magazine (drops down during reload)
        val magDrop = if (isReloading) sin(reloadProgress * PI).toFloat() * 0.24f else 0f
        drawLocal(magazine, 0f, -0.11f - magDrop, -0.06f, 14f, 0f, MaterialTexture.SOLDIER_ARMOR, 0.44f, 0.47f, 0.52f, 0.55f, 0.42f, 0f, 0f, 0f, 0.30f, drawPart)

        // Holographic Optic Housing
        drawLocal(opticBase, 0f, 0.064f, -0.04f, 0f, 0f, MaterialTexture.GUNMETAL, 0.40f, 0.44f, 0.48f, 0.82f, 0.30f, 0f, 0f, 0f, 0.52f, drawPart)
        drawLocal(opticHoodLeft, -0.025f, 0.098f, -0.04f, 0f, 0f, MaterialTexture.GUNMETAL, 0.42f, 0.45f, 0.50f, 0.82f, 0.28f, 0f, 0f, 0f, 0.52f, drawPart)
        drawLocal(opticHoodRight, 0.025f, 0.098f, -0.04f, 0f, 0f, MaterialTexture.GUNMETAL, 0.42f, 0.45f, 0.50f, 0.82f, 0.28f, 0f, 0f, 0f, 0.52f, drawPart)
        drawLocal(opticHoodTop, 0f, 0.128f, -0.04f, 0f, 0f, MaterialTexture.GUNMETAL, 0.42f, 0.45f, 0.50f, 0.82f, 0.28f, 0f, 0f, 0f, 0.52f, drawPart)

        // Luminous Emerald Holographic Reticle Dot
        drawLocal(reticleDot, 0f, 0.098f, -0.045f, 0f, 0f, MaterialTexture.VISOR_GLASS, 0f, 1f, 0.72f, 0f, 1f, 0.0f, 2.2f, 1.45f, 0f, drawPart)

        // Side PEQ Laser Module + Glowing Ammo Status Strip
        drawLocal(laserBox, 0.042f, 0.015f, -0.35f, 0f, 0f, MaterialTexture.SOLDIER_ARMOR, 0.36f, 0.38f, 0.40f, 0.65f, 0.42f, 0f, 0f, 0f, 0.32f, drawPart)
        val stripR = if (ammoRatio < 0.25f) 2.2f else 0.0f
        val stripG = if (ammoRatio < 0.25f) 0.25f else 1.8f
        val stripB = if (ammoRatio < 0.25f) 0.30f else 1.15f
        drawLocal(ammoStrip, -0.036f, 0.022f, -0.04f, 0f, 0f, MaterialTexture.VISOR_GLASS, 0.2f, 1f, 0.8f, 0f, 1f, stripR, stripG, stripB, 0f, drawPart)

        // First-Person Gloved Support Hand & Tactical Sleeve (1:1 human realism)
        drawLocal(glovedHand, -0.025f, -0.055f, -0.31f, 0f, 0f, MaterialTexture.SOLDIER_ARMOR, 0.36f, 0.38f, 0.42f, 0.18f, 0.60f, 0f, 0f, 0f, 0.12f, drawPart)
        drawLocal(forearmSleeve, -0.14f, -0.17f, -0.18f, 52f, 34f, MaterialTexture.SOLDIER_FABRIC, 0.66f, 0.72f, 0.78f, 0.08f, 0.80f, 0f, 0f, 0f, 0.06f, drawPart)

        // Dynamic 3D Star-Burst Muzzle Flash
        if (muzzleFlash > 0.01f) {
            val s = 0.65f + muzzleFlash * 0.85f
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, 0f, 0.01f, -0.76f)
            Matrix.rotateM(partMat, 0, muzzleFlash * 190f, 0f, 0f, 1f)
            Matrix.scaleM(partMat, 0, s, s, s)
            Matrix.multiplyMM(worldMat, 0, rootMat, 0, partMat, 0)
            drawPart(muzzleStarA, worldMat, MaterialTexture.VISOR_GLASS, 1f, 0.85f, 0.45f, 0f, 1f, 3.2f * muzzleFlash, 1.9f * muzzleFlash, 0.55f * muzzleFlash, 0f)
            drawPart(muzzleStarB, worldMat, MaterialTexture.VISOR_GLASS, 1f, 0.85f, 0.45f, 0f, 1f, 3.2f * muzzleFlash, 1.9f * muzzleFlash, 0.55f * muzzleFlash, 0f)
        }
    }

    private fun drawLocal(
        mesh: GlMesh,
        x: Float,
        y: Float,
        z: Float,
        rotX: Float,
        rotY: Float,
        tex: MaterialTexture,
        r: Float,
        g: Float,
        b: Float,
        metallic: Float,
        roughness: Float,
        er: Float,
        eg: Float,
        eb: Float,
        refl: Float,
        drawPart: (GlMesh, FloatArray, MaterialTexture, Float, Float, Float, Float, Float, Float, Float, Float, Float) -> Unit
    ) {
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, x, y, z)
        if (rotY != 0f) Matrix.rotateM(partMat, 0, rotY, 0f, 1f, 0f)
        if (rotX != 0f) Matrix.rotateM(partMat, 0, rotX, 1f, 0f, 0f)
        Matrix.multiplyMM(worldMat, 0, rootMat, 0, partMat, 0)
        drawPart(mesh, worldMat, tex, r, g, b, metallic, roughness, er, eg, eb, refl)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun release() {
        receiver.release()
        handguard.release()
        topRail.release()
        suppressor.release()
        magazine.release()
        opticBase.release()
        opticHoodLeft.release()
        opticHoodRight.release()
        opticHoodTop.release()
        reticleDot.release()
        laserBox.release()
        ammoStrip.release()
        muzzleStarA.release()
        muzzleStarB.release()
        glovedHand.release()
        forearmSleeve.release()
    }
}
