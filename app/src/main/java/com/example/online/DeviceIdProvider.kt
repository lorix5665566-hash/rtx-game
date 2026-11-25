package com.example.online

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

/**
 * Generates a stable, permissionless SHA-256 device fingerprint used strictly for
 * server-side device-ban enforcement and preventing banned devices from creating new accounts.
 */
object DeviceIdProvider {
    private const val PREFS_NAME = "vanguard_device_prefs"
    private const val KEY_FALLBACK_ID = "fallback_device_uuid"
    private const val KNOWN_BAD_ANDROID_ID = "9774d56d682e549c"

    @Volatile
    private var cachedHash: String? = null

    fun getDeviceHash(context: Context): String {
        cachedHash?.let { return it }
        val appCtx = context.applicationContext
        val androidId = runCatching {
            Settings.Secure.getString(appCtx.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.trim().orEmpty()

        val rawSeed = if (androidId.length >= 8 && androidId != KNOWN_BAD_ANDROID_ID) {
            androidId
        } else {
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_FALLBACK_ID, null)?.trim().orEmpty()
            existing.ifBlank {
                UUID.randomUUID().toString().also { generated ->
                    prefs.edit().putString(KEY_FALLBACK_ID, generated).apply()
                }
            }
        }

        val digest = MessageDigest.getInstance("SHA-256")
            .digest("vanguard_rtx_dev_v1:$rawSeed".toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        cachedHash = hex
        return hex
    }
}
