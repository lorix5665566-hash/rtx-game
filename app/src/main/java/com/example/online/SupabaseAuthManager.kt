package com.example.online

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest

data class SupabaseSession(
    val userId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtSec: Long,
    val loginName: String
)

sealed interface AuthActionResult {
    data class Success(val session: SupabaseSession) : AuthActionResult
    data class Error(val message: String) : AuthActionResult
}

/**
 * Cloud username + password authentication manager powered by Supabase GoTrue HTTPS API.
 * Requires zero Google Play Services and persists encrypted/private session tokens across restarts.
 */
class SupabaseAuthManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val refreshMutex = Mutex()

    private val _session = MutableStateFlow<SupabaseSession?>(loadPersistedSession())
    val session: StateFlow<SupabaseSession?> = _session.asStateFlow()

    val isConfigured: Boolean
        get() = SupabaseConfig.isConfigured

    fun deviceHash(): String = DeviceIdProvider.getDeviceHash(appContext)

    /**
     * Verifies both mandatory latest version (`BuildConfig.VERSION_CODE >= min_version_code`)
     * and whether the current hardware device hash is banned on the server.
     */
    suspend fun checkClientAccess(): ClientAccessStatus = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext ClientAccessStatus(allowed = true, checked = false)
        val token = _session.value?.accessToken?.takeIf { it.isNotBlank() }
        val payload = JSONObject().apply {
            put("p_version_code", com.example.BuildConfig.VERSION_CODE)
            put("p_device_hash", deviceHash())
        }
        val builder = SupabaseConfig.applyPublicAuthHeaders(
            Request.Builder()
                .url("${SupabaseConfig.baseUrl}/rest/v1/rpc/check_client_access")
                .addHeader("Content-Type", "application/json")
        )
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        val req = builder.post(payload.toString().toRequestBody(JSON_MEDIA)).build()
        runCatching {
            SupabaseConfig.httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@use ClientAccessStatus(allowed = true, checked = false)
                }
                val text = resp.body?.string().orEmpty().trim()
                val json = runCatching { JSONObject(text) }.getOrNull()
                    ?: return@use ClientAccessStatus(allowed = true, checked = false)
                ClientAccessStatus(
                    allowed = json.optBoolean("allowed", true),
                    updateRequired = json.optBoolean("update_required", false),
                    minVersionCode = json.optInt("min_version_code", com.example.BuildConfig.VERSION_CODE),
                    latestVersionName = json.optString("latest_version_name", com.example.BuildConfig.VERSION_NAME),
                    updateMessage = json.optString("update_message", ""),
                    deviceBanned = json.optBoolean("device_banned", false),
                    banReason = json.optString("ban_reason", ""),
                    checked = true
                )
            }
        }.getOrElse {
            ClientAccessStatus(allowed = true, checked = false)
        }
    }

    suspend fun register(
        rawLogin: String,
        password: String,
        confirmPassword: String
    ): AuthActionResult = withContext(Dispatchers.IO) {
        SupabaseConfig.configValidationError()?.let {
            return@withContext AuthActionResult.Error(it)
        }
        val access = checkClientAccess()
        if (access.updateRequired) {
            return@withContext AuthActionResult.Error(
                "Требуется последняя версия ${access.latestVersionName}. Обновите игру, чтобы продолжить."
            )
        }
        if (access.deviceBanned) {
            return@withContext AuthActionResult.Error(
                "Это устройство заблокировано (${access.banReason.ifBlank { "решение администратора" }}). Создание новых аккаунтов запрещено."
            )
        }
        val login = rawLogin.trim()
        val validationError = validateCredentials(login, password)
        if (validationError != null) return@withContext AuthActionResult.Error(validationError)
        if (password != confirmPassword) {
            return@withContext AuthActionResult.Error("Пароли не совпадают.")
        }

        val syntheticEmail = loginToSupabaseEmail(login)
        val body = JSONObject().apply {
            put("email", syntheticEmail)
            put("password", password)
            put("data", JSONObject().apply {
                put("display_name", login)
                put("device_hash", deviceHash())
            })
        }

        val req = SupabaseConfig.applyPublicAuthHeaders(
            Request.Builder()
                .url("${SupabaseConfig.baseUrl}/auth/v1/signup")
                .addHeader("Content-Type", "application/json")
        )
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        runCatching {
            SupabaseConfig.httpClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (!resp.isSuccessful) {
                    return@use AuthActionResult.Error(mapAuthError(resp.code, json, text, isSignup = true))
                }
                val parsed = parseSession(json, fallbackLogin = login)
                if (parsed == null) {
                    // Happens when Supabase "Confirm email" toggle is still enabled
                    return@use AuthActionResult.Error(
                        "Аккаунт создан, но в Supabase включено подтверждение почты. Отключите «Confirm email» в Authentication → Providers → Email и войдите снова."
                    )
                }
                saveSession(parsed)
                val postAccess = checkClientAccess()
                if (postAccess.deviceBanned) {
                    logout()
                    return@use AuthActionResult.Error(
                        "Это устройство заблокировано (${postAccess.banReason.ifBlank { "решение администратора" }})."
                    )
                }
                AuthActionResult.Success(parsed)
            }
        }.getOrElse { err ->
            AuthActionResult.Error("Ошибка сети при регистрации: ${err.localizedMessage ?: "нет подключения"}")
        }
    }

    suspend fun login(
        rawLogin: String,
        password: String
    ): AuthActionResult = withContext(Dispatchers.IO) {
        SupabaseConfig.configValidationError()?.let {
            return@withContext AuthActionResult.Error(it)
        }
        val access = checkClientAccess()
        if (access.updateRequired) {
            return@withContext AuthActionResult.Error(
                "Требуется последняя версия ${access.latestVersionName}. Обновите игру, чтобы продолжить."
            )
        }
        if (access.deviceBanned) {
            return@withContext AuthActionResult.Error(
                "Это устройство заблокировано (${access.banReason.ifBlank { "решение администратора" }}). Вход невозможен."
            )
        }
        val login = rawLogin.trim()
        val validationError = validateCredentials(login, password)
        if (validationError != null) return@withContext AuthActionResult.Error(validationError)

        val syntheticEmail = loginToSupabaseEmail(login)
        val body = JSONObject().apply {
            put("email", syntheticEmail)
            put("password", password)
        }

        val req = SupabaseConfig.applyPublicAuthHeaders(
            Request.Builder()
                .url("${SupabaseConfig.baseUrl}/auth/v1/token?grant_type=password")
                .addHeader("Content-Type", "application/json")
        )
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        runCatching {
            SupabaseConfig.httpClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (!resp.isSuccessful) {
                    return@use AuthActionResult.Error(mapAuthError(resp.code, json, text, isSignup = false))
                }
                val parsed = parseSession(json, fallbackLogin = login)
                    ?: return@use AuthActionResult.Error("Сервер вернул некорректную сессию.")
                saveSession(parsed)
                val postAccess = checkClientAccess()
                if (postAccess.deviceBanned) {
                    logout()
                    return@use AuthActionResult.Error(
                        "Это устройство заблокировано (${postAccess.banReason.ifBlank { "решение администратора" }})."
                    )
                }
                AuthActionResult.Success(parsed)
            }
        }.getOrElse { err ->
            AuthActionResult.Error("Ошибка сети при входе: ${err.localizedMessage ?: "нет подключения"}")
        }
    }

    /**
     * Returns a fresh Bearer JWT access token, transparently refreshing via refresh_token when needed.
     */
    suspend fun getValidAccessToken(): String? = refreshMutex.withLock {
        val current = _session.value ?: return null
        val nowSec = System.currentTimeMillis() / 1000L
        if (current.expiresAtSec > nowSec + 45L && current.accessToken.isNotBlank()) {
            return current.accessToken
        }
        return refreshTokenLocked(current)
    }

    /**
     * Forces an immediate JWT refresh (used when PostgREST rejects a token with HTTP 401).
     */
    suspend fun forceRefreshAccessToken(): String? = refreshMutex.withLock {
        val current = _session.value ?: return null
        refreshTokenLocked(current)
    }

    private suspend fun refreshTokenLocked(current: SupabaseSession): String? {
        if (current.refreshToken.isBlank() || !SupabaseConfig.isConfigured) {
            return current.accessToken.takeIf { it.isNotBlank() }
        }

        val refreshed = withContext(Dispatchers.IO) {
            val body = JSONObject().apply { put("refresh_token", current.refreshToken) }
            val req = SupabaseConfig.applyPublicAuthHeaders(
                Request.Builder()
                    .url("${SupabaseConfig.baseUrl}/auth/v1/token?grant_type=refresh_token")
                    .addHeader("Content-Type", "application/json")
            )
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()
            runCatching {
                SupabaseConfig.httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val json = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull()
                    parseSession(json, fallbackLogin = current.loginName)
                }
            }.getOrNull()
        }

        return if (refreshed != null) {
            saveSession(refreshed)
            refreshed.accessToken
        } else {
            current.accessToken.takeIf { it.isNotBlank() }
        }
    }

    suspend fun logout() {
        val token = _session.value?.accessToken
        clearSession()
        if (!token.isNullOrBlank() && SupabaseConfig.isConfigured) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder()
                        .url("${SupabaseConfig.baseUrl}/auth/v1/logout")
                        .addHeader("apikey", SupabaseConfig.anonKey)
                        .addHeader("Authorization", "Bearer $token")
                        .post("".toRequestBody(JSON_MEDIA))
                        .build()
                    SupabaseConfig.httpClient.newCall(req).execute().close()
                }
            }
        }
    }

    private fun validateCredentials(login: String, password: String): String? {
        if (login.length < 3 || login.length > 20) {
            return "Логин должен содержать от 3 до 20 символов."
        }
        if (!LOGIN_REGEX.matches(login)) {
            return "Логин может содержать только буквы, цифры, «_», «-» и «.»."
        }
        if (password.length < 6 || password.length > 64) {
            return "Пароль должен содержать от 6 до 64 символов."
        }
        return null
    }

    private fun loginToSupabaseEmail(login: String): String {
        val lower = login.trim().lowercase()
        if (lower.contains("@")) return lower
        return if (ASCII_LOGIN_REGEX.matches(lower)) {
            "u_$lower@vanguardrtx.app"
        } else {
            val digest = MessageDigest.getInstance("SHA-256").digest(lower.toByteArray(Charsets.UTF_8))
            val hex = digest.take(10).joinToString("") { "%02x".format(it) }
            "u_$hex@vanguardrtx.app"
        }
    }

    private fun parseSession(json: JSONObject?, fallbackLogin: String): SupabaseSession? {
        if (json == null) return null
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        val userObj = json.optJSONObject("user")
        val uid = userObj?.optString("id").orEmpty()
        if (access.isBlank() || uid.isBlank()) return null

        val expiresIn = json.optLong("expires_in", 3600L).coerceAtLeast(60L)
        val expiresAt = json.optLong("expires_at", (System.currentTimeMillis() / 1000L) + expiresIn)
        val metaName = userObj?.optJSONObject("user_metadata")?.optString("display_name")
            ?.takeIf { it.isNotBlank() } ?: fallbackLogin

        return SupabaseSession(
            userId = uid,
            accessToken = access,
            refreshToken = refresh,
            expiresAtSec = expiresAt,
            loginName = metaName
        )
    }

    private fun mapAuthError(statusCode: Int, json: JSONObject?, raw: String, isSignup: Boolean): String {
        val rawDetail = listOf(
            json?.optString("msg").orEmpty(),
            json?.optString("error_description").orEmpty(),
            json?.optString("message").orEmpty(),
            json?.optString("error").orEmpty(),
            json?.optString("hint").orEmpty()
        ).firstOrNull { it.isNotBlank() }
            ?: raw.trim().replace(Regex("\\s+"), " ").take(140)
        val msg = rawDetail.lowercase()
        val actionLabel = if (isSignup) "Не удалось зарегистрироваться" else "Не удалось войти"
        return when {
            msg.contains("already registered") || msg.contains("user_already_exists") ->
                "Аккаунт с этим логином уже существует. Выберите «ВОЙТИ»."
            msg.contains("invalid login credentials") || msg.contains("invalid_credentials") ->
                "Неверный логин или пароль. Если у вас ещё нет аккаунта, выберите «РЕГИСТРАЦИЯ»."
            msg.contains("email not confirmed") ->
                "В Supabase включено подтверждение почты. Отключите «Confirm email» в настройках Email провайдера."
            msg.contains("invalid api key") || msg.contains("no api key") ->
                "Неверный ключ SUPABASE_ANON_KEY (HTTP $statusCode). Скопируйте Publishable key или anon public из Project Settings → API Keys."
            msg.contains("secret api key") || (statusCode == 401 && msg.contains("forbidden")) ->
                "В SUPABASE_ANON_KEY вставлен секретный ключ. Замените его на публичный Publishable key или anon public."
            msg.contains("signups not allowed") || msg.contains("signup_disabled") ->
                "В Supabase отключена регистрация. Включите «Allow new users to sign up» в Authentication → Sign In / Providers → Email."
            statusCode == 404 ->
                "Неверный адрес SUPABASE_URL (HTTP 404: ${SupabaseConfig.baseUrl}). Проверьте, что ссылка скопирована полностью через кнопку Copy в Data API."
            msg.contains("password") && msg.contains("6") ->
                "Пароль должен быть не короче 6 символов."
            msg.contains("rate limit") ->
                "Слишком много попыток. Подождите немного и попробуйте снова."
            rawDetail.isNotBlank() -> "$actionLabel (HTTP $statusCode): $rawDetail"
            else -> "$actionLabel (HTTP $statusCode): проверьте SUPABASE_URL и SUPABASE_ANON_KEY."
        }
    }

    private fun saveSession(session: SupabaseSession) {
        prefs.edit()
            .putString(KEY_UID, session.userId)
            .putString(KEY_ACCESS, session.accessToken)
            .putString(KEY_REFRESH, session.refreshToken)
            .putLong(KEY_EXPIRES_AT, session.expiresAtSec)
            .putString(KEY_LOGIN_NAME, session.loginName)
            .apply()
        _session.value = session
    }

    private fun clearSession() {
        prefs.edit().clear().apply()
        _session.value = null
    }

    private fun loadPersistedSession(): SupabaseSession? {
        if (!SupabaseConfig.isConfigured) return null
        val uid = prefs.getString(KEY_UID, null)?.takeIf { it.isNotBlank() } ?: return null
        val access = prefs.getString(KEY_ACCESS, null)?.takeIf { it.isNotBlank() } ?: return null
        val refresh = prefs.getString(KEY_REFRESH, "").orEmpty()
        val expires = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val login = prefs.getString(KEY_LOGIN_NAME, "Игрок").orEmpty().ifBlank { "Игрок" }
        return SupabaseSession(uid, access, refresh, expires, login)
    }

    companion object {
        private const val PREFS_NAME = "vanguard_supabase_session_v1"
        private const val KEY_UID = "uid"
        private const val KEY_ACCESS = "access"
        private const val KEY_REFRESH = "refresh"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_LOGIN_NAME = "login_name"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val LOGIN_REGEX = Regex("^[a-zA-Z0-9_.\\-а-яА-ЯёЁ@]+$")
        private val ASCII_LOGIN_REGEX = Regex("^[a-z0-9_\\-]+$")
    }
}
