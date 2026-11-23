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
    private val switchWeaponPcm = synthesizeWeaponSwitch()
    private val damagePcm = synthesizeDamage()
    private val bombBeepPcm = synthesizeBombBeep()
    private val bombPlantPcm = synthesizeBombPlantDone()
    private val bombExplodePcm = synthesizeBombExplosion()

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

    fun playHitMarker() = playClip(hitMarkerPcm, 0.74f)
    fun playHeadshot() = playClip(headshotPcm, 0.96f)
    fun playReload() = playClip(reloadPcm, 0.88f)
    fun playWeaponSwitch() { /* Weapon switch audio disabled per user preference */ }
    fun playDamage() = playClip(damagePcm, 0.85f)
    fun playBombBeep() = playClip(bombBeepPcm, 0.82f)
    fun playBombPlanted() = playClip(bombPlantPcm, 0.92f)
    fun playBombExplode() = playClip(bombExplodePcm, 1.0f)

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

    private fun synthesizeWeaponSwitch(): ShortArray {
        val count = (sampleRate * 0.15f).toInt()
        val out = ShortArray(count)
        val rng = Random(909)
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f
            val rustle = n * exp(-t * 30f) * 0.34f
            val latch = if (t > 0.045f) {
                val dt = t - 0.045f
                (n * 0.4f + sin(2.0 * PI * 1120.0 * dt).toFloat() * 0.24f) * exp(-dt * 88f)
            } else 0f
            out[i] = ((rustle + latch).coerceIn(-1f, 1f) * Short.MAX_VALUE * 0.72f).toInt().toShort()
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
}
