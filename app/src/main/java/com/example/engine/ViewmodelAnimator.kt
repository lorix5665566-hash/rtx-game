package com.example.engine

import com.example.model.WeaponType
import kotlin.math.PI
import kotlin.math.sin

/**
 * First-person viewmodel pose offsets in camera-local meters and degrees.
 * Positive X = right, Positive Y = up, Negative Z = forward away from camera.
 */
data class ViewmodelPoseDelta(
    var tx: Float = 0f,
    var ty: Float = 0f,
    var tz: Float = 0f,
    var yawDeg: Float = 0f,
    var pitchDeg: Float = 0f,
    var rollDeg: Float = 0f,
    // Subtle extra follow-through for the first-person hands rig (mag swap / bolt pull feel)
    var handTx: Float = 0f,
    var handTy: Float = 0f,
    var handTz: Float = 0f,
    var handPitchDeg: Float = 0f,
    var handRollDeg: Float = 0f
) {
    fun reset() {
        tx = 0f; ty = 0f; tz = 0f
        yawDeg = 0f; pitchDeg = 0f; rollDeg = 0f
        handTx = 0f; handTy = 0f; handTz = 0f
        handPitchDeg = 0f; handRollDeg = 0f
    }
}

/**
 * Procedural CS2 / Standoff-grade first-person animation synthesizer:
 * - Distinct multi-stage reload animations for EVERY reloadable weapon
 *   (AK-47 rock-and-lock + charging pull, M4A1-S mag drop + bolt slap, AWP heavy mag + 4-step bolt cycle,
 *    P90 top-mag unlatch + downward slap, MP9 fast grip reload, USP-S / Glock-18 / Deagle tactical slide rack)
 * - Smooth multi-stage weapon inspection animations for EVERY weapon including Knife 360° flip & C4 inspect.
 */
object ViewmodelAnimator {

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Smooth bell pulse peaking at 1.0 in the center of [start..end]. */
    private fun pulse(p: Float, start: Float, end: Float): Float {
        if (p <= start || p >= end) return 0f
        val u = (p - start) / (end - start)
        return sin(u * PI).toFloat().coerceAtLeast(0f)
    }

    /** Trapezoid envelope: ramps 0->1 over [inStart..inEnd], holds 1, ramps 1->0 over [outStart..outEnd]. */
    private fun plateau(p: Float, inStart: Float, inEnd: Float, outStart: Float, outEnd: Float): Float {
        return smoothstep(inStart, inEnd, p) * (1f - smoothstep(outStart, outEnd, p))
    }

    fun inspectDurationSec(weapon: WeaponType): Float = when (weapon) {
        WeaponType.KNIFE -> 2.45f
        WeaponType.GLOCK_18, WeaponType.USP_S -> 3.05f
        WeaponType.DESERT_EAGLE -> 3.25f
        WeaponType.MP9, WeaponType.P90 -> 3.30f
        WeaponType.AK47, WeaponType.M4A1_S -> 3.60f
        WeaponType.AWP -> 3.85f
        WeaponType.C4_BOMB -> 3.00f
    }

    /**
     * Evaluates the multi-stage reload pose for [weapon] at normalized reload progress [p] in [0..1].
     */
    fun computeReloadPose(weapon: WeaponType, pRaw: Float, out: ViewmodelPoseDelta) {
        out.reset()
        val p = pRaw.coerceIn(0f, 1f)
        if (p <= 0.001f || p >= 0.999f) return

        when (weapon) {
            WeaponType.AK47 -> {
                // Phase 1: Cant rifle sharp left & raise barrel to expose rock-and-lock magwell
                val hold = plateau(p, 0.00f, 0.15f, 0.84f, 0.99f)
                // Phase 2: Rock out empty banana mag (crisp downward-forward strike)
                val magOut = pulse(p, 0.18f, 0.38f)
                // Phase 3: Bring new mag up & lock in with authoritative upward clack
                val magDip = plateau(p, 0.34f, 0.44f, 0.56f, 0.65f)
                val magLock = pulse(p, 0.56f, 0.70f)
                // Phase 4: Tilt right and rack right-side charging handle back & release
                val boltRack = pulse(p, 0.72f, 0.90f)
                val boltSnap = pulse(p, 0.83f, 0.92f)

                out.tx = -0.045f * hold + 0.024f * boltRack
                out.ty = -0.045f * hold - 0.055f * magOut - 0.032f * magDip + 0.036f * magLock - 0.015f * boltRack
                out.tz = 0.032f * hold + 0.052f * boltRack - 0.016f * boltSnap
                out.yawDeg = -17f * hold + 24f * boltRack
                out.pitchDeg = 14f * hold - 12f * magOut + 11f * magLock + 7f * boltRack - 6f * boltSnap
                out.rollDeg = 34f * hold + 9f * magDip - 48f * boltRack

                // Left hand drops to swap mag, then reaches over receiver to rack charging handle
                out.handTy = -0.045f * magOut - 0.038f * magDip + 0.025f * magLock
                out.handTz = 0.028f * boltRack
                out.handRollDeg = -10f * magOut + 12f * boltRack
            }

            WeaponType.M4A1_S -> {
                // Phase 1: Clean tactical inward cant pressing mag release
                val hold = plateau(p, 0.00f, 0.14f, 0.84f, 0.99f)
                // Phase 2: Straight STANAG mag drops out
                val magDrop = pulse(p, 0.16f, 0.34f)
                // Phase 3: Fresh mag slides up & seats firmly
                val magSeat = pulse(p, 0.44f, 0.62f)
                // Phase 4: Crisp left-side bolt-catch SLAP!
                val boltSlapPrep = pulse(p, 0.64f, 0.78f)
                val boltSlapHit = pulse(p, 0.74f, 0.86f)

                out.tx = -0.038f * hold + 0.026f * boltSlapHit
                out.ty = -0.036f * hold - 0.042f * magDrop + 0.044f * magSeat - 0.010f * boltSlapHit
                out.tz = 0.026f * hold + 0.018f * boltSlapPrep
                out.yawDeg = -14f * hold + 12f * boltSlapHit
                out.pitchDeg = 11f * hold - 8f * magDrop + 10f * magSeat - 5f * boltSlapHit
                out.rollDeg = 28f * hold - 36f * boltSlapPrep - 14f * boltSlapHit

                out.handTy = -0.042f * magDrop + 0.026f * magSeat
                out.handTx = -0.020f * boltSlapPrep + 0.018f * boltSlapHit
            }

            WeaponType.AWP -> {
                // Heavy sniper reload: lift & cant -> remove box mag -> seat heavy mag -> full 4-beat bolt cycle
                val hold = plateau(p, 0.00f, 0.13f, 0.87f, 0.99f)
                val magOut = pulse(p, 0.15f, 0.33f)
                val magIn = pulse(p, 0.40f, 0.57f)
                val boltLift = pulse(p, 0.59f, 0.72f)
                val boltPull = pulse(p, 0.66f, 0.82f)
                val boltLock = pulse(p, 0.79f, 0.90f)

                out.tx = -0.052f * hold + 0.022f * boltPull
                out.ty = -0.035f * hold - 0.058f * magOut + 0.046f * magIn + 0.024f * boltLift - 0.018f * boltLock
                out.tz = 0.042f * hold + 0.072f * boltPull - 0.022f * boltLock
                out.yawDeg = -15f * hold + 18f * boltPull
                out.pitchDeg = 17f * hold - 14f * magOut + 12f * magIn + 9f * boltPull - 7f * boltLock
                out.rollDeg = 30f * hold - 44f * boltLift - 16f * boltPull + 14f * boltLock

                out.handTy = -0.048f * magOut + 0.028f * magIn
                out.handTz = 0.034f * boltPull
            }

            WeaponType.P90 -> {
                // Iconic P90 top-mounted horizontal magazine reload!
                val hold = plateau(p, 0.00f, 0.15f, 0.85f, 0.99f)
                val unlatch = pulse(p, 0.17f, 0.40f)
                val placeNew = plateau(p, 0.40f, 0.50f, 0.60f, 0.68f)
                val topSlap = pulse(p, 0.60f, 0.74f) // Firm downward slap to seat top mag!
                val chargePull = pulse(p, 0.75f, 0.90f)

                out.tx = -0.040f * hold
                out.ty = -0.048f * hold + 0.028f * unlatch - 0.052f * topSlap
                out.tz = 0.035f * hold + 0.042f * chargePull
                out.yawDeg = -18f * hold + 10f * chargePull
                out.pitchDeg = -12f * hold + 14f * unlatch - 14f * topSlap + 6f * chargePull
                out.rollDeg = -34f * hold + 12f * placeNew + 44f * chargePull

                out.handTy = 0.030f * unlatch - 0.036f * topSlap
                out.handTz = 0.022f * chargePull
            }

            WeaponType.MP9 -> {
                // Snappy compact SMG reload (grip mag swap + quick left-side charging pull)
                val hold = plateau(p, 0.00f, 0.14f, 0.84f, 0.99f)
                val magDrop = pulse(p, 0.16f, 0.36f)
                val magSlam = pulse(p, 0.44f, 0.64f)
                val charge = pulse(p, 0.68f, 0.86f)

                out.tx = -0.036f * hold + 0.018f * charge
                out.ty = -0.034f * hold - 0.045f * magDrop + 0.042f * magSlam
                out.tz = 0.028f * hold + 0.042f * charge
                out.yawDeg = -16f * hold + 16f * charge
                out.pitchDeg = 16f * hold - 10f * magDrop + 13f * magSlam + 6f * charge
                out.rollDeg = 32f * hold - 44f * charge

                out.handTy = -0.038f * magDrop + 0.026f * magSlam
                out.handTz = 0.024f * charge
            }

            WeaponType.USP_S, WeaponType.GLOCK_18, WeaponType.DESERT_EAGLE -> {
                // Crisp tactical pistol reload: wrist flick -> drop mag -> punch new mag up -> rack slide!
                val heavy = if (weapon == WeaponType.DESERT_EAGLE) 1.24f else 1.0f
                val hold = plateau(p, 0.00f, 0.15f, 0.85f, 0.99f)
                val magEject = pulse(p, 0.14f, 0.34f)
                val reachBelt = plateau(p, 0.30f, 0.42f, 0.50f, 0.60f)
                val magInsert = pulse(p, 0.50f, 0.67f)
                val slideRack = pulse(p, 0.69f, 0.87f)
                val slideForward = pulse(p, 0.81f, 0.91f)

                out.tx = (-0.042f * hold + 0.018f * slideRack) * heavy
                out.ty = (-0.030f * hold + 0.022f * magEject - 0.045f * reachBelt + 0.048f * magInsert - 0.012f * slideRack) * heavy
                out.tz = (0.030f * hold + 0.054f * slideRack - 0.020f * slideForward) * heavy
                out.yawDeg = (-18f * hold + 22f * slideRack) * heavy
                out.pitchDeg = (24f * hold + 14f * magEject - 10f * reachBelt + 12f * magInsert - 11f * slideRack - 7f * slideForward) * heavy
                out.rollDeg = (36f * hold - 52f * slideRack) * heavy

                out.handTy = (-0.040f * reachBelt + 0.030f * magInsert) * heavy
                out.handTz = (0.026f * slideRack) * heavy
                out.handRollDeg = -10f * slideRack
            }

            WeaponType.KNIFE, WeaponType.C4_BOMB -> Unit
        }
    }

    /**
     * Evaluates the smooth CS2-style multi-stage inspection pose for [weapon] at normalized [p] in [0..1].
     */
    fun computeInspectPose(weapon: WeaponType, pRaw: Float, out: ViewmodelPoseDelta) {
        out.reset()
        val p = pRaw.coerceIn(0f, 1f)
        if (p <= 0.001f || p >= 0.999f) return

        // Global smooth bring-up & return envelope
        val lift = plateau(p, 0.00f, 0.16f, 0.82f, 1.00f)
        // Subtle breathing micro-drift during hold pauses
        val microFloat = sin(p * PI * 4.0).toFloat() * 0.005f * lift

        when (weapon) {
            WeaponType.KNIFE -> {
                // Iconic CS2 / Standoff knife inspect: brisk 360° blade spin -> flat light-catching tilt -> reverse check -> return!
                val spinT = smoothstep(0.06f, 0.38f, p)
                val leftShowcase = plateau(p, 0.36f, 0.50f, 0.64f, 0.76f)
                val rightShowcase = plateau(p, 0.66f, 0.76f, 0.84f, 0.94f)
                val spinKick = pulse(p, 0.06f, 0.38f)

                out.tx = -0.065f * lift + 0.025f * rightShowcase
                out.ty = 0.055f * lift + 0.022f * spinKick + microFloat
                out.tz = 0.055f * lift - 0.020f * leftShowcase
                out.yawDeg = 52f * leftShowcase - 24f * rightShowcase + 26f * spinKick
                out.pitchDeg = 18f * lift + 14f * leftShowcase - 10f * rightShowcase + 360f * spinT
                out.rollDeg = -32f * leftShowcase + 42f * rightShowcase - 45f * spinKick
            }

            WeaponType.USP_S, WeaponType.GLOCK_18, WeaponType.DESERT_EAGLE -> {
                // Stylish pistol inspect: raise near center -> flourish cant -> admire left slide -> roll to right slide!
                val leftView = plateau(p, 0.08f, 0.24f, 0.48f, 0.60f)
                val rightView = plateau(p, 0.52f, 0.66f, 0.82f, 0.94f)
                val deagleSpin = if (weapon == WeaponType.DESERT_EAGLE) {
                    smoothstep(0.04f, 0.28f, p) * 360f
                } else 0f

                out.tx = -0.075f * lift + 0.022f * rightView
                out.ty = 0.058f * lift + microFloat
                out.tz = 0.068f * lift
                out.yawDeg = 54f * leftView - 16f * rightView
                out.pitchDeg = 14f * leftView - 6f * rightView + deagleSpin
                out.rollDeg = -28f * leftView + 52f * rightView
            }

            WeaponType.AK47, WeaponType.M4A1_S, WeaponType.AWP, WeaponType.P90, WeaponType.MP9 -> {
                // Classic two-sided rifle/SMG showcase: bring across chest -> show left profile -> roll over to show ejection port side
                val leftSide = plateau(p, 0.06f, 0.22f, 0.48f, 0.62f)
                val rightSide = plateau(p, 0.52f, 0.66f, 0.82f, 0.96f)
                val scale = if (weapon == WeaponType.AWP) 0.86f else 1.0f

                out.tx = (-0.082f * lift + 0.025f * rightSide) * scale
                out.ty = (0.048f * lift + 0.012f * rightSide + microFloat) * scale
                out.tz = (0.052f * lift) * scale
                out.yawDeg = (46f * leftSide + 18f * rightSide) * scale
                out.pitchDeg = (11f * leftSide - 7f * rightSide) * scale
                out.rollDeg = (-22f * leftSide + 42f * rightSide) * scale
            }

            WeaponType.C4_BOMB -> {
                // Bring C4 keypad up toward eyes, inspect left & right wiring
                val leftTilt = plateau(p, 0.10f, 0.26f, 0.48f, 0.60f)
                val rightTilt = plateau(p, 0.54f, 0.68f, 0.82f, 0.94f)
                out.tx = -0.030f * lift
                out.ty = 0.072f * lift + microFloat
                out.tz = 0.085f * lift
                out.yawDeg = -22f * leftTilt + 22f * rightTilt
                out.pitchDeg = 24f * lift
                out.rollDeg = 16f * leftTilt - 16f * rightTilt
            }
        }
    }
}
