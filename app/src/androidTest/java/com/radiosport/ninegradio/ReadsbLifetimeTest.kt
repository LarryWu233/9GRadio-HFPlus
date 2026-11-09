package com.radiosport.ninegradio

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.ReadsbReceiver
import com.radiosport.ninegradio.pipeline.CaptureClock
import com.radiosport.ninegradio.pipeline.IqStream
import com.radiosport.ninegradio.source.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadsbLifetimeTest {
    private class Source:IqSource by RtlTcpSource("127.0.0.1",0) {
        val stream=IqStream(lossless=true)
        override val iqFlow=stream.flow
        override val iqSubscriberCount get()=stream.subscriberCount
        override fun getSampleRate()=2400000
    }
    private suspend fun waitUntil(condition:()->Boolean) {
        withTimeout(7000) { while(!condition()) delay(10) }
    }
    @Test fun repeatedStartStopJoinsNativeReadersBeforeReplacement():Unit=runBlocking {
        val receiver=ReadsbReceiver(InstrumentationRegistry.getInstrumentation().targetContext)
        val source=Source()
        try {
            repeat(30) {
                receiver.start(source)
                waitUntil { source.iqSubscriberCount==1 }
                val started=SystemClock.elapsedRealtime()
                receiver.stop()
                assertFalse("Cycle $it: ${receiver.pendingReleaseDetails}",receiver.hasPendingRelease)
                assertEquals(0,source.iqSubscriberCount)
                assertTrue(SystemClock.elapsedRealtime()-started<5000)
            }
            delay(600)
            assertFalse(receiver.status.value.running)
            assertTrue(receiver.aircraft.value.isEmpty())
        } finally { receiver.stop() }
    }
    @Test fun iqGapRestartsNativeSynchronizationAndCountsMissingSamples():Unit=runBlocking {
        val receiver=ReadsbReceiver(InstrumentationRegistry.getInstrumentation().targetContext)
        val source=Source();val clock=CaptureClock();val noise=ByteArray(8192) { 127 }
        try {
            receiver.start(source)
            waitUntil { source.iqSubscriberCount==1 }
            val first=clock.stamp(4096,2400000,1090000000)
            source.stream.send(IqBlock.Unsigned8(noise,first))
            waitUntil { receiver.status.value.inputSamples==4096L }
            val generation=receiver.status.value.decoderGeneration
            source.stream.send(IqBlock.Unsigned8(noise,first)) // duplicate must not be processed
            clock.stamp(4096,2400000,1090000000) // one application block is deliberately absent
            source.stream.send(IqBlock.Unsigned8(noise,clock.stamp(4096,2400000,1090000000)))
            waitUntil { receiver.status.value.decoderGeneration>generation }
            val status=receiver.status.value
            assertNull(status.error)
            assertEquals(8192L,status.inputSamples)
            assertEquals(4096L,status.inputMissingSamples)
            assertEquals(0L,status.driverMissingSamples)
            val module:com.radiosport.ninegradio.pipeline.IqDecoderModule<List<com.radiosport.ninegradio.dsp.AdsbDecoder.AdsbFrame>> = receiver
            module.start(source,com.radiosport.ninegradio.pipeline.IqDecoderConfiguration(768000,
                com.radiosport.ninegradio.pipeline.IqSampleFormat.FLOAT_32))
            assertFalse("A running module must also reject an unsupported replacement",receiver.status.value.running)
            assertNotNull(receiver.status.value.error)
            assertEquals(0,source.iqSubscriberCount)
        } finally { receiver.stop() }
        assertFalse(receiver.pendingReleaseDetails,receiver.hasPendingRelease)
    }
}
