package com.example.online

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
                val existing = fetchProfileByUid(userId)
                if (existing != null) {
                    setOnlineStatus(userId, true)
                    return@runCatching existing.copy(online = true)
                }

                val cleanName = sanitizeDisplayName(fallbackName)
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
                        // If a concurrent call already created our profile (409 / duplicate user_id), read and return it immediately
                        if (errMsg.contains("409") || errMsg.contains("duplicate key", ignoreCase = true) || errMsg.contains("23505")) {
                            val alreadyCreated = runCatching { fetchProfileByUid(userId) }.getOrNull()
                            if (alreadyCreated != null) {
                                setOnlineStatus(userId, true)
                                return@runCatching alreadyCreated.copy(online = true)
                            }
                            // Otherwise short_id collided -> continue loop with a fresh 6-digit ID
                        } else {
                            // Non-conflict error (e.g. missing profiles table, RLS policy, network) -> fail fast so UI shows reason
                            throw err ?: IllegalStateException("Не удалось создать облачный профиль игрока.")
                        }
                    }
                }
                throw lastError ?: IllegalStateException("Не удалось создать облачный профиль игрока.")
            }
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
                patchRest(
                    table = "profiles",
                    query = mapOf("user_id" to "eq.$userId"),
                    body = JSONObject().apply { put("online", online) }
                )
            }
        }
    }

    suspend fun findPlayerByShortId(shortIdRaw: String): Result<PlayerProfile?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val shortId = shortIdRaw.trim()
                require(SHORT_ID_REGEX.matches(shortId)) {
                    "ID игрока должен состоять из 6 цифр (например, 482915)."
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
                        toUid = toUid,
                        toShortId = toP?.shortId.orEmpty(),
                        toName = toP?.displayName ?: "Игрок",
                        toAvatarData = toP?.avatarData ?: AvatarPresets.DEFAULT_KEY,
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
                put("team", team.name)
                put("weapon", if (team == TeamSide.CT) "USP_S" else "GLOCK_18")
            }
            postRest(
                table = "lobby_members",
                body = memberBody,
                prefer = "resolution=merge-duplicates,return=minimal"
            ).getOrThrow()

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
                put("team", assignedTeam.name)
                put("weapon", if (assignedTeam == TeamSide.CT) "USP_S" else "GLOCK_18")
            }
            postRest(
                table = "lobby_members",
                body = memberBody,
                prefer = "resolution=merge-duplicates,return=minimal"
            ).getOrThrow()

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
    ) {
        withContext(Dispatchers.IO) {
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
            patchRest(
                table = "lobby_members",
                query = mapOf("lobby_id" to "eq.$lobbyId", "user_id" to "eq.$userId"),
                body = payload
            )
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
            raw.code == 403 || rawMsg.contains("row-level security", ignoreCase = true) ->
                "Нет прав доступа RLS (HTTP ${raw.code}). Выполните полный скрипт supabase_schema.sql в SQL Editor."
            rawMsg.isNotBlank() -> "Ошибка сервера (HTTP ${raw.code}): $rawMsg"
            else -> "Ошибка сервера (HTTP ${raw.code})"
        }
        return Result.failure(IllegalStateException(friendly))
    }

    private fun parseProfile(o: JSONObject): PlayerProfile = PlayerProfile(
        id = o.optString("user_id"),
        userId = o.optString("user_id"),
        shortId = o.optString("short_id"),
        displayName = o.optString("display_name", "Игрок"),
        avatarData = o.optString("avatar_data", AvatarPresets.DEFAULT_KEY)
            .ifBlank { AvatarPresets.DEFAULT_KEY },
        online = o.optBoolean("online", false),
        totalKills = o.optInt("total_kills", 0),
        totalHeadshots = o.optInt("total_headshots", 0)
    )

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

    private fun parseMemberState(o: JSONObject): PlayerMatchState = PlayerMatchState(
        id = o.optString("user_id"),
        userId = o.optString("user_id"),
        shortId = o.optString("short_id"),
        displayName = o.optString("display_name", "Игрок"),
        avatarData = o.optString("avatar_data", AvatarPresets.DEFAULT_KEY)
            .ifBlank { AvatarPresets.DEFAULT_KEY },
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
        weapon = o.optString("weapon", "GLOCK_18"),
        kills = o.optInt("kills", 0),
        deaths = o.optInt("deaths", 0),
        hitTargetUid = o.optString("hit_target_uid", ""),
        hitDamage = o.optInt("hit_damage", 0),
        hitHeadshot = o.optBoolean("hit_headshot", false),
        hitWeapon = o.optString("hit_weapon", ""),
        hitSeq = o.optInt("hit_seq", 0)
    )

    private fun sanitizeDisplayName(raw: String): String {
        val cleaned = raw.trim().filter {
            it.isLetterOrDigit() || it == '_' || it == '-' || it == ' '
        }.take(20).trim()
        return if (cleaned.length >= 3) cleaned else "Player_${Random.nextInt(100, 999)}"
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val SHORT_ID_REGEX = Regex("^[1-9][0-9]{5}$")
        private val DISPLAY_NAME_REGEX = Regex("^[a-zA-Z0-9_\\- а-яА-ЯёЁ]+$")
        private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
