/* SPDX-License-Identifier: GPL-3.0-only
 * SDR++ core/src/dsp/noise_reduction/noise_blanker.h, pinned commit
 * f10fb98af8b80de01de94e60c17608e4281e2515; original alongside this file.
 * Adaptation: independent per-sample gain API for interleaved float IQ.
 * The amplitude estimator, threshold and positive common gain are retained.
 */
#pragma once
#include <cmath>
class IqAmplitudeBlanker {
    float amp=1.0f, rate=0, invRate=1;
public:
    void configure(int sampleRate) {
        rate=1.0f-std::exp(-1.0f/(0.01f*sampleRate)); invRate=1.0f-rate;
    }
    void reset() { amp=1.0f; }
    float gain(float re,float im,float level) {
        float inAmp=std::sqrt(re*re+im*im);
        float gain=1.0f;
        if(inAmp!=0.0f) {
            amp=amp*invRate+inAmp*rate;
            float excess=inAmp/amp;
            if(excess>level) gain=1.0f/excess;
        }
        return gain;
    }
};
