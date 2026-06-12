package com.socatv.nova.ui.blacklist

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.socatv.nova.R
import com.socatv.nova.utils.DeviceReporter

class BlacklistActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blacklist)

        val reason = intent.getStringExtra("reason")
            ?.takeIf { it.isNotBlank() }
            ?: "Your device has been flagged for unauthorized use or tampering."

        findViewById<TextView>(R.id.tvReason).text = reason
        findViewById<TextView>(R.id.tvDeviceId).text =
            "ID: ${DeviceReporter.getDeviceId(this)}"

        startPulse()
        startSkullPulse()
    }

    private fun startPulse() {
        val overlay = findViewById<View>(R.id.pulseOverlay)
        ObjectAnimator.ofFloat(overlay, View.ALPHA, 0.85f, 0.4f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun startSkullPulse() {
        val skull = findViewById<TextView>(R.id.tvSkull)
        ObjectAnimator.ofFloat(skull, View.SCALE_X, 1f, 1.15f).apply {
            duration = 800; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; start()
        }
        ObjectAnimator.ofFloat(skull, View.SCALE_Y, 1f, 1.15f).apply {
            duration = 800; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; start()
        }
    }

    // Block all back/home navigation — device is locked to this screen
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = true
    override fun onBackPressed() {}
}
