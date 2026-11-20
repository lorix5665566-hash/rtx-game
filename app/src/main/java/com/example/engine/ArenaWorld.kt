package com.example.engine

import com.example.model.ArenaMapId
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

data class MapAtmosphere(
    val floorTexture: MaterialTexture,
    val floorColor: FloatArray,
    val floorRoughness: Float,
    val floorReflectivity: Float,
    val sunDir: FloatArray,
    val sunColor: FloatArray,
    val skyTopColor: FloatArray,
    val skyHorizonColor: FloatArray,
    val skySunGlow: FloatArray,
    val fogColor: FloatArray,
    val fogDensity: Float,
    val lightPositions: FloatArray,
    val lightColors: FloatArray,
    val lightRadii: FloatArray
)

/**
 * 3 distinct tactical 3D arenas:
 * - INDUSTRIAL (Промзона): rain-slicked night urban industrial plaza with RTX wet asphalt & neon gantries
 * - HARBOR (Порт-Сити): misty shipping container yard with towering blue/rust containers & tight lanes
 * - RUINS (Форпост): golden-hour desert sandstone outpost with ruined bunkers & long sniper sightlines
 */
class ArenaWorld(val mapId: ArenaMapId = ArenaMapId.INDUSTRIAL) {
    val halfSize = 21.5f
    val atmosphere: MapAtmosphere = buildAtmosphere(mapId)
    val blocks: List<WorldBlock> = when (mapId) {
        ArenaMapId.INDUSTRIAL -> buildIndustrialBlocks()
        ArenaMapId.HARBOR -> buildHarborBlocks()
        ArenaMapId.RUINS -> buildRuinsBlocks()
    }
    val spawnPoints: List<Pair<Float, Float>> = when (mapId) {
        ArenaMapId.INDUSTRIAL -> listOf(
            Pair(-13.5f, -14.5f), Pair(0.0f, -16.0f), Pair(13.5f, -14.5f),
            Pair(-15.5f, -2.5f), Pair(15.5f, -2.5f),
            Pair(-12.5f, 9.5f), Pair(12.5f, 9.5f), Pair(0.0f, -8.5f)
        )
        ArenaMapId.HARBOR -> listOf(
            Pair(-15.0f, -15.5f), Pair(-2.0f, -16.5f), Pair(14.5f, -15.0f),
            Pair(-16.0f, 1.0f), Pair(16.0f, 1.0f),
            Pair(-8.5f, -8.0f), Pair(8.5f, -8.0f), Pair(0.0f, -12.0f)
        )
        ArenaMapId.RUINS -> listOf(
            Pair(-14.5f, -16.0f), Pair(0.0f, -17.5f), Pair(14.5f, -16.0f),
            Pair(-16.0f, -5.0f), Pair(16.0f, -5.0f),
            Pair(-9.5f, -10.5f), Pair(9.5f, -10.5f), Pair(0.0f, -9.0f)
        )
    }

    private fun buildAtmosphere(id: ArenaMapId): MapAtmosphere = when (id) {
        ArenaMapId.INDUSTRIAL -> MapAtmosphere(
            floorTexture = MaterialTexture.WET_ASPHALT,
            floorColor = floatArrayOf(0.92f, 0.96f, 1.0f),
            floorRoughness = 0.44f,
            floorReflectivity = 1.0f,
            sunDir = floatArrayOf(0.42f, 0.68f, -0.60f),
            sunColor = floatArrayOf(0.95f, 1.08f, 1.28f),
            skyTopColor = floatArrayOf(0.018f, 0.032f, 0.068f),
            skyHorizonColor = floatArrayOf(0.08f, 0.13f, 0.20f),
            skySunGlow = floatArrayOf(0.55f, 0.72f, 0.95f),
            fogColor = floatArrayOf(0.045f, 0.070f, 0.115f),
            fogDensity = 0.00050f,
            lightPositions = floatArrayOf(
                0.0f, 3.0f, -5.5f,
                -10.5f, 2.5f, -1.0f,
                10.5f, 2.5f, -1.0f,
                0.0f, 1.5f, 10.0f
            ),
            lightColors = floatArrayOf(
                0.0f, 2.1f, 1.55f,
                2.3f, 1.35f, 0.28f,
                0.28f, 1.80f, 2.5f,
                0f, 0f, 0f
            ),
            lightRadii = floatArrayOf(18.0f, 16.0f, 16.0f, 0.1f)
        )
        ArenaMapId.HARBOR -> MapAtmosphere(
            floorTexture = MaterialTexture.WET_ASPHALT,
            floorColor = floatArrayOf(0.84f, 0.90f, 0.94f),
            floorRoughness = 0.36f,
            floorReflectivity = 1.1f,
            sunDir = floatArrayOf(-0.35f, 0.72f, -0.58f),
            sunColor = floatArrayOf(0.95f, 1.04f, 1.15f),
            skyTopColor = floatArrayOf(0.06f, 0.10f, 0.16f),
            skyHorizonColor = floatArrayOf(0.20f, 0.28f, 0.36f),
            skySunGlow = floatArrayOf(0.70f, 0.85f, 0.98f),
            fogColor = floatArrayOf(0.11f, 0.16f, 0.22f),
            fogDensity = 0.00072f,
            lightPositions = floatArrayOf(
                -9.0f, 3.6f, -6.0f,
                9.0f, 3.6f, -6.0f,
                0.0f, 3.8f, 4.0f,
                0.0f, 1.5f, 10.0f
            ),
            lightColors = floatArrayOf(
                2.2f, 1.45f, 0.55f,
                0.65f, 1.65f, 2.2f,
                1.8f, 1.9f, 2.0f,
                0f, 0f, 0f
            ),
            lightRadii = floatArrayOf(20.0f, 20.0f, 18.0f, 0.1f)
        )
        ArenaMapId.RUINS -> MapAtmosphere(
            floorTexture = MaterialTexture.DESERT_STONE,
            floorColor = floatArrayOf(0.95f, 0.86f, 0.72f),
            floorRoughness = 0.86f,
            floorReflectivity = 0.15f,
            sunDir = floatArrayOf(0.62f, 0.38f, -0.68f),
            sunColor = floatArrayOf(1.65f, 1.18f, 0.78f),
            skyTopColor = floatArrayOf(0.14f, 0.20f, 0.34f),
            skyHorizonColor = floatArrayOf(0.62f, 0.38f, 0.22f),
            skySunGlow = floatArrayOf(1.25f, 0.68f, 0.28f),
            fogColor = floatArrayOf(0.28f, 0.20f, 0.14f),
            fogDensity = 0.00038f,
            lightPositions = floatArrayOf(
                0.0f, 3.5f, -10.0f,
                -11.0f, 2.6f, 2.0f,
                11.0f, 2.6f, 2.0f,
                0.0f, 1.5f, 10.0f
            ),
            lightColors = floatArrayOf(
                2.1f, 1.35f, 0.60f,
                1.8f, 1.05f, 0.40f,
                1.8f, 1.05f, 0.40f,
                0f, 0f, 0f
            ),
            lightRadii = floatArrayOf(22.0f, 16.0f, 16.0f, 0.1f)
        )
    }

    private fun buildIndustrialBlocks(): List<WorldBlock> {
        val list = mutableListOf<WorldBlock>()
        addPerimeter(list, MaterialTexture.CONCRETE_WALL, 0.92f, 0.95f, 1.0f, 0.32f)

        // Central Tactical Bunker & Split Cover Lanes
        list += WorldBlock(0f, 0.95f, -2.0f, 5.2f, 1.9f, 1.1f, MaterialTexture.CONCRETE_WALL, 0.85f, 0.90f, 0.95f, 0.15f, 0.62f, reflectivity = 0.30f)
        list += WorldBlock(-4.8f, 1.65f, -5.5f, 1.8f, 3.3f, 1.8f, MaterialTexture.CONCRETE_WALL, 0.88f, 0.92f, 0.98f, 0.15f, 0.60f, reflectivity = 0.35f)
        list += WorldBlock( 4.8f, 1.65f, -5.5f, 1.8f, 3.3f, 1.8f, MaterialTexture.CONCRETE_WALL, 0.88f, 0.92f, 0.98f, 0.15f, 0.60f, reflectivity = 0.35f)
        list += WorldBlock(0f, 3.45f, -5.5f, 11.4f, 0.45f, 0.9f, MaterialTexture.CRATE_STEEL, 0.55f, 0.60f, 0.68f, 0.72f, 0.34f, reflectivity = 0.50f, collidable = false)
        list += WorldBlock(0f, 3.18f, -5.5f, 7.6f, 0.14f, 0.24f, MaterialTexture.VISOR_GLASS, 0.2f, 1f, 0.75f, 0f, 1f, emissiveR = 0.0f, emissiveG = 2.4f, emissiveB = 1.7f, collidable = false)

        // Flank Concrete Barriers & Forward Bunkers
        list += WorldBlock(-10.5f, 1.15f, -1.0f, 1.2f, 2.3f, 6.2f, MaterialTexture.CONCRETE_WALL, 0.88f, 0.90f, 0.96f, 0.12f, 0.64f, reflectivity = 0.30f)
        list += WorldBlock( 10.5f, 1.15f, -1.0f, 1.2f, 2.3f, 6.2f, MaterialTexture.CONCRETE_WALL, 0.88f, 0.90f, 0.96f, 0.12f, 0.64f, reflectivity = 0.30f)
        list += WorldBlock(-9.0f, 1.4f, -12.0f, 4.8f, 2.8f, 1.2f, MaterialTexture.CONCRETE_WALL, 0.85f, 0.88f, 0.94f, 0.14f, 0.64f, reflectivity = 0.30f)
        list += WorldBlock( 9.0f, 1.4f, -12.0f, 4.8f, 2.8f, 1.2f, MaterialTexture.CONCRETE_WALL, 0.85f, 0.88f, 0.94f, 0.14f, 0.64f, reflectivity = 0.30f)
        list += WorldBlock(-5.5f, 0.68f, 6.5f, 3.4f, 1.36f, 0.95f, MaterialTexture.CONCRETE_WALL, 0.90f, 0.92f, 0.96f, 0.16f, 0.60f, reflectivity = 0.28f)
        list += WorldBlock( 5.5f, 0.68f, 6.5f, 3.4f, 1.36f, 0.95f, MaterialTexture.CONCRETE_WALL, 0.90f, 0.92f, 0.96f, 0.16f, 0.60f, reflectivity = 0.28f)

        // Military Steel Crates & Beacons
        list += WorldBlock(-15.5f, 0.85f, -7.5f, 2.1f, 1.7f, 2.1f, MaterialTexture.CRATE_STEEL, 0.82f, 0.88f, 0.92f, 0.65f, 0.36f, reflectivity = 0.45f)
        list += WorldBlock(-15.2f, 2.35f, -7.7f, 1.5f, 1.3f, 1.5f, MaterialTexture.CRATE_STEEL, 0.88f, 0.84f, 0.78f, 0.65f, 0.36f, reflectivity = 0.45f)
        list += WorldBlock( 15.5f, 0.85f, -7.5f, 2.1f, 1.7f, 2.1f, MaterialTexture.CRATE_STEEL, 0.82f, 0.88f, 0.92f, 0.65f, 0.36f, reflectivity = 0.45f)
        list += WorldBlock(-10.5f, 2.42f, -1.0f, 0.25f, 0.22f, 3.6f, MaterialTexture.VISOR_GLASS, 1f, 0.65f, 0.2f, 0f, 1f, emissiveR = 2.4f, emissiveG = 1.4f, emissiveB = 0.25f, collidable = false)
        list += WorldBlock( 10.5f, 2.42f, -1.0f, 0.25f, 0.22f, 3.6f, MaterialTexture.VISOR_GLASS, 0.2f, 0.85f, 1f, 0f, 1f, emissiveR = 0.25f, emissiveG = 1.85f, emissiveB = 2.6f, collidable = false)
        return list
    }

    private fun buildHarborBlocks(): List<WorldBlock> {
        val list = mutableListOf<WorldBlock>()
        addPerimeter(list, MaterialTexture.CONCRETE_WALL, 0.82f, 0.88f, 0.94f, 0.38f)

        // Towering Shipping Containers (2.6m tall x 6.4m long x 2.5m wide) creating tight urban lanes
        list += WorldBlock(-8.5f, 1.35f, -5.5f, 2.6f, 2.7f, 6.8f, MaterialTexture.CONTAINER_BLUE, 1f, 1f, 1f, 0.55f, 0.42f, reflectivity = 0.40f)
        list += WorldBlock(-8.5f, 4.05f, -5.8f, 2.5f, 2.6f, 6.2f, MaterialTexture.CONTAINER_RUST, 1f, 1f, 1f, 0.52f, 0.46f, reflectivity = 0.35f, collidable = false)

        list += WorldBlock( 8.5f, 1.35f, -5.5f, 2.6f, 2.7f, 6.8f, MaterialTexture.CONTAINER_RUST, 1f, 1f, 1f, 0.52f, 0.46f, reflectivity = 0.38f)
        list += WorldBlock( 8.5f, 4.05f, -5.2f, 2.5f, 2.6f, 6.2f, MaterialTexture.CONTAINER_BLUE, 1f, 1f, 1f, 0.55f, 0.42f, reflectivity = 0.40f, collidable = false)

        // Diagonal/Cross Center Container & Flanking Crates
        list += WorldBlock(0.0f, 1.35f, -11.0f, 6.8f, 2.7f, 2.6f, MaterialTexture.CONTAINER_BLUE, 0.95f, 0.98f, 1.0f, 0.55f, 0.42f, reflectivity = 0.40f)
        list += WorldBlock(-14.5f, 1.35f, 4.5f, 2.6f, 2.7f, 6.4f, MaterialTexture.CONTAINER_RUST, 1f, 1f, 1f, 0.50f, 0.48f, reflectivity = 0.35f)
        list += WorldBlock( 14.5f, 1.35f, 4.5f, 2.6f, 2.7f, 6.4f, MaterialTexture.CONTAINER_BLUE, 1f, 1f, 1f, 0.55f, 0.42f, reflectivity = 0.38f)

        // Low Cargo Crates for tactical peek cover in the center & player side
        list += WorldBlock(-2.8f, 0.72f, 1.5f, 2.2f, 1.44f, 1.6f, MaterialTexture.CRATE_STEEL, 0.85f, 0.88f, 0.92f, 0.62f, 0.38f, reflectivity = 0.42f)
        list += WorldBlock( 3.2f, 0.72f, -1.0f, 2.2f, 1.44f, 1.6f, MaterialTexture.CRATE_STEEL, 0.88f, 0.84f, 0.76f, 0.62f, 0.38f, reflectivity = 0.42f)
        list += WorldBlock( 0.0f, 0.68f, 7.5f, 4.4f, 1.36f, 1.1f, MaterialTexture.CONCRETE_WALL, 0.86f, 0.90f, 0.94f, 0.14f, 0.62f, reflectivity = 0.30f)

        // Harbor Floodlight Bars
        list += WorldBlock(-8.5f, 5.45f, -5.8f, 0.3f, 0.25f, 4.5f, MaterialTexture.VISOR_GLASS, 1f, 0.85f, 0.5f, 0f, 1f, emissiveR = 2.4f, emissiveG = 1.6f, emissiveB = 0.6f, collidable = false)
        list += WorldBlock( 8.5f, 5.45f, -5.2f, 0.3f, 0.25f, 4.5f, MaterialTexture.VISOR_GLASS, 0.6f, 0.9f, 1f, 0f, 1f, emissiveR = 0.7f, emissiveG = 1.8f, emissiveB = 2.5f, collidable = false)
        return list
    }

    private fun buildRuinsBlocks(): List<WorldBlock> {
        val list = mutableListOf<WorldBlock>()
        addPerimeter(list, MaterialTexture.DESERT_STONE, 1.0f, 0.92f, 0.80f, 0.10f)

        // Desert Outpost Ruined Watch-Walls & Sniper Cover Pillars
        list += WorldBlock(-11.5f, 1.6f, -11.5f, 5.6f, 3.2f, 1.3f, MaterialTexture.DESERT_STONE, 1.0f, 0.92f, 0.82f, 0.06f, 0.82f, reflectivity = 0.08f)
        list += WorldBlock( 11.5f, 1.6f, -11.5f, 5.6f, 3.2f, 1.3f, MaterialTexture.DESERT_STONE, 1.0f, 0.92f, 0.82f, 0.06f, 0.82f, reflectivity = 0.08f)

        // Central Ruined Arch Pillars (leaves long central sniper sightline!)
        list += WorldBlock(-5.4f, 2.0f, -4.5f, 2.0f, 4.0f, 2.0f, MaterialTexture.DESERT_STONE, 0.96f, 0.88f, 0.78f, 0.06f, 0.84f, reflectivity = 0.08f)
        list += WorldBlock( 5.4f, 2.0f, -4.5f, 2.0f, 4.0f, 2.0f, MaterialTexture.DESERT_STONE, 0.96f, 0.88f, 0.78f, 0.06f, 0.84f, reflectivity = 0.08f)
        list += WorldBlock( 0.0f, 3.9f, -4.5f, 12.6f, 0.65f, 1.4f, MaterialTexture.DESERT_STONE, 0.94f, 0.86f, 0.76f, 0.06f, 0.86f, reflectivity = 0.06f, collidable = false)

        // Low Ruined Sand/Concrete Barricades & Military Supply Crates
        list += WorldBlock(-7.5f, 0.68f, 5.5f, 4.2f, 1.36f, 1.0f, MaterialTexture.DESERT_STONE, 0.98f, 0.90f, 0.80f, 0.06f, 0.84f, reflectivity = 0.08f)
        list += WorldBlock( 7.5f, 0.68f, 5.5f, 4.2f, 1.36f, 1.0f, MaterialTexture.DESERT_STONE, 0.98f, 0.90f, 0.80f, 0.06f, 0.84f, reflectivity = 0.08f)
        list += WorldBlock(-14.5f, 1.1f, 0.5f, 1.5f, 2.2f, 5.5f, MaterialTexture.DESERT_STONE, 0.95f, 0.86f, 0.74f, 0.06f, 0.84f, reflectivity = 0.08f)
        list += WorldBlock( 14.5f, 1.1f, 0.5f, 1.5f, 2.2f, 5.5f, MaterialTexture.DESERT_STONE, 0.95f, 0.86f, 0.74f, 0.06f, 0.84f, reflectivity = 0.08f)
        list += WorldBlock( 0.0f, 0.62f, -1.0f, 2.6f, 1.24f, 1.2f, MaterialTexture.CRATE_STEEL, 0.76f, 0.72f, 0.60f, 0.50f, 0.50f, reflectivity = 0.22f)
        list += WorldBlock( 0.0f, 1.25f, -15.5f, 4.6f, 2.5f, 1.4f, MaterialTexture.DESERT_STONE, 0.96f, 0.88f, 0.78f, 0.06f, 0.84f, reflectivity = 0.08f)
        return list
    }

    private fun addPerimeter(
        list: MutableList<WorldBlock>,
        tex: MaterialTexture,
        r: Float,
        g: Float,
        b: Float,
        refl: Float
    ) {
        val wallH = 5.2f
        val wallT = 1.4f
        val span = 44.4f
        list += WorldBlock(0f, wallH * 0.5f, -22.2f, span, wallH, wallT, tex, r, g, b, 0.10f, 0.72f, reflectivity = refl)
        list += WorldBlock(0f, wallH * 0.5f,  22.2f, span, wallH, wallT, tex, r, g, b, 0.10f, 0.72f, reflectivity = refl)
        list += WorldBlock(-22.2f, wallH * 0.5f, 0f, wallT, wallH, span, tex, r, g, b, 0.10f, 0.72f, reflectivity = refl)
        list += WorldBlock( 22.2f, wallH * 0.5f, 0f, wallT, wallH, span, tex, r, g, b, 0.10f, 0.72f, reflectivity = refl)
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
                    val penLeft = abs(rx - b.minX)
                    val penRight = abs(b.maxX - rx)
                    val penBack = abs(rz - b.minZ)
                    val penFront = abs(b.maxZ - rz)
                    when (minOf(penLeft, penRight, penBack, penFront)) {
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
            if (t in 0.05f..best) best = t
        }
        return best
    }

    private fun intersectRayBox(
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
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
            tMin = max(tMin, t1); tMax = min(tMax, t2)
            if (tMin > tMax) return Float.MAX_VALUE
        }

        if (abs(dy) < 1e-6f) {
            if (oy < b.minY || oy > b.maxY) return Float.MAX_VALUE
        } else {
            val inv = 1f / dy
            var t1 = (b.minY - oy) * inv
            var t2 = (b.maxY - oy) * inv
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
            tMin = max(tMin, t1); tMax = min(tMax, t2)
            if (tMin > tMax) return Float.MAX_VALUE
        }

        if (abs(dz) < 1e-6f) {
            if (oz < b.minZ || oz > b.maxZ) return Float.MAX_VALUE
        } else {
            val inv = 1f / dz
            var t1 = (b.minZ - oz) * inv
            var t2 = (b.maxZ - oz) * inv
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
            tMin = max(tMin, t1); tMax = min(tMax, t2)
            if (tMin > tMax) return Float.MAX_VALUE
        }
        return if (tMin > 0f) tMin else Float.MAX_VALUE
    }
}
