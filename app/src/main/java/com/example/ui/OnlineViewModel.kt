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
import com.example.online.OnlineRepository
import com.example.online.PlayerMatchState
import com.example.online.PlayerProfile
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface OnlineUiState<out T> {
    data object Loading : OnlineUiState<Nothing>
    data class Success<T>(val data: T) : OnlineUiState<T>
    data class Error(val message: String) : OnlineUiState<Nothing>
}

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineViewModel(
    val currentUserId: String,
    private val initialDisplayName: String?,
    private val repository: OnlineRepository
) : ViewModel() {

    private val _profileState = MutableStateFlow<OnlineUiState<PlayerProfile>>(OnlineUiState.Loading)
    val profileState: StateFlow<OnlineUiState<PlayerProfile>> = _profileState.asStateFlow()

    private val _activeLobbyId = MutableStateFlow<String?>(null)
    val activeLobbyId: StateFlow<String?> = _activeLobbyId.asStateFlow()

    private val _searchResult = MutableStateFlow<PlayerProfile?>(null)
    val searchResult: StateFlow<PlayerProfile?> = _searchResult.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    val friendsState: StateFlow<OnlineUiState<List<FriendEntry>>> = repository.observeFriends(currentUserId)
        .map<List<FriendEntry>, OnlineUiState<List<FriendEntry>>> { OnlineUiState.Success(it) }
        .catch { error ->
            val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
            if (fe == null && error is CancellationException) throw error
            emit(OnlineUiState.Error((fe ?: error).message ?: "Ошибка загрузки списка друзей"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), OnlineUiState.Loading)

    val incomingRequests: StateFlow<List<FriendRequest>> = repository.observeIncomingFriendRequests(currentUserId)
        .catch { error ->
            val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
            if (fe == null && error is CancellationException) throw error
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val outgoingRequests: StateFlow<List<FriendRequest>> = repository.observeOutgoingFriendRequests(currentUserId)
        .catch { error ->
            val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
            if (fe == null && error is CancellationException) throw error
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val incomingInvites: StateFlow<List<LobbyInvite>> = repository.observeIncomingInvites(currentUserId)
        .catch { error ->
            val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
            if (fe == null && error is CancellationException) throw error
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val activeLobby: StateFlow<MultiplayerLobby?> = _activeLobbyId
        .flatMapLatest { id ->
            if (id.isNullOrBlank()) flowOf(null) else repository.observeLobby(id)
        }
        .catch { error ->
            val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
            if (fe == null && error is CancellationException) throw error
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val lobbyMemberStates: StateFlow<List<PlayerMatchState>> = activeLobby
        .flatMapLatest { lobby ->
            if (lobby == null || lobby.id.isBlank()) flowOf(emptyList())
            else repository.observeLobbyMemberStates(lobby.id, lobby.memberUids)
        }
        .catch { error ->
            val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
            if (fe == null && error is CancellationException) throw error
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    init {
        initializeProfile()
    }

    fun initializeProfile() {
        viewModelScope.launch {
            _profileState.value = OnlineUiState.Loading
            repository.ensurePlayerProfile(initialDisplayName)
                .onSuccess { profile ->
                    _profileState.value = OnlineUiState.Success(profile)
                }
                .onFailure { err ->
                    _profileState.value = OnlineUiState.Error(
                        err.localizedMessage ?: "Не удалось загрузить профиль игрока"
                    )
                }
        }
    }

    fun clearBanner() {
        _statusBanner.value = null
    }

    fun clearSearch() {
        _searchResult.value = null
    }

    fun currentProfileOrNull(): PlayerProfile? =
        (_profileState.value as? OnlineUiState.Success)?.data

    fun searchByShortId(shortId: String) {
        viewModelScope.launch {
            _isSearching.value = true
            _searchResult.value = null
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
        val me = currentProfileOrNull() ?: return
        viewModelScope.launch {
            repository.sendFriendRequest(me, target)
                .onSuccess {
                    _statusBanner.value = "Заявка отправлена игроку ${target.displayName} (ID ${target.shortId})"
                    _searchResult.value = null
                }
                .onFailure { err ->
                    _statusBanner.value = err.localizedMessage ?: "Не удалось отправить заявку"
                }
        }
    }

    fun respondFriendRequest(req: FriendRequest, accept: Boolean) {
        val me = currentProfileOrNull() ?: return
        viewModelScope.launch {
            repository.respondFriendRequest(req, me, accept)
                .onSuccess {
                    _statusBanner.value = if (accept) {
                        "${req.fromName} добавлен в друзья!"
                    } else {
                        "Заявка от ${req.fromName} отклонена"
                    }
                }
                .onFailure { err ->
                    _statusBanner.value = err.localizedMessage ?: "Ошибка обработки заявки"
                }
        }
    }

    fun removeFriend(friend: FriendEntry) {
        viewModelScope.launch {
            repository.removeFriend(friend.friendUid)
                .onSuccess { _statusBanner.value = "${friend.friendName} удалён из друзей" }
                .onFailure { err -> _statusBanner.value = err.localizedMessage ?: "Ошибка удаления" }
        }
    }

    fun createMultiplayerLobby(
        map: ArenaMapId,
        mode: GameMode,
        roundsToWin: Int,
        allowBots: Boolean,
        team: TeamSide,
        onCreated: (String) -> Unit
    ) {
        val me = currentProfileOrNull()
        if (me == null) {
            _statusBanner.value = "Профиль ещё загружается..."
            return
        }
        viewModelScope.launch {
            repository.createLobby(me, map, mode, roundsToWin, allowBots, team)
                .onSuccess { lobbyId ->
                    _activeLobbyId.value = lobbyId
                    onCreated(lobbyId)
                }
                .onFailure { err ->
                    _statusBanner.value = err.localizedMessage ?: "Не удалось создать лобби"
                }
        }
    }

    fun acceptLobbyInvite(invite: LobbyInvite, preferredTeam: TeamSide, onJoined: () -> Unit) {
        val me = currentProfileOrNull() ?: return
        viewModelScope.launch {
            repository.respondLobbyInvite(invite, accept = true)
            repository.joinLobby(invite.lobbyId, me, preferredTeam)
                .onSuccess {
                    _activeLobbyId.value = invite.lobbyId
                    onJoined()
                }
                .onFailure { err ->
                    _statusBanner.value = err.localizedMessage ?: "Не удалось войти в лобби"
                }
        }
    }

    fun declineLobbyInvite(invite: LobbyInvite) {
        viewModelScope.launch {
            repository.respondLobbyInvite(invite, accept = false)
        }
    }

    fun inviteFriendToActiveLobby(friend: FriendEntry) {
        val me = currentProfileOrNull() ?: return
        val lobby = activeLobby.value ?: return
        viewModelScope.launch {
            repository.sendLobbyInvite(lobby, me, friend.friendUid)
                .onSuccess { _statusBanner.value = "Приглашение отправлено: ${friend.friendName}" }
                .onFailure { err -> _statusBanner.value = err.localizedMessage ?: "Ошибка приглашения" }
        }
    }

    fun updateActiveLobbyConfig(map: ArenaMapId, mode: GameMode, roundsToWin: Int, allowBots: Boolean) {
        val lobbyId = _activeLobbyId.value ?: return
        viewModelScope.launch {
            repository.updateLobbyConfig(lobbyId, map, mode, roundsToWin, allowBots)
                .onFailure { err -> _statusBanner.value = err.localizedMessage ?: "Ошибка обновления лобби" }
        }
    }

    fun switchMyTeam(side: TeamSide) {
        val lobbyId = _activeLobbyId.value ?: return
        viewModelScope.launch {
            repository.switchMyTeam(lobbyId, side)
                .onFailure { err -> _statusBanner.value = err.localizedMessage ?: "Ошибка смены команды" }
        }
    }

    fun startActiveLobbyMatch() {
        val lobbyId = _activeLobbyId.value ?: return
        viewModelScope.launch {
            repository.startLobbyMatch(lobbyId)
                .onFailure { err -> _statusBanner.value = err.localizedMessage ?: "Не удалось запустить матч" }
        }
    }

    fun leaveActiveLobby(onLeft: () -> Unit = {}) {
        val lobbyId = _activeLobbyId.value
        _activeLobbyId.value = null
        viewModelScope.launch {
            if (!lobbyId.isNullOrBlank()) {
                repository.leaveLobby(lobbyId)
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
        viewModelScope.launch {
            repository.publishLiveMatchState(
                lobbyId, team, x, y, z, yawDeg, pitchDeg,
                hp, dead, moving, shooting, weaponName, kills, deaths, outgoingHit
            )
        }
    }

    fun recordOnlineMatchStats(kills: Int, headshots: Int) {
        viewModelScope.launch {
            repository.recordOnlineStats(kills, headshots)
        }
    }

    fun setOfflineBeforeSignOut() {
        viewModelScope.launch {
            val lobbyId = _activeLobbyId.value
            if (!lobbyId.isNullOrBlank()) repository.leaveLobby(lobbyId)
            repository.updatePresence(online = false, activeLobbyId = "")
        }
    }

    companion object {
        fun factory(
            currentUserId: String,
            initialDisplayName: String?,
            repository: OnlineRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return OnlineViewModel(currentUserId, initialDisplayName, repository) as T
            }
        }
    }
}
