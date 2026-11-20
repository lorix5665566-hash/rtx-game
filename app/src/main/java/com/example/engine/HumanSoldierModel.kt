package com.example.engine

import android.opengl.Matrix
import com.example.model.TeamSide
import com.example.model.WeaponType
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class BotOperative(
    val id: Int,
    val team: TeamSide,
    var weapon: WeaponType = if (team == TeamSide.CT) WeaponType.M4A1_S else WeaponType.AK47,
    var hasBomb: Boolean = false,
    var x: Float,
    var z: Float,
    var yawDeg: Float,
    var hp: Float = 100f,
    val maxHp: Float = 100f,
    var armor: Float = 100f,
    var walkPhase: Float = 0f,
    var strafePhase: Float = 0f,
    var moveForwardRatio: Float = 0f,
    var moveStrafeRatio: Float = 0f,
    var isMoving: Boolean = false,
    var hitFlinch: Float = 0f,
    var muzzleFlashTimer: Float = 0f,
    var muzzleFlashRoll: Float = 0f,
    var fireCooldown: Float = 0.8f,
    var strafeDir: Float = 1f,
    var strafeTimer: Float = 1.6f,
    var targetSiteIndex: Int = 0, // 0 = Site A, 1 = Site B
    var waypointIndex: Int = 0,
    var plantProgress: Float = 0f,
    var defuseProgress: Float = 0f,
    var isDead: Boolean = false,
    var deathProgress: Float = 0f,
    var deathFallDir: Float = 1f
)

/**
 * CS2-inspired 1:1 human operative models split strictly into 2 distinct factions:
 * 1) CT (Спецназ): Sculpted FAST tactical helmet + headset + ballistic goggles, dark navy BDU,
 *    charcoal Kevlar plate carrier with MOLLE mag pouches & radio, hard kneepads, black boots.
 * 2) T (Террорист): Knitted dark balaclava with eye/mouth openings, tan plaid rolled-sleeve shirt
 *    with bare forearms, tan Cordura plate carrier vest, tactical belt + pouches, olive-brown
 *    cargo pants, brown leather combat boots — matching the CS2 lobby aesthetic.
 */
class HumanSoldierModel {
    // Sculpted anatomical head & facial geometry
    private val headCranium = MeshBuilder.createProfiledLoft(
        listOf(
            LoftRing(-0.10f, 0.056f, 0.064f, offsetZ = 0.012f),
            LoftRing(-0.06f, 0.072f, 0.082f, offsetZ = 0.015f),
            LoftRing(-0.01f, 0.086f, 0.098f, offsetZ = 0.006f),
            LoftRing( 0.04f, 0.092f, 0.104f, offsetZ = 0.002f),
            LoftRing( 0.09f, 0.082f, 0.094f, offsetZ = -0.004f),
            LoftRing( 0.12f, 0.048f, 0.056f, offsetZ = -0.006f)
        ), segments = 20
    )
    private val noseMesh = MeshBuilder.createEllipsoid(0.015f, 0.025f, 0.024f, 8, 10)
    private val eyePatch = MeshBuilder.createBeveledBox(0.112f, 0.036f, 0.034f, 0.28f)
    private val eyeBall = MeshBuilder.createEllipsoid(0.013f, 0.009f, 0.010f, 8, 8)
    private val mouthPatch = MeshBuilder.createEllipsoid(0.028f, 0.016f, 0.016f, 8, 10)
    private val neckMesh = MeshBuilder.createTaperedLimb(0.056f, 0.056f, 0.050f, 0.050f, 0.11f, 1.0f, 14, 5)

    // CT FAST Helmet, Headset, Visor Goggles
    private val ctHelmetShell = MeshBuilder.createProfiledLoft(
        listOf(
            LoftRing(-0.02f, 0.106f, 0.118f, offsetZ = -0.008f, squareness = 0.18f),
            LoftRing( 0.02f, 0.112f, 0.124f, offsetZ = -0.006f, squareness = 0.16f),
            LoftRing( 0.065f, 0.104f, 0.114f, offsetZ = -0.004f, squareness = 0.10f),
            LoftRing( 0.100f, 0.072f, 0.080f, offsetZ = -0.004f, squareness = 0.05f),
            LoftRing( 0.116f, 0.025f, 0.030f, offsetZ = -0.004f)
        ), segments = 20
    )
    private val ctHelmetRail = MeshBuilder.createBeveledBox(0.018f, 0.028f, 0.11f, 0.25f)
    private val ctHeadsetCup = MeshBuilder.createEllipsoid(0.026f, 0.044f, 0.038f, 10, 12)
    private val ctGogglesFrame = MeshBuilder.createBeveledBox(0.145f, 0.052f, 0.056f, 0.28f)

    // Sculpted anatomical human torso + contoured ballistic plate carrier
    private val anatomicalTorso = MeshBuilder.createProfiledLoft(
        listOf(
            LoftRing(0.00f, 0.150f, 0.104f, squareness = 0.22f), // Waist
            LoftRing(0.11f, 0.162f, 0.110f, squareness = 0.24f), // Lower ribs
            LoftRing(0.24f, 0.194f, 0.128f, offsetZ = 0.008f, squareness = 0.28f), // Chest pectorals
            LoftRing(0.36f, 0.214f, 0.124f, offsetZ = 0.004f, squareness = 0.24f), // Broad shoulders
            LoftRing(0.44f, 0.168f, 0.095f, squareness = 0.15f)  // Trapezius slope
        ), segments = 22
    )
    private val vestBody = MeshBuilder.createProfiledLoft(
        listOf(
            LoftRing(0.04f, 0.174f, 0.132f, squareness = 0.42f),
            LoftRing(0.14f, 0.186f, 0.142f, offsetZ = 0.005f, squareness = 0.46f),
            LoftRing(0.28f, 0.190f, 0.144f, offsetZ = 0.006f, squareness = 0.46f),
            LoftRing(0.38f, 0.166f, 0.128f, squareness = 0.36f)
        ), segments = 20
    )
    private val vestFrontPlate = MeshBuilder.createBeveledBox(0.255f, 0.31f, 0.048f, 0.26f)
    private val vestBackPlate = MeshBuilder.createBeveledBox(0.255f, 0.31f, 0.048f, 0.26f)
    private val vestShoulderStrap = MeshBuilder.createBeveledBox(0.062f, 0.08f, 0.19f, 0.28f)
    private val magPouch = MeshBuilder.createBeveledBox(0.064f, 0.128f, 0.048f, 0.22f)
    private val radioBox = MeshBuilder.createBeveledBox(0.048f, 0.118f, 0.042f, 0.22f)
    private val radioAntenna = MeshBuilder.createTaperedLimb(0.004f, 0.004f, 0.0025f, 0.0025f, 0.24f, 1.0f, 8, 3)

    // Pelvis, tactical leather/webbing belt, side pouches & drop-leg holster
    private val pelvisMesh = MeshBuilder.createProfiledLoft(
        listOf(
            LoftRing(-0.09f, 0.156f, 0.112f, squareness = 0.18f),
            LoftRing( 0.00f, 0.168f, 0.118f, squareness = 0.22f),
            LoftRing( 0.09f, 0.160f, 0.112f, squareness = 0.22f)
        ), segments = 18
    )
    private val beltMesh = MeshBuilder.createProfiledLoft(
        listOf(
            LoftRing(-0.032f, 0.175f, 0.128f, squareness = 0.30f),
            LoftRing( 0.032f, 0.175f, 0.128f, squareness = 0.30f)
        ), segments = 18
    )
    private val beltBuckle = MeshBuilder.createBeveledBox(0.064f, 0.056f, 0.030f, 0.20f)
    private val sidePouch = MeshBuilder.createBeveledBox(0.064f, 0.125f, 0.088f, 0.24f)

    // Muscular arms & hands
    private val shoulderJoint = MeshBuilder.createEllipsoid(0.066f, 0.064f, 0.064f, 12, 14)
    private val upperArm = MeshBuilder.createTaperedLimb(0.045f, 0.046f, 0.056f, 0.058f, 0.30f, 1.09f, 16, 7, centered = false)
    private val forearm = MeshBuilder.createTaperedLimb(0.034f, 0.035f, 0.046f, 0.047f, 0.27f, 1.07f, 16, 7, centered = false)
    private val handMesh = MeshBuilder.createBeveledBox(0.054f, 0.082f, 0.044f, 0.32f)

    // Sculpted cargo-pant legs & contoured combat boots
    private val thigh = MeshBuilder.createTaperedLimb(0.072f, 0.075f, 0.096f, 0.098f, 0.44f, 1.08f, 16, 8, centered = false)
    private val cargoPocket = MeshBuilder.createBeveledBox(0.032f, 0.15f, 0.11f, 0.25f)
    private val kneePad = MeshBuilder.createEllipsoid(0.056f, 0.064f, 0.046f, 10, 12)
    private val calf = MeshBuilder.createTaperedLimb(0.052f, 0.054f, 0.072f, 0.074f, 0.42f, 1.07f, 16, 8, centered = false)
    private val bootSculpted = MeshBuilder.createProfiledLoft(
        listOf(
            LoftRing(-0.055f, 0.054f, 0.132f, offsetZ = 0.025f, squareness = 0.35f), // Thick tread sole
            LoftRing(-0.032f, 0.052f, 0.128f, offsetZ = 0.024f, squareness = 0.32f), // Lower vamp
            LoftRing( 0.005f, 0.046f, 0.092f, offsetZ = 0.006f, squareness = 0.20f), // Instep slope
            LoftRing( 0.055f, 0.048f, 0.054f, offsetZ = -0.022f, squareness = 0.12f) // Ankle cuff
        ), segments = 16
    )

    // Third-person weapon meshes (realistic machined steel & dark polymer, zero blocky boxes)
    private val rifleReceiver = MeshBuilder.createBeveledBox(0.046f, 0.076f, 0.42f, 0.22f)
    private val rifleHandguard = MeshBuilder.createBeveledBox(0.042f, 0.056f, 0.24f, 0.24f)
    private val rifleStock = MeshBuilder.createBeveledBox(0.038f, 0.088f, 0.22f, 0.24f)
    private val rifleBarrel = MeshBuilder.createTaperedLimb(0.012f, 0.012f, 0.014f, 0.014f, 0.34f, 1.0f, 10, 3)
    private val rifleMag = MeshBuilder.createBeveledBox(0.030f, 0.16f, 0.056f, 0.22f)
    private val knifeBlade = MeshBuilder.createBeveledBox(0.008f, 0.034f, 0.17f, 0.32f)
    private val knifeGrip = MeshBuilder.createTaperedLimb(0.014f, 0.018f, 0.016f, 0.020f, 0.11f, 1.0f, 10, 3)
    private val c4BackPack = MeshBuilder.createBeveledBox(0.18f, 0.13f, 0.065f, 0.20f)
    private val flameCards = MeshBuilder.createVolumetricMuzzleFlame(length = 0.24f, radius = 0.095f)

    private val rootMat = FloatArray(16)
    private val torsoMat = FloatArray(16)
    private val headMat = FloatArray(16)
    private val partMat = FloatArray(16)
    private val subMat = FloatArray(16)

    fun draw(
        bot: BotOperative,
        timeSec: Float,
        isLobbyPose: Boolean = false,
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
        drawFlame: (mesh: GlMesh, modelMatrix: FloatArray, intensity: Float) -> Unit = { _, _, _ -> }
    ) {
        val isCT = bot.team == TeamSide.CT
        val flinchHit = bot.hitFlinch.coerceIn(0f, 1f)
        val hitTintR = 1.0f + flinchHit * 0.50f
        val hitEmissiveR = flinchHit * 0.30f

        // --- 4-WAY DIRECTIONAL LOCOMOTION BIOMECHANICS ---
        val fwd = if (bot.isDead || isLobbyPose) 0f else bot.moveForwardRatio.coerceIn(-1f, 1f)
        val strafe = if (bot.isDead || isLobbyPose) 0f else bot.moveStrafeRatio.coerceIn(-1f, 1f)
        val moveTotal = sqrt(fwd * fwd + strafe * strafe).coerceIn(0f, 1f)

        val breath = sin(timeSec * 2.2f + bot.id * 1.3f) * 0.008f
        val fwdSin = sin(bot.walkPhase)
        val fwdCos = cos(bot.walkPhase)
        val strSin = sin(bot.strafePhase)
        val strCos = cos(bot.strafePhase)

        val bobY = if (bot.isDead || isLobbyPose) 0f else {
            (abs(fwdSin) * abs(fwd) * 0.034f + abs(strSin) * abs(strafe) * 0.022f).coerceAtMost(0.040f)
        }
        val pelvisShiftX = if (bot.isDead || isLobbyPose) 0f else strSin * strafe * 0.040f

        Matrix.setIdentityM(rootMat, 0)
        Matrix.translateM(rootMat, 0, bot.x + pelvisShiftX, bobY, bot.z)
        Matrix.rotateM(rootMat, 0, bot.yawDeg, 0f, 1f, 0f)

        if (bot.isDead) {
            val fall = bot.deathProgress.coerceIn(0f, 1f)
            val eased = 1f - (1f - fall) * (1f - fall)
            Matrix.translateM(rootMat, 0, 0f, 0.14f * eased, -0.32f * eased)
            Matrix.rotateM(rootMat, 0, -86f * eased, 1f, 0f, 0f)
            Matrix.rotateM(rootMat, 0, 14f * eased * bot.deathFallDir, 0f, 0f, 1f)
        }

        val pantsTex = if (isCT) MaterialTexture.CT_UNIFORM else MaterialTexture.T_CARGO_PANTS
        val shirtTex = if (isCT) MaterialTexture.CT_UNIFORM else MaterialTexture.T_SHIRT_PLAID
        val vestTex = if (isCT) MaterialTexture.VEST_CT else MaterialTexture.VEST_TAN
        val bootTex = if (isCT) MaterialTexture.TACTICAL_POLYMER else MaterialTexture.LEATHER_BOOT

        // --- PELVIS, HEAVY TACTICAL BELT, BUCKLE & SIDE POUCHES ---
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.91f, 0f)
        Matrix.multiplyMM(subMat, 0, rootMat, 0, partMat, 0)
        drawPart(pelvisMesh, subMat, pantsTex, 1.0f * hitTintR, 1.0f, 1.0f, 0.04f, 0.86f, hitEmissiveR, 0f, 0f, 0.02f)

        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.99f, 0f)
        Matrix.multiplyMM(subMat, 0, rootMat, 0, partMat, 0)
        drawPart(beltMesh, subMat, MaterialTexture.TACTICAL_POLYMER, 0.34f, 0.30f, 0.26f, 0.15f, 0.68f, 0f, 0f, 0f, 0.05f)

        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.99f, 0.132f)
        Matrix.multiplyMM(subMat, 0, rootMat, 0, partMat, 0)
        drawPart(beltBuckle, subMat, MaterialTexture.GUNMETAL_STEEL, 0.65f, 0.60f, 0.52f, 0.82f, 0.34f, 0f, 0f, 0f, 0.35f)

        // Hip pouches & holster matching CS2 Terrorist/CT silhouette
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0.175f, 0.92f, 0.02f)
        Matrix.multiplyMM(subMat, 0, rootMat, 0, partMat, 0)
        drawPart(sidePouch, subMat, vestTex, 0.92f, 0.90f, 0.88f, 0.10f, 0.78f, 0f, 0f, 0f, 0.04f)

        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, -0.172f, 0.93f, 0.04f)
        Matrix.multiplyMM(subMat, 0, rootMat, 0, partMat, 0)
        drawPart(sidePouch, subMat, MaterialTexture.LEATHER_BOOT, 0.78f, 0.72f, 0.66f, 0.12f, 0.74f, 0f, 0f, 0f, 0.05f)

        // --- 4-WAY ARTICULATED LEGS & SCULPTED COMBAT BOOTS ---
        for (side in intArrayOf(-1, 1)) {
            val isBackpedal = fwd < -0.05f
            val fwdMag = abs(fwd)
            val strMag = abs(strafe)

            val hipPitchX: Float
            val hipRollZ: Float
            val totalKneeBend: Float
            if (isLobbyPose) {
                // Confident natural CS2 lobby stance (wide planted feet, subtle weight shift)
                hipPitchX = if (side == 1) -4f else 5f
                hipRollZ = side * 6.5f
                totalKneeBend = if (side == 1) 4f else 7f
            } else {
                val rawSagittal = if (isBackpedal) {
                    -fwdSin * 21f * side * fwdMag - 5f * fwdMag
                } else {
                    fwdSin * 31f * side * fwdMag
                }
                val strafeAbduct = (strSin * 20f * side + 7f * side) * strMag * (if (strafe >= 0f) 1f else -1f)
                val lateralStaggerPitch = strCos * 9f * side * strMag
                hipPitchX = rawSagittal + lateralStaggerPitch
                hipRollZ = strafeAbduct * (if (strafe >= 0f) 1f else -1f)

                val fwdKnee = if (isBackpedal) {
                    (if (side == 1) maxOf(0f, fwdCos) else maxOf(0f, -fwdCos)) * 38f * fwdMag + 12f * fwdMag
                } else {
                    (if (side == 1) maxOf(0f, -fwdCos) else maxOf(0f, fwdCos)) * 36f * fwdMag + 4f * fwdMag
                }
                val strKnee = (if (side == 1) maxOf(0f, strCos) else maxOf(0f, -strCos)) * 28f * strMag + 10f * strMag
                totalKneeBend = (fwdKnee + strKnee).coerceIn(4f, 56f)
            }

            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, side * 0.106f, 0.88f, 0f)
            Matrix.rotateM(partMat, 0, hipPitchX, 1f, 0f, 0f)
            Matrix.rotateM(partMat, 0, hipRollZ, 0f, 0f, 1f)
            Matrix.multiplyMM(subMat, 0, rootMat, 0, partMat, 0)

            val thighRender = FloatArray(16)
            Matrix.setIdentityM(thighRender, 0)
            Matrix.translateM(thighRender, 0, 0f, -0.44f, 0f)
            val thighWorld = FloatArray(16)
            Matrix.multiplyMM(thighWorld, 0, subMat, 0, thighRender, 0)
            drawPart(thigh, thighWorld, pantsTex, 1f, 1f, 1f, 0.04f, 0.88f, 0f, 0f, 0f, 0.02f)

            // Side cargo pocket on outer thigh
            val pockLocal = FloatArray(16)
            Matrix.setIdentityM(pockLocal, 0)
            Matrix.translateM(pockLocal, 0, side * 0.088f, -0.22f, 0f)
            val pockWorld = FloatArray(16)
            Matrix.multiplyMM(pockWorld, 0, subMat, 0, pockLocal, 0)
            drawPart(cargoPocket, pockWorld, pantsTex, 0.94f, 0.94f, 0.94f, 0.04f, 0.88f, 0f, 0f, 0f, 0.02f)

            // Hard tactical kneepads on CT (Terrorist wears rugged cargo folds)
            if (isCT) {
                val kneeLocal = FloatArray(16)
                Matrix.setIdentityM(kneeLocal, 0)
                Matrix.translateM(kneeLocal, 0, 0f, -0.43f, 0.036f)
                val kneeWorld = FloatArray(16)
                Matrix.multiplyMM(kneeWorld, 0, subMat, 0, kneeLocal, 0)
                drawPart(kneePad, kneeWorld, MaterialTexture.TACTICAL_POLYMER, 0.42f, 0.44f, 0.48f, 0.16f, 0.64f, 0f, 0f, 0f, 0.08f)
            }

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
            drawPart(calf, calfWorld, pantsTex, 0.94f, 0.94f, 0.94f, 0.04f, 0.88f, 0f, 0f, 0f, 0.02f)

            // Sculpted Leather / Tactical Combat Boot
            val bootOffset = FloatArray(16)
            Matrix.setIdentityM(bootOffset, 0)
            Matrix.translateM(bootOffset, 0, 0f, -0.395f, 0.024f)
            Matrix.rotateM(bootOffset, 0, totalKneeBend * 0.40f, 1f, 0f, 0f)
            if (isLobbyPose) Matrix.rotateM(bootOffset, 0, side * 14f, 0f, 1f, 0f)
            val bootWorld = FloatArray(16)
            Matrix.multiplyMM(bootWorld, 0, calfBase, 0, bootOffset, 0)
            drawPart(
                bootSculpted, bootWorld, bootTex,
                if (isCT) 0.45f else 1.0f,
                if (isCT) 0.46f else 0.95f,
                if (isCT) 0.50f else 0.90f,
                0.14f, if (isCT) 0.56f else 0.62f, 0f, 0f, 0f, 0.10f
            )
        }

        // --- ANATOMICAL TORSO + SCULPTED BALLISTIC PLATE CARRIER ---
        val torsoForwardLean = fwd * 7.5f
        val torsoStrafeBank = -strafe * 6.0f
        val torsoCounterTwist = if (isLobbyPose) -5f else ((fwdSin * abs(fwd) * -6.5f) + (strSin * abs(strafe) * 3.5f))

        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 1.00f + breath, 0f)
        Matrix.rotateM(partMat, 0, torsoForwardLean - flinchHit * 13f, 1f, 0f, 0f)
        Matrix.rotateM(partMat, 0, torsoStrafeBank, 0f, 0f, 1f)
        Matrix.rotateM(partMat, 0, torsoCounterTwist, 0f, 1f, 0f)
        Matrix.multiplyMM(torsoMat, 0, rootMat, 0, partMat, 0)

        // Base combat shirt / plaid shirt
        drawPart(anatomicalTorso, torsoMat, shirtTex, 1.0f * hitTintR, 1.0f, 1.0f, 0.04f, 0.85f, hitEmissiveR, 0f, 0f, 0.02f)

        // Contoured Kevlar / Cordura plate carrier vest
        drawPart(vestBody, torsoMat, vestTex, 1.0f * hitTintR, 1.0f, 1.0f, 0.08f, 0.78f, hitEmissiveR * 0.4f, 0f, 0f, 0.04f)

        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.24f, 0.132f)
        Matrix.multiplyMM(subMat, 0, torsoMat, 0, partMat, 0)
        drawPart(vestFrontPlate, subMat, vestTex, 1.06f * hitTintR, 1.06f, 1.06f, 0.10f, 0.74f, hitEmissiveR * 0.4f, 0f, 0f, 0.05f)

        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.24f, -0.130f)
        Matrix.multiplyMM(subMat, 0, torsoMat, 0, partMat, 0)
        drawPart(vestBackPlate, subMat, vestTex, 0.98f * hitTintR, 0.98f, 0.98f, 0.10f, 0.76f, 0f, 0f, 0f, 0.04f)

        for (side in intArrayOf(-1, 1)) {
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, side * 0.108f, 0.415f, 0f)
            Matrix.multiplyMM(subMat, 0, torsoMat, 0, partMat, 0)
            drawPart(vestShoulderStrap, subMat, vestTex, 0.94f, 0.94f, 0.94f, 0.08f, 0.80f, 0f, 0f, 0f, 0.03f)
        }

        // Front MOLLE Magazine Pouches
        for (p in -1..1) {
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, p * 0.078f, 0.145f, 0.162f)
            Matrix.multiplyMM(subMat, 0, torsoMat, 0, partMat, 0)
            drawPart(magPouch, subMat, vestTex, 0.90f, 0.90f, 0.90f, 0.08f, 0.82f, 0f, 0f, 0f, 0.03f)
        }

        if (isCT) {
            // CT Chest Tactical Radio + Whip Antenna
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, -0.142f, 0.30f, 0.148f)
            Matrix.multiplyMM(subMat, 0, torsoMat, 0, partMat, 0)
            drawPart(radioBox, subMat, MaterialTexture.TACTICAL_POLYMER, 0.38f, 0.40f, 0.44f, 0.30f, 0.55f, 0f, 0f, 0f, 0.10f)

            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, -0.156f, 0.45f, 0.148f)
            Matrix.multiplyMM(subMat, 0, torsoMat, 0, partMat, 0)
            drawPart(radioAntenna, subMat, MaterialTexture.GUNMETAL_STEEL, 0.30f, 0.32f, 0.35f, 0.65f, 0.40f, 0f, 0f, 0f, 0.25f)
        } else if (bot.hasBomb) {
            // C4 Bomb strapped visibly on the Terrorist carrier's back!
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, 0f, 0.25f, -0.175f)
            Matrix.multiplyMM(subMat, 0, torsoMat, 0, partMat, 0)
            drawPart(c4BackPack, subMat, MaterialTexture.C4_PACK, 1f, 1f, 1f, 0.15f, 0.65f, 0.35f, 0.05f, 0.02f, 0.08f)
        }

        // --- HEAD: CT (FAST HELMET + HEADSET + GOGGLES) vs T (KNITTED BALACLAVA + EYES/MOUTH) ---
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.46f, 0.008f)
        Matrix.rotateM(partMat, 0, -torsoForwardLean * 0.6f - flinchHit * 15f, 1f, 0f, 0f)
        Matrix.rotateM(partMat, 0, -torsoStrafeBank * 0.6f, 0f, 0f, 1f)
        Matrix.rotateM(partMat, 0, if (isLobbyPose) 8f else -torsoCounterTwist * 0.7f, 0f, 1f, 0f)
        Matrix.multiplyMM(headMat, 0, torsoMat, 0, partMat, 0)

        // Neck (covered by balaclava)
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.045f, 0f)
        Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
        drawPart(
            neckMesh, subMat,
            if (isCT) MaterialTexture.CT_UNIFORM else MaterialTexture.T_BALACLAVA,
            0.85f, 0.85f, 0.85f, 0.04f, 0.90f, 0f, 0f, 0f, 0.02f
        )

        // Head wrapped in dark balaclava (CT black/navy balaclava, T dark brown knitted ski-mask)
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.185f, 0.004f)
        Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
        drawPart(
            headCranium, subMat,
            if (isCT) MaterialTexture.TACTICAL_POLYMER else MaterialTexture.T_BALACLAVA,
            if (isCT) 0.55f * hitTintR else 1.0f * hitTintR,
            if (isCT) 0.58f else 0.98f,
            if (isCT) 0.62f else 0.96f,
            0.04f, 0.92f, hitEmissiveR * 0.4f, 0f, 0f, 0.02f
        )

        // Visible human skin eye cutout + dark eyes inside balaclava
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.194f, 0.082f)
        Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
        drawPart(eyePatch, subMat, MaterialTexture.SOLDIER_SKIN, 0.96f, 0.92f, 0.88f, 0.02f, 0.62f, 0f, 0f, 0f, 0.02f)

        for (side in intArrayOf(-1, 1)) {
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, side * 0.032f, 0.194f, 0.098f)
            Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
            drawPart(eyeBall, subMat, MaterialTexture.TACTICAL_POLYMER, 0.18f, 0.16f, 0.15f, 0.25f, 0.20f, 0f, 0f, 0f, 0.18f)
        }

        if (isCT) {
            // CT Sculpted Ballistic FAST Helmet + Side Rails + Tactical Headset + Dark Ballistic Goggles
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, 0f, 0.206f, -0.002f)
            Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
            drawPart(ctHelmetShell, subMat, MaterialTexture.VEST_CT, 0.95f, 1.0f, 1.08f, 0.18f, 0.58f, 0f, 0f, 0f, 0.12f)

            for (side in intArrayOf(-1, 1)) {
                Matrix.setIdentityM(partMat, 0)
                Matrix.translateM(partMat, 0, side * 0.106f, 0.222f, -0.002f)
                Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
                drawPart(ctHelmetRail, subMat, MaterialTexture.TACTICAL_POLYMER, 0.42f, 0.45f, 0.50f, 0.24f, 0.52f, 0f, 0f, 0f, 0.12f)

                Matrix.setIdentityM(partMat, 0)
                Matrix.translateM(partMat, 0, side * 0.104f, 0.174f, -0.006f)
                Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
                drawPart(ctHeadsetCup, subMat, MaterialTexture.TACTICAL_POLYMER, 0.36f, 0.38f, 0.42f, 0.28f, 0.48f, 0f, 0f, 0f, 0.14f)
            }

            // Matte dark ballistic goggles over CT eyes
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, 0f, 0.198f, 0.092f)
            Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
            drawPart(ctGogglesFrame, subMat, MaterialTexture.VISOR_GLASS, 0.26f, 0.32f, 0.42f, 0.72f, 0.16f, 0f, 0f, 0f, 0.65f)
        } else {
            // Terrorist ski-mask nose contour & mouth opening (exact CS2 Phoenix Terrorist look from screenshot!)
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, 0f, 0.178f, 0.100f)
            Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
            drawPart(noseMesh, subMat, MaterialTexture.T_BALACLAVA, 0.96f, 0.94f, 0.92f, 0.04f, 0.92f, 0f, 0f, 0f, 0.02f)

            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, 0f, 0.136f, 0.092f)
            Matrix.multiplyMM(subMat, 0, headMat, 0, partMat, 0)
            drawPart(mouthPatch, subMat, MaterialTexture.SOLDIER_SKIN, 0.88f, 0.80f, 0.74f, 0.02f, 0.68f, 0f, 0f, 0f, 0.02f)
        }

        // --- ARMS, HANDS & WEAPON (COMBAT STANCE vs CS2 LOBBY SHOWCASE POSE) ---
        val aimBounce = if (moveTotal > 0.1f) sin(bot.walkPhase * 2f) * 2.4f * moveTotal else 0f
        var rightHandWorld: FloatArray? = null

        for (side in intArrayOf(-1, 1)) {
            val sx = side * 0.222f
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, sx, 0.395f, 0f)
            Matrix.multiplyMM(subMat, 0, torsoMat, 0, partMat, 0)
            drawPart(shoulderJoint, subMat, shirtTex, 1f, 1f, 1f, 0.04f, 0.86f, 0f, 0f, 0f, 0.02f)

            val armBase = FloatArray(16)
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, sx, 0.385f, 0f)
            if (isLobbyPose) {
                // Natural relaxed CS2 lobby pose (right hand slightly out holding knife/weapon, left arm relaxed)
                val pitch = if (side == 1) -14f else -6f
                val roll = side * 11f
                Matrix.rotateM(partMat, 0, pitch, 1f, 0f, 0f)
                Matrix.rotateM(partMat, 0, roll, 0f, 0f, 1f)
            } else {
                Matrix.rotateM(partMat, 0, (if (side == 1) -52f else -58f) + aimBounce, 1f, 0f, 0f)
                Matrix.rotateM(partMat, 0, side * -18f, 0f, 0f, 1f)
            }
            Matrix.multiplyMM(armBase, 0, torsoMat, 0, partMat, 0)

            val uaOff = FloatArray(16)
            Matrix.setIdentityM(uaOff, 0)
            Matrix.translateM(uaOff, 0, 0f, -0.29f, 0f)
            Matrix.multiplyMM(subMat, 0, armBase, 0, uaOff, 0)
            drawPart(upperArm, subMat, shirtTex, 1f, 1f, 1f, 0.04f, 0.86f, 0f, 0f, 0f, 0.02f)

            val faPivot = FloatArray(16)
            Matrix.setIdentityM(faPivot, 0)
            Matrix.translateM(faPivot, 0, 0f, -0.28f, 0f)
            if (isLobbyPose) {
                Matrix.rotateM(faPivot, 0, if (side == 1) -28f else -10f, 1f, 0f, 0f)
                Matrix.rotateM(faPivot, 0, side * 6f, 0f, 0f, 1f)
            } else {
                Matrix.rotateM(faPivot, 0, -34f, 1f, 0f, 0f)
                Matrix.rotateM(faPivot, 0, side * 26f, 0f, 1f, 0f)
            }
            val faBase = FloatArray(16)
            Matrix.multiplyMM(faBase, 0, armBase, 0, faPivot, 0)

            val faOff = FloatArray(16)
            Matrix.setIdentityM(faOff, 0)
            Matrix.translateM(faOff, 0, 0f, -0.26f, 0f)
            Matrix.multiplyMM(subMat, 0, faBase, 0, faOff, 0)
            // Terrorist has rolled sleeves showing realistic muscular bare forearms; CT wears full BDU sleeves
            val forearmTex = if (isCT) MaterialTexture.CT_UNIFORM else MaterialTexture.SOLDIER_SKIN
            drawPart(
                forearm, subMat, forearmTex,
                if (isCT) 0.96f else 0.98f,
                if (isCT) 0.96f else 0.94f,
                if (isCT) 0.96f else 0.90f,
                0.04f, if (isCT) 0.86f else 0.62f, 0f, 0f, 0f, 0.02f
            )

            // Dark tactical gloves
            val handOff = FloatArray(16)
            Matrix.setIdentityM(handOff, 0)
            Matrix.translateM(handOff, 0, 0f, -0.27f, 0f)
            val handW = FloatArray(16)
            Matrix.multiplyMM(handW, 0, faBase, 0, handOff, 0)
            drawPart(handMesh, handW, MaterialTexture.TACTICAL_POLYMER, 0.38f, 0.36f, 0.34f, 0.10f, 0.72f, 0f, 0f, 0f, 0.04f)
            if (side == 1) rightHandWorld = handW
        }

        if (isLobbyPose && rightHandWorld != null) {
            if (bot.weapon == WeaponType.KNIFE) {
                // Holding tactical combat knife in right hand exactly like the user's attached CS2 screenshot!
                val kMat = FloatArray(16)
                Matrix.setIdentityM(partMat, 0)
                Matrix.translateM(partMat, 0, 0.02f, -0.02f, 0.07f)
                Matrix.rotateM(partMat, 0, 12f, 0f, 1f, 0f)
                Matrix.rotateM(partMat, 0, 90f, 1f, 0f, 0f)
                Matrix.multiplyMM(kMat, 0, rightHandWorld, 0, partMat, 0)
                drawPart(knifeGrip, kMat, MaterialTexture.TACTICAL_POLYMER, 0.34f, 0.34f, 0.36f, 0.16f, 0.68f, 0f, 0f, 0f, 0.08f)

                Matrix.setIdentityM(partMat, 0)
                Matrix.translateM(partMat, 0, 0.04f, -0.02f, 0.18f)
                Matrix.rotateM(partMat, 0, 16f, 0f, 1f, 0f)
                Matrix.multiplyMM(kMat, 0, rightHandWorld, 0, partMat, 0)
                drawPart(knifeBlade, kMat, MaterialTexture.CHROME_STEEL, 0.94f, 0.96f, 1.0f, 0.94f, 0.16f, 0f, 0f, 0f, 0.85f)
            } else {
                // Holding rifle cleanly across hip/chest in showcase
                drawThirdPersonRifle(isCT, bot, rightHandWorld, isLobby = true, drawPart = drawPart, drawFlame = drawFlame)
            }
        } else {
            drawThirdPersonRifle(isCT, bot, torsoMat, isLobby = false, drawPart = drawPart, drawFlame = drawFlame)
        }
    }

    private fun drawThirdPersonRifle(
        isCT: Boolean,
        bot: BotOperative,
        parentMat: FloatArray,
        isLobby: Boolean,
        drawPart: (GlMesh, FloatArray, MaterialTexture, Float, Float, Float, Float, Float, Float, Float, Float, Float) -> Unit,
        drawFlame: (GlMesh, FloatArray, Float) -> Unit
    ) {
        val weaponMat = FloatArray(16)
        Matrix.setIdentityM(partMat, 0)
        if (isLobby) {
            Matrix.translateM(partMat, 0, 0.04f, 0.01f, 0.14f)
            Matrix.rotateM(partMat, 0, 52f, 1f, 0f, 0f)
            Matrix.rotateM(partMat, 0, -24f, 0f, 1f, 0f)
        } else {
            Matrix.translateM(partMat, 0, 0.072f, 0.25f, 0.34f)
            Matrix.rotateM(partMat, 0, -6f, 0f, 1f, 0f)
        }
        Matrix.multiplyMM(weaponMat, 0, parentMat, 0, partMat, 0)

        // Beveled dark gunmetal receiver
        drawPart(rifleReceiver, weaponMat, MaterialTexture.GUNMETAL_STEEL, 0.72f, 0.74f, 0.78f, 0.86f, 0.28f, 0f, 0f, 0f, 0.55f)

        // Handguard & stock: dark oiled walnut ONLY for AK-47, matte black tactical polymer for CT/M4/SMG
        val furnitureTex = if (!isCT && bot.weapon == WeaponType.AK47) MaterialTexture.WALNUT_STOCK else MaterialTexture.TACTICAL_POLYMER
        val fCol = if (furnitureTex == MaterialTexture.WALNUT_STOCK) 1.0f else 0.52f

        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, -0.004f, 0.22f)
        Matrix.multiplyMM(subMat, 0, weaponMat, 0, partMat, 0)
        drawPart(rifleHandguard, subMat, furnitureTex, fCol, fCol * 0.98f, fCol * 0.96f, 0.12f, 0.54f, 0f, 0f, 0f, 0.16f)

        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, -0.022f, -0.26f)
        Matrix.multiplyMM(subMat, 0, weaponMat, 0, partMat, 0)
        drawPart(rifleStock, subMat, furnitureTex, fCol, fCol * 0.98f, fCol * 0.96f, 0.12f, 0.56f, 0f, 0f, 0f, 0.14f)

        // Magazine
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, -0.095f, 0.06f)
        Matrix.rotateM(partMat, 0, if (isCT) 8f else 20f, 1f, 0f, 0f)
        Matrix.multiplyMM(subMat, 0, weaponMat, 0, partMat, 0)
        drawPart(rifleMag, subMat, MaterialTexture.TACTICAL_POLYMER, 0.48f, 0.50f, 0.54f, 0.48f, 0.44f, 0f, 0f, 0f, 0.24f)

        // Cylindrical forged steel barrel / suppressor
        Matrix.setIdentityM(partMat, 0)
        Matrix.translateM(partMat, 0, 0f, 0.012f, 0.46f)
        Matrix.rotateM(partMat, 0, 90f, 1f, 0f, 0f)
        Matrix.multiplyMM(subMat, 0, weaponMat, 0, partMat, 0)
        drawPart(rifleBarrel, subMat, MaterialTexture.GUNMETAL_STEEL, 0.62f, 0.65f, 0.70f, 0.90f, 0.24f, 0f, 0f, 0f, 0.60f)

        // Additive volumetric real fire burst when bot shoots!
        if (!isLobby && bot.muzzleFlashTimer > 0.02f && !bot.isDead) {
            val fMat = FloatArray(16)
            Matrix.setIdentityM(partMat, 0)
            Matrix.translateM(partMat, 0, 0f, 0.012f, 0.64f)
            Matrix.rotateM(partMat, 0, 180f, 0f, 1f, 0f)
            Matrix.rotateM(partMat, 0, bot.muzzleFlashRoll, 0f, 0f, 1f)
            val s = 0.75f + bot.muzzleFlashTimer * 0.55f
            Matrix.scaleM(partMat, 0, s, s, s)
            Matrix.multiplyMM(fMat, 0, weaponMat, 0, partMat, 0)
            drawFlame(flameCards, fMat, bot.muzzleFlashTimer)
        }
    }

    fun release() {
        headCranium.release(); noseMesh.release(); eyePatch.release(); eyeBall.release()
        mouthPatch.release(); neckMesh.release()
        ctHelmetShell.release(); ctHelmetRail.release(); ctHeadsetCup.release(); ctGogglesFrame.release()
        anatomicalTorso.release(); vestBody.release(); vestFrontPlate.release(); vestBackPlate.release()
        vestShoulderStrap.release(); magPouch.release(); radioBox.release(); radioAntenna.release()
        pelvisMesh.release(); beltMesh.release(); beltBuckle.release(); sidePouch.release()
        shoulderJoint.release(); upperArm.release(); forearm.release(); handMesh.release()
        thigh.release(); cargoPocket.release(); kneePad.release(); calf.release(); bootSculpted.release()
        rifleReceiver.release(); rifleHandguard.release(); rifleStock.release()
        rifleBarrel.release(); rifleMag.release(); knifeBlade.release(); knifeGrip.release()
        c4BackPack.release(); flameCards.release()
    }
}
