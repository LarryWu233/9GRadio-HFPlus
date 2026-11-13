package com.radiosport.ninegradio.source

import com.radiosport.ninegradio.dsp.DemodMode
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/** Paces raw recordings at their recorded sample rate; no uint8 conversion for CF32. */
class FileIqSource(private val file: File, private val rate: Int, private val frequency: Long,
                   mode: DemodMode = DemodMode.AM) : IqSource {
    private val info=com.radiosport.ninegradio.recording.IqRecordingInfo.read(file,frequency,mode)
    val mode=info.mode
    val dialFrequencyHz=info.dialHz
    private val samples = com.radiosport.ninegradio.pipeline.IqStream(lossless = true)
    private val captureClock = com.radiosport.ninegradio.pipeline.CaptureClock()
    override val iqFlow = samples.flow
    override val iqSubscriberCount get() = samples.subscriberCount
    private val status = MutableStateFlow(IqSource.SourceStatus(centerFreqHz = info.hardwareHz, sampleRate = rate))
    override val statusFlow = status.asStateFlow()
    private val floating = info.floating
    override val capabilities = DeviceCapabilities("file", listOf(info.dialHz..info.dialHz), listOf(rate),
        directSampling = false, biasTee = false, hardwareAgc = false, floatIq = floating, blockSamples = 4096)
    private var input: InputStream? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    override fun open(): Boolean = try {
        require(rate in 32000..6_000_000)
        input = if (file.name.endsWith(".gz")) GZIPInputStream(file.inputStream()) else file.inputStream().buffered()
        status.value = status.value.copy(connected = true)
        true
    } catch (_: Exception) { false }
    override fun startStreaming(bufferSize: Int) {
        if (job?.isActive == true) return
        job = scope.launch {
            val reader = input ?: return@launch
            val bytesPerSample = if (floating) 8 else 2
            val buffer = ByteArray(4096 * bytesPerSample)
            var deadline = System.nanoTime()
            try {
                while (isActive) {
                    var count = 0
                    while (count < buffer.size) {
                        val read = reader.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        count += read
                    }
                    if (count == 0) break
                    require(count % bytesPerSample == 0) { "Truncated IQ sample" }
                    val meta = captureClock.stamp(count / bytesPerSample, rate, info.hardwareHz)
                    val block = if (floating) {
                        val values = FloatArray(count / 4)
                        ByteBuffer.wrap(buffer, 0, count).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values)
                        require(values.all { it.isFinite() }) { "Invalid float IQ sample" }
                        IqBlock.Float32(values, meta.sequence, meta)
                    } else IqBlock.Unsigned8(buffer.copyOf(count), meta)
                    samples.send(block)
                    deadline += block.sampleCount * 1_000_000_000L / rate
                    delay(((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(0))
                }
                samples.complete()
            } catch (error: Exception) {
                if (isActive) status.value = status.value.copy(connected = false, error = error.message)
            }
        }
    }
    override fun stopStreaming() {
        val old = job; old?.cancel(); job = null
        kotlinx.coroutines.runBlocking { old?.join() }
    }
    override fun close() { stopStreaming(); input?.close(); input = null; scope.cancel(); status.value = status.value.copy(connected = false) }
    override fun setCenterFrequency(freqHz: Long) = freqHz == info.hardwareHz || freqHz==info.dialHz
    override fun getCenterFrequency() = info.hardwareHz
    override fun setSampleRate(rateHz: Int) = rateHz == rate
    override fun getSampleRate() = rate
    override fun setGainMode(mode: Int) = Unit
    override fun setGain(gainIndex: Int) = Unit
    override fun getGainIndex() = 0
    override fun getGainDb() = 0f
    override fun getGainCount() = 1
    override fun setTunerAgcEnabled(enable: Boolean) = Unit
    override fun setHardwareAgcEnabled(enable: Boolean) = Unit
    override fun setBiasTee(enable: Boolean) = Unit
    override fun setDirectSampling(mode: Int) = Unit
    override fun getDirectSampling() = 0
    override fun setPpmCorrection(ppm: Int) = Unit
    override fun getPpmCorrection() = 0
    override fun getSourceName() = "File: ${file.name}"
    override fun isOpen() = input != null
}
