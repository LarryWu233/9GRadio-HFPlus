package com.radiosport.ninegradio.dsp

/** Platform processors may mutate only this branch's owned stereo buffer. */
interface ListeningPostProcessor : AutoCloseable {
    fun process(stereo:FloatArray,rate:Int,mode:DemodMode)
    fun reset()
    override fun close()
}

/** Serial monitoring only: decoder PCM is read-only; recording/output own later forks.
 * Reuses the reference NR, stereo and squelch stages in their original order.
 */
class MonitorAudioBranch(private val post:ListeningPostProcessor) : AutoCloseable {
    data class Settings(val listening:Boolean=true,val noiseReduction:Boolean=false,val volume:Float=1f)
    private val noise=AudioDsp.NoiseReducer()
    private val stereo=StereoAudioPath()
    private val squelch=ListeningSquelch()
    private val voiceFade=ListeningSquelch()
    private var closed=false

    fun setNoiseStrength(strength:Float) { check(!closed);noise.setStrength(strength);stereo.setStrength(strength) }
    fun updateSquelch(levelDb:Float,thresholdDb:Float,seconds:Double):Boolean {
        check(!closed)
        return squelch.update(levelDb,thresholdDb,seconds)
    }
    fun analog(demodulated:FloatArray,demodRate:Int,monoAtSinkRate:FloatArray,
               sinkRate:Int,mode:DemodMode,settings:Settings,open:Boolean):FloatArray {
        check(!closed)
        val allowed=NoiseProcessingPolicy.allows(mode)
        val isStereo=mode==DemodMode.WFM_STEREO
        var output=if(isStereo) stereo.process(demodulated,demodRate,sinkRate,settings.noiseReduction && allowed,false)
            else monoAtSinkRate.copyOf()
        if(!isStereo) {
            if(allowed) output=noise.processListening(output,settings.noiseReduction,sinkRate)
            output=StereoAudioPath.duplicateMono(output)
        }
        post.process(output,sinkRate,mode)
        squelch.apply(output,settings.listening && open,sinkRate)
        // Preserve the reference analog branch's gain ordering and threshold.
        if(settings.volume>1.001f) scale(output,settings.volume)
        return output
    }
    fun voice(monoAtSinkRate:FloatArray,sinkRate:Int,mode:DemodMode,
              settings:Settings,open:Boolean,discontinuity:Boolean):FloatArray {
        check(!closed)
        if(discontinuity) voiceFade.reset()
        val output=StereoAudioPath.duplicateMono(monoAtSinkRate)
        if(!settings.listening) output.fill(0f)
        post.process(output,sinkRate,mode)
        if(settings.volume!=1f) scale(output,settings.volume)
        voiceFade.apply(output,settings.listening && open,sinkRate)
        return output
    }
    private fun scale(samples:FloatArray,gain:Float) {
        for(i in samples.indices) samples[i]=(samples[i]*gain).coerceIn(-1f,1f)
    }
    fun reset() {
        check(!closed)
        noise.reset();stereo.reset();squelch.reset();voiceFade.reset();post.reset()
    }
    override fun close() {
        if(closed) return
        closed=true
        post.close()
    }
}
