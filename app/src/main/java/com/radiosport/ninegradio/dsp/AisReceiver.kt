package com.radiosport.ninegradio.dsp

import android.content.Context
import com.google.gson.JsonParser
import com.radiosport.ninegradio.pipeline.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.io.File

enum class AisTargetKind { VESSEL, STATION, AID, AIRCRAFT }
data class AisVessel(val mmsi:Int,val name:String?=null,val callsign:String?=null,
    val latitude:Double?=null,val longitude:Double?=null,val speedKnots:Double?=null,
    val courseDegrees:Double?=null,val headingDegrees:Double?=null,val destination:String?=null,
    val lastMessageMs:Long=0,val positionTimeMs:Long?=null,val messageType:Int=0,val kind:AisTargetKind=AisTargetKind.VESSEL)

/** JSON presentation adapter only: upstream AIS-catcher owns radio/packet decoding. */
internal class AisVessels {
    private val entries=linkedMapOf<Int,AisVessel>()
    fun clear()=entries.clear()
    fun accept(text:String,now:Long):Boolean {
        if(!text.startsWith('{')) return false
        return runCatching {
            val objectValue=JsonParser.parseString(text).asJsonObject
            fun number(key:String)=objectValue[key]?.takeUnless { it.isJsonNull }?.asDouble?.takeIf { it.isFinite() }
            fun word(key:String)=objectValue[key]?.takeUnless { it.isJsonNull }?.asString?.trim(' ','@')?.takeIf { it.isNotEmpty() }
            val mmsi=number("mmsi")?.takeIf { it in 1.0..999999999.0 && it==it.toInt().toDouble() }?.toInt() ?: return false
            val type=number("type")?.toInt()?.takeIf { it in 1..27 } ?: return false
            val previous=entries[mmsi] ?: AisVessel(mmsi)
            val lat=number("lat")?.takeIf { it in -90.0..90.0 }
            val lon=number("lon")?.takeIf { it in -180.0..180.0 }
            val validPosition=lat!=null && lon!=null
            val dynamic=when(type) { 1,2,3,9,18,19,27 -> true;else -> false }
            entries[mmsi]=previous.copy(name=word("shipname") ?: previous.name,callsign=word("callsign") ?: previous.callsign,
                latitude=if(validPosition) lat else previous.latitude,longitude=if(validPosition) lon else previous.longitude,
                speedKnots=if(dynamic) number("speed")?.takeIf { it in 0.0..<102.3 } else previous.speedKnots,
                courseDegrees=if(dynamic) number("course")?.takeIf { it in 0.0..<360.0 } else previous.courseDegrees,
                headingDegrees=if(dynamic) number("heading")?.takeIf { it in 0.0..<360.0 } else previous.headingDegrees,
                destination=word("destination") ?: previous.destination,lastMessageMs=now,
                positionTimeMs=if(validPosition) now else previous.positionTimeMs,messageType=type,
                kind=when(type) { 4,11 -> AisTargetKind.STATION;9 -> AisTargetKind.AIRCRAFT;21 -> AisTargetKind.AID
                    1,2,3,5,18,19,24,27 -> AisTargetKind.VESSEL;else -> previous.kind })
            val expired=now-15*60_000
            entries.entries.removeAll { it.value.lastMessageMs<expired }
            if(entries.size>1000) entries.remove(entries.values.minBy { it.lastMessageMs }.mmsi)
            true
        }.getOrDefault(false)
    }
    fun snapshot()=entries.values.toList()
}

class AisReceiver(private val context:Context):IqDecoderModule<List<AisVessel>> {
    companion object {
        // Upstream's ordinary dual-channel ModelFrontend admission range.
        val REQUIREMENTS=com.radiosport.ninegradio.modules.RadioModules.AIS_REQUIREMENTS
    }
    override val requirements=REQUIREMENTS
    private val table=AisVessels()
    private val mutable=MutableStateFlow<List<AisVessel>>(emptyList())
    private var dirty=false
    val vessels=mutable.asStateFlow()
    override val results=vessels
    private val pipe=NativeIqPipe(create={ config ->
        val executable=File(context.applicationInfo.nativeLibraryDir,"libninegradio_ais.so")
        check(executable.canExecute())
        ProcessBuilder(executable.path,"-r",if(config.format==IqSampleFormat.FLOAT_32) "CF32" else "CU8",".",
            "-s",config.sampleRate.toString(),"-c","AB","-o","5")
            .redirectError(File(context.cacheDir,"ais-decoder.log")).apply {
                environment()["LD_LIBRARY_PATH"]=context.applicationInfo.nativeLibraryDir
            }.start()
    },reset={ table.clear();dirty=false;mutable.value=emptyList() },line={ text ->
        table.accept(text,System.currentTimeMillis()).also { if(it) dirty=true }
    },flush={ if(dirty) { mutable.value=table.snapshot();dirty=false } })
    internal val diagnostics=pipe.status
    override val state=pipe.status.map { DecoderModuleState(it.running,it.error) }
    override val hasPendingRelease get()=pipe.hasPendingRelease
    override fun start(input:IqReadInput,configuration:IqDecoderConfiguration) {
        if(!requirements.accepts(configuration)) { pipe.refuse("AIS 需要 96–12288 kS/s 的原始 IQ");return }
        pipe.start(input,configuration)
    }
    override fun stop()=pipe.stop()
}
