package com.socatv.nova.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.socatv.nova.NovaApp
import com.socatv.nova.R
import com.socatv.nova.data.repository.IptvRepository
import com.socatv.nova.ui.home.PanelPickerActivity
import com.socatv.nova.utils.Prefs
import com.socatv.nova.utils.ServerAutoSelector
import com.socatv.nova.utils.setFocusBorder
import kotlinx.coroutines.launch

class XtreamImportActivity : AppCompatActivity() {

    private lateinit var etServer: EditText
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnConnect: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var btnBack: View

    private val repo by lazy { IptvRepository(NovaApp.instance.database) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_xtream_import)

        etServer   = findViewById(R.id.etXtreamServer)
        etUsername = findViewById(R.id.etXtreamUser)
        etPassword = findViewById(R.id.etXtreamPass)
        btnConnect = findViewById(R.id.btnXtreamConnect)
        progressBar= findViewById(R.id.xtreamProgress)
        tvStatus   = findViewById(R.id.tvXtreamStatus)
        btnBack    = findViewById(R.id.btnXtreamBack)

        btnBack.setOnClickListener { finish() }
        btnBack.setOnFocusChangeListener { v, f -> v.setFocusBorder(f) }

        btnConnect.setOnClickListener { doConnect() }
        btnConnect.setOnFocusChangeListener { v, f -> v.setFocusBorder(f) }
        btnConnect.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                doConnect(); true
            } else false
        }

        etServer.requestFocus()
    }

    private fun doConnect() {
        val server = etServer.text?.toString()?.trim() ?: ""
        val user   = etUsername.text?.toString()?.trim() ?: ""
        val pass   = etPassword.text?.toString()?.trim() ?: ""

        if (server.isBlank() || user.isBlank() || pass.isBlank()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true, "Connecting…")

        lifecycleScope.launch {
            // Try the provided server directly, then fall back to auto-detection
            val extraServers = listOf(
                server,
                server.trimEnd('/'),
                if (!server.startsWith("http")) "http://$server" else server
            ).distinct()

            val result = ServerAutoSelector.findWorkingServer(repo, user, pass, extraServers)

            if (result != null) {
                // Save credentials using the working server
                Prefs.serverUrl  = result.serverUrl
                Prefs.username   = user
                Prefs.password   = pass
                Prefs.isLoggedIn = true
                Prefs.importType = "xtream"
                result.response.userInfo?.let { info ->
                    Prefs.accountStatus = info.status ?: "Active"
                    Prefs.expDate       = info.expDate ?: ""
                    Prefs.putString("max_connections", info.maxConnections ?: "—")
                    Prefs.putString("active_cons",     info.activeCons ?: "—")
                    Prefs.putString("created_at",      info.createdAt ?: "—")
                    Prefs.putString("is_trial",        info.isTrial ?: "0")
                }
                // Also add this server to the known custom list for future attempts
                ServerAutoSelector.addCustomServer(result.serverUrl)

                setLoading(false, "")
                Toast.makeText(this@XtreamImportActivity, "Connected!", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this@XtreamImportActivity, PanelPickerActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
            } else {
                setLoading(false, "")
                Toast.makeText(this@XtreamImportActivity,
                    "Connection failed — check your server URL and credentials",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setLoading(loading: Boolean, status: String) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        tvStatus.text = status
        tvStatus.visibility = if (status.isNotEmpty()) View.VISIBLE else View.GONE
        btnConnect.isEnabled = !loading
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
