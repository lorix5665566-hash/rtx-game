package com.example.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class WorldBlock(
    val cx: Float,
    val cy: Float,
    val cz: Float,
    val sx: Float,
    val sy: Float,
    val sz: Float,
    val texture: MaterialTexture,
    val r: Float = 1f,
    val g: Float = 1f,
    val b: Float = 1f,
    val metallic: Float = 0.15f,
    val roughness: Float = 0.65f,
    val emissiveR: Float = 0f,
    val emissiveG: Float = 0f,
    val emissiveB: Float = 0f,
    val reflectivity: Float = 0.25f,
    val collidable: Boolean = true
) {
    val minX: Float get() = cx - sx * 0.5f
    val maxX: Float get() = cx + sx * 0.5f
    val minY: Float get() = cy - sy * 0.5f
    val maxY: Float get() = cy + sy * 0.5f
    val minZ: Float get() = cz - sz * 0.5f
    val maxZ: Float get() = cz + sz * 0.5f
}

/**
 * 44m x 44m rain-slicked cyber-industrial urban tactical plaza with multi-lane
 * reinforced concrete cover bunkers, military crates, pillars, and RTX neon light gantries.
 */
class ArenaWorld {
    val halfSize = 21.5f
    val blocks: List<WorldBlock> = buildArenaBlocks()
    val spawnPoints: List<Pair<Float, Float>> = listOf(
        Pair(-13.5f, -14.5f),
        Pair(0.0f, -16.0f),
        Pair(13.5f, -14.5f),
        Pair(-15.5f, -2.5f),
        Pair(15.5f, -2.5f),
        Pair(-12.5f, 9.5f),
        Pair(12.5f, 9.5f),
        Pair(0.0f, -8.5f)
    )

    private fun buildArenaBlocks(): List<WorldBlock> {
        val list = mutableListOf<WorldBlock>()
        val wallH = 5.2f
        val wallT = 1.4f
        val span = 44.4f

        // 4 Perimeter Reinforced Concrete Walls
        list += WorldBlock(0f, wallH * 0.5f, -22.2f, span, wallH, wallT, MaterialTexture.CONCRETE_WALL, 0.92f, 0.95f, 1.0f, 0.12f, 0.68f, reflectivity = 0.35f)
        list += WorldBlock(0f, wallH * 0.5f,  22.2f, span, wallH, wallT, MaterialTexture.CONCRETE_WALL, 0.92f, 0.95f, 1.0f, 0.12f, 0.68f, reflectivity = 0.35f)
        list += WorldBlock(-22.2f, wallH * 0.5f, 0f, wallT, wallH, span, MaterialTexture.CONCRETE_WALL, 0.92f, 0.95f, 1.0f, 0.12f, 0.68f, reflectivity = 0.35f)
        list += WorldBlock( 22.2f, wallH * 0.5f, 0f, wallT, wallH, span, MaterialTexture.CONCRETE_WALL, 0.92f, 0.95f, 1.0f, 0.12f, 0.68f, reflectivity = 0.35f)

        // Central Tactical Bunker & Split Cover Lanes
        list += WorldBlock(0f, 0.95f, -2.0f, 5.2f, 1.9f, 1.1f, MaterialTexture.CONCRETE_WALL, 0.85f, 0.90f, 0.95f, 0.15f, 0.62f, reflectivity = 0.30f)
        list += WorldBlock(-4.8f, 1.65f, -5.5f, 1.8f, 3.3f, 1.8f, MaterialTexture.CONCRETE_WALL, 0.88f, 0.92f, 0.98f, 0.15f, 0.60f, reflectivity = 0.35f)
        list += WorldBlock( 4.8f, 1.65f, -5.5f, 1.8f, 3.3f, 1.8f, MaterialTexture.CONCRETE_WALL, 0.88f, 0.92f, 0.98f, 0.15f, 0.60f, reflectivity = 0.35f)

        // Overhead Tactical Light Gantry Beam (non-collidable overhead arch)
        list += WorldBlock(0f, 3.45f, -5.5f, 11.4f, 0.45f, 0.9f, MaterialTexture.CRATE_STEEL, 0.55f, 0.60f, 0.68f, 0.72f, 0.34f, reflectivity = 0.50f, collidable = false)
        // Glowing RTX Emerald Light Strip on Central Gantry
        list += WorldBlock(0f, 3.18f, -5.5f, 7.6f, 0.14f, 0.24f, MaterialTexture.VISOR_GLASS, 0.2f, 1f, 0.75f, 0f, 1f, emissiveR = 0.0f, emissiveG = 2.6f, emissiveB = 1.8f, collidable = false)

        // Left & Right Flank Concrete Cover Barriers
        list += WorldBlock(-10.5f, 1.15f, -1.0f, 1.2f, 2.3f, 6.2f, MaterialTexture.CONCRETE_WALL, 0.88f, 0.90f, 0.96f, 0.12f, 0.64f, reflectivity = 0.30f)
        list += WorldBlock( 10.5f, 1.15f, -1.0f, 1.2f, 2.3f, 6.2f, MaterialTexture.CONCRETE_WALL, 0.88f, 0.90f, 0.96f, 0.12f, 0.64f, reflectivity = 0.30f)

        // Forward Bunker Walls (Enemy strongpoints)
        list += WorldBlock(-9.0f, 1.4f, -12.0f, 4.8f, 2.8f, 1.2f, MaterialTexture.CONCRETE_WALL, 0.85f, 0.88f, 0.94f, 0.14f, 0.64f, reflectivity = 0.30f)
        list += WorldBlock( 9.0f, 1.4f, -12.0f, 4.8f, 2.8f, 1.2f, MaterialTexture.CONCRETE_WALL, 0.85f, 0.88f, 0.94f, 0.14f, 0.64f, reflectivity = 0.30f)

        // Player-side Tactical Cover Blocks (waist-high & chest-high)
        list += WorldBlock(-5.5f, 0.68f, 6.5f, 3.4f, 1.36f, 0.95f, MaterialTexture.CONCRETE_WALL, 0.90f, 0.92f, 0.96f, 0.16f, 0.60f, reflectivity = 0.28f)
        list += WorldBlock( 5.5f, 0.68f, 6.5f, 3.4f, 1.36f, 0.95f, MaterialTexture.CONCRETE_WALL, 0.90f, 0.92f, 0.96f, 0.16f, 0.60f, reflectivity = 0.28f)

        // Heavy Industrial Military Steel Crates
        list += WorldBlock(-15.5f, 0.85f, -7.5f, 2.1f, 1.7f, 2.1f, MaterialTexture.CRATE_STEEL, 0.82f, 0.88f, 0.92f, 0.65f, 0.36f, reflectivity = 0.45f)
        list += WorldBlock(-15.2f, 2.35f, -7.7f, 1.5f, 1.3f, 1.5f, MaterialTexture.CRATE_STEEL, 0.88f, 0.84f, 0.78f, 0.65f, 0.36f, reflectivity = 0.45f)
        list += WorldBlock( 15.5f, 0.85f, -7.5f, 2.1f, 1.7f, 2.1f, MaterialTexture.CRATE_STEEL, 0.82f, 0.88f, 0.92f, 0.65f, 0.36f, reflectivity = 0.45f)
        list += WorldBlock( 2.8f, 0.65f, 1.8f, 1.4f, 1.3f, 1.4f, MaterialTexture.CRATE_STEEL, 0.76f, 0.82f, 0.88f, 0.70f, 0.32f, reflectivity = 0.48f)
        list += WorldBlock(-12.5f, 0.65f, 11.5f, 2.4f, 1.3f, 1.6f, MaterialTexture.CRATE_STEEL, 0.80f, 0.84f, 0.90f, 0.65f, 0.38f, reflectivity = 0.40f)
        list += WorldBlock( 12.5f, 0.65f, 11.5f, 2.4f, 1.3f, 1.6f, MaterialTexture.CRATE_STEEL, 0.80f, 0.84f, 0.90f, 0.65f, 0.38f, reflectivity = 0.40f)

        // Glowing Amber & Cyan Tactical Beacons on Pillars & Perimeter (reflecting on wet floor!)
        list += WorldBlock(-10.5f, 2.42f, -1.0f, 0.25f, 0.22f, 3.6f, MaterialTexture.VISOR_GLASS, 1f, 0.65f, 0.2f, 0f, 1f, emissiveR = 2.6f, emissiveG = 1.45f, emissiveB = 0.25f, collidable = false)
        list += WorldBlock( 10.5f, 2.42f, -1.0f, 0.25f, 0.22f, 3.6f, MaterialTexture.VISOR_GLASS, 0.2f, 0.85f, 1f, 0f, 1f, emissiveR = 0.25f, emissiveG = 1.95f, emissiveB = 2.8f, collidable = false)
        list += WorldBlock(0f, 4.4f, -21.3f, 12.0f, 0.26f, 0.25f, MaterialTexture.VISOR_GLASS, 1f, 0.6f, 0.2f, 0f, 1f, emissiveR = 2.2f, emissiveG = 1.2f, emissiveB = 0.2f, collidable = false)

        return list
    }

    fun resolveHorizontalCollision(x: Float, z: Float, radius: Float): Pair<Float, Float> {
        var rx = x.coerceIn(-halfSize + radius, halfSize - radius)
        var rz = z.coerceIn(-halfSize + radius, halfSize - radius)
        for (b in blocks) {
            if (!b.collidable) continue
            val closestX = rx.coerceIn(b.minX, b.maxX)
            val closestZ = rz.coerceIn(b.minZ, b.maxZ)
            val dx = rx - closestX
            val dz = rz - closestZ
            val distSq = dx * dx + dz * dz
            if (distSq < radius * radius) {
                if (distSq > 0.00001f) {
                    val dist = kotlin.math.sqrt(distSq)
                    val push = radius - dist
                    rx += (dx / dist) * push
                    rz += (dz / dist) * push
                } else {
                    // Inside box center fallback: push along shallowest axis
                    val penLeft = abs(rx - b.minX)
                    val penRight = abs(b.maxX - rx)
                    val penBack = abs(rz - b.minZ)
                    val penFront = abs(b.maxZ - rz)
                    val m = minOf(penLeft, penRight, penBack, penFront)
                    when (m) {
                        penLeft -> rx = b.minX - radius
                        penRight -> rx = b.maxX + radius
                        penBack -> rz = b.minZ - radius
                        else -> rz = b.maxZ + radius
                    }
                }
            }
        }
        return Pair(rx, rz)
    }

    /**
     * Ray-vs-AABB slab intersection distance against all collidable walls/cover.
     * Returns distance to closest hit or Float.MAX_VALUE if clear.
     */
    fun raycastWalls(
        ox: Float,
        oy: Float,
        oz: Float,
        dx: Float,
        dy: Float,
        dz: Float,
        maxDist: Float = 80f
    ): Float {
        var best = maxDist
        for (b in blocks) {
            if (!b.collidable) continue
            val t = intersectRayBox(ox, oy, oz, dx, dy, dz, b)
            if (t in 0.05f..best) {
                best = t
            }
        }
        return best
    }

    private fun intersectRayBox(
        ox: Float,
        oy: Float,
        oz: Float,
        dx: Float,
        dy: Float,
        dz: Float,
        b: WorldBlock
    ): Float {
        var tMin = 0.0f
        var tMax = 1000.0f

        if (abs(dx) < 1e-6f) {
            if (ox < b.minX || ox > b.maxX) return Float.MAX_VALUE
        } else {
            val inv = 1f / dx
            var t1 = (b.minX - ox) * inv
            var t2 = (b.maxX - ox) * inv
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
            tMin = max(tMin, t1)
            tMax = min(tMax, t2)
            if (tMin > tMax) return Float.MAX_VALUE
        }

        if (abs(dy) < 1e-6f) {
            if (oy < b.minY || oy > b.maxY) return Float.MAX_VALUE
        } else {
            val inv = 1f / dy
            var t1 = (b.minY - oy) * inv
            var t2 = (b.maxY - oy) * inv
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
            tMin = max(tMin, t1)
            tMax = min(tMax, t2)
            if (tMin > tMax) return Float.MAX_VALUE
        }

        if (abs(dz) < 1e-6f) {
            if (oz < b.minZ || oz > b.maxZ) return Float.MAX_VALUE
        } else {
            val inv = 1f / dz
            var t1 = (b.minZ - oz) * inv
            var t2 = (b.maxZ - oz) * inv
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
            tMin = max(tMin, t1)
            tMax = min(tMax, t2)
            if (tMin > tMax) return Float.MAX_VALUE
        }

        return if (tMin > 0f) tMin else Float.MAX_VALUE
    }
}
