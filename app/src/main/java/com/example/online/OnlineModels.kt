package com.example.online

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class PlayerProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val shortId: String = "",
    val displayName: String = "",
    val online: Boolean = false,
    val activeLobbyId: String = "",
    val totalKills: Int = 0,
    val totalHeadshots: Int = 0,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class PlayerIdReservation(
    @DocumentId val id: String = "",
    val shortId: String = "",
    val userId: String = "",
    val displayName: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FriendEntry(
    @DocumentId val id: String = "",
    val friendUid: String = "",
    val friendShortId: String = "",
    val friendName: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FriendRequest(
    @DocumentId val id: String = "",
    val fromUid: String = "",
    val fromShortId: String = "",
    val fromName: String = "",
    val toUid: String = "",
    val toShortId: String = "",
    val toName: String = "",
    val status: String = "pending",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class LobbyInvite(
    @DocumentId val id: String = "",
    val lobbyId: String = "",
    val fromUid: String = "",
    val fromName: String = "",
    val fromShortId: String = "",
    val toUid: String = "",
    val mapId: String = "MIRAGE",
    val mode: String = "BOMB_DEFUSAL",
    val status: String = "pending",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class MultiplayerLobby(
    @DocumentId val id: String = "",
    val hostUid: String = "",
    val hostName: String = "",
    val mapId: String = "MIRAGE",
    val mode: String = "BOMB_DEFUSAL",
    val roundsToWin: Int = 8,
    val allowBots: Boolean = false,
    val status: String = "waiting",
    val memberUids: List<String> = emptyList(),
    val tScore: Int = 0,
    val ctScore: Int = 0,
    val roundNumber: Int = 1,
    val bombState: String = "CARRIED",
    val bombX: Double = 0.0,
    val bombY: Double = 0.0,
    val bombZ: Double = 0.0,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class PlayerMatchState(
    @DocumentId val id: String = "",
    val userId: String = "",
    val shortId: String = "",
    val displayName: String = "",
    val team: String = "T",
    val ready: Boolean = true,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    val yawDeg: Double = 0.0,
    val pitchDeg: Double = 0.0,
    val hp: Int = 100,
    val dead: Boolean = false,
    val moving: Boolean = false,
    val shooting: Boolean = false,
    val weapon: String = "AK47",
    val kills: Int = 0,
    val deaths: Int = 0,
    val hitTargetUid: String = "",
    val hitDamage: Int = 0,
    val hitHeadshot: Boolean = false,
    val hitWeapon: String = "",
    val hitSeq: Int = 0,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class FriendWithPresence(
    val entry: FriendEntry,
    val online: Boolean = false,
    val activeLobbyId: String = ""
)
