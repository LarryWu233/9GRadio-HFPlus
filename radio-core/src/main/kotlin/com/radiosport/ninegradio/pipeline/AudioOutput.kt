package com.radiosport.ninegradio.pipeline

data class PlaybackClock(val writtenFrames: Long = 0, val playbackHeadFrames: Long = 0,
                         val timestampFrames: Long = -1, val timestampNanos: Long = -1) {
    fun asLegacyMap() = mapOf("writtenFrames" to writtenFrames, "playbackHeadFrames" to playbackHeadFrames,
        "timestampFrames" to timestampFrames, "timestampNanos" to timestampNanos)
}

/** Platform sink. Recording and DSP are deliberately absent from this interface. */
interface AudioOutput : AutoCloseable {
    val sampleRate: Int
    val underruns: Int
    fun start()
    fun configureEpoch(epoch:Long) = Unit
    fun setVolume(volume: Float)
    fun write(block: AudioBlock)
    fun finishInput() = Unit
    fun interruptWrites()
    fun clearPendingAudio()
    fun playbackClock(): PlaybackClock
    fun stop()
    override fun close() = stop()
}

fun interface AudioOutputFactory { fun create(sampleRate: Int): AudioOutput }
