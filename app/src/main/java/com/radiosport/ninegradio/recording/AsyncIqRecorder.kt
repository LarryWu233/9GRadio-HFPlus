package com.radiosport.ninegradio.recording

import com.radiosport.ninegradio.source.IqBlock
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Platform storage adapter. Published blocks own their arrays; the writer never sees DSP scratch. */
class AsyncIqRecorder(private val createSink: () -> IqRecordingSink = { IqRecorder() }) {
    private class Session(val sink: IqRecordingSink) {
        val queue=ArrayBlockingQueue<IqBlock>(64)
        @Volatile var accepting=true
        @Volatile var duration=0L
        var captureKey: Triple<Long,Int,Long>?=null
        lateinit var thread: Thread
    }
    @Volatile private var active: Session?=null
    @Volatile private var finishing: Session?=null
    @Volatile private var lastDuration=0L
    @Volatile var error: String?=null
        private set
    val isRecording get()=active?.let { it.accepting && it.sink.isRecording } == true

    fun start(path:String,rate:Int,format:IqRecorder.Format) {
        stop()
        check(finishing?.thread?.isAlive!=true) { "IQ 录制文件仍在完成" }
        error=null;lastDuration=0
        val state=Session(createSink());val ready=CompletableFuture<Unit>()
        state.thread=Thread({
            try {
                state.sink.start(path,rate,format)
                check(state.sink.isRecording) { "无法打开 IQ 录制文件" }
                ready.complete(Unit)
                while(state.accepting || state.queue.isNotEmpty()) {
                    val block=state.queue.poll(40,TimeUnit.MILLISECONDS) ?: continue
                    state.sink.write(block)
                    state.duration=state.sink.capturedDurationMs()
                    if(!state.sink.isRecording) throw IOException("IQ 写入失败")
                }
            } catch(t:Exception) {
                error="IQ 录制失败（${t.javaClass.simpleName}）";state.accepting=false
                ready.completeExceptionally(t)
            } finally {
                state.sink.stop();state.duration=state.sink.capturedDurationMs();state.accepting=false
            }
        },"radio-iq-recorder").apply { start() }
        try { ready.get(8,TimeUnit.SECONDS);active=state }
        catch(t:Exception) { state.accepting=false;finishing=state;error="无法开始 IQ 录制" }
    }

    @Synchronized fun write(block:IqBlock) {
        val state=active ?: return
        if(!state.accepting) return
        val key=Triple(block.metadata.streamId,block.metadata.sampleRate,block.metadata.centerHz)
        if(state.captureKey==null) state.captureKey=key
        else if(state.captureKey!=key) { abort("IQ 采样配置已改变，录制已停止");return }
        if(!state.queue.offer(block)) abort("IQ 存储未能跟上，录制已停止")
    }

    @Synchronized fun abort(reason:String) { error=reason;active?.accepting=false }

    fun stop() {
        val old=synchronized(this) {
            val state=active ?: finishing
            active=null;state?.accepting=false;finishing=state
            state
        } ?: return
        old.thread.join(15000)
        lastDuration=old.duration
        if(old.thread.isAlive) { error="IQ 录制文件仍在完成";return }
        finishing=null
    }

    fun capturedDurationMs()=active?.duration ?: finishing?.duration ?: lastDuration
}
