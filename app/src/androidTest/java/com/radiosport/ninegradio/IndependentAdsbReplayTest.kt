package com.radiosport.ninegradio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.dsp.ReadsbReceiver
import com.radiosport.ninegradio.source.FileIqSource
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Public upstream RF recording, explicitly resampled offline; never a synthetic station. */
@RunWith(AndroidJUnit4::class)
class IndependentAdsbReplayTest {
    @Test fun upstreamRfRecordingDrainsThroughNativeDecoder():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"reference-modes1-2400k.u8")
        assumeTrue("Supply the pinned independent fixture described in the validation record",file.isFile)
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("Pinned independent fixture provenance","7b7a619d4ed796e9d1fb6608c2af6aca123c50df02c370f0199e465e601602fc",digest)
        val source=FileIqSource(file,2400000,1090000000,DemodMode.ADSB)
        val receiver=ReadsbReceiver(context)
        try {
            assertTrue(source.open());receiver.start(source)
            withTimeout(7000) { while(source.iqSubscriberCount==0) delay(10) }
            source.startStreaming()
            withTimeout(10000) { while(receiver.status.value.running) delay(20) }
            val status=receiver.status.value
            assertNull(status.error)
            assertEquals(file.length()/2,status.inputSamples)
            assertTrue("At least one CRC-accepted message in the independent recording",status.messages>0)
            assertTrue("Native tracking received independent RF data",receiver.aircraft.value.isNotEmpty())
            assertEquals(0L,status.inputMissingSamples)
        } finally { receiver.stop();source.close() }
        assertFalse(receiver.hasPendingRelease)
    }
}
