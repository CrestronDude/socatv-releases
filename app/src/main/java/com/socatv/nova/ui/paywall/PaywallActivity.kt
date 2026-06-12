package com.socatv.nova.ui.paywall

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.socatv.nova.R
import com.socatv.nova.utils.DeviceReporter
import com.socatv.nova.utils.LicenseManager
import com.socatv.nova.utils.RemoteConfigManager
import com.socatv.nova.utils.TrialManager
import com.socatv.nova.utils.setFocusBorder

class PaywallActivity : AppCompatActivity() {

    private lateinit var tvHeadline: TextView
    private lateinit var tvSubtext: TextView
    private lateinit var tvPrice: TextView
    private lateinit var btnBuy: Button
    private lateinit var btnActivate: Button
    private lateinit var btnRestore: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_paywall)

        tvHeadline  = findViewById(R.id.tvPaywallHeadline)
        tvSubtext   = findViewById(R.id.tvPaywallSubtext)
        tvPrice     = findViewById(R.id.tvPaywallPrice)
        btnBuy      = findViewById(R.id.btnPaywallBuy)
        btnActivate = findViewById(R.id.btnPaywallActivate)
        btnRestore  = findViewById(R.id.btnPaywallRestore)

        val cfg = RemoteConfigManager.getCached()
        val isExpired = intent.getBooleanExtra("trial_expired", false)

        if (isExpired) {
            tvHeadline.text = "Your Free Trial Has Ended"
            tvSubtext.text  = "Get full access to all channels, movies, series\nand premium features for just ${cfg.priceLabel}."
        } else {
            val daysLeft = TrialManager.getDaysRemaining(cfg.trialDays)
            tvHeadline.text = "Enjoying SocaTV Nova?"
            tvSubtext.text  = "$daysLeft day${if (daysLeft != 1) "s" else ""} left in your free trial.\nUnlock full access and never miss a moment."
        }
        tvPrice.text = cfg.priceLabel

        btnBuy.text = "Get Access — ${cfg.priceLabel}"
        btnBuy.setOnClickListener { openPayment(cfg.paymentUrl) }
        btnBuy.setOnFocusChangeListener { v, f -> v.setFocusBorder(f) }

        btnActivate.setOnClickListener {
            startActivity(Intent(this, ActivationActivity::class.java))
        }
        btnActivate.setOnFocusChangeListener { v, f -> v.setFocusBorder(f) }

        btnRestore.setOnClickListener { checkRestore() }
        btnRestore.setOnFocusChangeListener { v, f -> v.setFocusBorder(f) }

        // If the user already has a valid license, don't show the paywall
        if (LicenseManager.hasValidLicense()) {
            finishToHome()
            return
        }

        TrialManager.markPaywallSeen()
        DeviceReporter.reportPaywallHit(this, lifecycleScope)
        btnBuy.requestFocus()
    }

    private fun openPayment(url: String) {
        if (url.isBlank()) {
            Toast.makeText(this, "Payment URL not configured yet", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "Open a browser and visit: $url", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkRestore() {
        val stored = LicenseManager.getStoredLicense()
        if (stored.isNotBlank()) {
            val result = LicenseManager.validate(stored)
            when (result.status) {
                LicenseManager.LicenseStatus.VALID -> {
                    Toast.makeText(this, "License restored!", Toast.LENGTH_SHORT).show()
                    finishToHome()
                }
                LicenseManager.LicenseStatus.EXPIRED ->
                    Toast.makeText(this, "Your license expired — renew at the store", Toast.LENGTH_LONG).show()
                else ->
                    Toast.makeText(this, "No valid license found", Toast.LENGTH_SHORT).show()
            }
        } else {
            startActivity(Intent(this, ActivationActivity::class.java))
        }
    }

    private fun finishToHome() {
        startActivity(Intent(this, com.socatv.nova.ui.home.PanelPickerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
    }

    override fun onResume() {
        super.onResume()
        // Re-check after returning from ActivationActivity
        if (LicenseManager.hasValidLicense()) finishToHome()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Don't let back key bypass paywall — must activate or buy
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.onKeyDown(keyCode, event)
    }
}
