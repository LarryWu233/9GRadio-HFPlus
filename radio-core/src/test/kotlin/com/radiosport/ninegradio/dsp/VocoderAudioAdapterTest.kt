package com.radiosport.ninegradio.dsp

import com.radiosport.ninegradio.pipeline.AudioBlock
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class VocoderAudioAdapterTest {
    private fun block(values:FloatArray,epoch:Long=1,first:Long=0)=AudioBlock(values,8000,1,epoch,first)
    @Test fun default48kKeepsLegacyInterpolationExactly() {
        val input=FloatArray(160) { sin(2*PI*700*it/8000).toFloat() }
        val expected=FloatArray(960)
        for(i in input.indices) {
            val a=input[i];val b=if(i+1<input.size) input[i+1] else a
            for(r in 0 until 6) expected[i*6+r]=a+(b-a)*r.toFloat()/6
        }
        assertArrayEquals(expected,VocoderAudioAdapter().process(block(input),48000)!!.samples,0f)
    }
    @Test fun fractional44k1PreservesSampleCountAndChunkContinuity() {
        val input=FloatArray(8000) { sin(2*PI*700*it/8000).toFloat() }
        val whole=VocoderAudioAdapter().process(block(input),44100)!!.samples
        val adapter=VocoderAudioAdapter();val pieces=ArrayList<FloatArray>();var offset=0
        while(offset<input.size) {
            val count=minOf(1+(offset*17+13)%311,input.size-offset)
            pieces+=adapter.process(block(input.copyOfRange(offset,offset+count),first=offset.toLong()),44100)!!.samples
            offset+=count
        }
        val split=pieces.flatMap { it.asList() }.toFloatArray()
        assertEquals(44100,whole.size);assertArrayEquals(whole,split,0f)
        val rms=sqrt(whole.drop(4000).sumOf { it.toDouble()*it }/(whole.size-4000))
        assertEquals(sqrt(.5),rms,.015)
    }
    @Test fun gapsAndEpochChangesResetFractionalStateAndRejectOldBlocks() {
        val adapter=VocoderAudioAdapter();val input=FloatArray(160) { .25f }
        assertTrue(adapter.process(block(input),44100)!!.discontinuity)
        assertFalse(adapter.process(block(input,first=160),44100)!!.discontinuity)
        assertNull(adapter.process(block(input,first=160),44100))
        assertTrue(adapter.process(block(input,first=480),44100)!!.discontinuity)
        val reset=adapter.process(block(input,epoch=2),44100)!!
        assertTrue(reset.discontinuity)
        assertArrayEquals(VocoderAudioAdapter().process(block(input,epoch=2),44100)!!.samples,reset.samples,0f)
        assertNull(adapter.process(block(input,epoch=1,first=640),44100))
    }
}
