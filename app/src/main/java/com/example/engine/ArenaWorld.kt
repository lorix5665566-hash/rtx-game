package com.example.engine

import com.example.model.ArenaMapId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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
    val metallic: Float = 0.12f,
    val roughness: Float = 0.72f,
    val emissiveR: Float = 0f,
    val emissiveG: Float = 0f,
    val emissiveB: Float = 0f,
    val reflectivity: Float = 0.20f,
    val collidable: Boolean = true
) {
    val minX: Float get() = cx - sx * 0.5f
    val maxX: Float get() = cx + sx * 0.5f
    val minY: Float get() = cy - sy * 0.5f
    val maxY: Float get() = cy + sy * 0.5f
    val minZ: Float get() = cz - sz * 0.5f
    val maxZ: Float get() = cz + sz * 0.5f
}

data class BombsiteZone(
    val label: String, // "A" or "B"
    val cx: Float,
    val cz: Float,
    val radius: Float
) {
    fun contains(x: Float, z: Float): Boolean {
        val dx = x - cx
        val dz = z - cz
        return dx * dx + dz * dz <= radius * radius
    }
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
 * Large tactical CS2-scale arenas featuring:
 * - MIRAGE (de_mirage, 78m x 72m): T Spawn, A Ramp, Palace, Site A (Triple Box, Default, Ticket, Stairs, Jungle),
 *   Mid (Top Mid, Window, Connector, Catwalk/Short), B Apartments, Site B (Van, Bench, Market), CT Spawn.
 * - INDUSTRIAL (Промзона, 52m x 52m): Wet asphalt night RTX complex with Bombsites A & B.
 * - HARBOR (Порт-Сити, 52m x 52m): Misty shipping container terminal with Bombsites A & B.
 */
class ArenaWorld(val mapId: ArenaMapId = ArenaMapId.MIRAGE) {
    val halfWidth: Float = if (mapId == ArenaMapId.MIRAGE) 39.0f else 26.0f
    val halfDepth: Float = if (mapId == ArenaMapId.MIRAGE) 36.0f else 26.0f
    val atmosphere: MapAtmosphere = buildAtmosphere(mapId)

    val blocks: List<WorldBlock> = when (mapId) {
        ArenaMapId.MIRAGE -> buildMirageBlocks()
        ArenaMapId.INDUSTRIAL -> buildIndustrialBlocks()
        ArenaMapId.HARBOR -> buildHarborBlocks()
    }

    val bombsites: List<BombsiteZone> = when (mapId) {
        ArenaMapId.MIRAGE -> listOf(
            BombsiteZone("A", -23.0f, -12.0f, 6.2f),
            BombsiteZone("B",  23.0f, -14.0f, 6.2f)
        )
        ArenaMapId.INDUSTRIAL -> listOf(
            BombsiteZone("A", -14.0f, -12.0f, 5.2f),
            BombsiteZone("B",  14.0f, -12.0f, 5.2f)
        )
        ArenaMapId.HARBOR -> listOf(
            BombsiteZone("A", -15.0f, -11.0f, 5.2f),
            BombsiteZone("B",  15.0f, -11.0f, 5.2f)
        )
    }

    val tSpawns: List<Pair<Float, Float>> = when (mapId) {
        ArenaMapId.MIRAGE -> listOf(
            Pair(0.0f, 29.5f), Pair(-4.5f, 29.0f), Pair(4.5f, 29.0f),
            Pair(-2.2f, 26.5f), Pair(2.2f, 26.5f)
        )
        else -> listOf(
            Pair(0.0f, 20.5f), Pair(-4.0f, 20.0f), Pair(4.0f, 20.0f),
            Pair(-2.0f, 18.0f), Pair(2.0f, 18.0f)
        )
    }

    val ctSpawns: List<Pair<Float, Float>> = when (mapId) {
        ArenaMapId.MIRAGE -> listOf(
            Pair(0.0f, -29.5f), Pair(-5.0f, -28.5f), Pair(5.0f, -28.5f),
            Pair(-2.5f, -26.0f), Pair(2.5f, -26.0f)
        )
        else -> listOf(
            Pair(0.0f, -20.5f), Pair(-4.0f, -20.0f), Pair(4.0f, -20.0f),
            Pair(-2.0f, -18.0f), Pair(2.0f, -18.0f)
        )
    }

    // Tactical lane waypoints for bots attacking Site A (index 0) or Site B (index 1)
    val tAttackRoutes: List<List<Pair<Float, Float>>> = when (mapId) {
        ArenaMapId.MIRAGE -> listOf(
            // Route to Site A via A Ramp / Tetris
            listOf(Pair(-16f, 22f), Pair(-28f, 12f), Pair(-27f, 0f), Pair(-23f, -11f)),
            // Route to Site B via B Apartments
            listOf(Pair(16f, 22f), Pair(29f, 12f), Pair(28f, -1f), Pair(23f, -13f)),
            // Mid-to-Connector split to A
            listOf(Pair(0f, 16f), Pair(-2f, 3f), Pair(-11f, -6f), Pair(-22f, -12f))
        )
        else -> listOf(
            listOf(Pair(-14f, 12f), Pair(-14f, 0f), Pair(-14f, -11f)),
            listOf(Pair(14f, 12f), Pair(14f, 0f), Pair(14f, -11f))
        )
    }

    val ctDefenseRoutes: List<List<Pair<Float, Float>>> = when (mapId) {
        ArenaMapId.MIRAGE -> listOf(
            // Hold Site A (Ticket / Triple / Jungle)
            listOf(Pair(-14f, -24f), Pair(-19f, -18f), Pair(-21f, -11f)),
            // Hold Site B (Market / Van / Short)
            listOf(Pair(14f, -24f), Pair(19f, -18f), Pair(21f, -13f)),
            // Hold Mid / Window
            listOf(Pair(0f, -20f), Pair(0f, -12f), Pair(-4f, -6f))
        )
        else -> listOf(
            listOf(Pair(-14f, -16f), Pair(-13f, -9f)),
            listOf(Pair(14f, -16f), Pair(13f, -9f))
        )
    }

    fun getCalloutRu(x: Float, z: Float): String {
        if (mapId != ArenaMapId.MIRAGE) {
            for (site in bombsites) {
                if (site.contains(x, z)) return "ТОЧКА ${site.label}"
            }
            return mapId.titleRu
        }
        return when {
            z > 21f -> "Т-СПАВН"
            z < -23f -> "КТ-СПАВН"
            bombsites[0].contains(x, z) -> "ТОЧКА A"
            bombsites[1].contains(x, z) -> "ТОЧКА B"
            x < -15f && z in 0f..21f -> "РАМПА A / КОВЁР"
            x < -13f && z in -23f..0f -> "ПЛЭНТ A / ДЖАНГЛ"
            x > 15f && z in -2f..21f -> "АПАРТАМЕНТЫ B"
            x > 13f && z in -23f..-2f -> "ПЛЭНТ B / МАГАЗИН"
            x in -13f..-4f && z in -12f..2f -> "КОННЕКТОР"
            x in 4f..13f && z in -14f..2f -> "ШОРТ"
            z < -9f -> "ОКНО МИДА"
            else -> "МИД"
        }
    }

    private fun buildAtmosphere(id: ArenaMapId): MapAtmosphere = when (id) {
        ArenaMapId.MIRAGE -> MapAtmosphere(
            floorTexture = MaterialTexture.MIRAGE_FLOOR,
            floorColor = floatArrayOf(1.04f, 0.98f, 0.90f),
            floorRoughness = 0.78f,
            floorReflectivity = 0.14f,
            sunDir = floatArrayOf(0.54f, 0.66f, -0.52f),
            sunColor = floatArrayOf(1.62f, 1.44f, 1.18f), // Crisp sunlit CS2 Mirage afternoon sun!
            skyTopColor = floatArrayOf(0.18f, 0.42f, 0.78f),
            skyHorizonColor = floatArrayOf(0.76f, 0.78f, 0.80f),
            skySunGlow = floatArrayOf(1.35f, 1.15f, 0.78f),
            fogColor = floatArrayOf(0.54f, 0.50f, 0.44f),
            fogDensity = 0.00014f,
            lightPositions = floatArrayOf(
                -23.0f, 4.2f, -12.0f,
                 23.0f, 4.2f, -14.0f,
                  0.0f, 4.5f,   4.0f,
                  0.0f, 1.5f,  26.0f
            ),
            lightColors = floatArrayOf(
                1.45f, 1.25f, 0.95f,
                1.45f, 1.25f, 0.95f,
                1.30f, 1.15f, 0.90f,
                0f, 0f, 0f
            ),
            lightRadii = floatArrayOf(24.0f, 24.0f, 22.0f, 0.1f)
        )
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
            fogDensity = 0.00042f,
            lightPositions = floatArrayOf(
                -14.0f, 3.6f, -12.0f,
                 14.0f, 3.6f, -12.0f,
                  0.0f, 3.6f,   2.0f,
                  0.0f, 1.5f,  18.0f
            ),
            lightColors = floatArrayOf(
                2.3f, 1.40f, 0.35f,
                0.35f, 1.85f, 2.5f,
                0.2f, 2.0f, 1.5f,
                0f, 0f, 0f
            ),
            lightRadii = floatArrayOf(22.0f, 22.0f, 20.0f, 0.1f)
        )
        ArenaMapId.HARBOR -> MapAtmosphere(
            floorTexture = MaterialTexture.WET_ASPHALT,
            floorColor = floatArrayOf(0.84f, 0.90f, 0.94f),
            floorRoughness = 0.36f,
            floorReflectivity = 1.05f,
            sunDir = floatArrayOf(-0.35f, 0.72f, -0.58f),
            sunColor = floatArrayOf(1.02f, 1.10f, 1.20f),
            skyTopColor = floatArrayOf(0.08f, 0.14f, 0.22f),
            skyHorizonColor = floatArrayOf(0.24f, 0.32f, 0.40f),
            skySunGlow = floatArrayOf(0.75f, 0.88f, 1.0f),
            fogColor = floatArrayOf(0.14f, 0.20f, 0.26f),
            fogDensity = 0.00052f,
            lightPositions = floatArrayOf(
                -15.0f, 4.0f, -11.0f,
                 15.0f, 4.0f, -11.0f,
                  0.0f, 4.0f,   5.0f,
                  0.0f, 1.5f,  18.0f
            ),
            lightColors = floatArrayOf(
                2.1f, 1.45f, 0.65f,
                0.70f, 1.65f, 2.2f,
                1.8f, 1.85f, 1.95f,
                0f, 0f, 0f
            ),
            lightRadii = floatArrayOf(22.0f, 22.0f, 20.0f, 0.1f)
        )
    }

    /**
     * Authentic large CS2 de_mirage layout (78m wide x 72m deep) with warm Moroccan sandstone,
     * iconic blue-and-gold Moroccan tile trim towers, wooden triple boxes, Mid, Window, A & B sites.
     */
    private fun buildMirageBlocks(): List<WorldBlock> {
        val list = mutableListOf<WorldBlock>()
        val wH = 6.4f
        val wT = 1.6f
        val spanW = halfWidth * 2f + wT
        val spanD = halfDepth * 2f + wT

        // Outer Moroccan sandstone perimeter with blue-gold tile frieze
        list += WorldBlock(0f, wH * 0.5f, -halfDepth - wT * 0.5f, spanW, wH, wT, MaterialTexture.MIRAGE_TILE_TRIM, 1f, 0.98f, 0.95f, 0.04f, 0.84f, reflectivity = 0.08f)
        list += WorldBlock(0f, wH * 0.5f,  halfDepth + wT * 0.5f, spanW, wH, wT, MaterialTexture.MIRAGE_TILE_TRIM, 1f, 0.98f, 0.95f, 0.04f, 0.84f, reflectivity = 0.08f)
        list += WorldBlock(-halfWidth - wT * 0.5f, wH * 0.5f, 0f, wT, wH, spanD, MaterialTexture.MIRAGE_PLASTER, 1f, 0.98f, 0.95f, 0.04f, 0.84f, reflectivity = 0.08f)
        list += WorldBlock( halfWidth + wT * 0.5f, wH * 0.5f, 0f, wT, wH, spanD, MaterialTexture.MIRAGE_PLASTER, 1f, 0.98f, 0.95f, 0.04f, 0.84f, reflectivity = 0.08f)

        // --- T SPAWN COURTYARD ARCHES & DECORATIVE MOROCCAN TOWERS (also used for Live 3D Lobby Backdrop!) ---
        list += WorldBlock(-8.5f, 3.0f, 22.5f, 3.2f, 6.0f, 3.2f, MaterialTexture.MIRAGE_TILE_TRIM, 1f, 0.98f, 0.95f, 0.04f, 0.82f)
        list += WorldBlock( 8.5f, 3.0f, 22.5f, 3.2f, 6.0f, 3.2f, MaterialTexture.MIRAGE_TILE_TRIM, 1f, 0.98f, 0.95f, 0.04f, 0.82f)
        list += WorldBlock( 0.0f, 5.2f, 22.5f, 17.0f, 1.4f, 2.2f, MaterialTexture.MIRAGE_PLASTER, 1f, 0.97f, 0.93f, 0.04f, 0.84f, collidable = false)
        list += WorldBlock(-13.5f, 0.85f, 28.5f, 2.0f, 1.7f, 1.8f, MaterialTexture.WOOD_CRATE, 1f, 0.96f, 0.90f, 0.06f, 0.80f)

        // --- MAJOR DIVIDING STRUCTURES CREATING TRUE MIRAGE LANES (A RAMP / MID / B APPS) ---
        // Left Divider Block (separates A Ramp/Palace from Mid, leaves Connector gap at z = -8..-2)
        list += WorldBlock(-14.5f, 2.6f, 10.5f, 8.5f, 5.2f, 15.0f, MaterialTexture.MIRAGE_PLASTER, 1f, 0.96f, 0.90f, 0.04f, 0.84f)
        // Right Divider Block (separates B Apartments from Mid, leaves Catwalk/Short gap at z = -10..-3)
        list += WorldBlock( 14.5f, 2.6f, 10.5f, 8.5f, 5.2f, 15.0f, MaterialTexture.MIRAGE_PLASTER, 1f, 0.96f, 0.90f, 0.04f, 0.84f)

        // --- MID: TOP MID BOXES, SNIPER'S NEST WINDOW, CONNECTOR & SHORT ---
        list += WorldBlock(-1.5f, 0.95f, 10.5f, 2.3f, 1.9f, 2.3f, MaterialTexture.WOOD_CRATE, 1f, 0.95f, 0.88f, 0.06f, 0.80f) // Top Mid Box
        list += WorldBlock( 2.2f, 0.72f, 4.0f, 2.2f, 1.44f, 1.8f, MaterialTexture.WOOD_CRATE, 0.98f, 0.94f, 0.88f, 0.06f, 0.80f) // Mid Cart/Crate
        // Sniper's Nest / Window Building (North Mid with low sill wall you can shoot over!)
        list += WorldBlock(-5.5f, 2.6f, -15.5f, 5.0f, 5.2f, 4.5f, MaterialTexture.MIRAGE_TILE_TRIM, 1f, 0.98f, 0.95f, 0.04f, 0.84f)
        list += WorldBlock( 5.5f, 2.6f, -15.5f, 5.0f, 5.2f, 4.5f, MaterialTexture.MIRAGE_TILE_TRIM, 1f, 0.98f, 0.95f, 0.04f, 0.84f)
        list += WorldBlock( 0.0f, 0.62f, -15.5f, 6.0f, 1.24f, 1.4f, MaterialTexture.MIRAGE_PLASTER, 1f, 0.95f, 0.88f, 0.04f, 0.84f) // Window Sill!
        list += WorldBlock( 0.0f, 4.4f, -15.5f, 6.4f, 1.6f, 4.5f, MaterialTexture.MIRAGE_PLASTER, 1f, 0.96f, 0.90f, 0.04f, 0.84f, collidable = false)

        // Connector / Jungle Divider & Short Pillar
        list += WorldBlock(-11.5f, 2.4f, -13.5f, 4.6f, 4.8f, 6.0f, MaterialTexture.MIRAGE_PLASTER, 1f, 0.97f, 0.92f, 0.04f, 0.84f)
        list += WorldBlock( 11.5f, 2.4f, -14.5f, 4.6f, 4.8f, 6.0f, MaterialTexture.MIRAGE_PLASTER, 1f, 0.97f, 0.92f, 0.04f, 0.84f)

        // --- BOMBSITE A (WEST PLAZA): TRIPLE BOX, FIREBOX, TETRIS, STAIRS, TICKET BOOTH ---
        // A Ramp Entrance Wall & Tetris Crates
        list += WorldBlock(-32.5f, 2.2f, 8.0f, 3.2f, 4.4f, 9.0f, MaterialTexture.MIRAGE_PLASTER, 1f, 0.96f, 0.90f, 0.04f, 0.84f)
        list += WorldBlock(-25.0f, 0.85f, 1.0f, 2.2f, 1.7f, 2.2f, MaterialTexture.WOOD_CRATE, 1f, 0.96f, 0.90f, 0.06f, 0.80f) // Tetris 1
        list += WorldBlock(-22.8f, 0.58f, 2.0f, 1.8f, 1.16f, 1.8f, MaterialTexture.WOOD_CRATE, 0.96f, 0.92f, 0.86f, 0.06f, 0.80f) // Tetris step
        // Iconic Triple Box stack on Site A!
        list += WorldBlock(-21.5f, 0.95f, -11.5f, 2.2f, 1.9f, 2.2f, MaterialTexture.WOOD_CRATE, 1f, 0.96f, 0.90f, 0.06f, 0.80f)
        list += WorldBlock(-23.8f, 0.95f, -11.5f, 2.2f, 1.9f, 2.2f, MaterialTexture.WOOD_CRATE, 0.96f, 0.92f, 0.86f, 0.06f, 0.80f)
        list += WorldBlock(-22.6f, 2.65f, -11.5f, 2.0f, 1.5f, 2.0f, MaterialTexture.WOOD_CRATE, 1f, 0.95f, 0.88f, 0.06f, 0.80f, collidable = false)
        // Default A Crate & Firebox
        list += WorldBlock(-28.5f, 0.90f, -14.5f, 2.2f, 1.8f, 2.2f, MaterialTexture.WOOD_CRATE, 1f, 0.95f, 0.88f, 0.06f, 0.80f)
        list += WorldBlock(-17.5f, 1.10f, -6.5f, 2.4f, 2.2f, 2.0f, MaterialTexture.MIRAGE_PLASTER, 1f, 0.96f, 0.90f, 0.04f, 0.84f) // Stairs box
        // Ticket Booth near CT-A entrance
        list += WorldBlock(-25.5f, 1.85f, -24.5f, 4.2f, 3.7f, 3.6f, MaterialTexture.MIRAGE_TILE_TRIM, 1f, 0.98f, 0.95f, 0.06f, 0.80f)

        // --- BOMBSITE B (EAST PLAZA): B APPS, VAN, DEFAULT CRATES, BENCH, MARKET ---
        list += WorldBlock( 32.5f, 2.2f, 8.0f, 3.2f, 4.4f, 9.0f, MaterialTexture.MIRAGE_PLASTER, 1f, 0.96f, 0.90f, 0.04f, 0.84f)
        // B Van / Truck Cover
        list += WorldBlock( 28.5f, 1.15f, -4.5f, 2.4f, 2.3f, 4.6f, MaterialTexture.CONTAINER_BLUE, 0.92f, 0.96f, 1.0f, 0.45f, 0.48f, reflectivity = 0.25f)
        // Site B Default Crates & Pillar
        list += WorldBlock( 22.5f, 0.95f, -13.5f, 2.4f, 1.9f, 2.4f, MaterialTexture.WOOD_CRATE, 1f, 0.96f, 0.90f, 0.06f, 0.80f)
        list += WorldBlock( 25.0f, 0.72f, -15.5f, 1.9f, 1.44f, 1.9f, MaterialTexture.WOOD_CRATE, 0.95f, 0.91f, 0.85f, 0.06f, 0.80f)
        list += WorldBlock( 32.0f, 0.70f, -14.5f, 1.6f, 1.4f, 4.4f, MaterialTexture.MIRAGE_PLASTER, 1f, 0.95f, 0.90f, 0.04f, 0.84f) // Bench wall
        // Market / Kitchen Building near CT-B
        list += WorldBlock( 25.0f, 2.2f, -24.5f, 6.2f, 4.4f, 3.8f, MaterialTexture.MIRAGE_TILE_TRIM, 1f, 0.98f, 0.95f, 0.04f, 0.84f)

        // --- SUBTLE CHALK/GLOW BOMBSITE A & B FLOOR MARKERS ---
        addSiteMarker(list, -23.0f, -12.0f, 1.0f, 0.45f, 0.14f)
        addSiteMarker(list,  23.0f, -14.0f, 0.20f, 0.72f, 1.0f)

        return list
    }

    private fun buildIndustrialBlocks(): List<WorldBlock> {
        val list = mutableListOf<WorldBlock>()
        addPerimeter(list, MaterialTexture.CONCRETE_WALL, 0.92f, 0.95f, 1.0f, 0.30f)

        list += WorldBlock(0f, 1.2f, -2.0f, 6.4f, 2.4f, 1.4f, MaterialTexture.CONCRETE_WALL, 0.86f, 0.90f, 0.96f, 0.14f, 0.62f, reflectivity = 0.28f)
        list += WorldBlock(-9.0f, 2.2f, 4.0f, 2.0f, 4.4f, 10.0f, MaterialTexture.CONCRETE_WALL, 0.88f, 0.92f, 0.98f, 0.14f, 0.62f, reflectivity = 0.28f)
        list += WorldBlock( 9.0f, 2.2f, 4.0f, 2.0f, 4.4f, 10.0f, MaterialTexture.CONCRETE_WALL, 0.88f, 0.92f, 0.98f, 0.14f, 0.62f, reflectivity = 0.28f)
        list += WorldBlock(-14.0f, 0.95f, -12.0f, 2.4f, 1.9f, 2.4f, MaterialTexture.CRATE_STEEL, 0.85f, 0.88f, 0.92f, 0.65f, 0.36f, reflectivity = 0.45f)
        list += WorldBlock( 14.0f, 0.95f, -12.0f, 2.4f, 1.9f, 2.4f, MaterialTexture.CRATE_STEEL, 0.88f, 0.84f, 0.78f, 0.65f, 0.36f, reflectivity = 0.45f)
        list += WorldBlock(-6.5f, 1.5f, -14.5f, 4.4f, 3.0f, 1.4f, MaterialTexture.CONCRETE_WALL, 0.86f, 0.90f, 0.95f, 0.14f, 0.62f, reflectivity = 0.28f)
        list += WorldBlock( 6.5f, 1.5f, -14.5f, 4.4f, 3.0f, 1.4f, MaterialTexture.CONCRETE_WALL, 0.86f, 0.90f, 0.95f, 0.14f, 0.62f, reflectivity = 0.28f)
        addSiteMarker(list, -14.0f, -12.0f, 1.0f, 0.50f, 0.14f)
        addSiteMarker(list,  14.0f, -12.0f, 0.22f, 0.78f, 1.0f)
        return list
    }

    private fun buildHarborBlocks(): List<WorldBlock> {
        val list = mutableListOf<WorldBlock>()
        addPerimeter(list, MaterialTexture.CONCRETE_WALL, 0.84f, 0.88f, 0.94f, 0.34f)

        list += WorldBlock(-8.5f, 1.4f, 2.5f, 2.8f, 2.8f, 9.0f, MaterialTexture.CONTAINER_BLUE, 1f, 1f, 1f, 0.52f, 0.42f, reflectivity = 0.38f)
        list += WorldBlock( 8.5f, 1.4f, 2.5f, 2.8f, 2.8f, 9.0f, MaterialTexture.CONTAINER_RUST, 1f, 1f, 1f, 0.50f, 0.46f, reflectivity = 0.34f)
        list += WorldBlock( 0.0f, 1.4f, -12.0f, 8.2f, 2.8f, 2.8f, MaterialTexture.CONTAINER_BLUE, 0.96f, 0.98f, 1f, 0.52f, 0.42f, reflectivity = 0.38f)
        list += WorldBlock(-15.0f, 0.9f, -11.0f, 2.4f, 1.8f, 2.4f, MaterialTexture.CRATE_STEEL, 0.86f, 0.90f, 0.94f, 0.62f, 0.38f, reflectivity = 0.42f)
        list += WorldBlock( 15.0f, 0.9f, -11.0f, 2.4f, 1.8f, 2.4f, MaterialTexture.CRATE_STEEL, 0.90f, 0.86f, 0.78f, 0.62f, 0.38f, reflectivity = 0.42f)
        addSiteMarker(list, -15.0f, -11.0f, 1.0f, 0.50f, 0.14f)
        addSiteMarker(list,  15.0f, -11.0f, 0.22f, 0.78f, 1.0f)
        return list
    }

    private fun addSiteMarker(list: MutableList<WorldBlock>, cx: Float, cz: Float, r: Float, g: Float, b: Float) {
        val len = 6.2f
        val thick = 0.18f
        val y = 0.025f
        list += WorldBlock(cx, y, cz - len * 0.5f, len, 0.04f, thick, MaterialTexture.VISOR_GLASS, r, g, b, 0f, 1f, r * 0.75f, g * 0.75f, b * 0.75f, 0f, collidable = false)
        list += WorldBlock(cx, y, cz + len * 0.5f, len, 0.04f, thick, MaterialTexture.VISOR_GLASS, r, g, b, 0f, 1f, r * 0.75f, g * 0.75f, b * 0.75f, 0f, collidable = false)
        list += WorldBlock(cx - len * 0.5f, y, cz, thick, 0.04f, len, MaterialTexture.VISOR_GLASS, r, g, b, 0f, 1f, r * 0.75f, g * 0.75f, b * 0.75f, 0f, collidable = false)
        list += WorldBlock(cx + len * 0.5f, y, cz, thick, 0.04f, len, MaterialTexture.VISOR_GLASS, r, g, b, 0f, 1f, r * 0.75f, g * 0.75f, b * 0.75f, 0f, collidable = false)
    }

    private fun addPerimeter(
        list: MutableList<WorldBlock>,
        tex: MaterialTexture,
        r: Float, g: Float, b: Float, refl: Float
    ) {
        val wallH = 5.6f
        val wallT = 1.6f
        val spanW = halfWidth * 2f + wallT
        val spanD = halfDepth * 2f + wallT
        list += WorldBlock(0f, wallH * 0.5f, -halfDepth - wallT * 0.5f, spanW, wallH, wallT, tex, r, g, b, 0.10f, 0.72f, reflectivity = refl)
        list += WorldBlock(0f, wallH * 0.5f,  halfDepth + wallT * 0.5f, spanW, wallH, wallT, tex, r, g, b, 0.10f, 0.72f, reflectivity = refl)
        list += WorldBlock(-halfWidth - wallT * 0.5f, wallH * 0.5f, 0f, wallT, wallH, spanD, tex, r, g, b, 0.10f, 0.72f, reflectivity = refl)
        list += WorldBlock( halfWidth + wallT * 0.5f, wallH * 0.5f, 0f, wallT, wallH, spanD, tex, r, g, b, 0.10f, 0.72f, reflectivity = refl)
    }

    fun resolveHorizontalCollision(x: Float, z: Float, radius: Float): Pair<Float, Float> {
        var rx = x.coerceIn(-halfWidth + radius, halfWidth - radius)
        var rz = z.coerceIn(-halfDepth + radius, halfDepth - radius)
        for (b in blocks) {
            if (!b.collidable) continue
            val closestX = rx.coerceIn(b.minX, b.maxX)
            val closestZ = rz.coerceIn(b.minZ, b.maxZ)
            val dx = rx - closestX
            val dz = rz - closestZ
            val distSq = dx * dx + dz * dz
            if (distSq < radius * radius) {
                if (distSq > 0.00001f) {
                    val dist = sqrt(distSq)
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
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
        maxDist: Float = 110f
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
