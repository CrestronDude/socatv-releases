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
import com.socatv.nova.ui.home.PanelPickerActivity
import com.socatv.nova.utils.M3uParser
import com.socatv.nova.utils.Prefs
import com.socatv.nova.utils.setFocusBorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class M3uImportActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var btnImport: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var btnBack: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_m3u_import)

        etUrl       = findViewById(R.id.etM3uUrl)
        btnImport   = findViewById(R.id.btnM3uImport)
        progressBar = findViewById(R.id.m3uProgress)
        tvStatus    = findViewById(R.id.tvM3uStatus)
        btnBack     = findViewById(R.id.btnM3uBack)

        etUrl.setText(Prefs.m3uUrl)

        btnBack.setOnClickListener { finish() }
        btnBack.setOnFocusChangeListener { v, f -> v.setFocusBorder(f) }

        btnImport.setOnClickListener { doImport() }
        btnImport.setOnFocusChangeListener { v, f -> v.setFocusBorder(f) }
        btnImport.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                doImport(); true
            } else false
        }

        etUrl.requestFocus()
    }

    private fun doImport() {
        val url = etUrl.text?.toString()?.trim() ?: ""
        if (url.isBlank()) {
            Toast.makeText(this, "Please enter an M3U URL", Toast.LENGTH_SHORT).show()
            return
        }

        Prefs.m3uUrl = url
        setLoading(true, "Downloading playlist…")

        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) { M3uParser.parseFromUrl(url) }

            if (parsed.error != null) {
                setLoading(false, "")
                Toast.makeText(this@M3uImportActivity,
                    "Import failed: ${parsed.error}", Toast.LENGTH_LONG).show()
                return@launch
            }

            if (parsed.channels.isEmpty()) {
                setLoading(false, "")
                Toast.makeText(this@M3uImportActivity,
                    "No channels found in playlist", Toast.LENGTH_LONG).show()
                return@launch
            }

            setLoading(true, "Saving ${parsed.channels.size} channels…")
            withContext(Dispatchers.IO) {
                NovaApp.instance.database.channelDao().clearAll()
                NovaApp.instance.database.channelDao().insertChannels(parsed.channels)
            }

            Prefs.importType = "m3u"
            Prefs.isLoggedIn = true
            Prefs.username   = "M3U Import"
            Prefs.serverUrl  = ""

            setLoading(false, "")
            Toast.makeText(this@M3uImportActivity,
                "Imported ${parsed.channels.size} channels!", Toast.LENGTH_SHORT).show()

            startActivity(Intent(this@M3uImportActivity, PanelPickerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }
    }

    private fun setLoading(loading: Boolean, status: String) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        tvStatus.text = status
        tvStatus.visibility = if (status.isNotEmpty()) View.VISIBLE else View.GONE
        btnImport.isEnabled = !loading
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
