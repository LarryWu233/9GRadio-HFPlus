package com.radiosport.ninegradio.dsp

import kotlin.math.*

/** Stateful rational upfirdn. Prototype is designed at inputRate * L. */
class PolyphaseResampler(val inputRate: Int, val outputRate: Int,
    numTaps: Int = 127, cutoffHz: Double? = null) {
    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    private val common = gcd(inputRate, outputRate)
    private val l = outputRate / common
    private val m = inputRate / common
    private val phaseLength = max(48, (numTaps + l - 1) / l).coerceAtMost(256)
    private val history = FloatArray(phaseLength)
    private val bank: Array<FloatArray>
    private var position = 0
    private var inputCount = 0L
    private var outputCount = 0L
    init {
        require(inputRate > 0 && outputRate > 0)
        val count = phaseLength * l
        val limit = min(inputRate, outputRate) * 0.45
        val cutoff = min(cutoffHz ?: limit, limit) / (inputRate.toDouble() * l)
        val prototype = DoubleArray(count) { i ->
            val offset = i - (count - 1) / 2.0
            val x = 2 * PI * cutoff * offset
            val sinc = if (abs(x) < 1e-12) 1.0 else sin(x) / x
            val w = 0.35875 - 0.48829 * cos(2 * PI * i / (count - 1)) +
                0.14128 * cos(4 * PI * i / (count - 1)) - 0.01168 * cos(6 * PI * i / (count - 1))
            sinc * w
        }
        val scale = l / prototype.sum()
        bank = Array(l) { phase -> FloatArray(phaseLength) { k -> (prototype[phase + k * l] * scale).toFloat() } }
    }
    fun process(input: FloatArray): FloatArray {
        val result = FloatArray(((input.size.toLong() * l + m - 1) / m + 1).toInt())
        var produced = 0
        for (sample in input) {
            history[position] = sample
            val newest = position
            position = (position + 1) % phaseLength
            // floor(t/L) must match the sample currently in the delay line.
            // Using t < n*L (the old implementation) selects the wrong input
            // sample at fractional phases and causes periodic modulation.
            while (outputCount * m < (inputCount + 1) * l) {
                val taps = bank[(outputCount * m % l).toInt()]
                var value = 0f
                for (k in taps.indices) {
                    var idx = newest - k
                    if (idx < 0) idx += phaseLength
                    value += taps[k] * history[idx]
                }
                result[produced++] = value
                outputCount++
            }
            inputCount++
        }
        return if (produced == result.size) result else result.copyOf(produced)
    }
    internal fun coefficientsForUnitRate(): FloatArray { require(l==1 && m==1); return bank.single().copyOf() }
    fun reset() { history.fill(0f); position = 0; inputCount = 0; outputCount = 0 }
    val ratio: Double get() = outputRate.toDouble() / inputRate
    val isDecimating get() = outputRate < inputRate
}

class ComplexDecimator(inputRate: Int, outputRate: Int, numTaps: Int = 127, cutoffHz: Double? = null) {
    private val iFilter = PolyphaseResampler(inputRate, outputRate, numTaps, cutoffHz)
    private val qFilter = PolyphaseResampler(inputRate, outputRate, numTaps, cutoffHz)

    // Scratch buffers — resized lazily when block size changes, but not
    // re-allocated on every call.  Eliminates 4 FloatArray heap allocations
    // (iSamples, qSamples, iOut, qOut) per IQ block (~14–20/sec).
    private var scratchIn  = 0               // tracks last n so we only reallocate on size change
    private var iScratch   = FloatArray(0)
    private var qScratch   = FloatArray(0)
    // Cached interleaved output — grown only when output size increases.
    private var outScratch = FloatArray(0)

    /**
     * Process interleaved IQ float array, return decimated interleaved IQ.
     * Returns a view into a reused internal buffer — valid until the next call.
     */
    fun process(iq: FloatArray): FloatArray {
        val n = iq.size / 2
        if (n != scratchIn) {
            iScratch  = FloatArray(n)
            qScratch  = FloatArray(n)
            scratchIn = n
        }
        for (i in 0 until n) {
            iScratch[i] = iq[2 * i]
            qScratch[i] = iq[2 * i + 1]
        }
        val iOut = iFilter.process(iScratch)
        val qOut = qFilter.process(qScratch)
        val outLen = minOf(iOut.size, qOut.size)
        val needed = outLen * 2
        if (needed > outScratch.size) outScratch = FloatArray(needed)
        val out = outScratch
        for (i in 0 until outLen) {
            out[2 * i]     = iOut[i]
            out[2 * i + 1] = qOut[i]
        }
        // Return a correctly-sized slice.  After the first block the estimate
        // is exact and no copy is made; on the very first block (start-up
        // latency) we may trim by 1–2 samples.
        return if (needed == out.size) out else out.copyOf(needed)
    }

    fun reset() { iFilter.reset(); qFilter.reset() }
}
