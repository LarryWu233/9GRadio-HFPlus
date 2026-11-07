package com.radiosport.ninegradio.dsp

import org.junit.Assert.*
import org.junit.Test

class NoiseProcessingPolicyTest {
    @Test fun onlyAnalogAudioModesUseNoiseProcessing() {
        val analog=setOf(DemodMode.AM,DemodMode.FM,DemodMode.WFM,DemodMode.WFM_STEREO,
            DemodMode.NFM,DemodMode.USB,DemodMode.LSB,DemodMode.DSB)
        DemodMode.values().forEach { assertEquals(it.name,it in analog,NoiseProcessingPolicy.allows(it)) }
    }
}
