package com.radiosport.ninegradio.pipeline

import org.junit.Assert.*
import org.junit.Test

class AudioContinuityTest {
    private fun block(first:Long,epoch:Long=1,rate:Int=48000,channels:Int=1)=
        AudioBlock(FloatArray(10*channels),rate,channels,epoch,first)
    @Test fun gapsAndDuplicatesDoNotSilentlyBecomeContinuousAudio() {
        val tracker=AudioContinuity()
        assertEquals(AudioContinuity.Change.FIRST,tracker.accept(block(0)).change)
        assertEquals(AudioContinuity.Change.CONTINUOUS,tracker.accept(block(10)).change)
        assertEquals(AudioContinuity.Change.STALE,tracker.accept(block(10)).change)
        val gap=tracker.accept(block(30))
        assertEquals(AudioContinuity.Change.GAP,gap.change);assertEquals(10L,gap.missingFrames)
        assertEquals(AudioContinuity.Change.CONTINUOUS,tracker.accept(block(40)).change)
    }
    @Test fun epochsRejectOldAudioAndAllowNewFormats() {
        val tracker=AudioContinuity();tracker.accept(block(0))
        assertEquals(AudioContinuity.Change.RECONFIGURED,tracker.accept(block(0,2,44100,2)).change)
        assertEquals(AudioContinuity.Change.STALE,tracker.accept(block(10)).change)
        assertEquals(AudioContinuity.Change.CONTINUOUS,tracker.accept(block(10,2,44100,2)).change)
    }
    @Test fun formatChangesRequireAnEpochAndDoNotCorruptPositionOnRejection() {
        val tracker=AudioContinuity();tracker.accept(block(0))
        assertThrows(IllegalArgumentException::class.java) { tracker.accept(block(10,rate=44100)) }
        assertEquals(AudioContinuity.Change.CONTINUOUS,tracker.accept(block(10)).change)
    }
}
