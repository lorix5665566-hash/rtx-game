package com.example.engine

import android.opengl.Matrix
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
    var strafePhase: Float = 0f,
    var moveForwardRatio: Float = 0f, // +1 forward, -1 backward
    var moveStrafeRatio: Float = 0f,  // +1 right, -1 left
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
 * Realistic 1:1 anatomically proportioned (~1.80m) human special-forces soldiers
 * wearing matte Kevlar ballistic plate carriers, combat uniforms, real human faces
 * (skin, jawline, nose, eyes, eyebrows, ears, matte helmet + goggles up on brow),
 * and full 4-way directional locomotion animations (Forward / Backward / Strafe Left / Strafe Right).
 */
class HumanSoldierModel {
    // Realistic human head & facial features (zero sci-fi robot parts!)
    private val headCranium = MeshBuilder.createEllipsoid(0.094f, 0.116f, 0.106f, 14, 18)
    private val jawChin = MeshBuilder.createEllipsoid(0.068f, 0.062f, 0.076f, 10, 14)
    private val noseMesh = MeshBuilder.createEllipsoid(0.017f, 0.028f, 0.026f, 8, 10)
    private val earMesh = MeshBuilder.createEllipsoid(0.014f, 0.032f, 0.020f, 8, 8)
    private val eyeMesh = MeshBuilder.createEllipsoid(0.014f, 0.010f, 0.010f, 8, 8)
    private val browMesh = MeshBuilder.createBox(0.032f, 0.010f, 0.014f)
    private val beardMesh = MeshBuilder.createEllipsoid(0.071f, 0.048f, 0.074f, 10, 12)
    private val neckMesh = MeshBuilder.createTaperedLimb(0.056f, 0.056f, 0.050f, 0.050f, 0.11f, 1.0f, 12, 5)

    // Realistic military combat helmet + matte goggles resting on forehead
    private val helmetShell = MeshBuilder.createEllipsoid(0.112f, 0.098f, 0.122f, 14, 18)
    private val helmetBrim = MeshBuilder.createBox(0.16f, 0.018f, 0.05f)
    private val goggleLens = MeshBuilder.createBox(0.058f, 0.034f, 0.028f)
    private val goggleStrap = MeshBuilder.createTaperedLimb(0.114f, 0.124f, 0.114f, 0.124f, 0.026f, 1.0f, 16, 3)

    // Realistic torso + matte Kevlar ballistic vest (front & back SAPI plates, straps, pouches, radio)
    private val upperTorso = MeshBuilder.createTaperedLimb(0.155f, 0.105f, 0.210f, 0.126f, 0.44f, 1.06f, 18, 8)
    private val vestFrontPlate = MeshBuilder.createBox(0.285f, 0.34f, 0.055f)
    private val vestBackPlate = MeshBuilder.createBox(0.285f, 0.34f, 0.055f)
    private val vestSideCummerbund = MeshBuilder.createBox(0.355f, 0.19f, 0.235f)
    private val vestStrap = MeshBuilder.createBox(0.062f, 0.12f, 0.20f)
    private val magPouch = MeshBuilder.createBox(0.068f, 0.135f, 0.052f)
    private val radioBox = MeshBuilder.createBox(0.052f, 0.125f, 0.045f)
    private val radioAntenna = MeshBuilder.createTaperedLimb(0.004f, 0.004f, 0.003f, 0.003f, 0.24f, 1.0f, 6, 3)
    private val pelvisMesh = MeshBuilder.createTaperedLimb(0.152f, 0.112f, 0.165f, 0.115f, 0.18f, 1.03f, 16, 6)
    private val beltMesh = MeshBuilder.createTaperedLimb(0.172f, 0.126f, 0.172f, 0.126f, 0.055f, 1.0f, 16, 3)
    private val holsterBox = MeshBuilder.createBox(0.055f, 0.14f, 0.095f)

    // Arms & legs
    private val shoulderJoint = MeshBuilder.createEllipsoid(0.066f, 0.062f, 0.064f, 10, 12)
    private val heavyPad = MeshBuilder.createEllipsoid(0.078f, 0.074f, 0.078f, 10, 12)
    private val upperArm = MeshBuilder.createTaperedLimb(0.046f, 0.046f, 0.055f, 0.055f, 0.30f, 1.07f, 12, 6, centered = false)
    private val forearm = MeshBuilder.createTaperedLimb(0.035f, 0.035f, 0.045f, 0.045f, 0.27f, 1.05f, 12, 6, centered = false)
    private val handMesh = MeshBuilder.createEllipsoid(0.040f, 0.050f, 0.034f, 8, 10)

    private val thigh = MeshBuilder.createTaperedLimb(0.068f, 0.070f, 0.092f, 0.094f, 0.44f, 1.07f, 14, 7, centered = false)
    private val kneePad = MeshBuilder.createEllipsoid(0.058f, 0.064f, 0.048f, 10, 12)
    private val calf = MeshBuilder.createTaperedLimb(0.044f, 0.046f, 0.064f, 0.066f, 0.42f, 1.09f, 14, 7, centered = false)
    private val bootMesh = MeshBuilder.createBox(0.102f, 0.10f, 0.27f)

    // Realistic enemy assault rifle
    private val rifleBody = MeshBuilder.createBox(0.052f, 0.086f, 0.56f)
    private val rifleWood = MeshBuilder.createBox(0.048f, 0.068f, 0.22f)
    private val rifleBarrel = MeshBuilder.createTaperedLimb(0.013f, 0.013f, 0.015f, 0.015f, 0.30f, 1.0f, 8, 3)
    private val rifleMag = MeshBuilder.createBox(0.034f, 0.16f, 0.062f)
    private val flashMesh = MeshBuilder.createEllipsoid(0.085f, 0.085f, 0.17f, 8, 10)

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
        val widthScale = if (isHeavy) 1.08f else if (isRecon) 0.96f else 1.0f

        // Natural real-world military uniform palettes (Olive, Coyote Tan, Urban Grey)
        val (uR, uG, uB) = when (enemy.soldierClass) {
            SoldierClass.ASSAULT -> Triple(0.88f, 0.92f, 0.84f) // Olive camo
            SoldierClass.RECON -> Triple(1.00f, 0.94f, 0.80f)   // Coyote desert tan
            SoldierClass.HEAVY -> Triple(0.68f, 0.72f, 0.74f)   // Dark tactical urban grey
        }

        // Matte ballistic Kevlar vest color (Coyote / Olive / Dark Charcoal)
        val (vR, vG, vB) = when (enemy.soldierClass) {
            SoldierClass.ASSAULT -> Triple(0.64f, 0.60f, 0.50f) // Coyote plate carrier
            SoldierClass.RECON -> Triple(0.46f, 0.50f, 0.42f)   // Ranger green vest
            SoldierClass.HEAVY -> Triple(0.36f, 0.39f, 0.42f)   // Heavy obsidian ballistic armor
        }

        val flinchHit = enemy.hitFlinch.coerceIn(0f, 1f)
        val hitTintR = 1.0f + flinchHit * 0.55f
        val hitEmissiveR = flinchHit * 0.35f

        // --- 4-WAY DIRECTIONAL LOCOMOTION BIOMECHANICS ---
        val fwd = if (enemy.isDead) 0f else enemy.moveForwardRatio.coerceIn(-1f, 1f)
        val strafe = if (enemy.isDead) 0f else enemy.moveStrafeRatio.coerceIn(-1f, 1f)
        val moveTotal = sqrt(fwd * fwd + strafe * strafe).coerceIn(0f, 1f)

        val breath = sin(timeSec * 2.5f + enemy.id * 1.3f) * 0.010f
        val fwdSin = sin(enemy.walkPhase)
        val fwdCos = cos(enemy.walkPhase)
        val strSin = sin(enemy.strafePhase)
        val strCos = cos(enemy.strafePhase)

        // Separate vertical bob for forward, backpedal, and sidestep
        val bobY = if (enemy.isDead) 0f else {
            (abs(fwdSin) * abs(fwd) * 0.036f + abs(strSin) * abs(strafe) * 0.022f).coerceAtMost(0.042f)
        }
        // Subtle lateral weight shift during strafing
        val pelvisShiftX = if (enemy.isDead) 0f else strSin * strafe * 0.042f

        Matrix.setIdentityM(rootMat, 0)
        Matrix.translateM(rootMat, 0, enemy.x + pelvisShiftX, bobY, enemy.z)
        Matrix.rotateM(rootMat, 0, enemy.yawDeg, 0f, 1f, 0f)

        if (enemy.isDead) {
            val fall = enemy.deathProgress.coerceIn(0f, 1f)
            val eased = 1f - (1f - fall) * (1f - fall)
            Matrix.translateM(rootMat, 0, 0f, 0.14f * eased, -0.32f * eased)
            Matrix.rotateM(rootMat, 0, -86f * eased, 1f, 0f, 0f)
            Matrix.rotateM(rootMat, 0, 14f * eased * enemy.deathFallDir, 0f, 0f, 1f)
        }

        // --- PELVIS, UTILITY BELT & DROP-LEG HOLSTER ---
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.91f, 0f)
        Matrix.scaleM(partMat, 0, widthScale, 1f, widthScale)
        Matrix.multiplyMM(subMat, 0, rootMat, 0, partMat, 0)
        drawPart(pelvisMesh, subMat, MaterialTexture.SOLDIER_FABRIC, uR * hitTintR, uG, uB, 0.04f, 0.86f, hitEmissiveR, 0f, 0f, 0.02f)

        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.99f, 0f)
        Matrix.scaleM(partMat, 0, widthScale, 1f, widthScale)
        Matrix.multiplyMM(subMat, 0, rootMat, 0, partMat, 0)
        drawPart(beltMesh, subMat, MaterialTexture.SOLDIER_ARMOR, 0.34f, 0.33f, 0.30f, 0.14f, 0.72f, 0f, 0f, 0f, 0.04f)

        // Sidearm holster on right hip
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0.175f * widthScale, 0.84f, 0.01f)
        Matrix.multiplyMM(subMat, 0, rootMat, 0, partMat, 0)
        drawPart(holsterBox, subMat, MaterialTexture.SOLDIER_ARMOR, 0.30f, 0.29f, 0.27f, 0.12f, 0.76f, 0f, 0f, 0f, 0.04f)

        // --- 4-WAY ARTICULATED HUMAN LEGS (Forward / Backpedal / Left & Right Lateral Strafe) ---
        for (side in intArrayOf(-1, 1)) {
            val isBackpedal = fwd < -0.05f
            val fwdMag = abs(fwd)
            val strMag = abs(strafe)

            // 1) Forward vs Backward sagittal hip pitch (X-axis)
            // Forward walk: long natural stride (+32 deg forward / -24 deg back)
            // Backward walk: shorter cautious reverse reach (reversed swing phase & smaller amplitude)
            val rawSagittal = if (isBackpedal) {
                -fwdSin * 21f * side * fwdMag - 5f * fwdMag
            } else {
                fwdSin * 31f * side * fwdMag
            }

            // 2) Lateral Strafe hip abduction/adduction (Z-axis)
            // Real sidestep: leading leg steps out sideways, trailing leg pulls in cleanly
            val strafeAbduct = (strSin * 20f * side + 7f * side) * strMag * (if (strafe >= 0f) 1f else -1f)
            val lateralStaggerPitch = strCos * 9f * side * strMag

            val hipPitchX = rawSagittal + lateralStaggerPitch
            val hipRollZ = strafeAbduct * (if (strafe >= 0f) 1f else -1f)

            // 3) Knee flexion:
            // Forward: knee bends strongly during swing phase (behind -> passing forward)
            // Backpedal: knee lifts & bends earlier so heel doesn't drag backward
            // Strafe: athletic slight tactical knee bend + lift on stepping leg
            val fwdKnee = if (isBackpedal) {
                (if (side == 1) maxOf(0f, fwdCos) else maxOf(0f, -fwdCos)) * 38f * fwdMag + 12f * fwdMag
            } else {
                (if (side == 1) maxOf(0f, -fwdCos) else maxOf(0f, fwdCos)) * 36f * fwdMag + 4f * fwdMag
            }
            val strKnee = (if (side == 1) maxOf(0f, strCos) else maxOf(0f, -strCos)) * 28f * strMag + 10f * strMag
            val totalKneeBend = (fwdKnee + strKnee).coerceIn(4f, 56f)

            // Thigh pivot at anatomical hip socket (y = 0.88m)
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, side * 0.105f * widthScale, 0.88f, 0f)
            Matrix.rotateM(partMat, 0, hipPitchX, 1f, 0f, 0f)
            Matrix.rotateM(partMat, 0, hipRollZ, 0f, 0f, 1f)
            Matrix.multiplyMM(subMat, 0, rootMat, 0, partMat, 0)

            val thighRender = FloatArray(16)
            Matrix.setIdentityM(thighRender, 0)
            Matrix.translateM(thighRender, 0, 0f, -0.44f, 0f)
            val thighWorld = FloatArray(16)
            Matrix.multiplyMM(thighWorld, 0, subMat, 0, thighRender, 0)
            drawPart(thigh, thighWorld, MaterialTexture.SOLDIER_FABRIC, uR, uG, uB, 0.04f, 0.88f, 0f, 0f, 0f, 0.02f)

            // Matte tactical kneepad
            val kneeLocal = FloatArray(16)
            Matrix.setIdentityM(kneeLocal, 0)
            Matrix.translateM(kneeLocal, 0, 0f, -0.43f, 0.028f)
            val kneeWorld = FloatArray(16)
            Matrix.multiplyMM(kneeWorld, 0, subMat, 0, kneeLocal, 0)
            drawPart(kneePad, kneeWorld, MaterialTexture.SOLDIER_ARMOR, 0.34f, 0.33f, 0.30f, 0.12f, 0.72f, 0f, 0f, 0f, 0.05f)

            // Calf
            val calfPivot = FloatArray(16)
            Matrix.setIdentityM(calfPivot, 0)
            Matrix.translateM(calfPivot, 0, 0f, -0.43f, 0f)
            Matrix.rotateM(calfPivot, 0, -totalKneeBend, 1f, 0f, 0f)
            val calfBase = FloatArray(16)
            Matrix.multiplyMM(calfBase, 0, subMat, 0, calfPivot, 0)

            val calfOffset = FloatArray(16)
            Matrix.setIdentityM(calfOffset, 0)
            Matrix.translateM(calfOffset, 0, 0f, -0.41f, 0f)
            val calfWorld = FloatArray(16)
            Matrix.multiplyMM(calfWorld, 0, calfBase, 0, calfOffset, 0)
            drawPart(calf, calfWorld, MaterialTexture.SOLDIER_FABRIC, uR * 0.94f, uG * 0.94f, uB * 0.94f, 0.04f, 0.88f, 0f, 0f, 0f, 0.02f)

            // Combat Boot (counter-rotates slightly so sole stays natural)
            val bootOffset = FloatArray(16)
            Matrix.setIdentityM(bootOffset, 0)
            Matrix.translateM(bootOffset, 0, 0f, -0.40f, 0.048f)
            Matrix.rotateM(bootOffset, 0, totalKneeBend * 0.35f, 1f, 0f, 0f)
            val bootWorld = FloatArray(16)
            Matrix.multiplyMM(bootWorld, 0, calfBase, 0, bootOffset, 0)
            drawPart(bootMesh, bootWorld, MaterialTexture.SOLDIER_ARMOR, 0.28f, 0.26f, 0.23f, 0.14f, 0.68f, 0f, 0f, 0f, 0.08f)
        }

        // --- TORSO WITH DIRECTIONAL LEAN & DETAILED BALLISTIC VEST ---
        val torsoForwardLean = fwd * 7.5f // leans forward walking ahead, leans slightly back when backpedaling
        val torsoStrafeBank = -strafe * 6.0f // realistic lean into sidestep direction
        val torsoCounterTwist = (fwdSin * abs(fwd) * -6.5f) + (strSin * abs(strafe) * 3.5f)

        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 1.00f + breath, 0f)
        Matrix.rotateM(partMat, 0, torsoForwardLean - flinchHit * 13f, 1f, 0f, 0f)
        Matrix.rotateM(partMat, 0, torsoStrafeBank, 0f, 0f, 1f)
        Matrix.rotateM(partMat, 0, torsoCounterTwist, 0f, 1f, 0f)
        Matrix.multiplyMM(torsoMat, 0, rootMat, 0, partMat, 0)

        // Combat shirt torso
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.23f, 0f)
        Matrix.scaleM(partMat, 0, widthScale, 1f, widthScale)
        Matrix.multiplyMM(subMat, 0, torsoMat, 0, partMat, 0)
        drawPart(upperTorso, subMat, MaterialTexture.SOLDIER_FABRIC, uR * hitTintR, uG, uB, 0.04f, 0.86f, hitEmissiveR, 0f, 0f, 0.02f)

        // Realistic Ballistic Kevlar Vest: Side Cummerbund + Front SAPI Plate + Back SAPI Plate + Shoulder Straps
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.20f, 0f)
        Matrix.scaleM(partMat, 0, widthScale, 1f, widthScale)
        Matrix.multiplyMM(subMat, 0, torsoMat, 0, partMat, 0)
        drawPart(vestSideCummerbund, subMat, MaterialTexture.SOLDIER_ARMOR, vR * hitTintR, vG, vB, 0.10f, 0.78f, hitEmissiveR * 0.4f, 0f, 0f, 0.04f)

        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.25f, 0.125f * widthScale)
        Matrix.scaleM(partMat, 0, widthScale, 1f, 1f)
        Matrix.multiplyMM(subMat, 0, torsoMat, 0, partMat, 0)
        drawPart(vestFrontPlate, subMat, MaterialTexture.SOLDIER_ARMOR, vR * 1.06f * hitTintR, vG * 1.06f, vB * 1.06f, 0.14f, 0.70f, hitEmissiveR * 0.5f, 0f, 0f, 0.06f)

        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.25f, -0.125f * widthScale)
        Matrix.scaleM(partMat, 0, widthScale, 1f, 1f)
        Matrix.multiplyMM(subMat, 0, torsoMat, 0, partMat, 0)
        drawPart(vestBackPlate, subMat, MaterialTexture.SOLDIER_ARMOR, vR * hitTintR, vG, vB, 0.14f, 0.72f, 0f, 0f, 0f, 0.05f)

        // Vest Shoulder Straps
        for (side in intArrayOf(-1, 1)) {
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, side * 0.11f * widthScale, 0.42f, 0f)
            Matrix.multiplyMM(subMat, 0, torsoMat, 0, partMat, 0)
            drawPart(vestStrap, subMat, MaterialTexture.SOLDIER_ARMOR, vR * 0.9f, vG * 0.9f, vB * 0.9f, 0.10f, 0.80f, 0f, 0f, 0f, 0.03f)
        }

        // 3 Front Rifle Magazine Pouches + Tactical Radio with Whip Antenna
        for (p in -1..1) {
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, p * 0.084f * widthScale, 0.16f, 0.165f * widthScale)
            Matrix.multiplyMM(subMat, 0, torsoMat, 0, partMat, 0)
            drawPart(magPouch, subMat, MaterialTexture.SOLDIER_ARMOR, vR * 0.86f, vG * 0.86f, vB * 0.86f, 0.08f, 0.82f, 0f, 0f, 0f, 0.03f)
        }
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, -0.14f * widthScale, 0.30f, 0.155f * widthScale)
        Matrix.multiplyMM(subMat, 0, torsoMat, 0, partMat, 0)
        drawPart(radioBox, subMat, MaterialTexture.GUNMETAL, 0.30f, 0.31f, 0.33f, 0.35f, 0.55f, 0f, 0f, 0f, 0.10f)

        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, -0.155f * widthScale, 0.45f, 0.155f * widthScale)
        Matrix.multiplyMM(subMat, 0, torsoMat, 0, partMat, 0)
        drawPart(radioAntenna, subMat, MaterialTexture.GUNMETAL, 0.25f, 0.26f, 0.28f, 0.45f, 0.50f, 0f, 0f, 0f, 0.10f)

        // --- REALISTIC HUMAN HEAD, FACE, NECK & COMBAT HELMET (NO ROBOT VISOR!) ---
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.47f, 0.01f)
        // Head stays level looking at player even while torso leans/twists during strafe
        Matrix.rotateM(partMat, 0, -torsoForwardLean * 0.6f - flinchHit * 16f, 1f, 0f, 0f)
        Matrix.rotateM(partMat, 0, -torsoStrafeBank * 0.6f, 0f, 0f, 1f)
        Matrix.rotateM(partMat, 0, -torsoCounterTwist * 0.7f, 0f, 1f, 0f)
        Matrix.multiplyMM(headMat, 0, torsoMat, 0, partMat, 0)

        // Natural human skin neck
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.04f, 0f)
        Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
        drawPart(neckMesh, subMat, MaterialTexture.SOLDIER_SKIN, 0.96f, 0.92f, 0.88f, 0.02f, 0.65f, 0f, 0f, 0f, 0.02f)

        // Sculpted human head & jawline (warm natural skin!)
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.175f, 0.008f)
        Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
        drawPart(headCranium, subMat, MaterialTexture.SOLDIER_SKIN, 1.0f * hitTintR, 0.95f, 0.92f, 0.02f, 0.60f, hitEmissiveR * 0.4f, 0f, 0f, 0.02f)

        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.112f, 0.026f)
        Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
        drawPart(jawChin, subMat, MaterialTexture.SOLDIER_SKIN, 0.95f, 0.90f, 0.86f, 0.02f, 0.62f, 0f, 0f, 0f, 0.02f)

        // Subtle dark tactical beard/stubble on Assault & Heavy for realistic human character
        if (!isRecon) {
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, 0f, 0.106f, 0.032f)
            Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
            drawPart(beardMesh, subMat, MaterialTexture.SOLDIER_ARMOR, 0.24f, 0.20f, 0.17f, 0.02f, 0.92f, 0f, 0f, 0f, 0f)
        }

        // Nose
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.165f, 0.102f)
        Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
        drawPart(noseMesh, subMat, MaterialTexture.SOLDIER_SKIN, 0.98f, 0.91f, 0.86f, 0.02f, 0.60f, 0f, 0f, 0f, 0.02f)

        // Natural Eyes, Eyebrows & Ears
        for (side in intArrayOf(-1, 1)) {
            // Eye
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, side * 0.034f, 0.184f, 0.095f)
            Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
            drawPart(eyeMesh, subMat, MaterialTexture.SOLDIER_ARMOR, 0.16f, 0.15f, 0.14f, 0.15f, 0.25f, 0f, 0f, 0f, 0.12f)

            // Eyebrow
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, side * 0.035f, 0.202f, 0.096f)
            Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
            drawPart(browMesh, subMat, MaterialTexture.SOLDIER_ARMOR, 0.22f, 0.18f, 0.15f, 0.02f, 0.90f, 0f, 0f, 0f, 0f)

            // Ear
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, side * 0.094f, 0.170f, 0.002f)
            Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
            drawPart(earMesh, subMat, MaterialTexture.SOLDIER_SKIN, 0.92f, 0.86f, 0.82f, 0.02f, 0.65f, 0f, 0f, 0f, 0.02f)
        }

        // Matte Military Combat Helmet (sits naturally above eyebrows so full human face is visible!)
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.232f, -0.008f)
        Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
        drawPart(helmetShell, subMat, MaterialTexture.SOLDIER_ARMOR, vR * 0.92f, vG * 0.94f, vB * 0.90f, 0.12f, 0.72f, 0f, 0f, 0f, 0.06f)

        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.214f, 0.095f)
        Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
        drawPart(helmetBrim, subMat, MaterialTexture.SOLDIER_ARMOR, vR * 0.88f, vG * 0.90f, vB * 0.86f, 0.12f, 0.74f, 0f, 0f, 0f, 0.05f)

        // Tactical goggles resting UP on the helmet forehead (realistic real-world soldier silhouette)
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.246f, -0.004f)
        Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
        drawPart(goggleStrap, subMat, MaterialTexture.SOLDIER_ARMOR, 0.24f, 0.24f, 0.24f, 0.05f, 0.85f, 0f, 0f, 0f, 0.02f)

        for (side in intArrayOf(-1, 1)) {
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, side * 0.036f, 0.248f, 0.108f)
            Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
            drawPart(goggleLens, subMat, MaterialTexture.VISOR_GLASS, 0.22f, 0.24f, 0.26f, 0.65f, 0.18f, 0f, 0f, 0f, 0.45f)
        }

        // --- ARMS & 3D RIFLE HELD IN REALISTIC COMBAT STANCE ---
        val aimBounce = if (moveTotal > 0.1f) sin(enemy.walkPhase * 2f) * 2.5f * moveTotal else 0f
        for (side in intArrayOf(-1, 1)) {
            val sx = side * 0.220f * widthScale
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, sx, 0.40f, 0f)
            Matrix.multiplyMM(subMat, 0, torsoMat, 0, partMat, 0)
            drawPart(
                if (isHeavy) heavyPad else shoulderJoint,
                subMat,
                if (isHeavy) MaterialTexture.SOLDIER_ARMOR else MaterialTexture.SOLDIER_FABRIC,
                if (isHeavy) vR else uR,
                if (isHeavy) vG else uG,
                if (isHeavy) vB else uB,
                0.08f, 0.80f, 0f, 0f, 0f, 0.03f
            )

            val armBase = FloatArray(16)
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, sx, 0.39f, 0f)
            Matrix.rotateM(partMat, 0, (if (side == 1) -52f else -58f) + aimBounce, 1f, 0f, 0f)
            Matrix.rotateM(partMat, 0, side * -18f, 0f, 0f, 1f)
            Matrix.multiplyMM(armBase, 0, torsoMat, 0, partMat, 0)

            val uaOff = FloatArray(16)
            Matrix.setIdentityM(uaOff, 0)
            Matrix.translateM(uaOff, 0, 0f, -0.29f, 0f)
            Matrix.multiplyMM(subMat, 0, armBase, 0, uaOff, 0)
            drawPart(upperArm, subMat, MaterialTexture.SOLDIER_FABRIC, uR, uG, uB, 0.04f, 0.86f, 0f, 0f, 0f, 0.02f)

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
            // Rolled sleeves showing realistic human forearms on Recon, camo sleeves on Assault/Heavy
            val forearmTex = if (isRecon) MaterialTexture.SOLDIER_SKIN else MaterialTexture.SOLDIER_FABRIC
            drawPart(
                forearm, subMat, forearmTex,
                if (isRecon) 0.96f else uR * 0.92f,
                if (isRecon) 0.92f else uG * 0.92f,
                if (isRecon) 0.88f else uB * 0.92f,
                0.04f, 0.78f, 0f, 0f, 0f, 0.02f
            )

            // Realistic human hands / matte tactical gloves
            val handOff = FloatArray(16)
            Matrix.setIdentityM(handOff, 0)
            Matrix.translateM(handOff, 0, 0f, -0.26f, 0f)
            Matrix.multiplyMM(subMat, 0, faBase, 0, handOff, 0)
            drawPart(handMesh, subMat, MaterialTexture.SOLDIER_ARMOR, 0.34f, 0.32f, 0.28f, 0.08f, 0.76f, 0f, 0f, 0f, 0.03f)
        }

        // Enemy 3D Rifle (AK/M4 silhouette with wood/gunmetal accents)
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0.075f, 0.25f, 0.36f)
        Matrix.rotateM(partMat, 0, -6f, 0f, 1f, 0f)
        Matrix.multiplyMM(subMat, 0, torsoMat, 0, partMat, 0)
        drawPart(rifleBody, subMat, MaterialTexture.GUNMETAL, 0.56f, 0.58f, 0.62f, 0.78f, 0.32f, 0f, 0f, 0f, 0.35f)

        val woodOff = FloatArray(16)
        Matrix.setIdentityM(woodOff, 0)
        Matrix.translateM(woodOff, 0, 0f, -0.005f, 0.16f)
        val woodWorld = FloatArray(16)
        Matrix.multiplyMM(woodWorld, 0, subMat, 0, woodOff, 0)
        drawPart(rifleWood, woodWorld, MaterialTexture.WOOD_AK, 0.92f, 0.88f, 0.84f, 0.08f, 0.58f, 0f, 0f, 0f, 0.12f)

        val magOff = FloatArray(16)
        Matrix.setIdentityM(magOff, 0)
        Matrix.translateM(magOff, 0, 0f, -0.095f, 0.05f)
        Matrix.rotateM(magOff, 0, 14f, 1f, 0f, 0f)
        val magWorld = FloatArray(16)
        Matrix.multiplyMM(magWorld, 0, subMat, 0, magOff, 0)
        drawPart(rifleMag, magWorld, MaterialTexture.GUNMETAL, 0.42f, 0.43f, 0.45f, 0.70f, 0.38f, 0f, 0f, 0f, 0.28f)

        val barrelOff = FloatArray(16)
        Matrix.setIdentityM(barrelOff, 0)
        Matrix.translateM(barrelOff, 0, 0f, 0.01f, 0.40f)
        Matrix.rotateM(barrelOff, 0, 90f, 1f, 0f, 0f)
        val barrelWorld = FloatArray(16)
        Matrix.multiplyMM(barrelWorld, 0, subMat, 0, barrelOff, 0)
        drawPart(rifleBarrel, barrelWorld, MaterialTexture.GUNMETAL, 0.48f, 0.50f, 0.54f, 0.88f, 0.25f, 0f, 0f, 0f, 0.48f)

        if (enemy.muzzleFlashTimer > 0f && !enemy.isDead) {
            val fOff = FloatArray(16)
            Matrix.setIdentityM(fOff, 0)
            Matrix.translateM(fOff, 0, 0f, 0.01f, 0.62f)
            val fWorld = FloatArray(16)
            Matrix.multiplyMM(fWorld, 0, subMat, 0, fOff, 0)
            drawPart(flashMesh, fWorld, MaterialTexture.VISOR_GLASS, 1f, 0.82f, 0.38f, 0f, 1f, 2.8f, 1.55f, 0.38f, 0f)
        }
    }

    fun release() {
        headCranium.release(); jawChin.release(); noseMesh.release(); earMesh.release()
        eyeMesh.release(); browMesh.release(); beardMesh.release(); neckMesh.release()
        helmetShell.release(); helmetBrim.release(); goggleLens.release(); goggleStrap.release()
        upperTorso.release(); vestFrontPlate.release(); vestBackPlate.release()
        vestSideCummerbund.release(); vestStrap.release(); magPouch.release()
        radioBox.release(); radioAntenna.release(); pelvisMesh.release()
        beltMesh.release(); holsterBox.release()
        shoulderJoint.release(); heavyPad.release(); upperArm.release()
        forearm.release(); handMesh.release()
        thigh.release(); kneePad.release(); calf.release(); bootMesh.release()
        rifleBody.release(); rifleWood.release(); rifleBarrel.release()
        rifleMag.release(); flashMesh.release()
    }
}
