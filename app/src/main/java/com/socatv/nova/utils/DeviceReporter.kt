package com.socatv.nova.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.util.Collections

object DeviceReporter {

    // Loaded from signed RemoteConfig at runtime — never hardcoded.
    // Falls back to empty string (check-in silently skipped) until config is fetched.
    private val API_KEY: String get() = RemoteConfigManager.getCached().checkinApiKey

    fun checkIn(context: Context, scope: CoroutineScope) {
        val reportingUrl = RemoteConfigManager.getCached().reportingUrl
        if (reportingUrl.isNullOrBlank()) return

        scope.launch(Dispatchers.IO) {
            try {
                val body = buildCheckinPayload(context, paywallHit = false)
                doPost("$reportingUrl/api/checkin", body) { resp ->
                    handleResponse(context, resp, scope)
                }
            } catch (_: Exception) {}
        }
    }

    fun reportPaywallHit(context: Context, scope: CoroutineScope) {
        val reportingUrl = RemoteConfigManager.getCached().reportingUrl
        if (reportingUrl.isNullOrBlank()) return

        scope.launch(Dispatchers.IO) {
            try {
                val body = buildCheckinPayload(context, paywallHit = true)
                doPost("$reportingUrl/api/checkin", body) { resp ->
                    handleResponse(context, resp, scope)
                }
            } catch (_: Exception) {}
        }
    }

    private fun buildCheckinPayload(context: Context, paywallHit: Boolean): JSONObject {
        return JSONObject().apply {
            put("deviceId",       getDeviceId(context))
            put("androidId",      getAndroidId(context))
            put("macAddress",     getMacAddress())
            put("model",          "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            put("brand",          Build.BRAND)
            put("androidVersion", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            put("appVersionCode", getVersionCode(context))
            put("status",         resolveStatus())
            put("trialStartTs",   Prefs.getLong("trial_install_ts", 0L))
            put("licenseKey",     LicenseManager.getStoredLicense())
            put("paywallHit",     paywallHit)
            put("apiKey",         API_KEY)
        }
    }

    private fun handleResponse(context: Context, resp: JSONObject, scope: CoroutineScope) {
        // ── Blacklist enforcement ─────────────────────────────────────────────
        if (resp.optBoolean("blocked")) {
            val reason = resp.optString("blockReason", "")
            scope.launch(Dispatchers.Main) {
                showBlacklistScreen(context, reason)
            }
            return
        }

        // ── Trial extension ───────────────────────────────────────────────────
        if (resp.optBoolean("trialExtended")) {
            val extraDays = resp.optLong("extraDays", 0L)
            if (extraDays > 0) {
                val current = Prefs.getLong("trial_install_ts", System.currentTimeMillis())
                Prefs.putLong("trial_install_ts", current - extraDays * 86_400_000L)
            }
        }

        // ── Admin message ─────────────────────────────────────────────────────
        val adminMsg = resp.optString("adminMessage", "")
        if (adminMsg.isNotBlank()) Prefs.putString("admin_message", adminMsg)
    }

    private fun showBlacklistScreen(context: Context, reason: String) {
        try {
            val intent = Intent(context, com.socatv.nova.ui.blacklist.BlacklistActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("reason", reason)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun doPost(urlStr: String, body: JSONObject, onSuccess: ((JSONObject) -> Unit)? = null) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 8_000
            readTimeout    = 8_000
            doOutput       = true
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode == 200) {
            val resp = conn.inputStream.bufferedReader().readText()
            onSuccess?.invoke(JSONObject(resp))
        }
        conn.disconnect()
    }

    private fun resolveStatus(): String {
        if (LicenseManager.hasValidLicense()) return "licensed"
        return when (TrialManager.getStatus()) {
            TrialManager.Status.ACTIVE   -> "trial"
            TrialManager.Status.EXPIRED  -> "expired"
            TrialManager.Status.LICENSED -> "licensed"
        }
    }

    fun getDeviceId(context: Context): String {
        var id = Prefs.getString("device_id", "")
        if (id.isBlank()) {
            id = java.util.UUID.randomUUID().toString()
            Prefs.putString("device_id", id)
        }
        return id
    }

    private fun getAndroidId(context: Context): String = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    } catch (_: Exception) { "unknown" }

    fun getMacAddress(): String {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.name != "wlan0" && intf.name != "eth0" && intf.name != "wlan1") continue
                val mac = intf.hardwareAddress ?: continue
                if (mac.size < 6) continue
                return mac.joinToString(":") { "%02X".format(it) }
            }
            "unknown"
        } catch (_: Exception) { "unknown" }
    }

    private fun getVersionCode(context: Context): Int = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
        else @Suppress("DEPRECATION") pi.versionCode
    } catch (_: Exception) { 1 }
}
