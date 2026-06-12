package com.socatv.nova.ui.subscription

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.socatv.nova.NovaApp
import com.socatv.nova.R
import com.socatv.nova.data.repository.IptvRepository
import com.socatv.nova.ui.paywall.ActivationActivity
import com.socatv.nova.utils.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private val FMT = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
private fun Date.fmt(): String = FMT.format(this)

class SubscriptionActivity : AppCompatActivity() {

    private val repo by lazy { IptvRepository(NovaApp.instance.database) }
    private val cfg  get() = RemoteConfigManager.getCached()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscription)

        loadFanart()
        render()
        setupButtons()
    }

    private fun loadFanart() {
        lifecycleScope.launch {
            val url = try { repo.getTrendingBackdrop() } catch (_: Exception) { null }
            if (!url.isNullOrBlank())
                Glide.with(this@SubscriptionActivity).load(url).centerCrop()
                    .into(findViewById(R.id.imgFanart))
        }
    }

    private fun render() {
        val isLicensed = LicenseManager.hasValidLicense()
        val trialDays  = cfg.trialDays
        val trialStatus = TrialManager.getStatus(trialDays)

        // ── Header status pill ───────────────────────────────────────────────
        val pillTv = findViewById<TextView>(R.id.tvHeaderStatus)
        when {
            isLicensed                           -> pillTv.apply { text = "LICENSED ✓";  setTextColor(0xFF00DC6E.toInt()) }
            trialStatus == TrialManager.Status.ACTIVE   -> pillTv.apply { text = "FREE TRIAL";  setTextColor(0xFF00DCFF.toInt()) }
            else                                 -> pillTv.apply { text = "EXPIRED";     setTextColor(0xFFFF5555.toInt()) }
        }

        // ── Big status + sub-label ───────────────────────────────────────────
        val tvAppStatus = findViewById<TextView>(R.id.tvAppStatus)
        val tvAppStatusSub = findViewById<TextView>(R.id.tvAppStatusSub)
        val tvStatusIcon = findViewById<TextView>(R.id.tvStatusIcon)
        when {
            isLicensed -> {
                tvStatusIcon.text = "★"; tvStatusIcon.setTextColor(0xFFFFD700.toInt())
                tvAppStatus.text = "Licensed"; tvAppStatus.setTextColor(0xFF00DC6E.toInt())
                val expiry = LicenseManager.getExpiryDate()
                tvAppStatusSub.text = if (expiry != null) "Full access until ${expiry.fmt()}" else "Full unlimited access"
            }
            trialStatus == TrialManager.Status.ACTIVE -> {
                val day   = TrialManager.getTrialDayNumber(trialDays)
                val left  = TrialManager.getDaysRemaining(trialDays)
                tvStatusIcon.text = "◉"; tvStatusIcon.setTextColor(0xFF00DCFF.toInt())
                tvAppStatus.text = "Free Trial"; tvAppStatus.setTextColor(0xFF00DCFF.toInt())
                tvAppStatusSub.text = "Day $day of $trialDays  ·  $left day${if (left != 1) "s" else ""} remaining"
            }
            else -> {
                tvStatusIcon.text = "✕"; tvStatusIcon.setTextColor(0xFFFF5555.toInt())
                tvAppStatus.text = "Trial Expired"; tvAppStatus.setTextColor(0xFFFF5555.toInt())
                tvAppStatusSub.text = "Get full access to continue watching"
            }
        }

        // ── Trial progress bar ───────────────────────────────────────────────
        val layoutTrial = findViewById<View>(R.id.layoutTrialProgress)
        if (!isLicensed && trialStatus == TrialManager.Status.ACTIVE) {
            layoutTrial.visibility = View.VISIBLE
            val day  = TrialManager.getTrialDayNumber(trialDays)
            val left = TrialManager.getDaysRemaining(trialDays)
            val pct  = ((day - 1).toFloat() / trialDays * 100).toInt().coerceIn(0, 100)
            findViewById<TextView>(R.id.tvTrialDayLabel).text = "Trial progress"
            findViewById<TextView>(R.id.tvTrialDaysLeft).text = "$left day${if (left != 1) "s" else ""} left"
            val pb = findViewById<ProgressBar>(R.id.progressTrial)
            pb.progress = pct
            pb.progressTintList = android.content.res.ColorStateList.valueOf(
                if (left <= 2) 0xFFFF5555.toInt() else 0xFF00DCFF.toInt()
            )
        } else {
            layoutTrial.visibility = View.GONE
        }

        // ── License key display ──────────────────────────────────────────────
        val layoutKey = findViewById<View>(R.id.layoutLicenseKey)
        if (isLicensed) {
            val storedKey = LicenseManager.getStoredLicense()
            if (storedKey.isNotBlank()) {
                layoutKey.visibility = View.VISIBLE
                findViewById<TextView>(R.id.tvLicenseKey).text = storedKey
            }
        } else {
            layoutKey.visibility = View.GONE
        }

        // ── App expiry ───────────────────────────────────────────────────────
        val tvExpiry = findViewById<TextView>(R.id.tvAppExpiry)
        when {
            isLicensed -> {
                val exp = LicenseManager.getExpiryDate()?.fmt() ?: "—"
                tvExpiry.text = exp; tvExpiry.setTextColor(0xFF00DC6E.toInt())
            }
            trialStatus == TrialManager.Status.ACTIVE -> {
                val left = TrialManager.getDaysRemaining(trialDays)
                tvExpiry.text = "$left days"; tvExpiry.setTextColor(0xFF00DCFF.toInt())
            }
            else -> {
                tvExpiry.text = "Expired"; tvExpiry.setTextColor(0xFFFF5555.toInt())
            }
        }

        // ── Get Access button ────────────────────────────────────────────────
        val btnAccess = findViewById<Button>(R.id.btnGetAccess)
        val layoutPrice = findViewById<View>(R.id.layoutPriceCard)
        if (!isLicensed) {
            btnAccess.visibility = View.VISIBLE
            btnAccess.text = if (trialStatus == TrialManager.Status.ACTIVE)
                "Upgrade — ${cfg.priceLabel}" else "Get Access — ${cfg.priceLabel}"
            layoutPrice.visibility = View.VISIBLE
            findViewById<TextView>(R.id.tvPriceLabel).text = cfg.priceLabel
        } else {
            btnAccess.visibility = View.GONE
            layoutPrice.visibility = View.GONE
        }

        // ── IPTV service section ─────────────────────────────────────────────
        val iptvCard = findViewById<View>(R.id.layoutIptvCard)
        if (Prefs.isLoggedIn && Prefs.username.isNotBlank()) {
            iptvCard.visibility = View.VISIBLE
            val iptvStatusText = Prefs.accountStatus.ifBlank { "Active" }
            val tvIptvStatus = findViewById<TextView>(R.id.tvIptvStatus)
            tvIptvStatus.text = iptvStatusText
            tvIptvStatus.setTextColor(
                if (iptvStatusText.equals("Active", ignoreCase = true)) 0xFF00DC6E.toInt()
                else 0xFFFF5555.toInt()
            )
            val expRaw = Prefs.expDate
            findViewById<TextView>(R.id.tvIptvExpiry).text = formatIptvExpiry(expRaw)
            findViewById<TextView>(R.id.tvIptvUser).text = Prefs.username.uppercase()
        } else {
            iptvCard.visibility = View.GONE
        }

        // ── Announcement banner ──────────────────────────────────────────────
        val ann = cfg.announcement
        val layoutAnn = findViewById<View>(R.id.layoutAnnouncement)
        if (ann.isNotBlank()) {
            layoutAnn.visibility = View.VISIBLE
            findViewById<TextView>(R.id.tvAnnouncement).text = ann
        } else {
            layoutAnn.visibility = View.GONE
        }

        // ── Admin message ────────────────────────────────────────────────────
        val adminMsg = Prefs.getString("admin_message", "")
        val layoutAdmin = findViewById<View>(R.id.layoutAdminMsg)
        if (adminMsg.isNotBlank()) {
            layoutAdmin.visibility = View.VISIBLE
            findViewById<TextView>(R.id.tvAdminMsg).text = adminMsg
        } else {
            layoutAdmin.visibility = View.GONE
        }
    }

    private fun setupButtons() {
        val btnBack     = findViewById<View>(R.id.btnBack)
        val btnAccess   = findViewById<Button>(R.id.btnGetAccess)
        val btnActivate = findViewById<Button>(R.id.btnActivateKey)
        val btnRestore  = findViewById<Button>(R.id.btnRestore)

        btnBack.setOnClickListener { finish() }

        btnAccess.setOnClickListener {
            val url = cfg.paymentUrl
            if (url.isBlank()) {
                Toast.makeText(this, "Payment URL not configured yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            catch (_: Exception) { Toast.makeText(this, "Open browser: $url", Toast.LENGTH_LONG).show() }
        }

        btnActivate.setOnClickListener {
            startActivity(Intent(this, ActivationActivity::class.java))
        }

        btnRestore.setOnClickListener {
            val stored = LicenseManager.getStoredLicense()
            if (stored.isNotBlank()) {
                val result = LicenseManager.validate(stored)
                when (result.status) {
                    LicenseManager.LicenseStatus.VALID -> {
                        Toast.makeText(this, "License restored!", Toast.LENGTH_SHORT).show()
                        render()
                    }
                    LicenseManager.LicenseStatus.EXPIRED ->
                        Toast.makeText(this, "Your license has expired — renew to continue", Toast.LENGTH_LONG).show()
                    else ->
                        Toast.makeText(this, "No valid license found. Enter your key below.", Toast.LENGTH_SHORT).show()
                }
            } else {
                startActivity(Intent(this, ActivationActivity::class.java))
            }
        }

        val btnDownload = findViewById<Button>(R.id.btnOpenDownload)
        val tvDownloadUrl = findViewById<TextView>(R.id.tvDownloadUrl)
        val downloadUrl = cfg.downloadUrl.ifBlank { "https://bit.ly/SocaTvNova_app" }
        tvDownloadUrl.text = downloadUrl.removePrefix("https://")
        btnDownload.setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))) }
            catch (_: Exception) { Toast.makeText(this, downloadUrl, Toast.LENGTH_LONG).show() }
        }

        listOf(btnBack, btnAccess, btnActivate, btnRestore, btnDownload).forEach { v ->
            v.setOnFocusChangeListener { view, hasFocus ->
                view.animate().scaleX(if (hasFocus) 1.05f else 1f)
                    .scaleY(if (hasFocus) 1.05f else 1f).setDuration(120).start()
                view.setFocusBorder(hasFocus)
            }
        }

        // Default focus
        if (!LicenseManager.hasValidLicense()) btnAccess.requestFocus()
        else btnActivate.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun formatIptvExpiry(raw: String): String {
        if (raw.isBlank()) return "—"
        return try { Date(raw.toLong() * 1000).fmt() } catch (_: Exception) { raw }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
