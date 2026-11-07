package com.radiosport.ninegradio.dsp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ResamplerContinuityTest {
    @Test fun fractionalPhasesPreserveDcAndRejectAliasing() {
        for ((inputRate, outputRate) in listOf(228000 to 48000, 200000 to 48000, 912000 to 200000, 204800 to 44100)) {
            val out = PolyphaseResampler(inputRate, outputRate).process(FloatArray(inputRate / 4) { 1f })
            assertEquals(outputRate / 4.0, out.size.toDouble(), 1.0)
            val stable = out.drop(300)
            assertTrue("DC ripple at $inputRate/$outputRate", stable.maxOrNull()!! - stable.minOrNull()!! < 0.002f)
            val stop = PolyphaseResampler(inputRate, outputRate).process(FloatArray(inputRate / 4) { sin(2 * PI * outputRate * 0.8 * it / inputRate).toFloat() })
            assertTrue("Alias rejection at $inputRate/$outputRate", sqrt(stop.drop(300).map { it * it.toDouble() }.average()) < 0.01)
        }
    }
    @Test fun arbitraryChunksMatchContinuousSignal() {
        val signal = FloatArray(91200) { sin(2 * PI * 1000 * it / 912000).toFloat() }
        val expected = PolyphaseResampler(912000, 200000).process(signal)
        val resampler = PolyphaseResampler(912000, 200000)
        val actual = ArrayList<Float>()
        var offset = 0
        val sizes = intArrayOf(4096, 8192, 123, 1, 777)
        var index = 0
        while (offset < signal.size) {
            val end = min(signal.size, offset + sizes[index++ % sizes.size])
            actual.addAll(resampler.process(signal.copyOfRange(offset, end)).toList())
            offset = end
        }
        assertArrayEquals(expected, actual.toFloatArray(), 0f)
    }
    @Test fun channelWidthActuallyRejectsAdjacentEnergy() {
        val iq = FloatArray(91200 * 2) { index ->
            val phase = 2 * PI * 80000 * (index / 2) / 912000
            (if (index % 2 == 0) cos(phase) else sin(phase)).toFloat()
        }
        fun rms(width: Double): Double {
            val out = ComplexDecimator(912000, 228000, cutoffHz = width / 2).process(iq)
            return sqrt(out.drop(1000).map { it * it.toDouble() }.average())
        }
        assertTrue(rms(200000.0) > 0.4)
        assertTrue(rms(100000.0) < 0.02)
    }
}
