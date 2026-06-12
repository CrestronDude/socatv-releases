package com.socatv.nova.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.socatv.nova.NovaApp
import com.socatv.nova.databinding.ActivityLoginBinding
import com.socatv.nova.data.repository.IptvRepository
import com.socatv.nova.ui.browse.BrowseActivity
import com.socatv.nova.ui.home.PanelPickerActivity
import com.socatv.nova.utils.PanelAvailability
import com.socatv.nova.utils.Prefs
import com.socatv.nova.utils.ServerAutoSelector
import com.socatv.nova.utils.setFocusBorder
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var repository: IptvRepository

    private var pendingPanelId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = IptvRepository(NovaApp.instance.database)
        pendingPanelId = intent.getStringExtra("pending_panel_id")

        binding.etUsername.setText(Prefs.username)

        // Main connect button
        binding.btnConnect.setOnClickListener { attemptLogin() }
        binding.btnConnect.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                attemptLogin(); true
            } else false
        }
        binding.btnConnect.setOnFocusChangeListener { v, f -> v.setFocusBorder(f) }

        // Alternative sign-in options
        binding.btnQrLogin.setOnClickListener {
            startActivity(Intent(this, QrLoginActivity::class.java))
        }
        binding.btnQrLogin.setOnFocusChangeListener { v, f -> v.setFocusBorder(f) }

        binding.btnImportM3u.setOnClickListener {
            startActivity(Intent(this, M3uImportActivity::class.java))
        }
        binding.btnImportM3u.setOnFocusChangeListener { v, f -> v.setFocusBorder(f) }

        binding.btnImportXtream.setOnClickListener {
            startActivity(Intent(this, XtreamImportActivity::class.java))
        }
        binding.btnImportXtream.setOnFocusChangeListener { v, f -> v.setFocusBorder(f) }

        // Quick Connect — one-tap re-login for returning users
        val savedUser = Prefs.username
        val savedPass = Prefs.password
        if (savedUser.isNotBlank() && savedPass.isNotBlank()) {
            binding.btnQuickConnect.text = "Continue as $savedUser"
            binding.btnQuickConnect.visibility = View.VISIBLE
            binding.btnQuickConnect.setOnClickListener {
                binding.etUsername.setText(savedUser)
                binding.etPassword.setText(savedPass)
                attemptLogin()
            }
            binding.btnQuickConnect.setOnFocusChangeListener { v, f -> v.setFocusBorder(f) }
        }

        binding.etUsername.requestFocus()
    }

    private fun attemptLogin() {
        val username = binding.etUsername.text?.toString()?.trim() ?: ""
        val password = binding.etPassword.text?.toString()?.trim() ?: ""

        if (username.isBlank() || password.isBlank()) {
            Toast.makeText(this, "Please enter username and password", Toast.LENGTH_SHORT).show()
            return
        }

        showLoading(true)

        lifecycleScope.launch {
            // Try all known panel servers silently — only show error if ALL fail
            val result = ServerAutoSelector.findWorkingServer(
                repo = repository,
                username = username,
                password = password,
                extraServers = ServerAutoSelector.getCustomServers()
            )

            showLoading(false)

            if (result != null) {
                Prefs.serverUrl     = result.serverUrl
                Prefs.username      = username
                Prefs.password      = password
                Prefs.isLoggedIn    = true
                Prefs.importType    = "standard"
                result.response.userInfo?.let { info ->
                    Prefs.accountStatus = info.status ?: "Active"
                    Prefs.expDate       = info.expDate ?: ""
                    Prefs.putString("max_connections", info.maxConnections ?: "—")
                    Prefs.putString("active_cons",     info.activeCons ?: "—")
                    Prefs.putString("created_at",      info.createdAt ?: "—")
                    Prefs.putString("is_trial",        info.isTrial ?: "0")
                }
                // Reset so PanelPickerActivity knows to start a fresh probe
                PanelAvailability.reset()

                navigateAfterLogin()
            } else {
                // All servers failed — now it's OK to show an error
                binding.errorText.text = "Cannot connect. Check credentials and try again."
                binding.errorText.visibility = View.VISIBLE
                Toast.makeText(
                    this@LoginActivity,
                    "Login failed — all servers tried",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun navigateAfterLogin() {
        val dest: Intent = when (val pid = pendingPanelId) {
            null, "" -> Intent(this, PanelPickerActivity::class.java)
            "settings" -> {
                startActivity(Intent(this, com.socatv.nova.ui.settings.SettingsActivity::class.java))
                finish()
                return
            }
            else -> {
                Intent(this, BrowseActivity::class.java).apply {
                    putExtra("content_type", panelIdToContentType(pid))
                }
            }
        }
        startActivity(dest)
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun panelIdToContentType(panelId: String) = when (panelId) {
        "live"        -> "LIVE"
        "vod"         -> "VOD"
        "series"      -> "SERIES"
        "radio"       -> "RADIO"
        "favorites"   -> "FAVORITES"
        "catchup"     -> "CATCHUP"
        "multiscreen" -> "MULTISCREEN"
        "all"         -> "ALL"
        "account"     -> "ACCOUNT"
        "epg"         -> "EPG"
        else          -> "LIVE"
    }

    private fun showLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnConnect.isEnabled = !loading
        binding.btnConnect.text = if (loading) "Connecting…" else "Connect"
        if (loading) {
            binding.errorText.visibility = View.GONE
        }
    }
}
