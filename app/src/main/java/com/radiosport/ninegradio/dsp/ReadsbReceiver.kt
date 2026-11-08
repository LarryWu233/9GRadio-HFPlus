package com.radiosport.ninegradio.dsp

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.gson.JsonParser
import com.radiosport.ninegradio.pipeline.IqContinuity
import com.radiosport.ninegradio.source.IqBlock
import com.radiosport.ninegradio.source.IqSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CopyOnWriteArrayList

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
    data class Status(val running: Boolean=false,val inputSamples: Long=0,val messages: Long=0,val error: String?=null,
        val inputMissingSamples:Long=0,val driverMissingSamples:Long=0,val decoderGeneration:Long=0)
    private val _status=MutableStateFlow(Status())
    val status=_status.asStateFlow()
    private val _aircraft=MutableStateFlow<List<AdsbDecoder.AdsbFrame>>(emptyList())
    val aircraft=_aircraft.asStateFlow()
    private val lifecycleLock=Any()
    private val publicationLock=Any()
    private val generations=AtomicLong()
    @Volatile private var session:Session?=null
    @Volatile private var finishing:Session?=null
    val hasPendingRelease get()=finishing!=null
    val pendingReleaseDetails get()=finishing?.releaseDetails() ?: "released"
    fun start(source: IqSource):Unit=synchronized(lifecycleLock) {
        if(_status.value.running && session?.source===source && session?.native?.child?.isAlive==true) return@synchronized
        stop()
        if(finishing!=null) return@synchronized
        if(source.getSampleRate()!=SAMPLE_RATE || source.capabilities.floatIq) {
            _status.value=Status(error="ADS-B 需要 2.400 MS/s 的原始 IQ")
            return@synchronized
        }
        try {
            val next=Session(source)
            synchronized(publicationLock) { session=next;_status.value=Status(running=true) }
            next.start()
        } catch(error: Exception) { stop(); _status.value=Status(error=error.message ?: "ADS-B 启动失败") }
    }
    fun stop():Unit=synchronized(lifecycleLock) {
        val old=synchronized(publicationLock) {
            val previous=session ?: finishing
            session=null;finishing=previous
            _aircraft.value=emptyList();_status.value=Status()
            previous
        }
        old?.cancel()
        if(old==null || old.awaitReleased(5000)) finishing=null
        else _status.value=Status(error="ADS-B 解码组件仍在关闭，请稍候")
    }

    private inner class Session(val source:IqSource) {
        private val lifetime=SupervisorJob()
        private val scope=CoroutineScope(lifetime+Dispatchers.IO)
        private val runs=CopyOnWriteArrayList<NativeRun>()
        private val continuity=IqContinuity()
        private val samples=AtomicLong()
        private val missing=AtomicLong()
        private val driverMissing=AtomicLong()
        @Volatile private var accepting=true
        @Volatile private var inputFinished=false
        @Volatile private var completedMessages=0L
        @Volatile var native:NativeRun?=null
            private set

        private fun publish(run:NativeRun,update:()->Unit) {
            synchronized(publicationLock) {
                if(session===this && accepting && native===run) update()
            }
        }
        fun start() {
            replaceNative()
            // ATOMIC installs the pipe's finally block even if Stop wins before
            // this IO worker is first dispatched. The body still runs on IO.
            scope.launch(start=CoroutineStart.ATOMIC) {
                try {
                    source.iqFlow.collect { block ->
                        ensureActive()
                        check(block is IqBlock.Unsigned8) { "readsb 输入格式发生变化" }
                        check(block.metadata.sampleRate==0 || block.metadata.sampleRate==SAMPLE_RATE) { "readsb 输入采样率发生变化" }
                        val observation=continuity.accept(block.metadata,block.sampleCount)
                        if(observation.change==IqContinuity.Change.STALE) return@collect
                        missing.addAndGet(observation.missingSamples)
                        driverMissing.addAndGet(observation.driverLostSamples)
                        if(observation.change in listOf(IqContinuity.Change.GAP,IqContinuity.Change.RECONFIGURED))
                            replaceNative()
                        val current=checkNotNull(native)
                        current.child.outputStream.write(block.bytes)
                        samples.addAndGet(block.sampleCount.toLong())
                    }
                    // EOF is a drain, not an error. The poller reads final JSON after exit.
                    inputFinished=true
                    native?.child?.outputStream?.apply { flush();close() }
                } catch(cancel:CancellationException) { throw cancel }
                catch(error:Exception) {
                    synchronized(publicationLock) {
                        if(session===this@Session && accepting) _status.value=_status.value.copy(
                            running=false,error="IQ 输入中断：${error.message}")
                        accepting=false
                    }
                    this@Session.cancel()
                } finally {
                    // EOF also survives a termination request made during native
                    // startup, before its signal/configuration state is ready.
                    runCatching { native?.closeInput() }
                }
            }
        }
        private fun replaceNative() {
            check(accepting && lifetime.isActive) { "ADS-B session closed" }
            native?.let { old ->
                // Invalidate its publications before waiting for native resources.
                synchronized(publicationLock) { native=null }
                old.closeInput() // only this writer owns the pipe; no in-flight write
                old.cancel()
                check(old.awaitReleased(3000)) { "ADS-B 解码组件未完成释放" }
                completedMessages+=old.messages
            }
            check(accepting && lifetime.isActive) { "ADS-B session closed" }
            val next=NativeRun().also { runs.add(it) }
            val accepted=synchronized(publicationLock) {
                if(session===this && accepting && lifetime.isActive) { native=next;true } else false
            }
            if(accepted) next.startReaders() else { next.closeInput();next.cancel();error("ADS-B session closed") }
        }
        fun cancel() {
            accepting=false;lifetime.cancel()
            runs.forEach { it.cancel() }
        }
        fun awaitReleased(timeoutMs:Long):Boolean {
            fun released()=lifetime.isCompleted && runs.all { it.fullyReleased }
            // isCompleted includes every child and its finally block. Inspect actual
            // quiescence within one deadline, without nested rounded timeout results.
            runBlocking { withTimeoutOrNull(timeoutMs.coerceAtLeast(1)) {
                while(!released()) delay(5)
            } }
            val complete=released()
            if(complete) runs.forEach { it.directory.deleteRecursively() }
            return complete
        }
        fun releaseDetails()="owner_completed=${lifetime.isCompleted}; children=${lifetime.children.count()}; native_alive=${runs.count { it.child.isAlive }}; observers=${runs.sumOf { it.pendingReaders() }}; subscribers=${source.iqSubscriberCount}"
        inner class NativeRun {
            val generation=generations.incrementAndGet()
            val directory=File(context.cacheDir,"readsb/session-${SystemClock.elapsedRealtimeNanos()}").apply { mkdirs() }
            private val log=File(directory,"native.log")
            val child:Process
            private val readers=CopyOnWriteArrayList<Job>()
            @Volatile var messages=0L
                private set
            init {
                val executable=File(context.applicationInfo.nativeLibraryDir,"libninegradio_readsb.so")
                check(executable.canExecute()) { "readsb 解码组件不可用" }
                val args=mutableListOf(executable.path,"--device-type","ifile","--ifile","-","--iformat","UC8",
                    "--quiet","--write-json",directory.path,"--write-json-every","0.5")
                val location=context.getSharedPreferences("radio_map",Context.MODE_PRIVATE)
                if(location.contains("receiverLat") && location.contains("receiverLon")) args+=listOf(
                    "--lat",location.getFloat("receiverLat",0f).toString(),"--lon",location.getFloat("receiverLon",0f).toString())
                child=ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log).start()
            }
            fun startReaders() {
                readers+=scope.launch {
                    val file=File(directory,"aircraft.json")
                    while(isActive) {
                        val alive=child.isAlive
                        publish(this@NativeRun) {
                            _status.value=_status.value.copy(inputSamples=samples.get(),
                                inputMissingSamples=missing.get(),driverMissingSamples=driverMissing.get(),decoderGeneration=generation)
                        }
                        if(file.isFile) try {
                            val snapshot=ReadsbSnapshotParser.parse(file.readText())
                            messages=snapshot.messages
                            publish(this@NativeRun) {
                                _aircraft.value=snapshot.aircraft
                                _status.value=Status(alive,samples.get(),completedMessages+messages,null,
                                    missing.get(),driverMissing.get(),generation)
                            }
                        } catch(error:Exception) { if(isActive) Log.w("Readsb","Waiting for complete aircraft snapshot",error) }
                        if(!alive) {
                            if(child.exitValue()!=0) runCatching { log.copyTo(File(context.cacheDir,"readsb-last-error.log"),true) }
                            publish(this@NativeRun) {
                                _status.value=_status.value.copy(running=false,inputSamples=samples.get(),
                                    error=if(inputFinished && child.exitValue()==0) null else "readsb 已停止（${child.exitValue()}）")
                            }
                            break
                        }
                        delay(500)
                    }
                }
            }
            fun cancel() {
                readers.forEach { it.cancel() }
                // Cancellation discards this generation. The native stdin adapter
                // has a bounded wait; Android does not guarantee SIGKILL here.
                child.destroyForcibly()
            }
            fun closeInput() { runCatching { child.outputStream.close() } }
            fun pendingReaders()=readers.count { !it.isCompleted }
            val fullyReleased get()=!child.isAlive && readers.all { it.isCompleted }
            fun awaitReleased(timeoutMs:Long):Boolean {
                runBlocking { withTimeoutOrNull(timeoutMs.coerceAtLeast(1)) {
                    while(!fullyReleased) delay(5)
                } }
                return fullyReleased
            }
        }
    }
}
