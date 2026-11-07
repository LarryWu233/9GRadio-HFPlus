package com.radiosport.ninegradio.recording

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaCodecList
import android.os.Build
import androidx.preference.PreferenceManager

enum class AudioRecordingFormat(val label:String,val extension:String,val mime:String) {
    AAC("M4A · AAC","m4a","audio/mp4a-latm"),
    OPUS("OGG · Opus","ogg","audio/opus"),
    WAV("WAV · PCM","wav","audio/wav")
}

data class AudioRecordingProfile(val format:AudioRecordingFormat=AudioRecordingFormat.AAC,
    val bitDepth:Int=16,val bitRate:Int=128000) {
    fun normalized()=copy(bitDepth=bitDepth.takeIf { it in listOf(16,24,32) } ?: 16,
        bitRate=bitRate.takeIf { it in BIT_RATES } ?: 128000)
    fun fileSampleRate(inputRate:Int)=if(format==AudioRecordingFormat.OPUS) 48000 else inputRate
    fun summary()=if(format==AudioRecordingFormat.WAV) "WAV · ${if(bitDepth==32) "32-bit float" else "$bitDepth-bit PCM"}" else "${format.label} · ${bitRate/1000} kb/s"
    companion object {
        val BIT_RATES=listOf(32000,48000,64000,96000,128000,192000,256000)
        fun read(context:Context)=read(PreferenceManager.getDefaultSharedPreferences(context)).let { profile ->
            if(profile.format in supportedFormats()) profile else profile.copy(format=supportedFormats().first())
        }
        fun read(prefs:SharedPreferences)=AudioRecordingProfile(
            runCatching { AudioRecordingFormat.valueOf(prefs.getString("pref_record_audio_format","AAC")!!) }.getOrDefault(AudioRecordingFormat.AAC),
            prefs.getString("pref_record_audio_depth","16")?.toIntOrNull() ?: 16,
            prefs.getString("pref_record_audio_bitrate","128000")?.toIntOrNull() ?: 128000).normalized()
        fun forFile(path:String)=AudioRecordingProfile(format=when(java.io.File(path).extension.lowercase()) {
            "m4a","mp4" -> AudioRecordingFormat.AAC; "ogg","opus" -> AudioRecordingFormat.OPUS
            else -> AudioRecordingFormat.WAV
        })
        fun mime(path:String)=when(java.io.File(path).extension.lowercase()) {
            "m4a","mp4" -> "audio/mp4"; "ogg","opus" -> "audio/ogg"; "wav" -> "audio/wav"; "flac" -> "audio/flac"
            else -> "application/octet-stream"
        }
        private val availableFormats:List<AudioRecordingFormat> by lazy {
            val types=runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder }.flatMap { it.supportedTypes.toList() }.map { it.lowercase() }.toSet() }.getOrDefault(emptySet())
            AudioRecordingFormat.entries.filter { it==AudioRecordingFormat.WAV ||
                (it.mime in types && (it!=AudioRecordingFormat.OPUS || Build.VERSION.SDK_INT>=29)) }
        }
        fun supportedFormats():List<AudioRecordingFormat> = availableFormats
    }
}
