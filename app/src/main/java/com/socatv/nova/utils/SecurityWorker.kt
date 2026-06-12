package com.socatv.nova.utils

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.work.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Runs every 15 minutes in the background (minimum Android WorkManager interval).
 * Checks in with the admin server. If the device is blacklisted, marks it blocked
 * so the BlacklistActivity shows on the next foreground event.
 */
class SecurityWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val reportingUrl = RemoteConfigManager.getCached().reportingUrl
        if (reportingUrl.isNullOrBlank()) return Result.success()

        return try {
            val apiKey = RemoteConfigManager.getCached().checkinApiKey
            val body = JSONObject().apply {
                put("deviceId",        DeviceReporter.getDeviceId(applicationContext))
                put("macAddress",      DeviceReporter.getMacAddress())
                put("apiKey",          apiKey)
                put("backgroundCheck", true)
            }

            val conn = (URL("$reportingUrl/api/checkin").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 10_000
                readTimeout    = 10_000
                doOutput       = true
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            if (conn.responseCode == 200) {
                val resp = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()

                if (resp.optBoolean("blocked")) {
                    val reason = resp.optString("blockReason", "")
                    Prefs.putBoolean("device_blocked", true)
                    Prefs.putString("block_reason", reason)
                    // If app is in foreground, launch the blacklist screen immediately
                    launchBlacklistIfForeground(reason)
                } else {
                    Prefs.putBoolean("device_blocked", false)
                }
            } else {
                conn.disconnect()
            }
            Result.success()
        } catch (_: Exception) {
            Result.success() // Never fail silently — don't block the app if offline
        }
    }

    private fun launchBlacklistIfForeground(reason: String) {
        val am = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val foreground = am.runningAppProcesses?.any {
            it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                    it.processName == applicationContext.packageName
        } ?: false

        if (foreground) {
            applicationContext.startActivity(
                Intent(applicationContext, com.socatv.nova.ui.blacklist.BlacklistActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra("reason", reason)
                }
            )
        }
    }

    companion object {
        private const val WORK_TAG = "nova_security_check"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SecurityWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(WORK_TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }
    }
}
