package com.radiosport.ninegradio.audio

import android.media.*
import android.os.Build
import com.radiosport.ninegradio.recording.*
import com.radiosport.ninegradio.dsp.PolyphaseResampler
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

/** Bounded worker adapter to Android MediaCodec/MediaMuxer; no codec runs on the audio writer. */
class AudioFileRecorder(private val path:String,private val rate:Int,private val channels:Int,
    private val profile:AudioRecordingProfile) {
    @Volatile var isRecording=false
        private set
    @Volatile var error:String?=null
        private set
    @Volatile private var finishing=false
    private val queue=ArrayBlockingQueue<FloatArray>(128)
    private val frames=AtomicLong()
    private var worker:Thread?=null

    fun start() {
        val ready=CompletableFuture<Unit>()
        worker=Thread({
            var sink:Sink?=null
            try {
                sink=if(profile.format==AudioRecordingFormat.WAV) WaveSink(path,rate,channels,profile.bitDepth)
                    else CodecSink(path,profile.fileSampleRate(rate),channels,profile)
                val targetRate=profile.fileSampleRate(rate)
                val resamplers=if(targetRate!=rate) Array(channels) { PolyphaseResampler(rate,targetRate) } else null
                isRecording=true;ready.complete(Unit)
                while(!finishing || queue.isNotEmpty()) {
                    val data=queue.poll(40,TimeUnit.MILLISECONDS) ?: continue
                    val converted=if(resamplers==null) data else {
                        val separated=Array(channels) { c -> resamplers[c].process(FloatArray(data.size/channels) { i -> data[i*channels+c] }) }
                        val count=separated.minOf { it.size }
                        FloatArray(count*channels) { i -> separated[i%channels][i/channels] }
                    }
                    sink.write(converted);frames.addAndGet((data.size/channels).toLong())
                }
            } catch(t:Throwable) {
                error=t.message ?: "录制失败";isRecording=false;finishing=true
                ready.completeExceptionally(t)
            } finally {
                runCatching { sink?.close() }.onFailure { error=it.message ?: "录制文件未能完成" }
                isRecording=false
            }
        },"radio-audio-recorder").apply { start() }
        try { ready.get(8,TimeUnit.SECONDS) } catch(t:Exception) { finishing=true;isRecording=false;throw IOException("无法启动所选录制格式",t) }
    }
    fun writeSamples(samples:FloatArray) {
        if(!isRecording || finishing) return
        if(!queue.offer(samples.copyOf())) {
            error="编码或存储未能跟上，录制已停止";isRecording=false;finishing=true
        }
    }
    fun stop() {
        isRecording=false;finishing=true
        worker?.join(15000)
        if(worker?.isAlive==true) { error="录制文件仍在完成，请稍候";throw IOException(error) }
    }
    fun capturedDurationMs()=frames.get()*1000/rate

    private interface Sink { fun write(values:FloatArray);fun close() }
    private class WaveSink(path:String,rate:Int,channels:Int,depth:Int):Sink {
        private val recorder=PcmRecorder(path,rate,channels,depth).apply { start() }
        override fun write(values:FloatArray) { recorder.writeSamples(values);if(!recorder.isRecording) throw IOException("WAV 写入失败") }
        override fun close()=recorder.stop()
    }
    /** Android synchronous codec flow, with bounded buffer waits and monotonic sample timestamps. */
    private class CodecSink(path:String,private val rate:Int,private val channels:Int,profile:AudioRecordingProfile):Sink {
        private val codec=MediaCodec.createEncoderByType(profile.format.mime)
        private var muxer:MediaMuxer?=null
        private var started=false
        private var track=-1
        private var inputFrames=0L
        private var eos=false
        private val info=MediaCodec.BufferInfo()
        init {
            try {
                require(profile.format!=AudioRecordingFormat.OPUS || Build.VERSION.SDK_INT>=29)
                val format=MediaFormat.createAudioFormat(profile.format.mime,rate,channels)
                format.setInteger(MediaFormat.KEY_BIT_RATE,profile.bitRate)
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,16384)
                format.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT)
                if(profile.format==AudioRecordingFormat.AAC) format.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start()
                muxer=MediaMuxer(path,if(profile.format==AudioRecordingFormat.OPUS) MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } catch(t:Throwable) { runCatching { codec.release() };runCatching { muxer?.release() };throw t }
        }
        private fun inputIndex():Int {
            val deadline=System.nanoTime()+5_000_000_000L
            while(System.nanoTime()<deadline) {
                val index=codec.dequeueInputBuffer(10000)
                if(index>=0) return index
                drain(false)
            }
            throw IOException("音频编码器输入超时")
        }
        override fun write(values:FloatArray) {
            var offset=0
            while(offset<values.size) {
                val index=inputIndex();val buffer=codec.getInputBuffer(index)!!
                buffer.clear();buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val count=(minOf(values.size-offset,buffer.remaining()/2)/channels)*channels
                check(count>0)
                repeat(count) { i -> val value=values[offset+i].takeIf { it.isFinite() } ?: 0f
                    buffer.putShort((value.coerceIn(-1f,1f)*32767f).roundToInt().toShort()) }
                codec.queueInputBuffer(index,0,count*2,inputFrames*1_000_000/rate,0)
                inputFrames+=count/channels;offset+=count;drain(false)
            }
        }
        private fun drain(ending:Boolean) {
            val deadline=System.nanoTime()+5_000_000_000L
            while(true) {
                when(val index=codec.dequeueOutputBuffer(info,if(ending) 10000 else 0)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> if(!ending) return else if(System.nanoTime()>deadline) throw IOException("音频编码器完成超时")
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { check(!started);track=muxer!!.addTrack(codec.outputFormat);muxer!!.start();started=true }
                    else -> if(index>=0) {
                        try {
                            if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {
                                check(started)
                                val data=codec.getOutputBuffer(index)!!;data.position(info.offset);data.limit(info.offset+info.size)
                                muxer!!.writeSampleData(track,data,info)
                            }
                            if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0) eos=true
                        } finally { codec.releaseOutputBuffer(index,false) }
                        if(eos) return
                    }
                }
            }
        }
        override fun close() {
            try {
                val index=inputIndex();codec.queueInputBuffer(index,0,0,inputFrames*1_000_000/rate,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(true)
            } finally {
                runCatching { codec.stop() };codec.release()
                try { if(started) muxer?.stop() } finally { muxer?.release() }
            }
        }
    }
}
