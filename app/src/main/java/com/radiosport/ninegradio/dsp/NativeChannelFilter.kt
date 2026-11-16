package com.radiosport.ninegradio.dsp

/** Existing Blackman-Harris FIR design + vendored VOLK dot product, at channel rate.
 * Only delay-line/ownership adaptation; no new filter-design or convolution kernel.
 */
class NativeChannelFilter:ComplexChannelFilter {
    private var handle=0L
    private var configuration=0 to 0
    private var closed=false
    override fun process(iq:FloatArray,rate:Int,widthHz:Int):FloatArray {
        check(!closed);require(rate>0 && widthHz>0 && iq.size%2==0)
        val next=rate to widthHz
        if(handle==0L || configuration!=next) {
            check(NativeDsp.isNativeAvailable()) { "频道滤波原生模块未加载" }
            // Six full 16-float VOLK groups, without a scalar remainder.
            val taps=PolyphaseResampler(rate,rate,numTaps=96,cutoffHz=widthHz/2.0).coefficientsForUnitRate()
            val fresh=nativeCreate(taps);check(fresh!=0L)
            if(handle!=0L) nativeDestroy(handle)
            handle=fresh;configuration=next
        }
        if(iq.isNotEmpty()) nativeProcess(handle,iq)
        return iq
    }
    override fun reset() { if(handle!=0L) nativeDestroy(handle);handle=0L;configuration=0 to 0 }
    override fun close() { if(closed) return;reset();closed=true }
    private external fun nativeCreate(taps:FloatArray):Long
    private external fun nativeProcess(handle:Long,iq:FloatArray)
    private external fun nativeDestroy(handle:Long)
}
