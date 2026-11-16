/* WDSP 2.11 interpolating wideband NB adapter; no new detection/repair kernel. */
#include <jni.h>
#include <vector>
#include <algorithm>
#include <cmath>
#include "vendor/sdrpp/iq_amplitude_blanker.h"
extern "C" {
#include "vendor/wdsp-nb/standalone.h"
}

struct IqBlanker {
    NOB nb=nullptr;
    IqAmplitudeBlanker amplitude;
    bool preservePhase=false;
    int rate=0, delaySamples=0;
    size_t position=0;
    float wet=0;
    std::vector<double> scratch;
    std::vector<float> dry;
    ~IqBlanker() { if(nb) destroy_nob(nb); }
    void initialize(int sampleRate,bool fm) {
        if(nb) { destroy_nob(nb); nb=nullptr; }
        rate=sampleRate;
        preservePhase=fm;
        amplitude.configure(rate); amplitude.reset();
        // Upstream mode 4: interpolation, short cosine transitions and hold.
        if(!fm) nb=create_nob(1,0,nullptr,nullptr,rate,4,0.00005,0.00005,0.00005,0.0001,0.002,0.01,8.0);
        delaySamples=nb ? nb->in_idx-nb->out_idx : 0;
        dry.assign(delaySamples*2,0);
        position=0; wet=0;
    }
    void reset() { if(nb) flush_nob(nb); amplitude.reset(); std::fill(dry.begin(),dry.end(),0); position=0; wet=0; }
    int process(float* iq,int count,int sampleRate,bool reinitialize,bool enabled,bool fm,float threshold) {
        if(rate!=sampleRate || preservePhase!=fm) initialize(sampleRate,fm);
        else if(reinitialize) reset();
        const float ramp=1.0f/(0.002f*rate);
        if(fm) {
            if(!enabled && wet==0.0f) {
                // Keep SDR++'s amplitude estimator warm, exactly as before.
                // With a fully dry mix its returned gain is unused: do not
                // calculate the ramp/mix or rewrite unchanged IQ samples.
                for(int i=0;i<count;i+=2) (void)amplitude.gain(iq[i],iq[i+1],threshold);
                return 0;
            }
            int modified=0;
            for(int i=0;i<count;i+=2) {
                wet=std::clamp(wet+(enabled?ramp:-ramp),0.0f,1.0f);
                float gain=1.0f+wet*(amplitude.gain(iq[i],iq[i+1],threshold)-1.0f);
                if(gain<0.999999f) ++modified;
                iq[i]*=gain; iq[i+1]*=gain;
            }
            return modified;
        }
        scratch.resize(count);
        for(int i=0;i<count;++i) scratch[i]=iq[i];
        nb->threshold=threshold;
        nb->in=nb->out=scratch.data(); nb->buffsize=count/2;
        xnob(nb);
        int modified=0;
        for(int i=0;i<count;i+=2) {
            const float delayedI=dry[position], delayedQ=dry[position+1];
            dry[position]=iq[i]; dry[position+1]=iq[i+1];
            position+=2; if(position==dry.size()) position=0;
            wet=std::clamp(wet+(enabled?ramp:-ramp),0.0f,1.0f);
            const float outI=delayedI+wet*(static_cast<float>(scratch[i])-delayedI);
            const float outQ=delayedQ+wet*(static_cast<float>(scratch[i+1])-delayedQ);
            if(wet>0 && (std::fabs(outI-delayedI)>1e-7f || std::fabs(outQ-delayedQ)>1e-7f)) ++modified;
            iq[i]=outI; iq[i+1]=outQ;
        }
        return modified;
    }
};

extern "C" JNIEXPORT jlong JNICALL Java_com_radiosport_ninegradio_dsp_IqNoiseBlanker_nativeCreate(JNIEnv*,jobject) {
    return reinterpret_cast<jlong>(new IqBlanker());
}
extern "C" JNIEXPORT void JNICALL Java_com_radiosport_ninegradio_dsp_IqNoiseBlanker_nativeDestroy(JNIEnv*,jobject,jlong handle) {
    delete reinterpret_cast<IqBlanker*>(handle);
}
extern "C" JNIEXPORT jint JNICALL Java_com_radiosport_ninegradio_dsp_IqNoiseBlanker_nativeDelay(JNIEnv*,jobject,jlong handle) {
    return reinterpret_cast<IqBlanker*>(handle)->delaySamples;
}
extern "C" JNIEXPORT jint JNICALL Java_com_radiosport_ninegradio_dsp_IqNoiseBlanker_nativeProcess(
    JNIEnv* env,jobject,jlong handle,jfloatArray audio,jint rate,jboolean reset,jboolean enabled,jboolean fm,jfloat threshold) {
    auto* nb=reinterpret_cast<IqBlanker*>(handle);
    if(!nb || rate<8000 || rate>10000000) return 0;
    auto count=env->GetArrayLength(audio);
    if(count%2!=0) return 0;
    auto* iq=env->GetFloatArrayElements(audio,nullptr);
    if(!iq) return 0;
    int modified=nb->process(iq,count,rate,reset,enabled,fm,threshold);
    env->ReleaseFloatArrayElements(audio,iq,0);
    return modified;
}
