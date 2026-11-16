package com.radiosport.ninegradio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.*
import com.radiosport.ninegradio.pipeline.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Affected branch/lifetime checks only; uses the existing synthetic IQ source. */
@RunWith(AndroidJUnit4::class)
class MonitorRoutingTest {
    @Test fun reopenedPlatformOutputStartsANewPlaybackClock() {
        val output=com.radiosport.ninegradio.audio.AudioEngine(48000)
        try {
            output.start();output.setVolume(0f)
            output.write(AudioBlock(FloatArray(28800),48000,2,0,0))
            assertTrue(output.playbackClock().writtenFrames>0)
            output.stop();output.start();output.setVolume(0f)
            assertEquals(0L,output.playbackClock().writtenFrames)
        } finally { output.stop() }
    }
    private class Output(override val sampleRate:Int):AudioOutput {
        val starts=AtomicLong();val writes=AtomicLong()
        @Volatile var open=false
        override val underruns=0
        override fun start() { open=true;starts.incrementAndGet() }
        override fun write(block:AudioBlock) { check(open);writes.incrementAndGet() }
        override fun setVolume(volume:Float)=Unit
        override fun interruptWrites()=Unit
        override fun clearPendingAudio()=Unit
        override fun stop() { open=false }
        override fun playbackClock()=PlaybackClock()
    }
    private suspend fun until(condition:()->Boolean) { withTimeout(5000) { while(!condition()) delay(10) } }
    @Test fun pureDataNeverStartsPlaybackAndRetainsProtocolPcm():Unit=runBlocking {
        val source=ArchitectureLifecycleTest.Source();source.open()
        val output=Output(48000);val decoderFrames=AtomicLong()
        val engine=DspEngine(source,AudioOutputFactory { output })
        try {
            engine.setDemodMode(DemodMode.ADSB);engine.start()
            until { engine.pipelineDiagnostics().inputSamples>1000 }
            assertFalse(engine.isPlaybackActive());assertEquals(0L,output.starts.get())
            engine.setAudioDecoderInput(object:AudioDecoderInput {
                override fun configure(epoch:Long)=Unit
                override fun accept(block:AudioBlock) { assertEquals(48000,block.sampleRate);decoderFrames.addAndGet(block.frameCount.toLong()) }
            })
            for(mode in listOf(DemodMode.APRS,DemodMode.ACARS,DemodMode.FLEX)) {
                val before=decoderFrames.get()
                engine.setDemodMode(mode);engine.setListeningEnabled(true)
                until { decoderFrames.get()>before+1000 }
                assertFalse("$mode must remain silent",engine.isPlaybackActive())
                assertFalse(engine.canRecordAudio())
            }
            engine.setDemodMode(DemodMode.RAW);delay(100)
            assertEquals(0L,output.starts.get());assertEquals(0L,output.writes.get())
            engine.setAudioDecoderInput(null);engine.setDemodMode(DemodMode.USB)
            val content=com.radiosport.ninegradio.content.AudioContentReceiver(InstrumentationRegistry.getInstrumentation().targetContext)
            try {
                content.attach(engine)
                val config=com.radiosport.ninegradio.content.ContentConfig(com.radiosport.ninegradio.content.AudioContentKind.SSTV)
                content.start(config)
                until { content.state.value.sampleCount>4800 }
                assertFalse(engine.isPlaybackActive());assertFalse(engine.canRecordAudio())
                val starts=output.starts.get()
                content.start(config)
                until { content.state.value.sampleCount>4800 }
                assertEquals("Replacing a data decoder must not briefly reopen playback",starts,output.starts.get())
            } finally { content.stop();content.attach(null) }
        } finally { engine.stop();source.close() }
    }
    @Test fun monitorOffKeepsDecoderAndRecordingButStopsOutputUntilExplicitResume():Unit=runBlocking {
        val source=ArchitectureLifecycleTest.Source();source.open()
        val output=Output(48000);val decoderFrames=AtomicLong()
        val file=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"monitor-routing.wav")
        val engine=DspEngine(source,AudioOutputFactory { output })
        try {
            engine.setDemodMode(DemodMode.AM);engine.setSquelchLevel(-120f);engine.setListeningEnabled(false)
            engine.setAudioDecoderInput(object:AudioDecoderInput {
                override fun configure(epoch:Long)=Unit
                override fun accept(block:AudioBlock) { decoderFrames.addAndGet(block.frameCount.toLong()) }
            })
            engine.startAudioRecording(file.path);engine.start()
            until { engine.audioRecordedDurationMs()>200 && decoderFrames.get()>4800 }
            assertEquals(0L,output.starts.get());assertFalse(output.open)
            engine.setListeningEnabled(true);until { output.writes.get()>2 }
            engine.setListeningEnabled(false)
            val writes=output.writes.get();val pcm=decoderFrames.get();val recorded=engine.audioRecordedDurationMs()
            delay(250)
            assertEquals(writes,output.writes.get());assertFalse(output.open)
            assertTrue(decoderFrames.get()>pcm);assertTrue(engine.audioRecordedDurationMs()>recorded)
            assertNull(engine.audioRecordingError())
            engine.setListeningEnabled(true);until { output.writes.get()>writes+2 }
            engine.stopAudioRecording();assertTrue(file.length()>44)
        } finally { engine.stop();source.close();file.delete() }
    }
}
