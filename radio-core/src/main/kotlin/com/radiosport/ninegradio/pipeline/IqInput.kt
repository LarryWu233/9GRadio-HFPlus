package com.radiosport.ninegradio.pipeline

import com.radiosport.ninegradio.source.IqBlock
import kotlinx.coroutines.flow.Flow

/** Decoder-facing read port. A subscriber cannot stop or close shared production. */
interface IqReadInput {
    val iqFlow: Flow<IqBlock>
    val iqSubscriberCount: Int
}

/** Processing-facing producer port. Hardware controls live in the platform adapter. */
interface IqInput : IqReadInput,AutoCloseable {
    fun startStreaming(bufferSize: Int = 32_768)
    /** Returns only after the producer has stopped accessing its resources. */
    fun stopStreaming()
    override fun close()
}
