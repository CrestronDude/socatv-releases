package com.socatv.nova.utils

import android.annotation.SuppressLint
import android.os.Build
import android.view.View
import androidx.core.content.ContextCompat
import com.socatv.nova.R

@SuppressLint("NewApi")
fun View.setFocusBorder(hasFocus: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        foreground = if (hasFocus) {
            ContextCompat.getDrawable(context, R.drawable.focus_border_hard)
        } else null
    }
}
