package com.radiosport.ninegradio.dsp

import org.junit.Assert.*
import org.junit.Test

class ChannelRatePolicyTest {
    @Test fun broadcastAndFullHfWidthsPreserveExistingRateChoices() {
        val ordinary=listOf(240000 to 240000,250000 to 250000,256000 to 200000,
            272000 to 200000,286000 to 200000,300000 to 200000,768000 to 200000,
            912000 to 228000,960000 to 240000,1024000 to 204800,1440000 to 240000,
            1920000 to 240000,2048000 to 204800,2160000 to 216000,2400000 to 200000)
        for((source,expected) in ordinary) assertEquals("Auto at $source",expected,ChannelRatePolicy.wfmIfRate(source))
        for(source in listOf(768000,912000)) {
            val full=ChannelRatePolicy.wfmIfRate(source,660000)
            assertEquals(source,full)
            assertTrue(full*.9>=660000)
        }
        assertEquals(228000,ChannelRatePolicy.wfmIfRate(912000,200000))
    }
    @Test fun fractionalDecoderRatesAndLowOutputRatesKeepTheirOwnPolicies() {
        assertEquals(48000,ChannelRatePolicy.narrow(2048000,16000,DemodMode.APRS,-1,500))
        assertEquals(25000,ChannelRatePolicy.narrow(2048000,16000,DemodMode.APRS,25000,500))
        assertEquals(48000,ChannelRatePolicy.narrow(2048000,16000,DemodMode.APRS,100000,500))
        assertEquals(32000,ChannelRatePolicy.narrow(1024000,32000,DemodMode.AM,-1,500))
        assertEquals(32000,ChannelRatePolicy.narrow(2560000,32000,DemodMode.AM,-1,500))
        assertEquals(51200,ChannelRatePolicy.narrow(2560000,48000,DemodMode.AM,-1,500))
        assertEquals(10000,ChannelRatePolicy.narrow(768000,48000,DemodMode.AM,10000,500))
        assertEquals(100,ChannelRatePolicy.narrow(768000,48000,DemodMode.AM,1,500))
    }
    @Test fun cwClockRemainsIndependentOfMonitorRateAndUsesEnoughBandwidth() {
        for(source in listOf(768000,912000,1920000,2048000)) for(mode in listOf(DemodMode.CW,DemodMode.CWR)) {
            assertEquals(8000,ChannelRatePolicy.narrow(source,44100,mode,500,500))
            assertEquals(8000,ChannelRatePolicy.narrow(source,16000,mode,-1,500))
            val wider=ChannelRatePolicy.narrow(source,44100,mode,9600,9600)
            assertTrue(wider*.8>=9600)
            assertEquals(0,source%wider)
        }
    }
}
