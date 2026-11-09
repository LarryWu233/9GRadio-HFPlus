package com.radiosport.ninegradio.dsp

/** Existing receiver rate policy, separated from Android/lifecycle orchestration.
 * Keeps alpha28 divisor selection, wide-FM override and decoder-rate behavior.
 */
object ChannelRatePolicy {
    const val DEFAULT_AUDIO_RATE=48000
    const val WFM_TARGET_BW=200000
    const val WFM_MAX_DEMOD_RATE=250000
    const val MIN_DEMOD_RATE=30000
    const val MIN_IF_BANDWIDTH_HZ=100
    fun wfmIfRate(deviceRate: Int, bandwidthHz: Int = 0): Int {
        val defaultRate=defaultWfmIfRate(deviceRate)
        if(bandwidthHz<=defaultRate*0.9) return defaultRate
        val required=kotlin.math.ceil(bandwidthHz/0.9).toInt().coerceAtMost(deviceRate)
        // An explicitly wide filter needs enough complex IF samples. Keep
        // the efficient broadcast path for Auto and ordinary 200 kHz FM.
        return (1..(deviceRate/required.coerceAtLeast(1)).coerceAtLeast(1))
            .filter { deviceRate%it==0 }.map { deviceRate/it }.minOrNull() ?: deviceRate
    }

    private fun defaultWfmIfRate(deviceRate: Int): Int {
        // Already within the WFM demodulator's operating range: no decimation
        // required.  Pass the device rate straight through (1:1 ComplexDecimator).
        // This covers the full low-rate band (240 000–300 000 Hz) and any device
        // rate that happens to land at or below the 250 kS/s ceiling.
        if (deviceRate <= WFM_MAX_DEMOD_RATE) {
            return deviceRate
        }

        // Search exact integer divisors of deviceRate whose quotient lands
        // in the [WFM_TARGET_BW, WFM_MAX_DEMOD_RATE] window, preferring the
        // quotient closest to WFM_TARGET_BW.
        var bestRate = -1
        var bestDist = Int.MAX_VALUE
        val nMax = deviceRate / WFM_TARGET_BW
        for (n in 1..maxOf(1, nMax + 1)) {
            if (deviceRate % n != 0) continue
            val ifRate = deviceRate / n
            if (ifRate < WFM_TARGET_BW || ifRate > WFM_MAX_DEMOD_RATE) continue
            val dist = kotlin.math.abs(ifRate - WFM_TARGET_BW)
            if (dist < bestDist) { bestDist = dist; bestRate = ifRate }
        }
        if (bestRate > 0) return bestRate

        // No exact divisor landed in the window — use a RATIONAL decimation
        // target. WFM_TARGET_BW (200 000 Hz) is itself ≤ WFM_MAX_DEMOD_RATE,
        // so this is always safely within the ceiling; ComplexDecimator's
        // PolyphaseResampler reduces (deviceRate : WFM_TARGET_BW) via GCD to
        // the appropriate rational interp/decim ratio.
        return minOf(WFM_TARGET_BW, WFM_MAX_DEMOD_RATE, deviceRate)
    }

    fun narrowIfRateFor(deviceRate: Int, sinkRate: Int, minimumRate: Int = MIN_DEMOD_RATE): Int {
        val floor = maxOf(sinkRate, minimumRate)
        val nMax = deviceRate / floor   // largest N that keeps ifRate >= floor
        var bestRate = deviceRate       // fallback: no-decimation (N=1)
        for (n in 1..maxOf(1, nMax)) {
            if (deviceRate % n == 0) {
                val ifRate = deviceRate / n
                if (ifRate >= floor) bestRate = ifRate
            }
        }
        return bestRate
    }

    fun narrow(deviceRate:Int,sinkRate:Int,mode:DemodMode,bandwidthHz:Int,cwWidth:Int):Int {
        if(mode==DemodMode.APRS) return if(bandwidthHz<=0) DEFAULT_AUDIO_RATE
            else bandwidthHz.coerceIn(MIN_IF_BANDWIDTH_HZ,DEFAULT_AUDIO_RATE)
        if(mode==DemodMode.CW || mode==DemodMode.CWR)
            return narrowIfRateFor(deviceRate,maxOf(8000,kotlin.math.ceil(cwWidth/0.8).toInt()),8000)
        val auto=narrowIfRateFor(deviceRate,sinkRate)
        return if(bandwidthHz<=0) auto else bandwidthHz.coerceIn(MIN_IF_BANDWIDTH_HZ,auto)
    }
}
