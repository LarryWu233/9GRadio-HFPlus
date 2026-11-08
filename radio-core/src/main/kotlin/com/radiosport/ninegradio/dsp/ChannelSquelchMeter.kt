package com.radiosport.ninegradio.dsp

import kotlin.math.*

/** FFT adapter: use the same bin reference as the displayed trace, restricted to BW. */
object ChannelPassband {
    fun widthHz(mode: DemodMode, requestedHz: Int, maximumHz: Int): Int {
        var width = requestedHz.takeIf { it > 0 } ?: mode.defaultBwHz
        if (mode == DemodMode.USB || mode == DemodMode.LSB) width = minOf(width, 3_000)
        return width.coerceIn(100, maximumHz.coerceAtLeast(100))
    }
    fun sideband(mode: DemodMode) = when(mode) { DemodMode.USB -> 1; DemodMode.LSB -> -1; else -> 0 }
}

class ChannelSquelchMeter {
    data class Configuration(val frequencyHz: Long, val hardwareHz: Long, val mode: DemodMode,
        val bandwidthHz: Int, val sampleRate: Int, val decimation: Int, val fftSize: Int)
    data class Reading(val sequence: Long = 0, val timestampNanos: Long = 0,
        val configuration: Configuration? = null, val peakDb: Float = -200f,
        val levelDb: Float = -200f, val complete: Boolean = false, val valid: Boolean = false)

    private var configuration: Configuration? = null
    private var previousSequence = -1L
    private var previousNanos = 0L
    private var averagePower = Double.NaN
    var reading = Reading()
        private set

    fun update(spectrum: FloatArray, sequence: Long, config: Configuration, nowNanos: Long): Reading {
        if (configuration != config) {
            configuration = config; previousSequence = sequence; previousNanos = 0
            averagePower = Double.NaN
            reading = Reading(configuration = config)
            return reading // Wait for a fresh FFT, not the previous tuning's cached trace.
        }
        if(sequence == previousSequence || sequence <= 0) return reading
        previousSequence = sequence
        val span = config.sampleRate.toDouble() / config.decimation.coerceAtLeast(1)
        val binHz = span / spectrum.size.coerceAtLeast(1)
        val dial = (config.frequencyHz-config.hardwareHz).toDouble()
        val side = ChannelPassband.sideband(config.mode)
        val lo = dial + if(side == 1) 0.0 else if(side == -1) -config.bandwidthHz.toDouble() else -config.bandwidthHz/2.0
        val hi = dial + if(side == -1) 0.0 else if(side == 1) config.bandwidthHz.toDouble() else config.bandwidthHz/2.0
        val complete = lo >= -span/2 && hi <= span/2
        val from = ceil(spectrum.size/2.0+lo/binHz).toInt().coerceAtLeast(0)
        val to = floor(spectrum.size/2.0+hi/binHz).toInt().coerceAtMost(spectrum.lastIndex)
        var peak = Float.NEGATIVE_INFINITY
        if(from <= to) {
            for(i in from..to) if(spectrum[i].isFinite()) peak = maxOf(peak,spectrum[i])
        } else {
            val nearest = (spectrum.size/2.0+(lo+hi)/2/binHz).roundToInt()
            if(nearest in spectrum.indices) peak = spectrum[nearest]
        }
        if(!peak.isFinite()) { reading = Reading(sequence,nowNanos,config); return reading }
        val power = 10.0.pow(peak/10.0)
        // GNU Radio single_pole_iir: y = alpha*x + (1-alpha)*previous.
        // GPL-3.0-or-later, FSF 2002/2006/2012; see third-party-squelch.md.
        // Adapt the coefficient to elapsed time; average POWER, never dB values.
        val elapsed = ((nowNanos-previousNanos)/1e9).coerceIn(0.0,1.0)
        val alpha = 1.0-exp(-elapsed/0.04)
        averagePower = if(averagePower.isNaN() || previousNanos==0L) power else alpha*power+(1-alpha)*averagePower
        previousNanos = nowNanos
        reading = Reading(sequence,nowNanos,config,peak,(10*log10(averagePower.coerceAtLeast(1e-30))).toFloat(),complete,true)
        return reading
    }
}

/** Experimental UI calibration, not a classifier that can prove a channel is idle. */
object SquelchRecommendation {
    data class Result(val thresholdDb: Float, val referenceDb: Float, val variationDb: Float, val samples: Int)
    fun from(levels: List<Float>): Result? {
        val sorted = levels.filter { it.isFinite() && it > -190f && it < -1f }.sorted()
        if(sorted.size < 8) return null
        fun percentile(fraction: Float) = sorted[((sorted.size-1)*fraction).roundToInt()]
        // Gqrx's current-level +3 dB approach; use a lower temporal quantile to
        // reduce contamination by intermittent transmissions in this UI measurement.
        val reference = percentile(0.2f)
        if(reference >= -10f) return null
        return Result(ceil(reference+3f).coerceIn(-119f,-1f),reference,
            percentile(0.9f)-percentile(0.1f),sorted.size)
    }
}
