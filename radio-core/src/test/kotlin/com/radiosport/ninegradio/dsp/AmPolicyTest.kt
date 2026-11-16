package com.radiosport.ninegradio.dsp
import org.junit.Assert.*
import org.junit.Test
class AmPolicyTest {
    @Test fun centeredAmAndAcarsCarriersAreProtectedAtBothDeviceRates() {
        for(rate in listOf(768000,2400000)) for(mode in listOf(DemodMode.AM,DemodMode.ACARS)) {
            assertFalse(AmCarrierProtection.allowsDc(mode,0,rate))
            assertTrue(AmCarrierProtection.allowsDc(mode,5000,rate))
        }
        assertTrue(AmCarrierProtection.allowsDc(DemodMode.NFM,0,2400000))
    }
    @Test fun audioBandwidthUsesActualRfSidebandAndSampleRate() {
        val dsb=AmSettings()
        assertEquals(4500,dsb.cutoffHz(10000))
        assertEquals(4500,dsb.copy(detector=AmDetector.SAM_USB).cutoffHz(5000))
        assertEquals(3600,dsb.cutoffHz(10000,8000))
        assertEquals(2000,dsb.copy(audioAuto=false,audioHz=2000).cutoffHz(10000))
    }
}
