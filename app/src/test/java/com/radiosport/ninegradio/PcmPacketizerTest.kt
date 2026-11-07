package com.radiosport.ninegradio

import com.radiosport.ninegradio.audio.PcmPacketizer
import com.radiosport.ninegradio.dsp.ListeningSquelch
import com.radiosport.ninegradio.dsp.FftEngine
import org.junit.Assert.*
import org.junit.Test

class PcmPacketizerTest {
    @Test fun arbitraryUsbChunksKeepEverySampleInOrder() {
        val input=FloatArray(48000*2) { it.toFloat() }
        val output=ArrayList<Float>()
        val packetizer=PcmPacketizer(48000)
        var offset=0; var chunk=0
        val chunks=intArrayOf(430,432,862,218,4096)
        while(offset<input.size) {
            val count=minOf(chunks[chunk++%chunks.size],input.size-offset)
            packetizer.append(input.copyOfRange(offset,offset+count)) { packet ->
                assertEquals(1920,packet.size); output.addAll(packet.toList())
            }
            offset+=count
        }
        assertArrayEquals(input,output.toFloatArray(),0f)
    }
    @Test fun shortThresholdDipsDoNotChopSpeech() {
        val gate=ListeningSquelch()
        assertTrue(gate.update(-50f,-60f,0.01))
        repeat(10) { assertTrue(gate.update(-80f,-60f,0.01)) }
        assertTrue(gate.update(-59f,-60f,0.01)) // A return above the actual line resets hold.
        repeat(19) { assertTrue(gate.update(-80f,-60f,0.01)) }
        assertFalse(gate.update(-80f,-60f,0.02))
        assertTrue(gate.update(-200f,-120f,0.001))
    }
    @Test fun closingSquelchUsesARampInsteadOfHardBlockCuts() {
        val gate=ListeningSquelch()
        val samples=FloatArray(960*2) { 1f }
        gate.apply(samples,false,48000)
        assertTrue(samples[0]>0.99f)
        assertEquals(0f,samples.last(),0f)
        for(i in 2 until samples.size step 2) assertTrue(kotlin.math.abs(samples[i]-samples[i-2])<0.003f)
    }
    @Test fun fftPacingRetainsContinuousInputWithoutTransformingEveryUsbBlock() {
        val fft=FftEngine(256)
        fft.smoothingAlpha=0f; fft.analysisIntervalSamples=4096
        val iq=FloatArray(1024*2) { if(it%2==0) kotlin.math.cos(2*Math.PI*32*(it/2)/256).toFloat() else kotlin.math.sin(2*Math.PI*32*(it/2)/256).toFloat() }
        repeat(3) { fft.processFloat(iq) }
        assertTrue(fft.lastPeakDb < -100)
        fft.processFloat(iq)
        assertTrue(fft.lastPeakDb > -2)
    }
}
