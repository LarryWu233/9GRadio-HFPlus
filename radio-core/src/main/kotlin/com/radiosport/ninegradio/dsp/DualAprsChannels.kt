package com.radiosport.ninegradio.dsp

import com.radiosport.ninegradio.pipeline.AudioBlock
import com.radiosport.ninegradio.pipeline.AudioBlockClock
import java.util.concurrent.atomic.AtomicLong

/** Existing dual APRS channel math, independent of Android, maps and subscriptions. */
class DualAprsChannels(private val resamplers:ComplexIqResamplerFactory=ReferenceIqResamplers):AutoCloseable {
    companion object {
        const val NORTH_AMERICA_HZ=144390000L
        const val EUROPE_HZ=144800000L
        private const val DEFAULT_AUDIO_RATE=48000
        private val generations=AtomicLong()
    }
    data class Audio(val northAmerica:AudioBlock,val europe:AudioBlock)
    private data class Configuration(val rate:Int,val center:Long,val parentEpoch:Long)
    private var configuration:Configuration?=null
    private val admission=AtomicLong(generations.incrementAndGet())
    val currentGeneration:Long get()=admission.get()
    private var processingGeneration=0L
    private var parentEpoch=Long.MIN_VALUE
    private var closed=false
    private var first:Channel?=null
    private var second:Channel?=null
    private class Channel(val decimator:ComplexIqResampler,val audio:PolyphaseResampler) {
        val phase=doubleArrayOf(0.0)
        val previous=FloatArray(2)
        val clock=AudioBlockClock()
    }
    fun reset() {
        check(!closed)
        releasePlans();invalidate()
    }
    /** Nonblocking barrier; never frees a plan being processed on its owner. */
    fun invalidate() { admission.set(generations.incrementAndGet()) }
    private fun releasePlans() {
        first?.decimator?.close();second?.decimator?.close()
        first=null;second=null;configuration=null
    }
    override fun close() {
        if(closed) return
        reset();closed=true
    }
    fun process(iq:FloatArray,rate:Int,center:Long,parentEpoch:Long):Audio? {
        check(!closed)
        require(rate>0 && iq.size%2==0)
        if(parentEpoch<this.parentEpoch) return null
        if(iq.isEmpty()) return null
        val na=(NORTH_AMERICA_HZ-center).toDouble()
        val eu=(EUROPE_HZ-center).toDouble()
        if(center==0L || kotlin.math.abs(na)>=rate/2.0 || kotlin.math.abs(eu)>=rate/2.0) {
            if(configuration!=null) reset()
            return null
        }
        val next=Configuration(rate,center,parentEpoch)
        val ifRate=ChannelRatePolicy.narrowIfRateFor(rate,DEFAULT_AUDIO_RATE)
        if(configuration!=next || processingGeneration!=admission.get()) {
            val before=admission.get()
            var a:ComplexIqResampler?=null;var b:ComplexIqResampler?=null
            val freshA:Channel;val freshB:Channel
            try {
                a=resamplers.create(rate,ifRate,null);b=resamplers.create(rate,ifRate,null)
                freshA=Channel(a,PolyphaseResampler(ifRate,DEFAULT_AUDIO_RATE))
                freshB=Channel(b,PolyphaseResampler(ifRate,DEFAULT_AUDIO_RATE))
            } catch(error:Throwable) { a?.close();b?.close();throw error }
            val generation=generations.incrementAndGet()
            if(!admission.compareAndSet(before,generation)) {
                freshA.decimator.close();freshB.decimator.close();return null
            }
            releasePlans();first=freshA;second=freshB;configuration=next
            processingGeneration=generation;this.parentEpoch=parentEpoch
        }
        val generation=processingGeneration
        fun demod(channel:Channel,offset:Double):AudioBlock {
            val mixed=ncoMix(iq,rate,offset,channel.phase)
            val decimated=channel.decimator.process(mixed)
            val pcm=channel.audio.process(nfmDiscriminate(decimated,channel.previous,ifRate))
            return channel.clock.publish(pcm,DEFAULT_AUDIO_RATE,1,generation)
        }
        val audio=Audio(demod(first!!,na),demod(second!!,eu))
        return if(generation==admission.get()) audio else null
    }

    private fun ncoMix(iq: FloatArray, deviceRate: Int, offsetHz: Double,
                       phaseRef: DoubleArray): FloatArray {
        val n = iq.size / 2
        val out = FloatArray(iq.size)
        val step = -2.0 * Math.PI * offsetHz / deviceRate   // negate: shift signal down to 0 Hz
        // Phasor-recurrence: same optimisation as the main NCO path — 4 muls instead
        // of 2 transcendentals per sample.
        val stepRe = Math.cos(step)
        val stepIm = Math.sin(step)
        var pRe = Math.cos(phaseRef[0])
        var pIm = Math.sin(phaseRef[0])
        for (i in 0 until n) {
            val cosP = pRe.toFloat()
            val sinP = pIm.toFloat()
            val re = iq[2 * i]; val im = iq[2 * i + 1]
            out[2 * i]     = re * cosP - im * sinP
            out[2 * i + 1] = re * sinP + im * cosP
            val nextRe = pRe * stepRe - pIm * stepIm
            val nextIm = pRe * stepIm + pIm * stepRe
            pRe = nextRe; pIm = nextIm
        }
        val mag = Math.sqrt(pRe * pRe + pIm * pIm)
        if (mag > 0.0) { pRe /= mag; pIm /= mag }
        phaseRef[0] = Math.atan2(pIm, pRe)
        return out
    }

    private fun nfmDiscriminate(iq: FloatArray, prevState: FloatArray,
                                 ifRate: Int = DEFAULT_AUDIO_RATE): FloatArray {
        val n = iq.size / 2
        if(n==0) return FloatArray(0)
        val out = FloatArray(n)
        val gain = ifRate / (2f * Math.PI.toFloat() * 5_000f)
        val pi2 = (2.0 * Math.PI).toFloat()

        // prevState[0] = previous I, prevState[1] = previous Q
        var prevPhase = Math.atan2(prevState[1].toDouble(), prevState[0].toDouble()).toFloat()

        for (i in 0 until n) {
            val ci = iq[2 * i]; val cq = iq[2 * i + 1]
            val phase = Math.atan2(cq.toDouble(), ci.toDouble()).toFloat()
            var diff = phase - prevPhase
            // Phase unwrap to (−π, π]
            if (diff >  Math.PI)  diff -= pi2
            if (diff < -Math.PI)  diff += pi2
            out[i] = diff * gain
            prevPhase = phase
        }
        // Store last sample so phase is continuous across USB block boundaries
        val lastI = iq[(n - 1) * 2]
        val lastQ = iq[(n - 1) * 2 + 1]
        prevState[0] = lastI; prevState[1] = lastQ
        return out
    }
}
