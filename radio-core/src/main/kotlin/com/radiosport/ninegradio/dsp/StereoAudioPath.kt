package com.radiosport.ninegradio.dsp

/** Independent resampler and listening filters for each channel. */
class StereoAudioPath {
    private var inputRate=0; private var outputRate=0
    private var leftResampler: PolyphaseResampler?=null
    private var rightResampler: PolyphaseResampler?=null
    private val leftNr=AudioDsp.NoiseReducer(); private val rightNr=AudioDsp.NoiseReducer()
    fun reset() {
        inputRate=0; outputRate=0; leftResampler=null; rightResampler=null
        leftNr.reset(); rightNr.reset()
    }
    fun setStrength(value: Float) { leftNr.setStrength(value); rightNr.setStrength(value) }
    fun process(interleaved: FloatArray, fromRate: Int, toRate: Int, nr: Boolean, nb: Boolean): FloatArray {
        if (fromRate!=inputRate || toRate!=outputRate) {
            inputRate=fromRate; outputRate=toRate
            leftResampler=if(fromRate==toRate) null else PolyphaseResampler(fromRate,toRate)
            rightResampler=if(fromRate==toRate) null else PolyphaseResampler(fromRate,toRate)
        }
        val n=interleaved.size/2
        var left=FloatArray(n) { interleaved[it*2] }
        var right=FloatArray(n) { interleaved[it*2+1] }
        left=leftResampler?.process(left)?:left; right=rightResampler?.process(right)?:right
        // NB belongs to pre-demod IQ, never to L/R listening audio.
        left=leftNr.processListening(left,nr,toRate); right=rightNr.processListening(right,nr,toRate)
        val size=minOf(left.size,right.size)
        return FloatArray(size*2) { if(it%2==0) left[it/2] else right[it/2] }
    }
    companion object {
        fun duplicateMono(samples: FloatArray)=FloatArray(samples.size*2) { samples[it/2] }
    }
}
