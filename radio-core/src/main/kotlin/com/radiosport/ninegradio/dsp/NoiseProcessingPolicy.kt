package com.radiosport.ninegradio.dsp

/** Frequency bands do not identify modulation. Only analog audio modes opt in. */
object NoiseProcessingPolicy {
    fun allows(mode: DemodMode): Boolean = when(mode) {
        DemodMode.AM,DemodMode.FM,DemodMode.WFM,DemodMode.WFM_STEREO,
        DemodMode.NFM,DemodMode.USB,DemodMode.LSB,DemodMode.DSB -> true
        else -> false
    }
}
