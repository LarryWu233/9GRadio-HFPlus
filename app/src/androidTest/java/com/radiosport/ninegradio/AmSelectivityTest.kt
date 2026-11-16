package com.radiosport.ninegradio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.radiosport.ninegradio.dsp.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class AmSelectivityTest {
    private fun amplitude(samples:FloatArray,hz:Double):Double {
        var re=0.0;var im=0.0
        for(i in samples.indices) { val p=2*PI*hz*i/48000;re+=samples[i]*cos(p);im+=samples[i]*sin(p) }
        return 2*hypot(re,im)/samples.size
    }
    @Test fun selectedRfWidthRejectsAdjacentCarrierBeforeAmDetection() {
        for(detector in AmDetector.values()) observedAmChannel().use { channel ->
            val pcm=ArrayList<Float>()
            val settings=AmSettings(detector,agc=AmAgc.OFF)
            val config=ChannelConfiguration(DemodMode.AM,768000,48000,if(detector.sideband==0) 10000 else 5000,768000,am=settings,signalDb=-30f)
            repeat(180) { block ->
                val iq=FloatArray(8192)
                for(i in 0 until 4096) {
                    val t=(block*4096+i)/768000.0
                    iq[2*i]=(.15+.03*sin(2*PI*1000*t)+.03*cos(2*PI*10000*t)).toFloat()
                    iq[2*i+1]=(-.03*sin(2*PI*10000*t)).toFloat()
                }
                val out=channel.process(iq,config).mono
                if(block>=60) out.forEach { pcm.add(it) }
            }
            val signal=pcm.takeLast(24000).toFloatArray()
            val voice=amplitude(signal,1000.0);val beat=amplitude(signal,10000.0)
            assertTrue("$detector passband voice=$voice",abs(20*log10(voice/.03))<.3)
            assertTrue("$detector adjacent beat=$beat voice=$voice",beat<voice*.001)
        }
    }
}
