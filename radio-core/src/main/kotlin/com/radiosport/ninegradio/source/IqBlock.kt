package com.radiosport.ninegradio.source

import com.radiosport.ninegradio.pipeline.IqMetadata

/** Published arrays are owned by this block and read-only for every consumer. */
sealed class IqBlock {
    abstract val sampleCount: Int
    abstract val metadata: IqMetadata

    class Unsigned8(val bytes: ByteArray, override val metadata: IqMetadata = IqMetadata()) : IqBlock() {
        init { require(bytes.size % 2 == 0) }
        override val sampleCount get() = bytes.size / 2
    }

    class Float32(val values: FloatArray, val sequence: Long = -1,
                  override val metadata: IqMetadata = IqMetadata(sequence = sequence)) : IqBlock() {
        init { require(values.size % 2 == 0) }
        override val sampleCount get() = values.size / 2
    }
}
