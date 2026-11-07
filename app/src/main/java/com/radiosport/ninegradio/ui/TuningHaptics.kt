package com.radiosport.ninegradio.ui

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View

class TuningHaptics(private val view: View) {
    private var last = 0L
    fun tick() {
        val now = SystemClock.uptimeMillis()
        if (now - last < 45) return
        last = now
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}
