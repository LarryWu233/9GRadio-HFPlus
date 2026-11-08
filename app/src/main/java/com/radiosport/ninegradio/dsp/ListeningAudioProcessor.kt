package com.radiosport.ninegradio.dsp

/** Owns mature native processors only on the listening branch; never decoder input. */
class ListeningAudioProcessor : AutoCloseable {
    data class Settings(val leveling: Boolean=false, val limitDb: Float=12f,
                        val notch: Boolean=false, val notchSpeed: Float=0.005f)
    @Volatile var settings=Settings()
    private var handle=0L
    private var closed=false
    @Synchronized fun process(stereo: FloatArray, rate: Int, mode: DemodMode) {
        if(closed || !NoiseProcessingPolicy.allows(mode) || !NativeDsp.isNativeAvailable()) return
        val config=settings
        if(handle==0L) {
            if(!config.leveling && !config.notch) return
            handle=nativeCreate()
        }
        // Preserve intentional CW tones. Data decoder audio never passes here.
        val notchAllowed=mode!=DemodMode.CW && mode!=DemodMode.CWR && mode!=DemodMode.RAW
        nativeProcess(handle,stereo,rate,mode.ordinal,config.leveling,config.notch && notchAllowed,
            config.limitDb.coerceIn(0f,18f),config.notchSpeed.coerceIn(0.0001f,0.02f))
    }
    @Synchronized fun reset() {
        if(handle!=0L) nativeDestroy(handle)
        handle=0
    }
    @Synchronized override fun close() {
        closed=true
        if(handle!=0L) nativeDestroy(handle)
        handle=0
    }
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeProcess(handle: Long,audio: FloatArray,rate: Int,mode: Int,
        leveling: Boolean,notch: Boolean,limit: Float,speed: Float)
}
