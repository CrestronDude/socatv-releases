package com.socatv.nova.utils

import android.app.ProgressDialog
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.socatv.nova.BuildConfig
import com.socatv.nova.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object AppUpdater {

    private const val RELEASES_API =
        "https://api.github.com/repos/CrestronDude/socatv-releases/releases/latest"

    // Prefs keys for storing pending update across activity transitions
    private const val KEY_PENDING_CODE  = "pending_update_code"
    private const val KEY_PENDING_NAME  = "pending_update_name"
    private const val KEY_PENDING_URL   = "pending_update_url"
    private const val KEY_PENDING_FORCE = "pending_update_force"

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()

    /**
     * Checks GitHub for a new release and stores update info in prefs if found.
     * Returns true if a FORCE update is available (splash should not navigate past this).
     * Silent on any error.
     */
    suspend fun checkAndStore(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(RELEASES_API)
                .header("Accept", "application/vnd.github.v3+json")
                .build()
            val resp = http.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext false

            val json = JSONObject(resp.body?.string() ?: return@withContext false)
            val tag = json.optString("tag_name", "")
            val latestCode = tag.removePrefix("v").toIntOrNull() ?: return@withContext false
            if (latestCode <= BuildConfig.VERSION_CODE) {
                clearPending()
                return@withContext false
            }

            val assets = json.optJSONArray("assets") ?: return@withContext false
            var apkUrl = ""
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    apkUrl = a.getString("browser_download_url")
                    break
                }
            }
            if (apkUrl.isBlank()) return@withContext false

            val versionName  = json.optString("name", tag)
            val forceUpdate  = RemoteConfigManager.getCached().forceUpdate

            Prefs.putInt("pending_update_code",  latestCode)
            Prefs.putString(KEY_PENDING_NAME,     versionName)
            Prefs.putString(KEY_PENDING_URL,      apkUrl)
            Prefs.putBoolean(KEY_PENDING_FORCE,   forceUpdate)

            forceUpdate
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Shows the update dialog if a pending update is stored in prefs.
     * Returns true if a FORCE update dialog is now blocking the UI.
     * Should be called from the main thread (or switched via withContext).
     */
    fun showPendingDialog(activity: AppCompatActivity, scope: CoroutineScope): Boolean {
        val pendingCode = Prefs.getInt(KEY_PENDING_CODE, 0)
        if (pendingCode <= BuildConfig.VERSION_CODE) return false

        val versionName = Prefs.getString(KEY_PENDING_NAME, "New version")
        val apkUrl      = Prefs.getString(KEY_PENDING_URL,  "")
        val forceUpdate = Prefs.getBoolean(KEY_PENDING_FORCE)

        if (apkUrl.isBlank()) return false
        if (activity.isFinishing || activity.isDestroyed) return false

        showUpdateDialog(activity, scope, versionName, apkUrl, forceUpdate)
        return forceUpdate
    }

    private fun showUpdateDialog(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        versionName: String,
        apkUrl: String,
        force: Boolean
    ) {
        val msg = "$versionName is available.\n\nYou have v${BuildConfig.VERSION_NAME}.\n\n" +
                "Install now for the latest features and improvements."

        AlertDialog.Builder(activity, R.style.NovaDialogTheme)
            .setTitle("Update Available")
            .setMessage(msg)
            .setPositiveButton("Update Now") { _, _ ->
                clearPending()
                downloadAndInstall(activity, scope, apkUrl, versionName)
            }
            .apply { if (!force) setNegativeButton("Later", null) }
            .setCancelable(!force)
            .show()
    }

    fun clearPending() {
        Prefs.putInt(KEY_PENDING_CODE, 0)
        Prefs.putString(KEY_PENDING_NAME,  "")
        Prefs.putString(KEY_PENDING_URL,   "")
        Prefs.putBoolean(KEY_PENDING_FORCE, false)
    }

    @Suppress("DEPRECATION")
    fun downloadAndInstall(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        url: String,
        label: String
    ) {
        val progress = ProgressDialog(activity).apply {
            setTitle("Downloading Update")
            setMessage("$label — please wait…")
            isIndeterminate = false
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }

        scope.launch(Dispatchers.IO) {
            try {
                val updateDir = File(activity.getExternalFilesDir(null), "update")
                updateDir.mkdirs()
                val apkFile = File(updateDir, "SocaTvNova_update.apk")

                val req = Request.Builder().url(url)
                    .header("Accept", "application/octet-stream")
                    .build()
                val resp = http.newCall(req).execute()
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")

                val body = resp.body ?: throw Exception("Empty response body")
                val total = body.contentLength()
                var downloaded = 0L

                apkFile.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(16_384)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                            downloaded += n
                            if (total > 0) {
                                val pct = (downloaded * 100 / total).toInt()
                                withContext(Dispatchers.Main) { progress.progress = pct }
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    triggerInstall(activity, apkFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    Toast.makeText(activity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun triggerInstall(activity: AppCompatActivity, apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )
            activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Toast.makeText(activity, "Install error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
