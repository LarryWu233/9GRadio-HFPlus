package com.radiosport.ninegradio.source

import com.radiosport.ninegradio.dsp.DemodMode
import kotlin.math.abs

data class DeviceCapabilities(
    val id: String,
    val frequencyRanges: List<LongRange>,
    val sampleRates: List<Int>,
    val directSampling: Boolean = true,
    val biasTee: Boolean = true,
    val hardwareAgc: Boolean = true,
    val floatIq: Boolean = false,
    val blockSamples: Int = 8192
) {
    fun supportsFrequency(hz: Long) = frequencyRanges.any { hz in it }
    fun supportsBrowsing(hz: Long) = hz in frequencyRanges.minOf { it.first }..frequencyRanges.maxOf { it.last }
    fun browseFrequency(target: Long) = target.coerceIn(frequencyRanges.minOf { it.first }, frequencyRanges.maxOf { it.last })
    fun nearestFrequency(hz: Long): Long = frequencyRanges
        .map { hz.coerceIn(it.first, it.last) }.minBy { abs(it - hz) }

    /** Relative tuning stops at this band's edge rather than jumping across a gap. */
    fun stepFrequency(current: Long, target: Long): Long = frequencyRanges
        .firstOrNull { current in it }?.let { target.coerceIn(it.first, it.last) }
        ?: nearestFrequency(target)

    fun nearestRate(rate: Int) = sampleRates.minBy { abs(it.toLong() - rate) }
    fun maxChannelBandwidth(rate: Int): Int = minOf((rate*0.9).toInt(),if(id=="airspyhf") 660000 else Int.MAX_VALUE)
    fun scanRanges(start: Long, stop: Long): List<LongRange> = frequencyRanges.mapNotNull {
        val lo = maxOf(start, it.first)
        val hi = minOf(stop, it.last)
        if (lo <= hi) lo..hi else null
    }
    fun moduleSupport()=com.radiosport.ninegradio.modules.ModuleSourceSupport(frequencyRanges,sampleRates,
        setOf(if(floatIq) com.radiosport.ninegradio.pipeline.IqSampleFormat.FLOAT_32 else com.radiosport.ninegradio.pipeline.IqSampleFormat.UNSIGNED_8),
        sampleRates.maxOrNull()?.let { maxChannelBandwidth(it) } ?: 0)
    fun modeAvailability(mode:DemodMode)=com.radiosport.ninegradio.modules.RadioModules.mode(mode).availability(moduleSupport())
    fun supportsMode(mode:DemodMode)=modeAvailability(mode).available

    companion object {
        val RTL = DeviceCapabilities("rtl", listOf(500_000L..1_766_000_000L),
            com.radiosport.ninegradio.usb.RtlSdrDevice.SAMPLE_RATES.toList())
        fun airspy(rates: List<Int> = listOf(768000)) = DeviceCapabilities(
            "airspyhf", listOf(500L..31_000_000L, 64_000_000L..260_000_000L), rates,
            directSampling = false, biasTee = false, hardwareAgc = false, floatIq = true, blockSamples = 4096)
    }
}
