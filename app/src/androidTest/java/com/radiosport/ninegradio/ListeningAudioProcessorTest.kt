package com.radiosport.ninegradio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.dsp.ListeningAudioProcessor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.*
import java.util.Random

@RunWith(AndroidJUnit4::class)
class ListeningAudioProcessorTest {
    private val rate=48000
    private fun run(input: FloatArray, settings: ListeningAudioProcessor.Settings, mode: DemodMode=DemodMode.AM, block: Int=960): FloatArray {
        val out=input.copyOf()
        ListeningAudioProcessor().use { processor ->
            processor.settings=settings
            var start=0
            while(start<out.size) {
                val end=min(out.size,start+block*2)
                val part=out.copyOfRange(start,end)
                processor.process(part,rate,mode); part.copyInto(out,start)
                start=end
            }
        }
        return out
    }
    private fun rms(input: FloatArray, start: Int, end: Int): Double =
        sqrt((start until end).sumOf { input[it].toDouble().pow(2) }/(end-start))
    private fun tone(input: FloatArray, hz: Double, start: Int): Double {
        var re=0.0; var im=0.0
        val frames=input.size/2
        for(i in start until frames) {
            re+=input[2*i]*cos(2*PI*hz*i/rate); im+=input[2*i]*sin(2*PI*hz*i/rate)
        }
        return 2*hypot(re,im)/(frames-start)
    }
    @Test fun bypassAndCwPreserveSamples() {
        val input=FloatArray(rate*2) { sin(2*PI*700*(it/2)/rate).toFloat()*.1f }
        assertArrayEquals(input,run(input,ListeningAudioProcessor.Settings()),0f)
        assertArrayEquals(input,run(input,ListeningAudioProcessor.Settings(notch=true),DemodMode.CW),0f)
    }
    @Test fun boundedAgcLevelsFadingWithoutChangingStereoBalance() {
        val input=FloatArray(rate*2*8) { index ->
            val frame=index/2
            val amplitude=if(frame<rate*4) .02 else .08
            (amplitude*sin(2*PI*900*frame/rate)*if(index%2==0) 1.0 else .35).toFloat()
        }
        val output=run(input,ListeningAudioProcessor.Settings(leveling=true))
        val first=rms(output,rate*2*2,rate*2*4)
        val second=rms(output,rate*2*6,rate*2*8)
        assertTrue("fade ratio ${second/first}",second/first<1.7)
        assertTrue(output.all { it.isFinite() && abs(it)<.9 })
        for(i in output.indices step 2) assertEquals(output[i]*.35f,output[i+1],.00001f)
        assertTrue("bounded amplification",first/rms(input,rate*2*2,rate*2*4)<=10.0.pow(12.0/20)+.001)
    }
    @Test fun wdspSuppressesSteadyWhistleWhileRetainingUncorrelatedAudio() {
        val random=Random(173)
        val input=FloatArray(rate*2*8)
        for(i in 0 until input.size/2) {
            val sample=(.15*sin(2*PI*5000*i/rate)+.04*random.nextGaussian()).toFloat()
            input[2*i]=sample; input[2*i+1]=sample
        }
        val output=run(input,ListeningAudioProcessor.Settings(notch=true))
        val suppression=20*log10(tone(output,5000.0,rate*4)/tone(input,5000.0,rate*4))
        assertTrue("whistle suppression $suppression dB",suppression< -20)
        val residual=rms(output,rate*2*4,output.size)
        assertTrue("broadband RMS $residual",residual in .025.. .06)
        assertTrue(output.all { it.isFinite() && abs(it)<1 })
    }
    @Test fun processingDoesNotDependOnUsbBlockBoundaries() {
        val input=FloatArray(rate*4) { (.05*sin(2*PI*1300*(it/2)/rate)).toFloat() }
        val settings=ListeningAudioProcessor.Settings(leveling=true,notch=true)
        assertArrayEquals(run(input,settings,block=960),run(input,settings,block=137),.000001f)
    }
}
