package com.radiosport.ninegradio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.radiosport.ninegradio.dsp.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class IqNoiseBlankerTest {
    private data class Result(val iq: FloatArray,val stats: IqNoiseBlanker.Stats)
    private fun clean(rate: Int,seconds: Double)=FloatArray((rate*seconds).toInt()*2) { index ->
        val n=index/2
        val magnitude=.01*(1+.4*sin(2*PI*900*n/rate))
        (magnitude*if(index%2==0) cos(2*PI*2300*n/rate) else sin(2*PI*2300*n/rate)).toFloat()
    }
    private fun run(input: FloatArray,rate: Int,block: Int=4096,enabled: Boolean=true,toggle: Boolean=false): Result {
        val output=FloatArray(input.size)
        IqNoiseBlanker().use { nb ->
            var start=0
            while(start<input.size) {
                val part=input.copyOfRange(start,min(input.size,start+2*block))
                nb.process(part,rate,DemodMode.AM,11916000,if(toggle) (start/(2*block))%2==0 else enabled)
                part.copyInto(output,start); start+=part.size
            }
            return Result(output,nb.stats())
        }
    }
    private fun error(output: FloatArray,reference: FloatArray,delay: Int,rate: Int): Double {
        var sum=0.0; var count=0
        for(i in max(rate/5,delay) until output.size/2) for(channel in 0..1) {
            sum+=(output[2*i+channel]-reference[2*(i-delay)+channel]).toDouble().pow(2); count++
        }
        return sum/count
    }
    @Test fun repairsImpulsesWithoutDeletingIqSamples() {
        val rate=912000; val clean=clean(rate,.8); val damaged=clean.copyOf()
        for(start in rate/3 until damaged.size/2 step rate/50) {
            for(n in start until min(start+rate/20000,damaged.size/2)) {
                damaged[2*n]+=.7f; damaged[2*n+1]-=.6f
            }
        }
        val result=run(damaged,rate)
        val before=run(damaged,rate,enabled=false)
        val ratio=10*log10(error(result.iq,clean,result.stats.latencySamples,rate)/error(before.iq,clean,before.stats.latencySamples,rate))
        assertTrue("Impulse error change $ratio dB",ratio< -15)
        assertEquals(damaged.size,result.iq.size)
        assertEquals(damaged.size/2L,result.stats.processedSamples)
        assertTrue(result.stats.repairedSamples>0)
        assertTrue(result.stats.repairedSamples<result.stats.processedSamples/10)
        assertTrue(result.iq.all { it.isFinite() })
        println("IQ_NB impulseErrorChangeDb=$ratio delaySamples=${result.stats.latencySamples} changed=${result.stats.repairedSamples}")
        java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"alpha12-iq-nb-sample.json")
            .writeText(com.google.gson.Gson().toJson(mapOf("rate" to rate,"pulseWidthSamples" to rate/20000,
                "pulsePeriodSamples" to rate/50,"iqErrorChangeDb" to ratio,"stats" to result.stats)))
    }
    @Test fun cleanAmAndOffOnTransitionsPreserveAmplitudeAndPhase() {
        val rate=768000; val source=clean(rate,.3)
        val enabled=run(source,rate,toggle=true)
        val disabled=run(source,rate,enabled=false)
        assertArrayEquals(disabled.iq,enabled.iq,1e-7f)
        assertEquals(0L,enabled.stats.repairedSamples)
        assertEquals(0.0,error(enabled.iq,source,enabled.stats.latencySamples,rate),1e-15)
    }
    @Test fun blockSplitsAndRateChangesKeepEverySample() {
        for(rate in listOf(228000,912000,3200000)) {
            val source=clean(rate,.25)
            val a=run(source,rate,block=4096)
            val b=run(source,rate,block=137)
            assertArrayEquals("rate $rate",a.iq,b.iq,1e-7f)
            assertTrue(a.stats.latencySamples.toDouble()/rate in .002.. .003)
        }
        IqNoiseBlanker().use { nb ->
            for(rate in listOf(912000,228000,768000)) {
                val values=clean(rate,.05)
                nb.process(values,rate,DemodMode.USB,7100000,true)
                assertTrue(nb.stats().latencySamples.toDouble()/rate in .002.. .003)
                assertTrue(values.all { it.isFinite() })
            }
        }
    }
    @Test fun digitalAndToneModesBypassAllNoiseProcessingImmediately() {
        val input=clean(48000,.1)
        IqNoiseBlanker().use { nb ->
            ListeningAudioProcessor().use { audio ->
                audio.settings=ListeningAudioProcessor.Settings(leveling=true,notch=true)
                nb.process(input.copyOf(),48000,DemodMode.AM,11916000,true)
                audio.process(input.copyOf(),48000,DemodMode.AM)
                for(mode in DemodMode.values().filterNot { NoiseProcessingPolicy.allows(it) }) {
                    val iq=input.copyOf(); nb.process(iq,48000,mode,11916000,true)
                    assertArrayEquals(mode.name,input,iq,0f)
                    val pcm=input.copyOf(); audio.process(pcm,48000,mode)
                    assertArrayEquals(mode.name,input,pcm,0f)
                    assertFalse(nb.stats().active)
                }
            }
        }
    }
    @Test fun longBurstRecoversAndResetDoesNotLeakOldSignal() {
        val rate=228000
        val source=clean(rate,.6)
        for(n in rate/3 until rate/3+rate/50) { source[2*n]=.8f; source[2*n+1]=-.7f }
        val result=run(source,rate)
        assertTrue(result.iq.all { it.isFinite() && abs(it)<2 })
        val start=rate/2
        assertTrue(result.iq.sliceArray(2*start until result.iq.size).any { abs(it)>.005 })
        IqNoiseBlanker().use { nb ->
            nb.process(clean(rate,.1),rate,DemodMode.AM,11916000,true)
            val zeros=FloatArray(rate/100*2)
            nb.process(zeros,rate,DemodMode.AM,11917000,true)
            assertTrue(zeros.all { it==0f })
        }
    }
    @Test fun fmAmplitudeBlankerSuppressesSpikesWithoutChangingIqPhase() {
        val rate=228000; val input=clean(rate,.5)
        for(start in rate/4 until input.size/2 step rate/50) {
            for(n in start until minOf(start+12,input.size/2)) { input[2*n]*=50; input[2*n+1]*=50 }
        }
        val expected=input.copyOf()
        IqNoiseBlanker().use { nb ->
            nb.process(input,rate,DemodMode.WFM,87800000,true)
            assertEquals(0,nb.stats().latencySamples)
            assertTrue(nb.stats().repairedSamples>0)
            for(i in input.indices step 2) {
                val phaseDifference=atan2(input[i]*expected[i+1]-input[i+1]*expected[i],
                    input[i]*expected[i]+input[i+1]*expected[i+1])
                assertEquals(0f,phaseDifference,1e-6f)
            }
            assertTrue(input.maxOf { abs(it) }<expected.maxOf { abs(it) }/5)
        }
    }
}
