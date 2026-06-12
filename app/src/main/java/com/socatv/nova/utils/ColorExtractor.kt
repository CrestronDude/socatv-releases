package com.socatv.nova.utils

import android.graphics.Bitmap
import android.graphics.Color
import androidx.palette.graphics.Palette

data class NovaColorScheme(
    val primary: Int,
    val primaryDark: Int,
    val accent: Int,
    val surface: Int,
    val onSurface: Int,
    val glow: Int
)

object ColorExtractor {

    private val DEFAULT_SCHEME = NovaColorScheme(
        primary = 0xFF00DCFF.toInt(),
        primaryDark = 0xFF0099CC.toInt(),
        accent = 0xFF00DCFF.toInt(),
        surface = 0xFF141422.toInt(),
        onSurface = 0xFFFFFFFF.toInt(),
        glow = 0x8800DCFF.toInt()
    )

    fun extract(bitmap: Bitmap, callback: (NovaColorScheme) -> Unit) {
        Palette.from(bitmap)
            .maximumColorCount(16)
            .generate { palette ->
                if (palette == null) {
                    callback(DEFAULT_SCHEME)
                    return@generate
                }
                callback(buildScheme(palette))
            }
    }

    suspend fun extractSync(bitmap: Bitmap): NovaColorScheme {
        return try {
            val palette = Palette.from(bitmap).generate()
            buildScheme(palette)
        } catch (e: Exception) {
            DEFAULT_SCHEME
        }
    }

    private fun buildScheme(palette: Palette): NovaColorScheme {
        // Priority: vibrant > light vibrant > dominant > default cyan
        val vibrant = palette.getVibrantColor(0)
        val lightVibrant = palette.getLightVibrantColor(0)
        val dominant = palette.getDominantColor(0xFF00DCFF.toInt())
        val muted = palette.getMutedColor(0xFF141422.toInt())
        val darkMuted = palette.getDarkMutedColor(0xFF080810.toInt())

        val primary = when {
            vibrant != 0 -> vibrant
            lightVibrant != 0 -> lightVibrant
            dominant != 0 -> ensureVibrant(dominant)
            else -> 0xFF00DCFF.toInt()
        }

        val surface = if (darkMuted != 0) darken(darkMuted, 0.4f) else 0xFF141422.toInt()
        val accent = if (muted != 0) lighten(muted, 0.3f) else lighten(primary, 0.2f)

        return NovaColorScheme(
            primary = primary,
            primaryDark = darken(primary, 0.3f),
            accent = accent,
            surface = surface,
            onSurface = Color.WHITE,
            glow = (primary and 0x00FFFFFF) or 0x88000000.toInt()
        )
    }

    private fun ensureVibrant(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[1] < 0.4f) hsv[1] = 0.6f
        if (hsv[2] < 0.4f) hsv[2] = 0.7f
        return Color.HSVToColor(hsv)
    }

    private fun darken(color: Int, factor: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] *= (1f - factor)
        return Color.HSVToColor(hsv)
    }

    private fun lighten(color: Int, factor: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = minOf(1f, hsv[2] + factor)
        return Color.HSVToColor(hsv)
    }

    fun withAlpha(color: Int, alpha: Int): Int {
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    fun getDefault(): NovaColorScheme = DEFAULT_SCHEME
}
