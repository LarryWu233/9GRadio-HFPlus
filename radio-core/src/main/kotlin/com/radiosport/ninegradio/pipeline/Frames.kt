package com.radiosport.ninegradio.pipeline

import java.util.concurrent.atomic.AtomicLong

/** Arrival is a software monotonic timestamp, never an ADC timestamp. */
data class IqMetadata(
    val streamId: Long = 0,
    val epoch: Long = 0,
    val sequence: Long = -1,
    val firstSample: Long = -1,
    val sampleRate: Int = 0,
    val centerHz: Long = 0,
    val arrivalNanos: Long = 0,
    val driverLostSamples: Long = 0
)

/** One producer stamps data BEFORE a queue can discard it. */
class CaptureClock {
    companion object { private val ids = AtomicLong() }
    private var stream = ids.incrementAndGet()
    private var epoch = 0L
    private var sequence = 0L
    private var position = 0L
    private var rate = 0
    private var frequency = 0L

    @Synchronized fun restart() {
        stream = ids.incrementAndGet(); epoch = 0; sequence = 0; position = 0; rate = 0
    }

    @Synchronized fun stamp(count: Int, sampleRate: Int, centerHz: Long,
                            arrivalNanos: Long = System.nanoTime(), driverLostSamples: Long = 0): IqMetadata {
        require(count >= 0 && sampleRate > 0 && driverLostSamples >= 0)
        if (rate != sampleRate || frequency != centerHz) { epoch++; rate = sampleRate; frequency = centerHz }
        position += driverLostSamples
        return IqMetadata(stream, epoch, sequence++, position, rate, frequency, arrivalNanos, driverLostSamples)
            .also { position += count }
    }
}

data class AudioBlock(val samples: FloatArray, val sampleRate: Int, val channels: Int,
                      val epoch: Long, val firstFrame: Long) {
    init { require(sampleRate > 0 && channels > 0 && samples.size % channels == 0 && firstFrame >= 0) }
    val frameCount get() = samples.size / channels
}

/** Per consumer: a slow display is not counted as a decoder or driver failure. */
class IqContinuity {
    enum class Change { FIRST, CONTINUOUS, GAP, RECONFIGURED, STALE }
    data class Observation(val change: Change, val missingBlocks: Long = 0, val missingSamples: Long = 0)
    private var previous: IqMetadata? = null
    private var end = 0L
    @Synchronized fun reset() { previous = null; end = 0 }
    @Synchronized fun accept(meta: IqMetadata, count: Int): Observation {
        if (meta.sequence < 0) return Observation(Change.CONTINUOUS)
        val old = previous
        if (old != null && (meta.streamId < old.streamId || (meta.streamId == old.streamId &&
            (meta.epoch < old.epoch || meta.sequence <= old.sequence)))) return Observation(Change.STALE)
        val change = when {
            old == null -> Change.FIRST
            meta.streamId != old.streamId || meta.epoch != old.epoch -> Change.RECONFIGURED
            meta.sequence != old.sequence + 1 || (meta.firstSample >= 0 && meta.firstSample != end) || meta.driverLostSamples > 0 -> Change.GAP
            else -> Change.CONTINUOUS
        }
        val missingBlocks = if (change == Change.GAP) (meta.sequence - old!!.sequence - 1).coerceAtLeast(0) else 0
        val missingSamples = if (change == Change.GAP && meta.firstSample >= 0) (meta.firstSample - end).coerceAtLeast(0) else 0
        previous = meta; end = meta.firstSample + count
        return Observation(change, missingBlocks, missingSamples)
    }
}
