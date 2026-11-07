package com.radiosport.ninegradio.dsp

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

class SpectralNoiseReducerTest {
    @Test fun zeroStrengthReconstructsSignalWithFixedLatency() {
        val input = FloatArray(16000) { (0.2 * sin(2 * PI * 1000 * it / 48000)).toFloat() }
        val reducer = SpectralNoiseReducer().apply { strength = 0f }
        val output = reducer.process(input)
        for (i in 4096 until input.size) assertEquals(input[i - 1024], output[i], 0.00001f)
    }
    @Test fun arbitraryInputBlocksHaveIdenticalOutput() {
        val random = Random(27)
        val input = FloatArray(18000) { (random.nextGaussian() * 0.05).toFloat() }
        val expected = SpectralNoiseReducer().process(input)
        val reducer = SpectralNoiseReducer()
        val actual = FloatArray(input.size)
        var offset = 0
        while (offset < input.size) {
            val end = minOf(offset + 137, input.size)
            reducer.process(input.copyOfRange(offset, end)).copyInto(actual, offset)
            offset = end
        }
        assertArrayEquals(expected, actual, 0f)
        assertTrue(actual.all { it.isFinite() })
    }
    @Test fun reducesNoiseRelativeToRetainedToneRatherThanOnlyTurningVolumeDown() {
        val rate = 48000
        val random = Random(91)
        val input = FloatArray(rate * 4) { n ->
            (random.nextGaussian() * 0.08 + if (n >= rate * 2) 0.12 * sin(2 * PI * 1000 * n / rate) else 0.0).toFloat()
        }
        val output = SpectralNoiseReducer().process(input)
        fun measure(data: FloatArray): Pair<Double, Double> {
            val start = rate * 3
            var sine = 0.0; var cosine = 0.0; var power = 0.0
            for (n in start until data.size) {
                sine += data[n] * sin(2 * PI * 1000 * n / rate)
                cosine += data[n] * cos(2 * PI * 1000 * n / rate)
                power += data[n] * data[n]
            }
            val count = data.size - start
            val tone = 2 * (sine * sine + cosine * cosine) / (count.toDouble() * count)
            val noise = (power / count - tone).coerceAtLeast(1e-12)
            return 10 * log10(tone / noise) to sqrt(2 * tone)
        }
        val before = measure(input); val after = measure(output)
        assertTrue("SNR before=${before.first}, after=${after.first}", after.first > before.first + 2.0)
        assertTrue("Tone was suppressed excessively", after.second > before.second * 0.7)
    }
    @Test fun noiseBlankerDoesNotGetStuckAtZeroOnStartup() {
        val tone = FloatArray(4096) { (0.2 * sin(2 * PI * 1000 * it / 48000)).toFloat() }
        val result = AudioDsp.NoiseBlanker().process(tone)
        assertTrue(result.takeLast(2048).count { abs(it) > 0.01 } > 1000)
    }
}
