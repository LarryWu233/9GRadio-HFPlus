package com.radiosport.ninegradio.audio

import com.radiosport.ninegradio.pipeline.AudioBlock
import com.radiosport.ninegradio.recording.AudioRecordingProfile

/** Recorder owns its encoder, queue and lifetime independently of the playback device. */
class SoundCapture {
    private data class Active(val recorder: AudioFileRecorder, val rate: Int, val packets: PcmPacketizer)
    @Volatile private var active: Active? = null
    @Volatile var error: String? = null
        private set
    @Volatile private var lastDuration = 0L
    val isRecording get() = active?.recorder?.isRecording == true

    fun start(path: String, rate: Int, profile: AudioRecordingProfile) {
        stop(); error=null; lastDuration=0
        val recorder=AudioFileRecorder(path,rate,2,profile)
        try {
            recorder.start()
            synchronized(this) { active=Active(recorder,rate,PcmPacketizer(rate)) }
        } catch(e: Exception) { error=e.message ?: "无法开始录制" }
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

    fun stop() {
        val old=synchronized(this) {
            val current=active; active=null
            if(current?.recorder?.isRecording==true) current.packets.flush { current.recorder.writeSamples(it) }
            current
        } ?: return
        // Joining an encoder/storage worker must not hold the DSP-facing lock.
        runCatching { old.recorder.stop() }.onFailure { error=it.message ?: "录制未能完成" }
        lastDuration=old.recorder.capturedDurationMs()
        error=old.recorder.error ?: error
    }

    fun durationMs()=active?.recorder?.capturedDurationMs() ?: lastDuration
}
