package com.radiosport.ninegradio.dsp

/** Stateless JNI adapter; each core demodulator retains its own filter and phase state. */
object AndroidDemodPrimitives:DemodPrimitives {
    override fun amDetect(iq:FloatArray)=NativeDsp.amDetect(iq)
    override fun fmDemodulateStateful(iq:FloatArray,state:FloatArray,gainHz:Float)=NativeDsp.fmDemodulateStateful(iq,state,gainHz)
    override fun fmDemodulateStatefulInto(iq:FloatArray,state:FloatArray,gainHz:Float,out:FloatArray)=NativeDsp.fmDemodulateStatefulInto(iq,state,gainHz,out)
    override fun applyFirDual(iq:FloatArray,taps:FloatArray,bufI:FloatArray,bufQ:FloatArray,bufPos:IntArray)=NativeDsp.applyFirDual(iq,taps,bufI,bufQ,bufPos)
}
