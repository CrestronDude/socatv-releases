package com.socatv.nova.ui.account

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.socatv.nova.NovaApp
import com.socatv.nova.R
import com.socatv.nova.data.repository.IptvRepository
import com.socatv.nova.databinding.ActivityAccountBinding
import com.socatv.nova.ui.home.PanelPickerActivity
import com.socatv.nova.ui.subscription.SubscriptionActivity
import com.socatv.nova.utils.LicenseManager
import com.socatv.nova.utils.Prefs
import com.socatv.nova.utils.RemoteConfigManager
import com.socatv.nova.utils.TrialManager
import com.socatv.nova.utils.setFocusBorder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AccountActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAccountBinding
    private val repo by lazy { IptvRepository(NovaApp.instance.database) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccountBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadFanart()
        populateAccountInfo()
        populateSubscriptionCard()
        setupListeners()
        refreshFromServer()
    }

    private fun loadFanart() {
        lifecycleScope.launch {
            val url = try { repo.getTrendingBackdrop() } catch (e: Exception) { null }
            if (!url.isNullOrBlank()) {
                Glide.with(this@AccountActivity).load(url).centerCrop().into(binding.imgFanart)
            }
        }
    }

    private fun populateAccountInfo() {
        binding.tvUsername.text    = Prefs.username.uppercase().ifBlank { "—" }
        binding.tvServerUrl.text   = Prefs.serverUrl.ifBlank { "—" }

        val statusText = Prefs.accountStatus.ifBlank { "Active" }
        binding.tvStatus.text = statusText
        binding.tvStatus.setTextColor(
            if (statusText.equals("Active", ignoreCase = true)) 0xFF00DC6E.toInt()
            else 0xFFFF5555.toInt()
        )

        val expRaw = Prefs.expDate
        binding.tvExpiry.text = formatExpiry(expRaw)

        binding.tvMaxConnections.text    = Prefs.getString("max_connections", "—")
        binding.tvActiveConnections.text = Prefs.getString("active_cons", "—")
        binding.tvCreatedAt.text         = Prefs.getString("created_at", "—")
        binding.tvTrial.text             = if (Prefs.getString("is_trial", "0") == "1") "Trial Account" else "Full Account"

        binding.tvVersion.text = "SocaTV Nova v1.0.0"
    }

    private fun populateSubscriptionCard() {
        val cfg         = RemoteConfigManager.getCached()
        val isLicensed  = LicenseManager.hasValidLicense()
        val trialStatus = TrialManager.getStatus(cfg.trialDays)
        val daysLeft    = TrialManager.getDaysRemaining(cfg.trialDays)

        val icon   = binding.tvSubStatusIcon
        val status = binding.tvSubStatus
        val detail = binding.tvSubDetail

        when {
            isLicensed -> {
                icon.text = "★"; icon.setTextColor(0xFFFFD700.toInt())
                status.text = "Licensed"; status.setTextColor(0xFF00DC6E.toInt())
                val exp = LicenseManager.getExpiryDate()
                detail.text = if (exp != null)
                    "Valid until ${java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(exp)}"
                else "Full access active"
            }
            trialStatus == TrialManager.Status.ACTIVE -> {
                icon.text = "◉"; icon.setTextColor(0xFF00DCFF.toInt())
                status.text = "Free Trial"; status.setTextColor(0xFF00DCFF.toInt())
                detail.text = "$daysLeft day${if (daysLeft != 1) "s" else ""} remaining"
            }
            else -> {
                icon.text = "✕"; icon.setTextColor(0xFFFF5555.toInt())
                status.text = "Trial Expired"; status.setTextColor(0xFFFF5555.toInt())
                detail.text = "Upgrade to continue"
            }
        }
    }

    private fun formatExpiry(raw: String): String {
        if (raw.isBlank()) return "—"
        return try {
            val epoch = raw.toLong()
            val date  = Date(epoch * 1000)
            SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(date)
        } catch (e: Exception) {
            raw
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnSwitchPanel.setOnClickListener {
            startActivity(Intent(this, PanelPickerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
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

        binding.btnRefresh.setOnClickListener { refreshFromServer() }

        binding.btnManageSubscription.setOnClickListener {
            startActivity(Intent(this, SubscriptionActivity::class.java))
        }

        listOf(binding.btnBack, binding.btnSwitchPanel, binding.btnLogout, binding.btnRefresh, binding.btnManageSubscription).forEach { btn ->
            btn.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.06f else 1f)
                    .scaleY(if (hasFocus) 1.06f else 1f).setDuration(150).start()
                v.setFocusBorder(hasFocus)
            }
        }
    }

    private fun refreshFromServer() {
        binding.progressRefresh.visibility = View.VISIBLE
        lifecycleScope.launch {
            repo.authenticate(Prefs.serverUrl, Prefs.username, Prefs.password).fold(
                onSuccess = { auth ->
                    auth.userInfo?.let { ui ->
                        Prefs.accountStatus = ui.status ?: ""
                        Prefs.expDate       = ui.expDate ?: ""
                        Prefs.putString("max_connections", ui.maxConnections ?: "—")
                        Prefs.putString("active_cons", ui.activeCons ?: "—")
                        Prefs.putString("created_at", ui.createdAt ?: "—")
                        Prefs.putString("is_trial", ui.isTrial ?: "0")
                    }
                    populateAccountInfo()
                },
                onFailure = { /* keep cached values */ }
            )
            binding.progressRefresh.visibility = View.GONE
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
