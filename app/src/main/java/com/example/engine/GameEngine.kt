package com.example.engine

import android.content.Context
import android.os.SystemClock
import com.example.audio.SoundEngine
import com.example.model.ArenaMapId
import com.example.model.AspectRatioMode
import com.example.model.BombState
import com.example.model.BombSyncEvent
import com.example.model.DeathRecapInfo
import com.example.model.EspBox
import com.example.model.GameMode
import com.example.model.GameSettings
import com.example.model.GraphicsSettings
import com.example.model.HudSnapshot
import com.example.model.KillFeedEntry
import com.example.model.KillFeedSyncEvent
import com.example.model.MatchConfig
import com.example.model.MatchSyncEvent
import com.example.model.OutgoingHitEvent
import com.example.model.RadarBlip
import com.example.model.RemoteHumanPlayer
import com.example.model.ScoreboardRow
import com.example.model.TeamSide
import com.example.model.WeaponSlot
import com.example.model.WeaponType
import com.example.online.ADMIN_SHORT_ID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

data class BulletImpactMark(
    val x: Float,
    val y: Float,
    val z: Float,
    val nx: Float,
    val ny: Float,
    val nz: Float,
    val rollDeg: Float
)

data class BulletTracer(
    val startX: Float,
    val startY: Float,
    val startZ: Float,
    val endX: Float,
    val endY: Float,
    val endZ: Float,
    var lifeSec: Float,
    val maxLifeSec: Float
)

data class ExplosionState(
    var active: Boolean = false,
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f,
    var elapsedSec: Float = 0f,
    var durationSec: Float = 4.2f
) {
    val progress: Float
        get() = if (!active || durationSec <= 0f) 0f else (elapsedSec / durationSec).coerceIn(0f, 1f)
}

class GameEngine(
    private val appContext: Context,
    private val soundEngine: SoundEngine,
    private val onHapticPulse: (isHeavy: Boolean) -> Unit,
    private val onMatchFinished: (score: Int, kills: Int, headshots: Int) -> Unit
) {
    @Volatile
    var world: ArenaWorld = ArenaWorld(appContext, ArenaMapId.MIRAGE)
        private set

    private val rng = Random(System.currentTimeMillis())

    @Volatile
    var settings: GameSettings = GameSettings()
        set(value) {
            field = value
            soundEngine.volume = value.audio.sfxVolume
        }

    @Volatile
    var isPaused: Boolean = false

    @Volatile
    var matchConfig: MatchConfig = MatchConfig()
        private set

    // Input state (UI thread -> Render/Sim thread)
    @Volatile var moveInputX: Float = 0f
    @Volatile var moveInputY: Float = 0f
    @Volatile var isFiring: Boolean = false
    @Volatile var isAdsToggle: Boolean = false
    @Volatile var isCrouching: Boolean = false
    @Volatile var isBombActionHeld: Boolean = false

    private val lookLock = Any()
    private var pendingLookDx: Float = 0f
    private var pendingLookDy: Float = 0f
    @Volatile private var pendingJump: Boolean = false
    @Volatile private var pendingReload: Boolean = false
    @Volatile private var pendingReset: Boolean = false
    @Volatile private var pendingSlotSwitch: WeaponSlot? = null
    @Volatile private var pendingQuickSwitch: Boolean = false
    @Volatile private var pendingInspect: Boolean = false
    @Volatile private var pendingBuyWeapon: WeaponType? = null
    @Volatile private var pendingBuyArmor: Boolean = false
    @Volatile private var pendingBuyKit: Boolean = false

    // Player physical state (1:1 human scale on real 3D heightmap)
    var playerX: Float = 39.1f
    var playerY: Float = -0.16f
    var playerZ: Float = -4.6f
    var playerFloorY: Float = -0.16f
    var playerVelY: Float = 0f
    var eyeHeight: Float = 1.60f
    var playerYawRad: Float = 0f
    var playerPitchRad: Float = 0f

    // Player CS2 Inventory Slots
    @Volatile var activeWeapon: WeaponType = WeaponType.GLOCK_18
        private set
    private var lastWeapon: WeaponType = WeaponType.KNIFE
    private var primaryWeapon: WeaponType? = null
    private var pistolWeapon: WeaponType = WeaponType.GLOCK_18
    private var playerHasBomb: Boolean = false
    private val magAmmoMap = mutableMapOf<WeaponType, Int>()
    private val reserveAmmoMap = mutableMapOf<WeaponType, Int>()
    private var lastTriggerPressed: Boolean = false

    // Viewmodel animation, equip/draw lockout, muzzle flash & C4 detonation VFX state
    var adsBlend: Float = 0f
    var walkBobPhase: Float = 0f
    var moveSpeedRatio: Float = 0f
    var recoilKick: Float = 0f
    var muzzleFlashTimer: Float = 0f
    var muzzleFlashRoll: Float = 0f
    var boltProgress: Float = 0f
    var knifeSwingProgress: Float = 0f
    var bombPlantProgress: Float = 0f
    var cameraShake: Float = 0f
    var explosionFlashAlpha: Float = 0f
    var timeSec: Float = 0f

    var equipTimer: Float = 0f
    var equipDuration: Float = 0.60f
    val isEquipping: Boolean
        get() = equipTimer > 0.001f
    val equipProgress: Float
        get() = if (equipDuration <= 0.001f) 1f else (1f - equipTimer / equipDuration).coerceIn(0f, 1f)

    var inspectTimer: Float = 0f
    var inspectDuration: Float = 3.2f
    val isInspecting: Boolean
        get() = inspectTimer > 0.001f
    val inspectProgress: Float
        get() = if (inspectDuration <= 0.001f) 0f else (1f - inspectTimer / inspectDuration).coerceIn(0f, 1f)

    // Smooth 3D viewmodel inertia (camera-turn sway, strafe bank, jump/landing spring)
    var weaponSwayX: Float = 0f
    var weaponSwayY: Float = 0f
    var strafeRoll: Float = 0f
    var landingDip: Float = 0f
    private var landingVel: Float = 0f
    private var wasGrounded: Boolean = true

    val explosion = ExplosionState()

    private var isReloading: Boolean = false
    private var reloadTimer: Float = 0f
    private var fireTimer: Float = 0f

    // Match, Warmup (60s), Freeze Time (8s), Round, Economy & C4 Bomb state
    private var isWarmup: Boolean = true
    private var warmupTimer: Float = 60f
    @Volatile private var freezeTimeSec: Float = 0f
    @Volatile private var spawnProtectionTimer: Float = 0f
    @Volatile private var pendingSkipWarmup: Boolean = false
    private var hp: Float = 100f
    private var armor: Float = 0f
    private var hasDefuseKit: Boolean = false
    private var money: Int = 800
    @Volatile private var isPlayerDead: Boolean = false
    private var tdmRespawnTimer: Float = 0f

    private var ctScore: Int = 0
    private var tScore: Int = 0
    private var roundNumber: Int = 1
    private var roundTimeSec: Float = 105f
    private var buyTimeRemaining: Float = 28f
    private var roundOverTimer: Float = 0f
    private var roundOverWinner: TeamSide = TeamSide.CT
    private var roundOverReason: String = ""
    @Volatile private var hasSwappedSides: Boolean = false
    @Volatile var awaitingTeamSelection: Boolean = false
        private set
    @Volatile private var pendingTeamSelection: TeamSide? = null
    @Volatile var onTeamChanged: ((TeamSide) -> Unit)? = null
    @Volatile private var isRoundOver: Boolean = false
    @Volatile private var isMatchFinished: Boolean = false
    private var isPlayerTeamWinner: Boolean = false
    private var matchRecorded: Boolean = false

    @Volatile var bombState: BombState = BombState.CARRIED
        private set
    @Volatile var plantedBombX: Float = 0f
        private set
    @Volatile var plantedBombY: Float = 0f
        private set
    @Volatile var plantedBombZ: Float = 0f
        private set
    private var plantedSiteLabel: String = ""
    @Volatile var bombCountdownSec: Float = 35f
        private set
    private var bombBeepTimer: Float = 0f
    private var plantBeepTimer: Float = 0f

    // Authoritative multiplayer bomb, round, score & halftime sync bridges
    @Volatile var latestBombSync: BombSyncEvent? = null
        private set
    private var localBombSyncSeq: Int = 0
    private val remoteBombLock = Any()
    private val pendingRemoteBombEvents = ArrayDeque<BombSyncEvent>()
    private val lastAppliedBombSeqBySender = HashMap<String, Int>()

    @Volatile var latestMatchSync: MatchSyncEvent? = null
        private set
    private var localMatchSyncSeq: Int = 0
    private val remoteMatchLock = Any()
    private val pendingRemoteMatchEvents = ArrayDeque<MatchSyncEvent>()
    private val lastAppliedMatchSeqBySender = HashMap<String, Int>()
    private var matchSyncHeartbeatTimer: Float = 0f

    // Combat stats, Damage Exchange Recap, Bloodied Death Overlay & Ally Spectator state
    private var score: Int = 0
    private var kills: Int = 0
    private var deaths: Int = 0
    private var headshots: Int = 0
    private var shotsFired: Int = 0
    private var shotsHit: Int = 0
    private var hitMarkerAlpha: Float = 0f
    private var isHeadshotHit: Boolean = false
    private var isKillHit: Boolean = false
    private var damageFlashAlpha: Float = 0f
    private var deathBloodTimer: Float = 0f
    private var deathRecapTimer: Float = 0f
    private var activeDeathRecap: DeathRecapInfo? = null
    private val damageDealtByTarget = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val damageTakenByAttacker = ConcurrentHashMap<String, Pair<Int, Int>>()
    // Anti-trade tracker: maps targetUid -> (elapsedMs, wasHeadshot) for recent lethal hits we sent
    private val lastLethalSentTo = ConcurrentHashMap<String, Pair<Long, Boolean>>()

    // Persistent 3D wall/floor bullet impact decals (max 48 per round) & brief yellow laser bullet tracers
    val bulletImpacts = ArrayList<BulletImpactMark>(48)
    val bulletTracers = ArrayList<BulletTracer>(16)

    // Smooth Ally Spectator camera state (read by FilamentGameplayController)
    private var deathCamDelayTimer: Float = 0f
    private var spectateTargetIndex: Int = 0
    @Volatile private var pendingSpectateDelta: Int = 0
    @Volatile var isSpectatingAlly: Boolean = false
        private set
    var spectateCamX: Float = 0f
        private set
    var spectateCamY: Float = 0f
        private set
    var spectateCamZ: Float = 0f
        private set
    var spectateLookX: Float = 0f
        private set
    var spectateLookY: Float = 0f
        private set
    var spectateLookZ: Float = 0f
        private set
    private var spectateCamInitialized: Boolean = false
    private var spectatedAllyName: String = ""
    private var spectatedAllyHp: Int = 0
    private var spectatedAllyArmor: Int = 0
    private var spectatedAllyWeapon: String = ""
    private var spectatedAllyCount: Int = 0

    private var bannerText: String = ""
    private var bannerTimer: Float = 0f
    private var killFeedText: String = ""
    private var killFeedTimer: Float = 0f
    private data class TimedKillFeed(val entry: KillFeedEntry, var remainingSec: Float)
    private val killFeedList = ArrayList<TimedKillFeed>(6)
    private var killFeedIdGen: Long = 1L
    private val killFeedSyncLock = Any()
    private val pendingOutgoingKillFeeds = ArrayDeque<KillFeedSyncEvent>()
    private val pendingIncomingKillFeeds = ArrayDeque<KillFeedSyncEvent>()
    private val lastKillFeedSeqBySender = HashMap<String, Int>()
    private var outgoingKillFeedSeq: Int = 0

    // Admin features strictly restricted to player with shortId == "932760"
    val isLocalAdmin: Boolean
        get() = matchConfig.localShortId == ADMIN_SHORT_ID
    @Volatile var adminEspEnabled: Boolean = false
    @Volatile var adminAutoAimEnabled: Boolean = false
    @Volatile var viewportAspect: Float = 16f / 9f
    private var adminLockedOnTarget: Boolean = false
    private val pendingKickBotIds = ArrayDeque<Int>()
    private val kickBotLock = Any()

    private var nextBotId: Int = 1
    private var lastTStrategy: Int = -1
    private var lastCtStrategy: Int = -1
    @Volatile var livePingMs: Int = 0

    val bots = mutableListOf<BotOperative>()

    // Real-time multiplayer remote human players (thread-safe bridge with Supabase Realtime & REST)
    private val remoteLock = Any()
    private val remotePlayersMap = LinkedHashMap<String, RemoteHumanPlayer>()
    private val lastProcessedHitSeq = HashMap<String, Int>()
    private val pendingIncomingPvpHits = ArrayDeque<IncomingPvpHit>()
    @Volatile var lastOutgoingHit: OutgoingHitEvent? = null
        private set
    private var outgoingHitSeq: Int = 0

    private data class IncomingPvpHit(
        val attackerId: String,
        val attackerName: String,
        val attackerSide: TeamSide,
        val weaponName: String,
        val headshot: Boolean,
        val damage: Float
    )

    private var fpsFrameCount: Int = 0
    private var fpsAccumTime: Float = 0f
    private var currentFps: Int = 60

    @Volatile
    var latestHud: HudSnapshot = HudSnapshot()
        private set

    fun getRemotePlayersSnapshot(): List<RemoteHumanPlayer> = synchronized(remoteLock) {
        remotePlayersMap.values.map { it.copy() }
    }

    fun fillRemotePlayersSnapshot(out: MutableList<RemoteHumanPlayer>) {
        out.clear()
        synchronized(remoteLock) {
            for (rp in remotePlayersMap.values) {
                out.add(rp.copy())
            }
        }
    }

    /**
     * Thread-safe enqueue for incoming C4 Bomb & Warmup-skip sync events from Supabase Realtime WebSocket.
     */
    fun enqueueRemoteBombSync(event: BombSyncEvent) {
        synchronized(remoteBombLock) {
            if (pendingRemoteBombEvents.size > 16) pendingRemoteBombEvents.removeFirst()
            pendingRemoteBombEvents.addLast(event)
        }
    }

    /**
     * Thread-safe enqueue for authoritative round/score/warmup/halftime-swap sync events.
     */
    fun enqueueRemoteMatchSync(event: MatchSyncEvent) {
        synchronized(remoteMatchLock) {
            if (pendingRemoteMatchEvents.size > 16) pendingRemoteMatchEvents.removeFirst()
            pendingRemoteMatchEvents.addLast(event)
        }
    }

    private fun isAuthoritativeForMatch(): Boolean {
        if (!matchConfig.isMultiplayer) return true
        if (matchConfig.isMatchHost) return true
        // Deterministic failover if original host disconnected
        val myUid = matchConfig.localUserId
        if (myUid.isBlank()) return false
        synchronized(remoteLock) {
            if (remotePlayersMap.isEmpty()) return true
            return remotePlayersMap.keys.all { myUid <= it }
        }
    }

    /**
     * Thread-safe enqueue for incoming synchronized killfeed entries from Supabase Realtime WebSocket.
     */
    fun enqueueRemoteKillFeed(event: KillFeedSyncEvent) {
        synchronized(killFeedSyncLock) {
            if (pendingIncomingKillFeeds.size > 24) pendingIncomingKillFeeds.removeFirst()
            pendingIncomingKillFeeds.addLast(event)
        }
    }

    /**
     * Drains locally-emitted killfeed events for immediate WebSocket broadcast to all match clients.
     */
    fun drainOutgoingKillFeeds(): List<KillFeedSyncEvent> = synchronized(killFeedSyncLock) {
        if (pendingOutgoingKillFeeds.isEmpty()) return emptyList()
        val out = ArrayList<KillFeedSyncEvent>(pendingOutgoingKillFeeds.size)
        while (pendingOutgoingKillFeeds.isNotEmpty()) {
            out.add(pendingOutgoingKillFeeds.removeFirst())
        }
        out
    }

    /**
     * Admin-only action: kicks a bot out of the active match (strictly verified against ADMIN_SHORT_ID).
     */
    fun requestKickBot(botId: Int) {
        if (!isLocalAdmin) return
        synchronized(kickBotLock) {
            pendingKickBotIds.addLast(botId)
        }
    }

    private fun drainPendingBotKicks() {
        val ids = synchronized(kickBotLock) {
            if (pendingKickBotIds.isEmpty()) return
            val list = ArrayList<Int>(pendingKickBotIds.size)
            while (pendingKickBotIds.isNotEmpty()) list.add(pendingKickBotIds.removeFirst())
            list
        }
        if (!isLocalAdmin) return
        for (id in ids) {
            bots.removeAll { it.id == id }
        }
    }

    private fun drainIncomingKillFeeds() {
        val batch = synchronized(killFeedSyncLock) {
            if (pendingIncomingKillFeeds.isEmpty()) return
            val list = ArrayList<KillFeedSyncEvent>(pendingIncomingKillFeeds.size)
            while (pendingIncomingKillFeeds.isNotEmpty()) {
                list.add(pendingIncomingKillFeeds.removeFirst())
            }
            list
        }
        val localUid = matchConfig.localUserId
        for (ev in batch) {
            if (ev.senderUid.isNotBlank() && ev.seq > 0) {
                val prevSeq = lastKillFeedSeqBySender[ev.senderUid] ?: 0
                if (ev.seq <= prevSeq) continue
                lastKillFeedSeqBySender[ev.senderUid] = ev.seq
            }
            val kTeam = if (ev.killerTeam == TeamSide.CT.name) TeamSide.CT else TeamSide.T
            val vTeam = if (ev.victimTeam == TeamSide.CT.name) TeamSide.CT else TeamSide.T
            pushKillFeed(
                killerName = ev.killerName,
                killerTeam = kTeam,
                victimName = ev.victimName,
                victimTeam = vTeam,
                weaponLabel = ev.weaponLabel,
                isHeadshot = ev.isHeadshot,
                killerIsLocal = localUid.isNotBlank() && ev.killerUid == localUid,
                victimIsLocal = localUid.isNotBlank() && ev.victimUid == localUid,
                broadcast = false
            )
        }
    }

    private fun pushKillFeed(
        killerName: String,
        killerTeam: TeamSide,
        victimName: String,
        victimTeam: TeamSide,
        weaponLabel: String,
        isHeadshot: Boolean = false,
        killerIsLocal: Boolean = false,
        victimIsLocal: Boolean = false,
        broadcast: Boolean = true,
        killerUid: String = "",
        victimUid: String = ""
    ) {
        val cleanKiller = killerName.ifBlank { killerTeam.shortRu }
        val cleanVictim = victimName.ifBlank { victimTeam.shortRu }
        val cleanWeapon = weaponLabel.ifBlank { "ОРУЖИЕ" }

        // Deduplicate simultaneous killer + victim reports of the same kill within 2.8 seconds
        val alreadyExists = killFeedList.any { item ->
            item.remainingSec >= 2.8f &&
                item.entry.killerName == cleanKiller &&
                item.entry.victimName == cleanVictim &&
                item.entry.weaponLabel == cleanWeapon
        }
        if (alreadyExists) return

        val entry = KillFeedEntry(
            id = killFeedIdGen++,
            killerName = cleanKiller,
            killerTeam = killerTeam,
            victimName = cleanVictim,
            victimTeam = victimTeam,
            weaponLabel = cleanWeapon,
            isHeadshot = isHeadshot,
            killerIsLocal = killerIsLocal,
            victimIsLocal = victimIsLocal
        )
        killFeedList.add(TimedKillFeed(entry, remainingSec = 5.6f))
        while (killFeedList.size > 5) {
            killFeedList.removeAt(0)
        }
        val hsTag = if (isHeadshot) " [ГОЛОВА]" else ""
        killFeedText = "${entry.killerName} [${entry.weaponLabel}]$hsTag → ${entry.victimName}"
        killFeedTimer = 3.5f

        if (broadcast && matchConfig.isMultiplayer) {
            val resolvedKillerUid = killerUid.ifBlank { if (killerIsLocal) matchConfig.localUserId else "" }
            val resolvedVictimUid = victimUid.ifBlank { if (victimIsLocal) matchConfig.localUserId else "" }
            synchronized(killFeedSyncLock) {
                outgoingKillFeedSeq++
                if (pendingOutgoingKillFeeds.size > 16) pendingOutgoingKillFeeds.removeFirst()
                pendingOutgoingKillFeeds.addLast(
                    KillFeedSyncEvent(
                        seq = outgoingKillFeedSeq,
                        killerName = cleanKiller,
                        killerTeam = killerTeam.name,
                        victimName = cleanVictim,
                        victimTeam = victimTeam.name,
                        weaponLabel = cleanWeapon,
                        isHeadshot = isHeadshot,
                        killerUid = resolvedKillerUid,
                        victimUid = resolvedVictimUid,
                        senderUid = matchConfig.localUserId
                    )
                )
            }
        }
    }

    /**
     * Ultra-fast single-player update path invoked directly from the 30Hz Supabase Realtime WebSocket
     * (bypasses Compose recomposition latency completely!).
     */
    fun applyRemoteSingleState(
        localUid: String,
        st: com.example.online.PlayerMatchState
    ) {
        if (st.userId.isBlank() || st.userId == localUid) return
        val nowMs = SystemClock.elapsedRealtime()
        synchronized(remoteLock) {
            upsertRemotePlayerLocked(localUid, st, nowMs, fromWebSocket = true)
        }
    }

    /**
     * Called from Supabase PostgREST snapshot sync when full lobby member list arrives.
     * Protects fresh 30Hz WebSocket transforms from stale 1.6s DB polls and applies a 4.5s
     * reconnect grace window so players never flicker or vanish on brief network hiccups.
     */
    fun applyRemoteStates(
        localUid: String,
        remoteStates: List<com.example.online.PlayerMatchState>
    ) {
        val nowMs = SystemClock.elapsedRealtime()
        synchronized(remoteLock) {
            val activeUids = HashSet<String>()
            for (st in remoteStates) {
                if (st.userId.isBlank() || st.userId == localUid) continue
                activeUids.add(st.userId)
                upsertRemotePlayerLocked(localUid, st, nowMs, fromWebSocket = false)
            }
            // Keep players seen via WebSocket or DB within the 4.5s reconnect grace window;
            // prune genuinely disconnected players so zero ghost models ever linger!
            val it = remotePlayersMap.entries.iterator()
            while (it.hasNext()) {
                val (uid, rp) = it.next()
                if (uid !in activeUids && (nowMs - rp.lastSeenMs) > 4_500L) {
                    it.remove()
                    lastProcessedHitSeq.remove(uid)
                }
            }
        }
    }

    private fun upsertRemotePlayerLocked(
        localUid: String,
        st: com.example.online.PlayerMatchState,
        nowMs: Long,
        fromWebSocket: Boolean
    ) {
        val side = if (st.team == "CT") TeamSide.CT else TeamSide.T
        val wep = WeaponType.entries.firstOrNull { it.name == st.weapon } ?: WeaponType.AK47
        val hasTransform = !st.x.isNaN() && !st.y.isNaN() && !st.z.isNaN()
        val newX = if (hasTransform) st.x.toFloat() else 0f
        val newY = if (hasTransform) st.y.toFloat() else 0f
        val newZ = if (hasTransform) st.z.toFloat() else 0f
        val newPitch = st.pitchDeg.toFloat().coerceIn(-55f, 55f)
        val existing = remotePlayersMap[st.userId]
        if (existing == null) {
            if (hasTransform) {
                remotePlayersMap[st.userId] = RemoteHumanPlayer(
                    userId = st.userId,
                    shortId = st.shortId,
                    displayName = st.displayName,
                    team = side,
                    x = newX,
                    y = newY,
                    z = newZ,
                    targetX = newX,
                    targetY = newY,
                    targetZ = newZ,
                    lastSyncMs = nowMs,
                    lastWsSyncMs = if (fromWebSocket) nowMs else 0L,
                    lastSeenMs = nowMs,
                    yawDeg = st.yawDeg.toFloat(),
                    targetYawDeg = st.yawDeg.toFloat(),
                    pitchDeg = newPitch,
                    targetPitchDeg = newPitch,
                    hp = st.hp,
                    isDead = st.dead,
                    deathProgress = if (st.dead) 1f else 0f,
                    isMoving = st.moving,
                    isShooting = st.shooting,
                    weapon = wep,
                    kills = st.kills,
                    deaths = st.deaths
                )
            }
            // Baseline hitSeq on first observe so old hits aren't replayed on join
            lastProcessedHitSeq[st.userId] = st.hitSeq
        } else {
            existing.lastSeenMs = nowMs
            existing.team = side
            if (hasTransform) {
                existing.kills = st.kills
                existing.deaths = st.deaths

                // Guard: never let a 2.2s delayed PostgREST DB snapshot snap a player backwards over a fresh 50Hz WS packet!
                val ignoreStaleTransform = !fromWebSocket &&
                    existing.lastWsSyncMs > 0L &&
                    (nowMs - existing.lastWsSyncMs) < 2_200L

                if (!ignoreStaleTransform) {
                    val dtSync = ((nowMs - existing.lastSyncMs) / 1000f).coerceIn(0.016f, 0.40f)
                    val dx = newX - existing.targetX
                    val dy = newY - existing.targetY
                    val dz = newZ - existing.targetZ
                    val jumpDistSq = dx * dx + dz * dz
                    if (jumpDistSq > 36f) {
                        existing.x = newX
                        existing.y = newY
                        existing.z = newZ
                        existing.vx = 0f
                        existing.vy = 0f
                        existing.vz = 0f
                    } else if (st.moving && !st.dead) {
                        existing.vx = (dx / dtSync).coerceIn(-7.8f, 7.8f)
                        existing.vy = (dy / dtSync).coerceIn(-8.0f, 8.0f)
                        existing.vz = (dz / dtSync).coerceIn(-7.8f, 7.8f)
                    } else {
                        existing.vx = 0f
                        existing.vy = 0f
                        existing.vz = 0f
                    }
                    existing.targetX = newX
                    existing.targetY = newY
                    existing.targetZ = newZ
                    existing.lastSyncMs = nowMs
                    if (fromWebSocket) existing.lastWsSyncMs = nowMs
                    existing.targetYawDeg = st.yawDeg.toFloat()
                    existing.targetPitchDeg = newPitch
                    existing.hp = st.hp
                    if (existing.isDead != st.dead) {
                        // Reset accumulated exchange damage immediately on opponent death or respawn!
                        damageDealtByTarget.remove(st.userId)
                        damageTakenByAttacker.remove(st.userId)
                    }
                    existing.isDead = st.dead
                    if (!st.dead) existing.deathProgress = 0f
                    existing.isMoving = st.moving
                    existing.isShooting = st.shooting
                    existing.weapon = wep
                }
            }

            val prevSeq = lastProcessedHitSeq[st.userId] ?: 0
            if (st.hitSeq > prevSeq) {
                lastProcessedHitSeq[st.userId] = st.hitSeq
                // Strictly drop stale hits during team pick, round-end, freeze time, death, or post-spawn grace!
                val canReceiveHitNow = !awaitingTeamSelection && !isRoundOver && !isMatchFinished && !isPlayerDead &&
                    freezeTimeSec <= 0f && spawnProtectionTimer <= 0f
                if (canReceiveHitNow && st.hitTargetUid == localUid && st.hitDamage > 0 && side != matchConfig.playerSide) {
                    pendingIncomingPvpHits.addLast(
                        IncomingPvpHit(
                            attackerId = st.userId,
                            attackerName = st.displayName.ifBlank { side.shortRu },
                            attackerSide = side,
                            weaponName = st.hitWeapon.ifBlank { wep.shortLabel },
                            headshot = st.hitHeadshot,
                            damage = st.hitDamage.toFloat()
                        )
                    )
                }
            }
        }
    }

    private fun drainIncomingPvpHits() {
        val batch = ArrayList<IncomingPvpHit>()
        synchronized(remoteLock) {
            while (pendingIncomingPvpHits.isNotEmpty()) {
                batch.add(pendingIncomingPvpHits.removeFirst())
            }
        }
        val nowMs = SystemClock.elapsedRealtime()
        for (hit in batch) {
            if (awaitingTeamSelection || isPlayerDead || isRoundOver || isMatchFinished || freezeTimeSec > 0f || spawnProtectionTimer > 0f) {
                continue
            }
            // Anti-trade / No simultaneous mutual kills:
            // 1. If the attacker is ALREADY confirmed dead, reject post-mortem in-flight hits!
            val attackerAlreadyDead = synchronized(remoteLock) {
                remotePlayersMap[hit.attackerId]?.isDead == true
            }
            if (attackerAlreadyDead) continue

            // 2. If both players landed lethal shots on each other within RTT window (380ms), resolve deterministically
            //    so strictly ONE player dies and NEVER both simultaneously!
            val absorbed = minOf(armor, hit.damage * 0.55f)
            val predictedLoss = (hit.damage - absorbed * 0.5f).coerceAtLeast(1f)
            val wouldKillLocal = (hp - predictedLoss) <= 0f
            val myRecentLethal = lastLethalSentTo[hit.attackerId]
            if (wouldKillLocal && myRecentLethal != null && (nowMs - myRecentLethal.first) <= 380L) {
                val myHeadshot = myRecentLethal.second
                val localWinsTie = when {
                    myHeadshot && !hit.headshot -> true
                    !myHeadshot && hit.headshot -> false
                    else -> matchConfig.localUserId < hit.attackerId
                }
                if (localWinsTie) continue
            }

            applyPlayerDamage(
                amount = hit.damage,
                attackerSide = hit.attackerSide,
                killedByBomb = false,
                attackerId = hit.attackerId,
                attackerName = hit.attackerName,
                attackerWeapon = hit.weaponName,
                isHeadshot = hit.headshot
            )
        }
    }

    private fun drainIncomingMatchEvents() {
        val batch = ArrayList<MatchSyncEvent>()
        synchronized(remoteMatchLock) {
            while (pendingRemoteMatchEvents.isNotEmpty()) {
                batch.add(pendingRemoteMatchEvents.removeFirst())
            }
        }
        for (ev in batch) {
            val senderKey = ev.senderUid.ifBlank { "host" }
            val lastSeq = lastAppliedMatchSeqBySender[senderKey] ?: 0
            if (ev.seq <= lastSeq) continue
            lastAppliedMatchSeqBySender[senderKey] = ev.seq

            // Host is authoritative; ignore stale packets if we ourselves are authoritative
            if (isAuthoritativeForMatch()) continue

            // 1. Warmup -> Live match alignment
            if (!ev.isWarmup && isWarmup) {
                endWarmupAndStartLiveMatch(broadcast = false)
            }

            // 2. Halftime side swap alignment
            val swappedJustNow = ev.sidesSwapped && !hasSwappedSides
            if (swappedJustNow) {
                performHalftimeSideSwap(broadcast = false)
            }

            // 3. Authoritative scores
            ctScore = ev.ctScore
            tScore = ev.tScore

            // 4. Authoritative round number & round-over state
            if (ev.roundNumber > roundNumber) {
                roundNumber = ev.roundNumber
                if (ev.isRoundOver) {
                    isRoundOver = true
                    roundOverTimer = 3.8f
                    if (ev.reason.isNotBlank()) {
                        bannerText = "${ev.reason} (CT $ctScore : $tScore T)"
                        bannerTimer = 3.8f
                    }
                } else {
                    startNewRound(
                        keepPlayerGear = !isPlayerDead && !swappedJustNow,
                        resetBotsEconomy = swappedJustNow,
                        broadcast = false
                    )
                }
            } else if (ev.roundNumber == roundNumber) {
                if (ev.isRoundOver && !isRoundOver) {
                    isRoundOver = true
                    roundOverTimer = 4.2f
                    val winner = if (ev.winnerSide == TeamSide.CT.name) TeamSide.CT else TeamSide.T
                    val playerWon = winner == matchConfig.playerSide
                    money = (money + if (playerWon) 3250 else 2150).coerceAtMost(16000)
                    for (b in bots) {
                        val won = b.team == winner
                        b.money = (b.money + if (won) 3250 else 2150).coerceAtMost(16000)
                    }
                    val reasonLabel = ev.reason.ifBlank {
                        if (winner == TeamSide.CT) "ПОБЕДА СПЕЦНАЗА" else "ПОБЕДА ТЕРРОРИСТОВ"
                    }
                    bannerText = "$reasonLabel (CT $ctScore : $tScore T)"
                    bannerTimer = 3.8f
                    if (ctScore >= matchConfig.roundsToWin || tScore >= matchConfig.roundsToWin) {
                        roundOverTimer = 3.4f
                    }
                } else if (!ev.isRoundOver && isRoundOver) {
                    startNewRound(
                        keepPlayerGear = !isPlayerDead && !swappedJustNow,
                        resetBotsEconomy = swappedJustNow,
                        broadcast = false
                    )
                } else if (!ev.isRoundOver && !isWarmup) {
                    // Gently keep round & freeze timers tightly aligned with host
                    if (abs(roundTimeSec - ev.roundTimeSec) > 1.5f) {
                        roundTimeSec = ev.roundTimeSec.coerceAtLeast(0f)
                    }
                    if (abs(freezeTimeSec - ev.freezeTimeSec) > 0.9f) {
                        freezeTimeSec = ev.freezeTimeSec.coerceAtLeast(0f)
                    }
                }
            }

            if (ctScore >= matchConfig.roundsToWin || tScore >= matchConfig.roundsToWin) {
                if (!isRoundOver) finishMatch()
            }
        }
    }

    private fun emitMatchSyncEvent(winnerSide: String = "", reason: String = "") {
        if (!matchConfig.isMultiplayer) return
        if (!isAuthoritativeForMatch()) return
        localMatchSyncSeq++
        latestMatchSync = MatchSyncEvent(
            seq = localMatchSyncSeq,
            roundNumber = roundNumber,
            ctScore = ctScore,
            tScore = tScore,
            isWarmup = isWarmup,
            isRoundOver = isRoundOver,
            sidesSwapped = hasSwappedSides,
            winnerSide = winnerSide,
            reason = reason,
            roundTimeSec = roundTimeSec,
            freezeTimeSec = freezeTimeSec,
            senderUid = matchConfig.localUserId
        )
    }

    private fun drainIncomingBombEvents() {
        val batch = ArrayList<BombSyncEvent>()
        synchronized(remoteBombLock) {
            while (pendingRemoteBombEvents.isNotEmpty()) {
                batch.add(pendingRemoteBombEvents.removeFirst())
            }
        }
        for (ev in batch) {
            val senderKey = ev.senderUid.ifBlank { "remote" }
            val lastSeq = lastAppliedBombSeqBySender[senderKey] ?: 0
            if (ev.seq <= lastSeq) continue
            lastAppliedBombSeqBySender[senderKey] = ev.seq

            // If remote player skipped warmup into live match, align immediately
            if (!ev.isWarmup && isWarmup) {
                endWarmupAndStartLiveMatch(broadcast = false)
            }

            when (ev.state) {
                BombState.PLANTED -> {
                    if (bombState != BombState.PLANTED && !isRoundOver && !isWarmup) {
                        bombState = BombState.PLANTED
                        plantedBombX = ev.x
                        plantedBombY = ev.y
                        plantedBombZ = ev.z
                        plantedSiteLabel = ev.siteLabel.ifBlank { "A" }
                        bombCountdownSec = ev.countdownSec.coerceIn(1f, 35f)
                        bombBeepTimer = 0.45f
                        bombPlantProgress = 0f
                        playerHasBomb = false
                        for (b in bots) {
                            b.hasBomb = false
                            b.plantProgress = 0f
                            b.navPath = emptyList()
                            b.navPathIndex = 0
                            b.repathTimer = 0f
                        }
                        soundEngine.playBombPlanted()
                        bannerText = "БОМБА C4 ЗАЛОЖЕНА НА ТОЧКЕ $plantedSiteLabel!"
                        bannerTimer = 3.2f
                    } else if (bombState == BombState.PLANTED) {
                        // Keep countdown tightly aligned between T and CT
                        if (abs(bombCountdownSec - ev.countdownSec) > 0.9f) {
                            bombCountdownSec = ev.countdownSec.coerceIn(0.2f, 35f)
                        }
                    }
                }
                BombState.DEFUSED -> {
                    if (bombState == BombState.PLANTED && !isRoundOver) {
                        bombState = BombState.DEFUSED
                        bombPlantProgress = 0f
                        soundEngine.playBombPlanted()
                        endRound(TeamSide.CT, "БОМБА РАЗМИНИРОВАНА • ПОБЕДА СПЕЦНАЗА", delaySec = 3.6f)
                    }
                }
                BombState.EXPLODED -> {
                    if (bombState == BombState.PLANTED && !isRoundOver) {
                        detonatePlantedBomb(broadcast = false)
                    }
                }
                BombState.CARRIED -> Unit
            }
        }
    }

    private fun emitBombSyncEvent() {
        if (!matchConfig.isMultiplayer) return
        localBombSyncSeq++
        latestBombSync = BombSyncEvent(
            seq = localBombSyncSeq,
            state = bombState,
            x = plantedBombX,
            y = plantedBombY,
            z = plantedBombZ,
            siteLabel = plantedSiteLabel,
            countdownSec = bombCountdownSec,
            roundNumber = roundNumber,
            isWarmup = isWarmup,
            senderUid = matchConfig.localUserId
        )
    }

    private fun updateRemotePlayersInterpolation(dt: Float) {
        val nowMs = SystemClock.elapsedRealtime()
        val posAlpha = (dt * 32f).coerceAtMost(1f)
        val rotAlpha = (dt * 34f).coerceAtMost(1f)
        synchronized(remoteLock) {
            for (rp in remotePlayersMap.values) {
                // Tight 65ms dead-reckoning tuned for 50Hz TCP_NODELAY Realtime WebSocket broadcasts
                val leadSec = if (rp.isMoving && !rp.isDead) {
                    ((nowMs - rp.lastSyncMs) / 1000f).coerceIn(0f, 0.065f)
                } else 0f
                val predX = rp.targetX + rp.vx * leadSec
                val predY = rp.targetY + rp.vy * leadSec * 0.35f
                val predZ = rp.targetZ + rp.vz * leadSec

                rp.x += (predX - rp.x) * posAlpha
                rp.y += (predY - rp.y) * posAlpha
                rp.z += (predZ - rp.z) * posAlpha
                var diff = (rp.targetYawDeg - rp.yawDeg) % 360f
                if (diff > 180f) diff -= 360f
                if (diff < -180f) diff += 360f
                rp.yawDeg += diff * rotAlpha
                rp.pitchDeg += (rp.targetPitchDeg - rp.pitchDeg) * rotAlpha
                if (rp.isDead) {
                    rp.deathProgress = (rp.deathProgress + dt * 1.45f).coerceAtMost(1f)
                } else {
                    rp.deathProgress = 0f
                }
            }
        }
    }

    init {
        resetMatchInternal()
    }

    fun startNewMatch(config: MatchConfig) {
        matchConfig = config
        isPaused = false
        pendingReset = true
    }

    fun requestReset() {
        isPaused = false
        pendingReset = true
    }

    fun selectInitialTeam(side: TeamSide) {
        pendingTeamSelection = side
    }

    fun skipWarmup() {
        pendingSkipWarmup = true
    }

    fun spectateNextAlly() { pendingSpectateDelta += 1 }
    fun spectatePrevAlly() { pendingSpectateDelta -= 1 }

    fun selectSlot(slot: WeaponSlot) { pendingSlotSwitch = slot }
    fun quickSwitchWeapon() { pendingQuickSwitch = true }
    fun triggerWeaponInspect() { pendingInspect = true }
    fun buyWeapon(weapon: WeaponType) { pendingBuyWeapon = weapon }
    fun buyArmor() { pendingBuyArmor = true }
    fun buyDefuseKit() { pendingBuyKit = true }

    fun addLookDelta(dxPixels: Float, dyPixels: Float) {
        synchronized(lookLock) {
            pendingLookDx += dxPixels
            pendingLookDy += dyPixels
        }
    }

    fun triggerJump() { pendingJump = true }
    fun triggerReload() {
        if (!isEquipping) pendingReload = true
    }
    fun toggleAds() {
        if (!isEquipping && activeWeapon != WeaponType.KNIFE && activeWeapon != WeaponType.C4_BOMB) {
            isAdsToggle = !isAdsToggle
            if (isAdsToggle) inspectTimer = 0f
        }
    }
    fun toggleCrouch() { isCrouching = !isCrouching }

    fun update(rawDt: Float) {
        val dt = rawDt.coerceIn(0.001f, 0.05f)

        fpsFrameCount++
        fpsAccumTime += rawDt
        if (fpsAccumTime >= 0.45f) {
            currentFps = (fpsFrameCount / fpsAccumTime).toInt().coerceIn(1, 240)
            fpsFrameCount = 0
            fpsAccumTime = 0f
        }

        if (pendingReset) {
            pendingReset = false
            resetMatchInternal()
        }

        val chosenSide = pendingTeamSelection
        if (chosenSide != null) {
            pendingTeamSelection = null
            applyInitialTeamSelection(chosenSide)
        }

        if (isPaused) {
            publishHud()
            return
        }

        timeSec += dt
        if (bannerTimer > 0f) {
            bannerTimer -= dt
            if (bannerTimer <= 0f) bannerText = ""
        }
        if (killFeedTimer > 0f) {
            killFeedTimer -= dt
            if (killFeedTimer <= 0f) killFeedText = ""
        }
        var kfi = killFeedList.size - 1
        while (kfi >= 0) {
            val item = killFeedList[kfi]
            item.remainingSec -= dt
            if (item.remainingSec <= 0f) killFeedList.removeAt(kfi)
            kfi--
        }

        // Update active C4 explosion animation & camera shake!
        if (explosion.active) {
            explosion.elapsedSec += dt
            if (explosion.elapsedSec >= explosion.durationSec) {
                explosion.active = false
            }
        }
        cameraShake = (cameraShake - dt * 1.65f).coerceAtLeast(0f)
        explosionFlashAlpha = (explosionFlashAlpha - dt * 1.15f).coerceAtLeast(0f)

        // Tick active yellow bullet tracers (micro-flash ~55ms)
        var ti = bulletTracers.size - 1
        while (ti >= 0) {
            val tr = bulletTracers[ti]
            tr.lifeSec -= dt
            if (tr.lifeSec <= 0f) {
                bulletTracers.removeAt(ti)
            }
            ti--
        }

        if (isMatchFinished) {
            muzzleFlashTimer = (muzzleFlashTimer - dt * 22f).coerceAtLeast(0f)
            publishHud()
            return
        }

        drainPendingBotKicks()

        if (matchConfig.isMultiplayer) {
            drainIncomingMatchEvents()
            drainIncomingBombEvents()
            drainIncomingKillFeeds()
            if (isAuthoritativeForMatch()) {
                matchSyncHeartbeatTimer += dt
                if (matchSyncHeartbeatTimer >= 1.2f) {
                    matchSyncHeartbeatTimer = 0f
                    emitMatchSyncEvent(
                        winnerSide = if (isRoundOver) roundOverWinner.name else "",
                        reason = if (isRoundOver) roundOverReason else ""
                    )
                }
            }
        }

        // While the player is choosing CT or T at the start of the match, show live map overview & keep MP synced
        if (awaitingTeamSelection) {
            if (matchConfig.isMultiplayer) {
                updateRemotePlayersInterpolation(dt)
            }
            publishHud()
            return
        }

        if (isRoundOver) {
            roundOverTimer -= dt
            // Post-round free movement! Players can freely walk, jump, look, switch, inspect & celebrate-fire
            // after the round ends instead of freezing in place!
            if (equipTimer > 0f) equipTimer = (equipTimer - dt).coerceAtLeast(0f)
            if (pendingInspect) {
                pendingInspect = false
                if (!isPlayerDead && !isEquipping && !isReloading) {
                    isAdsToggle = false
                    inspectDuration = ViewmodelAnimator.inspectDurationSec(activeWeapon)
                    inspectTimer = inspectDuration
                }
            }
            if (inspectTimer > 0f) {
                if (isPlayerDead || isEquipping || isReloading || isAdsToggle) inspectTimer = 0f
                else inspectTimer = (inspectTimer - dt).coerceAtLeast(0f)
            }
            processWeaponSwitching()
            updatePlayerLookAndMovement(dt)
            updateWeaponsAndFiring(dt)

            // Keep dead bots falling smoothly during explosion / round transition!
            for (b in bots) {
                if (b.isDead) b.deathProgress = (b.deathProgress + dt * 2.2f).coerceAtMost(1f)
            }
            if (matchConfig.isMultiplayer) {
                updateRemotePlayersInterpolation(dt)
            }
            updateDeathFeedbackAndSpectator(dt)
            hitMarkerAlpha = (hitMarkerAlpha - dt * 3.5f).coerceAtLeast(0f)
            damageFlashAlpha = (damageFlashAlpha - dt * 2.4f).coerceAtLeast(0f)
            muzzleFlashTimer = (muzzleFlashTimer - dt * 22f).coerceAtLeast(0f)
            recoilKick = (recoilKick - dt * 5.6f).coerceAtLeast(0f)
            if (boltProgress > 0f) boltProgress = (boltProgress - dt * 1.35f).coerceAtLeast(0f)
            if (knifeSwingProgress > 0f) {
                knifeSwingProgress += dt * 3.8f
                if (knifeSwingProgress >= 1f) knifeSwingProgress = 0f
            }

            // Host advances the next round (or halftime side swap!) authoritatively; non-host waits up to 1.8s grace
            val canAdvanceRound = if (isAuthoritativeForMatch()) roundOverTimer <= 0f else roundOverTimer <= -1.8f
            if (canAdvanceRound) {
                if (ctScore >= matchConfig.roundsToWin || tScore >= matchConfig.roundsToWin) {
                    finishMatch()
                } else {
                    val halftimeRounds = (matchConfig.roundsToWin - 1).coerceAtLeast(1)
                    val shouldSwapNow = !hasSwappedSides &&
                        matchConfig.mode == GameMode.BOMB_DEFUSAL &&
                        (ctScore + tScore >= halftimeRounds)
                    roundNumber++
                    if (shouldSwapNow) {
                        performHalftimeSideSwap(broadcast = false)
                        startNewRound(keepPlayerGear = false, resetBotsEconomy = true, broadcast = true)
                    } else {
                        startNewRound(keepPlayerGear = !isPlayerDead, resetBotsEconomy = false, broadcast = true)
                    }
                }
            }
            publishHud()
            return
        }

        if (spawnProtectionTimer > 0f) {
            spawnProtectionTimer = (spawnProtectionTimer - dt).coerceAtLeast(0f)
        }

        if (matchConfig.isMultiplayer) {
            drainIncomingPvpHits()
        }

        if (freezeTimeSec > 0f) {
            val prevFreeze = freezeTimeSec
            freezeTimeSec = (freezeTimeSec - dt).coerceAtLeast(0f)
            if (prevFreeze > 0f && freezeTimeSec <= 0f) {
                bannerText = "РАУНД $roundNumber — В БОЙ!"
                bannerTimer = 1.9f
            }
        }

        if (buyTimeRemaining > 0f) {
            buyTimeRemaining = (buyTimeRemaining - dt).coerceAtLeast(0f)
        }
        if (equipTimer > 0f) {
            equipTimer = (equipTimer - dt).coerceAtLeast(0f)
        }
        if (pendingInspect) {
            pendingInspect = false
            if (!isPlayerDead && !isEquipping && !isReloading && bombPlantProgress <= 0.001f) {
                isAdsToggle = false
                inspectDuration = ViewmodelAnimator.inspectDurationSec(activeWeapon)
                inspectTimer = inspectDuration
            }
        }
        if (inspectTimer > 0f) {
            if (isPlayerDead || isEquipping || isReloading || isAdsToggle || bombPlantProgress > 0.001f) {
                inspectTimer = 0f
            } else {
                inspectTimer = (inspectTimer - dt).coerceAtLeast(0f)
            }
        }

        processShopRequests()
        processWeaponSwitching()
        updatePlayerLookAndMovement(dt)
        applyAdminAutoAim(dt)
        updateWeaponsAndFiring(dt)
        updateBombInteractions(dt)
        updateBots(dt)
        if (matchConfig.isMultiplayer) {
            updateRemotePlayersInterpolation(dt)
        }
        updateDeathFeedbackAndSpectator(dt)

        if (isWarmup) {
            updateWarmupPhase(dt)
        } else if (matchConfig.mode == GameMode.BOMB_DEFUSAL) {
            if (freezeTimeSec <= 0f) {
                updateBombAndRoundTimers(dt)
            }
        } else {
            updateTeamDeathmatchRules(dt)
        }

        hitMarkerAlpha = (hitMarkerAlpha - dt * 3.8f).coerceAtLeast(0f)
        damageFlashAlpha = (damageFlashAlpha - dt * 2.6f).coerceAtLeast(0f)
        muzzleFlashTimer = (muzzleFlashTimer - dt * 22f).coerceAtLeast(0f)
        recoilKick = (recoilKick - dt * 5.6f).coerceAtLeast(0f)
        if (boltProgress > 0f) boltProgress = (boltProgress - dt * 1.35f).coerceAtLeast(0f)
        if (knifeSwingProgress > 0f) {
            knifeSwingProgress += dt * 3.8f
            if (knifeSwingProgress >= 1f) knifeSwingProgress = 0f
        }

        publishHud()
    }

    private fun applyInitialTeamSelection(side: TeamSide) {
        matchConfig = matchConfig.copy(playerSide = side)
        awaitingTeamSelection = false
        onTeamChanged?.invoke(side)
        if (isWarmup || matchConfig.mode == GameMode.TEAM_DEATHMATCH) {
            money = 16000
            primaryWeapon = if (side == TeamSide.CT) WeaponType.M4A1_S else WeaponType.AK47
            pistolWeapon = if (side == TeamSide.CT) WeaponType.USP_S else WeaponType.GLOCK_18
            armor = 100f
            hasDefuseKit = side == TeamSide.CT
        } else {
            money = 800
            primaryWeapon = null
            pistolWeapon = if (side == TeamSide.CT) WeaponType.USP_S else WeaponType.GLOCK_18
            armor = 0f
            hasDefuseKit = false
        }
        bots.clear()
        lastTStrategy = -1
        lastCtStrategy = -1
        startNewRound(
            keepPlayerGear = isWarmup || matchConfig.mode == GameMode.TEAM_DEATHMATCH,
            resetBotsEconomy = true,
            broadcast = isAuthoritativeForMatch()
        )
    }

    private fun performHalftimeSideSwap(broadcast: Boolean) {
        if (hasSwappedSides) return
        hasSwappedSides = true
        val oldCt = ctScore
        ctScore = tScore
        tScore = oldCt

        val swappedSide = if (matchConfig.playerSide == TeamSide.CT) TeamSide.T else TeamSide.CT
        matchConfig = matchConfig.copy(playerSide = swappedSide)
        onTeamChanged?.invoke(swappedSide)

        // Reset second-half pistol round economy & gear cleanly
        money = 800
        primaryWeapon = null
        pistolWeapon = if (swappedSide == TeamSide.CT) WeaponType.USP_S else WeaponType.GLOCK_18
        armor = 0f
        hasDefuseKit = false
        playerHasBomb = false

        bots.clear()
        synchronized(remoteLock) {
            for (rp in remotePlayersMap.values) {
                rp.team = if (rp.team == TeamSide.CT) TeamSide.T else TeamSide.CT
            }
            pendingIncomingPvpHits.clear()
        }
        lastLethalSentTo.clear()
        damageDealtByTarget.clear()
        damageTakenByAttacker.clear()
        bannerText = "СМЕНА СТОРОН! ВЫ ТЕПЕРЬ ${swappedSide.shortRu} (CT $ctScore : $tScore T)"
        bannerTimer = 4.2f
        if (broadcast) emitMatchSyncEvent(reason = "СМЕНА СТОРОН!")
    }

    private fun resetMatchInternal() {
        if (world.mapId != matchConfig.map) {
            world = ArenaWorld(appContext, matchConfig.map)
        }
        ctScore = 0
        tScore = 0
        roundNumber = 1
        hasSwappedSides = false
        awaitingTeamSelection = true
        pendingTeamSelection = null
        score = 0
        kills = 0
        deaths = 0
        headshots = 0
        shotsFired = 0
        shotsHit = 0
        isMatchFinished = false
        isPlayerTeamWinner = false
        matchRecorded = false
        killFeedList.clear()
        killFeedText = ""
        killFeedTimer = 0f
        lastLethalSentTo.clear()
        // Start every match with a 60-second CS2 Warmup ($16000, open shop, instant respawn, Skip button!)
        isWarmup = true
        warmupTimer = 60f
        freezeTimeSec = 0f
        pendingSkipWarmup = false
        money = 16000
        primaryWeapon = if (matchConfig.playerSide == TeamSide.CT) WeaponType.M4A1_S else WeaponType.AK47
        pistolWeapon = if (matchConfig.playerSide == TeamSide.CT) WeaponType.USP_S else WeaponType.GLOCK_18
        armor = 100f
        hasDefuseKit = matchConfig.playerSide == TeamSide.CT
        bots.clear()
        lastTStrategy = -1
        lastCtStrategy = -1
        synchronized(remoteLock) {
            if (!matchConfig.isMultiplayer) {
                remotePlayersMap.clear()
            } else {
                for (rp in remotePlayersMap.values) {
                    rp.isDead = false
                    rp.deathProgress = 0f
                    rp.vx = 0f; rp.vy = 0f; rp.vz = 0f
                }
            }
            pendingIncomingPvpHits.clear()
        }
        synchronized(remoteBombLock) {
            pendingRemoteBombEvents.clear()
            lastAppliedBombSeqBySender.clear()
        }
        synchronized(remoteMatchLock) {
            pendingRemoteMatchEvents.clear()
            lastAppliedMatchSeqBySender.clear()
        }
        synchronized(killFeedSyncLock) {
            pendingOutgoingKillFeeds.clear()
            pendingIncomingKillFeeds.clear()
            lastKillFeedSeqBySender.clear()
            outgoingKillFeedSeq = 0
        }
        synchronized(kickBotLock) {
            pendingKickBotIds.clear()
        }
        if (!isLocalAdmin) {
            adminEspEnabled = false
            adminAutoAimEnabled = false
        }
        adminLockedOnTarget = false
        startNewRound(keepPlayerGear = true, resetBotsEconomy = true, broadcast = false)
    }

    private fun endWarmupAndStartLiveMatch(broadcast: Boolean = true) {
        isWarmup = false
        warmupTimer = 0f
        pendingSkipWarmup = false
        ctScore = 0
        tScore = 0
        roundNumber = 1
        hasSwappedSides = false
        score = 0
        kills = 0
        deaths = 0
        headshots = 0
        shotsFired = 0
        shotsHit = 0
        killFeedList.clear()
        killFeedText = ""
        lastLethalSentTo.clear()
        money = if (matchConfig.mode == GameMode.TEAM_DEATHMATCH) 16000 else 800
        primaryWeapon = if (matchConfig.mode == GameMode.TEAM_DEATHMATCH) {
            if (matchConfig.playerSide == TeamSide.CT) WeaponType.M4A1_S else WeaponType.AK47
        } else null
        pistolWeapon = if (matchConfig.playerSide == TeamSide.CT) WeaponType.USP_S else WeaponType.GLOCK_18
        armor = if (matchConfig.mode == GameMode.TEAM_DEATHMATCH) 100f else 0f
        hasDefuseKit = false
        soundEngine.playBombPlanted()
        bots.clear()
        lastTStrategy = -1
        lastCtStrategy = -1
        startNewRound(keepPlayerGear = false, resetBotsEconomy = true, broadcast = broadcast)
        if (broadcast) emitBombSyncEvent()
        bannerText = "МАТЧ НАЧАЛСЯ • ПОДГОТОВКА 8 СЕК • ${matchConfig.map.titleRu}"
        bannerTimer = 3.0f
    }

    private fun updateWarmupPhase(dt: Float) {
        warmupTimer = (warmupTimer - dt).coerceAtLeast(0f)
        if (pendingSkipWarmup || warmupTimer <= 0f) {
            endWarmupAndStartLiveMatch(broadcast = true)
            return
        }
        // Fast 2.2s player respawn during warmup with $16000 & full armor
        if (isPlayerDead) {
            tdmRespawnTimer -= dt
            if (tdmRespawnTimer <= 0f) {
                synchronized(remoteLock) { pendingIncomingPvpHits.clear() }
                lastLethalSentTo.clear()
                hp = 100f
                armor = 100f
                money = 16000
                isPlayerDead = false
                damageFlashAlpha = 0f
                spawnProtectionTimer = 1.25f
                deathBloodTimer = 0f
                deathRecapTimer = 0f
                activeDeathRecap = null
                isSpectatingAlly = false
                spectateCamInitialized = false
                damageDealtByTarget.clear()
                damageTakenByAttacker.clear()
                val (sx, sy, sz) = world.getSpawnPoint(matchConfig.playerSide, rng.nextInt(5))
                playerX = sx; playerY = sy; playerFloorY = sy; playerZ = sz
                playerYawRad = atan2(sx.toDouble(), sz.toDouble()).toFloat()
                playerPitchRad = 0f
                refillAmmoForOwnedWeapons()
                switchWeaponInternal(primaryWeapon ?: pistolWeapon)
            }
        }
    }

    private fun startNewRound(
        keepPlayerGear: Boolean,
        resetBotsEconomy: Boolean = false,
        broadcast: Boolean = false
    ) {
        isRoundOver = false
        roundOverTimer = 0f
        roundOverReason = ""
        roundTimeSec = if (matchConfig.mode == GameMode.BOMB_DEFUSAL) 105f else 300f
        // 8-second tactical Freeze Time preparation at the beginning of every Bomb Defusal round!
        freezeTimeSec = if (!isWarmup && matchConfig.mode == GameMode.BOMB_DEFUSAL) 8.0f else 0f
        buyTimeRemaining = if (isWarmup || matchConfig.mode == GameMode.TEAM_DEATHMATCH) 999f else 28f
        explosion.active = false
        explosionFlashAlpha = 0f
        cameraShake = 0f
        lastLethalSentTo.clear()

        bombState = BombState.CARRIED
        plantedSiteLabel = ""
        bombCountdownSec = 35f
        bombPlantProgress = 0f
        isBombActionHeld = false
        if (broadcast) emitMatchSyncEvent()

        // Flush any stale incoming PvP hits from the previous round BEFORE restoring full 100 HP!
        synchronized(remoteLock) {
            pendingIncomingPvpHits.clear()
        }
        bulletImpacts.clear()
        bulletTracers.clear()

        hp = 100f
        isPlayerDead = false
        damageFlashAlpha = 0f
        spawnProtectionTimer = 1.25f
        tdmRespawnTimer = 0f
        deathBloodTimer = 0f
        deathRecapTimer = 0f
        activeDeathRecap = null
        deathCamDelayTimer = 0f
        isSpectatingAlly = false
        spectateCamInitialized = false
        damageDealtByTarget.clear()
        damageTakenByAttacker.clear()
        if (!isWarmup && !keepPlayerGear && matchConfig.mode == GameMode.BOMB_DEFUSAL) {
            primaryWeapon = null
            pistolWeapon = if (matchConfig.playerSide == TeamSide.CT) WeaponType.USP_S else WeaponType.GLOCK_18
            armor = 0f
            hasDefuseKit = false
        }

        refillAmmoForOwnedWeapons()
        val enemySide = if (matchConfig.playerSide == TeamSide.T) TeamSide.CT else TeamSide.T
        val remoteAllies = if (matchConfig.isMultiplayer) {
            synchronized(remoteLock) { remotePlayersMap.values.count { it.team == matchConfig.playerSide } }
        } else 0
        val remoteEnemies = if (matchConfig.isMultiplayer) {
            synchronized(remoteLock) { remotePlayersMap.values.count { it.team == enemySide } }
        } else 0
        val allowAnyBots = !matchConfig.isMultiplayer || matchConfig.allowBots
        val allyBotsCount = if (allowAnyBots) (matchConfig.playerTeamSize - 1 - remoteAllies).coerceIn(0, 4) else 0
        val enemyBotsCount = if (allowAnyBots) (matchConfig.enemyTeamSize - remoteEnemies).coerceIn(0, 5) else 0

        // When playing Bomb Defusal with T bots, assign C4 to a T bot so bots actively plant on A/B;
        // if playing solo T (no ally bots), player carries C4.
        val giveBombToAllyBot = (!isWarmup && matchConfig.mode == GameMode.BOMB_DEFUSAL &&
            matchConfig.playerSide == TeamSide.T && allyBotsCount > 0)
        val giveBombToEnemyBot = (!isWarmup && matchConfig.mode == GameMode.BOMB_DEFUSAL &&
            enemySide == TeamSide.T && enemyBotsCount > 0 && remoteEnemies == 0)
        playerHasBomb = (!isWarmup && matchConfig.mode == GameMode.BOMB_DEFUSAL &&
            matchConfig.playerSide == TeamSide.T && !giveBombToAllyBot)

        activeWeapon = primaryWeapon ?: pistolWeapon
        lastWeapon = WeaponType.KNIFE
        equipDuration = activeWeapon.equipDurationSec
        equipTimer = activeWeapon.equipDurationSec
        isReloading = false
        reloadTimer = 0f
        isAdsToggle = false
        adsBlend = 0f
        isCrouching = false
        recoilKick = 0f
        muzzleFlashTimer = 0f
        fireTimer = 0f
        weaponSwayX = 0f
        weaponSwayY = 0f
        strafeRoll = 0f
        landingDip = 0f
        landingVel = 0f
        wasGrounded = true

        // Spawn Player on real spawn coordinates facing map center
        val (sx, sy, sz) = world.getSpawnPoint(matchConfig.playerSide, 0)
        playerX = sx
        playerY = sy
        playerFloorY = sy
        playerZ = sz
        playerVelY = 0f
        playerPitchRad = 0f
        playerYawRad = atan2(sx.toDouble(), sz.toDouble()).toFloat()

        // Pick varied per-round team strategies (guaranteed different from the previous round!)
        val tStrategy = pickDistinctStrategy(lastTStrategy, 5) // 0=RushA, 1=RushB, 2=SplitA, 3=SplitB, 4=SlowDefault
        lastTStrategy = tStrategy
        val ctStrategy = pickDistinctStrategy(lastCtStrategy, 4) // 0=2-1-2, 1=StackA, 2=StackB, 3=MidPush
        lastCtStrategy = ctStrategy

        if (!allowAnyBots) {
            bots.clear()
        } else {
            val prevAllies = if (resetBotsEconomy) emptyList() else bots.filter { it.team == matchConfig.playerSide }
            val prevEnemies = if (resetBotsEconomy) emptyList() else bots.filter { it.team == enemySide }
            val updatedBots = ArrayList<BotOperative>(allyBotsCount + enemyBotsCount)

            val allyBombCarrierIdx = if (giveBombToAllyBot) rng.nextInt(allyBotsCount) else -1
            val enemyBombCarrierIdx = if (giveBombToEnemyBot) rng.nextInt(enemyBotsCount) else -1

            var allyAwpCount = 0
            for (i in 0 until allyBotsCount) {
                val existing = prevAllies.getOrNull(i)
                val bot = prepareBotForRound(
                    existing = existing,
                    side = matchConfig.playerSide,
                    slotIdx = i,
                    spawnIdx = i + 1,
                    assignBomb = (i == allyBombCarrierIdx),
                    teamStrategy = if (matchConfig.playerSide == TeamSide.T) tStrategy else ctStrategy,
                    teamHasAwp = allyAwpCount > 0
                )
                if (bot.weapon == WeaponType.AWP) allyAwpCount++
                updatedBots.add(bot)
            }

            var enemyAwpCount = 0
            for (i in 0 until enemyBotsCount) {
                val existing = prevEnemies.getOrNull(i)
                val bot = prepareBotForRound(
                    existing = existing,
                    side = enemySide,
                    slotIdx = i,
                    spawnIdx = i,
                    assignBomb = (i == enemyBombCarrierIdx),
                    teamStrategy = if (enemySide == TeamSide.T) tStrategy else ctStrategy,
                    teamHasAwp = enemyAwpCount > 0
                )
                if (bot.weapon == WeaponType.AWP) enemyAwpCount++
                updatedBots.add(bot)
            }

            bots.clear()
            bots.addAll(updatedBots)
        }

        bannerText = when {
            isWarmup -> "РАЗМИНКА • $16 000 • СВОБОДНАЯ ПОКУПКА ОРУЖИЯ"
            matchConfig.mode == GameMode.BOMB_DEFUSAL -> "РАУНД $roundNumber • ${matchConfig.map.titleRu} • ${matchConfig.playerSide.shortRu}"
            else -> "БОЙ НАСМЕРТЬ • ${matchConfig.map.titleRu}"
        }
        bannerTimer = 2.8f
        publishHud()
    }

    private fun pickDistinctStrategy(prev: Int, count: Int): Int {
        if (count <= 1) return 0
        var next = rng.nextInt(count)
        if (next == prev) next = (next + 1 + rng.nextInt(count - 1)) % count
        return next
    }

    private fun prepareBotForRound(
        existing: BotOperative?,
        side: TeamSide,
        slotIdx: Int,
        spawnIdx: Int,
        assignBomb: Boolean,
        teamStrategy: Int,
        teamHasAwp: Boolean
    ): BotOperative {
        val (sx, sy, sz) = world.getSpawnPoint(side, spawnIdx)
        val yaw = Math.toDegrees(atan2(-sx.toDouble(), -sz.toDouble())).toFloat()
        val survivedPrev = existing != null && !existing.isDead && !isWarmup && matchConfig.mode == GameMode.BOMB_DEFUSAL

        val bot = existing ?: BotOperative(
            id = nextBotId++,
            name = botCallsign(side, slotIdx),
            team = side,
            weapon = if (side == TeamSide.CT) WeaponType.USP_S else WeaponType.GLOCK_18,
            money = if (isWarmup || matchConfig.mode == GameMode.TEAM_DEATHMATCH) 16000 else 800,
            armor = 0f,
            x = sx,
            y = sy,
            z = sz,
            yawDeg = yaw,
            visualYawDeg = yaw,
            aimYawDeg = yaw,
            alertYawDeg = yaw,
            aimSwaySeed = rng.nextFloat() * 100f
        )

        // Reset round spatial & combat state while keeping persistent match economy + K/D!
        bot.x = sx; bot.y = sy; bot.z = sz
        bot.yawDeg = yaw; bot.visualYawDeg = yaw; bot.aimYawDeg = yaw; bot.alertYawDeg = yaw
        bot.hp = 100f; bot.maxHp = 100f
        bot.isDead = false
        bot.deathProgress = 0f
        bot.hasBomb = assignBomb
        bot.hasLineOfSight = false
        bot.hitFlinch = 0f
        bot.plantProgress = 0f
        bot.defuseProgress = 0f
        bot.waypointIndex = 0
        bot.navPath = emptyList()
        bot.navPathIndex = 0
        bot.repathTimer = rng.nextFloat() * 0.30f
        bot.stuckTimer = 0f
        bot.lastProgressX = sx
        bot.lastProgressZ = sz
        bot.unstuckTimer = 0f
        bot.unstuckDir = if (rng.nextBoolean()) 1f else -1f
        bot.fireCooldown = 0.25f + rng.nextFloat() * 0.25f
        bot.targetSightTime = 0f
        bot.losLostGraceTimer = 0f
        bot.lastTargetKey = -1
        bot.alertTimer = 0f
        bot.reactionDelaySec = 0.30f + rng.nextFloat() * 0.12f
        bot.burstShotsLeft = 2 + rng.nextInt(3)
        bot.burstPauseTimer = 0f

        // Real CS2 bot economy & weapon purchasing decisions
        buyBotLoadout(bot, survivedPrev = survivedPrev, teamHasAwp = teamHasAwp)

        // Assign diverse tactical route & hold timing based on this round's team strategy
        assignBotRoundTactic(bot, slotIdx = slotIdx, teamStrategy = teamStrategy)
        return bot
    }

    private fun buyBotLoadout(bot: BotOperative, survivedPrev: Boolean, teamHasAwp: Boolean) {
        val starterPistol = if (bot.team == TeamSide.CT) WeaponType.USP_S else WeaponType.GLOCK_18
        if (isWarmup || matchConfig.mode == GameMode.TEAM_DEATHMATCH) {
            bot.money = 16000
            bot.armor = 100f
            bot.weapon = when (rng.nextInt(6)) {
                0 -> if (bot.team == TeamSide.CT) WeaponType.M4A1_S else WeaponType.AK47
                1 -> if (bot.team == TeamSide.CT) WeaponType.M4A1_S else WeaponType.AK47
                2 -> WeaponType.P90
                3 -> WeaponType.AWP
                4 -> WeaponType.MP9
                else -> WeaponType.DESERT_EAGLE
            }
            return
        }

        // If bot died last round, reset weapon & armor before buying
        if (!survivedPrev) {
            bot.weapon = starterPistol
            bot.armor = 0f
        }

        val hasPrimary = bot.weapon.slot == WeaponSlot.PRIMARY

        // Round 1: Classic CS2 Pistol Round ($800 budget)
        if (roundNumber == 1) {
            when {
                bot.money >= 700 && rng.nextFloat() < 0.34f -> {
                    bot.money -= WeaponType.DESERT_EAGLE.price
                    bot.weapon = WeaponType.DESERT_EAGLE
                }
                bot.money >= 650 -> {
                    bot.money -= 650
                    bot.armor = 100f
                }
            }
            return
        }

        // If bot survived with a primary gun, just refresh armor if low & affordable
        if (hasPrimary) {
            if (bot.armor < 65f && bot.money >= 1000) {
                bot.money -= 1000
                bot.armor = 100f
            }
            return
        }

        val rifle = if (bot.team == TeamSide.CT) WeaponType.M4A1_S else WeaponType.AK47
        when {
            // AWP sniper buy (max 1 per team so matches stay varied & realistic)
            !teamHasAwp && bot.money >= WeaponType.AWP.price + 650 && rng.nextFloat() < 0.24f -> {
                bot.money -= WeaponType.AWP.price
                bot.weapon = WeaponType.AWP
                if (bot.money >= 1000) { bot.money -= 1000; bot.armor = 100f }
            }
            // Full rifle + armor buy
            bot.money >= rifle.price + 650 -> {
                bot.money -= rifle.price
                bot.weapon = rifle
                val armorCost = if (bot.money >= 1000) 1000 else 650
                bot.money -= armorCost
                bot.armor = 100f
            }
            // Glass-cannon rifle or P90 buy
            bot.money >= rifle.price && rng.nextFloat() < 0.55f -> {
                bot.money -= rifle.price
                bot.weapon = rifle
            }
            bot.money >= WeaponType.P90.price + 650 -> {
                bot.money -= WeaponType.P90.price
                bot.weapon = WeaponType.P90
                bot.money -= 650
                bot.armor = 100f
            }
            // Force-buy SMG (MP9 / P90) + Kevlar
            bot.money >= WeaponType.MP9.price + 650 && rng.nextFloat() < 0.72f -> {
                bot.money -= WeaponType.MP9.price
                bot.weapon = WeaponType.MP9
                bot.money -= 650
                bot.armor = 100f
            }
            // Eco / Deagle force buy
            bot.money >= 1350 && rng.nextFloat() < 0.48f -> {
                bot.money -= WeaponType.DESERT_EAGLE.price
                bot.weapon = WeaponType.DESERT_EAGLE
                if (bot.money >= 650 && rng.nextFloat() < 0.5f) {
                    bot.money -= 650
                    bot.armor = 100f
                }
            }
            // Full Eco save
            else -> {
                bot.weapon = starterPistol
            }
        }
    }

    private fun assignBotRoundTactic(bot: BotOperative, slotIdx: Int, teamStrategy: Int) {
        val routes = if (bot.team == TeamSide.T) world.tAttackRoutes else world.ctDefenseRoutes
        val maxSite = world.bombsites.lastIndex.coerceAtLeast(0)

        if (bot.team == TeamSide.T) {
            // 0=RushA, 1=RushB, 2=SplitA, 3=SplitB, 4=SlowDefault
            val mainSite = when (teamStrategy) {
                0, 2 -> 0
                1, 3 -> minOf(1, maxSite)
                else -> rng.nextInt(world.bombsites.size.coerceAtLeast(1))
            }
            val lurkSite = (1 - mainSite).coerceIn(0, maxSite)
            val isLurk = !bot.hasBomb && slotIdx == 3 && teamStrategy != 0 && teamStrategy != 1
            val targetSite = if (isLurk) lurkSite else mainSite

            val matchingRoutes = world.tRouteBombsiteIdx.indices.filter {
                world.tRouteBombsiteIdx[it] == targetSite
            }
            val routeIdx = when {
                (teamStrategy == 2 || teamStrategy == 3) && matchingRoutes.size >= 2 ->
                    matchingRoutes[slotIdx % matchingRoutes.size]
                matchingRoutes.isNotEmpty() ->
                    matchingRoutes[rng.nextInt(matchingRoutes.size)]
                else -> rng.nextInt(routes.size)
            }
            bot.targetSiteIndex = routeIdx
            bot.targetBombsiteIdx = world.tRouteBombsiteIdx.getOrElse(routeIdx) { targetSite }.coerceIn(0, maxSite)
            bot.tacticRole = teamStrategy
            bot.holdTimer = when (teamStrategy) {
                0, 1 -> if (slotIdx == 0) 0f else rng.nextFloat() * 0.7f // Fast coordinated rush
                2, 3 -> 0.8f + rng.nextFloat() * 2.2f                    // Timed split execute
                else -> 2.8f + rng.nextFloat() * 4.2f                    // Slow default map control
            }
        } else {
            // CT Strategies: 0=Balanced 2-1-2, 1=Stack A, 2=Stack B, 3=Aggressive Mid Push
            val routeIdx = when (teamStrategy) {
                1 -> if (slotIdx < 3) (slotIdx % minOf(2, routes.size)) else (routes.lastIndex - (slotIdx % 2)).coerceIn(0, routes.lastIndex)
                2 -> if (slotIdx < 3) (routes.lastIndex - (slotIdx % minOf(2, routes.size))).coerceIn(0, routes.lastIndex) else (slotIdx % routes.size)
                3 -> if (slotIdx < 2) (routes.size / 2).coerceIn(0, routes.lastIndex) else (slotIdx % routes.size)
                else -> (slotIdx + roundNumber) % routes.size
            }
            bot.targetSiteIndex = routeIdx
            bot.targetBombsiteIdx = if (routeIdx < routes.size / 2) 0 else minOf(1, maxSite)
            bot.tacticRole = teamStrategy
            bot.holdTimer = when (teamStrategy) {
                3 -> if (slotIdx < 2) 0f else 2.0f + rng.nextFloat() * 3.0f
                else -> 1.5f + rng.nextFloat() * 4.5f
            }
        }
    }

    private fun botCallsign(side: TeamSide, slotIdx: Int): String {
        val tNames = listOf("БОТ Кобра", "БОТ Шакал", "БОТ Вихрь", "БОТ Фантом", "БОТ Вулкан")
        val ctNames = listOf("БОТ Сокол", "БОТ Беркут", "БОТ Вектор", "БОТ Гром", "БОТ Щит")
        val pool = if (side == TeamSide.T) tNames else ctNames
        return pool[slotIdx % pool.size]
    }

    private fun refillAmmoForOwnedWeapons() {
        for (w in WeaponType.entries) {
            if (w.magSize > 0) {
                magAmmoMap[w] = w.magSize
                reserveAmmoMap[w] = w.initialReserve
            }
        }
    }

    private fun canPlayerBuyNow(): Boolean {
        if (isPlayerDead || isRoundOver) return false
        if (isWarmup || matchConfig.mode == GameMode.TEAM_DEATHMATCH) return true
        return buyTimeRemaining > 0f
    }

    private fun processShopRequests() {
        val wantWeapon = pendingBuyWeapon
        if (wantWeapon != null) {
            pendingBuyWeapon = null
            if (canPlayerBuyNow() && wantWeapon.category != null) {
                val cost = if (isWarmup || matchConfig.mode == GameMode.TEAM_DEATHMATCH) 0 else wantWeapon.price
                if (money >= cost) {
                    money -= cost
                    when (wantWeapon.slot) {
                        WeaponSlot.PRIMARY -> primaryWeapon = wantWeapon
                        WeaponSlot.PISTOL -> pistolWeapon = wantWeapon
                        else -> Unit
                    }
                    magAmmoMap[wantWeapon] = wantWeapon.magSize
                    reserveAmmoMap[wantWeapon] = wantWeapon.initialReserve
                    switchWeaponInternal(wantWeapon)
                    soundEngine.playReload()
                }
            }
        }
        if (pendingBuyArmor) {
            pendingBuyArmor = false
            val cost = if (isWarmup || matchConfig.mode == GameMode.TEAM_DEATHMATCH) 0 else 1000
            if (canPlayerBuyNow() && armor < 100f && money >= cost) {
                money -= cost
                armor = 100f
                soundEngine.playReload()
            }
        }
        if (pendingBuyKit) {
            pendingBuyKit = false
            val cost = 400
            if (canPlayerBuyNow() && matchConfig.playerSide == TeamSide.CT && !hasDefuseKit && money >= cost) {
                money -= cost
                hasDefuseKit = true
                soundEngine.playReload()
            }
        }
    }

    private fun processWeaponSwitching() {
        val reqSlot = pendingSlotSwitch
        if (reqSlot != null) {
            pendingSlotSwitch = null
            val target = when (reqSlot) {
                WeaponSlot.KNIFE -> WeaponType.KNIFE
                WeaponSlot.PISTOL -> pistolWeapon
                WeaponSlot.PRIMARY -> primaryWeapon
                WeaponSlot.BOMB -> if (matchConfig.mode == GameMode.BOMB_DEFUSAL && playerHasBomb) WeaponType.C4_BOMB else null
            }
            if (target != null && target != activeWeapon) {
                switchWeaponInternal(target)
            }
        }
        if (pendingQuickSwitch) {
            pendingQuickSwitch = false
            val candidate = when {
                lastWeapon != activeWeapon && isWeaponAvailable(lastWeapon) -> lastWeapon
                activeWeapon != WeaponType.KNIFE -> WeaponType.KNIFE
                primaryWeapon != null -> primaryWeapon!!
                else -> pistolWeapon
            }
            if (candidate != activeWeapon) switchWeaponInternal(candidate)
        }
    }

    private fun isWeaponAvailable(w: WeaponType): Boolean = when (w.slot) {
        WeaponSlot.KNIFE -> true
        WeaponSlot.PISTOL -> w == pistolWeapon
        WeaponSlot.PRIMARY -> w == primaryWeapon
        WeaponSlot.BOMB -> matchConfig.mode == GameMode.BOMB_DEFUSAL && playerHasBomb
    }

    private fun switchWeaponInternal(next: WeaponType) {
        lastWeapon = activeWeapon
        activeWeapon = next
        equipDuration = next.equipDurationSec
        equipTimer = next.equipDurationSec
        inspectTimer = 0f
        pendingInspect = false
        isReloading = false
        reloadTimer = 0f
        pendingReload = false
        isAdsToggle = false
        boltProgress = 0f
        knifeSwingProgress = 0f
        fireTimer = 0f
        soundEngine.playWeaponSwitch()
    }

    private fun updatePlayerLookAndMovement(dt: Float) {
        if (isPlayerDead) return

        var dx: Float
        var dy: Float
        synchronized(lookLock) {
            dx = pendingLookDx; dy = pendingLookDy
            pendingLookDx = 0f; pendingLookDy = 0f
        }

        // Pure raw skill aiming — zero aim assist!
        val sens = settings.sensitivity
        val adsMult = if (isAdsToggle) sens.adsSensitivity else 1.0f
        val sniperExtra = if (activeWeapon.hasSniperScope && isAdsToggle) 0.42f else 1.0f
        val baseRadPerPx = 0.0031f * sens.lookSensitivity * adsMult * sniperExtra
        val dYaw = -dx * baseRadPerPx * sens.xAxisMultiplier
        val pitchSign = if (sens.invertY) 1f else -1f
        val dPitch = pitchSign * dy * baseRadPerPx * sens.yAxisMultiplier
        playerYawRad += dYaw
        playerPitchRad = (playerPitchRad + dPitch).coerceIn(-1.28f, 1.28f)

        // Smooth viewmodel camera-turn inertia sway!
        val swayScale = 1f - adsBlend * 0.72f
        val targetSwayX = (-dYaw * 0.42f * swayScale).coerceIn(-0.042f, 0.042f)
        val targetSwayY = (-dPitch * 0.34f * swayScale).coerceIn(-0.032f, 0.032f)
        val swayAlpha = (dt * 11.5f).coerceAtMost(1f)
        weaponSwayX += (targetSwayX - weaponSwayX) * swayAlpha
        weaponSwayY += (targetSwayY - weaponSwayY) * swayAlpha

        val fwdX = -sin(playerYawRad)
        val fwdZ = -cos(playerYawRad)
        val rightX = cos(playerYawRad)
        val rightZ = -sin(playerYawRad)

        val baseSpeed = when {
            isCrouching -> 2.8f
            isAdsToggle -> 3.4f
            else -> 5.7f
        } * activeWeapon.moveSpeedMult

        val frozen = freezeTimeSec > 0f
        val mx = if (frozen) 0f else moveInputX.coerceIn(-1f, 1f)
        val my = if (frozen) 0f else moveInputY.coerceIn(-1f, 1f)
        val mag = sqrt(mx * mx + my * my)
        val nx = if (mag > 1f) mx / mag else mx
        val ny = if (mag > 1f) my / mag else my

        // Smooth strafe banking roll for natural CS2 movement feel
        val targetStrafeRoll = (-nx * 2.8f * (1f - adsBlend * 0.75f))
        strafeRoll += (targetStrafeRoll - strafeRoll) * (dt * 10f).coerceAtMost(1f)

        val targetX = playerX + (fwdX * ny + rightX * nx) * baseSpeed * dt
        val targetZ = playerZ + (fwdZ * ny + rightZ * nx) * baseSpeed * dt
        val (resolvedX, floorY, resolvedZ) = world.resolveMovement(
            curX = playerX,
            curY = playerFloorY,
            curZ = playerZ,
            targetX = targetX,
            targetZ = targetZ,
            radius = 0.36f,
            actualFeetY = playerY
        )
        playerX = resolvedX
        playerZ = resolvedZ
        playerFloorY = floorY

        if (pendingJump) {
            pendingJump = false
            if (!frozen && playerY <= playerFloorY + 0.08f) {
                playerVelY = 5.80f // Crisp ~1.16m CS jump apex so climbable crates are easy to jump onto!
                isCrouching = false
                landingVel -= 0.16f // subtle upward jump kick
            }
        }
        val prevVelY = playerVelY
        playerVelY -= 14.5f * dt
        playerY += playerVelY * dt
        val groundedNow = playerY <= playerFloorY + 0.002f
        if (groundedNow) {
            if (!wasGrounded && prevVelY < -1.8f) {
                // Crisp damped spring dip when landing from a jump or drop!
                landingVel += (-prevVelY * 0.055f).coerceAtMost(0.45f)
            }
            playerY = playerFloorY
            playerVelY = 0f
        }
        wasGrounded = groundedNow

        // Critically-damped spring integration for landing dip
        val springAcc = -landingDip * 95f - landingVel * 14f
        landingVel += springAcc * dt
        landingDip = (landingDip + landingVel * dt).coerceIn(-0.045f, 0.065f)

        val targetEye = if (isCrouching) 1.14f else 1.60f
        eyeHeight += (targetEye - eyeHeight) * (dt * 11f).coerceAtMost(1f)

        moveSpeedRatio = mag.coerceIn(0f, 1f)
        if (moveSpeedRatio > 0.08f && playerY <= playerFloorY + 0.08f) {
            walkBobPhase += dt * (4.4f + moveSpeedRatio * 5.2f)
        }

        val targetAds = if (isAdsToggle && !isReloading && !isEquipping && activeWeapon != WeaponType.KNIFE && activeWeapon != WeaponType.C4_BOMB) 1f else 0f
        adsBlend += (targetAds - adsBlend) * (dt * 13f).coerceAtMost(1f)
    }

    private fun updateWeaponsAndFiring(dt: Float) {
        if (isPlayerDead || freezeTimeSec > 0f) {
            lastTriggerPressed = isFiring
            return
        }

        if (fireTimer > 0f) fireTimer -= dt

        // STRICT CS2 FIRING LOCKOUT: while weapon draw/switch animation plays, firing/reloading/knifing are blocked!
        if (isEquipping) {
            pendingReload = false
            lastTriggerPressed = isFiring
            return
        }

        val w = activeWeapon
        if (pendingReload) {
            pendingReload = false
            beginReload()
        }

        if (isReloading) {
            reloadTimer += dt
            if (reloadTimer >= w.reloadSec) {
                val curMag = magAmmoMap[w] ?: 0
                val curRes = reserveAmmoMap[w] ?: 0
                val need = (w.magSize - curMag).coerceAtLeast(0)
                val take = minOf(need, curRes)
                magAmmoMap[w] = curMag + take
                reserveAmmoMap[w] = curRes - take
                isReloading = false
                reloadTimer = 0f
            }
        }

        val triggerJustPressed = isFiring && !lastTriggerPressed
        val wantsFire = if (w.isAutomatic) isFiring else triggerJustPressed

        if (wantsFire && fireTimer <= 0f && !isReloading) {
            inspectTimer = 0f
            when (w) {
                WeaponType.KNIFE -> performKnifeSlash()
                WeaponType.C4_BOMB -> Unit
                else -> {
                    val curMag = magAmmoMap[w] ?: 0
                    if (curMag > 0) {
                        firePlayerWeapon()
                    } else if ((reserveAmmoMap[w] ?: 0) > 0) {
                        beginReload()
                    }
                }
            }
        }
        lastTriggerPressed = isFiring
    }

    private fun updateBombInteractions(dt: Float) {
        if (isWarmup || freezeTimeSec > 0f || matchConfig.mode != GameMode.BOMB_DEFUSAL || isPlayerDead) {
            bombPlantProgress = 0f
            return
        }

        val site = world.bombsites.firstOrNull { it.contains(playerX, playerZ) }
        val wantsPlant = matchConfig.playerSide == TeamSide.T &&
            playerHasBomb &&
            bombState == BombState.CARRIED &&
            site != null &&
            (isBombActionHeld || (activeWeapon == WeaponType.C4_BOMB && isFiring))

        if (wantsPlant && site != null) {
            if (activeWeapon != WeaponType.C4_BOMB) switchWeaponInternal(WeaponType.C4_BOMB)
            bombPlantProgress = (bombPlantProgress + dt / 3.0f).coerceAtMost(1f)
            plantBeepTimer -= dt
            if (plantBeepTimer <= 0f) {
                soundEngine.playBombBeep()
                plantBeepTimer = 0.55f
            }
            if (bombPlantProgress >= 1f) {
                plantBombAt(playerX, playerFloorY, playerZ, site.label, plantedByPlayer = true)
            }
            return
        }

        val distToBomb = world.distance2D(playerX, playerZ, plantedBombX, plantedBombZ)
        val wantsDefuse = matchConfig.playerSide == TeamSide.CT &&
            bombState == BombState.PLANTED &&
            distToBomb <= 3.6f &&
            abs(playerY - plantedBombY) <= 2.4f &&
            isBombActionHeld

        if (wantsDefuse) {
            val defuseTime = if (hasDefuseKit) 2.8f else 5.0f
            bombPlantProgress = (bombPlantProgress + dt / defuseTime).coerceAtMost(1f)
            plantBeepTimer -= dt
            if (plantBeepTimer <= 0f) {
                soundEngine.playBombBeep()
                plantBeepTimer = 0.65f
            }
            if (bombPlantProgress >= 1f) {
                bombState = BombState.DEFUSED
                bombPlantProgress = 0f
                money = (money + 300).coerceAtMost(16000)
                score += 400
                emitBombSyncEvent()
                soundEngine.playBombPlanted()
                endRound(TeamSide.CT, "БОМБА РАЗМИНИРОВАНА • ПОБЕДА СПЕЦНАЗА", delaySec = 3.6f)
            }
            return
        }

        bombPlantProgress = (bombPlantProgress - dt * 3f).coerceAtLeast(0f)
    }

    private fun plantBombAt(x: Float, y: Float, z: Float, siteLabel: String, plantedByPlayer: Boolean) {
        bombState = BombState.PLANTED
        plantedBombX = x
        plantedBombY = y
        plantedBombZ = z
        plantedSiteLabel = siteLabel
        bombCountdownSec = 35f
        bombBeepTimer = 0.8f
        bombPlantProgress = 0f
        playerHasBomb = false
        for (b in bots) {
            b.hasBomb = false
            b.plantProgress = 0f
            // Force all CT & T bots to immediately recompute A* routes toward/around the planted C4!
            b.navPath = emptyList()
            b.navPathIndex = 0
            b.repathTimer = 0f
        }
        if (plantedByPlayer) {
            money = (money + 300).coerceAtMost(16000)
            score += 350
            switchWeaponInternal(primaryWeapon ?: pistolWeapon)
        }
        emitBombSyncEvent()
        soundEngine.playBombPlanted()
        bannerText = "БОМБА C4 ЗАЛОЖЕНА НА ТОЧКЕ $siteLabel!"
        bannerTimer = 3.0f
    }

    /**
     * FULL CS2 C4 DETONATION:
     * Triggers massive 3D fireball + shockwave + smoke + camera shake + blinding flash + LETHAL 26m blast radius!
     */
    private fun detonatePlantedBomb(broadcast: Boolean = true) {
        bombState = BombState.EXPLODED
        if (broadcast) emitBombSyncEvent()
        explosion.active = true
        explosion.x = plantedBombX
        explosion.y = plantedBombY
        explosion.z = plantedBombZ
        explosion.elapsedSec = 0f
        explosion.durationSec = 4.4f

        soundEngine.playBombExplode()
        if (settings.audio.hapticsEnabled) onHapticPulse(true)

        val blastRadius = 26.0f
        var blastKills = 0

        // 1. Blast damage / kills against ALL alive bots (both CT & T!)
        for (b in bots) {
            if (b.isDead) continue
            val dx = b.x - plantedBombX
            val dy = (b.y + 1.0f) - (plantedBombY + 0.4f)
            val dz = b.z - plantedBombZ
            val dist = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.1f)
            if (dist <= blastRadius) {
                val wallDist = world.raycastWalls(
                    plantedBombX, plantedBombY + 0.5f, plantedBombZ,
                    dx / dist, dy / dist, dz / dist, dist
                )
                val coverMult = if (wallDist < dist - 0.8f) 0.58f else 1.0f
                val dmg = (540f * exp(-((dist / 11.5f) * (dist / 11.5f))) * coverMult)
                if (dmg >= 4f) {
                    b.hp -= dmg
                    b.hitFlinch = 1.0f
                    if (b.hp <= 0f) {
                        b.hp = 0f
                        b.isDead = true
                        b.deathProgress = 0.05f
                        blastKills++
                        pushKillFeed(
                            killerName = "Бомба C4",
                            killerTeam = TeamSide.T,
                            victimName = b.name,
                            victimTeam = b.team,
                            weaponLabel = "C4"
                        )
                    }
                }
            }
        }

        // 2. Blast damage / kill + camera shake + blinding flash for the Player!
        val pdx = playerX - plantedBombX
        val pdy = (playerY + 1.0f) - (plantedBombY + 0.4f)
        val pdz = playerZ - plantedBombZ
        val pDist = sqrt(pdx * pdx + pdy * pdy + pdz * pdz).coerceAtLeast(0.1f)

        val shakeFactor = (1f - (pDist / 48f)).coerceIn(0.28f, 1.0f)
        cameraShake = 1.0f * shakeFactor
        explosionFlashAlpha = (1.0f - (pDist / 38f)).coerceIn(0.25f, 1.0f)

        if (!isPlayerDead && pDist <= blastRadius) {
            val wallDist = world.raycastWalls(
                plantedBombX, plantedBombY + 0.5f, plantedBombZ,
                pdx / pDist, pdy / pDist, pdz / pDist, pDist
            )
            val coverMult = if (wallDist < pDist - 0.8f) 0.58f else 1.0f
            val pDmg = (540f * exp(-((pDist / 11.5f) * (pDist / 11.5f))) * coverMult)
            if (pDmg >= 4f) {
                applyPlayerDamage(
                    amount = pDmg,
                    attackerSide = TeamSide.T,
                    killedByBomb = true,
                    attackerId = "bomb_c4",
                    attackerName = "Бомба C4",
                    attackerWeapon = "C4",
                    isHeadshot = false
                )
                if (isPlayerDead) blastKills++
            }
        }

        if (blastKills > 0) {
            killFeedText = "ВЗРЫВ C4 • ПОГИБЛО В ЗОНЕ ПОРАЖЕНИЯ: $blastKills"
            killFeedTimer = 4.4f
        }

        // Give 4.6s so player sees the full 3D fireball, rising smoke, shockwave & fallen bodies!
        endRound(TeamSide.T, "ВЗРЫВ C4 • ПОБЕДА ТЕРРОРИСТОВ", delaySec = 4.6f)
    }

    private fun updateBombAndRoundTimers(dt: Float) {
        if (bombState == BombState.PLANTED) {
            bombCountdownSec -= dt
            bombBeepTimer -= dt
            val beepInterval = (0.12f + (bombCountdownSec / 35f) * 0.92f).coerceIn(0.11f, 1.05f)
            if (bombBeepTimer <= 0f) {
                soundEngine.playBombBeep()
                bombBeepTimer = beepInterval
            }
            if (bombCountdownSec <= 0f) {
                detonatePlantedBomb()
                return
            }
        } else if (bombState == BombState.CARRIED) {
            roundTimeSec = (roundTimeSec - dt).coerceAtLeast(0f)
            if (roundTimeSec <= 0f && isAuthoritativeForMatch()) {
                endRound(TeamSide.CT, "ВРЕМЯ ВЫШЛО • ПОБЕДА СПЕЦНАЗА", delaySec = 3.6f)
                return
            }
        }

        val ctAlive = countAlive(TeamSide.CT)
        val tAlive = countAlive(TeamSide.T)
        if (isAuthoritativeForMatch()) {
            if (ctAlive == 0 && bombState != BombState.DEFUSED) {
                endRound(TeamSide.T, "СПЕЦНАЗ УНИЧТОЖЕН • ПОБЕДА ТЕРРОРИСТОВ", delaySec = 3.6f)
            } else if (tAlive == 0 && bombState == BombState.CARRIED) {
                endRound(TeamSide.CT, "ТЕРРОРИСТЫ УНИЧТОЖЕНЫ • ПОБЕДА СПЕЦНАЗА", delaySec = 3.6f)
            }
        }
    }

    private fun endRound(winner: TeamSide, reason: String, delaySec: Float) {
        if (isRoundOver) return
        isRoundOver = true
        roundOverTimer = delaySec
        roundOverWinner = winner
        roundOverReason = reason
        if (winner == TeamSide.CT) ctScore++ else tScore++
        val playerWon = winner == matchConfig.playerSide
        money = (money + if (playerWon) 3250 else 2150).coerceAtMost(16000)
        // Award real round economy to all bots so they buy weapons / save naturally across rounds!
        for (b in bots) {
            val won = b.team == winner
            b.money = (b.money + if (won) 3250 else 2150).coerceAtMost(16000)
        }
        bannerText = "$reason (CT $ctScore : $tScore T)"
        bannerTimer = delaySec
        emitMatchSyncEvent(winnerSide = winner.name, reason = reason)
    }

    private fun beginReload() {
        if (isEquipping) return
        val w = activeWeapon
        if (w == WeaponType.KNIFE || w == WeaponType.C4_BOMB) return
        val curMag = magAmmoMap[w] ?: 0
        val curRes = reserveAmmoMap[w] ?: 0
        if (isReloading || curMag >= w.magSize || curRes <= 0) return
        isReloading = true
        reloadTimer = 0f
        inspectTimer = 0f
        isAdsToggle = false
        soundEngine.playReload()
    }

    private fun performKnifeSlash() {
        if (isEquipping) return
        fireTimer = WeaponType.KNIFE.fireIntervalSec
        knifeSwingProgress = 0.01f
        soundEngine.playWeaponShot(WeaponType.KNIFE)

        val camX = playerX
        val camY = playerY + eyeHeight
        val camZ = playerZ
        val cosP = cos(playerPitchRad)
        val dirX = -sin(playerYawRad) * cosP
        val dirY = sin(playerPitchRad)
        val dirZ = -cos(playerYawRad) * cosP

        val maxRange = 2.75f
        var bestBot: BotOperative? = null
        var bestRemote: RemoteHumanPlayer? = null
        var bestDist = maxRange
        var bestBackstabOrHead = false
        var bestIsHead = false

        // Melee cone + point-blank check (works reliably even at 0.2m where ray-sphere origin is inside the target!)
        fun evaluateMeleeTarget(tx: Float, ty: Float, tz: Float, targetFwdX: Float, targetFwdZ: Float): Pair<Float, Pair<Boolean, Boolean>>? {
            val dx = tx - camX
            val dy = (ty + 1.12f) - camY
            val dz = tz - camZ
            val dist2D = sqrt(dx * dx + dz * dz)
            if (dist2D > maxRange || abs(dy) > 1.70f) return null
            val dist3D = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.05f)
            val nx = dx / dist3D; val ny = dy / dist3D; val nz = dz / dist3D
            val dot = nx * dirX + ny * dirY + nz * dirZ
            // Generous close-quarters arc; even tighter at max reach, ultra-forgiving at point-blank <0.95m
            val minDot = if (dist2D <= 0.95f) 0.12f else 0.56f
            if (dot < minDot) return null
            // Ensure no solid wall separates the knife from the enemy
            val wallDist = world.raycastWalls(camX, camY, camZ, nx, ny, nz, dist3D)
            if (wallDist < dist3D - 0.35f) return null
            // Backstab check: player attacks from behind enemy's facing vector
            val horizInv = 1f / dist2D.coerceAtLeast(0.05f)
            val backDot = (dx * horizInv) * targetFwdX + (dz * horizInv) * targetFwdZ
            val isBackstab = backDot > 0.35f
            val dyHead = (ty + 1.56f) - camY
            val headDist = sqrt(dx * dx + dyHead * dyHead + dz * dz).coerceAtLeast(0.05f)
            val headDot = (dx / headDist) * dirX + (dyHead / headDist) * dirY + (dz / headDist) * dirZ
            val isHead = headDot >= 0.93f
            return dist3D to ((isBackstab || isHead) to isHead)
        }

        for (b in bots) {
            if (b.isDead || b.team == matchConfig.playerSide) continue
            val yawRad = Math.toRadians(b.visualYawDeg.toDouble())
            val res = evaluateMeleeTarget(b.x, b.y, b.z, sin(yawRad).toFloat(), cos(yawRad).toFloat()) ?: continue
            if (res.first < bestDist) {
                bestDist = res.first
                bestBot = b
                bestRemote = null
                bestBackstabOrHead = res.second.first
                bestIsHead = res.second.second
            }
        }

        if (matchConfig.isMultiplayer) {
            val remotes = getRemotePlayersSnapshot()
            for (rp in remotes) {
                if (rp.isDead || rp.team == matchConfig.playerSide) continue
                val yawRad = Math.toRadians(rp.yawDeg.toDouble())
                val res = evaluateMeleeTarget(rp.x, rp.y, rp.z, -sin(yawRad).toFloat(), -cos(yawRad).toFloat()) ?: continue
                if (res.first < bestDist) {
                    bestDist = res.first
                    bestRemote = rp
                    bestBot = null
                    bestBackstabOrHead = res.second.first
                    bestIsHead = res.second.second
                }
            }
        }

        if (bestBot != null) {
            val dmg = if (bestBackstabOrHead) WeaponType.KNIFE.headDamage else WeaponType.KNIFE.bodyDamage
            shotsHit++
            hitMarkerAlpha = 1.0f
            isHeadshotHit = bestIsHead
            applyBotDamage(bestBot, dmg, isHeadshot = bestIsHead, killerIsPlayer = true, weaponUsed = WeaponType.KNIFE)
            isKillHit = bestBot.isDead
            if (bestIsHead) soundEngine.playHeadshot() else soundEngine.playHitMarker()
            if (settings.audio.hapticsEnabled) onHapticPulse(bestBot.isDead || bestBackstabOrHead)
        } else if (bestRemote != null) {
            val dmg = (if (bestBackstabOrHead) WeaponType.KNIFE.headDamage else WeaponType.KNIFE.bodyDamage).toInt()
            val killedRemote = dmg >= bestRemote.hp
            shotsHit++
            hitMarkerAlpha = 1.0f
            isHeadshotHit = bestIsHead
            isKillHit = killedRemote
            recordDamageDealt(bestRemote.userId, minOf(dmg, bestRemote.hp.coerceAtLeast(1)))
            outgoingHitSeq++
            lastOutgoingHit = OutgoingHitEvent(
                seq = outgoingHitSeq,
                targetUid = bestRemote.userId,
                damage = dmg,
                headshot = bestIsHead,
                weaponName = WeaponType.KNIFE.shortLabel
            )
            if (killedRemote) {
                lastLethalSentTo[bestRemote.userId] = SystemClock.elapsedRealtime() to bestIsHead
                damageDealtByTarget.remove(bestRemote.userId)
                damageTakenByAttacker.remove(bestRemote.userId)
                if (!isWarmup) {
                    kills++
                    if (bestIsHead) headshots++
                    money = (money + WeaponType.KNIFE.killReward).coerceAtMost(16000)
                    score += 300
                }
                pushKillFeed(
                    killerName = matchConfig.localDisplayName.ifBlank { "ВЫ" },
                    killerTeam = matchConfig.playerSide,
                    victimName = bestRemote.displayName,
                    victimTeam = bestRemote.team,
                    weaponLabel = WeaponType.KNIFE.shortLabel,
                    isHeadshot = bestIsHead,
                    killerIsLocal = true
                )
            }
            if (bestIsHead) soundEngine.playHeadshot() else soundEngine.playHitMarker()
            if (settings.audio.hapticsEnabled) onHapticPulse(killedRemote || bestBackstabOrHead)
        }
    }

    private fun firePlayerWeapon() {
        if (isEquipping) return
        val w = activeWeapon
        val nextMag = ((magAmmoMap[w] ?: 1) - 1).coerceAtLeast(0)
        magAmmoMap[w] = nextMag
        shotsFired++
        fireTimer = w.fireIntervalSec
        recoilKick = (recoilKick + (if (isAdsToggle) w.recoilKick * 0.65f else w.recoilKick)).coerceAtMost(1.45f)
        muzzleFlashTimer = 1.0f
        muzzleFlashRoll = rng.nextFloat() * 360f
        playerPitchRad = (playerPitchRad + (if (isAdsToggle) w.recoilPitch * 0.68f else w.recoilPitch))
            .coerceIn(-1.28f, 1.28f)
        if (w.hasSniperScope) boltProgress = 1.0f

        soundEngine.playWeaponShot(w)
        if (settings.audio.hapticsEnabled) onHapticPulse(w == WeaponType.AWP || w == WeaponType.DESERT_EAGLE)

        val camX = playerX
        val camY = playerY + eyeHeight
        val camZ = playerZ
        val cosP = cos(playerPitchRad)
        var dirX = -sin(playerYawRad) * cosP
        var dirY = sin(playerPitchRad)
        var dirZ = -cos(playerYawRad) * cosP

        // CS2-style movement & airborne inaccuracy (running/jumping strongly scatters bullets; standing still is pinpoint)
        val isAirborne = playerY > playerFloorY + 0.08f || abs(playerVelY) > 0.35f
        val baseSpread = (if (isAdsToggle) w.adsSpread else w.hipSpread) *
            if (isCrouching && moveSpeedRatio < 0.12f && !isAirborne) 0.72f else 1.0f
        val weaponMoveInaccuracy = when (w) {
            WeaponType.AWP -> 0.145f
            WeaponType.AK47 -> 0.078f
            WeaponType.M4A1_S -> 0.068f
            WeaponType.DESERT_EAGLE -> 0.064f
            WeaponType.USP_S, WeaponType.GLOCK_18 -> 0.036f
            WeaponType.MP9, WeaponType.P90 -> 0.026f
            else -> 0.030f
        }
        val moveRatio = moveSpeedRatio.coerceIn(0f, 1f)
        val moveSpread = if (moveRatio > 0.08f) {
            weaponMoveInaccuracy * moveRatio * (0.35f + 0.65f * moveRatio)
        } else 0f
        val airSpread = if (isAirborne) 0.115f else 0f
        val spraySpread = recoilKick * 0.018f
        val spread = if (isLocalAdmin && adminAutoAimEnabled && adminLockedOnTarget) {
            0.0005f
        } else {
            (baseSpread + moveSpread + airSpread + spraySpread).coerceAtMost(0.24f)
        }
        dirX += (rng.nextFloat() * 2f - 1f) * spread
        dirY += (rng.nextFloat() * 2f - 1f) * spread
        dirZ += (rng.nextFloat() * 2f - 1f) * spread
        val inv = 1f / sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ).coerceAtLeast(0.0001f)
        dirX *= inv; dirY *= inv; dirZ *= inv

        val wallHit = world.raycastWallsWithNormal(camX, camY, camZ, dirX, dirY, dirZ, 110f)
        val wallDist = wallHit.dist

        var hitBot: BotOperative? = null
        var hitRemote: RemoteHumanPlayer? = null
        var hitDist = wallDist
        var headshot = false

        // Hitboxes aligned tightly to calibrated 1.76m human character height!
        for (b in bots) {
            if (b.isDead || b.team == matchConfig.playerSide) continue
            val tHead = intersectRaySphere(camX, camY, camZ, dirX, dirY, dirZ, b.x, b.y + 1.56f, b.z, 0.22f)
            if (tHead < hitDist) {
                hitDist = tHead; hitBot = b; hitRemote = null; headshot = true
            }
            val tChest = intersectRaySphere(camX, camY, camZ, dirX, dirY, dirZ, b.x, b.y + 1.12f, b.z, 0.35f)
            if (tChest < hitDist) {
                hitDist = tChest; hitBot = b; hitRemote = null; headshot = false
            }
            val tLegs = intersectRaySphere(camX, camY, camZ, dirX, dirY, dirZ, b.x, b.y + 0.54f, b.z, 0.31f)
            if (tLegs < hitDist) {
                hitDist = tLegs; hitBot = b; hitRemote = null; headshot = false
            }
        }

        // Also test against live remote human opponents in multiplayer matches!
        if (matchConfig.isMultiplayer) {
            val remotes = getRemotePlayersSnapshot()
            for (rp in remotes) {
                if (rp.isDead || rp.team == matchConfig.playerSide) continue
                val tHead = intersectRaySphere(camX, camY, camZ, dirX, dirY, dirZ, rp.x, rp.y + 1.56f, rp.z, 0.24f)
                if (tHead < hitDist) {
                    hitDist = tHead; hitRemote = rp; hitBot = null; headshot = true
                }
                val tChest = intersectRaySphere(camX, camY, camZ, dirX, dirY, dirZ, rp.x, rp.y + 1.12f, rp.z, 0.36f)
                if (tChest < hitDist) {
                    hitDist = tChest; hitRemote = rp; hitBot = null; headshot = false
                }
                val tLegs = intersectRaySphere(camX, camY, camZ, dirX, dirY, dirZ, rp.x, rp.y + 0.54f, rp.z, 0.32f)
                if (tLegs < hitDist) {
                    hitDist = tLegs; hitRemote = rp; hitBot = null; headshot = false
                }
            }
        }

        // Emit crisp micro-flash yellow bullet tracer straight from the gun muzzle tip to impact point!
        val rightX = cos(playerYawRad)
        val rightZ = -sin(playerYawRad)
        val sinP = sin(playerPitchRad)
        val upX = sin(playerYawRad) * sinP
        val upY = cosP
        val upZ = cos(playerYawRad) * sinP
        val muzzleRight = (if (isAdsToggle) 0.015f else 0.145f) * (1f - adsBlend * 0.85f)
        val muzzleDown = if (isAdsToggle) -0.055f else -0.095f
        val muzzleFwd = (-w.muzzleOffsetZ + 0.32f).coerceIn(0.45f, 1.15f)
        val trStartX = camX + dirX * muzzleFwd + rightX * muzzleRight + upX * muzzleDown
        val trStartY = camY + dirY * muzzleFwd + upY * muzzleDown
        val trStartZ = camZ + dirZ * muzzleFwd + rightZ * muzzleRight + upZ * muzzleDown
        val tracerDist = hitDist.coerceIn(1.1f, 68f)
        val trEndX = camX + dirX * tracerDist
        val trEndY = camY + dirY * tracerDist
        val trEndZ = camZ + dirZ * tracerDist
        addBulletTracer(trStartX, trStartY, trStartZ, trEndX, trEndY, trEndZ, 0.055f)

        if (hitBot != null) {
            shotsHit++
            hitMarkerAlpha = 1.0f
            isHeadshotHit = headshot
            val dmg = if (headshot) w.headDamage else w.bodyDamage
            applyBotDamage(hitBot, dmg, headshot, killerIsPlayer = true, weaponUsed = w)
            // White hitmarker on all non-lethal hits (body & head), RED strictly on kill!
            isKillHit = hitBot.isDead
            if (headshot) soundEngine.playHeadshot() else soundEngine.playHitMarker()
            if (headshot && settings.audio.hapticsEnabled) onHapticPulse(true)
        } else if (hitRemote != null) {
            shotsHit++
            hitMarkerAlpha = 1.0f
            isHeadshotHit = headshot
            val dmg = (if (headshot) w.headDamage else w.bodyDamage).toInt().coerceIn(1, 200)
            val killedRemote = dmg >= hitRemote.hp
            isKillHit = killedRemote
            recordDamageDealt(hitRemote.userId, minOf(dmg, hitRemote.hp.coerceAtLeast(1)))
            outgoingHitSeq++
            lastOutgoingHit = OutgoingHitEvent(
                seq = outgoingHitSeq,
                targetUid = hitRemote.userId,
                damage = dmg,
                headshot = headshot,
                weaponName = w.shortLabel
            )
            if (killedRemote) {
                lastLethalSentTo[hitRemote.userId] = SystemClock.elapsedRealtime() to headshot
                // Opponent eliminated -> reset accumulated damage against them back to 0!
                damageDealtByTarget.remove(hitRemote.userId)
                damageTakenByAttacker.remove(hitRemote.userId)
                if (!isWarmup) {
                    kills++
                    if (headshot) headshots++
                    money = (money + w.killReward).coerceAtMost(16000)
                    score += if (headshot) 250 else 150
                }
                pushKillFeed(
                    killerName = matchConfig.localDisplayName.ifBlank { "ВЫ" },
                    killerTeam = matchConfig.playerSide,
                    victimName = hitRemote.displayName,
                    victimTeam = hitRemote.team,
                    weaponLabel = w.shortLabel,
                    isHeadshot = headshot,
                    killerIsLocal = true
                )
            }
            if (headshot) soundEngine.playHeadshot() else soundEngine.playHitMarker()
            if (headshot && settings.audio.hapticsEnabled) onHapticPulse(true)
        } else if (wallHit.hit) {
            // Leave a crisp 3D bullet impact mark flush on the struck wall/crate/floor!
            addBulletImpact(wallHit.x, wallHit.y, wallHit.z, wallHit.nx, wallHit.ny, wallHit.nz)
        }

        if (nextMag == 0 && (reserveAmmoMap[w] ?: 0) > 0) beginReload()
    }

    private fun addBulletImpact(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
        if (bulletImpacts.size >= 48) {
            bulletImpacts.removeAt(0)
        }
        bulletImpacts.add(BulletImpactMark(x, y, z, nx, ny, nz, rng.nextFloat() * 360f))
    }

    private fun addBulletTracer(
        sx: Float, sy: Float, sz: Float,
        ex: Float, ey: Float, ez: Float,
        durationSec: Float = 0.055f
    ) {
        if (bulletTracers.size >= 16) {
            bulletTracers.removeAt(0)
        }
        bulletTracers.add(BulletTracer(sx, sy, sz, ex, ey, ez, durationSec, durationSec))
    }

    private fun recordDamageDealt(targetId: String, amount: Int) {
        if (amount <= 0) return
        val prev = damageDealtByTarget[targetId] ?: (0 to 0)
        damageDealtByTarget[targetId] = ((prev.first + amount).coerceAtMost(100)) to (prev.second + 1)
    }

    private fun recordDamageTaken(attackerId: String, amount: Int) {
        if (amount <= 0) return
        val prev = damageTakenByAttacker[attackerId] ?: (0 to 0)
        damageTakenByAttacker[attackerId] = ((prev.first + amount).coerceAtMost(100)) to (prev.second + 1)
    }

    private fun applyBotDamage(
        target: BotOperative,
        dmg: Float,
        isHeadshot: Boolean,
        killerIsPlayer: Boolean,
        weaponUsed: WeaponType,
        killerBot: BotOperative? = null
    ) {
        if (target.isDead) return
        val effectiveDmg = if (target.armor > 0f && !isHeadshot) {
            val absorbed = minOf(target.armor, dmg * 0.35f)
            target.armor -= absorbed
            dmg - absorbed * 0.55f
        } else {
            dmg
        }
        val hpBefore = target.hp
        target.hp -= effectiveDmg
        if (killerIsPlayer) {
            val dealt = minOf(hpBefore, effectiveDmg).toInt().coerceAtLeast(1)
            recordDamageDealt("bot_${target.id}", dealt)
        }
        target.hitFlinch = 1.0f
        // If shot from flank/behind, alert the bot to turn smoothly toward the threat (no instant 180 snap!)
        if (killerIsPlayer && target.hp > 0f) {
            target.alertYawDeg = Math.toDegrees(atan2((playerX - target.x).toDouble(), (playerZ - target.z).toDouble())).toFloat()
            target.alertTimer = 2.4f
        } else if (killerBot != null && target.hp > 0f) {
            target.alertYawDeg = Math.toDegrees(atan2((killerBot.x - target.x).toDouble(), (killerBot.z - target.z).toDouble())).toFloat()
            target.alertTimer = 2.2f
        }
        if (target.hp <= 0f) {
            target.hp = 0f
            target.isDead = true
            target.deathProgress = 0.01f
            target.plantProgress = 0f
            target.defuseProgress = 0f
            if (!isWarmup) target.deaths++
            // Bot eliminated -> immediately reset accumulated exchange damage for this bot to 0!
            damageDealtByTarget.remove("bot_${target.id}")
            damageTakenByAttacker.remove("bot_${target.id}")
            if (killerIsPlayer) {
                if (!isWarmup) {
                    kills++
                    if (isHeadshot) headshots++
                    money = (money + weaponUsed.killReward).coerceAtMost(16000)
                    score += if (isHeadshot) 250 else 150
                }
                pushKillFeed(
                    killerName = matchConfig.localDisplayName.ifBlank { "ВЫ" },
                    killerTeam = matchConfig.playerSide,
                    victimName = target.name,
                    victimTeam = target.team,
                    weaponLabel = weaponUsed.shortLabel,
                    isHeadshot = isHeadshot,
                    killerIsLocal = true
                )
            } else {
                if (killerBot != null && !isWarmup) {
                    killerBot.kills++
                    killerBot.money = (killerBot.money + weaponUsed.killReward).coerceAtMost(16000)
                }
                val kTeam = killerBot?.team ?: if (target.team == TeamSide.CT) TeamSide.T else TeamSide.CT
                val killerLabel = killerBot?.name ?: kTeam.shortRu
                pushKillFeed(
                    killerName = killerLabel,
                    killerTeam = kTeam,
                    victimName = target.name,
                    victimTeam = target.team,
                    weaponLabel = weaponUsed.shortLabel,
                    isHeadshot = isHeadshot
                )
            }
            if (!isWarmup && target.hasBomb && bombState == BombState.CARRIED) {
                target.hasBomb = false
                val nextTBot = bots.firstOrNull { !it.isDead && it.team == TeamSide.T }
                if (matchConfig.playerSide == TeamSide.T && !isPlayerDead) {
                    playerHasBomb = true
                } else if (nextTBot != null) {
                    nextTBot.hasBomb = true
                    nextTBot.navPath = emptyList()
                    nextTBot.repathTimer = 0f
                }
            }
        }
    }

    private fun shortestAngleDiffDeg(fromDeg: Float, toDeg: Float): Float {
        var diff = (toDeg - fromDeg) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return diff
    }

    private fun moveAngleTowardDeg(currentDeg: Float, targetDeg: Float, maxDeltaDeg: Float): Float {
        val diff = shortestAngleDiffDeg(currentDeg, targetDeg)
        return currentDeg + diff.coerceIn(-maxDeltaDeg, maxDeltaDeg)
    }

    private fun updateBots(dt: Float) {
        // During 8-second Freeze Time preparation, bots hold spawn positions cleanly
        if (freezeTimeSec > 0f) {
            for (b in bots) {
                b.isMoving = false
                b.moveForwardRatio = 0f
                b.moveStrafeRatio = 0f
                b.hasLineOfSight = false
                b.muzzleFlashTimer = 0f
            }
            return
        }
        val camY = playerY + eyeHeight

        // Designate the single closest alive CT bot as active bomb defuser when C4 is planted
        var assignedDefuserId = -1
        if (!isWarmup && matchConfig.mode == GameMode.BOMB_DEFUSAL && bombState == BombState.PLANTED) {
            var bestCtDist = Float.MAX_VALUE
            for (ct in bots) {
                if (ct.isDead || ct.team != TeamSide.CT) continue
                val d = world.distance2D(ct.x, ct.z, plantedBombX, plantedBombZ)
                if (d < bestCtDist) {
                    bestCtDist = d
                    assignedDefuserId = ct.id
                }
            }
        }

        for (b in bots) {
            if (b.isDead) {
                b.deathProgress = (b.deathProgress + dt * 1.35f).coerceAtMost(1f)
                b.hasLineOfSight = false
                if ((isWarmup || matchConfig.mode == GameMode.TEAM_DEATHMATCH) && b.deathProgress >= 1f) {
                    b.fireCooldown -= dt
                    if (b.fireCooldown <= -1.8f) respawnBotTdm(b)
                }
                continue
            }

            b.hitFlinch = (b.hitFlinch - dt * 5.0f).coerceAtLeast(0f)
            b.muzzleFlashTimer = (b.muzzleFlashTimer - dt * 20f).coerceAtLeast(0f)
            if (b.fireCooldown > 0f) b.fireCooldown = (b.fireCooldown - dt).coerceAtLeast(0f)
            if (b.burstPauseTimer > 0f) b.burstPauseTimer = (b.burstPauseTimer - dt).coerceAtLeast(0f)
            if (b.repathTimer > 0f) b.repathTimer = (b.repathTimer - dt).coerceAtLeast(0f)
            if (b.unstuckTimer > 0f) b.unstuckTimer = (b.unstuckTimer - dt).coerceAtLeast(0f)
            if (b.alertTimer > 0f) b.alertTimer = (b.alertTimer - dt).coerceAtLeast(0f)

            // 1. Human-like Target Detection (148° forward vision cone + close proximity awareness)
            var targetX = 0f
            var targetY = 0f
            var targetZ = 0f
            var targetBot: BotOperative? = null
            var targetIsPlayer = false
            var targetMoving = false
            var targetKey = -1
            var bestTargetDist = Float.MAX_VALUE
            var hasLos = false

            if (b.team != matchConfig.playerSide && !isPlayerDead) {
                val pdx = playerX - b.x
                val pdy = camY - (b.y + 1.50f)
                val pdz = playerZ - b.z
                val pDist = sqrt(pdx * pdx + pdy * pdy + pdz * pdz).coerceAtLeast(0.01f)
                if (pDist < 48f) {
                    val yawToPlayer = Math.toDegrees(atan2(pdx.toDouble(), pdz.toDouble())).toFloat()
                    val fovDiff = abs(shortestAngleDiffDeg(b.visualYawDeg, yawToPlayer))
                    val inVision = fovDiff <= 74f || pDist < 7.5f || (b.lastTargetKey == 0 && b.losLostGraceTimer > 0f)
                    if (inVision) {
                        val wt = world.raycastWalls(b.x, b.y + 1.50f, b.z, pdx / pDist, pdy / pDist, pdz / pDist, pDist)
                        if (wt >= pDist - 0.7f) {
                            bestTargetDist = pDist
                            targetX = playerX; targetY = camY; targetZ = playerZ
                            targetIsPlayer = true
                            targetMoving = moveSpeedRatio > 0.25f
                            targetKey = 0
                            hasLos = true
                        }
                    } else if (pDist < 12f && isFiring) {
                        // Heard player gunfire nearby -> smoothly turn to check!
                        b.alertYawDeg = yawToPlayer
                        b.alertTimer = 1.8f
                    }
                }
            }

            for (other in bots) {
                if (other.isDead || other.team == b.team) continue
                val odx = other.x - b.x
                val ody = (other.y + 1.45f) - (b.y + 1.50f)
                val odz = other.z - b.z
                val oDist = sqrt(odx * odx + ody * ody + odz * odz).coerceAtLeast(0.01f)
                if (oDist < bestTargetDist && oDist < 46f) {
                    val yawToBot = Math.toDegrees(atan2(odx.toDouble(), odz.toDouble())).toFloat()
                    val fovDiff = abs(shortestAngleDiffDeg(b.visualYawDeg, yawToBot))
                    val inVision = fovDiff <= 76f || oDist < 8.0f || (b.lastTargetKey == other.id && b.losLostGraceTimer > 0f)
                    if (inVision) {
                        val wt = world.raycastWalls(b.x, b.y + 1.50f, b.z, odx / oDist, ody / oDist, odz / oDist, oDist)
                        if (wt >= oDist - 0.7f) {
                            bestTargetDist = oDist
                            targetX = other.x; targetY = other.y + 1.45f; targetZ = other.z
                            targetBot = other
                            targetIsPlayer = false
                            targetMoving = other.isMoving
                            targetKey = other.id
                            hasLos = true
                        }
                    }
                }
            }

            b.hasLineOfSight = hasLos
            val prevX = b.x
            val prevZ = b.z
            val moveSpeed = (3.75f * b.weapon.moveSpeedMult).coerceIn(3.15f, 4.15f)

            // 2. Bomb Planting (T) & Bomb Defusing (CT)
            var isDoingBombTask = false
            if (!isWarmup && matchConfig.mode == GameMode.BOMB_DEFUSAL) {
                val closeThreat = hasLos && bestTargetDist < 12.5f
                if (b.team == TeamSide.T && b.hasBomb && bombState == BombState.CARRIED) {
                    val currentSite = world.bombsites.firstOrNull { it.contains(b.x, b.z) }
                    if (currentSite != null && !closeThreat) {
                        isDoingBombTask = true
                        b.plantProgress += dt / 3.2f
                        b.bombBeepTimer -= dt
                        if (b.bombBeepTimer <= 0f) {
                            soundEngine.playBombBeep()
                            b.bombBeepTimer = 0.65f
                        }
                        if (b.plantProgress >= 1f) {
                            b.plantProgress = 0f
                            b.money = (b.money + 300).coerceAtMost(16000)
                            plantBombAt(b.x, b.y, b.z, currentSite.label, plantedByPlayer = false)
                        }
                    } else if (closeThreat) {
                        b.plantProgress = (b.plantProgress - dt * 1.5f).coerceAtLeast(0f)
                    }
                } else if (b.team == TeamSide.CT && bombState == BombState.PLANTED && b.id == assignedDefuserId) {
                    val distBomb = world.distance2D(b.x, b.z, plantedBombX, plantedBombZ)
                    if (distBomb <= 2.45f && !closeThreat) {
                        isDoingBombTask = true
                        b.defuseProgress += dt / 4.8f
                        b.bombBeepTimer -= dt
                        if (b.bombBeepTimer <= 0f) {
                            soundEngine.playBombBeep()
                            b.bombBeepTimer = 0.75f
                        }
                        // Face the planted C4 while defusing
                        val toBombYaw = Math.toDegrees(atan2((plantedBombX - b.x).toDouble(), (plantedBombZ - b.z).toDouble())).toFloat()
                        b.aimYawDeg = moveAngleTowardDeg(b.aimYawDeg, toBombYaw, 180f * dt)
                        b.visualYawDeg = b.aimYawDeg
                        if (b.defuseProgress >= 1f) {
                            b.defuseProgress = 0f
                            b.money = (b.money + 300).coerceAtMost(16000)
                            bombState = BombState.DEFUSED
                            emitBombSyncEvent()
                            soundEngine.playBombPlanted()
                            endRound(TeamSide.CT, "СПЕЦНАЗ РАЗМИНИРОВАЛ C4!", delaySec = 3.6f)
                            return
                        }
                    } else if (closeThreat) {
                        b.defuseProgress = (b.defuseProgress - dt * 1.5f).coerceAtLeast(0f)
                    }
                } else {
                    b.plantProgress = 0f
                    b.defuseProgress = 0f
                }
            }

            if (isDoingBombTask) {
                b.stuckTimer = 0f
                b.lastProgressX = b.x
                b.lastProgressZ = b.z
                updateDirectionalLocomotion(
                    b, prevX, prevZ,
                    sin(Math.toRadians(b.visualYawDeg.toDouble())).toFloat(),
                    cos(Math.toRadians(b.visualYawDeg.toDouble())).toFloat(),
                    moveSpeed, dt
                )
                continue
            }

            var faceX: Float
            var faceZ: Float
            var vx = 0f
            var vz = 0f

            // 3. Combat Engagement (Medium human reaction ~0.35s, smooth aim tracking, ~45% accuracy, ZERO triggerbot)
            if (hasLos) {
                if (targetKey != b.lastTargetKey || b.losLostGraceTimer <= 0f && b.targetSightTime <= 0f) {
                    b.lastTargetKey = targetKey
                    b.targetSightTime = 0f
                    // Human-like reaction delay centered at ~0.35s (0.30s..0.42s)
                    b.reactionDelaySec = 0.30f + rng.nextFloat() * 0.12f
                    b.burstShotsLeft = 2 + rng.nextInt(3)
                    b.burstPauseTimer = 0f
                }
                b.targetSightTime += dt
                b.losLostGraceTimer = 0.45f

                val dx = targetX - b.x
                val dz = targetZ - b.z
                val dist2d = sqrt(dx * dx + dz * dz).coerceAtLeast(0.01f)
                faceX = dx / dist2d
                faceZ = dz / dist2d
                val rightX = faceZ
                val rightZ = -faceX

                // Natural organic aim sway (~±2.4°) and human turning speed (~185°/s)
                val idealYaw = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()
                val swayDeg = sin((timeSec * 3.8f + b.aimSwaySeed).toDouble()).toFloat() * 2.4f
                val desiredYaw = idealYaw + swayDeg
                b.yawDeg = idealYaw
                b.aimYawDeg = moveAngleTowardDeg(b.aimYawDeg, desiredYaw, 185f * dt)

                // Tactical strafing during gunfights
                b.strafeTimer -= dt
                if (b.strafeTimer <= 0f) {
                    b.strafeDir = when (rng.nextInt(3)) { 0 -> -1f; 1 -> 1f; else -> 0f }
                    b.strafeTimer = 1.0f + rng.nextFloat() * 1.2f
                }
                if (dist2d > 16f) {
                    vx += faceX * moveSpeed * 0.72f; vz += faceZ * moveSpeed * 0.72f
                } else if (dist2d < 6.5f) {
                    vx -= faceX * moveSpeed * 0.55f; vz -= faceZ * moveSpeed * 0.55f
                }
                vx += rightX * b.strafeDir * moveSpeed * 0.55f
                vz += rightZ * b.strafeDir * moveSpeed * 0.55f

                // Shoot ONLY after human reaction delay (~0.35s) AND once gun aim is aligned on target
                val aimErrorDeg = abs(shortestAngleDiffDeg(b.aimYawDeg, idealYaw))
                val isReactionReady = b.targetSightTime >= b.reactionDelaySec
                if (isReactionReady && aimErrorDeg <= 13.5f && b.burstPauseTimer <= 0f && b.fireCooldown <= 0f) {
                    b.muzzleFlashTimer = 1.0f
                    b.muzzleFlashRoll = rng.nextFloat() * 360f

                    b.burstShotsLeft--
                    if (b.weapon == WeaponType.AWP) {
                        b.burstShotsLeft = 1
                        b.fireCooldown = 1.45f
                        b.burstPauseTimer = 0.45f
                    } else if (b.burstShotsLeft <= 0) {
                        // Brief human recoil-reset pause between 2-4 shot bursts
                        b.burstShotsLeft = 2 + rng.nextInt(3)
                        b.burstPauseTimer = 0.44f + rng.nextFloat() * 0.28f
                        b.fireCooldown = 0.18f
                    } else {
                        b.fireCooldown = (b.weapon.fireIntervalSec * 1.55f).coerceIn(0.14f, 0.34f) + rng.nextFloat() * 0.06f
                    }

                    // Calibrated Medium Human Accuracy (~45% at medium range, never 100%)
                    val distBaseAcc = (0.52f - (bestTargetDist - 8f).coerceAtLeast(0f) * 0.0085f).coerceIn(0.16f, 0.56f)
                    val targetMoveMult = if (targetMoving) 0.84f else 1.0f
                    val botStrafeMult = if (abs(b.strafeDir) > 0.1f) 0.88f else 1.0f
                    val aimAlignMult = (1f - (aimErrorDeg / 22f)).coerceIn(0.55f, 1.0f)
                    val hitChance = (distBaseAcc * targetMoveMult * botStrafeMult * aimAlignMult).coerceIn(0.12f, 0.58f)

                    // Emit brief yellow bullet tracer from bot's gun muzzle toward target!
                    val muzzleX = b.x + faceX * 0.55f + rightX * 0.16f
                    val muzzleY = b.y + 1.38f
                    val muzzleZ = b.z + faceZ * 0.55f + rightZ * 0.16f
                    val shotHit = rng.nextFloat() < hitChance

                    if (shotHit) {
                        addBulletTracer(muzzleX, muzzleY, muzzleZ, targetX, targetY - 0.12f, targetZ, 0.055f)
                        val isHs = rng.nextFloat() < 0.12f
                        val baseDmg = when (b.weapon) {
                            WeaponType.AWP -> 58f
                            WeaponType.DESERT_EAGLE -> 22f
                            WeaponType.AK47 -> 15f
                            WeaponType.M4A1_S -> 13f
                            WeaponType.P90, WeaponType.MP9 -> 9.5f
                            else -> 10f
                        }
                        val finalDmg = if (isHs) baseDmg * 1.55f else baseDmg
                        if (targetIsPlayer) {
                            val wasAlive = !isPlayerDead
                            applyPlayerDamage(
                                amount = finalDmg,
                                attackerSide = b.team,
                                killedByBomb = false,
                                attackerId = "bot_${b.id}",
                                attackerName = b.name,
                                attackerWeapon = b.weapon.shortLabel,
                                isHeadshot = isHs
                            )
                            if (wasAlive && isPlayerDead && !isWarmup) {
                                b.kills++
                                b.money = (b.money + b.weapon.killReward).coerceAtMost(16000)
                            }
                        } else if (targetBot != null) {
                            applyBotDamage(targetBot, finalDmg * 1.35f, isHeadshot = isHs, killerIsPlayer = false, weaponUsed = b.weapon, killerBot = b)
                        }
                    } else {
                        // Missed shot flies slightly wide and leaves a tracer + wall impact mark!
                        val mdx = (targetX - muzzleX) + (rng.nextFloat() * 2f - 1f) * 0.9f
                        val mdy = (targetY - 0.15f - muzzleY) + (rng.nextFloat() * 2f - 1f) * 0.55f
                        val mdz = (targetZ - muzzleZ) + (rng.nextFloat() * 2f - 1f) * 0.9f
                        val mLen = sqrt(mdx * mdx + mdy * mdy + mdz * mdz).coerceAtLeast(0.1f)
                        val bWall = world.raycastWallsWithNormal(muzzleX, muzzleY, muzzleZ, mdx / mLen, mdy / mLen, mdz / mLen, 55f)
                        addBulletTracer(muzzleX, muzzleY, muzzleZ, bWall.x, bWall.y, bWall.z, 0.055f)
                        if (bWall.hit) {
                            addBulletImpact(bWall.x, bWall.y, bWall.z, bWall.nx, bWall.ny, bWall.nz)
                        }
                    }
                }
            } else {
                // Target out of sight -> decay grace timer so next encounter gets a fresh human reaction window
                if (b.losLostGraceTimer > 0f) {
                    b.losLostGraceTimer = (b.losLostGraceTimer - dt).coerceAtLeast(0f)
                    if (b.losLostGraceTimer <= 0f) {
                        b.targetSightTime = 0f
                        b.lastTargetKey = -1
                    }
                }

                // 4. Smart A* Navigation Along Corridors (never walks blindly into walls!)
                val objectiveGoal = getBotObjectiveGoal(b, isAssignedDefuser = (b.id == assignedDefuserId))
                val distToGoal = world.distance2D(b.x, b.z, objectiveGoal.first, objectiveGoal.second)

                if (distToGoal > 1.35f) {
                    if (b.navPath.isEmpty() || b.navPathIndex >= b.navPath.size || b.repathTimer <= 0f) {
                        b.navPath = world.findPath(b.x, b.z, objectiveGoal.first, objectiveGoal.second)
                        b.navPathIndex = 0
                        b.repathTimer = 1.6f + rng.nextFloat() * 0.9f
                    }
                    // Advance through reached intermediate path nodes
                    while (b.navPathIndex < b.navPath.size - 1) {
                        val wp = b.navPath[b.navPathIndex]
                        if (world.distance2D(b.x, b.z, wp.first, wp.second) < 1.15f) {
                            b.navPathIndex++
                        } else break
                    }

                    val activeNode = b.navPath.getOrNull(b.navPathIndex) ?: objectiveGoal
                    val dx = activeNode.first - b.x
                    val dz = activeNode.second - b.z
                    val dist = sqrt(dx * dx + dz * dz).coerceAtLeast(0.01f)
                    faceX = dx / dist
                    faceZ = dz / dist

                    vx = faceX * moveSpeed
                    vz = faceZ * moveSpeed

                    // If recently stuck against a corner or doorframe, apply clean lateral detour steering
                    if (b.unstuckTimer > 0f) {
                        val perpX = faceZ * b.unstuckDir
                        val perpZ = -faceX * b.unstuckDir
                        vx = (faceX * 0.45f + perpX * 0.88f) * moveSpeed
                        vz = (faceZ * 0.45f + perpZ * 0.88f) * moveSpeed
                    }

                    val moveYaw = Math.toDegrees(atan2(vx.toDouble(), vz.toDouble())).toFloat()
                    val lookYaw = if (b.alertTimer > 0f) b.alertYawDeg else moveYaw
                    b.yawDeg = lookYaw
                    b.aimYawDeg = moveAngleTowardDeg(b.aimYawDeg, lookYaw, 210f * dt)
                } else {
                    // Reached current waypoint/site -> hold tactical angle briefly (varies per round!) before advancing
                    val scanOffset = sin((timeSec * 1.1f + b.aimSwaySeed).toDouble()).toFloat() * 24f
                    val moveYaw = if (b.alertTimer > 0f) b.alertYawDeg else (b.yawDeg + scanOffset)
                    faceX = sin(Math.toRadians(b.visualYawDeg.toDouble())).toFloat()
                    faceZ = cos(Math.toRadians(b.visualYawDeg.toDouble())).toFloat()
                    b.aimYawDeg = moveAngleTowardDeg(b.aimYawDeg, moveYaw, 155f * dt)

                    if (bombState != BombState.PLANTED) {
                        if (b.holdTimer > 0f && b.waypointIndex > 0) {
                            b.holdTimer = (b.holdTimer - dt).coerceAtLeast(0f)
                        } else {
                            val routes = if (b.team == TeamSide.T) world.tAttackRoutes else world.ctDefenseRoutes
                            val route = routes[b.targetSiteIndex % routes.size]
                            if (b.waypointIndex < route.size - 1) {
                                b.waypointIndex++
                                b.navPath = emptyList()
                                b.repathTimer = 0f
                                // Brief tactical pause on next angle for slow-default / anchor roles
                                if (b.tacticRole >= 3) {
                                    b.holdTimer = 1.6f + rng.nextFloat() * 2.6f
                                }
                            } else if (matchConfig.mode == GameMode.TEAM_DEATHMATCH || isWarmup) {
                                b.targetSiteIndex = rng.nextInt(routes.size)
                                b.waypointIndex = 0
                                b.navPath = emptyList()
                                b.repathTimer = 0f
                            } else if (b.team == TeamSide.CT && roundTimeSec < 75f && rng.nextFloat() < 0.004f) {
                                // Occasional CT mid-round flank/rotation so rounds never feel static
                                b.targetSiteIndex = (b.targetSiteIndex + 1) % routes.size
                                b.waypointIndex = 0
                                b.navPath = emptyList()
                                b.repathTimer = 0f
                            }
                        }
                    }
                }
            }

            // 5. Gentle Bot-to-Bot Separation (prevents bots from blocking each other in doorways)
            var sepX = 0f
            var sepZ = 0f
            for (other in bots) {
                if (other.id == b.id || other.isDead) continue
                val sdx = b.x - other.x
                val sdz = b.z - other.z
                val sd2 = sdx * sdx + sdz * sdz
                if (sd2 in 0.0004f..1.10f) {
                    val sd = sqrt(sd2)
                    val push = (1.05f - sd) * 2.4f
                    sepX += (sdx / sd) * push
                    sepZ += (sdz / sd) * push
                }
            }
            vx += sepX
            vz += sepZ

            val (nx, ny, nz) = world.resolveMovement(b.x, b.y, b.z, b.x + vx * dt, b.z + vz * dt, 0.30f)
            b.x = nx; b.y = ny; b.z = nz

            // 6. Anti-Stuck Detector: if bot intended to move but barely progressed, sidestep & recalculate A* path
            val intendedSpeed = sqrt(vx * vx + vz * vz)
            if (!hasLos && intendedSpeed > 1.0f) {
                b.stuckTimer += dt
                if (b.stuckTimer >= 0.55f) {
                    val progressed = world.distance2D(b.x, b.z, b.lastProgressX, b.lastProgressZ)
                    b.lastProgressX = b.x
                    b.lastProgressZ = b.z
                    b.stuckTimer = 0f
                    if (progressed < 0.30f) {
                        b.unstuckDir = -b.unstuckDir
                        b.unstuckTimer = 0.55f
                        b.navPath = emptyList()
                        b.repathTimer = 0f
                    }
                }
            } else {
                b.stuckTimer = 0f
                b.lastProgressX = b.x
                b.lastProgressZ = b.z
            }

            // Smooth shortest-arc visual body turn following human aim angle
            val bodyDiff = shortestAngleDiffDeg(b.visualYawDeg, b.aimYawDeg)
            b.visualYawDeg += bodyDiff * (dt * 12f).coerceAtMost(1f)
            updateDirectionalLocomotion(b, prevX, prevZ, faceX, faceZ, moveSpeed, dt)
        }
    }

    private fun getBotObjectiveGoal(b: BotOperative, isAssignedDefuser: Boolean): Pair<Float, Float> {
        if (!isWarmup && matchConfig.mode == GameMode.BOMB_DEFUSAL) {
            if (bombState == BombState.PLANTED) {
                return if (b.team == TeamSide.CT && isAssignedDefuser) {
                    // Assigned CT defuser walks straight onto the planted C4 via A*
                    plantedBombX to plantedBombZ
                } else {
                    // Other CT bots cover near C4; T bots guard C4 from surrounding angles
                    val angle = (b.id * 1.35f).toDouble()
                    val guardRadius = if (b.team == TeamSide.CT) 4.5f else 6.2f
                    world.snapToNearestWalkable(
                        plantedBombX + cos(angle).toFloat() * guardRadius,
                        plantedBombZ + sin(angle).toFloat() * guardRadius
                    )
                }
            }
            // Terrorist bomb carrier advances along its route straight into target bombsite center (A or B)
            if (b.team == TeamSide.T && b.hasBomb) {
                val routes = world.tAttackRoutes
                val route = routes[b.targetSiteIndex % routes.size]
                return if (b.waypointIndex < route.size - 1) {
                    route[b.waypointIndex.coerceIn(0, route.lastIndex)]
                } else {
                    val site = world.bombsites[b.targetBombsiteIdx.coerceIn(0, world.bombsites.lastIndex)]
                    world.snapToNearestWalkable(site.centerX, site.centerZ)
                }
            }
        }
        val routes = if (b.team == TeamSide.T) world.tAttackRoutes else world.ctDefenseRoutes
        val route = routes[b.targetSiteIndex % routes.size]
        return route[b.waypointIndex.coerceIn(0, route.lastIndex)]
    }

    private fun updateDirectionalLocomotion(
        b: BotOperative,
        prevX: Float, prevZ: Float,
        faceX: Float, faceZ: Float,
        refSpeed: Float, dt: Float
    ) {
        val rightX = faceZ
        val rightZ = -faceX
        val velX = (b.x - prevX) / dt
        val velZ = (b.z - prevZ) / dt
        val localFwd = ((velX * faceX + velZ * faceZ) / refSpeed).coerceIn(-1f, 1f)
        val localStrafe = ((velX * rightX + velZ * rightZ) / refSpeed).coerceIn(-1f, 1f)
        val smooth = (dt * 10f).coerceAtMost(1f)
        b.moveForwardRatio += (localFwd - b.moveForwardRatio) * smooth
        b.moveStrafeRatio += (localStrafe - b.moveStrafeRatio) * smooth
        val totalSpeed = sqrt(velX * velX + velZ * velZ)
        b.isMoving = totalSpeed > 0.14f
        if (abs(b.moveForwardRatio) > 0.05f) b.walkPhase += dt * (2.6f + abs(b.moveForwardRatio) * 5.8f)
        if (abs(b.moveStrafeRatio) > 0.05f) b.strafePhase += dt * (2.8f + abs(b.moveStrafeRatio) * 5.2f)
    }

    private fun applyPlayerDamage(
        amount: Float,
        attackerSide: TeamSide,
        killedByBomb: Boolean,
        attackerId: String = "",
        attackerName: String = "",
        attackerWeapon: String = "",
        isHeadshot: Boolean = false
    ) {
        if (awaitingTeamSelection || isPlayerDead || isRoundOver || isMatchFinished || freezeTimeSec > 0f || (!killedByBomb && spawnProtectionTimer > 0f)) {
            return
        }
        damageFlashAlpha = if (killedByBomb) 1.0f else 0.88f
        if (!killedByBomb) soundEngine.playDamage()
        if (settings.audio.hapticsEnabled) onHapticPulse(true)

        val absorbed = minOf(armor, amount * 0.55f)
        armor -= absorbed
        val actualLoss = (amount - absorbed * 0.5f).coerceAtLeast(1f)
        val hpBefore = hp
        hp -= actualLoss

        val key = attackerId.ifBlank { if (killedByBomb) "bomb_c4" else attackerName.ifBlank { attackerSide.name } }
        recordDamageTaken(key, minOf(hpBefore, actualLoss).toInt().coerceAtLeast(1))

        if (hp <= 0f) {
            hp = 0f
            isPlayerDead = true
            isAdsToggle = false
            isFiring = false
            isBombActionHeld = false
            if (!isWarmup) deaths++

            val displayKiller = attackerName.ifBlank { if (killedByBomb) "Бомба C4" else attackerSide.shortRu }
            pushKillFeed(
                killerName = displayKiller,
                killerTeam = attackerSide,
                victimName = matchConfig.localDisplayName.ifBlank { "ВЫ" },
                victimTeam = matchConfig.playerSide,
                weaponLabel = if (killedByBomb) "C4" else attackerWeapon.ifBlank { "ОРУЖИЕ" },
                isHeadshot = isHeadshot,
                victimIsLocal = true
            )

            // Bloodied death screen for ~3.8s + Killer & Damage Exchange Recap Card for ~6.0s (strictly capped at 100!)
            deathBloodTimer = 3.8f
            deathRecapTimer = 6.0f
            deathCamDelayTimer = 1.65f
            spectateCamInitialized = false
            val dealt = damageDealtByTarget[key] ?: (0 to 0)
            val taken = damageTakenByAttacker[key] ?: (actualLoss.toInt().coerceIn(1, 100) to 1)
            activeDeathRecap = DeathRecapInfo(
                killerName = displayKiller,
                killerWeapon = attackerWeapon,
                killerTeam = attackerSide,
                wasHeadshot = isHeadshot,
                killedByBomb = killedByBomb,
                damageGiven = dealt.first.coerceIn(0, 100),
                hitsGiven = dealt.second,
                damageTaken = taken.first.coerceIn(0, 100),
                hitsTaken = taken.second
            )
            // Immediately reset all damage accumulators to 0 upon death so future fights never carry over old numbers!
            damageDealtByTarget.clear()
            damageTakenByAttacker.clear()

            if (!isWarmup && playerHasBomb && bombState == BombState.CARRIED) {
                playerHasBomb = false
                bots.firstOrNull { !it.isDead && it.team == TeamSide.T }?.hasBomb = true
            }
            if (isWarmup) {
                tdmRespawnTimer = 2.4f
            } else if (matchConfig.mode == GameMode.TEAM_DEATHMATCH) {
                tdmRespawnTimer = 2.8f
            }
        }
    }

    private data class SpectateCandidate(
        val name: String,
        val x: Float,
        val y: Float,
        val z: Float,
        val fwdX: Float,
        val fwdZ: Float,
        val hp: Int,
        val armor: Int,
        val weaponLabel: String
    )

    private fun updateDeathFeedbackAndSpectator(dt: Float) {
        if (deathBloodTimer > 0f) {
            deathBloodTimer = (deathBloodTimer - dt).coerceAtLeast(0f)
        }
        if (deathRecapTimer > 0f) {
            deathRecapTimer = (deathRecapTimer - dt).coerceAtLeast(0f)
            if (deathRecapTimer <= 0f) {
                activeDeathRecap = null
            }
        }

        if (!isPlayerDead || isMatchFinished || isWarmup || matchConfig.mode == GameMode.TEAM_DEATHMATCH) {
            isSpectatingAlly = false
            spectatedAllyCount = 0
            spectateCamInitialized = false
            pendingSpectateDelta = 0
            return
        }

        if (deathCamDelayTimer > 0f) {
            deathCamDelayTimer = (deathCamDelayTimer - dt).coerceAtLeast(0f)
            isSpectatingAlly = false
            return
        }

        val candidates = ArrayList<SpectateCandidate>()
        // Include surviving human teammates first, then surviving allied bots
        if (matchConfig.isMultiplayer) {
            synchronized(remoteLock) {
                for (rp in remotePlayersMap.values) {
                    if (!rp.isDead && rp.team == matchConfig.playerSide) {
                        val yawRad = Math.toRadians(rp.yawDeg.toDouble())
                        candidates.add(
                            SpectateCandidate(
                                name = rp.displayName.ifBlank { "Союзник" },
                                x = rp.x,
                                y = rp.y,
                                z = rp.z,
                                fwdX = -sin(yawRad).toFloat(),
                                fwdZ = -cos(yawRad).toFloat(),
                                hp = rp.hp,
                                armor = 100,
                                weaponLabel = rp.weapon.shortLabel
                            )
                        )
                    }
                }
            }
        }
        for (b in bots) {
            if (!b.isDead && b.team == matchConfig.playerSide) {
                val yawRad = Math.toRadians(b.visualYawDeg.toDouble())
                candidates.add(
                    SpectateCandidate(
                        name = b.name,
                        x = b.x,
                        y = b.y,
                        z = b.z,
                        fwdX = sin(yawRad).toFloat(),
                        fwdZ = cos(yawRad).toFloat(),
                        hp = b.hp.toInt().coerceAtLeast(1),
                        armor = b.armor.toInt().coerceAtLeast(0),
                        weaponLabel = b.weapon.shortLabel
                    )
                )
            }
        }

        spectatedAllyCount = candidates.size
        if (candidates.isEmpty()) {
            isSpectatingAlly = false
            spectateCamInitialized = false
            pendingSpectateDelta = 0
            return
        }

        val delta = pendingSpectateDelta
        if (delta != 0) {
            pendingSpectateDelta = 0
            spectateTargetIndex = ((spectateTargetIndex + delta) % candidates.size + candidates.size) % candidates.size
            spectateCamInitialized = false
        } else if (spectateTargetIndex >= candidates.size) {
            spectateTargetIndex = 0
            spectateCamInitialized = false
        }

        val target = candidates[spectateTargetIndex]
        val rightX = -target.fwdZ
        val rightZ = target.fwdX

        // Wall-safe over-the-shoulder tactical spectator camera (never clips into walls!)
        val anchorX = target.x
        val anchorY = target.y + 1.56f
        val anchorZ = target.z
        val desiredX = target.x - target.fwdX * 2.45f + rightX * 0.42f
        val desiredY = target.y + 1.98f
        val desiredZ = target.z - target.fwdZ * 2.45f + rightZ * 0.42f

        val rx = desiredX - anchorX
        val ry = desiredY - anchorY
        val rz = desiredZ - anchorZ
        val rLen = sqrt(rx * rx + ry * ry + rz * rz).coerceAtLeast(0.01f)
        val wallDist = world.raycastWalls(anchorX, anchorY, anchorZ, rx / rLen, ry / rLen, rz / rLen, rLen)
        val safeDist = (minOf(rLen, wallDist - 0.30f)).coerceAtLeast(0.45f)

        val goalCamX = anchorX + (rx / rLen) * safeDist
        val goalCamY = anchorY + (ry / rLen) * safeDist
        val goalCamZ = anchorZ + (rz / rLen) * safeDist
        val goalLookX = target.x + target.fwdX * 11.0f
        val goalLookY = target.y + 1.48f
        val goalLookZ = target.z + target.fwdZ * 11.0f

        if (!spectateCamInitialized) {
            spectateCamX = goalCamX
            spectateCamY = goalCamY
            spectateCamZ = goalCamZ
            spectateLookX = goalLookX
            spectateLookY = goalLookY
            spectateLookZ = goalLookZ
            spectateCamInitialized = true
        } else {
            val camAlpha = (dt * 10.5f).coerceAtMost(1f)
            spectateCamX += (goalCamX - spectateCamX) * camAlpha
            spectateCamY += (goalCamY - spectateCamY) * camAlpha
            spectateCamZ += (goalCamZ - spectateCamZ) * camAlpha
            spectateLookX += (goalLookX - spectateLookX) * camAlpha
            spectateLookY += (goalLookY - spectateLookY) * camAlpha
            spectateLookZ += (goalLookZ - spectateLookZ) * camAlpha
        }

        isSpectatingAlly = true
        spectatedAllyName = target.name
        spectatedAllyHp = target.hp
        spectatedAllyArmor = target.armor
        spectatedAllyWeapon = target.weaponLabel
    }

    private fun updateTeamDeathmatchRules(dt: Float) {
        roundTimeSec -= dt
        if (isPlayerDead) {
            tdmRespawnTimer -= dt
            if (tdmRespawnTimer <= 0f) {
                synchronized(remoteLock) { pendingIncomingPvpHits.clear() }
                hp = 100f
                armor = 100f
                isPlayerDead = false
                damageFlashAlpha = 0f
                spawnProtectionTimer = 1.25f
                deathBloodTimer = 0f
                deathRecapTimer = 0f
                activeDeathRecap = null
                isSpectatingAlly = false
                spectateCamInitialized = false
                damageDealtByTarget.clear()
                damageTakenByAttacker.clear()
                val (sx, sy, sz) = world.getSpawnPoint(matchConfig.playerSide, rng.nextInt(5))
                playerX = sx; playerY = sy; playerFloorY = sy; playerZ = sz
                refillAmmoForOwnedWeapons()
            }
        }
        if (kills >= 25 || roundTimeSec <= 0f) {
            isPlayerTeamWinner = kills >= deaths
            finishMatch()
        }
    }

    private fun respawnBotTdm(b: BotOperative) {
        val (sx, sy, sz) = world.getSpawnPoint(b.team, rng.nextInt(5))
        b.x = sx; b.y = sy; b.z = sz
        b.hp = 100f
        b.isDead = false
        b.deathProgress = 0f
        b.fireCooldown = 0.4f
        b.waypointIndex = 0
        b.navPath = emptyList()
        b.navPathIndex = 0
        b.repathTimer = 0f
        b.stuckTimer = 0f
        b.lastProgressX = sx
        b.lastProgressZ = sz
        b.targetSightTime = 0f
        b.losLostGraceTimer = 0f
        b.lastTargetKey = -1
        b.alertTimer = 0f
        damageDealtByTarget.remove("bot_${b.id}")
        damageTakenByAttacker.remove("bot_${b.id}")
    }

    private fun finishMatch() {
        isMatchFinished = true
        isPlayerTeamWinner = when (matchConfig.mode) {
            GameMode.BOMB_DEFUSAL -> {
                if (matchConfig.playerSide == TeamSide.CT) ctScore >= tScore else tScore >= ctScore
            }
            GameMode.TEAM_DEATHMATCH -> kills >= deaths
        }
        if (!matchRecorded) {
            matchRecorded = true
            onMatchFinished(score, kills, headshots)
        }
    }

    private fun countAlive(side: TeamSide): Int {
        var count = bots.count { !it.isDead && it.team == side }
        if (matchConfig.playerSide == side && !isPlayerDead) count++
        if (matchConfig.isMultiplayer) {
            synchronized(remoteLock) {
                count += remotePlayersMap.values.count { !it.isDead && it.team == side }
            }
        }
        return count
    }

    private fun intersectRaySphere(
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
        cx: Float, cy: Float, cz: Float,
        radius: Float
    ): Float {
        val ocX = ox - cx; val ocY = oy - cy; val ocZ = oz - cz
        val b = ocX * dx + ocY * dy + ocZ * dz
        val c = ocX * ocX + ocY * ocY + ocZ * ocZ - radius * radius
        val disc = b * b - c
        if (disc < 0f) return Float.MAX_VALUE
        val t = -b - sqrt(disc)
        return if (t > 0.1f) t else Float.MAX_VALUE
    }

    private fun publishHud() {
        val w = activeWeapon
        val inSite = world.bombsites.firstOrNull { it.contains(playerX, playerZ) }
        val distBomb = world.distance2D(playerX, playerZ, plantedBombX, plantedBombZ)

        val canPlant = matchConfig.mode == GameMode.BOMB_DEFUSAL &&
            !isWarmup &&
            freezeTimeSec <= 0f &&
            !isPlayerDead &&
            !isRoundOver &&
            matchConfig.playerSide == TeamSide.T &&
            playerHasBomb &&
            bombState == BombState.CARRIED &&
            inSite != null

        val canDefuse = matchConfig.mode == GameMode.BOMB_DEFUSAL &&
            !isWarmup &&
            !isPlayerDead &&
            !isRoundOver &&
            matchConfig.playerSide == TeamSide.CT &&
            bombState == BombState.PLANTED &&
            distBomb <= 3.6f &&
            abs(playerY - plantedBombY) <= 2.4f

        val remotes = if (matchConfig.isMultiplayer) getRemotePlayersSnapshot() else emptyList()
        val blips = ArrayList<RadarBlip>(bots.size + remotes.size + 1)
        val eyeX = playerX
        val eyeY = playerY + eyeHeight
        val eyeZ = playerZ
        val cosP = cos(playerPitchRad)
        val camFwdX = -sin(playerYawRad) * cosP
        val camFwdY = sin(playerPitchRad)
        val camFwdZ = -cos(playerYawRad) * cosP

        // Fair CS2 Radar (NO wallhack!): allies & planted C4 are always shown; enemies appear ONLY when directly seen!
        fun isEnemyInDirectSight(ex: Float, ey: Float, ez: Float): Boolean {
            if (isPlayerDead) return false
            val dx = ex - eyeX
            val dyChest = (ey + 1.24f) - eyeY
            val dz = ez - eyeZ
            val dist = sqrt(dx * dx + dyChest * dyChest + dz * dz)
            if (dist < 0.05f || dist > 56f) return false
            val inv = 1f / dist
            val dot = (dx * camFwdX + dyChest * camFwdY + dz * camFwdZ) * inv
            // Strictly inside forward camera view cone (~56° half-angle) so enemies behind walls/back never appear
            if (dot < 0.56f) return false
            val wtChest = world.raycastWalls(eyeX, eyeY, eyeZ, dx * inv, dyChest * inv, dz * inv, dist)
            if (wtChest >= dist - 0.65f) return true
            // Also check peeking head height over low cover/crates
            val dyHead = (ey + 1.58f) - eyeY
            val distH = sqrt(dx * dx + dyHead * dyHead + dz * dz).coerceAtLeast(0.05f)
            val wtHead = world.raycastWalls(eyeX, eyeY, eyeZ, dx / distH, dyHead / distH, dz / distH, distH)
            return wtHead >= distH - 0.65f
        }

        for (b in bots) {
            if (!b.isDead) {
                val ally = b.team == matchConfig.playerSide
                if (ally || isEnemyInDirectSight(b.x, b.y, b.z)) {
                    blips.add(RadarBlip(b.x - playerX, b.z - playerZ, isAlly = ally))
                }
            }
        }
        for (rp in remotes) {
            if (!rp.isDead) {
                val ally = rp.team == matchConfig.playerSide
                if (ally || isEnemyInDirectSight(rp.x, rp.y, rp.z)) {
                    blips.add(RadarBlip(rp.x - playerX, rp.z - playerZ, isAlly = ally))
                }
            }
        }
        if (bombState == BombState.PLANTED) {
            blips.add(RadarBlip(plantedBombX - playerX, plantedBombZ - playerZ, isAlly = false, isBomb = true))
        }

        val scoreboardRows = ArrayList<ScoreboardRow>(1 + remotes.size + bots.size)
        scoreboardRows.add(
            ScoreboardRow(
                id = matchConfig.localUserId.ifBlank { "local_player" },
                name = matchConfig.localDisplayName.ifBlank { "ВЫ" },
                team = matchConfig.playerSide,
                kills = kills,
                deaths = deaths,
                money = money,
                pingMs = if (matchConfig.isMultiplayer) livePingMs else 0,
                isAlive = !isPlayerDead,
                isLocalPlayer = true,
                isAlly = true,
                isBot = false,
                weaponLabel = activeWeapon.shortLabel
            )
        )
        for (rp in remotes) {
            val ally = rp.team == matchConfig.playerSide
            scoreboardRows.add(
                ScoreboardRow(
                    id = rp.userId,
                    name = rp.displayName.ifBlank { "Игрок" },
                    team = rp.team,
                    kills = rp.kills,
                    deaths = rp.deaths,
                    money = -1, // Strictly hide enemy money (and unshared remote money)
                    pingMs = livePingMs,
                    isAlive = !rp.isDead,
                    isLocalPlayer = false,
                    isAlly = ally,
                    isBot = false,
                    weaponLabel = if (ally) rp.weapon.shortLabel else ""
                )
            )
        }
        for (b in bots) {
            val ally = b.team == matchConfig.playerSide
            scoreboardRows.add(
                ScoreboardRow(
                    id = "bot_${b.id}",
                    name = b.name,
                    team = b.team,
                    kills = b.kills,
                    deaths = b.deaths,
                    money = if (ally) b.money else -1, // Enemy money strictly hidden!
                    pingMs = 0,
                    isAlive = !b.isDead,
                    isLocalPlayer = false,
                    isAlly = ally,
                    isBot = true,
                    weaponLabel = if (ally) b.weapon.shortLabel else ""
                )
            )
        }

        val bloodAlpha = if (deathBloodTimer > 0f) {
            (deathBloodTimer / 3.8f).coerceIn(0f, 1f)
        } else 0f

        val ctHumans = remotes.count { it.team == TeamSide.CT } + if (!awaitingTeamSelection && matchConfig.playerSide == TeamSide.CT) 1 else 0
        val tHumans = remotes.count { it.team == TeamSide.T } + if (!awaitingTeamSelection && matchConfig.playerSide == TeamSide.T) 1 else 0

        val isAirborneNow = playerY > playerFloorY + 0.08f || abs(playerVelY) > 0.35f
        val dynamicCrosshairSpread = (
            recoilKick * 0.55f +
                moveSpeedRatio.coerceIn(0f, 1f) * 0.90f +
                (if (isAirborneNow) 0.72f else 0f)
            ).coerceIn(0f, 1.85f)

        val espBoxes = if (isLocalAdmin && adminEspEnabled && !awaitingTeamSelection && !isMatchFinished && !isSpectatingAlly) {
            computeAdminEspBoxes(remotes, ::isEnemyInDirectSight)
        } else {
            emptyList()
        }

        latestHud = HudSnapshot(
            mapId = matchConfig.map,
            hp = hp.toInt().coerceAtLeast(0),
            maxHp = 100,
            armor = armor.toInt().coerceAtLeast(0),
            hasDefuseKit = hasDefuseKit,
            money = money,
            mode = matchConfig.mode,
            playerSide = matchConfig.playerSide,
            activeWeapon = w,
            primaryWeapon = primaryWeapon,
            pistolWeapon = pistolWeapon,
            hasBomb = playerHasBomb,
            ammo = magAmmoMap[w] ?: 0,
            magSize = w.magSize,
            reserveAmmo = reserveAmmoMap[w] ?: 0,
            isReloading = isReloading,
            reloadProgress = if (isReloading && w.reloadSec > 0f) (reloadTimer / w.reloadSec).coerceIn(0f, 1f) else 0f,
            isEquipping = isEquipping,
            equipProgress = equipProgress,
            isInspecting = isInspecting,
            inspectProgress = inspectProgress,
            isAds = isAdsToggle,
            adsBlend = adsBlend,
            isCrouching = isCrouching,
            isPlayerDead = isPlayerDead,
            ctScore = ctScore,
            tScore = tScore,
            roundsToWin = matchConfig.roundsToWin,
            roundNumber = roundNumber,
            roundTimeSec = roundTimeSec.coerceAtLeast(0f),
            freezeTimeSec = freezeTimeSec.coerceAtLeast(0f),
            isWarmup = isWarmup,
            warmupTimeSec = warmupTimer.coerceAtLeast(0f),
            ctAlive = countAlive(TeamSide.CT),
            tAlive = countAlive(TeamSide.T),
            bombState = bombState,
            bombSiteLabel = plantedSiteLabel,
            bombCountdownSec = bombCountdownSec.coerceAtLeast(0f),
            bombDistanceMeters = if (bombState == BombState.PLANTED) distBomb.toInt().coerceAtLeast(0) else 0,
            inPlantZoneLabel = inSite?.label ?: "",
            canPlantBomb = canPlant,
            canDefuseBomb = canDefuse,
            bombActionProgress = bombPlantProgress,
            explosionFlashAlpha = explosionFlashAlpha,
            canBuy = canPlayerBuyNow(),
            calloutName = world.getCalloutName(playerX, playerZ),
            score = score,
            kills = kills,
            deaths = deaths,
            headshots = headshots,
            shotsFired = shotsFired,
            shotsHit = shotsHit,
            fps = currentFps,
            hitMarkerAlpha = hitMarkerAlpha,
            isHeadshotHit = isHeadshotHit,
            isKillHit = isKillHit,
            damageFlashAlpha = damageFlashAlpha,
            deathBloodAlpha = bloodAlpha,
            deathRecap = if (deathRecapTimer > 0f) activeDeathRecap else null,
            isSpectatingAlly = isSpectatingAlly,
            spectatedAllyName = spectatedAllyName,
            spectatedAllyHp = spectatedAllyHp,
            spectatedAllyArmor = spectatedAllyArmor,
            spectatedAllyWeapon = spectatedAllyWeapon,
            spectatedAllyIndex = spectateTargetIndex,
            spectatedAllyCount = spectatedAllyCount,
            crosshairSpread = dynamicCrosshairSpread,
            isMatchFinished = isMatchFinished,
            isPlayerTeamWinner = isPlayerTeamWinner,
            bannerText = bannerText,
            killFeedText = killFeedText,
            killFeedEntries = killFeedList.map { it.entry },
            awaitingTeamSelection = awaitingTeamSelection,
            ctTeamCount = ctHumans,
            tTeamCount = tHumans,
            sidesSwapped = hasSwappedSides,
            radarBlips = blips,
            playerYawDeg = Math.toDegrees(playerYawRad.toDouble()).toFloat(),
            isMultiplayer = matchConfig.isMultiplayer,
            onlinePlayersCount = 1 + remotes.size,
            pingMs = if (matchConfig.isMultiplayer) livePingMs else 0,
            scoreboardRows = scoreboardRows,
            espBoxes = espBoxes,
            adminEspEnabled = isLocalAdmin && adminEspEnabled,
            adminAutoAimEnabled = isLocalAdmin && adminAutoAimEnabled
        )
    }

    /**
     * Admin-only Auto-Aim (exclusively unlocked for player with shortId == "932760").
     * Locks cleanly onto the best line-of-sight enemy head and counters recoil while active.
     */
    private fun applyAdminAutoAim(dt: Float) {
        adminLockedOnTarget = false
        if (!isLocalAdmin || !adminAutoAimEnabled || isPlayerDead || awaitingTeamSelection || isMatchFinished) return
        if (activeWeapon == WeaponType.C4_BOMB) return

        val eyeX = playerX
        val eyeY = playerY + eyeHeight
        val eyeZ = playerZ
        val cosP = cos(playerPitchRad)
        val fwdX = -sin(playerYawRad) * cosP
        val fwdY = sin(playerPitchRad)
        val fwdZ = -cos(playerYawRad) * cosP

        var bestScore = Float.POSITIVE_INFINITY
        var targetYaw = playerYawRad
        var targetPitch = playerPitchRad
        var found = false

        fun considerEnemy(ex: Float, ey: Float, ez: Float) {
            val aimY = ey + 1.54f
            val dx = ex - eyeX
            val dy = aimY - eyeY
            val dz = ez - eyeZ
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist < 0.35f || dist > 68f) return
            val inv = 1f / dist
            val wt = world.raycastWalls(eyeX, eyeY, eyeZ, dx * inv, dy * inv, dz * inv, dist)
            if (wt < dist - 0.55f) return

            val dot = (dx * fwdX + dy * fwdY + dz * fwdZ) * inv
            // Prefer enemies closest to crosshair and within reasonable distance
            val angularPenalty = (1f - dot.coerceIn(-1f, 1f)) * 28f
            val scoreVal = angularPenalty + dist * 0.14f
            if (scoreVal < bestScore) {
                bestScore = scoreVal
                val horiz = sqrt(dx * dx + dz * dz).coerceAtLeast(0.001f)
                targetYaw = atan2(-dx, -dz)
                targetPitch = atan2(dy, horiz).coerceIn(-1.25f, 1.25f)
                found = true
            }
        }

        for (b in bots) {
            if (!b.isDead && b.team != matchConfig.playerSide) {
                considerEnemy(b.x, b.y, b.z)
            }
        }
        if (matchConfig.isMultiplayer) {
            val remotes = getRemotePlayersSnapshot()
            for (rp in remotes) {
                if (!rp.isDead && rp.team != matchConfig.playerSide) {
                    considerEnemy(rp.x, rp.y, rp.z)
                }
            }
        }

        if (!found) return
        var dYaw = targetYaw - playerYawRad
        while (dYaw > PI.toFloat()) dYaw -= (2.0 * PI).toFloat()
        while (dYaw < -PI.toFloat()) dYaw += (2.0 * PI).toFloat()
        val dPitch = targetPitch - playerPitchRad
        val snapRate = if (isFiring) 46f else 24f
        val blend = (1f - exp(-dt * snapRate)).coerceIn(0.25f, 1f)
        playerYawRad += dYaw * blend
        playerPitchRad = (playerPitchRad + dPitch * blend).coerceIn(-1.28f, 1.28f)
        adminLockedOnTarget = (abs(dYaw) < 0.14f && abs(dPitch) < 0.14f)
    }

    /**
     * Admin-only 2D Wallhack ESP projection (exclusively unlocked for player with shortId == "932760").
     */
    private fun computeAdminEspBoxes(
        remotes: List<RemoteHumanPlayer>,
        hasSight: (Float, Float, Float) -> Boolean
    ): List<EspBox> {
        val eyeX = playerX
        val eyeY = playerY + eyeHeight
        val eyeZ = playerZ
        val cosP = cos(playerPitchRad)
        val sinP = sin(playerPitchRad)
        val sinY = sin(playerYawRad)
        val cosY = cos(playerYawRad)

        val fwdX = -sinY * cosP
        val fwdY = sinP
        val fwdZ = -cosY * cosP
        val rightX = cosY
        val rightZ = -sinY
        val upX = sinY * sinP
        val upY = cosP
        val upZ = cosY * sinP

        val activeAspect = if (settings.graphics.aspectRatio == AspectRatioMode.RATIO_4_3) {
            4f / 3f
        } else {
            viewportAspect.coerceIn(1.0f, 3.0f)
        }
        val liveFovDeg = GraphicsSettings.FIXED_FOV * (1f - adsBlend * (1f - activeWeapon.adsZoomFactor))
        val tanHalfV = tan(Math.toRadians(liveFovDeg.toDouble()) * 0.5).toFloat().coerceAtLeast(0.05f)
        val tanHalfH = (tanHalfV * activeAspect).coerceAtLeast(0.05f)

        val out = ArrayList<EspBox>(10)

        fun projectEnemy(
            id: String,
            name: String,
            team: TeamSide,
            hpVal: Int,
            weaponLabel: String,
            isBot: Boolean,
            ex: Float,
            ey: Float,
            ez: Float
        ) {
            val dx = ex - eyeX
            val dyMid = (ey + 0.90f) - eyeY
            val dz = ez - eyeZ
            val vzMid = dx * fwdX + dyMid * fwdY + dz * fwdZ
            if (vzMid <= 0.35f) return

            val dyFeet = ey - eyeY
            val dyHead = (ey + 1.78f) - eyeY
            val dyHeadCenter = (ey + 1.56f) - eyeY

            val vzFeet = (dx * fwdX + dyFeet * fwdY + dz * fwdZ).coerceAtLeast(0.25f)
            val vzHead = (dx * fwdX + dyHead * fwdY + dz * fwdZ).coerceAtLeast(0.25f)

            val vx = dx * rightX + dz * rightZ
            val vyFeet = dx * upX + dyFeet * upY + dz * upZ
            val vyHead = dx * upX + dyHead * upY + dz * upZ
            val vyHeadCenter = dx * upX + dyHeadCenter * upY + dz * upZ

            val centerX = 0.5f + (vx / (vzMid * tanHalfH)) * 0.5f
            val topFrac = 0.5f - (vyHead / (vzHead * tanHalfV)) * 0.5f
            val bottomFrac = 0.5f - (vyFeet / (vzFeet * tanHalfV)) * 0.5f
            val headYFrac = 0.5f - (vyHeadCenter / (vzHead * tanHalfV)) * 0.5f
            val halfW = ((0.40f / (vzMid * tanHalfH)) * 0.5f).coerceIn(0.014f, 0.38f)

            val leftFrac = centerX - halfW
            val rightFrac = centerX + halfW
            if (rightFrac < -0.15f || leftFrac > 1.15f || bottomFrac < -0.15f || topFrac > 1.15f) return

            val distM = sqrt(dx * dx + dyMid * dyMid + dz * dz).toInt().coerceAtLeast(1)
            out.add(
                EspBox(
                    id = id,
                    name = name,
                    team = team,
                    hp = hpVal.coerceIn(0, 100),
                    distanceMeters = distM,
                    weaponLabel = weaponLabel,
                    isBot = isBot,
                    hasLineOfSight = hasSight(ex, ey, ez),
                    leftFrac = leftFrac,
                    topFrac = topFrac,
                    rightFrac = rightFrac,
                    bottomFrac = bottomFrac,
                    headYFrac = headYFrac
                )
            )
        }

        for (b in bots) {
            if (!b.isDead && b.team != matchConfig.playerSide) {
                projectEnemy("bot_${b.id}", b.name, b.team, b.hp.toInt(), b.weapon.shortLabel, true, b.x, b.y, b.z)
            }
        }
        for (rp in remotes) {
            if (!rp.isDead && rp.team != matchConfig.playerSide) {
                projectEnemy(rp.userId, rp.displayName, rp.team, rp.hp, rp.weapon.shortLabel, false, rp.x, rp.y, rp.z)
            }
        }
        return out
    }
}
