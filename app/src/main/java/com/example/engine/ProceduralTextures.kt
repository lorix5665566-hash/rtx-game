package com.example.engine

import android.opengl.GLES30
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class MaterialTexture {
    WET_ASPHALT,
    CONCRETE_WALL,
    SOLDIER_ARMOR,
    SOLDIER_FABRIC,
    SOLDIER_SKIN,
    GUNMETAL,
    CRATE_STEEL,
    VISOR_GLASS
}

data class TexturePair(
    val albedoId: Int,
    val normalId: Int
)

class ProceduralTextures {
    private val textures = mutableMapOf<MaterialTexture, TexturePair>()

    fun initAll() {
        textures[MaterialTexture.WET_ASPHALT] = buildTexture(256) { u, v -> sampleWetAsphalt(u, v) }
        textures[MaterialTexture.CONCRETE_WALL] = buildTexture(256) { u, v -> sampleConcreteWall(u, v) }
        textures[MaterialTexture.SOLDIER_ARMOR] = buildTexture(128) { u, v -> sampleSoldierArmor(u, v) }
        textures[MaterialTexture.SOLDIER_FABRIC] = buildTexture(128) { u, v -> sampleSoldierFabric(u, v) }
        textures[MaterialTexture.SOLDIER_SKIN] = buildTexture(128) { u, v -> sampleSoldierSkin(u, v) }
        textures[MaterialTexture.GUNMETAL] = buildTexture(128) { u, v -> sampleGunmetal(u, v) }
        textures[MaterialTexture.CRATE_STEEL] = buildTexture(128) { u, v -> sampleCrateSteel(u, v) }
        textures[MaterialTexture.VISOR_GLASS] = buildTexture(64) { u, v -> sampleVisorGlass(u, v) }
    }

    fun get(type: MaterialTexture): TexturePair = textures.getValue(type)

    fun release() {
        val ids = textures.values.flatMap { listOf(it.albedoId, it.normalId) }.toIntArray()
        if (ids.isNotEmpty()) {
            GLES30.glDeleteTextures(ids.size, ids, 0)
        }
        textures.clear()
    }

    private data class PixelSample(
        val r: Float,
        val g: Float,
        val b: Float,
        val wetness: Float,
        val height: Float
    )

    private fun buildTexture(size: Int, sampler: (Float, Float) -> PixelSample): TexturePair {
        val heights = FloatArray(size * size)
        val albedoBytes = ByteArray(size * size * 4)

        for (y in 0 until size) {
            val v = y.toFloat() / size
            for (x in 0 until size) {
                val u = x.toFloat() / size
                val s = sampler(u, v)
                val idx = y * size + x
                heights[idx] = s.height
                val base = idx * 4
                albedoBytes[base] = toByte(s.r)
                albedoBytes[base + 1] = toByte(s.g)
                albedoBytes[base + 2] = toByte(s.b)
                albedoBytes[base + 3] = toByte(s.wetness)
            }
        }

        // Derive crisp tangent-space normal map via Sobel height gradient
        val normalBytes = ByteArray(size * size * 4)
        val strength = 3.2f
        for (y in 0 until size) {
            val ym = (y - 1 + size) % size
            val yp = (y + 1) % size
            for (x in 0 until size) {
                val xm = (x - 1 + size) % size
                val xp = (x + 1) % size
                val hL = heights[y * size + xm]
                val hR = heights[y * size + xp]
                val hD = heights[ym * size + x]
                val hU = heights[yp * size + x]
                val dx = (hL - hR) * strength
                val dy = (hD - hU) * strength
                val dz = 1.0f
                val len = sqrt(dx * dx + dy * dy + dz * dz)
                val base = (y * size + x) * 4
                normalBytes[base] = toByte((dx / len) * 0.5f + 0.5f)
                normalBytes[base + 1] = toByte((dy / len) * 0.5f + 0.5f)
                normalBytes[base + 2] = toByte((dz / len) * 0.5f + 0.5f)
                normalBytes[base + 3] = 255.toByte()
            }
        }

        return TexturePair(
            albedoId = uploadRgba(size, albedoBytes),
            normalId = uploadRgba(size, normalBytes)
        )
    }

    private fun uploadRgba(size: Int, data: ByteArray): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        val buf = ByteBuffer.allocateDirect(data.size).put(data)
        buf.position(0)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA,
            size,
            size,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            buf
        )
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        return id
    }

    private fun sampleWetAsphalt(u: Float, v: Float): PixelSample {
        val grain = fbm(u * 26f, v * 26f)
        val fine = valueNoise(u * 96f, v * 96f)
        val puddleField = fbm(u * 3.5f + 1.3f, v * 3.5f + 2.7f)
        val wetness = ((puddleField - 0.38f) * 2.6f).coerceIn(0f, 1f)

        // Tactical expansion tile joints
        val seamX = abs(u - 0.5f) < 0.008f || u < 0.008f || u > 0.992f
        val seamY = abs(v - 0.5f) < 0.008f || v < 0.008f || v > 0.992f
        val seam = if (seamX || seamY) 0.55f else 1.0f

        val base = (0.14f + grain * 0.12f + fine * 0.05f) * seam
        val h = (grain * 0.6f + fine * 0.35f) * (if (seam < 1f) 0.25f else 1f) * (1f - wetness * 0.85f)
        return PixelSample(base * 0.96f, base * 1.0f, base * 1.08f, wetness, h)
    }

    private fun sampleConcreteWall(u: Float, v: Float): PixelSample {
        val grunge = fbm(u * 10f + 4.1f, v * 10f + 1.9f)
        val streak = fbm(u * 28f, v * 4f)
        val fine = valueNoise(u * 64f, v * 64f)

        val panelLine = (abs(u - 0.5f) < 0.01f) || (abs(v - 0.5f) < 0.012f) || u < 0.01f || v < 0.012f
        val bolt = ((abs(u - 0.06f) < 0.014f || abs(u - 0.44f) < 0.014f || abs(u - 0.56f) < 0.014f || abs(u - 0.94f) < 0.014f) &&
            (abs(v - 0.1f) < 0.016f || abs(v - 0.9f) < 0.016f))

        // Subtle amber tactical hazard stripe near lower edge
        val inStripe = v in 0.14f..0.20f
        val diag = ((u * 8f + v * 8f) % 1f) < 0.5f

        var r = 0.24f + grunge * 0.14f - streak * 0.06f + fine * 0.04f
        var g = 0.26f + grunge * 0.14f - streak * 0.06f + fine * 0.04f
        var b = 0.29f + grunge * 0.15f - streak * 0.05f + fine * 0.04f
        var h = 0.5f + grunge * 0.3f + fine * 0.15f

        if (inStripe) {
            if (diag) {
                r = 0.62f * (0.75f + grunge * 0.3f)
                g = 0.42f * (0.75f + grunge * 0.3f)
                b = 0.10f * (0.75f + grunge * 0.3f)
            } else {
                r *= 0.45f
                g *= 0.45f
                b *= 0.48f
            }
        }
        if (panelLine) {
            r *= 0.55f; g *= 0.55f; b *= 0.58f; h = 0.15f
        }
        if (bolt) {
            r *= 0.42f; g *= 0.42f; b *= 0.46f; h = 0.1f
        }
        val wetness = (streak * 0.55f).coerceIn(0f, 0.7f)
        return PixelSample(r, g, b, wetness, h)
    }

    private fun sampleSoldierArmor(u: Float, v: Float): PixelSample {
        // Kevlar hexagonal/woven grid + MOLLE tactical webbing bands
        val weave = (sin(u * PI * 48.0) * sin(v * PI * 48.0)).toFloat() * 0.5f + 0.5f
        val molle = ((v * 6f) % 1f) in 0.25f..0.48f
        val seam = abs(u - 0.5f) < 0.02f || abs(u - 0.2f) < 0.015f || abs(u - 0.8f) < 0.015f
        val wear = fbm(u * 12f, v * 12f)

        var base = 0.15f + weave * 0.06f + wear * 0.05f
        var h = 0.45f + weave * 0.22f
        if (molle) {
            base *= 0.78f
            h += 0.24f
        }
        if (seam) {
            base *= 0.60f
            h -= 0.18f
        }
        return PixelSample(base * 0.94f, base * 1.0f, base * 0.98f, 0.25f, h)
    }

    private fun sampleSoldierFabric(u: Float, v: Float): PixelSample {
        // Dark urban ripstop tactical multicam cloth
        val ripstop = (if (((u * 40f).toInt() + (v * 40f).toInt()) % 2 == 0) 0.03f else 0f)
        val camo1 = fbm(u * 5f + 2f, v * 5f + 7f)
        val camo2 = fbm(u * 9f + 9f, v * 9f + 3f)
        val patch = when {
            camo1 > 0.62f -> 0.24f
            camo2 < 0.36f -> 0.11f
            else -> 0.17f
        }
        val folds = sin(v * PI * 6.0 + camo1 * 3.0).toFloat() * 0.03f
        val r = (patch + ripstop + folds) * 0.92f
        val g = (patch + ripstop + folds) * 0.98f
        val b = (patch + ripstop + folds) * 1.04f
        val h = 0.45f + ripstop * 3f + folds * 4f + camo2 * 0.15f
        return PixelSample(r, g, b, 0.1f, h)
    }

    private fun sampleSoldierSkin(u: Float, v: Float): PixelSample {
        val pore = valueNoise(u * 56f, v * 56f)
        val tone = fbm(u * 6f, v * 6f)
        val r = 0.64f + tone * 0.08f + pore * 0.03f
        val g = 0.47f + tone * 0.07f + pore * 0.025f
        val b = 0.38f + tone * 0.05f + pore * 0.02f
        val h = 0.5f + pore * 0.08f + tone * 0.06f
        return PixelSample(r, g, b, 0.18f, h)
    }

    private fun sampleGunmetal(u: Float, v: Float): PixelSample {
        // Anisotropic brushed dark titanium / machined steel
        val brush = fbm(u * 4f, v * 64f)
        val scratch = valueNoise(u * 18f, v * 96f)
        val rail = if (v in 0.42f..0.58f && ((u * 24f) % 1f) < 0.45f) 0.08f else 0f
        val base = 0.18f + brush * 0.10f + scratch * 0.04f + rail
        val h = 0.5f + brush * 0.18f + rail * 2.5f
        return PixelSample(base * 0.95f, base * 0.99f, base * 1.08f, 0.55f, h)
    }

    private fun sampleCrateSteel(u: Float, v: Float): PixelSample {
        val rib = (sin(u * PI * 14.0).toFloat() * 0.5f + 0.5f)
        val frame = u < 0.08f || u > 0.92f || v < 0.08f || v > 0.92f
        val grime = fbm(u * 10f, v * 10f)
        var r = 0.21f + rib * 0.08f + grime * 0.07f
        var g = 0.25f + rib * 0.08f + grime * 0.06f
        var b = 0.27f + rib * 0.08f + grime * 0.06f
        var h = 0.35f + rib * 0.42f
        if (frame) {
            r *= 0.72f; g *= 0.72f; b *= 0.75f; h = 0.85f
        }
        return PixelSample(r, g, b, 0.35f, h)
    }

    private fun sampleVisorGlass(u: Float, v: Float): PixelSample {
        val sheen = sin((u + v) * PI * 2.0).toFloat() * 0.04f
        return PixelSample(0.06f + sheen, 0.14f + sheen, 0.20f + sheen, 1.0f, 0.5f)
    }

    private fun toByte(f: Float): Byte = (f.coerceIn(0f, 1f) * 255f).toInt().toByte()

    private fun hash2(x: Int, y: Int): Float {
        val n = (x * 374761393 + y * 668265263) xor (x * 1274126177)
        val m = (n xor (n ushr 13)) * 1274126177
        return ((m xor (m ushr 16)) and 0x7fffffff).toFloat() / 0x7fffffff.toFloat()
    }

    private fun valueNoise(x: Float, y: Float): Float {
        val ix = kotlin.math.floor(x).toInt()
        val iy = kotlin.math.floor(y).toInt()
        val fx = x - ix
        val fy = y - iy
        val ux = fx * fx * (3f - 2f * fx)
        val uy = fy * fy * (3f - 2f * fy)
        val a = hash2(ix, iy)
        val b = hash2(ix + 1, iy)
        val c = hash2(ix, iy + 1)
        val d = hash2(ix + 1, iy + 1)
        return (a + (b - a) * ux) + ((c - a) + (a - b - c + d) * ux) * uy
    }

    private fun fbm(x: Float, y: Float): Float {
        var v = 0f
        var amp = 0.5f
        var fx = x
        var fy = y
        for (i in 0 until 4) {
            v += amp * valueNoise(fx, fy)
            fx *= 2.03f
            fy *= 2.03f
            amp *= 0.5f
        }
        return v
    }
}
