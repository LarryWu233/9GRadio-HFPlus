/* Copyright 2011,2012 Free Software Foundation, Inc.
 * SPDX-License-Identifier: GPL-3.0-or-later
 * GNU Radio moving_averager_f and dc_blocker_ff short-form kernel.
 * Framework extraction + PCM16 adapter; original files retained under vendor/gnuradio.
 */
#include <deque>
#include <algorithm>
#include <cmath>
namespace {
class moving_averager_f
{
public:
    moving_averager_f(int D);

    float filter(float x);
    float delayed_sig() { return d_out; }

private:
    int d_length;
    float d_out, d_out_d1, d_out_d2;
    std::deque<float> d_delay_line;
};

moving_averager_f::moving_averager_f(int D)
    : d_length(D), d_out(0), d_out_d1(0), d_out_d2(0), d_delay_line(d_length - 1, 0)
{
}

float moving_averager_f::filter(float x)
{
    d_out_d1 = d_out;
    d_delay_line.push_back(x);
    d_out = d_delay_line[0];
    d_delay_line.pop_front();

    float y = x - d_out_d1 + d_out_d2;
    d_out_d2 = y;

    return (y / (float)(d_length));
}



}
extern "C" void cw_remove_dc(short *pcm, int count) {
    static moving_averager_f ma0(128), ma1(128);
    for (int i = 0; i < count; ++i) {
        float y1 = ma0.filter(pcm[i]);
        float y2 = ma1.filter(y1);
        float out = ma0.delayed_sig() - y2;
        pcm[i] = static_cast<short>(std::nearbyint(std::clamp(out, -32768.0f, 32767.0f)));
    }
}
