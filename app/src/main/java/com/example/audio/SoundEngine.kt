package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.example.model.WeaponType
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * 44.1 kHz ballistic firearm DSP synthesizer.
 * Generates layered supersonic crack, chamber gas sub-bass blast, mechanical bolt clack,
 * and concrete early-reflection canyon echo tailored per firearm (M4A1, AK-47, AWM .338).
 */
class SoundEngine {
    private val sampleRate = 44100
    private val executor = Executors.newFixedThreadPool(4)

    @Volatile
    var volume: Float = 0.90f

    private val m4ShotPcm: ShortArray = synthesizeM4A1Shot()
    private val ak47ShotPcm: ShortArray = synthesizeAk47Shot()
    private val sniperShotPcm: ShortArray = synthesizeSniperShot()
    private val hitMarkerPcm: ShortArray = synthesizeHitThump(isHeadshot = false)
    private val headshotPcm: ShortArray = synthesizeHitThump(isHeadshot = true)
    private val reloadPcm: ShortArray = synthesizeReload()
    private val switchWeaponPcm: ShortArray = synthesizeWeaponSwitch()
    private val damagePcm: ShortArray = synthesizeDamage()

    fun playWeaponShot(weapon: WeaponType) {
        when (weapon) {
            WeaponType.M4A1 -> playClip(m4ShotPcm, 0.95f)
            WeaponType.AK47 -> playClip(ak47ShotPcm, 1.0f)
            WeaponType.SNIPER -> playClip(sniperShotPcm, 1.0f)
        }
    }

    fun playHitMarker() = playClip(hitMarkerPcm, 0.72f)
    fun playHeadshot() = playClip(headshotPcm, 0.92f)
    fun playReload() = playClip(reloadPcm, 0.78f)
    fun playWeaponSwitch() = playClip(switchWeaponPcm, 0.68f)
    fun playDamage() = playClip(damagePcm, 0.85f)

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

    /**
     * M4A1 5.56x45mm: razor-sharp supersonic whip crack + tight gas pressure thump + bolt snap + concrete echo.
     */
    private fun synthesizeM4A1Shot(): ShortArray {
        val count = (sampleRate * 0.34f).toInt()
        val dry = FloatArray(count)
        val rng = Random(101)
        var lpLow = 0f
        var lpMid = 0f
        var hpPrev = 0f
        var prevNoise = 0f

        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f

            // Low-pass filtered noise for real gunpowder gas thump (no tonal beep!)
            lpLow += (n - lpLow) * 0.032f
            // Mid-band explosive bark
            lpMid += (n - lpMid) * 0.22f
            // High-pass crack for supersonic muzzle transient
            val hp = 0.85f * (hpPrev + n - prevNoise)
            hpPrev = hp
            prevNoise = n

            val blastCrack = hp * exp(-t * 175f) * 1.65f
            val midBark = lpMid * exp(-t * 48f) * 1.45f
            val chestThump = lpLow * exp(-t * 22f) * 2.4f
            // Crisp metallic M4 bolt cycling click around 22ms
            val bolt = if (t in 0.018f..0.040f) {
                val dt = t - 0.018f
                hp * exp(-dt * 210f) * 0.45f
            } else 0f

            dry[i] = tanh(((blastCrack + midBark + chestThump + bolt) * 1.35f).toDouble()).toFloat()
        }
        return applyUrbanEchoAndNormalize(dry, echoGain = 0.36f, tailDecay = 9.5f)
    }

    /**
     * AK-47 7.62x39mm: brutal low-mid roar + heavy gas explosion + unmistakable steel bolt clank + wide echo.
     */
    private fun synthesizeAk47Shot(): ShortArray {
        val count = (sampleRate * 0.44f).toInt()
        val dry = FloatArray(count)
        val rng = Random(207)
        var lpSub = 0f
        var lpGrowl = 0f
        var lpMid = 0f
        var hpPrev = 0f
        var prevNoise = 0f

        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f

            lpSub += (n - lpSub) * 0.020f
            lpGrowl += (n - lpGrowl) * 0.085f
            lpMid += (n - lpMid) * 0.28f
            val hp = 0.82f * (hpPrev + n - prevNoise)
            hpPrev = hp
            prevNoise = n

            val crack = hp * exp(-t * 140f) * 1.45f
            val heavyBark = lpGrowl * exp(-t * 26f) * 2.65f
            val midPunch = lpMid * exp(-t * 36f) * 1.35f
            val subBoom = lpSub * exp(-t * 15f) * 3.1f

            // Heavy stamped-steel AK piston/bolt carrier clank at 28ms
            val clank = if (t in 0.024f..0.062f) {
                val dt = t - 0.024f
                val ring = (sin(2.0 * PI * 1120.0 * dt) + 0.6 * sin(2.0 * PI * 2480.0 * dt)).toFloat()
                (ring * 0.25f + hp * 0.45f) * exp(-dt * 120f)
            } else 0f

            dry[i] = tanh(((crack + heavyBark + midPunch + subBoom + clank) * 1.55f).toDouble()).toFloat()
        }
        return applyUrbanEchoAndNormalize(dry, echoGain = 0.44f, tailDecay = 7.2f)
    }

    /**
     * AWM .338 Lapua Magnum Sniper: thunderous cannon crack + deep pressure shockwave + long valley reverb + bolt cycle!
     */
    private fun synthesizeSniperShot(): ShortArray {
        val count = (sampleRate * 0.92f).toInt()
        val dry = FloatArray(count)
        val rng = Random(338)
        var lpSub = 0f
        var lpLow = 0f
        var lpMid = 0f
        var hpPrev = 0f
        var prevNoise = 0f

        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f

            lpSub += (n - lpSub) * 0.012f
            lpLow += (n - lpLow) * 0.055f
            lpMid += (n - lpMid) * 0.20f
            val hp = 0.88f * (hpPrev + n - prevNoise)
            hpPrev = hp
            prevNoise = n

            val whipCrack = hp * exp(-t * 95f) * 2.1f
            val cannonRoar = lpLow * exp(-t * 13f) * 3.2f
            val midBlast = lpMid * exp(-t * 22f) * 1.6f
            val shockwave = lpSub * exp(-t * 8.5f) * 3.8f

            // Mechanical sniper bolt-action cycle (lift-pull-push-lock between 0.44s and 0.80s)
            var boltCycle = 0f
            if (t in 0.44f..0.52f) {
                val dt = t - 0.44f
                boltCycle += (hp * 0.4f + sin(2.0 * PI * 1450.0 * dt).toFloat() * 0.14f) * exp(-dt * 75f)
            }
            if (t in 0.62f..0.74f) {
                val dt = t - 0.62f
                boltCycle += (hp * 0.45f + sin(2.0 * PI * 980.0 * dt).toFloat() * 0.16f) * exp(-dt * 60f)
            }

            dry[i] = tanh(((whipCrack + cannonRoar + midBlast + shockwave) * 1.75f).toDouble()).toFloat() + boltCycle * 0.55f
        }
        return applyUrbanEchoAndNormalize(dry, echoGain = 0.52f, tailDecay = 4.5f)
    }

    private fun applyUrbanEchoAndNormalize(dry: FloatArray, echoGain: Float, tailDecay: Float): ShortArray {
        val n = dry.size
        val wet = FloatArray(n)
        // Concrete courtyard early reflection taps (ms -> samples)
        val taps = intArrayOf(
            (sampleRate * 0.028f).toInt(),
            (sampleRate * 0.059f).toInt(),
            (sampleRate * 0.098f).toInt(),
            (sampleRate * 0.148f).toInt(),
            (sampleRate * 0.210f).toInt()
        )
        val tapWeights = floatArrayOf(0.55f, 0.42f, 0.30f, 0.20f, 0.13f)

        var lpEcho = 0f
        var peak = 0.001f
        for (i in 0 until n) {
            var echoSum = 0f
            for (k in taps.indices) {
                val idx = i - taps[k]
                if (idx >= 0) {
                    echoSum += dry[idx] * tapWeights[k]
                }
            }
            // Distant concrete echoes lose high frequencies
            lpEcho += (echoSum - lpEcho) * 0.14f
            val t = i.toFloat() / sampleRate
            val sample = dry[i] + lpEcho * echoGain * exp(-t * tailDecay * 0.35f)
            wet[i] = sample
            val a = kotlin.math.abs(sample)
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
        val duration = if (isHeadshot) 0.14f else 0.065f
        val count = (sampleRate * duration).toInt()
        val out = ShortArray(count)
        val rng = Random(if (isHeadshot) 777 else 555)
        var lp = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f
            lp += (n - lp) * 0.12f
            val sample = if (isHeadshot) {
                // Helmet ballistic crunch + crisp high confirmation
                val crunch = n * exp(-t * 95f) * 0.75f
                val helmetDink = sin(2.0 * PI * 2180.0 * t).toFloat() * exp(-t * 32f) * 0.55f
                val overtone = sin(2.0 * PI * 3240.0 * t).toFloat() * exp(-t * 45f) * 0.35f
                (crunch + helmetDink + overtone).coerceIn(-1f, 1f)
            } else {
                // Realistic ballistic vest thud + crisp tactile tick
                val thud = lp * exp(-t * 55f) * 1.3f
                val tick = n * exp(-t * 160f) * 0.5f
                (thud + tick).coerceIn(-1f, 1f)
            }
            out[i] = (sample * Short.MAX_VALUE * 0.85f).toInt().toShort()
        }
        return out
    }

    private fun synthesizeReload(): ShortArray {
        val count = (sampleRate * 0.42f).toInt()
        val out = ShortArray(count)
        val rng = Random(808)
        var hpPrev = 0f
        var prevN = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f
            val hp = 0.8f * (hpPrev + n - prevN)
            hpPrev = hp; prevN = n
            var s = 0f
            // Mag out click
            if (t < 0.07f) s += (hp * 0.6f + sin(2.0 * PI * 520.0 * t).toFloat() * 0.25f) * exp(-t * 75f)
            // Mag seat thud
            if (t in 0.16f..0.24f) {
                val dt = t - 0.16f
                s += (n * 0.5f + sin(2.0 * PI * 340.0 * dt).toFloat() * 0.35f) * exp(-dt * 65f)
            }
            // Bolt rack metallic snap
            if (t in 0.28f..0.40f) {
                val dt = t - 0.28f
                s += (hp * 0.8f + sin(2.0 * PI * 1350.0 * dt).toFloat() * 0.22f) * exp(-dt * 55f)
            }
            out[i] = (s.coerceIn(-1f, 1f) * Short.MAX_VALUE * 0.8f).toInt().toShort()
        }
        return out
    }

    private fun synthesizeWeaponSwitch(): ShortArray {
        val count = (sampleRate * 0.16f).toInt()
        val out = ShortArray(count)
        val rng = Random(909)
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val n = rng.nextFloat() * 2f - 1f
            val rustle = n * exp(-t * 28f) * 0.35f
            val latch = if (t > 0.05f) {
                val dt = t - 0.05f
                (n * 0.4f + sin(2.0 * PI * 1100.0 * dt).toFloat() * 0.25f) * exp(-dt * 85f)
            } else 0f
            out[i] = ((rustle + latch).coerceIn(-1f, 1f) * Short.MAX_VALUE * 0.75f).toInt().toShort()
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
}
