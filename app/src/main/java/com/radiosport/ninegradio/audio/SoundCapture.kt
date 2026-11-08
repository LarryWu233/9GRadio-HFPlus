package com.radiosport.ninegradio.audio

import com.radiosport.ninegradio.pipeline.AudioBlock
import com.radiosport.ninegradio.recording.AudioRecordingProfile

/** Recorder owns its encoder, queue and lifetime independently of the playback device. */
class SoundCapture(private val createSink:(String,Int,AudioRecordingProfile)->SoundRecordingSink =
    { path,rate,profile -> AudioFileRecorder(path,rate,2,profile) }) {
    private data class Active(val recorder: SoundRecordingSink, val rate: Int, val packets: PcmPacketizer)
    private val lifecycleLock=Any()
    @Volatile private var active: Active? = null
    @Volatile private var finishing: Active? = null
    @Volatile var error: String? = null
        private set
    @Volatile private var lastDuration = 0L
    val isRecording get() = active?.recorder?.isRecording == true

    fun start(path: String, rate: Int, profile: AudioRecordingProfile) = synchronized(lifecycleLock) {
        stop()
        if(finishing?.recorder?.isWorkerAlive==true) { error="录制文件仍在完成，请稍候";return@synchronized }
        error=null; lastDuration=0
        val recorder=createSink(path,rate,profile)
        val next=Active(recorder,rate,PcmPacketizer(rate))
        finishing=next
        try {
            recorder.start()
            synchronized(this) { active=next;finishing=null }
        } catch(e: Exception) {
            error=e.message ?: "无法开始录制"
            recorder.abort(error!!)
            if(!recorder.isWorkerAlive) finishing=null
        }
    }

    /** Short lock only; AudioFileRecorder.offer copies/enqueues and never encodes here. */
    @Synchronized fun offer(block: AudioBlock, allowed: Boolean) {
        val state=active ?: return
        if(!state.recorder.isRecording || !allowed) return
        if(block.sampleRate!=state.rate || block.channels!=2) { abort("录制音频格式发生变化");return }
        state.packets.append(block.samples) { state.recorder.writeSamples(it) }
        error=state.recorder.error ?: error
    }

    @Synchronized fun abort(reason: String) {
        error=reason;active?.recorder?.abort(reason)
    }

    fun stop():Unit = synchronized(lifecycleLock) {
        val old=synchronized(this) {
            val current=active ?: finishing; active=null;finishing=current
            if(current?.recorder?.isRecording==true) current.packets.flush { current.recorder.writeSamples(it) }
            current
        } ?: return@synchronized
        // Joining an encoder/storage worker must not hold the DSP-facing lock.
        runCatching { old.recorder.stop() }.onFailure { error=it.message ?: "录制未能完成" }
        lastDuration=old.recorder.capturedDurationMs()
        error=old.recorder.error ?: error
        if(!old.recorder.isWorkerAlive) finishing=null
    }

    fun durationMs()=active?.recorder?.capturedDurationMs() ?: finishing?.recorder?.capturedDurationMs() ?: lastDuration
}
