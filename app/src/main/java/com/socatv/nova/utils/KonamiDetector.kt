package com.socatv.nova.utils

import android.view.KeyEvent

class KonamiDetector(private val onUnlocked: () -> Unit) {

    // ↑ ↑ ↓ ↓ ← → ← → (B A not needed for D-pad TV remote)
    private val SEQUENCE = intArrayOf(
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT
    )

    private var currentIndex = 0
    private var lastKeyTime = 0L
    private val TIMEOUT_MS = 3000L

    fun onKeyDown(keyCode: Int): Boolean {
        val now = System.currentTimeMillis()

        // Reset if too slow
        if (now - lastKeyTime > TIMEOUT_MS && currentIndex > 0) {
            currentIndex = 0
        }
        lastKeyTime = now

        if (keyCode == SEQUENCE[currentIndex]) {
            currentIndex++
            if (currentIndex == SEQUENCE.size) {
                currentIndex = 0
                onUnlocked()
                return true
            }
        } else {
            // Allow restarting from index 0 if this key matches start
            currentIndex = if (keyCode == SEQUENCE[0]) 1 else 0
        }
        return false
    }

    fun reset() {
        currentIndex = 0
        lastKeyTime = 0L
    }
}
