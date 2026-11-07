package com.radiosport.ninegradio

import com.radiosport.ninegradio.dsp.*
import kotlin.math.*

object FmFixture {
    fun verifyContinuity() {
        val rate = 912000
        for (mode in listOf(DemodMode.FM, DemodMode.WFM)) for(width in listOf(200000,660000)) {
            val ifRate = DspEngine.wfmIfRate(rate,width)
            val demod = DemodulatorFactory.create(mode)
            check(demod is WfmDemodulator)
            val decimator = ComplexDecimator(rate, ifRate, cutoffHz = width/2.0)
            val resampler = PolyphaseResampler(ifRate, 48000)
            val output = ArrayList<Float>()
            var phase = 0.0
            var sample = 0
            val count = rate / 2
            val chunks = intArrayOf(4096, 8192, 777, 16384)
            var chunk = 0
            while (sample < count) {
                val size = min(chunks[chunk++ % chunks.size], count - sample)
                val iq = FloatArray(size * 2)
                for (i in 0 until size) {
                    phase += 2 * PI * 60000 * sin(2 * PI * 1000 * sample++ / rate) / rate
                    iq[i*2] = (0.6 * cos(phase)).toFloat(); iq[i*2+1] = (0.6 * sin(phase)).toFloat()
                }
                output.addAll(resampler.process(demod.demodulate(decimator.process(iq), ifRate)).toList())
            }
            check(abs(output.size - 24000) <= 2) { "Incorrect audio sample count: ${output.size}" }
            val rms = (2000 until output.size - 480 step 480).map { start ->
                sqrt((start until start+480).sumOf { output[it].toDouble().pow(2) } / 480)
            }
            check(rms.minOrNull()!! > 0.2) { "Audio level for $mode" }
            check(rms.maxOrNull()!! / rms.minOrNull()!! < 1.02) { "Periodic dropouts for $mode: $rms" }
            println("FM_PIPELINE mode=$mode width=$width ifRate=$ifRate samples=${output.size} minRms=${rms.minOrNull()} maxRms=${rms.maxOrNull()}")
        }
    }
}
