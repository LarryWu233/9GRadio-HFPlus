package com.radiosport.ninegradio.audio

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Existing RIFF writer extended to 16/24-bit PCM and 32-bit IEEE float. */
class PcmRecorder(private val filePath:String,private val sampleRate:Int,private val channels:Int=1,
    private val bitDepth:Int=16) {
    @Volatile var isRecording=false
        private set
    private var stream:OutputStream?=null
    private var bytesWritten=0L
    private var buffer=ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)
    private val bytesPerSample=bitDepth/8
    @Synchronized fun start() {
        require(bitDepth in listOf(16,24,32) && sampleRate>0 && channels in 1..2)
        val file=File(filePath);file.parentFile?.mkdirs()
        stream=BufferedOutputStream(FileOutputStream(file))
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36).put("WAVEfmt ".toByteArray()).putInt(16)
        header.putShort(if(bitDepth==32) 3 else 1).putShort(channels.toShort()).putInt(sampleRate)
        header.putInt(sampleRate*channels*bytesPerSample).putShort((channels*bytesPerSample).toShort())
        header.putShort(bitDepth.toShort()).put("data".toByteArray()).putInt(0)
        try { stream!!.write(header.array());bytesWritten=0;isRecording=true }
        catch(t:Throwable) { runCatching { stream?.close() };stream=null;throw t }
    }
    @Synchronized fun writeSamples(samples:FloatArray) {
        if(!isRecording) return
        require(samples.size%channels==0)
        val bytes=samples.size*bytesPerSample
        if(bytesWritten+bytes>0xffffffffL-36) throw IOException("WAV 已达到 4 GB 限制")
        if(buffer.capacity()<bytes) buffer=ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.clear()
        for(sample in samples) {
            val value=sample.takeIf { it.isFinite() } ?: 0f
            when(bitDepth) {
                16 -> buffer.putShort((value.coerceIn(-1f,1f)*32767f).roundToInt().toShort())
                24 -> { val pcm=(value.coerceIn(-1f,1f)*8388607f).roundToInt();buffer.put(pcm.toByte());buffer.put((pcm shr 8).toByte());buffer.put((pcm shr 16).toByte()) }
                32 -> buffer.putFloat(value)
            }
        }
        stream!!.write(buffer.array(),0,bytes);bytesWritten+=bytes
    }
    @Synchronized fun stop() {
        if(!isRecording) return
        isRecording=false
        val pad=(bytesWritten%2).toInt()
        try { if(pad!=0) stream?.write(0);stream?.close() } finally { stream=null }
        RandomAccessFile(filePath,"rw").use { out ->
            val size=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            out.seek(4);size.putInt((36+bytesWritten+pad).toInt());out.write(size.array())
            out.seek(40);size.clear();size.putInt(bytesWritten.toInt());out.write(size.array())
        }
    }
    @Synchronized fun capturedDurationMs()=bytesWritten*1000/(sampleRate*channels*bytesPerSample)
}
