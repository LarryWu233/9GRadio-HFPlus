package com.radiosport.ninegradio

import com.radiosport.ninegradio.recording.*
import com.radiosport.ninegradio.source.IqBlock
import com.radiosport.ninegradio.pipeline.CaptureClock
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class AsyncIqRecorderTest {
    private class Sink(private val delayMs:Long=0,private val fail:Boolean=false):IqRecordingSink {
        @Volatile override var isRecording=false
        val samples=AtomicLong();var stops=0
        override fun start(path:String,rate:Int,format:IqRecorder.Format) { isRecording=true }
        override fun write(block:IqBlock) {
            if(delayMs>0) Thread.sleep(delayMs)
            if(fail) throw IOException("controlled error")
            samples.addAndGet(block.sampleCount.toLong())
        }
        override fun capturedDurationMs()=samples.get()*1000/48000
        override fun stop() { isRecording=false;stops++ }
    }
    @Test fun slowStorageStopsExplicitlyAndDrainsOnlyAcceptedPrefix() {
        val sink=Sink(3);val recorder=AsyncIqRecorder { sink }
        recorder.start("unused",48000,IqRecorder.Format.RAW_UINT8)
        repeat(300) { recorder.write(IqBlock.Unsigned8(ByteArray(960))) }
        assertFalse(recorder.isRecording)
        assertNotNull(recorder.error)
        recorder.stop()
        assertTrue(sink.samples.get()>0)
        assertTrue(sink.samples.get()<300*480)
        assertEquals(1,sink.stops)
    }
    @Test fun failedWriterIsReportedWithoutCrashingProducer() {
        val sink=Sink(fail=true);val recorder=AsyncIqRecorder { sink }
        recorder.start("unused",48000,IqRecorder.Format.RAW_UINT8)
        recorder.write(IqBlock.Unsigned8(ByteArray(960)));recorder.stop()
        assertNotNull(recorder.error);assertFalse(recorder.isRecording);assertEquals(1,sink.stops)
    }
    @Test fun incompatibleCaptureConfigurationCannotEnterConstantMetadataFile() {
        val sink=Sink();val recorder=AsyncIqRecorder { sink };val clock=CaptureClock()
        recorder.start("unused",48000,IqRecorder.Format.RAW_UINT8)
        val a=clock.stamp(480,48000,10000000)
        recorder.write(IqBlock.Unsigned8(ByteArray(960),a))
        val b=clock.stamp(480,48000,11000000)
        recorder.write(IqBlock.Unsigned8(ByteArray(960),b));recorder.stop()
        assertEquals(480L,sink.samples.get());assertNotNull(recorder.error)
    }
}
