package com.radiosport.ninegradio.source

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.radiosport.ninegradio.debug.DebugBus
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*

class AirspyHfSource(private val usbManager: UsbManager, val usbDevice: UsbDevice) : IqSource {
    companion object {
        fun isSupported(device: UsbDevice) = device.vendorId == 0x03eb && device.productId == 0x800c
        init { System.loadLibrary("ninegradio_airspyhf") }
    }
    private external fun nativeOpen(fd: Int): Long
    private external fun nativeClose(handle: Long)
    private external fun nativeStart(handle: Long): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeRates(handle: Long): IntArray
    private external fun nativeRate(handle: Long, rate: Int): Boolean
    private external fun nativeFrequency(handle: Long, hz: Long): Boolean
    private external fun nativeAgc(handle: Long, enabled: Boolean): Boolean
    private external fun nativeAttenuation(handle: Long, index: Int): Boolean
    private external fun nativePpm(handle: Long, ppm: Int): Boolean

    private val samples = com.radiosport.ninegradio.pipeline.IqStream()
    private val clock = com.radiosport.ninegradio.pipeline.CaptureClock()
    override val iqFlow = samples.flow
    override val iqSubscriberCount get() = samples.subscriberCount
    private val status = MutableStateFlow(IqSource.SourceStatus(centerFreqHz = 10_000_000, sampleRate = 768000))
    override val statusFlow = status.asStateFlow()
    private var connection: UsbDeviceConnection? = null
    @Volatile private var handle = 0L
    @Volatile private var streaming = false
    @Volatile private var caps = DeviceCapabilities.airspy()
    override val capabilities get() = caps

    // Called from the driver's consumer thread. JNI supplies a fresh owned array.
    @Suppress("unused")
    private fun onIq(values: FloatArray, dropped: Long) {
        val state = status.value
        val meta = clock.stamp(values.size / 2, state.sampleRate, state.centerFreqHz, driverLostSamples = dropped)
        samples.offer(IqBlock.Float32(values, meta.sequence, meta))
        DebugBus.recordIqTransfer(values.size * 4, values.size * 4, 0L)
        DebugBus.tick(DebugBus.STAGE_IQ_STREAM)
        if (dropped > 0) DebugBus.setExtra(DebugBus.STAGE_IQ_STREAM, "HF_DRIVER_DROPPED_SAMPLES", dropped.toString())
    }

    @Synchronized override fun open(): Boolean {
        if (handle != 0L) return true
        val conn = usbManager.openDevice(usbDevice) ?: return false
        val opened = nativeOpen(conn.fileDescriptor)
        if (opened == 0L) { conn.close(); return false }
        connection = conn
        handle = opened
        clock.restart()
        val rates = nativeRates(handle).filter { it > 0 }.distinct().sorted()
        if (rates.isEmpty()) { close(); return false }
        caps = DeviceCapabilities.airspy(rates)
        val rate = if (768000 in rates) 768000 else rates.lastOrNull { it <= 768000 } ?: rates.first()
        if (!nativeRate(handle, rate) || !nativeFrequency(handle, 10_000_000) || !nativeAgc(handle, true)) {
            close(); return false
        }
        status.value = IqSource.SourceStatus(connected = true, centerFreqHz = 10_000_000,
            sampleRate = rate, tunerAgcEnabled = true, hardwareAgcEnabled = false)
        android.util.Log.i("AirspyHfSource","Opened source=${System.identityHashCode(this)} rate=$rate")
        return true
    }
    @Synchronized override fun startStreaming(bufferSize: Int) {
        if (handle == 0L || streaming) return
        streaming = nativeStart(handle)
        if (!streaming) status.value = status.value.copy(error = "Airspy HF+ stream failed", streamRestartRequired = true)
    }
    @Synchronized override fun stopStreaming() {
        if (handle != 0L && streaming) nativeStop(handle)
        streaming = false
    }
    @Synchronized override fun close() {
        stopStreaming()
        if (handle != 0L) nativeClose(handle)
        handle = 0
        connection?.close()
        connection = null
        status.value = status.value.copy(connected = false)
        samples.complete()
    }
    @Synchronized override fun setCenterFrequency(freqHz: Long): Boolean {
        if (handle == 0L || !caps.supportsFrequency(freqHz)) return false
        if (!nativeFrequency(handle, freqHz)) return false
        status.value = status.value.copy(centerFreqHz = freqHz)
        return true
    }
    override fun getCenterFrequency() = status.value.centerFreqHz
    @Synchronized override fun setSampleRate(rateHz: Int): Boolean {
        if (handle == 0L || rateHz !in caps.sampleRates) return false
        if (rateHz == getSampleRate()) return true
        val resume = streaming
        stopStreaming()
        val ok = nativeRate(handle, rateHz)
        android.util.Log.i("AirspyHfSource","Rate source=${System.identityHashCode(this)} ${getSampleRate()}->$rateHz resume=$resume result=$ok")
        if (ok) status.value = status.value.copy(sampleRate = rateHz)
        if (resume) startStreaming()
        return ok
    }
    override fun getSampleRate() = status.value.sampleRate
    override fun setGainMode(mode: Int) = setTunerAgcEnabled(mode == 0)
    @Synchronized override fun setGain(gainIndex: Int) {
        val index = gainIndex.coerceIn(0, 8)
        if (handle != 0L && nativeAttenuation(handle, index))
            status.value = status.value.copy(gainDb10 = -index * 60)
    }
    override fun getGainIndex() = -status.value.gainDb10 / 60
    override fun getGainDb() = status.value.gainDb10 / 10f
    override fun getGainCount() = 9
    @Synchronized override fun setTunerAgcEnabled(enable: Boolean) {
        if (handle != 0L && nativeAgc(handle, enable)) status.value = status.value.copy(tunerAgcEnabled = enable, gainMode = if (enable) 0 else 1)
    }
    override fun setHardwareAgcEnabled(enable: Boolean) = Unit
    override fun setBiasTee(enable: Boolean) = Unit
    override fun setDirectSampling(mode: Int) = Unit
    override fun getDirectSampling() = 0
    @Synchronized override fun setPpmCorrection(ppm: Int) {
        if (handle != 0L && nativePpm(handle, ppm)) status.value = status.value.copy(ppmCorrection = ppm)
    }
    override fun getPpmCorrection() = status.value.ppmCorrection
    override fun getSourceName() = "Airspy HF+ Discovery"
    override fun isOpen() = handle != 0L
}
