package com.socatv.nova.ui.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.CountDownTimer
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class BingeCountdownView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onCountdownComplete: (() -> Unit)? = null
    var onCancelCountdown: (() -> Unit)? = null

    private var totalSeconds = 10
    private var remainingSeconds = 10
    private var sweepProgress = 360f
    private var countDownTimer: CountDownTimer? = null
    private var sweepAnimator: ValueAnimator? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC141422")
        style = Paint.Style.FILL
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00DCFF")
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99FFFFFF")
        textAlign = Paint.Align.CENTER
    }

    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) * 0.75f
        val strokeW = 10f

        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Background circle
        canvas.drawCircle(cx, cy, radius + strokeW * 2, bgPaint)

        // Track arc
        canvas.drawArc(oval, -90f, 360f, false, trackPaint)

        // Progress arc
        canvas.drawArc(oval, -90f, sweepProgress, false, progressPaint)

        // Center number
        textPaint.textSize = radius * 0.55f
        canvas.drawText(remainingSeconds.toString(), cx, cy + textPaint.textSize * 0.35f, textPaint)

        // Label
        labelPaint.textSize = radius * 0.18f
        canvas.drawText("Next Episode", cx, cy + radius * 0.45f + labelPaint.textSize, labelPaint)
        labelPaint.color = Color.parseColor("#00DCFF")
        canvas.drawText("Press BACK to cancel", cx, cy + radius + strokeW * 3f, labelPaint)
        labelPaint.color = Color.parseColor("#99FFFFFF")
    }

    fun startCountdown(seconds: Int) {
        totalSeconds = seconds
        remainingSeconds = seconds
        sweepProgress = 360f
        invalidate()

        sweepAnimator?.cancel()
        sweepAnimator = ValueAnimator.ofFloat(360f, 0f).apply {
            duration = (seconds * 1000L)
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                sweepProgress = anim.animatedValue as Float
                invalidate()
            }
            start()
        }

        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                remainingSeconds = (millisUntilFinished / 1000L).toInt() + 1
                invalidate()
            }
            override fun onFinish() {
                remainingSeconds = 0
                invalidate()
                onCountdownComplete?.invoke()
            }
        }.start()
    }

    fun cancelCountdown() {
        countDownTimer?.cancel()
        sweepAnimator?.cancel()
        remainingSeconds = 0
        sweepProgress = 0f
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            cancelCountdown()
            onCancelCountdown?.invoke()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
