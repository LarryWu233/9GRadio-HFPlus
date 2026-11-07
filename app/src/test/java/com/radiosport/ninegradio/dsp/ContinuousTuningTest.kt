package com.radiosport.ninegradio.dsp

import com.radiosport.ninegradio.source.DeviceCapabilities
import org.junit.Assert.*
import org.junit.Test

class ContinuousTuningTest {
    @Test fun blindRangeIsBrowsableButNeverBecomesReceiverCapability() {
        val caps = DeviceCapabilities.airspy()
        for (hz in 31000001L until 64000000L step 1000000) {
            assertTrue(caps.supportsBrowsing(hz))
            assertEquals(hz, caps.browseFrequency(hz))
            assertFalse(caps.supportsFrequency(hz))
            assertTrue(caps.supportsFrequency(caps.nearestFrequency(hz)))
        }
        assertEquals(listOf(30000000L..31000000L,64000000L..65000000L), caps.scanRanges(30000000,65000000))
    }
    @Test fun browsingAcrossBothEdgesIsContinuousAndOverallBoundsRemain() {
        val caps = DeviceCapabilities.airspy()
        for ((a,b) in listOf(31000000L to 32000000L,63000000L to 64000000L,64000000L to 63000000L,32000000L to 31000000L)) {
            assertEquals(b, caps.browseFrequency(b))
            assertTrue(caps.supportsBrowsing(a)); assertTrue(caps.supportsBrowsing(b))
        }
        assertEquals(500L, caps.browseFrequency(-1000))
        assertEquals(260000000L, caps.browseFrequency(270000000))
        assertFalse(caps.supportsBrowsing(270000000))
    }
}
