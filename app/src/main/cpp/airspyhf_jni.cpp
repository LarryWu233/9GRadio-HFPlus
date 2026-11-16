#include <jni.h>
#include <cstdint>
#include <mutex>
#include <vector>
#include "vendor/airspyhf/libairspyhf/src/airspyhf.h"

static JavaVM* vm;
struct AttachedEnv {
    JNIEnv* env = nullptr;
    bool attached = false;
    AttachedEnv() {
        if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
            attached = vm->AttachCurrentThread(&env, nullptr) == JNI_OK;
        }
    }
    ~AttachedEnv() { if (attached) vm->DetachCurrentThread(); }
};
struct Receiver {
    airspyhf_device_t* device = nullptr;
    jobject owner = nullptr;
    jmethodID callback = nullptr;
    int32_t factory_ppb = 0;
};
static std::mutex controls;
static Receiver* receiver(jlong handle) { return reinterpret_cast<Receiver*>(handle); }
static int samples(airspyhf_transfer_t* transfer) {
    auto* r = static_cast<Receiver*>(transfer->ctx);
    thread_local AttachedEnv thread;
    JNIEnv* env = thread.env;
    if (!env) return -1;
    jfloatArray data = env->NewFloatArray(transfer->sample_count * 2);
    if (!data) { env->ExceptionClear(); return -1; }
    env->SetFloatArrayRegion(data, 0, transfer->sample_count * 2,
                            reinterpret_cast<const jfloat*>(transfer->samples));
    env->CallVoidMethod(r->owner, r->callback, data, static_cast<jlong>(transfer->dropped_samples));
    env->DeleteLocalRef(data);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return -1; }
    return 0;
}
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* value, void*) { vm = value; return JNI_VERSION_1_6; }
#define JNI_METHOD(name) Java_com_radiosport_ninegradio_source_AirspyHfSource_##name
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(nativeOpen)(JNIEnv* env, jobject owner, jint fd) {
    std::lock_guard<std::mutex> lock(controls);
    auto* r = new Receiver();
    if (airspyhf_open_fd(&r->device, fd) != AIRSPYHF_SUCCESS) { delete r; return 0; }
    r->owner = env->NewGlobalRef(owner);
    jclass type = env->GetObjectClass(owner);
    r->callback = env->GetMethodID(type, "onIq", "([FJ)V");
    env->DeleteLocalRef(type);
    if (!r->owner || !r->callback) {
        env->ExceptionClear();
        airspyhf_close(r->device);
        if (r->owner) env->DeleteGlobalRef(r->owner);
        delete r; return 0;
    }
    airspyhf_get_calibration(r->device, &r->factory_ppb);
    return reinterpret_cast<jlong>(r);
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeClose)(JNIEnv* env, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(controls);
    auto* r = receiver(handle);
    airspyhf_close(r->device); // Stops and joins native threads before releasing the Java owner.
    env->DeleteGlobalRef(r->owner);
    delete r;
}
extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(nativeStart)(JNIEnv*, jobject, jlong h) {
    std::lock_guard<std::mutex> lock(controls);
    auto* r = receiver(h);
    return airspyhf_start(r->device, samples, r) == AIRSPYHF_SUCCESS;
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeStop)(JNIEnv*, jobject, jlong h) {
    std::lock_guard<std::mutex> lock(controls);
    airspyhf_stop(receiver(h)->device);
}
extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(nativeStreaming)(JNIEnv*, jobject, jlong h) {
    std::lock_guard<std::mutex> lock(controls);
    return airspyhf_is_streaming(receiver(h)->device) != 0;
}
extern "C" JNIEXPORT jintArray JNICALL JNI_METHOD(nativeRates)(JNIEnv* env, jobject, jlong h) {
    std::lock_guard<std::mutex> lock(controls);
    uint32_t count = 0;
    if (airspyhf_get_samplerates(receiver(h)->device, &count, 0) || count == 0 || count > 128) return env->NewIntArray(0);
    std::vector<uint32_t> rates(count);
    if (airspyhf_get_samplerates(receiver(h)->device, rates.data(), count)) return env->NewIntArray(0);
    auto output = env->NewIntArray(count);
    if (output) env->SetIntArrayRegion(output, 0, count, reinterpret_cast<jint*>(rates.data()));
    return output;
}
extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(nativeRate)(JNIEnv*, jobject, jlong h, jint rate) {
    std::lock_guard<std::mutex> lock(controls);
    return airspyhf_set_samplerate(receiver(h)->device, rate) == AIRSPYHF_SUCCESS;
}
extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(nativeFrequency)(JNIEnv*, jobject, jlong h, jlong hz) {
    std::lock_guard<std::mutex> lock(controls);
    return airspyhf_set_freq(receiver(h)->device, static_cast<uint32_t>(hz)) == AIRSPYHF_SUCCESS;
}
extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(nativeAgc)(JNIEnv*, jobject, jlong h, jboolean enabled) {
    std::lock_guard<std::mutex> lock(controls);
    return airspyhf_set_hf_agc(receiver(h)->device, enabled) == AIRSPYHF_SUCCESS;
}
extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(nativeAttenuation)(JNIEnv*, jobject, jlong h, jint index) {
    std::lock_guard<std::mutex> lock(controls);
    return airspyhf_set_hf_att(receiver(h)->device, index) == AIRSPYHF_SUCCESS;
}
extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(nativePpm)(JNIEnv*, jobject, jlong h, jint ppm) {
    std::lock_guard<std::mutex> lock(controls);
    auto* r = receiver(h);
    return airspyhf_set_calibration(r->device, r->factory_ppb + ppm * 1000) == AIRSPYHF_SUCCESS;
}
