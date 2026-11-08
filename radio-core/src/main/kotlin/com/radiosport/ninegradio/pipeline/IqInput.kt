package com.radiosport.ninegradio.pipeline

import com.radiosport.ninegradio.source.IqBlock
import kotlinx.coroutines.flow.Flow

/** Processing-facing source port. Hardware controls live in the platform adapter. */
interface IqInput : AutoCloseable {
    val iqFlow: Flow<IqBlock>
    val iqSubscriberCount: Int
    fun startStreaming(bufferSize: Int = 32_768)
    /** Returns only after the producer has stopped accessing its resources. */
    fun stopStreaming()
    override fun close()
}
