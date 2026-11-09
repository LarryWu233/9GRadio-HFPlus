package com.radiosport.ninegradio.dsp

/** Immutable channel request. Device capability calculation stays in the source adapter. */
data class ChannelConfiguration(val mode:DemodMode,val sourceRate:Int,val sinkRate:Int,
    val bandwidthHz:Int,val maximumBandwidthHz:Int,val deemphasisUs:Int=75)

/** Serial channel computation using the existing kernels. No USB, playback, recording or UI.
 * Scratch outputs are internal views valid until the next process call. Adapters must publish
 * owned blocks before asynchronous delivery, as the receiver's AudioBlockClock already does.
 */
class ChannelProcessor(private val primitives:DemodPrimitives=ReferenceDemodPrimitives,
                       private val resamplers:ComplexIqResamplerFactory=ReferenceIqResamplers):AutoCloseable {
    data class Output(val iq:FloatArray,val ifRate:Int,val demodulated:FloatArray,
        val mono:FloatArray,val decoderPcm:FloatArray,val sinkPcm:FloatArray,
        val rdsMultiplex:FloatArray?,val planChanged:Boolean)
    companion object {
        fun isNarrow(mode:DemodMode)=when(mode) {
            DemodMode.AM,DemodMode.NFM,DemodMode.USB,DemodMode.LSB,DemodMode.CW,DemodMode.CWR,
            DemodMode.DSB,DemodMode.APRS,DemodMode.ACARS,DemodMode.DMR,DemodMode.P25,DemodMode.NXDN,
            DemodMode.DSTAR,DemodMode.YSF,DemodMode.M17,DemodMode.DPMR,DemodMode.DIG -> true
            else -> false
        }
    }
    private var mode=DemodMode.NFM
    private var demodulator:Demodulator=NfmDemodulator(primitives)
    private val cw=CwChannelizer(primitives,resamplers)
    private var narrow:ComplexIqResampler?=null
    private var narrowRates=0 to 0
    private var wfm:ComplexIqResampler?=null
    private var wfmPlan=Triple(0,0,0)
    private var monoMix=FloatArray(0)
    private val decoder=AudioResampling()
    private val sink=AudioResampling()
    private var closed=false
    private class AudioResampling {
        private var rates=0 to 0
        private var filter:PolyphaseResampler?=null
        fun reset() { rates=0 to 0;filter=null }
        fun process(input:FloatArray,from:Int,to:Int):FloatArray {
            if(input.isEmpty()) return FloatArray(0)
            if(from==to) return input
            if(rates!=(from to to) || filter==null) {
                filter=PolyphaseResampler(from,to);rates=from to to
            }
            return filter!!.process(input)
        }
    }
    fun invalidateNarrow() { check(!closed);narrow?.close();narrow=null;narrowRates=0 to 0 }
    private fun invalidateWfm() { wfm?.close();wfm=null;wfmPlan=Triple(0,0,0) }
    fun outputRateChanged() { check(!closed);sink.reset();invalidateNarrow() }
    fun setMode(next:DemodMode) {
        check(!closed)
        if(next==mode) return
        mode=next;demodulator=DemodulatorFactory.create(next,primitives);cw.reset();invalidateNarrow()
        // Preserve the previous mode-switch invalidation policy, including FM.
        if(next!=DemodMode.WFM && next!=DemodMode.WFM_STEREO) invalidateWfm()
    }
    fun reset() {
        check(!closed)
        demodulator=DemodulatorFactory.create(mode,primitives);cw.reset()
        invalidateNarrow();invalidateWfm();decoder.reset();sink.reset()
    }
    override fun close() {
        if(closed) return
        invalidateNarrow();invalidateWfm();cw.close();closed=true
    }
    fun process(input:FloatArray,config:ChannelConfiguration):Output {
        check(!closed)
        require(config.sourceRate>0 && config.sinkRate>0 && input.size%2==0)
        setMode(config.mode)
        var changed=false
        val isWfm=mode==DemodMode.FM || mode==DemodMode.WFM || mode==DemodMode.WFM_STEREO
        val width=(config.bandwidthHz.takeIf { it>0 } ?: mode.defaultBwHz).coerceAtLeast(100)
        val rate:Int
        val iq:FloatArray
        when {
            mode==DemodMode.CW || mode==DemodMode.CWR -> {
                rate=ChannelRatePolicy.narrow(config.sourceRate,config.sinkRate,mode,config.bandwidthHz,width)
                iq=cw.process(input,config.sourceRate,rate,width)
            }
            isWfm -> {
                val requested=(config.bandwidthHz.takeIf { it>0 } ?: ChannelRatePolicy.WFM_TARGET_BW)
                    .coerceAtMost(config.maximumBandwidthHz)
                rate=ChannelRatePolicy.wfmIfRate(config.sourceRate,requested)
                val filterWidth=requested.coerceIn(50000,(rate*.9).toInt().coerceAtLeast(50000))
                val next=Triple(config.sourceRate,rate,filterWidth)
                if(wfm==null || wfmPlan!=next) {
                    val fresh=resamplers.create(config.sourceRate,rate,filterWidth/2.0)
                    wfm?.close();wfm=fresh;wfmPlan=next;changed=true
                }
                iq=wfm!!.process(input)
            }
            isNarrow(mode) -> {
                rate=ChannelRatePolicy.narrow(config.sourceRate,config.sinkRate,mode,config.bandwidthHz,width)
                val next=config.sourceRate to rate
                if(narrow==null || narrowRates!=next) {
                    val fresh=resamplers.create(config.sourceRate,rate,null)
                    narrow?.close();narrow=fresh;narrowRates=next;changed=true
                }
                iq=narrow!!.process(input)
            }
            else -> { rate=config.sourceRate;iq=input }
        }
        (demodulator as? WfmDemodulator)?.deemphasisUs=config.deemphasisUs
        val audio=demodulator.demodulate(iq,rate)
        val mono=if(mode==DemodMode.WFM_STEREO && audio.size>=2 && audio.size%2==0) {
            if(monoMix.size!=audio.size/2) monoMix=FloatArray(audio.size/2)
            for(i in monoMix.indices) monoMix[i]=(audio[i*2]+audio[i*2+1])*.5f
            monoMix
        } else audio
        val decoderPcm=decoder.process(mono,rate,48000)
        val sinkPcm=when {
            mono.isEmpty() -> FloatArray(0)
            rate==config.sinkRate -> mono
            config.sinkRate==48000 -> decoderPcm
            else -> sink.process(mono,rate,config.sinkRate)
        }
        return Output(iq,rate,audio,mono,decoderPcm,sinkPcm,(demodulator as? WfmDemodulator)?.multiplex,changed)
    }
}
