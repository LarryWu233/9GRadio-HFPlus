package com.radiosport.ninegradio.pipeline

/** A selected decoder adapter owns delivery policy: live input must never wait for decoding. */
fun interface AudioDecoderInput {
    fun accept(block:AudioBlock)
    /** Nonblocking admission barrier. Reset native/stateful work on the decoder worker. */
    fun configure(epoch:Long) = Unit
}

/** Serial software audio publication clock. Positions count samples, not callback wall time. */
class AudioBlockClock {
    private var epoch=Long.MIN_VALUE
    private var rate=0
    private var channels=0
    private var position=0L
    @Synchronized fun publish(samples:FloatArray,rate:Int,channels:Int,epoch:Long):AudioBlock {
        require(rate>0 && channels>0 && samples.size%channels==0)
        if(this.epoch==epoch) require(this.rate==rate && this.channels==channels) { "Audio format changed without a configuration epoch" }
        else { this.epoch=epoch;this.rate=rate;this.channels=channels;position=0 }
        val block=AudioBlock(samples.copyOf(),rate,channels,epoch,position)
        position+=block.frameCount
        return block
    }
}
