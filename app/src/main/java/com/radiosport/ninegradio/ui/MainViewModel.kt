package com.radiosport.ninegradio.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.*
import com.radiosport.ninegradio.data.*
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.dsp.DspEngine
import com.radiosport.ninegradio.dsp.FftEngine
import com.radiosport.ninegradio.scanner.FrequencyScanner
import com.radiosport.ninegradio.source.IqSource
import com.radiosport.ninegradio.source.RtlTcpSource
import com.radiosport.ninegradio.source.DeviceCapabilities
import com.radiosport.ninegradio.usb.RtlSdrDevice
import com.radiosport.ninegradio.usb.RtlSdrService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        private const val PREFS = "rtlsdr_prefs"
    }

    private val db = (application as com.radiosport.ninegradio.RtlSdrApplication).database
    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var profileId = prefs.getString("activeDevice", "rtl") ?: "rtl"
    private val _capabilities = MutableStateFlow(if (profileId == "airspyhf") DeviceCapabilities.airspy(
        prefs.getString("device_airspyhf_rates",null)?.split(',')?.mapNotNull { it.toIntOrNull()?.takeIf { r -> r>0 } }?.takeIf { it.isNotEmpty() }
            ?: listOf(prefs.getInt("device_airspyhf_rate",768000).coerceAtLeast(1))) else DeviceCapabilities.RTL)
    val capabilities: StateFlow<DeviceCapabilities> = _capabilities
    val availableSampleRates: IntArray get() = _capabilities.value.sampleRates.toIntArray()
    private val _receiverEnabled = MutableStateFlow(prefs.getBoolean("receiverEnabled", true))
    val receiverEnabled = _receiverEnabled.asStateFlow()
    fun setReceiverEnabled(enabled: Boolean) {
        _receiverEnabled.value = enabled
        sdrService?.setReceiverEnabled(enabled)
        prefs.edit().putBoolean("receiverEnabled", enabled).apply()
    }
    fun setNoiseReductionStrength(strength: Float) {
        sdrService?.setNoiseReductionStrength(strength)
        prefs.edit().putFloat("nrStrength", strength).apply()
    }
    private val _autoDemod = MutableStateFlow(prefs.getBoolean("autoDemod", false))
    private val _modeStationMemory = MutableStateFlow(prefs.getBoolean("modeStationMemory", true))
    val modeStationMemory = _modeStationMemory.asStateFlow()

    fun setModeStationMemory(enabled: Boolean) {
        if (_modeStationMemory.value == enabled) return
        saveJob?.cancel()
        // Flush the last enabled-session frequency before freezing the store.
        if (!enabled) saveDeviceSettings()
        _modeStationMemory.value = enabled
        prefs.edit().putBoolean("modeStationMemory", enabled).apply()
        // Restore before any delayed/device save can copy the disabled-session dial.
        if (enabled && restoreModeStation(_demodMode.value)) saveDeviceSettings()
    }

    private fun restoreModeStation(mode: DemodMode): Boolean {
        if (!prefs.contains(modeKey(mode, "freq"))) return false
        val remembered = prefs.getLong(modeKey(mode, "freq"), _centerFreqHz.value)
        if (_capabilities.value.supportsFrequency(remembered)) {
            _centerFreqHz.value = remembered
            sdrService?.setFrequency(remembered, immediate = true)
            return true
        } else _messages.tryEmit("该模式的历史频率不适用于当前设备")
        return false
    }
    private val _listeningAudio=MutableStateFlow(com.radiosport.ninegradio.dsp.ListeningAudioProcessor.Settings(
        leveling=prefs.getBoolean("audioLeveling",false),limitDb=prefs.getFloat("audioLevelLimit",12f),
        notch=prefs.getBoolean("audioNotch",false),notchSpeed=prefs.getFloat("audioNotchSpeed",0.005f)))
    val listeningAudio=_listeningAudio.asStateFlow()
    fun setListeningAudio(settings: com.radiosport.ninegradio.dsp.ListeningAudioProcessor.Settings) {
        _listeningAudio.value=settings
        sdrService?.setListeningAudio(settings)
        prefs.edit().putBoolean("audioLeveling",settings.leveling).putFloat("audioLevelLimit",settings.limitDb)
            .putBoolean("audioNotch",settings.notch).putFloat("audioNotchSpeed",settings.notchSpeed).apply()
    }
    val autoDemod = _autoDemod.asStateFlow()
    fun setAutoDemod(enabled: Boolean) {
        if (_autoDemod.value == enabled) return
        _autoDemod.value = enabled
        prefs.edit().putBoolean("autoDemod", enabled).apply()
        if (enabled) applyAutomaticDemod(_centerFreqHz.value)
    }
    private fun applyAutomaticDemod(hz: Long,previousFrequencyHz: Long=_centerFreqHz.value) {
        val mode = com.radiosport.ninegradio.dsp.ReceiverBandPlan.at(hz).mode ?: return
        if (mode != _demodMode.value && _capabilities.value.supportsMode(mode))
            setDemodMode(mode, automatic = true,previousFrequencyHz=previousFrequencyHz)
    }
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()
    private var saveJob: Job? = null
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch { delay(350); saveDeviceSettings() }
    }
    private fun saveDeviceSettings() {
        val key = "device_${profileId}_"
        val editor=prefs.edit()
        if(_modeStationMemory.value && _capabilities.value.supportsFrequency(_centerFreqHz.value))
            editor.putLong(modeKey(_demodMode.value,"freq"),_centerFreqHz.value)
        editor.putString("activeDevice", profileId)
            .putLong(key + "freq", _centerFreqHz.value).putString(key + "mode", _demodMode.value.name)
            .putInt(key + "rate", _sampleRate.value).putInt(key + "gain", _gainIndex.value)
            .putInt(key + "width", _ifBandwidthHz.value)
            .putBoolean(key + "agc", _tunerAgcEnabled.value).putInt(key + "ppm", _ppm.value)
            .putBoolean(key + "hwAgc", _hardwareAgcEnabled.value)
            .putBoolean(key + "bias", _biasTee.value).putInt(key + "direct", _directSampling.value)
            .putLong("freq", _centerFreqHz.value).putString("demod", _demodMode.value.name).apply()
    }
    private var sourceProfileLoaded = false
    private fun adoptSource(service: RtlSdrService) {
        val caps = service.capabilities
        prefs.edit().putString("device_${caps.id}_rates",caps.sampleRates.joinToString(",")).apply()
        if (!sourceProfileLoaded || profileId != caps.id) {
            saveJob?.cancel()
            if (sourceProfileLoaded) saveDeviceSettings()
            sourceProfileLoaded = true
            profileId = caps.id
            val key = "device_${profileId}_"
            _centerFreqHz.value = caps.browseFrequency(prefs.getLong(key + "freq", if (caps.floatIq) 10_000_000 else 100_000_000))
            _demodMode.value = runCatching { DemodMode.valueOf(prefs.getString(key + "mode", if (caps.floatIq) "AM" else "NFM")!!) }
                .getOrDefault(if (caps.floatIq) DemodMode.AM else DemodMode.NFM)
            if (!caps.supportsMode(_demodMode.value)) _demodMode.value = DemodMode.AM
            _ifBandwidthHz.value = validWidth(_demodMode.value,
                prefs.getInt(key + "width", prefs.getInt(modeKey(_demodMode.value, "ifBandwidth"), 0)))
            _sampleRate.value = caps.nearestRate(prefs.getInt(key + "rate", service.source?.getSampleRate() ?: 1920000))
            _gainIndex.value = prefs.getInt(key + "gain", if (caps.floatIq) 0 else 26)
                .coerceIn(0, ((service.source?.getGainCount() ?: 29) - 1).coerceAtLeast(0))
            _tunerAgcEnabled.value = prefs.getBoolean(key + "agc", caps.floatIq)
            _ppm.value = prefs.getInt(key + "ppm", 0)
            _hardwareAgcEnabled.value = caps.hardwareAgc && prefs.getBoolean(key + "hwAgc", false)
            _biasTee.value = caps.biasTee && prefs.getBoolean(key + "bias", false)
            _directSampling.value = if (caps.directSampling) prefs.getInt(key + "direct", 0) else 0
        }
        if (service.source is com.radiosport.ninegradio.source.FileIqSource) {
            val file = service.source as com.radiosport.ninegradio.source.FileIqSource
            _centerFreqHz.value = file.dialFrequencyHz
            _demodMode.value = file.mode
            _sampleRate.value = file.getSampleRate()
        }
        _capabilities.value = caps
        syncFmStereoPreference(_demodMode.value)
        _centerFreqHz.value = caps.browseFrequency(_centerFreqHz.value)
        _sampleRate.value = caps.nearestRate(_sampleRate.value)
        if(_demodMode.value==DemodMode.ADSB && caps.supportsMode(DemodMode.ADSB))
            _sampleRate.value=com.radiosport.ninegradio.dsp.ReadsbReceiver.SAMPLE_RATE
        if(_demodMode.value==DemodMode.ADSB) configureAdsbRf(fromDeviceRestore=true)
        scheduleSave()
    }


    // ─── Service binding ──────────────────────────────────────────────────────

    private var sdrService: RtlSdrService? = null
    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected

    @Volatile private var pendingDevice: UsbDevice? = null

    val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            sdrService = (binder as RtlSdrService.LocalBinder).getService()
            _serviceConnected.value = true
            // Push the saved FFT size so the service's pendingFftSize is
            // populated before the first DspEngine is created.  Without this
            // the engine always starts at the default 256 bins regardless of
            // what the user previously chose.
            sdrService?.setFftSize(_fftSize.value)
            // Push the saved decimation factor.
            sdrService?.setFftDecimation(_decimation.value)
            // Push saved noise processing state.
            sdrService?.setNoiseBlanker(_noiseBlankerEnabled.value)
            sdrService?.setIqNoiseBlankerThreshold(_iqNbThreshold.value)
            sdrService?.setNoiseReducer(_noiseReducerEnabled.value)
            sdrService?.setReceiverEnabled(_receiverEnabled.value)
            sdrService?.setNoiseReductionStrength(prefs.getFloat("nrStrength", 1f))
            sdrService?.setListeningAudio(_listeningAudio.value)
            // Push saved IF bandwidth (0 = auto, ignored by service).
            val savedIfBw = _ifBandwidthHz.value
            if (savedIfBw > 0) sdrService?.setIfBandwidth(savedIfBw)
            // Push saved sample rate so pendingSampleRate is populated before
            // connectDevice() calls open().  The observeServiceFlows() Connected
            // handler also applies it post-connect, but that fires only after
            // engine.start() has already begun streaming.  Pushing here ensures
            // the rate is correct from the very first IQ block.
            sdrService?.setSampleRate(_sampleRate.value)
            observeServiceFlows()
            // Connect any device that arrived before the service bound.
            pendingDevice?.let { dev ->
                pendingDevice = null
                sdrService?.reconnect(dev, _receiverEnabled.value)
            }
            Log.i(TAG, "Service connected")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            sdrService = null
            _serviceConnected.value = false
        }
    }

    private var serviceFlowJob: Job? = null
    private fun observeServiceFlows() {
        val svc = sdrService ?: return

        serviceFlowJob?.cancel()
        serviceFlowJob = viewModelScope.launch {
        // Mirror connection state, and push all saved settings to hardware
        // the moment the device reaches Connected.  The RTL-SDR resets to its
        // hardware defaults on every open() — notably sample rate = 2 048 000 Hz —
        // so without this block any user-chosen rate other than 2.048 MS/s is never
        // programmed, causing the DSP engine to process IQ at the wrong rate and
        // producing severely garbled audio on every mode.
        launch {
            svc.connectionState.collect { state ->
                if (state is RtlSdrService.ConnectionState.Connected) adoptSource(svc)
                _connectionState.value = state

                if (state is RtlSdrService.ConnectionState.Connected) {
                    val svc2 = sdrService ?: return@collect

                    // 1. Frequency — before demod mode so direct-sampling is
                    //    configured at the correct band.
                    svc2.setFrequency(_centerFreqHz.value)

                    // 2. Demod mode — resets DSP demodulator and IF bandwidth.
                    svc2.setDemodMode(_demodMode.value)

                    // 3. Sample rate — programs the RTL2832U fractional divider.
                    //    Without this call the hardware stays at 2 048 000 Hz.
                    svc2.setSampleRate(_sampleRate.value)

                    // 4. Gain / AGC.
                    svc2.setTunerAgc(_tunerAgcEnabled.value)
                    svc2.setHardwareAgc(_hardwareAgcEnabled.value)
                    if (!_tunerAgcEnabled.value) svc2.setGain(_gainIndex.value)

                    // 5. IF bandwidth (0 = use mode default; skip).
                    val ifBw = _ifBandwidthHz.value
                    if (ifBw > 0) svc2.setIfBandwidth(ifBw)

                    // 6. Squelch, volume, PPM, bias-tee, direct sampling.
                    svc2.setSquelch(_squelch.value)
                    svc2.setAudioVolume(_volume.value)
                    svc2.setPpm(_ppm.value)
                    svc2.setBiasTee(_biasTee.value)
                    if (_directSampling.value != 0)
                        svc2.setDirectSampling(_directSampling.value)

                    // 7. Audio-sink rate — sets AudioTrack sample rate.
                    svc2.setAudioSinkRate(_audioSinkRate.value)

                    Log.i("MainViewModel",
                        "Device connected — applied saved settings: " +
                        "rate=${_sampleRate.value} mode=${_demodMode.value.name} " +
                        "gain=${_gainIndex.value} tunerAgc=${_tunerAgcEnabled.value}")
                }
            }
        }

        // Use the service's own delegated flows which handle the connectionState
        // switching internally. This eliminates any race between dspEngine being
        // assigned and the connectionState emission being observed here.
        launch {
            _spectrumData.subscriptionCount.map { it>0 }.distinctUntilChanged().flatMapLatest { visible ->
                if(visible) svc.spectrumFlow else emptyFlow()
            }.collect { spectrum ->
                _spectrumData.value = spectrum
                svc.dspEngine?.fftEngine?.getPeakHold()?.let { _peakData.value = it }
            }
        }

        launch {
            svc.statusFlow.collect { status ->
                _deviceStatus.value = status
            }
        }

        launch {
            svc.statsFlow.collect { stats ->
                _dspStats.value = stats
            }
        }
        }
    }

    // ─── Observable state ─────────────────────────────────────────────────────

    private val _spectrumData = MutableStateFlow(FloatArray(256) { -100f })
    val spectrumData: StateFlow<FloatArray> = _spectrumData

    private val _peakData = MutableStateFlow(FloatArray(256) { -100f })
    val peakData: StateFlow<FloatArray> = _peakData

    private val _deviceStatus = MutableStateFlow(IqSource.SourceStatus())
    val deviceStatus: StateFlow<IqSource.SourceStatus> = _deviceStatus

    private val _dspStats = MutableStateFlow(DspEngine.DspStats())
    val dspStats: StateFlow<DspEngine.DspStats> = _dspStats

    private val _connectionState = MutableStateFlow<RtlSdrService.ConnectionState>(
        RtlSdrService.ConnectionState.Disconnected
    )
    val connectionState: StateFlow<RtlSdrService.ConnectionState> = _connectionState

    // Persistent settings
    private val _centerFreqHz = MutableStateFlow(prefs.getLong("device_${profileId}_freq",
        prefs.getLong("freq", if (profileId == "airspyhf") 10_000_000 else 100_000_000)))
    val centerFreqHz: StateFlow<Long> = _centerFreqHz

    private val _demodMode = MutableStateFlow(
        runCatching { DemodMode.valueOf(prefs.getString("device_${profileId}_mode",
            prefs.getString("demod", "NFM")) ?: "NFM") }.getOrDefault(DemodMode.NFM)
    )
    val demodMode: StateFlow<DemodMode> = _demodMode

    // Migrate any previously persisted rate to the nearest valid entry.
    // The RTL-SDR has a dead zone (~300–900 kS/s) where register ratios alias;
    // nearestSampleRate() snaps within the correct band (low-rate ≤300 kS/s or
    // main ≥900 kS/s) so a dead-zone value never reaches the hardware.
    private val _sampleRate = MutableStateFlow(
        (if(profileId=="airspyhf") prefs.getInt("device_airspyhf_rate",prefs.getInt("rate",768000)).coerceAtLeast(1)
         else RtlSdrDevice.nearestSampleRate(prefs.getInt("rate", 1_920_000))).also { snapped ->
            val saved = prefs.getInt("rate", 1_920_000)
            if (snapped != saved) prefs.edit().putInt("rate", snapped).apply()
        }
    )
    val sampleRate: StateFlow<Int> = _sampleRate

    private val _gainIndex = MutableStateFlow(prefs.getInt("gain", 26))
    val gainIndex: StateFlow<Int> = _gainIndex

    private val _gainMode = MutableStateFlow(prefs.getInt("gainMode", RtlSdrDevice.GAIN_MODE_MANUAL))
    val gainMode: StateFlow<Int> = _gainMode

    private val _tunerAgcEnabled = MutableStateFlow(prefs.getBoolean("tunerAgc", false))
    val tunerAgcEnabled: StateFlow<Boolean> = _tunerAgcEnabled

    private val _hardwareAgcEnabled = MutableStateFlow(prefs.getBoolean("hardwareAgc", false))
    val hardwareAgcEnabled: StateFlow<Boolean> = _hardwareAgcEnabled

    private val _biasTee = MutableStateFlow(prefs.getBoolean("biastee", false))
    val biasTee: StateFlow<Boolean> = _biasTee

    private val _noiseBlankerEnabled = MutableStateFlow(prefs.getBoolean("noiseBlanker", true))
    private val _iqNbThreshold=MutableStateFlow(prefs.getFloat("iqNbThreshold",8f).coerceIn(3f,20f))
    val iqNbThreshold=_iqNbThreshold.asStateFlow()
    fun setIqNoiseBlankerThreshold(value: Float) {
        _iqNbThreshold.value=value.coerceIn(3f,20f)
        sdrService?.setIqNoiseBlankerThreshold(_iqNbThreshold.value)
        prefs.edit().putFloat("iqNbThreshold",_iqNbThreshold.value).apply()
    }
    val noiseBlankerEnabled: StateFlow<Boolean> = _noiseBlankerEnabled

    private val _noiseReducerEnabled = MutableStateFlow(prefs.getBoolean("noiseReducer", false))
    val noiseReducerEnabled: StateFlow<Boolean> = _noiseReducerEnabled

    // IF filter bandwidth — 0 means "auto (dynamic per device rate via narrowIfRate)".
    // Stored as an absolute Hz value from [DspEngine.MIN_IF_BANDWIDTH_HZ] up to
    // narrowIfRate(deviceRate) — the ceiling is device-rate-dependent (e.g. 50 kHz
    // at 250 kS/s, 48 kHz at all 48k-multiple device rates).
    private val _ifBandwidthHz = MutableStateFlow(validWidth(_demodMode.value, prefs.getInt("ifBandwidth", 0)))
    val ifBandwidthHz: StateFlow<Int> = _ifBandwidthHz

    // Audio-output (sink) sample rate — the sample rate presented to Android
    // AudioTrack.  Independent of the internal 48 kHz DSP/decoder rate.
    // Default is DspEngine.DEFAULT_AUDIO_SINK_RATE (44 100 Hz).
    private val _audioSinkRate = MutableStateFlow(
        prefs.getInt("audioSinkRate", DspEngine.DEFAULT_AUDIO_SINK_RATE)
    )
    val audioSinkRate: StateFlow<Int> = _audioSinkRate

    private val _directSampling = MutableStateFlow(prefs.getInt("directSampling", 0))
    val directSampling: StateFlow<Int> = _directSampling

    private val _ppm = MutableStateFlow(run {
        // "ppm" is the RF-tab slider's store (rtlsdr_prefs).
        // "pref_ppm" is the Settings screen's store (default SharedPreferences).
        // On first launch (or after a fresh install) only one of these may have
        // been written; prefer whichever was written, falling back to 0.
        val rfPanelPpm = if (prefs.contains("ppm")) prefs.getInt("ppm", 0) else null
        val settingsPpm = if (androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(application).contains("pref_ppm"))
            androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(application).getInt("pref_ppm", 0)
        else null
        rfPanelPpm ?: settingsPpm ?: 0
    })
    val ppm: StateFlow<Int> = _ppm

    private val _allowOutOfBand = MutableStateFlow(prefs.getBoolean("allowOutOfBand", false))
    val allowOutOfBand: StateFlow<Boolean> = _allowOutOfBand

    // Default −120 dB = slider minimum (fully open). The old −100 default pre-dated
    // the FFT-peak squelch; wideband RMS at 2 MS/s read ~−20..−45 dBFS so −100 was
    // effectively always open. With FFT-peak the noise floor sits at ~−65 dBFS, so
    // −100 keeps squelch permanently closed and silences NFM.  −120 = always open.
    //
    // On startup: if the current mode has ever had its squelch explicitly set, use
    // that per-mode value rather than the global settings default.  This means the
    // app resumes with the same squelch level the user last used for this protocol,
    // not a generic default.  Falls back to global "squelch" only when the mode has
    // never been configured (first use).
    private val _squelch = MutableStateFlow(run {
        val startupModeName = prefs.getString("demod", "NFM") ?: "NFM"
        val modeSquelchKey = "mode_${startupModeName}_squelch"
        if (prefs.contains(modeSquelchKey))
            prefs.getFloat(modeSquelchKey, -120f)
        else
            prefs.getFloat("squelch", -120f)
    })
    val squelch: StateFlow<Float> = _squelch

    private val _volume = MutableStateFlow(prefs.getFloat("volume", 1f))
    val volume: StateFlow<Float> = _volume

    private val _fftSize = MutableStateFlow(prefs.getInt("fftSize", 2048))
    val fftSize: StateFlow<Int> = _fftSize

    /**
     * FFT decimation factor — persisted across sessions.
     * 1 = off (full bandwidth); 2/4/8/16/32/64 = narrowed bandwidth.
     * The effective displayed sample rate is sampleRate / decimation.
     */
    private val _decimation = MutableStateFlow(
        prefs.getInt("fftDecimation", 64).let { saved ->
            if (saved in intArrayOf(1, 2, 4, 8, 16, 32, 64)) saved else 1
        }
    )
    val decimation: StateFlow<Int> = _decimation

    private val _waterfallPalette = MutableStateFlow(
        WaterfallView.Companion.Palette.valueOf(
            displayPrefs().getString("pref_waterfall_palette", prefs.getString("palette", "RAINBOW")) ?: "RAINBOW"
        )
    )
    val waterfallPalette: StateFlow<WaterfallView.Companion.Palette> = _waterfallPalette

    private val _spectrumTheme = MutableStateFlow(displayPrefs().getString("pref_spectrum_theme", prefs.getString("theme", "UI")) ?: "Futuristic")
    val spectrumTheme: StateFlow<String> = _spectrumTheme

    // ─── Database flows ───────────────────────────────────────────────────────

    val memoryChannels = db.memoryChannelDao().getAll()
    val bookmarks = db.bookmarkDao().getAll()
    val bookmarkLists = db.bookmarkListDao().getAll()
    val favoriteBookmarks = db.bookmarkDao().getFavorites()
    val scanEntries = db.scanEntryDao().getAll()
    val recordings = db.recordingMetaDao().getAll()

    // ─── SDR Control commands ─────────────────────────────────────────────────

    fun connectDevice(usbDevice: UsbDevice) {
        val svc = sdrService
        if (svc == null) {
            pendingDevice = usbDevice
            return
        }
        pendingDevice = null
        svc.reconnect(usbDevice, _receiverEnabled.value)
    }

    /**
     * Connect to an rtl_tcp / rtl_tcp_andro server.
     * Saves host:port to SharedPreferences so the UI can restore them.
     */
    fun connectTcp(host: String, port: Int) {
        prefs.edit()
            .putString("tcp_host", host)
            .putInt("tcp_port", port)
            .apply()
        _receiverEnabled.value = true
        prefs.edit().putBoolean("receiverEnabled", true).apply()
        sdrService?.connectTcp(host, port)
    }

    /** Last-used TCP host (empty string if never set). */
    fun savedTcpHost(): String = prefs.getString("tcp_host", "") ?: ""
    /** Last-used TCP port (default 1234). */
    fun savedTcpPort(): Int = prefs.getInt("tcp_port", 1234)

    fun setFrequency(hz: Long): Boolean {
        if (!_capabilities.value.supportsBrowsing(hz)) {
            _messages.tryEmit("频率超出当前接收机的范围")
            return false
        }
        val previousFrequencyHz=_centerFreqHz.value
        _centerFreqHz.value = hz
        if (!_capabilities.value.supportsFrequency(hz) && (_dspStats.value.isRecordingIq || _dspStats.value.isRecordingAudio))
            _messages.tryEmit("已停止录制：当前频率不可接收")
        if (_autoDemod.value) applyAutomaticDemod(hz,previousFrequencyHz)
        sdrService?.setFrequency(hz)
        scheduleSave()
        return true
    }
    fun stepFrequency(delta: Long) {
        val current = _centerFreqHz.value
        setFrequency(_capabilities.value.browseFrequency(current + delta))
    }
    fun finishTuning() {
        sdrService?.setFrequency(_centerFreqHz.value, immediate = true)
        saveJob?.cancel()
        saveDeviceSettings()
    }

    // ─── Per-mode settings store ──────────────────────────────────────────────

    /**
     * RF settings that are saved and restored independently for every DemodMode.
     * When the user switches from NFM to AM (for example) the current NFM settings
     * are written to "mode_NFM_*" keys, then the AM snapshot (if any) is read back
     * and applied.  On the very first switch to a mode the global current values
     * carry forward, so there is no reset to factory defaults.
     *
     * Persisted fields per mode:
     *   freq         – center frequency in Hz
     *   gain         – gain-table index (0-28)
     *   gainMode     – GAIN_MODE_AGC / GAIN_MODE_MANUAL
     *   tunerAgc     – Boolean
     *   hardwareAgc  – Boolean
     *   rate         – sample rate in Hz
     *   fftDecimation
     *   fftSize      – FFT bin count (256..8192)
     *   frameAvg     – frame averaging count (1/2/4/8/16/32)
     *   specFloor    – spectrum floor in dBFS (negative float, stored as negative)
     *   specCeiling  – spectrum ceiling in dBFS (≤0 float)
     *   squelch      – dBFS float
     *   ifBandwidth  – Hz, 0 = mode default
     *
     * Additionally, every Drawer "RF" tab and "Display" tab control is saved
     * and restored per mode (see the dedicated blocks in [saveSettingsForMode]
     * and [restoreSettingsForMode] below). RF-tab extras: volume, biasTee,
     * noiseBlanker, noiseReducer, directSampling, ppm, audioSinkRate.
     * Display-tab extras (beyond fftSize/fftDecimation/frameAvg/specFloor/
     * specCeiling already listed above): spectrumTheme, waterfallPalette,
     * waterfallSpeed, peakHold, fillOpacity, autoRange, showNoiseFloor,
     * peakDecay, showPeakAnnotations, crosshairEnabled, waterfallBrightness,
     * waterfallContrast, waterfallAutoStretch, waterfallShowTimestamp,
     * waterfallShowTunerMarker.
     */
    private fun modeKey(mode: DemodMode, field: String) = "device_${profileId}_mode_${mode.name}_$field"

    fun rememberedModeFrequency(mode: DemodMode): Long? {
        if(!prefs.contains(modeKey(mode,"freq"))) return null
        return prefs.getLong(modeKey(mode,"freq"),0).takeIf { _capabilities.value.supportsFrequency(it) }
    }

    private fun displayPrefs() =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(getApplication())

    /** Save the current live RF state under the given mode's namespace. */
    private fun saveSettingsForMode(mode: DemodMode,frequencyHz: Long=_centerFreqHz.value) {
        val dp = displayPrefs()
        val editor=prefs.edit()
        if(_modeStationMemory.value && _capabilities.value.supportsFrequency(frequencyHz)) editor.putLong(modeKey(mode,"freq"),frequencyHz)
        editor
            .putInt    (modeKey(mode, "gain"),          _gainIndex.value)
            .putInt    (modeKey(mode, "gainMode"),      _gainMode.value)
            .putBoolean(modeKey(mode, "tunerAgc"),      _tunerAgcEnabled.value)
            .putBoolean(modeKey(mode, "hardwareAgc"),   _hardwareAgcEnabled.value)
            .putInt    (modeKey(mode, "rate"),          _sampleRate.value)
            .putInt    (modeKey(mode, "fftDecimation"), _decimation.value)
            .putInt    (modeKey(mode, "fftSize"),        _fftSize.value)
            .putInt    (modeKey(mode, "frameAvg"),       dp.getString("pref_frame_averaging", "1")?.toIntOrNull() ?: 1)
            .putFloat  (modeKey(mode, "specFloor"),      dp.getInt("pref_db_min", 120).toFloat() * -1f)
            .putFloat  (modeKey(mode, "specCeiling"),    dp.getInt("pref_db_max", 0).toFloat())
            .putFloat  (modeKey(mode, "squelch"),        _squelch.value)
            .putInt    (modeKey(mode, "ifBandwidth"),    _ifBandwidthHz.value)
            // ── RF tab extras ──────────────────────────────────────────────
            .putFloat  (modeKey(mode, "volume"),         _volume.value)
            .putBoolean(modeKey(mode, "biasTee"),        _biasTee.value)
            .putBoolean(modeKey(mode, "noiseBlanker"),   _noiseBlankerEnabled.value)
            .putBoolean(modeKey(mode, "noiseReducer"),   _noiseReducerEnabled.value)
            .putInt    (modeKey(mode, "directSampling"), _directSampling.value)
            .putInt    (modeKey(mode, "ppm"),            _ppm.value)
            .putInt    (modeKey(mode, "audioSinkRate"),  _audioSinkRate.value)
            // ── Display tab extras ─────────────────────────────────────────
            .putString (modeKey(mode, "spectrumTheme"),       dp.getString("pref_spectrum_theme", "Futuristic"))
            .putString (modeKey(mode, "waterfallPalette"),    dp.getString("pref_waterfall_palette", "RAINBOW"))
            .putInt    (modeKey(mode, "waterfallSpeed"),      dp.getInt("pref_waterfall_speed", 25))
            .putBoolean(modeKey(mode, "peakHold"),            dp.getBoolean("pref_peak_hold", true))
            .putInt    (modeKey(mode, "fillOpacity"),         dp.getInt("pref_fill_opacity", 73))
            .putBoolean(modeKey(mode, "autoRange"),           dp.getBoolean("pref_auto_range", false))
            .putBoolean(modeKey(mode, "showNoiseFloor"),      dp.getBoolean("pref_show_noise_floor", true))
            .putInt    (modeKey(mode, "peakDecay"),           dp.getInt("pref_peak_decay", 2))
            .putBoolean(modeKey(mode, "showPeakAnnotations"), dp.getBoolean("pref_show_peak_annotations", true))
            .putBoolean(modeKey(mode, "crosshairEnabled"),    dp.getBoolean("pref_crosshair_enabled", true))
            .putInt    (modeKey(mode, "waterfallBrightness"), dp.getInt("pref_waterfall_brightness", 50))
            .putInt    (modeKey(mode, "waterfallContrast"),   dp.getInt("pref_waterfall_contrast", 50))
            .putBoolean(modeKey(mode, "waterfallAutoStretch"),dp.getBoolean("pref_waterfall_auto_stretch", false))
            .putBoolean(modeKey(mode, "waterfallShowTimestamp"),  dp.getBoolean("pref_waterfall_show_timestamp", true))
            .putBoolean(modeKey(mode, "waterfallShowTunerMarker"), dp.getBoolean("pref_waterfall_show_tuner_marker", true))
            .apply()
    }

    /**
     * Restore RF state for [mode] from its saved snapshot.
     * Returns true if a snapshot existed and was applied; false if this is the
     * first time the mode has been used (caller may apply its own defaults).
     */
    private fun restoreSettingsForMode(mode: DemodMode): Boolean {
        val key = modeKey(mode, "gain")
        if (!prefs.contains(key)) return false   // never saved — first use

        val freq    = _centerFreqHz.value
        val gain    = _gainIndex.value
        val gMode   = _gainMode.value
        val tAgc    = _tunerAgcEnabled.value
        val hAgc    = _hardwareAgcEnabled.value
        val rate    = _sampleRate.value
        val decim      = prefs.getInt    (modeKey(mode, "fftDecimation"), _decimation.value)
        val fftSz      = prefs.getInt    (modeKey(mode, "fftSize"),        _fftSize.value)
        val frameAvg   = prefs.getInt    (modeKey(mode, "frameAvg"),       1)
        val specFloor  = -(displayPrefs().getInt("pref_db_min", 120).takeIf { it in 10..200 } ?: 120).toFloat()
        val specCeiling= displayPrefs().getInt("pref_db_max", 0).toFloat()
        val squelch    = prefs.getFloat  (modeKey(mode, "squelch"),        _squelch.value)
        val ifBw       = prefs.getInt    (modeKey(mode, "ifBandwidth"),    0)

        // RF tab extras
        val volume         = _volume.value
        val biasTee        = _biasTee.value
        val noiseBlanker   = _noiseBlankerEnabled.value
        val noiseReducer   = _noiseReducerEnabled.value
        val directSampling = _directSampling.value
        val ppmVal         = _ppm.value
        val audioSinkRate  = _audioSinkRate.value

        // Display tab extras
        val dp = displayPrefs()
        val spectrumTheme        = dp.getString("pref_spectrum_theme", "Futuristic")
        val waterfallPalette     = dp.getString("pref_waterfall_palette", "RAINBOW")
        val waterfallSpeed       = dp.getInt("pref_waterfall_speed", 25)
        val peakHold             = dp.getBoolean("pref_peak_hold", true)
        val fillOpacity          = dp.getInt("pref_fill_opacity", 73)
        val autoRange            = dp.getBoolean("pref_auto_range", false)
        val showNoiseFloor       = dp.getBoolean("pref_show_noise_floor", true)
        val peakDecay            = dp.getInt("pref_peak_decay", 2)
        val showPeakAnnotations  = dp.getBoolean("pref_show_peak_annotations", true)
        val crosshairEnabled     = dp.getBoolean("pref_crosshair_enabled", true)
        val waterfallBrightness  = dp.getInt("pref_waterfall_brightness", 50)
        val waterfallContrast    = dp.getInt("pref_waterfall_contrast", 50)
        val waterfallAutoStretch = dp.getBoolean("pref_waterfall_auto_stretch", false)
        val waterfallShowTimestamp   = dp.getBoolean("pref_waterfall_show_timestamp", true)
        val waterfallShowTunerMarker = dp.getBoolean("pref_waterfall_show_tuner_marker", true)


        // Apply to state flows + hardware — use internal setters to avoid
        // recursive setDemodMode calls; hardware calls go through the service.
        val clamped = _capabilities.value.nearestFrequency(freq)
        _centerFreqHz.value = clamped
        sdrService?.setFrequency(clamped)

        _gainIndex.value        = gain
        _gainMode.value         = gMode
        _tunerAgcEnabled.value  = tAgc
        _hardwareAgcEnabled.value = hAgc
        // AGC and gain are pushed AFTER setDirectSampling() below.
        // setDirectSampling(OFF) calls r82xxInit() which writes the R82xx
        // initialisation register array to hardware.  That array hardcodes
        // reg 0x05/0x07 with LNA-auto=ON / Mixer-auto=ON (AGC enabled),
        // silently overwriting any AGC state pushed here before that call.
        // Deferring the hardware push until after setDirectSampling() ensures
        // the correct saved AGC state is the last thing written to the device.

        val validRate = _capabilities.value.nearestRate(rate)
        _sampleRate.value = validRate
        sdrService?.setSampleRate(validRate)

        val validDecim = if (decim in intArrayOf(1, 2, 4, 8, 16, 32, 64)) decim else 1
        _decimation.value = validDecim
        sdrService?.setFftDecimation(validDecim)

        val validFftSz = if (fftSz in intArrayOf(256, 512, 1024, 2048, 4096, 8192)) fftSz else _fftSize.value
        if (validFftSz != _fftSize.value) {
            _fftSize.value = validFftSz
            sdrService?.setFftSize(validFftSz)
        }

        // frameAvg, specFloor, specCeiling, and all other Display-tab extras are
        // persisted in defaultSharedPreferences so the Display tab and
        // applyDisplayPreferences() pick them up automatically. The caller
        // (setDemodMode -> MainActivity's demodMode observer) MUST invoke
        // applyDisplayPreferences() after this returns, or these writes sit
        // inert until the next unrelated trigger re-reads them.
        val validFrameAvg = if (frameAvg in intArrayOf(1, 2, 4, 8, 16, 32)) frameAvg else 1
        dp.edit()
            // Write pref_decimation and pref_fft_size so applyDisplayPreferences()
            // (called immediately after this returns) finds values matching the
            // just-restored ViewModel state and its equality guards don't
            // incorrectly overwrite them with whatever was in the pref store before.
            .putString ("pref_decimation",      validDecim.toString())
            .putString ("pref_fft_size",        validFftSz.toString())
            .putString ("pref_frame_averaging", validFrameAvg.toString())
            .putInt    ("pref_db_min", (specFloor * -1f).toInt().coerceIn(0, 200))
            .putInt    ("pref_db_max", specCeiling.toInt().coerceIn(-100, 50))
            .putString ("pref_spectrum_theme", spectrumTheme)
            .putString ("pref_waterfall_palette", waterfallPalette)
            .putInt    ("pref_waterfall_speed", waterfallSpeed)
            .putBoolean("pref_peak_hold", peakHold)
            .putInt    ("pref_fill_opacity", fillOpacity)
            .putBoolean("pref_auto_range", autoRange)
            .putBoolean("pref_show_noise_floor", showNoiseFloor)
            .putInt    ("pref_peak_decay", peakDecay)
            .putBoolean("pref_show_peak_annotations", showPeakAnnotations)
            .putBoolean("pref_crosshair_enabled", crosshairEnabled)
            .putInt    ("pref_waterfall_brightness", waterfallBrightness)
            .putInt    ("pref_waterfall_contrast", waterfallContrast)
            .putBoolean("pref_waterfall_auto_stretch", waterfallAutoStretch)
            .putBoolean("pref_waterfall_show_timestamp", waterfallShowTimestamp)
            .putBoolean("pref_waterfall_show_tuner_marker", waterfallShowTunerMarker)
            .apply()

        // RF tab extras — apply through the normal setters so hardware, state
        // flows, and the RF-tab UI observer all stay in sync.
        setVolume(volume)
        setBiasTee(biasTee)
        setNoiseBlanker(noiseBlanker)
        setDirectSampling(directSampling)
        setPpm(ppmVal)
        setAudioSinkRate(audioSinkRate)

        // Apply AGC and gain HERE — after setDirectSampling() — because
        // setDirectSampling(OFF) calls r82xxInit() which resets the R82xx
        // tuner registers (including the LNA/Mixer AGC bits in reg 0x05/0x07)
        // to the factory default (AGC enabled).  Any AGC state pushed earlier
        // in this function is clobbered by that re-init; pushing it again
        // here ensures the saved per-protocol AGC state is the last thing
        // written, so the hardware ends up exactly where the snapshot says.
        // Note: setTunerAgcEnabled() already gates setGain() on !enable
        // internally, so the extra guard here is defensive-in-depth.
        sdrService?.setTunerAgc(tAgc)
        sdrService?.setHardwareAgc(hAgc)
        if (!tAgc) sdrService?.setGain(gain)

        _squelch.value = squelch
        sdrService?.setSquelch(squelch)

        // FIX 25: APRS (and USB/LSB) are forced onto the auto (-1) sentinel by
        // setDemodMode() — see DspEngine.setDemodMode() FIX 24. A stale
        // per-mode snapshot saved from BEFORE that fix (or from manually
        // dragging the RF-tab "Width" slider) can contain a literal
        // ifBandwidth=48000 for APRS, which is not an exact divisor of common
        // device rates (2.048/1.024/2.56 MS/s) and re-introduces the
        // fractional-resampler regression FIX 24 removes. Skip restoring a
        // saved ifBandwidth for these always-auto modes; the -1 sentinel
        // setDemodMode() already applied is authoritative for them.
        val forcesAuto = mode == DemodMode.USB || mode == DemodMode.LSB || mode == DemodMode.APRS
        if (!forcesAuto) {
            _ifBandwidthHz.value = ifBw
            if (ifBw > 0) sdrService?.setIfBandwidth(ifBw)
        }

        // Persist the restored values as the current global state
        prefs.edit()
            .putLong   ("freq",          clamped)
            .putInt    ("gain",          gain)
            .putInt    ("gainMode",      gMode)
            .putBoolean("tunerAgc",      tAgc)
            .putBoolean("hardwareAgc",   hAgc)
            .putInt    ("rate",          validRate)
            .putInt    ("fftDecimation", validDecim)
            .putInt    ("fftSize",       validFftSz)
            .putFloat  ("squelch",       squelch)
            .putInt    ("ifBandwidth",   ifBw)
            .apply()
        // Apply noise reducer last — after mode switch, demodulator creation,
        // sample-rate, squelch, gain, and IF bandwidth are all in their final
        // state.  setNoiseReducer triggers reset() which re-calibrates the noise
        // floor estimator; doing it last ensures calibration runs against the
        // correctly configured audio pipeline for the new mode.
        setNoiseReducer(noiseReducer)
        return true
    }

    /**
     * Returns true if a per-mode settings snapshot has ever been saved for [mode].
     * Used by protocol activities to decide whether to seed first-use defaults.
     */
    fun hasModeSnapshot(mode: DemodMode): Boolean = prefs.contains(modeKey(mode, "gain"))

    fun setDemodMode(mode: DemodMode, automatic: Boolean = false,previousFrequencyHz: Long=_centerFreqHz.value) {
        if (automatic && mode == DemodMode.WFM && displayPrefs().getBoolean("pref_stereo_wfm", true)) {
            setDemodMode(DemodMode.WFM_STEREO, automatic = true,previousFrequencyHz=previousFrequencyHz)
            return
        }
        if (!automatic && _autoDemod.value) setAutoDemod(false)
        if (!_capabilities.value.supportsMode(mode)) {
            _messages.tryEmit("当前接收机不支持 ${mode.displayName}")
            return
        }
        if (!automatic) syncFmStereoPreference(mode)
        if (mode == _demodMode.value) return
        val previousMode=_demodMode.value
        saveSettingsForMode(previousMode,previousFrequencyHz)
        _demodMode.value = mode
        if(mode==DemodMode.ADSB) configureAdsbRf()
        else if(previousMode==DemodMode.ADSB) restoreRfAfterAdsb(mode)
        // Manual type selection restores this device's last valid frequency.
        // Automatic mode selection follows the dial and must never retune it.
        if(!automatic && _modeStationMemory.value) restoreModeStation(mode)
        val width = validWidth(mode, prefs.getInt(modeKey(mode, "ifBandwidth"), 0))
        _ifBandwidthHz.value = width
        val requiredRate = if (mode == DemodMode.ADSB) com.radiosport.ninegradio.dsp.ReadsbReceiver.SAMPLE_RATE else kotlin.math.ceil(mode.defaultBwHz / 0.86).toInt()
        if (_sampleRate.value < requiredRate) {
            _capabilities.value.sampleRates.filter { it >= requiredRate }.minOrNull()?.let { setSampleRate(it) }
        }
        sdrService?.setDemodMode(mode)
        sdrService?.setIfBandwidth(width)
        prefs.edit().putString("demod", mode.name).putInt("ifBandwidth", width).apply()
        scheduleSave()
    }

    private fun configureAdsbRf(fromDeviceRestore: Boolean=false) {
        if(_capabilities.value.id!="rtl" || sdrService?.source is RtlTcpSource) return
        val ready=prefs.getBoolean(modeKey(DemodMode.ADSB,"rfReadyV1"),false)
        setDecimation(if(ready) prefs.getInt(modeKey(DemodMode.ADSB,"fftDecimation"),1) else 1)
        if(ready && fromDeviceRestore) return
        val gain=if(ready) prefs.getInt(modeKey(DemodMode.ADSB,"gain"),26) else 26
        val tuner=ready && prefs.getBoolean(modeKey(DemodMode.ADSB,"tunerAgc"),false)
        val hardware=ready && prefs.getBoolean(modeKey(DemodMode.ADSB,"hardwareAgc"),false)
        if(!ready) prefs.edit().putInt("device_${profileId}_adsbReturnGain",_gainIndex.value)
            .putBoolean("device_${profileId}_adsbReturnTunerAgc",_tunerAgcEnabled.value)
            .putBoolean("device_${profileId}_adsbReturnHardwareAgc",_hardwareAgcEnabled.value)
            .putBoolean(modeKey(DemodMode.ADSB,"rfReadyV1"),true).apply()
        setTunerAgc(tuner); setHardwareAgc(hardware); setGain(gain.coerceIn(0,28))
    }
    private fun restoreRfAfterAdsb(mode: DemodMode) {
        if(_capabilities.value.id!="rtl" || sdrService?.source is RtlTcpSource) return
        setTunerAgc(prefs.getBoolean(modeKey(mode,"tunerAgc"),prefs.getBoolean("device_${profileId}_adsbReturnTunerAgc",false)))
        setHardwareAgc(prefs.getBoolean(modeKey(mode,"hardwareAgc"),prefs.getBoolean("device_${profileId}_adsbReturnHardwareAgc",false)))
        setGain(prefs.getInt(modeKey(mode,"gain"),prefs.getInt("device_${profileId}_adsbReturnGain",26)).coerceIn(0,28))
        setSampleRate(_capabilities.value.nearestRate(prefs.getInt(modeKey(mode,"rate"),_sampleRate.value)))
        setDecimation(prefs.getInt(modeKey(mode,"fftDecimation"),_decimation.value))
    }

    /**
     * Seed FFT size, frame averaging, and decimation for [mode] from
     * [DemodMode]'s intelligent per-protocol defaults (2048 bins / x8
     * averaging / protocol-aware decimation -- see DemodMode.kt). Only
     * called on a mode's first-ever selection (restoreSettingsForMode()
     * found no prior snapshot), so it never clobbers a value the user has
     * previously set for this specific mode.
     */
    private fun applyIntelligentAutoConfig(mode: DemodMode) {
        val dp = displayPrefs()

        setFftSize(mode.defaultFftSize)
        setDecimation(mode.defaultDecimation)

        dp.edit()
            .putString("pref_fft_size", mode.defaultFftSize.toString())
            .putString("pref_decimation", mode.defaultDecimation.toString())
            .putString("pref_frame_averaging", mode.defaultFrameAveraging.toString())
            .apply()
        sdrService?.dspEngine?.fftEngine?.frameAveragingCount = mode.defaultFrameAveraging
    }

    fun setSampleRate(rate: Int) {
        if(_demodMode.value==DemodMode.ADSB && rate!=com.radiosport.ninegradio.dsp.ReadsbReceiver.SAMPLE_RATE) {
            _messages.tryEmit("ADS-B 解码使用 2.400 MS/s")
            return
        }
        scheduleSave()
        val snapped = _capabilities.value.nearestRate(rate)
        _sampleRate.value = snapped
        sdrService?.setSampleRate(snapped)
        prefs.edit().putInt("rate", snapped).apply()
    }

    fun setAudioSinkRate(rate: Int) {
        _audioSinkRate.value = rate
        sdrService?.setAudioSinkRate(rate)
        prefs.edit().putInt("audioSinkRate", rate).apply()
        // Keep the Settings screen's "Audio Output Sample Rate" ListPreference
        // (key "pref_audio_sink_rate", stored in the *default* SharedPreferences
        // file — separate from "rtlsdr_prefs") in sync. Without this,
        // SettingsFragment.applyAllPrefsToEngine() re-applies its own stale/
        // default value (44100) every time the Settings screen is opened,
        // silently reverting the rate chosen here and rebuilding the
        // AudioTrack + resampler chain (audible glitch/dropout).
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(getApplication())
            .edit().putString("pref_audio_sink_rate", rate.toString()).apply()
    }

    fun setGain(index: Int) {
        scheduleSave()
        _gainIndex.value = index
        sdrService?.setGain(index)
        prefs.edit().putInt("gain", index).apply()
        if(_demodMode.value==DemodMode.ADSB) prefs.edit().putInt(modeKey(DemodMode.ADSB,"gain"),index).apply()
    }

    fun setGainMode(mode: Int) {
        _gainMode.value = mode
        sdrService?.setGainMode(mode)
        prefs.edit().putInt("gainMode", mode).apply()
        // Keep individual AGC flags in sync with mode
        val agc = (mode == RtlSdrDevice.GAIN_MODE_AGC)
        _tunerAgcEnabled.value    = agc
        _hardwareAgcEnabled.value = agc
        prefs.edit().putBoolean("tunerAgc", agc).putBoolean("hardwareAgc", agc).apply()
    }

    fun setTunerAgc(enabled: Boolean) {
        scheduleSave()
        _tunerAgcEnabled.value = enabled
        sdrService?.setTunerAgc(enabled)
        prefs.edit().putBoolean("tunerAgc", enabled).apply()
        if(_demodMode.value==DemodMode.ADSB) prefs.edit().putBoolean(modeKey(DemodMode.ADSB,"tunerAgc"),enabled).apply()
        // Derive gainMode from combined AGC state
        val manual = !enabled && !_hardwareAgcEnabled.value
        val mode   = if (manual) RtlSdrDevice.GAIN_MODE_MANUAL else RtlSdrDevice.GAIN_MODE_AGC
        _gainMode.value = mode
        prefs.edit().putInt("gainMode", mode).apply()
    }

    fun setHardwareAgc(enabled: Boolean) {
        if (!_capabilities.value.hardwareAgc) return
        scheduleSave()
        _hardwareAgcEnabled.value = enabled
        sdrService?.setHardwareAgc(enabled)
        prefs.edit().putBoolean("hardwareAgc", enabled).apply()
        if(_demodMode.value==DemodMode.ADSB) prefs.edit().putBoolean(modeKey(DemodMode.ADSB,"hardwareAgc"),enabled).apply()
        // Derive gainMode from combined AGC state
        val manual = !_tunerAgcEnabled.value && !enabled
        val mode   = if (manual) RtlSdrDevice.GAIN_MODE_MANUAL else RtlSdrDevice.GAIN_MODE_AGC
        _gainMode.value = mode
        prefs.edit().putInt("gainMode", mode).apply()
    }

    fun setBiasTee(on: Boolean) {
        if (!_capabilities.value.biasTee) return
        scheduleSave()
        _biasTee.value = on
        sdrService?.setBiasTee(on)
        prefs.edit().putBoolean("biastee", on).apply()
    }

    fun setNoiseBlanker(enabled: Boolean) {
        _noiseBlankerEnabled.value = enabled
        sdrService?.setNoiseBlanker(enabled)
        prefs.edit().putBoolean("noiseBlanker", enabled).apply()
    }

    fun setNoiseReducer(enabled: Boolean) {
        _noiseReducerEnabled.value = enabled
        sdrService?.setNoiseReducer(enabled)
        prefs.edit().putBoolean("noiseReducer", enabled).apply()
    }

    /**
     * Set the IF filter / protocol bandwidth for narrow modes.
     * Pass 0 to revert to the mode-default bandwidth.
     * @param hz  Desired IF bandwidth in Hz; clamped by DspEngine.
     */
    private fun validWidth(mode: DemodMode, hz: Int): Int =
        if (mode in listOf(DemodMode.FM, DemodMode.WFM, DemodMode.WFM_STEREO)) {
            if(hz<50_000) 0 else hz.coerceAtMost(_capabilities.value.maxChannelBandwidth(_sampleRate.value).coerceAtLeast(50_000))
        } else hz.coerceAtLeast(0)
    fun setIfBandwidth(hz: Int) {
        val width = validWidth(_demodMode.value, hz)
        _ifBandwidthHz.value = width
        sdrService?.setIfBandwidth(width)
        prefs.edit().putInt("ifBandwidth", width).apply()
        scheduleSave()
    }

    fun setDirectSampling(mode: Int) {
        if (!_capabilities.value.directSampling) return
        scheduleSave()
        _directSampling.value = mode
        sdrService?.setDirectSampling(mode)
        prefs.edit().putInt("directSampling", mode).apply()
    }

    fun setPpm(ppm: Int) {
        scheduleSave()
        _ppm.value = ppm
        sdrService?.setPpm(ppm)
        prefs.edit().putInt("ppm", ppm).apply()
        // Keep the Settings screen's "PPM Correction" SeekBarPreference
        // (key "pref_ppm", stored in default SharedPreferences — separate from
        // "rtlsdr_prefs") in sync, so the Settings slider always reflects the
        // value last chosen on the RF tab and vice-versa.
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(getApplication())
            .edit().putInt("pref_ppm", ppm).apply()
    }

    /**
     * Enable or disable out-of-band frequency access.
     * When true, [setFrequency] skips the normal 500 kHz – 1766 MHz clamp,
     * allowing the R828D / RTL-SDR Blog V4 extended tuning range.
     * The choice is persisted so it survives app restarts.
     */
    fun setAllowOutOfBand(allow: Boolean) {
        _allowOutOfBand.value = allow
        prefs.edit().putBoolean("allowOutOfBand", allow).apply()
    }

    /** True when the currently connected USB source is an RTL-SDR Blog V4. */
    val isV4: Boolean get() = sdrService?.isV4 ?: false

    fun setSquelch(db: Float) {
        _squelch.value = db
        sdrService?.setSquelch(db)
        prefs.edit().putFloat("squelch", db).apply()
    }

    fun setVolume(vol: Float) {
        _volume.value = vol
        sdrService?.setAudioVolume(vol)
        prefs.edit().putFloat("volume", vol).apply()
    }

    fun setFftSize(size: Int) {
        _fftSize.value = size
        // Route through the service so pendingFftSize is updated and the setting
        // survives device reconnects without re-applying it manually each time.
        sdrService?.setFftSize(size) ?: run {
            // Service not yet bound; the size will be applied when the service
            // connects via the observeServiceFlows() → applyStoredSettings() path.
        }
        prefs.edit().putInt("fftSize", size).apply()
    }

    /** Set the FFT decimation factor.  Valid: 1 (off), 2, 4, 8, 16, 32, 64. */
    fun setDecimation(factor: Int) {
        val valid = intArrayOf(1, 2, 4, 8, 16, 32, 64)
        val clamped = if (factor in valid) factor else 1
        _decimation.value = clamped
        sdrService?.setFftDecimation(clamped)
        prefs.edit().putInt("fftDecimation", clamped).apply()
        displayPrefs().edit().putString("pref_decimation",clamped.toString()).apply()
    }

    /**
     * Returns the effective FFT sample rate: the bandwidth visible in the
     * spectrum/waterfall display after decimation.
     * SpectrumView and WaterfallView should be initialised with this value.
     */
    fun effectiveSampleRate(): Int = sampleRate.value / decimation.value.coerceAtLeast(1)

    fun setWaterfallPalette(p: WaterfallView.Companion.Palette) {
        _waterfallPalette.value = p
        prefs.edit().putString("palette", p.name).apply()
        displayPrefs().edit().putString("pref_waterfall_palette", p.name).apply()
    }

    fun setSpectrumTheme(name: String) {
        _spectrumTheme.value = name
        prefs.edit().putString("theme", name).apply()
        displayPrefs().edit().putString("pref_spectrum_theme", name).apply()
    }

    // ─── Recording ────────────────────────────────────────────────────────────

    fun playIqRecording(meta: RecordingMeta) {
        viewModelScope.launch {
            serviceConnected.first { it }
            _receiverEnabled.value = true
            prefs.edit().putBoolean("receiverEnabled", true).apply()
            sdrService?.connectFile(meta.filePath, meta.sampleRate, meta.frequencyHz,
                runCatching { DemodMode.valueOf(meta.demodMode) }.getOrDefault(DemodMode.AM))
        }
    }

    fun startIqRecording(path: String) { sdrService?.startRecording(path) }
    fun stopIqRecording() { sdrService?.stopRecording() }
    fun startAudioRecording(path:String,profile:com.radiosport.ninegradio.recording.AudioRecordingProfile=
        com.radiosport.ninegradio.recording.AudioRecordingProfile.forFile(path)) { sdrService?.startAudioRecording(path,profile) }
    fun stopAudioRecording() { sdrService?.stopAudioRecording() }

    // ─── Memory channels ──────────────────────────────────────────────────────

    fun saveCurrentAsMemory(name: String, group: String = "Default") {
        viewModelScope.launch {
            db.memoryChannelDao().insert(MemoryChannel(
                name = name,
                frequencyHz = _centerFreqHz.value,
                demodMode = _demodMode.value.name,
                sampleRate = _sampleRate.value,
                gain = _gainIndex.value,
                squelch = _squelch.value,
                biasTee = _biasTee.value,
                directSampling = _directSampling.value,
                ppmCorrection = _ppm.value,
                group = group
            ))
        }
    }

    fun loadMemoryChannel(channel: MemoryChannel) {
        setFrequency(channel.frequencyHz)
        setDemodMode(DemodMode.valueOf(channel.demodMode))
        setSampleRate(channel.sampleRate)
        setGain(channel.gain)
        setSquelch(channel.squelch)
        setBiasTee(channel.biasTee)
        setDirectSampling(channel.directSampling)
        setPpm(channel.ppmCorrection)
        viewModelScope.launch {
            db.memoryChannelDao().updateLastUsed(channel.id)
        }
    }

    fun deleteMemoryChannel(channel: MemoryChannel) {
        viewModelScope.launch { db.memoryChannelDao().delete(channel) }
    }

    // ─── Bookmarks ────────────────────────────────────────────────────────────

    fun addBookmark(freqHz: Long, label: String, demodMode: String = "", notes: String = "",
                    color: Int = 0xFF2196F3.toInt(), bookmarkListId: Long? = null) {
        viewModelScope.launch {
            db.bookmarkDao().insert(
                Bookmark(frequencyHz = freqHz, label = label, demodMode = demodMode,
                    notes = notes, color = color, bookmarkListId = bookmarkListId)
            )
        }
    }

    fun updateBookmark(bookmark: Bookmark) {
        viewModelScope.launch { db.bookmarkDao().update(bookmark.copy(updatedAt = System.currentTimeMillis())) }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch { db.bookmarkDao().delete(bookmark) }
    }

    fun toggleBookmarkFavorite(bookmark: Bookmark) {
        viewModelScope.launch { db.bookmarkDao().setFavorite(bookmark.id, !bookmark.favorite) }
    }

    fun moveBookmarksToList(ids: List<Int>, listId: Long?) {
        viewModelScope.launch { db.bookmarkDao().moveToList(ids, listId) }
    }

    fun getBookmarksFiltered(search: String, onlyFavorites: Boolean, listId: Long) =
        db.bookmarkDao().getFiltered(search, onlyFavorites, listId)

    fun importBookmarks(bookmarks: List<Bookmark>) {
        viewModelScope.launch { db.bookmarkDao().insertAll(bookmarks) }
    }

    // ─── Bookmark Lists ────────────────────────────────────────────────────────

    fun addBookmarkList(name: String, notes: String = "", color: Int = 0xFF2196F3.toInt()) {
        viewModelScope.launch { db.bookmarkListDao().insert(BookmarkList(name = name, notes = notes, color = color)) }
    }

    fun updateBookmarkList(list: BookmarkList) {
        viewModelScope.launch { db.bookmarkListDao().update(list) }
    }

    fun deleteBookmarkList(list: BookmarkList, deleteContents: Boolean) {
        viewModelScope.launch {
            if (deleteContents) db.bookmarkDao().deleteByList(list.id)
            else db.bookmarkDao().moveToList(
                db.bookmarkDao().getByList(list.id).first().map { it.id },
                null
            )
            db.bookmarkListDao().delete(list)
        }
    }

    // ─── FFT window ───────────────────────────────────────────────────────────

    fun setFftWindow(w: FftEngine.WindowType) {
        sdrService?.dspEngine?.setFftWindow(w)
    }

    fun setFftSmoothing(alpha: Float) {
        sdrService?.dspEngine?.setFftSmoothing(alpha)
    }

    // ─── Vocoder ──────────────────────────────────────────────────────────────

    /**
     * Rebuild the native dsdcc decoder handle. Forwards to
     * [DspEngine.rebuildVocoderDecoder]. Safe to call when no engine is
     * running (no-op in that case).
     */
    fun rebuildVocoderDecoder() {
        sdrService?.rebuildVocoderDecoder()
    }

    // ─── RDS data ─────────────────────────────────────────────────────────────

    val rdsData: StateFlow<com.radiosport.ninegradio.dsp.RdsDecoder.RdsData>
        get() = sdrService?.dspEngine?.rdsDecoder?.dataFlow
            ?: MutableStateFlow(com.radiosport.ninegradio.dsp.RdsDecoder.RdsData())

    // ─── Scan entries ─────────────────────────────────────────────────────────

    fun saveScanEntry(entry: com.radiosport.ninegradio.data.ScanEntry) {
        viewModelScope.launch { db.scanEntryDao().insert(entry) }
    }

    fun deleteScanEntry(entry: com.radiosport.ninegradio.data.ScanEntry) {
        viewModelScope.launch { db.scanEntryDao().delete(entry) }
    }

    // ─── Device discovery ─────────────────────────────────────────────────────

    fun getService(): RtlSdrService? = sdrService

    fun detectUsbDevices(): List<UsbDevice> {
        val usbManager = getApplication<Application>().getSystemService(UsbManager::class.java)
        return usbManager.deviceList.values.filter { com.radiosport.ninegradio.source.UsbSources.isSupported(it) }
    }

    override fun onCleared() {
        displayPrefs().unregisterOnSharedPreferenceChangeListener(fmPreferenceListener)
        saveDeviceSettings()
        super.onCleared()
    }

    private var syncingFmPreference=false
    private fun syncFmStereoPreference(mode: DemodMode) {
        if (mode !in listOf(DemodMode.FM, DemodMode.WFM, DemodMode.WFM_STEREO)) return
        val stereo=mode==DemodMode.WFM_STEREO
        if (displayPrefs().getBoolean("pref_stereo_wfm",true)==stereo) return
        syncingFmPreference=true
        try { displayPrefs().edit().putBoolean("pref_stereo_wfm",stereo).apply() }
        finally { syncingFmPreference=false }
    }
    private val fmPreferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { settings, key ->
        if (syncingFmPreference) return@OnSharedPreferenceChangeListener
        when (key) {
            "pref_stereo_wfm" -> if (_demodMode.value in listOf(DemodMode.FM, DemodMode.WFM, DemodMode.WFM_STEREO)) {
                setDemodMode(if (settings.getBoolean(key, true)) DemodMode.WFM_STEREO else DemodMode.WFM, automatic = true)
            }
            "pref_deemph_us" -> sdrService?.setFmDeemphasis(settings.getString(key, "75")?.toIntOrNull() ?: 75)
        }
    }
    init {
        syncFmStereoPreference(_demodMode.value)
        displayPrefs().registerOnSharedPreferenceChangeListener(fmPreferenceListener)
    }
}
