package com.radiosport.ninegradio

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.*
import com.radiosport.ninegradio.pipeline.*
import com.radiosport.ninegradio.source.FileIqSource
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit external reference run. Derived IQ is not presented as captured RF.
 * The fixtures and received content are never packaged in the application.
 */
@RunWith(AndroidJUnit4::class)
class AisReferenceReplayTest {
    @Test fun publicCaptureDerivedIqDecodesThroughBothSourceFormats():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val u8=File(context.cacheDir,"ais-reference-288k.cu8")
        val f32=File(context.cacheDir,"ais-reference-768k.cf32")
        for(file in listOf(u8,f32)) if(!file.isFile) {
            File(context.getExternalFilesDir(null),file.name).takeIf { it.isFile }?.copyTo(file)
        }
        assumeTrue("Push the documented independent-derived reference fixtures explicitly",u8.isFile && f32.isFile)
        for((file,rate,format) in listOf(Triple(u8,288000,IqSampleFormat.UNSIGNED_8),Triple(f32,768000,IqSampleFormat.FLOAT_32))) {
            val source=FileIqSource(file,rate,162000000,DemodMode.AIS)
            val receiver=AisReceiver(context)
            val registered=com.radiosport.ninegradio.modules.RegisteredIqModules(mapOf("ais" to { receiver }))
            try {
                assertTrue(source.open())
                assertTrue(registered.select(DemodMode.AIS,source,IqDecoderConfiguration(rate,format),source.capabilities.moduleSupport()).available)
                assertEquals("Decoder subscribes before producer start",1,source.iqSubscriberCount)
                val begin=SystemClock.elapsedRealtime();source.startStreaming()
                withTimeout(25000) { while(receiver.diagnostics.value.running) delay(25) }
                val status=receiver.diagnostics.value
                assertNull(status.error);assertTrue("CRC-valid AIS messages: ${status.messages}",status.messages>=80)
                assertTrue(receiver.vessels.value.size>=8)
                assertTrue(receiver.vessels.value.any { it.latitude!=null && it.longitude!=null })
                assertEquals(file.length()/(if(format==IqSampleFormat.FLOAT_32) 8 else 2),status.samples)
                assertEquals(0L,status.missingSamples);assertEquals(0L,status.driverLostSamples)
                assertTrue("Paced 12-second replay must not accumulate",SystemClock.elapsedRealtime()-begin<16000)
            } finally { registered.stop();receiver.stop();source.close() }
            assertFalse(receiver.hasPendingRelease);assertEquals(0,source.iqSubscriberCount)
        }
    }
}
