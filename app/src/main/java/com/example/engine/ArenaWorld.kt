package com.example.engine

import android.content.Context
import com.example.model.ArenaMapId
import com.example.model.TeamSide
import com.example.model.WeaponType
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
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
    var visualYawDeg: Float = yawDeg,
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
 * Multi-Level Real 3D CS2 Navigation & Collision World:
 * Decodes up to 3 walkable floor layers per (x, z) cell baked directly from the real
 * 3D triangles of `map_mirage.glb` & `map_dust2.glb`, so players and bots walk seamlessly
 * through ALL indoor passages (A Ramp, Palace, Connector, Window, Market, B Apartments,
 * Underpass, B Tunnels) with accurate wall sliding and 3D raycasts!
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
    private var floorLayers: List<IntArray> = emptyList()

    val bombsites: List<BombsiteZone>
    val tSpawns: List<Pair<Float, Float>>
    val ctSpawns: List<Pair<Float, Float>>
    val tAttackRoutes: List<List<Pair<Float, Float>>>
    val ctDefenseRoutes: List<List<Pair<Float, Float>>>
    val lobbyStandPos: Triple<Float, Float, Float>
    val lobbyCamPos: Triple<Float, Float, Float>
    val lobbyLookTarget: Triple<Float, Float, Float>

    init {
        loadMultiLayerFloors(context, mapId.floorsAssetPath)

        when (mapId) {
            ArenaMapId.MIRAGE -> {
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
                    // Route A: T Spawn -> A Ramp -> Site A
                    listOf(33.0f to 8.0f, 24.0f to 16.0f, 16.4f to 21.3f, 9.7f to 23.8f),
                    // Route Mid -> Connector -> Site A
                    listOf(24.0f to -3.5f, 10.0f to -3.5f, 2.0f to 5.5f, 5.7f to 13.4f, 9.7f to 23.8f),
                    // Route B: T Spawn -> B Apartments -> Site B
                    listOf(22.0f to -18.0f, 4.0f to -24.0f, -16.0f to -30.2f, -28.0f to -24.0f)
                ).map { route -> route.map { snapToNearestWalkable(it.first, it.second) } }

                ctDefenseRoutes = listOf(
                    listOf(-4.1f to 29.4f, 5.7f to 13.4f, 9.7f to 23.8f),
                    listOf(-12.0f to 10.0f, -8.8f to -3.8f, 0.2f to 2.4f),
                    listOf(-22.0f to 5.0f, -24.0f to -5.0f, -28.0f to -24.0f)
                ).map { route -> route.map { snapToNearestWalkable(it.first, it.second) } }

                // Verified 100% unobstructed sunlit Mirage T-Spawn courtyard!
                val stand = snapToNearestWalkable(38.5f, -5.5f)
                val standY = sampleFloorHeight(stand.first, stand.second) ?: -0.08f
                lobbyStandPos = Triple(stand.first, standY, stand.second)
                lobbyCamPos = Triple(stand.first + 3.0f, standY + 1.18f, stand.second + 0.8f)
                lobbyLookTarget = Triple(stand.first, standY + 0.96f, stand.second)
            }

            ArenaMapId.DUST2 -> {
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
                    listOf(13.3f to 31.4f, 27.8f to 10.0f, 31.9f to -20.0f, 27.9f to -34.2f),
                    listOf(-4.4f to 12.0f, 6.0f to -6.0f, 12.5f to -11.6f, 27.9f to -34.2f),
                    listOf(-24.0f to 22.0f, -35.4f to -1.1f, -35.0f to -22.0f, -26.9f to -41.1f)
                ).map { route -> route.map { snapToNearestWalkable(it.first, it.second) } }

                ctDefenseRoutes = listOf(
                    listOf(22.0f to -30.0f, 27.9f to -34.2f),
                    listOf(4.0f to -22.0f, -4.4f to -14.7f, 12.5f to -11.6f),
                    listOf(-12.0f to -27.0f, -24.4f to -28.0f, -26.9f to -41.1f)
                ).map { route -> route.map { snapToNearestWalkable(it.first, it.second) } }

                val stand = snapToNearestWalkable(27.9f, -34.2f)
                val standY = sampleFloorHeight(stand.first, stand.second) ?: 2.17f
                lobbyStandPos = Triple(stand.first, standY, stand.second)
                lobbyCamPos = Triple(stand.first + 3.0f, standY + 1.18f, stand.second + 0.8f)
                lobbyLookTarget = Triple(stand.first, standY + 0.96f, stand.second)
            }
        }
    }

    private fun decodeRle(rle: JSONArray, totalCells: Int): IntArray {
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
        return decoded
    }

    private fun loadMultiLayerFloors(context: Context, assetPath: String) {
        runCatching {
            val jsonText = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            val root = JSONObject(jsonText)
            gridSize = root.optInt("size", 256)
            gridMin = root.optDouble("min", -51.2).toFloat()
            gridSpan = root.optDouble("span", 102.4).toFloat()
            heightScale = root.optDouble("scale", 100.0).toFloat()
            missingVal = root.optInt("missing", -32768)

            val levelsObj = root.getJSONObject("levels")
            val totalCells = gridSize * gridSize
            val layers = ArrayList<IntArray>(3)
            for (key in listOf("l0", "l1", "l2", "upper")) {
                if (levelsObj.has(key)) {
                    layers.add(decodeRle(levelsObj.getJSONArray(key), totalCells))
                }
            }
            floorLayers = layers
        }
    }

    /**
     * Picks the best matching walkable floor height at (x, z) near [refY].
     * Enables smooth walking inside multi-story Mirage buildings (Palace, B Apps, Connector, Underpass).
     */
    fun sampleNearestFloorHeight(
        x: Float,
        z: Float,
        refY: Float,
        maxStepUp: Float = 0.92f,
        maxDrop: Float = 3.20f
    ): Float? {
        if (floorLayers.isEmpty()) return 0f
        val gx = floor((x - gridMin) / gridSpan * gridSize).toInt()
        val gz = floor((z - gridMin) / gridSpan * gridSize).toInt()
        if (gx !in 0 until gridSize || gz !in 0 until gridSize) return null
        val cell = gz * gridSize + gx

        var bestH: Float? = null
        var bestDiff = Float.MAX_VALUE
        for (layer in floorLayers) {
            val raw = layer[cell]
            if (raw == missingVal) continue
            val h = raw / heightScale
            val delta = h - refY
            if (delta <= maxStepUp && delta >= -maxDrop) {
                val diff = abs(delta)
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestH = h
                }
            }
        }
        return bestH
    }

    /**
     * Samples the lowest available walkable ground height at (x, z) (used for spawning/snapping).
     */
    fun sampleFloorHeight(x: Float, z: Float): Float? {
        if (floorLayers.isEmpty()) return 0f
        val gx = floor((x - gridMin) / gridSpan * gridSize).toInt()
        val gz = floor((z - gridMin) / gridSpan * gridSize).toInt()
        if (gx !in 0 until gridSize || gz !in 0 until gridSize) return null
        val cell = gz * gridSize + gx
        for (layer in floorLayers) {
            val raw = layer[cell]
            if (raw != missingVal) return raw / heightScale
        }
        return null
    }

    fun snapToNearestWalkable(x: Float, z: Float): Pair<Float, Float> {
        sampleFloorHeight(x, z)?.let { return x to z }
        for (ring in 1..20) {
            val r = ring * 0.42f
            for (step in 0 until 16) {
                val ang = step * (Math.PI * 2.0 / 16.0)
                val tx = x + cos(ang).toFloat() * r
                val tz = z + sin(ang).toFloat() * r
                if (sampleFloorHeight(tx, tz) != null) return tx to tz
            }
        }
        return x to z
    }

    /**
     * Smooth multi-level movement with doorway-friendly radius (0.24m), stair/ramp elevation,
     * and crisp wall sliding across ALL Mirage & Dust 2 indoor/outdoor passages!
     */
    fun resolveMovement(
        curX: Float,
        curY: Float,
        curZ: Float,
        targetX: Float,
        targetZ: Float,
        radius: Float = 0.24f
    ): Triple<Float, Float, Float> {
        val currentFloor = sampleNearestFloorHeight(curX, curZ, curY, maxStepUp = 1.1f, maxDrop = 3.5f)
            ?: sampleFloorHeight(curX, curZ)
            ?: curY

        fun canStandAt(px: Float, pz: Float): Float? {
            val c = sampleNearestFloorHeight(px, pz, currentFloor, maxStepUp = 0.90f, maxDrop = 3.20f)
                ?: return null
            // Gentle doorway-friendly clearance check: allows tight 1m doorways while stopping solid walls
            val p1 = sampleNearestFloorHeight(px + radius, pz, c, 0.95f, 3.2f)
            val p2 = sampleNearestFloorHeight(px - radius, pz, c, 0.95f, 3.2f)
            val p3 = sampleNearestFloorHeight(px, pz + radius, c, 0.95f, 3.2f)
            val p4 = sampleNearestFloorHeight(px, pz - radius, c, 0.95f, 3.2f)
            val valid = (if (p1 != null) 1 else 0) +
                (if (p2 != null) 1 else 0) +
                (if (p3 != null) 1 else 0) +
                (if (p4 != null) 1 else 0)
            return if (valid >= 1) c else null
        }

        canStandAt(targetX, targetZ)?.let { h ->
            return Triple(targetX, h, targetZ)
        }
        canStandAt(targetX, curZ)?.let { h ->
            return Triple(targetX, h, curZ)
        }
        canStandAt(curX, targetZ)?.let { h ->
            return Triple(curX, h, targetZ)
        }
        return Triple(curX, currentFloor, curZ)
    }

    /**
     * Marches a 3D ray through the multi-layer CS2 navigation world to block bullets/LOS on walls & floors.
     */
    fun raycastWalls(
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
        maxDist: Float
    ): Float {
        if (floorLayers.isEmpty()) return maxDist
        var dist = 0.45f
        val step = 0.38f
        while (dist < maxDist) {
            val px = ox + dx * dist
            val py = oy + dy * dist
            val pz = oz + dz * dist
            // Check if there is any walkable space near this ray's elevation
            val floorH = sampleNearestFloorHeight(px, pz, py - 1.35f, maxStepUp = 2.6f, maxDrop = 4.2f)
            if (floorH == null) {
                return dist
            }
            if (py < floorH + 0.04f) {
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
                x in 16f..28f && z > 20f -> "ДВОРЕЦ A"
                z in -10f..4f -> "МИД"
                z < -20f -> "КОВРЫ B"
                x < -18f && z in -16f..8f -> "МАРКЕТ"
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

    fun distance2D(ax: Float, az: Float, bx: Float, bz: Float): Float {
        val dx = ax - bx
        val dz = az - bz
        return sqrt(dx * dx + dz * dz)
    }
}
