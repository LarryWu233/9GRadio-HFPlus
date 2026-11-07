package com.radiosport.ninegradio.dsp

/** CW VFO adapter: staged decimation followed by channel FIR, before the audio pitch shifter.
 * Reuses the existing resampler and FIR kernels; no new filtering algorithm.
 */
internal class CwChannelizer {
    private var configuration=Triple(0,0,0)
    private val stages=mutableListOf<ComplexDecimator>()
    private var taps=FloatArray(0)
    private var iHistory=FloatArray(0)
    private var qHistory=FloatArray(0)
    private val position=IntArray(1)
    fun reset() { configuration=Triple(0,0,0) }
    fun process(input: FloatArray,sourceRate: Int,ifRate: Int,width: Int): FloatArray {
        val next=Triple(sourceRate,ifRate,width)
        if(configuration!=next) {
            configuration=next; stages.clear()
            var rate=sourceRate
            // Keep every integer decimation stage at 2:1; a narrow final channel must not alias
            // the whole source through one short FIR at an enormous decimation ratio.
            while(rate%2==0 && rate/2>=ifRate) {
                stages.add(ComplexDecimator(rate,rate/2)); rate/=2
            }
            if(rate!=ifRate) stages.add(ComplexDecimator(rate,ifRate))
            taps=PolyphaseResampler(ifRate,ifRate,numTaps=127,cutoffHz=width/2.0).coefficientsForUnitRate()
            iHistory=FloatArray(taps.size); qHistory=FloatArray(taps.size); position[0]=0
        }
        var iq=input
        stages.forEach { iq=it.process(iq) }
        return NativeDsp.applyFirDual(iq,taps,iHistory,qHistory,position)
    }
}
