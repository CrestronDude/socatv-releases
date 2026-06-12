package com.socatv.nova.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object Prefs {
    private const val PREF_NAME = "nova_prefs"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // ========== SERVER CONFIG ==========

    var serverUrl: String
        get() = prefs.getString("server_url", "http://hostengine.live") ?: "http://hostengine.live"
        set(v) = prefs.edit { putString("server_url", v) }

    var username: String
        get() = prefs.getString("username", "") ?: ""
        set(v) = prefs.edit { putString("username", v) }

    var password: String
        get() = prefs.getString("password", "") ?: ""
        set(v) = prefs.edit { putString("password", v) }

    var isLoggedIn: Boolean
        get() = prefs.getBoolean("is_logged_in", false)
        set(v) = prefs.edit { putBoolean("is_logged_in", v) }

    var accountStatus: String
        get() = prefs.getString("account_status", "") ?: ""
        set(v) = prefs.edit { putString("account_status", v) }

    var expDate: String
        get() = prefs.getString("exp_date", "") ?: ""
        set(v) = prefs.edit { putString("exp_date", v) }

    // ========== PROFILES ==========

    var profilesJson: String
        get() = prefs.getString("profiles_json", "") ?: ""
        set(v) = prefs.edit { putString("profiles_json", v) }

    var activeProfileId: String
        get() = prefs.getString("active_profile_id", "default") ?: "default"
        set(v) = prefs.edit { putString("active_profile_id", v) }

    // ========== SETTINGS ==========

    var subtitleLanguage: String
        get() = prefs.getString("subtitle_lang", "en") ?: "en"
        set(v) = prefs.edit { putString("subtitle_lang", v) }

    var subtitleEnabled: Boolean
        get() = prefs.getBoolean("subtitle_enabled", false)
        set(v) = prefs.edit { putBoolean("subtitle_enabled", v) }

    var goveeIp: String
        get() = prefs.getString("govee_ip", "") ?: ""
        set(v) = prefs.edit { putString("govee_ip", v) }

    var goveeEnabled: Boolean
        get() = prefs.getBoolean("govee_enabled", false)
        set(v) = prefs.edit { putBoolean("govee_enabled", v) }

    var parentalPin: String
        get() = prefs.getString("parental_pin", "") ?: ""
        set(v) = prefs.edit { putString("parental_pin", v) }

    var parentalEnabled: Boolean
        get() = prefs.getBoolean("parental_enabled", false)
        set(v) = prefs.edit { putBoolean("parental_enabled", v) }

    var screensaverDelayMinutes: Int
        get() = prefs.getInt("screensaver_delay", 5)
        set(v) = prefs.edit { putInt("screensaver_delay", v) }

    var timeThemeEnabled: Boolean
        get() = prefs.getBoolean("time_theme_enabled", true)
        set(v) = prefs.edit { putBoolean("time_theme_enabled", v) }

    var streamBufferMs: Int
        get() = prefs.getInt("stream_buffer_ms", 5000)
        set(v) = prefs.edit { putInt("stream_buffer_ms", v) }

    var konamiUnlocked: Boolean
        get() = prefs.getBoolean("konami_unlocked", false)
        set(v) = prefs.edit { putBoolean("konami_unlocked", v) }

    var lastMood: String
        get() = prefs.getString("last_mood", "SURPRISE") ?: "SURPRISE"
        set(v) = prefs.edit { putString("last_mood", v) }

    var bingeCountdownEnabled: Boolean
        get() = prefs.getBoolean("binge_countdown", true)
        set(v) = prefs.edit { putBoolean("binge_countdown", v) }

    var splitScreenEnabled: Boolean
        get() = prefs.getBoolean("split_screen", false)
        set(v) = prefs.edit { putBoolean("split_screen", v) }

    var openSubtitlesToken: String
        get() = prefs.getString("opensubs_token", "") ?: ""
        set(v) = prefs.edit { putString("opensubs_token", v) }

    // ========== FAVORITES ==========

    private var favoritesSet: Set<String>
        get() = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        set(v) = prefs.edit { putStringSet("favorites", v) }

    fun addFavorite(id: String) { favoritesSet = favoritesSet + id }
    fun removeFavorite(id: String) { favoritesSet = favoritesSet - id }
    fun isFavorite(id: String): Boolean = id in favoritesSet
    fun getFavoriteIds(): List<String> = favoritesSet.toList()
    fun toggleFavorite(id: String): Boolean {
        return if (isFavorite(id)) { removeFavorite(id); false } else { addFavorite(id); true }
    }

    // ========== M3U IMPORT ==========

    var m3uUrl: String
        get() = prefs.getString("m3u_url", "") ?: ""
        set(v) = prefs.edit { putString("m3u_url", v) }

    // ========== CUSTOM PLAYLISTS ==========

    var playlistsJson: String
        get() = prefs.getString("playlists_json", "") ?: ""
        set(v) = prefs.edit { putString("playlists_json", v) }

    // ========== CONTINUE WATCHING ==========

    fun saveProgress(contentId: String, progressMs: Long, durationMs: Long) {
        prefs.edit {
            putLong("progress_$contentId", progressMs)
            putLong("duration_$contentId", durationMs)
        }
    }

    fun getProgress(contentId: String): Pair<Long, Long> {
        return Pair(
            prefs.getLong("progress_$contentId", 0L),
            prefs.getLong("duration_$contentId", 0L)
        )
    }

    // ========== SERVER PROFILES ==========

    var serverProfilesJson: String
        get() = prefs.getString("server_profiles_json", "") ?: ""
        set(v) = prefs.edit { putString("server_profiles_json", v) }

    var activeServerProfileIndex: Int
        get() = prefs.getInt("active_server_profile_idx", 0)
        set(v) = prefs.edit { putInt("active_server_profile_idx", v) }

    // ========== CUSTOM SERVER LIST (pipe-separated) ==========

    var customServersJson: String
        get() = prefs.getString("custom_servers_json", "") ?: ""
        set(v) = prefs.edit { putString("custom_servers_json", v) }

    // ========== IMPORT TYPE (xtream | m3u | standard) ==========

    var importType: String
        get() = prefs.getString("import_type", "standard") ?: "standard"
        set(v) = prefs.edit { putString("import_type", v) }

    // ========== GENERIC KEY/VALUE ==========

    fun getString(key: String, default: String = ""): String =
        prefs.getString(key, default) ?: default

    fun putString(key: String, value: String) =
        prefs.edit { putString(key, value) }

    fun getLong(key: String, default: Long = 0L): Long =
        prefs.getLong(key, default)

    fun putLong(key: String, value: Long) =
        prefs.edit { putLong(key, value) }

    fun getInt(key: String, default: Int = 0): Int =
        prefs.getInt(key, default)

    fun putInt(key: String, value: Int) =
        prefs.edit { putInt(key, value) }

    fun getBoolean(key: String, default: Boolean = false): Boolean =
        prefs.getBoolean(key, default)

    fun putBoolean(key: String, value: Boolean) =
        prefs.edit { putBoolean(key, value) }

    // ========== UTIL ==========

    fun clearCredentials() {
        prefs.edit {
            remove("username")
            remove("password")
            remove("is_logged_in")
            remove("account_status")
            remove("exp_date")
        }
    }

    fun clearAll() {
        prefs.edit { clear() }
    }
}
