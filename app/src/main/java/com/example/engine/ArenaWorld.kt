package com.example.engine

import android.content.Context
import com.example.model.ArenaMapId
import com.example.model.TeamSide
import com.example.model.WeaponType
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

data class BombsiteZone(
    val label: String,
    val minX: Float,
    val maxX: Float,
    val minZ: Float,
    val maxZ: Float,
    val centerX: Float,
    val centerZ: Float
) {
    fun contains(x: Float, z: Float): Boolean =
        x in minX..maxX && z in minZ..maxZ
}

data class BotOperative(
    val id: Int,
    val team: TeamSide,
    var weapon: WeaponType,
    var x: Float,
    var y: Float = 0f,
    var z: Float,
    var yawDeg: Float = 0f,
    var hp: Float = 100f,
    var maxHp: Float = 100f,
    var isDead: Boolean = false,
    var deathProgress: Float = 0f,
    var hasBomb: Boolean = false,
    var walkPhase: Float = 0f,
    var strafePhase: Float = 0f,
    var moveForwardRatio: Float = 0f,
    var moveStrafeRatio: Float = 0f,
    var isMoving: Boolean = false,
    var hitFlinch: Float = 0f,
    var fireCooldown: Float = 1.0f,
    var strafeDir: Float = 1f,
    var strafeTimer: Float = 1.5f,
    var muzzleFlashTimer: Float = 0f,
    var muzzleFlashRoll: Float = 0f,
    var targetSiteIndex: Int = 0,
    var waypointIndex: Int = 0,
    var plantProgress: Float = 0f,
    var defuseProgress: Float = 0f
)

/**
 * Real CS2 Map Spatial & Collision World:
 * Decodes the native 256x256 RLE floor heightmap exported from Source 2 (`mirage-floors.json` / `dust2-floors.json`)
 * so players & bots walk smoothly up stairs/ramps and collide accurately with walls on the real .GLB maps!
 */
class ArenaWorld(
    context: Context,
    val mapId: ArenaMapId
) {
    private var gridSize: Int = 256
    private var gridMin: Float = -51.2f
    private var gridSpan: Float = 102.4f
    private var heightScale: Float = 100f
    private var missingVal: Int = -32768
    private var floorGrid: IntArray = IntArray(0)

    val bombsites: List<BombsiteZone>
    val tSpawns: List<Pair<Float, Float>>
    val ctSpawns: List<Pair<Float, Float>>
    val tAttackRoutes: List<List<Pair<Float, Float>>>
    val ctDefenseRoutes: List<List<Pair<Float, Float>>>
    val lobbyStandPos: Triple<Float, Float, Float>
    val lobbyCamPos: Triple<Float, Float, Float>
    val lobbyLookTarget: Triple<Float, Float, Float>

    init {
        loadFloorHeightmap(context, mapId.floorsAssetPath)

        when (mapId) {
            ArenaMapId.MIRAGE -> {
                // Exact native CS2 Mirage coordinates matching map_mirage.glb & mirage-floors.json!
                bombsites = listOf(
                    BombsiteZone("A", 3.5f, 15.5f, 16.5f, 28.5f, 9.7f, 23.8f),
                    BombsiteZone("B", -33.0f, -21.5f, -29.5f, -18.0f, -28.0f, -24.0f)
                )
                tSpawns = listOf(
                    39.1f to -4.6f,
                    38.2f to -7.8f,
                    39.8f to -10.5f,
                    37.4f to -13.2f,
                    36.2f to -5.5f
                ).map { snapToNearestWalkable(it.first, it.second) }

                ctSpawns = listOf(
                    -18.5f to 24.5f,
                    -15.0f to 23.0f,
                    -12.0f to 25.5f,
                    -20.5f to 19.5f,
                    -16.5f to 17.5f
                ).map { snapToNearestWalkable(it.first, it.second) }

                tAttackRoutes = listOf(
                    // Route A: T Spawn -> A Ramp -> Site A Default
                    listOf(33.0f to 8.0f, 21.0f to 18.0f, 16.4f to 21.3f, 9.7f to 23.8f),
                    // Route Mid -> Connector -> Site A
                    listOf(24.0f to -3.5f, 12.0f to -3.5f, 0.2f to 2.4f, 5.7f to 13.4f, 9.7f to 23.8f),
                    // Route B: T Spawn -> Top Mid / B Apps -> Site B
                    listOf(22.0f to -18.0f, 4.0f to -24.0f, -16.0f to -30.2f, -28.0f to -24.0f)
                ).map { route -> route.map { snapToNearestWalkable(it.first, it.second) } }

                ctDefenseRoutes = listOf(
                    // Defend Site A (CT -> Stairs / Site A)
                    listOf(-4.1f to 29.4f, 5.7f to 13.4f, 9.7f to 23.8f),
                    // Defend Mid / Window / Connector
                    listOf(-12.0f to 10.0f, -8.8f to -3.8f, 0.2f to 2.4f),
                    // Defend Site B (CT -> Market -> Site B)
                    listOf(-22.0f to 5.0f, -24.0f to -12.0f, -28.0f to -24.0f)
                ).map { route -> route.map { snapToNearestWalkable(it.first, it.second) } }

                // Sunlit Mirage T-Spawn Courtyard for the live 3D CS2 Main Menu showcase!
                val stand = snapToNearestWalkable(38.6f, -6.5f)
                val standY = sampleFloorHeight(stand.first, stand.second) ?: -0.16f
                lobbyStandPos = Triple(stand.first, standY, stand.second)
                lobbyCamPos = Triple(stand.first - 3.6f, standY + 1.28f, stand.second + 1.8f)
                lobbyLookTarget = Triple(stand.first, standY + 1.05f, stand.second)
            }

            ArenaMapId.DUST2 -> {
                // Exact native CS2 Dust 2 coordinates matching map_dust2.glb & dust2-floors.json!
                bombsites = listOf(
                    BombsiteZone("A", 21.0f, 34.5f, -41.0f, -28.0f, 27.9f, -34.2f),
                    BombsiteZone("B", -34.0f, -21.0f, -46.5f, -34.0f, -26.9f, -41.1f)
                )
                tSpawns = listOf(
                    -1.7f to 48.8f,
                    2.5f to 47.0f,
                    -6.0f to 47.5f,
                    0.5f to 44.5f,
                    -4.5f to 44.0f
                ).map { snapToNearestWalkable(it.first, it.second) }

                ctSpawns = listOf(
                    17.6f to -28.0f,
                    12.5f to -27.0f,
                    9.5f to -24.0f,
                    15.0f to -22.0f,
                    6.0f to -28.0f
                ).map { snapToNearestWalkable(it.first, it.second) }

                tAttackRoutes = listOf(
                    // Long A -> Site A
                    listOf(13.3f to 31.4f, 27.8f to 10.0f, 31.9f to -20.0f, 27.9f to -34.2f),
                    // Mid -> Short -> Site A
                    listOf(-4.4f to 12.0f, 6.0f to -6.0f, 12.5f to -11.6f, 27.9f to -34.2f),
                    // B Tunnels -> Site B
                    listOf(-24.0f to 22.0f, -35.4f to -1.1f, -35.0f to -22.0f, -26.9f to -41.1f)
                ).map { route -> route.map { snapToNearestWalkable(it.first, it.second) } }

                ctDefenseRoutes = listOf(
                    listOf(22.0f to -30.0f, 27.9f to -34.2f),
                    listOf(4.0f to -22.0f, -4.4f to -14.7f, 12.5f to -11.6f),
                    listOf(-12.0f to -27.0f, -24.4f to -28.0f, -26.9f to -41.1f)
                ).map { route -> route.map { snapToNearestWalkable(it.first, it.second) } }

                val stand = snapToNearestWalkable(27.9f, -34.2f)
                val standY = sampleFloorHeight(stand.first, stand.second) ?: 2.19f
                lobbyStandPos = Triple(stand.first, standY, stand.second)
                lobbyCamPos = Triple(stand.first - 3.6f, standY + 1.28f, stand.second + 1.8f)
                lobbyLookTarget = Triple(stand.first, standY + 1.05f, stand.second)
            }
        }
    }

    private fun loadFloorHeightmap(context: Context, assetPath: String) {
        runCatching {
            val jsonText = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            val root = JSONObject(jsonText)
            gridSize = root.optInt("size", 256)
            gridMin = root.optDouble("min", -51.2).toFloat()
            gridSpan = root.optDouble("span", 102.4).toFloat()
            heightScale = root.optDouble("scale", 100.0).toFloat()
            missingVal = root.optInt("missing", -32768)

            val rle = root.getJSONObject("levels").getJSONArray("upper")
            val totalCells = gridSize * gridSize
            val decoded = IntArray(totalCells) { missingVal }
            var idx = 0
            var i = 0
            while (i + 1 < rle.length() && idx < totalCells) {
                val count = rle.getInt(i)
                val value = rle.getInt(i + 1)
                val end = (idx + count).coerceAtMost(totalCells)
                for (k in idx until end) {
                    decoded[k] = value
                }
                idx = end
                i += 2
            }
            floorGrid = decoded
        }
    }

    fun sampleFloorHeight(x: Float, z: Float): Float? {
        if (floorGrid.isEmpty()) return 0f
        val gx = floor((x - gridMin) / gridSpan * gridSize).toInt()
        val gz = floor((z - gridMin) / gridSpan * gridSize).toInt()
        if (gx !in 0 until gridSize || gz !in 0 until gridSize) return null
        val raw = floorGrid[gz * gridSize + gx]
        return if (raw == missingVal) null else raw / heightScale
    }

    fun snapToNearestWalkable(x: Float, z: Float): Pair<Float, Float> {
        sampleFloorHeight(x, z)?.let { return x to z }
        // Expanding ring search up to 8m
        for (ring in 1..20) {
            val r = ring * 0.45f
            for (step in 0 until 16) {
                val ang = step * (Math.PI * 2.0 / 16.0)
                val tx = x + kotlin.math.cos(ang).toFloat() * r
                val tz = z + kotlin.math.sin(ang).toFloat() * r
                if (sampleFloorHeight(tx, tz) != null) return tx to tz
            }
        }
        return x to z
    }

    /**
     * Resolves smooth horizontal movement + stairs/ramp elevation + wall sliding on the real CS2 heightmap.
     * Returns (newX, floorY, newZ).
     */
    fun resolveMovement(
        curX: Float,
        curY: Float,
        curZ: Float,
        targetX: Float,
        targetZ: Float,
        radius: Float = 0.42f
    ): Triple<Float, Float, Float> {
        val currentFloor = sampleFloorHeight(curX, curZ) ?: curY

        fun canStandAt(px: Float, pz: Float): Float? {
            val c = sampleFloorHeight(px, pz) ?: return null
            if (abs(c - currentFloor) > 1.45f) return null
            // Check 4 perimeter probes around body radius so player doesn't clip into walls
            val p1 = sampleFloorHeight(px + radius, pz)
            val p2 = sampleFloorHeight(px - radius, pz)
            val p3 = sampleFloorHeight(px, pz + radius)
            val p4 = sampleFloorHeight(px, pz - radius)
            val validProbes = listOfNotNull(p1, p2, p3, p4).count { abs(it - c) <= 1.65f }
            return if (validProbes >= 2) c else null
        }

        // Try full target step first
        canStandAt(targetX, targetZ)?.let { h ->
            return Triple(targetX, h, targetZ)
        }
        // Try sliding along X
        canStandAt(targetX, curZ)?.let { h ->
            return Triple(targetX, h, curZ)
        }
        // Try sliding along Z
        canStandAt(curX, targetZ)?.let { h ->
            return Triple(curX, h, targetZ)
        }
        return Triple(curX, currentFloor, curZ)
    }

    /**
     * Marches a 3D ray through the CS2 heightmap to detect wall / solid structure hits for bullets & AI LOS.
     */
    fun raycastWalls(
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
        maxDist: Float
    ): Float {
        if (floorGrid.isEmpty()) return maxDist
        var dist = 0.45f
        val step = 0.35f
        while (dist < maxDist) {
            val px = ox + dx * dist
            val py = oy + dy * dist
            val pz = oz + dz * dist
            val floorH = sampleFloorHeight(px, pz)
            if (floorH == null) {
                // Wall/void boundary hit!
                return dist
            }
            if (py < floorH + 0.04f) {
                // Ground or elevated platform hit!
                return dist
            }
            dist += step
        }
        return maxDist
    }

    fun getCalloutName(x: Float, z: Float): String {
        return when (mapId) {
            ArenaMapId.MIRAGE -> when {
                bombsites[0].contains(x, z) -> "ТОЧКА A"
                bombsites[1].contains(x, z) -> "ТОЧКА B"
                x > 30f -> "Т-СПАВН"
                x < -10f && z > 15f -> "КТ-СПАВН"
                x in -13f..-4f && z in -8f..1f -> "ОКНО"
                x in -4f..6f && z in -2f..10f -> "КОННЕКТОР"
                z in -10f..4f -> "МИД"
                z < -20f -> "КОВРЫ B"
                z > 14f -> "РАМПА A"
                else -> "MIRAGE"
            }
            ArenaMapId.DUST2 -> when {
                bombsites[0].contains(x, z) -> "ТОЧКА A"
                bombsites[1].contains(x, z) -> "ТОЧКА B"
                z > 35f -> "Т-СПАВН"
                x in 4f..22f && z in -32f..-20f -> "КТ-СПАВН"
                x > 20f -> "ЛОНГ A"
                x < -20f -> "ТЁМКА B"
                else -> "МИД"
            }
        }
    }

    fun getSpawnPoint(side: TeamSide, index: Int): Triple<Float, Float, Float> {
        val list = if (side == TeamSide.T) tSpawns else ctSpawns
        val (sx, sz) = list[index % list.size]
        val sy = sampleFloorHeight(sx, sz) ?: 0f
        return Triple(sx, sy, sz)
    }

    fun distance2D(ax: Float, az: Float, bx: Float, bz: Float): Float =
        sqrt((ax - bx) * (ax - bx) + (az - bz) * (az - bz))
}
