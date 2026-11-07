package com.radiosport.ninegradio.source

import com.radiosport.ninegradio.dsp.FftEngine

/** Arrays belong to the published block and must not be modified by consumers. */
sealed class IqBlock {
    abstract val sampleCount: Int
    class Unsigned8(val bytes: ByteArray) : IqBlock() {
        init { require(bytes.size % 2 == 0) }
        override val sampleCount get() = bytes.size / 2
    }
    class Float32(val values: FloatArray, val sequence: Long = -1) : IqBlock() {
        init { require(values.size % 2 == 0) }
        override val sampleCount get() = values.size / 2
    }
    fun spectrum(engine: FftEngine): FloatArray = when (this) {
        is Unsigned8 -> engine.processUint8(bytes)
        is Float32 -> engine.processFloat(values)
    }
}
