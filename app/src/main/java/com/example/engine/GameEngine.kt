package com.example.engine

import com.example.audio.SoundEngine
import com.example.model.ArenaMapId
import com.example.model.BombState
import com.example.model.GameMode
import com.example.model.GameSettings
import com.example.model.HudSnapshot
import com.example.model.MatchConfig
import com.example.model.RadarBlip
import com.example.model.TeamSide
import com.example.model.WeaponSlot
import com.example.model.WeaponType
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class TracerEffect(
    val x: Float,
    val y: Float,
    val z: Float,
    val isEnemy: Boolean,
    var life: Float
)

class GameEngine(
    private val soundEngine: SoundEngine,
    private val onHapticPulse: (isHeavy: Boolean) -> Unit,
    private val onMatchFinished: (score: Int, kills: Int, headshots: Int) -> Unit
) {
    @Volatile
    var world: ArenaWorld = ArenaWorld(ArenaMapId.MIRAGE)
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

    // Input state (UI thread -> GL thread)
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

    // Player physical state (1:1 human scale)
    var playerX: Float = 0f
    var playerZ: Float = 28f
    var playerY: Float = 0f
    var playerVelY: Float = 0f
    var eyeHeight: Float = 1.65f
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

    // Viewmodel animation & microsecond fire flash state
    var adsBlend: Float = 0f
    var walkBobPhase: Float = 0f
    var moveSpeedRatio: Float = 0f
    var recoilKick: Float = 0f
    var muzzleFlashTimer: Float = 0f
    var muzzleFlashRoll: Float = 0f
    var boltProgress: Float = 0f
    var knifeSwingProgress: Float = 0f
    var bombPlantProgress: Float = 0f
    var timeSec: Float = 0f

    private var isReloading: Boolean = false
    private var reloadTimer: Float = 0f
    private var fireTimer: Float = 0f

    // Match, Round, Economy & C4 Bomb state
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
    private var buyTimeRemaining: Float = 20f
    private var roundOverTimer: Float = 0f
    private var isRoundOver: Boolean = false
    private var isMatchFinished: Boolean = false
    private var isPlayerTeamWinner: Boolean = false
    private var matchRecorded: Boolean = false

    @Volatile var bombState: BombState = BombState.CARRIED
        private set
    @Volatile var plantedBombX: Float = 0f
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
    val impacts = mutableListOf<TracerEffect>()

    private var fpsFrameCount: Int = 0
    private var fpsAccumTime: Float = 0f
    private var currentFps: Int = 60

    @Volatile
    var latestHud: HudSnapshot = HudSnapshot()
        private set

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

    fun selectSlot(slot: WeaponSlot) {
        pendingSlotSwitch = slot
    }

    fun quickSwitchWeapon() {
        pendingQuickSwitch = true
    }

    fun buyWeapon(weapon: WeaponType) {
        pendingBuyWeapon = weapon
    }

    fun buyArmor() {
        pendingBuyArmor = true
    }

    fun buyDefuseKit() {
        pendingBuyKit = true
    }

    fun addLookDelta(dxPixels: Float, dyPixels: Float) {
        synchronized(lookLock) {
            pendingLookDx += dxPixels
            pendingLookDy += dyPixels
        }
    }

    fun triggerJump() { pendingJump = true }
    fun triggerReload() { pendingReload = true }
    fun toggleAds() {
        if (activeWeapon != WeaponType.KNIFE && activeWeapon != WeaponType.C4_BOMB) {
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

        if (isMatchFinished) {
            muzzleFlashTimer = (muzzleFlashTimer - dt * 22f).coerceAtLeast(0f)
            recoilKick = (recoilKick - dt * 6f).coerceAtLeast(0f)
            updateDeadBodies(dt)
            publishHud()
            return
        }

        // Process pending Buy Menu purchases
        processPurchases()

        // Process round transition
        if (isRoundOver) {
            roundOverTimer -= dt
            muzzleFlashTimer = (muzzleFlashTimer - dt * 22f).coerceAtLeast(0f)
            updateDeadBodies(dt)
            if (roundOverTimer <= 0f) {
                val target = matchConfig.roundsToWin
                if (ctScore >= target || tScore >= target) {
                    isMatchFinished = true
                    isPlayerTeamWinner = (matchConfig.playerSide == TeamSide.CT && ctScore >= target) ||
                        (matchConfig.playerSide == TeamSide.T && tScore >= target)
                    bannerText = if (isPlayerTeamWinner) "ПОБЕДА В МАТЧЕ!" else "ПОРАЖЕНИЕ В МАТЧЕ"
                    recordMatchOnce()
                } else {
                    roundNumber++
                    startRoundInternal(resetEconomy = false)
                }
            }
            publishHud()
            return
        }

        // Handle slot swipe switches
        val reqSlot = pendingSlotSwitch
        if (reqSlot != null) {
            pendingSlotSwitch = null
            switchToSlotInternal(reqSlot)
        } else if (pendingQuickSwitch) {
            pendingQuickSwitch = false
            val candidate = if (lastWeapon != activeWeapon && ownsWeapon(lastWeapon)) {
                lastWeapon
            } else {
                primaryWeapon ?: if (activeWeapon != pistolWeapon) pistolWeapon else WeaponType.KNIFE
            }
            switchWeaponInternal(candidate)
        }

        // Update buy time window
        if (buyTimeRemaining > 0f) {
            buyTimeRemaining = (buyTimeRemaining - dt).coerceAtLeast(0f)
        }

        // Player camera look (100% raw manual skill — NO aim assist!)
        var dx = 0f
        var dy = 0f
        synchronized(lookLock) {
            dx = pendingLookDx
            dy = pendingLookDy
            pendingLookDx = 0f
            pendingLookDy = 0f
        }
        val sens = settings.sensitivity
        val scopeFactor = if (isAdsToggle) {
            if (activeWeapon.hasSniperScope) sens.adsSensitivity * 0.44f else sens.adsSensitivity
        } else 1f
        val baseSens = sens.lookSensitivity * scopeFactor * 0.0036f
        playerYawRad -= dx * baseSens * sens.xAxisMultiplier
        val ySign = if (sens.invertY) 1f else -1f
        playerPitchRad = (playerPitchRad + dy * baseSens * sens.yAxisMultiplier * ySign)
            .coerceIn(-1.24f, 1.24f)

        // Update Player Movement, Combat, Bomb Plant/Defuse
        if (!isPlayerDead) {
            updatePlayerMovement(dt)
            updatePlayerCombat(dt)
            updatePlayerBombActions(dt)
        } else {
            isFiring = false
            isAdsToggle = false
            adsBlend = 0f
            muzzleFlashTimer = (muzzleFlashTimer - dt * 22f).coerceAtLeast(0f)
            if (matchConfig.mode == GameMode.TEAM_DEATHMATCH) {
                tdmRespawnTimer -= dt
                if (tdmRespawnTimer <= 0f) {
                    respawnPlayerTdm()
                }
            }
        }

        // Update Tactical Bots (CT & T teams!)
        updateBots(dt)

        // Update Planted C4 Countdown & Round Timers
        if (matchConfig.mode == GameMode.BOMB_DEFUSAL) {
            updateBombAndRoundTimers(dt)
        }

        // Decay impact sparks
        val iter = impacts.iterator()
        while (iter.hasNext()) {
            val fx = iter.next()
            fx.life -= dt
            if (fx.life <= 0f) iter.remove()
        }

        publishHud()
    }

    private fun processPurchases() {
        val canShop = isBuyAllowed()
        val buyW = pendingBuyWeapon
        if (buyW != null) {
            pendingBuyWeapon = null
            val cost = if (matchConfig.mode == GameMode.TEAM_DEATHMATCH) 0 else buyW.price
            if (canShop && money >= cost) {
                money -= cost
                when (buyW.slot) {
                    WeaponSlot.PRIMARY -> primaryWeapon = buyW
                    WeaponSlot.PISTOL -> pistolWeapon = buyW
                    else -> Unit
                }
                magAmmoMap[buyW] = buyW.magSize
                reserveAmmoMap[buyW] = buyW.initialReserve
                switchWeaponInternal(buyW)
                soundEngine.playWeaponSwitch()
            }
        }
        if (pendingBuyArmor) {
            pendingBuyArmor = false
            val cost = if (matchConfig.mode == GameMode.TEAM_DEATHMATCH) 0 else 1000
            if (canShop && money >= cost && armor < 100f) {
                money -= cost
                armor = 100f
                soundEngine.playWeaponSwitch()
            }
        }
        if (pendingBuyKit) {
            pendingBuyKit = false
            val cost = 400
            if (canShop && matchConfig.playerSide == TeamSide.CT && !hasDefuseKit && money >= cost) {
                money -= cost
                hasDefuseKit = true
                soundEngine.playWeaponSwitch()
            }
        }
    }

    private fun isBuyAllowed(): Boolean {
        if (isPlayerDead || isRoundOver || isMatchFinished) return false
        if (matchConfig.mode == GameMode.TEAM_DEATHMATCH) return true
        return buyTimeRemaining > 0f
    }

    private fun ownsWeapon(w: WeaponType): Boolean = when (w.slot) {
        WeaponSlot.KNIFE -> true
        WeaponSlot.BOMB -> playerHasBomb && matchConfig.mode == GameMode.BOMB_DEFUSAL
        WeaponSlot.PISTOL -> w == pistolWeapon
        WeaponSlot.PRIMARY -> w == primaryWeapon
    }

    private fun switchToSlotInternal(slot: WeaponSlot) {
        val target = when (slot) {
            WeaponSlot.KNIFE -> WeaponType.KNIFE
            WeaponSlot.PISTOL -> pistolWeapon
            WeaponSlot.PRIMARY -> primaryWeapon ?: return
            WeaponSlot.BOMB -> if (playerHasBomb && matchConfig.mode == GameMode.BOMB_DEFUSAL) WeaponType.C4_BOMB else return
        }
        switchWeaponInternal(target)
    }

    private fun switchWeaponInternal(target: WeaponType) {
        if (target == activeWeapon) return
        lastWeapon = activeWeapon
        activeWeapon = target
        isReloading = false
        reloadTimer = 0f
        isAdsToggle = false
        adsBlend = 0f
        bombPlantProgress = 0f
        fireTimer = 0.20f
        recoilKick = 0.20f
        soundEngine.playWeaponSwitch()
    }

    private fun updatePlayerMovement(dt: Float) {
        val forwardX = -sin(playerYawRad)
        val forwardZ = -cos(playerYawRad)
        val rightX = cos(playerYawRad)
        val rightZ = -sin(playerYawRad)

        val moveMag = sqrt(moveInputX * moveInputX + moveInputY * moveInputY).coerceAtMost(1f)
        val maxSpeed = when {
            bombPlantProgress > 0.02f -> 0.6f
            isCrouching -> 2.8f
            isAdsToggle -> 3.0f
            else -> 5.7f * activeWeapon.moveSpeedMult
        }
        val wishX = (rightX * moveInputX + forwardX * moveInputY) * maxSpeed
        val wishZ = (rightZ * moveInputX + forwardZ * moveInputY) * maxSpeed

        val (nx, nz) = world.resolveHorizontalCollision(playerX + wishX * dt, playerZ + wishZ * dt, 0.46f)
        playerX = nx
        playerZ = nz

        if (pendingJump) {
            pendingJump = false
            if (playerY <= 0.01f) {
                isCrouching = false
                playerVelY = 5.3f
            }
        }
        playerVelY -= 14.5f * dt
        playerY += playerVelY * dt
        if (playerY < 0f) {
            playerY = 0f
            playerVelY = 0f
        }

        val targetEye = if (isCrouching) 1.12f else 1.65f
        eyeHeight += (targetEye - eyeHeight) * (dt * 11f).coerceAtMost(1f)

        moveSpeedRatio += (moveMag - moveSpeedRatio) * (dt * 9f).coerceAtMost(1f)
        if (moveMag > 0.08f && playerY <= 0.02f) {
            walkBobPhase += dt * (if (isCrouching) 6.5f else 10.4f) * moveMag
        }
    }

    private fun updatePlayerCombat(dt: Float) {
        val adsTarget = if (isAdsToggle && !isReloading && activeWeapon != WeaponType.KNIFE && activeWeapon != WeaponType.C4_BOMB) 1f else 0f
        adsBlend += (adsTarget - adsBlend) * (dt * 14f).coerceAtMost(1f)
        recoilKick = (recoilKick - dt * 8.2f).coerceAtLeast(0f)
        // Ultra-crisp ~45ms realistic fire muzzle flash decay!
        muzzleFlashTimer = (muzzleFlashTimer - dt * 22f).coerceAtLeast(0f)
        hitMarkerAlpha = (hitMarkerAlpha - dt * 4.2f).coerceAtLeast(0f)
        damageFlashAlpha = (damageFlashAlpha - dt * 2.6f).coerceAtLeast(0f)

        if (knifeSwingProgress > 0f) {
            knifeSwingProgress = (knifeSwingProgress + dt * 4.5f)
            if (knifeSwingProgress >= 1f) knifeSwingProgress = 0f
        }

        if (pendingReload) {
            pendingReload = false
            beginReload()
        }

        val curWeapon = activeWeapon
        if (isReloading) {
            reloadTimer += dt
            if (reloadTimer >= curWeapon.reloadSec) {
                val curMag = magAmmoMap[curWeapon] ?: 0
                val curRes = reserveAmmoMap[curWeapon] ?: 0
                val needed = curWeapon.magSize - curMag
                val taken = minOf(needed, curRes)
                magAmmoMap[curWeapon] = curMag + taken
                reserveAmmoMap[curWeapon] = curRes - taken
                isReloading = false
                reloadTimer = 0f
            }
        }

        fireTimer = (fireTimer - dt).coerceAtLeast(0f)
        boltProgress = if (curWeapon == WeaponType.AWP && fireTimer > 0f) {
            (1f - fireTimer / curWeapon.fireIntervalSec).coerceIn(0f, 1f)
        } else 0f

        val shouldFire = if (curWeapon.isAutomatic) {
            isFiring
        } else {
            isFiring && !lastTriggerPressed
        }
        lastTriggerPressed = isFiring

        if (curWeapon == WeaponType.C4_BOMB) return

        if (shouldFire && !isReloading && fireTimer <= 0f) {
            if (curWeapon == WeaponType.KNIFE) {
                performKnifeSlash()
            } else {
                val curMag = magAmmoMap[curWeapon] ?: 0
                if (curMag > 0) {
                    firePlayerWeapon()
                } else {
                    beginReload()
                }
            }
        }
    }

    private fun updatePlayerBombActions(dt: Float) {
        if (matchConfig.mode != GameMode.BOMB_DEFUSAL) {
            bombPlantProgress = 0f
            return
        }

        // Terrorist planting C4 inside Site A or B (hold USE button OR hold Fire while holding C4)
        val site = world.bombsites.firstOrNull { it.contains(playerX, playerZ) }
        val wantsPlant = matchConfig.playerSide == TeamSide.T &&
            playerHasBomb &&
            bombState == BombState.CARRIED &&
            site != null &&
            (isBombActionHeld || (activeWeapon == WeaponType.C4_BOMB && isFiring))

        if (wantsPlant && site != null) {
            if (activeWeapon != WeaponType.C4_BOMB) {
                switchWeaponInternal(WeaponType.C4_BOMB)
            }
            bombPlantProgress = (bombPlantProgress + dt / 3.0f).coerceAtMost(1f)
            plantBeepTimer -= dt
            if (plantBeepTimer <= 0f) {
                soundEngine.playBombBeep()
                plantBeepTimer = 0.55f
            }
            if (bombPlantProgress >= 1f) {
                plantBombAt(playerX, playerZ, site.label, plantedByPlayer = true)
            }
            return
        }

        // CT defusing planted C4
        val distToBomb = sqrt((playerX - plantedBombX) * (playerX - plantedBombX) + (playerZ - plantedBombZ) * (playerZ - plantedBombZ))
        val wantsDefuse = matchConfig.playerSide == TeamSide.CT &&
            bombState == BombState.PLANTED &&
            distToBomb <= 2.8f &&
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
                endRound(TeamSide.CT, "БОМБА РАЗМИНИРОВАНА • ПОБЕДА СПЕЦНАЗА")
            }
            return
        }

        bombPlantProgress = (bombPlantProgress - dt * 3f).coerceAtLeast(0f)
    }

    private fun plantBombAt(x: Float, z: Float, siteLabel: String, plantedByPlayer: Boolean) {
        bombState = BombState.PLANTED
        plantedBombX = x
        plantedBombZ = z
        plantedSiteLabel = siteLabel
        bombCountdownSec = 35f
        bombBeepTimer = 0.8f
        bombPlantProgress = 0f
        playerHasBomb = false
        for (b in bots) b.hasBomb = false
        if (plantedByPlayer) {
            money = (money + 300).coerceAtMost(16000)
            score += 350
            switchWeaponInternal(primaryWeapon ?: pistolWeapon)
        }
        soundEngine.playBombPlanted()
        bannerText = "БОМБА C4 ЗАЛОЖЕНА НА ТОЧКЕ $siteLabel!"
        bannerTimer = 3.0f
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
                bombState = BombState.EXPLODED
                soundEngine.playBombExplode()
                if (settings.audio.hapticsEnabled) onHapticPulse(true)
                endRound(TeamSide.T, "ВЗРЫВ C4 • ПОБЕДА ТЕРРОРИСТОВ")
                return
            }
        } else if (bombState == BombState.CARRIED) {
            roundTimeSec -= dt
            if (roundTimeSec <= 0f) {
                endRound(TeamSide.CT, "ВРЕМЯ ВЫШЛО • ПОБЕДА СПЕЦНАЗА")
                return
            }
        }

        // Check team elimination
        val ctAlive = countAlive(TeamSide.CT)
        val tAlive = countAlive(TeamSide.T)

        if (ctAlive == 0 && bombState != BombState.DEFUSED) {
            endRound(TeamSide.T, "СПЕЦНАЗ УНИЧТОЖЕН • ПОБЕДА ТЕРРОРИСТОВ")
        } else if (tAlive == 0 && bombState == BombState.CARRIED) {
            endRound(TeamSide.CT, "ТЕРРОРИСТЫ УНИЧТОЖЕНЫ • ПОБЕДА СПЕЦНАЗА")
        }
    }

    private fun endRound(winner: TeamSide, reason: String) {
        if (isRoundOver) return
        isRoundOver = true
        roundOverTimer = 3.6f
        if (winner == TeamSide.CT) ctScore++ else tScore++
        val playerWon = winner == matchConfig.playerSide
        money = (money + if (playerWon) 3250 else 2150).coerceAtMost(16000)
        bannerText = "$reason (CT $ctScore : $tScore T)"
        bannerTimer = 3.6f
    }

    private fun beginReload() {
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
        fireTimer = WeaponType.KNIFE.fireIntervalSec
        knifeSwingProgress = 0.01f
        soundEngine.playWeaponShot(WeaponType.KNIFE)

        val camY = playerY + eyeHeight
        val cosP = cos(playerPitchRad)
        val dirX = -sin(playerYawRad) * cosP
        val dirY = sin(playerPitchRad)
        val dirZ = -cos(playerYawRad) * cosP

        var bestBot: BotOperative? = null
        var bestDist = 2.4f
        for (b in bots) {
            if (b.isDead || b.team == matchConfig.playerSide) continue
            val t = intersectRaySphere(playerX, camY, playerZ, dirX, dirY, dirZ, b.x, 1.25f, b.z, 0.55f)
            if (t < bestDist) {
                bestDist = t; bestBot = b
            }
        }
        if (bestBot != null) {
            applyBotDamage(bestBot, WeaponType.KNIFE.bodyDamage, isHeadshot = false, killerIsPlayer = true, weaponUsed = WeaponType.KNIFE)
        }
    }

    private fun firePlayerWeapon() {
        val w = activeWeapon
        val nextMag = ((magAmmoMap[w] ?: 1) - 1).coerceAtLeast(0)
        magAmmoMap[w] = nextMag
        shotsFired++
        fireTimer = w.fireIntervalSec
        recoilKick = (recoilKick + (if (isAdsToggle) w.recoilKick * 0.65f else w.recoilKick)).coerceAtMost(1.45f)
        muzzleFlashTimer = 1.0f
        muzzleFlashRoll = rng.nextFloat() * 360f
        playerPitchRad = (playerPitchRad + (if (isAdsToggle) w.recoilPitch * 0.68f else w.recoilPitch))
            .coerceIn(-1.24f, 1.24f)

        soundEngine.playWeaponShot(w)
        if (settings.audio.hapticsEnabled) onHapticPulse(w == WeaponType.AWP || w == WeaponType.AK47 || w == WeaponType.DESERT_EAGLE)

        val camY = playerY + eyeHeight
        val spread = if (isAdsToggle) w.adsSpread else (w.hipSpread + moveSpeedRatio * 0.016f)
        val yaw = playerYawRad + (rng.nextFloat() - 0.5f) * spread
        val pitch = playerPitchRad + (rng.nextFloat() - 0.5f) * spread
        val cosP = cos(pitch)
        val dirX = -sin(yaw) * cosP
        val dirY = sin(pitch)
        val dirZ = -cos(yaw) * cosP

        val wallDist = world.raycastWalls(playerX, camY, playerZ, dirX, dirY, dirZ, 110f)

        var bestBot: BotOperative? = null
        var bestDist = wallDist
        var bestHeadshot = false

        for (b in bots) {
            if (b.isDead || b.team == matchConfig.playerSide) continue
            val headT = intersectRaySphere(playerX, camY, playerZ, dirX, dirY, dirZ, b.x, 1.67f, b.z, 0.21f)
            if (headT < bestDist) {
                bestDist = headT; bestBot = b; bestHeadshot = true
            }
            val chestT = intersectRaySphere(playerX, camY, playerZ, dirX, dirY, dirZ, b.x, 1.20f, b.z, 0.34f)
            if (chestT < bestDist) {
                bestDist = chestT; bestBot = b; bestHeadshot = false
            }
            val lowerT = intersectRaySphere(playerX, camY, playerZ, dirX, dirY, dirZ, b.x, 0.60f, b.z, 0.30f)
            if (lowerT < bestDist) {
                bestDist = lowerT; bestBot = b; bestHeadshot = false
            }
        }

        if (bestBot != null) {
            shotsHit++
            val hitX = playerX + dirX * bestDist
            val hitY = camY + dirY * bestDist
            val hitZ = playerZ + dirZ * bestDist
            impacts.add(TracerEffect(hitX, hitY, hitZ, isEnemy = false, life = 0.18f))

            val dmg = if (bestHeadshot) w.headDamage else w.bodyDamage
            applyBotDamage(bestBot, dmg, bestHeadshot, killerIsPlayer = true, weaponUsed = w)
        } else if (wallDist < 109f) {
            val hitX = playerX + dirX * (wallDist - 0.05f)
            val hitY = camY + dirY * (wallDist - 0.05f)
            val hitZ = playerZ + dirZ * (wallDist - 0.05f)
            impacts.add(TracerEffect(hitX, hitY, hitZ, isEnemy = false, life = 0.16f))
        }

        if (w == WeaponType.AWP && isAdsToggle) {
            isAdsToggle = false
        }

        if (nextMag == 0 && (reserveAmmoMap[w] ?: 0) > 0) {
            beginReload()
        }
    }

    private fun applyBotDamage(
        target: BotOperative,
        rawDmg: Float,
        isHeadshot: Boolean,
        killerIsPlayer: Boolean,
        weaponUsed: WeaponType
    ) {
        val absorbed = minOf(target.armor, rawDmg * 0.35f)
        target.armor -= absorbed
        target.hp -= (rawDmg - absorbed * 0.5f)
        target.hitFlinch = 1.0f

        if (killerIsPlayer) {
            hitMarkerAlpha = 1.0f
            isHeadshotHit = isHeadshot
            if (isHeadshot) {
                soundEngine.playHeadshot()
                if (settings.audio.hapticsEnabled) onHapticPulse(true)
            } else {
                soundEngine.playHitMarker()
            }
        }

        if (target.hp <= 0f && !target.isDead) {
            target.isDead = true
            target.deathProgress = 0f
            target.deathFallDir = if (rng.nextBoolean()) 1f else -1f
            if (killerIsPlayer) {
                kills++
                if (isHeadshot) headshots++
                money = (money + weaponUsed.killReward).coerceAtMost(16000)
                score += if (isHeadshot) 250 else 150
                val hsTag = if (isHeadshot) " [ГОЛОВА]" else ""
                killFeedText = "ВЫ [${weaponUsed.shortLabel}]$hsTag → ${target.team.shortRu}"
                killFeedTimer = 3.2f
            } else {
                val killerTeam = if (target.team == TeamSide.CT) TeamSide.T else TeamSide.CT
                killFeedText = "${killerTeam.shortRu} → ${target.team.shortRu}"
                killFeedTimer = 2.6f
            }

            // If Terrorist bot carrying C4 dies, pass C4 to another alive T so round never stalls
            if (target.hasBomb && bombState == BombState.CARRIED) {
                target.hasBomb = false
                if (matchConfig.playerSide == TeamSide.T && !isPlayerDead) {
                    playerHasBomb = true
                } else {
                    bots.firstOrNull { !it.isDead && it.team == TeamSide.T }?.hasBomb = true
                }
            }
        }
    }

    private fun updateBots(dt: Float) {
        val camY = playerY + eyeHeight

        for (b in bots) {
            if (b.isDead) {
                b.deathProgress = (b.deathProgress + dt * 2.2f).coerceAtMost(1f)
                if (matchConfig.mode == GameMode.TEAM_DEATHMATCH && b.deathProgress >= 1f) {
                    b.fireCooldown -= dt
                    if (b.fireCooldown <= -2.5f) respawnBotTdm(b)
                }
                continue
            }

            b.hitFlinch = (b.hitFlinch - dt * 5.0f).coerceAtLeast(0f)
            b.muzzleFlashTimer = (b.muzzleFlashTimer - dt * 20f).coerceAtLeast(0f)
            b.fireCooldown -= dt

            // 1. Find nearest visible enemy target (opposing bot OR player if opposing side)
            var targetX = 0f
            var targetZ = 0f
            var targetY = 1.42f
            var targetBot: BotOperative? = null
            var targetIsPlayer = false
            var bestTargetDist = Float.MAX_VALUE
            var hasLos = false

            // Check player as target if bot is on opposing team
            if (b.team != matchConfig.playerSide && !isPlayerDead) {
                val pdx = playerX - b.x
                val pdz = playerZ - b.z
                val pDist = sqrt(pdx * pdx + pdz * pdz).coerceAtLeast(0.01f)
                if (pDist < 54f) {
                    val wt = world.raycastWalls(b.x, 1.46f, b.z, pdx / pDist, (camY - 1.46f) / pDist, pdz / pDist, pDist)
                    if (wt >= pDist - 0.55f) {
                        bestTargetDist = pDist
                        targetX = playerX; targetZ = playerZ; targetY = camY
                        targetIsPlayer = true; hasLos = true
                    }
                }
            }

            // Check opposing bots
            for (other in bots) {
                if (other.isDead || other.team == b.team) continue
                val odx = other.x - b.x
                val odz = other.z - b.z
                val oDist = sqrt(odx * odx + odz * odz).coerceAtLeast(0.01f)
                if (oDist < bestTargetDist && oDist < 52f) {
                    val wt = world.raycastWalls(b.x, 1.46f, b.z, odx / oDist, 0f, odz / oDist, oDist)
                    if (wt >= oDist - 0.55f) {
                        bestTargetDist = oDist
                        targetX = other.x; targetZ = other.z; targetY = 1.42f
                        targetBot = other; targetIsPlayer = false; hasLos = true
                    }
                }
            }

            val prevX = b.x
            val prevZ = b.z
            val moveSpeed = 3.2f

            // 2. Bomb Defusal AI Objective: T carrier plants on site; CT rushes planted C4 to defuse!
            var isDoingBombTask = false
            if (matchConfig.mode == GameMode.BOMB_DEFUSAL) {
                if (b.team == TeamSide.T && b.hasBomb && bombState == BombState.CARRIED) {
                    val site = world.bombsites[b.targetSiteIndex % world.bombsites.size]
                    if (site.contains(b.x, b.z) && !hasLos) {
                        isDoingBombTask = true
                        b.plantProgress += dt / 3.2f
                        if (b.plantProgress >= 1f) {
                            b.plantProgress = 0f
                            plantBombAt(b.x, b.z, site.label, plantedByPlayer = false)
                        }
                    }
                } else if (b.team == TeamSide.CT && bombState == BombState.PLANTED) {
                    val distBomb = sqrt((b.x - plantedBombX) * (b.x - plantedBombX) + (b.z - plantedBombZ) * (b.z - plantedBombZ))
                    if (distBomb < 2.2f && !hasLos) {
                        isDoingBombTask = true
                        b.defuseProgress += dt / 4.8f
                        if (b.defuseProgress >= 1f) {
                            b.defuseProgress = 0f
                            bombState = BombState.DEFUSED
                            soundEngine.playBombPlanted()
                            endRound(TeamSide.CT, "СПЕЦНАЗ РАЗМИНИРОВАЛ C4!")
                            return
                        }
                    }
                }
            }

            if (isDoingBombTask) {
                updateDirectionalLocomotion(b, prevX, prevZ, sin(Math.toRadians(b.yawDeg.toDouble())).toFloat(), cos(Math.toRadians(b.yawDeg.toDouble())).toFloat(), moveSpeed, dt)
                continue
            }

            // 3. Movement & Tactical Engagement
            var faceX: Float
            var faceZ: Float
            var vx = 0f
            var vz = 0f

            if (hasLos) {
                val dx = targetX - b.x
                val dz = targetZ - b.z
                val dist = bestTargetDist.coerceAtLeast(0.01f)
                faceX = dx / dist
                faceZ = dz / dist
                val rightX = faceZ
                val rightZ = -faceX
                b.yawDeg = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()

                b.strafeTimer -= dt
                if (b.strafeTimer <= 0f) {
                    b.strafeDir = when (rng.nextInt(3)) { 0 -> -1f; 1 -> 1f; else -> 0f }
                    b.strafeTimer = 1.2f + rng.nextFloat() * 1.4f
                }

                if (dist > 15f) {
                    vx += faceX * moveSpeed * 0.85f; vz += faceZ * moveSpeed * 0.85f
                } else if (dist < 7.5f) {
                    vx -= faceX * moveSpeed * 0.65f; vz -= faceZ * moveSpeed * 0.65f
                }
                vx += rightX * b.strafeDir * moveSpeed * 0.68f
                vz += rightZ * b.strafeDir * moveSpeed * 0.68f

                // Fire with realistic burst cooldown
                if (b.fireCooldown <= 0f) {
                    b.muzzleFlashTimer = 1.0f
                    b.muzzleFlashRoll = rng.nextFloat() * 360f
                    b.fireCooldown = 0.62f + rng.nextFloat() * 0.52f
                    impacts.add(
                        TracerEffect(
                            x = b.x + faceX * minOf(dist * 0.45f, 6f),
                            y = 1.42f + ((targetY - 1.42f) / dist) * minOf(dist * 0.45f, 6f),
                            z = b.z + faceZ * minOf(dist * 0.45f, 6f),
                            isEnemy = (b.team != matchConfig.playerSide),
                            life = 0.12f
                        )
                    )
                    val hitChance = (0.50f - dist * 0.0085f).coerceIn(0.12f, 0.50f)
                    if (rng.nextFloat() < hitChance) {
                        val dmg = if (b.weapon == WeaponType.AK47) 14f else 11f
                        if (targetIsPlayer) {
                            applyPlayerDamage(dmg, b.team)
                        } else if (targetBot != null) {
                            applyBotDamage(targetBot, dmg * 1.45f, isHeadshot = rng.nextFloat() < 0.18f, killerIsPlayer = false, weaponUsed = b.weapon)
                        }
                    }
                }
            } else {
                // Follow Mirage lane waypoints (or rush planted bomb!)
                val goal = getBotObjectiveGoal(b)
                val dx = goal.first - b.x
                val dz = goal.second - b.z
                val dist = sqrt(dx * dx + dz * dz).coerceAtLeast(0.01f)
                faceX = dx / dist
                faceZ = dz / dist
                b.yawDeg = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()

                if (dist > 1.8f) {
                    vx = faceX * moveSpeed
                    vz = faceZ * moveSpeed
                } else {
                    // Advance waypoint along lane
                    val routes = if (b.team == TeamSide.T) world.tAttackRoutes else world.ctDefenseRoutes
                    val route = routes[b.targetSiteIndex % routes.size]
                    if (b.waypointIndex < route.size - 1) {
                        b.waypointIndex++
                    } else if (matchConfig.mode == GameMode.TEAM_DEATHMATCH) {
                        b.targetSiteIndex = rng.nextInt(routes.size)
                        b.waypointIndex = 0
                    }
                }
            }

            val (nx, nz) = world.resolveHorizontalCollision(b.x + vx * dt, b.z + vz * dt, 0.48f)
            b.x = nx; b.z = nz
            updateDirectionalLocomotion(b, prevX, prevZ, faceX, faceZ, moveSpeed, dt)
        }
    }

    private fun getBotObjectiveGoal(b: BotOperative): Pair<Float, Float> {
        if (matchConfig.mode == GameMode.BOMB_DEFUSAL && bombState == BombState.PLANTED) {
            return Pair(plantedBombX, plantedBombZ)
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
        if (abs(b.moveForwardRatio) > 0.05f) {
            b.walkPhase += dt * (2.6f + abs(b.moveForwardRatio) * 5.8f)
        }
        if (abs(b.moveStrafeRatio) > 0.05f) {
            b.strafePhase += dt * (2.8f + abs(b.moveStrafeRatio) * 5.2f)
        }
    }

    private fun applyPlayerDamage(amount: Float, attackerSide: TeamSide) {
        damageFlashAlpha = 0.85f
        soundEngine.playDamage()
        if (settings.audio.hapticsEnabled) onHapticPulse(true)

        val absorbed = minOf(armor, amount * 0.55f)
        armor -= absorbed
        hp -= (amount - absorbed * 0.5f)
        if (hp <= 0f) {
            hp = 0f
            deaths++
            isPlayerDead = true
            isFiring = false
            isAdsToggle = false
            bombPlantProgress = 0f
            tdmRespawnTimer = 2.6f
            killFeedText = "${attackerSide.shortRu} → ВЫ"
            killFeedTimer = 3.2f
            // Drop C4 to surviving Terrorist teammate if player died carrying it
            if (playerHasBomb && bombState == BombState.CARRIED) {
                playerHasBomb = false
                bots.firstOrNull { !it.isDead && it.team == TeamSide.T }?.hasBomb = true
            }
        }
    }

    private fun resetMatchInternal() {
        world = ArenaWorld(matchConfig.map)
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
        startRoundInternal(resetEconomy = true)
    }

    private fun startRoundInternal(resetEconomy: Boolean) {
        val side = matchConfig.playerSide
        val isTdm = matchConfig.mode == GameMode.TEAM_DEATHMATCH

        if (resetEconomy) {
            money = if (isTdm) 16000 else 800
            armor = if (isTdm) 100f else 0f
            hasDefuseKit = false
            pistolWeapon = if (side == TeamSide.CT) WeaponType.USP_S else WeaponType.GLOCK_18
            primaryWeapon = if (isTdm) (if (side == TeamSide.CT) WeaponType.M4A1_S else WeaponType.AK47) else null
        } else if (isPlayerDead) {
            // Lost weapon on death in Bomb Defusal (classic CS2 economy!)
            armor = 0f
            hasDefuseKit = false
            primaryWeapon = null
            pistolWeapon = if (side == TeamSide.CT) WeaponType.USP_S else WeaponType.GLOCK_18
        }

        // Refill ammo for owned weapons
        for (w in WeaponType.entries) {
            magAmmoMap[w] = w.magSize
            reserveAmmoMap[w] = w.initialReserve
        }

        activeWeapon = primaryWeapon ?: pistolWeapon
        lastWeapon = WeaponType.KNIFE
        hp = 100f
        isPlayerDead = false
        isReloading = false
        reloadTimer = 0f
        fireTimer = 0f
        isAdsToggle = false
        adsBlend = 0f
        isCrouching = false
        isFiring = false
        recoilKick = 0f
        muzzleFlashTimer = 0f
        boltProgress = 0f
        knifeSwingProgress = 0f
        bombPlantProgress = 0f
        hitMarkerAlpha = 0f
        damageFlashAlpha = 0f

        roundTimeSec = 105f
        buyTimeRemaining = 22f
        isRoundOver = false
        roundOverTimer = 0f
        bombState = BombState.CARRIED
        plantedSiteLabel = ""
        bombCountdownSec = 35f
        playerHasBomb = (!isTdm && side == TeamSide.T)

        // Position Player at CT or T Spawn facing the arena center
        val playerSpawns = if (side == TeamSide.CT) world.ctSpawns else world.tSpawns
        val pSp = playerSpawns[0]
        playerX = pSp.first
        playerZ = pSp.second
        playerY = 0f
        playerVelY = 0f
        eyeHeight = 1.65f
        playerYawRad = if (side == TeamSide.CT) Math.PI.toFloat() else 0f
        playerPitchRad = 0f

        // Spawn Allied & Enemy Bots according to user's chosen team sizes (e.g. 5v5, 1v2, 1v5)
        bots.clear()
        impacts.clear()
        val ctCount = if (side == TeamSide.CT) (matchConfig.playerTeamSize - 1).coerceAtLeast(0) else matchConfig.enemyTeamSize
        val tCount = if (side == TeamSide.T) (matchConfig.playerTeamSize - 1).coerceAtLeast(0) else matchConfig.enemyTeamSize

        val ctStartIdx = if (side == TeamSide.CT) 1 else 0
        for (i in 0 until ctCount) {
            val sp = world.ctSpawns[(ctStartIdx + i) % world.ctSpawns.size]
            val (bx, bz) = world.resolveHorizontalCollision(sp.first + (rng.nextFloat() - 0.5f) * 1.4f, sp.second + (rng.nextFloat() - 0.5f) * 1.4f, 0.5f)
            val botWeapon = if (roundNumber == 1 && !isTdm) WeaponType.USP_S else WeaponType.M4A1_S
            bots += BotOperative(
                id = nextBotId++,
                team = TeamSide.CT,
                weapon = botWeapon,
                hasBomb = false,
                x = bx, z = bz, yawDeg = 0f,
                targetSiteIndex = i % world.ctDefenseRoutes.size,
                fireCooldown = 0.8f + rng.nextFloat() * 0.9f
            )
        }

        val tStartIdx = if (side == TeamSide.T) 1 else 0
        for (i in 0 until tCount) {
            val sp = world.tSpawns[(tStartIdx + i) % world.tSpawns.size]
            val (bx, bz) = world.resolveHorizontalCollision(sp.first + (rng.nextFloat() - 0.5f) * 1.4f, sp.second + (rng.nextFloat() - 0.5f) * 1.4f, 0.5f)
            val botWeapon = if (roundNumber == 1 && !isTdm) WeaponType.GLOCK_18 else WeaponType.AK47
            val carriesBomb = (!isTdm && side == TeamSide.CT && i == 0)
            bots += BotOperative(
                id = nextBotId++,
                team = TeamSide.T,
                weapon = botWeapon,
                hasBomb = carriesBomb,
                x = bx, z = bz, yawDeg = 180f,
                targetSiteIndex = rng.nextInt(world.tAttackRoutes.size),
                fireCooldown = 0.8f + rng.nextFloat() * 0.9f
            )
        }

        bannerText = if (isTdm) {
            "${matchConfig.map.titleRu} • БОЙ НАСМЕРТЬ"
        } else {
            "РАУНД $roundNumber • ${matchConfig.map.titleRu} (${matchConfig.playerTeamSize}v${matchConfig.enemyTeamSize})"
        }
        bannerTimer = 2.6f
        publishHud()
    }

    private fun respawnPlayerTdm() {
        val side = matchConfig.playerSide
        val sp = (if (side == TeamSide.CT) world.ctSpawns else world.tSpawns).random(rng)
        playerX = sp.first; playerZ = sp.second; playerY = 0f
        playerYawRad = if (side == TeamSide.CT) Math.PI.toFloat() else 0f
        hp = 100f; armor = 100f; isPlayerDead = false
        for (w in WeaponType.entries) {
            magAmmoMap[w] = w.magSize
            reserveAmmoMap[w] = w.initialReserve
        }
    }

    private fun respawnBotTdm(b: BotOperative) {
        val sp = (if (b.team == TeamSide.CT) world.ctSpawns else world.tSpawns).random(rng)
        b.x = sp.first; b.z = sp.second; b.hp = b.maxHp; b.armor = 100f
        b.isDead = false; b.deathProgress = 0f; b.waypointIndex = 0; b.fireCooldown = 0.8f
    }

    private fun countAlive(team: TeamSide): Int {
        var c = bots.count { !it.isDead && it.team == team }
        if (matchConfig.playerSide == team && !isPlayerDead) c++
        return c
    }

    private fun updateDeadBodies(dt: Float) {
        for (b in bots) {
            if (b.isDead) b.deathProgress = (b.deathProgress + dt * 2.2f).coerceAtMost(1f)
        }
    }

    private fun recordMatchOnce() {
        if (!matchRecorded) {
            matchRecorded = true
            onMatchFinished(score, kills, headshots)
        }
    }

    private fun publishHud() {
        val blips = ArrayList<RadarBlip>(bots.size + 1)
        val cosY = cos(playerYawRad)
        val sinY = sin(playerYawRad)
        val radarRange = if (matchConfig.map == ArenaMapId.MIRAGE) 44f else 30f

        for (b in bots) {
            if (b.isDead) continue
            val isAlly = b.team == matchConfig.playerSide
            val dx = b.x - playerX
            val dz = b.z - playerZ
            val dist = sqrt(dx * dx + dz * dz)
            // Show allies always; show enemies within radar awareness or when firing
            if (!isAlly && dist > 28f && b.muzzleFlashTimer <= 0.05f) continue
            val localRight = dx * cosY - dz * sinY
            val localForward = -dx * sinY - dz * cosY
            blips += RadarBlip(
                relX = (localRight / radarRange).coerceIn(-1f, 1f),
                relZ = (localForward / radarRange).coerceIn(-1f, 1f),
                isAlly = isAlly
            )
        }
        if (matchConfig.mode == GameMode.BOMB_DEFUSAL && bombState == BombState.PLANTED) {
            val dx = plantedBombX - playerX
            val dz = plantedBombZ - playerZ
            val localRight = dx * cosY - dz * sinY
            val localForward = -dx * sinY - dz * cosY
            blips += RadarBlip(
                relX = (localRight / radarRange).coerceIn(-1f, 1f),
                relZ = (localForward / radarRange).coerceIn(-1f, 1f),
                isAlly = false,
                isBomb = true
            )
        }

        val site = world.bombsites.firstOrNull { it.contains(playerX, playerZ) }
        val distToBomb = sqrt((playerX - plantedBombX) * (playerX - plantedBombX) + (playerZ - plantedBombZ) * (playerZ - plantedBombZ))
        val canPlant = matchConfig.mode == GameMode.BOMB_DEFUSAL &&
            !isPlayerDead &&
            matchConfig.playerSide == TeamSide.T &&
            playerHasBomb &&
            bombState == BombState.CARRIED &&
            site != null
        val canDefuse = matchConfig.mode == GameMode.BOMB_DEFUSAL &&
            !isPlayerDead &&
            matchConfig.playerSide == TeamSide.CT &&
            bombState == BombState.PLANTED &&
            distToBomb <= 2.8f

        val w = activeWeapon
        latestHud = HudSnapshot(
            hp = hp.toInt().coerceIn(0, 100),
            maxHp = 100,
            armor = armor.toInt().coerceIn(0, 100),
            hasDefuseKit = hasDefuseKit,
            money = money,
            mode = matchConfig.mode,
            playerSide = matchConfig.playerSide,
            activeWeapon = w,
            primaryWeapon = primaryWeapon,
            pistolWeapon = pistolWeapon,
            hasBomb = playerHasBomb && bombState == BombState.CARRIED && matchConfig.mode == GameMode.BOMB_DEFUSAL,
            ammo = magAmmoMap[w] ?: w.magSize,
            magSize = w.magSize,
            reserveAmmo = reserveAmmoMap[w] ?: w.initialReserve,
            isReloading = isReloading,
            reloadProgress = if (isReloading && w.reloadSec > 0f) (reloadTimer / w.reloadSec).coerceIn(0f, 1f) else 0f,
            isAds = isAdsToggle,
            adsBlend = adsBlend,
            isCrouching = isCrouching,
            isPlayerDead = isPlayerDead,
            ctScore = ctScore,
            tScore = tScore,
            roundsToWin = matchConfig.roundsToWin,
            roundNumber = roundNumber,
            roundTimeSec = roundTimeSec.coerceAtLeast(0f),
            ctAlive = countAlive(TeamSide.CT),
            tAlive = countAlive(TeamSide.T),
            bombState = bombState,
            bombSiteLabel = plantedSiteLabel,
            bombCountdownSec = bombCountdownSec.coerceAtLeast(0f),
            inPlantZoneLabel = site?.label ?: "",
            canPlantBomb = canPlant,
            canDefuseBomb = canDefuse,
            bombActionProgress = bombPlantProgress,
            canBuy = isBuyAllowed(),
            calloutName = world.getCalloutRu(playerX, playerZ),
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
            crosshairSpread = (recoilKick * 12f + moveSpeedRatio * 8f) * (1f - adsBlend * 0.85f),
            isMatchFinished = isMatchFinished,
            isPlayerTeamWinner = isPlayerTeamWinner,
            bannerText = bannerText,
            killFeedText = killFeedText,
            radarBlips = blips,
            playerYawDeg = Math.toDegrees(playerYawRad.toDouble()).toFloat()
        )
    }

    private fun intersectRaySphere(
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
        cx: Float, cy: Float, cz: Float,
        radius: Float
    ): Float {
        val lx = ox - cx; val ly = oy - cy; val lz = oz - cz
        val b = lx * dx + ly * dy + lz * dz
        val c = lx * lx + ly * ly + lz * lz - radius * radius
        val disc = b * b - c
        if (disc < 0f) return Float.MAX_VALUE
        val t = -b - sqrt(disc)
        return if (t > 0.05f) t else Float.MAX_VALUE
    }
}
