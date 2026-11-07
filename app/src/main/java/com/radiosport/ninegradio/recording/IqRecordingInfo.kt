package com.radiosport.ninegradio.recording

import android.util.AtomicFile
import com.radiosport.ninegradio.dsp.DemodMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** SigMF 1.2.6 metadata for the existing raw dataset filenames (core:dataset). */
object IqRecordingInfo {
    data class Info(val dialHz: Long,val hardwareHz: Long,val mode: DemodMode,val floating: Boolean)
    fun metadataFile(dataset: File)=File(dataset.path+".sigmf-meta")
    fun write(dataset: File,dialHz: Long,hardwareHz: Long,rate: Int,mode: DemodMode,floating: Boolean) {
        val global=JSONObject().put("core:version","1.2.6").put("core:datatype",if(floating) "cf32_le" else "cu8")
            .put("core:sample_rate",rate).put("core:dataset",dataset.name).put("core:recorder","9GRadio HF+")
            .put("ninegradio:dial_frequency",dialHz).put("ninegradio:demod_mode",mode.name)
            .put("core:extensions",JSONArray().put(JSONObject().put("name","ninegradio").put("version","1.0.0").put("optional",true)))
        val metadata=JSONObject().put("global",global)
            .put("captures",JSONArray().put(JSONObject().put("core:sample_start",0).put("core:frequency",hardwareHz)))
            .put("annotations",JSONArray())
        val atomic=AtomicFile(metadataFile(dataset)); val stream=atomic.startWrite()
        try { stream.write(metadata.toString(2).toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch(e: Exception) { atomic.failWrite(stream); throw e }
    }
    fun read(dataset: File,legacyHardwareHz: Long,legacyMode: DemodMode): Info {
        val metadata=metadataFile(dataset)
        if(metadata.exists() && metadata.length()<=65536) runCatching {
            val json=JSONObject(metadata.readText()); val global=json.getJSONObject("global")
            val hardware=json.getJSONArray("captures").getJSONObject(0).getLong("core:frequency")
            val dial=global.getLong("ninegradio:dial_frequency")
            require(dial in 1..1_000_000_000_000 && hardware in 1..1_000_000_000_000)
            val mode=DemodMode.valueOf(global.getString("ninegradio:demod_mode"))
            Info(dial,hardware,mode,global.getString("core:datatype")=="cf32_le")
        }.getOrNull()?.let { return it }
        val dial=if(legacyMode==DemodMode.USB || legacyMode==DemodMode.LSB) (legacyHardwareHz-1500).coerceAtLeast(1) else legacyHardwareHz
        return Info(dial,legacyHardwareHz,legacyMode,dataset.extension.equals("cf32",true))
    }
}
