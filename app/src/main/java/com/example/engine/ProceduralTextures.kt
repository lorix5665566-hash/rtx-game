package com.example.engine

import android.opengl.GLES30
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

enum class MaterialTexture {
    // High-realism weapon materials (NO toy/wooden look!)
    GUNMETAL_STEEL,
    TACTICAL_POLYMER,
    CHROME_STEEL,
    WALNUT_STOCK,
    C4_PACK,
    VISOR_GLASS,

    // CS2 Character materials (Спецназ CT & Террорист T)
    CT_UNIFORM,
    VEST_CT,
    T_SHIRT_PLAID,
    T_CARGO_PANTS,
    T_BALACLAVA,
    VEST_TAN,
    SOLDIER_SKIN,
    LEATHER_BOOT,

    // CS2 Mirage & Arena architecture
    MIRAGE_FLOOR,
    MIRAGE_PLASTER,
    MIRAGE_TILE_TRIM,
    WOOD_CRATE,
    WET_ASPHALT,
    CONCRETE_WALL,
    CRATE_STEEL,
    CONTAINER_BLUE,
    CONTAINER_RUST,

    // Volumetric muzzle fire sprite
    MUZZLE_FIRE
}

data class TexturePair(
    val albedoId: Int,
    val normalId: Int
)

class ProceduralTextures {
    private val textures = mutableMapOf<MaterialTexture, TexturePair>()

    fun initAll() {
        // Weapon & gear textures (256x256 crisp micro-detail!)
        textures[MaterialTexture.GUNMETAL_STEEL] = buildTexture(256) { u, v -> sampleGunmetalSteel(u, v) }
        textures[MaterialTexture.TACTICAL_POLYMER] = buildTexture(256) { u, v -> sampleTacticalPolymer(u, v) }
        textures[MaterialTexture.CHROME_STEEL] = buildTexture(256) { u, v -> sampleChromeSteel(u, v) }
        textures[MaterialTexture.WALNUT_STOCK] = buildTexture(256) { u, v -> sampleWalnutStock(u, v) }
        textures[MaterialTexture.C4_PACK] = buildTexture(128) { u, v -> sampleC4Pack(u, v) }
        textures[MaterialTexture.VISOR_GLASS] = buildTexture(64) { u, v -> sampleVisorGlass(u, v) }

        // Character fabrics & gear
        textures[MaterialTexture.CT_UNIFORM] = buildTexture(256) { u, v -> sampleCtUniform(u, v) }
        textures[MaterialTexture.VEST_CT] = buildTexture(256) { u, v -> sampleVest(u, v, isTan = false) }
        textures[MaterialTexture.T_SHIRT_PLAID] = buildTexture(256) { u, v -> sampleTShirtPlaid(u, v) }
        textures[MaterialTexture.T_CARGO_PANTS] = buildTexture(256) { u, v -> sampleTCargoPants(u, v) }
        textures[MaterialTexture.T_BALACLAVA] = buildTexture(128) { u, v -> sampleTBalaclava(u, v) }
        textures[MaterialTexture.VEST_TAN] = buildTexture(256) { u, v -> sampleVest(u, v, isTan = true) }
        textures[MaterialTexture.SOLDIER_SKIN] = buildTexture(128) { u, v -> sampleSoldierSkin(u, v) }
        textures[MaterialTexture.LEATHER_BOOT] = buildTexture(128) { u, v -> sampleLeatherBoot(u, v) }

        // CS2 Mirage & Arena maps
        textures[MaterialTexture.MIRAGE_FLOOR] = buildTexture(256) { u, v -> sampleMirageFloor(u, v) }
        textures[MaterialTexture.MIRAGE_PLASTER] = buildTexture(256) { u, v -> sampleMiragePlaster(u, v) }
        textures[MaterialTexture.MIRAGE_TILE_TRIM] = buildTexture(256) { u, v -> sampleMirageTileTrim(u, v) }
        textures[MaterialTexture.WOOD_CRATE] = buildTexture(256) { u, v -> sampleWoodCrate(u, v) }
        textures[MaterialTexture.WET_ASPHALT] = buildTexture(256) { u, v -> sampleWetAsphalt(u, v) }
        textures[MaterialTexture.CONCRETE_WALL] = buildTexture(256) { u, v -> sampleConcreteWall(u, v) }
        textures[MaterialTexture.CRATE_STEEL] = buildTexture(128) { u, v -> sampleCrateSteel(u, v) }
        textures[MaterialTexture.CONTAINER_BLUE] = buildTexture(128) { u, v -> sampleContainer(u, v, isRust = false) }
        textures[MaterialTexture.CONTAINER_RUST] = buildTexture(128) { u, v -> sampleContainer(u, v, isRust = true) }

        // Volumetric fire texture (clamped UV so flame edges never wrap!)
        textures[MaterialTexture.MUZZLE_FIRE] = buildFlameTexture(256)
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
        val gloss: Float,
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
                albedoBytes[base + 3] = toByte(s.gloss)
            }
        }

        val normalBytes = ByteArray(size * size * 4)
        val strength = 3.4f
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
            albedoId = uploadRgba(size, albedoBytes, clamp = false),
            normalId = uploadRgba(size, normalBytes, clamp = false)
        )
    }

    /**
     * Generates an authentic irregular white-hot plasma & jagged orange propellant flame
     * sprite for split-second additive gunfire muzzle flash (zero blocky cylinders!).
     */
    private fun buildFlameTexture(size: Int): TexturePair {
        val data = ByteArray(size * size * 4)
        for (y in 0 until size) {
            val v = y.toFloat() / (size - 1) // 0 = muzzle exit, 1 = forward tip
            for (x in 0 until size) {
                val u = x.toFloat() / (size - 1) // 0.5 = center axis
                val cx = (u - 0.5f) * 2f
                val cy = (v - 0.18f) * 1.25f

                // Radial distance from bright muzzle core + longitudinal jet stretch
                val radial = sqrt(cx * cx * 2.1f + cy * cy * 0.82f)
                val angle = atan2(cy.toDouble(), cx.toDouble()).toFloat()

                // Irregular spiky plasma flame tongues via multi-frequency angular FBM
                val rays = 0.55f + 0.45f * (
                    sin(angle * 7f + 0.4f) * 0.45f +
                    cos(angle * 13f - 1.1f) * 0.30f +
                    sin(angle * 19f + 2.3f) * 0.25f
                )
                val turb = fbm(u * 9f + 2.1f, v * 11f + 0.7f)
                val flameShape = ((1f - radial / (0.58f + rays * 0.42f + turb * 0.22f)) * 1.45f).coerceIn(0f, 1f)
                // Soft edge mask so quad borders are 100% invisible
                val edgeFade = (1f - abs(cx)).coerceIn(0f, 1f) * sin(v * PI).toFloat().coerceIn(0f, 1f)
                val density = (flameShape * flameShape * (0.35f + 0.65f * edgeFade)).coerceIn(0f, 1f)

                // White-hot core -> bright yellow-gold -> roaring deep orange flame tips
                val core = exp(-((cx * cx * 9.5f) + ((v - 0.16f) * (v - 0.16f) * 14f)).toDouble()).toFloat()
                val r = (density * 1.45f + core * 1.2f).coerceIn(0f, 1f)
                val g = (density * density * 0.88f + core * 1.15f).coerceIn(0f, 1f)
                val b = (density * density * density * 0.28f + core * 0.95f).coerceIn(0f, 1f)
                val a = (density * 0.92f + core * 0.85f).coerceIn(0f, 1f)

                val idx = (y * size + x) * 4
                data[idx] = toByte(r)
                data[idx + 1] = toByte(g)
                data[idx + 2] = toByte(b)
                data[idx + 3] = toByte(a)
            }
        }
        val id = uploadRgba(size, data, clamp = true)
        return TexturePair(id, id)
    }

    private fun uploadRgba(size: Int, data: ByteArray, clamp: Boolean): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        val wrap = if (clamp) GLES30.GL_CLAMP_TO_EDGE else GLES30.GL_REPEAT
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        val buf = ByteBuffer.allocateDirect(data.size).put(data)
        buf.position(0)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
            size, size, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf
        )
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        return id
    }

    // --- AUTHENTIC FIREARM PBR MATERIALS ---

    private fun sampleGunmetalSteel(u: Float, v: Float): PixelSample {
        // Cold blued/Parkerized forged steel with fine brushed machining lines, micro-scratches & receiver serrations
        val brush = fbm(u * 6f, v * 96f)
        val wear = fbm(u * 14f + 3f, v * 14f + 5f)
        val scratch = valueNoise(u * 24f, v * 140f)
        val edgeWear = if (u < 0.05f || u > 0.95f || v < 0.05f || v > 0.95f) 0.14f else 0f
        val serration = if (v in 0.38f..0.62f && ((u * 32f) % 1f) < 0.42f) 0.055f else 0f

        val base = 0.20f + brush * 0.08f + wear * 0.07f + scratch * 0.035f + edgeWear + serration
        val gloss = (0.58f + edgeWear * 1.8f + brush * 0.20f).coerceIn(0.2f, 0.96f)
        val h = 0.50f + brush * 0.16f + serration * 3.8f + scratch * 0.08f
        return PixelSample(base * 0.94f, base * 0.98f, base * 1.07f, gloss, h)
    }

    private fun sampleTacticalPolymer(u: Float, v: Float): PixelSample {
        // Matte fiberglass-reinforced black weapon polymer with realistic micro-stippling grip texture
        val stipple = valueNoise(u * 120f, v * 120f)
        val macro = fbm(u * 10f, v * 10f)
        val rib = if (((v * 18f) % 1f) < 0.25f) 0.03f else 0f
        val base = 0.13f + stipple * 0.045f + macro * 0.04f + rib
        val h = 0.45f + stipple * 0.32f + rib * 3.2f
        return PixelSample(base * 0.98f, base * 1.0f, base * 1.04f, 0.26f + macro * 0.14f, h)
    }

    private fun sampleChromeSteel(u: Float, v: Float): PixelSample {
        // Brushed satin stainless steel for Desert Eagle, knife blade, bolt carrier
        val grain = fbm(u * 5f, v * 110f)
        val micro = valueNoise(u * 40f, v * 140f)
        val base = 0.64f + grain * 0.16f + micro * 0.06f
        return PixelSample(base * 0.96f, base * 0.99f, base * 1.04f, 0.92f, 0.50f + grain * 0.12f)
    }

    private fun sampleWalnutStock(u: Float, v: Float): PixelSample {
        // Deep dark oiled Russian walnut laminate (strictly for AK-47 wood furniture — rich dark mahogany, NOT flat light wood)
        val warp = fbm(u * 5f, v * 14f) * 2.1f
        val rings = sin((v * 28f + warp) * PI.toFloat()) * 0.5f + 0.5f
        val pores = valueNoise(u * 110f, v * 28f)
        val r = 0.28f + rings * 0.17f + pores * 0.03f
        val g = 0.12f + rings * 0.08f + pores * 0.015f
        val b = 0.055f + rings * 0.035f + pores * 0.01f
        val h = 0.48f + rings * 0.16f + pores * 0.14f
        return PixelSample(r, g, b, 0.48f + rings * 0.18f, h)
    }

    private fun sampleC4Pack(u: Float, v: Float): PixelSample {
        // Olive/grey C4 explosive block with keypad grid & taped straps
        val tape = abs(u - 0.5f) < 0.08f || abs(v - 0.5f) < 0.08f
        val keypad = u in 0.22f..0.78f && v in 0.22f..0.78f
        val keyCell = keypad && (((u * 18f) % 1f) in 0.15f..0.85f) && (((v * 18f) % 1f) in 0.15f..0.85f)
        val noise = fbm(u * 16f, v * 16f)
        return when {
            keyCell -> PixelSample(0.24f, 0.26f, 0.28f, 0.35f, 0.78f)
            keypad -> PixelSample(0.14f, 0.16f, 0.17f, 0.28f, 0.62f)
            tape -> PixelSample(0.16f, 0.16f, 0.15f, 0.45f, 0.68f)
            else -> PixelSample(0.38f + noise * 0.08f, 0.37f + noise * 0.07f, 0.30f + noise * 0.05f, 0.18f, 0.45f + noise * 0.15f)
        }
    }

    private fun sampleVisorGlass(u: Float, v: Float): PixelSample {
        val sheen = sin((u + v) * PI * 2.0).toFloat() * 0.05f
        return PixelSample(0.10f + sheen, 0.14f + sheen, 0.19f + sheen, 0.96f, 0.5f)
    }

    // --- CS2 CHARACTER MATERIALS (CT СПЕЦНАЗ & T ТЕРРОРИСТ) ---

    private fun sampleCtUniform(u: Float, v: Float): PixelSample {
        // Deep navy-slate SAS / SWAT ripstop BDU fabric with realistic weave & seam folds
        val weave = if (((u * 64f).toInt() + (v * 64f).toInt()) % 2 == 0) 0.018f else 0f
        val folds = sin(v * PI * 9.0 + fbm(u * 6f, v * 6f) * 3.2).toFloat() * 0.028f
        val wear = fbm(u * 12f, v * 12f)
        val r = 0.13f + wear * 0.05f + weave + folds
        val g = 0.17f + wear * 0.06f + weave + folds
        val b = 0.24f + wear * 0.08f + weave + folds
        return PixelSample(r, g, b, 0.08f, 0.46f + weave * 4f + folds * 5f)
    }

    private fun sampleTShirtPlaid(u: Float, v: Float): PixelSample {
        // Sunlit warm tan/cream checkered tactical shirt matching the Terrorist in the user's CS2 screenshot!
        val stripeX = sin(u * PI * 14.0).toFloat() * 0.5f + 0.5f
        val stripeY = sin(v * PI * 14.0).toFloat() * 0.5f + 0.5f
        val plaid = if (stripeX > 0.72f || stripeY > 0.72f) -0.10f else 0.04f
        val fabric = fbm(u * 18f, v * 18f)
        val fold = sin(v * PI * 8.0 + fabric * 2.6).toFloat() * 0.04f
        val r = 0.68f + plaid + fabric * 0.09f + fold
        val g = 0.60f + plaid * 0.95f + fabric * 0.08f + fold
        val b = 0.52f + plaid * 0.90f + fabric * 0.07f + fold
        return PixelSample(r, g, b, 0.05f, 0.48f + fold * 4.5f + fabric * 0.12f)
    }

    private fun sampleTCargoPants(u: Float, v: Float): PixelSample {
        // Rugged olive-brown cargo trousers matching the user's attached screenshot!
        val twill = valueNoise(u * 80f, v * 40f)
        val fold = sin(v * PI * 10.0 + fbm(u * 5f, v * 5f) * 3.5).toFloat() * 0.038f
        val dust = fbm(u * 9f, v * 9f)
        val r = 0.30f + dust * 0.09f + twill * 0.03f + fold
        val g = 0.26f + dust * 0.08f + twill * 0.025f + fold
        val b = 0.18f + dust * 0.06f + twill * 0.02f + fold
        return PixelSample(r, g, b, 0.05f, 0.46f + fold * 5f + twill * 0.12f)
    }

    private fun sampleTBalaclava(u: Float, v: Float): PixelSample {
        // Dark ribbed knitted ski-mask fabric matching the user's attached screenshot!
        val rib = sin(u * PI * 36.0).toFloat() * 0.5f + 0.5f
        val knit = sin(v * PI * 48.0).toFloat() * 0.5f + 0.5f
        val wear = fbm(u * 10f, v * 10f)
        val base = 0.15f + rib * 0.045f + knit * 0.025f + wear * 0.04f
        return PixelSample(base * 1.15f, base * 0.95f, base * 0.80f, 0.04f, 0.35f + rib * 0.38f + knit * 0.15f)
    }

    private fun sampleVest(u: Float, v: Float, isTan: Boolean): PixelSample {
        // Authentic matte Cordura 1000D nylon / Kevlar plate carrier with MOLLE webbing & stitching
        val weave = (sin(u * PI * 64.0) * sin(v * PI * 64.0)).toFloat() * 0.5f + 0.5f
        val molle = ((v * 6f) % 1f) in 0.24f..0.48f
        val stitch = abs(u - 0.5f) < 0.015f || abs(u - 0.2f) < 0.012f || abs(u - 0.8f) < 0.012f
        val wear = fbm(u * 12f, v * 12f)

        var r = if (isTan) 0.58f else 0.17f
        var g = if (isTan) 0.53f else 0.19f
        var b = if (isTan) 0.42f else 0.22f
        val mod = 0.86f + weave * 0.09f + wear * 0.14f
        r *= mod; g *= mod; b *= mod
        var h = 0.45f + weave * 0.22f
        if (molle) {
            r *= 0.86f; g *= 0.86f; b *= 0.86f; h += 0.28f
        }
        if (stitch) {
            r *= 0.68f; g *= 0.68f; b *= 0.68f; h -= 0.18f
        }
        return PixelSample(r, g, b, 0.10f, h)
    }

    private fun sampleSoldierSkin(u: Float, v: Float): PixelSample {
        val pore = valueNoise(u * 72f, v * 72f)
        val tone = fbm(u * 7f, v * 7f)
        val r = 0.72f + tone * 0.10f + pore * 0.03f
        val g = 0.53f + tone * 0.08f + pore * 0.025f
        val b = 0.41f + tone * 0.06f + pore * 0.02f
        return PixelSample(r, g, b, 0.14f, 0.50f + pore * 0.08f + tone * 0.05f)
    }

    private fun sampleLeatherBoot(u: Float, v: Float): PixelSample {
        val grain = fbm(u * 22f, v * 22f)
        val scuff = valueNoise(u * 48f, v * 48f)
        val base = 0.28f + grain * 0.12f + scuff * 0.05f
        return PixelSample(base * 1.15f, base * 0.88f, base * 0.62f, 0.34f, 0.48f + grain * 0.22f)
    }

    // --- CS2 MIRAGE ARCHITECTURE (MATCHING USER'S ATTACHED SCREENSHOT!) ---

    private fun sampleMirageFloor(u: Float, v: Float): PixelSample {
        // Warm sunlit Moroccan sandstone courtyard paving tiles with grout seams and fine sand dust
        val tileU = (u * 4f) % 1f
        val tileV = (v * 4f) % 1f
        val row = (v * 4f).toInt()
        val staggeredU = ((u * 4f + if (row % 2 == 1) 0.5f else 0f) % 1f)
        val seam = staggeredU < 0.028f || staggeredU > 0.972f || tileV < 0.028f || tileV > 0.972f

        val stone = fbm(u * 14f + 2.3f, v * 14f + 1.1f)
        val fine = valueNoise(u * 84f, v * 84f)
        val tileTint = hash2((u * 4f).toInt(), row) * 0.08f - 0.04f

        var r = 0.74f + stone * 0.14f + fine * 0.04f + tileTint
        var g = 0.66f + stone * 0.13f + fine * 0.035f + tileTint
        var b = 0.54f + stone * 0.10f + fine * 0.03f + tileTint * 0.8f
        var h = 0.55f + stone * 0.26f + fine * 0.12f
        if (seam) {
            r *= 0.70f; g *= 0.68f; b *= 0.65f; h = 0.16f
        }
        return PixelSample(r, g, b, 0.12f, h)
    }

    private fun sampleMiragePlaster(u: Float, v: Float): PixelSample {
        // Sun-baked Moroccan sandstone stucco plaster with subtle brick underlay & weathering
        val plaster = fbm(u * 10f + 5f, v * 10f + 2f)
        val fine = valueNoise(u * 68f, v * 68f)
        val stain = fbm(u * 20f, v * 4f)
        val lowerTrim = v < 0.12f

        var r = 0.76f + plaster * 0.13f - stain * 0.05f + fine * 0.03f
        var g = 0.64f + plaster * 0.12f - stain * 0.05f + fine * 0.03f
        var b = 0.50f + plaster * 0.10f - stain * 0.04f + fine * 0.025f
        var h = 0.52f + plaster * 0.28f + fine * 0.14f
        if (lowerTrim) {
            r *= 0.84f; g *= 0.82f; b *= 0.80f; h += 0.15f
        }
        return PixelSample(r, g, b, 0.08f, h)
    }

    private fun sampleMirageTileTrim(u: Float, v: Float): PixelSample {
        // Iconic blue-and-gold Moroccan geometric tile frieze seen directly behind the character in the screenshot!
        val plaster = fbm(u * 12f, v * 12f)
        val inBand = v in 0.58f..0.86f
        if (!inBand) {
            return sampleMiragePlaster(u, v)
        }
        val bv = (v - 0.58f) / 0.28f
        val bu = (u * 8f) % 1f
        val diamond = abs(bu - 0.5f) + abs(bv - 0.5f)
        val border = bv < 0.10f || bv > 0.90f
        return when {
            border -> PixelSample(0.68f, 0.50f, 0.26f, 0.28f, 0.72f) // Terracotta/gold border
            diamond < 0.32f -> PixelSample(0.22f + plaster * 0.08f, 0.44f + plaster * 0.10f, 0.64f + plaster * 0.10f, 0.42f, 0.66f) // Moroccan cobalt blue
            diamond < 0.44f -> PixelSample(0.84f, 0.76f, 0.62f, 0.35f, 0.58f) // Cream star outline
            else -> PixelSample(0.36f + plaster * 0.08f, 0.54f + plaster * 0.08f, 0.66f + plaster * 0.08f, 0.38f, 0.52f)
        }
    }

    private fun sampleWoodCrate(u: Float, v: Float): PixelSample {
        // Slatted military/shipping crate planks (Mirage Triple Box, Default, Tetris)
        val frame = u < 0.09f || u > 0.91f || v < 0.09f || v > 0.91f
        val diag = abs(u - v) < 0.075f
        val plankSeam = abs((v * 5f) % 1f - 0.5f) > 0.46f
        val grain = fbm(u * 18f, v * 4f)
        var r = 0.54f + grain * 0.16f
        var g = 0.40f + grain * 0.12f
        var b = 0.24f + grain * 0.08f
        var h = 0.46f + grain * 0.20f
        if (frame || diag) {
            r *= 0.88f; g *= 0.86f; b *= 0.84f; h = 0.82f
        } else if (plankSeam) {
            r *= 0.55f; g *= 0.55f; b *= 0.55f; h = 0.15f
        }
        return PixelSample(r, g, b, 0.14f, h)
    }

    private fun sampleWetAsphalt(u: Float, v: Float): PixelSample {
        val grain = fbm(u * 26f, v * 26f)
        val fine = valueNoise(u * 96f, v * 96f)
        val puddleField = fbm(u * 3.5f + 1.3f, v * 3.5f + 2.7f)
        val wetness = ((puddleField - 0.38f) * 2.6f).coerceIn(0f, 1f)
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
        var r = 0.30f + grunge * 0.14f - streak * 0.06f + fine * 0.04f
        var g = 0.32f + grunge * 0.14f - streak * 0.06f + fine * 0.04f
        var b = 0.34f + grunge * 0.15f - streak * 0.05f + fine * 0.04f
        var h = 0.5f + grunge * 0.3f + fine * 0.15f
        if (panelLine) {
            r *= 0.58f; g *= 0.58f; b *= 0.60f; h = 0.16f
        }
        return PixelSample(r, g, b, (streak * 0.45f).coerceIn(0f, 0.6f), h)
    }

    private fun sampleCrateSteel(u: Float, v: Float): PixelSample {
        val rib = sin(u * PI * 14.0).toFloat() * 0.5f + 0.5f
        val frame = u < 0.08f || u > 0.92f || v < 0.08f || v > 0.92f
        val grime = fbm(u * 10f, v * 10f)
        var r = 0.22f + rib * 0.08f + grime * 0.07f
        var g = 0.26f + rib * 0.08f + grime * 0.06f
        var b = 0.26f + rib * 0.08f + grime * 0.06f
        var h = 0.35f + rib * 0.42f
        if (frame) {
            r *= 0.72f; g *= 0.72f; b *= 0.75f; h = 0.85f
        }
        return PixelSample(r, g, b, 0.32f, h)
    }

    private fun sampleContainer(u: Float, v: Float, isRust: Boolean): PixelSample {
        val corr = sin(u * PI * 22.0).toFloat() * 0.5f + 0.5f
        val rustNoise = fbm(u * 8f + 5f, v * 8f + 2f)
        val trim = v < 0.07f || v > 0.93f || u < 0.05f || u > 0.95f
        var r: Float; var g: Float; var b: Float
        if (isRust) {
            r = 0.44f + corr * 0.12f + rustNoise * 0.10f
            g = 0.20f + corr * 0.06f + rustNoise * 0.05f
            b = 0.14f + corr * 0.05f
        } else {
            r = 0.14f + corr * 0.06f + rustNoise * 0.07f
            g = 0.26f + corr * 0.10f + rustNoise * 0.05f
            b = 0.40f + corr * 0.14f
        }
        var h = 0.25f + corr * 0.58f
        if (trim) {
            r *= 0.68f; g *= 0.68f; b *= 0.70f; h = 0.88f
        }
        return PixelSample(r, g, b, 0.28f, h)
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
