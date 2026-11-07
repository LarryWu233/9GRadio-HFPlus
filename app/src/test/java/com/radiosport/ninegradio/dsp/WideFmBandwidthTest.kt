package com.radiosport.ninegradio.dsp

import com.radiosport.ninegradio.source.DeviceCapabilities
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class WideFmBandwidthTest {
    @Test fun hfFullBandwidthHasEnoughComplexIfSamples() {
        for(rate in listOf(768000,912000)) {
            assertEquals(660000,DeviceCapabilities.airspy().maxChannelBandwidth(rate))
            assertTrue(DspEngine.wfmIfRate(rate,660000)*0.9>=660000)
        }
        assertEquals(228000,DspEngine.wfmIfRate(912000,200000))
    }
    @Test fun fullWidthPassesEnergyThatBroadcastWidthRejects() {
        val rate=768000
        val iq=FloatArray(76800*2) { i ->
            val phase=2*PI*280000*(i/2)/rate
            (if(i%2==0) cos(phase) else sin(phase)).toFloat()
        }
        fun power(width: Int): Double {
            val output=ComplexDecimator(rate,DspEngine.wfmIfRate(rate,width),cutoffHz=width/2.0).process(iq)
            return output.drop(1000).map { it*it.toDouble() }.average()
        }
        assertTrue(power(660000)>0.45)
        assertTrue(power(200000)<0.001)
    }
}
