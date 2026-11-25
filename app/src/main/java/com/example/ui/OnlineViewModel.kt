package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.model.AdminActionEvent
import com.example.model.ArenaMapId
import com.example.model.BombSyncEvent
import com.example.model.GameMode
import com.example.model.KillFeedSyncEvent
import com.example.model.LevelProgression
import com.example.model.MatchSyncEvent
import com.example.model.OutgoingHitEvent
import com.example.model.SkinCase
import com.example.model.SkinCatalog
import com.example.model.TeamSide
import com.example.model.WeaponSkin
import com.example.model.WeaponType
import com.example.online.ADMIN_SHORT_ID
import com.example.online.BannedDeviceEntry
import com.example.online.ClientAccessStatus
import com.example.online.FriendEntry
import com.example.online.FriendRequest
import com.example.online.LobbyInvite
import com.example.online.MultiplayerLobby
import com.example.online.PlayerMatchState
import com.example.online.PlayerProfile
import com.example.online.SupabaseAuthManager
import com.example.online.SupabaseOnlineRepository
import com.example.online.SupabaseRealtimeClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

sealed interface OnlineUiState<out T> {
    data object Loading : OnlineUiState<Nothing>
    data class Success<T>(val data: T) : OnlineUiState<T>
    data class Error(val message: String) : OnlineUiState<Nothing>
}

/**
 * Cloud OnlineViewModel backed by SupabaseOnlineRepository (zero Google Services).
 * Synchronizes player profile customization, cloud friends/requests, lobby invites,
 * 1v1 lobbies, and real-time match ticks.
 */
class OnlineViewModel(
    val currentUserId: String,
    private val initialLoginName: String,
    private val repository: SupabaseOnlineRepository,
    private val authManager: SupabaseAuthManager
) : ViewModel() {

    private val _profileState = MutableStateFlow<OnlineUiState<PlayerProfile>>(OnlineUiState.Loading)
    val profileState: StateFlow<OnlineUiState<PlayerProfile>> = _profileState.asStateFlow()

    private val _friendsState = MutableStateFlow<OnlineUiState<List<FriendEntry>>>(OnlineUiState.Loading)
    val friendsState: StateFlow<OnlineUiState<List<FriendEntry>>> = _friendsState.asStateFlow()

    private val _incomingRequests = MutableStateFlow<List<FriendRequest>>(emptyList())
    val incomingRequests: StateFlow<List<FriendRequest>> = _incomingRequests.asStateFlow()

    private val _outgoingRequests = MutableStateFlow<List<FriendRequest>>(emptyList())
    val outgoingRequests: StateFlow<List<FriendRequest>> = _outgoingRequests.asStateFlow()

    private val _incomingInvites = MutableStateFlow<List<LobbyInvite>>(emptyList())
    val incomingInvites: StateFlow<List<LobbyInvite>> = _incomingInvites.asStateFlow()

    private val _searchResult = MutableStateFlow<PlayerProfile?>(null)
    val searchResult: StateFlow<PlayerProfile?> = _searchResult.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _isSavingProfile = MutableStateFlow(false)
    val isSavingProfile: StateFlow<Boolean> = _isSavingProfile.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _activeLobbyId = MutableStateFlow<String?>(null)
    val activeLobbyId: StateFlow<String?> = _activeLobbyId.asStateFlow()

    private val _activeLobby = MutableStateFlow<MultiplayerLobby?>(null)
    val activeLobby: StateFlow<MultiplayerLobby?> = _activeLobby.asStateFlow()

    private val _lobbyMemberStates = MutableStateFlow<List<PlayerMatchState>>(emptyList())
    val lobbyMemberStates: StateFlow<List<PlayerMatchState>> = _lobbyMemberStates.asStateFlow()

    private val _pingMs = MutableStateFlow(0)
    val pingMs: StateFlow<Int> = _pingMs.asStateFlow()

    private val _realtimeConnected = MutableStateFlow(false)
    val realtimeConnected: StateFlow<Boolean> = _realtimeConnected.asStateFlow()

    private val _bannedAccounts = MutableStateFlow<List<PlayerProfile>>(emptyList())
    val bannedAccounts: StateFlow<List<PlayerProfile>> = _bannedAccounts.asStateFlow()

    private val _bannedDevices = MutableStateFlow<List<BannedDeviceEntry>>(emptyList())
    val bannedDevices: StateFlow<List<BannedDeviceEntry>> = _bannedDevices.asStateFlow()

    private val _clientAccessStatus = MutableStateFlow(ClientAccessStatus())
    val clientAccessStatus: StateFlow<ClientAccessStatus> = _clientAccessStatus.asStateFlow()

    private val _adminBusy = MutableStateFlow(false)
    val adminBusy: StateFlow<Boolean> = _adminBusy.asStateFlow()

    private val _adminStatus = MutableStateFlow<String?>(null)
    val adminStatus: StateFlow<String?> = _adminStatus.asStateFlow()

    private val _kickedMessage = MutableStateFlow<String?>(null)
    val kickedMessage: StateFlow<String?> = _kickedMessage.asStateFlow()

    private val _localBanNotice = MutableStateFlow<String?>(null)
    val localBanNotice: StateFlow<String?> = _localBanNotice.asStateFlow()

    private val _isOpeningCase = MutableStateFlow(false)
    val isOpeningCase: StateFlow<Boolean> = _isOpeningCase.asStateFlow()

    private val _isUpdatingSkin = MutableStateFlow(false)
    val isUpdatingSkin: StateFlow<Boolean> = _isUpdatingSkin.asStateFlow()

    private val _progressionNotice = MutableStateFlow<String?>(null)
    val progressionNotice: StateFlow<String?> = _progressionNotice.asStateFlow()

    @Volatile private var isAppForeground: Boolean = true
    @Volatile private var lastPresenceHeartbeatMs: Long = 0L

    // Direct zero-latency listeners invoked straight from the WebSocket thread into GameEngine!
    @Volatile private var directRemoteStateSink: ((PlayerMatchState) -> Unit)? = null
    @Volatile private var directBombSyncSink: ((BombSyncEvent) -> Unit)? = null
    @Volatile private var directMatchSyncSink: ((MatchSyncEvent) -> Unit)? = null
    @Volatile private var directKillFeedSink: ((KillFeedSyncEvent) -> Unit)? = null
    @Volatile private var lastDbMirrorMs: Long = 0L
    private var adminActionSeq: Int = 0

    private val realtimeClient = SupabaseRealtimeClient(
        authManager = authManager,
        onRemoteStateReceived = { remoteState ->
            directRemoteStateSink?.invoke(remoteState)
        },
        onBombSyncReceived = { bombEvent ->
            directBombSyncSink?.invoke(bombEvent)
        },
        onMatchSyncReceived = { matchEvent ->
            directMatchSyncSink?.invoke(matchEvent)
        },
        onKillFeedReceived = { killFeedEvent ->
            directKillFeedSink?.invoke(killFeedEvent)
        },
        onAdminActionReceived = { adminEvent ->
            handleIncomingAdminAction(adminEvent)
        },
        onPingMeasured = { rttMs ->
            recordRtt(rttMs)
        },
        onConnectionChanged = { connected ->
            _realtimeConnected.value = connected
        }
    )

    private var lobbyPollJob: Job? = null
    private val tickInFlight = AtomicBoolean(false)

    fun attachDirectMatchSinks(
        onRemoteState: ((PlayerMatchState) -> Unit)?,
        onBombSync: ((BombSyncEvent) -> Unit)?,
        onMatchSync: ((MatchSyncEvent) -> Unit)? = null,
        onKillFeed: ((KillFeedSyncEvent) -> Unit)? = null
    ) {
        directRemoteStateSink = onRemoteState
        directBombSyncSink = onBombSync
        directMatchSyncSink = onMatchSync
        directKillFeedSink = onKillFeed
    }

    init {
        initializeAndStartSocialPolling()
    }

    fun currentProfileOrNull(): PlayerProfile? =
        (_profileState.value as? OnlineUiState.Success)?.data

    fun clearBanner() {
        _statusBanner.value = null
    }

    fun clearSearch() {
        _searchResult.value = null
    }

    fun retryLoadProfile() {
        viewModelScope.launch {
            _profileState.value = OnlineUiState.Loading
            _statusBanner.value = null
            repository.ensurePlayerProfile(currentUserId, initialLoginName)
                .onSuccess { profile ->
                    _profileState.value = OnlineUiState.Success(profile)
                    refreshSocialData()
                    if (profile.banned && !profile.isAdmin) {
                        checkClientAccessNow()
                    }
                }
                .onFailure { err ->
                    val msg = err.localizedMessage ?: "Не удалось загрузить или создать ID профиля"
                    if (msg.contains("DEVICE_BANNED", ignoreCase = true)) {
                        _clientAccessStatus.value = _clientAccessStatus.value.copy(
                            allowed = false,
                            deviceBanned = true,
                            banReason = msg.substringAfter("DEVICE_BANNED:").trim()
                                .ifBlank { "Устройство заблокировано администратором." },
                            checked = true
                        )
                    }
                    _profileState.value = OnlineUiState.Error(msg)
                }
        }
    }

    fun clearKickedMessage() {
        _kickedMessage.value = null
    }

    fun clearAdminStatus() {
        _adminStatus.value = null
    }

    fun refreshBanStatusNow() {
        viewModelScope.launch {
            checkClientAccessNow()
            repository.refreshMyProfile(currentUserId).onSuccess { updated ->
                if (updated != null) {
                    _profileState.value = OnlineUiState.Success(updated)
                    if (!updated.banned) {
                        _localBanNotice.value = null
                    }
                }
            }
        }
    }

    fun clearProgressionNotice() {
        _progressionNotice.value = null
    }

    fun checkClientAccessNow() {
        viewModelScope.launch {
            val status = authManager.checkClientAccess()
            _clientAccessStatus.value = status
            if (status.deviceBanned) {
                leaveActiveLobby()
                _localBanNotice.value = status.banReason.ifBlank { "Устройство заблокировано администратором." }
            }
        }
    }

    /**
     * Driven by MainActivity ON_START / ON_STOP so Friends online status & version/device ban checks stay current!
     */
    fun setAppForeground(foreground: Boolean) {
        isAppForeground = foreground
        viewModelScope.launch {
            if (foreground) {
                checkClientAccessNow()
            }
            repository.setOnlineStatus(currentUserId, online = foreground)
            if (foreground) {
                lastPresenceHeartbeatMs = android.os.SystemClock.elapsedRealtime()
                refreshSocialData()
            }
        }
    }

    /**
     * Called on every non-warmup kill (bot or PvP) -> awards +100 EXP on server and +50 coins per level-up!
     */
    fun onLocalKillEarned(headshot: Boolean, onToast: ((String) -> Unit)? = null) {
        val prevLevel = currentProfileOrNull()?.level ?: 1
        viewModelScope.launch {
            repository.awardKillXp(kills = 1, headshots = if (headshot) 1 else 0)
                .onSuccess { updated ->
                    _profileState.value = OnlineUiState.Success(updated)
                    val levelsGained = (updated.level - prevLevel).coerceAtLeast(0)
                    val msg = if (levelsGained > 0) {
                        val coinsAwarded = levelsGained * LevelProgression.COINS_PER_LEVEL_UP
                        "УРОВЕНЬ ${updated.level}! +$coinsAwarded МОНЕТ"
                    } else {
                        "+${LevelProgression.XP_PER_KILL} EXP"
                    }
                    onToast?.invoke(msg)
                    if (levelsGained > 0) {
                        _progressionNotice.value = msg
                    }
                }
                .onFailure {
                    // Still show local +100 EXP feedback if network hiccups
                    onToast?.invoke("+${LevelProgression.XP_PER_KILL} EXP")
                }
        }
    }

    fun openSkinCase(
        skinCase: SkinCase,
        onRolled: (skin: WeaponSkin, wasDuplicate: Boolean, refundCoins: Int) -> Unit,
        onError: (String) -> Unit
    ) {
        val me = currentProfileOrNull()
        if (me == null) {
            onError("Профиль ещё загружается.")
            return
        }
        if (me.coins < skinCase.priceCoins) {
            onError("Недостаточно монет! Нужно ${skinCase.priceCoins} монет (у вас ${me.coins}).")
            return
        }
        if (_isOpeningCase.value) return
        viewModelScope.launch {
            _isOpeningCase.value = true
            val rolled = SkinCatalog.rollSkinFromCase(skinCase)
            val alreadyOwned = me.ownedSkins.contains(rolled.id)
            val refund = if (alreadyOwned) rolled.rarity.duplicateRefundCoins else 0
            repository.openSkinCase(rolled.id, rolled.rarity.duplicateRefundCoins)
                .onSuccess { updated ->
                    _profileState.value = OnlineUiState.Success(updated)
                    onRolled(rolled, alreadyOwned, refund)
                }
                .onFailure { err ->
                    onError(err.localizedMessage ?: "Не удалось открыть кейс")
                }
            _isOpeningCase.value = false
        }
    }

    fun equipWeaponSkin(weapon: WeaponType, skinId: String?) {
        if (_isUpdatingSkin.value) return
        viewModelScope.launch {
            _isUpdatingSkin.value = true
            repository.equipWeaponSkin(weapon.name, skinId)
                .onSuccess { updated ->
                    _profileState.value = OnlineUiState.Success(updated)
                    _statusBanner.value = if (skinId.isNullOrBlank()) {
                        "Скин снят с ${weapon.shortLabel}"
                    } else {
                        val sName = SkinCatalog.byId(skinId)?.nameRu ?: skinId
                        "Применён скин: $sName"
                    }
                }
                .onFailure { err ->
                    _statusBanner.value = err.localizedMessage ?: "Не удалось применить скин"
                }
            _isUpdatingSkin.value = false
        }
    }

    fun adminGrantXp(targetShortIdRaw: String, xpAmount: Int) {
        val me = currentProfileOrNull()
        if (me?.isAdmin != true) return
        viewModelScope.launch {
            _adminBusy.value = true
            repository.adminGrantXp(targetShortIdRaw, xpAmount)
                .onSuccess { updated ->
                    if (updated.userId == currentUserId) {
                        _profileState.value = OnlineUiState.Success(updated)
                    } else {
                        realtimeClient.sendAdminAction(
                            AdminActionEvent(
                                seq = ++adminActionSeq,
                                action = "refresh",
                                targetUid = updated.userId,
                                targetShortId = updated.shortId,
                                reason = "",
                                senderUid = currentUserId,
                                senderShortId = me.shortId
                            )
                        )
                    }
                    _adminStatus.value = "Начислено +$xpAmount EXP для ${updated.displayName} (ID ${updated.shortId}) → LVL ${updated.level} (${updated.coins} монет)"
                }
                .onFailure { err ->
                    _adminStatus.value = err.localizedMessage ?: "Ошибка начисления EXP"
                }
            _adminBusy.value = false
        }
    }

    fun adminGrantSkins(targetShortIdRaw: String, skinIds: List<String>) {
        val me = currentProfileOrNull()
        if (me?.isAdmin != true) return
        viewModelScope.launch {
            _adminBusy.value = true
            repository.adminGrantSkins(targetShortIdRaw, skinIds)
                .onSuccess { updated ->
                    if (updated.userId == currentUserId) {
                        _profileState.value = OnlineUiState.Success(updated)
                    } else {
                        realtimeClient.sendAdminAction(
                            AdminActionEvent(
                                seq = ++adminActionSeq,
                                action = "refresh",
                                targetUid = updated.userId,
                                targetShortId = updated.shortId,
                                reason = "",
                                senderUid = currentUserId,
                                senderShortId = me.shortId
                            )
                        )
                    }
                    val label = if (skinIds.size == 1) {
                        SkinCatalog.byId(skinIds.first())?.nameRu ?: skinIds.first()
                    } else {
                        "${skinIds.size} скинов"
                    }
                    _adminStatus.value = "Выдано ($label) игроку ${updated.displayName} (ID ${updated.shortId})"
                }
                .onFailure { err ->
                    _adminStatus.value = err.localizedMessage ?: "Ошибка выдачи скина"
                }
            _adminBusy.value = false
        }
    }

    private fun handleIncomingAdminAction(ev: AdminActionEvent) {
        // Strictly validate that the sender is the designated admin account (ID developer)
        if (!ev.senderShortId.equals(ADMIN_SHORT_ID, ignoreCase = true)) return
        val knownSender = _lobbyMemberStates.value.firstOrNull { it.userId == ev.senderUid }
        if (knownSender != null && knownSender.shortId.isNotBlank() && !knownSender.shortId.equals(ADMIN_SHORT_ID, ignoreCase = true)) {
            return
        }
        val myShortId = currentProfileOrNull()?.shortId.orEmpty()
        val isTargeted = (ev.targetUid.isNotBlank() && ev.targetUid == currentUserId) ||
            (ev.targetShortId.isNotBlank() && ev.targetShortId.equals(myShortId, ignoreCase = true))
        if (!isTargeted) return

        when (ev.action) {
            "kick" -> {
                leaveActiveLobby()
                _kickedMessage.value = "Администратор исключил вас из матча."
            }
            "ban" -> {
                val msg = ev.reason.ifBlank { "Аккаунт заблокирован администратором." }
                leaveActiveLobby()
                _localBanNotice.value = msg
                refreshBanStatusNow()
            }
            "unban" -> {
                _localBanNotice.value = null
                refreshBanStatusNow()
            }
            "refresh" -> {
                refreshBanStatusNow()
            }
        }
    }

    fun refreshBannedAccounts() {
        if (currentProfileOrNull()?.isAdmin != true) return
        viewModelScope.launch {
            _adminBusy.value = true
            repository.adminFetchBannedProfiles()
                .onSuccess { list ->
                    _bannedAccounts.value = list
                }
                .onFailure { err ->
                    _adminStatus.value = err.localizedMessage ?: "Не удалось загрузить список банов"
                }
            repository.adminFetchBannedDevices()
                .onSuccess { devices ->
                    _bannedDevices.value = devices
                }
            _adminBusy.value = false
        }
    }

    fun adminSetDeviceBanByAccount(shortIdRaw: String, banned: Boolean, reasonRaw: String = "") {
        val me = currentProfileOrNull()
        if (me?.isAdmin != true) return
        val cleanId = shortIdRaw.trim().lowercase()
        if (cleanId == ADMIN_SHORT_ID) {
            _adminStatus.value = "Нельзя заблокировать устройство администратора."
            return
        }
        viewModelScope.launch {
            _adminBusy.value = true
            repository.adminSetDeviceBanByAccount(cleanId, banned, reasonRaw)
                .onSuccess { hash ->
                    _adminStatus.value = if (banned) {
                        "Устройство игрока ID $cleanId заблокировано (${hash.take(8)}…)."
                    } else {
                        "Бан устройства игрока ID $cleanId снят."
                    }
                    refreshBannedAccounts()
                }
                .onFailure { err ->
                    _adminStatus.value = err.localizedMessage ?: "Не удалось изменить бан устройства"
                }
            _adminBusy.value = false
        }
    }

    fun adminUnbanDeviceHash(deviceHash: String) {
        val me = currentProfileOrNull()
        if (me?.isAdmin != true) return
        viewModelScope.launch {
            _adminBusy.value = true
            repository.adminUnbanDeviceHash(deviceHash)
                .onSuccess {
                    _adminStatus.value = "Устройство (${deviceHash.take(8)}…) разблокировано."
                    refreshBannedAccounts()
                }
                .onFailure { err ->
                    _adminStatus.value = err.localizedMessage ?: "Не удалось разбанить устройство"
                }
            _adminBusy.value = false
        }
    }

    fun adminKickPlayer(targetUid: String, targetShortId: String, targetName: String) {
        val me = currentProfileOrNull()
        if (me?.isAdmin != true) return
        if (targetUid == currentUserId || targetShortId.equals(ADMIN_SHORT_ID, ignoreCase = true)) {
            _adminStatus.value = "Нельзя кикнуть самого себя."
            return
        }
        val lobbyId = _activeLobbyId.value
        val ev = AdminActionEvent(
            seq = ++adminActionSeq,
            action = "kick",
            targetUid = targetUid,
            targetShortId = targetShortId,
            reason = "Кик администратором",
            senderUid = currentUserId,
            senderShortId = me.shortId
        )
        realtimeClient.sendAdminAction(ev)
        _lobbyMemberStates.value = _lobbyMemberStates.value.filterNot { it.userId == targetUid }
        _adminStatus.value = "Игрок $targetName исключён из матча"
        if (!lobbyId.isNullOrBlank() && targetUid.isNotBlank()) {
            viewModelScope.launch {
                repository.adminKickFromLobby(lobbyId, targetUid)
                    .onFailure { err ->
                        _adminStatus.value = "Кик отправлен в матч (БД: ${err.localizedMessage ?: "обновите SQL-схему"})"
                    }
            }
        }
    }

    fun adminBanAccount(shortIdRaw: String, reasonRaw: String, targetUid: String = "") {
        val me = currentProfileOrNull()
        if (me?.isAdmin != true) return
        val cleanId = shortIdRaw.trim().lowercase()
        val cleanReason = reasonRaw.trim().ifBlank { "Нарушение правил сервера" }.take(120)
        if (cleanId == ADMIN_SHORT_ID) {
            _adminStatus.value = "Нельзя заблокировать аккаунт администратора ($ADMIN_SHORT_ID)."
            return
        }
        viewModelScope.launch {
            _adminBusy.value = true
            // Broadcast instant realtime kick/ban in current match room if active
            val ev = AdminActionEvent(
                seq = ++adminActionSeq,
                action = "ban",
                targetUid = targetUid,
                targetShortId = cleanId,
                reason = cleanReason,
                senderUid = currentUserId,
                senderShortId = me.shortId
            )
            realtimeClient.sendAdminAction(ev)

            repository.adminSetAccountBan(cleanId, banned = true, reasonRaw = cleanReason)
                .onSuccess { bannedProfile ->
                    // Also broadcast using resolved UID in case targetUid wasn't supplied
                    if (targetUid.isBlank() && bannedProfile.userId.isNotBlank()) {
                        realtimeClient.sendAdminAction(ev.copy(seq = ++adminActionSeq, targetUid = bannedProfile.userId))
                    }
                    _lobbyMemberStates.value = _lobbyMemberStates.value.filterNot {
                        it.userId == bannedProfile.userId || it.shortId == cleanId
                    }
                    _adminStatus.value = if (bannedProfile.lastDeviceHash.isNotBlank()) {
                        "Аккаунт ${bannedProfile.displayName} (ID $cleanId) и его устройство автоматически забанены."
                    } else {
                        "Аккаунт ${bannedProfile.displayName} (ID $cleanId) забанен (устройство заблокируется автоматически при входе)."
                    }
                    refreshBannedAccounts()
                }
                .onFailure { err ->
                    _adminStatus.value = err.localizedMessage ?: "Ошибка бана аккаунта"
                }
            _adminBusy.value = false
        }
    }

    fun adminUnbanAccount(shortIdRaw: String) {
        val me = currentProfileOrNull()
        if (me?.isAdmin != true) return
        val cleanId = shortIdRaw.trim()
        viewModelScope.launch {
            _adminBusy.value = true
            repository.adminSetAccountBan(cleanId, banned = false, reasonRaw = "")
                .onSuccess { unbannedProfile ->
                    realtimeClient.sendAdminAction(
                        AdminActionEvent(
                            seq = ++adminActionSeq,
                            action = "unban",
                            targetUid = unbannedProfile.userId,
                            targetShortId = cleanId,
                            reason = "",
                            senderUid = currentUserId,
                            senderShortId = me.shortId
                        )
                    )
                    _adminStatus.value = "Аккаунт ${unbannedProfile.displayName} (ID $cleanId) разбанен."
                    refreshBannedAccounts()
                }
                .onFailure { err ->
                    _adminStatus.value = err.localizedMessage ?: "Ошибка разбана аккаунта"
                }
            _adminBusy.value = false
        }
    }

    private fun initializeAndStartSocialPolling() {
        viewModelScope.launch {
            _clientAccessStatus.value = authManager.checkClientAccess()
            _profileState.value = OnlineUiState.Loading
            repository.ensurePlayerProfile(currentUserId, initialLoginName)
                .onSuccess { profile ->
                    _profileState.value = OnlineUiState.Success(profile)
                    refreshSocialData()
                }
                .onFailure { err ->
                    val msg = err.localizedMessage ?: "Не удалось загрузить или создать ID профиля"
                    if (msg.contains("DEVICE_BANNED", ignoreCase = true)) {
                        _clientAccessStatus.value = _clientAccessStatus.value.copy(
                            allowed = false,
                            deviceBanned = true,
                            banReason = msg.substringAfter("DEVICE_BANNED:").trim()
                                .ifBlank { "Устройство заблокировано администратором." },
                            checked = true
                        )
                    }
                    _profileState.value = OnlineUiState.Error(msg)
                }

            // Periodic background cloud sync + strict 15s foreground presence heartbeat + version/device checks!
            var pollCycle = 0
            while (isActive) {
                delay(3_500L)
                pollCycle++
                val nowElapsed = android.os.SystemClock.elapsedRealtime()
                if (isAppForeground && nowElapsed - lastPresenceHeartbeatMs >= 15_000L) {
                    lastPresenceHeartbeatMs = nowElapsed
                    repository.setOnlineStatus(currentUserId, online = true)
                    val access = authManager.checkClientAccess()
                    _clientAccessStatus.value = access
                    if (access.deviceBanned || access.updateRequired) {
                        leaveActiveLobby()
                    }
                }
                if (_activeLobby.value?.status != "playing") {
                    if (_profileState.value !is OnlineUiState.Success) {
                        repository.ensurePlayerProfile(currentUserId, initialLoginName)
                            .onSuccess {
                                _profileState.value = OnlineUiState.Success(it)
                                refreshSocialData()
                                if (it.banned && !it.isAdmin) checkClientAccessNow()
                            }
                            .onFailure { err ->
                                val msg = err.localizedMessage ?: "Не удалось загрузить или создать ID профиля"
                                if (msg.contains("DEVICE_BANNED", ignoreCase = true)) {
                                    _clientAccessStatus.value = _clientAccessStatus.value.copy(
                                        allowed = false,
                                        deviceBanned = true,
                                        banReason = msg.substringAfter("DEVICE_BANNED:").trim()
                                            .ifBlank { "Устройство заблокировано администратором." },
                                        checked = true
                                    )
                                }
                                _profileState.value = OnlineUiState.Error(msg)
                            }
                    } else if (isAppForeground) {
                        refreshSocialData()
                        if (pollCycle % 2 == 0) {
                            repository.refreshMyProfile(currentUserId).onSuccess { refreshed ->
                                if (refreshed != null) {
                                    _profileState.value = OnlineUiState.Success(refreshed)
                                    if (!refreshed.banned) {
                                        _localBanNotice.value = null
                                    } else if (!refreshed.isAdmin) {
                                        checkClientAccessNow()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun refreshSocialData() {
        viewModelScope.launch {
            repository.fetchFriends(currentUserId)
                .onSuccess { _friendsState.value = OnlineUiState.Success(it) }
                .onFailure { err ->
                    if (_friendsState.value is OnlineUiState.Loading) {
                        _friendsState.value = OnlineUiState.Error(
                            err.localizedMessage ?: "Ошибка загрузки списка друзей"
                        )
                    }
                }
            repository.fetchFriendRequests(currentUserId)
                .onSuccess { (incoming, outgoing) ->
                    _incomingRequests.value = incoming
                    _outgoingRequests.value = outgoing
                }
            repository.fetchIncomingInvites(currentUserId)
                .onSuccess { _incomingInvites.value = it }
        }
    }

    fun saveProfileCustomization(
        displayName: String,
        avatarData: String,
        onSaved: () -> Unit
    ) {
        viewModelScope.launch {
            _isSavingProfile.value = true
            _statusBanner.value = null
            repository.updatePlayerProfile(currentUserId, displayName, avatarData)
                .onSuccess { updated ->
                    _profileState.value = OnlineUiState.Success(updated)
                    _statusBanner.value = "Профиль обновлён!"
                    onSaved()
                }
                .onFailure { err ->
                    _statusBanner.value = err.localizedMessage ?: "Не удалось сохранить профиль"
                }
            _isSavingProfile.value = false
        }
    }

    fun searchByShortId(shortId: String) {
        viewModelScope.launch {
            _isSearching.value = true
            _searchResult.value = null
            _statusBanner.value = null
            repository.findPlayerByShortId(shortId)
                .onSuccess { found ->
                    _searchResult.value = found
                    if (found == null) {
                        _statusBanner.value = "Игрок с ID $shortId не найден"
                    }
                }
                .onFailure { err ->
                    _statusBanner.value = err.localizedMessage ?: "Ошибка поиска по ID"
                }
            _isSearching.value = false
        }
    }

    fun sendFriendRequest(target: PlayerProfile) {
        viewModelScope.launch {
            repository.sendFriendRequest(currentUserId, target)
                .onSuccess {
                    _statusBanner.value = "Заявка отправлена игроку ${target.displayName} (ID ${target.shortId})"
                    _searchResult.value = null
                    refreshSocialData()
                }
                .onFailure { err ->
                    _statusBanner.value = err.localizedMessage ?: "Не удалось отправить заявку"
                }
        }
    }

    fun respondFriendRequest(req: FriendRequest, accept: Boolean) {
        viewModelScope.launch {
            repository.respondFriendRequest(req.id, accept)
                .onSuccess {
                    _statusBanner.value = if (accept) {
                        "${req.fromName} добавлен в друзья!"
                    } else {
                        "Заявка от ${req.fromName} отклонена"
                    }
                    refreshSocialData()
                }
                .onFailure { err ->
                    _statusBanner.value = err.localizedMessage ?: "Ошибка обработки заявки"
                }
        }
    }

    fun removeFriend(friend: FriendEntry) {
        viewModelScope.launch {
            repository.removeFriend(friend.friendUid)
                .onSuccess {
                    _statusBanner.value = "${friend.friendName} удалён из друзей"
                    refreshSocialData()
                }
                .onFailure { err ->
                    _statusBanner.value = err.localizedMessage ?: "Ошибка удаления"
                }
        }
    }

    fun createMultiplayerLobby(
        map: ArenaMapId,
        mode: GameMode,
        roundsToWin: Int,
        allowBots: Boolean,
        team: TeamSide,
        onCreated: () -> Unit
    ) {
        val me = currentProfileOrNull()
        if (me == null) {
            _statusBanner.value = "Профиль ещё загружается..."
            return
        }
        viewModelScope.launch {
            _statusBanner.value = null
            repository.createLobby(me, map, mode, roundsToWin, allowBots, team)
                .onSuccess { lobby ->
                    _activeLobbyId.value = lobby.id
                    _activeLobby.value = lobby
                    _lobbyMemberStates.value = listOf(
                        PlayerMatchState(
                            id = me.userId,
                            userId = me.userId,
                            shortId = me.shortId,
                            displayName = me.displayName,
                            avatarData = me.avatarData,
                            level = me.level,
                            equippedSkins = me.equippedSkins,
                            team = team.name
                        )
                    )
                    startLobbyPolling(lobby.id)
                    onCreated()
                }
                .onFailure { err ->
                    _statusBanner.value = err.localizedMessage ?: "Не удалось создать облачное лобби"
                }
        }
    }

    fun inviteFriendToActiveLobby(friend: FriendEntry) {
        val lobby = _activeLobby.value ?: return
        viewModelScope.launch {
            repository.sendLobbyInvite(lobby, currentUserId, friend.friendUid)
                .onSuccess {
                    _statusBanner.value = "Приглашение отправлено: ${friend.friendName}"
                }
                .onFailure { err ->
                    _statusBanner.value = err.localizedMessage ?: "Ошибка приглашения"
                }
        }
    }

    fun acceptLobbyInvite(invite: LobbyInvite, preferredTeam: TeamSide, onJoined: () -> Unit) {
        val me = currentProfileOrNull() ?: return
        viewModelScope.launch {
            repository.respondLobbyInvite(invite.id, accept = true)
            _incomingInvites.value = _incomingInvites.value.filterNot { it.id == invite.id }
            repository.joinLobby(invite.lobbyId, me, preferredTeam)
                .onSuccess { (lobby, members) ->
                    _activeLobbyId.value = lobby.id
                    _activeLobby.value = lobby
                    _lobbyMemberStates.value = members
                    startLobbyPolling(lobby.id)
                    onJoined()
                }
                .onFailure { err ->
                    _statusBanner.value = err.localizedMessage ?: "Не удалось войти в лобби"
                }
        }
    }

    fun declineLobbyInvite(invite: LobbyInvite) {
        _incomingInvites.value = _incomingInvites.value.filterNot { it.id == invite.id }
        viewModelScope.launch {
            repository.respondLobbyInvite(invite.id, accept = false)
        }
    }

    fun updateActiveLobbyConfig(map: ArenaMapId, mode: GameMode, roundsToWin: Int, allowBots: Boolean) {
        val lobby = _activeLobby.value ?: return
        _activeLobby.value = lobby.copy(
            mapId = map.name,
            mode = mode.name,
            roundsToWin = roundsToWin,
            allowBots = allowBots
        )
        viewModelScope.launch {
            repository.updateLobbyConfig(lobby.id, map, mode, roundsToWin, allowBots)
                .onFailure { err -> _statusBanner.value = err.localizedMessage ?: "Ошибка обновления лобби" }
        }
    }

    fun switchMyTeam(side: TeamSide) {
        val lobbyId = _activeLobbyId.value ?: return
        _lobbyMemberStates.value = _lobbyMemberStates.value.map { st ->
            if (st.userId == currentUserId) st.copy(team = side.name) else st
        }
        viewModelScope.launch {
            repository.switchMyTeam(lobbyId, currentUserId, side)
                .onFailure { err -> _statusBanner.value = err.localizedMessage ?: "Ошибка смены команды" }
        }
    }

    fun startActiveLobbyMatch() {
        val lobby = _activeLobby.value ?: return
        viewModelScope.launch {
            repository.startLobbyMatch(lobby.id)
                .onSuccess {
                    _activeLobby.value = lobby.copy(status = "playing")
                }
                .onFailure { err ->
                    _statusBanner.value = err.localizedMessage ?: "Не удалось запустить матч"
                }
        }
    }

    fun leaveActiveLobby(onLeft: () -> Unit = {}) {
        val lobby = _activeLobby.value
        val lobbyId = _activeLobbyId.value
        lobbyPollJob?.cancel()
        lobbyPollJob = null
        realtimeClient.disconnect()
        _activeLobbyId.value = null
        _activeLobby.value = null
        _lobbyMemberStates.value = emptyList()
        _pingMs.value = 0
        viewModelScope.launch {
            if (!lobbyId.isNullOrBlank()) {
                repository.leaveLobby(lobbyId, currentUserId, isHost = lobby?.hostUid == currentUserId)
            }
            onLeft()
        }
    }

    fun publishBombSync(lobbyId: String, event: BombSyncEvent) {
        if (lobbyId.isBlank()) return
        realtimeClient.sendBombEvent(event)
    }

    fun publishMatchSync(lobbyId: String, event: MatchSyncEvent) {
        if (lobbyId.isBlank()) return
        realtimeClient.sendMatchEvent(event)
    }

    fun publishKillFeedSync(lobbyId: String, event: KillFeedSyncEvent) {
        if (lobbyId.isBlank()) return
        realtimeClient.sendKillFeedEvent(event)
    }

    fun publishMatchTick(
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
        outgoingHit: OutgoingHitEvent?,
        bombSync: BombSyncEvent? = null,
        matchSync: MatchSyncEvent? = null
    ) {
        if (lobbyId.isBlank()) return
        val prof = currentProfileOrNull()
        val displayName = prof?.displayName ?: initialLoginName
        val myLevel = prof?.level ?: 1
        val activeSkinId = prof?.equippedSkins?.get(weaponName).orEmpty()
        // 0. Ultra-low-latency dedicated PvP hit packet first when shooting hits an enemy!
        if (outgoingHit != null) {
            realtimeClient.sendHitEvent(
                userId = currentUserId,
                displayName = displayName,
                team = team,
                hit = outgoingHit
            )
        }
        // 1. Primary path: instant 50Hz TCP_NODELAY WebSocket broadcast (zero DB write latency!)
        val sentViaWs = realtimeClient.sendPlayerState(
            userId = currentUserId,
            displayName = displayName,
            team = team,
            x = x,
            y = y,
            z = z,
            yawDeg = yawDeg,
            pitchDeg = pitchDeg,
            hp = hp,
            dead = dead,
            moving = moving,
            shooting = shooting,
            weaponName = weaponName,
            kills = kills,
            deaths = deaths,
            outgoingHit = outgoingHit,
            bombSync = bombSync,
            matchSync = matchSync,
            level = myLevel,
            activeSkinId = activeSkinId
        )

        // 2. Database mirror: immediate ONLY if WebSocket failed; otherwise lightweight 2.2s mirror
        //    so HTTP overhead never competes with live WebSocket combat sockets!
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val needsDbSync = !sentViaWs || (nowMs - lastDbMirrorMs >= 2_200L)
        if (!needsDbSync) return
        if (!tickInFlight.compareAndSet(false, true)) return
        lastDbMirrorMs = nowMs
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val rtt = repository.publishMatchTick(
                    lobbyId = lobbyId,
                    userId = currentUserId,
                    team = team,
                    x = x,
                    y = y,
                    z = z,
                    yawDeg = yawDeg,
                    pitchDeg = pitchDeg,
                    hp = hp,
                    dead = dead,
                    moving = moving,
                    shooting = shooting,
                    weaponName = weaponName,
                    kills = kills,
                    deaths = deaths,
                    outgoingHit = if (!sentViaWs) outgoingHit else null
                )
                if (rtt != null && (!sentViaWs || _pingMs.value == 0)) {
                    recordRtt(rtt)
                }
            } finally {
                tickInFlight.set(false)
            }
        }
    }

    suspend fun setOfflineAndLeaveBeforeSignOut() {
        val lobby = _activeLobby.value
        val lobbyId = _activeLobbyId.value
        lobbyPollJob?.cancel()
        lobbyPollJob = null
        realtimeClient.disconnect()
        _activeLobbyId.value = null
        _activeLobby.value = null
        _lobbyMemberStates.value = emptyList()
        _pingMs.value = 0
        if (!lobbyId.isNullOrBlank()) {
            repository.leaveLobby(lobbyId, currentUserId, isHost = lobby?.hostUid == currentUserId)
        }
        repository.setOnlineStatus(currentUserId, online = false)
    }

    private fun recordRtt(rttMs: Int) {
        val clamped = rttMs.coerceIn(1, 999)
        val prev = _pingMs.value
        _pingMs.value = if (prev <= 0) {
            clamped
        } else {
            (prev * 0.55f + clamped * 0.45f).toInt().coerceIn(1, 999)
        }
    }

    private fun startLobbyPolling(lobbyId: String) {
        lobbyPollJob?.cancel()
        realtimeClient.connect(lobbyId, currentUserId)
        lobbyPollJob = viewModelScope.launch {
            var matchTickCount = 0
            while (isActive && _activeLobbyId.value == lobbyId) {
                val inMatch = _activeLobby.value?.status == "playing"
                val wsLive = realtimeClient.isJoined

                if (inMatch && wsLive) {
                    // WebSocket handles 30Hz state/hit/bomb sync; keep lightweight DB snapshot every 1.6s
                    repository.fetchLobbySnapshot(lobbyId)
                        .onSuccess { snapshot ->
                            if (snapshot == null) {
                                _statusBanner.value = "Хост закрыл лобби."
                                realtimeClient.disconnect()
                                _activeLobbyId.value = null
                                _activeLobby.value = null
                                _lobbyMemberStates.value = emptyList()
                                _pingMs.value = 0
                                return@launch
                            }
                            if (snapshot.second.none { it.userId == currentUserId }) {
                                realtimeClient.disconnect()
                                _activeLobbyId.value = null
                                _activeLobby.value = null
                                _lobbyMemberStates.value = emptyList()
                                _pingMs.value = 0
                                _kickedMessage.value = "Вы были исключены из матча."
                                return@launch
                            }
                            _activeLobby.value = snapshot.first
                            _lobbyMemberStates.value = snapshot.second
                        }
                    delay(1_600L)
                } else if (inMatch && matchTickCount % 20 != 0) {
                    // Automatic fast PostgREST fallback when WebSocket is reconnecting
                    repository.fetchLobbyMembersFast(lobbyId)
                        .onSuccess { (fastMembers, rttMs) ->
                            recordRtt(rttMs)
                            val cachedAvatars = _lobbyMemberStates.value.associate { it.userId to it.avatarData }
                            _lobbyMemberStates.value = fastMembers.map { m ->
                                val preserved = cachedAvatars[m.userId]
                                if (!preserved.isNullOrBlank()) m.copy(avatarData = preserved) else m
                            }
                        }
                    matchTickCount++
                    delay(55L)
                } else {
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    repository.fetchLobbySnapshot(lobbyId)
                        .onSuccess { snapshot ->
                            if (!wsLive) {
                                val rtt = ((android.os.SystemClock.elapsedRealtime() - t0) / 2L).toInt().coerceIn(1, 999)
                                recordRtt(rtt)
                            }
                            if (snapshot == null) {
                                _statusBanner.value = "Хост закрыл лобби."
                                realtimeClient.disconnect()
                                _activeLobbyId.value = null
                                _activeLobby.value = null
                                _lobbyMemberStates.value = emptyList()
                                _pingMs.value = 0
                                return@launch
                            }
                            _activeLobby.value = snapshot.first
                            _lobbyMemberStates.value = snapshot.second
                        }
                    if (_activeLobby.value?.status == "playing") {
                        matchTickCount++
                        delay(if (realtimeClient.isJoined) 1_600L else 55L)
                    } else {
                        matchTickCount = 0
                        delay(500L)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        lobbyPollJob?.cancel()
        realtimeClient.disconnect()
        super.onCleared()
    }

    companion object {
        fun factory(
            currentUserId: String,
            initialLoginName: String,
            repository: SupabaseOnlineRepository,
            authManager: SupabaseAuthManager
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return OnlineViewModel(currentUserId, initialLoginName, repository, authManager) as T
            }
        }
    }
}
