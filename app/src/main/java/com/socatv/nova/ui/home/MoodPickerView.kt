package com.socatv.nova.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.socatv.nova.data.model.Mood
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class MoodPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onMoodSelected: ((Mood) -> Unit)? = null

    private val moods = Mood.values()
    private val sectorCount = moods.size
    private val sweepAngle = 360f / sectorCount

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#00DCFF")
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val sectorColors = listOf(
        Color.parseColor("#CC2244"),
        Color.parseColor("#CC8800"),
        Color.parseColor("#CC44AA"),
        Color.parseColor("#882299"),
        Color.parseColor("#004499"),
        Color.parseColor("#2255CC"),
        Color.parseColor("#006622"),
        Color.parseColor("#336699")
    )

    private var selectedIndex = -1
    private var hoveredIndex = -1

    private val oval = RectF()
    private val innerOval = RectF()
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) * 0.92f
        val innerRadius = radius * 0.30f

        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        innerOval.set(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius)

        textPaint.textSize = radius * 0.09f
        emojiPaint.textSize = radius * 0.13f
        strokePaint.strokeWidth = radius * 0.008f

        // Draw each sector
        for (i in 0 until sectorCount) {
            val startAngle = i * sweepAngle - 90f
            val isSelected = i == selectedIndex
            val isHovered = i == hoveredIndex

            paint.color = when {
                isSelected -> lighten(sectorColors[i % sectorColors.size], 0.3f)
                isHovered -> lighten(sectorColors[i % sectorColors.size], 0.15f)
                else -> sectorColors[i % sectorColors.size]
            }

            // Fill sector
            path.reset()
            path.moveTo(cx, cy)
            path.arcTo(oval, startAngle, sweepAngle)
            path.close()
            canvas.drawPath(path, paint)

            // Stroke
            canvas.drawArc(oval, startAngle, sweepAngle, true, strokePaint)

            // Text label
            val midAngle = Math.toRadians((startAngle + sweepAngle / 2).toDouble())
            val textR = radius * 0.65f
            val tx = cx + (textR * cos(midAngle)).toFloat()
            val ty = cy + (textR * sin(midAngle)).toFloat()

            val emoji = moods[i].emoji
            val label = moods[i].label

            canvas.drawText(emoji, tx, ty - textPaint.textSize * 0.3f, emojiPaint)
            canvas.drawText(label, tx, ty + textPaint.textSize * 1.2f, textPaint)
        }

        // Draw inner circle overlay
        paint.color = Color.parseColor("#CC141422")
        canvas.drawCircle(cx, cy, innerRadius, paint)
        strokePaint.color = Color.parseColor("#00DCFF")
        canvas.drawCircle(cx, cy, innerRadius, strokePaint)

        // Center text
        textPaint.textSize = innerRadius * 0.3f
        textPaint.color = Color.parseColor("#00DCFF")
        canvas.drawText("Mood", cx, cy + textPaint.textSize * 0.4f, textPaint)
        textPaint.color = Color.WHITE
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val dx = event.x - cx
        val dy = event.y - cy
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90
        val normalizedAngle = ((angle % 360) + 360) % 360
        val index = (normalizedAngle / sweepAngle).toInt().coerceIn(0, sectorCount - 1)

        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                hoveredIndex = index
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                selectedIndex = index
                invalidate()
                onMoodSelected?.invoke(moods[index])
            }
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                hoveredIndex = ((hoveredIndex + 1) % sectorCount)
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                hoveredIndex = ((hoveredIndex - 1 + sectorCount) % sectorCount)
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val idx = if (hoveredIndex >= 0) hoveredIndex else 0
                selectedIndex = idx
                invalidate()
                onMoodSelected?.invoke(moods[idx])
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun lighten(color: Int, factor: Float): Int {
        val r = ((Color.red(color) + (255 - Color.red(color)) * factor).toInt()).coerceIn(0, 255)
        val g = ((Color.green(color) + (255 - Color.green(color)) * factor).toInt()).coerceIn(0, 255)
        val b = ((Color.blue(color) + (255 - Color.blue(color)) * factor).toInt()).coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}
