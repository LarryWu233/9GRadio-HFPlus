package com.radiosport.ninegradio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.*
import com.radiosport.ninegradio.pipeline.*
import com.radiosport.ninegradio.source.IqBlock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AisNativePipeTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private class Input(private val format:IqSampleFormat,private val rate:Int,private val finite:Boolean,private val gap:Boolean=false):IqReadInput {
        val subscribers=AtomicInteger()
        override val iqSubscriberCount get()=subscribers.get()
        override val iqFlow=flow {
            subscribers.incrementAndGet()
            try {
                var position=0L;var sequence=0L
                while(!finite || position<rate*3L/4) {
                    val count=if(finite) minOf(4096,(rate*3L/4-position).toInt()) else 4096
                    val offset=if(gap && sequence>=2) 1400 else 0
                    val meta=IqMetadata(streamId=1,sequence=sequence,firstSample=position+offset,sampleRate=rate,centerHz=162000000,
                        driverLostSamples=if(gap && sequence==2L) 400 else 0,driverLostTotal=if(offset>0) 400 else 0)
                    sequence++
                    emit(if(format==IqSampleFormat.UNSIGNED_8) IqBlock.Unsigned8(ByteArray(count*2) { 128.toByte() },meta)
                        else IqBlock.Float32(FloatArray(count*2),metadata=meta))
                    position+=count
                }
            } finally { subscribers.decrementAndGet() }
        }
    }
    @Test fun bothIqFormatsReachNativeEofWithoutPhantomTargets():Unit=runBlocking {
        for(format in IqSampleFormat.entries) {
            val rate=if(format==IqSampleFormat.FLOAT_32) 768000 else 288000
            val source=Input(format,rate,true);val receiver=AisReceiver(context)
            try {
                receiver.start(source,IqDecoderConfiguration(rate,format))
                withTimeout(15000) { while(receiver.diagnostics.value.running) delay(20) }
                val status=receiver.diagnostics.value
                assertNull(status.error);assertEquals(rate*3L/4,status.samples)
                assertEquals(0L,status.missingSamples);assertEquals(0L,status.driverLostSamples)
                assertTrue(receiver.vessels.value.isEmpty())
            } finally { receiver.stop() }
            assertFalse(receiver.hasPendingRelease);assertEquals(0,source.iqSubscriberCount)
        }
    }
    @Test fun stopReleasesBusyPipeAndRejectsUnsupportedRate():Unit=runBlocking {
        val receiver=AisReceiver(context)
        for(format in IqSampleFormat.entries) {
            val source=Input(format,768000,false)
            receiver.start(source,IqDecoderConfiguration(768000,format))
            withTimeout(5000) { while(source.iqSubscriberCount==0) delay(10) }
            delay(100);receiver.stop()
            assertFalse(receiver.hasPendingRelease);assertEquals(0,source.iqSubscriberCount)
        }
        val refused=Input(IqSampleFormat.FLOAT_32,48000,false)
        receiver.start(refused,IqDecoderConfiguration(48000,IqSampleFormat.FLOAT_32))
        assertFalse(receiver.diagnostics.value.running);assertNotNull(receiver.diagnostics.value.error)
        assertEquals(0,refused.iqSubscriberCount);receiver.stop()
    }
    @Test fun nativeResynchronizationKeepsApplicationAndDriverLossSeparate():Unit=runBlocking {
        for(format in IqSampleFormat.entries) {
            val source=Input(format,288000,true,gap=true);val receiver=AisReceiver(context)
            try {
                receiver.start(source,IqDecoderConfiguration(288000,format))
                withTimeout(10000) { while(receiver.diagnostics.value.running) delay(10) }
                val status=receiver.diagnostics.value
                assertNull(status.error);assertEquals(216000L,status.samples)
                assertEquals(1000L,status.missingSamples);assertEquals(400L,status.driverLostSamples)
                assertTrue(receiver.vessels.value.isEmpty())
            } finally { receiver.stop() }
            assertFalse(receiver.hasPendingRelease);assertEquals(0,source.iqSubscriberCount)
        }
    }
}
