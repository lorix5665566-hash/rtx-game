package com.example.engine

import com.example.audio.SoundEngine
import com.example.model.ArenaMapId
import com.example.model.GameMode
import com.example.model.GameSettings
import com.example.model.HudSnapshot
import com.example.model.RadarBlip
import com.example.model.WeaponType
import kotlin.math.PI
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
    var world: ArenaWorld = ArenaWorld(ArenaMapId.INDUSTRIAL)
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
    var mode: GameMode = GameMode.ASSAULT

    @Volatile
    var selectedMapId: ArenaMapId = ArenaMapId.INDUSTRIAL

    // Input state (written from UI thread, read from GL thread)
    @Volatile var moveInputX: Float = 0f
    @Volatile var moveInputY: Float = 0f
    @Volatile var isFiring: Boolean = false
    @Volatile var isAdsToggle: Boolean = false
    @Volatile var isCrouching: Boolean = false

    private val lookLock = Any()
    private var pendingLookDx: Float = 0f
    private var pendingLookDy: Float = 0f
    @Volatile private var pendingJump: Boolean = false
    @Volatile private var pendingReload: Boolean = false
    @Volatile private var pendingReset: Boolean = false
    @Volatile private var pendingWeaponSelect: WeaponType? = null
    @Volatile private var pendingWeaponCycle: Boolean = false

    // Player physical state (1:1 metric scale: standing eye height 1.65m, crouch 1.12m)
    var playerX: Float = 0f
    var playerZ: Float = 14.5f
    var playerY: Float = 0f
    var playerVelY: Float = 0f
    var eyeHeight: Float = 1.65f
    var playerYawRad: Float = 0f
    var playerPitchRad: Float = 0f

    // Weapon arsenal & animation state
    @Volatile
    var activeWeapon: WeaponType = WeaponType.M4A1
        private set
    private val magAmmoMap = mutableMapOf<WeaponType, Int>()
    private val reserveAmmoMap = mutableMapOf<WeaponType, Int>()
    private var lastTriggerPressed: Boolean = false

    var adsBlend: Float = 0f
    var walkBobPhase: Float = 0f
    var moveSpeedRatio: Float = 0f
    var recoilKick: Float = 0f
    var muzzleFlashTimer: Float = 0f
    var boltProgress: Float = 0f
    var timeSec: Float = 0f

    private var isReloading: Boolean = false
    private var reloadTimer: Float = 0f
    private var fireTimer: Float = 0f

    // Combat stats
    private var hp: Float = 100f
    private var armor: Float = 100f
    private var damageCooldown: Float = 0f
    private var score: Int = 0
    private var wave: Int = 1
    private val assaultTotalWaves = 5
    private var kills: Int = 0
    private var headshots: Int = 0
    private var shotsFired: Int = 0
    private var shotsHit: Int = 0
    private var hitMarkerAlpha: Float = 0f
    private var isHeadshotHit: Boolean = false
    private var damageFlashAlpha: Float = 0f
    private var isGameOver: Boolean = false
    private var isVictory: Boolean = false
    private var matchRecorded: Boolean = false
    private var bannerText: String = ""
    private var bannerTimer: Float = 0f
    private var waveTransitionTimer: Float = 0f
    private var nextEnemyId: Int = 1

    val enemies = mutableListOf<EnemyOperative>()
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

    fun startNewMatch(selectedMode: GameMode, mapId: ArenaMapId, startingWeapon: WeaponType) {
        mode = selectedMode
        selectedMapId = mapId
        activeWeapon = startingWeapon
        isPaused = false
        pendingReset = true
    }

    fun requestReset() {
        isPaused = false
        pendingReset = true
    }

    fun selectWeapon(weapon: WeaponType) {
        pendingWeaponSelect = weapon
    }

    fun cycleWeapon() {
        pendingWeaponCycle = true
    }

    fun addLookDelta(dxPixels: Float, dyPixels: Float) {
        synchronized(lookLock) {
            pendingLookDx += dxPixels
            pendingLookDy += dyPixels
        }
    }

    fun triggerJump() {
        pendingJump = true
    }

    fun triggerReload() {
        pendingReload = true
    }

    fun toggleAds() {
        isAdsToggle = !isAdsToggle
    }

    fun toggleCrouch() {
        isCrouching = !isCrouching
    }

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

        if (isGameOver || isVictory) {
            muzzleFlashTimer = (muzzleFlashTimer - dt * 5f).coerceAtLeast(0f)
            recoilKick = (recoilKick - dt * 6f).coerceAtLeast(0f)
            updateDeadBodies(dt)
            publishHud()
            return
        }

        // Handle weapon switching
        val reqWeapon = pendingWeaponSelect
        if (reqWeapon != null) {
            pendingWeaponSelect = null
            switchWeaponInternal(reqWeapon)
        } else if (pendingWeaponCycle) {
            pendingWeaponCycle = false
            val all = WeaponType.entries
            val next = all[(all.indexOf(activeWeapon) + 1) % all.size]
            switchWeaponInternal(next)
        }

        // 1. Camera look with sensitivity + Sniper Scope precision scaling
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
            if (activeWeapon.hasSniperScope) sens.adsSensitivity * 0.45f else sens.adsSensitivity
        } else 1f
        val baseSens = sens.lookSensitivity * scopeFactor * 0.0036f
        playerYawRad -= dx * baseSens * sens.xAxisMultiplier
        val ySign = if (sens.invertY) 1f else -1f
        playerPitchRad = (playerPitchRad + dy * baseSens * sens.yAxisMultiplier * ySign)
            .coerceIn(-1.22f, 1.22f)

        if (sens.aimAssist && (isFiring || isAdsToggle)) {
            applyGentleAimAssist(dt)
        }

        // 2. Player movement, crouch, jump & collision
        val forwardX = -sin(playerYawRad)
        val forwardZ = -cos(playerYawRad)
        val rightX = cos(playerYawRad)
        val rightZ = -sin(playerYawRad)

        val moveMag = sqrt(moveInputX * moveInputX + moveInputY * moveInputY).coerceAtMost(1f)
        val weaponMoveScale = if (activeWeapon == WeaponType.SNIPER) 0.88f else 1.0f
        val maxSpeed = when {
            isCrouching -> 2.8f
            isAdsToggle -> 3.1f
            else -> 5.6f * weaponMoveScale
        }
        val wishX = (rightX * moveInputX + forwardX * moveInputY) * maxSpeed
        val wishZ = (rightZ * moveInputX + forwardZ * moveInputY) * maxSpeed

        val (nx, nz) = world.resolveHorizontalCollision(
            playerX + wishX * dt,
            playerZ + wishZ * dt,
            0.46f
        )
        playerX = nx
        playerZ = nz

        if (pendingJump) {
            pendingJump = false
            if (playerY <= 0.01f) {
                isCrouching = false
                playerVelY = 5.2f
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
            walkBobPhase += dt * (if (isCrouching) 6.5f else 10.2f) * moveMag
        }

        // 3. Weapon ADS, Bolt Action, Reload & Firing
        val adsTarget = if (isAdsToggle && !isReloading) 1f else 0f
        adsBlend += (adsTarget - adsBlend) * (dt * 14f).coerceAtMost(1f)
        recoilKick = (recoilKick - dt * 7.8f).coerceAtLeast(0f)
        muzzleFlashTimer = (muzzleFlashTimer - dt * 15f).coerceAtLeast(0f)
        hitMarkerAlpha = (hitMarkerAlpha - dt * 4.2f).coerceAtLeast(0f)
        damageFlashAlpha = (damageFlashAlpha - dt * 2.5f).coerceAtLeast(0f)
        if (bannerTimer > 0f) {
            bannerTimer -= dt
            if (bannerTimer <= 0f) bannerText = ""
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
                val taken = if (mode == GameMode.RANGE) needed else minOf(needed, curRes)
                magAmmoMap[curWeapon] = curMag + taken
                if (mode != GameMode.RANGE) reserveAmmoMap[curWeapon] = curRes - taken
                isReloading = false
                reloadTimer = 0f
            }
        }

        fireTimer = (fireTimer - dt).coerceAtLeast(0f)
        boltProgress = if (curWeapon == WeaponType.SNIPER && fireTimer > 0f) {
            (1f - fireTimer / curWeapon.fireIntervalSec).coerceIn(0f, 1f)
        } else 0f

        val shouldFire = if (curWeapon.isAutomatic) {
            isFiring
        } else {
            isFiring && !lastTriggerPressed
        }
        lastTriggerPressed = isFiring

        if (shouldFire && !isReloading && fireTimer <= 0f) {
            val curMag = magAmmoMap[curWeapon] ?: 0
            if (curMag > 0) {
                firePlayerWeapon()
            } else {
                beginReload()
            }
        }

        // 4. Health & Armor Regen
        damageCooldown = (damageCooldown - dt).coerceAtLeast(0f)
        if (damageCooldown <= 0f) {
            if (hp < 100f) hp = (hp + dt * 10f).coerceAtMost(100f)
            if (armor < 100f && hp >= 100f) armor = (armor + dt * 7f).coerceAtMost(100f)
        }

        // 5. Update Human Enemies with 4-way locomotion
        updateEnemies(dt)

        // 6. Decay impact sparks
        val iter = impacts.iterator()
        while (iter.hasNext()) {
            val fx = iter.next()
            fx.life -= dt
            if (fx.life <= 0f) iter.remove()
        }

        publishHud()
    }

    private fun switchWeaponInternal(target: WeaponType) {
        if (target == activeWeapon) return
        activeWeapon = target
        isReloading = false
        reloadTimer = 0f
        isAdsToggle = false
        adsBlend = 0f
        fireTimer = 0.22f
        recoilKick = 0.25f
        soundEngine.playWeaponSwitch()
    }

    private fun resetMatchInternal() {
        world = ArenaWorld(selectedMapId)
        playerX = 0f
        playerZ = 15.0f
        playerY = 0f
        playerVelY = 0f
        eyeHeight = 1.65f
        playerYawRad = 0f
        playerPitchRad = 0f
        adsBlend = 0f
        isAdsToggle = false
        isCrouching = false
        isFiring = false
        lastTriggerPressed = false
        recoilKick = 0f
        muzzleFlashTimer = 0f
        boltProgress = 0f

        for (w in WeaponType.entries) {
            magAmmoMap[w] = w.magSize
            reserveAmmoMap[w] = w.initialReserve
        }
        isReloading = false
        reloadTimer = 0f
        fireTimer = 0f
        hp = 100f
        armor = 100f
        damageCooldown = 0f
        score = 0
        wave = 1
        kills = 0
        headshots = 0
        shotsFired = 0
        shotsHit = 0
        hitMarkerAlpha = 0f
        damageFlashAlpha = 0f
        isGameOver = false
        isVictory = false
        matchRecorded = false
        waveTransitionTimer = 0f
        enemies.clear()
        impacts.clear()
        spawnWave(1)
        publishHud()
    }

    private fun beginReload() {
        val w = activeWeapon
        val curMag = magAmmoMap[w] ?: 0
        val curRes = reserveAmmoMap[w] ?: 0
        if (isReloading || curMag >= w.magSize) return
        if (mode != GameMode.RANGE && curRes <= 0) return
        isReloading = true
        reloadTimer = 0f
        isAdsToggle = false
        soundEngine.playReload()
    }

    private fun firePlayerWeapon() {
        val w = activeWeapon
        val nextMag = ((magAmmoMap[w] ?: 1) - 1).coerceAtLeast(0)
        magAmmoMap[w] = nextMag
        shotsFired++
        fireTimer = w.fireIntervalSec
        recoilKick = (recoilKick + (if (isAdsToggle) w.recoilKick * 0.65f else w.recoilKick)).coerceAtMost(1.5f)
        muzzleFlashTimer = 1.0f
        playerPitchRad = (playerPitchRad + (if (isAdsToggle) w.recoilPitch * 0.68f else w.recoilPitch))
            .coerceIn(-1.22f, 1.22f)

        // Authentic firearm audio & haptics
        soundEngine.playWeaponShot(w)
        if (settings.audio.hapticsEnabled) onHapticPulse(w == WeaponType.SNIPER || w == WeaponType.AK47)

        val camY = playerY + eyeHeight
        val spread = if (isAdsToggle) w.adsSpread else (w.hipSpread + moveSpeedRatio * 0.015f)
        val jitterYaw = (rng.nextFloat() - 0.5f) * spread
        val jitterPitch = (rng.nextFloat() - 0.5f) * spread

        val yaw = playerYawRad + jitterYaw
        val pitch = playerPitchRad + jitterPitch
        val cosP = cos(pitch)
        val dirX = -sin(yaw) * cosP
        val dirY = sin(pitch)
        val dirZ = -cos(yaw) * cosP

        val wallDist = world.raycastWalls(playerX, camY, playerZ, dirX, dirY, dirZ, 85f)

        var bestEnemy: EnemyOperative? = null
        var bestDist = wallDist
        var bestHeadshot = false

        for (e in enemies) {
            if (e.isDead) continue
            val headT = intersectRaySphere(playerX, camY, playerZ, dirX, dirY, dirZ, e.x, 1.67f, e.z, 0.21f)
            if (headT < bestDist) {
                bestDist = headT; bestEnemy = e; bestHeadshot = true
            }
            val chestT = intersectRaySphere(playerX, camY, playerZ, dirX, dirY, dirZ, e.x, 1.20f, e.z, 0.34f)
            if (chestT < bestDist) {
                bestDist = chestT; bestEnemy = e; bestHeadshot = false
            }
            val lowerT = intersectRaySphere(playerX, camY, playerZ, dirX, dirY, dirZ, e.x, 0.60f, e.z, 0.30f)
            if (lowerT < bestDist) {
                bestDist = lowerT; bestEnemy = e; bestHeadshot = false
            }
        }

        if (bestEnemy != null) {
            shotsHit++
            val hitX = playerX + dirX * bestDist
            val hitY = camY + dirY * bestDist
            val hitZ = playerZ + dirZ * bestDist
            impacts.add(TracerEffect(hitX, hitY, hitZ, isEnemy = false, life = 0.22f))

            val dmg = if (bestHeadshot) w.headDamage else w.bodyDamage
            bestEnemy.hp -= dmg
            bestEnemy.hitFlinch = 1.0f
            hitMarkerAlpha = 1.0f
            isHeadshotHit = bestHeadshot

            if (bestHeadshot) {
                soundEngine.playHeadshot()
                if (settings.audio.hapticsEnabled) onHapticPulse(true)
            } else {
                soundEngine.playHitMarker()
            }

            if (bestEnemy.hp <= 0f) {
                bestEnemy.isDead = true
                bestEnemy.deathProgress = 0f
                bestEnemy.deathFallDir = if (rng.nextBoolean()) 1f else -1f
                kills++
                if (bestHeadshot) headshots++
                val pts = when {
                    bestHeadshot && bestEnemy.soldierClass == SoldierClass.HEAVY -> 350
                    bestHeadshot -> 250
                    bestEnemy.soldierClass == SoldierClass.HEAVY -> 180
                    else -> 120
                }
                score += pts
                if (mode != GameMode.RANGE) {
                    val ammoDrop = if (w == WeaponType.SNIPER) 4 else 18
                    val cap = if (w == WeaponType.SNIPER) 50 else 240
                    reserveAmmoMap[w] = ((reserveAmmoMap[w] ?: 0) + ammoDrop).coerceAtMost(cap)
                }
            }
        } else if (wallDist < 84f) {
            val hitX = playerX + dirX * (wallDist - 0.05f)
            val hitY = camY + dirY * (wallDist - 0.05f)
            val hitZ = playerZ + dirZ * (wallDist - 0.05f)
            impacts.add(TracerEffect(hitX, hitY, hitZ, isEnemy = false, life = 0.18f))
        }

        // Unscope briefly after sniper bolt-action shot for realistic feel
        if (w == WeaponType.SNIPER && isAdsToggle) {
            isAdsToggle = false
        }

        if (nextMag == 0 && (mode == GameMode.RANGE || (reserveAmmoMap[w] ?: 0) > 0)) {
            beginReload()
        }
    }

    private fun applyGentleAimAssist(dt: Float) {
        val camY = playerY + eyeHeight
        val cosP = cos(playerPitchRad)
        val lookX = -sin(playerYawRad) * cosP
        val lookY = sin(playerPitchRad)
        val lookZ = -cos(playerYawRad) * cosP

        var bestDot = 0.986f
        var targetYaw = playerYawRad
        var targetPitch = playerPitchRad
        var found = false

        for (e in enemies) {
            if (e.isDead) continue
            val tx = e.x - playerX
            val ty = 1.44f - camY
            val tz = e.z - playerZ
            val len = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(0.01f)
            val dot = (tx * lookX + ty * lookY + tz * lookZ) / len
            if (dot > bestDot) {
                bestDot = dot
                targetYaw = atan2(-tx, -tz)
                targetPitch = kotlin.math.asin((ty / len).coerceIn(-0.98f, 0.98f))
                found = true
            }
        }
        if (found) {
            val yawDiff = normalizeAngle(targetYaw - playerYawRad)
            val pitchDiff = targetPitch - playerPitchRad
            val pull = (dt * 4.2f).coerceAtMost(0.32f)
            playerYawRad += yawDiff * pull
            playerPitchRad = (playerPitchRad + pitchDiff * pull).coerceIn(-1.22f, 1.22f)
        }
    }

    private fun updateEnemies(dt: Float) {
        var aliveCount = 0
        val camY = playerY + eyeHeight

        for (e in enemies) {
            if (e.isDead) {
                e.deathProgress = (e.deathProgress + dt * 2.2f).coerceAtMost(1f)
                continue
            }
            aliveCount++
            e.hitFlinch = (e.hitFlinch - dt * 5.0f).coerceAtLeast(0f)
            e.muzzleFlashTimer = (e.muzzleFlashTimer - dt * 9.0f).coerceAtLeast(0f)

            val dx = playerX - e.x
            val dz = playerZ - e.z
            val dist = sqrt(dx * dx + dz * dz).coerceAtLeast(0.01f)
            val faceX = dx / dist
            val faceZ = dz / dist
            // Enemy local right vector (perpendicular to facing vector in XZ plane)
            val rightX = faceZ
            val rightZ = -faceX
            e.yawDeg = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()

            val prevX = e.x
            val prevZ = e.z

            if (mode == GameMode.RANGE) {
                e.strafeTimer -= dt
                if (e.strafeTimer <= 0f) {
                    e.strafeDir = -e.strafeDir
                    e.strafeTimer = 1.9f + rng.nextFloat() * 1.4f
                }
                val speed = if (e.soldierClass == SoldierClass.RECON) 2.2f else 1.3f
                val (ex, ez) = world.resolveHorizontalCollision(
                    e.x + rightX * e.strafeDir * speed * dt,
                    e.z + rightZ * e.strafeDir * speed * dt,
                    0.48f
                )
                e.x = ex
                e.z = ez
                updateDirectionalLocomotion(e, prevX, prevZ, faceX, faceZ, rightX, rightZ, speed, dt)
                continue
            }

            // Combat AI: tactical advance, tactical backpedal when too close, and lateral strafing!
            val desiredDist = when (e.soldierClass) {
                SoldierClass.HEAVY -> 8.0f
                SoldierClass.ASSAULT -> 12.0f
                SoldierClass.RECON -> 16.5f
            }
            val moveSpeed = when (e.soldierClass) {
                SoldierClass.HEAVY -> 2.1f
                SoldierClass.ASSAULT -> 2.95f
                SoldierClass.RECON -> 3.4f
            }

            e.strafeTimer -= dt
            if (e.strafeTimer <= 0f) {
                // Periodically switch between strafe-left, strafe-right, or brief hold
                e.strafeDir = when (rng.nextInt(3)) {
                    0 -> -1f
                    1 -> 1f
                    else -> 0f
                }
                e.strafeTimer = 1.4f + rng.nextFloat() * 1.8f
            }

            var vx = 0f
            var vz = 0f
            if (dist > desiredDist + 1.6f) {
                // Walk forward toward player
                vx += faceX * moveSpeed
                vz += faceZ * moveSpeed
            } else if (dist < desiredDist - 2.4f) {
                // Backpedal tactically while keeping rifle trained on player!
                vx -= faceX * (moveSpeed * 0.72f)
                vz -= faceZ * (moveSpeed * 0.72f)
            }
            // Lateral sidestep strafe
            vx += rightX * e.strafeDir * (moveSpeed * 0.68f)
            vz += rightZ * e.strafeDir * (moveSpeed * 0.68f)

            val (ex, ez) = world.resolveHorizontalCollision(e.x + vx * dt, e.z + vz * dt, 0.48f)
            e.x = ex
            e.z = ez
            updateDirectionalLocomotion(e, prevX, prevZ, faceX, faceZ, rightX, rightZ, moveSpeed, dt)

            // Enemy firing logic with Line-of-Sight raycast
            e.fireCooldown -= dt
            if (e.fireCooldown <= 0f && dist < 36f) {
                val dirX = (playerX - e.x) / dist
                val dirY = (camY - 1.45f) / dist
                val dirZ = (playerZ - e.z) / dist
                val wallT = world.raycastWalls(e.x, 1.45f, e.z, dirX, dirY, dirZ, dist)
                if (wallT >= dist - 0.5f) {
                    e.muzzleFlashTimer = 1.0f
                    e.fireCooldown = when (e.soldierClass) {
                        SoldierClass.HEAVY -> 0.85f + rng.nextFloat() * 0.45f
                        SoldierClass.ASSAULT -> 1.15f + rng.nextFloat() * 0.55f
                        SoldierClass.RECON -> 1.55f + rng.nextFloat() * 0.65f
                    }
                    impacts.add(
                        TracerEffect(
                            x = e.x + dirX * minOf(dist * 0.45f, 5f),
                            y = 1.42f + dirY * minOf(dist * 0.45f, 5f),
                            z = e.z + dirZ * minOf(dist * 0.45f, 5f),
                            isEnemy = true,
                            life = 0.14f
                        )
                    )
                    val hitChance = (0.56f - dist * 0.012f - moveSpeedRatio * 0.16f - (if (isCrouching) 0.10f else 0f))
                        .coerceIn(0.14f, 0.56f)
                    if (rng.nextFloat() < hitChance) {
                        val rawDmg = when (e.soldierClass) {
                            SoldierClass.HEAVY -> 12f
                            SoldierClass.RECON -> 15f
                            SoldierClass.ASSAULT -> 9f
                        }
                        applyPlayerDamage(rawDmg)
                    }
                } else {
                    e.fireCooldown = 0.45f
                }
            }
        }

        if (aliveCount == 0) {
            if (mode == GameMode.RANGE) {
                waveTransitionTimer += dt
                if (waveTransitionTimer >= 1.2f) {
                    waveTransitionTimer = 0f
                    wave++
                    spawnWave(wave)
                }
            } else if (mode == GameMode.ASSAULT && wave >= assaultTotalWaves) {
                isVictory = true
                bannerText = "ОПЕРАЦИЯ ЗАВЕРШЕНА"
                recordMatchOnce()
            } else {
                if (waveTransitionTimer == 0f) {
                    bannerText = "ВОЛНА $wave ЗАЧИЩЕНА"
                    bannerTimer = 2.0f
                    armor = (armor + 35f).coerceAtMost(100f)
                    hp = (hp + 25f).coerceAtMost(100f)
                }
                waveTransitionTimer += dt
                if (waveTransitionTimer >= 2.2f) {
                    waveTransitionTimer = 0f
                    wave++
                    spawnWave(wave)
                }
            }
        }
    }

    /**
     * Projects actual world displacement into each soldier's local forward/backward
     * and left/right strafe axes to drive separate 4-way human locomotion animations.
     */
    private fun updateDirectionalLocomotion(
        e: EnemyOperative,
        prevX: Float,
        prevZ: Float,
        faceX: Float,
        faceZ: Float,
        rightX: Float,
        rightZ: Float,
        refSpeed: Float,
        dt: Float
    ) {
        val velX = (e.x - prevX) / dt
        val velZ = (e.z - prevZ) / dt
        val localFwd = ((velX * faceX + velZ * faceZ) / refSpeed).coerceIn(-1f, 1f)
        val localStrafe = ((velX * rightX + velZ * rightZ) / refSpeed).coerceIn(-1f, 1f)

        val smooth = (dt * 10f).coerceAtMost(1f)
        e.moveForwardRatio += (localFwd - e.moveForwardRatio) * smooth
        e.moveStrafeRatio += (localStrafe - e.moveStrafeRatio) * smooth

        val totalSpeed = sqrt(velX * velX + velZ * velZ)
        e.isMoving = totalSpeed > 0.15f
        if (abs(e.moveForwardRatio) > 0.05f) {
            // Advance walk phase proportionally to actual forward/backward gait speed
            e.walkPhase += dt * (2.6f + abs(e.moveForwardRatio) * 5.8f)
        }
        if (abs(e.moveStrafeRatio) > 0.05f) {
            // Separate sidestep cadence for realistic lateral footwork
            e.strafePhase += dt * (2.8f + abs(e.moveStrafeRatio) * 5.2f)
        }
    }

    private fun updateDeadBodies(dt: Float) {
        for (e in enemies) {
            if (e.isDead) e.deathProgress = (e.deathProgress + dt * 2.2f).coerceAtMost(1f)
        }
    }

    private fun applyPlayerDamage(amount: Float) {
        damageCooldown = 4.2f
        damageFlashAlpha = 0.85f
        soundEngine.playDamage()
        if (settings.audio.hapticsEnabled) onHapticPulse(true)

        val absorbed = minOf(armor, amount * 0.65f)
        armor -= absorbed
        hp -= (amount - absorbed)
        if (hp <= 0f) {
            hp = 0f
            isGameOver = true
            isFiring = false
            isAdsToggle = false
            bannerText = "БОЕЦ ВЫВЕДЕН ИЗ СТРОЯ"
            recordMatchOnce()
        }
    }

    private fun recordMatchOnce() {
        if (!matchRecorded) {
            matchRecorded = true
            onMatchFinished(score, kills, headshots)
        }
    }

    private fun spawnWave(waveNum: Int) {
        enemies.removeAll { it.isDead }
        val count = when (mode) {
            GameMode.RANGE -> 5
            GameMode.ASSAULT -> (3 + waveNum).coerceAtMost(8)
            GameMode.SURVIVAL -> (3 + waveNum).coerceAtMost(10)
        }
        val spawns = world.spawnPoints.shuffled(rng)
        for (i in 0 until count) {
            val sp = spawns[i % spawns.size]
            val jitterX = (rng.nextFloat() - 0.5f) * 2.2f
            val jitterZ = (rng.nextFloat() - 0.5f) * 2.2f
            val (sx, sz) = world.resolveHorizontalCollision(sp.first + jitterX, sp.second + jitterZ, 0.6f)
            val cls = when {
                i % 4 == 3 && waveNum >= 2 -> SoldierClass.HEAVY
                i % 3 == 2 -> SoldierClass.RECON
                else -> SoldierClass.ASSAULT
            }
            val maxHp = when (cls) {
                SoldierClass.HEAVY -> 150f + waveNum * 12f
                SoldierClass.RECON -> 80f + waveNum * 6f
                SoldierClass.ASSAULT -> 100f + waveNum * 8f
            }
            enemies += EnemyOperative(
                id = nextEnemyId++,
                soldierClass = cls,
                x = sx,
                z = sz,
                yawDeg = 0f,
                hp = maxHp,
                maxHp = maxHp,
                fireCooldown = 1.0f + rng.nextFloat() * 1.4f,
                isRangeTarget = (mode == GameMode.RANGE)
            )
        }
        bannerText = when (mode) {
            GameMode.RANGE -> "${selectedMapId.titleRu} • ПОЛИГОН"
            GameMode.ASSAULT -> "${selectedMapId.titleRu} • ВОЛНА $waveNum/$assaultTotalWaves"
            GameMode.SURVIVAL -> "${selectedMapId.titleRu} • ВОЛНА $waveNum"
        }
        bannerTimer = 2.2f
    }

    private fun publishHud() {
        val blips = ArrayList<RadarBlip>(enemies.size)
        val cosY = cos(playerYawRad)
        val sinY = sin(playerYawRad)
        var alive = 0
        for (e in enemies) {
            if (e.isDead) continue
            alive++
            val dx = e.x - playerX
            val dz = e.z - playerZ
            val localRight = dx * cosY - dz * sinY
            val localForward = -dx * sinY - dz * cosY
            blips += RadarBlip(
                relX = (localRight / 26f).coerceIn(-1f, 1f),
                relZ = (localForward / 26f).coerceIn(-1f, 1f),
                isElite = e.soldierClass == SoldierClass.HEAVY
            )
        }

        val w = activeWeapon
        val curAmmo = magAmmoMap[w] ?: w.magSize
        val curReserve = reserveAmmoMap[w] ?: w.initialReserve
        latestHud = HudSnapshot(
            hp = hp.toInt().coerceIn(0, 100),
            maxHp = 100,
            armor = armor.toInt().coerceIn(0, 100),
            weapon = w,
            ammo = curAmmo,
            magSize = w.magSize,
            reserveAmmo = if (mode == GameMode.RANGE) 999 else curReserve,
            isReloading = isReloading,
            reloadProgress = if (isReloading) (reloadTimer / w.reloadSec).coerceIn(0f, 1f) else 0f,
            isAds = isAdsToggle,
            adsBlend = adsBlend,
            isCrouching = isCrouching,
            score = score,
            wave = wave,
            totalWaves = if (mode == GameMode.ASSAULT) assaultTotalWaves else 0,
            kills = kills,
            headshots = headshots,
            shotsFired = shotsFired,
            shotsHit = shotsHit,
            enemiesAlive = alive,
            fps = currentFps,
            hitMarkerAlpha = hitMarkerAlpha,
            isHeadshotHit = isHeadshotHit,
            damageFlashAlpha = damageFlashAlpha,
            crosshairSpread = (recoilKick * 12f + moveSpeedRatio * 8f) * (1f - adsBlend * 0.85f),
            isGameOver = isGameOver,
            isVictory = isVictory,
            bannerText = bannerText,
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
        val lx = ox - cx
        val ly = oy - cy
        val lz = oz - cz
        val b = lx * dx + ly * dy + lz * dz
        val c = lx * lx + ly * ly + lz * lz - radius * radius
        val disc = b * b - c
        if (disc < 0f) return Float.MAX_VALUE
        val t = -b - sqrt(disc)
        return if (t > 0.05f) t else Float.MAX_VALUE
    }

    private fun normalizeAngle(a: Float): Float {
        var r = a
        while (r > PI) r -= (2.0 * PI).toFloat()
        while (r < -PI) r += (2.0 * PI).toFloat()
        return r
    }
}
