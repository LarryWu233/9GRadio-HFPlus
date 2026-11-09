package com.radiosport.ninegradio.dsp

import android.util.Log
import com.radiosport.ninegradio.debug.DebugBus
import com.radiosport.ninegradio.audio.AudioEngine
import com.radiosport.ninegradio.recording.IqRecorder
import com.radiosport.ninegradio.source.IqSource
import com.radiosport.ninegradio.source.IqBlock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/**
 * Main DSP pipeline:
 *   RTL-SDR IQ → [FIR Decimation] → Demodulator → [Resampler] → Audio
 *                                 ↓
 *                              FFT Engine → Spectrum / Waterfall updates
 *                                 ↓
 *                         Protocol Decoders (APRS, RDS …)
 */
class DspEngine(private val device: IqSource,
    private val outputFactory: com.radiosport.ninegradio.pipeline.AudioOutputFactory =
        com.radiosport.ninegradio.pipeline.AudioOutputFactory { AudioEngine(it) },
    private val iqResamplers:ComplexIqResamplerFactory = ReferenceIqResamplers,
    val externalDecoderStream:ExternalDecoderStream = ExternalDecoderStream()) {
    @Volatile private var tuningCorrectionHz = 0L

    // Centre frequency used for dual-APRS NCO calculation.
    // Set by enableDualAprs(); cleared to 0 by disableDualAprs().
    // Accessed only from the DSP processing coroutine so no lock needed.
    @Volatile private var dualAprsCentreHz = 0L

    companion object {
        private const val TAG = "DspEngine"

        // ── Pipeline block-size constants ──────────────────────────────────────
        /**
         * USB USBDEVFS_BULK transfer size (bytes).
         * Must be a multiple of the USB Full-Speed bulk max-packet size (512 B).
         * 32,768 = 64 packets — efficient USB batching.
         */
        const val USB_STREAMING_BUF = 32_768

        /**
         * DSP block size (bytes) delivered to the IQ flow and processIqBlock().
         *
         * Both sources present this block size to the DSP pipeline:
         *  • USB:   startStreaming() emits 32,768-byte USB transfers split into
         *           2 × DSP_CHUNK_SIZE chunks before the SharedFlow emit.
         *  • TCP:   RtlTcpSource.READ_BUF_SIZE = 16,384 = DSP_CHUNK_SIZE.
         *
         * At 1,920,000 S/s this gives a block period of ≈ 4.27 ms,
         * matching the TCP source's natural granularity and giving both sources
         * equal DSP budget and SharedFlow jitter headroom.
         */
        const val DSP_CHUNK_SIZE = 16_384

        /**
         * Internal intermediate and protocol-decoder audio rate (Hz).
         * Must stay at 48 000 — it is an exact integer divisor of every device
         * sample rate in [RtlSdrDevice.SAMPLE_RATES] (all multiples of 48 000),
         * guaranteeing whole-number ComplexDecimator factors with no fractional
         * resampling artefacts.  Protocol decoders (ACARS, APRS, RDS) also have
         * coefficients built for this rate and must not change.
         */
        const val DEFAULT_AUDIO_RATE = 48_000

        /**
         * Default audio-output (sink) sample rate presented to Android AudioTrack.
         *
         * The audio sink rate is independent of the internal processing rate
         * [DEFAULT_AUDIO_RATE] = 48 000 Hz.  A PolyphaseResampler bridges the two
         * only when they differ.  Selectable via the UI audio-sink chip row.
         */
        // 48 kHz matches DEFAULT_AUDIO_RATE so the WFM resampler ratio is always
        // an exact integer (or trivially 1:1), avoiding the large-interpFactor
        // polyphase-bank collapse that produced "watery"/gritty audio at 44.1 kHz.
        // (GCD(wfmIfRate, 44100) can yield interpFactor = 441 → only 3 taps/branch.)
        const val DEFAULT_AUDIO_SINK_RATE = 48_000

        /**
         * Supported audio-output (sink) sample rates in Hz.
         * Presented as chip buttons in the RF tab and as a ListPreference.
         */
        val AUDIO_SINK_RATES = intArrayOf(48_000, 44_100, 32_000, 24_000, 22_050, 16_000)

        /** Maximum FFT / spectrum update rate (fps). Keeps CPU load bounded on fast sample rates. */
        private const val SPECTRUM_MAX_FPS = 30
        /** Assumed narrowband channel width (Hz) used to average FFT bins
         *  around the tuned (DC) centre frequency for [DspStats.narrowbandCenterDb].
         *  Matches the common 12.5 kHz NFM channel spacing used elsewhere
         *  (e.g. FrequencyScanner's default stepHz); a reasonable default for
         *  gating channel-specific signal level regardless of exact mode. */
        private const val NARROWBAND_CHANNEL_HZ = 12_500.0

        /** Minimum IF bandwidth the user can select (Hz). */
        const val MIN_IF_BANDWIDTH_HZ = 100

        /**
         * SSB carrier / BFO offset (Hz).
         *
         * The BFO mixes with exp(+j*2pi*1500*t) — an UPWARD shift of +1500 Hz:
         *   mixI = inI*cos - inQ*sin ;  mixQ = inI*sin + inQ*cos
         *
         * Hardware must be tuned +1500 Hz ABOVE the dial for BOTH USB and LSB:
         *   hardware = dial + SSB_BFO_OFFSET_HZ
         *
         * USB proof (tone at f_audio):
         *   carrier in IF = dial - hw = -1500 Hz
         *   voice   in IF = -1500 + f_audio  (300-3000 Hz voice -> -1200 to +1500 Hz)
         *   after BFO +1500: f_audio -> within 3 kHz LPF -> I+Q -> correct audio.
         *
         * LSB proof (tone at f_audio below carrier):
         *   voice   in IF = -1500 - f_audio  (-1800 to -4500 Hz)
         *   after BFO +1500: -f_audio -> within 3 kHz LPF -> I-Q -> correct audio.
         *
         * Without the offset, voice shifts to +1800..+4500 Hz after the BFO and is
         * blocked by the 3 kHz LPF, producing silence. (The old dial-1500 for USB
         * was derived assuming a downward-shifting BFO and was therefore wrong here.)
         */
        // Public (not private) so UI code -- specifically the spectrum/waterfall
        // display, which must show the *actual* hardware-tuned frequency, not the
        // dial frequency, to keep the FFT trace aligned with the passband
        // highlight box for USB/LSB -- can apply the identical offset. See
        // MainActivity.hardwareCenterFreqHz().
        const val SSB_BFO_OFFSET_HZ = 1500L
        /** Offset reception moves a CW carrier away from the hardware DC notch. */
        fun cwTunerOffset(dial: Long, caps: com.radiosport.ninegradio.source.DeviceCapabilities, rate: Int): Long {
            val offset=minOf(12000L,rate.toLong()/4)
            return when {
                caps.supportsFrequency(dial+offset) -> offset
                caps.supportsFrequency(dial-offset) -> -offset
                else -> 0L
            }
        }
        private const val SPECTRUM_MIN_INTERVAL_MS = 1000L / SPECTRUM_MAX_FPS

        private const val WFM_TARGET_BW=ChannelRatePolicy.WFM_TARGET_BW
        const val WFM_MAX_DEMOD_RATE=ChannelRatePolicy.WFM_MAX_DEMOD_RATE
        fun wfmIfRate(deviceRate:Int,bandwidthHz:Int=0):Int =
            ChannelRatePolicy.wfmIfRate(deviceRate,bandwidthHz)
        fun narrowIfRateFor(deviceRate:Int,sinkRate:Int,minimumRate:Int=ChannelRatePolicy.MIN_DEMOD_RATE):Int =
            ChannelRatePolicy.narrowIfRateFor(deviceRate,sinkRate,minimumRate)
        fun narrowIfRate(deviceRate:Int):Int = narrowIfRateFor(deviceRate,DEFAULT_AUDIO_RATE)

        /**
         * DC-blocker leaky-integrator coefficient.
         * At 2 MS/s this gives τ ≈ 10 000 samples ≈ 5 ms — fast enough to
         * converge within a couple of USB blocks, slow enough not to affect
         * audio content after FM demodulation.
         */
        private const val DC_ALPHA = 0.9999f

        // APRS channel frequencies used by the dual-watch NCO path
        const val APRS_NA_HZ = 144_390_000L   // North America
        const val APRS_EU_HZ = 144_800_000L   // Europe / Australia
        const val APRS_MIDPOINT_HZ = (APRS_NA_HZ + APRS_EU_HZ) / 2  // 144_595_000
        // Minimum sample rate (Hz) that can cover both channels simultaneously.
        // Each channel is 205 kHz from the midpoint; passband must reach ≥ 205 kHz
        // either side, i.e. total bandwidth ≥ 410 kHz → sample rate ≥ 820 kHz.
        const val DUAL_APRS_MIN_SAMPLE_RATE = 820_000

        /** Set of all digital voice demod modes that feed [DigitalVoiceDecoder]. */
        val DIGITAL_VOICE_MODES = setOf(
            DemodMode.DMR, DemodMode.P25, DemodMode.NXDN,
            DemodMode.DSTAR, DemodMode.YSF, DemodMode.M17,
            DemodMode.DPMR,
            DemodMode.DIG
        )
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Current demodulator
    private val channel=ChannelProcessor(AndroidDemodPrimitives,iqResamplers)
    @Volatile private var demodMode = DemodMode.NFM

    // ─── IF Filter / Protocol Bandwidth ──────────────────────────────────────
    // User-adjustable narrow-mode intermediate rate.  The ComplexDecimator
    // down-converts the full device rate to this rate before demodulation,
    // which sets the effective IF bandwidth (≈ 0.9 × ifBandwidthHz).
    //
    // Narrow modes use narrowDecimator; the rate is reset to the mode default
    // whenever the demod mode changes, then the user can narrow or widen it.
    //
    // When -1 (sentinel), the rate is computed dynamically per-block as
    // narrowIfRate(deviceRate) — the closest integer-divisor of the device rate
    // to DEFAULT_AUDIO_RATE (48 kHz).  This handles 250 kS/s (→ 50 kHz) and
    // any future non-48k-multiple device rate without hard-coding.
    // Valid user-set range: 2 kHz – narrowIfRate(deviceRate) for narrow modes.
    @Volatile private var ifBandwidthHz: Int = -1   // -1 = auto (dynamic per device rate)

    // FFT engine for spectrum
    val fftEngine = FftEngine(256)

    // Audio output — recreated via setAudioSinkRate() when the user changes the
    // output sample rate.  @Volatile so setAudioSinkRate() can swap the reference
    // atomically from any thread without holding a lock (start/stop are guarded
    // by the DSP coroutine's sequential execution model).
    @Volatile private var audioSinkRate: Int = DEFAULT_AUDIO_SINK_RATE
    @Volatile private var audioEngine: com.radiosport.ninegradio.pipeline.AudioOutput = outputFactory.create(DEFAULT_AUDIO_SINK_RATE)
    private val soundCapture = com.radiosport.ninegradio.audio.SoundCapture()

    // IQ recorder
    private val iqRecorder = com.radiosport.ninegradio.recording.AsyncIqRecorder()

    // RDS decoder (active in WFM/WFM_STEREO mode) — reconstructed with the
    // actual WFM IF rate the first time a WFM block is processed, then reused.
    // Exposed as a val for UI access; the internal decoder is replaced when the
    // device rate changes (see getOrCreateRdsDecoder()).
    private var _rdsDecoder: RdsDecoder = RdsDecoder(213_333)  // placeholder
    val rdsDecoder: RdsDecoder get() = _rdsDecoder
    private var rdsDecoderRate = 0

    // ACARS decoder (active in ACARS mode) — expects 48 kHz audio
    val acarsDecoder = AcarsDecoder(48_000)

    // Paging decoder (active in FLEX mode). POCSAG mode/support has been
    // removed from the app; this decoder (still named PocsagDecoder, as it
    // implements the POCSAG bit-sync/codeword pipeline) is now only invoked
    // for DemodMode.FLEX. See the feed() call site below.
    //
    // FIX 28: this was constructed as PocsagDecoder(22_050), a leftover from
    // before decoderAudio existed, when protocol decoders were fed audio at
    // whatever raw rate the demodulator happened to produce. Every block
    // reaching a protocol decoder is now unconditionally resampled to
    // DEFAULT_AUDIO_RATE (48 kHz) by resampleForDecoder() below — ACARS and
    // APRS were already constructed/configured for 48 kHz to match, but this
    // one was never updated. PocsagDecoder has no setSampleRate(); audioRate
    // is a constructor-only val baked into samplesPerBit (audioRate /
    // detectedBaud) and into autoBaud()'s crossings→Hz conversion. With
    // audioRate=22_050 but real samples arriving at 48_000 Hz, every "sample"
    // the clock-recovery loop counted was only 22050/48000 ≈ 46% of an actual
    // audio sample's worth of time, so samplesPerBit was ~2.18× too small —
    // onBit() fired more than twice as often as the real bit period, autoBaud
    // misclassified the incoming rate against BAUD_RATES, and the bit stream
    // fed to the sync-word search was essentially random. That made POCSAG
    // sync (0x7CD215D8) and therefore every FLEX/POCSAG message silently
    // fail to lock — the demodulator and AGC looked fine, there was simply
    // never a valid bitstream downstream. Constructing at 48_000 to match
    // the actual feed rate is the fix.
    val pocsagDecoder = PocsagDecoder(48_000)

    // APRS decoder (active in APRS mode) — expects 48 kHz audio
    val aprsDecoder = AprsDecoder().also { it.setSampleRate(DEFAULT_AUDIO_RATE) }

    // Digital voice decoder (active in DMR / P25 / NXDN / D-STAR / YSF / M17 modes).
    // Receives 48 kHz raw FM-discriminated audio (same path as APRS — no AGC/LPF/limiter).
    // Identifies the protocol from sync words and — when the DSD-Neo native library
    // is present (libdsd_neo.so in jniLibs/) — decodes voice frames via DigitalVoiceJni.
    private val voiceStage=DigitalVoiceStage(scope,::decoderFailed)
    private fun decoderFailed(epoch:Long,error:Exception) {
        if(!session.snapshot.closed && epoch==session.snapshot.epoch)
            _failure.value="数字语音处理停止（${error.javaClass.simpleName}）"
    }
    val digitalVoiceDecoder get()=voiceStage.decoder

    /**
     * Pipes raw 48 kHz FM-discriminator audio (the same pre-vocoder signal
     * fed to [digitalVoiceDecoder]) out over UDP as PCM16LE for an external
     * decoder app (dsd-neo, DSD-FME, DSDPlus, etc.) to consume. Disabled by
     * default; see [ExternalDecoderStream] for the wire format and rationale.
     */

    /** Enable/disable piping [digitalVoiceDecoder]'s input audio to the configured external decoder. */
    fun setExternalDecoderEnabled(enabled: Boolean) = externalDecoderStream.setEnabled(enabled)

    /** Change the UDP destination. Safe to call while streaming; takes effect on the next block. */
    fun setExternalDecoderTarget(host: String, port: Int) = externalDecoderStream.configure(host, port)

    // DC block toggle — enabled by default.  Disabling may help when the leaky-
    // integrator time constant is too short for the selected sample rate and
    // produces audible modulation artefacts near the centre frequency.
    @Volatile private var dcBlockEnabled = true

    // ─── Audio Noise Processing ───────────────────────────────────────────────
    // Noise Blanker (NB) — suppresses impulsive noise spikes (ignition, power-line clicks).
    // Noise Reducer (NR) — spectral-subtraction broadband noise reduction.
    // Both are disabled by default and toggled via the UI switch in the control panel.
    private val iqNoiseBlanker=IqNoiseBlanker()
    @Volatile private var iqNbThreshold=8f
    fun setIqNoiseBlankerThreshold(value: Float) { iqNbThreshold=value.coerceIn(3f,20f) }
    fun getIqNoiseBlankerStats()=iqNoiseBlanker.stats()
    @Volatile private var deemphasisUs = 75
    fun setFmDeemphasis(us: Int) { deemphasisUs = if (us == 50) 50 else 75 }
    @Volatile private var noiseBlankerEnabled = false
    @Volatile private var noiseReducerEnabled = false

    // Software frequency offset — shifts the entire baseband by a fixed Hz amount
    // by rotating the IQ phasor each sample.  Useful when a signal sits slightly
    // off-centre and you want to bring it to zero-IF without re-tuning the hardware.
    // Allowed values: 0 (off), ±1000, ±1500, ±2000 Hz.
    @Volatile private var freqOffsetHz = 0
    // Continuous NCO phase accumulator — preserved across IQ blocks so the complex
    // rotation has no discontinuity at USB bulk-transfer boundaries.
    private var freqShiftPhase = 0.0

    /** Last dial frequency requested by the user (Hz).
     *  Remembered so setCarrierFrequency() can reapply the SSB BFO offset whenever
     *  the demod mode flips between SSB and non-SSB (see setDemodMode()). */
    @Volatile private var dialFreqHz: Long = 0L
    @Volatile private var cwCenterOffsetHz = 0L
    private fun isCw()=demodMode==DemodMode.CW || demodMode==DemodMode.CWR

    // Squelch
    @Volatile private var squelchLevel = -200f
    @Volatile private var squelchOpen = true
    @Volatile private var contentAudioSink: ((FloatArray) -> Unit)? = null
    private data class DecoderAttachment(val input:com.radiosport.ninegradio.pipeline.AudioDecoderInput?=null,
        val legacy:((FloatArray)->Unit)?=null)
    @Volatile private var contentAttachment=DecoderAttachment()
    private val decoderAudioClock=com.radiosport.ninegradio.pipeline.AudioBlockClock()
    private val legacyDecoderAudioClock=com.radiosport.ninegradio.pipeline.AudioBlockClock()
    @Volatile private var externalCaptureMuted = false
    @Volatile private var recordingPlaybackMuted=false
    fun setRecordingPlaybackMuted(muted:Boolean) {
        recordingPlaybackMuted=muted
        audioEngine.setVolume(if(receptionAvailable && !externalCaptureMuted && !muted) volume.coerceIn(0f,1f) else 0f)
    }
    /** Legacy synchronous compatibility hook. New decoder adapters use typed blocks. */
    fun setContentAudioSink(sink: ((FloatArray) -> Unit)?) {
        contentAudioSink=sink;contentAttachment=DecoderAttachment(legacy=sink)
    }
    fun setAudioDecoderInput(input:com.radiosport.ninegradio.pipeline.AudioDecoderInput?) {
        input?.configure(session.snapshot.epoch)
        // Keep the existing test/facade hook; it stamps explicit injected PCM as well.
        contentAudioSink=input?.let { target -> { pcm ->
            target.accept(legacyDecoderAudioClock.publish(pcm,DEFAULT_AUDIO_RATE,1,session.snapshot.epoch))
        } }
        contentAttachment=DecoderAttachment(input=input)
    }
    private fun configureDecoderEpoch() {
        dualAprsChannels.invalidate()
        contentAttachment.input?.configure(session.snapshot.epoch)
        externalDecoderStream.configure(session.snapshot.epoch)
    }
    fun setExternalCaptureMuted(muted: Boolean) {
        externalCaptureMuted = muted
        audioEngine.setVolume(if(receptionAvailable && !recordingPlaybackMuted && !muted) volume.coerceIn(0f,1f) else 0f)
    }
    @Volatile private var listeningEnabled = true
    @Volatile private var receptionAvailable = true
    @Synchronized fun setReceptionAvailable(available: Boolean) {
        if (receptionAvailable == available) return
        audioEngine.setVolume(0f)
        receptionAvailable = available
        iqContinuity.reset()
        while (audioWriteChannel.tryReceive().isSuccess) { }
        pcmPacketizer.reset()
        audioEngine.clearPendingAudio()
        if (!available) {
            stopIqRecording(); stopAudioRecording()
        }
        else { fftEngine.resetSpectrumAveraging(); audioEngine.setVolume(if(!externalCaptureMuted && !recordingPlaybackMuted) volume.coerceIn(0f, 1f) else 0f) }
    }
    fun setListeningEnabled(enabled: Boolean) { listeningEnabled = enabled }
    @Synchronized fun setNoiseReductionStrength(strength: Float) { monitoring.setNoiseStrength(strength) }

    // Volume
    @Volatile private var volume = 1.0f

    // FFT rate limiter — last wall-clock time a spectrum frame was emitted
    @Volatile private var lastSpectrumMs = 0L

    // DC-blocker state: running IIR mean for I and Q channels.
    // Persists across processIqBlock() calls so the filter is continuous
    // across USB bulk-transfer boundaries (no step artefact at block edges).
    // Only accessed from the single DSP processing coroutine — no lock needed.
    private val dcState = FloatArray(2)   // [dcI, dcQ], initialised to 0f

    // Reusable float-IQ buffer for convertUint8ToFloat() output.  The RTL-SDR
    // always delivers exactly [bufferSize] bytes per block (default 131 072), so
    // this buffer is allocated once on the first block and reused thereafter —
    // eliminating a 512 KB FloatArray allocation on every IQ block (~15–20/sec).
    private var floatIqBuf = FloatArray(0)

    // Spectrum/waterfall publish flow — DROP_OLDEST so tryEmit() never blocks
    private val _spectrumFlow = MutableSharedFlow<FloatArray>(
        replay = 1,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val spectrumFlow: SharedFlow<FloatArray> = _spectrumFlow.asSharedFlow()

    /**
     * Demodulated audio samples at DEFAULT_AUDIO_RATE (48 kHz) — consumed by
     * protocol decoders (APRS, etc.) and any other subscriber needing baseband.
     */
    private val _audioFlow = MutableSharedFlow<FloatArray>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioFlow: SharedFlow<FloatArray> = _audioFlow.asSharedFlow()
    private val _decoderAudioBlocks=MutableSharedFlow<com.radiosport.ninegradio.pipeline.AudioBlock>(
        replay=0,extraBufferCapacity=8,onBufferOverflow=BufferOverflow.DROP_OLDEST)
    /** Optional diagnostic observation; protocol decoding does not depend on this lossy flow. */
    val decoderAudioBlocks:Flow<com.radiosport.ninegradio.pipeline.AudioBlock> = _decoderAudioBlocks.filter {
        !session.snapshot.closed && it.epoch==session.snapshot.epoch
    }

    /**
     * Raw IQ float samples — consumed by ADS-B decoder which needs magnitude at sample-rate.
     */
    private val _iqMagnitudeFlow = MutableSharedFlow<FloatArray>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val iqMagnitudeFlow: SharedFlow<FloatArray> = _iqMagnitudeFlow.asSharedFlow()

    /**
     * Dual-channel APRS audio — emits a Pair<FloatArray, FloatArray> where
     * [first] is the NA channel (144.390 MHz) and [second] is the EU channel
     * (144.800 MHz), both at DEFAULT_AUDIO_RATE (48 kHz), NFM-demodulated.
     *
     * Only emitted when the device sample rate is wide enough to capture both
     * channels simultaneously (≥ 820 kHz, i.e. the SDR is tuned to the midpoint
     * 144.595 MHz and both ±205 kHz offsets fall inside the passband).
     * When the sample rate is too narrow the flow simply does not emit; callers
     * must fall back to single-channel mode.
     *
     * Each channel is produced by:
     *   1. NCO-mix the full-rate IQ to shift the target frequency to 0 Hz.
     *   2. Polyphase-decimate to narrowIfRate(deviceRate) — the closest exact-integer-
     *      divisor of the device rate to 48 kHz (e.g. 50 kHz at 250 kS/s).
     *   3. NFM discriminator (gain normalised for ±5 kHz deviation at the IF rate).
     *   4. Polyphase-resample to DEFAULT_AUDIO_RATE (48 kHz).
     */
    private val _dualAprsFlow = MutableSharedFlow<Pair<FloatArray, FloatArray>>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val dualAprsFlow: SharedFlow<Pair<FloatArray, FloatArray>> = _dualAprsFlow.asSharedFlow()

    private val dualAprsChannels=DualAprsChannels(iqResamplers)
    private val _dualAprsAudioBlocks=MutableSharedFlow<DualAprsChannels.Audio>(extraBufferCapacity=8)
    val dualAprsAudioBlocks:SharedFlow<DualAprsChannels.Audio> = _dualAprsAudioBlocks.asSharedFlow()
    val dualAprsGeneration:Long get()=dualAprsChannels.currentGeneration
    private val dualAprsDroppedPairs=java.util.concurrent.atomic.AtomicLong()

    // DSP statistics
    private val _stats = MutableStateFlow(DspStats())
    val statsFlow: StateFlow<DspStats> = _stats.asStateFlow()

    data class DspStats(
        val sampleRate: Int = 0,
        val audioRate: Int = DEFAULT_AUDIO_SINK_RATE,
        val demodMode: String = "NFM",
        val squelchOpen: Boolean = false,
        val signalDb: Float = -120f,
        val channelPeakDb: Float = -200f,
        val widebandPeakDb: Float = -200f,
        /** Scanner's existing mean-bin power; listening signalDb is the BW-limited smoothed peak. */
        val narrowbandCenterDb: Float = -120f,
        val audioVolume: Float = 1f,
        val isRecordingIq: Boolean = false,
        val isRecordingAudio: Boolean = false,
        val bufferDrops: Int = 0
    )

    private var processingJob: Job? = null
    private var producerStartJob: Job? = null
    private val started = java.util.concurrent.atomic.AtomicBoolean()
    private fun receiverConfiguration() = com.radiosport.ninegradio.pipeline.ReceiverConfiguration(
        demodMode.name, device.getSampleRate(), ifBandwidthHz, audioSinkRate)
    private val session = com.radiosport.ninegradio.pipeline.PipelineSession(receiverConfiguration())
    private val measurement=com.radiosport.ninegradio.pipeline.SignalAnalyzer()
    private val display=com.radiosport.ninegradio.pipeline.SpectrumBranch(fftEngine,scope) { frame ->
        if(!session.snapshot.closed && frame.metadata.sampleRate==device.getSampleRate()
            && frame.metadata.centerHz==device.getCenterFrequency()) {
            _spectrumFlow.tryEmit(frame.bins)
            DebugBus.tick(DebugBus.STAGE_FFT_ENGINE)
        }
    }
    private fun configurationChanged() {
        val old=session.snapshot.epoch
        if(session.configure(receiverConfiguration()).epoch!=old) {
            configureDecoderEpoch()
            audioEngine.configureEpoch(session.snapshot.epoch)
            voiceStage.configure(session.snapshot.epoch)
            pcmPacketizer.reset(); audioFramePosition=0; packetFramePosition=0
            while(audioWriteChannel.tryReceive().isSuccess) { }
        }
    }

    // ─── Decoupled audio output ───────────────────────────────────────────────
    // processIqBlock() must never block the IQ collection coroutine waiting on
    // AudioTrack.write().  When WRITE_BLOCKING is used and the AudioTrack buffer
    // is full, the DSP coroutine stalls.  While it stalls, the USB IQ producer
    // keeps emitting into _iqFlow (DROP_OLDEST, capacity 17).  After ~3 minutes
    // enough blocks have been silently evicted to produce audible gaps in WFM.
    // The TCP source is immune because the server-side socket buffer absorbs the
    // backpressure and slows the producer, preventing overflow.
    //
    // Fix: processIqBlock() sends audio samples to audioWriteChannel without
    // blocking.  A dedicated audioWriterJob coroutine (on Dispatchers.Default)
    // drains the channel and calls audioEngine.write() — WRITE_BLOCKING is still
    // used there, so playback stays perfectly rate-limited without any busy-loop.
    // The IQ collection coroutine is now decoupled from AudioTrack latency.
    //
    // Fixed 20 ms stereo packets make the queue budget independent of IQ callback size.
    private val audioQueueDrops = java.util.concurrent.atomic.AtomicLong()
    private var pcmPacketizer=com.radiosport.ninegradio.audio.PcmPacketizer(DEFAULT_AUDIO_SINK_RATE)
    private val channelSquelchMeter=ChannelSquelchMeter()
    private val _squelchReading=MutableStateFlow(ChannelSquelchMeter.Reading())
    val squelchReading: StateFlow<ChannelSquelchMeter.Reading> = _squelchReading.asStateFlow()
    @Volatile private var recordingSquelch=ListeningSquelch(false)
    @Volatile private var recordBySquelch=false
    @Volatile var recordingGateOpen=true
        private set
    fun setRecordingSquelch(enabled:Boolean) {
        if(recordBySquelch!=enabled) {
            recordingSquelch=ListeningSquelch(false)
            recordingGateOpen=!enabled
            recordBySquelch=enabled
        }
    }
    fun iqRecordedDurationMs()=iqRecorder.capturedDurationMs()
    fun iqRecordingError()=iqRecorder.error
    fun audioRecordedDurationMs()=soundCapture.durationMs()
    fun audioRecordingError()=soundCapture.error
    fun isAudioRecording()=soundCapture.isRecording
    private val listeningAudio=ListeningAudioProcessor()
    private val monitoring=MonitorAudioBranch(listeningAudio)
    fun setListeningAudio(settings: ListeningAudioProcessor.Settings) { listeningAudio.settings=settings }
    private val audioWriteChannel = Channel<com.radiosport.ninegradio.pipeline.AudioBlock>(
        capacity = 6, // 120 ms queue + 120 ms platform buffer, independent of IQ block size
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = {
            if(!session.snapshot.closed) DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, "PCM_QUEUE_DROPS", audioQueueDrops.incrementAndGet().toString())
        }
    )
    private var audioWriterJob: Job? = null
    @Volatile private var audioFramePosition=0L
    private var packetFramePosition=0L
    fun getPlaybackQueueDrops()=audioQueueDrops.get()
    fun getListeningFrameCount()=audioFramePosition
    @Synchronized private fun enqueueAudio(samples: FloatArray,expectedEpoch:Long=session.snapshot.epoch) {
        if(session.snapshot.closed || expectedEpoch!=session.snapshot.epoch) return
        val epoch=session.snapshot.epoch
        val block=com.radiosport.ninegradio.pipeline.AudioBlock(samples,audioSinkRate,2,epoch,audioFramePosition)
        audioFramePosition+=block.frameCount
        soundCapture.offer(block,recordingGateOpen)
        pcmPacketizer.append(samples) {
            val packet=com.radiosport.ninegradio.pipeline.AudioBlock(it,audioSinkRate,2,epoch,packetFramePosition)
            packetFramePosition+=packet.frameCount
            audioWriteChannel.trySend(packet)
        }
    }

    // ─── Digital voice audio output ───────────────────────────────────────────
    // When a digital voice mode is active and the DSD-Neo software path or the
    // AMBE hardware plugin has decoded a frame, pcmAudio carries 8 kHz 16-bit
    // PCM samples from the vocoder. A dedicated coroutine subscribes to
    // digitalVoiceDecoder.frames and upsamples + routes non-empty pcmAudio to
    // audioWriteChannel. For digital voice modes this coroutine is the sole
    // writer to that channel — processIqBlock() does not write anything to it
    // in that case (see "Write to AudioTrack" in processIqBlock).
    //
    // Upsampling: 8 kHz → audioSinkRate (48 kHz) using linear interpolation.
    // The ratio 48000/8000 = 6 is exact, so no fractional resampler is needed.
    private var digVoiceAudioJob: Job? = null

    // ─── WFM pre-decimation ───────────────────────────────────────────────────
    // The WFM IF rate is computed adaptively per device rate (see wfmIfRate()).
    // Integer-only decimation eliminates fractional-ratio artefacts and pitch
    // drift; the WfmDemodulator reconstructs all coefficients from the actual
    // IF rate received via sampleRate in demodulate().


    /** Return (or recreate) the RDS decoder for the given WFM IF rate. */
    private fun getOrCreateRdsDecoder(ifRate: Int): RdsDecoder {
        if (ifRate != rdsDecoderRate) {
            _rdsDecoder = RdsDecoder(ifRate)
            rdsDecoderRate = ifRate
        }
        return _rdsDecoder
    }

    // ─── Narrow-mode pre-decimation ──────────────────────────────────────────
    // AM, NFM, USB, LSB, CW, CWR, DSB, ACARS need channel-bandwidth IQ, not the
    // full device bandwidth.  We decimate to narrowIfRateFor(deviceRate, audioSinkRate)
    // — the smallest exact-integer-divisor of deviceRate that is ≥ audioSinkRate.
    //
    // GnuRadio rational_resampler approach:
    //   • 1920kS/s, sinkRate=32kHz  → IF=32kHz  (÷60, direct; no final resampler)
    //   • 250kS/s,  sinkRate=32kHz  → IF=50kHz  (÷5),  then 50k→32k (16/25)
    //   • 2560kS/s, sinkRate=32kHz  → IF=32kHz  (÷80, direct; previously BROKEN)
    //
    // Invalidated by setAudioSinkRate() and setDemodMode(); rebuilt on next IQ block.
    /** Publish the current IQ→Audio chain rates to DebugBus for the Debug Panel. */
    private fun publishChainSnapshot(deviceRate: Int, ifRate: Int, sinkRate: Int,isWfm:Boolean=false) {
        val g = gcd(ifRate, sinkRate)
        val p = sinkRate / g
        val q = ifRate   / g
        val isAuto = ifBandwidthHz <= 0
        DebugBus.setChain(com.radiosport.ninegradio.debug.DebugBus.ChainSnapshot(
            sourceHz    = deviceRate,
            ifHz        = ifRate,
            decimFactor = deviceRate / ifRate,
            sinkHz      = sinkRate,
            resampleP   = p,
            resampleQ   = q,
            direct      = ifRate == sinkRate,
            demodMode   = demodMode.displayName,
            wfmIfHz     = if(isWfm) ifRate else 0,
            timestampMs = System.currentTimeMillis(),
            protocolFilterHz   = if(isWfm) 0 else if (isAuto) ifRate else ifBandwidthHz,
            protocolFilterAuto = if(isWfm) true else isAuto,
            decimP = if(isWfm) deviceRate/gcd(deviceRate,ifRate) else 0,
            decimQ = if(isWfm) ifRate/gcd(deviceRate,ifRate) else 0
        ))
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    /** Returns true for modes that benefit from narrow pre-decimation. */
    private fun DemodMode.isNarrowMode()=ChannelProcessor.isNarrow(this)

    // ─── Public API ───────────────────────────────────────────────────────────

    fun start() {
        check(!session.snapshot.closed) { "Closed DSP sessions cannot be restarted" }
        if (!started.compareAndSet(false, true)) return
        audioEngine.start()
        audioEngine.configureEpoch(session.snapshot.epoch)
        audioEngine.setVolume(if(!externalCaptureMuted && !recordingPlaybackMuted && receptionAvailable) volume.coerceIn(0f,1f) else 0f)
        // Re-arm the peak-hold warm-up so the initial-acquisition transient
        // (filter/decimator settling, AGC convergence) doesn't get latched
        // in as a permanent false peak line on the spectrogram.
        fftEngine.resetPeakHold()
        DebugBus.setStatus(DebugBus.STAGE_AUDIO_TRACK, DebugBus.StageStatus.OK, "AudioTrack started @ ${audioSinkRate} Hz")
        startAudioWriter()
        startDigVoiceAudio()
        display.start()
        startProcessingPipeline()
        rebuildVocoderDecoder()
        Log.i(TAG, "DSP engine started")
    }

    /** Launch (or re-launch) the dedicated audio writer coroutine. */
    private fun startAudioWriter() {
        audioWriterJob?.cancel()
        // DISPATCHERS.IO — not Dispatchers.Default.
        //
        // AudioEngine.write() calls AudioTrack.write(WRITE_BLOCKING) inside a
        // Java synchronized() block.  synchronized() is not a coroutine suspend
        // point: the thread is completely parked while AudioTrack drains room in
        // its ring buffer.  On a budget 2-core device Dispatchers.Default has only
        // 2 threads; with one permanently blocked in the audio write loop there is
        // only ONE thread left for processIqBlock() — and any competing scheduler
        // pressure (GC, binder, UI) causes the 34 ms (→ now 8.5 ms) DSP budget
        // to slip, triggering the SharedFlow DROP_OLDEST and producing audio gaps.
        //
        // Dispatchers.IO has an elastic thread pool (default 64 threads) specifically
        // designed for blocking I/O calls.  AudioTrack.write(WRITE_BLOCKING) is exactly
        // that: a blocking I/O call into the Android audio HAL.  Parking an IO thread
        // for a few ms costs nothing — it does not reduce the CPU-bound Default pool
        // that processIqBlock() depends on.
        //
        // rtl_tcp never suffers this problem because its 4.3 ms audio blocks return
        // from AudioTrack.write() almost immediately (ring-buffer copy), so the
        // Default thread is only parked for <1 ms per block rather than up to 8.5 ms.
        audioWriterJob = scope.launch(Dispatchers.IO) {
            try {
            for (samples in audioWriteChannel) {
                if (samples.epoch == session.snapshot.epoch && !session.snapshot.closed)
                    audioEngine.write(samples)
            }
            audioEngine.finishInput()
            } catch(cancel:CancellationException) { throw cancel }
            catch(error:Exception) {
                if(!session.snapshot.closed) _failure.value="声音输出停止（${error.javaClass.simpleName}）"
            }
        }
    }

    /**
     * Subscribe to [DigitalVoiceDecoder.frames] and route decoded PCM audio
     * to the AudioTrack.
     *
     * DSD-Neo / AMBE plugin returns 8 kHz 16-bit PCM. We upsample to
     * [audioSinkRate] (48 kHz) with a factor-of-6 linear interpolation and
     * convert to FloatArray before pushing to [audioWriteChannel]. Using
     * `collect` (not `collectLatest`) ensures every decoded frame is played —
     * there is no reason to drop voice frames under normal load.
     *
     * This is the ONLY writer to [audioWriteChannel] while a digital voice
     * mode is active — processIqBlock() intentionally skips its normal
     * per-IQ-block write in that case (see the "Write to AudioTrack" section
     * there) so its much-higher-frequency writes can't evict real decoded
     * audio out of the small DROP_OLDEST channel before it gets played.
     *
     * If the native library is absent (nativeAvailable = false) or no vocoder
     * (DSD-Neo software path or AMBE plugin) successfully decodes a frame,
     * pcmAudio will be empty and this coroutine simply emits nothing for that
     * frame — there is no separate silence-fill path for digital voice modes.
     */
    private fun startDigVoiceAudio() {
        digVoiceAudioJob?.cancel()
        digVoiceAudioJob = scope.launch(Dispatchers.Default,start=CoroutineStart.UNDISPATCHED) {
            val adapter=VocoderAudioAdapter()
            voiceStage.audioFlow.collect { block -> synchronized(this@DspEngine) {
                if(block.epoch!=session.snapshot.epoch || session.snapshot.closed) return@collect
                val adapted=adapter.process(block,audioSinkRate) ?: return@collect
                val stereo=monitoring.voice(adapted.samples,audioSinkRate,demodMode,
                    MonitorAudioBranch.Settings(listeningEnabled,noiseReducerEnabled,volume),squelchOpen,adapted.discontinuity)
                enqueueAudio(stereo,block.epoch)
            } }
        }
    }

    fun stop() {
        if (!session.close()) return
        configureDecoderEpoch()
        voiceStage.stopAccepting()
        runBlocking { producerStartJob?.cancelAndJoin() }
        device.stopStreaming()
        processingJob?.cancel()
        audioWriterJob?.cancel()
        digVoiceAudioJob?.cancel()
        audioEngine.interruptWrites()
        // Do not hold the DSP monitor: an in-flight block must be able to finish.
        runBlocking { processingJob?.join(); digVoiceAudioJob?.join(); audioWriterJob?.join() }
        display.close()
        voiceStage.close()
        synchronized(this) {
            channel.close();dualAprsChannels.close()
        }
        audioEngine.stop()
        soundCapture.stop()
        audioWriteChannel.cancel()
        iqRecorder.stop()
        externalDecoderStream.shutdown()
        scope.cancel()
        monitoring.close()
        iqNoiseBlanker.close()
        DebugBus.setStatus(DebugBus.STAGE_DSP_PROCESS, DebugBus.StageStatus.IDLE, "Engine stopped")
        DebugBus.setStatus(DebugBus.STAGE_FFT_ENGINE, DebugBus.StageStatus.IDLE, "Engine stopped")
        DebugBus.setStatus(DebugBus.STAGE_DEMODULATOR, DebugBus.StageStatus.IDLE, "Engine stopped")
        DebugBus.setStatus(DebugBus.STAGE_AUDIO_RESAMPLE, DebugBus.StageStatus.IDLE, "Engine stopped")
        DebugBus.setStatus(DebugBus.STAGE_AUDIO_TRACK, DebugBus.StageStatus.IDLE, "Engine stopped")
        Log.i(TAG, "DSP engine stopped")
    }

    /**
     * Rebuild the native dsdcc decoder handle.  Safe to call while the engine
     * is running because [DigitalVoiceDecoder.rebuildHandle] holds the native
     * mutex around the destroy/create pair.
     */
    fun rebuildVocoderDecoder() {
        digitalVoiceDecoder.rebuildHandle()
        Log.i(TAG, "rebuildVocoderDecoder: handle recreated, vocoder=${DsdccNative.vocoderModeLabel}")
    }

    @Synchronized fun setDemodMode(mode: DemodMode) {
        if(session.snapshot.closed) return
        val modeChanged = (mode != demodMode)
        if(!modeChanged) return
        session.discontinuity();configureDecoderEpoch()
        audioEngine.configureEpoch(session.snapshot.epoch);voiceStage.configure(session.snapshot.epoch)
        pcmPacketizer.reset()
        while(audioWriteChannel.tryReceive().isSuccess) { }
        audioEngine.clearPendingAudio()
        demodMode = mode
        channel.setMode(mode)
        // Switching protocols changes the IF bandwidth, decimation chain, and
        // tuned frequency — any peaks accumulated under the previous mode are
        // no longer meaningful, so clear the peak-hold trace and re-arm the
        // warm-up window to ignore the resulting retune/reconfigure transient.
        if (modeChanged) {
            fftEngine.resetPeakHold()
            DebugBus.clearChain()
            // Notify the digital voice decoder of the new mode so it activates
            // the correct signal path (GMSK for D-STAR, 4FSK for everything else)
            // and resets internal state.  Called for every mode change, not just
            // when entering a digital voice mode, so the decoder always starts clean.
        }
        // Reset IF bandwidth to the mode's default when switching to a
        // DIFFERENT mode, so the decimation chain always starts at a sensible
        // bandwidth for the new mode.
        //
        // When [mode] equals the mode that was already active (e.g.
        // applyAllSettings() re-invoking setDemodMode() on reconnect without
        // an actual mode switch), the user's Protocol Filter width
        // (ifBandwidthHz, set via setIfBandwidth()) must NOT be clobbered —
        // doing so silently desyncs the RF-tab "Width" slider from the
        // engine's actual IF bandwidth, making the control appear broken.
        //
        // Modes whose defaultIfRate() equals the full narrow-IF ceiling (48 kHz)
        // use the -1 sentinel so resolveNarrowIfRate() always picks the correct
        // exact-integer-divisor ceiling for the current device rate (e.g. 50 kHz
        // at 250 kS/s instead of forcing a fractional 48 kHz).
        if (mode.isNarrowMode() && modeChanged) {
            val modeDefault = mode.defaultIfRate()
            // Use -1 sentinel when the mode's default IF ≥ the current dynamic ceiling,
            // so resolveNarrowIfRate() picks the correct sink-rate-relative ceiling on
            // the next IQ block (e.g. 32 kHz at 250 kS/s when sinkRate=32 kHz, instead
            // of the old hardcoded 48 kHz target).
            val dynamicCeiling = narrowIfRateFor(device.getSampleRate(), audioSinkRate)
            // USB/LSB: always use the auto sentinel (-1) so the UI slider and spectrum
            // highlight reflect the true audio passband (defaultBwHz = 3 kHz) rather
            // than the internal IF decimation rate (defaultIfRate = 12 kHz).
            // resolveNarrowIfRate() will then return the full narrow-IF ceiling (typically
            // 48 kHz), and SsbDemodulator's own 3 kHz LPF provides the final selectivity.
            // This prevents the spectrum highlight from showing 12 kHz (the DSP rate) when
            // the demodulator only ever passes 3 kHz of audio.
            // FIX 24: APRS must ALWAYS resolve via the auto (-1) sentinel, never the
            // literal modeDefault (48 000).
            //
            // modeDefault for APRS is 48 000 (DemodMode.defaultIfRate()'s "maximum
            // narrow rate" bucket), chosen specifically so this branch falls through
            // to auto. But "modeDefault >= dynamicCeiling" computes dynamicCeiling
            // from narrowIfRateFor(deviceRate, audioSinkRate) — the GENERIC sink-rate
            // floor — not the APRS-specific DEFAULT_AUDIO_RATE floor that
            // resolveNarrowIfRate() actually uses for APRS. At the most common RTL-SDR
            // device rates (2.048 / 1.024 / 2.56 MS/s -> 51 200 Hz ceiling; 3.2 MS/s ->
            // 50 000 Hz) that true ceiling EXCEEDS 48 000, so "48000 >= ceiling" is
            // FALSE and this branch pins ifBandwidthHz to a literal 48000.
            //
            // 48000 is then NOT an exact integer divisor of those device rates (e.g.
            // 2 048 000 / 48 000 = 42.67), so resolveNarrowIfRate()'s
            // ifBandwidthHz.coerceIn(MIN, ceiling) passes 48000 straight through to
            // ComplexDecimator(2_048_000, 48_000) — a fractional 3/128 rational
            // resampler instead of the pure-integer decimation (-> 51 200 Hz) that
            // FIX 22 was specifically designed to guarantee for the AFSK bit-clock.
            // The narrow IF path silently stopped being the clean decimate-only chain
            // FIX 1-21's AFSK timing assumes, on the exact sample rates most dongles
            // ship with by default.
            //
            // Explicitly routing APRS into the auto branch removes the broken
            // modeDefault/dynamicCeiling comparison entirely for this mode: auto
            // always defers to resolveNarrowIfRate()'s APRS-aware floor, which is
            // already correct.
            ifBandwidthHz = if (mode == DemodMode.USB || mode == DemodMode.LSB ||
                                 mode == DemodMode.APRS || mode == DemodMode.CW || mode == DemodMode.CWR || modeDefault >= dynamicCeiling) -1 else modeDefault
            channel.invalidateNarrow()


        }
        // Reapply the carrier-offset hardware frequency whenever the mode changes
        // into or out of USB/LSB.  setCarrierFrequency() applies ±SSB_BFO_OFFSET_HZ
        // for USB/LSB and passes dialFreqHz straight through for all other modes.
        // This corrects the 1500 Hz tuning error that occurs because the Weaver BFO
        // mixes at 1500 Hz: the hardware must be pre-offset so the carrier lands at
        // 0 Hz after demodulation, matching what the dial shows.  Only reapply when
        // modeChanged to avoid an unnecessary retune (and its transient) on a simple
        // applyAllSettings() reconnect where the mode has not actually changed.
        if (modeChanged && dialFreqHz > 0L) {
            setCarrierFrequency(dialFreqHz)
        }
        // Free WFM decimator when leaving WFM modes — saves memory and forces
        // re-creation (with up-to-date device rate) if we return to WFM later.

        // Free narrow decimator when leaving narrow modes — same rationale.
        if (!mode.isNarrowMode()) {
            channel.invalidateNarrow()

        }
        updateStats()
        DebugBus.setStatus(DebugBus.STAGE_DEMODULATOR, DebugBus.StageStatus.OK,
            "Mode: ${mode.displayName}")
        Log.i(TAG, "Demod mode: $mode")
        configurationChanged()
    }

    fun setSquelchLevel(db: Float) {
        squelchLevel = db
        updateStats()
    }

    fun setVolume(vol: Float) {
        volume = vol.coerceIn(0f, 2f)
        // AudioTrack.setVolume() is silently clamped to getMaxVolume() (1.0) on all
        // Android devices.  Clamp here so the intent is explicit: hardware level handles
        // attenuation (0–1.0) and processIqBlock applies a software sample-gain for
        // values above 1.0, giving the full 0–2× range to the user.
        audioEngine.setVolume(if (receptionAvailable && !externalCaptureMuted && !recordingPlaybackMuted) vol.coerceIn(0f, 1f) else 0f)
        updateStats()
    }

    /**
     * Switch the audio-output (sink) sample rate presented to Android AudioTrack.
     *
     * This controls ONLY what reaches the speaker/headphone — the internal DSP
     * pipeline (decimation, demodulation, protocol decoders) always operates at
     * [DEFAULT_AUDIO_RATE] = 48 000 Hz.  A PolyphaseResampler bridges the two
     * rates only when they differ, eliminating the mickey-mouse / slow-motion
     * pitch distortion that can occur when certain IQ sample rates produce a
     * demod output whose ratio to 48 kHz is non-trivial for the AudioTrack.
     *
     * Call this while the engine is running: it stops the current AudioTrack,
     * replaces [audioEngine] with a new one at [rate], and restarts playback
     * transparently.
     *
     * @param rate One of [AUDIO_SINK_RATES] (44 100, 32 000, 24 000, 22 050,
     *             16 000, 48 000 Hz).
     */
    fun setAudioSinkRate(rate: Int) {
        if (rate == audioSinkRate || session.snapshot.closed) return
        val running = started.get()
        soundCapture.stop()
        if (running) {
            runBlocking { producerStartJob?.cancelAndJoin() }
            device.stopStreaming()
            runBlocking { processingJob?.cancelAndJoin(); digVoiceAudioJob?.cancelAndJoin() }
        }
        audioEngine.interruptWrites()
        runBlocking { audioWriterJob?.cancelAndJoin() }
        synchronized(this) {
            val oldEngine = audioEngine
            audioSinkRate = rate
            pcmPacketizer = com.radiosport.ninegradio.audio.PcmPacketizer(rate)
            while (audioWriteChannel.tryReceive().isSuccess) { }
            channel.outputRateChanged()
            channel.invalidateNarrow()
            audioEngine = outputFactory.create(rate)
            oldEngine.stop()
            configurationChanged()
            iqContinuity.reset()
        }
        if (running) {
            audioEngine.start()
            audioEngine.setVolume(if(!externalCaptureMuted && !recordingPlaybackMuted && receptionAvailable) volume.coerceIn(0f,1f) else 0f)
            startAudioWriter(); startDigVoiceAudio(); startProcessingPipeline()
        }
        updateStats()
    }

    fun getAudioSinkRate(): Int = audioSinkRate
    fun getPlaybackUnderruns(): Int = audioEngine.underruns
    fun getPlaybackClock(): Map<String,Long> = audioEngine.playbackClock().asLegacyMap()

    fun startIqRecording(filePath: String) {
        iqRecorder.start(filePath, device.getSampleRate(), if (device.capabilities.floatIq) IqRecorder.Format.FLOAT32 else IqRecorder.Format.RAW_UINT8)
        updateStats()
    }

    fun stopIqRecording() {
        iqRecorder.stop()
        updateStats()
    }

    fun startAudioRecording(filePath:String,profile:com.radiosport.ninegradio.recording.AudioRecordingProfile=
        com.radiosport.ninegradio.recording.AudioRecordingProfile.forFile(filePath)) {
        soundCapture.start(filePath,audioSinkRate,profile)
        updateStats()
    }

    fun stopAudioRecording() {
        soundCapture.stop()
        updateStats()
    }

    fun getFftSize(): Int = fftEngine.fftSize
    fun setFftSize(size: Int) { fftEngine.setFftSize(size) }
    fun setFftWindow(w: FftEngine.WindowType) { fftEngine.setWindowType(w) }
    fun setFftSmoothing(alpha: Float) { fftEngine.smoothingAlpha = alpha }

    /**
     * Set the FFT decimation factor.  Valid values: 1 (off), 2, 4, 8, 16, 32, 64.
     * FftEngine.decimationFactor is updated immediately; the next IQ block
     * processed by processUint8/processFloat will use the new factor.
     * The caller must also update SpectrumView/WaterfallView with the effective
     * sample rate (= deviceSampleRate / decimationFactor).
     */
    fun setFftDecimation(factor: Int) {
        fftEngine.decimationFactor = factor
        Log.i(TAG, "FFT decimation: ×$factor  effective rate: ${device.getSampleRate() / factor.coerceAtLeast(1)} Hz")
    }

    fun getFftDecimation(): Int = fftEngine.decimationFactor

    /** Toggle the IQ-level DC-offset removal filter.  Disable if the leaky
     *  integrator is audibly interfering with signals near the centre frequency. */
    fun setDcBlockEnabled(enabled: Boolean) {
        dcBlockEnabled = enabled
        if (!enabled) dcState.fill(0f)   // reset leaky-integrator so re-enable starts clean
        Log.i(TAG, "DC block ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Set the IF filter bandwidth for narrow modes (AM, NFM, USB, LSB, CW, etc.).
     * This controls the intermediate rate of the ComplexDecimator that precedes
     * demodulation — effectively the channel bandwidth seen by the demodulator.
     *
     * Clamped to [MIN_IF_BANDWIDTH_HZ, narrowIfRateFor(deviceRate, audioSinkRate)].
     * The upper bound is the smallest exact-integer-divisor of the device rate that
     * is ≥ audioSinkRate — i.e. the same dynamic ceiling used by the auto path.
     * The narrowDecimator is torn down and rebuilt on the next IQ block.
     *
     * Pass 0 (or any negative value) to reset to auto (dynamic per device+sink rate).
     *
     * @param hz  Desired IF bandwidth in Hz (e.g. 2000 = very narrow CW,
     *            15000 = standard NFM, 25000 = AM/ACARS), or 0 for auto.
     */
    @Synchronized fun setIfBandwidth(hz: Int) {
        if(session.snapshot.closed) return
        // 0 (or negative) means "auto — use narrowIfRate(deviceRate)".
        // Reset to sentinel -1 so resolveNarrowIfRate() picks the correct rate
        // for the current device rate on the next IQ block.
        val newHz = if (hz <= 0) -1
                    else hz.coerceIn(MIN_IF_BANDWIDTH_HZ, Int.MAX_VALUE)
        if (newHz == ifBandwidthHz) return
        ifBandwidthHz = newHz
        // Invalidate so getNarrowDecimator() rebuilds at next IQ block.
        channel.invalidateNarrow()


        Log.i(TAG, "IF bandwidth set to ${if (newHz < 0) "auto" else "$newHz Hz"}")
        configurationChanged()
    }

    /**
     * Returns the current IF bandwidth in Hz, or -1 when in auto mode
     * (rate is determined dynamically by [narrowIfRate] for the device rate).
     */
    fun getIfBandwidth(): Int = ifBandwidthHz

    /** Enable or disable the Noise Blanker (impulsive-noise suppressor). */
    fun setNoiseBlankerEnabled(enabled: Boolean) {
        noiseBlankerEnabled = enabled
        Log.i(TAG, "Noise Blanker ${if (enabled) "enabled" else "disabled"}")
    }

    /** Changes the requested state; reset and crossfade are owned by the DSP consumer. */
    fun setNoiseReducerEnabled(enabled: Boolean) {
        noiseReducerEnabled = enabled
        Log.i(TAG, "Noise Reducer ${if (enabled) "enabled" else "disabled"}")
    }

    fun isNoiseBlankerEnabled(): Boolean = noiseBlankerEnabled && NoiseProcessingPolicy.allows(demodMode)
    fun isNoiseReducerEnabled(): Boolean = noiseReducerEnabled && NoiseProcessingPolicy.allows(demodMode)

    /** Set a software frequency offset applied to the IQ stream before demodulation.
     *  Positive values shift the passband upward (signal appears to move left in spectrum);
     *  negative values shift it downward.  Pass 0 to disable. */
    fun setFreqOffset(hz: Int) {
        freqOffsetHz = hz
        freqShiftPhase = 0.0   // reset NCO phase to avoid a click on setting change
        Log.i(TAG, "Frequency offset: $hz Hz")
    }

    /**
     * Tune the hardware to the user's [dialHz] frequency, transparently applying the
     * SSB BFO carrier offset for USB and LSB modes.
     *
     * The BFO mixes with exp(+j*2pi*1500*t) — an UPWARD shift of +1500 Hz.
     * To place the carrier at -1500 Hz in the IF (so after the BFO it lands at 0 Hz),
     * the hardware must tune +1500 Hz ABOVE the dial for both USB and LSB:
     *
     *   USB, LSB -> hardware = dialHz + SSB_BFO_OFFSET_HZ
     *   All other modes -> hardware = dialHz (no offset)
     *
     * This is the single point that applies the offset; all callers route through here.
     *
     * @param dialHz The frequency the user intends to receive (as shown on the dial).
     */
    @Synchronized fun setCarrierFrequency(dialHz: Long) {
        dialFreqHz = dialHz
        if(device is com.radiosport.ninegradio.source.FileIqSource) {
            val hardware=device.getCenterFrequency()
            cwCenterOffsetHz=if(isCw()) hardware-dialHz else 0L
            val expected=when(demodMode) { DemodMode.USB,DemodMode.LSB -> dialHz+SSB_BFO_OFFSET_HZ; else -> dialHz }
            tuningCorrectionHz=if(isCw()) 0L else hardware-expected
            session.tuneBoundary()
            return
        }
        // The BFO mixes with exp(+j*2pi*1500*t) — a +1500 Hz upward spectral shift.
        // To have a USB carrier (at dialHz in RF) land at 0 Hz after demodulation, the
        // hardware must present that carrier at −1500 Hz in the baseband IF:
        //
        //   hardware = dialHz + 1500 Hz  →  carrier in IF at (dialHz − (dialHz+1500)) = −1500 Hz
        //   after BFO +1500 Hz shift:  −1500 + 1500 = 0 Hz  ✓
        //
        // USB voice (300–3000 Hz above carrier) then sits at −1200 to +1500 Hz in the IF,
        // and after the BFO shift occupies +300 to +3000 Hz — all within the 3 kHz LPF.
        // I+Q sideband select then yields correct audio.
        //
        // For LSB, the voice is 300–3000 Hz BELOW the carrier, so in the IF it sits at
        // −1800 to −4500 Hz.  After BFO +1500: −300 to −3000 Hz — within the LPF.
        // I−Q sideband select inverts the negative-frequency components into audio. ✓
        //
        // Therefore both USB and LSB use the same +SSB_BFO_OFFSET_HZ hardware shift.
        // (The previous −1500 for USB was wrong: it placed voice at +1800–+4500 Hz
        //  in the IF, which the +1500 BFO shift pushed to +3300–+6000 Hz — entirely
        //  above the 3 kHz LPF, producing silence.)
        cwCenterOffsetHz=if(isCw()) cwTunerOffset(dialHz,device.capabilities,device.getSampleRate()) else 0L
        val hwHz = when (demodMode) {
            DemodMode.CW, DemodMode.CWR -> dialHz + cwCenterOffsetHz
            DemodMode.USB,
            DemodMode.LSB -> dialHz + SSB_BFO_OFFSET_HZ
            else          -> dialHz
        }
        val actual = device.capabilities.nearestFrequency(hwHz)
        if (device.setCenterFrequency(actual)) tuningCorrectionHz = actual - hwHz
        session.tuneBoundary()
        Log.i(TAG, "setCarrierFrequency: dial=$dialHz mode=$demodMode hw=$hwHz")
    }
    fun getDialFrequencyHz(): Long = dialFreqHz.takeIf { it>0 } ?: device.getCenterFrequency()
    @Synchronized fun controlSnapshot() = com.radiosport.ninegradio.pipeline.ReceiverControls(
        demodMode,getDialFrequencyHz(),ifBandwidthHz,squelchLevel,volume,listeningEnabled,
        dcBlockEnabled,freqOffsetHz,deemphasisUs)
    @Synchronized fun restoreControls(state:com.radiosport.ninegradio.pipeline.ReceiverControls) {
        setDemodMode(state.mode);setIfBandwidth(state.bandwidthHz)
        setSquelchLevel(state.squelchDb);setVolume(state.volume);setListeningEnabled(state.listening)
        setDcBlockEnabled(state.dcBlock);setFreqOffset(state.frequencyOffsetHz);setFmDeemphasis(state.deemphasisUs)
        setCarrierFrequency(state.dialHz)
    }
    /** The scanner has tuned the exact capture centre, without VFO/BFO offsets. */
    @Synchronized fun scannerCenterApplied(centerHz:Long) {
        if(session.snapshot.closed) return
        dialFreqHz=when(demodMode) { DemodMode.USB,DemodMode.LSB -> centerHz-SSB_BFO_OFFSET_HZ;else -> centerHz }
        cwCenterOffsetHz=0L;tuningCorrectionHz=0L
        session.tuneBoundary();fftEngine.resetSpectrumAveraging()
    }

    /**
     * Activate the dual-APRS simultaneous-reception path.
     *
     * The caller must have already tuned the device to [centreHz] (typically
     * [APRS_MIDPOINT_HZ] = 144.595 MHz) so both APRS channels fall inside the
     * passband.  The DSP loop will start emitting to [dualAprsFlow] on the next
     * IQ block; the caller subscribes to that flow and feeds two [AprsDecoder]
     * instances independently.
     *
     * Has no effect if [centreHz] is 0 or if the device sample rate is less than
     * [DUAL_APRS_MIN_SAMPLE_RATE].
     */
    @Synchronized fun enableDualAprs(centreHz: Long) {
        if(session.snapshot.closed) return
        if(dualAprsCentreHz!=centreHz) dualAprsChannels.invalidate()
        dualAprsCentreHz = centreHz
        Log.i(TAG, "Dual-APRS enabled, centre: ${centreHz / 1e6} MHz")
    }

    /** Deactivate the dual-APRS path and release its DSP objects. */
    @Synchronized fun disableDualAprs() {
        dualAprsCentreHz=0L
        if(!session.snapshot.closed) dualAprsChannels.reset()
        Log.i(TAG,"Dual-APRS disabled")
    }

    // ─── Processing Pipeline ─────────────────────────────────────────────────

    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()
    private val _inputComplete=MutableStateFlow(false)
    val inputComplete:StateFlow<Boolean> = _inputComplete.asStateFlow()

    private fun startProcessingPipeline() {
        // UNDISTPATCHED registers the source subscription before startStreaming; no polling timeout.
        processingJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                device.iqFlow.collect { block -> processIqBlock(block) }
                if(!session.snapshot.closed) {
                    voiceStage.finishInput();digVoiceAudioJob?.join()
                    synchronized(this@DspEngine) {
                        pcmPacketizer.flush {
                            audioWriteChannel.trySend(com.radiosport.ninegradio.pipeline.AudioBlock(
                                it,audioSinkRate,2,session.snapshot.epoch,packetFramePosition))
                        }
                    }
                    audioWriteChannel.close();audioWriterJob?.join()
                    val deadline=System.nanoTime()+1_000_000_000L
                    while(!session.snapshot.closed && System.nanoTime()<deadline) {
                        val clock=audioEngine.playbackClock()
                        if(clock.playbackHeadFrames>=clock.writtenFrames) break
                        delay(10)
                    }
                    _inputComplete.value=true
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                _failure.value = "Receiver processing failed: ${error.javaClass.simpleName}"
                DebugBus.setError(DebugBus.STAGE_DSP_PROCESS, _failure.value!!)
            }
        }
        producerStartJob = scope.launch {
            if (!session.snapshot.closed) device.startStreaming(USB_STREAMING_BUF)
        }
    }

    private val iqContinuity = com.radiosport.ninegradio.pipeline.IqContinuity()
    private val inputSamples=java.util.concurrent.atomic.AtomicLong()
    private val inputMissingSamples=java.util.concurrent.atomic.AtomicLong()
    private val staleInputBlocks=java.util.concurrent.atomic.AtomicLong()
    @Volatile private var driverMissingSamples=0L
    fun pipelineDiagnostics()=com.radiosport.ninegradio.pipeline.ReceiverDiagnostics(
        session.snapshot.epoch,device.getSampleRate(),audioSinkRate,inputSamples.get(),
        DebugBus.getIqFlowDropCount(),inputMissingSamples.get(),driverMissingSamples,staleInputBlocks.get(),
        display.skipped.get(),voiceStage.droppedPackets.get(),audioQueueDrops.get(),audioEngine.underruns,
        decoderOutputDroppedPackets=voiceStage.droppedOutputPackets.get(),
        externalDecoderDroppedPackets=externalDecoderStream.packetsDropped,
        externalDecoderStalePackets=externalDecoderStream.stalePackets,
        externalDecoderPendingRelease=externalDecoderStream.hasPendingRelease,
        dualAprsDroppedPairs=dualAprsDroppedPairs.get())
    private fun recoverDiscontinuity() {
        session.discontinuity()
        configureDecoderEpoch()
        audioEngine.configureEpoch(session.snapshot.epoch)
        measurement.reset()
        soundCapture.abort("输入信号出现缺口，录制已停止")
        channel.reset()
        dcState.fill(0f); freqShiftPhase=0.0;dualAprsChannels.reset()
        iqNoiseBlanker.reset()
        channel.invalidateNarrow()

        aprsDecoder.setSampleRate(DEFAULT_AUDIO_RATE)
        acarsDecoder.resetSignalState(); pocsagDecoder.resetSignalState(); rdsDecoder.resetSignalState()
        voiceStage.configure(session.snapshot.epoch)
        monitoring.reset()
        pcmPacketizer.reset()
        while(audioWriteChannel.tryReceive().isSuccess) { }
        if(iqRecorder.isRecording) {
            iqRecorder.abort("输入 IQ 出现缺口，录制已停止")
            DebugBus.setError(DebugBus.STAGE_DSP_PROCESS,"IQ 录制因输入缺口停止")
        }
    }
    @Synchronized private fun processIqBlock(block: IqBlock) {
        val decoderAttachment=contentAttachment
        if (!session.admits(block.metadata)) { staleInputBlocks.incrementAndGet();return }
        if (block.metadata.streamId != 0L && (block.metadata.sampleRate != device.getSampleRate()
                || block.metadata.centerHz != device.getCenterFrequency())) { staleInputBlocks.incrementAndGet();return }
        if (!receptionAvailable) return
        val continuity = iqContinuity.accept(block.metadata, block.sampleCount)
        if (continuity.change == com.radiosport.ninegradio.pipeline.IqContinuity.Change.STALE) { staleInputBlocks.incrementAndGet();return }
        if (continuity.missingBlocks > 0) DebugBus.incrementIqFlowDrops(continuity.missingBlocks)
        inputSamples.addAndGet(block.sampleCount.toLong())
        inputMissingSamples.addAndGet(continuity.missingSamples)
        driverMissingSamples=block.metadata.driverLostTotal
        if (continuity.missingSamples > 0) DebugBus.setExtra(DebugBus.STAGE_IQ_STREAM,
            "CONSUMER_MISSING_SAMPLES", continuity.missingSamples.toString())
        if (continuity.change == com.radiosport.ninegradio.pipeline.IqContinuity.Change.GAP) recoverDiscontinuity()
        // Recovery establishes a new epoch; this current block belongs to that epoch.
        val admitted=session.snapshot
        if(admitted.closed) return
        val processingEpoch=admitted.epoch
        val rawIq = (block as? IqBlock.Unsigned8)?.bytes
        // Retain the existing U8 invalid-content guard, but confirm BOTH components
        // across the entire block. Sparse checks at even offsets can mistake valid
        // I=0/Q!=0 input for an empty USB buffer. Content alone cannot identify a
        // hardware failure. A rejected block is an explicit main-channel gap.
        if (rawIq != null && rawIq.isNotEmpty() && rawIq[0] == 0.toByte()
                && rawIq.all { it == 0.toByte() }) {
            val counter = DebugBus.counter(DebugBus.STAGE_IQ_STREAM)
            if (counter % 14L == 0L) {
                Log.w(TAG, "processIqBlock: rejected all-zero U8 input block")
                DebugBus.setError(DebugBus.STAGE_DSP_PROCESS,"全零 U8 输入已跳过，处理状态已重置")
            }
            inputSamples.addAndGet(-block.sampleCount.toLong())
            inputMissingSamples.addAndGet(block.sampleCount.toLong())
            DebugBus.incrementIqFlowDrops(1)
            recoverDiscontinuity()
            return
        }

        val n = block.sampleCount
        if (floatIqBuf.size != n * 2) floatIqBuf = FloatArray(n * 2)
        val floatIq = floatIqBuf
        if (block is IqBlock.Float32) {
            block.values.copyInto(floatIq)
        } else if (rawIq != null && NativeDsp.isNativeAvailable()) {
            NativeDsp.convertUint8ToFloatInto(rawIq, floatIq, rawIq.size)
        } else {
            for (i in rawIq!!.indices) {
                floatIq[i] = ((rawIq[i].toInt() and 0xFF) - 127.5f) / 128.0f
            }
        }

        val rate=device.getSampleRate()
        val hardwareHz=device.getCenterFrequency()
        val measured=measurement.process(floatIq,getDialFrequencyHz(),hardwareHz,demodMode,
            ChannelPassband.widthHz(demodMode,ifBandwidthHz,device.capabilities.maxChannelBandwidth(rate)),
            rate,if(isCw()) cwCenterOffsetHz else 0L,System.nanoTime())
        val sqlReading=measured.channel
        _squelchReading.value=sqlReading
        val squelchSignalDb=if(sqlReading.valid) sqlReading.levelDb else -200f
        val widebandPeakDb=measured.widebandPeakDb
        val narrowbandCenterDb=measured.scannerCenterDb
        recordingGateOpen=!recordBySquelch || recordingSquelch.update(squelchSignalDb,squelchLevel,block.sampleCount.toDouble()/rate)
        if(iqRecorder.isRecording && recordingGateOpen) iqRecorder.write(block)
        if(_spectrumFlow.subscriptionCount.value>0) {
            val meta=block.metadata.copy(sampleRate=rate,centerHz=hardwareHz)
            val raw=if(block is IqBlock.Float32) block.values else floatIq.copyOf()
            display.offer(IqBlock.Float32(raw,meta.sequence,meta))
        }

        // ── DC offset removal ─────────────────────────────────────────────────
        // RTL-SDR hardware leaves a large DC component at the centre bin due
        // to LO leakage in the direct-conversion mixer.  Remove it with a
        // leaky-integrator IIR filter before any downstream processing so:
        //   • the squelch power measurement is not inflated by a constant bias,
        //   • weak signals near the centre frequency are not buried under DC,
        //   • ADS-B magnitude samples have a clean zero baseline.
        // The FFT spectrum gets its own block-mean removal inside FftEngine so
        // the two paths are decoupled (FFT still reads the raw uint8 buffer).
        // Conditionally applied — user can disable via Settings if it interferes.
        if (dcBlockEnabled && !(isCw() && cwCenterOffsetHz==0L)) {
            NativeDsp.removeDc(floatIq, dcState, DC_ALPHA)
        }

        // ── Software frequency offset (NCO fine-tune) ─────────────────────────
        // Rotates every IQ sample by a fixed angular increment, effectively
        // translating the baseband spectrum up or down by freqOffsetHz.  Done
        // after DC removal so the DC spike is not shifted into the passband.
        // The phase accumulator persists across blocks for continuous rotation.
        val offsetHz = freqOffsetHz + tuningCorrectionHz + if(isCw()) cwCenterOffsetHz else 0L
        if (offsetHz != 0L) {
            // Phase-rotation recurrence: multiply the running phasor by a fixed
            // unit step each sample instead of calling cos/sin per sample.
            // Cost: 4 muls + 2 adds per sample vs 2 transcendentals — roughly
            // 20× faster on ARM, eliminating up to 2M cos/sin calls/sec at 2 MS/s.
            // Phase re-normalisation every block keeps magnitude drift < 1e-6.
            val phaseStep = 2.0 * Math.PI * offsetHz / device.getSampleRate()
            val stepRe = Math.cos(phaseStep)   // computed once per block
            val stepIm = Math.sin(phaseStep)
            // Restore from saved phase accumulator
            var pRe = Math.cos(freqShiftPhase)
            var pIm = Math.sin(freqShiftPhase)
            val numSamples = floatIq.size / 2
            for (i in 0 until numSamples) {
                val cosP = pRe.toFloat()
                val sinP = pIm.toFloat()
                val re = floatIq[2 * i]
                val im = floatIq[2 * i + 1]
                floatIq[2 * i]     = re * cosP - im * sinP
                floatIq[2 * i + 1] = re * sinP + im * cosP
                // Advance phasor: (pRe + j·pIm) × (stepRe + j·stepIm)
                val nextRe = pRe * stepRe - pIm * stepIm
                val nextIm = pRe * stepIm + pIm * stepRe
                pRe = nextRe; pIm = nextIm
            }
            // Re-normalise magnitude drift once per block (cheap compared to per-sample)
            val mag = Math.sqrt(pRe * pRe + pIm * pIm)
            if (mag > 0.0) { pRe /= mag; pIm /= mag }
            // Persist phase as angle for next block
            freqShiftPhase = Math.atan2(pIm, pRe)
        }

        // ── Emit IQ magnitude for ADS-B (and other raw-IQ consumers) ─────────
        if (_iqMagnitudeFlow.subscriptionCount.value > 0) {
            val mag = FloatArray(n)
            for (i in 0 until n) {
                val I = floatIq[2 * i]; val Q = floatIq[2 * i + 1]
                mag[i] = kotlin.math.sqrt(I * I + Q * Q)
            }
            _iqMagnitudeFlow.tryEmit(mag)
        }

        // ── Dual-APRS simultaneous reception ─────────────────────────────────
        // When AprsActivity is in dual-watch mode it subscribes to _dualAprsFlow.
        // We derive two independent NFM-demodulated audio streams from the same
        // floatIq block by:
        //   1. NCO-mixing a copy of the IQ to shift each APRS channel to 0 Hz.
        //   2. Polyphase-decimating each mixed copy to narrowIfRate(deviceRate).
        //   3. Running an FM discriminator on each decimated stream.
        //   4. Resampling each discriminator output to DEFAULT_AUDIO_RATE.
        //
        // The NCO offset is (targetFreq − centreFreq).  AprsActivity is responsible
        // for tuning the device to the APRS midpoint (144.595 MHz) so that both
        // channels are within the passband; it reads centreFreq from the ViewModel
        // and supplies it via enableDualAprs().  If the sample rate is too narrow
        // to cover both channels the flow is simply not emitted (no exception).
        if((_dualAprsAudioBlocks.subscriptionCount.value>0 || _dualAprsFlow.subscriptionCount.value>0)
            && dualAprsCentreHz!=0L) {
            dualAprsChannels.process(floatIq,device.getSampleRate(),dualAprsCentreHz,session.snapshot.epoch)?.let { audio ->
                if(!_dualAprsAudioBlocks.tryEmit(audio)) dualAprsDroppedPairs.incrementAndGet()
                if(_dualAprsFlow.subscriptionCount.value>0)
                    _dualAprsFlow.tryEmit(Pair(audio.northAmerica.samples,audio.europe.samples))
            }
        }

        // ── Signal level → squelch gate ───────────────────────────────────────
        // Same dBFS bin reference as the trace, restricted to the selected BW.
        // Decoder audio remains upstream of the listening gate; scanner has its own detector.
        val signalDb = squelchSignalDb
        squelchOpen = listeningEnabled && monitoring.updateSquelch(signalDb,squelchLevel,block.sampleCount.toDouble()/device.getSampleRate())
        DebugBus.tick(DebugBus.STAGE_DSP_PROCESS)
        if (DebugBus.counter(DebugBus.STAGE_DSP_PROCESS) % 10L == 0L) {
            DebugBus.setDetail(DebugBus.STAGE_DSP_PROCESS,
                "${"%.1f".format(signalDb)} dBFS  squelch: ${if (squelchOpen) "OPEN" else "closed"}  mode: ${demodMode.displayName}")
        }

        // ── WFM pre-decimation ────────────────────────────────────────────────
        // Integer-decimate to the adaptive WFM IF rate (wfmIfRate(deviceRate)).
        //
        // ── Narrow-mode pre-decimation ────────────────────────────────────────
        // Decimate to narrowIfRateFor(deviceRate, audioSinkRate) — the smallest
        // exact-integer-divisor of deviceRate that is ≥ audioSinkRate.
        //
        // This mirrors GnuRadio's rational_resampler approach:
        //   • When deviceRate is a multiple of audioSinkRate, the IF rate equals
        //     audioSinkRate and the final PolyphaseResampler is trivially 1:1.
        //   • When it is not (e.g. 250 kS/s → 32 kHz: IF=50 kHz, then 50→32 kHz),
        //     the resampler ratio is as small as possible for maximum quality.
        //   • No hardcoded 48 kHz intermediate — the IF rate scales with sinkRate.
        //   • 2560 kS/s (which has no exact divisor near 48 kHz) now decimes
        //     cleanly: e.g. to 32 kHz directly (÷80) for a 32 kHz sink.
        // Raw IQ recording, spectrum, magnitude and dual-data consumers above
        // retain the source data. Analog IQ is repaired before channel filtering.
        iqNoiseBlanker.process(floatIq,device.getSampleRate(),demodMode,device.getCenterFrequency(),noiseBlankerEnabled && decoderAttachment.input==null && decoderAttachment.legacy==null,iqNbThreshold)
        if(DebugBus.counter(DebugBus.STAGE_DSP_PROCESS)%30L==0L) {
            val nb=iqNoiseBlanker.stats()
            DebugBus.setExtra(DebugBus.STAGE_DSP_PROCESS,"NB_DOMAIN","pre-demod wideband IQ")
            DebugBus.setExtra(DebugBus.STAGE_DSP_PROCESS,"NB_ACTIVE",nb.active.toString())
            DebugBus.setExtra(DebugBus.STAGE_DSP_PROCESS,"NB_REPAIRED_SAMPLES",nb.repairedSamples.toString())
            DebugBus.setExtra(DebugBus.STAGE_DSP_PROCESS,"NB_DELAY_SAMPLES",nb.latencySamples.toString())
        }
        val isWfm = demodMode == DemodMode.FM || demodMode == DemodMode.WFM || demodMode == DemodMode.WFM_STEREO
        val isNarrow = demodMode.isNarrowMode()
        val isDigital = demodMode in DIGITAL_VOICE_MODES
        val deviceRate = device.getSampleRate()
        val channelOutput=channel.process(floatIq,ChannelConfiguration(demodMode,deviceRate,audioSinkRate,
            ifBandwidthHz,device.capabilities.maxChannelBandwidth(deviceRate),deemphasisUs))
        val demodRate=channelOutput.ifRate
        val audio=channelOutput.demodulated
        val afterProcessing=session.snapshot
        if(afterProcessing.closed || afterProcessing.epoch!=processingEpoch) return
        if(channelOutput.planChanged) publishChainSnapshot(deviceRate,demodRate,audioSinkRate,isWfm)


        // ── Demod output tick ────────────────────────────────────────────────
        if (audio.isNotEmpty()) {
            DebugBus.tick(DebugBus.STAGE_DEMODULATOR)
            if (DebugBus.counter(DebugBus.STAGE_DEMODULATOR) % 15L == 0L) {
                DebugBus.setDetail(DebugBus.STAGE_DEMODULATOR,
                    "${demodMode.displayName}  ${audio.size} samples  " +
                    when {
                        isWfm            -> {
                            val g = gcd(deviceRate, demodRate)
                            "WFM IF: $demodRate Hz (ratio=${deviceRate/g}:${demodRate/g})"
                        }
                        demodMode.isNarrowMode() -> "narrow IF: $demodRate Hz (N=${deviceRate/demodRate})"
                        else             -> "rate: $deviceRate Hz"
                    })
            }
            // APRS frame diagnostics: push the full decode funnel on EVERY tick so
            // the Debug Panel reflects live state without ~1-second lag.
            // The funnel shows exactly where frames are dying:
            //   samples_in=0 → decoder not called; flags=0 → no bit-sync;
            //   size_rej >> flags → noise only; fcs_fail >> rcvd → bit errors;
            //   ax25_fail > 0 → AX.25 frame structure problems.
            if (demodMode == DemodMode.APRS) {
                DebugBus.setExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_SAMPLES_IN, "${aprsDecoder.samplesIn}")
                DebugBus.setExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_FLAG_SYNCS, "${aprsDecoder.flagSyncs}")
                DebugBus.setExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_SIZE_REJ,   "${aprsDecoder.sizeRejected}")
                DebugBus.setExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_RCVD,       "${aprsDecoder.framesReceived}")
                DebugBus.setExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_FCS_FAIL,   "${aprsDecoder.fcsFailed}")
                DebugBus.setExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_AX25_FAIL,  "${aprsDecoder.ax25Failed}")
                DebugBus.setExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_DECODED,    "${aprsDecoder.framesDecoded}")
            } else {
                DebugBus.clearExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_SAMPLES_IN)
                DebugBus.clearExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_FLAG_SYNCS)
                DebugBus.clearExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_SIZE_REJ)
                DebugBus.clearExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_RCVD)
                DebugBus.clearExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_FCS_FAIL)
                DebugBus.clearExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_AX25_FAIL)
                DebugBus.clearExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_APRS_DECODED)
            }
        }

        val monoAudio=channelOutput.mono
        val decoderAudio=channelOutput.decoderPcm
        val sinkAudio=channelOutput.sinkPcm
        if(isWfm && monoAudio.isNotEmpty())
            getOrCreateRdsDecoder(demodRate).feedAudio(channelOutput.rdsMultiplex ?: monoAudio)

        if (sinkAudio.isNotEmpty()) {
            DebugBus.tick(DebugBus.STAGE_AUDIO_RESAMPLE)
            if (DebugBus.counter(DebugBus.STAGE_AUDIO_RESAMPLE) % 15L == 0L) {
                DebugBus.setDetail(DebugBus.STAGE_AUDIO_RESAMPLE,
                    "${demodRate} → ${audioSinkRate} Hz  ${sinkAudio.size} samples out")
            }
        }

        // ── Protocol decoders (non-WFM) — feed 48 kHz decoderAudio ───────────
        // acarsDecoder was constructed with AcarsDecoder(48_000) → correct rate.
        // pocsagDecoder is now constructed with PocsagDecoder(48_000) (FIX 28,
        // see the field declaration above) to match decoderAudio's actual rate.
        // POCSAG mode/support has been removed; pocsagDecoder now only runs
        // for DemodMode.FLEX.
        if (demodMode == DemodMode.ACARS && decoderAudio.isNotEmpty()) {
            acarsDecoder.feed(decoderAudio)
        }
        if (demodMode == DemodMode.FLEX
                && decoderAudio.isNotEmpty()) {
            pocsagDecoder.feed(decoderAudio)
        }
        // aprsDecoder: APRS mode uses NFM demodulation; the 48 kHz audio
        // produced by the narrow-FM discriminator is fed directly to the AFSK
        // decoder.
        //
        // FIX 21: feed() is a plain synchronous function now (see
        // ProtocolDecoders.kt) and is called inline, on this same DSP
        // processing thread/coroutine, rather than via scope.launch{}.
        // scope uses Dispatchers.Default — a multi-threaded pool — so
        // launching a new coroutine per IQ block gave no guarantee that
        // block N's feed() would finish (or even start) before block N+1's,
        // letting concurrent calls race on bitBuffer / samplesSinceBit /
        // lastRawBit / syncArmed / lastBit and the bandpass-filter envelope
        // state. That silently corrupted the bit-clock on essentially every
        // packet, regardless of how correct the underlying AFSK/AX.25 logic
        // (FIX 1-19) was. Calling feed() directly here guarantees blocks are
        // processed in order, on one thread, with no extra allocation.
        if (demodMode == DemodMode.APRS && decoderAudio.isNotEmpty()) {
            aprsDecoder.feed(decoderAudio)
        }
        // Digital voice decoder — DMR / P25 / NXDN / D-STAR / YSF / M17.
        // Uses the same raw FM-discriminated 48 kHz audio as APRS (AprsDemodulator
        // path — no post-discriminator AGC, LPF, or limiter). DigitalVoiceDecoder
        // performs its own clock recovery, 4FSK symbol slicing, and sync-word
        // correlation. Emits DigitalFrame events on digitalVoiceDecoder.frames.
        //
        // FIX: feed() is a plain synchronous function now (see
        // DigitalVoiceDecoder.kt), and is called inline, on this same DSP
        // processing thread/coroutine, rather than via scope.launch{}.
        // scope uses Dispatchers.Default — a multi-threaded pool — so
        // launching a new coroutine per IQ block gave no guarantee that
        // block N's feed() would finish (or even start) before block N+1's,
        // letting concurrent calls race on agcEnv/dcAcc/phase/tedPrev/
        // thrHigh/thrLow/symBuf/symWrite and the Gardner TED state. That
        // silently corrupted the symbol clock on essentially every frame, so
        // sync words almost never matched and the AMBE/Codec2 vocoder was
        // essentially never invoked — the receiver looked alive (AGC/RSSI
        // still updated) while producing silence. Calling feed() directly
        // here guarantees blocks are processed in order, on one thread, with
        // no extra allocation — the same fix already applied to the APRS/
        // AFSK decoder above (see "FIX 21").
        if (decoderAudio.isNotEmpty() && demodMode in DIGITAL_VOICE_MODES) {
            DebugBus.setExtra(DebugBus.STAGE_DEMODULATOR, DebugBus.EXTRA_DV_FEED_RATE,
                "${demodRate} Hz \u2192 ${DEFAULT_AUDIO_RATE} Hz (decoderAudio)")
            voiceStage.offer(decoderAudio,demodMode,squelchSignalDb,processingEpoch)
        }

        // ── Publish 48 kHz audio baseband for APRS / other subscribers ────────
        if (decoderAudio.isNotEmpty()) {
            val published=decoderAudioClock.publish(decoderAudio,DEFAULT_AUDIO_RATE,1,processingEpoch)
            val input=decoderAttachment.input
            if(input!=null) input.accept(published) else decoderAttachment.legacy?.invoke(published.samples)
            if(_decoderAudioBlocks.subscriptionCount.value>0) _decoderAudioBlocks.tryEmit(published)
            if(_audioFlow.subscriptionCount.value>0) _audioFlow.tryEmit(published.samples)
            externalDecoderStream.accept(published)
        }

        // ── Pipe the same pre-vocoder audio to an external decoder app ────────
        // Independent of demod mode/squelch gating above: a user piping to an
        // external decoder (e.g. to follow a protocol 9GRadio doesn't decode
        // natively, or to cross-check the built-in dsdcc decode) wants the
        // raw discriminator feed exactly as-is, not just the subset already
        // routed to DigitalVoiceDecoder. No-op when disabled (see
        // ExternalDecoderStream.send()).

        // ── Write to AudioTrack ───────────────────────────────────────────────
        // Digital voice modes: decoded speech comes from the DSD-Neo/AMBE
        // vocoder via DigitalFrame.pcmAudio, written to audioWriteChannel by
        // the dedicated startDigVoiceAudio() coroutine (one write per ~20 ms
        // decoded voice frame).
        //
        // BUGFIX: this function used to ALSO push a same-size silence block to
        // audioWriteChannel on every single IQ block while in a digital voice
        // mode, "to avoid AudioTrack starvation". But processIqBlock() runs at
        // the IQ processing rate (hundreds of blocks/sec) — vastly faster than
        // voice frames arrive — into a small capacity=8 DROP_OLDEST channel.
        // Those frequent silence writes evicted the real decoded PCM sitting
        // in the queue almost as soon as startDigVoiceAudio() enqueued it,
        // so the AMBE/DSD-Neo decoder could be decoding perfectly and the
        // speaker would still only ever produce silence. Digital voice modes
        // now skip this per-IQ-block write entirely and let the per-frame
        // writer be the sole producer for audioWriteChannel; AudioTrack
        // underrun during the (typically <100 ms) gap between voice frames
        // is far less audible than permanently dropping all decoded audio.
        if (!isDigital && sinkAudio.isNotEmpty()) {
            val audioOut=monitoring.analog(audio,demodRate,sinkAudio,audioSinkRate,demodMode,
                MonitorAudioBranch.Settings(listeningEnabled,noiseReducerEnabled,volume),squelchOpen)
            if (receptionAvailable) enqueueAudio(audioOut,processingEpoch)
            DebugBus.tick(DebugBus.STAGE_AUDIO_TRACK)
            if (DebugBus.counter(DebugBus.STAGE_AUDIO_TRACK) % 15L == 0L) {
                val rate = DebugBus.rate(DebugBus.STAGE_AUDIO_TRACK)
                DebugBus.setDetail(DebugBus.STAGE_AUDIO_TRACK,
                    "${audioSinkRate} Hz  ${sinkAudio.size} samp/write  ${rate.toInt()} writes/s  vol: $volume")
            }
        } else if (isDigital) {
            // Still tick the stage so the debug panel shows this path as alive
            // (the actual audio write happens in startDigVoiceAudio()).
            DebugBus.tick(DebugBus.STAGE_AUDIO_TRACK)
        }

        // ── Stats update ──────────────────────────────────────────────────────
        // bufferDrops reflects IQ blocks silently discarded by the SharedFlow
        // DROP_OLDEST policy when processIqBlock() is too slow to keep up.
        // The count lives in DebugBus (incremented by RtlSdrDevice.tryEmit()).
        _stats.value = _stats.value.copy(
            sampleRate = device.getSampleRate(),
            audioRate  = audioSinkRate,
            demodMode = demodMode.displayName,
            squelchOpen = squelchOpen,
            signalDb = signalDb,
            channelPeakDb = sqlReading.peakDb,
            widebandPeakDb = widebandPeakDb,
            narrowbandCenterDb = narrowbandCenterDb,
            isRecordingIq = iqRecorder.isRecording,
            isRecordingAudio = soundCapture.isRecording,
            bufferDrops = DebugBus.getIqFlowDropCount().toInt()
        )
    }

    // ── Sink resampler ────────────────────────────────────────────────────────
    // Converts demod audio (at demodRate) → audioSinkRate for the AudioTrack.
    // Persists across blocks so the polyphase delay-line gives continuous-time
    // reconstruction at USB transfer boundaries (no click/glitch at block edges).
    // Invalidated (set null) by setAudioSinkRate() whenever the user switches
    // the output rate; also rebuilt automatically when demodRate changes (e.g.
    // switching between WFM IF rate and narrow-mode rate).
    private fun updateStats() {
        _stats.value = _stats.value.copy(
            sampleRate = device.getSampleRate(),   // keep sample-rate current on every settings change
            demodMode = demodMode.displayName,
            audioVolume = volume,
            isRecordingIq = iqRecorder.isRecording,
            isRecordingAudio = soundCapture.isRecording
        )
    }
}
