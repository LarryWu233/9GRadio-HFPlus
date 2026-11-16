package com.radiosport.ninegradio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.content.*
import com.radiosport.ninegradio.dsp.DspEngine
import com.radiosport.ninegradio.pipeline.AudioBlock
import com.radiosport.ninegradio.pipeline.AudioDecoderInput
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioContentEpochTest {
    @Test fun sstvCannotRewritePublishedDecoderAudio():Unit=runBlocking {
        val engine=DspEngine(ArchitectureLifecycleTest.Source())
        val receiver=AudioContentReceiver(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            receiver.attach(engine);receiver.start(ContentConfig(AudioContentKind.SSTV))
            val input=AudioContentReceiver::class.java.getDeclaredField("session").apply { isAccessible=true }
                .get(receiver) as AudioDecoderInput
            val samples=FloatArray(4800) { kotlin.math.sin(2*Math.PI*1500*it/48000).toFloat()*.2f }
            val original=samples.copyOf()
            input.accept(AudioBlock(samples,48000,1,1,0))
            withTimeout(7000) { while(receiver.state.value.sampleCount<samples.size) delay(10) }
            assertArrayEquals("A destructive backend must copy its input",original,samples,0f)
        } finally { receiver.stop();engine.stop() }
        assertFalse(receiver.hasPendingRelease)
    }
    @Test fun nativeDecoderResetsOnGapsAndEpochsWithoutAcceptingOldAudio():Unit=runBlocking {
        val engine=DspEngine(ArchitectureLifecycleTest.Source())
        val receiver=AudioContentReceiver(InstrumentationRegistry.getInstrumentation().targetContext)
        suspend fun waitUntil(condition:()->Boolean) { withTimeout(7000) { while(!condition()) delay(10) } }
        try {
            receiver.attach(engine);receiver.start(ContentConfig(AudioContentKind.CW))
            // Test-only access to the selected adapter; no injected entry point in the app UI.
            val input=AudioContentReceiver::class.java.getDeclaredField("session").apply { isAccessible=true }
                .get(receiver) as AudioDecoderInput
            fun send(epoch:Long,first:Long)=input.accept(AudioBlock(FloatArray(4800),48000,1,epoch,first))
            send(1,0);waitUntil { receiver.state.value.sampleCount>=4800 }
            send(1,9600);waitUntil { receiver.state.value.synchronizationResets>=1 && receiver.state.value.sampleCount>=9600 }
            assertEquals(4800L,receiver.state.value.missingFrames)
            send(2,0);waitUntil { receiver.state.value.sampleCount>=14400 }
            assertEquals(2L,receiver.state.value.synchronizationResets)
            send(1,14400);delay(200)
            assertEquals(14400L,receiver.state.value.sampleCount)
            assertEquals(2L,receiver.state.value.synchronizationResets)
            assertTrue(receiver.state.value.running)
            input.configure(3) // invalidate old results before new audio arrives
            send(2,4800);delay(100)
            assertEquals(14400L,receiver.state.value.sampleCount)
            send(3,0);waitUntil { receiver.state.value.sampleCount>=19200 }
            assertEquals(3L,receiver.state.value.synchronizationResets)
        } finally { receiver.stop();engine.stop() }
        assertFalse(receiver.hasPendingRelease)
    }
}
