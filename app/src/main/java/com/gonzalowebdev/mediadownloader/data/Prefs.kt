package com.gonzalowebdev.mediadownloader.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Ajustes normales + tokens/secretos cifrados (EncryptedSharedPreferences, AES-256). */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secure: SharedPreferences by lazy {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var wifiOnly: Boolean
        get() = sp.getBoolean("wifiOnly", false)
        set(v) = sp.edit().putBoolean("wifiOnly", v).apply()

    var notifications: Boolean
        get() = sp.getBoolean("notifications", true)
        set(v) = sp.edit().putBoolean("notifications", v).apply()

    var concurrency: Int
        get() = sp.getInt("concurrency", 2).coerceIn(1, 5)
        set(v) = sp.edit().putInt("concurrency", v.coerceIn(1, 5)).apply()

    var pinterestClientId: String?
        get() = sp.getString("pinterestClientId", null)
        set(v) = sp.edit().putString("pinterestClientId", v).apply()

    var pinterestRedirectUri: String
        get() = sp.getString("pinterestRedirectUri", DEFAULT_REDIRECT) ?: DEFAULT_REDIRECT
        set(v) = sp.edit().putString("pinterestRedirectUri", v).apply()

    var pinterestSandbox: Boolean
        get() = sp.getBoolean("pinterestSandbox", false)
        set(v) = sp.edit().putBoolean("pinterestSandbox", v).apply()

    var oauthState: String?
        get() = sp.getString("oauthState", null)
        set(v) = sp.edit().putString("oauthState", v).apply()

    var pinterestClientSecret: String?
        get() = secure.getString("pinterestClientSecret", null)
        set(v) = secure.edit().putString("pinterestClientSecret", v).apply()

    var accessToken: String?
        get() = secure.getString("accessToken", null)
        set(v) = secure.edit().putString("accessToken", v).apply()

    var refreshToken: String?
        get() = secure.getString("refreshToken", null)
        set(v) = secure.edit().putString("refreshToken", v).apply()

    var tokenExpiresAt: Long
        get() = secure.getLong("tokenExpiresAt", 0L)
        set(v) = secure.edit().putLong("tokenExpiresAt", v).apply()

    var ytdlpLastUpdate: Long
        get() = sp.getLong("ytdlpLastUpdate", 0L)
        set(v) = sp.edit().putLong("ytdlpLastUpdate", v).apply()

    fun getSession(platform: String): String? = secure.getString("session_$platform", null)

    fun setSession(platform: String, value: String?) {
        if (value == null) secure.edit().remove("session_$platform").apply()
        else secure.edit().putString("session_$platform", value).apply()
    }

    fun clearPinterestTokens() {
        secure.edit().remove("accessToken").remove("refreshToken").remove("tokenExpiresAt").apply()
    }

    companion object {
        const val DEFAULT_REDIRECT = "mediadownloaderhub://pinterest-callback"
    }
}
