/* SPDX-License-Identifier: GPL-3.0-only
 * SDR++ CW processing graph and float AGC port, f10fb98af8b80de01de94e60c17608e4281e2515.
 * Channel filtering is performed by DspEngine using its existing resampler/FIR.
 * Scheduler/stream wrappers removed; set point and clipping ceiling use Android PCM full scale.
 * Original headers are retained under cpp/vendor/sdrpp/.
 */
package com.radiosport.ninegradio.dsp

import kotlin.math.*

class CwDemodulator(private val reverse: Boolean=false): Demodulator() {
    override val mode=if(reverse) DemodMode.CWR else DemodMode.CW
    private var phase=0.0
    private var amp=0f // SDR++ initGain=INFINITY
    private var rate=0
    private var attack=0f
    private var decay=0f
    override fun demodulate(iq: FloatArray,sampleRate: Int): FloatArray {
        if(rate!=sampleRate) {
            rate=sampleRate; attack=100f/rate; decay=5f/rate; amp=0f
        }
        val audio=FloatArray(iq.size/2)
        val step=2*PI*(if(reverse) -800 else 800)/rate
        // SDR++ FrequencyXlator -> ComplexToReal. The VFO has already filtered the carrier.
        for(i in audio.indices) {
            audio[i]=(iq[i*2]*cos(phase)-iq[i*2+1]*sin(phase)).toFloat()
            phase+=step
            if(phase>PI) phase-=2*PI else if(phase< -PI) phase+=2*PI
        }
        // SDR++ AGC<float>::process kernel; safe PCM setPoint=.35, ceiling=.95.
        for(i in audio.indices) {
            val input=abs(audio[i])
            var gain: Float
            if(input!=0f) {
                amp=if(input>amp) amp*(1-attack)+input*attack else amp*(1-decay)+input*decay
                gain=min(.35f/amp,10e6f)
            } else gain=1f
            if(input*gain>.95f) {
                var maximum=0f
                for(j in i until audio.size) maximum=max(maximum,abs(audio[j]))
                amp=maximum; gain=min(.35f/amp,10e6f)
            }
            audio[i]*=gain
        }
        return audio
    }
}
