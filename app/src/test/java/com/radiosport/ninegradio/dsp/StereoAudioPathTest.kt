package com.radiosport.ninegradio.dsp

import org.junit.Assert.*
import org.junit.Test

class StereoAudioPathTest {
    @Test fun channelTransportPreservesValuesAndFrameCount() {
        val path=StereoAudioPath()
        val input=FloatArray(228000/4*2) { if(it%2==0) 0.5f else -0.25f }
        val out=path.process(input,228000,48000,false,false)
        assertEquals(24000,out.size)
        for(i in 4000 until out.size step 2) {
            assertEquals(0.5f,out[i],0.001f)
            assertEquals(-0.25f,out[i+1],0.001f)
        }
        assertArrayEquals(floatArrayOf(1f,1f,2f,2f),StereoAudioPath.duplicateMono(floatArrayOf(1f,2f)),0f)
    }
}
