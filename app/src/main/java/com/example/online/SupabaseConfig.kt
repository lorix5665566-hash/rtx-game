package com.example.online

import com.example.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Central Supabase configuration sourced strictly from BuildConfig (.env / AI Studio Secrets).
 * Never hardcodes credentials in source code.
 */
object SupabaseConfig {
    val baseUrl: String
        get() {
            val raw = BuildConfig.SUPABASE_URL
                .trim()
                .trim('"', '\'')
                .trim()
                .trimEnd('/')
            val parsed = raw.toHttpUrlOrNull()
            return if (parsed != null && parsed.host.endsWith(".supabase.co", ignoreCase = true)) {
                "${parsed.scheme}://${parsed.host}"
            } else {
                raw
                    .removeSuffix("/rest/v1")
                    .removeSuffix("/auth/v1")
                    .trimEnd('/')
            }
        }

    val anonKey: String
        get() = BuildConfig.SUPABASE_ANON_KEY
            .trim()
            .trim('"', '\'')
            .trim()

    val isConfigured: Boolean
        get() {
            val url = baseUrl
            val key = anonKey
            return url.startsWith("https://") &&
                !url.contains("YOUR_SUPABASE_URL") &&
                key.length >= 20 &&
                !key.contains("YOUR_SUPABASE_ANON_KEY")
        }

    fun configValidationError(): String? {
        if (!isConfigured) return MISSING_CONFIG_MESSAGE
        if (baseUrl.contains("supabase.com/dashboard", ignoreCase = true)) {
            return "В SUPABASE_URL указана ссылка на панель (supabase.com/dashboard). Нужен Project URL вида https://xxxx.supabase.co из Project Settings → Data API."
        }
        if (anonKey.startsWith("sb_secret_", ignoreCase = true)) {
            return "В SUPABASE_ANON_KEY вставлен секретный ключ (sb_secret_). Нужен публичный Publishable key (sb_publishable_...) или anon public (eyJ...)."
        }
        return null
    }

    /**
     * Attaches required Supabase GoTrue headers for unauthenticated auth requests.
     * Supports both new Publishable keys (`sb_publishable_...`) and legacy JWT `anon` keys (`eyJ...`).
     */
    fun applyPublicAuthHeaders(builder: okhttp3.Request.Builder): okhttp3.Request.Builder {
        val key = anonKey
        builder.addHeader("apikey", key)
        if (key.startsWith("eyJ")) {
            builder.addHeader("Authorization", "Bearer $key")
        }
        return builder
    }

    const val MISSING_CONFIG_MESSAGE =
        "Облачный сервер Supabase ещё не подключён. Добавьте SUPABASE_URL и SUPABASE_ANON_KEY в панель Secrets (или в секреты GitHub Actions) и пересоберите приложение."

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
