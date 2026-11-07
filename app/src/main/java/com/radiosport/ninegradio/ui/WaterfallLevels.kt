package com.radiosport.ninegradio.ui

import kotlin.math.max

/** Robust noise-referenced colour range; no allocation or sorting per spectrum. */
class WaterfallLevels {
    private val histogram = IntArray(241)
    var floor = -120f
        private set
    var ceiling = -80f
        private set
    private var initialized = false

    fun reset() { initialized = false }

    fun observe(spectrum: FloatArray) {
        histogram.fill(0)
        var count = 0
        for (db in spectrum) if (db.isFinite() && db > -200f) {
            histogram[(db.toInt() + 200).coerceIn(0, 240)]++
            count++
        }
        if (count == 0) return
        fun percentile(fraction: Float): Float {
            val target = max(1, (count * fraction).toInt())
            var seen = 0
            for (index in histogram.indices) {
                seen += histogram[index]
                if (seen >= target) return (index - 200).toFloat()
            }
            return 0f
        }
        val low = percentile(0.2f) - 5f
        val high = max(low + 32f, percentile(0.95f) + 6f)
        if (!initialized) {
            floor = low
            ceiling = high
            initialized = true
        } else {
            floor += (low - floor) * 0.08f
            ceiling += (high - ceiling) * 0.08f
        }
    }
}
