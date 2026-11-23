package com.example.engine

import android.opengl.Matrix
import com.example.model.WeaponType
import com.google.android.filament.Engine
import com.google.android.filament.gltfio.FilamentAsset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tracks per-operative turn-in-place yaw delta so stationary characters smoothly shuffle
 * and step their feet in BOTH left (CCW) and right (CW) turning directions.
 */
class TurnShuffleState {
    var initialized: Boolean = false
    var lastYawDeg: Float = 0f
    var smoothedYawRate: Float = 0f
    var shuffleBlend: Float = 0f
    var shufflePhase: Float = 0f
    var turnDir: Float = 1f // +1f = turning left (CCW), -1f = turning right (CW)

    fun reset() {
        initialized = false
        smoothedYawRate = 0f
        shuffleBlend = 0f
        shufflePhase = 0f
    }

    fun update(currentYawDeg: Float, dt: Float, isMoving: Boolean, isDead: Boolean) {
        if (isDead) {
            shuffleBlend = 0f
            smoothedYawRate = 0f
            initialized = false
            return
        }
        if (!initialized) {
            lastYawDeg = currentYawDeg
            initialized = true
            return
        }
        val safeDt = dt.coerceIn(0.002f, 0.05f)
        var deltaDeg = (currentYawDeg - lastYawDeg) % 360f
        if (deltaDeg > 180f) deltaDeg -= 360f
        if (deltaDeg < -180f) deltaDeg += 360f
        lastYawDeg = currentYawDeg

        if (isMoving) {
            // While walking/running, full locomotion cycle drives legs; fade turn shuffle out cleanly
            smoothedYawRate *= (1f - (safeDt * 12f).coerceAtMost(1f))
            shuffleBlend = (shuffleBlend - safeDt * 7.5f).coerceAtLeast(0f)
            return
        }

        val instantRate = (deltaDeg / safeDt).coerceIn(-480f, 480f)
        val rateAlpha = (safeDt * 14f).coerceAtMost(1f)
        smoothedYawRate += (instantRate - smoothedYawRate) * rateAlpha

        val absRate = abs(smoothedYawRate)
        if (absRate > 10f) {
            turnDir = if (smoothedYawRate >= 0f) 1f else -1f
            val targetBlend = ((absRate - 10f) / 75f).coerceIn(0.32f, 1.0f)
            shuffleBlend += (targetBlend - shuffleBlend) * (safeDt * 12f).coerceAtMost(1f)
            // Advance step cadence proportional to turn speed so slow & fast spins both look natural
            val cadence = (abs(deltaDeg) * 0.115f) + safeDt * (4.2f + (absRate / 180f).coerceAtMost(1.2f) * 3.2f)
            shufflePhase = (shufflePhase + cadence) % (PI.toFloat() * 200f)
        } else {
            shuffleBlend = (shuffleBlend - safeDt * 6.5f).coerceAtLeast(0f)
        }
    }
}

/**
 * Cached Filament joint entity handles for char_terrorist.glb & char_ct.glb.
 */
class OperativeRigHandles(asset: FilamentAsset) {
    val pelvis: Int = asset.getFirstEntityByName("Pelvis")
    val spine: Int = asset.getFirstEntityByName("Spine")
    val head: Int = asset.getFirstEntityByName("Head")
    val lShoulder: Int = asset.getFirstEntityByName("L_Shoulder")
    val lElbow: Int = asset.getFirstEntityByName("L_Elbow")
    val rShoulder: Int = asset.getFirstEntityByName("R_Shoulder")
    val rElbow: Int = asset.getFirstEntityByName("R_Elbow")
    val lHip: Int = asset.getFirstEntityByName("L_Hip")
    val lKnee: Int = asset.getFirstEntityByName("L_Knee")
    val rHip: Int = asset.getFirstEntityByName("R_Hip")
    val rKnee: Int = asset.getFirstEntityByName("R_Knee")

    val hasFullHumanoidRig: Boolean =
        pelvis != 0 && spine != 0 &&
            lShoulder != 0 && lElbow != 0 &&
            rShoulder != 0 && rElbow != 0 &&
            lHip != 0 && lKnee != 0 &&
            rHip != 0 && rKnee != 0
}

/**
 * Character-space third-person weapon pose + exact palm grip coordinates for BOTH hands!
 * Character faces +Z natively (+Y up, +X left, -X right).
 */
data class HeldWeaponSpec(
    val weaponX: Float,
    val weaponY: Float,
    val weaponZ: Float,
    val weaponYawDeg: Float,   // Added on top of 180° (+Z barrel alignment)
    val weaponPitchDeg: Float,
    val weaponRollDeg: Float,
    val weaponScale: Float,
    val rightHandX: Float,
    val rightHandY: Float,
    val rightHandZ: Float,
    val leftHandX: Float,
    val leftHandY: Float,
    val leftHandZ: Float,
    val spineTwistYawDeg: Float
)

/**
 * Exact zero-allocation procedural skeletal overlay for third-person operatives:
 * 1. Turn-in-place foot shuffling (both left & right directions) with knee bend & directional foot pivot.
 * 2. Exact 2-bone analytical IK for BOTH arms so Right Hand grips the weapon handle AND Left Hand
 *    supports the foregrip / handguard / pistol grip directly — visible to all enemies & teammates!
 * 3. Synchronized 3rd-person weapon transform locked directly inside both hands.
 */
class OperativeRigAnimator {

    // Exact bind-pose joint offsets measured directly from char_terrorist.glb & char_ct.glb
    companion object {
        private const val L_SHOULDER_X = 0.22f
        private const val R_SHOULDER_X = -0.22f
        private const val SHOULDER_Y = 0.24f

        // Upper arm rest vector from Shoulder -> Elbow
        private const val L_UPPER_X = 0.045f
        private const val R_UPPER_X = -0.045f
        private const val UPPER_Y = -0.290f
        private const val UPPER_Z = 0.020f
        private val UPPER_LEN = sqrt(L_UPPER_X * L_UPPER_X + UPPER_Y * UPPER_Y + UPPER_Z * UPPER_Z)

        // Forearm rest vector from Elbow -> Palm center
        private const val FORE_X = 0.0f
        private const val FORE_Y = -0.312f
        private const val FORE_Z = 0.040f
        private val FORE_LEN = sqrt(FORE_X * FORE_X + FORE_Y * FORE_Y + FORE_Z * FORE_Z)

        private val L_U0_X = L_UPPER_X / UPPER_LEN
        private val L_U0_Y = UPPER_Y / UPPER_LEN
        private val L_U0_Z = UPPER_Z / UPPER_LEN

        private val R_U0_X = R_UPPER_X / UPPER_LEN
        private val R_U0_Y = UPPER_Y / UPPER_LEN
        private val R_U0_Z = UPPER_Z / UPPER_LEN

        private val F0_X = FORE_X / FORE_LEN
        private val F0_Y = FORE_Y / FORE_LEN
        private val F0_Z = FORE_Z / FORE_LEN

        fun getHeldWeaponSpec(weapon: WeaponType): HeldWeaponSpec = when (weapon) {
            WeaponType.AWP -> HeldWeaponSpec(
                weaponX = -0.14f, weaponY = 1.24f, weaponZ = 0.26f,
                weaponYawDeg = 13f, weaponPitchDeg = -2.5f, weaponRollDeg = 3f, weaponScale = 1.04f,
                // Right palm directly on AWP pistol grip; Left palm directly under front chassis/forend
                rightHandX = -0.14f, rightHandY = 1.20f, rightHandZ = 0.26f,
                leftHandX = -0.07f, leftHandY = 1.21f, leftHandZ = 0.50f,
                spineTwistYawDeg = -11f
            )
            WeaponType.AK47, WeaponType.M4A1_S -> HeldWeaponSpec(
                weaponX = -0.14f, weaponY = 1.24f, weaponZ = 0.27f,
                weaponYawDeg = 14f, weaponPitchDeg = -2.0f, weaponRollDeg = 3f, weaponScale = 1.06f,
                // Right palm directly on pistol grip; Left palm directly gripping wooden/rail handguard!
                rightHandX = -0.14f, rightHandY = 1.195f, rightHandZ = 0.265f,
                leftHandX = -0.07f, leftHandY = 1.215f, leftHandZ = 0.495f,
                spineTwistYawDeg = -12f
            )
            WeaponType.P90, WeaponType.MP9 -> HeldWeaponSpec(
                weaponX = -0.12f, weaponY = 1.24f, weaponZ = 0.29f,
                weaponYawDeg = 12f, weaponPitchDeg = -2.0f, weaponRollDeg = 3f, weaponScale = 1.04f,
                // Right palm on rear grip; Left palm on front foregrip/shroud
                rightHandX = -0.12f, rightHandY = 1.20f, rightHandZ = 0.285f,
                leftHandX = -0.06f, leftHandY = 1.215f, leftHandZ = 0.455f,
                spineTwistYawDeg = -10f
            )
            WeaponType.USP_S, WeaponType.GLOCK_18, WeaponType.DESERT_EAGLE -> HeldWeaponSpec(
                weaponX = -0.03f, weaponY = 1.29f, weaponZ = 0.43f,
                weaponYawDeg = 3f, weaponPitchDeg = -1.5f, weaponRollDeg = 0f, weaponScale = 1.08f,
                // Classic two-handed tactical pistol grip: both palms locked together around grip!
                rightHandX = -0.045f, rightHandY = 1.25f, rightHandZ = 0.415f,
                leftHandX = 0.010f, leftHandY = 1.245f, leftHandZ = 0.425f,
                spineTwistYawDeg = -4f
            )
            WeaponType.C4_BOMB -> HeldWeaponSpec(
                weaponX = 0.0f, weaponY = 1.19f, weaponZ = 0.34f,
                weaponYawDeg = 0f, weaponPitchDeg = -18f, weaponRollDeg = 0f, weaponScale = 0.92f,
                rightHandX = -0.085f, rightHandY = 1.18f, rightHandZ = 0.33f,
                leftHandX = 0.085f, leftHandY = 1.18f, leftHandZ = 0.33f,
                spineTwistYawDeg = 0f
            )
            WeaponType.KNIFE -> HeldWeaponSpec(
                weaponX = -0.15f, weaponY = 1.21f, weaponZ = 0.37f,
                weaponYawDeg = 8f, weaponPitchDeg = -6f, weaponRollDeg = 12f, weaponScale = 1.06f,
                rightHandX = -0.15f, rightHandY = 1.20f, rightHandZ = 0.36f,
                leftHandX = 0.14f, leftHandY = 1.21f, leftHandZ = 0.31f,
                spineTwistYawDeg = -6f
            )
        }

        private fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    }

    // Reusable scratch matrices & vectors (zero GC allocations per frame!)
    private val mPelvis = FloatArray(16)
    private val mSpine = FloatArray(16)
    private val mPelvisSpine = FloatArray(16)
    private val mInvSpine = FloatArray(16)
    private val mJoint = FloatArray(16)
    private val mRot = FloatArray(16)
    private val mTmp = FloatArray(16)
    private val mQuat = FloatArray(16)
    private val vIn = FloatArray(4)
    private val vOut = FloatArray(4)

    // Reusable quaternion buffers (x, y, z, w)
    private val qUpper = FloatArray(4)
    private val qUpperConj = FloatArray(4)
    private val qElbow = FloatArray(4)

    /**
     * Applies procedural skeletal overlays (Turn-in-Place foot shuffling + Two-Handed Weapon Arm IK)
     * AFTER `animator.applyAnimation(...)` and BEFORE `animator.updateBoneMatrices()`.
     */
    fun applyProceduralPose(
        engine: Engine,
        handles: OperativeRigHandles,
        weapon: WeaponType,
        turnState: TurnShuffleState,
        timeSec: Float,
        isMoving: Boolean,
        isDead: Boolean,
        deathProgress: Float,
        isShooting: Boolean
    ) {
        if (!handles.hasFullHumanoidRig) return
        val tm = engine.transformManager
        val armIkWeight = if (isDead) 0f else 1f
        val spec = getHeldWeaponSpec(weapon)

        // 1. Read current animated Pelvis & Spine transforms
        val pelvisInst = tm.getInstance(handles.pelvis)
        val spineInst = tm.getInstance(handles.spine)
        tm.getTransform(pelvisInst, mPelvis)
        tm.getTransform(spineInst, mSpine)

        // 2. Apply Turn-in-Place Foot Shuffling when stationary & rotating in EITHER direction!
        val blend = if (isDead) 0f else turnState.shuffleBlend
        if (blend > 0.01f) {
            val phase = turnState.shufflePhase
            val dir = turnState.turnDir // +1 left (CCW), -1 right (CW)
            val s = sin(phase)
            val c = cos(phase)
            val lLift = max(0f, s) * blend
            val rLift = max(0f, -s) * blend

            // Subtle pelvis weight-shift bob + hip yaw oscillation
            Matrix.setIdentityM(mRot, 0)
            Matrix.translateM(mRot, 0, s * 0.016f * blend, abs(s) * 0.020f * blend, 0f)
            Matrix.rotateM(mRot, 0, -dir * 6.5f * c * blend, 0f, 1f, 0f)
            Matrix.rotateM(mRot, 0, s * 2.8f * blend, 0f, 0f, 1f)
            Matrix.multiplyMM(mTmp, 0, mPelvis, 0, mRot, 0)
            System.arraycopy(mTmp, 0, mPelvis, 0, 16)
            tm.setTransform(pelvisInst, mPelvis)

            // Counter-rotate Spine so upper body stays focused toward aim target while legs step underneath
            Matrix.setIdentityM(mRot, 0)
            Matrix.rotateM(mRot, 0, dir * 7.5f * c * blend, 0f, 1f, 0f)
            Matrix.multiplyMM(mTmp, 0, mSpine, 0, mRot, 0)
            System.arraycopy(mTmp, 0, mSpine, 0, 16)

            // Left leg step: lift thigh forward, pivot foot in turn direction, flex knee!
            applyRelativeRotation(
                tm, handles.lHip,
                pitchDeg = -25f * lLift + 5f * rLift,
                yawDeg = dir * 18f * s * blend,
                rollDeg = dir * 7.5f * lLift
            )
            applyRelativeRotation(
                tm, handles.lKnee,
                pitchDeg = 46f * lLift,
                yawDeg = 0f,
                rollDeg = 0f
            )

            // Right leg step (alternating 180° out of phase):
            applyRelativeRotation(
                tm, handles.rHip,
                pitchDeg = -25f * rLift + 5f * lLift,
                yawDeg = dir * 18f * (-s) * blend,
                rollDeg = -dir * 7.5f * rLift
            )
            applyRelativeRotation(
                tm, handles.rKnee,
                pitchDeg = 46f * rLift,
                yawDeg = 0f,
                rollDeg = 0f
            )
        }

        // 3. Apply tactical torso combat angle + Exact Two-Handed Arm IK!
        if (armIkWeight > 0.02f) {
            // Subtle tactical stance twist on Spine (angled shoulder for rifles, square for pistols)
            val twistDeg = spec.spineTwistYawDeg * armIkWeight
            if (abs(twistDeg) > 0.1f) {
                Matrix.setIdentityM(mRot, 0)
                Matrix.rotateM(mRot, 0, twistDeg, 0f, 1f, 0f)
                Matrix.multiplyMM(mTmp, 0, mSpine, 0, mRot, 0)
                System.arraycopy(mTmp, 0, mSpine, 0, 16)
            }
            tm.setTransform(spineInst, mSpine)

            // Keep Head looking straight forward along character +Z even when torso twists!
            if (handles.head != 0 && abs(twistDeg) > 0.1f) {
                applyRelativeRotation(tm, handles.head, pitchDeg = 0f, yawDeg = -twistDeg * 0.85f, rollDeg = 0f)
            }

            // Compute Spine transform in Character space so IK targets stay 100% locked during walk/run/turn!
            Matrix.multiplyMM(mPelvisSpine, 0, mPelvis, 0, mSpine, 0)
            val inverted = Matrix.invertM(mInvSpine, 0, mPelvisSpine, 0)
            if (inverted) {
                val bobY = computeWeaponBobY(timeSec, isMoving)
                val recoilZ = if (isShooting && !isDead) -0.032f else 0f

                // Transform Right Hand target into Spine-local space
                vIn[0] = spec.rightHandX
                vIn[1] = spec.rightHandY + bobY
                vIn[2] = spec.rightHandZ + recoilZ
                vIn[3] = 1f
                Matrix.multiplyMV(vOut, 0, mInvSpine, 0, vIn, 0)
                solveAndApplyTwoBoneArmIk(
                    tm = tm,
                    shoulderEntity = handles.rShoulder,
                    elbowEntity = handles.rElbow,
                    sx = R_SHOULDER_X, sy = SHOULDER_Y, sz = 0f,
                    hx = vOut[0], hy = vOut[1], hz = vOut[2],
                    // Right elbow points comfortably outward-right & downward
                    poleX = -0.68f, poleY = -0.72f, poleZ = -0.14f,
                    u0x = R_U0_X, u0y = R_U0_Y, u0z = R_U0_Z,
                    elbowOffsetX = R_UPPER_X, elbowOffsetY = UPPER_Y, elbowOffsetZ = UPPER_Z
                )

                // Transform Left Hand target into Spine-local space
                vIn[0] = spec.leftHandX
                vIn[1] = spec.leftHandY + bobY
                vIn[2] = spec.leftHandZ + recoilZ
                vIn[3] = 1f
                Matrix.multiplyMV(vOut, 0, mInvSpine, 0, vIn, 0)
                solveAndApplyTwoBoneArmIk(
                    tm = tm,
                    shoulderEntity = handles.lShoulder,
                    elbowEntity = handles.lElbow,
                    sx = L_SHOULDER_X, sy = SHOULDER_Y, sz = 0f,
                    hx = vOut[0], hy = vOut[1], hz = vOut[2],
                    // Left elbow drops naturally downward-left underneath the weapon handguard!
                    poleX = 0.56f, poleY = -0.82f, poleZ = -0.08f,
                    u0x = L_U0_X, u0y = L_U0_Y, u0z = L_U0_Z,
                    elbowOffsetX = L_UPPER_X, elbowOffsetY = UPPER_Y, elbowOffsetZ = UPPER_Z
                )
            }
        }
    }

    /**
     * Computes exact synchronized world transform for the third-person weapon so it sits
     * directly inside BOTH hands of the operative (and drops smoothly to the ground on death).
     */
    fun buildHeldWeaponMatrix(
        out: FloatArray,
        weapon: WeaponType,
        charX: Float,
        charY: Float,
        charZ: Float,
        charYawDeg: Float,
        timeSec: Float,
        isMoving: Boolean,
        deathProgress: Float,
        isShooting: Boolean
    ) {
        val spec = getHeldWeaponSpec(weapon)
        val dp = deathProgress.coerceIn(0f, 1f)
        val drop = dp * dp * (3f - 2f * dp)

        val bobY = if (dp < 0.05f) computeWeaponBobY(timeSec, isMoving) else 0f
        val recoilZ = if (isShooting && dp < 0.05f) -0.032f else 0f

        val localX = lerpF(spec.weaponX, -0.44f, drop)
        val localY = lerpF(spec.weaponY + bobY, 0.06f, drop)
        val localZ = lerpF(spec.weaponZ + recoilZ, 0.14f, drop)

        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, charX, charY, charZ)
        Matrix.rotateM(out, 0, charYawDeg, 0f, 1f, 0f)
        Matrix.translateM(out, 0, localX, localY, localZ)
        // Bundled weapons have barrel along -Z; rotate 180° around Y to point straight along character +Z!
        Matrix.rotateM(out, 0, 180f + lerpF(spec.weaponYawDeg, 36f, drop), 0f, 1f, 0f)
        val pitchKick = if (isShooting && dp < 0.05f) 4.2f else 0f
        Matrix.rotateM(out, 0, lerpF(spec.weaponPitchDeg + pitchKick, 0f, drop), 1f, 0f, 0f)
        Matrix.rotateM(out, 0, lerpF(spec.weaponRollDeg, 88f, drop), 0f, 0f, 1f)
        Matrix.scaleM(out, 0, spec.weaponScale, spec.weaponScale, spec.weaponScale)
    }

    private fun computeWeaponBobY(timeSec: Float, isMoving: Boolean): Float {
        return if (isMoving) {
            sin(timeSec * 10.5f) * 0.012f
        } else {
            sin(timeSec * 2.1f) * 0.005f
        }
    }

    private fun applyRelativeRotation(
        tm: com.google.android.filament.TransformManager,
        entity: Int,
        pitchDeg: Float,
        yawDeg: Float,
        rollDeg: Float
    ) {
        val inst = tm.getInstance(entity)
        if (inst == 0) return
        tm.getTransform(inst, mJoint)
        Matrix.setIdentityM(mRot, 0)
        if (yawDeg != 0f) Matrix.rotateM(mRot, 0, yawDeg, 0f, 1f, 0f)
        if (pitchDeg != 0f) Matrix.rotateM(mRot, 0, pitchDeg, 1f, 0f, 0f)
        if (rollDeg != 0f) Matrix.rotateM(mRot, 0, rollDeg, 0f, 0f, 1f)
        Matrix.multiplyMM(mTmp, 0, mJoint, 0, mRot, 0)
        tm.setTransform(inst, mTmp)
    }

    /**
     * Exact analytical 2-bone IK solver (Law of Cosines + Pole Vector):
     * Places the palm at (hx, hy, hz) in Spine-local space with zero arm inversion or drift.
     */
    private fun solveAndApplyTwoBoneArmIk(
        tm: com.google.android.filament.TransformManager,
        shoulderEntity: Int,
        elbowEntity: Int,
        sx: Float, sy: Float, sz: Float,
        hx: Float, hy: Float, hz: Float,
        poleX: Float, poleY: Float, poleZ: Float,
        u0x: Float, u0y: Float, u0z: Float,
        elbowOffsetX: Float, elbowOffsetY: Float, elbowOffsetZ: Float
    ) {
        val sInst = tm.getInstance(shoulderEntity)
        val eInst = tm.getInstance(elbowEntity)
        if (sInst == 0 || eInst == 0) return

        val dx = hx - sx
        val dy = hy - sy
        val dz = hz - sz
        val rawDist = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.05f)
        val maxReach = (UPPER_LEN + FORE_LEN) * 0.992f
        val minReach = abs(UPPER_LEN - FORE_LEN) + 0.04f
        val d = rawDist.coerceIn(minReach, maxReach)

        val dirX = dx / rawDist
        val dirY = dy / rawDist
        val dirZ = dz / rawDist

        // Project pole vector perpendicular to shoulder->hand direction to define natural elbow bend plane
        val poleDot = poleX * dirX + poleY * dirY + poleZ * dirZ
        var bendX = poleX - dirX * poleDot
        var bendY = poleY - dirY * poleDot
        var bendZ = poleZ - dirZ * poleDot
        val bendLen = sqrt(bendX * bendX + bendY * bendY + bendZ * bendZ)
        if (bendLen > 1e-4f) {
            bendX /= bendLen; bendY /= bendLen; bendZ /= bendLen
        } else {
            bendX = 0f; bendY = -1f; bendZ = 0f
        }

        // Law of cosines: distance along shoulder->hand axis and perpendicular elbow height
        val cosA = ((UPPER_LEN * UPPER_LEN + d * d - FORE_LEN * FORE_LEN) / (2f * UPPER_LEN * d)).coerceIn(-1f, 1f)
        val sinA = sqrt((1f - cosA * cosA).coerceAtLeast(0f))

        val along = UPPER_LEN * cosA
        val perp = UPPER_LEN * sinA

        // Solved Elbow world position in Spine-local space
        val ex = sx + dirX * along + bendX * perp
        val ey = sy + dirY * along + bendY * perp
        val ez = sz + dirZ * along + bendZ * perp

        // Upper arm desired unit direction (Shoulder -> Elbow)
        val uLen = sqrt((ex - sx) * (ex - sx) + (ey - sy) * (ey - sy) + (ez - sz) * (ez - sz)).coerceAtLeast(1e-4f)
        val uTx = (ex - sx) / uLen
        val uTy = (ey - sy) / uLen
        val uTz = (ez - sz) / uLen

        // Forearm desired unit direction (Elbow -> Clamped Hand)
        val targetHx = sx + dirX * d
        val targetHy = sy + dirY * d
        val targetHz = sz + dirZ * d
        val fLen = sqrt(
            (targetHx - ex) * (targetHx - ex) +
                (targetHy - ey) * (targetHy - ey) +
                (targetHz - ez) * (targetHz - ez)
        ).coerceAtLeast(1e-4f)
        val fTx = (targetHx - ex) / fLen
        val fTy = (targetHy - ey) / fLen
        val fTz = (targetHz - ez) / fLen

        // 1. Shoulder rotation quaternion: rotates rest upper arm u0 -> uT
        quatFromTo(u0x, u0y, u0z, uTx, uTy, uTz, qUpper)

        // 2. Elbow local rotation quaternion: rotates rest forearm f0 -> qUpper^-1 * fT
        qUpperConj[0] = -qUpper[0]
        qUpperConj[1] = -qUpper[1]
        qUpperConj[2] = -qUpper[2]
        qUpperConj[3] = qUpper[3]
        rotateVecByQuat(qUpperConj, fTx, fTy, fTz, vOut)
        quatFromTo(F0_X, F0_Y, F0_Z, vOut[0], vOut[1], vOut[2], qElbow)

        // Write Shoulder local transform: T(sx, sy, sz) * R(qUpper)
        quatToMatrix(qUpper, mQuat)
        Matrix.setIdentityM(mJoint, 0)
        Matrix.translateM(mJoint, 0, sx, sy, sz)
        Matrix.multiplyMM(mTmp, 0, mJoint, 0, mQuat, 0)
        tm.setTransform(sInst, mTmp)

        // Write Elbow local transform: T(elbowOffset) * R(qElbow)
        quatToMatrix(qElbow, mQuat)
        Matrix.setIdentityM(mJoint, 0)
        Matrix.translateM(mJoint, 0, elbowOffsetX, elbowOffsetY, elbowOffsetZ)
        Matrix.multiplyMM(mTmp, 0, mJoint, 0, mQuat, 0)
        tm.setTransform(eInst, mTmp)
    }

    private fun quatFromTo(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        outQ: FloatArray
    ) {
        val dot = (ax * bx + ay * by + az * bz).coerceIn(-1f, 1f)
        if (dot < -0.9995f) {
            // 180° opposite: pick orthogonal axis
            var ox = -ay
            var oy = ax
            var oz = 0f
            val len = sqrt(ox * ox + oy * oy)
            if (len < 1e-4f) {
                ox = 0f; oy = -az; oz = ay
            }
            val n = sqrt(ox * ox + oy * oy + oz * oz).coerceAtLeast(1e-4f)
            outQ[0] = ox / n; outQ[1] = oy / n; outQ[2] = oz / n; outQ[3] = 0f
            return
        }
        val cx = ay * bz - az * by
        val cy = az * bx - ax * bz
        val cz = ax * by - ay * bx
        val w = 1f + dot
        val inv = 1f / sqrt(cx * cx + cy * cy + cz * cz + w * w).coerceAtLeast(1e-6f)
        outQ[0] = cx * inv
        outQ[1] = cy * inv
        outQ[2] = cz * inv
        outQ[3] = w * inv
    }

    private fun rotateVecByQuat(q: FloatArray, vx: Float, vy: Float, vz: Float, outV: FloatArray) {
        val qx = q[0]; val qy = q[1]; val qz = q[2]; val qw = q[3]
        // t = 2 * cross(q.xyz, v)
        val tx = 2f * (qy * vz - qz * vy)
        val ty = 2f * (qz * vx - qx * vz)
        val tz = 2f * (qx * vy - qy * vx)
        // v' = v + qw * t + cross(q.xyz, t)
        outV[0] = vx + qw * tx + (qy * tz - qz * ty)
        outV[1] = vy + qw * ty + (qz * tx - qx * tz)
        outV[2] = vz + qw * tz + (qx * ty - qy * tx)
    }

    private fun quatToMatrix(q: FloatArray, outM: FloatArray) {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z

        // OpenGL / Filament column-major 4x4 rotation matrix
        outM[0] = 1f - 2f * (yy + zz)
        outM[1] = 2f * (xy + wz)
        outM[2] = 2f * (xz - wy)
        outM[3] = 0f

        outM[4] = 2f * (xy - wz)
        outM[5] = 1f - 2f * (xx + zz)
        outM[6] = 2f * (yz + wx)
        outM[7] = 0f

        outM[8] = 2f * (xz + wy)
        outM[9] = 2f * (yz - wx)
        outM[10] = 1f - 2f * (xx + yy)
        outM[11] = 0f

        outM[12] = 0f
        outM[13] = 0f
        outM[14] = 0f
        outM[15] = 1f
    }
}
