package com.radiosport.ninegradio.dsp

/** Internal serial processing buffer; output is valid until the next call, not for publication. */
interface ComplexIqResampler : AutoCloseable {
    fun process(iq:FloatArray):FloatArray
    fun reset()
    override fun close() = Unit
}

fun interface ComplexIqResamplerFactory {
    fun create(inputRate:Int,outputRate:Int,cutoffHz:Double?):ComplexIqResampler
}

object ReferenceIqResamplers : ComplexIqResamplerFactory {
    override fun create(inputRate:Int,outputRate:Int,cutoffHz:Double?) =
        ComplexDecimator(inputRate,outputRate,cutoffHz=cutoffHz)
}
