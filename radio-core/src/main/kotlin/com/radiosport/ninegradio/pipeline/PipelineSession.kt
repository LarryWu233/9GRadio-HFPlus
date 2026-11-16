package com.radiosport.ninegradio.pipeline

data class ReceiverConfiguration(val mode: String, val sampleRate: Int,
                                 val bandwidthHz: Int, val audioRate: Int)
data class ReceiverSnapshot(val epoch: Long, val configuration: ReceiverConfiguration,
                            val acceptAfterNanos: Long, val closed: Boolean = false)

/** Configuration/lifetime barrier. Adapters own the platform resources. */
class PipelineSession(initial: ReceiverConfiguration, private val nanoTime: () -> Long = System::nanoTime) {
    @Volatile var snapshot = ReceiverSnapshot(1, initial, 0)
        private set
    @Synchronized fun configure(next: ReceiverConfiguration): ReceiverSnapshot {
        check(!snapshot.closed)
        if (next != snapshot.configuration) snapshot = ReceiverSnapshot(snapshot.epoch + 1, next, nanoTime())
        return snapshot
    }
    @Synchronized fun tuneBoundary() {
        if (!snapshot.closed) snapshot = snapshot.copy(acceptAfterNanos = nanoTime())
    }
    @Synchronized fun discontinuity() {
        if (!snapshot.closed) snapshot = snapshot.copy(epoch = snapshot.epoch + 1)
    }
    fun admits(meta: IqMetadata): Boolean {
        val now = snapshot
        return !now.closed && (meta.arrivalNanos == 0L || meta.arrivalNanos >= now.acceptAfterNanos)
    }
    @Synchronized fun close(): Boolean {
        if (snapshot.closed) return false
        snapshot = snapshot.copy(epoch = snapshot.epoch + 1, closed = true)
        return true
    }
}
