package com.example.online

import android.os.SystemClock
import com.example.model.ArenaMapId
import com.example.model.GameMode
import com.example.model.OutgoingHitEvent
import com.example.model.TeamSide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Cloud online repository backed by Supabase PostgREST HTTPS API + RLS policies.
 * Handles player profiles (6-digit ID, editable nickname & avatar), friends by ID,
 * cloud lobby invites, 1v1 lobbies, and real-time match synchronization over the internet.
 */
class SupabaseOnlineRepository(
    private val authManager: SupabaseAuthManager
) {

    // -------------------------------------------------------------------------
    // 1. Player Profile & Customization (6-digit ID, Display Name, Avatar)
    // -------------------------------------------------------------------------

    suspend fun ensurePlayerProfile(userId: String, fallbackName: String): Result<PlayerProfile> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanName = sanitizeDisplayName(fallbackName)
                val deviceHash = authManager.deviceHash()

                // Primary path: atomic server RPC enforces strict latest version, device bans & server-HMAC device tokens
                val rpcRes = callRpc(
                    fnName = "ensure_my_profile",
                    body = JSONObject().apply {
                        put("p_display_name", cleanName)
                        put("p_device_hash", deviceHash)
                        put("p_version_code", com.example.BuildConfig.VERSION_CODE)
                        put("p_version_name", com.example.BuildConfig.VERSION_NAME)
                    }
                )
                if (rpcRes.isSuccess) {
                    val obj = parseSingleObject(rpcRes.getOrNull()?.trim().orEmpty())
                    if (obj != null && obj.optString("user_id").isNotBlank() && obj.optString("short_id").isNotBlank()) {
                        return@runCatching parseProfile(obj)
                    }
                } else {
                    val errMsg = rpcRes.exceptionOrNull()?.message.orEmpty()
                    if (errMsg.contains("DEVICE_BANNED", ignoreCase = true) ||
                        errMsg.contains("VERSION_MISMATCH", ignoreCase = true)
                    ) {
                        throw IllegalStateException(errMsg)
                    }
                }

                // Fallback path: check existing profile directly
                val existingResult = runCatching { fetchProfileByUid(userId) }
                if (existingResult.isSuccess) {
                    val existing = existingResult.getOrNull()
                    if (existing != null) {
                        setOnlineStatus(userId, true)
                        return@runCatching existing.copy(online = true)
                    }
                } else {
                    throw existingResult.exceptionOrNull()
                        ?: IllegalStateException("Не удалось загрузить облачный профиль.")
                }

                var lastError: Throwable? = null
                repeat(8) {
                    val candidateId = Random.nextInt(100_000, 1_000_000).toString()
                    val payload = JSONObject().apply {
                        put("user_id", userId)
                        put("short_id", candidateId)
                        put("display_name", cleanName)
                        put("avatar_data", AvatarPresets.DEFAULT_KEY)
                        put("online", true)
                    }
                    val resp = postRest("profiles", payload, prefer = "return=representation")
                    if (resp.isSuccess) {
                        val arr = runCatching { JSONArray(resp.getOrThrow()) }.getOrNull()
                        val first = arr?.optJSONObject(0)
                        if (first != null) return@runCatching parseProfile(first)
                        return@runCatching PlayerProfile(
                            id = userId,
                            userId = userId,
                            shortId = candidateId,
                            displayName = cleanName,
                            avatarData = AvatarPresets.DEFAULT_KEY,
                            online = true
                        )
                    } else {
                        val err = resp.exceptionOrNull()
                        lastError = err
                        val errMsg = err?.message.orEmpty()
                        if (errMsg.contains("DEVICE_BANNED", ignoreCase = true) ||
                            errMsg.contains("VERSION_MISMATCH", ignoreCase = true)
                        ) {
                            throw IllegalStateException(errMsg)
                        }
                        if (errMsg.contains("409") || errMsg.contains("duplicate key", ignoreCase = true) || errMsg.contains("23505")) {
                            val alreadyCreated = runCatching { fetchProfileByUid(userId) }.getOrNull()
                            if (alreadyCreated != null) {
                                setOnlineStatus(userId, true)
                                return@runCatching alreadyCreated.copy(online = true)
                            }
                        } else {
                            val rpcProfile = tryEnsureProfileViaRpc(cleanName)
                            if (rpcProfile != null) return@runCatching rpcProfile
                            throw err ?: IllegalStateException("Не удалось создать облачный профиль игрока.")
                        }
                    }
                }
                throw lastError ?: IllegalStateException("Не удалось создать облачный профиль игрока.")
            }
        }

    private suspend fun tryEnsureProfileViaRpc(cleanName: String): PlayerProfile? {
        val rpcRes = callRpc(
            fnName = "ensure_my_profile",
            body = JSONObject().apply {
                put("p_display_name", cleanName)
                put("p_device_hash", authManager.deviceHash())
                put("p_version_code", com.example.BuildConfig.VERSION_CODE)
                put("p_version_name", com.example.BuildConfig.VERSION_NAME)
            }
        )
        if (rpcRes.isFailure) return null
        val text = rpcRes.getOrNull()?.trim().orEmpty()
        if (text.isBlank()) return null
        val obj = parseSingleObject(text) ?: return null
        val uid = obj.optString("user_id")
        val sid = obj.optString("short_id")
        return if (uid.isNotBlank() && sid.isNotBlank()) parseProfile(obj) else null
    }

    suspend fun updatePlayerProfile(
        userId: String,
        rawDisplayName: String,
        avatarData: String
    ): Result<PlayerProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanName = rawDisplayName.trim()
            require(cleanName.length in 3..20) { "Имя игрока должно содержать от 3 до 20 символов." }
            require(DISPLAY_NAME_REGEX.matches(cleanName)) {
                "Имя может содержать только буквы, цифры, пробелы, «_» и «-»."
            }
            require(AvatarPresets.isValid(avatarData)) {
                "Некорректный формат или слишком большой размер аватарки."
            }

            val payload = JSONObject().apply {
                put("display_name", cleanName)
                put("avatar_data", avatarData)
                put("online", true)
            }
            val updatedJson = patchRest(
                table = "profiles",
                query = mapOf("user_id" to "eq.$userId"),
                body = payload,
                prefer = "return=representation"
            ).getOrThrow()

            // Also keep active lobby_members rows in sync so teammates/opponents see the new name & avatar
            runCatching {
                patchRest(
                    table = "lobby_members",
                    query = mapOf("user_id" to "eq.$userId"),
                    body = JSONObject().apply {
                        put("display_name", cleanName)
                        put("avatar_data", avatarData)
                    }
                )
            }

            val arr = runCatching { JSONArray(updatedJson) }.getOrNull()
            val first = arr?.optJSONObject(0)
            if (first != null) {
                parseProfile(first)
            } else {
                fetchProfileByUid(userId) ?: error("Профиль обновлён, но не удалось прочитать ответ.")
            }
        }
    }

    suspend fun setOnlineStatus(userId: String, online: Boolean) {
        withContext(Dispatchers.IO) {
            runCatching {
                val rpc = callRpc(
                    fnName = "ping_presence",
                    body = JSONObject().apply {
                        put("p_online", online)
                        put("p_device_hash", authManager.deviceHash())
                    }
                )
                if (rpc.isFailure) {
                    patchRest(
                        table = "profiles",
                        query = mapOf("user_id" to "eq.$userId"),
                        body = JSONObject().apply {
                            put("online", online)
                            put("updated_at", nowIsoUtc())
                        }
                    )
                }
            }
        }
    }

    suspend fun awardKillXp(kills: Int = 1, headshots: Int = 0): Result<PlayerProfile> =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = callRpc(
                    fnName = "award_kill_xp",
                    body = JSONObject().apply {
                        put("p_kills", kills.coerceIn(1, 5))
                        put("p_headshots", headshots.coerceIn(0, kills.coerceIn(1, 5)))
                    }
                ).getOrThrow().trim()
                val obj = parseSingleObject(raw) ?: error("Не удалось прочитать профиль после начисления EXP.")
                parseProfile(obj)
            }
        }

    suspend fun openSkinCase(skinId: String, duplicateRefund: Int): Result<PlayerProfile> =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = callRpc(
                    fnName = "open_skin_case",
                    body = JSONObject().apply {
                        put("p_skin_id", skinId.trim())
                        put("p_duplicate_refund", duplicateRefund.coerceIn(0, 120))
                    }
                ).getOrThrow().trim()
                val obj = parseSingleObject(raw) ?: error("Не удалось прочитать профиль после открытия кейса.")
                parseProfile(obj)
            }
        }

    suspend fun equipWeaponSkin(weaponName: String, skinId: String?): Result<PlayerProfile> =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = callRpc(
                    fnName = "equip_weapon_skin",
                    body = JSONObject().apply {
                        put("p_weapon", weaponName.trim().uppercase())
                        put("p_skin_id", skinId?.trim().orEmpty())
                    }
                ).getOrThrow().trim()
                val obj = parseSingleObject(raw) ?: error("Не удалось обновить экипированный скин.")
                parseProfile(obj)
            }
        }

    suspend fun adminGrantXp(targetShortIdRaw: String, xpAmount: Int): Result<PlayerProfile> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanId = targetShortIdRaw.trim().lowercase()
                require(isValidPlayerShortId(cleanId)) {
                    "Укажите 6-значный ID игрока или developer."
                }
                val safeXp = xpAmount.coerceIn(1, 500_000)
                val raw = callRpc(
                    fnName = "admin_grant_xp",
                    body = JSONObject().apply {
                        put("p_target_short_id", cleanId)
                        put("p_xp_amount", safeXp)
                    }
                ).getOrThrow().trim()
                val obj = parseSingleObject(raw) ?: error("Не удалось начислить EXP игроку.")
                parseProfile(obj)
            }
        }

    suspend fun adminGrantSkins(targetShortIdRaw: String, skinIds: List<String>): Result<PlayerProfile> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanId = targetShortIdRaw.trim().lowercase()
                require(isValidPlayerShortId(cleanId)) {
                    "Укажите 6-значный ID игрока или developer."
                }
                val cleanSkins = skinIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
                require(cleanSkins.isNotEmpty()) { "Выберите хотя бы один скин." }
                val arr = JSONArray()
                cleanSkins.forEach { arr.put(it) }
                val raw = callRpc(
                    fnName = "admin_grant_skins",
                    body = JSONObject().apply {
                        put("p_target_short_id", cleanId)
                        put("p_skin_ids", arr)
                    }
                ).getOrThrow().trim()
                val obj = parseSingleObject(raw) ?: error("Не удалось выдать скины игроку.")
                parseProfile(obj)
            }
        }

    suspend fun findPlayerByShortId(shortIdRaw: String): Result<PlayerProfile?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val shortId = shortIdRaw.trim().lowercase()
                require(isValidPlayerShortId(shortId)) {
                    "Введите 6-значный ID игрока (например, 482915) или developer."
                }
                val body = getRest(
                    table = "profiles",
                    query = mapOf(
                        "select" to "*",
                        "short_id" to "eq.$shortId",
                        "limit" to "1"
                    )
                ).getOrThrow()
                val arr = JSONArray(body)
                if (arr.length() == 0) null else parseProfile(arr.getJSONObject(0))
            }
        }

    suspend fun refreshMyProfile(userId: String): Result<PlayerProfile?> =
        withContext(Dispatchers.IO) {
            runCatching { fetchProfileByUid(userId) }
        }

    suspend fun adminSetAccountBan(
        targetShortIdRaw: String,
        banned: Boolean,
        reasonRaw: String = ""
    ): Result<PlayerProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val shortId = targetShortIdRaw.trim().lowercase()
            require(shortId != ADMIN_SHORT_ID) {
                "Нельзя заблокировать аккаунт администратора ($ADMIN_SHORT_ID)."
            }
            require(SHORT_ID_REGEX.matches(shortId)) {
                "ID игрока должен состоять из 6 цифр."
            }
            val cleanReason = reasonRaw.trim().take(120)
            val body = JSONObject().apply {
                put("p_target_short_id", shortId)
                put("p_banned", banned)
                put("p_reason", cleanReason)
            }
            val raw = callRpc("admin_set_ban", body).getOrThrow().trim()
            val obj = parseSingleObject(raw) ?: error("Не удалось прочитать ответ сервера.")
            parseProfile(obj)
        }
    }

    suspend fun adminSetDeviceBanByAccount(
        targetShortIdRaw: String,
        banned: Boolean,
        reasonRaw: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val shortId = targetShortIdRaw.trim().lowercase()
            require(shortId != ADMIN_SHORT_ID) {
                "Нельзя заблокировать устройство администратора ($ADMIN_SHORT_ID)."
            }
            require(SHORT_ID_REGEX.matches(shortId)) {
                "ID игрока должен состоять из 6 цифр."
            }
            val body = JSONObject().apply {
                put("p_target_short_id", shortId)
                put("p_banned", banned)
                put("p_reason", reasonRaw.trim().take(160))
            }
            val raw = callRpc("admin_set_device_ban", body).getOrThrow().trim()
            val obj = parseSingleObject(raw)
            obj?.optString("device_hash").orEmpty()
        }
    }

    suspend fun adminUnbanDeviceHash(deviceHash: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            callRpc(
                fnName = "admin_unban_device_hash",
                body = JSONObject().apply { put("p_device_hash", deviceHash.trim().lowercase()) }
            ).getOrThrow()
            Unit
        }
    }

    suspend fun adminFetchBannedDevices(): Result<List<BannedDeviceEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = callRpc("admin_list_banned_devices", JSONObject()).getOrThrow()
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        BannedDeviceEntry(
                            deviceHash = o.optString("device_hash"),
                            reason = o.optString("reason"),
                            bannedUid = o.optString("banned_uid"),
                            bannedShortId = o.optString("banned_short_id"),
                            bannedAt = o.optString("banned_at")
                        )
                    )
                }
            }
        }
    }

    suspend fun adminFetchBannedProfiles(): Result<List<PlayerProfile>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val rpc = callRpc("admin_list_banned", JSONObject())
                val raw = if (rpc.isSuccess) {
                    rpc.getOrThrow()
                } else {
                    getRest(
                        table = "profiles",
                        query = mapOf(
                            "select" to "*",
                            "banned" to "eq.true",
                            "order" to "updated_at.desc",
                            "limit" to "100"
                        )
                    ).getOrThrow()
                }
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        add(parseProfile(o))
                    }
                }
            }
        }

    suspend fun adminKickFromLobby(lobbyId: String, targetUid: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(UUID_REGEX.matches(lobbyId) && UUID_REGEX.matches(targetUid)) {
                    "Некорректные идентификаторы лобби или игрока."
                }
                val rpc = callRpc(
                    fnName = "admin_kick_member",
                    body = JSONObject().apply {
                        put("p_lobby_id", lobbyId)
                        put("p_target_uid", targetUid)
                    }
                )
                if (rpc.isFailure) {
                    // Fallback if admin is host and SQL migration wasn't run yet
                    deleteRest(
                        table = "lobby_members",
                        query = mapOf("lobby_id" to "eq.$lobbyId", "user_id" to "eq.$targetUid")
                    ).getOrThrow()
                }
                Unit
            }
        }

    // -------------------------------------------------------------------------
    // 2. Cloud Friends & Requests
    // -------------------------------------------------------------------------

    suspend fun fetchFriends(userId: String): Result<List<FriendEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = getRest(
                table = "friendships",
                query = mapOf("select" to "friend_uid", "user_id" to "eq.$userId")
            ).getOrThrow()
            val arr = JSONArray(raw)
            val uids = buildList {
                for (i in 0 until arr.length()) {
                    val fUid = arr.optJSONObject(i)?.optString("friend_uid").orEmpty()
                    if (fUid.isNotBlank()) add(fUid)
                }
            }
            if (uids.isEmpty()) return@runCatching emptyList()

            val profiles = fetchProfilesByUids(uids)
            uids.mapNotNull { fUid ->
                val p = profiles[fUid] ?: return@mapNotNull null
                FriendEntry(
                    friendUid = p.userId,
                    friendShortId = p.shortId,
                    friendName = p.displayName,
                    friendAvatarData = p.avatarData,
                    level = p.level,
                    online = p.online
                )
            }.sortedWith(compareByDescending<FriendEntry> { it.online }.thenBy { it.friendName.lowercase() })
        }
    }

    suspend fun fetchFriendRequests(userId: String): Result<Pair<List<FriendRequest>, List<FriendRequest>>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = getRest(
                    table = "friend_requests",
                    query = mapOf(
                        "select" to "*",
                        "status" to "eq.pending",
                        "or" to "(to_uid.eq.$userId,from_uid.eq.$userId)",
                        "order" to "created_at.desc"
                    )
                ).getOrThrow()
                val arr = JSONArray(raw)
                val uids = HashSet<String>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    uids.add(o.optString("from_uid"))
                    uids.add(o.optString("to_uid"))
                }
                val profiles = fetchProfilesByUids(uids.filter { it.isNotBlank() })

                val incoming = ArrayList<FriendRequest>()
                val outgoing = ArrayList<FriendRequest>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val fromUid = o.optString("from_uid")
                    val toUid = o.optString("to_uid")
                    val fromP = profiles[fromUid]
                    val toP = profiles[toUid]
                    val req = FriendRequest(
                        id = o.optString("id"),
                        fromUid = fromUid,
                        fromShortId = fromP?.shortId.orEmpty(),
                        fromName = fromP?.displayName ?: "Игрок",
                        fromAvatarData = fromP?.avatarData ?: AvatarPresets.DEFAULT_KEY,
                        fromLevel = fromP?.level ?: 1,
                        toUid = toUid,
                        toShortId = toP?.shortId.orEmpty(),
                        toName = toP?.displayName ?: "Игрок",
                        toAvatarData = toP?.avatarData ?: AvatarPresets.DEFAULT_KEY,
                        toLevel = toP?.level ?: 1,
                        status = o.optString("status", "pending")
                    )
                    if (toUid == userId) incoming.add(req) else if (fromUid == userId) outgoing.add(req)
                }
                incoming to outgoing
            }
        }

    suspend fun sendFriendRequest(fromUid: String, target: PlayerProfile): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(fromUid != target.userId) { "Нельзя добавить в друзья самого себя." }

                // Check if already friends
                val existingFriends = fetchFriends(fromUid).getOrDefault(emptyList())
                if (existingFriends.any { it.friendUid == target.userId }) {
                    error("${target.displayName} уже есть в вашем списке друзей.")
                }

                // Check if pending request already exists in either direction
                val (incoming, outgoing) = fetchFriendRequests(fromUid).getOrDefault(emptyList<FriendRequest>() to emptyList())
                if (outgoing.any { it.toUid == target.userId }) {
                    error("Заявка игроку ${target.displayName} уже отправлена.")
                }
                val existingIncoming = incoming.firstOrNull { it.fromUid == target.userId }
                if (existingIncoming != null) {
                    // Auto-accept mutual request!
                    respondFriendRequest(existingIncoming.id, accept = true).getOrThrow()
                    return@runCatching
                }

                val payload = JSONObject().apply {
                    put("from_uid", fromUid)
                    put("to_uid", target.userId)
                    put("status", "pending")
                }
                postRest("friend_requests", payload).getOrThrow()
                Unit
            }
        }

    suspend fun respondFriendRequest(requestId: String, accept: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (accept) {
                    callRpc(
                        fnName = "accept_friend_request",
                        body = JSONObject().apply { put("p_request_id", requestId) }
                    ).getOrThrow()
                } else {
                    patchRest(
                        table = "friend_requests",
                        query = mapOf("id" to "eq.$requestId"),
                        body = JSONObject().apply { put("status", "declined") }
                    ).getOrThrow()
                }
                Unit
            }
        }

    suspend fun removeFriend(friendUid: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            callRpc(
                fnName = "remove_friendship",
                body = JSONObject().apply { put("p_friend_uid", friendUid) }
            ).getOrThrow()
            Unit
        }
    }

    // -------------------------------------------------------------------------
    // 3. Cloud Multiplayer Lobbies, Invites & 1v1 Live Match Sync
    // -------------------------------------------------------------------------

    suspend fun createLobby(
        host: PlayerProfile,
        map: ArenaMapId,
        mode: GameMode,
        roundsToWin: Int,
        allowBots: Boolean,
        team: TeamSide
    ): Result<MultiplayerLobby> = withContext(Dispatchers.IO) {
        runCatching {
            val lobbyBody = JSONObject().apply {
                put("host_uid", host.userId)
                put("host_name", host.displayName)
                put("map_id", map.name)
                put("mode", mode.name)
                put("rounds_to_win", roundsToWin.coerceIn(1, 15))
                put("allow_bots", allowBots)
                put("status", "waiting")
            }
            val resp = postRest("lobbies", lobbyBody, prefer = "return=representation").getOrThrow()
            val obj = JSONArray(resp).getJSONObject(0)
            val lobbyId = obj.getString("id")

            val memberBody = JSONObject().apply {
                put("lobby_id", lobbyId)
                put("user_id", host.userId)
                put("short_id", host.shortId)
                put("display_name", host.displayName)
                put("avatar_data", host.avatarData)
                put("level", host.level.coerceAtLeast(1))
                put("equipped_skins", JSONObject(host.equippedSkins))
                put("team", team.name)
                put("weapon", if (team == TeamSide.CT) "USP_S" else "GLOCK_18")
            }
            val insertRes = postRest(
                table = "lobby_members",
                body = memberBody,
                prefer = "resolution=merge-duplicates,return=minimal"
            )
            if (insertRes.isFailure) {
                // Backward-compatible fallback if v0.07 SQL migration not yet run
                memberBody.remove("level")
                memberBody.remove("equipped_skins")
                postRest(
                    table = "lobby_members",
                    body = memberBody,
                    prefer = "resolution=merge-duplicates,return=minimal"
                ).getOrThrow()
            }

            parseLobby(obj, listOf(host.userId))
        }
    }

    suspend fun joinLobby(
        lobbyId: String,
        profile: PlayerProfile,
        preferredTeam: TeamSide
    ): Result<Pair<MultiplayerLobby, List<PlayerMatchState>>> = withContext(Dispatchers.IO) {
        runCatching {
            val (lobby, existingMembers) = fetchLobbySnapshot(lobbyId).getOrThrow()
                ?: error("Лобби уже закрыто или не найдено.")

            // For crisp 1v1 UX: auto-assign opposite side if preferred side has players and opposite is empty!
            val tCount = existingMembers.count { it.team == TeamSide.T.name && it.userId != profile.userId }
            val ctCount = existingMembers.count { it.team == TeamSide.CT.name && it.userId != profile.userId }
            val assignedTeam = when {
                tCount > 0 && ctCount == 0 -> TeamSide.CT
                ctCount > 0 && tCount == 0 -> TeamSide.T
                else -> preferredTeam
            }

            val memberBody = JSONObject().apply {
                put("lobby_id", lobbyId)
                put("user_id", profile.userId)
                put("short_id", profile.shortId)
                put("display_name", profile.displayName)
                put("avatar_data", profile.avatarData)
                put("level", profile.level.coerceAtLeast(1))
                put("equipped_skins", JSONObject(profile.equippedSkins))
                put("team", assignedTeam.name)
                put("weapon", if (assignedTeam == TeamSide.CT) "USP_S" else "GLOCK_18")
            }
            val insertRes = postRest(
                table = "lobby_members",
                body = memberBody,
                prefer = "resolution=merge-duplicates,return=minimal"
            )
            if (insertRes.isFailure) {
                memberBody.remove("level")
                memberBody.remove("equipped_skins")
                postRest(
                    table = "lobby_members",
                    body = memberBody,
                    prefer = "resolution=merge-duplicates,return=minimal"
                ).getOrThrow()
            }

            fetchLobbySnapshot(lobbyId).getOrThrow()
                ?: (lobby to existingMembers)
        }
    }

    suspend fun fetchLobbySnapshot(lobbyId: String): Result<Pair<MultiplayerLobby, List<PlayerMatchState>>?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val lobbyRaw = getRest(
                    table = "lobbies",
                    query = mapOf("select" to "*", "id" to "eq.$lobbyId", "limit" to "1")
                ).getOrThrow()
                val lobbyArr = JSONArray(lobbyRaw)
                if (lobbyArr.length() == 0) return@runCatching null

                val membersRaw = getRest(
                    table = "lobby_members",
                    query = mapOf("select" to "*", "lobby_id" to "eq.$lobbyId")
                ).getOrThrow()
                val membersArr = JSONArray(membersRaw)
                val members = ArrayList<PlayerMatchState>(membersArr.length())
                for (i in 0 until membersArr.length()) {
                    val o = membersArr.optJSONObject(i) ?: continue
                    members.add(parseMemberState(o))
                }
                val sorted = members.sortedBy { it.displayName.lowercase() }
                val lobby = parseLobby(lobbyArr.getJSONObject(0), sorted.map { it.userId })
                lobby to sorted
            }
        }

    /**
     * Ultra-lightweight single-GET match tick fetch omitting base64 avatar blobs.
     * Returns parsed member states paired with measured round-trip time (RTT) in ms.
     */
    suspend fun fetchLobbyMembersFast(lobbyId: String): Result<Pair<List<PlayerMatchState>, Int>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val startMs = SystemClock.elapsedRealtime()
                val membersRaw = getRest(
                    table = "lobby_members",
                    query = mapOf(
                        "select" to FAST_MEMBER_SELECT,
                        "lobby_id" to "eq.$lobbyId"
                    )
                ).getOrThrow()
                val rttMs = (SystemClock.elapsedRealtime() - startMs).toInt().coerceIn(1, 999)
                val membersArr = JSONArray(membersRaw)
                val members = ArrayList<PlayerMatchState>(membersArr.length())
                for (i in 0 until membersArr.length()) {
                    val o = membersArr.optJSONObject(i) ?: continue
                    members.add(parseMemberState(o))
                }
                members.sortedBy { it.displayName.lowercase() } to rttMs
            }
        }

    /**
     * Single-RPC lobby liveness & roster check during live WebSocket matches (zero 65KB avatar re-downloads).
     * Falls back cleanly to ultra-lean REST column projection if the RPC is not yet installed.
     */
    suspend fun fetchLobbyHeartbeat(lobbyId: String): Result<Pair<MultiplayerLobby, List<PlayerMatchState>>?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val rpc = callRpc(
                    fnName = "get_lobby_heartbeat",
                    body = JSONObject().apply { put("p_lobby_id", lobbyId) }
                )
                if (rpc.isSuccess) {
                    val raw = rpc.getOrThrow().trim()
                    if (raw.isBlank() || raw == "null") return@runCatching null
                    val obj = parseSingleObject(raw) ?: return@runCatching null
                    if (obj.optBoolean("closed", false)) return@runCatching null
                    val lobbyObj = obj.optJSONObject("lobby") ?: return@runCatching null
                    val membersArr = obj.optJSONArray("members") ?: JSONArray()
                    val members = ArrayList<PlayerMatchState>(membersArr.length())
                    for (i in 0 until membersArr.length()) {
                        val mo = membersArr.optJSONObject(i) ?: continue
                        members.add(parseMemberState(mo))
                    }
                    val sorted = members.sortedBy { it.displayName.lowercase() }
                    return@runCatching parseLobby(lobbyObj, sorted.map { it.userId }) to sorted
                }

                // Lean fallback omitting avatar_data & equipped_skins blobs
                val lobbyRaw = getRest(
                    table = "lobbies",
                    query = mapOf(
                        "select" to "id,host_uid,host_name,map_id,mode,rounds_to_win,allow_bots,status",
                        "id" to "eq.$lobbyId",
                        "limit" to "1"
                    )
                ).getOrThrow()
                val lobbyArr = JSONArray(lobbyRaw)
                if (lobbyArr.length() == 0) return@runCatching null

                val membersRaw = getRest(
                    table = "lobby_members",
                    query = mapOf(
                        "select" to "user_id,short_id,display_name,level,team,kills,deaths,dead",
                        "lobby_id" to "eq.$lobbyId"
                    )
                ).getOrThrow()
                val membersArr = JSONArray(membersRaw)
                val members = ArrayList<PlayerMatchState>(membersArr.length())
                for (i in 0 until membersArr.length()) {
                    val o = membersArr.optJSONObject(i) ?: continue
                    members.add(parseMemberState(o))
                }
                val sorted = members.sortedBy { it.displayName.lowercase() }
                parseLobby(lobbyArr.getJSONObject(0), sorted.map { it.userId }) to sorted
            }
        }

    suspend fun updateLobbyConfig(
        lobbyId: String,
        map: ArenaMapId,
        mode: GameMode,
        roundsToWin: Int,
        allowBots: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            patchRest(
                table = "lobbies",
                query = mapOf("id" to "eq.$lobbyId"),
                body = JSONObject().apply {
                    put("map_id", map.name)
                    put("mode", mode.name)
                    put("rounds_to_win", roundsToWin.coerceIn(1, 15))
                    put("allow_bots", allowBots)
                }
            ).getOrThrow()
            Unit
        }
    }

    suspend fun switchMyTeam(lobbyId: String, userId: String, side: TeamSide): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                patchRest(
                    table = "lobby_members",
                    query = mapOf("lobby_id" to "eq.$lobbyId", "user_id" to "eq.$userId"),
                    body = JSONObject().apply {
                        put("team", side.name)
                        put("weapon", if (side == TeamSide.CT) "USP_S" else "GLOCK_18")
                    }
                ).getOrThrow()
                Unit
            }
        }

    suspend fun startLobbyMatch(lobbyId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            patchRest(
                table = "lobbies",
                query = mapOf("id" to "eq.$lobbyId"),
                body = JSONObject().apply { put("status", "playing") }
            ).getOrThrow()
            Unit
        }
    }

    suspend fun leaveLobby(lobbyId: String, userId: String, isHost: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                deleteRest(
                    table = "lobby_members",
                    query = mapOf("lobby_id" to "eq.$lobbyId", "user_id" to "eq.$userId")
                )
                if (isHost) {
                    deleteRest(
                        table = "lobbies",
                        query = mapOf("id" to "eq.$lobbyId")
                    )
                }
                Unit
            }
        }

    suspend fun sendLobbyInvite(
        lobby: MultiplayerLobby,
        fromUid: String,
        toUid: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(fromUid != toUid) { "Нельзя пригласить самого себя." }
            // Remove stale pending invites for this lobby & target to avoid duplicates
            runCatching {
                deleteRest(
                    table = "lobby_invites",
                    query = mapOf(
                        "lobby_id" to "eq.${lobby.id}",
                        "to_uid" to "eq.$toUid",
                        "status" to "eq.pending"
                    )
                )
            }
            val payload = JSONObject().apply {
                put("lobby_id", lobby.id)
                put("from_uid", fromUid)
                put("to_uid", toUid)
                put("map_id", lobby.mapId)
                put("status", "pending")
            }
            postRest("lobby_invites", payload).getOrThrow()
            Unit
        }
    }

    suspend fun fetchIncomingInvites(userId: String): Result<List<LobbyInvite>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = getRest(
                    table = "lobby_invites",
                    query = mapOf(
                        "select" to "*",
                        "to_uid" to "eq.$userId",
                        "status" to "eq.pending",
                        "order" to "created_at.desc",
                        "limit" to "5"
                    )
                ).getOrThrow()
                val arr = JSONArray(raw)
                if (arr.length() == 0) return@runCatching emptyList()

                val senderUids = (0 until arr.length()).mapNotNull {
                    arr.optJSONObject(it)?.optString("from_uid")?.takeIf { u -> u.isNotBlank() }
                }.distinct()
                val profiles = fetchProfilesByUids(senderUids)

                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val fromUid = o.optString("from_uid")
                        val sender = profiles[fromUid]
                        add(
                            LobbyInvite(
                                id = o.optString("id"),
                                lobbyId = o.optString("lobby_id"),
                                fromUid = fromUid,
                                fromShortId = sender?.shortId.orEmpty(),
                                fromName = sender?.displayName ?: "Игрок",
                                fromAvatarData = sender?.avatarData ?: AvatarPresets.DEFAULT_KEY,
                                fromLevel = sender?.level ?: 1,
                                toUid = o.optString("to_uid"),
                                mapId = o.optString("map_id", "MIRAGE"),
                                status = o.optString("status", "pending")
                            )
                        )
                    }
                }
            }
        }

    suspend fun respondLobbyInvite(inviteId: String, accept: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                patchRest(
                    table = "lobby_invites",
                    query = mapOf("id" to "eq.$inviteId"),
                    body = JSONObject().apply { put("status", if (accept) "accepted" else "declined") }
                ).getOrThrow()
                Unit
            }
        }

    suspend fun publishMatchTick(
        lobbyId: String,
        userId: String,
        team: TeamSide,
        x: Float,
        y: Float,
        z: Float,
        yawDeg: Float,
        pitchDeg: Float,
        hp: Int,
        dead: Boolean,
        moving: Boolean,
        shooting: Boolean,
        weaponName: String,
        kills: Int,
        deaths: Int,
        outgoingHit: OutgoingHitEvent?
    ): Int? = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("team", team.name)
            put("x", x.toDouble())
            put("y", y.toDouble())
            put("z", z.toDouble())
            put("yaw_deg", yawDeg.toDouble())
            put("pitch_deg", pitchDeg.toDouble())
            put("hp", hp.coerceIn(0, 100))
            put("dead", dead)
            put("moving", moving)
            put("shooting", shooting)
            put("weapon", weaponName.take(32))
            put("kills", kills.coerceIn(0, 500))
            put("deaths", deaths.coerceIn(0, 500))
            if (outgoingHit != null) {
                put("hit_target_uid", outgoingHit.targetUid.take(64))
                put("hit_damage", outgoingHit.damage.coerceIn(0, 200))
                put("hit_headshot", outgoingHit.headshot)
                put("hit_weapon", outgoingHit.weaponName.take(32))
                put("hit_seq", outgoingHit.seq.coerceIn(0, 1_000_000))
            }
        }
        val startMs = SystemClock.elapsedRealtime()
        val res = patchRest(
            table = "lobby_members",
            query = mapOf("lobby_id" to "eq.$lobbyId", "user_id" to "eq.$userId"),
            body = payload
        )
        if (res.isSuccess) {
            (SystemClock.elapsedRealtime() - startMs).toInt().coerceIn(1, 999)
        } else {
            null
        }
    }

    // -------------------------------------------------------------------------
    // Internal PostgREST Helpers
    // -------------------------------------------------------------------------

    private suspend fun fetchProfileByUid(userId: String): PlayerProfile? {
        val raw = getRest(
            table = "profiles",
            query = mapOf("select" to "*", "user_id" to "eq.$userId", "limit" to "1")
        ).getOrThrow()
        val arr = JSONArray(raw)
        return if (arr.length() == 0) null else parseProfile(arr.getJSONObject(0))
    }

    private suspend fun fetchProfilesByUids(uids: List<String>): Map<String, PlayerProfile> {
        val safeUids = uids.distinct().filter { UUID_REGEX.matches(it) }
        if (safeUids.isEmpty()) return emptyMap()
        val inClause = "in.(${safeUids.joinToString(",")})"
        val raw = getRest(
            table = "profiles",
            query = mapOf("select" to "*", "user_id" to inClause)
        ).getOrThrow()
        val arr = JSONArray(raw)
        val map = HashMap<String, PlayerProfile>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val p = parseProfile(o)
            map[p.userId] = p
        }
        return map
    }

    private suspend fun getRest(table: String, query: Map<String, String>): Result<String> {
        val urlBuilder = "${SupabaseConfig.baseUrl}/rest/v1/$table".toHttpUrlOrNull()?.newBuilder()
            ?: return Result.failure(IllegalStateException("Некорректный адрес SUPABASE_URL."))
        query.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        val url = urlBuilder.build()
        return executeAuthedCall { token ->
            Request.Builder()
                .url(url)
                .addHeader("apikey", SupabaseConfig.anonKey)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
        }
    }

    private suspend fun postRest(
        table: String,
        body: JSONObject,
        prefer: String = "return=minimal"
    ): Result<String> {
        val bodyStr = body.toString()
        return executeAuthedCall { token ->
            Request.Builder()
                .url("${SupabaseConfig.baseUrl}/rest/v1/$table")
                .addHeader("apikey", SupabaseConfig.anonKey)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", prefer)
                .post(bodyStr.toRequestBody(JSON_MEDIA))
                .build()
        }
    }

    private suspend fun patchRest(
        table: String,
        query: Map<String, String>,
        body: JSONObject,
        prefer: String = "return=minimal"
    ): Result<String> {
        val urlBuilder = "${SupabaseConfig.baseUrl}/rest/v1/$table".toHttpUrlOrNull()?.newBuilder()
            ?: return Result.failure(IllegalStateException("Некорректный адрес SUPABASE_URL."))
        query.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        val url = urlBuilder.build()
        val bodyStr = body.toString()
        return executeAuthedCall { token ->
            Request.Builder()
                .url(url)
                .addHeader("apikey", SupabaseConfig.anonKey)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", prefer)
                .patch(bodyStr.toRequestBody(JSON_MEDIA))
                .build()
        }
    }

    private suspend fun deleteRest(table: String, query: Map<String, String>): Result<String> {
        val urlBuilder = "${SupabaseConfig.baseUrl}/rest/v1/$table".toHttpUrlOrNull()?.newBuilder()
            ?: return Result.failure(IllegalStateException("Некорректный адрес SUPABASE_URL."))
        query.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        val url = urlBuilder.build()
        return executeAuthedCall { token ->
            Request.Builder()
                .url(url)
                .addHeader("apikey", SupabaseConfig.anonKey)
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build()
        }
    }

    private suspend fun callRpc(fnName: String, body: JSONObject): Result<String> {
        val bodyStr = body.toString()
        return executeAuthedCall { token ->
            Request.Builder()
                .url("${SupabaseConfig.baseUrl}/rest/v1/rpc/$fnName")
                .addHeader("apikey", SupabaseConfig.anonKey)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(bodyStr.toRequestBody(JSON_MEDIA))
                .build()
        }
    }

    private suspend fun executeAuthedCall(buildRequest: (token: String) -> Request): Result<String> {
        val token = authManager.getValidAccessToken()
            ?: return Result.failure(IllegalStateException("Сессия истекла. Войдите в аккаунт снова."))
        val first = executeCallRaw(buildRequest(token))
        if (first.code == 401) {
            val refreshed = authManager.forceRefreshAccessToken()
            if (!refreshed.isNullOrBlank() && refreshed != token) {
                return finishCallResult(executeCallRaw(buildRequest(refreshed)))
            }
        }
        return finishCallResult(first)
    }

    private data class RawHttpResp(val code: Int, val body: String, val exception: Throwable? = null)

    private fun executeCallRaw(request: Request): RawHttpResp {
        return try {
            SupabaseConfig.httpClient.newCall(request).execute().use { resp ->
                RawHttpResp(code = resp.code, body = resp.body?.string().orEmpty())
            }
        } catch (t: Throwable) {
            RawHttpResp(code = -1, body = "", exception = t)
        }
    }

    private fun finishCallResult(raw: RawHttpResp): Result<String> {
        if (raw.exception != null) {
            return Result.failure(
                IllegalStateException("Ошибка сети: ${raw.exception.localizedMessage ?: "нет подключения"}")
            )
        }
        if (raw.code in 200..299) {
            return Result.success(raw.body)
        }
        val json = runCatching { JSONObject(raw.body) }.getOrNull()
        val rawMsg = listOf(
            json?.optString("message").orEmpty(),
            json?.optString("details").orEmpty(),
            json?.optString("hint").orEmpty(),
            json?.optString("code").orEmpty()
        ).firstOrNull { it.isNotBlank() } ?: raw.body.trim().take(160)

        val friendly = when {
            rawMsg.contains("does not exist", ignoreCase = true) ||
                rawMsg.contains("Could not find the table", ignoreCase = true) ||
                rawMsg.contains("PGRST205", ignoreCase = true) ||
                raw.code == 404 ->
                "Таблицы Supabase не найдены (HTTP ${raw.code}). Выполните скрипт supabase_schema.sql в SQL Editor вашего проекта Supabase."
            raw.code == 401 ->
                "Ошибка авторизации Supabase (HTTP 401): $rawMsg. Попробуйте выйти и войти снова."
            raw.code == 403 || rawMsg.contains("row-level security", ignoreCase = true) -> {
                val detail = if (rawMsg.isNotBlank()) " ($rawMsg)" else ""
                "Нет прав доступа RLS (HTTP ${raw.code})$detail. Выполните полный скрипт supabase_schema.sql в SQL Editor."
            }
            rawMsg.isNotBlank() -> "Ошибка сервера (HTTP ${raw.code}): $rawMsg"
            else -> "Ошибка сервера (HTTP ${raw.code})"
        }
        return Result.failure(IllegalStateException(friendly))
    }

    private fun parseSingleObject(raw: String): JSONObject? = runCatching {
        if (raw.startsWith("[")) JSONArray(raw).optJSONObject(0) else JSONObject(raw)
    }.getOrNull()

    private fun parseProfile(o: JSONObject): PlayerProfile {
        val rawOnline = o.optBoolean("online", false)
        val lastSeenStr = o.optString("last_seen_at", "").ifBlank { o.optString("updated_at", "") }
        val lastSeenMs = parseIsoTimestampMs(lastSeenStr)
        val nowMs = System.currentTimeMillis()
        // Strict real-time presence: player is only online if flagged online AND heartbeat was within 55 seconds!
        val isTrulyOnline = rawOnline && (lastSeenMs > 0L && (nowMs - lastSeenMs) in -60_000L..ONLINE_PRESENCE_TTL_MS)

        val ownedList = ArrayList<String>()
        val ownedArr = o.optJSONArray("owned_skins")
        if (ownedArr != null) {
            for (i in 0 until ownedArr.length()) {
                val sid = ownedArr.optString(i).trim()
                if (sid.isNotBlank() && !ownedList.contains(sid)) ownedList.add(sid)
            }
        }

        val equippedMap = LinkedHashMap<String, String>()
        val eqObj = o.optJSONObject("equipped_skins")
        if (eqObj != null) {
            val keys = eqObj.keys()
            while (keys.hasNext()) {
                val wKey = keys.next()
                val sVal = eqObj.optString(wKey).trim()
                if (wKey.isNotBlank() && sVal.isNotBlank()) {
                    equippedMap[wKey] = sVal
                }
            }
        }

        val xp = o.optLong("xp", 0L).coerceAtLeast(0L)
        val level = o.optInt("level", 1).coerceAtLeast(1)

        return PlayerProfile(
            id = o.optString("user_id"),
            userId = o.optString("user_id"),
            shortId = o.optString("short_id"),
            displayName = o.optString("display_name", "Игрок"),
            avatarData = o.optString("avatar_data", AvatarPresets.DEFAULT_KEY)
                .ifBlank { AvatarPresets.DEFAULT_KEY },
            online = isTrulyOnline,
            lastSeenAtMs = lastSeenMs,
            totalKills = o.optInt("total_kills", 0),
            totalHeadshots = o.optInt("total_headshots", 0),
            xp = xp,
            level = level,
            coins = o.optInt("coins", 0).coerceAtLeast(0),
            ownedSkins = ownedList,
            equippedSkins = equippedMap,
            banned = o.optBoolean("banned", false),
            banReason = o.optString("ban_reason", ""),
            lastDeviceHash = o.optString("last_device_hash", ""),
            deviceBanned = o.optBoolean("device_banned", false)
        )
    }

    private fun parseLobby(o: JSONObject, memberUids: List<String>): MultiplayerLobby = MultiplayerLobby(
        id = o.optString("id"),
        hostUid = o.optString("host_uid"),
        hostName = o.optString("host_name", "Хост"),
        mapId = o.optString("map_id", "MIRAGE"),
        mode = o.optString("mode", "BOMB_DEFUSAL"),
        roundsToWin = o.optInt("rounds_to_win", 8).coerceIn(1, 15),
        allowBots = o.optBoolean("allow_bots", false),
        status = o.optString("status", "waiting"),
        memberUids = memberUids
    )

    private fun parseMemberState(o: JSONObject): PlayerMatchState {
        val equippedMap = LinkedHashMap<String, String>()
        val eqObj = o.optJSONObject("equipped_skins")
        if (eqObj != null) {
            val keys = eqObj.keys()
            while (keys.hasNext()) {
                val wKey = keys.next()
                val sVal = eqObj.optString(wKey).trim()
                if (wKey.isNotBlank() && sVal.isNotBlank()) equippedMap[wKey] = sVal
            }
        }
        val weaponName = o.optString("weapon", "GLOCK_18")
        return PlayerMatchState(
            id = o.optString("user_id"),
            userId = o.optString("user_id"),
            shortId = o.optString("short_id"),
            displayName = o.optString("display_name", "Игрок"),
            avatarData = o.optString("avatar_data", AvatarPresets.DEFAULT_KEY)
                .ifBlank { AvatarPresets.DEFAULT_KEY },
            level = o.optInt("level", 1).coerceAtLeast(1),
            equippedSkins = equippedMap,
            activeSkinId = equippedMap[weaponName].orEmpty(),
            team = if (o.optString("team") == TeamSide.CT.name) TeamSide.CT.name else TeamSide.T.name,
            ready = o.optBoolean("ready", true),
            x = o.optDouble("x", 0.0),
            y = o.optDouble("y", 0.0),
            z = o.optDouble("z", 0.0),
            yawDeg = o.optDouble("yaw_deg", 0.0),
            pitchDeg = o.optDouble("pitch_deg", 0.0),
            hp = o.optInt("hp", 100).coerceIn(0, 100),
            dead = o.optBoolean("dead", false),
            moving = o.optBoolean("moving", false),
            shooting = o.optBoolean("shooting", false),
            weapon = weaponName,
            kills = o.optInt("kills", 0),
            deaths = o.optInt("deaths", 0),
            hitTargetUid = o.optString("hit_target_uid", ""),
            hitDamage = o.optInt("hit_damage", 0),
            hitHeadshot = o.optBoolean("hit_headshot", false),
            hitWeapon = o.optString("hit_weapon", ""),
            hitSeq = o.optInt("hit_seq", 0)
        )
    }

    private fun parseIsoTimestampMs(raw: String): Long {
        if (raw.isBlank()) return 0L
        return runCatching {
            // Normalize PostgREST timestamptz e.g. "2026-11-24T12:34:56.789123+00:00" or "...Z"
            val cleaned = raw.trim().replace(" ", "T")
            val tzRegex = Regex("([+-]\\d{2}):(\\d{2})$")
            val normalized = when {
                cleaned.endsWith("Z") -> cleaned.dropLast(1) + "+0000"
                tzRegex.containsMatchIn(cleaned) -> tzRegex.replace(cleaned, "$1$2")
                else -> cleaned + "+0000"
            }
            // Trim sub-second precision to 3 digits for SimpleDateFormat
            val dotIdx = normalized.indexOf('.')
            val tzIdx = normalized.lastIndexOfAny(charArrayOf('+', '-'))
            val trimmed = if (dotIdx in 1 until tzIdx) {
                val frac = normalized.substring(dotIdx + 1, tzIdx).take(3).padEnd(3, '0')
                normalized.substring(0, dotIdx + 1) + frac + normalized.substring(tzIdx)
            } else {
                normalized
            }
            val pattern = if (trimmed.contains('.')) "yyyy-MM-dd'T'HH:mm:ss.SSSZ" else "yyyy-MM-dd'T'HH:mm:ssZ"
            val fmt = java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            fmt.parse(trimmed)?.time ?: 0L
        }.getOrDefault(0L)
    }

    private fun nowIsoUtc(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        return fmt.format(java.util.Date())
    }

    private fun sanitizeDisplayName(raw: String): String {
        val cleaned = raw.trim().filter {
            it.isLetterOrDigit() || it == '_' || it == '-' || it == ' '
        }.take(20).trim()
        return if (cleaned.length >= 3) cleaned else "Player_${Random.nextInt(100, 999)}"
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val ONLINE_PRESENCE_TTL_MS = 55_000L
        private const val FAST_MEMBER_SELECT =
            "user_id,short_id,display_name,team,ready,x,y,z,yaw_deg,pitch_deg,hp,dead,moving,shooting,weapon,kills,deaths,hit_target_uid,hit_damage,hit_headshot,hit_weapon,hit_seq"
        private val SHORT_ID_REGEX = Regex("^[1-9][0-9]{5}$")
        private val DISPLAY_NAME_REGEX = Regex("^[a-zA-Z0-9_\\- а-яА-ЯёЁ]+$")
        private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
