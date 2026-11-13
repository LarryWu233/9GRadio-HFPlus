package com.radiosport.ninegradio.pipeline

import com.radiosport.ninegradio.dsp.FftEngine
import com.radiosport.ninegradio.source.IqBlock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicLong

data class SpectrumFrame(val metadata: IqMetadata, val bins: FloatArray)

/** Display-only analysis. The producer never waits for FFT or the UI. */
class SpectrumBranch(val engine: FftEngine, private val scope: CoroutineScope,
                     private val nanoTime: () -> Long = System::nanoTime,
                     private val onFrame: (SpectrumFrame) -> Unit) : AutoCloseable {
    val skipped = AtomicLong()
    @Volatile private var closing=false
    private val queue=Channel<IqBlock>(2,BufferOverflow.DROP_OLDEST,
        onUndeliveredElement={ if(!closing) skipped.incrementAndGet() })
    private var job: Job?=null
    fun start() {
        check(job==null)
        job=scope.launch {
            val continuity=IqContinuity()
            var lastPublishedNanos:Long?=null
            for(block in queue) {
                when(continuity.accept(block.metadata,block.sampleCount).change) {
                    IqContinuity.Change.STALE -> continue
                    IqContinuity.Change.GAP,IqContinuity.Change.RECONFIGURED -> engine.resetSpectrumAveraging()
                    else -> Unit
                }
                engine.analysisIntervalSamples=(block.metadata.sampleRate/60).coerceAtLeast(1)
                val before=engine.spectrumSequence
                val result=when(block) {
                    is IqBlock.Unsigned8 -> engine.processUint8(block.bytes)
                    is IqBlock.Float32 -> engine.processFloat(block.values)
                }
                if(engine.spectrumSequence!=before) {
                    val now=nanoTime()
                    // Preserve alpha28's 30 Hz display budget. Measurement keeps its
                    // independent sample clock; gestures still draw at screen cadence.
                    val previous=lastPublishedNanos
                    if(previous==null || now-previous>=1_000_000_000L/30) {
                        lastPublishedNanos=now
                        onFrame(SpectrumFrame(block.metadata,result.copyOf()))
                    }
                }
            }
        }
    }
    /** Only immutable, source-owned arrays may cross this boundary. */
    fun offer(block:IqBlock) { if(!closing) queue.trySend(block) }
    override fun close() {
        closing=true;queue.cancel()
        runBlocking { job?.cancelAndJoin() }
    }
}
