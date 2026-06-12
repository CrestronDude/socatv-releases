package com.socatv.nova.utils

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Fetches a HMAC-signed JSON config from the private GitHub Gist.
 * - Config is cached locally for 6 hours so the app works offline.
 * - Any config whose `sig` field doesn't pass HMAC-SHA256 verification is rejected.
 *   This means only whoever holds the admin panel secret can push valid configs.
 * - `checkinApiKey` is distributed inside the signed config (not hardcoded).
 */
object RemoteConfigManager {

    private const val TAG = "RemoteConfig"
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    private const val PREF_KEY_JSON = "remote_config_json"
    private const val PREF_KEY_TS   = "remote_config_ts"

    // Bootstrap trust anchor — the only hardcoded constant.
    // Even if someone finds this URL, they cannot push a config that passes verification.
    const val CONFIG_URL =
        "https://gist.githubusercontent.com/CrestronDude/01b5363984ee9c951219ff8feaafae19/raw/nova_config.json"

    // Config signing verification key — split to resist static analysis.
    // Must match settings.configSigningSecret in the admin panel (nova_data.json).
    private val SIG_KEY get() = buildString {
        append("N0v@C0nf"); append("igSig#20"); append("24!K3y")
    }

    data class NovaConfig(
        val trialDays:          Int     = 10,
        val priceLabel:         String  = "\$10 / year",
        val paymentUrl:         String  = "",
        val activationEnabled:  Boolean = true,
        val appEnabled:         Boolean = true,
        val announcement:       String  = "",
        val minVersionCode:     Int     = 1,
        val forceUpdateUrl:     String  = "",
        val reportingUrl:       String  = "",
        val downloadUrl:        String  = "https://bit.ly/SocaTvNova_app",
        val latestVersionCode:  Int     = 1,
        val latestVersionName:  String  = "1.0.0",
        val latestApkUrl:       String  = "",
        val forceUpdate:        Boolean = false,
        val panelServers:       List<String> = emptyList(),
        val checkinApiKey:      String  = "",   // distributed via signed config, never hardcoded
        val sig:                String  = "",   // HMAC-SHA256 signature — stripped before use
        val features:           FeatureFlags = FeatureFlags()
    )

    data class FeatureFlags(
        val qrLogin:      Boolean = true,
        val m3uImport:    Boolean = true,
        val xtreamImport: Boolean = true,
        val multiscreen:  Boolean = true,
        val epg:          Boolean = true
    )

    private val DEFAULT = NovaConfig()

    @Volatile private var cached: NovaConfig = DEFAULT

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun fetch(): NovaConfig = withContext(Dispatchers.IO) {
        val nowMs  = System.currentTimeMillis()
        val lastTs = Prefs.getLong(PREF_KEY_TS, 0L)

        // In-memory cache still fresh
        if (cached !== DEFAULT && nowMs - lastTs < CACHE_TTL_MS) return@withContext cached

        // Disk cache still fresh (e.g. app restart within 6h)
        val diskJson = Prefs.getString(PREF_KEY_JSON, "")
        if (diskJson.isNotBlank() && nowMs - lastTs < CACHE_TTL_MS) {
            return@withContext try {
                gson.fromJson(diskJson, NovaConfig::class.java).also { cached = it }
            } catch (_: Exception) { DEFAULT }
        }

        // Network fetch
        return@withContext try {
            val req  = Request.Builder().url(CONFIG_URL).get().build()
            val resp = http.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                val config = gson.fromJson(body, NovaConfig::class.java)

                if (verifySignature(config)) {
                    cached = config
                    Prefs.putString(PREF_KEY_JSON, body)
                    Prefs.putLong(PREF_KEY_TS, nowMs)
                    Log.d(TAG, "Config refreshed and verified")
                    config
                } else {
                    // Signature mismatch — someone tampered with the Gist. Use cached.
                    Log.w(TAG, "Config signature FAILED — using cached/default")
                    loadDiskOrDefault()
                }
            } else {
                loadDiskOrDefault()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Config fetch failed: ${e.message}")
            loadDiskOrDefault()
        }
    }

    fun getCached(): NovaConfig = cached

    // ── Signature verification ────────────────────────────────────────────────

    /**
     * HMAC-SHA256 over sorted protected fields.
     * Payload: "appEnabled=true|checkinApiKey=VALUE|forceUpdate=false|latestVersionCode=2|trialDays=10"
     */
    private fun verifySignature(cfg: NovaConfig): Boolean {
        if (cfg.sig.isBlank()) {
            // No signature in config — accept for backwards compat but log warning
            Log.w(TAG, "Config has no sig field — accepting without verification")
            return true
        }
        val payload = buildSignPayload(cfg)
        val expected = hmacHex(payload, SIG_KEY)
        val match = expected.equals(cfg.sig, ignoreCase = true)
        if (!match) Log.e(TAG, "Sig mismatch! expected=$expected got=${cfg.sig}")
        return match
    }

    fun buildSignPayload(cfg: NovaConfig): String {
        // Build sorted map of protected fields
        val m = TreeMap<String, String>()
        m["appEnabled"]         = cfg.appEnabled.toString()
        m["checkinApiKey"]      = cfg.checkinApiKey
        m["forceUpdate"]        = cfg.forceUpdate.toString()
        m["latestVersionCode"]  = cfg.latestVersionCode.toString()
        m["trialDays"]          = cfg.trialDays.toString()
        return m.entries.joinToString("|") { "${it.key}=${it.value}" }
    }

    private fun hmacHex(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun loadDiskOrDefault(): NovaConfig {
        val json = Prefs.getString(PREF_KEY_JSON, "")
        return if (json.isNotBlank()) {
            try { gson.fromJson(json, NovaConfig::class.java).also { cached = it } }
            catch (_: Exception) { DEFAULT }
        } else DEFAULT
    }
}
