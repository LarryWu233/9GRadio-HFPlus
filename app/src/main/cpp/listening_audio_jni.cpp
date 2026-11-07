/* Listening-only adapter: WDSP ANF + SDR++ AGC. See vendor/ and reuse notes. */
#include <jni.h>
#include <vector>
#include <algorithm>
#include <cmath>
#include "vendor/sdrpp/linked_audio_agc.h"
extern "C" {
#include "vendor/wdsp/anf.h"
}

struct ListeningAudio {
    ANF left, right;
    LinkedAudioAgc agc;
    std::vector<double> l, r;
    float agcWet=0, notchWet=0;
    int rate=0, mode=-1;
    ListeningAudio() {
        // WDSP RXA.c defaults, with user-adjustable adaptation speed (two_mu).
        left = create_anf(1,0,0,nullptr,nullptr,2048,64,16,0.0001,0.1,1,0,200,6.25e-12,6.25e-10,1,3);
        right = create_anf(1,0,0,nullptr,nullptr,2048,64,16,0.0001,0.1,1,0,200,6.25e-12,6.25e-10,1,3);
    }
    ~ListeningAudio() { destroy_anf(left); destroy_anf(right); }
    void reset() { flush_anf(left); flush_anf(right); agc.reset(); }
    void process(float* data,int count,int sampleRate,int modeId,bool leveling,bool notch,float limitDb,float speed) {
        const int frames=count/2;
        if(rate!=sampleRate || mode!=modeId) { reset(); rate=sampleRate; mode=modeId; }
        agc.configure(rate,limitDb);
        if(notch || notchWet>0) {
            l.resize(count); r.resize(count);
            for(int i=0;i<frames;++i) { l[2*i]=data[2*i]; l[2*i+1]=0; r[2*i]=data[2*i+1]; r[2*i+1]=0; }
            left->buff_size=right->buff_size=frames;
            left->in_buff=left->out_buff=l.data(); right->in_buff=right->out_buff=r.data();
            left->two_mu=right->two_mu=speed;
            xanf(left,0); xanf(right,0);
        } else { flush_anf(left); flush_anf(right); }
        const float ramp=1.0f/(0.02f*rate);
        for(int i=0;i<frames;++i) {
            agcWet=std::clamp(agcWet+(leveling?ramp:-ramp),0.0f,1.0f);
            notchWet=std::clamp(notchWet+(notch?ramp:-ramp),0.0f,1.0f);
            float dl=data[2*i], dr=data[2*i+1];
            float nl=notchWet>0 ? dl+notchWet*(static_cast<float>(l[2*i])-dl) : dl;
            float nr=notchWet>0 ? dr+notchWet*(static_cast<float>(r[2*i])-dr) : dr;
            float gain=agc.frameGain(std::max(std::fabs(nl),std::fabs(nr)),data,i,frames);
            gain=1+agcWet*(gain-1);
            data[2*i]=nl*gain; data[2*i+1]=nr*gain;
        }
    }
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_radiosport_ninegradio_dsp_ListeningAudioProcessor_nativeCreate(JNIEnv*,jobject) {
    return reinterpret_cast<jlong>(new ListeningAudio());
}
extern "C" JNIEXPORT void JNICALL
Java_com_radiosport_ninegradio_dsp_ListeningAudioProcessor_nativeDestroy(JNIEnv*,jobject,jlong handle) {
    delete reinterpret_cast<ListeningAudio*>(handle);
}
extern "C" JNIEXPORT void JNICALL
Java_com_radiosport_ninegradio_dsp_ListeningAudioProcessor_nativeProcess(JNIEnv* env,jobject,jlong handle,jfloatArray audio,
 jint rate,jint mode,jboolean leveling,jboolean notch,jfloat limit,jfloat speed) {
    auto* p=reinterpret_cast<ListeningAudio*>(handle);
    if(!p || rate<=0) return;
    int count=env->GetArrayLength(audio);
    auto* data=env->GetFloatArrayElements(audio,nullptr);
    if(!data) return;
    p->process(data,count,rate,mode,leveling,notch,limit,speed);
    env->ReleaseFloatArrayElements(audio,data,0);
}
