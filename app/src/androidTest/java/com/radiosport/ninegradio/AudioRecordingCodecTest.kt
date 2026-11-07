package com.radiosport.ninegradio

import android.media.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.audio.AudioFileRecorder
import com.radiosport.ninegradio.recording.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteOrder
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class AudioRecordingCodecTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun check(format:AudioRecordingFormat,rate:Int) {
        assertTrue("Required encoder available on test phone",format in AudioRecordingProfile.supportedFormats())
        val file=File(context.cacheDir,"codec-check-${rate}.${format.extension}")
        try {
            val profile=AudioRecordingProfile(format,16,128000)
            val recorder=AudioFileRecorder(file.path,rate,2,profile)
            recorder.start()
            var position=0
            while(position<rate*4) {
                val count=minOf(2048,rate*4-position)
                recorder.writeSamples(FloatArray(count*2) { i ->
                    (0.45*sin(2*Math.PI*(if(i%2==0) 1000 else 2300)*(position+i/2)/rate)).toFloat()
                });position+=count
            }
            recorder.stop()
            assertNull("Recorder error",recorder.error);assertEquals(4000L,recorder.capturedDurationMs())
            assertTrue("Compressed file is substantially smaller than PCM",file.length() in 1000 until (rate*4*2*2/3).toLong())
            val retriever=MediaMetadataRetriever()
            try { retriever.setDataSource(file.path)
                assertTrue("Container duration",abs(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()-4000)<150)
            } finally { retriever.release() }
            val extractor=MediaExtractor()
            try {
                extractor.setDataSource(file.path);assertEquals(1,extractor.trackCount)
                val media=extractor.getTrackFormat(0);assertEquals(profile.fileSampleRate(rate),media.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                assertEquals(2,media.getInteger(MediaFormat.KEY_CHANNEL_COUNT));extractor.selectTrack(0)
                val decoder=MediaCodec.createDecoderByType(media.getString(MediaFormat.KEY_MIME)!!)
                try {
                    decoder.configure(media,null,null,0);decoder.start()
                    val info=MediaCodec.BufferInfo();var inputDone=false;var outputDone=false
                    var samples=0L;val correlations=DoubleArray(4)
                    val deadline=System.nanoTime()+15_000_000_000L
                    while(!outputDone && System.nanoTime()<deadline) {
                        if(!inputDone) {
                            val index=decoder.dequeueInputBuffer(1000)
                            if(index>=0) {
                                val data=decoder.getInputBuffer(index)!!;val bytes=extractor.readSampleData(data,0)
                                if(bytes<0) { decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true }
                                else { decoder.queueInputBuffer(index,0,bytes,extractor.sampleTime,0);extractor.advance() }
                            }
                        }
                        val index=decoder.dequeueOutputBuffer(info,1000)
                        if(index>=0) {
                            val output=decoder.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                            output.position(info.offset);output.limit(info.offset+info.size)
                            while(output.remaining()>=4) {
                                val l=output.short/32768.0;val r=output.short/32768.0
                                val t=samples.toDouble()/profile.fileSampleRate(rate)
                                // Quadrature makes this independent of codec priming delay.
                                val freq=1000.0;val phase=2*Math.PI*freq*t
                                correlations[0]+=l*cos(phase);correlations[1]+=l*sin(phase)
                                correlations[2]+=r*cos(phase);correlations[3]+=r*sin(phase);samples++
                            }
                            outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                            decoder.releaseOutputBuffer(index,false)
                        }
                    }
                    assertTrue("Decoder reached EOS",outputDone)
                    assertTrue("Decoded frame count",abs(samples-profile.fileSampleRate(rate)*4L)<profile.fileSampleRate(rate)/6)
                    assertTrue("Stereo channel separation",hypot(correlations[0],correlations[1])>10*hypot(correlations[2],correlations[3]))
                } finally { runCatching { decoder.stop() };decoder.release() }
            } finally { extractor.release() }
        } finally { file.delete() }
    }
    @Test fun aacStereoRoundTrip() = check(AudioRecordingFormat.AAC,48000)
    @Test fun opusStereoRoundTrip() = check(AudioRecordingFormat.OPUS,48000)
    @Test fun opusResamples44100WithoutChangingDuration() = check(AudioRecordingFormat.OPUS,44100)
}
