package com.radiosport.ninegradio.dsp

/** Owns WDSP's native interpolating IQ NB. Call only with a DSP-owned copy. */
class IqNoiseBlanker : AutoCloseable {
    data class Stats(val active: Boolean,val latencySamples: Int,val processedSamples: Long,val repairedSamples: Long)
    private var handle=0L
    private var closed=false
    private var previousMode: DemodMode?=null
    private var previousRate=0
    private var previousFrequency=Long.MIN_VALUE
    private var resetPending=true
    private var processed=0L
    private var repaired=0L
    private var latency=0
    private var active=false
    @Synchronized fun process(iq: FloatArray,rate: Int,mode: DemodMode,frequency: Long,enabled: Boolean,threshold: Float=8f) {
        active=false
        if(closed || !NoiseProcessingPolicy.allows(mode)) { resetPending=true; return }
        if(!NativeDsp.isNativeAvailable() || rate !in 8000..10000000) return
        if(handle==0L) handle=nativeCreate()
        val reset=resetPending || previousMode!=mode || previousRate!=rate || previousFrequency!=frequency
        val fm=mode==DemodMode.FM || mode==DemodMode.WFM || mode==DemodMode.WFM_STEREO || mode==DemodMode.NFM
        repaired+=nativeProcess(handle,iq,rate,reset,enabled,fm,threshold.coerceIn(3f,20f))
        processed+=iq.size/2
        if(previousRate!=rate || previousMode!=mode) latency=nativeDelay(handle)
        active=enabled
        resetPending=false; previousMode=mode; previousRate=rate; previousFrequency=frequency
    }
    @Synchronized fun stats()=Stats(active,latency,processed,repaired)
    @Synchronized override fun close() {
        closed=true; active=false
        if(handle!=0L) nativeDestroy(handle)
        handle=0
    }
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeDelay(handle: Long): Int
    private external fun nativeProcess(handle: Long,iq: FloatArray,rate: Int,reset: Boolean,enabled: Boolean,fm: Boolean,threshold: Float): Int
}
