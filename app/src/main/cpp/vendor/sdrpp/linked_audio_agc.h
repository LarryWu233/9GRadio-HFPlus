/* SPDX-License-Identifier: GPL-3.0-only
 * Adapted from SDR++ core/src/dsp/loop/agc.h, commit
 * f10fb98af8b80de01de94e60c17608e4281e2515 (original in agc-original.h).
 * Changes: remove stream/thread plumbing; detect max(L,R) to link stereo;
 * bound attenuation as well as amplification. Envelope and lookahead retained.
 */
#pragma once
#include <algorithm>
#include <cmath>

class LinkedAudioAgc {
    // Conservative listening reference: -32 dBFS envelope, with bounded gain.
    static constexpr float target = 0.025f;
    float amp = target;
    float attack = 0, decay = 0;
    float maxGain = 4;
public:
    void configure(int rate, float limitDb) {
        attack = 1.0f - std::exp(-1.0f / (0.005f * rate));
        decay = 1.0f - std::exp(-1.0f / (0.4f * rate));
        maxGain = std::pow(10.0f, limitDb / 20.0f);
    }
    void reset() { amp = target; }
    float frameGain(float level, const float* data, int frame, int frames) {
        float gain;
        if (level != 0.0f) {
            amp = level > amp ? amp * (1 - attack) + level * attack
                              : amp * (1 - decay) + level * decay;
            gain = std::min(target / amp, maxGain);
        } else gain = 1;
        if (level * gain > 0.9f) {
            float maxAmp = 0;
            for (int j = frame; j < frames; ++j)
                maxAmp = std::max(maxAmp, std::max(std::fabs(data[2*j]), std::fabs(data[2*j+1])));
            amp = maxAmp;
            gain = std::min(target / amp, maxGain);
        }
        return std::max(gain, 1.0f / maxGain);
    }
};
