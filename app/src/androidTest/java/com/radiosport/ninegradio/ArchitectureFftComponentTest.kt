package com.radiosport.ninegradio

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.*

/** Component comparison only; no preferences, USB, UI or received content. */
@RunWith(AndroidJUnit4::class)
class ArchitectureFftComponentTest {
    private val native=ComplexFftFactory { PffftBackend(it) }
    private fun engine(n:Int,backend:ComplexFftFactory?)=FftEngine(n,backend).apply { smoothingAlpha=0f;frameAveragingCount=1 }
    private fun input(n:Int,amplitude:Double=.1,bin:Double=31.25)=FloatArray(n*2) {
        val phase=2*PI*bin*(it/2)/n
        (amplitude*if(it%2==0) cos(phase) else sin(phase)).toFloat()
    }
    @Test fun orderedFftMatchesReferenceAcrossSizesLevelsAndChunkBoundaries() {
        for(n in listOf(256,1024,2048,8192,65536)) for(amplitude in listOf(.5,.01,.0001)) {
            engine(n,null).use { reference -> engine(n,native).use { candidate ->
                val data=input(n,amplitude)
                val expected=reference.processFloat(data).copyOf()
                var offset=0
                while(offset<data.size) {
                    val end=minOf(data.size,offset+138)
                    candidate.processFloat(data.copyOfRange(offset,end));offset=end
                }
                val actual=candidate.processFloat(floatArrayOf()).copyOf()
                val peak=expected.indices.maxBy { expected[it] }
                assertEquals("Peak bin",peak,actual.indices.maxBy { actual[it] })
                assertEquals("Calibrated amplitude at n=$n",expected[peak],actual[peak],.3f)
                for(i in expected.indices) if(expected[i]>-85f)
                    assertEquals("Passband n=$n bin=$i",expected[i],actual[i],.3f)
            } }
        }
    }
    @Test fun plansResizeCloseAndRejectUseAfterRelease() {
        repeat(100) {
            engine(256,native).use { fft ->
                fft.processFloat(input(256));fft.setFftSize(2048);fft.processFloat(input(2048))
                fft.close();fft.close()
                try { fft.processFloat(input(256));fail("Closed plan accepted input") } catch(expected:IllegalStateException) { }
            }
        }
    }
    @Test fun fivePairedFullSpectrumMeasurements() {
        val results=mutableListOf<String>()
        for(n in listOf(256,2048,8192)) {
            val data=input(n)
            engine(n,null).use { reference -> engine(n,native).use { candidate ->
                repeat(200) { reference.processFloat(data);candidate.processFloat(data) }
                fun measure(fft:FftEngine):Long {
                    val begin=System.nanoTime();repeat(600) { fft.processFloat(data) }
                    return (System.nanoTime()-begin)/600
                }
                val old=mutableListOf<Long>();val next=mutableListOf<Long>()
                repeat(5) { trial ->
                    if(trial%2==0) { old+=measure(reference);next+=measure(candidate) }
                    else { next+=measure(candidate);old+=measure(reference) }
                }
                val gain=100.0*(old.sorted()[2]-next.sorted()[2])/old.sorted()[2]
                results+="n=$n reference_ns=$old pffft_ns=$next median_improvement_pct=$gain"
            } }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply { putString("stream","\nFFT comparison\n${results.joinToString("\n")}\n") })
    }
}
