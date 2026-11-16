/* Platform adapter around WDSP 2.11 xamd; PLL/sideband kernel is vendored unchanged.
 * Carrier AGC: SDR++ AGC<complex_t> envelope kernel, pinned f10fb98; gain ceiling,
 * low-level hold and normalized set point are adapter policy. LPF/DC/soft limiter
 * reuse the existing 9GRadio AM biquad/DC/tanh equations, with rate/BW parameters. */
#include <jni.h>
#include <vector>
#include <cmath>
#include <algorithm>
extern "C" {
#include "vendor/wdsp-am/standalone.h"
}
namespace {
constexpr double pi=3.14159265358979323846;
struct LowPass {
    double b0=1,b1=0,b2=0,a1=0,a2=0,x1=0,x2=0,y1=0,y2=0;
    void configure(double fc,int rate) {
        double w=2*pi*std::min(fc,rate*.45)/rate,c=std::cos(w),s=std::sin(w)/std::sqrt(2.0),inv=1/(1+s);
        b0=(1-c)*.5*inv;b1=2*b0;b2=b0;a1=-2*c*inv;a2=(1-s)*inv;
    }
    double process(double x) { double y=b0*x+b1*x1+b2*x2-a1*y1-a2*y2;x2=x1;x1=x;y2=y1;y1=y;return y; }
};
struct Processor {
    AMD amd=nullptr;
    int rate=0,mode=-1;
    double amp=.5,gain=1,wet=0,dc=0,previous=0,goodSeconds=0,badSeconds=0,lowSeconds=0;
    bool locked=false,everLocked=false;
    unsigned long long limited=0,total=0;
    LowPass lowpass;
    std::vector<double> input,output;
    ~Processor() { if(amd) destroy_amd(amd); }
    void configure(int fs,int selection) {
        if(rate==fs && mode==selection) return;
        if(amd) destroy_amd(amd);
        rate=fs;mode=selection;amp=.5;gain=1;wet=dc=previous=goodSeconds=badSeconds=lowSeconds=0;
        locked=everLocked=false;lowpass=LowPass();limited=total=0;
        // WDSP's allpass output labels use the opposite sideband convention to
        // this receiver's exp(+j*w*t) positive-frequency IQ. Adapt at the boundary.
        amd=create_amd(1,0,nullptr,nullptr,1,0,selection==2?1:selection==3?2:0,fs,-250,250,.707,200,.02,1.4);
    }
    void process(float* iq,float* out,int count,int fs,int selection,double cutoff,int agc,double maxGainDb,bool allow,double* stats) {
        configure(fs,selection);input.resize(count*2);output.resize(count*2);
        lowpass.configure(cutoff,fs);
        const double attack=1-std::exp(-1/((agc==2?.005:.02)*fs));
        const double decay=1-std::exp(-1/((agc==2?.15:1.0)*fs));
        const double maxGain=std::pow(10,maxGainDb/20);
        double envelopeSum=0;
        for(int i=0;i<count;i++) {
            double re=iq[2*i],im=iq[2*i+1],level=std::hypot(re,im);envelopeSum+=level;
            // SDR++ complex-envelope AGC; disallow gain growth below the RF guard.
            if(level>amp) amp=amp*(1-attack)+level*attack;
            else if(allow) amp=amp*(1-decay)+level*decay;
            double desired=agc==0?1:std::clamp(.5/std::max(amp,1e-12),.001,maxGain);
            lowSeconds=allow?0:lowSeconds+1.0/fs;
            if(!allow && lowSeconds>.5) desired=std::min(desired,1.0);
            gain+=((desired<gain)?attack:decay)*(desired-gain);
            if(agc==0) gain=1;
            input[2*i]=re*gain;input[2*i+1]=im*gain;
        }
        double offset=0;
        if(selection!=0) {
            amd->in_buff=input.data();amd->out_buff=output.data();amd->buff_size=count;xamd(amd);
            offset=amd->omega*fs/(2*pi);
            // Telemetry only: observe coherence/phase against the existing PLL;
            // never steer or replace its loop. Hold through short fades.
            double re=0,im=0,absSum=0;
            for(int i=0;i<count;i++) {
                double phase=amd->phs-amd->fil_out*(count-i),c=std::cos(phase),s=std::sin(phase);
                re+=input[2*i]*c+input[2*i+1]*s;im+=input[2*i+1]*c-input[2*i]*s;
                absSum+=std::hypot(input[2*i],input[2*i+1]);
            }
            bool good=allow && absSum>count*1e-8 && std::hypot(re,im)/absSum>.55 && std::fabs(std::atan2(im,re))<.3;
            double seconds=double(count)/fs;
            goodSeconds=good?goodSeconds+seconds:0;badSeconds=good?0:badSeconds+seconds;
            if(goodSeconds>=.2) locked=everLocked=true;
            if(badSeconds>=.35) locked=false;
        }
        const double dcAlpha=std::exp(-2*pi*.8/fs),ramp=1/(.02*fs);
        for(int i=0;i<count;i++) {
            const double envelope=std::hypot(input[2*i],input[2*i+1]);
            const double wanted=selection!=0 && locked?1:0;
            wet=std::clamp(wet+(wanted>0?ramp:-ramp),0.0,1.0);
            double detected=envelope;
            if(selection!=0) detected+=wet*(output[2*i]-envelope);
            dc=dcAlpha*(dc+detected-previous);previous=detected;
            double x=lowpass.process(dc);
            if(std::fabs(x)>.9) { ++limited;x=std::copysign(.9+.1*std::tanh((std::fabs(x)-.9)*10),x); }
            out[i]=std::isfinite(x)?float(x):0; ++total;
        }
        stats[0]=selection==0?0:locked?2:everLocked?3:1;stats[1]=offset;
        stats[2]=20*std::log10(std::max(gain,1e-12));stats[3]=double(limited);stats[4]=double(total);stats[5]=wet;
    }
};
}
extern "C" JNIEXPORT jlong JNICALL Java_com_radiosport_ninegradio_dsp_NativeAmProcessor_nativeCreate(JNIEnv*,jobject) { return reinterpret_cast<jlong>(new Processor()); }
extern "C" JNIEXPORT void JNICALL Java_com_radiosport_ninegradio_dsp_NativeAmProcessor_nativeDestroy(JNIEnv*,jobject,jlong h) { delete reinterpret_cast<Processor*>(h); }
extern "C" JNIEXPORT void JNICALL Java_com_radiosport_ninegradio_dsp_NativeAmProcessor_nativeProcess(JNIEnv* e,jobject,jlong h,jfloatArray iq,jfloatArray out,jint rate,jint mode,jdouble cutoff,jint agc,jfloat maxGain,jboolean allow,jdoubleArray telemetry) {
    auto* p=reinterpret_cast<Processor*>(h);if(!p) return;
    auto* in=e->GetFloatArrayElements(iq,nullptr);auto* output=e->GetFloatArrayElements(out,nullptr);double status[6]={};
    p->process(in,output,e->GetArrayLength(out),rate,mode,cutoff,agc,maxGain,allow,status);
    e->ReleaseFloatArrayElements(iq,in,JNI_ABORT);e->ReleaseFloatArrayElements(out,output,0);e->SetDoubleArrayRegion(telemetry,0,6,status);
}
