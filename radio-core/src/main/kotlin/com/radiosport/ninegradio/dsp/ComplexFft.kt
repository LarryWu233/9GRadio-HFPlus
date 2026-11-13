package com.radiosport.ninegradio.dsp

/** Ordered, unnormalised, forward complex FFT. One serial owner per plan. */
interface ComplexFft : AutoCloseable {
    fun transform(real: DoubleArray, imaginary: DoubleArray)
    override fun close()
}

fun interface ComplexFftFactory { fun create(size: Int): ComplexFft }
