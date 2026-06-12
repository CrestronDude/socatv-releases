package com.socatv.nova.ui.paywall

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.socatv.nova.R
import com.socatv.nova.utils.LicenseManager
import com.socatv.nova.utils.setFocusBorder
import java.text.SimpleDateFormat
import java.util.Locale

class ActivationActivity : AppCompatActivity() {

    private lateinit var etKey: EditText
    private lateinit var btnActivate: Button
    private lateinit var tvResult: TextView
    private lateinit var btnBack: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_activation)

        etKey      = findViewById(R.id.etLicenseKey)
        btnActivate= findViewById(R.id.btnActivateLicense)
        tvResult   = findViewById(R.id.tvActivationResult)
        btnBack    = findViewById(R.id.btnActivationBack)

        btnBack.setOnClickListener { finish() }
        btnBack.setOnFocusChangeListener { v, f -> v.setFocusBorder(f) }

        btnActivate.setOnClickListener { doActivate() }
        btnActivate.setOnFocusChangeListener { v, f -> v.setFocusBorder(f) }
        btnActivate.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                doActivate(); true
            } else false
        }

        // Pre-fill stored key if any
        val stored = LicenseManager.getStoredLicense()
        if (stored.isNotBlank()) etKey.setText(stored)

        etKey.requestFocus()
    }

    private fun doActivate() {
        val key = etKey.text?.toString()?.trim() ?: ""
        if (key.isBlank()) {
            showResult("Please enter your license key", "#FF5555")
            return
        }

        val result = LicenseManager.activateLicense(key)
        when (result.status) {
            LicenseManager.LicenseStatus.VALID -> {
                val fmt = SimpleDateFormat("MMMM d, yyyy", Locale.US)
                val expiry = result.expiryDate?.let { fmt.format(it) } ?: "Unknown"
                showResult("Activated! Valid until $expiry", "#00DC6E")
                btnActivate.postDelayed({
                    startActivity(Intent(this, com.socatv.nova.ui.home.PanelPickerActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                }, 1500L)
            }
            LicenseManager.LicenseStatus.EXPIRED -> {
                val fmt = SimpleDateFormat("MMMM d, yyyy", Locale.US)
                val expiry = result.expiryDate?.let { fmt.format(it) } ?: "Unknown"
                showResult("Key expired on $expiry — renew at the store", "#FFAA00")
            }
            LicenseManager.LicenseStatus.INVALID ->
                showResult("Invalid key — check for typos", "#FF5555")
            LicenseManager.LicenseStatus.NONE ->
                showResult("Please enter a license key", "#FF5555")
        }
    }

    private fun showResult(msg: String, colorHex: String) {
        tvResult.text = msg
        tvResult.setTextColor(android.graphics.Color.parseColor(colorHex))
        tvResult.visibility = View.VISIBLE
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
