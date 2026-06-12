package com.socatv.nova.utils

import android.graphics.Color
import java.util.Calendar

enum class TimeOfDay {
    DAWN,    // 5-7
    MORNING, // 7-12  -> gold
    AFTERNOON, // 12-17 -> default cyan
    EVENING, // 17-20 -> amber/orange
    NIGHT,   // 20-24 -> crimson/purple
    MIDNIGHT // 0-5   -> deep purple
}

data class TimeColorScheme(
    val primary: Int,
    val background: Int,
    val accent: Int,
    val periodName: String
)

object TimeTheme {

    fun getCurrentTimeOfDay(): TimeOfDay {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..6 -> TimeOfDay.DAWN
            in 7..11 -> TimeOfDay.MORNING
            in 12..16 -> TimeOfDay.AFTERNOON
            in 17..19 -> TimeOfDay.EVENING
            in 20..23 -> TimeOfDay.NIGHT
            else -> TimeOfDay.MIDNIGHT
        }
    }

    fun getScheme(timeOfDay: TimeOfDay = getCurrentTimeOfDay()): TimeColorScheme {
        return when (timeOfDay) {
            TimeOfDay.DAWN -> TimeColorScheme(
                primary = Color.parseColor("#FF9F43"),      // warm orange
                background = Color.parseColor("#0D0A08"),
                accent = Color.parseColor("#FFCF77"),
                periodName = "Dawn"
            )
            TimeOfDay.MORNING -> TimeColorScheme(
                primary = Color.parseColor("#FFD700"),      // gold
                background = Color.parseColor("#0D0A00"),
                accent = Color.parseColor("#FFF0A0"),
                periodName = "Morning"
            )
            TimeOfDay.AFTERNOON -> TimeColorScheme(
                primary = Color.parseColor("#00DCFF"),      // cyan (default)
                background = Color.parseColor("#080810"),
                accent = Color.parseColor("#66EEFF"),
                periodName = "Afternoon"
            )
            TimeOfDay.EVENING -> TimeColorScheme(
                primary = Color.parseColor("#FF8C00"),      // dark orange/amber
                background = Color.parseColor("#0D0800"),
                accent = Color.parseColor("#FFBB55"),
                periodName = "Evening"
            )
            TimeOfDay.NIGHT -> TimeColorScheme(
                primary = Color.parseColor("#DC143C"),      // crimson
                background = Color.parseColor("#0D0008"),
                accent = Color.parseColor("#FF6699"),
                periodName = "Night"
            )
            TimeOfDay.MIDNIGHT -> TimeColorScheme(
                primary = Color.parseColor("#8A2BE2"),      // blue violet / deep purple
                background = Color.parseColor("#07000D"),
                accent = Color.parseColor("#CC88FF"),
                periodName = "Midnight"
            )
        }
    }

    fun getGreeting(): String {
        return when (getCurrentTimeOfDay()) {
            TimeOfDay.DAWN -> "Good Dawn"
            TimeOfDay.MORNING -> "Good Morning"
            TimeOfDay.AFTERNOON -> "Good Afternoon"
            TimeOfDay.EVENING -> "Good Evening"
            TimeOfDay.NIGHT -> "Good Night"
            TimeOfDay.MIDNIGHT -> "Still Up?"
        }
    }
}
