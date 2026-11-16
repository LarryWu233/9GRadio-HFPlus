package com.radiosport.ninegradio.dsp

import android.os.SystemClock
import com.radiosport.ninegradio.pipeline.*
import com.radiosport.ninegradio.source.IqBlock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Android stdin/stdout glue for owned native decoder processes. No USB, UI or DSP kernels.
 * Follows readsb's joined-stop protocol: retain ownership after an incomplete stop.
 */
internal class NativeIqPipe(private val create:(IqDecoderConfiguration)->Process,
    private val reset:()->Unit,private val line:(String)->Boolean,private val flush:()->Unit={}) {
    data class Status(val running:Boolean=false,val samples:Long=0,val messages:Long=0,
        val missingSamples:Long=0,val driverLostSamples:Long=0,val error:String?=null)
    private val mutable=MutableStateFlow(Status());val status=mutable.asStateFlow()
    private val lifecycleLock=Any();private val publicationLock=Any()
    private var session:Session?=null
    @Volatile private var finishing:Session?=null
    val hasPendingRelease get()=finishing!=null
    fun refuse(message:String):Unit=synchronized(lifecycleLock) {
        stop();if(finishing==null) mutable.value=Status(error=message)
    }
    fun start(input:IqReadInput,config:IqDecoderConfiguration):Unit=synchronized(lifecycleLock) {
        if(session?.let { it.source===input && it.config==config && it.native?.child?.isAlive==true }==true) return@synchronized
        stop()
        if(finishing!=null) return@synchronized
        try {
            val next=Session(input,config)
            synchronized(publicationLock) { reset();session=next;mutable.value=Status(running=true) }
            next.start()
        } catch(error:Exception) { stop();mutable.value=mutable.value.copy(running=false,error="解码组件启动失败") }
    }
    fun stop():Unit=synchronized(lifecycleLock) {
        val old=synchronized(publicationLock) {
            val value=session ?: finishing;session=null;finishing=value;reset();mutable.value=Status();value
        }
        old?.cancel()
        if(old==null || old.awaitReleased(5000)) finishing=null
        else mutable.value=Status(error="解码组件仍在关闭，请稍候")
    }
    private inner class Session(val source:IqReadInput,val config:IqDecoderConfiguration) {
        val job=SupervisorJob();private val scope=CoroutineScope(job+Dispatchers.IO)
        private val runs=CopyOnWriteArrayList<Run>()
        private val continuity=IqContinuity()
        private val samples=AtomicLong();private val messages=AtomicLong()
        private val missing=AtomicLong();private val driver=AtomicLong()
        @Volatile private var accepting=true
        @Volatile private var inputFinished=false
        @Volatile var native:Run?=null;private set
        private fun publish(run:Run,action:()->Unit) = synchronized(publicationLock) {
            if(session===this && accepting && native===run) action()
        }
        private fun update(run:Run,running:Boolean=true,error:String?=null) { publish(run) {
            flush()
            mutable.value=Status(running,samples.get(),messages.get(),missing.get(),driver.get(),error)
        } }
        private fun fail(run:Run?,message:String) {
            val current=synchronized(publicationLock) {
                if(session===this && accepting && job.isActive && (run==null || native===run)) {
                    flush();mutable.value=Status(false,samples.get(),messages.get(),missing.get(),driver.get(),message);true
                } else false
            }
            // A retired reader's close/kill exception must not cancel its successor.
            if(current) cancel()
        }
        fun start() {
            replace()
            // Enrol before the owner starts capture. The first receive suspends;
            // subsequent native writes run on IO. Finally is installed immediately.
            scope.launch(start=CoroutineStart.UNDISPATCHED) {
                var bytes=ByteArray(0);var floats=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                var lastStatus=0L
                var first=true
                try {
                    source.iqFlow.collect { block ->
                        // Even a synchronous replay Flow must return start() before
                        // pipe I/O. Yield once; hot sources are already subscribed.
                        if(first) { first=false;yield() }
                        ensureActive()
                        check(block.metadata.sampleRate==0 || block.metadata.sampleRate==config.sampleRate)
                        check((block is IqBlock.Float32)==(config.format==IqSampleFormat.FLOAT_32))
                        val observation=continuity.accept(block.metadata,block.sampleCount)
                        if(observation.change==IqContinuity.Change.STALE) return@collect
                        missing.addAndGet(observation.missingSamples);driver.addAndGet(observation.driverLostSamples)
                        if(observation.change in listOf(IqContinuity.Change.GAP,IqContinuity.Change.RECONFIGURED)) replace()
                        val current=checkNotNull(native)
                        when(block) {
                            is IqBlock.Unsigned8 -> current.child.outputStream.write(block.bytes)
                            is IqBlock.Float32 -> {
                                val length=block.sampleCount*8
                                if(bytes.size<length) {
                                    bytes=ByteArray(length);floats=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                                }
                                floats.position(0);floats.put(block.values)
                                current.child.outputStream.write(bytes,0,length)
                            }
                        }
                        samples.addAndGet(block.sampleCount.toLong())
                        val now=SystemClock.elapsedRealtime()
                        if(now-lastStatus>=250) { lastStatus=now;update(current) }
                    }
                    inputFinished=true;native?.child?.outputStream?.apply { flush();close() }
                } catch(cancel:CancellationException) { throw cancel }
                catch(error:Exception) { fail(null,"IQ 输入中断") }
                finally { runCatching { native?.child?.outputStream?.close() } }
            }
        }
        private fun replace() {
            check(accepting && job.isActive)
            native?.let { old ->
                synchronized(publicationLock) { native=null;reset() }
                old.cancel();check(old.awaitReleased(3000)) { "Decoder has not released" }
                runs.remove(old)
            }
            val next=Run(create(config)).also { runs+=it }
            synchronized(publicationLock) { check(session===this && accepting && job.isActive);native=next }
            next.start()
        }
        fun cancel() { accepting=false;job.cancel();runs.forEach { it.cancel() } }
        fun awaitReleased(timeout:Long):Boolean {
            fun done()=job.isCompleted && runs.all { it.released }
            runBlocking { withTimeoutOrNull(timeout) { while(!done()) delay(5) } }
            return done()
        }
        inner class Run(val child:Process) {
            private var reader:Job?=null
            fun start() { reader=scope.launch(start=CoroutineStart.ATOMIC) {
                try {
                    child.inputStream.bufferedReader().use { input ->
                        while(isActive) {
                            val text=input.readLine() ?: break
                            publish(this@Run) { if(line(text)) messages.incrementAndGet() }
                        }
                    }
                    val exit=child.waitFor()
                    update(this@Run,false,if(inputFinished && exit==0) null else "解码组件已停止（$exit）")
                } catch(cancel:CancellationException) { throw cancel }
                catch(error:Exception) { fail(this@Run,"解码结果读取失败") }
            } }
            fun cancel() { reader?.cancel();child.destroyForcibly() }
            val released get()=!child.isAlive && reader?.isCompleted!=false
            fun awaitReleased(timeout:Long):Boolean {
                runBlocking { withTimeoutOrNull(timeout) { while(!released) delay(5) } };return released
            }
        }
    }
}
