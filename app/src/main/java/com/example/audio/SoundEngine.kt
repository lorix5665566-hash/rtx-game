package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.example.model.WeaponType
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * 44.1 kHz studio ballistic DSP synthesizer for all CS2 firearms, knife slashes,
 * and C4 bomb beeps / planting / defusing / explosion.
 */
class SoundEngine {
    private val sampleRate = 44100
    private val executor = Executors.newFixedThreadPool(4)

    @Volatile
    var volume: Float = 0.92f

    private val uspShotPcm = synthesizeSuppressedShot(seed = 11, durationSec = 0.22f, punch = 0.85f)
    private val m4sShotPcm = synthesizeSuppressedShot(seed = 22, durationSec = 0.26f, punch = 1.25f)
    private val glockShotPcm = synthesizePistolShot(seed = 33, heavy = false)
    private val deagleShotPcm = synthesizePistolShot(seed = 50, heavy = true)
    private val mp9ShotPcm = synthesizeSmgShot(seed = 66, bright = true)
    private val p90ShotPcm = synthesizeSmgShot(seed = 90, bright = false)
    private val ak47ShotPcm = synthesizeAk47Shot()
    private val awpShotPcm = synthesizeAwpShot()
    private val knifeSlashPcm = synthesizeKnifeSlash()

    private val hitMarkerPcm = synthesizeHitThump(isHeadshot = false)
    private val headshotPcm = synthesizeHitThump(isHeadshot = true)
    private val reloadPcm = synthesizeReload()
    private val damagePcm = synthesizeDamage()
    private val bombBeepPcm = synthesizeBombBeep()
    private val bombPlantPcm = synthesizeBombPlantDone()
    private val bombExplodePcm = synthesizeBombExplosion()
    private val metalHitPcm = synthesizeMetalHit()
    private val metalBreakPcm = synthesizeMetalBreak()

    // Unique mechanical draw / equip sound for every weapon (safety flick, bolt rack, slide, sheath)
    private val drawAk47Pcm = synthesizeAk47Draw()
    private val drawM4a1sPcm = synthesizeM4a1sDraw()
    private val drawAwpPcm = synthesizeAwpDraw()
    private val drawDeaglePcm = synthesizeDeagleDraw()
    private val drawGlockPcm = synthesizeGlockDraw()
    private val drawUspsPcm = synthesizeUspsDraw()
    private val drawMp9Pcm = synthesizeMp9Draw()
    private val drawP90Pcm = synthesizeP90Draw()
    private val drawKnifePcm = synthesizeKnifeDraw()
    private val drawC4Pcm = synthesizeC4Draw()

    fun playWeaponShot(weapon: WeaponType) {
        when (weapon) {
            WeaponType.USP_S -> playClip(uspShotPcm, 0.84f)
            WeaponType.GLOCK_18 -> playClip(glockShotPcm, 0.90f)
            WeaponType.DESERT_EAGLE -> playClip(deagleShotPcm, 1.0f)
            WeaponType.MP9 -> playClip(mp9ShotPcm, 0.88f)
            WeaponType.P90 -> playClip(p90ShotPcm, 0.90f)
            WeaponType.M4A1_S -> playClip(m4sShotPcm, 0.88f)
            WeaponType.AK47 -> playClip(ak47ShotPcm, 1.0f)
            WeaponType.AWP -> playClip(awpShotPcm, 1.0f)
            WeaponType.KNIFE -> playClip(knifeSlashPcm, 0.82f)
            WeaponType.C4_BOMB -> playClip(bombBeepPcm, 0.72f)
        }
    }

    fun playWeaponDraw(weapon: WeaponType) {
        when (weapon) {
            WeaponType.AK47 -> playClip(drawAk47Pcm, 0.86f)
            WeaponType.M4A1_S -> playClip(drawM4a1sPcm, 0.82f)
            WeaponType.AWP -> playClip(drawAwpPcm, 0.90f)
            WeaponType.DESERT_EAGLE -> playClip(drawDeaglePcm, 0.86f)
            WeaponType.GLOCK_18 -> playClip(drawGlockPcm, 0.78f)
            WeaponType.USP_S -> playClip(drawUspsPcm, 0.78f)
            WeaponType.MP9 -> playClip(drawMp9Pcm, 0.80f)
            WeaponType.P90 -> playClip(drawP90Pcm, 0.80f)
            WeaponType.KNIFE -> playClip(drawKnifePcm, 0.82f)
            WeaponType.C4_BOMB -> playClip(drawC4Pcm, 0.76f)
        }
    }

    fun playHitMarker() = playClip(hitMarkerPcm, 0.74f)
    fun playHeadshot() = playClip(headshotPcm, 0.96f)
    fun playReload() = playClip(reloadPcm, 0.88f)
    fun playWeaponSwitch() { /* Replaced by playWeaponDraw(weapon) */ }
    fun playDamage() = playClip(damagePcm, 0.85f)
    fun playBombBeep() = playClip(bombBeepPcm, 0.82f)
    fun playBombPlanted() = playClip(bombPlantPcm, 0.92f)
    fun playBombExplode() = playClip(bombExplodePcm, 1.0f)
    fun playMetalHit() = playClip(metalHitPcm, 0.88f)
    fun playMetalBreak() = playClip(metalBreakPcm, 0.96f)

    private fun playClip(pcm: ShortArray, gain: Float) {
        val effectiveGain = (volume * gain).coerceIn(0f, 1f)
        if (effectiveGain <= 0.01f) return
        executor.execute {
            runCatching {
                val byteCount = pcm.size * 2
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(byteCount)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.setVolume(effectiveGain)
                track.play()
                val durationMs = (pcm.size * 1000L) / sampleRate + 35L
                Thread.sleep(durationMs)
                track.stop()
                track.release()
            }
        }
    }

    fun release() {
        executor.shutdownNow()
    }

    private fun synthesizeSuppressedShot(seed: Int, durationSec: Float, punch: Float): ShortArray {
        // Authentic tactical suppressor: tight gas puff + sharp subsonic baffle snap + crisp bolt clack
        val count = (sampleRate * durationSec).toInt()
        val dry = FloatArray(count)
        val rng = Random(seed)
        var lpLow = 0f
        var lpMid = 0f
        var hpPrev = 0f
        var prevN = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f
            lpLow += (n - lpLow) * 0.045f
            lpMid += (n - lpMid) * 0.25f
            val hp = 0.86f * (hpPrev + n - prevN)
            hpPrev = hp; prevN = n
            val puff = hp * exp(-t * 145f) * 1.15f
            val baffle = lpMid * exp(-t * 72f) * (1.2f * punch)
            val thump = lpLow * exp(-t * 42f) * (2.0f * punch)
            val bolt = if (t in 0.015f..0.036f) hp * exp(-(t - 0.015f) * 240f) * 0.6f else 0f
            dry[i] = tanh(((puff + baffle + thump + bolt) * 1.3f).toDouble()).toFloat()
        }
        return applyCourtyardEcho(dry, echoGain = 0.16f, tailDecay = 14f)
    }

    private fun synthesizePistolShot(seed: Int, heavy: Boolean): ShortArray {
        val count = (sampleRate * if (heavy) 0.56f else 0.30f).toInt()
        val dry = FloatArray(count)
        val rng = Random(seed)
        var lpSub = 0f
        var lpMid = 0f
        var hpPrev = 0f
        var prevN = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f
            lpSub += (n - lpSub) * (if (heavy) 0.016f else 0.04f)
            lpMid += (n - lpMid) * (if (heavy) 0.15f else 0.28f)
            val hp = 0.85f * (hpPrev + n - prevN)
            hpPrev = hp; prevN = n

            val crack = hp * exp(-t * (if (heavy) 110f else 185f)) * (if (heavy) 2.2f else 1.55f)
            val bark = lpMid * exp(-t * (if (heavy) 22f else 54f)) * (if (heavy) 2.6f else 1.4f)
            val boom = lpSub * exp(-t * (if (heavy) 12f else 32f)) * (if (heavy) 3.6f else 1.7f)
            dry[i] = tanh(((crack + bark + boom) * (if (heavy) 1.65f else 1.3f)).toDouble()).toFloat()
        }
        return applyCourtyardEcho(dry, echoGain = if (heavy) 0.48f else 0.28f, tailDecay = if (heavy) 6.2f else 11f)
    }

    private fun synthesizeSmgShot(seed: Int, bright: Boolean): ShortArray {
        val count = (sampleRate * 0.26f).toInt()
        val dry = FloatArray(count)
        val rng = Random(seed)
        var lpLow = 0f
        var lpMid = 0f
        var hpPrev = 0f
        var prevN = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f
            lpLow += (n - lpLow) * (if (bright) 0.055f else 0.038f)
            lpMid += (n - lpMid) * (if (bright) 0.34f else 0.24f)
            val hp = 0.84f * (hpPrev + n - prevN)
            hpPrev = hp; prevN = n

            val snap = hp * exp(-t * 195f) * 1.45f
            val body = lpMid * exp(-t * 62f) * 1.55f
            val kick = lpLow * exp(-t * 36f) * 1.95f
            dry[i] = tanh(((snap + body + kick) * 1.32f).toDouble()).toFloat()
        }
        return applyCourtyardEcho(dry, echoGain = 0.26f, tailDecay = 12f)
    }

    private fun synthesizeAk47Shot(): ShortArray {
        val count = (sampleRate * 0.46f).toInt()
        val dry = FloatArray(count)
        val rng = Random(207)
        var lpSub = 0f
        var lpGrowl = 0f
        var lpMid = 0f
        var hpPrev = 0f
        var prevN = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f
            lpSub += (n - lpSub) * 0.018f
            lpGrowl += (n - lpGrowl) * 0.082f
            lpMid += (n - lpMid) * 0.26f
            val hp = 0.83f * (hpPrev + n - prevN)
            hpPrev = hp; prevN = n

            val crack = hp * exp(-t * 140f) * 1.55f
            val heavyBark = lpGrowl * exp(-t * 25f) * 2.75f
            val midPunch = lpMid * exp(-t * 36f) * 1.40f
            val subBoom = lpSub * exp(-t * 15f) * 3.2f
            val clank = if (t in 0.022f..0.058f) {
                val dt = t - 0.022f
                val ring = (sin(2.0 * PI * 1160.0 * dt) + 0.5 * sin(2.0 * PI * 2440.0 * dt)).toFloat()
                (ring * 0.24f + hp * 0.45f) * exp(-dt * 125f)
            } else 0f
            dry[i] = tanh(((crack + heavyBark + midPunch + subBoom + clank) * 1.55f).toDouble()).toFloat()
        }
        return applyCourtyardEcho(dry, echoGain = 0.44f, tailDecay = 7.0f)
    }

    private fun synthesizeAwpShot(): ShortArray {
        val count = (sampleRate * 0.96f).toInt()
        val dry = FloatArray(count)
        val rng = Random(338)
        var lpSub = 0f
        var lpLow = 0f
        var lpMid = 0f
        var hpPrev = 0f
        var prevN = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f
            lpSub += (n - lpSub) * 0.011f
            lpLow += (n - lpLow) * 0.052f
            lpMid += (n - lpMid) * 0.20f
            val hp = 0.88f * (hpPrev + n - prevN)
            hpPrev = hp; prevN = n

            val whipCrack = hp * exp(-t * 92f) * 2.2f
            val cannonRoar = lpLow * exp(-t * 12.5f) * 3.3f
            val midBlast = lpMid * exp(-t * 22f) * 1.65f
            val shockwave = lpSub * exp(-t * 8.2f) * 3.9f

            var boltCycle = 0f
            if (t in 0.46f..0.56f) {
                val dt = t - 0.46f
                boltCycle += (hp * 0.42f + sin(2.0 * PI * 1450.0 * dt).toFloat() * 0.14f) * exp(-dt * 72f)
            }
            if (t in 0.64f..0.78f) {
                val dt = t - 0.64f
                boltCycle += (hp * 0.48f + sin(2.0 * PI * 980.0 * dt).toFloat() * 0.16f) * exp(-dt * 58f)
            }
            dry[i] = tanh(((whipCrack + cannonRoar + midBlast + shockwave) * 1.78f).toDouble()).toFloat() + boltCycle * 0.55f
        }
        return applyCourtyardEcho(dry, echoGain = 0.54f, tailDecay = 4.4f)
    }

    private fun synthesizeKnifeSlash(): ShortArray {
        val count = (sampleRate * 0.19f).toInt()
        val out = ShortArray(count)
        val rng = Random(71)
        var bp = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f
            val alpha = (0.08f + (t / 0.19f) * 0.28f).coerceAtMost(0.4f)
            bp += (n - bp) * alpha
            val env = sin(PI * (t / 0.19f)).toFloat() * exp(-t * 8f)
            val s = ((n - bp) * 0.9f + bp * 0.5f) * env
            out[i] = (s.coerceIn(-1f, 1f) * Short.MAX_VALUE * 0.78f).toInt().toShort()
        }
        return out
    }

    private fun applyCourtyardEcho(dry: FloatArray, echoGain: Float, tailDecay: Float): ShortArray {
        val n = dry.size
        val wet = FloatArray(n)
        val taps = intArrayOf(
            (sampleRate * 0.026f).toInt(),
            (sampleRate * 0.056f).toInt(),
            (sampleRate * 0.095f).toInt(),
            (sampleRate * 0.145f).toInt(),
            (sampleRate * 0.205f).toInt()
        )
        val tapWeights = floatArrayOf(0.55f, 0.42f, 0.30f, 0.20f, 0.13f)
        var lpEcho = 0f
        var peak = 0.001f
        for (i in 0 until n) {
            var echoSum = 0f
            for (k in taps.indices) {
                val idx = i - taps[k]
                if (idx >= 0) echoSum += dry[idx] * tapWeights[k]
            }
            lpEcho += (echoSum - lpEcho) * 0.14f
            val t = i.toFloat() / sampleRate
            val sample = dry[i] + lpEcho * echoGain * exp(-t * tailDecay * 0.35f)
            wet[i] = sample
            val a = abs(sample)
            if (a > peak) peak = a
        }
        val scale = (0.94f / peak).coerceAtMost(1.4f)
        val out = ShortArray(n)
        for (i in 0 until n) {
            val s = tanh((wet[i] * scale).toDouble()).toFloat()
            out[i] = (s * Short.MAX_VALUE * 0.95f).toInt().toShort()
        }
        return out
    }

    private fun synthesizeHitThump(isHeadshot: Boolean): ShortArray {
        val duration = if (isHeadshot) 0.165f else 0.065f
        val count = (sampleRate * duration).toInt()
        val out = ShortArray(count)
        val rng = Random(if (isHeadshot) 777 else 555)
        var lp = 0f
        var bpLow = 0f
        var bpMid = 0f
        var hpPrev = 0f
        var prevN = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f
            lp += (n - lp) * 0.12f
            bpLow += (n - bpLow) * 0.055f
            bpMid += (n - bpMid) * 0.24f
            val hp = 0.84f * (hpPrev + n - prevN)
            hpPrev = hp; prevN = n
            val sample = if (isHeadshot) {
                // Realistic dry skull/bone crunch ("хруст головы"):
                // 3 tightly staggered crackling fracture transients + hollow bone resonance + wet sub-thud (NO musical bell!)
                val crack1 = (hp * 1.15f + (bpMid - bpLow) * 1.85f) * exp(-t * 88f)
                val crack2 = if (t >= 0.016f) {
                    val dt = t - 0.016f
                    val snapGrain = if (abs(n) > 0.52f) n * 1.65f else n * 0.55f
                    (hp * 0.95f + snapGrain * 0.85f + (bpMid - bpLow) * 1.45f) * exp(-dt * 92f)
                } else 0f
                val crack3 = if (t >= 0.038f) {
                    val dt = t - 0.038f
                    val splinter = if (abs(n) > 0.62f) n * 1.45f else n * 0.35f
                    (hp * 0.65f + splinter * 0.90f) * exp(-dt * 70f)
                } else 0f
                // Short, dry bone-cavity crack (decaying frequency chirp 940 -> 380 Hz)
                val boneSnap = sin(2.0 * PI * (380.0 + 560.0 * exp(-t * 48.0)) * t).toFloat() * exp(-t * 68f) * 0.42f
                val wetThud = bpLow * exp(-t * 36f) * 1.75f
                tanh(((crack1 + crack2 * 0.92f + crack3 * 0.68f + boneSnap + wetThud) * 1.38f).toDouble()).toFloat()
            } else {
                val thud = lp * exp(-t * 55f) * 1.3f
                val tick = n * exp(-t * 160f) * 0.48f
                (thud + tick).coerceIn(-1f, 1f)
            }
            out[i] = (sample * Short.MAX_VALUE * 0.92f).toInt().toShort()
        }
        return out
    }

    private fun synthesizeReload(): ShortArray {
        // Multi-stage realistic tactical reload (1.28s):
        // Stage 1 (0.00-0.16s): Mag release button click + empty magazine sliding out of magwell
        // Stage 2 (0.44-0.66s): Fresh magazine insertion scrape + solid polymer/steel seat thump + latch lock
        // Stage 3 (0.86-1.22s): Crisp metallic charging handle pull back + spring snap forward clack!
        val count = (sampleRate * 1.28f).toInt()
        val out = ShortArray(count)
        val rng = Random(8088)
        var lpLow = 0f
        var lpMid = 0f
        var hpPrev = 0f
        var prevN = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f
            lpLow += (n - lpLow) * 0.045f
            lpMid += (n - lpMid) * 0.22f
            val hp = 0.85f * (hpPrev + n - prevN)
            hpPrev = hp; prevN = n
            var s = 0f

            // 1A. Mag catch click (0.00..0.045s)
            if (t < 0.045f) {
                s += (hp * 0.85f + sin(2.0 * PI * 1420.0 * t).toFloat() * 0.28f) * exp(-t * 125f)
            }
            // 1B. Empty mag sliding out friction (0.04..0.18s)
            if (t in 0.04f..0.18f) {
                val dt = t - 0.04f
                val env = sin(PI * (dt / 0.14f)).toFloat()
                s += (hp * 0.36f + (lpMid - lpLow) * 0.42f) * env * 0.65f
            }
            // 2A. Fresh mag guiding into magwell (0.44..0.54s)
            if (t in 0.44f..0.54f) {
                val dt = t - 0.44f
                val env = sin(PI * (dt / 0.10f)).toFloat()
                s += ((lpMid - lpLow) * 0.55f + hp * 0.28f) * env * 0.72f
            }
            // 2B. Solid mag seat thud + steel lock click (0.54..0.67s)
            if (t in 0.54f..0.67f) {
                val dt = t - 0.54f
                val thump = (lpLow * 1.9f + sin(2.0 * PI * 240.0 * dt).toFloat() * 0.42f) * exp(-dt * 62f)
                val latch = if (dt >= 0.018f) {
                    val d2 = dt - 0.018f
                    (hp * 0.95f + sin(2.0 * PI * 1680.0 * d2).toFloat() * 0.28f) * exp(-d2 * 115f)
                } else 0f
                s += (thump + latch) * 0.95f
            }
            // 3A. Charging handle / slide pulled back (0.86..1.00s)
            if (t in 0.86f..1.00f) {
                val dt = t - 0.86f
                val scrape = (hp * 0.62f + sin(2.0 * PI * 2150.0 * dt).toFloat() * 0.16f) * exp(-dt * 26f)
                val stopClick = if (dt >= 0.075f) {
                    val d2 = dt - 0.075f
                    (hp * 0.88f + sin(2.0 * PI * 1180.0 * d2).toFloat() * 0.25f) * exp(-d2 * 105f)
                } else 0f
                s += (scrape * 0.65f + stopClick * 0.85f)
            }
            // 3B. Bolt/slide snapping sharply forward into battery (1.03..1.24s)
            if (t in 1.03f..1.24f) {
                val dt = t - 1.03f
                val clack = (hp * 1.15f + (lpMid - lpLow) * 0.95f + sin(2.0 * PI * 1540.0 * dt).toFloat() * 0.30f +
                    sin(2.0 * PI * 2890.0 * dt).toFloat() * 0.18f) * exp(-dt * 72f)
                val slam = lpLow * 1.45f * exp(-dt * 48f)
                s += (clack + slam) * 1.05f
            }

            val shaped = tanh((s * 1.25f).toDouble()).toFloat()
            out[i] = (shaped * Short.MAX_VALUE * 0.88f).toInt().toShort()
        }
        return out
    }

    /**
     * AK-47: iconic stamped-steel safety selector snap + heavy steel bolt carrier rack back & forward clank.
     */
    private fun synthesizeAk47Draw(): ShortArray {
        val count = (sampleRate * 0.56f).toInt()
        val out = ShortArray(count)
        val rng = Random(1047)
        var lpLow = 0f; var lpMid = 0f; var hpPrev = 0f; var prevN = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f
            lpLow += (n - lpLow) * 0.05f
            lpMid += (n - lpMid) * 0.24f
            val hp = 0.86f * (hpPrev + n - prevN); hpPrev = hp; prevN = n
            var s = 0f
            // 1. Stamped-steel safety lever sweep & snap (0.04..0.14s)
            if (t in 0.04f..0.14f) {
                val dt = t - 0.04f
                s += (hp * 0.7f + sin(2.0 * PI * 1850.0 * dt).toFloat() * 0.26f) * exp(-dt * 75f) * 0.72f
            }
            // 2. Heavy AK bolt carrier pulled back with gritty spring scrape (0.18..0.33s)
            if (t in 0.18f..0.33f) {
                val dt = t - 0.18f
                val scrape = (hp * 0.65f + (lpMid - lpLow) * 0.9f + sin(2.0 * PI * 1240.0 * dt).toFloat() * 0.18f) *
                    sin(PI * (dt / 0.15f)).toFloat()
                val rearStop = if (dt > 0.105f) {
                    val d2 = dt - 0.105f
                    (hp * 1.1f + lpLow * 1.3f + sin(2.0 * PI * 920.0 * d2).toFloat() * 0.30f) * exp(-d2 * 90f)
                } else 0f
                s += scrape * 0.68f + rearStop * 0.82f
            }
            // 3. Heavy steel bolt slamming home into receiver (0.35..0.54s)
            if (t in 0.35f..0.54f) {
                val dt = t - 0.35f
                val slam = (hp * 1.35f + (lpMid - lpLow) * 1.1f + lpLow * 1.9f +
                    sin(2.0 * PI * 1380.0 * dt).toFloat() * 0.34f +
                    sin(2.0 * PI * 2650.0 * dt).toFloat() * 0.20f) * exp(-dt * 48f)
                s += slam * 0.96f
            }
            out[i] = (tanh((s * 1.25f).toDouble()).toFloat() * Short.MAX_VALUE * 0.86f).toInt().toShort()
        }
        return out
    }

    /**
     * M4A1-S: crisp AR-15 thumb safety click + smooth T-handle charge & buffer spring snap.
     */
    private fun synthesizeM4a1sDraw(): ShortArray {
        val count = (sampleRate * 0.50f).toInt()
        val out = ShortArray(count)
        val rng = Random(1401)
        var lpLow = 0f; var lpMid = 0f; var hpPrev = 0f; var prevN = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f
            lpLow += (n - lpLow) * 0.06f
            lpMid += (n - lpMid) * 0.28f
            val hp = 0.87f * (hpPrev + n - prevN); hpPrev = hp; prevN = n
            var s = 0f
            // 1. Crisp AR thumb safety click (0.03..0.09s)
            if (t in 0.03f..0.09f) {
                val dt = t - 0.03f
                s += (hp * 1.05f + sin(2.0 * PI * 2780.0 * dt).toFloat() * 0.32f) * exp(-dt * 145f) * 0.78f
            }
            // 2. Smooth charging handle latch + draw back (0.14..0.28s)
            if (t in 0.14f..0.28f) {
                val dt = t - 0.14f
                s += (hp * 0.55f + (lpMid - lpLow) * 0.65f + sin(2.0 * PI * 2240.0 * dt).toFloat() * 0.15f) *
                    sin(PI * (dt / 0.14f)).toFloat() * 0.72f
            }
            // 3. Fast buffer-spring snap forward + crisp bolt lock (0.30..0.48s)
            if (t in 0.30f..0.48f) {
                val dt = t - 0.30f
                val lock = (hp * 1.2f + lpLow * 1.25f + sin(2.0 * PI * 1920.0 * dt).toFloat() * 0.26f +
                    sin(2.0 * PI * 3420.0 * dt).toFloat() * 0.16f) * exp(-dt * 68f)
                s += lock * 0.88f
            }
            out[i] = (tanh((s * 1.2f).toDouble()).toFloat() * Short.MAX_VALUE * 0.84f).toInt().toShort()
        }
        return out
    }

    /**
     * AWP: deep sniper sling shift + heavy bolt-action handle lift, slide, and lock down.
     */
    private fun synthesizeAwpDraw(): ShortArray {
        val count = (sampleRate * 0.66f).toInt()
        val out = ShortArray(count)
        val rng = Random(1338)
        var lpLow = 0f; var lpMid = 0f; var hpPrev = 0f; var prevN = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f
            lpLow += (n - lpLow) * 0.035f
            lpMid += (n - lpMid) * 0.18f
            val hp = 0.86f * (hpPrev + n - prevN); hpPrev = hp; prevN = n
            var s = 0f
            // 1. Heavy rifle shoulder lift thud (0.00..0.14s)
            if (t < 0.14f) {
                s += lpLow * 1.5f * sin(PI * (t / 0.14f)).toFloat() * 0.65f
            }
            // 2. Bolt handle unlock lift click (0.17..0.27s)
            if (t in 0.17f..0.27f) {
                val dt = t - 0.17f
                s += (hp * 0.95f + lpLow * 1.1f + sin(2.0 * PI * 1080.0 * dt).toFloat() * 0.32f) * exp(-dt * 80f) * 0.85f
            }
            // 3. Heavy precision bolt push forward (0.31..0.44s)
            if (t in 0.31f..0.44f) {
                val dt = t - 0.31f
                s += (hp * 0.55f + (lpMid - lpLow) * 0.9f) * sin(PI * (dt / 0.13f)).toFloat() * 0.72f
            }
            // 4. Solid steel bolt lock-down clunk (0.45..0.63s)
            if (t in 0.45f..0.63f) {
                val dt = t - 0.45f
                s += (hp * 1.15f + lpLow * 2.1f + sin(2.0 * PI * 760.0 * dt).toFloat() * 0.36f +
                    sin(2.0 * PI * 1620.0 * dt).toFloat() * 0.22f) * exp(-dt * 42f) * 0.98f
            }
            out[i] = (tanh((s * 1.3f).toDouble()).toFloat() * Short.MAX_VALUE * 0.88f).toInt().toShort()
        }
        return out
    }

    /**
     * Desert Eagle: heavy .50 AE stainless-steel slide rack + authoritative safety thumb click.
     */
    private fun synthesizeDeagleDraw(): ShortArray {
        val count = (sampleRate * 0.44f).toInt()
        val out = ShortArray(count)
        val rng = Random(1050)
        var lpLow = 0f; var lpMid = 0f; var hpPrev = 0f; var prevN = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f
            lpLow += (n - lpLow) * 0.045f
            lpMid += (n - lpMid) * 0.24f
            val hp = 0.88f * (hpPrev + n - prevN); hpPrev = hp; prevN = n
            var s = 0f
            // 1. Heavy steel slide pull back (0.04..0.17s)
            if (t in 0.04f..0.17f) {
                val dt = t - 0.04f
                s += (hp * 0.75f + (lpMid - lpLow) * 0.85f + sin(2.0 * PI * 1620.0 * dt).toFloat() * 0.22f) *
                    sin(PI * (dt / 0.13f)).toFloat() * 0.80f
            }
            // 2. Heavy slide forward slam (0.19..0.34s)
            if (t in 0.19f..0.34f) {
                val dt = t - 0.19f
                s += (hp * 1.3f + lpLow * 1.75f + sin(2.0 * PI * 1480.0 * dt).toFloat() * 0.34f +
                    sin(2.0 * PI * 3120.0 * dt).toFloat() * 0.20f) * exp(-dt * 58f) * 0.95f
            }
            // 3. Ambidextrous slide safety snap off (0.33..0.42s)
            if (t in 0.33f..0.42f) {
                val dt = t - 0.33f
                s += (hp * 0.9f + sin(2.0 * PI * 2450.0 * dt).toFloat() * 0.28f) * exp(-dt * 120f) * 0.68f
            }
            out[i] = (tanh((s * 1.25f).toDouble()).toFloat() * Short.MAX_VALUE * 0.86f).toInt().toShort()
        }
        return out
    }

    /**
     * Glock-18: polymer holster zip + crisp light compact pistol slide rack.
     */
    private fun synthesizeGlockDraw(): ShortArray {
        val count = (sampleRate * 0.36f).toInt()
        val out = ShortArray(count)
        val rng = Random(1018)
        var lpLow = 0f; var lpMid = 0f; var hpPrev = 0f; var prevN = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f
            lpLow += (n - lpLow) * 0.08f
            lpMid += (n - lpMid) * 0.34f
            val hp = 0.86f * (hpPrev + n - prevN); hpPrev = hp; prevN = n
            var s = 0f
            // 1. Polymer unholster brush (0.00..0.09s)
            if (t < 0.09f) {
                s += (lpMid - lpLow) * 0.85f * sin(PI * (t / 0.09f)).toFloat() * 0.60f
            }
            // 2. Quick snappy slide pull + spring snap (0.11..0.33s)
            if (t in 0.11f..0.20f) {
                val dt = t - 0.11f
                s += (hp * 0.72f + sin(2.0 * PI * 2380.0 * dt).toFloat() * 0.18f) * exp(-dt * 42f) * 0.70f
            }
            if (t in 0.21f..0.34f) {
                val dt = t - 0.21f
                s += (hp * 1.15f + lpLow * 0.95f + sin(2.0 * PI * 2650.0 * dt).toFloat() * 0.28f) * exp(-dt * 85f) * 0.88f
            }
            out[i] = (tanh((s * 1.2f).toDouble()).toFloat() * Short.MAX_VALUE * 0.82f).toInt().toShort()
        }
        return out
    }

    /**
     * USP-S: quiet tactical unholster + refined decocker/safety double-click + tight slide lock.
     */
    private fun synthesizeUspsDraw(): ShortArray {
        val count = (sampleRate * 0.38f).toInt()
        val out = ShortArray(count)
        val rng = Random(1045)
        var lpLow = 0f; var lpMid = 0f; var hpPrev = 0f; var prevN = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f
            lpLow += (n - lpLow) * 0.07f
            lpMid += (n - lpMid) * 0.28f
            val hp = 0.88f * (hpPrev + n - prevN); hpPrev = hp; prevN = n
            var s = 0f
            // 1. Suppressor clearing holster (0.00..0.10s)
            if (t < 0.10f) {
                s += (hp * 0.32f + (lpMid - lpLow) * 0.55f) * sin(PI * (t / 0.10f)).toFloat() * 0.55f
            }
            // 2. Crisp German metal safety/decocker click (0.12..0.19s)
            if (t in 0.12f..0.19f) {
                val dt = t - 0.12f
                s += (hp * 1.0f + sin(2.0 * PI * 3150.0 * dt).toFloat() * 0.30f) * exp(-dt * 135f) * 0.80f
            }
            // 3. Tight damped slide snap (0.22..0.36s)
            if (t in 0.22f..0.36f) {
                val dt = t - 0.22f
                s += (hp * 0.92f + lpLow * 1.15f + sin(2.0 * PI * 2120.0 * dt).toFloat() * 0.24f) * exp(-dt * 78f) * 0.82f
            }
            out[i] = (tanh((s * 1.2f).toDouble()).toFloat() * Short.MAX_VALUE * 0.82f).toInt().toShort()
        }
        return out
    }

    /**
     * MP9: fast folding-stock click + snappy lightweight SMG bolt cock.
     */
    private fun synthesizeMp9Draw(): ShortArray {
        val count = (sampleRate * 0.38f).toInt()
        val out = ShortArray(count)
        val rng = Random(1009)
        var lpLow = 0f; var lpMid = 0f; var hpPrev = 0f; var prevN = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f
            lpLow += (n - lpLow) * 0.06f
            lpMid += (n - lpMid) * 0.32f
            val hp = 0.87f * (hpPrev + n - prevN); hpPrev = hp; prevN = n
            var s = 0f
            // 1. Folding stock / grip snap (0.03..0.10s)
            if (t in 0.03f..0.10f) {
                val dt = t - 0.03f
                s += (hp * 0.95f + lpLow * 0.85f + sin(2.0 * PI * 1950.0 * dt).toFloat() * 0.22f) * exp(-dt * 110f) * 0.74f
            }
            // 2. Fast SMG T-handle cock back & snap (0.14..0.35s)
            if (t in 0.14f..0.22f) {
                val dt = t - 0.14f
                s += (hp * 0.72f + (lpMid - lpLow) * 0.7f) * sin(PI * (dt / 0.08f)).toFloat() * 0.72f
            }
            if (t in 0.23f..0.36f) {
                val dt = t - 0.23f
                s += (hp * 1.25f + lpLow * 1.05f + sin(2.0 * PI * 2860.0 * dt).toFloat() * 0.26f) * exp(-dt * 92f) * 0.86f
            }
            out[i] = (tanh((s * 1.2f).toDouble()).toFloat() * Short.MAX_VALUE * 0.82f).toInt().toShort()
        }
        return out
    }

    /**
     * P90: bullpup side charging knob slide + distinctive hollow polymer frame tap + fire selector click.
     */
    private fun synthesizeP90Draw(): ShortArray {
        val count = (sampleRate * 0.42f).toInt()
        val out = ShortArray(count)
        val rng = Random(1090)
        var lpLow = 0f; var lpMid = 0f; var hpPrev = 0f; var prevN = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f
            lpLow += (n - lpLow) * 0.055f
            lpMid += (n - lpMid) * 0.26f
            val hp = 0.86f * (hpPrev + n - prevN); hpPrev = hp; prevN = n
            var s = 0f
            // 1. Hollow polymer bullpup mag-top tap (0.04..0.12s)
            if (t in 0.04f..0.12f) {
                val dt = t - 0.04f
                s += (lpLow * 1.4f + sin(2.0 * PI * 540.0 * dt).toFloat() * 0.35f) * exp(-dt * 75f) * 0.76f
            }
            // 2. Side charging knob pull & snap (0.15..0.33s)
            if (t in 0.15f..0.24f) {
                val dt = t - 0.15f
                s += (hp * 0.65f + (lpMid - lpLow) * 0.8f) * sin(PI * (dt / 0.09f)).toFloat() * 0.70f
            }
            if (t in 0.25f..0.36f) {
                val dt = t - 0.25f
                s += (hp * 1.1f + lpLow * 1.25f + sin(2.0 * PI * 1720.0 * dt).toFloat() * 0.28f) * exp(-dt * 74f) * 0.86f
            }
            // 3. Rotary fire selector tick (0.35..0.41s)
            if (t in 0.35f..0.41f) {
                val dt = t - 0.35f
                s += (hp * 0.88f + sin(2.0 * PI * 3320.0 * dt).toFloat() * 0.22f) * exp(-dt * 150f) * 0.62f
            }
            out[i] = (tanh((s * 1.2f).toDouble()).toFloat() * Short.MAX_VALUE * 0.82f).toInt().toShort()
        }
        return out
    }

    /**
     * Combat Knife: crisp metallic blade unsheathing hiss + clean high-steel harmonic ring.
     */
    private fun synthesizeKnifeDraw(): ShortArray {
        val count = (sampleRate * 0.34f).toInt()
        val out = ShortArray(count)
        val rng = Random(1777)
        var hpPrev = 0f; var prevN = 0f; var bp = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f
            val hp = 0.90f * (hpPrev + n - prevN); hpPrev = hp; prevN = n
            bp += (hp - bp) * 0.22f
            val sheathEnv = sin(PI * (t / 0.22f).coerceIn(0f, 1f)).toFloat() * exp(-t * 5.5f)
            val scrape = bp * sheathEnv * 0.85f
            // Subtle high-carbon steel shimmer (ascending shimmer as blade clears sheath)
            val shimmer = if (t >= 0.04f) {
                val dt = t - 0.04f
                val f1 = 3650.0 + dt * 1100.0
                val f2 = 5820.0 + dt * 1400.0
                (sin(2.0 * PI * f1 * dt) * 0.22 + sin(2.0 * PI * f2 * dt) * 0.14).toFloat() * exp(-dt * 14f)
            } else 0f
            val s = tanh(((scrape + shimmer) * 1.25f).toDouble()).toFloat()
            out[i] = (s * Short.MAX_VALUE * 0.82f).toInt().toShort()
        }
        return out
    }

    /**
     * C4 Bomb: tactical pack rustle + soft arming keypad chirp.
     */
    private fun synthesizeC4Draw(): ShortArray {
        val count = (sampleRate * 0.28f).toInt()
        val out = ShortArray(count)
        val rng = Random(1004)
        var lp = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f
            lp += (n - lp) * 0.16f
            val rustle = lp * sin(PI * (t / 0.16f).coerceIn(0f, 1f)).toFloat() * 0.62f
            val chirp = if (t in 0.13f..0.24f) {
                val dt = t - 0.13f
                sin(2.0 * PI * 2420.0 * dt).toFloat() * exp(-dt * 28f) * 0.42f
            } else 0f
            out[i] = (((rustle + chirp).coerceIn(-1f, 1f)) * Short.MAX_VALUE * 0.78f).toInt().toShort()
        }
        return out
    }

    private fun synthesizeDamage(): ShortArray {
        val count = (sampleRate * 0.18f).toInt()
        val out = ShortArray(count)
        val rng = Random(404)
        var lp = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f
            lp += (n - lp) * 0.04f
            val punch = (lp * 2.6f + sin(2.0 * PI * 75.0 * t).toFloat() * 0.4f) * exp(-t * 22f)
            out[i] = (punch.coerceIn(-1f, 1f) * Short.MAX_VALUE * 0.9f).toInt().toShort()
        }
        return out
    }

    private fun synthesizeBombBeep(): ShortArray {
        val count = (sampleRate * 0.11f).toInt()
        val out = ShortArray(count)
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val env = exp(-t * 14f) * if (t < 0.004f) t / 0.004f else 1f
            val tone = (sin(2.0 * PI * 2820.0 * t) * 0.75 + sin(2.0 * PI * 5640.0 * t) * 0.25).toFloat()
            out[i] = (tone * env * Short.MAX_VALUE * 0.72f).toInt().toShort()
        }
        return out
    }

    private fun synthesizeBombPlantDone(): ShortArray {
        val count = (sampleRate * 0.36f).toInt()
        val out = ShortArray(count)
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val beep1 = if (t < 0.08f) sin(2.0 * PI * 2600.0 * t).toFloat() * exp(-t * 18f) else 0f
            val beep2 = if (t in 0.12f..0.28f) sin(2.0 * PI * 3100.0 * (t - 0.12f)).toFloat() * exp(-(t - 0.12f) * 14f) else 0f
            out[i] = (((beep1 + beep2) * 0.75f).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun synthesizeBombExplosion(): ShortArray {
        val count = (sampleRate * 1.15f).toInt()
        val out = ShortArray(count)
        val rng = Random(4444)
        var lpSub = 0f
        var lpMid = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f
            lpSub += (n - lpSub) * 0.009f
            lpMid += (n - lpMid) * 0.07f
            val s = tanh(((lpSub * 5.2f * exp(-t * 2.4f) + lpMid * 2.2f * exp(-t * 4.5f) + n * 0.8f * exp(-t * 18f)) * 1.4f).toDouble()).toFloat()
            out[i] = (s * Short.MAX_VALUE * 0.96f).toInt().toShort()
        }
        return out
    }

    private fun synthesizeMetalHit(): ShortArray {
        val count = (sampleRate * 0.22f).toInt()
        val out = ShortArray(count)
        val rng = Random(6101)
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val clack = (rng.nextFloat() * 2f - 1f) * exp(-t * 95f) * 0.55f
            val ring1 = sin(2.0 * PI * 1820.0 * t).toFloat() * exp(-t * 18f) * 0.55f
            val ring2 = sin(2.0 * PI * 3260.0 * t).toFloat() * exp(-t * 26f) * 0.35f
            val s = tanh(((clack + ring1 + ring2) * 1.15f).toDouble()).toFloat()
            out[i] = (s * Short.MAX_VALUE * 0.88f).toInt().toShort()
        }
        return out
    }

    private fun synthesizeMetalBreak(): ShortArray {
        val count = (sampleRate * 0.42f).toInt()
        val out = ShortArray(count)
        val rng = Random(6202)
        var lp = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f
            lp += (n - lp) * 0.28f
            val burst = lp * exp(-t * 16f) * 0.72f
            val clangA = sin(2.0 * PI * 1420.0 * t).toFloat() * exp(-t * 10f) * 0.48f
            val clangB = if (t > 0.055f) sin(2.0 * PI * 2740.0 * (t - 0.055f)).toFloat() * exp(-(t - 0.055f) * 14f) * 0.40f else 0f
            val s = tanh(((burst + clangA + clangB) * 1.25f).toDouble()).toFloat()
            out[i] = (s * Short.MAX_VALUE * 0.92f).toInt().toShort()
        }
        return out
    }
}
