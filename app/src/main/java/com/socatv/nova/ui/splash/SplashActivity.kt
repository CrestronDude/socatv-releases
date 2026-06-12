package com.socatv.nova.ui.splash

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.socatv.nova.BuildConfig
import com.socatv.nova.databinding.ActivitySplashBinding
import com.socatv.nova.ui.home.PanelPickerActivity
import com.socatv.nova.ui.paywall.PaywallActivity
import com.socatv.nova.ui.blacklist.BlacklistActivity
import com.socatv.nova.utils.AppUpdater
import com.socatv.nova.utils.DeviceReporter
import com.socatv.nova.utils.LicenseManager
import com.socatv.nova.utils.Prefs
import com.socatv.nova.utils.RemoteConfigManager
import com.socatv.nova.utils.TrialManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        animateLogo()
        DeviceReporter.checkIn(this, lifecycleScope)

        lifecycleScope.launch {
            val startMs = System.currentTimeMillis()

            // Fetch remote config — uses disk cache if offline, fast after first run
            val config = RemoteConfigManager.fetch()

            // Check for update using the already-fetched Gist config (no extra API call)
            val hasUpdate = config.latestVersionCode > BuildConfig.VERSION_CODE
                    && config.latestApkUrl.isNotBlank()

            if (hasUpdate) {
                // Show progress overlay and auto-download silently
                binding.layoutUpdateProgress.visibility = View.VISIBLE

                val installed = withTimeoutOrNull(5 * 60_000L) {
                    AppUpdater.autoDownloadAndInstall(
                        activity   = this@SplashActivity,
                        apkUrl     = config.latestApkUrl,
                        label      = config.latestVersionName,
                        onProgress = { pct, text ->
                            binding.tvUpdateStatus.text = text
                            binding.progressUpdate.progress = pct
                        }
                    )
                } ?: false

                // Give the installer a moment to surface, then navigate regardless
                // (user may accept or decline the install prompt)
                delay(1_500L)
                if (!isFinishing && !isDestroyed) navigateNext()
            } else {
                // No update — respect minimum splash duration
                val elapsed = System.currentTimeMillis() - startMs
                val remaining = 2_500L - elapsed
                if (remaining > 0) delay(remaining)
                navigateNext()
            }
        }
    }

    private fun navigateNext() {
        if (isFinishing || isDestroyed) return

        // Blocked by admin → skull screen
        if (Prefs.getBoolean("device_blocked")) {
            startActivity(Intent(this, BlacklistActivity::class.java).apply {
                putExtra("reason", Prefs.getString("block_reason", ""))
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            finish()
            return
        }

        val cfg = RemoteConfigManager.getCached()

        // Maintenance mode
        if (!cfg.appEnabled) {
            startActivity(Intent(this, PaywallActivity::class.java).apply {
                putExtra("maintenance_mode", true)
            })
            finish()
            return
        }

        // Valid license bypasses trial check
        if (LicenseManager.hasValidLicense()) { goHome(); return }

        when (TrialManager.getStatus(cfg.trialDays)) {
            TrialManager.Status.ACTIVE   -> goHome()
            TrialManager.Status.LICENSED -> goHome()
            TrialManager.Status.EXPIRED  -> {
                startActivity(Intent(this, PaywallActivity::class.java).apply {
                    putExtra("trial_expired", true)
                })
                @Suppress("DEPRECATION")
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
            }
        }
    }

    private fun goHome() {
        startActivity(Intent(this, PanelPickerActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun animateLogo() {
        binding.logoText.alpha = 0f
        binding.taglineText.alpha = 0f
        binding.glowCircle.alpha = 0f
        binding.glowCircle.scaleX = 0.3f
        binding.glowCircle.scaleY = 0.3f

        val glowScale = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.glowCircle, View.SCALE_X, 0.3f, 1.2f, 1f),
                ObjectAnimator.ofFloat(binding.glowCircle, View.SCALE_Y, 0.3f, 1.2f, 1f),
                ObjectAnimator.ofFloat(binding.glowCircle, View.ALPHA, 0f, 0.6f, 0.4f)
            )
            duration = 900L
            interpolator = AccelerateDecelerateInterpolator()
        }

        ObjectAnimator.ofFloat(binding.logoText, View.ALPHA, 0f, 1f).apply {
            duration = 600L; startDelay = 400L
        }.start()

        ObjectAnimator.ofFloat(binding.taglineText, View.ALPHA, 0f, 1f).apply {
            duration = 500L; startDelay = 800L
        }.start()

        glowScale.start()

        binding.glowCircle.postDelayed({
            if (!isDestroyed) {
                ObjectAnimator.ofFloat(binding.glowCircle, View.SCALE_X, 1f, 1.05f, 1f).apply {
                    duration = 1000L; repeatCount = ObjectAnimator.INFINITE
                }.start()
                ObjectAnimator.ofFloat(binding.glowCircle, View.SCALE_Y, 1f, 1.05f, 1f).apply {
                    duration = 1000L; repeatCount = ObjectAnimator.INFINITE
                }.start()
            }
        }, 1200L)
    }
}
