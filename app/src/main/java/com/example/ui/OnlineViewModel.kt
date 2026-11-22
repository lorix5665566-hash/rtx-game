package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.model.ArenaMapId
import com.example.model.GameMode
import com.example.model.OutgoingHitEvent
import com.example.model.TeamSide
import com.example.online.FriendEntry
import com.example.online.FriendRequest
import com.example.online.LobbyInvite
import com.example.online.MultiplayerLobby
import com.example.online.PlayerMatchState
import com.example.online.PlayerProfile
import com.example.online.SupabaseOnlineRepository
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
    private val repository: SupabaseOnlineRepository
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

    private var lobbyPollJob: Job? = null
    private val tickInFlight = AtomicBoolean(false)

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
                }
                .onFailure { err ->
                    _profileState.value = OnlineUiState.Error(
                        err.localizedMessage ?: "Не удалось загрузить или создать ID профиля"
                    )
                }
        }
    }

    private fun initializeAndStartSocialPolling() {
        viewModelScope.launch {
            _profileState.value = OnlineUiState.Loading
            repository.ensurePlayerProfile(currentUserId, initialLoginName)
                .onSuccess { profile ->
                    _profileState.value = OnlineUiState.Success(profile)
                    refreshSocialData()
                }
                .onFailure { err ->
                    _profileState.value = OnlineUiState.Error(
                        err.localizedMessage ?: "Не удалось загрузить или создать ID профиля"
                    )
                }

            // Periodic background cloud sync for profile recovery, friends, requests, and lobby invites
            while (isActive) {
                delay(3_500L)
                if (_activeLobby.value?.status != "playing") {
                    if (_profileState.value !is OnlineUiState.Success) {
                        repository.ensurePlayerProfile(currentUserId, initialLoginName)
                            .onSuccess {
                                _profileState.value = OnlineUiState.Success(it)
                                refreshSocialData()
                            }
                            .onFailure { err ->
                                _profileState.value = OnlineUiState.Error(
                                    err.localizedMessage ?: "Не удалось загрузить или создать ID профиля"
                                )
                            }
                    } else {
                        refreshSocialData()
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
        outgoingHit: OutgoingHitEvent?
    ) {
        if (lobbyId.isBlank()) return
        // Avoid piling up concurrent HTTP patches; always let outgoing hit events through immediately
        if (outgoingHit == null && !tickInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
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
                    outgoingHit = outgoingHit
                )
                if (rtt != null && _pingMs.value == 0) {
                    recordRtt(rtt)
                }
            } finally {
                if (outgoingHit == null) tickInFlight.set(false)
            }
        }
    }

    suspend fun setOfflineAndLeaveBeforeSignOut() {
        val lobby = _activeLobby.value
        val lobbyId = _activeLobbyId.value
        lobbyPollJob?.cancel()
        lobbyPollJob = null
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
            (prev * 0.65f + clamped * 0.35f).toInt().coerceIn(1, 999)
        }
    }

    private fun startLobbyPolling(lobbyId: String) {
        lobbyPollJob?.cancel()
        lobbyPollJob = viewModelScope.launch {
            var matchTickCount = 0
            while (isActive && _activeLobbyId.value == lobbyId) {
                val inMatch = _activeLobby.value?.status == "playing"
                if (inMatch && matchTickCount % 20 != 0) {
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
                    delay(65L)
                } else {
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    repository.fetchLobbySnapshot(lobbyId)
                        .onSuccess { snapshot ->
                            val rtt = ((android.os.SystemClock.elapsedRealtime() - t0) / 2L).toInt().coerceIn(1, 999)
                            recordRtt(rtt)
                            if (snapshot == null) {
                                _statusBanner.value = "Хост закрыл лобби."
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
                        delay(65L)
                    } else {
                        matchTickCount = 0
                        delay(550L)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        lobbyPollJob?.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(
            currentUserId: String,
            initialLoginName: String,
            repository: SupabaseOnlineRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return OnlineViewModel(currentUserId, initialLoginName, repository) as T
            }
        }
    }
}
