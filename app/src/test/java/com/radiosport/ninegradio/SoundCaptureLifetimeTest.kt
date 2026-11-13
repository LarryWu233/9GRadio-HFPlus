package com.radiosport.ninegradio

import com.radiosport.ninegradio.audio.*
import com.radiosport.ninegradio.pipeline.AudioBlock
import com.radiosport.ninegradio.recording.AudioRecordingProfile
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SoundCaptureLifetimeTest {
    private class Sink:SoundRecordingSink {
        override var isRecording=false
        override var isWorkerAlive=false
        override val error:String?=null
        var refuseFinish=false
        var entered:CountDownLatch?=null
        var release:CountDownLatch?=null
        override fun start() { isRecording=true;isWorkerAlive=true }
        override fun writeSamples(samples:FloatArray)=Unit
        override fun abort(reason:String) { isRecording=false }
        override fun capturedDurationMs()=0L
        override fun stop() {
            isRecording=false;entered?.countDown();release?.await(3,TimeUnit.SECONDS)
            if(refuseFinish) throw IOException("finishing")
            isWorkerAlive=false
        }
    }
    @Test fun timedOutEncoderRemainsOwnedAndBlocksReplacement() {
        val sinks=mutableListOf<Sink>()
        val capture=SoundCapture { _,_,_ -> Sink().also { sinks+=it } }
        capture.start("recording.wav",48000,AudioRecordingProfile())
        sinks.single().refuseFinish=true
        capture.stop();capture.start("replacement.wav",48000,AudioRecordingProfile())
        assertEquals("Do not create another encoder while the first one is alive",1,sinks.size)
        assertFalse(capture.isRecording);assertNotNull(capture.error)
        sinks.single().refuseFinish=false
        capture.stop();capture.start("replacement.wav",48000,AudioRecordingProfile())
        assertEquals(2,sinks.size);assertTrue(capture.isRecording);capture.stop()
    }
    @Test fun encoderJoinDoesNotHoldTheProcessingFacingLock() {
        val sink=Sink().apply { entered=CountDownLatch(1);release=CountDownLatch(1) }
        val capture=SoundCapture { _,_,_ -> sink }
        capture.start("recording.wav",48000,AudioRecordingProfile())
        val stopping=Thread { capture.stop() }.apply { start() }
        assertTrue(sink.entered!!.await(2,TimeUnit.SECONDS))
        try {
            val offered=CountDownLatch(1)
            Thread { capture.offer(AudioBlock(FloatArray(1920),48000,2,1,0),true);offered.countDown() }.start()
            assertTrue("IQ/listening work must not wait for encoder shutdown",offered.await(1,TimeUnit.SECONDS))
        } finally { sink.release!!.countDown();stopping.join(2000) }
        assertFalse(stopping.isAlive);assertFalse(sink.isWorkerAlive)
    }
}
