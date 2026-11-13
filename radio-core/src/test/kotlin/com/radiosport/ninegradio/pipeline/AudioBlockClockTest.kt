package com.radiosport.ninegradio.pipeline

import org.junit.Assert.*
import org.junit.Test

class AudioBlockClockTest {
    @Test fun publicationOwnsSamplesAndCountsIrregularStereoTails() {
        val clock=AudioBlockClock();val scratch=floatArrayOf(.2f,.3f,.4f,.5f,.6f,.7f)
        val first=clock.publish(scratch,48000,2,1)
        scratch.fill(0f)
        assertEquals(.2f,first.samples[0],0f);assertEquals(3,first.frameCount)
        val tail=clock.publish(floatArrayOf(.8f,.9f),48000,2,1)
        assertEquals(3L,tail.firstFrame);assertEquals(1,tail.frameCount)
        assertEquals(4L,clock.publish(floatArrayOf(),48000,2,1).firstFrame)
    }
    @Test fun configurationEpochResetsTheSampleClockAndPermitsFormatChange() {
        val clock=AudioBlockClock();clock.publish(FloatArray(12),48000,2,1)
        try { clock.publish(FloatArray(4),44100,1,1);fail("Unstamped format change") } catch(expected:IllegalArgumentException) { }
        val next=clock.publish(FloatArray(4),44100,1,2)
        assertEquals(0L,next.firstFrame);assertEquals(2L,next.epoch);assertEquals(4,next.frameCount)
    }
}
