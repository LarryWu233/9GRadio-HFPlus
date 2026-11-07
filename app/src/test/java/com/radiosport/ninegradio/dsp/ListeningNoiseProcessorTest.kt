package com.radiosport.ninegradio.dsp

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

class ListeningNoiseProcessorTest {
    @Test fun disabledOutputHasStableLatencyAndTogglesDoNotClickOrMute() {
        val size = 48000 * 3
        val input = FloatArray(size) { (0.2 * sin(2 * PI * 1000 * it / 48000)).toFloat() }
        val processor = ListeningNoiseProcessor()
        val output = FloatArray(size)
        for (start in 0 until size step 240) {
            val end = minOf(start + 240, size)
            processor.process(input.copyOfRange(start, end), start >= 24000 && (start / 12000) % 2 == 0, 48000)
                .copyInto(output, start)
        }
        for (index in 4096 until 20000) assertEquals(input[index - 1024], output[index], 0.00001f)
        val jump = (4096 until output.size).maxOf { abs(output[it] - output[it - 1]) }
        assertTrue("Switching introduced a discontinuity: $jump", jump < 0.06f)
        for (start in 24000 until size - 2400 step 2400) {
            val rms = sqrt((start until start + 2400).sumOf { output[it].toDouble().pow(2) } / 2400)
            assertTrue("Switching caused a gap", rms > 0.05)
        }
    }

    @Test fun stationaryNoiseHasUniformResidualRatherThanBursts() {
        val random = Random(772)
        val input = FloatArray(48000 * 4) { (random.nextGaussian() * 0.08).toFloat() }
        val output = SpectralNoiseReducer().process(input)
        val levels = (96000 until output.size - 2400 step 2400).map { start ->
            sqrt((start until start + 2400).sumOf { output[it].toDouble().pow(2) } / 2400)
        }
        val average = levels.average()
        val spread = sqrt(levels.sumOf { (it - average).pow(2) } / levels.size) / average
        assertTrue("Residual noise varies excessively: $spread", spread < 0.25)
        assertTrue("No useful noise reduction", average < 0.04)
    }
}
