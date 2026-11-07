package com.radiosport.ninegradio

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import com.radiosport.ninegradio.dsp.*
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class FmContinuityTest {
    @Test fun nativeFmAndWfmContinuity() = FmFixture.verifyContinuity()

    @Test fun nativeStereoIqReachesIndependentOutputChannels() {
        val rate=228000
        val demod=WfmDemodulator(true)
        val path=StereoAudioPath()
        val output=ArrayList<Float>()
        var index=0; var phase=0.0
        while(index<rate) {
            val n=min(4093,rate-index)
            val iq=FloatArray(n*2)
            for(i in 0 until n) {
                val t=index++.toDouble()/rate
                val l=0.3*sin(2*PI*1000*t); val r=0.3*sin(2*PI*2300*t)
                val mpx=(l+r)/2+(l-r)/2*cos(2*PI*38000*t+0.8)+0.1*cos(2*PI*19000*t+0.4)
                phase+=2*PI*75000*mpx/rate
                iq[2*i]=(0.6*cos(phase)).toFloat(); iq[2*i+1]=(0.6*sin(phase)).toFloat()
            }
            output.addAll(path.process(demod.demodulate(iq,rate),rate,48000,false,false).toList())
        }
        check(abs(output.size-96000)<=2) { "Stereo transport frame count: ${output.size}" }
        fun power(channel: Int, hz: Double): Double {
            var a=0.0; var b=0.0
            for(i in 24000 until output.size/2) {
                val x=output[2*i+channel]; val angle=2*PI*hz*i/48000
                a+=x*cos(angle); b+=x*sin(angle)
            }
            return a*a+b*b
        }
        val l=10*log10(power(0,1000.0)/power(0,2300.0))
        val r=10*log10(power(1,2300.0)/power(1,1000.0))
        println("NATIVE_FM_STEREO leftSeparation=$l rightSeparation=$r frames=${output.size/2}")
        check(l>20 && r>20) { "Stereo separation L=$l R=$r" }
    }
}
