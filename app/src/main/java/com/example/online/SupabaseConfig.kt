package com.example.online

import com.example.BuildConfig
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Central Supabase configuration sourced strictly from BuildConfig (.env / AI Studio Secrets).
 * Never hardcodes credentials in source code.
 */
object SupabaseConfig {
    val baseUrl: String
        get() = BuildConfig.SUPABASE_URL.trim().trimEnd('/')

    val anonKey: String
        get() = BuildConfig.SUPABASE_ANON_KEY.trim()

    val isConfigured: Boolean
        get() {
            val url = baseUrl
            val key = anonKey
            return url.startsWith("https://") &&
                !url.contains("YOUR_SUPABASE_URL") &&
                key.length >= 20 &&
                !key.contains("YOUR_SUPABASE_ANON_KEY")
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
