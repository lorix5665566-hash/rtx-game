package com.example.engine

import android.opengl.Matrix
import com.example.model.TeamSide
import com.example.model.WeaponType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * CS2-grade first-person 3D viewmodels for the complete tactical arsenal:
 * - Rifles: M4A1-S, AK-47, AWP .338
 * - SMGs: MP9, P90
 * - Pistols: USP-S, Glock-18, Desert Eagle
 * - Melee & Utility: Tactical Combat Knife, C4 Explosive Pack
 *
 * Uses beveled forged steel, matte stippled polymer, brushed chrome, dark oiled walnut (AK-47 only),
 * faction-matched sleeves/forearms/gloves (CT vs T), and additive-blended split-second fire flames.
 */
class WeaponViewModel {
    // M4A1-S beveled machined meshes
    private val m4Upper = MeshBuilder.createBeveledBox(0.056f, 0.068f, 0.38f, 0.24f)
    private val m4Lower = MeshBuilder.createBeveledBox(0.050f, 0.044f, 0.24f, 0.24f)
    private val m4Handguard = MeshBuilder.createBeveledBox(0.048f, 0.056f, 0.30f, 0.28f)
    private val topRail = MeshBuilder.createBeveledBox(0.026f, 0.014f, 0.52f, 0.20f)
    private val suppressor = MeshBuilder.createTaperedLimb(0.020f, 0.020f, 0.020f, 0.020f, 0.26f, 1.0f, 18, 4)
    private val barrelThin = MeshBuilder.createTaperedLimb(0.013f, 0.013f, 0.015f, 0.015f, 0.32f, 1.0f, 16, 4)
    private val magStraight = MeshBuilder.createBeveledBox(0.036f, 0.18f, 0.068f, 0.22f)
    private val pistolGrip = MeshBuilder.createBeveledBox(0.036f, 0.11f, 0.052f, 0.28f)
    private val opticBase = MeshBuilder.createBeveledBox(0.044f, 0.020f, 0.105f, 0.22f)
    private val opticHoodSide = MeshBuilder.createBeveledBox(0.008f, 0.048f, 0.076f, 0.25f)
    private val opticHoodTop = MeshBuilder.createBeveledBox(0.046f, 0.008f, 0.076f, 0.25f)
    private val reticleDot = MeshBuilder.createEllipsoid(0.0030f, 0.0030f, 0.0030f, 6, 8)

    // AK-47 meshes (blued stamped steel + dark oiled walnut furniture)
    private val akReceiver = MeshBuilder.createBeveledBox(0.054f, 0.074f, 0.36f, 0.20f)
    private val akDustCover = MeshBuilder.createTaperedLimb(0.027f, 0.027f, 0.025f, 0.025f, 0.32f, 1.0f, 16, 4)
    private val akWoodHandguard = MeshBuilder.createBeveledBox(0.050f, 0.056f, 0.24f, 0.28f)
    private val akWoodUpper = MeshBuilder.createTaperedLimb(0.020f, 0.020f, 0.020f, 0.020f, 0.21f, 1.0f, 14, 3)
    private val akWoodStock = MeshBuilder.createBeveledBox(0.044f, 0.086f, 0.26f, 0.26f)
    private val akMagUpper = MeshBuilder.createBeveledBox(0.034f, 0.125f, 0.068f, 0.22f)
    private val akMagLower = MeshBuilder.createBeveledBox(0.032f, 0.125f, 0.066f, 0.22f)
    private val akGasTube = MeshBuilder.createTaperedLimb(0.011f, 0.011f, 0.011f, 0.011f, 0.26f, 1.0f, 12, 3)
    private val akFrontSight = MeshBuilder.createBeveledBox(0.012f, 0.048f, 0.022f, 0.20f)

    // AWP Sniper meshes
    private val awpChassis = MeshBuilder.createBeveledBox(0.058f, 0.084f, 0.52f, 0.24f)
    private val awpBarrel = MeshBuilder.createTaperedLimb(0.015f, 0.015f, 0.019f, 0.019f, 0.56f, 1.0f, 18, 4)
    private val awpBrake = MeshBuilder.createBeveledBox(0.040f, 0.034f, 0.082f, 0.22f)
    private val scopeTube = MeshBuilder.createTaperedLimb(0.020f, 0.020f, 0.020f, 0.020f, 0.34f, 1.0f, 18, 4)
    private val scopeBellFront = MeshBuilder.createTaperedLimb(0.022f, 0.022f, 0.034f, 0.034f, 0.11f, 1.0f, 18, 4)
    private val scopeBellRear = MeshBuilder.createTaperedLimb(0.027f, 0.027f, 0.022f, 0.022f, 0.08f, 1.0f, 18, 4)
    private val scopeRing = MeshBuilder.createBeveledBox(0.034f, 0.046f, 0.020f, 0.20f)
    private val boltKnob = MeshBuilder.createEllipsoid(0.013f, 0.013f, 0.013f, 10, 12)
    private val boltStem = MeshBuilder.createBeveledBox(0.048f, 0.011f, 0.011f, 0.25f)

    // SMGs: MP9 & P90
    private val mp9Body = MeshBuilder.createBeveledBox(0.050f, 0.082f, 0.28f, 0.26f)
    private val mp9Foregrip = MeshBuilder.createTaperedLimb(0.015f, 0.016f, 0.016f, 0.017f, 0.10f, 1.0f, 12, 3)
    private val mp9Mag = MeshBuilder.createBeveledBox(0.026f, 0.20f, 0.040f, 0.22f)
    private val p90Body = MeshBuilder.createBeveledBox(0.056f, 0.105f, 0.42f, 0.30f)
    private val p90TopMag = MeshBuilder.createBeveledBox(0.046f, 0.024f, 0.28f, 0.24f)

    // Pistols: USP-S, Glock-18, Desert Eagle
    private val pistolSlide = MeshBuilder.createBeveledBox(0.034f, 0.034f, 0.21f, 0.24f)
    private val pistolFrame = MeshBuilder.createBeveledBox(0.032f, 0.028f, 0.19f, 0.26f)
    private val deagleSlide = MeshBuilder.createBeveledBox(0.042f, 0.044f, 0.26f, 0.22f)
    private val deagleBarrel = MeshBuilder.createBeveledBox(0.036f, 0.042f, 0.14f, 0.28f)
    private val uspSuppressor = MeshBuilder.createTaperedLimb(0.016f, 0.016f, 0.016f, 0.016f, 0.20f, 1.0f, 16, 4)

    // Tactical Combat Knife
    private val knifeHandle = MeshBuilder.createBeveledBox(0.024f, 0.034f, 0.13f, 0.32f)
    private val knifeGuard = MeshBuilder.createBeveledBox(0.034f, 0.052f, 0.016f, 0.24f)
    private val knifeBladeMain = MeshBuilder.createBeveledBox(0.006f, 0.034f, 0.17f, 0.30f)
    private val knifeBladeTip = MeshBuilder.createTaperedLimb(0.002f, 0.004f, 0.004f, 0.017f, 0.075f, 1.0f, 10, 3)

    // C4 Explosive Pack
    private val c4Body = MeshBuilder.createBeveledBox(0.16f, 0.11f, 0.068f, 0.18f)
    private val c4Screen = MeshBuilder.createBeveledBox(0.072f, 0.030f, 0.010f, 0.16f)
    private val c4Led = MeshBuilder.createEllipsoid(0.007f, 0.007f, 0.007f, 8, 8)

    // First-person hands, forearms & volumetric muzzle flame cards
    private val glovedHand = MeshBuilder.createBeveledBox(0.068f, 0.062f, 0.095f, 0.34f)
    private val forearmMesh = MeshBuilder.createTaperedLimb(0.044f, 0.044f, 0.054f, 0.054f, 0.32f, 1.05f, 16, 6)
    private val sleeveCuff = MeshBuilder.createTaperedLimb(0.054f, 0.054f, 0.062f, 0.062f, 0.18f, 1.02f, 16, 4)
    private val flameCards = MeshBuilder.createVolumetricMuzzleFlame(length = 0.26f, radius = 0.105f)

    private val rootMat = FloatArray(16)
    private val partMat = FloatArray(16)
    private val worldMat = FloatArray(16)

    fun draw(
        weapon: WeaponType,
        playerSide: TeamSide,
        adsBlend: Float,
        walkBobPhase: Float,
        moveSpeedRatio: Float,
        recoilKick: Float,
        reloadProgress: Float,
        isReloading: Boolean,
        muzzleFlash: Float,
        muzzleFlashRoll: Float,
        boltProgress: Float,
        knifeSwingProgress: Float,
        bombPlantProgress: Float,
        timeSec: Float,
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
        ) -> Unit,
        drawFlame: (mesh: GlMesh, modelMatrix: FloatArray, intensity: Float) -> Unit
    ) {
        // Hide 3D sniper once 4x optical scope overlay takes over
        if (weapon.hasSniperScope && adsBlend > 0.78f) return

        val bobAmp = (1f - adsBlend * 0.80f) * moveSpeedRatio
        val bobX = cos(walkBobPhase) * 0.013f * bobAmp
        val bobY = sin(walkBobPhase * 2f) * 0.010f * bobAmp

        val isPistol = weapon.slot == com.example.model.WeaponSlot.PISTOL
        val isKnife = weapon == WeaponType.KNIFE
        val isBomb = weapon == WeaponType.C4_BOMB

        val baseX = when {
            isBomb -> 0.02f
            isKnife -> 0.19f
            isPistol -> 0.17f
            else -> 0.205f
        }
        val baseY = when {
            isBomb -> -0.175f
            isKnife -> -0.195f
            isPistol -> -0.175f
            else -> -0.195f
        }
        val baseZ = when {
            isBomb -> -0.36f
            isKnife -> -0.38f
            isPistol -> -0.40f
            else -> -0.45f
        }
        val adsY = when (weapon) {
            WeaponType.AK47 -> -0.115f
            WeaponType.USP_S, WeaponType.GLOCK_18, WeaponType.DESERT_EAGLE -> -0.110f
            else -> -0.136f
        }

        val posX = lerp(baseX, 0.0f, adsBlend) + bobX
        val posY = lerp(baseY, adsY, adsBlend) + bobY - recoilKick * 0.012f
        val posZ = lerp(baseZ, baseZ + 0.10f, adsBlend) + recoilKick * 0.062f

        val reloadTilt = if (isReloading) sin(reloadProgress * PI).toFloat() else 0f
        val magDrop = if (isReloading) sin(reloadProgress * PI).toFloat() * 0.22f else 0f
        val boltTilt = if (weapon == WeaponType.AWP && boltProgress > 0f) sin(boltProgress * PI).toFloat() else 0f
        val slashArc = if (isKnife && knifeSwingProgress > 0f) sin(knifeSwingProgress * PI).toFloat() else 0f
        val slideKick = (recoilKick * 0.055f).coerceAtMost(0.055f)

        Matrix.setIdentityM(rootMat, 0)
        Matrix.translateM(
            rootMat, 0,
            posX - slashArc * 0.24f,
            posY - reloadTilt * 0.09f + slashArc * 0.04f,
            posZ - slashArc * 0.14f
        )
        Matrix.rotateM(rootMat, 0, recoilKick * 7.8f - reloadTilt * 16f + boltTilt * 6f - slashArc * 22f, 1f, 0f, 0f)
        Matrix.rotateM(rootMat, 0, reloadTilt * 28f - boltTilt * 14f + slashArc * 48f, 0f, 0f, 1f)
        Matrix.rotateM(rootMat, 0, lerp(3.2f, 0f, adsBlend) + slashArc * 38f, 0f, 1f, 0f)

        var flashZ = -0.74f
        var flashScale = 1.0f
        var hasMuzzleFlash = true

        when (weapon) {
            WeaponType.M4A1_S -> {
                drawM4A1S(magDrop, drawPart)
                flashZ = -0.76f; flashScale = 0.45f // Suppressed subtle flash!
            }
            WeaponType.AK47 -> {
                drawAk47(magDrop, drawPart)
                flashZ = -0.77f; flashScale = 1.15f
            }
            WeaponType.AWP -> {
                drawAwp(magDrop, boltTilt, drawPart)
                flashZ = -0.98f; flashScale = 1.45f
            }
            WeaponType.MP9 -> {
                drawMp9(magDrop, drawPart)
                flashZ = -0.48f; flashScale = 0.72f
            }
            WeaponType.P90 -> {
                drawP90(magDrop, drawPart)
                flashZ = -0.44f; flashScale = 0.75f
            }
            WeaponType.USP_S -> {
                drawPistol(isDeagle = false, isSuppressed = true, slideKick = slideKick, magDrop = magDrop, drawPart = drawPart)
                flashZ = -0.46f; flashScale = 0.36f
            }
            WeaponType.GLOCK_18 -> {
                drawPistol(isDeagle = false, isSuppressed = false, slideKick = slideKick, magDrop = magDrop, drawPart = drawPart)
                flashZ = -0.26f; flashScale = 0.65f
            }
            WeaponType.DESERT_EAGLE -> {
                drawPistol(isDeagle = true, isSuppressed = false, slideKick = slideKick, magDrop = magDrop, drawPart = drawPart)
                flashZ = -0.34f; flashScale = 1.18f
            }
            WeaponType.KNIFE -> {
                drawKnife(drawPart)
                hasMuzzleFlash = false
            }
            WeaponType.C4_BOMB -> {
                drawC4Bomb(bombPlantProgress, timeSec, drawPart)
                hasMuzzleFlash = false
            }
        }

        // Faction-authentic first-person forearms & gloves (CT navy BDU vs T rolled plaid sleeves + bare forearms)
        drawPlayerHands(playerSide, isPistol || isKnife, isBomb, drawPart)

        // Realistic split-second additive-blended volumetric fire tongue!
        if (hasMuzzleFlash && muzzleFlash > 0.02f) {
            val s = (0.72f + muzzleFlash * 0.55f) * flashScale
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, 0f, if (isPistol) 0.026f else 0.014f, flashZ)
            Matrix.rotateM(partMat, 0, muzzleFlashRoll, 0f, 0f, 1f)
            Matrix.scaleM(partMat, 0, s, s, s * (0.95f + muzzleFlash * 0.35f))
            Matrix.multiplyMM(worldMat, 0, rootMat, 0, partMat, 0)
            drawFlame(flameCards, worldMat, muzzleFlash)
        }
    }

    private fun drawPlayerHands(
        side: TeamSide,
        isCompact: Boolean,
        isBomb: Boolean,
        drawPart: (GlMesh, FloatArray, MaterialTexture, Float, Float, Float, Float, Float, Float, Float, Float, Float) -> Unit
    ) {
        val isCT = side == TeamSide.CT
        val armTex = if (isCT) MaterialTexture.CT_UNIFORM else MaterialTexture.SOLDIER_SKIN
        val sleeveTex = if (isCT) MaterialTexture.CT_UNIFORM else MaterialTexture.T_SHIRT_PLAID
        val armRough = if (isCT) 0.85f else 0.62f

        if (isBomb) {
            // Two hands holding C4 pack
            drawLocal(glovedHand, -0.10f, -0.02f, -0.02f, 0f, 0f, MaterialTexture.TACTICAL_POLYMER, 0.38f, 0.36f, 0.34f, 0.12f, 0.72f, 0f, 0f, 0f, 0.05f, drawPart)
            drawLocal(glovedHand,  0.10f, -0.02f, -0.02f, 0f, 0f, MaterialTexture.TACTICAL_POLYMER, 0.38f, 0.36f, 0.34f, 0.12f, 0.72f, 0f, 0f, 0f, 0.05f, drawPart)
            return
        }

        // Right firing hand + forearm
        val rx = if (isCompact) 0.01f else 0.02f
        val rz = if (isCompact) 0.08f else 0.14f
        drawLocal(glovedHand, rx, -0.068f, rz, 12f, 0f, MaterialTexture.TACTICAL_POLYMER, 0.36f, 0.35f, 0.34f, 0.12f, 0.72f, 0f, 0f, 0f, 0.05f, drawPart)
        drawLocal(forearmMesh, rx + 0.08f, -0.16f, rz + 0.14f, 60f, -24f, armTex, 1f, 0.96f, 0.92f, 0.04f, armRough, 0f, 0f, 0f, 0.02f, drawPart)

        // Left support hand & forearm (on rifles/SMGs/pistols)
        if (!isCompact) {
            drawLocal(glovedHand, -0.022f, -0.046f, -0.28f, 0f, 0f, MaterialTexture.TACTICAL_POLYMER, 0.36f, 0.35f, 0.34f, 0.12f, 0.72f, 0f, 0f, 0f, 0.05f, drawPart)
            drawLocal(forearmMesh, -0.14f, -0.15f, -0.16f, 56f, 36f, armTex, 1f, 0.96f, 0.92f, 0.04f, armRough, 0f, 0f, 0f, 0.02f, drawPart)
            drawLocal(sleeveCuff, -0.22f, -0.22f, -0.06f, 56f, 36f, sleeveTex, 1f, 1f, 1f, 0.04f, 0.86f, 0f, 0f, 0f, 0.02f, drawPart)
        }
    }

    private fun drawM4A1S(
        magDrop: Float,
        drawPart: (GlMesh, FloatArray, MaterialTexture, Float, Float, Float, Float, Float, Float, Float, Float, Float) -> Unit
    ) {
        // Forged cold-blued gunmetal upper/lower receiver + matte black stippled polymer handguard & suppressor
        drawLocal(m4Upper, 0f, 0.010f, 0f, 0f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.76f, 0.78f, 0.82f, 0.88f, 0.26f, 0f, 0f, 0f, 0.62f, drawPart)
        drawLocal(m4Lower, 0f, -0.032f, 0.02f, 0f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.68f, 0.70f, 0.74f, 0.84f, 0.30f, 0f, 0f, 0f, 0.55f, drawPart)
        drawLocal(m4Handguard, 0f, 0.008f, -0.32f, 0f, 0f, MaterialTexture.TACTICAL_POLYMER, 0.55f, 0.57f, 0.60f, 0.25f, 0.58f, 0f, 0f, 0f, 0.18f, drawPart)
        drawLocal(topRail, 0f, 0.048f, -0.15f, 0f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.82f, 0.85f, 0.90f, 0.92f, 0.22f, 0f, 0f, 0f, 0.68f, drawPart)
        drawLocal(suppressor, 0f, 0.010f, -0.60f, 90f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.44f, 0.47f, 0.52f, 0.86f, 0.32f, 0f, 0f, 0f, 0.52f, drawPart)
        drawLocal(magStraight, 0f, -0.115f - magDrop, -0.06f, 10f, 0f, MaterialTexture.TACTICAL_POLYMER, 0.52f, 0.54f, 0.58f, 0.38f, 0.46f, 0f, 0f, 0f, 0.22f, drawPart)
        drawLocal(pistolGrip, 0f, -0.088f, 0.12f, -18f, 0f, MaterialTexture.TACTICAL_POLYMER, 0.48f, 0.50f, 0.54f, 0.16f, 0.68f, 0f, 0f, 0f, 0.10f, drawPart)

        // Holographic optic
        drawLocal(opticBase, 0f, 0.064f, -0.04f, 0f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.56f, 0.58f, 0.62f, 0.84f, 0.30f, 0f, 0f, 0f, 0.50f, drawPart)
        drawLocal(opticHoodSide, -0.022f, 0.095f, -0.04f, 0f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.58f, 0.60f, 0.64f, 0.84f, 0.28f, 0f, 0f, 0f, 0.50f, drawPart)
        drawLocal(opticHoodSide,  0.022f, 0.095f, -0.04f, 0f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.58f, 0.60f, 0.64f, 0.84f, 0.28f, 0f, 0f, 0f, 0.50f, drawPart)
        drawLocal(opticHoodTop, 0f, 0.120f, -0.04f, 0f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.58f, 0.60f, 0.64f, 0.84f, 0.28f, 0f, 0f, 0f, 0.50f, drawPart)
        drawLocal(reticleDot, 0f, 0.095f, -0.045f, 0f, 0f, MaterialTexture.VISOR_GLASS, 0f, 1f, 0.72f, 0f, 1f, 0.0f, 2.2f, 1.4f, 0f, drawPart)
    }

    private fun drawAk47(
        magDrop: Float,
        drawPart: (GlMesh, FloatArray, MaterialTexture, Float, Float, Float, Float, Float, Float, Float, Float, Float) -> Unit
    ) {
        // Stamped blued steel receiver + ribbed dust cover + dark oiled Russian walnut furniture
        drawLocal(akReceiver, 0f, -0.004f, 0.01f, 0f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.62f, 0.65f, 0.70f, 0.88f, 0.28f, 0f, 0f, 0f, 0.56f, drawPart)
        drawLocal(akDustCover, 0f, 0.036f, 0.02f, 90f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.54f, 0.57f, 0.62f, 0.88f, 0.26f, 0f, 0f, 0f, 0.58f, drawPart)
        drawLocal(akWoodStock, 0f, -0.028f, 0.28f, -8f, 0f, MaterialTexture.WALNUT_STOCK, 0.96f, 0.92f, 0.88f, 0.08f, 0.42f, 0f, 0f, 0f, 0.22f, drawPart)
        drawLocal(akWoodHandguard, 0f, -0.004f, -0.28f, 0f, 0f, MaterialTexture.WALNUT_STOCK, 0.96f, 0.92f, 0.88f, 0.08f, 0.42f, 0f, 0f, 0f, 0.22f, drawPart)
        drawLocal(akWoodUpper, 0f, 0.028f, -0.28f, 90f, 0f, MaterialTexture.WALNUT_STOCK, 0.94f, 0.90f, 0.86f, 0.08f, 0.44f, 0f, 0f, 0f, 0.20f, drawPart)
        drawLocal(akGasTube, 0f, 0.028f, -0.44f, 90f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.58f, 0.61f, 0.66f, 0.90f, 0.24f, 0f, 0f, 0f, 0.60f, drawPart)
        drawLocal(barrelThin, 0f, 0.006f, -0.56f, 90f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.54f, 0.57f, 0.62f, 0.92f, 0.22f, 0f, 0f, 0f, 0.64f, drawPart)
        drawLocal(akFrontSight, 0f, 0.042f, -0.66f, 0f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.56f, 0.58f, 0.62f, 0.88f, 0.28f, 0f, 0f, 0f, 0.48f, drawPart)
        drawLocal(akMagUpper, 0f, -0.095f - magDrop, -0.09f, 16f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.50f, 0.48f, 0.46f, 0.78f, 0.34f, 0f, 0f, 0f, 0.36f, drawPart)
        drawLocal(akMagLower, 0f, -0.195f - magDrop, -0.135f, 34f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.50f, 0.48f, 0.46f, 0.78f, 0.34f, 0f, 0f, 0f, 0.36f, drawPart)
        drawLocal(pistolGrip, 0f, -0.088f, 0.12f, -18f, 0f, MaterialTexture.WALNUT_STOCK, 0.88f, 0.84f, 0.80f, 0.08f, 0.48f, 0f, 0f, 0f, 0.16f, drawPart)
    }

    private fun drawAwp(
        magDrop: Float,
        boltTilt: Float,
        drawPart: (GlMesh, FloatArray, MaterialTexture, Float, Float, Float, Float, Float, Float, Float, Float, Float) -> Unit
    ) {
        // Olive-drab AWP polymer chassis + long forged steel fluted barrel + telescopic scope + chrome bolt
        drawLocal(awpChassis, 0f, -0.008f, -0.06f, 0f, 0f, MaterialTexture.TACTICAL_POLYMER, 0.44f, 0.56f, 0.38f, 0.18f, 0.54f, 0f, 0f, 0f, 0.16f, drawPart)
        drawLocal(magStraight, 0f, -0.075f - magDrop, -0.06f, 4f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.50f, 0.53f, 0.58f, 0.82f, 0.30f, 0f, 0f, 0f, 0.45f, drawPart)
        drawLocal(awpBarrel, 0f, 0.022f, -0.58f, 90f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.70f, 0.74f, 0.80f, 0.92f, 0.20f, 0f, 0f, 0f, 0.70f, drawPart)
        drawLocal(awpBrake, 0f, 0.022f, -0.89f, 0f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.48f, 0.50f, 0.54f, 0.88f, 0.28f, 0f, 0f, 0f, 0.54f, drawPart)

        drawLocal(scopeRing, 0f, 0.060f, -0.02f, 0f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.50f, 0.52f, 0.56f, 0.86f, 0.26f, 0f, 0f, 0f, 0.52f, drawPart)
        drawLocal(scopeRing, 0f, 0.060f, -0.18f, 0f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.50f, 0.52f, 0.56f, 0.86f, 0.26f, 0f, 0f, 0f, 0.52f, drawPart)
        drawLocal(scopeTube, 0f, 0.092f, -0.10f, 90f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.44f, 0.46f, 0.50f, 0.88f, 0.22f, 0f, 0f, 0f, 0.62f, drawPart)
        drawLocal(scopeBellFront, 0f, 0.092f, -0.30f, 90f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.44f, 0.46f, 0.50f, 0.88f, 0.22f, 0f, 0f, 0f, 0.62f, drawPart)
        drawLocal(scopeBellRear, 0f, 0.092f, 0.09f, 90f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.44f, 0.46f, 0.50f, 0.88f, 0.22f, 0f, 0f, 0f, 0.62f, drawPart)

        val boltPullZ = 0.07f + boltTilt * 0.075f
        val boltLiftY = 0.015f + boltTilt * 0.032f
        drawLocal(boltStem, 0.048f, boltLiftY, boltPullZ, 0f, 0f, MaterialTexture.CHROME_STEEL, 0.92f, 0.94f, 0.98f, 0.95f, 0.16f, 0f, 0f, 0f, 0.82f, drawPart)
        drawLocal(boltKnob, 0.075f, boltLiftY, boltPullZ, 0f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.50f, 0.52f, 0.56f, 0.86f, 0.26f, 0f, 0f, 0f, 0.58f, drawPart)
    }

    private fun drawMp9(
        magDrop: Float,
        drawPart: (GlMesh, FloatArray, MaterialTexture, Float, Float, Float, Float, Float, Float, Float, Float, Float) -> Unit
    ) {
        drawLocal(mp9Body, 0f, 0.005f, -0.05f, 0f, 0f, MaterialTexture.TACTICAL_POLYMER, 0.52f, 0.56f, 0.60f, 0.28f, 0.54f, 0f, 0f, 0f, 0.20f, drawPart)
        drawLocal(topRail, 0f, 0.048f, -0.06f, 0f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.72f, 0.75f, 0.80f, 0.88f, 0.26f, 0f, 0f, 0f, 0.56f, drawPart)
        drawLocal(barrelThin, 0f, 0.016f, -0.28f, 90f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.58f, 0.60f, 0.65f, 0.90f, 0.24f, 0f, 0f, 0f, 0.60f, drawPart)
        drawLocal(mp9Foregrip, 0f, -0.075f, -0.15f, 0f, 0f, MaterialTexture.TACTICAL_POLYMER, 0.44f, 0.46f, 0.50f, 0.18f, 0.64f, 0f, 0f, 0f, 0.12f, drawPart)
        drawLocal(mp9Mag, 0f, -0.125f - magDrop, 0.01f, -6f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.48f, 0.50f, 0.55f, 0.72f, 0.36f, 0f, 0f, 0f, 0.36f, drawPart)
    }

    private fun drawP90(
        magDrop: Float,
        drawPart: (GlMesh, FloatArray, MaterialTexture, Float, Float, Float, Float, Float, Float, Float, Float, Float) -> Unit
    ) {
        drawLocal(p90Body, 0f, -0.004f, -0.04f, 0f, 0f, MaterialTexture.TACTICAL_POLYMER, 0.48f, 0.50f, 0.54f, 0.24f, 0.56f, 0f, 0f, 0f, 0.18f, drawPart)
        drawLocal(p90TopMag, 0f, 0.056f + magDrop * 0.4f, -0.06f, 0f, 0f, MaterialTexture.VISOR_GLASS, 0.42f, 0.36f, 0.24f, 0.35f, 0.28f, 0f, 0f, 0f, 0.40f, drawPart)
        drawLocal(opticBase, 0f, 0.068f, -0.18f, 0f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.56f, 0.58f, 0.62f, 0.86f, 0.28f, 0f, 0f, 0f, 0.54f, drawPart)
        drawLocal(barrelThin, 0f, 0.012f, -0.32f, 90f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.60f, 0.62f, 0.68f, 0.90f, 0.24f, 0f, 0f, 0f, 0.62f, drawPart)
    }

    private fun drawPistol(
        isDeagle: Boolean,
        isSuppressed: Boolean,
        slideKick: Float,
        magDrop: Float,
        drawPart: (GlMesh, FloatArray, MaterialTexture, Float, Float, Float, Float, Float, Float, Float, Float, Float) -> Unit
    ) {
        if (isDeagle) {
            // Brushed satin stainless steel Desert Eagle .50 AE
            drawLocal(deagleSlide, 0f, 0.024f, -0.04f + slideKick, 0f, 0f, MaterialTexture.CHROME_STEEL, 0.92f, 0.95f, 0.98f, 0.94f, 0.18f, 0f, 0f, 0f, 0.84f, drawPart)
            drawLocal(deagleBarrel, 0f, 0.024f, -0.20f, 0f, 0f, MaterialTexture.CHROME_STEEL, 0.88f, 0.91f, 0.95f, 0.94f, 0.20f, 0f, 0f, 0f, 0.80f, drawPart)
            drawLocal(pistolGrip, 0f, -0.056f, 0.06f, -18f, 0f, MaterialTexture.TACTICAL_POLYMER, 0.34f, 0.35f, 0.38f, 0.14f, 0.72f, 0f, 0f, 0f, 0.08f, drawPart)
        } else {
            // USP-S or Glock-18: blued steel slide + matte polymer frame
            drawLocal(pistolSlide, 0f, 0.022f, -0.06f + slideKick, 0f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.64f, 0.68f, 0.74f, 0.88f, 0.26f, 0f, 0f, 0f, 0.60f, drawPart)
            drawLocal(pistolFrame, 0f, -0.004f, -0.05f, 0f, 0f, MaterialTexture.TACTICAL_POLYMER, 0.44f, 0.46f, 0.50f, 0.20f, 0.62f, 0f, 0f, 0f, 0.14f, drawPart)
            drawLocal(pistolGrip, 0f, -0.060f - magDrop * 0.5f, 0.04f, -18f, 0f, MaterialTexture.TACTICAL_POLYMER, 0.42f, 0.44f, 0.48f, 0.18f, 0.66f, 0f, 0f, 0f, 0.10f, drawPart)
            if (isSuppressed) {
                drawLocal(uspSuppressor, 0f, 0.024f, -0.26f, 90f, 0f, MaterialTexture.GUNMETAL_STEEL, 0.44f, 0.46f, 0.50f, 0.86f, 0.30f, 0f, 0f, 0f, 0.52f, drawPart)
            }
        }
    }

    private fun drawKnife(
        drawPart: (GlMesh, FloatArray, MaterialTexture, Float, Float, Float, Float, Float, Float, Float, Float, Float) -> Unit
    ) {
        // CS2 Tactical Combat Knife held at ergonomic ready angle
        drawLocal(knifeHandle, 0f, -0.03f, 0.02f, -18f, -16f, MaterialTexture.TACTICAL_POLYMER, 0.40f, 0.42f, 0.46f, 0.20f, 0.66f, 0f, 0f, 0f, 0.12f, drawPart)
        drawLocal(knifeGuard, 0f, -0.008f, -0.05f, -18f, -16f, MaterialTexture.GUNMETAL_STEEL, 0.52f, 0.55f, 0.60f, 0.86f, 0.28f, 0f, 0f, 0f, 0.52f, drawPart)
        drawLocal(knifeBladeMain, -0.022f, 0.022f, -0.14f, -18f, -16f, MaterialTexture.CHROME_STEEL, 0.92f, 0.95f, 1.0f, 0.95f, 0.14f, 0f, 0f, 0f, 0.88f, drawPart)
        drawLocal(knifeBladeTip, -0.046f, 0.048f, -0.24f, -108f, -16f, MaterialTexture.CHROME_STEEL, 0.92f, 0.95f, 1.0f, 0.95f, 0.14f, 0f, 0f, 0f, 0.88f, drawPart)
    }

    private fun drawC4Bomb(
        plantProgress: Float,
        timeSec: Float,
        drawPart: (GlMesh, FloatArray, MaterialTexture, Float, Float, Float, Float, Float, Float, Float, Float, Float) -> Unit
    ) {
        val tilt = -36f - plantProgress * 22f
        drawLocal(c4Body, 0f, -0.01f, 0f, tilt, 0f, MaterialTexture.C4_PACK, 1f, 1f, 1f, 0.14f, 0.68f, 0f, 0f, 0f, 0.08f, drawPart)
        drawLocal(c4Screen, 0f, 0.032f, -0.034f, tilt, 0f, MaterialTexture.VISOR_GLASS, 0.15f, 0.85f, 0.35f, 0.10f, 0.30f, 0.15f, 0.95f, 0.30f, 0.25f, drawPart)
        val blink = if (plantProgress > 0f || ((timeSec * 2.5f).toInt() % 2 == 0)) 2.6f else 0.2f
        drawLocal(c4Led, 0.048f, 0.034f, -0.034f, tilt, 0f, MaterialTexture.VISOR_GLASS, 1f, 0.15f, 0.10f, 0f, 1f, blink, 0.12f, 0.06f, 0f, drawPart)
    }

    private fun drawLocal(
        mesh: GlMesh,
        x: Float, y: Float, z: Float,
        rotX: Float, rotY: Float,
        tex: MaterialTexture,
        r: Float, g: Float, b: Float,
        metallic: Float, roughness: Float,
        er: Float, eg: Float, eb: Float,
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
        m4Upper.release(); m4Lower.release(); m4Handguard.release(); topRail.release()
        suppressor.release(); barrelThin.release(); magStraight.release(); pistolGrip.release()
        opticBase.release(); opticHoodSide.release(); opticHoodTop.release(); reticleDot.release()
        akReceiver.release(); akDustCover.release(); akWoodHandguard.release(); akWoodUpper.release()
        akWoodStock.release(); akMagUpper.release(); akMagLower.release(); akGasTube.release(); akFrontSight.release()
        awpChassis.release(); awpBarrel.release(); awpBrake.release(); scopeTube.release()
        scopeBellFront.release(); scopeBellRear.release(); scopeRing.release(); boltKnob.release(); boltStem.release()
        mp9Body.release(); mp9Foregrip.release(); mp9Mag.release(); p90Body.release(); p90TopMag.release()
        pistolSlide.release(); pistolFrame.release(); deagleSlide.release(); deagleBarrel.release(); uspSuppressor.release()
        knifeHandle.release(); knifeGuard.release(); knifeBladeMain.release(); knifeBladeTip.release()
        c4Body.release(); c4Screen.release(); c4Led.release()
        glovedHand.release(); forearmMesh.release(); sleeveCuff.release(); flameCards.release()
    }
}
