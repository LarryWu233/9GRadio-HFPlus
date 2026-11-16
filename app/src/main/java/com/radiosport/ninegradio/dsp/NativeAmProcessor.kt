package com.radiosport.ninegradio.dsp

/** Owns the pinned WDSP detector. No USB, UI, scheduling, recording or platform audio. */
class NativeAmProcessor:AmProcessor {
    private var handle=0L
    private var closed=false
    private val telemetry=DoubleArray(6)
    override val status get()=AmStatus(telemetry[0].toInt(),telemetry[1].toFloat(),telemetry[2].toFloat(),telemetry[3].toLong(),telemetry[4].toLong())
    override fun process(iq:FloatArray,rate:Int,rfWidth:Int,settings:AmSettings,signalDb:Float):FloatArray {
        check(!closed)
        if(handle==0L) { check(NativeDsp.isNativeAvailable()) { "AM 原生模块未加载" };handle=nativeCreate() }
        val output=FloatArray(iq.size/2)
        val cutoff=settings.cutoffHz(rfWidth,rate).toDouble()
        nativeProcess(handle,iq,output,rate,settings.detector.ordinal,cutoff,settings.agc.ordinal,
            settings.maxGainDb.coerceIn(0f,60f),signalDb>=settings.lowLevelDb,telemetry)
        return output
    }
    override fun reset() { if(handle!=0L) nativeDestroy(handle);handle=0L;telemetry.fill(0.0) }
    override fun close() { reset();closed=true }
    private external fun nativeCreate():Long
    private external fun nativeDestroy(handle:Long)
    private external fun nativeProcess(handle:Long,iq:FloatArray,out:FloatArray,rate:Int,mode:Int,
        cutoff:Double,agc:Int,maxGain:Float,allowGainIncrease:Boolean,telemetry:DoubleArray)
}
