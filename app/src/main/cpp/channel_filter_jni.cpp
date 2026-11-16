/* Stateful Android adapter for the existing VOLK-derived dot-product kernel.
 * include/volk_android.h remains unchanged. The caller supplies the project's
 * existing FIR coefficients; reversed taps + retained overlap preserve the
 * causal FIR across arbitrary blocks. No USB, queues, resampling or audio IO. */
#include <jni.h>
#include <algorithm>
#include <vector>
#include "include/volk_android.h"

namespace {
struct ChannelFir {
    std::vector<float> taps,historyI,historyQ,workI,workQ;
    explicit ChannelFir(const float* h,int count):taps(h,h+count),historyI(count-1,0),historyQ(count-1,0) {
        std::reverse(taps.begin(),taps.end());
    }
    void process(float* iq,int frames) {
        const auto overlap=historyI.size();
        workI.resize(overlap+frames);workQ.resize(overlap+frames);
        std::copy(historyI.begin(),historyI.end(),workI.begin());
        std::copy(historyQ.begin(),historyQ.end(),workQ.begin());
        for(int i=0;i<frames;++i) { workI[overlap+i]=iq[2*i];workQ[overlap+i]=iq[2*i+1]; }
        for(int i=0;i<frames;++i) {
            gnuradio_volk_32f_x2_dot_prod_32f(&iq[2*i],workI.data()+i,taps.data(),taps.size());
            gnuradio_volk_32f_x2_dot_prod_32f(&iq[2*i+1],workQ.data()+i,taps.data(),taps.size());
        }
        std::copy(workI.end()-overlap,workI.end(),historyI.begin());
        std::copy(workQ.end()-overlap,workQ.end(),historyQ.begin());
    }
};
}
extern "C" JNIEXPORT jlong JNICALL Java_com_radiosport_ninegradio_dsp_NativeChannelFilter_nativeCreate(JNIEnv* e,jobject,jfloatArray coefficients) {
    const int count=e->GetArrayLength(coefficients);if(count<1) return 0;
    auto* h=e->GetFloatArrayElements(coefficients,nullptr);if(!h) return 0;
    auto* state=new ChannelFir(h,count);e->ReleaseFloatArrayElements(coefficients,h,JNI_ABORT);
    return reinterpret_cast<jlong>(state);
}
extern "C" JNIEXPORT void JNICALL Java_com_radiosport_ninegradio_dsp_NativeChannelFilter_nativeProcess(JNIEnv* e,jobject,jlong handle,jfloatArray iq) {
    auto* state=reinterpret_cast<ChannelFir*>(handle);if(!state) return;
    auto* data=e->GetFloatArrayElements(iq,nullptr);if(!data) return;
    state->process(data,e->GetArrayLength(iq)/2);e->ReleaseFloatArrayElements(iq,data,0);
}
extern "C" JNIEXPORT void JNICALL Java_com_radiosport_ninegradio_dsp_NativeChannelFilter_nativeDestroy(JNIEnv*,jobject,jlong handle) { delete reinterpret_cast<ChannelFir*>(handle); }
