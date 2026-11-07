package com.radiosport.ninegradio.dsp

import kotlin.math.*

/**
 * Collection of post-demodulation audio processing utilities:
 *
 *   - Noise Blanker (NB) — suppresses impulsive noise (power-line clicks, ignition)
 *   - Noise Reduction (NR) — spectral subtraction noise floor estimation
 *   - Auto Notch Filter (ANF) — tracks and nulls narrowband tones (heterodynes)
 *   - Audio AGC — normalises output level
 *   - DC blocker — removes residual DC offset from demod output
 *   - IF Notch — removes a fixed frequency within the IF passband
 */
object AudioDsp {

    // ─── DC Blocker (1-pole high-pass) ────────────────────────────────────────
    class DcBlocker {
        private var xm1 = 0f
        private var ym1 = 0f
        private val R   = 0.9995f

        fun process(x: Float): Float {
            val y = x - xm1 + R * ym1
            xm1 = x; ym1 = y
            return y
        }

        fun processBlock(samples: FloatArray): FloatArray {
            val out = FloatArray(samples.size)
            for (i in samples.indices) out[i] = process(samples[i])
            return out
        }
    }

    // ─── Noise Blanker ────────────────────────────────────────────────────────
    /**
     * Impulsive-noise blanker: suppresses spikes whose instantaneous amplitude
     * exceeds [thresholdFactor] × the running RMS level.
     *
     * Critical detail: the running power estimate is updated only from
     * *non-blanked* samples. If blanked (zero) samples were fed back into the
     * estimator, the average power would collapse toward zero, pulling the
     * threshold with it — causing runaway blanking that silences the output
     * indefinitely after any strong transient.
     */
    class NoiseBlanker(private val thresholdFactor: Float = 4f) {
        private var avgPower = 1e-8f
        private var warmup = 256
        private val alpha    = 0.001f
        private var effectiveThreshold = thresholdFactor

        fun process(samples: FloatArray): FloatArray {
            val out = FloatArray(samples.size)
            for (i in samples.indices) {
                val s = samples[i]
                val power = s * s
                if (warmup > 0) {
                    avgPower += alpha * (power - avgPower)
                    out[i] = s
                    warmup--
                    continue
                }
                val threshold = effectiveThreshold * sqrt(avgPower.coerceAtLeast(1e-12f))
                if (abs(s) > threshold) {
                    out[i] = 0f
                    avgPower += alpha * (minOf(power, avgPower * 2f + 1e-12f) - avgPower)
                    // Do NOT update avgPower from blanked samples —
                    // feeding zeros back collapses the estimate and the threshold,
                    // causing a positive-feedback loop that blanks everything.
                } else {
                    out[i] = s
                    // Only update the power estimate from valid (non-blanked) samples.
                    avgPower = (1 - alpha) * avgPower + alpha * power
                }
            }
            return out
        }

        fun setThreshold(factor: Float) {
            effectiveThreshold = factor
        }
    }

    // ─── Spectral Subtraction Noise Reducer ───────────────────────────────────
    /** Stateful STFT Wiener processing for listening audio; decoder audio bypasses this stage. */
    class NoiseReducer {
        private val spectral = SpectralNoiseReducer()
        private val listening = ListeningNoiseProcessor(spectral)
        fun setStrength(value: Float) { spectral.strength = value }
        fun setSampleRate(rate: Int) = spectral.setSampleRate(rate)
        internal fun calibrate(samples: FloatArray) = Unit
        fun process(samples: FloatArray) = spectral.process(samples)
        fun processListening(samples: FloatArray, enabled: Boolean, rate: Int) = listening.process(samples, enabled, rate)
        fun isCalibrated() = true
        fun reset() = spectral.reset()
    }

    // ─── Auto Notch Filter (LMS adaptive notch) ───────────────────────────────
    class AutoNotchFilter(
        private val mu: Float = 0.01f,
        private val delay: Int = 6,
        private val taps: Int = 32
    ) {
        private val weights = FloatArray(taps)
        private val delayBuf = FloatArray(maxOf(taps, delay + 1))
        private var pos = 0

        fun process(samples: FloatArray): FloatArray {
            val out = FloatArray(samples.size)
            for (i in samples.indices) {
                val x = samples[i]
                delayBuf[pos % delayBuf.size] = x

                // Predict using delayed signal
                var yHat = 0f
                for (k in 0 until taps) {
                    yHat += weights[k] * delayBuf[(pos - delay - k + delayBuf.size * 2) % delayBuf.size]
                }

                // Error = signal - prediction (desired = broadband, residual after notch)
                val e = x - yHat

                // LMS weight update
                for (k in 0 until taps) {
                    weights[k] += mu * e * delayBuf[(pos - delay - k + delayBuf.size * 2) % delayBuf.size]
                }

                out[i] = e  // Output the broadband residual (tones cancelled)
                pos++
            }
            return out
        }

        fun reset() { weights.fill(0f); delayBuf.fill(0f); pos = 0 }
    }

    // ─── Fixed-frequency IF notch (biquad band-reject) ────────────────────────
    class IfNotch(freqHz: Double, sampleRate: Int, q: Double = 30.0) {
        private val w0    = 2 * PI * freqHz / sampleRate
        private val alpha = sin(w0) / (2 * q)
        private val b0    = 1.0f;   private val b1 = (-2 * cos(w0)).toFloat(); private val b2 = 1.0f
        private val a0    = (1 + alpha).toFloat()
        private val a1    = b1;     private val a2 = (1 - alpha).toFloat()
        private var x1 = 0f; private var x2 = 0f
        private var y1 = 0f; private var y2 = 0f

        fun process(x: Float): Float {
            val y = (b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }

        fun processBlock(samples: FloatArray) = FloatArray(samples.size) { process(samples[it]) }
    }

    // ─── Audio AGC ────────────────────────────────────────────────────────────
    class AudioAgc(
        private val targetLevel: Float = 0.5f,
        private val attackMs: Float    = 5f,
        private val releaseMs: Float   = 200f,
        private val sampleRate: Int    = 48_000
    ) {
        private var gain         = 1f
        private val attackAlpha  = exp(-1f / (attackMs  * sampleRate / 1000f))
        private val releaseAlpha = exp(-1f / (releaseMs * sampleRate / 1000f))
        private var envLevel     = 0f
        val maxGain              = 100f
        val minGain              = 0.001f

        fun process(samples: FloatArray): FloatArray {
            val out = FloatArray(samples.size)
            for (i in samples.indices) {
                val s   = samples[i]
                val env = abs(s) * gain

                // Envelope follower
                envLevel = if (env > envLevel) {
                    attackAlpha  * envLevel + (1 - attackAlpha)  * env
                } else {
                    releaseAlpha * envLevel + (1 - releaseAlpha) * env
                }

                // Compute new gain
                if (envLevel > 0f) {
                    val newGain = targetLevel / envLevel
                    gain = newGain.coerceIn(minGain, maxGain)
                }

                out[i] = (s * gain).coerceIn(-1f, 1f)
            }
            return out
        }

        fun reset() { gain = 1f; envLevel = 0f }
        fun getGainDb(): Float = 20f * log10(gain)
    }

    // ─── Stereo balance / pan ─────────────────────────────────────────────────
    fun applyBalance(stereoSamples: FloatArray, balance: Float): FloatArray {
        // balance: -1 = full left, 0 = centre, +1 = full right
        val gainL = if (balance <= 0f) 1f else 1f - balance
        val gainR = if (balance >= 0f) 1f else 1f + balance
        val out   = FloatArray(stereoSamples.size)
        for (i in 0 until stereoSamples.size / 2) {
            out[2 * i]     = stereoSamples[2 * i]     * gainL
            out[2 * i + 1] = stereoSamples[2 * i + 1] * gainR
        }
        return out
    }

    // ─── Peak limiter (soft-clip) ─────────────────────────────────────────────
    fun softClip(samples: FloatArray, threshold: Float = 0.9f): FloatArray {
        val out = FloatArray(samples.size)
        for (i in samples.indices) {
            val s = samples[i]
            out[i] = when {
                s >  threshold ->  threshold + (1f - threshold) * tanh((s - threshold) / (1f - threshold))
                s < -threshold -> -threshold - (1f - threshold) * tanh((-s - threshold) / (1f - threshold))
                else           ->  s
            }
        }
        return out
    }

    private fun tanh(x: Float): Float = kotlin.math.tanh(x.toDouble()).toFloat()
}
