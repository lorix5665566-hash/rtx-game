package com.example.online

import android.content.Context
import com.example.R
import com.example.model.ArenaMapId
import com.example.model.GameMode
import com.example.model.OutgoingHitEvent
import com.example.model.TeamSide
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID
import kotlin.random.Random

/**
 * Authenticated Firestore repository for Vanguard RTX online features:
 * - Permanent unique 6-digit numeric Player ID for every human user (never assigned to bots)
 * - Friends search by 6-digit ID, friend requests, and friend list
 * - Live multiplayer lobbies (1v1 or up to 5v5), T/CT team selection, host bots toggle,
 *   live invites, and real-time match state sync.
 *
 * CRITICAL: Always uses the named database via `R.string.firestore_database_id`.
 */
class OnlineRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    private fun requireUserId(): String {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrBlank()) {
            throw IllegalStateException("Требуется вход через Google для сетевых функций.")
        }
        return uid
    }

    private fun sanitizeDisplayName(raw: String?): String {
        val cleaned = (raw ?: "").trim().replace(Regex("\\s+"), " ")
        return when {
            cleaned.isEmpty() -> "Игрок"
            cleaned.length > 24 -> cleaned.take(24)
            else -> cleaned
        }
    }

    private fun isValidShortId(shortId: String): Boolean =
        shortId.length == 6 && shortId[0] in '1'..'9' && shortId.all { it in '0'..'9' }

    // --- 1. HUMAN PLAYER PROFILE & UNIQUE 6-DIGIT ID ---

    suspend fun ensurePlayerProfile(rawDisplayName: String?): Result<PlayerProfile> {
        var docPath = "players/${auth.currentUser?.uid ?: "unauthenticated"}"
        return try {
            val uid = requireUserId()
            docPath = "players/$uid"
            val playerRef = db.collection("players").document(uid)
            val existingSnap = playerRef.get().await()
            if (existingSnap.exists()) {
                val profile = existingSnap.toObject(
                    PlayerProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                if (profile != null && isValidShortId(profile.shortId)) {
                    playerRef.update(
                        mapOf(
                            "online" to true,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                    return Result.success(profile.copy(online = true))
                }
            }

            val displayName = sanitizeDisplayName(rawDisplayName ?: auth.currentUser?.displayName)
            var claimedShortId: String? = null
            for (attempt in 0 until 12) {
                val candidate = Random.nextInt(100_000, 1_000_000).toString()
                val idRef = db.collection("playerIds").document(candidate)
                val idSnap = idRef.get().await()
                if (!idSnap.exists()) {
                    val reservationPayload = mapOf(
                        "shortId" to candidate,
                        "userId" to uid,
                        "displayName" to displayName,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                    val claimed = runCatching {
                        idRef.set(reservationPayload).await()
                        true
                    }.getOrDefault(false)
                    if (claimed) {
                        claimedShortId = candidate
                        break
                    }
                }
            }

            val finalShortId = claimedShortId
                ?: throw IllegalStateException("Не удалось выделить уникальный 6-значный ID. Повторите попытку.")

            val profilePayload = mapOf(
                "userId" to uid,
                "shortId" to finalShortId,
                "displayName" to displayName,
                "online" to true,
                "activeLobbyId" to "",
                "totalKills" to 0,
                "totalHeadshots" to 0,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            playerRef.set(profilePayload).await()
            Result.success(
                PlayerProfile(
                    id = uid,
                    userId = uid,
                    shortId = finalShortId,
                    displayName = displayName,
                    online = true
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    fun observeMyProfile(uid: String): Flow<PlayerProfile?> = flow {
        if (uid.isBlank() || auth.currentUser?.uid != uid) {
            emit(null)
            return@flow
        }
        val path = "players/$uid"
        emitAll(
            db.collection("players").document(uid)
                .snapshots()
                .map { snap ->
                    if (!snap.exists()) null
                    else snap.toObject(PlayerProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    suspend fun updatePresence(online: Boolean, activeLobbyId: String? = null): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.success(Unit)
        val path = "players/$uid"
        return try {
            val updates = mutableMapOf<String, Any>(
                "online" to online,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (activeLobbyId != null) {
                updates["activeLobbyId"] = activeLobbyId
            }
            db.collection("players").document(uid).update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun recordOnlineStats(addKills: Int, addHeadshots: Int): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.success(Unit)
        if (addKills <= 0 && addHeadshots <= 0) return Result.success(Unit)
        val path = "players/$uid"
        return try {
            val updates = mapOf<String, Any>(
                "totalKills" to FieldValue.increment(addKills.coerceIn(0, 500).toLong()),
                "totalHeadshots" to FieldValue.increment(addHeadshots.coerceIn(0, 500).toLong()),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("players").document(uid).update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    // --- 2. SEARCH PLAYER BY 6-DIGIT ID & FRIENDS SYSTEM ---

    suspend fun findPlayerByShortId(rawShortId: String): Result<PlayerProfile?> {
        val shortId = rawShortId.trim()
        if (!isValidShortId(shortId)) {
            return Result.failure(IllegalArgumentException("Введите корректный 6-значный ID (например, 482915)"))
        }
        val path = "playerIds/$shortId"
        return try {
            requireUserId()
            val idSnap = db.collection("playerIds").document(shortId).get().await()
            if (!idSnap.exists()) return Result.success(null)
            val reservation = idSnap.toObject(
                PlayerIdReservation::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: return Result.success(null)
            if (reservation.userId.isBlank()) return Result.success(null)

            val profileSnap = db.collection("players").document(reservation.userId).get().await()
            val profile = profileSnap.toObject(
                PlayerProfile::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            Result.success(
                profile ?: PlayerProfile(
                    id = reservation.userId,
                    userId = reservation.userId,
                    shortId = reservation.shortId,
                    displayName = reservation.displayName
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    suspend fun sendFriendRequest(myProfile: PlayerProfile, target: PlayerProfile): Result<Unit> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        if (target.userId.isBlank() || target.userId == uid) {
            return Result.failure(IllegalArgumentException("Нельзя добавить самого себя в друзья."))
        }
        if (!isValidShortId(myProfile.shortId) || !isValidShortId(target.shortId)) {
            return Result.failure(IllegalArgumentException("Некорректный ID игрока."))
        }
        val requestId = "${uid}_${target.userId}"
        val path = "friendRequests/$requestId"
        return try {
            // Check if already friends
            val alreadyFriend = db.collection("players").document(uid)
                .collection("friends").document(target.userId).get().await()
            if (alreadyFriend.exists()) {
                return Result.failure(IllegalStateException("${target.displayName} уже в вашем списке друзей."))
            }

            val reqRef = db.collection("friendRequests").document(requestId)
            val existingReq = runCatching { reqRef.get().await() }.getOrNull()
            if (existingReq != null && existingReq.exists()) {
                if (existingReq.getString("status") == "pending") {
                    return Result.failure(IllegalStateException("Заявка игроку ${target.displayName} уже отправлена."))
                }
                reqRef.delete().await()
            }

            val payload = mapOf(
                "fromUid" to uid,
                "fromShortId" to myProfile.shortId,
                "fromName" to sanitizeDisplayName(myProfile.displayName),
                "toUid" to target.userId,
                "toShortId" to target.shortId,
                "toName" to sanitizeDisplayName(target.displayName),
                "status" to "pending",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            reqRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun respondFriendRequest(
        req: FriendRequest,
        myProfile: PlayerProfile,
        accept: Boolean
    ): Result<Unit> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        if (req.id.isBlank() || req.toUid != uid) {
            return Result.failure(IllegalArgumentException("Некорректная заявка в друзья."))
        }
        val reqPath = "friendRequests/${req.id}"
        return try {
            val batch = db.batch()
            val reqRef = db.collection("friendRequests").document(req.id)
            batch.update(
                reqRef,
                mapOf(
                    "status" to if (accept) "accepted" else "declined",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            if (accept) {
                val myFriendRef = db.collection("players").document(uid)
                    .collection("friends").document(req.fromUid)
                batch.set(
                    myFriendRef,
                    mapOf(
                        "friendUid" to req.fromUid,
                        "friendShortId" to req.fromShortId,
                        "friendName" to sanitizeDisplayName(req.fromName),
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
                val otherFriendRef = db.collection("players").document(req.fromUid)
                    .collection("friends").document(uid)
                batch.set(
                    otherFriendRef,
                    mapOf(
                        "friendUid" to uid,
                        "friendShortId" to myProfile.shortId,
                        "friendName" to sanitizeDisplayName(myProfile.displayName),
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, reqPath)
            Result.failure(e)
        }
    }

    suspend fun removeFriend(friendUid: String): Result<Unit> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        if (friendUid.isBlank()) return Result.failure(IllegalArgumentException("Некорректный ID друга."))
        val path = "players/$uid/friends/$friendUid"
        return try {
            val batch = db.batch()
            batch.delete(db.collection("players").document(uid).collection("friends").document(friendUid))
            batch.delete(db.collection("players").document(friendUid).collection("friends").document(uid))
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    fun observeFriends(uid: String): Flow<List<FriendEntry>> = flow {
        if (uid.isBlank() || auth.currentUser?.uid != uid) {
            emit(emptyList())
            return@flow
        }
        val path = "players/$uid/friends"
        emitAll(
            db.collection("players").document(uid).collection("friends")
                .snapshots()
                .map { snap ->
                    snap.toObjects(FriendEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.friendName.lowercase() }
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    val toLog = fe ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeFriendPresence(friendUid: String): Flow<PlayerProfile?> = flow {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrBlank() || friendUid.isBlank()) {
            emit(null)
            return@flow
        }
        val path = "players/$friendUid"
        emitAll(
            db.collection("players").document(friendUid)
                .snapshots()
                .map { snap ->
                    if (!snap.exists()) null
                    else snap.toObject(PlayerProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    val toLog = fe ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.GET, path)
                    }
                    emit(null)
                }
        )
    }

    fun observeIncomingFriendRequests(uid: String): Flow<List<FriendRequest>> = flow {
        if (uid.isBlank() || auth.currentUser?.uid != uid) {
            emit(emptyList())
            return@flow
        }
        val path = "friendRequests"
        emitAll(
            db.collection("friendRequests")
                .whereEqualTo("toUid", uid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(FriendRequest::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .filter { it.status == "pending" }
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    val toLog = fe ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeOutgoingFriendRequests(uid: String): Flow<List<FriendRequest>> = flow {
        if (uid.isBlank() || auth.currentUser?.uid != uid) {
            emit(emptyList())
            return@flow
        }
        val path = "friendRequests"
        emitAll(
            db.collection("friendRequests")
                .whereEqualTo("fromUid", uid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(FriendRequest::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .filter { it.status == "pending" }
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    val toLog = fe ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    // --- 3. LIVE LOBBY INVITES ---

    suspend fun sendLobbyInvite(
        lobby: MultiplayerLobby,
        myProfile: PlayerProfile,
        friendUid: String
    ): Result<Unit> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        if (lobby.id.isBlank() || friendUid.isBlank() || friendUid == uid) {
            return Result.failure(IllegalArgumentException("Некорректные данные приглашения."))
        }
        val inviteId = "${lobby.id}_${friendUid}"
        val path = "invites/$inviteId"
        return try {
            val invRef = db.collection("invites").document(inviteId)
            val existingInv = runCatching { invRef.get().await() }.getOrNull()
            if (existingInv != null && existingInv.exists()) {
                runCatching { invRef.delete().await() }
            }
            val payload = mapOf(
                "lobbyId" to lobby.id,
                "fromUid" to uid,
                "fromName" to sanitizeDisplayName(myProfile.displayName),
                "fromShortId" to myProfile.shortId,
                "toUid" to friendUid,
                "mapId" to lobby.mapId,
                "mode" to lobby.mode,
                "status" to "pending",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            invRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun respondLobbyInvite(invite: LobbyInvite, accept: Boolean): Result<Unit> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        if (invite.id.isBlank() || invite.toUid != uid) {
            return Result.failure(IllegalArgumentException("Некорректное приглашение."))
        }
        val path = "invites/${invite.id}"
        return try {
            db.collection("invites").document(invite.id).update(
                mapOf(
                    "status" to if (accept) "accepted" else "declined",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    fun observeIncomingInvites(uid: String): Flow<List<LobbyInvite>> = flow {
        if (uid.isBlank() || auth.currentUser?.uid != uid) {
            emit(emptyList())
            return@flow
        }
        val path = "invites"
        emitAll(
            db.collection("invites")
                .whereEqualTo("toUid", uid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(LobbyInvite::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .filter { it.status == "pending" }
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    val toLog = fe ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    // --- 4. MULTIPLAYER LOBBIES & REAL-TIME PLAYER STATES ---

    suspend fun createLobby(
        myProfile: PlayerProfile,
        map: ArenaMapId,
        mode: GameMode,
        roundsToWin: Int,
        allowBots: Boolean,
        initialTeam: TeamSide
    ): Result<String> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        val lobbyId = "lobby_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val path = "lobbies/$lobbyId"
        return try {
            val lobbyPayload = mapOf(
                "hostUid" to uid,
                "hostName" to sanitizeDisplayName(myProfile.displayName),
                "mapId" to map.name,
                "mode" to mode.name,
                "roundsToWin" to roundsToWin.coerceIn(1, 15),
                "allowBots" to allowBots,
                "status" to "waiting",
                "memberUids" to listOf(uid),
                "tScore" to 0,
                "ctScore" to 0,
                "roundNumber" to 1,
                "bombState" to "CARRIED",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("lobbies").document(lobbyId).set(lobbyPayload).await()

            val statePayload = buildInitialPlayerStatePayload(uid, myProfile, initialTeam)
            db.collection("lobbies").document(lobbyId)
                .collection("states").document(uid).set(statePayload).await()

            updatePresence(online = true, activeLobbyId = lobbyId)
            Result.success(lobbyId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun joinLobby(
        lobbyId: String,
        myProfile: PlayerProfile,
        preferredTeam: TeamSide
    ): Result<Unit> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        if (lobbyId.isBlank()) return Result.failure(IllegalArgumentException("Некорректный ID лобби."))
        val path = "lobbies/$lobbyId"
        return try {
            val lobbyRef = db.collection("lobbies").document(lobbyId)
            val snap = lobbyRef.get().await()
            if (!snap.exists()) {
                return Result.failure(IllegalStateException("Лобби больше не существует."))
            }
            val lobby = snap.toObject(MultiplayerLobby::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: return Result.failure(IllegalStateException("Ошибка чтения лобби."))
            if (lobby.status == "finished") {
                return Result.failure(IllegalStateException("Матч в этом лобби уже завершён."))
            }
            if (!lobby.memberUids.contains(uid)) {
                if (lobby.memberUids.size >= 10) {
                    return Result.failure(IllegalStateException("Лобби заполнено (максимум 10 игроков)."))
                }
                lobbyRef.update(
                    mapOf(
                        "memberUids" to FieldValue.arrayUnion(uid),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            val stateRef = lobbyRef.collection("states").document(uid)
            stateRef.set(buildInitialPlayerStatePayload(uid, myProfile, preferredTeam)).await()
            updatePresence(online = true, activeLobbyId = lobbyId)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun leaveLobby(lobbyId: String): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.success(Unit)
        if (lobbyId.isBlank()) return Result.success(Unit)
        val path = "lobbies/$lobbyId"
        return try {
            val lobbyRef = db.collection("lobbies").document(lobbyId)
            val snap = lobbyRef.get().await()
            if (snap.exists()) {
                val lobby = snap.toObject(MultiplayerLobby::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                runCatching { lobbyRef.collection("states").document(uid).delete().await() }
                if (lobby != null) {
                    if (lobby.hostUid == uid && lobby.memberUids.size <= 1) {
                        lobbyRef.delete().await()
                    } else if (lobby.memberUids.contains(uid) && lobby.memberUids.size > 1) {
                        lobbyRef.update(
                            mapOf(
                                "memberUids" to FieldValue.arrayRemove(uid),
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                    }
                }
            }
            updatePresence(online = true, activeLobbyId = "")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateLobbyConfig(
        lobbyId: String,
        map: ArenaMapId,
        mode: GameMode,
        roundsToWin: Int,
        allowBots: Boolean
    ): Result<Unit> {
        val path = "lobbies/$lobbyId"
        return try {
            requireUserId()
            db.collection("lobbies").document(lobbyId).update(
                mapOf(
                    "mapId" to map.name,
                    "mode" to mode.name,
                    "roundsToWin" to roundsToWin.coerceIn(1, 15),
                    "allowBots" to allowBots,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun startLobbyMatch(lobbyId: String): Result<Unit> {
        val path = "lobbies/$lobbyId"
        return try {
            requireUserId()
            db.collection("lobbies").document(lobbyId).update(
                mapOf(
                    "status" to "playing",
                    "tScore" to 0,
                    "ctScore" to 0,
                    "roundNumber" to 1,
                    "bombState" to "CARRIED",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun switchMyTeam(lobbyId: String, side: TeamSide): Result<Unit> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        val path = "lobbies/$lobbyId/states/$uid"
        return try {
            db.collection("lobbies").document(lobbyId)
                .collection("states").document(uid)
                .update(
                    mapOf(
                        "team" to side.name,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun publishLiveMatchState(
        lobbyId: String,
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
    ): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.success(Unit)
        val path = "lobbies/$lobbyId/states/$uid"
        return try {
            val updates = mutableMapOf<String, Any>(
                "team" to team.name,
                "x" to x.toDouble(),
                "y" to y.toDouble(),
                "z" to z.toDouble(),
                "yawDeg" to yawDeg.toDouble(),
                "pitchDeg" to pitchDeg.toDouble(),
                "hp" to hp.coerceIn(0, 100),
                "dead" to dead,
                "moving" to moving,
                "shooting" to shooting,
                "weapon" to weaponName.take(32),
                "kills" to kills.coerceIn(0, 500),
                "deaths" to deaths.coerceIn(0, 500),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (outgoingHit != null) {
                updates["hitSeq"] = outgoingHit.seq.coerceIn(0, 1_000_000)
                updates["hitTargetUid"] = outgoingHit.targetUid.take(128)
                updates["hitDamage"] = outgoingHit.damage.coerceIn(0, 200)
                updates["hitHeadshot"] = outgoingHit.headshot
                updates["hitWeapon"] = outgoingHit.weaponName.take(32)
            }
            db.collection("lobbies").document(lobbyId)
                .collection("states").document(uid)
                .update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    fun observeLobby(lobbyId: String): Flow<MultiplayerLobby?> = flow {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrBlank() || lobbyId.isBlank()) {
            emit(null)
            return@flow
        }
        val path = "lobbies/$lobbyId"
        emitAll(
            db.collection("lobbies").document(lobbyId)
                .snapshots()
                .map { snap ->
                    if (!snap.exists()) null
                    else snap.toObject(MultiplayerLobby::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    val toLog = fe ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    /**
     * Observes each member's live PlayerMatchState via direct DocumentReference.snapshots()
     * (compliant with `allow get` / `allow list: if false` on `/lobbies/{lobbyId}/states/{playerUid}`).
     */
    fun observeLobbyMemberStates(lobbyId: String, memberUids: List<String>): Flow<List<PlayerMatchState>> {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrBlank() || lobbyId.isBlank() || memberUids.isEmpty()) {
            return flowOf(emptyList())
        }
        val flows = memberUids.distinct().take(10).map { memberUid ->
            val path = "lobbies/$lobbyId/states/$memberUid"
            db.collection("lobbies").document(lobbyId)
                .collection("states").document(memberUid)
                .snapshots()
                .map { snap ->
                    if (!snap.exists()) null
                    else snap.toObject(PlayerMatchState::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    val toLog = fe ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.GET, path)
                    }
                    emit(null)
                }
        }
        return combine(flows) { arr -> arr.filterNotNull() }
    }

    private fun buildInitialPlayerStatePayload(
        uid: String,
        profile: PlayerProfile,
        team: TeamSide
    ): Map<String, Any> = mapOf(
        "userId" to uid,
        "shortId" to profile.shortId,
        "displayName" to sanitizeDisplayName(profile.displayName),
        "team" to team.name,
        "ready" to true,
        "x" to 0.0,
        "y" to 0.0,
        "z" to 0.0,
        "yawDeg" to 0.0,
        "pitchDeg" to 0.0,
        "hp" to 100,
        "dead" to false,
        "moving" to false,
        "shooting" to false,
        "weapon" to if (team == TeamSide.CT) "USP_S" else "GLOCK_18",
        "kills" to 0,
        "deaths" to 0,
        "hitSeq" to 0,
        "updatedAt" to FieldValue.serverTimestamp()
    )
}
