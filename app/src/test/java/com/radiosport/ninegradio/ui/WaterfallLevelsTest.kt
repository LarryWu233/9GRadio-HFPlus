package com.radiosport.ninegradio.ui

import org.junit.Assert.*
import org.junit.Test

class WaterfallLevelsTest {
    @Test fun negativeDbSignalsDoNotLeaveCeilingAtZero() {
        val levels = WaterfallLevels()
        levels.observe(FloatArray(256) { if (it == 128) -40f else -100f })
        assertEquals(-105f, levels.floor, 0.01f)
        assertEquals(-73f, levels.ceiling, 0.01f)
    }

    @Test fun gainChangesFollowGraduallyWithoutColourFlashing() {
        val levels = WaterfallLevels()
        levels.observe(FloatArray(256) { -110f })
        val before = levels.floor
        levels.observe(FloatArray(256) { -90f })
        assertTrue(levels.floor > before)
        assertTrue(levels.floor - before < 2f)
        repeat(100) { levels.observe(FloatArray(256) { -90f }) }
        assertEquals(-95f, levels.floor, 0.1f)
    }

    @Test fun invalidFramesDoNotCorruptRangeAndResetFollowsNewBand() {
        val levels = WaterfallLevels()
        levels.observe(floatArrayOf(Float.NaN, Float.NEGATIVE_INFINITY, -300f))
        assertTrue(levels.ceiling > levels.floor)
        levels.observe(FloatArray(256) { -100f })
        levels.reset()
        levels.observe(FloatArray(256) { -70f })
        assertEquals(-75f, levels.floor, 0.01f)
    }
}
