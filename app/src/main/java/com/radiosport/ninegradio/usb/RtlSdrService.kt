@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.radiosport.ninegradio.usb

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.*
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.radiosport.ninegradio.debug.DebugBus
import androidx.core.app.NotificationCompat
import com.radiosport.ninegradio.R
import com.radiosport.ninegradio.RtlSdrApplication
import com.radiosport.ninegradio.dsp.DspEngine
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.source.IqSource
import com.radiosport.ninegradio.source.RtlSdrDeviceSource
import com.radiosport.ninegradio.source.RtlTcpSource
import com.radiosport.ninegradio.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.radiosport.ninegradio.source.UsbSources
import com.radiosport.ninegradio.source.DeviceCapabilities
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import android.os.SystemClock

/**
 * Foreground service that owns the IQ source connection (USB RTL-SDR or TCP rtl_tcp)
 * and the DSP engine. Survives activity lifecycle changes.
 *
 * Source selection:
 *   • USB  — start with ACTION_START + EXTRA_DEVICE (UsbDevice)
 *   • TCP  — start with ACTION_START_TCP + EXTRA_TCP_HOST + EXTRA_TCP_PORT
 */
class RtlSdrService : Service() {

    companion object {
        private const val TAG = "RtlSdrService"
        private const val NOTIF_ID = 1001

        const val ACTION_START     = "com.radiosport.ninegradio.START"
        const val ACTION_START_TCP = "com.radiosport.ninegradio.START_TCP"
        const val ACTION_STOP      = "com.radiosport.ninegradio.STOP"

        const val EXTRA_DEVICE   = "usb_device"
        const val EXTRA_TCP_HOST = "tcp_host"
        const val EXTRA_TCP_PORT = "tcp_port"
        const val DEFAULT_TCP_PORT = 1234
    }

    inner class LocalBinder : Binder() {
        fun getService(): RtlSdrService = this@RtlSdrService
    }

    private val binder = LocalBinder()
    val audioContent by lazy { com.radiosport.ninegradio.content.AudioContentReceiver(this) { receiverWanted } }
    val adsbReceiver by lazy { com.radiosport.ninegradio.dsp.ReadsbReceiver(this) }
    private var requestedMode: DemodMode?=null
    private val scope  = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Prevents concurrent connectSource() calls racing. */
    private val controlDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "receiver-control").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val controlScope = CoroutineScope(controlDispatcher + SupervisorJob())
    private val generation = AtomicLong()
    private var monitorJob: Job? = null
    private var targetsJob: Job? = null
    private data class Tune(val hz: Long, val generation: Long, val immediate: Boolean)
    private val tunes = Channel<Tune>(Channel.CONFLATED)
    private fun control(action: () -> Unit) {
        val expected = generation.get()
        controlScope.launch { if (expected == generation.get()) action() }
    }
    init {
        controlScope.launch {
            var nextAllowed = 0L
            for (initial in tunes) {
                var request = initial
                while (!request.immediate) {
                    val wait = nextAllowed - SystemClock.uptimeMillis()
                    if (wait <= 0) break
                    request = withTimeoutOrNull(wait) { tunes.receive() } ?: break
                }
                while (true) { request = tunes.tryReceive().getOrNull() ?: break }
                if (request.generation != generation.get()) continue
                val src = source ?: continue
                if (!src.capabilities.supportsFrequency(request.hz)) {
                    if (src.capabilities.supportsBrowsing(request.hz)) dspEngine?.setReceptionAvailable(false)
                    continue
                }
                dspEngine?.setCarrierFrequency(request.hz) ?: src.setCenterFrequency(request.hz)
                dspEngine?.setReceptionAvailable(true)
                nextAllowed = SystemClock.uptimeMillis() + 33L
            }
        }
    }
    @Volatile private var receiverWanted = true
    private var selectedFactory: (() -> IqSource)? = null
    private var selectedCapabilities = DeviceCapabilities.RTL
    val capabilities: DeviceCapabilities get() = source?.capabilities ?: selectedCapabilities

    @Volatile var source: IqSource? = null
        private set
    @Volatile var dspEngine: DspEngine? = null

    // ── Persisted pending settings (applied on every reconnect) ──────────────

    @Volatile private var pendingFftSize: Int       = 0
    @Volatile private var pendingFftDecimation: Int = 1
    @Volatile private var pendingSampleRate: Int    = 1_920_000
    @Volatile private var pendingNoiseBlanker: Boolean  = false
    @Volatile private var pendingNoiseReducer: Boolean  = false
    @Volatile private var pendingNrStrength = 1f
    @Volatile private var pendingIqNbThreshold=8f
    fun setIqNoiseBlankerThreshold(value: Float) {
        pendingIqNbThreshold=value.coerceIn(3f,20f)
        dspEngine?.setIqNoiseBlankerThreshold(pendingIqNbThreshold)
    }
    @Volatile private var pendingListeningAudio=com.radiosport.ninegradio.dsp.ListeningAudioProcessor.Settings()
    fun setListeningAudio(settings: com.radiosport.ninegradio.dsp.ListeningAudioProcessor.Settings) {
        pendingListeningAudio=settings
        dspEngine?.setListeningAudio(settings)
    }
    fun setReceiverEnabled(enabled: Boolean) {
        val changed = receiverWanted != enabled
        receiverWanted = enabled
        if (enabled && (changed || source == null && _connectionState.value !is ConnectionState.Connecting))
            selectedFactory?.let { switchSource(it) }
        else if (!enabled) disconnectSource()
    }
    fun setNoiseReductionStrength(strength: Float) {
        pendingNrStrength = strength
        dspEngine?.setNoiseReductionStrength(strength)
    }
    @Volatile private var pendingIfBandwidth: Int   = 0
    @Volatile private var pendingAudioSinkRate: Int = DspEngine.DEFAULT_AUDIO_SINK_RATE

    // ── Connection state ──────────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting   : ConnectionState()
        data class Connected(val deviceName: String) : ConnectionState()
        data class Error(val message: String)        : ConnectionState()
    }

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "RtlSdrService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val usbDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(EXTRA_DEVICE, UsbDevice::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DEVICE)
                startForegroundNotification()
                if (usbDevice != null) {
                    val usbManager = getSystemService(USB_SERVICE) as UsbManager
                    switchSource({ UsbSources.create(usbManager, usbDevice) })
                }
            }
            ACTION_START_TCP -> {
                val host = intent.getStringExtra(EXTRA_TCP_HOST) ?: "localhost"
                val port = intent.getIntExtra(EXTRA_TCP_PORT, DEFAULT_TCP_PORT)
                startForegroundNotification()
                connectTcp(host, port)
            }
            ACTION_STOP -> {
                disconnectSource()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        audioContent.stop()
        generation.incrementAndGet()
        controlScope.launch {
            disconnectNow()
            controlScope.cancel()
            controlDispatcher.close()
        }
        scope.cancel()
        Log.i(TAG, "RtlSdrService destroyed")
    }

    // ── Connection logic ──────────────────────────────────────────────────────

    private fun switchSource(factory: () -> IqSource, rateOverride: Int? = null) {
        receiverWanted = true
        val expected = generation.incrementAndGet()
        controlScope.launch {
            disconnectNow()
            if (expected != generation.get()) return@launch
            _connectionState.value = ConnectionState.Connecting
            val next = factory()
            try {
                check(next.open()) { "Failed to open ${next.getSourceName()}" }
                // Like SDR++'s Airspy HF+ source, configure the selected device
                // rate before starting either the receiver or its DSP clock.
                // A new HF+ profile keeps the driver's preferred 768 kS/s;
                // a saved profile supplies its own rate instead of an RTL rate.
                val requestedRate = rateOverride ?: if (next.capabilities.floatIq)
                    getSharedPreferences("rtlsdr_prefs",MODE_PRIVATE)
                        .getInt("device_${next.capabilities.id}_rate",next.getSampleRate())
                    else pendingSampleRate
                check(next.setSampleRate(next.capabilities.nearestRate(requestedRate))) {
                    "Failed to configure ${next.getSourceName()} sample rate"
                }
                if (expected != generation.get()) { next.close(); return@launch }
                selectedCapabilities = next.capabilities
                source = next
                val engine = DspEngine(next)
                engine.setFmDeemphasis(androidx.preference.PreferenceManager.getDefaultSharedPreferences(this@RtlSdrService)
                    .getString("pref_deemph_us","75")?.toIntOrNull() ?: 75)
                dspEngine = engine
                engine.setRecordingPlaybackMuted(playbackMonitorMuted)
                audioContent.attach(engine)
                if (next.capabilities.floatIq) engine.setDemodMode(DemodMode.AM)
                if (pendingFftSize > 0) engine.setFftSize(pendingFftSize)
                engine.setFftDecimation(pendingFftDecimation)
                engine.setNoiseBlankerEnabled(pendingNoiseBlanker)
                engine.setNoiseReducerEnabled(pendingNoiseReducer)
                engine.setListeningEnabled(true)
                engine.setNoiseReductionStrength(pendingNrStrength)
                engine.setIqNoiseBlankerThreshold(pendingIqNbThreshold)
                engine.setListeningAudio(pendingListeningAudio)
                if (pendingIfBandwidth > 0) engine.setIfBandwidth(pendingIfBandwidth)
                engine.setAudioSinkRate(pendingAudioSinkRate)
                targetsJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    engine.aprsDecoder.packets.collect { packet -> control {
                        val lat=packet.latitude; val lon=packet.longitude
                        if(source===next && lat!=null && lon!=null)
                            com.radiosport.ninegradio.data.RadioTargets.upsertStation(
                                com.radiosport.ninegradio.data.RadioTarget("aprs:${packet.source}",packet.source,
                                    lat,lon,"${packet.source}\n$lat, $lon",System.currentTimeMillis()))
                    } }
                }
                engine.start()
                if (expected != generation.get()) { disconnectNow(); return@launch }
                _connectionState.value = ConnectionState.Connected(next.getSourceName())
                updateNotification("Connected: ${next.getSourceName()}")
                monitorJob = scope.launch {
                    combine(next.statusFlow,engine.failure,engine.inputComplete) { status,failure,complete ->
                        when {
                            failure!=null -> status.copy(error=failure,streamRestartRequired=true)
                            complete -> status.copy(connected=false)
                            else -> status
                        }
                    }.filter { it.streamRestartRequired || !it.connected }.collect { status ->
                        control {
                            if (source === next) {
                                if(engine.inputComplete.value) audioContent.finishSoftwareInput()
                                disconnectNow()
                                if (status.error != null || status.streamRestartRequired)
                                    _connectionState.value = ConnectionState.Error(status.error ?: "IQ stream stopped; reconnect the receiver")
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                audioContent.attach(null)
                dspEngine?.stop()
                next.close()
                source = null
                dspEngine = null
                _connectionState.value = ConnectionState.Error(error.message ?: "Connection failed")
                Log.e(TAG, "Source connection failed", error)
            }
        }
    }
    private fun disconnectNow() {
        finishCapture(false); finishCapture(true)
        captureMonitor?.cancel(); captureMonitor=null
        monitorJob?.cancel()
        targetsJob?.cancel(); targetsJob=null
        monitorJob = null
        adsbReceiver.stop()
        audioContent.attach(null)
        dspEngine?.stop()
        dspEngine = null
        source?.close()
        source = null
        _connectionState.value = ConnectionState.Disconnected
        DebugBus.resetAll()
        updateNotification("Disconnected")
    }
    fun disconnectSource() {
        generation.incrementAndGet()
        controlScope.launch { disconnectNow() }
    }
    suspend fun awaitControls() { withContext(controlDispatcher) { } }

    fun reconnect(usbDevice: UsbDevice, start: Boolean = true) {
        val manager = getSystemService(USB_SERVICE) as UsbManager
        val factory = { UsbSources.create(manager, usbDevice) }
        selectedFactory = factory
        receiverWanted = start
        if (start) switchSource(factory) else disconnectSource()
    }
    fun connectTcp(host: String, port: Int = DEFAULT_TCP_PORT) {
        val factory = { RtlTcpSource(host, port) }
        selectedFactory = factory
        switchSource(factory)
    }
    fun connectFile(path: String, rate: Int, frequency: Long, mode: DemodMode) {
        val factory = { com.radiosport.ninegradio.source.FileIqSource(java.io.File(path), rate, frequency, mode) }
        selectedFactory = factory
        switchSource(factory)
    }

    val spectrumFlow: Flow<FloatArray> = connectionState.flatMapLatest { state ->
        if (state is ConnectionState.Connected) dspEngine?.spectrumFlow ?: emptyFlow()
        else emptyFlow()
    }

    val statsFlow: Flow<DspEngine.DspStats> = connectionState.flatMapLatest { state ->
        if (state is ConnectionState.Connected) dspEngine?.statsFlow ?: emptyFlow()
        else emptyFlow()
    }

    /** Unified source status (covers both USB DeviceStatus and TCP SourceStatus). */
    val sourceStatusFlow: Flow<IqSource.SourceStatus> = connectionState.flatMapLatest { state ->
        if (state is ConnectionState.Connected) source?.statusFlow ?: emptyFlow()
        else emptyFlow()
    }

    /**
     * Legacy alias for [sourceStatusFlow] typed to [RtlSdrDevice.DeviceStatus].
     *
     * Callers that still use [RtlSdrDevice.DeviceStatus] (e.g. existing ViewModel observers)
     * can collect this flow and get a mapped value; new code should prefer [sourceStatusFlow].
     */
    val statusFlow: Flow<IqSource.SourceStatus> get() = sourceStatusFlow

    val audioFlow: Flow<FloatArray> = connectionState.flatMapLatest { state ->
        if (state is ConnectionState.Connected) dspEngine?.audioFlow ?: emptyFlow()
        else emptyFlow()
    }

    val dualAprsFlow: Flow<Pair<FloatArray, FloatArray>> = connectionState.flatMapLatest { state ->
        if (state is ConnectionState.Connected) dspEngine?.dualAprsFlow ?: emptyFlow()
        else emptyFlow()
    }

    // ── Convenience wrappers ──────────────────────────────────────────────────

    /**
     * Tune to [hz] (the user's dial frequency).
     *
     * Routing goes through [DspEngine.setCarrierFrequency] rather than directly to
     * [source] so the engine can transparently apply the ±1500 Hz SSB BFO offset for
     * USB and LSB modes.  When [dspEngine] is null (device not yet connected) we fall
     * back to [source] directly — this matches prior behaviour and is safe because
     * the engine will apply the offset via [DspEngine.setDemodMode] on the next connect.
     */
    fun setFrequency(hz: Long, immediate: Boolean = false) {
        tunes.trySend(Tune(hz, generation.get(), immediate))
    }
    fun setSampleRate(rate: Int) {
        pendingSampleRate = rate
        control { source?.let { active ->
            val selected=active.capabilities.nearestRate(rate)
            if(selected!=active.getSampleRate()) {
                finishCapture(false); finishCapture(true)
                // SDR++ configures HF+ on a stopped source. Reuse our existing
                // close/open path so the old DSP and playback clock cannot
                // consume blocks across a device-rate transition.
                val factory=selectedFactory
                if(factory!=null) switchSource(factory,selected)
                else { adsbReceiver.stop(); active.setSampleRate(selected) }
            }
            updateAdsbReceiver()
        } }
    }
    fun setGain(idx: Int) = control { source?.setGain(idx) }
    fun setGainMode(mode: Int) = control { source?.setGainMode(mode) }
    fun setTunerAgc(on: Boolean) = control { source?.setTunerAgcEnabled(on) }
    fun setHardwareAgc(on: Boolean) = control { source?.setHardwareAgcEnabled(on) }
    fun setBiasTee(on: Boolean) = control { source?.setBiasTee(on) }
    fun setDirectSampling(mode: Int) = control { source?.setDirectSampling(mode) }
    fun setPpm(ppm: Int) = control { source?.setPpmCorrection(ppm) }
    fun setDemodMode(mode: DemodMode) = control {
        if (capabilities.supportsMode(mode)) {
            requestedMode=mode
            dspEngine?.setDemodMode(mode)
            updateAdsbReceiver()
        }
    }
    private fun updateAdsbReceiver() {
        val active=source
        if(requestedMode==DemodMode.ADSB && active!=null && active.isOpen() && capabilities.supportsMode(DemodMode.ADSB))
            adsbReceiver.start(active)
        else adsbReceiver.stop()
    }
    fun setFftSize(size: Int) { pendingFftSize = size; control { dspEngine?.setFftSize(size) } }
    fun setFftDecimation(factor: Int) { pendingFftDecimation = factor; control { dspEngine?.setFftDecimation(factor) } }
    fun setSquelch(dbLevel: Float) = control { dspEngine?.setSquelchLevel(dbLevel) }
    fun setAudioVolume(vol: Float) = control { dspEngine?.setVolume(vol) }
    fun setFmDeemphasis(us: Int) = control { dspEngine?.setFmDeemphasis(us) }
    fun setDcBlock(enabled: Boolean) = control { dspEngine?.setDcBlockEnabled(enabled) }
    fun setFreqOffset(hz: Int) = control { dspEngine?.setFreqOffset(hz) }
    fun enableDualAprs(centreHz: Long) = control { dspEngine?.enableDualAprs(centreHz) }
    fun disableDualAprs() = control { dspEngine?.disableDualAprs() }
    fun setIfBandwidth(hz: Int) { pendingIfBandwidth = hz; control { dspEngine?.setIfBandwidth(hz) } }
    fun getIfBandwidth(): Int = dspEngine?.getIfBandwidth() ?: pendingIfBandwidth
    fun setNoiseBlanker(enabled: Boolean) {
        pendingNoiseBlanker = enabled
        dspEngine?.setNoiseBlankerEnabled(enabled)
    }
    fun setNoiseReducer(enabled: Boolean) {
        pendingNoiseReducer = enabled
        dspEngine?.setNoiseReducerEnabled(enabled)
    }
    @Volatile private var playbackMonitorMuted=false
    fun setRecordingPlaybackMuted(muted:Boolean) {
        playbackMonitorMuted=muted;dspEngine?.setRecordingPlaybackMuted(muted)
    }
    fun setAudioSinkRate(rate: Int) {
        pendingAudioSinkRate = rate
        control { if(dspEngine?.getAudioSinkRate()!=rate) finishCapture(true); dspEngine?.setAudioSinkRate(rate) }
    }
    fun getAudioSinkRate(): Int    = dspEngine?.getAudioSinkRate() ?: pendingAudioSinkRate
    fun setExternalDecoderEnabled(enabled: Boolean) { dspEngine?.setExternalDecoderEnabled(enabled) }
    fun setExternalDecoderTarget(host: String, port: Int) { dspEngine?.setExternalDecoderTarget(host, port) }
    private data class CaptureSession(val id:Long,val path:String,val started:Long,val limitMs:Long)
    @Volatile private var iqSession:CaptureSession?=null
    @Volatile private var audioSession:CaptureSession?=null
    private var captureMonitor:Job?=null
    private val capturePrefs by lazy { androidx.preference.PreferenceManager.getDefaultSharedPreferences(this) }
    fun setRecordingSquelch(enabled:Boolean) {
        capturePrefs.edit().putBoolean("pref_record_squelch",enabled).apply()
        dspEngine?.setRecordingSquelch(enabled)
    }
    fun recordingRemainingMs(audio:Boolean):Long? {
        val session=if(audio) audioSession else iqSession
        return session?.takeIf { it.limitMs>0 }?.let { (it.limitMs-(SystemClock.elapsedRealtime()-it.started)).coerceAtLeast(0) }
    }
    private fun beginCapture(path:String,audio:Boolean,profile:com.radiosport.ninegradio.recording.AudioRecordingProfile?=null) {
        finishCapture(audio)
        val engine=dspEngine ?: return
        engine.setRecordingSquelch(capturePrefs.getBoolean("pref_record_squelch",false))
        if(!audio) try {
            com.radiosport.ninegradio.recording.IqRecordingInfo.write(java.io.File(path),engine.getDialFrequencyHz(),
                source?.getCenterFrequency() ?: engine.getDialFrequencyHz(),source?.getSampleRate() ?: 48000,
                requestedMode ?: DemodMode.AM,source?.capabilities?.floatIq==true)
        } catch(error: Exception) {
            Log.e(TAG,"IQ metadata could not be written",error)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(this,"无法保存 IQ 元数据，录制未开始",android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        if(audio) engine.startAudioRecording(path,profile ?: com.radiosport.ninegradio.recording.AudioRecordingProfile.forFile(path)) else engine.startIqRecording(path)
        val running=if(audio) engine.isAudioRecording() else engine.statsFlow.value.isRecordingIq
        if(!running) {
            if(!audio) com.radiosport.ninegradio.recording.IqRecordingInfo.metadataFile(java.io.File(path)).delete()
            else android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(this,"声音录制未开始，请检查格式与存储空间",android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        val meta=com.radiosport.ninegradio.data.RecordingMeta(filePath=path,type=if(audio) "AUDIO" else "IQ",
            frequencyHz=engine.getDialFrequencyHz(),sampleRate=if(audio) (profile ?: com.radiosport.ninegradio.recording.AudioRecordingProfile.forFile(path)).fileSampleRate(engine.getAudioSinkRate()) else source?.getSampleRate() ?: 0,
            demodMode=(requestedMode ?: DemodMode.AM).name,durationMs=0,fileSizeBytes=0)
        val id=(application as RtlSdrApplication).database.recordingMetaDao().insertSession(meta)
        val session=CaptureSession(id,path,SystemClock.elapsedRealtime(),capturePrefs.getString("pref_record_stop_seconds","0")?.toLongOrNull()?.coerceAtLeast(0)?.times(1000) ?: 0L)
        if(audio) audioSession=session else iqSession=session
        if(captureMonitor?.isActive!=true) captureMonitor=controlScope.launch {
            while(iqSession!=null || audioSession!=null) {
                delay(100)
                for(isAudio in listOf(false,true)) {
                    val active=(if(isAudio) audioSession else iqSession) ?: continue
                    val stats=dspEngine?.statsFlow?.value
                    val stillRecording=if(isAudio) dspEngine?.isAudioRecording()==true else stats?.isRecordingIq==true
                    if(!stillRecording || (active.limitMs>0 && SystemClock.elapsedRealtime()-active.started>=active.limitMs)) finishCapture(isAudio)
                }
            }
        }
    }
    private fun finishCapture(audio:Boolean) {
        val session=(if(audio) audioSession else iqSession) ?: return
        if(audio) audioSession=null else iqSession=null
        val engine=dspEngine
        if(audio) engine?.stopAudioRecording() else engine?.stopIqRecording()
        val duration=if(audio) engine?.audioRecordedDurationMs() else engine?.iqRecordedDurationMs()
        (application as RtlSdrApplication).database.recordingMetaDao().finishSession(session.id,duration ?: 0L,java.io.File(session.path).length())
        val failure=if(audio) engine?.audioRecordingError() else engine?.iqRecordingError()
        if(failure!=null) android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(this,"录制已停止：$failure",android.widget.Toast.LENGTH_LONG).show()
        }
    }
    fun startRecording(path: String) = control { beginCapture(path,false) }
    fun stopRecording() = control { finishCapture(false) }
    fun startAudioRecording(path:String,profile:com.radiosport.ninegradio.recording.AudioRecordingProfile=
        com.radiosport.ninegradio.recording.AudioRecordingProfile.forFile(path)) = control { beginCapture(path,true,profile) }
    fun stopAudioRecording() = control { finishCapture(true) }

    // ── Legacy device accessor (null when using TCP source) ───────────────────

    /** The underlying [RtlSdrDevice], or null when connected via TCP. */
    val device: RtlSdrDevice? get() = (source as? RtlSdrDeviceSource)?.device

    /** True when the connected USB device is an RTL-SDR Blog V4. False for TCP or non-V4. */
    val isV4: Boolean get() = device?.isV4 ?: false

    // ── Foreground notification ───────────────────────────────────────────────

    private fun startForegroundNotification() {
        val notif = buildNotification("Initializing…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(status))
    }

    private fun buildNotification(status: String): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, RtlSdrService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, RtlSdrApplication.CHANNEL_SDR_SERVICE)
            .setContentTitle("9GRadio")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentIntent(intent)
            .addAction(R.drawable.ic_stop, "Disconnect", stopIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
