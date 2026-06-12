package com.socatv.nova.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.socatv.nova.data.model.StreamHealth

class StreamHealthView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var samples: List<StreamHealth> = emptyList()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC080810")
        style = Paint.Style.FILL
    }

    private val bitratePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00DCFF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val bufferPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7CFC00")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val fpsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD700")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f
        color = Color.parseColor("#99FFFFFF")
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 0.5f
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4400DCFF")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    fun updateSamples(newSamples: List<StreamHealth>) {
        samples = newSamples
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val padding = 40f
        val graphLeft = padding + 80f
        val graphRight = w - padding
        val graphTop = padding + 20f
        val graphBottom = h - padding - 30f
        val graphW = graphRight - graphLeft
        val graphH = graphBottom - graphTop

        // Background
        canvas.drawRoundRect(0f, 0f, w, h, 12f, 12f, bgPaint)
        canvas.drawRoundRect(0f, 0f, w, h, 12f, 12f, borderPaint)

        // Title
        textPaint.textSize = 20f
        textPaint.color = Color.parseColor("#00DCFF")
        canvas.drawText("Stream Health", graphLeft, graphTop - 4f, textPaint)
        textPaint.color = Color.WHITE

        // Grid lines
        for (i in 0..4) {
            val y = graphTop + graphH * i / 4
            canvas.drawLine(graphLeft, y, graphRight, y, gridPaint)
        }

        if (samples.isEmpty()) {
            textPaint.textSize = 18f
            canvas.drawText("Collecting data...", graphLeft + graphW / 4, graphTop + graphH / 2, textPaint)
            return
        }

        // Latest values
        val latest = samples.last()
        textPaint.textSize = 18f
        canvas.drawText("${latest.bitrateKbps} kbps", graphLeft, graphBottom + 26f, bitratePaint)
        canvas.drawText("buf: ${latest.bufferMs}ms", graphLeft + 120f, graphBottom + 26f, bufferPaint.apply { style = Paint.Style.FILL })
        canvas.drawText("${latest.fps.toInt()} fps", graphLeft + 260f, graphBottom + 26f, fpsPaint.apply { style = Paint.Style.FILL })
        fpsPaint.style = Paint.Style.STROKE
        bufferPaint.style = Paint.Style.STROKE

        // Draw bitrate graph
        drawGraph(canvas, samples.map { it.bitrateKbps.toFloat() },
            graphLeft, graphTop, graphW, graphH, bitratePaint)

        // Draw buffer graph (normalized to 10s max)
        drawGraph(canvas, samples.map { it.bufferMs.toFloat() / 100f },
            graphLeft, graphTop, graphW, graphH, bufferPaint)

        // Draw FPS graph
        drawGraph(canvas, samples.map { it.fps },
            graphLeft, graphTop, graphW, graphH, fpsPaint)

        // Labels
        labelPaint.color = Color.parseColor("#00DCFF")
        canvas.drawText("kbps", padding, graphBottom, labelPaint)
        labelPaint.color = Color.parseColor("#99FFFFFF")
    }

    private fun drawGraph(
        canvas: Canvas,
        values: List<Float>,
        left: Float, top: Float, w: Float, h: Float,
        paint: Paint
    ) {
        if (values.size < 2) return

        val max = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val path = Path()
        val stepX = w / (values.size - 1)

        values.forEachIndexed { i, v ->
            val x = left + i * stepX
            val y = top + h - (v / max) * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}
