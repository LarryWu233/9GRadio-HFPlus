package com.radiosport.ninegradio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.*
import com.radiosport.ninegradio.pipeline.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ArchitectureRecordingIsolationTest {
    private class SlowOutput(override val sampleRate:Int):AudioOutput {
        @Volatile private var stopping=false
        override val underruns=0
        override fun start() { stopping=false }
        override fun write(block:AudioBlock) { repeat(50) { if(!stopping) Thread.sleep(2) } }
        override fun setVolume(volume:Float)=Unit
        override fun clearPendingAudio()=Unit
        override fun interruptWrites() { stopping=true }
        override fun playbackClock()=PlaybackClock()
        override fun stop() { stopping=true }
    }
    @Test fun playbackCongestionDoesNotDiscardSoundRecording():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"architecture-recording-isolation.wav")
        val source=ArchitectureLifecycleTest.Source();source.open()
        val engine=DspEngine(source,AudioOutputFactory { SlowOutput(it) })
        try {
            engine.setDemodMode(DemodMode.AM);engine.setSquelchLevel(-120f)
            engine.startAudioRecording(file.path)
            assertTrue(engine.isAudioRecording())
            engine.start();delay(1200);engine.stop()
            assertTrue("Playback must actually be congested",engine.getPlaybackQueueDrops()>0)
            assertEquals(engine.getListeningFrameCount()*1000/48000,engine.audioRecordedDurationMs())
            assertNull(engine.audioRecordingError())
            assertTrue(file.length()>44)
        } finally { engine.stop();source.close();file.delete() }
    }
    @Test fun fileEofDrainsShortAudioAndRecordingTail():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val input=File(context.cacheDir,"architecture-short-eof.iq")
        val output=File(context.cacheDir,"architecture-short-eof.wav")
        input.writeBytes(ByteArray(4800) { if(it%2==0) 140.toByte() else 130.toByte() }) // 50 ms at 48 kHz
        val source=com.radiosport.ninegradio.source.FileIqSource(input,48000,10000000,DemodMode.AM)
        assertTrue(source.open())
        val engine=DspEngine(source)
        try {
            engine.setDemodMode(DemodMode.AM);engine.setVolume(0f);engine.startAudioRecording(output.path)
            engine.start()
            withTimeout(5000) { engine.inputComplete.first { it } }
            engine.stopAudioRecording()
            assertNull(engine.failure.value)
            assertTrue(engine.getPlaybackClock().getValue("writtenFrames")>0)
            assertEquals(engine.getListeningFrameCount()*1000/48000,engine.audioRecordedDurationMs())
            assertTrue(output.length()>44)
        } finally { engine.stop();source.close();input.delete();output.delete() }
    }
}
