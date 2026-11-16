package com.radiosport.ninegradio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.radiosport.ninegradio.dsp.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class NativeChannelFilterTest {
    @Test fun matchesExistingFirAcrossIrregularBlocksAndReset() {
        val input=FloatArray(6000) { kotlin.random.Random(it).nextFloat()-.5f }
        val taps=PolyphaseResampler(48000,48000,96,5000.0).coefficientsForUnitRate()
        val expected=ReferenceDemodPrimitives.applyFirDual(input,taps,FloatArray(96),FloatArray(96),IntArray(1))
        NativeChannelFilter().use { filter ->
            val actual=ArrayList<Float>();var offset=0;var chunk=0
            val sizes=intArrayOf(2,18,800,6,126,2048)
            while(offset<input.size) {
                val size=minOf(sizes[chunk++%sizes.size],input.size-offset)
                val owned=input.copyOfRange(offset,offset+size)
                assertSame(owned,filter.process(owned,48000,10000))
                owned.forEach { actual.add(it) };offset+=size
            }
            assertArrayEquals(expected,actual.toFloatArray(),1e-6f)
            filter.reset()
            assertArrayEquals(expected,filter.process(input.copyOf(),48000,10000),1e-6f)
            for(hz in listOf(1000.0,3000.0)) {
                filter.reset()
                val tone=FloatArray(4000) { i -> (if(i%2==0) cos(2*PI*hz*(i/2)/48000) else sin(2*PI*hz*(i/2)/48000)).toFloat()*.1f }
                val out=filter.process(tone,48000,10000)
                val energy=out.drop(400).sumOf { it.toDouble()*it }/(out.size-400)
                assertTrue("$hz Hz passband",abs(10*log10(energy/.005))<.3)
            }
        }
    }
}
