package com.radiosport.ninegradio.dsp

/** CW VFO adapter: staged decimation followed by channel FIR, before the audio pitch shifter.
 * Reuses the existing resampler and FIR kernels; no new filtering algorithm.
 */
class CwChannelizer(private val primitives:DemodPrimitives = ReferenceDemodPrimitives,
                    private val resamplers:ComplexIqResamplerFactory = ReferenceIqResamplers) : AutoCloseable {
    private var configuration=Triple(0,0,0)
    private val stages=mutableListOf<ComplexIqResampler>()
    private var closed=false
    private var taps=FloatArray(0)
    private var iHistory=FloatArray(0)
    private var qHistory=FloatArray(0)
    private val position=IntArray(1)
    fun reset() {
        check(!closed)
        stages.forEach { it.close() };stages.clear()
        configuration=Triple(0,0,0)
    }
    override fun close() {
        if(closed) return
        reset();closed=true
    }
    fun process(input: FloatArray,sourceRate: Int,ifRate: Int,width: Int): FloatArray {
        check(!closed)
        val next=Triple(sourceRate,ifRate,width)
        if(configuration!=next) {
            val fresh=mutableListOf<ComplexIqResampler>()
            val freshTaps:FloatArray
            try {
                var rate=sourceRate
                // Preserve the existing staged 2:1 anti-aliasing design.
                while(rate%2==0 && rate/2>=ifRate) {
                    fresh.add(resamplers.create(rate,rate/2,null));rate/=2
                }
                if(rate!=ifRate) fresh.add(resamplers.create(rate,ifRate,null))
                freshTaps=PolyphaseResampler(ifRate,ifRate,numTaps=127,cutoffHz=width/2.0).coefficientsForUnitRate()
            } catch(error:Throwable) {
                fresh.forEach { it.close() };throw error
            }
            stages.forEach { it.close() };stages.clear();stages.addAll(fresh)
            taps=freshTaps;configuration=next
            iHistory=FloatArray(taps.size); qHistory=FloatArray(taps.size); position[0]=0
        }
        var iq=input
        stages.forEach { iq=it.process(iq) }
        return primitives.applyFirDual(iq,taps,iHistory,qHistory,position)
    }
}
