package com.radiosport.ninegradio.dsp

import org.junit.Assert.*
import org.junit.Test

class ChannelSquelchTest {
    private val configuration=ChannelSquelchMeter.Configuration(125_000_000,125_000_000,DemodMode.AM,10_000,240_000,1,2048)
    private fun read(data: FloatArray, config: ChannelSquelchMeter.Configuration=configuration): ChannelSquelchMeter.Reading {
        val meter=ChannelSquelchMeter()
        meter.update(data,1,config,1_000_000_000)
        return meter.update(data,2,config,1_020_000_000)
    }
    @Test fun offChannelPeakCannotKeepListeningOrRecordingOpen() {
        val bins=FloatArray(2048) { -100f }
        bins[1026]=-71f; bins[1450]=-22f
        val reading=read(bins)
        assertEquals(-71f,reading.peakDb,0.01f)
        assertEquals(-71f,reading.levelDb,0.01f)
        for(gate in listOf(ListeningSquelch(),ListeningSquelch(false))) {
            repeat(30) { gate.update(reading.levelDb,-70.5f,0.01) }
            assertFalse("Threshold just 0.5 dB above BW peak must close",gate.open)
            repeat(6_500) { assertFalse("Sustained quiet must stay muted",gate.update(reading.levelDb,-70.5f,0.01)) }
            assertTrue(gate.update(-68f,-70.5f,0.01))
        }
    }
    @Test fun usbAndLsbRespectSidebandAndHardwareOffset() {
        val config=configuration.copy(frequencyHz=7_100_000,hardwareHz=7_101_500,bandwidthHz=3000,sampleRate=48_000,fftSize=960)
        val bins=FloatArray(960) { -110f }
        bins[460]=-64f // -1000 Hz relative to hardware: +500 Hz relative to dial
        bins[420]=-35f // -3000 Hz relative to hardware: -1500 Hz relative to dial
        assertEquals(-64f,read(bins,config.copy(mode=DemodMode.USB)).peakDb,0.01f)
        assertEquals(-35f,read(bins,config.copy(mode=DemodMode.LSB)).peakDb,0.01f)
    }
    @Test fun cwOffsetDoesNotMeasureHardwareDcSpur() {
        val bins=FloatArray(2048) { -110f }; bins[512]=-60f; bins[1024]=-15f
        val config=configuration.copy(mode=DemodMode.CW,hardwareHz=125_012_000,bandwidthHz=500,sampleRate=48_000)
        assertEquals(-60f,read(bins,config).peakDb,0.01f)
    }
    @Test fun shortAverageIsInLinearPowerAndCachedFramesDoNotAccumulate() {
        val meter=ChannelSquelchMeter(); val bins=FloatArray(2048) { -100f }
        meter.update(bins,1,configuration,1_000_000_000)
        meter.update(bins,2,configuration,1_040_000_000)
        bins[1024]=-40f
        val rising=meter.update(bins,3,configuration,1_080_000_000)
        assertTrue(rising.levelDb in -43f..-41f) // dB averaging would yield about -62 dB.
        assertEquals(rising,meter.update(bins,3,configuration,2_000_000_000))
        assertFalse(meter.update(bins,3,configuration.copy(frequencyHz=125_010_000),2_000_000_001).valid)
    }
    @Test fun partialBwIsFlaggedAndFreshFrameRequiredAfterSettingChanges() {
        val config=configuration.copy(decimation=64,bandwidthHz=10_000)
        assertFalse(read(FloatArray(2048) { -90f },config).complete)
        assertTrue(read(FloatArray(2048) { -90f }).complete)
    }
    @Test fun recommendationUsesQuietQuantileAndMarginWithoutClaimingSignalClassification() {
        val levels=List(80) { -83f+(it%5)*0.4f }+List(20) { -40f }
        val result=SquelchRecommendation.from(levels)!!
        assertTrue(result.thresholdDb in -81f..-78f)
        assertTrue(result.variationDb>30f)
        assertNotNull("A constant carrier cannot be distinguished from noise by this measurement",SquelchRecommendation.from(List(20) { -25f }))
    }
    @Test fun invalidOrInsufficientMeasurementDoesNotProduceAdvice() {
        assertNull(SquelchRecommendation.from(List(7) { -80f }))
        assertNull(SquelchRecommendation.from(List(20) { Float.NaN }))
        assertNull(SquelchRecommendation.from(List(20) { -5f }))
    }
    @Test fun briefSpeechDipsRemainOpenAndDisableBypassesGate() {
        val gate=ListeningSquelch()
        repeat(10) { assertTrue(gate.update(-81f,-80f,0.01)) }
        assertTrue(gate.update(-60f,-80f,0.01))
        repeat(10) { assertTrue(gate.update(-81f,-80f,0.01)) }
        repeat(15) { gate.update(-81f,-80f,0.01) }
        assertFalse(gate.open)
        assertTrue(gate.update(-200f,-120f,0.01))
    }
}
