package com.example.engine

import android.opengl.Matrix
import com.example.model.WeaponType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * First-person 3D weapon viewmodel supporting 3 distinct authentic firearms:
 * - M4A1 Tactical (rail carbine + suppressor + EOTech holographic red-dot)
 * - AK-47 (stamped steel receiver + wooden handguard/stock + curved banana mag + iron sights)
 * - AWM .338 Sniper (long fluted barrel + muzzle brake + telescopic scope + bolt action + bipod)
 */
class WeaponViewModel {
    // Shared & M4A1 meshes
    private val m4Receiver = MeshBuilder.createBox(0.066f, 0.086f, 0.42f)
    private val m4Handguard = MeshBuilder.createBox(0.056f, 0.066f, 0.34f)
    private val topRail = MeshBuilder.createBox(0.030f, 0.015f, 0.56f)
    private val barrelCyl = MeshBuilder.createTaperedLimb(0.016f, 0.016f, 0.016f, 0.016f, 0.32f, 1.0f, 14, 4)
    private val suppressor = MeshBuilder.createTaperedLimb(0.022f, 0.022f, 0.022f, 0.022f, 0.22f, 1.0f, 14, 4)
    private val magStraight = MeshBuilder.createBox(0.040f, 0.19f, 0.072f)
    private val opticBase = MeshBuilder.createBox(0.050f, 0.020f, 0.11f)
    private val opticHoodSide = MeshBuilder.createBox(0.008f, 0.054f, 0.082f)
    private val opticHoodTop = MeshBuilder.createBox(0.052f, 0.008f, 0.082f)
    private val reticleDot = MeshBuilder.createEllipsoid(0.0036f, 0.0036f, 0.0036f, 6, 8)
    private val laserBox = MeshBuilder.createBox(0.026f, 0.026f, 0.10f)

    // AK-47 dedicated meshes
    private val akReceiver = MeshBuilder.createBox(0.062f, 0.082f, 0.38f)
    private val akDustCover = MeshBuilder.createTaperedLimb(0.030f, 0.030f, 0.028f, 0.028f, 0.34f, 1.0f, 12, 4)
    private val akWoodHandguard = MeshBuilder.createBox(0.056f, 0.062f, 0.25f)
    private val akWoodUpper = MeshBuilder.createTaperedLimb(0.022f, 0.022f, 0.022f, 0.022f, 0.22f, 1.0f, 10, 3)
    private val akWoodStock = MeshBuilder.createBox(0.050f, 0.095f, 0.26f)
    private val akMagUpper = MeshBuilder.createBox(0.038f, 0.13f, 0.072f)
    private val akMagLower = MeshBuilder.createBox(0.036f, 0.13f, 0.070f)
    private val akFrontSightPost = MeshBuilder.createBox(0.012f, 0.054f, 0.022f)
    private val akGasTube = MeshBuilder.createTaperedLimb(0.013f, 0.013f, 0.013f, 0.013f, 0.28f, 1.0f, 10, 3)

    // AWM Sniper dedicated meshes
    private val sniperChassis = MeshBuilder.createBox(0.064f, 0.092f, 0.52f)
    private val sniperBarrel = MeshBuilder.createTaperedLimb(0.016f, 0.016f, 0.020f, 0.020f, 0.56f, 1.0f, 14, 4)
    private val sniperBrake = MeshBuilder.createBox(0.044f, 0.036f, 0.085f)
    private val scopeTube = MeshBuilder.createTaperedLimb(0.022f, 0.022f, 0.022f, 0.022f, 0.34f, 1.0f, 14, 4)
    private val scopeBellFront = MeshBuilder.createTaperedLimb(0.024f, 0.024f, 0.036f, 0.036f, 0.11f, 1.0f, 14, 4)
    private val scopeBellRear = MeshBuilder.createTaperedLimb(0.028f, 0.028f, 0.023f, 0.023f, 0.08f, 1.0f, 14, 4)
    private val scopeRing = MeshBuilder.createBox(0.036f, 0.048f, 0.022f)
    private val boltKnob = MeshBuilder.createEllipsoid(0.014f, 0.014f, 0.014f, 8, 8)
    private val boltStem = MeshBuilder.createBox(0.048f, 0.012f, 0.012f)
    private val bipodLeg = MeshBuilder.createTaperedLimb(0.008f, 0.008f, 0.008f, 0.008f, 0.22f, 1.0f, 8, 3)

    // Shared hand, forearm & muzzle flash
    private val muzzleStarA = MeshBuilder.createBox(0.15f, 0.032f, 0.032f)
    private val muzzleStarB = MeshBuilder.createBox(0.032f, 0.15f, 0.032f)
    private val glovedHand = MeshBuilder.createEllipsoid(0.045f, 0.042f, 0.065f, 10, 12)
    private val forearmSleeve = MeshBuilder.createTaperedLimb(0.048f, 0.048f, 0.058f, 0.058f, 0.32f, 1.04f, 12, 5)

    private val rootMat = FloatArray(16)
    private val partMat = FloatArray(16)
    private val worldMat = FloatArray(16)

    fun draw(
        weapon: WeaponType,
        adsBlend: Float,
        walkBobPhase: Float,
        moveSpeedRatio: Float,
        recoilKick: Float,
        reloadProgress: Float,
        isReloading: Boolean,
        muzzleFlash: Float,
        boltProgress: Float,
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
        // Hide 3D sniper rifle once full-screen 4x optical scope overlay takes over
        if (weapon.hasSniperScope && adsBlend > 0.78f) return

        val bobAmp = (1f - adsBlend * 0.80f) * moveSpeedRatio
        val bobX = cos(walkBobPhase) * 0.014f * bobAmp
        val bobY = sin(walkBobPhase * 2f) * 0.011f * bobAmp

        val posX = lerp(0.21f, 0.0f, adsBlend) + bobX
        val posY = lerp(-0.205f, if (weapon == WeaponType.AK47) -0.118f else -0.142f, adsBlend) + bobY - recoilKick * 0.012f
        val posZ = lerp(-0.46f, -0.35f, adsBlend) + recoilKick * 0.068f

        val reloadTilt = if (isReloading) sin(reloadProgress * PI).toFloat() else 0f
        val magDrop = if (isReloading) sin(reloadProgress * PI).toFloat() * 0.24f else 0f
        val boltTilt = if (weapon == WeaponType.SNIPER && boltProgress > 0f) sin(boltProgress * PI).toFloat() else 0f

        Matrix.setIdentityM(rootMat, 0)
        Matrix.translateM(rootMat, 0, posX, posY - reloadTilt * 0.09f, posZ)
        Matrix.rotateM(rootMat, 0, recoilKick * 8.0f - reloadTilt * 16f + boltTilt * 6f, 1f, 0f, 0f)
        Matrix.rotateM(rootMat, 0, reloadTilt * 28f - boltTilt * 14f, 0f, 0f, 1f)
        Matrix.rotateM(rootMat, 0, lerp(3.5f, 0f, adsBlend), 0f, 1f, 0f)

        val flashZ: Float
        when (weapon) {
            WeaponType.M4A1 -> {
                drawM4A1(magDrop, drawPart)
                flashZ = -0.76f
            }
            WeaponType.AK47 -> {
                drawAk47(magDrop, drawPart)
                flashZ = -0.78f
            }
            WeaponType.SNIPER -> {
                drawSniper(magDrop, boltTilt, drawPart)
                flashZ = -1.04f
            }
        }

        // Gloved support hand & combat uniform forearm
        drawLocal(glovedHand, -0.025f, -0.055f, -0.30f, 0f, 0f, MaterialTexture.SOLDIER_ARMOR, 0.36f, 0.34f, 0.30f, 0.12f, 0.72f, 0f, 0f, 0f, 0.06f, drawPart)
        drawLocal(forearmSleeve, -0.14f, -0.17f, -0.18f, 52f, 34f, MaterialTexture.SOLDIER_FABRIC, 0.86f, 0.90f, 0.84f, 0.06f, 0.86f, 0f, 0f, 0f, 0.03f, drawPart)

        // Dynamic 3D Star-Burst Muzzle Flash
        if (muzzleFlash > 0.01f) {
            val scaleBase = if (weapon == WeaponType.SNIPER) 1.35f else if (weapon == WeaponType.AK47) 1.15f else 0.85f
            val s = (0.65f + muzzleFlash * 0.85f) * scaleBase
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, 0f, 0.012f, flashZ)
            Matrix.rotateM(partMat, 0, muzzleFlash * 210f, 0f, 0f, 1f)
            Matrix.scaleM(partMat, 0, s, s, s)
            Matrix.multiplyMM(worldMat, 0, rootMat, 0, partMat, 0)
            drawPart(muzzleStarA, worldMat, MaterialTexture.VISOR_GLASS, 1f, 0.85f, 0.45f, 0f, 1f, 3.4f * muzzleFlash, 2.0f * muzzleFlash, 0.55f * muzzleFlash, 0f)
            drawPart(muzzleStarB, worldMat, MaterialTexture.VISOR_GLASS, 1f, 0.85f, 0.45f, 0f, 1f, 3.4f * muzzleFlash, 2.0f * muzzleFlash, 0.55f * muzzleFlash, 0f)
        }
    }

    private fun drawM4A1(
        magDrop: Float,
        drawPart: (GlMesh, FloatArray, MaterialTexture, Float, Float, Float, Float, Float, Float, Float, Float, Float) -> Unit
    ) {
        drawLocal(m4Receiver, 0f, 0f, 0f, 0f, 0f, MaterialTexture.GUNMETAL, 0.58f, 0.61f, 0.66f, 0.84f, 0.26f, 0f, 0f, 0f, 0.52f, drawPart)
        drawLocal(m4Handguard, 0f, 0.005f, -0.34f, 0f, 0f, MaterialTexture.GUNMETAL, 0.44f, 0.48f, 0.52f, 0.78f, 0.34f, 0f, 0f, 0f, 0.42f, drawPart)
        drawLocal(topRail, 0f, 0.048f, -0.16f, 0f, 0f, MaterialTexture.GUNMETAL, 0.70f, 0.74f, 0.78f, 0.90f, 0.22f, 0f, 0f, 0f, 0.62f, drawPart)
        drawLocal(suppressor, 0f, 0.008f, -0.60f, 90f, 0f, MaterialTexture.GUNMETAL, 0.34f, 0.37f, 0.40f, 0.86f, 0.28f, 0f, 0f, 0f, 0.52f, drawPart)
        drawLocal(magStraight, 0f, -0.11f - magDrop, -0.06f, 12f, 0f, MaterialTexture.SOLDIER_ARMOR, 0.40f, 0.42f, 0.46f, 0.55f, 0.45f, 0f, 0f, 0f, 0.24f, drawPart)

        // EOTech Holographic Sight
        drawLocal(opticBase, 0f, 0.064f, -0.04f, 0f, 0f, MaterialTexture.GUNMETAL, 0.38f, 0.40f, 0.44f, 0.80f, 0.32f, 0f, 0f, 0f, 0.46f, drawPart)
        drawLocal(opticHoodSide, -0.024f, 0.098f, -0.04f, 0f, 0f, MaterialTexture.GUNMETAL, 0.40f, 0.42f, 0.46f, 0.80f, 0.30f, 0f, 0f, 0f, 0.46f, drawPart)
        drawLocal(opticHoodSide, 0.024f, 0.098f, -0.04f, 0f, 0f, MaterialTexture.GUNMETAL, 0.40f, 0.42f, 0.46f, 0.80f, 0.30f, 0f, 0f, 0f, 0.46f, drawPart)
        drawLocal(opticHoodTop, 0f, 0.126f, -0.04f, 0f, 0f, MaterialTexture.GUNMETAL, 0.40f, 0.42f, 0.46f, 0.80f, 0.30f, 0f, 0f, 0f, 0.46f, drawPart)
        drawLocal(reticleDot, 0f, 0.098f, -0.045f, 0f, 0f, MaterialTexture.VISOR_GLASS, 0f, 1f, 0.72f, 0f, 1f, 0.0f, 2.2f, 1.45f, 0f, drawPart)
        drawLocal(laserBox, 0.040f, 0.014f, -0.35f, 0f, 0f, MaterialTexture.SOLDIER_ARMOR, 0.38f, 0.36f, 0.32f, 0.55f, 0.48f, 0f, 0f, 0f, 0.24f, drawPart)
    }

    private fun drawAk47(
        magDrop: Float,
        drawPart: (GlMesh, FloatArray, MaterialTexture, Float, Float, Float, Float, Float, Float, Float, Float, Float) -> Unit
    ) {
        // Stamped blued steel AK receiver & ribbed dust cover
        drawLocal(akReceiver, 0f, -0.004f, 0.01f, 0f, 0f, MaterialTexture.GUNMETAL, 0.42f, 0.44f, 0.47f, 0.86f, 0.30f, 0f, 0f, 0f, 0.48f, drawPart)
        drawLocal(akDustCover, 0f, 0.036f, 0.02f, 90f, 0f, MaterialTexture.GUNMETAL, 0.36f, 0.38f, 0.42f, 0.84f, 0.32f, 0f, 0f, 0f, 0.46f, drawPart)

        // Iconic lacquered Russian birch wood buttstock & lower/upper handguard!
        drawLocal(akWoodStock, 0f, -0.028f, 0.28f, -8f, 0f, MaterialTexture.WOOD_AK, 1.0f, 0.96f, 0.92f, 0.08f, 0.46f, 0f, 0f, 0f, 0.18f, drawPart)
        drawLocal(akWoodHandguard, 0f, -0.006f, -0.29f, 0f, 0f, MaterialTexture.WOOD_AK, 1.0f, 0.96f, 0.92f, 0.08f, 0.48f, 0f, 0f, 0f, 0.18f, drawPart)
        drawLocal(akWoodUpper, 0f, 0.028f, -0.29f, 90f, 0f, MaterialTexture.WOOD_AK, 0.96f, 0.92f, 0.88f, 0.08f, 0.48f, 0f, 0f, 0f, 0.18f, drawPart)

        // Exposed AK gas tube + long steel barrel + classic hooded front sight post
        drawLocal(akGasTube, 0f, 0.028f, -0.45f, 90f, 0f, MaterialTexture.GUNMETAL, 0.38f, 0.40f, 0.43f, 0.88f, 0.28f, 0f, 0f, 0f, 0.50f, drawPart)
        drawLocal(barrelCyl, 0f, 0.004f, -0.58f, 90f, 0f, MaterialTexture.GUNMETAL, 0.34f, 0.36f, 0.39f, 0.90f, 0.25f, 0f, 0f, 0f, 0.56f, drawPart)
        drawLocal(akFrontSightPost, 0f, 0.044f, -0.68f, 0f, 0f, MaterialTexture.GUNMETAL, 0.36f, 0.38f, 0.40f, 0.85f, 0.32f, 0f, 0f, 0f, 0.40f, drawPart)

        // Unmistakable curved AK-47 7.62mm banana magazine (two angled segments)
        drawLocal(akMagUpper, 0f, -0.095f - magDrop, -0.09f, 16f, 0f, MaterialTexture.GUNMETAL, 0.38f, 0.34f, 0.30f, 0.72f, 0.38f, 0f, 0f, 0f, 0.32f, drawPart)
        drawLocal(akMagLower, 0f, -0.195f - magDrop, -0.135f, 34f, 0f, MaterialTexture.GUNMETAL, 0.38f, 0.34f, 0.30f, 0.72f, 0.38f, 0f, 0f, 0f, 0.32f, drawPart)
    }

    private fun drawSniper(
        magDrop: Float,
        boltTilt: Float,
        drawPart: (GlMesh, FloatArray, MaterialTexture, Float, Float, Float, Float, Float, Float, Float, Float, Float) -> Unit
    ) {
        // Olive/tactical polymer AWM chassis stock
        drawLocal(sniperChassis, 0f, -0.008f, -0.06f, 0f, 0f, MaterialTexture.SOLDIER_ARMOR, 0.52f, 0.58f, 0.46f, 0.18f, 0.62f, 0f, 0f, 0f, 0.14f, drawPart)
        // Compact 5-round .338 mag
        drawLocal(magStraight, 0f, -0.075f - magDrop, -0.06f, 4f, 0f, MaterialTexture.GUNMETAL, 0.36f, 0.38f, 0.42f, 0.75f, 0.36f, 0f, 0f, 0f, 0.32f, drawPart)

        // Long free-float fluted stainless-gunmetal sniper barrel + heavy dual-chamber muzzle brake
        drawLocal(sniperBarrel, 0f, 0.022f, -0.58f, 90f, 0f, MaterialTexture.GUNMETAL, 0.60f, 0.63f, 0.68f, 0.92f, 0.20f, 0f, 0f, 0f, 0.68f, drawPart)
        drawLocal(sniperBrake, 0f, 0.022f, -0.90f, 0f, 0f, MaterialTexture.GUNMETAL, 0.36f, 0.38f, 0.42f, 0.88f, 0.28f, 0f, 0f, 0f, 0.54f, drawPart)

        // Large High-Magnification Telescopic Optical Scope + Dual Mounting Rings
        drawLocal(scopeRing, 0f, 0.062f, -0.02f, 0f, 0f, MaterialTexture.GUNMETAL, 0.36f, 0.38f, 0.42f, 0.86f, 0.28f, 0f, 0f, 0f, 0.52f, drawPart)
        drawLocal(scopeRing, 0f, 0.062f, -0.18f, 0f, 0f, MaterialTexture.GUNMETAL, 0.36f, 0.38f, 0.42f, 0.86f, 0.28f, 0f, 0f, 0f, 0.52f, drawPart)
        drawLocal(scopeTube, 0f, 0.095f, -0.10f, 90f, 0f, MaterialTexture.GUNMETAL, 0.34f, 0.36f, 0.40f, 0.88f, 0.24f, 0f, 0f, 0f, 0.58f, drawPart)
        drawLocal(scopeBellFront, 0f, 0.095f, -0.30f, 90f, 0f, MaterialTexture.GUNMETAL, 0.34f, 0.36f, 0.40f, 0.88f, 0.24f, 0f, 0f, 0f, 0.58f, drawPart)
        drawLocal(scopeBellRear, 0f, 0.095f, 0.09f, 90f, 0f, MaterialTexture.GUNMETAL, 0.34f, 0.36f, 0.40f, 0.88f, 0.24f, 0f, 0f, 0f, 0.58f, drawPart)

        // Animated Bolt Handle (lifts and pulls back during bolt cycle!)
        val boltPullZ = 0.07f + boltTilt * 0.075f
        val boltLiftY = 0.015f + boltTilt * 0.032f
        drawLocal(boltStem, 0.048f, boltLiftY, boltPullZ, 0f, 0f, MaterialTexture.GUNMETAL, 0.72f, 0.75f, 0.80f, 0.94f, 0.18f, 0f, 0f, 0f, 0.70f, drawPart)
        drawLocal(boltKnob, 0.075f, boltLiftY, boltPullZ, 0f, 0f, MaterialTexture.GUNMETAL, 0.44f, 0.46f, 0.50f, 0.88f, 0.26f, 0f, 0f, 0f, 0.56f, drawPart)

        // Folded front tactical bipod legs
        drawLocal(bipodLeg, -0.038f, -0.042f, -0.42f, 90f, 0f, MaterialTexture.GUNMETAL, 0.32f, 0.34f, 0.36f, 0.84f, 0.32f, 0f, 0f, 0f, 0.44f, drawPart)
        drawLocal(bipodLeg, 0.038f, -0.042f, -0.42f, 90f, 0f, MaterialTexture.GUNMETAL, 0.32f, 0.34f, 0.36f, 0.84f, 0.32f, 0f, 0f, 0f, 0.44f, drawPart)
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
        m4Receiver.release(); m4Handguard.release(); topRail.release()
        barrelCyl.release(); suppressor.release(); magStraight.release()
        opticBase.release(); opticHoodSide.release(); opticHoodTop.release()
        reticleDot.release(); laserBox.release()
        akReceiver.release(); akDustCover.release(); akWoodHandguard.release()
        akWoodUpper.release(); akWoodStock.release(); akMagUpper.release()
        akMagLower.release(); akFrontSightPost.release(); akGasTube.release()
        sniperChassis.release(); sniperBarrel.release(); sniperBrake.release()
        scopeTube.release(); scopeBellFront.release(); scopeBellRear.release()
        scopeRing.release(); boltKnob.release(); boltStem.release(); bipodLeg.release()
        muzzleStarA.release(); muzzleStarB.release(); glovedHand.release(); forearmSleeve.release()
    }
}
