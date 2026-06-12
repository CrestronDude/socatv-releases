package com.socatv.nova.ui.home

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.socatv.nova.R
import com.socatv.nova.api.TMDbApi
import com.socatv.nova.data.model.TmdbItem

class AmbientScreensaverView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val bgA: ImageView
    private val bgB: ImageView
    private val tvTitle: TextView
    private val tvClock: TextView

    private var items: List<TmdbItem> = emptyList()
    private var currentIndex = 0
    private var isRunning = false
    private val switchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val clockHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var useA = true

    init {
        LayoutInflater.from(context).inflate(R.layout.view_ambient_screensaver, this, true)
        bgA = findViewById(R.id.ivBgA)
        bgB = findViewById(R.id.ivBgB)
        tvTitle = findViewById(R.id.tvScreensaverTitle)
        tvClock = findViewById(R.id.tvScreensaverClock)
    }

    fun startScreensaver(tmdbItems: List<TmdbItem>) {
        items = tmdbItems.filter { !it.backdropPath.isNullOrBlank() }
        isRunning = true
        showNextImage()
        startClock()
    }

    fun stopScreensaver() {
        isRunning = false
        switchHandler.removeCallbacksAndMessages(null)
        clockHandler.removeCallbacksAndMessages(null)
    }

    private fun showNextImage() {
        if (!isRunning || items.isEmpty()) return

        val item = items[currentIndex % items.size]
        currentIndex++

        val url = TMDbApi.imageUrl(item.backdropPath)
        val incoming = if (useA) bgA else bgB
        val outgoing = if (useA) bgB else bgA

        Glide.with(context)
            .load(url)
            .into(incoming)

        incoming.alpha = 0f
        val fadeIn = ObjectAnimator.ofFloat(incoming, "alpha", 0f, 0.7f).apply {
            duration = 2000L
            interpolator = LinearInterpolator()
        }
        val fadeOut = ObjectAnimator.ofFloat(outgoing, "alpha", outgoing.alpha, 0f).apply {
            duration = 2000L
            interpolator = LinearInterpolator()
        }

        AnimatorSet().apply {
            playTogether(fadeIn, fadeOut)
            start()
        }

        // Slow Ken Burns zoom
        incoming.animate()
            .scaleX(1.08f).scaleY(1.08f)
            .setDuration(10000L)
            .setInterpolator(LinearInterpolator())
            .withEndAction {
                incoming.scaleX = 1f
                incoming.scaleY = 1f
            }
            .start()

        tvTitle.text = item.displayTitle
        tvTitle.animate().alpha(0f).setDuration(500).withEndAction {
            tvTitle.animate().alpha(1f).setDuration(800).start()
        }.start()

        useA = !useA

        switchHandler.postDelayed({ showNextImage() }, 8000L)
    }

    private fun startClock() {
        val clockRunnable = object : Runnable {
            override fun run() {
                if (!isRunning) return
                val cal = java.util.Calendar.getInstance()
                val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
                val m = cal.get(java.util.Calendar.MINUTE)
                tvClock.text = "%02d:%02d".format(h, m)
                clockHandler.postDelayed(this, 30000L)
            }
        }
        clockRunnable.run()
    }
}
