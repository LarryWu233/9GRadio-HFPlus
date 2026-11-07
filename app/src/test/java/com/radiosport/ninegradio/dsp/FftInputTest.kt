package com.radiosport.ninegradio.dsp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class FftInputTest {
    private fun tone(samples: Int) = FloatArray(samples * 2) { index ->
        val phase = 2 * PI * 8 * (index / 2) / (256 * 64)
        (0.125 * if (index % 2 == 0) cos(phase) else sin(phase)).toFloat()
    }

    @Test fun hfBlocksAccumulateFullWindowAt64TimesDecimation() {
        val fft = FftEngine(256).apply { decimationFactor = 64; smoothingAlpha = 0f }
        val iq = tone(16384)
        repeat(4) { block -> fft.processFloat(iq.copyOfRange(block * 8192, (block + 1) * 8192)) }
        assertEquals(-18.1f, fft.lastPeakDb, 0.3f)
        val spectrum = fft.processFloat(iq.copyOfRange(0, 8192))
        assertEquals(136, spectrum.indices.maxBy { spectrum[it] })
    }

    @Test fun arbitraryBlockBoundariesRetainDecimationPhase() {
        val input = tone(16384)
        val whole = FftEngine(256).apply { decimationFactor = 64; smoothingAlpha = 0f }
        val split = FftEngine(256).apply { decimationFactor = 64; smoothingAlpha = 0f }
        val expected = whole.processFloat(input).copyOf()
        for (start in 0 until 16384 step 137)
            split.processFloat(input.copyOfRange(start * 2, minOf(start + 137, 16384) * 2))
        assertArrayEquals(expected, split.processFloat(floatArrayOf()), 0.0001f)
    }

    @Test fun uint8AndFloatShareTheSameWindowingAndScale() {
        val floats = tone(16384)
        val bytes = ByteArray(floats.size) { (floats[it] * 128 + 127.5f).roundToInt().toByte() }
        val a = FftEngine(256).apply { decimationFactor = 64; smoothingAlpha = 0f }
        val b = FftEngine(256).apply { decimationFactor = 64; smoothingAlpha = 0f }
        a.processFloat(floats)
        b.processUint8(bytes)
        assertEquals(a.lastPeakDb, b.lastPeakDb, 0.4f)
    }
}
