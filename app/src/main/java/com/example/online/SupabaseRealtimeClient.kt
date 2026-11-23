package com.example.online

import android.os.SystemClock
import com.example.model.AdminActionEvent
import com.example.model.BombState
import com.example.model.BombSyncEvent
import com.example.model.KillFeedSyncEvent
import com.example.model.MatchSyncEvent
import com.example.model.OutgoingHitEvent
import com.example.model.TeamSide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Low-latency Supabase Realtime WebSocket Broadcast client (Phoenix protocol v1.0.0).
 * Delivers 50 Hz player transform updates, instant PvP hit events, and authoritative
 * C4 Bomb / Round / Score / Halftime synchronization.
 */
class SupabaseRealtimeClient(
    private val authManager: SupabaseAuthManager,
    private val onRemoteStateReceived: (PlayerMatchState) -> Unit,
    private val onBombSyncReceived: (BombSyncEvent) -> Unit,
    private val onMatchSyncReceived: (MatchSyncEvent) -> Unit,
    private val onKillFeedReceived: (KillFeedSyncEvent) -> Unit = {},
    private val onAdminActionReceived: (AdminActionEvent) -> Unit = {},
    private val onPingMeasured: (Int) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refGen = AtomicInteger(1)
    private val closedManually = AtomicBoolean(false)
    private val heartbeatSentTimes = ConcurrentHashMap<String, Long>()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var currentLobbyId: String? = null
    @Volatile private var currentUserId: String = ""
    @Volatile private var currentTopic: String = ""
    @Volatile var isJoined: Boolean = false
        private set

    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectDelayMs: Long = 350L

    fun connect(lobbyId: String, userId: String) {
        if (lobbyId.isBlank() || !SupabaseConfig.isConfigured) return
        if (currentLobbyId == lobbyId && isJoined && webSocket != null) return

        disconnect()
        closedManually.set(false)
        currentLobbyId = lobbyId
        currentUserId = userId
        currentTopic = "realtime:match:$lobbyId"
        reconnectDelayMs = 350L
        openSocket()
    }

    fun disconnect() {
        closedManually.set(true)
        isJoined = false
        currentLobbyId = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatSentTimes.clear()
        webSocket?.close(1000, "Match ended")
        webSocket = null
        onConnectionChanged(false)
    }

    private fun openSocket() {
        val lobbyId = currentLobbyId ?: return
        if (closedManually.get()) return

        scope.launch {
            val token = authManager.getValidAccessToken()
            val wsBase = SupabaseConfig.baseUrl
                .replaceFirst("https://", "wss://")
                .replaceFirst("http://", "ws://")
                .trimEnd('/')
            val encodedKey = runCatching { URLEncoder.encode(SupabaseConfig.anonKey, "UTF-8") }
                .getOrDefault(SupabaseConfig.anonKey)
            val wsUrl = "$wsBase/realtime/v1/websocket?apikey=$encodedKey&vsn=1.0.0"

            val req = Request.Builder().url(wsUrl).build()
            val listener = object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    if (closedManually.get() || currentLobbyId != lobbyId) {
                        ws.close(1000, "Stale")
                        return
                    }
                    reconnectDelayMs = 350L
                    sendJoin(ws, currentTopic, token)
                    startHeartbeatLoop(ws)
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    handleIncomingMessage(text)
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    ws.close(code, reason)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    handleDisconnect(ws)
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    handleDisconnect(ws)
                }
            }
            webSocket = SupabaseConfig.httpClient.newWebSocket(req, listener)
        }
    }

    private fun handleDisconnect(ws: WebSocket) {
        if (webSocket === ws) {
            webSocket = null
        }
        val wasJoined = isJoined
        isJoined = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        if (wasJoined) onConnectionChanged(false)

        if (!closedManually.get() && currentLobbyId != null) {
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(reconnectDelayMs)
                reconnectDelayMs = (reconnectDelayMs * 2L).coerceAtMost(2_500L)
                if (!closedManually.get() && currentLobbyId != null) {
                    openSocket()
                }
            }
        }
    }

    private fun sendJoin(ws: WebSocket, topic: String, accessToken: String?) {
        val ref = refGen.getAndIncrement().toString()
        val broadcastCfg = JSONObject().apply {
            put("self", false)
            put("ack", false)
        }
        val config = JSONObject().apply {
            put("broadcast", broadcastCfg)
        }
        val payload = JSONObject().apply {
            put("config", config)
            if (!accessToken.isNullOrBlank()) {
                put("access_token", accessToken)
            }
        }
        val msg = JSONObject().apply {
            put("topic", topic)
            put("event", "phx_join")
            put("payload", payload)
            put("ref", ref)
        }
        heartbeatSentTimes[ref] = SystemClock.elapsedRealtime()
        ws.send(msg.toString())
    }

    private fun startHeartbeatLoop(ws: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && !closedManually.get() && webSocket === ws) {
                // Fast 2.0s Phoenix heartbeat doubles as crisp live WebSocket RTT ping meter
                delay(2_000L)
                if (webSocket !== ws || closedManually.get()) break
                val ref = refGen.getAndIncrement().toString()
                heartbeatSentTimes[ref] = SystemClock.elapsedRealtime()
                val hb = JSONObject().apply {
                    put("topic", "phoenix")
                    put("event", "heartbeat")
                    put("payload", JSONObject())
                    put("ref", ref)
                }
                if (!ws.send(hb.toString())) break
            }
        }
    }

    /**
     * Broadcasts compact player state + optional outgoing PvP hit + active bomb snapshot at ~30 Hz.
     * Returns true if sent immediately over the live WebSocket.
     */
    fun sendPlayerState(
        userId: String,
        displayName: String,
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
        bombSync: BombSyncEvent?,
        matchSync: MatchSyncEvent? = null
    ): Boolean {
        val ws = webSocket ?: return false
        if (!isJoined) return false

        val data = JSONObject().apply {
            put("u", userId)
            put("n", displayName.take(24))
            put("t", team.name)
            put("x", round2(x))
            put("y", round2(y))
            put("z", round2(z))
            put("yw", round1(yawDeg))
            put("pt", round1(pitchDeg))
            put("hp", hp.coerceIn(0, 100))
            put("d", dead)
            put("m", moving)
            put("s", shooting)
            put("w", weaponName.take(24))
            put("k", kills)
            put("dt", deaths)
            if (outgoingHit != null) {
                put("ht", outgoingHit.targetUid)
                put("hd", outgoingHit.damage)
                put("hh", outgoingHit.headshot)
                put("hw", outgoingHit.weaponName)
                put("hs", outgoingHit.seq)
            }
            if (bombSync != null && bombSync.seq > 0) {
                put("bq", bombSync.seq)
                put("bs", bombSync.state.name)
                put("bx", round2(bombSync.x))
                put("by", round2(bombSync.y))
                put("bz", round2(bombSync.z))
                put("bl", bombSync.siteLabel)
                put("bc", round1(bombSync.countdownSec))
                put("rn", bombSync.roundNumber)
                put("wu", bombSync.isWarmup)
            }
            if (matchSync != null && matchSync.seq > 0) {
                put("mq", matchSync.seq)
                put("mr", matchSync.roundNumber)
                put("mc", matchSync.ctScore)
                put("mt", matchSync.tScore)
                put("mw", matchSync.isWarmup)
                put("mo", matchSync.isRoundOver)
                put("ms", matchSync.sidesSwapped)
                put("mn", matchSync.winnerSide)
                put("mz", matchSync.reason)
                put("ml", round1(matchSync.roundTimeSec))
                put("mf", round1(matchSync.freezeTimeSec))
            }
        }

        val outerPayload = JSONObject().apply {
            put("type", "broadcast")
            put("event", "state")
            put("payload", data)
        }
        val frame = JSONObject().apply {
            put("topic", currentTopic)
            put("event", "broadcast")
            put("payload", outerPayload)
            put("ref", JSONObject.NULL)
        }
        return ws.send(frame.toString())
    }

    /**
     * Immediately sends a tiny, minimal-overhead PvP hit packet so hit registration arrives with minimum RTT.
     */
    fun sendHitEvent(
        userId: String,
        displayName: String,
        team: TeamSide,
        hit: OutgoingHitEvent
    ): Boolean {
        val ws = webSocket ?: return false
        if (!isJoined) return false
        val data = JSONObject().apply {
            put("u", userId)
            put("n", displayName.take(24))
            put("t", team.name)
            put("ht", hit.targetUid)
            put("hd", hit.damage)
            put("hh", hit.headshot)
            put("hw", hit.weaponName)
            put("hs", hit.seq)
        }
        val outerPayload = JSONObject().apply {
            put("type", "broadcast")
            put("event", "hit")
            put("payload", data)
        }
        val frame = JSONObject().apply {
            put("topic", currentTopic)
            put("event", "broadcast")
            put("payload", outerPayload)
            put("ref", JSONObject.NULL)
        }
        return ws.send(frame.toString())
    }

    /**
     * Immediately broadcasts an authoritative C4 Bomb or Warmup-skip event to all clients in the match.
     */
    fun sendBombEvent(event: BombSyncEvent): Boolean {
        val ws = webSocket ?: return false
        if (!isJoined) return false

        val data = JSONObject().apply {
            put("u", currentUserId)
            put("bq", event.seq)
            put("bs", event.state.name)
            put("bx", round2(event.x))
            put("by", round2(event.y))
            put("bz", round2(event.z))
            put("bl", event.siteLabel)
            put("bc", round1(event.countdownSec))
            put("rn", event.roundNumber)
            put("wu", event.isWarmup)
        }
        val outerPayload = JSONObject().apply {
            put("type", "broadcast")
            put("event", "bomb")
            put("payload", data)
        }
        val frame = JSONObject().apply {
            put("topic", currentTopic)
            put("event", "broadcast")
            put("payload", outerPayload)
            put("ref", JSONObject.NULL)
        }
        return ws.send(frame.toString())
    }

    /**
     * Immediately broadcasts authoritative round/score/warmup/halftime-swap state so all clients stay 100% synced.
     */
    fun sendMatchEvent(event: MatchSyncEvent): Boolean {
        val ws = webSocket ?: return false
        if (!isJoined) return false
        val data = JSONObject().apply {
            put("u", currentUserId)
            put("mq", event.seq)
            put("mr", event.roundNumber)
            put("mc", event.ctScore)
            put("mt", event.tScore)
            put("mw", event.isWarmup)
            put("mo", event.isRoundOver)
            put("ms", event.sidesSwapped)
            put("mn", event.winnerSide)
            put("mz", event.reason)
            put("ml", round1(event.roundTimeSec))
            put("mf", round1(event.freezeTimeSec))
        }
        val outerPayload = JSONObject().apply {
            put("type", "broadcast")
            put("event", "match")
            put("payload", data)
        }
        val frame = JSONObject().apply {
            put("topic", currentTopic)
            put("event", "broadcast")
            put("payload", outerPayload)
            put("ref", JSONObject.NULL)
        }
        return ws.send(frame.toString())
    }

    /**
     * Broadcasts a killfeed entry immediately so all players in the match see identical killfeed rows.
     */
    fun sendKillFeedEvent(event: KillFeedSyncEvent): Boolean {
        val ws = webSocket ?: return false
        if (!isJoined) return false
        val data = JSONObject().apply {
            put("u", currentUserId)
            put("q", event.seq)
            put("kn", event.killerName.take(28))
            put("kt", event.killerTeam.take(4))
            put("vn", event.victimName.take(28))
            put("vt", event.victimTeam.take(4))
            put("w", event.weaponLabel.take(20))
            put("hs", event.isHeadshot)
            put("ku", event.killerUid.take(64))
            put("vu", event.victimUid.take(64))
        }
        val outerPayload = JSONObject().apply {
            put("type", "broadcast")
            put("event", "killfeed")
            put("payload", data)
        }
        val frame = JSONObject().apply {
            put("topic", currentTopic)
            put("event", "broadcast")
            put("payload", outerPayload)
            put("ref", JSONObject.NULL)
        }
        return ws.send(frame.toString())
    }

    /**
     * Broadcasts an instant admin moderation action (kick / ban / unban) across the active match room.
     */
    fun sendAdminAction(event: AdminActionEvent): Boolean {
        val ws = webSocket ?: return false
        if (!isJoined) return false
        val data = JSONObject().apply {
            put("u", currentUserId)
            put("q", event.seq)
            put("act", event.action.take(16))
            put("tu", event.targetUid.take(64))
            put("ts", event.targetShortId.take(16))
            put("rs", event.reason.take(120))
            put("ss", event.senderShortId.take(16))
        }
        val outerPayload = JSONObject().apply {
            put("type", "broadcast")
            put("event", "admin")
            put("payload", data)
        }
        val frame = JSONObject().apply {
            put("topic", currentTopic)
            put("event", "broadcast")
            put("payload", outerPayload)
            put("ref", JSONObject.NULL)
        }
        return ws.send(frame.toString())
    }

    private fun handleIncomingMessage(text: String) {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return
        val event = root.optString("event")
        val topic = root.optString("topic")
        val ref = root.optString("ref")

        if (event == "phx_reply") {
            val sentAt = heartbeatSentTimes.remove(ref)
            if (sentAt != null) {
                val rtt = (SystemClock.elapsedRealtime() - sentAt).toInt().coerceIn(1, 999)
                onPingMeasured(rtt)
            }
            val payload = root.optJSONObject("payload")
            val status = payload?.optString("status")
            if (topic == currentTopic && status == "ok" && !isJoined) {
                isJoined = true
                onConnectionChanged(true)
            }
            return
        }

        if (event != "broadcast") return
        val outer = root.optJSONObject("payload") ?: return
        val bEvent = outer.optString("event")
        val data = outer.optJSONObject("payload") ?: return

        when (bEvent) {
            "state" -> {
                val uid = data.optString("u")
                if (uid.isBlank() || uid == currentUserId) return
                val st = PlayerMatchState(
                    id = uid,
                    userId = uid,
                    displayName = data.optString("n", "Игрок"),
                    team = if (data.optString("t") == TeamSide.CT.name) TeamSide.CT.name else TeamSide.T.name,
                    x = data.optDouble("x", 0.0),
                    y = data.optDouble("y", 0.0),
                    z = data.optDouble("z", 0.0),
                    yawDeg = data.optDouble("yw", 0.0),
                    pitchDeg = data.optDouble("pt", 0.0),
                    hp = data.optInt("hp", 100).coerceIn(0, 100),
                    dead = data.optBoolean("d", false),
                    moving = data.optBoolean("m", false),
                    shooting = data.optBoolean("s", false),
                    weapon = data.optString("w", "GLOCK_18"),
                    kills = data.optInt("k", 0),
                    deaths = data.optInt("dt", 0),
                    hitTargetUid = data.optString("ht", ""),
                    hitDamage = data.optInt("hd", 0),
                    hitHeadshot = data.optBoolean("hh", false),
                    hitWeapon = data.optString("hw", ""),
                    hitSeq = data.optInt("hs", 0)
                )
                onRemoteStateReceived(st)

                // Piggybacked bomb & match state guarantees every client stays 100% synchronized!
                val bq = data.optInt("bq", 0)
                if (bq > 0) {
                    parseBombSync(data, uid)?.let(onBombSyncReceived)
                }
                val mq = data.optInt("mq", 0)
                if (mq > 0) {
                    onMatchSyncReceived(parseMatchSync(data, uid))
                }
            }
            "hit" -> {
                val uid = data.optString("u")
                if (uid.isBlank() || uid == currentUserId) return
                val st = PlayerMatchState(
                    id = uid,
                    userId = uid,
                    displayName = data.optString("n", "Игрок"),
                    team = if (data.optString("t") == TeamSide.CT.name) TeamSide.CT.name else TeamSide.T.name,
                    x = Double.NaN,
                    y = Double.NaN,
                    z = Double.NaN,
                    hitTargetUid = data.optString("ht", ""),
                    hitDamage = data.optInt("hd", 0),
                    hitHeadshot = data.optBoolean("hh", false),
                    hitWeapon = data.optString("hw", ""),
                    hitSeq = data.optInt("hs", 0)
                )
                onRemoteStateReceived(st)
            }
            "bomb" -> {
                val uid = data.optString("u")
                if (uid == currentUserId) return
                parseBombSync(data, uid)?.let(onBombSyncReceived)
            }
            "match" -> {
                val uid = data.optString("u")
                if (uid == currentUserId) return
                onMatchSyncReceived(parseMatchSync(data, uid))
            }
            "killfeed" -> {
                val uid = data.optString("u")
                if (uid.isBlank() || uid == currentUserId) return
                onKillFeedReceived(
                    KillFeedSyncEvent(
                        seq = data.optInt("q", 0),
                        killerName = data.optString("kn", ""),
                        killerTeam = data.optString("kt", "T"),
                        victimName = data.optString("vn", ""),
                        victimTeam = data.optString("vt", "CT"),
                        weaponLabel = data.optString("w", ""),
                        isHeadshot = data.optBoolean("hs", false),
                        killerUid = data.optString("ku", ""),
                        victimUid = data.optString("vu", ""),
                        senderUid = uid
                    )
                )
            }
            "admin" -> {
                val uid = data.optString("u")
                if (uid.isBlank() || uid == currentUserId) return
                onAdminActionReceived(
                    AdminActionEvent(
                        seq = data.optInt("q", 0),
                        action = data.optString("act", ""),
                        targetUid = data.optString("tu", ""),
                        targetShortId = data.optString("ts", ""),
                        reason = data.optString("rs", ""),
                        senderUid = uid,
                        senderShortId = data.optString("ss", "")
                    )
                )
            }
        }
    }

    private fun parseBombSync(data: JSONObject, senderUid: String): BombSyncEvent? {
        val stateName = data.optString("bs")
        val state = BombState.entries.firstOrNull { it.name == stateName } ?: return null
        return BombSyncEvent(
            seq = data.optInt("bq", 0),
            state = state,
            x = data.optDouble("bx", 0.0).toFloat(),
            y = data.optDouble("by", 0.0).toFloat(),
            z = data.optDouble("bz", 0.0).toFloat(),
            siteLabel = data.optString("bl", ""),
            countdownSec = data.optDouble("bc", 35.0).toFloat(),
            roundNumber = data.optInt("rn", 1),
            isWarmup = data.optBoolean("wu", false),
            senderUid = senderUid
        )
    }

    private fun parseMatchSync(data: JSONObject, senderUid: String): MatchSyncEvent =
        MatchSyncEvent(
            seq = data.optInt("mq", 0),
            roundNumber = data.optInt("mr", 1),
            ctScore = data.optInt("mc", 0),
            tScore = data.optInt("mt", 0),
            isWarmup = data.optBoolean("mw", false),
            isRoundOver = data.optBoolean("mo", false),
            sidesSwapped = data.optBoolean("ms", false),
            winnerSide = data.optString("mn", ""),
            reason = data.optString("mz", ""),
            roundTimeSec = data.optDouble("ml", 105.0).toFloat(),
            freezeTimeSec = data.optDouble("mf", 0.0).toFloat(),
            senderUid = senderUid
        )

    private fun round2(v: Float): Double = kotlin.math.round(v * 100.0) / 100.0
    private fun round1(v: Float): Double = kotlin.math.round(v * 10.0) / 10.0
}
