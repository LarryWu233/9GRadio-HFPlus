package com.radiosport.ninegradio.audio

/** Fixed duration packets give the playback queue a rate independent time budget. */
class PcmPacketizer(sampleRate: Int, private val channels: Int = 2, packetMs: Int = 20) {
    private val size=(sampleRate*packetMs/1000).coerceAtLeast(1)*channels
    private var buffer=FloatArray(size)
    private var used=0
    fun append(input: FloatArray, emit: (FloatArray)->Unit) {
        require(input.size%channels==0)
        var offset=0
        while(offset<input.size) {
            val count=minOf(size-used,input.size-offset)
            input.copyInto(buffer,used,offset,offset+count)
            used+=count; offset+=count
            if(used==size) { val packet=buffer; buffer=FloatArray(size); used=0; emit(packet) }
        }
    }
    fun flush(emit: (FloatArray)->Unit) {
        if(used>0) { val tail=buffer.copyOf(used); reset(); emit(tail) }
    }
    fun reset() { used=0; buffer=FloatArray(size) }
}
