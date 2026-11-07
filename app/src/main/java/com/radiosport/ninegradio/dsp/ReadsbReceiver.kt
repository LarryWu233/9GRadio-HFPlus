package com.radiosport.ninegradio.dsp

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.gson.JsonParser
import com.radiosport.ninegradio.data.RadioTarget
import com.radiosport.ninegradio.data.RadioTargets
import com.radiosport.ninegradio.source.IqBlock
import com.radiosport.ninegradio.source.IqSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** JSON adaptation only. Detection, CRC, CPR and aircraft tracking belong to pinned readsb. */
object ReadsbSnapshotParser {
    data class Snapshot(val messages: Long,val aircraft: List<AdsbDecoder.AdsbFrame>)
    fun parse(json: String,now: Long=System.currentTimeMillis()): Snapshot {
        val root=JsonParser.parseString(json).asJsonObject
        val aircraft=root.getAsJsonArray("aircraft")?.mapNotNull { element ->
            val item=element.asJsonObject
            fun text(key: String)=item.get(key)?.takeUnless { it.isJsonNull }?.asString
            fun number(key: String)=text(key)?.toDoubleOrNull()?.takeIf { it.isFinite() }
            val id=text("hex")?.takeIf { it.matches(Regex("~?[0-9a-fA-F]{6}")) } ?: return@mapNotNull null
            val seen=number("seen") ?: 0.0
            if(seen>120.0) return@mapNotNull null
            val lat=number("lat")?.takeIf { it in -90.0..90.0 && (number("seen_pos") ?: 0.0)<=120.0 }
            val lon=number("lon")?.takeIf { it in -180.0..180.0 && lat!=null }
            AdsbDecoder.AdsbFrame(id.uppercase(),17,0,(number("alt_baro") ?: number("alt_geom"))?.toInt(),
                text("flight")?.trim()?.takeIf { it.isNotEmpty() },if(lon!=null) lat else null,lon,
                number("gs")?.toInt(),number("track"), (number("baro_rate") ?: number("geom_rate"))?.toInt(),
                text("alt_baro")=="ground",now-(seen*1000).toLong())
        } ?: emptyList()
        return Snapshot(root.get("messages")?.asLong ?: 0,aircraft)
    }
}

/** Service-owned raw-IQ consumer; UI navigation never owns or restarts the decoder. */
class ReadsbReceiver(private val context: Context) {
    companion object { const val SAMPLE_RATE=2_400_000 }
    data class Status(val running: Boolean=false,val inputSamples: Long=0,val messages: Long=0,val error: String?=null)
    private val _status=MutableStateFlow(Status())
    val status=_status.asStateFlow()
    private val _aircraft=MutableStateFlow<List<AdsbDecoder.AdsbFrame>>(emptyList())
    val aircraft=_aircraft.asStateFlow()
    @Volatile private var process: Process?=null
    private var session: CoroutineScope?=null
    private var activeSource: IqSource?=null
    private val samples=AtomicLong()
    @Synchronized fun start(source: IqSource) {
        if(activeSource===source && process?.isAlive==true) return
        stop()
        if(source.getSampleRate()!=SAMPLE_RATE || source.capabilities.floatIq) {
            _status.value=Status(error="ADS-B 需要 2.400 MS/s 的原始 IQ")
            return
        }
        try {
            val executable=File(context.applicationInfo.nativeLibraryDir,"libninegradio_readsb.so")
            check(executable.canExecute()) { "readsb 解码组件不可用" }
            val directory=File(context.cacheDir,"readsb/session-${SystemClock.elapsedRealtimeNanos()}").apply { mkdirs() }
            val args=mutableListOf(executable.path,"--device-type","ifile","--ifile","-","--iformat","UC8",
                "--quiet","--write-json",directory.path,"--write-json-every","0.5")
            val location=context.getSharedPreferences("radio_map",Context.MODE_PRIVATE)
            if(location.contains("receiverLat") && location.contains("receiverLon")) {
                args+=listOf("--lat",location.getFloat("receiverLat",0f).toString(),"--lon",location.getFloat("receiverLon",0f).toString())
            }
            val child=ProcessBuilder(args).redirectErrorStream(true).start()
            process=child; activeSource=source; samples.set(0)
            val jobs=CoroutineScope(SupervisorJob()+Dispatchers.IO); session=jobs
            _status.value=Status(running=true)
            jobs.launch {
                try { child.inputStream.bufferedReader().useLines { lines -> lines.forEach { Log.i("Readsb",it) } } }
                catch(error: Exception) { if(isActive) Log.w("Readsb","Decoder log stream closed",error) }
            }
            jobs.launch {
                try {
                    val output=child.outputStream
                    source.iqFlow.collect { block ->
                        check(block is IqBlock.Unsigned8) { "readsb 输入格式发生变化" }
                        output.write(block.bytes)
                        samples.addAndGet(block.sampleCount.toLong())
                    }
                } catch(cancel: CancellationException) { throw cancel }
                catch(error: Exception) { if(isActive) { _status.value=_status.value.copy(running=false,error="IQ 输入中断：${error.message}"); child.destroy() } }
            }
            jobs.launch {
                val file=File(directory,"aircraft.json")
                while(isActive && process===child) {
                    if(!child.isAlive) {
                        _status.value=_status.value.copy(running=false,error="readsb 已停止（${child.exitValue()}）")
                        break
                    }
                    if(file.isFile) try {
                        val snapshot=ReadsbSnapshotParser.parse(file.readText())
                        if(process!==child || !isActive) break
                        _aircraft.value=snapshot.aircraft
                        RadioTargets.aircraft.value=snapshot.aircraft.mapNotNull { item ->
                            val lat=item.latitude ?: return@mapNotNull null
                            val lon=item.longitude ?: return@mapNotNull null
                            RadioTarget(item.icao24,item.callsign ?: item.icao24,lat,lon,
                                "${item.callsign ?: item.icao24}\n${item.altitude?.let { "$it ft" } ?: "高度未知"} · ${item.velocity?.let { "$it kt" } ?: "速度未知"}\n$lat, $lon",item.timestamp)
                        }
                        _status.value=Status(true,samples.get(),snapshot.messages)
                    } catch(error: Exception) { Log.w("Readsb","Waiting for complete aircraft snapshot",error) }
                    delay(500)
                }
            }
        } catch(error: Exception) { stop(); _status.value=Status(error=error.message ?: "ADS-B 启动失败") }
    }
    @Synchronized fun stop() {
        val child=process
        process=null; activeSource=null
        session?.cancel(); session=null
        child?.destroy()
        runCatching { child?.outputStream?.close() }
        if(child!=null && !child.waitFor(300,TimeUnit.MILLISECONDS)) child.destroyForcibly()
        _status.value=Status(); _aircraft.value=emptyList(); RadioTargets.aircraft.value=emptyList()
    }
}
