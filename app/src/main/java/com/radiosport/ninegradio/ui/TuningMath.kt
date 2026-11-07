package com.radiosport.ninegradio.ui

import kotlin.math.*

object TuningMath {
    fun snap(hz: Long, step: Long): Long {
        val grid = step.coerceAtLeast(1)
        return (hz.toDouble() / grid).roundToLong() * grid
    }
    fun rate(position: Float): Double {
        val distance = abs(position).coerceAtMost(1f)
        if (distance < 0.08f) return 0.0
        return sign(position) * ((distance - 0.08) / 0.92).pow(2.2) * 50.0
    }
}
