package com.radiosport.ninegradio.dsp

/** Serial selected-channel FIR port. Input is channel-owned scratch and may be
 * filtered in place; raw source IQ and asynchronous consumers must not alias it.
 * Width is the full symmetric RF width, including both sides of the carrier.
 */
interface ComplexChannelFilter:AutoCloseable {
    fun process(iq:FloatArray,rate:Int,widthHz:Int):FloatArray
    fun reset()
}
