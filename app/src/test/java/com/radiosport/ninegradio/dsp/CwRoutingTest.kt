package com.radiosport.ninegradio.dsp

import com.radiosport.ninegradio.source.DeviceCapabilities
import org.junit.Assert.*
import org.junit.Test

class CwRoutingTest {
    @Test fun hardwareOffsetStaysInsideEachReceiverBand() {
        val caps=DeviceCapabilities.airspy(listOf(768000))
        assertEquals(12000L,DspEngine.cwTunerOffset(500,caps,768000))
        assertEquals(-12000L,DspEngine.cwTunerOffset(31000000,caps,768000))
        assertEquals(12000L,DspEngine.cwTunerOffset(64000000,caps,768000))
        assertEquals(-12000L,DspEngine.cwTunerOffset(260000000,caps,768000))
        val fixed=caps.copy(id="file",frequencyRanges=listOf(11920000L..11920000L))
        assertEquals(0L,DspEngine.cwTunerOffset(11920000,fixed,768000))
    }
    @Test fun cwClockIsIndependentFromTheNarrowChannelWidth() {
        assertEquals(8000,DspEngine.narrowIfRateFor(1920000,8000,8000))
        assertEquals(8000,DspEngine.narrowIfRateFor(768000,8000,8000))
        assertEquals(48000,DspEngine.narrowIfRateFor(1920000,48000))
        assertEquals(51200,DspEngine.narrowIfRateFor(2560000,48000))
    }
}
