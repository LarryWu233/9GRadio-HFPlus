package com.radiosport.ninegradio.ui

import org.junit.Assert.*
import org.junit.Test

class TuningMathTest {
    @Test fun springRateHasDeadZoneAndSymmetricAcceleration() {
        assertEquals(0.0, TuningMath.rate(0.07f), 0.0)
        assertEquals(50.0, TuningMath.rate(1f), 0.001)
        assertEquals(-TuningMath.rate(0.5f), TuningMath.rate(-0.5f), 0.0001)
        assertTrue(TuningMath.rate(0.9f) > TuningMath.rate(0.5f) * 2)
    }
    @Test fun ordinaryTuningClearsFractionalHertzAndFineStepsPreserveThem() {
        assertEquals(99088000L, TuningMath.snap(99088468, 1000))
        assertEquals(99088468L, TuningMath.snap(99088468, 1))
        assertEquals(100000000L, TuningMath.snap(100022000, 100000))
        assertEquals(118012500L, TuningMath.snap(118013333, 12500))
    }
}
