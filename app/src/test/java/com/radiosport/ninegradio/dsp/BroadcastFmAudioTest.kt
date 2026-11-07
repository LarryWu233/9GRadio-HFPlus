package com.radiosport.ninegradio.dsp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class BroadcastFmAudioTest {
    @Test fun pilotRecoversIndependentLeftAndRightAcrossBlocks() {
        val rate=228000
        val decoder=BroadcastFmAudio(true)
        val frames=ArrayList<Float>()
        var offset=0
        while (offset < rate) {
            val size=min(4093,rate-offset)
            val input=FloatArray(size) { j ->
                val t=(offset+j).toDouble()/rate
                val left=0.3*sin(2*PI*1000*t); val right=0.3*sin(2*PI*2300*t)
                ((left+right)/2 + (left-right)/2*cos(2*PI*38000*t+0.8) + 0.1*cos(2*PI*19000*t+0.4)).toFloat()
            }
            frames.addAll(decoder.process(input,rate,75).toList()); offset+=size
        }
        fun power(channel: Int,frequency: Double): Double {
            var a=0.0; var b=0.0
            for (i in rate/2 until rate) {
                val sample=frames[i*2+channel].toDouble(); val phase=2*PI*frequency*i/rate
                a+=sample*cos(phase); b+=sample*sin(phase)
            }
            return a*a+b*b
        }
        val left=10*log10(power(0,1000.0)/power(0,2300.0))
        val right=10*log10(power(1,2300.0)/power(1,1000.0))
        println("FM_STEREO separationLeft=$left separationRight=$right")
        assertTrue("Left separation: $left",left>20)
        assertTrue("Right separation: $right",right>20)
    }
    @Test fun fiftyAndSeventyFiveMicrosecondsProduceDifferentCorrectResponses() {
        val rate=228000
        fun amplitude(us: Int): Double {
            val input=FloatArray(rate/4) { sin(2*PI*6000*it/rate).toFloat() }
            val out=BroadcastFmAudio(false).process(input,rate,us)
            return sqrt(out.drop(2000).map { it*it.toDouble() }.average())
        }
        val actual=amplitude(50)/amplitude(75)
        val expected=sqrt((1+(2*PI*6000*75e-6).pow(2))/(1+(2*PI*6000*50e-6).pow(2)))
        assertEquals(expected,actual,0.01)
    }
}
