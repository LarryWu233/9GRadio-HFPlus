package com.radiosport.ninegradio.recording

import android.media.MediaMetadataRetriever
import com.radiosport.ninegradio.data.RecordingMeta
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

object RecordingDetails {
    private val cache=ConcurrentHashMap<String,Long>()
    fun duration(meta:RecordingMeta):Long? {
        if(meta.durationMs>0) return meta.durationMs
        val file=File(meta.filePath);if(!file.isFile) return null
        val key="${file.path}:${file.length()}:${file.lastModified()}"
        cache[key]?.let { return it }
        val value=if(meta.type=="IQ") {
            if(file.extension.equals("gz",true) || meta.sampleRate<=0) null
            else file.length()*1000/((if(IqRecordingInfo.read(file,meta.frequencyHz,runCatching { com.radiosport.ninegradio.dsp.DemodMode.valueOf(meta.demodMode) }.getOrDefault(com.radiosport.ninegradio.dsp.DemodMode.AM)).floating) 8 else 2)*meta.sampleRate.toLong())
        } else runCatching {
            val retriever=MediaMetadataRetriever()
            try { retriever.setDataSource(file.path);retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() }
            finally { retriever.release() }
        }.getOrNull() ?: if(file.extension.equals("wav",true)) waveDuration(file) else null
        if(value!=null) { if(cache.size>256) cache.clear();cache[key]=value }
        return value
    }
    private fun waveDuration(file:File):Long?=runCatching {
        RandomAccessFile(file,"r").use { input ->
            val head=ByteArray(12);input.readFully(head)
            if(String(head,0,4,Charsets.US_ASCII)!="RIFF" || String(head,8,4,Charsets.US_ASCII)!="WAVE") return@use null
            var rate=0L;var iterations=0
            while(input.filePointer+8<=input.length() && iterations++<128) {
                val bytes=ByteArray(8);input.readFully(bytes)
                val name=String(bytes,0,4,Charsets.US_ASCII)
                val size=ByteBuffer.wrap(bytes,4,4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
                val start=input.filePointer
                if(name=="fmt " && size>=16) {
                    val format=ByteArray(16);input.readFully(format)
                    rate=ByteBuffer.wrap(format).order(ByteOrder.LITTLE_ENDIAN).getInt(8).toLong() and 0xffffffffL
                }
                if(name=="data" && rate>0) return@use size.coerceAtMost(input.length()-start)*1000/rate
                val next=start+size+(size%2);if(next>input.length()) break;input.seek(next)
            };null
        }
    }.getOrNull()
}
