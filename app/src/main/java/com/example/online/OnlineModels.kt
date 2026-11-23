package com.example.online

/**
 * Built-in tactical avatar preset identifiers.
 * Gallery photos are stored as compact JPEG data URLs: "data:image/jpeg;base64,..."
 */
object AvatarPresets {
    const val GHOST = "preset:ghost"
    const val WOLF = "preset:wolf"
    const val SNIPER = "preset:sniper"
    const val SHIELD = "preset:shield"
    const val SKULL = "preset:skull"
    const val EAGLE = "preset:eagle"
    const val PHOENIX = "preset:phoenix"
    const val CYBER = "preset:cyber"

    const val DEFAULT_KEY = GHOST

    data class PresetInfo(
        val key: String,
        val labelRu: String
    )

    val all: List<PresetInfo> = listOf(
        PresetInfo(GHOST, "ПРИЗРАК"),
        PresetInfo(WOLF, "ВОЛК"),
        PresetInfo(SNIPER, "СНАЙПЕР"),
        PresetInfo(SHIELD, "ЩИТ"),
        PresetInfo(SKULL, "ЧЕРЕП"),
        PresetInfo(EAGLE, "ОРЁЛ"),
        PresetInfo(PHOENIX, "ФЕНИКС"),
        PresetInfo(CYBER, "КИБЕР")
    )

    fun isValid(avatarData: String): Boolean {
        if (all.any { it.key == avatarData }) return true
        return avatarData.startsWith("data:image/jpeg;base64,") && avatarData.length <= 65_000
    }
}

const val ADMIN_SHORT_ID: String = "932760"

/**
 * Cloud human player profile stored in Supabase.
 * Every real human player has a permanent unique 6-digit numeric ID (bots never have IDs).
 */
data class PlayerProfile(
    val id: String = "",
    val userId: String = "",
    val shortId: String = "",
    val displayName: String = "",
    val avatarData: String = AvatarPresets.DEFAULT_KEY,
    val online: Boolean = true,
    val totalKills: Int = 0,
    val totalHeadshots: Int = 0,
    val banned: Boolean = false,
    val banReason: String = ""
) {
    val isAdmin: Boolean
        get() = shortId == ADMIN_SHORT_ID
}

/**
 * Mutual cloud friend entry hydrated with live nickname, avatar, and online status.
 */
data class FriendEntry(
    val friendUid: String = "",
    val friendShortId: String = "",
    val friendName: String = "",
    val friendAvatarData: String = AvatarPresets.DEFAULT_KEY,
    val online: Boolean = false
)

/**
 * Pending incoming or outgoing friend request.
 */
data class FriendRequest(
    val id: String = "",
    val fromUid: String = "",
    val fromShortId: String = "",
    val fromName: String = "",
    val fromAvatarData: String = AvatarPresets.DEFAULT_KEY,
    val toUid: String = "",
    val toShortId: String = "",
    val toName: String = "",
    val toAvatarData: String = AvatarPresets.DEFAULT_KEY,
    val status: String = "pending"
)

/**
 * Active cloud multiplayer lobby (defaults to 1v1 / friends-only, host can toggle bots).
 */
data class MultiplayerLobby(
    val id: String = "",
    val hostUid: String = "",
    val hostName: String = "",
    val mapId: String = "MIRAGE",
    val mode: String = "BOMB_DEFUSAL",
    val roundsToWin: Int = 8,
    val allowBots: Boolean = false,
    val status: String = "waiting", // "waiting" | "playing" | "finished"
    val memberUids: List<String> = emptyList()
)

/**
 * Cloud invitation sent from a lobby host/member to a friend.
 */
data class LobbyInvite(
    val id: String = "",
    val lobbyId: String = "",
    val fromUid: String = "",
    val fromShortId: String = "",
    val fromName: String = "",
    val fromAvatarData: String = AvatarPresets.DEFAULT_KEY,
    val toUid: String = "",
    val mapId: String = "MIRAGE",
    val status: String = "pending"
)

/**
 * Real-time synchronized state of a human player inside an online multiplayer lobby/match.
 */
data class PlayerMatchState(
    val id: String = "",
    val userId: String = "",
    val shortId: String = "",
    val displayName: String = "",
    val avatarData: String = AvatarPresets.DEFAULT_KEY,
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
    val weapon: String = "GLOCK_18",
    val kills: Int = 0,
    val deaths: Int = 0,
    val hitTargetUid: String = "",
    val hitDamage: Int = 0,
    val hitHeadshot: Boolean = false,
    val hitWeapon: String = "",
    val hitSeq: Int = 0
)
