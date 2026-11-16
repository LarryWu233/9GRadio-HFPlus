#include <jni.h>
#include <memory>
#include <new>
#include <vector>
extern "C" {
#include "pffft.h"
}

namespace {
struct Plan {
    const int size;
    std::vector<double> real, imaginary;
    PFFFT_Setup* setup;
    float* data;
    float* work;
    explicit Plan(int n) : size(n), real(n), imaginary(n), setup(pffft_new_setup(n,PFFFT_COMPLEX)),
        data(static_cast<float*>(pffft_aligned_malloc(2*n*sizeof(float)))),
        work(static_cast<float*>(pffft_aligned_malloc(2*n*sizeof(float)))) {}
    ~Plan() {
        if(setup) pffft_destroy_setup(setup);
        pffft_aligned_free(data);pffft_aligned_free(work);
    }
};
void fail(JNIEnv* env,const char* type,const char* message) { env->ThrowNew(env->FindClass(type),message); }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_radiosport_ninegradio_dsp_PffftNative_create(JNIEnv* env,jobject,jint n) {
    if(n<256 || n>65536 || (n&(n-1))) { fail(env,"java/lang/IllegalArgumentException","Invalid FFT size");return 0; }
    try {
        auto plan=std::make_unique<Plan>(n);
        if(!plan->setup || !plan->data || !plan->work) throw std::bad_alloc();
        return reinterpret_cast<jlong>(plan.release());
    } catch(const std::bad_alloc&) { fail(env,"java/lang/OutOfMemoryError","FFT plan allocation failed");return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_radiosport_ninegradio_dsp_PffftNative_transform(JNIEnv* env,jobject,jlong handle,jdoubleArray re,jdoubleArray im) {
    auto* p=reinterpret_cast<Plan*>(handle);
    if(!p || !re || !im || env->GetArrayLength(re)!=p->size || env->GetArrayLength(im)!=p->size) {
        fail(env,"java/lang/IllegalArgumentException","FFT array mismatch");return;
    }
    env->GetDoubleArrayRegion(re,0,p->size,p->real.data());if(env->ExceptionCheck()) return;
    env->GetDoubleArrayRegion(im,0,p->size,p->imaginary.data());if(env->ExceptionCheck()) return;
    for(int i=0;i<p->size;i++) { p->data[2*i]=static_cast<float>(p->real[i]);p->data[2*i+1]=static_cast<float>(p->imaginary[i]); }
    pffft_transform_ordered(p->setup,p->data,p->data,p->work,PFFFT_FORWARD);
    for(int i=0;i<p->size;i++) { p->real[i]=p->data[2*i];p->imaginary[i]=p->data[2*i+1]; }
    env->SetDoubleArrayRegion(re,0,p->size,p->real.data());if(env->ExceptionCheck()) return;
    env->SetDoubleArrayRegion(im,0,p->size,p->imaginary.data());
}

extern "C" JNIEXPORT void JNICALL
Java_com_radiosport_ninegradio_dsp_PffftNative_destroy(JNIEnv*,jobject,jlong handle) {
    delete reinterpret_cast<Plan*>(handle);
}
