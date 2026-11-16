/* Test adapter for pinned liquid-dsp msresamp_crcf. No replacement filter design. */
#include <complex.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include "liquid.h"

enum { INPUT_RATE=2400000, OUTPUT_RATE=48000, BLOCK=4096 };
static double seconds(void) {
    struct timespec value;clock_gettime(CLOCK_MONOTONIC,&value);
    return value.tv_sec+value.tv_nsec*1e-9;
}
static double tone(float input_hz,float measured_hz,int irregular) {
    msresamp_crcf q=msresamp_crcf_create((float)OUTPUT_RATE/INPUT_RATE,80);
    if(!q) exit(2);
    float complex input[BLOCK],output[BLOCK];
    unsigned source_position=0,output_position=0,used=0;double real=0,imaginary=0;
    while(source_position<INPUT_RATE/2) {
        unsigned n=irregular ? 1+(source_position*37u+19u)%BLOCK : BLOCK;
        if(n>INPUT_RATE/2-source_position) n=INPUT_RATE/2-source_position;
        for(unsigned i=0;i<n;i++) input[i]=(float complex)cexp(I*(2*M_PI*input_hz*(source_position+i)/INPUT_RATE));
        unsigned expected=msresamp_crcf_get_num_output(q,n),produced=0;
        if(expected>BLOCK) exit(3);
        if(msresamp_crcf_execute(q,input,n,output,&produced)!=0 || produced!=expected) exit(4);
        for(unsigned i=0;i<produced;i++,output_position++) if(output_position>2048) {
            double phase=-2*M_PI*measured_hz*output_position/OUTPUT_RATE;
            double complex value=(double complex)output[i]*cexp(I*phase);
            real+=creal(value);imaginary+=cimag(value);used++;
        }
        source_position+=n;
    }
    msresamp_crcf_destroy(q);
    if(abs((int)output_position-OUTPUT_RATE/2)>2 || !used) exit(5);
    return 20*log10(hypot(real,imaginary)/used+1e-30);
}
int main(void) {
    double pass=tone(9000,9000,0),pass_chunks=tone(9000,9000,1);
    double alias=tone(59000,11000,0);
    printf("{\"case\":\"iq_resampling\",\"input_rate\":%d,\"output_rate\":%d,"
           "\"pass_9khz_db\":%.6f,\"pass_irregular_db\":%.6f,\"alias_59khz_to_11khz_db\":%.6f}\n",
           INPUT_RATE,OUTPUT_RATE,pass,pass_chunks,alias);
    if(fabs(pass)>.3 || fabs(pass-pass_chunks)>.01 || alias>-60) return 6;
    float complex input[BLOCK],output[BLOCK];
    for(unsigned i=0;i<BLOCK;i++) input[i]=.1f*cexpf(I*(float)(2*M_PI*9000*i/INPUT_RATE));
    for(unsigned trial=0;trial<5;trial++) {
        msresamp_crcf q=msresamp_crcf_create((float)OUTPUT_RATE/INPUT_RATE,80);
        double begin=seconds();unsigned long samples=0;
        for(unsigned block=0;block<2000;block++) {
            unsigned produced=0;
            if(msresamp_crcf_get_num_output(q,BLOCK)>BLOCK) return 7;
            if(msresamp_crcf_execute(q,input,BLOCK,output,&produced)!=0) return 8;
            samples+=produced;
        }
        printf("{\"case\":\"iq_timing\",\"trial\":%u,\"ns_per_input_sample\":%.5f,\"output_samples\":%lu}\n",
            trial,(seconds()-begin)*1e9/(BLOCK*2000.0),samples);
        msresamp_crcf_destroy(q);
    }
    return 0;
}
