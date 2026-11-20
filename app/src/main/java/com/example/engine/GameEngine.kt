package com.example.engine

import com.example.audio.SoundEngine
import com.example.model.GameMode
import com.example.model.GameSettings
import com.example.model.HudSnapshot
import com.example.model.RadarBlip
import kotlin.math.PI
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
    val world = ArenaWorld()
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

    // Player physical state (1:1 metric scale: standing eye height 1.65m, crouch 1.12m)
    var playerX: Float = 0f
    var playerZ: Float = 14.5f
    var playerY: Float = 0f
    var playerVelY: Float = 0f
    var eyeHeight: Float = 1.65f
    var playerYawRad: Float = 0f // 0 looks down -Z
    var playerPitchRad: Float = 0f

    // Weapon & animation state
    var adsBlend: Float = 0f
    var walkBobPhase: Float = 0f
    var moveSpeedRatio: Float = 0f
    var recoilKick: Float = 0f
    var muzzleFlashTimer: Float = 0f
    var timeSec: Float = 0f

    private var ammo: Int = 30
    private val magSize: Int = 30
    private var reserveAmmo: Int = 180
    private var isReloading: Boolean = false
    private var reloadTimer: Float = 0f
    private val reloadDuration: Float = 1.55f
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

    fun startNewMatch(selectedMode: GameMode) {
        mode = selectedMode
        isPaused = false
        pendingReset = true
    }

    fun requestReset() {
        isPaused = false
        pendingReset = true
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

        // FPS tracker
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

        // 1. Apply camera look deltas with user sensitivity & ADS scaling
        var dx = 0f
        var dy = 0f
        synchronized(lookLock) {
            dx = pendingLookDx
            dy = pendingLookDy
            pendingLookDx = 0f
            pendingLookDy = 0f
        }
        val sens = settings.sensitivity
        val baseSens = sens.lookSensitivity * (if (isAdsToggle) sens.adsSensitivity else 1f) * 0.0036f
        playerYawRad -= dx * baseSens * sens.xAxisMultiplier
        val ySign = if (sens.invertY) 1f else -1f
        playerPitchRad = (playerPitchRad + dy * baseSens * sens.yAxisMultiplier * ySign)
            .coerceIn(-1.22f, 1.22f)

        // Subtle Aim Assist toward closest visible enemy near crosshair center
        if (sens.aimAssist && (isFiring || isAdsToggle)) {
            applyGentleAimAssist(dt)
        }

        // 2. Movement, Crouch, Jump & Collision
        val forwardX = -sin(playerYawRad)
        val forwardZ = -cos(playerYawRad)
        val rightX = cos(playerYawRad)
        val rightZ = -sin(playerYawRad)

        val moveMag = sqrt(moveInputX * moveInputX + moveInputY * moveInputY).coerceAtMost(1f)
        val maxSpeed = when {
            isCrouching -> 2.8f
            isAdsToggle -> 3.4f
            else -> 5.6f
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

        // 3. Weapon ADS, Reload & Firing
        val adsTarget = if (isAdsToggle && !isReloading) 1f else 0f
        adsBlend += (adsTarget - adsBlend) * (dt * 14f).coerceAtMost(1f)
        recoilKick = (recoilKick - dt * 8.5f).coerceAtLeast(0f)
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
        if (isReloading) {
            reloadTimer += dt
            if (reloadTimer >= reloadDuration) {
                val needed = magSize - ammo
                val taken = if (mode == GameMode.RANGE) needed else minOf(needed, reserveAmmo)
                ammo += taken
                if (mode != GameMode.RANGE) reserveAmmo -= taken
                isReloading = false
                reloadTimer = 0f
            }
        }

        fireTimer = (fireTimer - dt).coerceAtLeast(0f)
        if (isFiring && !isReloading && fireTimer <= 0f) {
            if (ammo > 0) {
                firePlayerWeapon()
            } else {
                beginReload()
            }
        }

        // 4. Health/Armor Tactical Regen after taking cover
        damageCooldown = (damageCooldown - dt).coerceAtLeast(0f)
        if (damageCooldown <= 0f) {
            if (hp < 100f) hp = (hp + dt * 10f).coerceAtMost(100f)
            if (armor < 100f && hp >= 100f) armor = (armor + dt * 7f).coerceAtMost(100f)
        }

        // 5. Update Enemies & Waves
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

    private fun resetMatchInternal() {
        playerX = 0f
        playerZ = 14.5f
        playerY = 0f
        playerVelY = 0f
        eyeHeight = 1.65f
        playerYawRad = 0f
        playerPitchRad = 0f
        adsBlend = 0f
        isAdsToggle = false
        isCrouching = false
        isFiring = false
        recoilKick = 0f
        muzzleFlashTimer = 0f
        ammo = magSize
        reserveAmmo = 180
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
        if (isReloading || ammo >= magSize) return
        if (mode != GameMode.RANGE && reserveAmmo <= 0) return
        isReloading = true
        reloadTimer = 0f
        isAdsToggle = false
        soundEngine.playReload()
    }

    private fun firePlayerWeapon() {
        ammo--
        shotsFired++
        fireTimer = 0.115f
        recoilKick = (recoilKick + (if (isAdsToggle) 0.42f else 0.72f)).coerceAtMost(1.3f)
        muzzleFlashTimer = 1.0f
        playerPitchRad = (playerPitchRad + (if (isAdsToggle) 0.008f else 0.015f)).coerceIn(-1.22f, 1.22f)
        soundEngine.playRifleShot()
        if (settings.audio.hapticsEnabled) onHapticPulse(false)

        val camY = playerY + eyeHeight
        val spread = if (isAdsToggle) 0.003f else (0.014f + moveSpeedRatio * 0.014f)
        val jitterYaw = (rng.nextFloat() - 0.5f) * spread
        val jitterPitch = (rng.nextFloat() - 0.5f) * spread

        val yaw = playerYawRad + jitterYaw
        val pitch = playerPitchRad + jitterPitch
        val cosP = cos(pitch)
        val dirX = -sin(yaw) * cosP
        val dirY = sin(pitch)
        val dirZ = -cos(yaw) * cosP

        val wallDist = world.raycastWalls(playerX, camY, playerZ, dirX, dirY, dirZ, 75f)

        // Check anatomical Head & Body hitboxes for every alive enemy
        var bestEnemy: EnemyOperative? = null
        var bestDist = wallDist
        var bestHeadshot = false

        for (e in enemies) {
            if (e.isDead) continue
            // Head sphere: center (e.x, 1.67m, e.z), radius 0.19m (generous crisp tactical headshot)
            val headT = intersectRaySphere(playerX, camY, playerZ, dirX, dirY, dirZ, e.x, 1.67f, e.z, 0.21f)
            if (headT < bestDist) {
                bestDist = headT
                bestEnemy = e
                bestHeadshot = true
            }
            // Torso & Legs vertical cylinders approximated by two tight spheres (upper chest 1.22m, lower body 0.62m)
            val chestT = intersectRaySphere(playerX, camY, playerZ, dirX, dirY, dirZ, e.x, 1.20f, e.z, 0.34f)
            if (chestT < bestDist) {
                bestDist = chestT
                bestEnemy = e
                bestHeadshot = false
            }
            val lowerT = intersectRaySphere(playerX, camY, playerZ, dirX, dirY, dirZ, e.x, 0.60f, e.z, 0.30f)
            if (lowerT < bestDist) {
                bestDist = lowerT
                bestEnemy = e
                bestHeadshot = false
            }
        }

        if (bestEnemy != null) {
            shotsHit++
            val hitX = playerX + dirX * bestDist
            val hitY = camY + dirY * bestDist
            val hitZ = playerZ + dirZ * bestDist
            impacts.add(TracerEffect(hitX, hitY, hitZ, isEnemy = false, life = 0.22f))

            val dmg = if (bestHeadshot) 82f else 34f
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
                    reserveAmmo = (reserveAmmo + 18).coerceAtMost(240)
                }
            }
        } else if (wallDist < 74f) {
            val hitX = playerX + dirX * (wallDist - 0.05f)
            val hitY = camY + dirY * (wallDist - 0.05f)
            val hitZ = playerZ + dirZ * (wallDist - 0.05f)
            impacts.add(TracerEffect(hitX, hitY, hitZ, isEnemy = false, life = 0.18f))
        }

        if (ammo == 0 && (mode == GameMode.RANGE || reserveAmmo > 0)) {
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
            val ty = 1.42f - camY
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
            val pull = (dt * 4.5f).coerceAtMost(0.35f)
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
            e.yawDeg = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()

            if (mode == GameMode.RANGE) {
                // Range targets patrol laterally smoothly without shooting back
                e.strafeTimer -= dt
                if (e.strafeTimer <= 0f) {
                    e.strafeDir = -e.strafeDir
                    e.strafeTimer = 2.0f + rng.nextFloat() * 1.5f
                }
                val speed = if (e.soldierClass == SoldierClass.RECON) 2.2f else 1.1f
                val perpX = dz / dist
                val perpZ = -dx / dist
                val (ex, ez) = world.resolveHorizontalCollision(
                    e.x + perpX * e.strafeDir * speed * dt,
                    e.z + perpZ * e.strafeDir * speed * dt,
                    0.48f
                )
                e.x = ex
                e.z = ez
                e.isMoving = true
                e.walkPhase += dt * 6.5f
                continue
            }

            // Combat AI: advance to optimal engagement range & strafe tactically
            val desiredDist = when (e.soldierClass) {
                SoldierClass.HEAVY -> 7.5f
                SoldierClass.ASSAULT -> 11.5f
                SoldierClass.RECON -> 16.0f
            }
            val moveSpeed = when (e.soldierClass) {
                SoldierClass.HEAVY -> 2.1f
                SoldierClass.ASSAULT -> 3.0f
                SoldierClass.RECON -> 3.5f
            }

            e.strafeTimer -= dt
            if (e.strafeTimer <= 0f) {
                e.strafeDir = if (rng.nextBoolean()) 1f else -1f
                e.strafeTimer = 1.4f + rng.nextFloat() * 1.8f
            }

            var vx = 0f
            var vz = 0f
            if (dist > desiredDist + 1.5f) {
                vx += (dx / dist) * moveSpeed
                vz += (dz / dist) * moveSpeed
            } else if (dist < desiredDist - 3.0f) {
                vx -= (dx / dist) * (moveSpeed * 0.65f)
                vz -= (dz / dist) * (moveSpeed * 0.65f)
            }
            // Lateral strafe
            vx += (dz / dist) * e.strafeDir * (moveSpeed * 0.55f)
            vz += (-dx / dist) * e.strafeDir * (moveSpeed * 0.55f)

            val (ex, ez) = world.resolveHorizontalCollision(e.x + vx * dt, e.z + vz * dt, 0.48f)
            val moved = sqrt((ex - e.x) * (ex - e.x) + (ez - e.z) * (ez - e.z))
            e.x = ex
            e.z = ez
            e.isMoving = moved > 0.005f
            if (e.isMoving) {
                e.walkPhase += dt * (moveSpeed * 2.5f)
            }

            // Enemy firing logic with Line-of-Sight raycast
            e.fireCooldown -= dt
            if (e.fireCooldown <= 0f && dist < 34f) {
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
                    // Tracer flash partway toward player
                    impacts.add(
                        TracerEffect(
                            x = e.x + dirX * minOf(dist * 0.45f, 5f),
                            y = 1.42f + dirY * minOf(dist * 0.45f, 5f),
                            z = e.z + dirZ * minOf(dist * 0.45f, 5f),
                            isEnemy = true,
                            life = 0.14f
                        )
                    )
                    // Accuracy scales with distance and whether player is moving/crouching
                    val hitChance = (0.58f - dist * 0.012f - moveSpeedRatio * 0.16f - (if (isCrouching) 0.10f else 0f))
                        .coerceIn(0.14f, 0.58f)
                    if (rng.nextFloat() < hitChance) {
                        val rawDmg = when (e.soldierClass) {
                            SoldierClass.HEAVY -> 12f
                            SoldierClass.RECON -> 16f
                            SoldierClass.ASSAULT -> 9f
                        }
                        applyPlayerDamage(rawDmg)
                    }
                } else {
                    e.fireCooldown = 0.45f
                }
            }
        }

        // Handle wave clear or range respawn
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

    private fun updateDeadBodies(dt: Float) {
        for (e in enemies) {
            if (e.isDead) {
                e.deathProgress = (e.deathProgress + dt * 2.2f).coerceAtMost(1f)
            }
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
            GameMode.RANGE -> "ПОЛИГОН • ЦЕЛИ ГОТОВЫ"
            GameMode.ASSAULT -> "ВОЛНА $waveNum / $assaultTotalWaves"
            GameMode.SURVIVAL -> "ВОЛНА $waveNum"
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
            // Rotate into player's local forward/right frame for radar
            val localRight = dx * cosY - dz * sinY
            val localForward = -dx * sinY - dz * cosY
            blips += RadarBlip(
                relX = (localRight / 26f).coerceIn(-1f, 1f),
                relZ = (localForward / 26f).coerceIn(-1f, 1f),
                isElite = e.soldierClass == SoldierClass.HEAVY
            )
        }

        latestHud = HudSnapshot(
            hp = hp.toInt().coerceIn(0, 100),
            maxHp = 100,
            armor = armor.toInt().coerceIn(0, 100),
            ammo = ammo,
            magSize = magSize,
            reserveAmmo = if (mode == GameMode.RANGE) 999 else reserveAmmo,
            isReloading = isReloading,
            reloadProgress = if (isReloading) (reloadTimer / reloadDuration).coerceIn(0f, 1f) else 0f,
            isAds = isAdsToggle,
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
