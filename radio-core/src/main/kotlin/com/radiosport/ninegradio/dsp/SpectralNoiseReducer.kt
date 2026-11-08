package com.radiosport.ninegradio.dsp

import kotlin.math.*

/** Streaming real-audio Wiener filter. 1024-point STFT, 75% overlap, sqrt-Hann WOLA.
 * State is owned by the DSP consumer; no FFT/window allocations occur per frame.
 */
class SpectralNoiseReducer {
    private val size = 1024
    private val hop = size / 4
    private val input = FloatArray(size)
    private val output = FloatArray(size)
    private val weight = FloatArray(size)
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)
    private val noise = DoubleArray(size / 2 + 1)
    private val smoothPower = DoubleArray(size / 2 + 1)
    private val minimum = DoubleArray(size / 2 + 1) { Double.MAX_VALUE }
    private val gain = DoubleArray(size / 2 + 1) { 1.0 }
    private val desiredGain = DoubleArray(size / 2 + 1) { 1.0 }
    private val previousPosterior = DoubleArray(size / 2 + 1) { 1.0 }
    private val window = DoubleArray(size) { sqrt(0.5 - 0.5 * cos(2 * PI * it / size)) }
    private val twiddleRe = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val twiddleIm = DoubleArray(size / 2) { sin(2 * PI * it / size) }
    private var position = 0
    private var received = 0L
    private var frames = 0
    private var rate = 48000
    @Volatile var strength = 1f
        set(value) { field = value.coerceIn(0f, 1.5f) }

    fun setSampleRate(value: Int) { if (value != rate) { rate = value; reset() } }
    fun reset() {
        input.fill(0f); output.fill(0f); weight.fill(0f)
        noise.fill(0.0); smoothPower.fill(0.0); minimum.fill(Double.MAX_VALUE); gain.fill(1.0)
        previousPosterior.fill(1.0); desiredGain.fill(1.0)
        position = 0; received = 0; frames = 0
    }
    fun process(samples: FloatArray): FloatArray {
        val result = FloatArray(samples.size)
        for (i in samples.indices) {
            val norm = weight[position]
            result[i] = if (norm > 0.05f) output[position] / norm else 0f
            output[position] = 0f; weight[position] = 0f
            input[position] = if (samples[i].isFinite()) samples[i] else 0f
            position = (position + 1) % size
            received++
            if (received >= size && received % hop == 0L) processFrame()
        }
        return result
    }
    private fun processFrame() {
        for (i in 0 until size) { re[i] = input[(position + i) % size] * window[i]; im[i] = 0.0 }
        fft(false)
        var meanPower = 0.0
        for (k in noise.indices) meanPower += re[k] * re[k] + im[k] * im[k]
        meanPower /= noise.size
        val trackingFrames = max(1, (rate * 1.5 / hop).toInt())
        for (k in noise.indices) {
            val power = re[k] * re[k] + im[k] * im[k] + 1e-20
            smoothPower[k] = if (frames == 0) power else 0.8 * smoothPower[k] + 0.2 * power
            minimum[k] = min(minimum[k], smoothPower[k])
            if (frames == 0) noise[k] = min(power, meanPower * 3).coerceAtLeast(1e-20)
            else {
                // Compensate minimum-statistics bias. Freeze upward tracking
                // in bins dominated by a persistent carrier or voiced signal.
                val target = minimum[k] * 1.4
                val alpha = if (target < noise[k]) 0.97 else if (smoothPower[k] > noise[k] * 3) 1.0 else 0.98
                noise[k] = alpha * noise[k] + (1 - alpha) * target
            }
            val posterior = (power / noise[k].coerceAtLeast(1e-20)).coerceAtMost(10000.0)
            val prior = 0.96 * gain[k] * gain[k] * previousPosterior[k] +
                0.04 * max(posterior - 1.0, 0.0)
            previousPosterior[k] = posterior
            val amount = strength.toDouble()
            val floor = exp(-1.5 * amount)
            // Soft magnitude gain preserves weak voiced/consonant energy;
            // applying the power-domain suppression directly to magnitude
            // attenuates speech too aggressively at low SNR.
            desiredGain[k] = if (amount == 0.0) 1.0 else sqrt(prior / (prior + amount)).coerceIn(floor, 1.0)
        }
        // Smooth both across frequency and time. Isolated FFT-bin gates create
        // the metallic "musical noise" heard as uneven chirps on the noise floor.
        for (k in noise.indices) {
            val desired = 0.15 * desiredGain[(k - 1).coerceAtLeast(0)] +
                0.7 * desiredGain[k] + 0.15 * desiredGain[(k + 1).coerceAtMost(noise.lastIndex)]
            val smoothing = if (desired < gain[k]) 0.8 else 0.95
            gain[k] = smoothing * gain[k] + (1 - smoothing) * desired
            re[k] *= gain[k]; im[k] *= gain[k]
            if (k > 0 && k < size / 2) {
                re[size - k] *= gain[k]; im[size - k] *= gain[k]
            }
        }
        frames++
        if (frames % trackingFrames == 0) minimum.fill(Double.MAX_VALUE)
        fft(true)
        for (i in 0 until size) {
            val target = (position + i) % size
            output[target] += (re[i] * window[i]).toFloat()
            weight[target] += (window[i] * window[i]).toFloat()
        }
    }
    private fun fft(inverse: Boolean) {
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val r = re[i]; re[i] = re[j]; re[j] = r
                val q = im[i]; im[i] = im[j]; im[j] = q
            }
        }
        var length = 2
        while (length <= size) {
            val half = length / 2
            val stride = size / length
            var start = 0
            while (start < size) {
                for (k in 0 until half) {
                    val wr = twiddleRe[k * stride]
                    val wi = twiddleIm[k * stride] * if (inverse) 1 else -1
                    val a = start + k; val b = a + half
                    val r = re[b] * wr - im[b] * wi
                    val q = re[b] * wi + im[b] * wr
                    re[b] = re[a] - r; im[b] = im[a] - q
                    re[a] += r; im[a] += q
                }
                start += length
            }
            length *= 2
        }
        if (inverse) for (i in re.indices) { re[i] /= size; im[i] /= size }
    }
}
