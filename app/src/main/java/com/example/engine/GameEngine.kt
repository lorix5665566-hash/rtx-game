package com.example.engine

import android.content.Context
import com.example.audio.SoundEngine
import com.example.model.ArenaMapId
import com.example.model.BombState
import com.example.model.GameMode
import com.example.model.GameSettings
import com.example.model.HudSnapshot
import com.example.model.MatchConfig
import com.example.model.OutgoingHitEvent
import com.example.model.RadarBlip
import com.example.model.RemoteHumanPlayer
import com.example.model.TeamSide
import com.example.model.WeaponSlot
import com.example.model.WeaponType
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

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

    // Match, Warmup, Round, Economy & C4 Bomb state
    private var isWarmup: Boolean = true
    private var warmupTimer: Float = 30f
    @Volatile private var pendingSkipWarmup: Boolean = false
    private var hp: Float = 100f
    private var armor: Float = 0f
    private var hasDefuseKit: Boolean = false
    private var money: Int = 800
    private var isPlayerDead: Boolean = false
    private var tdmRespawnTimer: Float = 0f

    private var ctScore: Int = 0
    private var tScore: Int = 0
    private var roundNumber: Int = 1
    private var roundTimeSec: Float = 105f
    private var buyTimeRemaining: Float = 25f
    private var roundOverTimer: Float = 0f
    private var isRoundOver: Boolean = false
    private var isMatchFinished: Boolean = false
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
    private var bombCountdownSec: Float = 35f
    private var bombBeepTimer: Float = 0f
    private var plantBeepTimer: Float = 0f

    // Combat stats & HUD feedback
    private var score: Int = 0
    private var kills: Int = 0
    private var deaths: Int = 0
    private var headshots: Int = 0
    private var shotsFired: Int = 0
    private var shotsHit: Int = 0
    private var hitMarkerAlpha: Float = 0f
    private var isHeadshotHit: Boolean = false
    private var damageFlashAlpha: Float = 0f
    private var bannerText: String = ""
    private var bannerTimer: Float = 0f
    private var killFeedText: String = ""
    private var killFeedTimer: Float = 0f
    private var nextBotId: Int = 1

    val bots = mutableListOf<BotOperative>()

    // Real-time multiplayer remote human players (thread-safe bridge with Firestore)
    private val remoteLock = Any()
    private val remotePlayersMap = LinkedHashMap<String, RemoteHumanPlayer>()
    private val lastProcessedHitSeq = HashMap<String, Int>()
    @Volatile var lastOutgoingHit: OutgoingHitEvent? = null
        private set
    private var outgoingHitSeq: Int = 0

    private var fpsFrameCount: Int = 0
    private var fpsAccumTime: Float = 0f
    private var currentFps: Int = 60

    @Volatile
    var latestHud: HudSnapshot = HudSnapshot()
        private set

    fun getRemotePlayersSnapshot(): List<RemoteHumanPlayer> = synchronized(remoteLock) {
        remotePlayersMap.values.map { it.copy() }
    }

    /**
     * Called from Firestore live listener when remote human members in the lobby update their state.
     */
    fun applyRemoteStates(
        localUid: String,
        remoteStates: List<com.example.online.PlayerMatchState>
    ) {
        val incomingHits = ArrayList<Triple<String, com.example.online.PlayerMatchState, Float>>()
        synchronized(remoteLock) {
            val activeUids = HashSet<String>()
            for (st in remoteStates) {
                if (st.userId.isBlank() || st.userId == localUid) continue
                activeUids.add(st.userId)
                val side = if (st.team == "CT") TeamSide.CT else TeamSide.T
                val wep = WeaponType.entries.firstOrNull { it.name == st.weapon } ?: WeaponType.AK47
                val existing = remotePlayersMap[st.userId]
                if (existing == null) {
                    remotePlayersMap[st.userId] = RemoteHumanPlayer(
                        userId = st.userId,
                        shortId = st.shortId,
                        displayName = st.displayName,
                        team = side,
                        x = st.x.toFloat(),
                        y = st.y.toFloat(),
                        z = st.z.toFloat(),
                        targetX = st.x.toFloat(),
                        targetY = st.y.toFloat(),
                        targetZ = st.z.toFloat(),
                        yawDeg = st.yawDeg.toFloat(),
                        targetYawDeg = st.yawDeg.toFloat(),
                        hp = st.hp,
                        isDead = st.dead,
                        deathProgress = if (st.dead) 1f else 0f,
                        isMoving = st.moving,
                        isShooting = st.shooting,
                        weapon = wep,
                        kills = st.kills,
                        deaths = st.deaths
                    )
                    // Baseline hitSeq on first observe so old hits aren't replayed on join
                    lastProcessedHitSeq[st.userId] = st.hitSeq
                } else {
                    existing.targetX = st.x.toFloat()
                    existing.targetY = st.y.toFloat()
                    existing.targetZ = st.z.toFloat()
                    existing.targetYawDeg = st.yawDeg.toFloat()
                    existing.hp = st.hp
                    existing.isDead = st.dead
                    if (!st.dead) existing.deathProgress = 0f
                    existing.isMoving = st.moving
                    existing.isShooting = st.shooting
                    existing.weapon = wep
                    existing.kills = st.kills
                    existing.deaths = st.deaths

                    val prevSeq = lastProcessedHitSeq[st.userId] ?: 0
                    if (st.hitSeq > prevSeq) {
                        lastProcessedHitSeq[st.userId] = st.hitSeq
                        if (st.hitTargetUid == localUid && st.hitDamage > 0 && side != matchConfig.playerSide) {
                            incomingHits.add(Triple(st.displayName, st, st.hitDamage.toFloat()))
                        }
                    }
                }
            }
            remotePlayersMap.keys.retainAll(activeUids)
        }
        // Apply incoming human PvP damage outside lock
        for ((attackerName, st, dmg) in incomingHits) {
            if (!isPlayerDead && !isRoundOver && !isMatchFinished) {
                val side = if (st.team == "CT") TeamSide.CT else TeamSide.T
                applyPlayerDamage(dmg, side, killedByBomb = false, attackerLabel = attackerName)
            }
        }
    }

    private fun updateRemotePlayersInterpolation(dt: Float) {
        val posAlpha = (dt * 12f).coerceAtMost(1f)
        val rotAlpha = (dt * 14f).coerceAtMost(1f)
        synchronized(remoteLock) {
            for (rp in remotePlayersMap.values) {
                rp.x += (rp.targetX - rp.x) * posAlpha
                rp.y += (rp.targetY - rp.y) * posAlpha
                rp.z += (rp.targetZ - rp.z) * posAlpha
                var diff = (rp.targetYawDeg - rp.yawDeg) % 360f
                if (diff > 180f) diff -= 360f
                if (diff < -180f) diff += 360f
                rp.yawDeg += diff * rotAlpha
                if (rp.isDead) {
                    rp.deathProgress = (rp.deathProgress + dt * 2.2f).coerceAtMost(1f)
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

    fun skipWarmup() {
        pendingSkipWarmup = true
    }

    fun selectSlot(slot: WeaponSlot) { pendingSlotSwitch = slot }
    fun quickSwitchWeapon() { pendingQuickSwitch = true }
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
        }
    }
    fun toggleCrouch() { isCrouching = !isCrouching }

    fun update(rawDt: Float) {
        val dt = rawDt.coerceIn(0.001f, 0.05f)

        fpsFrameCount++
        fpsAccumTime += rawDt
        if (fpsAccumTime >= 0.45f) {
            currentFps = (fpsFrameCount / fpsAccumTime).toInt().coerceIn(1, 144)
            fpsFrameCount = 0
            fpsAccumTime = 0f
        }

        if (pendingReset) {
            pendingReset = false
            resetMatchInternal()
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

        // Update active C4 explosion animation & camera shake!
        if (explosion.active) {
            explosion.elapsedSec += dt
            if (explosion.elapsedSec >= explosion.durationSec) {
                explosion.active = false
            }
        }
        cameraShake = (cameraShake - dt * 1.65f).coerceAtLeast(0f)
        explosionFlashAlpha = (explosionFlashAlpha - dt * 1.15f).coerceAtLeast(0f)

        if (isMatchFinished) {
            muzzleFlashTimer = (muzzleFlashTimer - dt * 22f).coerceAtLeast(0f)
            publishHud()
            return
        }

        if (isRoundOver) {
            roundOverTimer -= dt
            // Keep dead bots falling smoothly during explosion / round transition!
            for (b in bots) {
                if (b.isDead) b.deathProgress = (b.deathProgress + dt * 2.2f).coerceAtMost(1f)
            }
            hitMarkerAlpha = (hitMarkerAlpha - dt * 3.5f).coerceAtLeast(0f)
            damageFlashAlpha = (damageFlashAlpha - dt * 2.4f).coerceAtLeast(0f)
            muzzleFlashTimer = (muzzleFlashTimer - dt * 22f).coerceAtLeast(0f)
            if (roundOverTimer <= 0f) {
                if (ctScore >= matchConfig.roundsToWin || tScore >= matchConfig.roundsToWin) {
                    finishMatch()
                } else {
                    roundNumber++
                    startNewRound(keepPlayerGear = !isPlayerDead)
                }
            }
            publishHud()
            return
        }

        if (buyTimeRemaining > 0f) {
            buyTimeRemaining = (buyTimeRemaining - dt).coerceAtLeast(0f)
        }
        if (equipTimer > 0f) {
            equipTimer = (equipTimer - dt).coerceAtLeast(0f)
        }

        processShopRequests()
        processWeaponSwitching()
        updatePlayerLookAndMovement(dt)
        updateWeaponsAndFiring(dt)
        updateBombInteractions(dt)
        updateBots(dt)
        if (matchConfig.isMultiplayer) {
            updateRemotePlayersInterpolation(dt)
        }

        if (isWarmup) {
            updateWarmupPhase(dt)
        } else if (matchConfig.mode == GameMode.BOMB_DEFUSAL) {
            updateBombAndRoundTimers(dt)
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

    private fun resetMatchInternal() {
        if (world.mapId != matchConfig.map) {
            world = ArenaWorld(appContext, matchConfig.map)
        }
        ctScore = 0
        tScore = 0
        roundNumber = 1
        score = 0
        kills = 0
        deaths = 0
        headshots = 0
        shotsFired = 0
        shotsHit = 0
        isMatchFinished = false
        isPlayerTeamWinner = false
        matchRecorded = false
        // Start every match with a 30-second CS2 Warmup ($16000, open shop, instant respawn, Skip button!)
        isWarmup = true
        warmupTimer = 30f
        pendingSkipWarmup = false
        money = 16000
        primaryWeapon = if (matchConfig.playerSide == TeamSide.CT) WeaponType.M4A1_S else WeaponType.AK47
        pistolWeapon = if (matchConfig.playerSide == TeamSide.CT) WeaponType.USP_S else WeaponType.GLOCK_18
        armor = 100f
        hasDefuseKit = matchConfig.playerSide == TeamSide.CT
        startNewRound(keepPlayerGear = true)
    }

    private fun endWarmupAndStartLiveMatch() {
        isWarmup = false
        warmupTimer = 0f
        pendingSkipWarmup = false
        ctScore = 0
        tScore = 0
        roundNumber = 1
        score = 0
        kills = 0
        deaths = 0
        headshots = 0
        shotsFired = 0
        shotsHit = 0
        money = if (matchConfig.mode == GameMode.TEAM_DEATHMATCH) 16000 else 800
        primaryWeapon = if (matchConfig.mode == GameMode.TEAM_DEATHMATCH) {
            if (matchConfig.playerSide == TeamSide.CT) WeaponType.M4A1_S else WeaponType.AK47
        } else null
        pistolWeapon = if (matchConfig.playerSide == TeamSide.CT) WeaponType.USP_S else WeaponType.GLOCK_18
        armor = if (matchConfig.mode == GameMode.TEAM_DEATHMATCH) 100f else 0f
        hasDefuseKit = false
        soundEngine.playBombPlanted()
        startNewRound(keepPlayerGear = false)
        bannerText = "МАТЧ НАЧАЛСЯ • РАУНД 1 • ${matchConfig.map.titleRu}"
        bannerTimer = 3.0f
    }

    private fun updateWarmupPhase(dt: Float) {
        warmupTimer = (warmupTimer - dt).coerceAtLeast(0f)
        if (pendingSkipWarmup || warmupTimer <= 0f) {
            endWarmupAndStartLiveMatch()
            return
        }
        // Fast 1.8s player respawn during warmup with $16000 & full armor
        if (isPlayerDead) {
            tdmRespawnTimer -= dt
            if (tdmRespawnTimer <= 0f) {
                hp = 100f
                armor = 100f
                money = 16000
                isPlayerDead = false
                val (sx, sy, sz) = world.getSpawnPoint(matchConfig.playerSide, rng.nextInt(5))
                playerX = sx; playerY = sy; playerFloorY = sy; playerZ = sz
                playerYawRad = atan2(sx.toDouble(), sz.toDouble()).toFloat()
                playerPitchRad = 0f
                refillAmmoForOwnedWeapons()
                switchWeaponInternal(primaryWeapon ?: pistolWeapon)
            }
        }
    }

    private fun startNewRound(keepPlayerGear: Boolean) {
        isRoundOver = false
        roundOverTimer = 0f
        roundTimeSec = if (matchConfig.mode == GameMode.BOMB_DEFUSAL) 105f else 300f
        buyTimeRemaining = if (isWarmup || matchConfig.mode == GameMode.TEAM_DEATHMATCH) 999f else 25f
        explosion.active = false
        explosionFlashAlpha = 0f
        cameraShake = 0f

        bombState = BombState.CARRIED
        plantedSiteLabel = ""
        bombCountdownSec = 35f
        bombPlantProgress = 0f
        isBombActionHeld = false

        hp = 100f
        isPlayerDead = false
        tdmRespawnTimer = 0f
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

        // Spawn CT & T Bots with coordinated round bombsite plan (A or B)
        bots.clear()
        val roundPrimarySite = rng.nextInt(world.bombsites.size.coerceAtLeast(1))
        if (allowAnyBots) {
            for (i in 0 until allyBotsCount) {
                bots.add(
                    createBot(
                        side = matchConfig.playerSide,
                        spawnIdx = i + 1,
                        assignBomb = giveBombToAllyBot && i == 0,
                        preferredBombsiteIdx = if (i < 2) roundPrimarySite else (1 - roundPrimarySite).coerceIn(0, world.bombsites.lastIndex)
                    )
                )
            }
            for (i in 0 until enemyBotsCount) {
                bots.add(
                    createBot(
                        side = enemySide,
                        spawnIdx = i,
                        assignBomb = giveBombToEnemyBot && i == 0,
                        preferredBombsiteIdx = if (i < 3) roundPrimarySite else (1 - roundPrimarySite).coerceIn(0, world.bombsites.lastIndex)
                    )
                )
            }
        }

        bannerText = when {
            isWarmup -> "РАЗМИНКА • $16 000 • СВОБОДНАЯ ПОКУПКА ОРУЖИЯ"
            matchConfig.mode == GameMode.BOMB_DEFUSAL -> "РАУНД $roundNumber • ${matchConfig.map.titleRu} • ${matchConfig.playerSide.shortRu}"
            else -> "БОЙ НАСМЕРТЬ • ${matchConfig.map.titleRu}"
        }
        bannerTimer = 2.8f
        publishHud()
    }

    private fun createBot(
        side: TeamSide,
        spawnIdx: Int,
        assignBomb: Boolean,
        preferredBombsiteIdx: Int = 0
    ): BotOperative {
        val (sx, sy, sz) = world.getSpawnPoint(side, spawnIdx)
        val botWeapon = if (roundNumber == 1 && matchConfig.mode == GameMode.BOMB_DEFUSAL) {
            if (side == TeamSide.CT) WeaponType.USP_S else WeaponType.GLOCK_18
        } else {
            when (rng.nextInt(4)) {
                0 -> if (side == TeamSide.CT) WeaponType.M4A1_S else WeaponType.AK47
                1 -> WeaponType.P90
                2 -> if (side == TeamSide.CT) WeaponType.M4A1_S else WeaponType.AK47
                else -> WeaponType.MP9
            }
        }
        val routes = if (side == TeamSide.T) world.tAttackRoutes else world.ctDefenseRoutes
        val chosenRouteIdx = if (side == TeamSide.T) {
            val matching = world.tRouteBombsiteIdx.indices.filter {
                world.tRouteBombsiteIdx[it] == preferredBombsiteIdx
            }
            if (matching.isNotEmpty()) matching[rng.nextInt(matching.size)] else rng.nextInt(routes.size)
        } else {
            spawnIdx % routes.size
        }
        val resolvedBombsiteIdx = if (side == TeamSide.T) {
            world.tRouteBombsiteIdx.getOrElse(chosenRouteIdx) { preferredBombsiteIdx }
                .coerceIn(0, world.bombsites.lastIndex)
        } else {
            preferredBombsiteIdx.coerceIn(0, world.bombsites.lastIndex)
        }
        val yaw = Math.toDegrees(atan2(-sx.toDouble(), -sz.toDouble())).toFloat()
        return BotOperative(
            id = nextBotId++,
            team = side,
            weapon = botWeapon,
            x = sx,
            y = sy,
            z = sz,
            yawDeg = yaw,
            visualYawDeg = yaw,
            aimYawDeg = yaw,
            hp = 100f,
            maxHp = 100f,
            hasBomb = assignBomb,
            targetSiteIndex = chosenRouteIdx,
            targetBombsiteIdx = resolvedBombsiteIdx,
            waypointIndex = 0,
            fireCooldown = 0.25f + rng.nextFloat() * 0.25f,
            repathTimer = rng.nextFloat() * 0.35f,
            lastProgressX = sx,
            lastProgressZ = sz,
            unstuckDir = if (rng.nextBoolean()) 1f else -1f,
            reactionDelaySec = 0.30f + rng.nextFloat() * 0.12f,
            burstShotsLeft = 2 + rng.nextInt(3),
            alertYawDeg = yaw,
            aimSwaySeed = rng.nextFloat() * 100f
        )
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

        val mx = moveInputX.coerceIn(-1f, 1f)
        val my = moveInputY.coerceIn(-1f, 1f)
        val mag = sqrt(mx * mx + my * my)
        val nx = if (mag > 1f) mx / mag else mx
        val ny = if (mag > 1f) my / mag else my

        // Smooth strafe banking roll for natural CS2 movement feel
        val targetStrafeRoll = (-nx * 2.8f * (1f - adsBlend * 0.75f))
        strafeRoll += (targetStrafeRoll - strafeRoll) * (dt * 10f).coerceAtMost(1f)

        val targetX = playerX + (fwdX * ny + rightX * nx) * baseSpeed * dt
        val targetZ = playerZ + (fwdZ * ny + rightZ * nx) * baseSpeed * dt
        val (resolvedX, floorY, resolvedZ) = world.resolveMovement(playerX, playerFloorY, playerZ, targetX, targetZ, 0.36f)
        playerX = resolvedX
        playerZ = resolvedZ
        playerFloorY = floorY

        if (pendingJump) {
            pendingJump = false
            if (playerY <= playerFloorY + 0.08f) {
                playerVelY = 4.9f
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
        if (isPlayerDead) {
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
        if (isWarmup || matchConfig.mode != GameMode.BOMB_DEFUSAL || isPlayerDead) {
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
            distToBomb <= 3.0f &&
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
        soundEngine.playBombPlanted()
        bannerText = "БОМБА C4 ЗАЛОЖЕНА НА ТОЧКЕ $siteLabel!"
        bannerTimer = 3.0f
    }

    /**
     * FULL CS2 C4 DETONATION:
     * Triggers massive 3D fireball + shockwave + smoke + camera shake + blinding flash + LETHAL 26m blast radius!
     */
    private fun detonatePlantedBomb() {
        bombState = BombState.EXPLODED
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
                applyPlayerDamage(pDmg, TeamSide.T, killedByBomb = true)
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
            roundTimeSec -= dt
            if (roundTimeSec <= 0f) {
                endRound(TeamSide.CT, "ВРЕМЯ ВЫШЛО • ПОБЕДА СПЕЦНАЗА", delaySec = 3.6f)
                return
            }
        }

        val ctAlive = countAlive(TeamSide.CT)
        val tAlive = countAlive(TeamSide.T)
        if (ctAlive == 0 && bombState != BombState.DEFUSED) {
            endRound(TeamSide.T, "СПЕЦНАЗ УНИЧТОЖЕН • ПОБЕДА ТЕРРОРИСТОВ", delaySec = 3.6f)
        } else if (tAlive == 0 && bombState == BombState.CARRIED) {
            endRound(TeamSide.CT, "ТЕРРОРИСТЫ УНИЧТОЖЕНЫ • ПОБЕДА СПЕЦНАЗА", delaySec = 3.6f)
        }
    }

    private fun endRound(winner: TeamSide, reason: String, delaySec: Float) {
        if (isRoundOver) return
        isRoundOver = true
        roundOverTimer = delaySec
        if (winner == TeamSide.CT) ctScore++ else tScore++
        val playerWon = winner == matchConfig.playerSide
        money = (money + if (playerWon) 3250 else 2150).coerceAtMost(16000)
        bannerText = "$reason (CT $ctScore : $tScore T)"
        bannerTimer = delaySec
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
        isAdsToggle = false
        soundEngine.playReload()
    }

    private fun performKnifeSlash() {
        if (isEquipping) return
        fireTimer = WeaponType.KNIFE.fireIntervalSec
        knifeSwingProgress = 0.01f
        soundEngine.playWeaponShot(WeaponType.KNIFE)

        val camY = playerY + eyeHeight
        val cosP = cos(playerPitchRad)
        val dirX = -sin(playerYawRad) * cosP
        val dirY = sin(playerPitchRad)
        val dirZ = -cos(playerYawRad) * cosP

        var bestBot: BotOperative? = null
        var bestDist = 2.6f
        for (b in bots) {
            if (b.isDead || b.team == matchConfig.playerSide) continue
            val t = intersectRaySphere(playerX, camY, playerZ, dirX, dirY, dirZ, b.x, b.y + 1.15f, b.z, 0.50f)
            if (t < bestDist) {
                bestDist = t; bestBot = b
            }
        }
        if (bestBot != null) {
            applyBotDamage(bestBot, WeaponType.KNIFE.bodyDamage, isHeadshot = false, killerIsPlayer = true, weaponUsed = WeaponType.KNIFE)
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

        val spread = (if (isAdsToggle) w.adsSpread else w.hipSpread) + moveSpeedRatio * 0.010f
        dirX += (rng.nextFloat() * 2f - 1f) * spread
        dirY += (rng.nextFloat() * 2f - 1f) * spread
        dirZ += (rng.nextFloat() * 2f - 1f) * spread
        val inv = 1f / sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ).coerceAtLeast(0.0001f)
        dirX *= inv; dirY *= inv; dirZ *= inv

        val wallDist = world.raycastWalls(camX, camY, camZ, dirX, dirY, dirZ, 110f)

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

        if (hitBot != null) {
            shotsHit++
            hitMarkerAlpha = 1.0f
            isHeadshotHit = headshot
            val dmg = if (headshot) w.headDamage else w.bodyDamage
            applyBotDamage(hitBot, dmg, headshot, killerIsPlayer = true, weaponUsed = w)
            if (headshot) soundEngine.playHeadshot() else soundEngine.playHitMarker()
            if (headshot && settings.audio.hapticsEnabled) onHapticPulse(true)
        } else if (hitRemote != null) {
            shotsHit++
            hitMarkerAlpha = 1.0f
            isHeadshotHit = headshot
            val dmg = (if (headshot) w.headDamage else w.bodyDamage).toInt().coerceIn(1, 200)
            outgoingHitSeq++
            lastOutgoingHit = OutgoingHitEvent(
                seq = outgoingHitSeq,
                targetUid = hitRemote.userId,
                damage = dmg,
                headshot = headshot,
                weaponName = w.shortLabel
            )
            if (dmg >= hitRemote.hp && !isWarmup) {
                kills++
                if (headshot) headshots++
                money = (money + w.killReward).coerceAtMost(16000)
                score += if (headshot) 250 else 150
                val hsTag = if (headshot) " [ГОЛОВА]" else ""
                killFeedText = "ВЫ [${w.shortLabel}]$hsTag → ${hitRemote.displayName}"
                killFeedTimer = 3.2f
            }
            if (headshot) soundEngine.playHeadshot() else soundEngine.playHitMarker()
            if (headshot && settings.audio.hapticsEnabled) onHapticPulse(true)
        }

        if (nextMag == 0 && (reserveAmmoMap[w] ?: 0) > 0) beginReload()
    }

    private fun applyBotDamage(
        target: BotOperative,
        dmg: Float,
        isHeadshot: Boolean,
        killerIsPlayer: Boolean,
        weaponUsed: WeaponType
    ) {
        if (target.isDead) return
        target.hp -= dmg
        target.hitFlinch = 1.0f
        // If shot from flank/behind, alert the bot to turn smoothly toward the threat (no instant 180 snap!)
        if (killerIsPlayer && target.hp > 0f) {
            target.alertYawDeg = Math.toDegrees(atan2((playerX - target.x).toDouble(), (playerZ - target.z).toDouble())).toFloat()
            target.alertTimer = 2.4f
        }
        if (target.hp <= 0f) {
            target.hp = 0f
            target.isDead = true
            target.deathProgress = 0.01f
            target.plantProgress = 0f
            target.defuseProgress = 0f
            if (killerIsPlayer) {
                if (!isWarmup) {
                    kills++
                    if (isHeadshot) headshots++
                    money = (money + weaponUsed.killReward).coerceAtMost(16000)
                    score += if (isHeadshot) 250 else 150
                }
                val hsTag = if (isHeadshot) " [ГОЛОВА]" else ""
                killFeedText = "ВЫ [${weaponUsed.shortLabel}]$hsTag → ${target.team.shortRu}"
                killFeedTimer = 3.2f
            } else {
                val killerTeam = if (target.team == TeamSide.CT) TeamSide.T else TeamSide.CT
                killFeedText = "${killerTeam.shortRu} → ${target.team.shortRu}"
                killFeedTimer = 2.6f
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
                b.deathProgress = (b.deathProgress + dt * 2.2f).coerceAtMost(1f)
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

            val prevX = b.x
            val prevZ = b.z
            val moveSpeed = 3.75f

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
                            bombState = BombState.DEFUSED
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
                    if (b.burstShotsLeft <= 0) {
                        // Brief human recoil-reset pause between 2-4 shot bursts
                        b.burstShotsLeft = 2 + rng.nextInt(3)
                        b.burstPauseTimer = 0.44f + rng.nextFloat() * 0.28f
                        b.fireCooldown = 0.18f
                    } else {
                        b.fireCooldown = 0.16f + rng.nextFloat() * 0.08f
                    }

                    // Calibrated Medium Human Accuracy (~45% at medium range, never 100%)
                    val distBaseAcc = (0.52f - (bestTargetDist - 8f).coerceAtLeast(0f) * 0.0085f).coerceIn(0.16f, 0.56f)
                    val targetMoveMult = if (targetMoving) 0.84f else 1.0f
                    val botStrafeMult = if (abs(b.strafeDir) > 0.1f) 0.88f else 1.0f
                    val aimAlignMult = (1f - (aimErrorDeg / 22f)).coerceIn(0.55f, 1.0f)
                    val hitChance = (distBaseAcc * targetMoveMult * botStrafeMult * aimAlignMult).coerceIn(0.12f, 0.58f)

                    if (rng.nextFloat() < hitChance) {
                        val isHs = rng.nextFloat() < 0.12f
                        val baseDmg = when (b.weapon) {
                            WeaponType.AK47 -> 14f
                            WeaponType.M4A1_S -> 12f
                            else -> 10f
                        }
                        val finalDmg = if (isHs) baseDmg * 1.55f else baseDmg
                        if (targetIsPlayer) {
                            applyPlayerDamage(finalDmg, b.team, killedByBomb = false)
                        } else if (targetBot != null) {
                            applyBotDamage(targetBot, finalDmg * 1.35f, isHeadshot = isHs, killerIsPlayer = false, weaponUsed = b.weapon)
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
                    // Reached current waypoint/site -> advance patrol/route or hold angle
                    val moveYaw = if (b.alertTimer > 0f) b.alertYawDeg else b.visualYawDeg
                    faceX = sin(Math.toRadians(moveYaw.toDouble())).toFloat()
                    faceZ = cos(Math.toRadians(moveYaw.toDouble())).toFloat()
                    b.aimYawDeg = moveAngleTowardDeg(b.aimYawDeg, moveYaw, 180f * dt)

                    if (bombState != BombState.PLANTED) {
                        val routes = if (b.team == TeamSide.T) world.tAttackRoutes else world.ctDefenseRoutes
                        val route = routes[b.targetSiteIndex % routes.size]
                        if (b.waypointIndex < route.size - 1) {
                            b.waypointIndex++
                            b.navPath = emptyList()
                            b.repathTimer = 0f
                        } else if (matchConfig.mode == GameMode.TEAM_DEATHMATCH || isWarmup) {
                            b.targetSiteIndex = rng.nextInt(routes.size)
                            b.waypointIndex = 0
                            b.navPath = emptyList()
                            b.repathTimer = 0f
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
        attackerLabel: String? = null
    ) {
        damageFlashAlpha = if (killedByBomb) 1.0f else 0.85f
        if (!killedByBomb) soundEngine.playDamage()
        if (settings.audio.hapticsEnabled) onHapticPulse(true)

        val absorbed = minOf(armor, amount * 0.55f)
        armor -= absorbed
        hp -= (amount - absorbed * 0.5f)
        if (hp <= 0f) {
            hp = 0f
            isPlayerDead = true
            isAdsToggle = false
            isFiring = false
            isBombActionHeld = false
            if (!isWarmup) deaths++
            killFeedText = if (killedByBomb) {
                "ВЫ ПОГИБЛИ ОТ ВЗРЫВА C4!"
            } else {
                "${attackerLabel ?: attackerSide.shortRu} → ВЫ"
            }
            killFeedTimer = 3.6f

            if (!isWarmup && playerHasBomb && bombState == BombState.CARRIED) {
                playerHasBomb = false
                bots.firstOrNull { !it.isDead && it.team == TeamSide.T }?.hasBomb = true
            }
            if (isWarmup) {
                tdmRespawnTimer = 1.8f
            } else if (matchConfig.mode == GameMode.TEAM_DEATHMATCH) {
                tdmRespawnTimer = 2.5f
            }
        }
    }

    private fun updateTeamDeathmatchRules(dt: Float) {
        roundTimeSec -= dt
        if (isPlayerDead) {
            tdmRespawnTimer -= dt
            if (tdmRespawnTimer <= 0f) {
                hp = 100f
                armor = 100f
                isPlayerDead = false
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
            !isPlayerDead &&
            matchConfig.playerSide == TeamSide.T &&
            playerHasBomb &&
            bombState == BombState.CARRIED &&
            inSite != null

        val canDefuse = matchConfig.mode == GameMode.BOMB_DEFUSAL &&
            !isPlayerDead &&
            matchConfig.playerSide == TeamSide.CT &&
            bombState == BombState.PLANTED &&
            distBomb <= 3.0f

        val remotes = if (matchConfig.isMultiplayer) getRemotePlayersSnapshot() else emptyList()
        val blips = ArrayList<RadarBlip>(bots.size + remotes.size + 1)
        for (b in bots) {
            if (!b.isDead) {
                blips.add(RadarBlip(b.x - playerX, b.z - playerZ, isAlly = b.team == matchConfig.playerSide))
            }
        }
        for (rp in remotes) {
            if (!rp.isDead) {
                blips.add(RadarBlip(rp.x - playerX, rp.z - playerZ, isAlly = rp.team == matchConfig.playerSide))
            }
        }
        if (bombState == BombState.PLANTED) {
            blips.add(RadarBlip(plantedBombX - playerX, plantedBombZ - playerZ, isAlly = false, isBomb = true))
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
            isAds = isAdsToggle,
            adsBlend = adsBlend,
            isCrouching = isCrouching,
            isPlayerDead = isPlayerDead,
            ctScore = ctScore,
            tScore = tScore,
            roundsToWin = matchConfig.roundsToWin,
            roundNumber = roundNumber,
            roundTimeSec = roundTimeSec.coerceAtLeast(0f),
            isWarmup = isWarmup,
            warmupTimeSec = warmupTimer.coerceAtLeast(0f),
            ctAlive = countAlive(TeamSide.CT),
            tAlive = countAlive(TeamSide.T),
            bombState = bombState,
            bombSiteLabel = plantedSiteLabel,
            bombCountdownSec = bombCountdownSec.coerceAtLeast(0f),
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
            damageFlashAlpha = damageFlashAlpha,
            crosshairSpread = (recoilKick * 0.55f + moveSpeedRatio * 0.35f).coerceIn(0f, 1.4f),
            isMatchFinished = isMatchFinished,
            isPlayerTeamWinner = isPlayerTeamWinner,
            bannerText = bannerText,
            killFeedText = killFeedText,
            radarBlips = blips,
            playerYawDeg = Math.toDegrees(playerYawRad.toDouble()).toFloat(),
            isMultiplayer = matchConfig.isMultiplayer,
            onlinePlayersCount = 1 + remotes.size
        )
    }
}
