package com.radiosport.ninegradio.audio

/** Encoder/storage lifetime port; a timed-out worker must remain owned until it exits. */
interface SoundRecordingSink {
    val isRecording:Boolean
    val error:String?
    val isWorkerAlive:Boolean
    fun start()
    fun writeSamples(samples:FloatArray)
    fun abort(reason:String)
    fun stop()
    fun capturedDurationMs():Long
}
