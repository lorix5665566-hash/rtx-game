package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class SoundEngine {
    private val sampleRate = 22050
    private val executor = Executors.newFixedThreadPool(2)

    @Volatile
    var volume: Float = 0.85f

    private val rifleShotPcm: ShortArray = synthesizeRifleShot()
    private val hitMarkerPcm: ShortArray = synthesizeHitMarker(isHeadshot = false)
    private val headshotPcm: ShortArray = synthesizeHitMarker(isHeadshot = true)
    private val reloadPcm: ShortArray = synthesizeReload()
    private val damagePcm: ShortArray = synthesizeDamage()

    fun playRifleShot() = playClip(rifleShotPcm, 0.95f)
    fun playHitMarker() = playClip(hitMarkerPcm, 0.70f)
    fun playHeadshot() = playClip(headshotPcm, 0.90f)
    fun playReload() = playClip(reloadPcm, 0.75f)
    fun playDamage() = playClip(damagePcm, 0.80f)

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
                val durationMs = (pcm.size * 1000L) / sampleRate + 30L
                Thread.sleep(durationMs)
                track.stop()
                track.release()
            }
        }
    }

    fun release() {
        executor.shutdownNow()
    }

    private fun synthesizeRifleShot(): ShortArray {
        val count = (sampleRate * 0.16f).toInt()
        val out = ShortArray(count)
        val rng = Random(42)
        var lp = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val noise = rng.nextFloat() * 2f - 1f
            lp += (noise - lp) * (if (t < 0.025f) 0.55f else 0.18f)
            val punch = sin(2.0 * PI * (150.0 * exp(-t * 32.0) + 48.0) * t).toFloat() * exp(-t * 28f)
            val crack = noise * exp(-t * 95f) * 0.75f
            val tail = lp * exp(-t * 16f) * 0.55f
            val sample = (punch * 0.9f + crack + tail).coerceIn(-1f, 1f)
            out[i] = (sample * Short.MAX_VALUE * 0.9f).toInt().toShort()
        }
        return out
    }

    private fun synthesizeHitMarker(isHeadshot: Boolean): ShortArray {
        val duration = if (isHeadshot) 0.14f else 0.065f
        val count = (sampleRate * duration).toInt()
        val out = ShortArray(count)
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val sample = if (isHeadshot) {
                val ping1 = sin(2.0 * PI * 1950.0 * t).toFloat() * exp(-t * 26f)
                val ping2 = sin(2.0 * PI * 2850.0 * t).toFloat() * exp(-t * 20f)
                (ping1 * 0.6f + ping2 * 0.55f).coerceIn(-1f, 1f)
            } else {
                val tick = sin(2.0 * PI * 1400.0 * t).toFloat() * exp(-t * 60f)
                tick.coerceIn(-1f, 1f)
            }
            out[i] = (sample * Short.MAX_VALUE * 0.85f).toInt().toShort()
        }
        return out
    }

    private fun synthesizeReload(): ShortArray {
        val count = (sampleRate * 0.22f).toInt()
        val out = ShortArray(count)
        val rng = Random(99)
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val click1 = if (t < 0.05f) {
                (sin(2.0 * PI * 680.0 * t).toFloat() + (rng.nextFloat() - 0.5f)) * exp(-t * 90f)
            } else 0f
            val click2 = if (t > 0.11f) {
                val dt = t - 0.11f
                (sin(2.0 * PI * 980.0 * dt).toFloat() + (rng.nextFloat() - 0.5f) * 0.8f) * exp(-dt * 75f)
            } else 0f
            val sample = (click1 * 0.7f + click2 * 0.85f).coerceIn(-1f, 1f)
            out[i] = (sample * Short.MAX_VALUE * 0.75f).toInt().toShort()
        }
        return out
    }

    private fun synthesizeDamage(): ShortArray {
        val count = (sampleRate * 0.15f).toInt()
        val out = ShortArray(count)
        for (i in 0 until count) {
            val t = i.toFloat() / sampleRate
            val thud = sin(2.0 * PI * (95.0 - t * 220.0).coerceAtLeast(35.0) * t).toFloat() * exp(-t * 20f)
            out[i] = (thud.coerceIn(-1f, 1f) * Short.MAX_VALUE * 0.85f).toInt().toShort()
        }
        return out
    }
}
