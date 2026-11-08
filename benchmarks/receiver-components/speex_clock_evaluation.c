/* Component adapter/test only; no receiver or replacement DSP algorithm.
 * SpeexDSP 1.2.1 resampler + PipeWire 1.4.8 spa_dll controller; see README. */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include "speex_resampler.h"
#include "dll.h"

static double seconds(void) {
    struct timespec value;clock_gettime(CLOCK_MONOTONIC,&value);
    return value.tv_sec+value.tv_nsec*1e-9;
}
static void checked(int error) {
    if(error!=RESAMPLER_ERR_SUCCESS) { fprintf(stderr,"resampler error %d\n",error);exit(2); }
}
static void simulation(double ppm) {
    const unsigned rate=48000,period=960,steps=90000;
    const double target=rate*.200;
    double buffered=target,minimum=target,maximum=target,energy=0;
    /* Speex 1.2.1 converts fractional phases with 32-bit products. Keep both
     * sides <=48048 so a ratio change cannot overflow the pinned implementation. */
    uint64_t frames=0;unsigned previous_den=rate;
    float input[1920],output[4096];
    for(unsigned i=0;i<period;i++) {
        input[2*i]=.1f*sinf(2*M_PI*1000*i/rate);
        input[2*i+1]=.05f*sinf(2*M_PI*2300*i/rate);
    }
    int error=0;
    SpeexResamplerState* state=speex_resampler_init(2,rate,rate,5,&error);checked(error);
    if(!state) exit(2);
    struct spa_dll dll;spa_dll_init(&dll);spa_dll_set_bw(&dll,SPA_DLL_BW_MIN,period,rate);
    double begin=seconds();
    for(unsigned step=0;step<steps;step++) {
        double correction=spa_dll_update(&dll,buffered-target);
        correction=fmax(.999,fmin(1.001,correction));
        unsigned den=(unsigned)llround(rate*correction);
        if(step%10==0 && den!=previous_den) {
            checked(speex_resampler_set_rate_frac(state,rate,den,rate,rate));previous_den=den;
        }
        spx_uint32_t consumed=period,produced=2048;
        checked(speex_resampler_process_interleaved_float(state,input,&consumed,output,&produced));
        if(consumed!=period || produced>2048) exit(3);
        buffered+=produced-period*(1+ppm*1e-6);
        minimum=fmin(minimum,buffered);maximum=fmax(maximum,buffered);
        for(unsigned i=0;i<produced*2;i++) {
            if(!isfinite(output[i]) || fabsf(output[i])>.2f) exit(4);
            energy+=output[i]*output[i];
        }
        frames+=produced;
    }
    double rms=sqrt(energy/(2*frames));
    double uncompensated=target/rate*1000-ppm*1e-6*1800*1000;
    printf("{\"case\":\"known_clock_simulation\",\"ppm\":%.0f,\"signal_seconds\":1800,"
           "\"buffer_min_ms\":%.6f,\"buffer_max_ms\":%.6f,\"buffer_end_ms\":%.6f,"
           "\"uncorrected_end_ms\":%.6f,\"output_frames\":%llu,\"rms\":%.8f,\"elapsed_s\":%.3f}\n",
           ppm,minimum/rate*1000,maximum/rate*1000,buffered/rate*1000,uncompensated,
           (unsigned long long)frames,rms,seconds()-begin);
    speex_resampler_destroy(state);
    if(minimum/rate<.150 || maximum/rate>.300 || fabs(rms-sqrt(.003125))>.003) exit(5);
}
int main(void) {
    /* Accelerated sample-time simulation, not 30 minutes of real hardware.
     * The fixed consumer clock is known; platform timestamp estimation and
     * actual playback integration require separate tests before adoption. */
    simulation(100);simulation(-100);
    return 0;
}
