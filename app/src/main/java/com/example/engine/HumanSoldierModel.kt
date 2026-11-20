package com.example.engine

import android.opengl.Matrix
import kotlin.math.cos
import kotlin.math.sin

enum class SoldierClass {
    ASSAULT,
    RECON,
    HEAVY
}

data class EnemyOperative(
    val id: Int,
    val soldierClass: SoldierClass,
    var x: Float,
    var z: Float,
    var yawDeg: Float,
    var hp: Float,
    val maxHp: Float,
    var walkPhase: Float = 0f,
    var isMoving: Boolean = false,
    var hitFlinch: Float = 0f,
    var muzzleFlashTimer: Float = 0f,
    var fireCooldown: Float = 1.4f,
    var strafeDir: Float = 1f,
    var strafeTimer: Float = 2.0f,
    var isDead: Boolean = false,
    var deathProgress: Float = 0f,
    var deathFallDir: Float = 1f,
    var isRangeTarget: Boolean = false
)

/**
 * True 1:1 anatomically proportioned (~1.80 m tall, 8-head canonical human ratios)
 * multi-part articulated 3D special-forces operative with sculpted limbs, torso,
 * Kevlar ballistic armor, Ops-Core helmet, reflective visor, and 3D tactical rifle.
 */
class HumanSoldierModel {
    // Sculpted anatomical meshes (dimensions in real meters, 1:1 scale)
    private val headCranium = MeshBuilder.createEllipsoid(0.095f, 0.118f, 0.108f, 14, 18)
    private val jawMesh = MeshBuilder.createEllipsoid(0.072f, 0.060f, 0.080f, 10, 14)
    private val neckMesh = MeshBuilder.createTaperedLimb(0.058f, 0.058f, 0.052f, 0.052f, 0.11f, 1.0f, 12, 5)
    private val helmetDome = MeshBuilder.createEllipsoid(0.116f, 0.112f, 0.128f, 14, 18)
    private val visorMesh = MeshBuilder.createEllipsoid(0.098f, 0.048f, 0.095f, 10, 14)
    private val earProMesh = MeshBuilder.createEllipsoid(0.028f, 0.045f, 0.036f, 8, 10)

    private val upperTorso = MeshBuilder.createTaperedLimb(0.155f, 0.105f, 0.215f, 0.128f, 0.44f, 1.07f, 18, 8)
    private val plateCarrier = MeshBuilder.createBox(0.32f, 0.36f, 0.255f)
    private val magPouch = MeshBuilder.createBox(0.072f, 0.125f, 0.055f)
    private val pelvisMesh = MeshBuilder.createTaperedLimb(0.152f, 0.112f, 0.165f, 0.115f, 0.18f, 1.03f, 16, 6)
    private val beltMesh = MeshBuilder.createTaperedLimb(0.172f, 0.126f, 0.172f, 0.126f, 0.055f, 1.0f, 16, 3)

    private val shoulderJoint = MeshBuilder.createEllipsoid(0.068f, 0.064f, 0.066f, 10, 12)
    private val pauldronMesh = MeshBuilder.createEllipsoid(0.082f, 0.078f, 0.082f, 10, 12)
    private val upperArm = MeshBuilder.createTaperedLimb(0.048f, 0.048f, 0.056f, 0.056f, 0.30f, 1.08f, 12, 6, centered = false)
    private val forearm = MeshBuilder.createTaperedLimb(0.036f, 0.036f, 0.047f, 0.047f, 0.28f, 1.06f, 12, 6, centered = false)
    private val gloveHand = MeshBuilder.createEllipsoid(0.042f, 0.052f, 0.036f, 8, 10)

    private val thigh = MeshBuilder.createTaperedLimb(0.068f, 0.070f, 0.094f, 0.096f, 0.44f, 1.07f, 14, 7, centered = false)
    private val kneePad = MeshBuilder.createEllipsoid(0.062f, 0.068f, 0.054f, 10, 12)
    private val calf = MeshBuilder.createTaperedLimb(0.045f, 0.048f, 0.066f, 0.068f, 0.42f, 1.10f, 14, 7, centered = false)
    private val bootMesh = MeshBuilder.createBox(0.105f, 0.10f, 0.27f)

    private val rifleBody = MeshBuilder.createBox(0.055f, 0.09f, 0.56f)
    private val rifleBarrel = MeshBuilder.createTaperedLimb(0.014f, 0.014f, 0.016f, 0.016f, 0.30f, 1.0f, 8, 3)
    private val rifleMag = MeshBuilder.createBox(0.036f, 0.16f, 0.065f)
    private val flashMesh = MeshBuilder.createEllipsoid(0.09f, 0.09f, 0.18f, 8, 10)

    private val rootMat = FloatArray(16)
    private val torsoMat = FloatArray(16)
    private val headMat = FloatArray(16)
    private val partMat = FloatArray(16)
    private val subMat = FloatArray(16)

    fun draw(
        enemy: EnemyOperative,
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
        ) -> Unit
    ) {
        val isHeavy = enemy.soldierClass == SoldierClass.HEAVY
        val isRecon = enemy.soldierClass == SoldierClass.RECON
        val widthScale = if (isHeavy) 1.10f else if (isRecon) 0.96f else 1.0f

        val visorR = when (enemy.soldierClass) {
            SoldierClass.ASSAULT -> 0.0f
            SoldierClass.RECON -> 0.15f
            SoldierClass.HEAVY -> 1.15f
        }
        val visorG = when (enemy.soldierClass) {
            SoldierClass.ASSAULT -> 0.85f
            SoldierClass.RECON -> 0.78f
            SoldierClass.HEAVY -> 0.52f
        }
        val visorB = when (enemy.soldierClass) {
            SoldierClass.ASSAULT -> 0.62f
            SoldierClass.RECON -> 1.15f
            SoldierClass.HEAVY -> 0.08f
        }

        val uniformTintR = when (enemy.soldierClass) {
            SoldierClass.ASSAULT -> 0.78f
            SoldierClass.RECON -> 0.65f
            SoldierClass.HEAVY -> 0.58f
        }
        val uniformTintG = when (enemy.soldierClass) {
            SoldierClass.ASSAULT -> 0.84f
            SoldierClass.RECON -> 0.76f
            SoldierClass.HEAVY -> 0.60f
        }
        val uniformTintB = when (enemy.soldierClass) {
            SoldierClass.ASSAULT -> 0.88f
            SoldierClass.RECON -> 0.86f
            SoldierClass.HEAVY -> 0.66f
        }

        val flinchHit = enemy.hitFlinch.coerceIn(0f, 1f)
        val hitTintR = 1.0f + flinchHit * 0.75f
        val hitEmissiveR = flinchHit * 0.55f

        val breath = sin(timeSec * 2.6f + enemy.id * 1.3f) * 0.012f
        val stride = if (enemy.isMoving && !enemy.isDead) sin(enemy.walkPhase) else 0f
        val strideCos = if (enemy.isMoving && !enemy.isDead) cos(enemy.walkPhase) else 0f
        val bobY = if (enemy.isMoving && !enemy.isDead) kotlin.math.abs(stride) * 0.035f else 0f

        Matrix.setIdentityM(rootMat, 0)
        Matrix.translateM(rootMat, 0, enemy.x, bobY, enemy.z)
        Matrix.rotateM(rootMat, 0, enemy.yawDeg, 0f, 1f, 0f)

        // Dramatic death fall onto back
        if (enemy.isDead) {
            val fall = enemy.deathProgress.coerceIn(0f, 1f)
            val eased = 1f - (1f - fall) * (1f - fall)
            Matrix.translateM(rootMat, 0, 0f, 0.14f * eased, -0.30f * eased)
            Matrix.rotateM(rootMat, 0, -86f * eased, 1f, 0f, 0f)
            Matrix.rotateM(rootMat, 0, 14f * eased * enemy.deathFallDir, 0f, 0f, 1f)
        }

        // --- PELVIS & BELT (y = 0.92 m, exact 1:1 hip height) ---
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.91f, 0f)
        Matrix.scaleM(partMat, 0, widthScale, 1f, widthScale)
        Matrix.multiplyMM(subMat, 0, rootMat, 0, partMat, 0)
        drawPart(pelvisMesh, subMat, MaterialTexture.SOLDIER_FABRIC, uniformTintR * hitTintR, uniformTintG, uniformTintB, 0.08f, 0.78f, hitEmissiveR, 0f, 0f, 0.05f)

        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.99f, 0f)
        Matrix.scaleM(partMat, 0, widthScale, 1f, widthScale)
        Matrix.multiplyMM(subMat, 0, rootMat, 0, partMat, 0)
        drawPart(beltMesh, subMat, MaterialTexture.SOLDIER_ARMOR, 0.45f, 0.46f, 0.50f, 0.35f, 0.52f, 0f, 0f, 0f, 0.15f)

        // --- ARTICULATED LEGS (Left & Right, hip at 0.88m -> ankle at 0.08m -> boot on floor) ---
        for (side in intArrayOf(-1, 1)) {
            val legSwing = stride * 28f * side
            val kneeBend = (if (side == 1) maxOf(0f, -strideCos) else maxOf(0f, strideCos)) * 34f

            // Thigh
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, side * 0.105f * widthScale, 0.88f, 0f)
            Matrix.rotateM(partMat, 0, legSwing, 1f, 0f, 0f)
            Matrix.multiplyMM(subMat, 0, rootMat, 0, partMat, 0)

            val thighRender = FloatArray(16)
            Matrix.setIdentityM(thighRender, 0)
            Matrix.translateM(thighRender, 0, 0f, -0.44f, 0f)
            val thighWorld = FloatArray(16)
            Matrix.multiplyMM(thighWorld, 0, subMat, 0, thighRender, 0)
            drawPart(thigh, thighWorld, MaterialTexture.SOLDIER_FABRIC, uniformTintR, uniformTintG, uniformTintB, 0.06f, 0.82f, 0f, 0f, 0f, 0.04f)

            // Knee joint + ballistic kneepad
            val kneeWorld = FloatArray(16)
            val kneeLocal = FloatArray(16)
            Matrix.setIdentityM(kneeLocal, 0)
            Matrix.translateM(kneeLocal, 0, 0f, -0.43f, 0.028f)
            Matrix.multiplyMM(kneeWorld, 0, subMat, 0, kneeLocal, 0)
            drawPart(kneePad, kneeWorld, MaterialTexture.SOLDIER_ARMOR, 0.40f, 0.44f, 0.48f, 0.30f, 0.48f, 0f, 0f, 0f, 0.20f)

            // Calf
            val calfPivot = FloatArray(16)
            Matrix.setIdentityM(calfPivot, 0)
            Matrix.translateM(calfPivot, 0, 0f, -0.43f, 0f)
            Matrix.rotateM(calfPivot, 0, -kneeBend, 1f, 0f, 0f)
            val calfBase = FloatArray(16)
            Matrix.multiplyMM(calfBase, 0, subMat, 0, calfPivot, 0)

            val calfOffset = FloatArray(16)
            Matrix.setIdentityM(calfOffset, 0)
            Matrix.translateM(calfOffset, 0, 0f, -0.41f, 0f)
            val calfWorld = FloatArray(16)
            Matrix.multiplyMM(calfWorld, 0, calfBase, 0, calfOffset, 0)
            drawPart(calf, calfWorld, MaterialTexture.SOLDIER_FABRIC, uniformTintR * 0.92f, uniformTintG * 0.92f, uniformTintB * 0.95f, 0.08f, 0.80f, 0f, 0f, 0f, 0.05f)

            // Combat Boot
            val bootOffset = FloatArray(16)
            Matrix.setIdentityM(bootOffset, 0)
            Matrix.translateM(bootOffset, 0, 0f, -0.40f, 0.048f)
            val bootWorld = FloatArray(16)
            Matrix.multiplyMM(bootWorld, 0, calfBase, 0, bootOffset, 0)
            drawPart(bootMesh, bootWorld, MaterialTexture.SOLDIER_ARMOR, 0.30f, 0.31f, 0.34f, 0.25f, 0.45f, 0f, 0f, 0f, 0.22f)
        }

        // --- TORSO & BALLISTIC PLATE CARRIER (y = 1.00 .. 1.46 m) ---
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 1.00f + breath, 0f)
        Matrix.rotateM(partMat, 0, -flinchHit * 14f, 1f, 0f, 0f)
        Matrix.rotateM(partMat, 0, stride * -6f, 0f, 1f, 0f)
        Matrix.multiplyMM(torsoMat, 0, rootMat, 0, partMat, 0)

        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.23f, 0f)
        Matrix.scaleM(partMat, 0, widthScale, 1f, widthScale)
        Matrix.multiplyMM(subMat, 0, torsoMat, 0, partMat, 0)
        drawPart(upperTorso, subMat, MaterialTexture.SOLDIER_FABRIC, uniformTintR * hitTintR, uniformTintG, uniformTintB, 0.10f, 0.76f, hitEmissiveR, 0f, 0f, 0.06f)

        // Kevlar Tactical Plate Carrier
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.24f, 0.012f)
        Matrix.scaleM(partMat, 0, widthScale, 1f, widthScale)
        Matrix.multiplyMM(subMat, 0, torsoMat, 0, partMat, 0)
        drawPart(plateCarrier, subMat, MaterialTexture.SOLDIER_ARMOR, 0.56f * hitTintR, 0.60f, 0.64f, 0.34f, 0.46f, hitEmissiveR * 0.5f, 0f, 0f, 0.24f)

        // 3 Front MOLLE Magazine Pouches
        for (p in -1..1) {
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, p * 0.088f * widthScale, 0.16f, 0.145f * widthScale)
            Matrix.multiplyMM(subMat, 0, torsoMat, 0, partMat, 0)
            drawPart(magPouch, subMat, MaterialTexture.SOLDIER_ARMOR, 0.45f, 0.48f, 0.52f, 0.22f, 0.58f, 0f, 0f, 0f, 0.10f)
        }

        // --- NECK, REALISTIC HEAD, OPS-CORE HELMET & REFLECTIVE VISOR (y = 1.46 .. 1.81 m) ---
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.47f, 0.01f)
        Matrix.rotateM(partMat, 0, flinchHit * -18f, 1f, 0f, 0f)
        Matrix.multiplyMM(headMat, 0, torsoMat, 0, partMat, 0)

        // Neck
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.04f, 0f)
        Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
        drawPart(neckMesh, subMat, MaterialTexture.SOLDIER_SKIN, 0.94f, 0.90f, 0.88f, 0.04f, 0.62f, 0f, 0f, 0f, 0.05f)

        // Sculpted Cranium & Lower Face/Jaw (realistic human skin)
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.175f, 0.008f)
        Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
        drawPart(headCranium, subMat, MaterialTexture.SOLDIER_SKIN, 0.98f * hitTintR, 0.94f, 0.90f, 0.05f, 0.56f, hitEmissiveR * 0.6f, 0f, 0f, 0.08f)

        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.115f, 0.042f)
        Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
        drawPart(jawMesh, subMat, MaterialTexture.SOLDIER_ARMOR, 0.36f, 0.39f, 0.43f, 0.18f, 0.65f, 0f, 0f, 0f, 0.10f)

        // Ballistic Ops-Core Helmet
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.215f, -0.005f)
        Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
        drawPart(helmetDome, subMat, MaterialTexture.SOLDIER_ARMOR, 0.50f, 0.54f, 0.58f, 0.45f, 0.36f, 0f, 0f, 0f, 0.35f)

        // Reflective RTX Tactical Ballistic Visor with subtle emissive HUD glint
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.180f, 0.046f)
        Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
        drawPart(visorMesh, subMat, MaterialTexture.VISOR_GLASS, 0.25f, 0.35f, 0.42f, 0.90f, 0.08f, visorR * 0.48f, visorG * 0.48f, visorB * 0.48f, 0.95f)

        // Tactical Comms Ear-Pro
        for (side in intArrayOf(-1, 1)) {
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, side * 0.108f, 0.175f, 0f)
            Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
            drawPart(earProMesh, subMat, MaterialTexture.GUNMETAL, 0.42f, 0.44f, 0.48f, 0.55f, 0.38f, 0f, 0f, 0f, 0.28f)
        }

        // --- ARMS & 3D TACTICAL RIFLE HELD IN COMBAT AIM ---
        for (side in intArrayOf(-1, 1)) {
            val sx = side * 0.225f * widthScale
            // Shoulder deltoid
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, sx, 0.40f, 0f)
            Matrix.multiplyMM(subMat, 0, torsoMat, 0, partMat, 0)
            drawPart(
                if (isHeavy) pauldronMesh else shoulderJoint,
                subMat,
                MaterialTexture.SOLDIER_ARMOR,
                0.52f, 0.55f, 0.60f, 0.40f, 0.42f, 0f, 0f, 0f, 0.25f
            )

            // Upper Arm raised forward to hold rifle
            val armBase = FloatArray(16)
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, sx, 0.39f, 0f)
            Matrix.rotateM(partMat, 0, if (side == 1) -52f else -58f, 1f, 0f, 0f)
            Matrix.rotateM(partMat, 0, side * -18f, 0f, 0f, 1f)
            Matrix.multiplyMM(armBase, 0, torsoMat, 0, partMat, 0)

            val uaOff = FloatArray(16)
            Matrix.setIdentityM(uaOff, 0)
            Matrix.translateM(uaOff, 0, 0f, -0.29f, 0f)
            Matrix.multiplyMM(subMat, 0, armBase, 0, uaOff, 0)
            drawPart(upperArm, subMat, MaterialTexture.SOLDIER_FABRIC, uniformTintR, uniformTintG, uniformTintB, 0.08f, 0.78f, 0f, 0f, 0f, 0.05f)

            // Forearm angled inward to grip rifle foregrip / pistol grip
            val faPivot = FloatArray(16)
            Matrix.setIdentityM(faPivot, 0)
            Matrix.translateM(faPivot, 0, 0f, -0.28f, 0f)
            Matrix.rotateM(faPivot, 0, -34f, 1f, 0f, 0f)
            Matrix.rotateM(faPivot, 0, side * 26f, 0f, 1f, 0f)
            val faBase = FloatArray(16)
            Matrix.multiplyMM(faBase, 0, armBase, 0, faPivot, 0)

            val faOff = FloatArray(16)
            Matrix.setIdentityM(faOff, 0)
            Matrix.translateM(faOff, 0, 0f, -0.26f, 0f)
            Matrix.multiplyMM(subMat, 0, faBase, 0, faOff, 0)
            drawPart(forearm, subMat, MaterialTexture.SOLDIER_FABRIC, uniformTintR * 0.9f, uniformTintG * 0.9f, uniformTintB * 0.95f, 0.12f, 0.72f, 0f, 0f, 0f, 0.08f)

            // Tactical Glove Hand
            val handOff = FloatArray(16)
            Matrix.setIdentityM(handOff, 0)
            Matrix.translateM(handOff, 0, 0f, -0.27f, 0f)
            Matrix.multiplyMM(subMat, 0, faBase, 0, handOff, 0)
            drawPart(gloveHand, subMat, MaterialTexture.SOLDIER_ARMOR, 0.30f, 0.32f, 0.36f, 0.20f, 0.55f, 0f, 0f, 0f, 0.12f)
        }

        // Enemy 3D Assault Rifle
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0.075f, 0.25f, 0.36f)
        Matrix.rotateM(partMat, 0, -6f, 0f, 1f, 0f)
        Matrix.multiplyMM(subMat, 0, torsoMat, 0, partMat, 0)
        drawPart(rifleBody, subMat, MaterialTexture.GUNMETAL, 0.65f, 0.68f, 0.72f, 0.82f, 0.28f, 0f, 0f, 0f, 0.55f)

        val magOff = FloatArray(16)
        Matrix.setIdentityM(magOff, 0)
        Matrix.translateM(magOff, 0, 0f, -0.095f, 0.05f)
        Matrix.rotateM(magOff, 0, 12f, 1f, 0f, 0f)
        val magWorld = FloatArray(16)
        Matrix.multiplyMM(magWorld, 0, subMat, 0, magOff, 0)
        drawPart(rifleMag, magWorld, MaterialTexture.GUNMETAL, 0.45f, 0.47f, 0.50f, 0.72f, 0.38f, 0f, 0f, 0f, 0.40f)

        val barrelOff = FloatArray(16)
        Matrix.setIdentityM(barrelOff, 0)
        Matrix.translateM(barrelOff, 0, 0f, 0.01f, 0.40f)
        Matrix.rotateM(barrelOff, 0, 90f, 1f, 0f, 0f)
        val barrelWorld = FloatArray(16)
        Matrix.multiplyMM(barrelWorld, 0, subMat, 0, barrelOff, 0)
        drawPart(rifleBarrel, barrelWorld, MaterialTexture.GUNMETAL, 0.52f, 0.55f, 0.60f, 0.90f, 0.22f, 0f, 0f, 0f, 0.65f)

        // Enemy Muzzle Flash when firing
        if (enemy.muzzleFlashTimer > 0f && !enemy.isDead) {
            val fOff = FloatArray(16)
            Matrix.setIdentityM(fOff, 0)
            Matrix.translateM(fOff, 0, 0f, 0.01f, 0.62f)
            val fWorld = FloatArray(16)
            Matrix.multiplyMM(fWorld, 0, subMat, 0, fOff, 0)
            drawPart(flashMesh, fWorld, MaterialTexture.VISOR_GLASS, 1f, 0.8f, 0.35f, 0f, 1f, 2.8f, 1.55f, 0.35f, 0f)
        }
    }

    fun release() {
        headCranium.release()
        jawMesh.release()
        neckMesh.release()
        helmetDome.release()
        visorMesh.release()
        earProMesh.release()
        upperTorso.release()
        plateCarrier.release()
        magPouch.release()
        pelvisMesh.release()
        beltMesh.release()
        shoulderJoint.release()
        pauldronMesh.release()
        upperArm.release()
        forearm.release()
        gloveHand.release()
        thigh.release()
        kneePad.release()
        calf.release()
        bootMesh.release()
        rifleBody.release()
        rifleBarrel.release()
        rifleMag.release()
        flashMesh.release()
    }
}
