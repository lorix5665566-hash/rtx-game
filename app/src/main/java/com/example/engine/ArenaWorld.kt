package com.example.engine

import android.content.Context
import com.example.model.ArenaMapId
import com.example.model.TeamSide
import com.example.model.WeaponType
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
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
    val name: String,
    val team: TeamSide,
    var weapon: WeaponType,
    var money: Int = 800,
    var armor: Float = 0f,
    var kills: Int = 0,
    var deaths: Int = 0,
    var x: Float,
    var y: Float = 0f,
    var z: Float,
    var yawDeg: Float = 0f,
    var visualYawDeg: Float = yawDeg,
    var aimYawDeg: Float = yawDeg,
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
    var hasLineOfSight: Boolean = false,
    var hitFlinch: Float = 0f,
    var fireCooldown: Float = 0.6f,
    var strafeDir: Float = 1f,
    var strafeTimer: Float = 1.5f,
    var muzzleFlashTimer: Float = 0f,
    var muzzleFlashRoll: Float = 0f,
    var targetSiteIndex: Int = 0,
    var targetBombsiteIdx: Int = 0,
    var waypointIndex: Int = 0,
    var tacticRole: Int = 0,
    var holdTimer: Float = 0f,
    var plantProgress: Float = 0f,
    var defuseProgress: Float = 0f,
    var bombBeepTimer: Float = 0f,
    // A* Navigation & anti-stuck state
    var navPath: List<Pair<Float, Float>> = emptyList(),
    var navPathIndex: Int = 0,
    var repathTimer: Float = 0f,
    var stuckTimer: Float = 0f,
    var lastProgressX: Float = x,
    var lastProgressZ: Float = z,
    var unstuckTimer: Float = 0f,
    var unstuckDir: Float = 1f,
    // Human-like vision, reaction delay (~0.35s) & burst recoil state
    var targetSightTime: Float = 0f,
    var losLostGraceTimer: Float = 0f,
    var reactionDelaySec: Float = 0.35f,
    var lastTargetKey: Int = -1,
    var burstShotsLeft: Int = 3,
    var burstPauseTimer: Float = 0f,
    var alertYawDeg: Float = yawDeg,
    var alertTimer: Float = 0f,
    var aimSwaySeed: Float = 0f
)

/**
 * Multi-Level Real 3D Navigation & Collision World:
 * Decodes walkable floor layers and 512x512 vertical wall bitmasks baked from
 * `map_mirage.glb` & `map_dust2.glb`, builds a fast 72x72 A* navigation graph for bots,
 * and computes verified wall-free 3D lobby showcase framing for both maps.
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

    // High-precision 512x512 32-bit vertical wall slice bitmask (0.5m slices, -4m..+12m)
    private var wallSize: Int = 0
    private var wallMin: Float = -51.2f
    private var wallSpan: Float = 102.4f
    private var wallMinY: Float = -4.0f
    private var wallStepY: Float = 0.5f
    private var wallBits: IntArray? = null

    // Precomputed coarse A* navigation grid (72x72 ≈ 1.42m per cell)
    private val navSize: Int = 72
    private val navWalkable = BooleanArray(navSize * navSize)
    private val navFloorY = FloatArray(navSize * navSize) { Float.NaN }
    // Bitmask of passable 8-neighbors per nav cell (pre-validated for step height & wall clearance)
    private val navEdgeMask = IntArray(navSize * navSize)

    val bombsites: List<BombsiteZone>
    val tSpawns: List<Pair<Float, Float>>
    val ctSpawns: List<Pair<Float, Float>>
    val tAttackRoutes: List<List<Pair<Float, Float>>>
    val tRouteBombsiteIdx: List<Int>
    val ctDefenseRoutes: List<List<Pair<Float, Float>>>
    val lobbyStandPos: Triple<Float, Float, Float>
    val lobbyCamPos: Triple<Float, Float, Float>
    val lobbyLookTarget: Triple<Float, Float, Float>
    val lobbyDefaultCharYawDeg: Float

    init {
        loadMultiLayerFloors(context, mapId.floorsAssetPath)
        buildNavGrid()

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
                    // Route 0 -> Site A via A Ramp
                    listOf(33.0f to 8.0f, 24.0f to 16.0f, 16.4f to 21.3f, 9.7f to 23.8f),
                    // Route 1 -> Site A via Mid -> Connector
                    listOf(24.0f to -3.5f, 10.0f to -3.5f, 2.0f to 5.5f, 5.7f to 13.4f, 9.7f to 23.8f),
                    // Route 2 -> Site B via B Apartments
                    listOf(22.0f to -18.0f, 4.0f to -24.0f, -16.0f to -30.2f, -28.0f to -24.0f)
                ).map { route -> route.map { snapToNearestWalkable(it.first, it.second) } }
                tRouteBombsiteIdx = listOf(0, 0, 1)

                ctDefenseRoutes = listOf(
                    listOf(-4.1f to 29.4f, 5.7f to 13.4f, 9.7f to 23.8f),
                    listOf(-12.0f to 10.0f, -8.8f to -3.8f, 0.2f to 2.4f),
                    listOf(-22.0f to 5.0f, -24.0f to -5.0f, -28.0f to -24.0f)
                ).map { route -> route.map { snapToNearestWalkable(it.first, it.second) } }

                val framing = computeOpenLobbyFraming(
                    preferredAnchors = listOf(
                        9.7f to 23.8f,    // Open sunlit Site A plaza
                        35.8f to -8.0f,   // Open T-Spawn yard (well clear of outer east wall)
                        6.0f to -3.5f,    // Open Mid plaza
                        -15.5f to 22.0f   // Open CT courtyard
                    )
                )
                lobbyStandPos = framing.standPos
                lobbyCamPos = framing.camPos
                lobbyLookTarget = framing.lookTarget
                lobbyDefaultCharYawDeg = framing.charYawDeg
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
                    // Route 0 -> Site A via Long A
                    listOf(13.3f to 31.4f, 27.8f to 10.0f, 31.9f to -20.0f, 27.9f to -34.2f),
                    // Route 1 -> Site A via Mid / Short
                    listOf(-4.4f to 12.0f, 6.0f to -6.0f, 12.5f to -11.6f, 27.9f to -34.2f),
                    // Route 2 -> Site B via B Tunnels
                    listOf(-24.0f to 22.0f, -35.4f to -1.1f, -35.0f to -22.0f, -26.9f to -41.1f)
                ).map { route -> route.map { snapToNearestWalkable(it.first, it.second) } }
                tRouteBombsiteIdx = listOf(0, 0, 1)

                ctDefenseRoutes = listOf(
                    listOf(22.0f to -30.0f, 27.9f to -34.2f),
                    listOf(4.0f to -22.0f, -4.4f to -14.7f, 12.5f to -11.6f),
                    listOf(-12.0f to -27.0f, -24.4f to -28.0f, -26.9f to -41.1f)
                ).map { route -> route.map { snapToNearestWalkable(it.first, it.second) } }

                val framing = computeOpenLobbyFraming(
                    preferredAnchors = listOf(
                        27.5f to -33.5f,  // Open sunlit Dust 2 Site A
                        27.8f to 8.5f,    // Wide Long A courtyard
                        -1.5f to 45.2f,   // Open T-Spawn terrace
                        12.5f to -25.5f   // Open CT plaza
                    )
                )
                lobbyStandPos = framing.standPos
                lobbyCamPos = framing.camPos
                lobbyLookTarget = framing.lookTarget
                lobbyDefaultCharYawDeg = framing.charYawDeg
            }
        }
    }

    private data class LobbyFraming(
        val standPos: Triple<Float, Float, Float>,
        val camPos: Triple<Float, Float, Float>,
        val lookTarget: Triple<Float, Float, Float>,
        val charYawDeg: Float
    )

    /**
     * Programmatically validates open-courtyard camera & character placement against the baked
     * wall bitmask and floor grid so the camera NEVER clips inside walls/crates and the operative
     * stands fully visible in an open sunlit zone of Mirage or Dust 2.
     */
    private fun computeOpenLobbyFraming(preferredAnchors: List<Pair<Float, Float>>): LobbyFraming {
        fun isAreaWallFree(px: Float, pz: Float, refY: Float, radius: Float, lowOff: Float, highOff: Float): Boolean {
            val h = sampleNearestFloorHeight(px, pz, refY, maxStepUp = 1.4f, maxDrop = 2.0f) ?: return false
            val yLow = min(refY, h) + lowOff
            val yHigh = maxOf(refY, h) + highOff
            if (hasWallAt(px, pz, yLow, yHigh)) return false
            val rDiag = radius * 0.7071f
            return !hasWallAt(px + radius, pz, yLow, yHigh) &&
                !hasWallAt(px - radius, pz, yLow, yHigh) &&
                !hasWallAt(px, pz + radius, yLow, yHigh) &&
                !hasWallAt(px, pz - radius, yLow, yHigh) &&
                !hasWallAt(px + rDiag, pz + rDiag, yLow, yHigh) &&
                !hasWallAt(px - rDiag, pz + rDiag, yLow, yHigh) &&
                !hasWallAt(px + rDiag, pz - rDiag, yLow, yHigh) &&
                !hasWallAt(px - rDiag, pz - rDiag, yLow, yHigh)
        }

        fun isSightlineClean(
            camX: Float, camZ: Float,
            standX: Float, standZ: Float,
            standY: Float
        ): Boolean {
            val dx = standX - camX
            val dz = standZ - camZ
            val dist = sqrt(dx * dx + dz * dz).coerceAtLeast(0.01f)
            val ux = dx / dist
            val uz = dz / dist
            val px = -uz
            val pz = ux
            val steps = (dist / 0.18f).toInt().coerceAtLeast(10)
            for (i in 0..steps) {
                val t = i.toFloat() / steps
                val sx = camX + dx * t
                val sz = camZ + dz * t
                // Check center + 0.34m left/right cone across feet-to-head vertical band
                if (hasWallAt(sx, sz, standY + 0.25f, standY + 1.90f)) return false
                if (hasWallAt(sx + px * 0.34f, sz + pz * 0.34f, standY + 0.35f, standY + 1.80f)) return false
                if (hasWallAt(sx - px * 0.34f, sz - pz * 0.34f, standY + 0.35f, standY + 1.80f)) return false
            }
            return true
        }

        var bestScore = Float.NEGATIVE_INFINITY
        var bestFraming: LobbyFraming? = null

        val candidateStands = ArrayList<Pair<Float, Float>>()
        for ((ax, az) in preferredAnchors) {
            val base = snapToNearestWalkable(ax, az)
            candidateStands.add(base)
            // Also probe a 2.5m ring around each anchor to find the widest unobstructed pocket
            for (k in 0 until 8) {
                val ang = k * (Math.PI * 2.0 / 8.0)
                val px = base.first + cos(ang).toFloat() * 2.2f
                val pz = base.second + sin(ang).toFloat() * 2.2f
                candidateStands.add(snapToNearestWalkable(px, pz))
            }
        }

        for ((idx, stand) in candidateStands.withIndex()) {
            val standY = sampleFloorHeight(stand.first, stand.second) ?: continue
            if (!isAreaWallFree(stand.first, stand.second, standY, radius = 0.85f, lowOff = 0.30f, highOff = 1.85f)) {
                continue
            }
            // Slight bonus for earlier (preferred) anchor zones
            val anchorBonus = if (idx < 9) 1.5f else 0f

            for (camDist in listOf(3.25f, 3.65f)) {
                for (step in 0 until 24) {
                    val ang = step * (Math.PI * 2.0 / 24.0)
                    val dirX = cos(ang).toFloat()
                    val dirZ = sin(ang).toFloat()
                    val camX = stand.first + dirX * camDist
                    val camZ = stand.second + dirZ * camDist

                    if (!isAreaWallFree(camX, camZ, standY, radius = 0.90f, lowOff = 0.25f, highOff = 2.05f)) {
                        continue
                    }
                    if (!isSightlineClean(camX, camZ, stand.first, stand.second, standY)) {
                        continue
                    }

                    // Measure open scenic backdrop depth behind the character (away from camera)
                    var backdropClear = 0f
                    for (d in 1..10) {
                        val bx = stand.first - dirX * (d * 0.9f)
                        val bz = stand.second - dirZ * (d * 0.9f)
                        if (hasWallAt(bx, bz, standY + 0.55f, standY + 2.10f)) break
                        backdropClear += 0.9f
                    }
                    // Also measure side breathing room around character so HUD doesn't crowd walls
                    val sideX = -dirZ
                    val sideZ = dirX
                    var sideClear = 0f
                    for (s in listOf(-1.6f, 1.6f)) {
                        if (!hasWallAt(stand.first + sideX * s, stand.second + sideZ * s, standY + 0.45f, standY + 1.85f)) {
                            sideClear += 1.0f
                        }
                    }

                    val score = backdropClear * 1.4f + sideClear * 1.2f + anchorBonus + (if (camDist < 3.4f) 0.5f else 0f)
                    if (score > bestScore) {
                        bestScore = score
                        val yaw = Math.toDegrees(atan2((camX - stand.first).toDouble(), (camZ - stand.second).toDouble())).toFloat()
                        bestFraming = LobbyFraming(
                            standPos = Triple(stand.first, standY, stand.second),
                            camPos = Triple(camX, standY + 1.16f, camZ),
                            lookTarget = Triple(stand.first, standY + 0.98f, stand.second),
                            charYawDeg = yaw
                        )
                    }
                }
            }
        }

        if (bestFraming != null) return bestFraming

        // Safe fallback if map grid failed to load
        val fallback = preferredAnchors.firstOrNull()?.let { snapToNearestWalkable(it.first, it.second) } ?: (0f to 0f)
        val fy = sampleFloorHeight(fallback.first, fallback.second) ?: 0f
        return LobbyFraming(
            standPos = Triple(fallback.first, fy, fallback.second),
            camPos = Triple(fallback.first, fy + 1.16f, fallback.second + 3.25f),
            lookTarget = Triple(fallback.first, fy + 0.98f, fallback.second),
            charYawDeg = 0f
        )
    }

    // =========================================================================
    // Precomputed 72x72 Nav-Grid & A* Pathfinding with String-Pulling
    // =========================================================================

    private fun navCellToWorldX(gx: Int): Float = gridMin + (gx + 0.5f) * (gridSpan / navSize)
    private fun navCellToWorldZ(gz: Int): Float = gridMin + (gz + 0.5f) * (gridSpan / navSize)
    private fun worldToNavX(x: Float): Int = floor((x - gridMin) / gridSpan * navSize).toInt().coerceIn(0, navSize - 1)
    private fun worldToNavZ(z: Float): Int = floor((z - gridMin) / gridSpan * navSize).toInt().coerceIn(0, navSize - 1)

    private fun buildNavGrid() {
        val rProbe = 0.34f
        for (gz in 0 until navSize) {
            val wz = navCellToWorldZ(gz)
            for (gx in 0 until navSize) {
                val wx = navCellToWorldX(gx)
                val idx = gz * navSize + gx
                val h = sampleFloorHeight(wx, wz)
                if (h == null) {
                    navWalkable[idx] = false
                    continue
                }
                val bodyLow = h + 0.58f
                val bodyHigh = h + 1.52f
                val blocked = hasWallAt(wx, wz, bodyLow, bodyHigh) ||
                    hasWallAt(wx + rProbe, wz, bodyLow, bodyHigh) ||
                    hasWallAt(wx - rProbe, wz, bodyLow, bodyHigh) ||
                    hasWallAt(wx, wz + rProbe, bodyLow, bodyHigh) ||
                    hasWallAt(wx, wz - rProbe, bodyLow, bodyHigh)
                if (!blocked) {
                    navWalkable[idx] = true
                    navFloorY[idx] = h
                }
            }
        }

        // Precompute valid 8-connected neighbor edges (checking wall clearance & step height)
        for (gz in 0 until navSize) {
            val wz = navCellToWorldZ(gz)
            for (gx in 0 until navSize) {
                val idx = gz * navSize + gx
                if (!navWalkable[idx]) continue
                val wx = navCellToWorldX(gx)
                val hA = navFloorY[idx]
                var mask = 0
                for (dir in 0 until 8) {
                    val nx = gx + NAV_DX[dir]
                    val nz = gz + NAV_DZ[dir]
                    if (nx !in 0 until navSize || nz !in 0 until navSize) continue
                    val nIdx = nz * navSize + nx
                    if (!navWalkable[nIdx]) continue
                    val hB = navFloorY[nIdx]
                    if (abs(hB - hA) > 1.05f) continue

                    // Prevent diagonal corner cutting around solid walls
                    if (NAV_DX[dir] != 0 && NAV_DZ[dir] != 0) {
                        val c1 = gz * navSize + nx
                        val c2 = nz * navSize + gx
                        if (!navWalkable[c1] || !navWalkable[c2]) continue
                    }

                    val nwx = navCellToWorldX(nx)
                    val nwz = navCellToWorldZ(nz)
                    val midX = (wx + nwx) * 0.5f
                    val midZ = (wz + nwz) * 0.5f
                    val midLow = min(hA, hB) + 0.58f
                    val midHigh = maxOf(hA, hB) + 1.52f
                    if (hasWallAt(midX, midZ, midLow, midHigh)) continue
                    if (sampleNearestFloorHeight(midX, midZ, (hA + hB) * 0.5f, 1.15f, 2.2f) == null) continue

                    mask = mask or (1 shl dir)
                }
                navEdgeMask[idx] = mask
            }
        }
    }

    private fun findNearestWalkableNavCell(x: Float, z: Float): Int {
        val cx = worldToNavX(x)
        val cz = worldToNavZ(z)
        val direct = cz * navSize + cx
        if (navWalkable[direct] && navEdgeMask[direct] != 0) return direct

        var bestIdx = -1
        var bestD2 = Float.MAX_VALUE
        for (ring in 1..6) {
            for (dz in -ring..ring) {
                for (dx in -ring..ring) {
                    if (abs(dx) != ring && abs(dz) != ring) continue
                    val nx = cx + dx
                    val nz = cz + dz
                    if (nx !in 0 until navSize || nz !in 0 until navSize) continue
                    val idx = nz * navSize + nx
                    if (navWalkable[idx] && navEdgeMask[idx] != 0) {
                        val wx = navCellToWorldX(nx)
                        val wz = navCellToWorldZ(nz)
                        val d2 = (wx - x) * (wx - x) + (wz - z) * (wz - z)
                        if (d2 < bestD2) {
                            bestD2 = d2
                            bestIdx = idx
                        }
                    }
                }
            }
            if (bestIdx >= 0) return bestIdx
        }
        return direct
    }

    /**
     * Checks if a bot can walk in a straight line from (ax, az) to (bx, bz) with wall clearance.
     */
    fun hasWalkableLine(ax: Float, az: Float, bx: Float, bz: Float): Boolean {
        val dx = bx - ax
        val dz = bz - az
        val dist = sqrt(dx * dx + dz * dz)
        if (dist < 0.2f) return true
        var refY = sampleFloorHeight(ax, az) ?: return false
        val steps = (dist / 0.42f).toInt().coerceAtLeast(2)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val px = ax + dx * t
            val pz = az + dz * t
            val h = sampleNearestFloorHeight(px, pz, refY, maxStepUp = 0.90f, maxDrop = 2.4f) ?: return false
            val low = h + 0.58f
            val high = h + 1.52f
            if (hasWallAt(px, pz, low, high) ||
                hasWallAt(px + 0.30f, pz, low, high) ||
                hasWallAt(px - 0.30f, pz, low, high) ||
                hasWallAt(px, pz + 0.30f, low, high) ||
                hasWallAt(px, pz - 0.30f, low, high)
            ) {
                return false
            }
            refY = h
        }
        return true
    }

    /**
     * Computes an obstacle-free A* path through corridors & doorways from (startX, startZ)
     * to (goalX, goalZ), then smooths waypoints via line-of-sight string-pulling.
     */
    fun findPath(startX: Float, startZ: Float, goalX: Float, goalZ: Float): List<Pair<Float, Float>> {
        if (hasWalkableLine(startX, startZ, goalX, goalZ)) {
            return listOf(goalX to goalZ)
        }

        val total = navSize * navSize
        val startCell = findNearestWalkableNavCell(startX, startZ)
        val goalCell = findNearestWalkableNavCell(goalX, goalZ)
        if (startCell == goalCell) return listOf(goalX to goalZ)

        val goalGx = goalCell % navSize
        val goalGz = goalCell / navSize

        val gScore = FloatArray(total) { Float.POSITIVE_INFINITY }
        val fScore = FloatArray(total) { Float.POSITIVE_INFINITY }
        val cameFrom = IntArray(total) { -1 }
        val closed = BooleanArray(total)
        val inOpen = BooleanArray(total)

        // Binary min-heap of cell indices ordered by fScore
        val heap = IntArray(total)
        var heapSize = 0

        fun heapPush(cell: Int) {
            var i = heapSize++
            heap[i] = cell
            while (i > 0) {
                val p = (i - 1) ushr 1
                if (fScore[heap[i]] < fScore[heap[p]]) {
                    val tmp = heap[p]; heap[p] = heap[i]; heap[i] = tmp
                    i = p
                } else break
            }
        }

        fun heapPop(): Int {
            val top = heap[0]
            heapSize--
            if (heapSize > 0) {
                heap[0] = heap[heapSize]
                var i = 0
                while (true) {
                    val l = (i shl 1) + 1
                    val r = l + 1
                    var smallest = i
                    if (l < heapSize && fScore[heap[l]] < fScore[heap[smallest]]) smallest = l
                    if (r < heapSize && fScore[heap[r]] < fScore[heap[smallest]]) smallest = r
                    if (smallest != i) {
                        val tmp = heap[i]; heap[i] = heap[smallest]; heap[smallest] = tmp
                        i = smallest
                    } else break
                }
            }
            return top
        }

        fun heuristic(cell: Int): Float {
            val dx = abs((cell % navSize) - goalGx).toFloat()
            val dz = abs((cell / navSize) - goalGz).toFloat()
            return (dx + dz) + (1.4142f - 2f) * min(dx, dz)
        }

        gScore[startCell] = 0f
        fScore[startCell] = heuristic(startCell)
        inOpen[startCell] = true
        heapPush(startCell)

        var bestReachable = startCell
        var bestReachableH = fScore[startCell]
        var expansions = 0
        val maxExpansions = 1800

        while (heapSize > 0 && expansions < maxExpansions) {
            val cur = heapPop()
            if (closed[cur]) continue
            closed[cur] = true
            expansions++

            if (cur == goalCell) {
                bestReachable = goalCell
                break
            }

            val hCur = heuristic(cur)
            if (hCur < bestReachableH) {
                bestReachableH = hCur
                bestReachable = cur
            }

            val curGx = cur % navSize
            val curGz = cur / navSize
            val edgeMask = navEdgeMask[cur]
            if (edgeMask == 0) continue

            for (dir in 0 until 8) {
                if ((edgeMask and (1 shl dir)) == 0) continue
                val nx = curGx + NAV_DX[dir]
                val nz = curGz + NAV_DZ[dir]
                val nCell = nz * navSize + nx
                if (closed[nCell]) continue

                val stepCost = if (NAV_DX[dir] != 0 && NAV_DZ[dir] != 0) 1.4142f else 1.0f
                val tentativeG = gScore[cur] + stepCost
                if (tentativeG < gScore[nCell]) {
                    cameFrom[nCell] = cur
                    gScore[nCell] = tentativeG
                    fScore[nCell] = tentativeG + heuristic(nCell) * 1.08f
                    inOpen[nCell] = true
                    heapPush(nCell)
                }
            }
        }

        // Reconstruct raw cell chain
        val rawPoints = ArrayList<Pair<Float, Float>>()
        var trace = bestReachable
        var safety = 0
        while (trace != -1 && trace != startCell && safety < 256) {
            rawPoints.add(navCellToWorldX(trace % navSize) to navCellToWorldZ(trace / navSize))
            trace = cameFrom[trace]
            safety++
        }
        rawPoints.reverse()
        if (bestReachable == goalCell) {
            rawPoints.add(goalX to goalZ)
        }
        if (rawPoints.isEmpty()) return listOf(goalX to goalZ)

        // String-pulling path smoothing so bots move cleanly down corridors
        val smoothed = ArrayList<Pair<Float, Float>>()
        var anchorX = startX
        var anchorZ = startZ
        var i = 0
        while (i < rawPoints.size) {
            var farthest = i
            val maxLook = min(rawPoints.size - 1, i + 8)
            for (j in maxLook downTo i + 1) {
                val p = rawPoints[j]
                if (hasWalkableLine(anchorX, anchorZ, p.first, p.second)) {
                    farthest = j
                    break
                }
            }
            val chosen = rawPoints[farthest]
            smoothed.add(chosen)
            anchorX = chosen.first
            anchorZ = chosen.second
            i = farthest + 1
        }
        return smoothed
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

            if (root.has("walls") && root.has("wallSize")) {
                wallSize = root.optInt("wallSize", 512)
                wallMin = root.optDouble("wallMin", gridMin.toDouble()).toFloat()
                wallSpan = root.optDouble("wallSpan", gridSpan.toDouble()).toFloat()
                wallMinY = root.optDouble("wallMinY", -4.0).toFloat()
                wallStepY = root.optDouble("wallStepY", 0.5).toFloat().coerceAtLeast(0.1f)
                val wallRle = root.getJSONArray("walls")
                val totalWallCells = wallSize * wallSize
                val decodedWalls = IntArray(totalWallCells)
                var wIdx = 0
                var i = 0
                while (i + 1 < wallRle.length() && wIdx < totalWallCells) {
                    val count = wallRle.getInt(i)
                    val mask = wallRle.getLong(i + 1).toInt()
                    val end = (wIdx + count).coerceAtMost(totalWallCells)
                    if (mask != 0) {
                        for (k in wIdx until end) decodedWalls[k] = mask
                    }
                    wIdx = end
                    i += 2
                }
                wallBits = decodedWalls
            }
        }
    }

    private fun buildVerticalMask(minY: Float, maxY: Float): Int {
        val s0 = floor((minY - wallMinY) / wallStepY).toInt().coerceIn(0, 31)
        val s1 = floor((maxY - wallMinY) / wallStepY).toInt().coerceIn(0, 31)
        var mask = 0
        for (bit in s0..s1) {
            mask = mask or (1 shl bit)
        }
        return mask
    }

    fun hasWallAt(x: Float, z: Float, minY: Float, maxY: Float): Boolean {
        val bits = wallBits ?: return false
        if (wallSize <= 0) return false
        val gx = floor((x - wallMin) / wallSpan * wallSize).toInt()
        val gz = floor((z - wallMin) / wallSpan * wallSize).toInt()
        if (gx !in 0 until wallSize || gz !in 0 until wallSize) return true
        val cellMask = bits[gz * wallSize + gx]
        if (cellMask == 0) return false
        return (cellMask and buildVerticalMask(minY, maxY)) != 0
    }

    /**
     * Picks the best matching walkable floor height at (x, z) near [refY].
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
        fun isClear(px: Float, pz: Float): Boolean {
            val h = sampleFloorHeight(px, pz) ?: return false
            return !hasWallAt(px, pz, h + 0.58f, h + 1.52f) &&
                !hasWallAt(px + 0.30f, pz, h + 0.58f, h + 1.52f) &&
                !hasWallAt(px - 0.30f, pz, h + 0.58f, h + 1.52f) &&
                !hasWallAt(px, pz + 0.30f, h + 0.58f, h + 1.52f) &&
                !hasWallAt(px, pz - 0.30f, h + 0.58f, h + 1.52f)
        }
        if (isClear(x, z)) return x to z
        for (ring in 1..20) {
            val r = ring * 0.42f
            for (step in 0 until 16) {
                val ang = step * (Math.PI * 2.0 / 16.0)
                val tx = x + cos(ang).toFloat() * r
                val tz = z + sin(ang).toFloat() * r
                if (isClear(tx, tz)) return tx to tz
            }
        }
        return x to z
    }

    /**
     * If a crate/box top is present in the vertical wall-bitmask with full standing headroom above it,
     * returns the crate's top surface height so players can cleanly jump onto and stand on climbable crates.
     */
    private fun sampleCrateTopHeight(x: Float, z: Float, minTopY: Float, maxTopY: Float): Float? {
        val bits = wallBits ?: return null
        if (wallSize <= 0) return null
        val gx = floor((x - wallMin) / wallSpan * wallSize).toInt()
        val gz = floor((z - wallMin) / wallSpan * wallSize).toInt()
        if (gx !in 0 until wallSize || gz !in 0 until wallSize) return null
        val cellMask = bits[gz * wallSize + gx]
        if (cellMask == 0) return null

        val minBit = floor((minTopY - wallMinY) / wallStepY).toInt().coerceIn(0, 30)
        val maxBit = floor((maxTopY - wallMinY) / wallStepY).toInt().coerceIn(minBit, 30)
        var highestSolid = -1
        for (bit in minBit..maxBit) {
            if ((cellMask and (1 shl bit)) != 0) {
                highestSolid = bit
            }
        }
        if (highestSolid < 0) return null
        val topH = wallMinY + (highestSolid + 1) * wallStepY
        if (topH < minTopY || topH > maxTopY) return null
        // Require at least 1.5m of clear standing headroom above the crate top (rejects full walls!)
        if (hasWallAt(x, z, topH + 0.08f, topH + 1.52f)) return null
        return topH
    }

    /**
     * Swept multi-step solid wall & floor collision.
     * Supports jumping cleanly onto climbable crates when [actualFeetY] is elevated mid-jump.
     */
    fun resolveMovement(
        curX: Float,
        curY: Float,
        curZ: Float,
        targetX: Float,
        targetZ: Float,
        radius: Float = 0.36f,
        actualFeetY: Float = curY
    ): Triple<Float, Float, Float> {
        var rx = curX
        var rz = curZ
        val baseGroundY = sampleNearestFloorHeight(curX, curZ, curY, maxStepUp = 1.1f, maxDrop = 3.5f)
            ?: sampleFloorHeight(curX, curZ)
            ?: curY
        val curCrateTop = sampleCrateTopHeight(curX, curZ, baseGroundY + 0.30f, actualFeetY + 0.25f)
        var ry = if (curCrateTop != null && curCrateTop > baseGroundY && actualFeetY >= curCrateTop - 0.30f) {
            curCrateTop
        } else {
            baseGroundY
        }

        val totalDx = targetX - curX
        val totalDz = targetZ - curZ
        val totalDist = sqrt(totalDx * totalDx + totalDz * totalDz)
        if (totalDist < 0.0001f) return Triple(rx, ry, rz)

        val isAirborne = actualFeetY > ry + 0.10f

        fun isBlockedByWall(px: Float, pz: Float, floorH: Float): Boolean {
            if (wallBits == null) return false
            // Use the higher of target surface or mid-air feet so jumping clears low crates cleanly
            val effFeet = maxOf(floorH, actualFeetY)
            val bodyLow = effFeet + if (isAirborne) 0.34f else 0.56f
            val bodyHigh = effFeet + 1.52f
            val rDiag = radius * 0.7071f
            return hasWallAt(px, pz, bodyLow, bodyHigh) ||
                hasWallAt(px + radius, pz, bodyLow, bodyHigh) ||
                hasWallAt(px - radius, pz, bodyLow, bodyHigh) ||
                hasWallAt(px, pz + radius, bodyLow, bodyHigh) ||
                hasWallAt(px, pz - radius, bodyLow, bodyHigh) ||
                hasWallAt(px + rDiag, pz + rDiag, bodyLow, bodyHigh) ||
                hasWallAt(px - rDiag, pz + rDiag, bodyLow, bodyHigh) ||
                hasWallAt(px + rDiag, pz - rDiag, bodyLow, bodyHigh) ||
                hasWallAt(px - rDiag, pz - rDiag, bodyLow, bodyHigh)
        }

        fun canStandAt(px: Float, pz: Float, refFloor: Float): Float? {
            // Allow climbing higher surfaces while airborne (jumping onto crates up to ~1.55m)
            val stepUpAllowed = if (isAirborne) maxOf(1.05f, (actualFeetY - refFloor) + 0.45f).coerceAtMost(1.65f) else 0.88f
            val floorCandidate = sampleNearestFloorHeight(px, pz, refFloor, maxStepUp = stepUpAllowed, maxDrop = 3.50f)

            // Check if there is a climbable crate top right under our elevated feet
            val minCrateY = (floorCandidate ?: refFloor) + 0.30f
            val maxCrateY = maxOf(actualFeetY + 0.34f, refFloor + 0.25f)
            val crateTop = if (isAirborne || refFloor > baseGroundY + 0.22f) {
                sampleCrateTopHeight(px, pz, minCrateY, maxCrateY)
            } else null

            val chosenH = when {
                crateTop != null && (floorCandidate == null || crateTop > floorCandidate + 0.15f) && actualFeetY >= crateTop - 0.36f ->
                    crateTop
                floorCandidate != null -> floorCandidate
                else -> return null
            }

            if (isBlockedByWall(px, pz, chosenH)) return null

            // Relax corner neighbor floor check when landing/walking on narrow crate tops
            if (crateTop != null && chosenH == crateTop) {
                return chosenH
            }
            val p1 = sampleNearestFloorHeight(px + radius * 0.75f, pz, chosenH, 1.05f, 3.5f)
            val p2 = sampleNearestFloorHeight(px - radius * 0.75f, pz, chosenH, 1.05f, 3.5f)
            val p3 = sampleNearestFloorHeight(px, pz + radius * 0.75f, chosenH, 1.05f, 3.5f)
            val p4 = sampleNearestFloorHeight(px, pz - radius * 0.75f, chosenH, 1.05f, 3.5f)
            val valid = (if (p1 != null) 1 else 0) +
                (if (p2 != null) 1 else 0) +
                (if (p3 != null) 1 else 0) +
                (if (p4 != null) 1 else 0)
            val minValid = if (chosenH > refFloor + 0.25f) 1 else 2
            return if (valid >= minValid) chosenH else null
        }

        val steps = kotlin.math.ceil((totalDist / 0.14f).toDouble()).toInt().coerceIn(1, 12)
        val stepDx = totalDx / steps
        val stepDz = totalDz / steps

        for (s in 0 until steps) {
            val nx = rx + stepDx
            val nz = rz + stepDz
            val bothH = canStandAt(nx, nz, ry)
            if (bothH != null) {
                rx = nx; rz = nz; ry = bothH
            } else {
                val xOnlyH = canStandAt(nx, rz, ry)
                if (xOnlyH != null) {
                    rx = nx; ry = xOnlyH
                } else {
                    val zOnlyH = canStandAt(rx, nz, ry)
                    if (zOnlyH != null) {
                        rz = nz; ry = zOnlyH
                    } else {
                        break
                    }
                }
            }
        }
        return Triple(rx, ry, rz)
    }

    /**
     * Marches a 3D bullet/LOS ray through the exact 512x512 vertical wall bitmask and floor surfaces.
     * Uses exact point-slice wall checks (no inflated ±0.14m box above crates) and allows shots to fly
     * cleanly through open air over crates, balconies, and gaps without invisible blockers.
     */
    fun raycastWalls(
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
        maxDist: Float
    ): Float {
        if (floorLayers.isEmpty()) return maxDist
        var dist = 0.30f
        val step = 0.20f
        val maxBound = gridMin + gridSpan
        while (dist < maxDist) {
            val px = ox + dx * dist
            val py = oy + dy * dist
            val pz = oz + dz * dist
            if (px < gridMin || px > maxBound || pz < gridMin || pz > maxBound) {
                return dist
            }
            // Exact point-height slice check so bullets pass crisply over crate tops without an invisible tall box
            if (hasWallAt(px, pz, py, py)) {
                return dist
            }
            // Only stop on floor if an actual floor surface exists above the ray (never block mid-air over gaps/crates!)
            val floorH = sampleNearestFloorHeight(px, pz, py, maxStepUp = 0.45f, maxDrop = 0.65f)
            if (floorH != null && py < floorH - 0.02f) {
                return dist
            }
            dist += step
        }
        return maxDist
    }

    data class WallHitResult(
        val hit: Boolean,
        val dist: Float,
        val x: Float,
        val y: Float,
        val z: Float,
        val nx: Float,
        val ny: Float,
        val nz: Float
    )

    /**
     * Precise bullet-impact raycast returning exact hit position + outward surface normal
     * (refined via binary search so bullet hole decals sit flush on walls, crates, and floors).
     */
    fun raycastWallsWithNormal(
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
        maxDist: Float
    ): WallHitResult {
        if (floorLayers.isEmpty()) {
            return WallHitResult(false, maxDist, ox + dx * maxDist, oy + dy * maxDist, oz + dz * maxDist, -dx, -dy, -dz)
        }
        var dist = 0.30f
        var prevDist = 0.25f
        val step = 0.20f
        val maxBound = gridMin + gridSpan
        while (dist < maxDist) {
            val px = ox + dx * dist
            val py = oy + dy * dist
            val pz = oz + dz * dist
            if (px < gridMin || px > maxBound || pz < gridMin || pz > maxBound) {
                return WallHitResult(false, dist, px, py, pz, -dx, -dy, -dz)
            }
            val hitWall = hasWallAt(px, pz, py, py)
            val floorH = if (!hitWall) sampleNearestFloorHeight(px, pz, py, maxStepUp = 0.45f, maxDrop = 0.65f) else null
            val hitFloor = !hitWall && floorH != null && py < floorH - 0.02f
            if (hitWall || hitFloor) {
                // Binary search 5 iterations to pinpoint exact surface contact
                var lo = prevDist
                var hi = dist
                for (iter in 0 until 5) {
                    val mid = (lo + hi) * 0.5f
                    val mx = ox + dx * mid
                    val my = oy + dy * mid
                    val mz = oz + dz * mid
                    val solid = if (hitWall) {
                        hasWallAt(mx, mz, my, my)
                    } else {
                        val fh = sampleNearestFloorHeight(mx, mz, my, maxStepUp = 0.45f, maxDrop = 0.65f)
                        fh != null && my < fh - 0.02f
                    }
                    if (solid) hi = mid else lo = mid
                }
                val hitDist = lo
                val hx = ox + dx * hitDist
                val hy = oy + dy * hitDist
                val hz = oz + dz * hitDist
                if (hitFloor) {
                    val surfaceY = sampleNearestFloorHeight(hx, hz, hy, maxStepUp = 0.55f, maxDrop = 0.55f) ?: hy
                    return WallHitResult(true, hitDist, hx, surfaceY, hz, 0f, 1f, 0f)
                }
                // Estimate outward horizontal wall normal by probing adjacent wall-bitmask cells
                val probe = 0.24f
                val solidPosX = hasWallAt(hx + probe, hz, hy, hy)
                val solidNegX = hasWallAt(hx - probe, hz, hy, hy)
                val solidPosZ = hasWallAt(hx, hz + probe, hy, hy)
                val solidNegZ = hasWallAt(hx, hz - probe, hy, hy)
                var nx = (if (solidNegX) 1f else 0f) - (if (solidPosX) 1f else 0f)
                var nz = (if (solidNegZ) 1f else 0f) - (if (solidPosZ) 1f else 0f)
                // If probing was symmetric (e.g. thin wall or corner), align with dominant incoming ray axis
                if (nx == 0f && nz == 0f) {
                    if (abs(dx) >= abs(dz)) {
                        nx = if (dx > 0f) -1f else 1f
                    } else {
                        nz = if (dz > 0f) -1f else 1f
                    }
                }
                // Guarantee normal points back toward the shooter
                if (nx * dx + nz * dz > 0f) {
                    nx = -nx; nz = -nz
                }
                val nLen = sqrt(nx * nx + nz * nz).coerceAtLeast(0.001f)
                return WallHitResult(true, hitDist, hx, hy, hz, nx / nLen, 0f, nz / nLen)
            }
            prevDist = dist
            dist += step
        }
        return WallHitResult(false, maxDist, ox + dx * maxDist, oy + dy * maxDist, oz + dz * maxDist, -dx, -dy, -dz)
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

    companion object {
        private val NAV_DX = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
        private val NAV_DZ = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
    }
}
