package com.radiosport.ninegradio.pipeline

import com.radiosport.ninegradio.dsp.DemodMode

/** Exact capture-centre tuning for scan analysis, dispatched by the session controller. */
fun interface CaptureTuner { suspend fun tune(centerHz:Long):Boolean }

/** Software arrival boundary plus capture metadata; not a hardware settling timestamp. */
data class CaptureSelection(val centerHz:Long,val afterNanos:Long) {
    fun accepts(metadata:IqMetadata)=metadata.streamId==0L ||
        (metadata.centerHz==centerHz && metadata.arrivalNanos>=afterNanos)
}

data class ReceiverControls(val mode:DemodMode,val dialHz:Long,val bandwidthHz:Int,
    val squelchDb:Float,val volume:Float,val listening:Boolean,val dcBlock:Boolean,
    val frequencyOffsetHz:Int,val deemphasisUs:Int)
