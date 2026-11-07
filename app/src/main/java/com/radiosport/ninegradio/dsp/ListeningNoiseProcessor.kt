package com.radiosport.ninegradio.dsp

/** Align dry/wet latency and crossfade toggles without sharing DSP state with UI threads. */
class ListeningNoiseProcessor(private val spectral: SpectralNoiseReducer = SpectralNoiseReducer()) {
    private val dryDelay = FloatArray(1024)
    private var position = 0
    private var wetMix = 0f
    private var wasProcessing = false
    private var warmSamples = 0
    private var sampleRate = 0

    fun process(input: FloatArray, enabled: Boolean, rate: Int): FloatArray {
        if (rate != sampleRate) {
            sampleRate = rate
            spectral.setSampleRate(rate)
            dryDelay.fill(0f)
            position = 0
            wetMix = 0f
            wasProcessing = false
        }
        val processing = enabled || wetMix > 0f
        if (processing && !wasProcessing) {
            spectral.reset()
            warmSamples = 0
        }
        val wet = if (processing) spectral.process(input) else null
        val result = FloatArray(input.size)
        val step = 1f / (rate * 0.025f).coerceAtLeast(1f)
        for (index in input.indices) {
            val dry = dryDelay[position]
            dryDelay[position] = input[index]
            position = (position + 1) % dryDelay.size
            val target = if (enabled && warmSamples >= 2048) 1f else 0f
            wetMix = if (wetMix < target) (wetMix + step).coerceAtMost(target)
                else (wetMix - step).coerceAtLeast(target)
            result[index] = dry + ((wet?.get(index) ?: dry) - dry) * wetMix
            if (processing) warmSamples++
        }
        wasProcessing = processing
        return result
    }
}
