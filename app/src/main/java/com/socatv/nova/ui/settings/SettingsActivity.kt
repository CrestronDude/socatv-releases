package com.socatv.nova.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.socatv.nova.NovaApp
import com.socatv.nova.R
import com.socatv.nova.data.model.ServerProfile
import com.socatv.nova.data.model.ServerProfileManager
import com.socatv.nova.databinding.ActivitySettingsBinding
import com.socatv.nova.ui.home.PanelPickerActivity
import com.socatv.nova.ui.profiles.ProfilesActivity
import com.socatv.nova.utils.M3uParser
import com.socatv.nova.utils.Prefs
import com.socatv.nova.utils.setFocusBorder
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadCurrentValues()
        setupListeners()
    }

    private fun loadCurrentValues() {
        binding.etSubtitleLang.setText(Prefs.subtitleLanguage)
        binding.etGoveeIp.setText(Prefs.goveeIp)
        binding.switchGovee.isChecked = Prefs.goveeEnabled
        binding.switchTimeTheme.isChecked = Prefs.timeThemeEnabled
        binding.switchBinge.isChecked = Prefs.bingeCountdownEnabled
        binding.switchSubtitles.isChecked = Prefs.subtitleEnabled

        binding.etM3uUrl.setText(Prefs.m3uUrl)
        binding.tvAccountStatus.text = "Status: ${Prefs.accountStatus.ifBlank { "Unknown" }}"
        binding.tvExpiry.text        = "Expires: ${Prefs.expDate.ifBlank { "N/A" }}"
        binding.tvAppVersion.text    = "SocaTV Nova v1.0.0"
    }

    private fun setupListeners() {
        binding.btnSwitchPanel.setOnClickListener {
            startActivity(Intent(this, PanelPickerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        binding.btnSaveSubtitle.setOnClickListener {
            Prefs.subtitleLanguage = binding.etSubtitleLang.text?.toString()?.trim() ?: "en"
            Prefs.subtitleEnabled  = binding.switchSubtitles.isChecked
            Toast.makeText(this, "Subtitle settings saved", Toast.LENGTH_SHORT).show()
        }

        binding.btnSaveGovee.setOnClickListener {
            val ip = binding.etGoveeIp.text?.toString()?.trim() ?: ""
            Prefs.goveeIp      = ip
            Prefs.goveeEnabled = binding.switchGovee.isChecked
            Toast.makeText(this,
                if (ip.isNotBlank()) "Govee IP saved" else "Govee disabled",
                Toast.LENGTH_SHORT).show()
        }

        binding.switchTimeTheme.setOnCheckedChangeListener { _, checked ->
            Prefs.timeThemeEnabled = checked
        }

        binding.switchBinge.setOnCheckedChangeListener { _, checked ->
            Prefs.bingeCountdownEnabled = checked
        }

        binding.btnManageServers.setOnClickListener { showManageServersDialog() }

        binding.btnManageProfiles.setOnClickListener {
            startActivity(Intent(this, ProfilesActivity::class.java))
        }

        binding.btnSetParentalPin.setOnClickListener { showSetPinDialog() }

        binding.btnClearHistory.setOnClickListener {
            AlertDialog.Builder(this, R.style.NovaDialogTheme)
                .setTitle("Clear Watch History")
                .setMessage("Are you sure you want to clear all watch history?")
                .setPositiveButton("Clear") { _, _ ->
                    CoroutineScope(Dispatchers.IO).launch {
                        NovaApp.instance.database.watchHistoryDao().clearForProfile(Prefs.activeProfileId)
                    }
                    Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnLogout.setOnClickListener {
            AlertDialog.Builder(this, R.style.NovaDialogTheme)
                .setTitle("Log Out")
                .setMessage("Sign out and return to panel selection?")
                .setPositiveButton("Log Out") { _, _ ->
                    Prefs.clearCredentials()
                    startActivity(Intent(this, PanelPickerActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnImportM3u.setOnClickListener { importM3uPlaylist() }

        binding.btnClearM3u.setOnClickListener {
            AlertDialog.Builder(this, R.style.NovaDialogTheme)
                .setTitle("Clear Imported Channels")
                .setMessage("This will remove all M3U imported channels from the database.")
                .setPositiveButton("Clear") { _, _ ->
                    CoroutineScope(Dispatchers.IO).launch {
                        NovaApp.instance.database.channelDao().clearM3uChannels()
                    }
                    Toast.makeText(this, "M3U channels cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnResetApp.setOnClickListener {
            AlertDialog.Builder(this, R.style.NovaDialogTheme)
                .setTitle("Reset App")
                .setMessage("This will clear ALL data including profiles, history and settings.")
                .setPositiveButton("Reset") { _, _ ->
                    Prefs.clearAll()
                    startActivity(Intent(this, PanelPickerActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        listOf(binding.btnSwitchPanel, binding.btnSaveSubtitle, binding.btnSaveGovee,
            binding.btnManageServers, binding.btnManageProfiles, binding.btnSetParentalPin,
            binding.btnClearHistory, binding.btnLogout, binding.btnResetApp,
            binding.btnImportM3u, binding.btnClearM3u).forEach { btn ->
            btn.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.06f else 1f)
                    .scaleY(if (hasFocus) 1.06f else 1f).setDuration(150).start()
                v.setFocusBorder(hasFocus)
            }
        }
    }

    private fun importM3uPlaylist() {
        val url = binding.etM3uUrl.text?.toString()?.trim() ?: ""
        if (url.isBlank()) {
            Toast.makeText(this, "Enter an M3U URL first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!url.startsWith("http")) {
            Toast.makeText(this, "URL must start with http:// or https://", Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.m3uUrl = url
        binding.btnImportM3u.isEnabled = false
        binding.btnImportM3u.text = "Importing..."
        Toast.makeText(this, "Fetching playlist…", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) { M3uParser.parseFromUrl(url) }
            binding.btnImportM3u.isEnabled = true
            binding.btnImportM3u.text = "Import Playlist"
            if (result.error != null) {
                Toast.makeText(this@SettingsActivity, result.error, Toast.LENGTH_LONG).show()
            } else if (result.channels.isEmpty()) {
                Toast.makeText(this@SettingsActivity, "No channels found in playlist", Toast.LENGTH_SHORT).show()
            } else {
                withContext(Dispatchers.IO) {
                    NovaApp.instance.database.channelDao().insertChannels(result.channels)
                }
                Toast.makeText(this@SettingsActivity,
                    "${result.channels.size} channels imported", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showManageServersDialog() {
        ServerProfileManager.syncCurrentToProfiles()
        val profiles = ServerProfileManager.getProfiles()
        val activeIdx = Prefs.activeServerProfileIndex

        val items = profiles.mapIndexed { i, p ->
            "${if (i == activeIdx) "● " else "  "}${p.name}  —  ${p.url}"
        }.toMutableList()
        items.add("+ Add New Server")

        AlertDialog.Builder(this, R.style.NovaDialogTheme)
            .setTitle("Server Profiles")
            .setItems(items.toTypedArray()) { _, idx ->
                if (idx < profiles.size) {
                    // Switch to selected server
                    ServerProfileManager.switchTo(idx)
                    Toast.makeText(this, "Switched to: ${profiles[idx].name}", Toast.LENGTH_SHORT).show()
                } else {
                    showAddServerDialog()
                }
            }.show()
    }

    private fun showAddServerDialog() {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        val etName = android.widget.EditText(this).apply {
            hint = "Name (e.g. Home Server)"
            setPadding(0, 8, 0, 8)
        }
        val etUrl = android.widget.EditText(this).apply {
            hint = "Server URL (http://...)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(0, 8, 0, 8)
        }
        val etUser = android.widget.EditText(this).apply {
            hint = "Username"
            setPadding(0, 8, 0, 8)
        }
        val etPass = android.widget.EditText(this).apply {
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(0, 8, 0, 8)
        }
        container.addView(etName)
        container.addView(etUrl)
        container.addView(etUser)
        container.addView(etPass)

        AlertDialog.Builder(this, R.style.NovaDialogTheme)
            .setTitle("Add Server")
            .setView(container)
            .setPositiveButton("Add & Switch") { _, _ ->
                val name = etName.text.toString().trim().ifBlank { "Server ${ServerProfileManager.getProfiles().size + 1}" }
                val url  = etUrl.text.toString().trim()
                val user = etUser.text.toString().trim()
                val pass = etPass.text.toString()
                if (url.isBlank() || user.isBlank()) {
                    Toast.makeText(this, "URL and username are required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val profile = ServerProfile(UUID.randomUUID().toString(), name, url, user, pass)
                ServerProfileManager.addProfile(profile)
                val idx = ServerProfileManager.getProfiles().indexOfFirst { it.id == profile.id }
                if (idx >= 0) ServerProfileManager.switchTo(idx)
                Toast.makeText(this, "Added and switched to: $name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSetPinDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Enter 4-digit PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(40, 20, 40, 20)
        }
        AlertDialog.Builder(this, R.style.NovaDialogTheme)
            .setTitle("Set Parental PIN")
            .setView(input)
            .setPositiveButton("Set") { _, _ ->
                val pin = input.text?.toString() ?: ""
                if (pin.length == 4) {
                    Prefs.parentalPin     = pin
                    Prefs.parentalEnabled = true
                    Toast.makeText(this, "Parental PIN set", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "PIN must be 4 digits", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
