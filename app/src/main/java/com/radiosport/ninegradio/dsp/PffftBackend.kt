package com.radiosport.ninegradio.dsp

/** JNI plan adapter only. Algorithm and licence are the baseline vendored PFFFT. */
class PffftBackend(private val size:Int) : ComplexFft {
    private var handle=PffftNative.create(size).also { check(it!=0L) { "Cannot allocate FFT plan" } }
    @Synchronized override fun transform(real:DoubleArray,imaginary:DoubleArray) {
        check(handle!=0L) { "FFT plan closed" }
        require(real.size==size && imaginary.size==size)
        PffftNative.transform(handle,real,imaginary)
    }
    @Synchronized override fun close() {
        if(handle!=0L) { PffftNative.destroy(handle);handle=0 }
    }
}

internal object PffftNative {
    init { System.loadLibrary("ninegradio_dsp") }
    external fun create(size:Int):Long
    external fun transform(handle:Long,real:DoubleArray,imaginary:DoubleArray)
    external fun destroy(handle:Long)
}
