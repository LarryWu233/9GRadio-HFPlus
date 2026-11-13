package com.radiosport.ninegradio.ui

import android.Manifest
import android.content.*
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.hardware.usb.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.radiosport.ninegradio.R
import com.radiosport.ninegradio.toExactMhzString
import com.radiosport.ninegradio.data.FrequencyDatabase
import com.radiosport.ninegradio.data.RecordingMeta
import org.json.JSONObject
import com.radiosport.ninegradio.databinding.ActivityMainBinding
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.dsp.DspEngine
import com.radiosport.ninegradio.usb.RtlSdrDevice
import com.radiosport.ninegradio.usb.RtlSdrService
import com.radiosport.ninegradio.source.IqSource
import com.radiosport.ninegradio.source.RtlSdrDriverApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.radiosport.ninegradio.dsp.FrequencyStepManager
import com.radiosport.ninegradio.dsp.AprsDecoder

/** Vocoder status label — mbelib-neo is statically linked and always active. */
private const val VOCODER_STATUS_TEXT = "Vocoder: mbelib-neo built-in active"

class MainActivity : RadioThemedActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_PERMISSIONS = 1001
    }

    private lateinit var etm: EtmController
    private lateinit var regionalReception: RegionalReceptionController
    private lateinit var graphLayout: SpectrumWaterfallLayout
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var sdrService: RtlSdrService? = null
    private var bound = false
    private var vmBound = false

    // Saved nav-bar inset so applyExpandedOffset can include it in the
    // single paddingBottom call it makes on the NestedScrollView.
    private var navBarHeightPx = 0

    // Track pending permission receiver so it can be cleaned up in onDestroy.
    private var pendingPermReceiver: BroadcastReceiver? = null

    /**
     * Set to true while an intentional USB → Rtl-sdr Driver switch is in progress.
     * During this window, [handleUsbDeviceAttached] is a no-op so that the USB
     * re-enumeration Android fires after we release the interface does not
     * immediately pull the source back to USB and undo the switch.
     * Cleared in onResume() once the TCP connection to the driver app is initiated.
     */
    private var suppressUsbAttach = false


    // Long-press repeat state for frequency +/− buttons.
    private var freqRepeatJob: Job? = null
    private var freqRepeatFired = false

    // Tabbed controls drawer
    private lateinit var pagerAdapter: ControlsPagerAdapter
    private var contentController: ContentModuleController?=null
    private val contentMicPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted -> contentController?.microphoneResult(granted) }
    private val contentWavPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> contentController?.wavResult(uri) }
    private val contentCsvPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> contentController?.csvResult(uri) }
    private lateinit var ctrlViews: ControlsTabManager

    // Snap-to-channel preference (synced with the Tune tab switch)
    private var snapToChannel: Boolean = true

    // Sample-rate / audio-sink chip label caches, populated once in
    // setupControlsPanelDeferred() and reused by syncTabControls() so the
    // RF tab's chip-selection highlight can be refreshed without rebuilding
    // the chip rows every time the tab is brought back into view.
    private var sampleRateLabelsCache: List<String> = emptyList()
    private var audioSinkLabelsCache: List<String> = emptyList()

    // ─── APRS drawer tab state ────────────────────────────────────────────────
    private val aprsPackets      = mutableListOf<Pair<AprsDecoder.AprsPacket, String>>()
    private val aprsDisplayList  = mutableListOf<String>()
    private var aprsListAdapter: ArrayAdapter<String>? = null
    private var aprsCollectJob: Job? = null

    // ─── ACARS drawer tab state ───────────────────────────────────────────────
    private val acarsMessages     = mutableListOf<com.radiosport.ninegradio.dsp.AcarsDecoder.AcarsMessage>()
    private val acarsDisplayList  = mutableListOf<String>()
    private var acarsListAdapter: ArrayAdapter<String>? = null
    private var acarsCollectJob: Job? = null

    // ─── DMR drawer tab state ─────────────────────────────────────────────────
    // dmrCalls groups consecutive frames of the same transmission into one
    // "call" entry (see RecentCallTracker) -- the drawer's list is a recent-
    // calls list, not a recent-frames list, for every digital voice mode
    // (APRS is a packet mode, not voice, and keeps its own separate packet
    // list untouched -- see aprsPackets above).
    private val dmrCalls        = com.radiosport.ninegradio.dsp.RecentCallTracker()
    private val dmrDisplayList  = mutableListOf<String>()
    private var dmrListAdapter: ArrayAdapter<String>? = null
    private var dmrCollectJob: Job? = null
    private var dmrFrameCount = 0

    // ─── Dig drawer tab state ─────────────────────────────────────────────────
    private val digCalls        = com.radiosport.ninegradio.dsp.RecentCallTracker()
    private val digDisplayList  = mutableListOf<String>()
    private var digListAdapter: ArrayAdapter<String>? = null
    private var digCollectJob: Job? = null
    private var digFrameCount = 0

    // ─── YSF drawer tab state ─────────────────────────────────────────────────
    private val ysfCalls        = com.radiosport.ninegradio.dsp.RecentCallTracker()
    private val ysfDisplayList  = mutableListOf<String>()
    private var ysfListAdapter: ArrayAdapter<String>? = null
    private var ysfCollectJob: Job? = null
    private var ysfFrameCount = 0

    // ─── D-STAR drawer tab state ──────────────────────────────────────────────
    private val dstarCalls        = com.radiosport.ninegradio.dsp.RecentCallTracker()
    private val dstarDisplayList  = mutableListOf<String>()
    private var dstarListAdapter: ArrayAdapter<String>? = null
    private var dstarCollectJob: Job? = null
    private var dstarFrameCount = 0

    // ─── Scan drawer tab state ────────────────────────────────────────────────
    // Unlike the protocol tabs above, this tab isn't tied to a DemodMode -- it
    // is shown/hidden on demand via showScanTab()/hideScanTab(), invoked from
    // the Settings tab's Scanner button and the main FAB.
    private var scanTabScanner: com.radiosport.ninegradio.scanner.FrequencyScanner? = null
    private var scanTabStartJob: kotlinx.coroutines.Job? = null
    private val scanTabHitLog = mutableListOf<RadioDataTable.Row>()
    private val scanTabHitFreqs = mutableListOf<Long>()
    private var scanTabHitAdapter: RadioDataTable? = null
    private val scanTabChannelRows = mutableListOf<com.radiosport.ninegradio.scanner.FrequencyScanner.ChannelEntry>()
    private var scanTabChannelAdapter: RadioDataTable? = null
    private var scanTabChannelTableJob: Job? = null
    private var scanTabStatusJob: Job? = null
    private var scanTabHitsJob: Job? = null

    /**
     * Everything [configureSpectrumForScan] and the scan handlers' own
     * `svc.setDemodMode` / `svc.setSquelch` calls are about to override,
     * captured right before a scan/search starts so it can all be put back
     * exactly as it was once the scan stops.
     *
     * This matters because none of those calls go through
     * [MainViewModel.setDemodMode] (which is what normally snapshots a
     * mode's settings on the way out) -- scanning deliberately bypasses it so
     * starting a scan doesn't itself count as "switching modes". But that
     * also means nothing else will restore these values afterward, and if
     * the user switches modes *while* the scan-tuned sample rate/decimation/
     * FFT size/frequency are still live, [MainViewModel.setDemodMode] would
     * snapshot *those* into the current mode's saved settings, silently
     * overwriting whatever the user had actually configured for it. Taking
     * our own snapshot and restoring it the moment the scan ends closes that
     * window.
     */
    private data class ScanRfSnapshot(
        val centerFreqHz: Long,
        val sampleRate: Int,
        val decimation: Int,
        val fftSize: Int,
        val frameAveraging: Int,
        val autoRange: Boolean,
        val demodMode: DemodMode,
        val squelchDb: Float,
        val demodChannelBwHz: Long
    )
    private var scanTabPreScanSnapshot: ScanRfSnapshot? = null

    /** Capture the live RF/display state the instant before a scan/search
     *  starts. No-op (keeps the existing snapshot) if a scan is already
     *  running, so Search-while-scanning style re-entry can't clobber the
     *  original pre-scan snapshot with already-scan-tuned values. */
    private fun captureRfSnapshotBeforeScan() {
        if (scanTabPreScanSnapshot != null) return
        val displayPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        scanTabPreScanSnapshot = ScanRfSnapshot(
            centerFreqHz = viewModel.centerFreqHz.value,
            sampleRate = viewModel.sampleRate.value,
            decimation = viewModel.decimation.value,
            fftSize = viewModel.fftSize.value,
            frameAveraging = displayPrefs.getString("pref_frame_averaging", "1")?.toIntOrNull() ?: 1,
            autoRange = displayPrefs.getBoolean("pref_auto_range", false),
            demodMode = viewModel.demodMode.value,
            squelchDb = viewModel.squelch.value,
            demodChannelBwHz = channelHighlightHz(viewModel.demodMode.value, viewModel.ifBandwidthHz.value)
        )
    }

    /** Put back everything [captureRfSnapshotBeforeScan] captured. Called
     *  once a scan/search actually stops (Stop/Reset/Close) -- never mid-scan,
     *  so pausing doesn't lose the live scan state. Safe to call with no
     *  snapshot pending (e.g. the tab was closed without ever starting a scan). */
    private fun restoreRfSnapshotAfterScan() {
        val snap = scanTabPreScanSnapshot ?: return
        scanTabPreScanSnapshot = null

        val svc = sdrService
        svc?.setDemodMode(snap.demodMode)
        viewModel.setSquelch(snap.squelchDb)

        if (snap.sampleRate != viewModel.sampleRate.value) {
            viewModel.setSampleRate(snap.sampleRate)
            val idx = viewModel.availableSampleRates.indexOfFirst { it == viewModel.sampleRate.value }
            if (idx >= 0) {
                sampleRateLabelsCache.getOrNull(idx)?.let { ctrlViews.tvSampleRateLabel?.text = it }
                updateSampleRateChips(idx)
            }
        }
        if (snap.decimation != viewModel.decimation.value) {
            viewModel.setDecimation(snap.decimation)
            val decimationFactors = listOf(1, 2, 4, 8, 16, 32, 64)
            decimationFactors.indexOf(snap.decimation).takeIf { it >= 0 }
                ?.let { ctrlViews.spinnerDecimation?.setSelection(it, false) }
        }
        val effectiveRate = viewModel.sampleRate.value / viewModel.decimation.value.coerceAtLeast(1)
        binding.spectrumView.setSampleRate(effectiveRate)
        binding.waterfallView.setSampleRate(effectiveRate)

        if (snap.fftSize != viewModel.fftSize.value) {
            viewModel.setFftSize(snap.fftSize)
            val fftSizes = listOf(256, 512, 1024, 2048, 4096, 8192)
            fftSizes.indexOf(snap.fftSize).takeIf { it >= 0 }
                ?.let { ctrlViews.spinnerFftSize?.setSelection(it, false) }
        }

        svc?.dspEngine?.fftEngine?.frameAveragingCount = snap.frameAveraging
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putString("pref_frame_averaging", snap.frameAveraging.toString()).apply()
        val frameAvgValues = listOf(1, 2, 4, 8, 16, 32)
        frameAvgValues.indexOf(snap.frameAveraging).takeIf { it >= 0 }
            ?.let { ctrlViews.spinnerFrameAvg?.setSelection(it, false) }

        binding.spectrumView.setAutoRange(snap.autoRange)
        ctrlViews.switchAutoRange?.isChecked = snap.autoRange
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putBoolean("pref_auto_range", snap.autoRange).apply()

        viewModel.setFrequency(snap.centerFreqHz)
        binding.spectrumView.setCenterFrequency(hardwareCenterFreqHz(snap.centerFreqHz, snap.demodMode))
        binding.spectrumView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(snap.centerFreqHz, snap.demodMode))
        binding.waterfallView.setCenterFrequency(hardwareCenterFreqHz(snap.centerFreqHz, snap.demodMode))
        binding.waterfallView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(snap.centerFreqHz, snap.demodMode))

        binding.spectrumView.setSquelch(snap.squelchDb)
        binding.spectrumView.setDemodChannelBandwidth(
            snap.demodChannelBwHz, channelHighlightSideband(snap.demodMode))
    }
    private var scanTabScanning = false
    private var scanTabPaused = false
    private var scanTabSearching = false

    // ─── Rec tab recordings list state ───────────────────────────────────────
    // Displays the same recordings the standalone RecordingActivity shows, but
    // inline at the bottom of the Rec tab so they're visible without leaving
    // the drawer. Container view stays GONE until at least one recording exists.
    private var recordingsTabAdapter: RecordingAdapter? = null

    // Launcher for vocoder plugin file picker (ACTION_OPEN_DOCUMENT).
    // Using registerForActivityResult avoids the deprecated startActivityForResult path
    // and correctly holds the URI grant across the picker round-trip.
    // Launcher for BookmarkActivity -- receives the selected frequency via setResult.
    private val bookmarkLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val hz = result.data?.getLongExtra(BookmarkActivity.EXTRA_FREQ_HZ, -1L) ?: -1L
            if (hz > 0L && viewModel.setFrequency(hz)) {
                viewModel.finishTuning()
                syncFrequencyDisplay()
                showSnackbar("已调谐至 ${"%.3f".format(hz/1e6)} MHz")
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            sdrService = (binder as RtlSdrService.LocalBinder).getService()
            bound = true
            contentController?.bindService()
            // If device is already connected (ViewModel connected it before us), apply settings now.
            if (viewModel.connectionState.value is RtlSdrService.ConnectionState.Connected) {
                if (viewModel.isV4 && !viewModel.allowOutOfBand.value) {
                    viewModel.setAllowOutOfBand(true)
                    ctrlViews.switchOutOfBound?.isChecked = true
                }
                applyAllSettings()
                return   // device already open -- do NOT call handleUsbDeviceAttached again
            }
            // Only scan for already-attached devices if the ViewModel has NOT already
            // initiated a connection.  If the ViewModel is in Connecting state the race
            // is already won -- wait for the connectionState observer to call applyAllSettings.
            if (viewModel.connectionState.value is RtlSdrService.ConnectionState.Disconnected) {
                val usbManager = getSystemService(UsbManager::class.java)
                usbManager.deviceList.values
                    .firstOrNull { com.radiosport.ninegradio.source.UsbSources.isSupported(it) }
                    ?.let { handleUsbDeviceAttached(it) }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            sdrService = null; bound = false
        }
    }

    // USB receiver for hotplug detection.
    // Must use RECEIVER_EXPORTED: ACTION_USB_DEVICE_ATTACHED / DETACHED are system broadcasts
    // sent from outside the app process, so RECEIVER_NOT_EXPORTED silently drops them on API 31+.
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val dev = intent.usbDevice()
                    if (dev != null && com.radiosport.ninegradio.source.UsbSources.isSupported(dev)) {
                        handleUsbDeviceAttached(dev)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    showSnackbar("Receiver disconnected")
                    updateConnectionUi(false)
                }
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {}
            }
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enable edge-to-edge so the layout extends behind system bars.
        // We then apply insets manually to the chip bar so it's never hidden
        // by the navigation bar on Android 15+ (target SDK 35 enforces edge-to-edge).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isStatusBarContrastEnforced = false
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !UiThemes.current(this).dark
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Use the navigation-bar inset to size the collapsed peek height so the
        // tab bar is always fully clear of the system navigation bar. Note: we
        // intentionally do NOT add this as bottom padding on controlTabLayout --
        // it has a fixed height (44dp), and on devices with a large gesture-nav
        // inset that padding can consume the entire height, clipping the tab
        // labels to invisible. The extra peekHeight margin alone is enough to
        // keep the tab bar above the nav bar.
        ViewCompat.setOnApplyWindowInsetsListener(binding.controlTabLayout) { view, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            // The measured header contains the arrow row and tab bar.
            // Add nav bar inset as breathing room below the tab bar.
            val density = resources.displayMetrics.density
            val peekPx = binding.controlsHeader.height.takeIf { it>0 }?.plus(navBar.bottom) ?: ((68*density).toInt()+navBar.bottom)
            val behavior = BottomSheetBehavior.from(binding.controlsBottomSheet)
            behavior.isGestureInsetBottomIgnored = true
            behavior.peekHeight = peekPx
            // Save for use in applyExpandedOffset -- do NOT set sheet paddingBottom here;
            // applyExpandedOffset owns that to avoid the two callbacks fighting.
            navBarHeightPx = navBar.bottom
            insets
        }

        setupToolbar()
        setupSpectrumView()
        setupWaterfallView()
        setupFrequencyView()
        setupControlsPanel()
        setupBottomSheet()
        graphLayout = SpectrumWaterfallLayout(binding.spectrumView, binding.waterfallView,
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this))

        regionalReception = RegionalReceptionController(this, { viewModel.capabilities.value }, { viewModel.demodMode.value to viewModel.centerFreqHz.value }) { mode, hz ->
            viewModel.setDemodMode(mode)
            viewModel.setFrequency(hz)
            viewModel.finishTuning()
            if (mode == DemodMode.ADSB) updateAdsbTabVisibility(mode)
        }
        etm = EtmController(this, { viewModel.getService() }, { viewModel.demodMode.value }, { viewModel.centerFreqHz.value },
            { mode, hz -> viewModel.setDemodMode(mode); viewModel.setFrequency(hz); viewModel.finishTuning() },
            { hz ->
                binding.frequencyView.setFrequency(hz)
                binding.spectrumView.setCenterFrequency(hardwareCenterFreqHz(hz))
                binding.waterfallView.setCenterFrequency(hardwareCenterFreqHz(hz))
            }, { viewModel.autoDemod.value }, { viewModel.setAutoDemod(it) }, { !scanTabScanning })
        setupFabMenu()
        observeViewModel()

        // Request dangerous runtime permissions the app needs.
        // RECORD_AUDIO  - declared in manifest; request so AudioEngine works.
        // POST_NOTIFICATIONS (API 33+) - required to show the foreground-service
        //   notification on Android 13 and above.
        requestInitialPermissions()

        // Bind to SDR service
        val serviceIntent = Intent(this, RtlSdrService::class.java).setAction(RtlSdrService.ACTION_START)
        startForegroundService(serviceIntent)
        vmBound = bindService(Intent(this, RtlSdrService::class.java), viewModel.serviceConnection, BIND_AUTO_CREATE)
        bindService(Intent(this, RtlSdrService::class.java), serviceConnection, BIND_AUTO_CREATE)

        // Register USB receiver.
        // RECEIVER_EXPORTED is required: USB attach/detach broadcasts are sent by
        // the system process, which is outside this app's UID. RECEIVER_NOT_EXPORTED
        // silently drops cross-UID broadcasts on API 31+.
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        // Handle launch-from-USB intent (app started cold by USB attach)
        intent?.usbDevice()?.let {
            if (com.radiosport.ninegradio.source.UsbSources.isSupported(it)) handleUsbDeviceAttached(it)
        }
    }

    /**
     * Request dangerous permissions needed at launch.
     * Only requests each one if not already granted.
     */
    private fun requestInitialPermissions() {
        val needed = mutableListOf<String>()


        // POST_NOTIFICATIONS is a runtime permission starting from Android 13 (API 33).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            permissions.forEachIndexed { i, perm ->
                if (grantResults.getOrElse(i) { PackageManager.PERMISSION_DENIED }
                        == PackageManager.PERMISSION_DENIED) {
                    when (perm) {
                        Manifest.permission.RECORD_AUDIO ->
                            showSnackbar("Audio permission denied - audio output will be silent")
                        Manifest.permission.POST_NOTIFICATIONS ->
                            showSnackbar("Notification permission denied - service notification hidden")
                    }
                }
            }
        }
    }

    /**
     * singleTask activities receive subsequent USB_DEVICE_ATTACHED intents here
     * (not in onCreate) when the app is already in the foreground.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.usbDevice()?.let {
            if (com.radiosport.ninegradio.source.UsbSources.isSupported(it) && !suppressUsbAttach) handleUsbDeviceAttached(it)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel any pending timed-shutdown countdown.
        stopAprsCollect()
        stopDmrCollect()
        stopYsfCollect()
        stopDstarCollect()
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        if (vmBound) {
            unbindService(viewModel.serviceConnection)
            vmBound = false
        }
        unregisterReceiver(usbReceiver)
        // Clean up any pending USB permission receiver to avoid leaks.
        pendingPermReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            pendingPermReceiver = null
        }
    }

    private var modulesSnapshot: String? = null
    override fun onResume() {
        super.onResume()
        val modulesNow=ModulePreferences(this).let { it.order.joinToString(",")+":"+it.managedVisibility() }
        if(modulesSnapshot!=null && modulesSnapshot!=modulesNow) { modulesSnapshot=modulesNow; recreate(); return }
        modulesSnapshot=modulesNow
        binding.frequencyView.reverseTuning=androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).getBoolean("pref_reverse_tuning",false)
        updateDigitalVisualization()
        applyDisplayPreferences()
        ContextCompat.registerReceiver(
            this, displayPrefsReceiver,
            android.content.IntentFilter(SettingsFragment.ACTION_DISPLAY_PREFS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Re-push the decimation factor to the DSP engine every time the app
        // returns from the background.  The service may have lost the pending
        // value while the process was suspended (the pending field is in-memory
        // only and is not re-applied when the engine restarts without a full
        // service unbind/rebind cycle).  viewModel.decimation is persisted in
        // SharedPreferences and is always the authoritative source of truth.
        val savedDecim = viewModel.decimation.value
        sdrService?.setFftDecimation(savedDecim)
        // Also ensure the spinner reflects the correct position (it may have
        // been reset to 0 if the fragment was recreated while backgrounded).
        val decimationFactors = listOf(1, 2, 4, 8, 16, 32, 64)
        val idx = decimationFactors.indexOf(savedDecim).takeIf { it >= 0 } ?: 0
        ctrlViews.spinnerDecimation?.setSelection(idx, false)
        // Update displayed effective sample rate immediately.
        val effectiveRate = viewModel.sampleRate.value / savedDecim.coerceAtLeast(1)
        binding.spectrumView.setSampleRate(effectiveRate)
        binding.waterfallView.setSampleRate(effectiveRate)
    }

    override fun onStop() {
        contentController?.onBackground()
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        if(::pagerAdapter.isInitialized) pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_ADSB)?.findViewById<AdsbRadarView>(R.id.adsbPanelRadar)?.setAnimating(false)
        if(::pagerAdapter.isInitialized) pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_RDF)?.findViewById<android.widget.FrameLayout>(R.id.rdfControls)
            ?.let { (it.getChildAt(0) as? RdfControls)?.setActive(false) }
        unregisterReceiver(displayPrefsReceiver)
        // Safety net: capture whatever is currently in the scan tab's fields
        // even if the user edited them but never pressed Start/Search before
        // backgrounding or leaving the app, so those edits aren't lost.
        if (ctrlViews.listScanTabHits != null) saveLastScanTabSettings()
    }

    /**
     * Called when the RTLSDR Driver app (DeviceOpenActivity) returns.
     * RESULT_OK means the rtl_tcp server is running -- connect immediately.
     * RESULT_CANCELED means the user cancelled or an error occurred.
     */
    @Deprecated("Using legacy onActivityResult for rtl_tcp_andro iqsrc:// protocol")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RtlSdrDriverApp.REQUEST_CODE) return

        suppressUsbAttach = false

        if (resultCode == android.app.Activity.RESULT_OK) {
            showSnackbar("Rtl-sdr Driver ready -- connecting…")
            viewModel.connectTcp(RtlSdrDriverApp.DEFAULT_ADDRESS, RtlSdrDriverApp.DEFAULT_PORT)
        } else {
            // Driver returned failure -- read the error code if available
            val errorId = data?.getIntExtra("marto.rtl_tcp_andro.RtlTcpExceptionId", -1) ?: -1
            val reason = if (errorId >= 0) " (error $errorId)" else ""
            showSnackbar("Rtl-sdr Driver failed to start$reason")
            // Revert the switch back to USB in the open dialog (if still showing)
            // This is best-effort; the switch state is local to the dialog.
        }
    }

    /** Receiver for real-time display-preference changes from SettingsFragment. */
    private val displayPrefsReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
            applyDisplayPreferences()
        }
    }

    /**
     * Reads all display-related preferences (theme, palette, dB range, waterfall speed)
     * from the default SharedPreferences and applies them to the Views.
     * Called on every onResume and on broadcast from SettingsFragment.
     */
    private fun applyDisplayPreferences() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)

        // FFT size -- synchronise Settings pref with ViewModel and drawer spinner.
        // pref_fft_size (default prefs) is the authoritative source when changed
        // from Settings; we push it into the ViewModel so the drawer spinner and
        // DspEngine/FftEngine are all kept in step.
        val fftSizes = listOf(256, 512, 1024, 2048, 4096, 8192)
        val fftSizeFromSettings = prefs.getString("pref_fft_size", null)?.toIntOrNull()
        if (fftSizeFromSettings != null && fftSizeFromSettings != viewModel.fftSize.value) {
            viewModel.setFftSize(fftSizeFromSettings)
            val idx = fftSizes.indexOf(fftSizeFromSettings).takeIf { it >= 0 } ?: -1
            if (idx >= 0) ctrlViews.spinnerFftSize?.setSelection(idx, false)
        }

        // Decimation -- synchronise Settings pref with ViewModel and drawer spinner.
        val decimationFactors = listOf(1, 2, 4, 8, 16, 32, 64)
        val decimFromSettings = prefs.getString("pref_decimation", null)?.toIntOrNull()
        if (decimFromSettings != null && decimFromSettings != viewModel.decimation.value) {
            viewModel.setDecimation(decimFromSettings)
            val dIdx = decimationFactors.indexOf(decimFromSettings).takeIf { it >= 0 } ?: -1
            if (dIdx >= 0) ctrlViews.spinnerDecimation?.setSelection(dIdx, false)
            // Update views immediately with new effective rate
            val effectiveRate = viewModel.sampleRate.value / decimFromSettings.coerceAtLeast(1)
            binding.spectrumView.setSampleRate(effectiveRate)
            binding.waterfallView.setSampleRate(effectiveRate)
        }

        // Spectrum theme
        val theme = prefs.getString("pref_spectrum_theme", "UI") ?: "Futuristic"
        binding.spectrumView.setTheme(theme)
        viewModel.setSpectrumTheme(theme)

        // dB range: preferences store pref_db_min as positive ×−1, pref_db_max as 0..n
        // These are now also saved/restored per-protocol by MainViewModel.
        val storedMin = prefs.getInt("pref_db_min", 120)
        val dbMinRaw = storedMin.takeIf { it in 10..200 } ?: 120
        if (dbMinRaw != storedMin) prefs.edit().putInt("pref_db_min", dbMinRaw).apply()
        val dbMaxRaw  = prefs.getInt("pref_db_max", 0)
        val dbMin     = -(dbMinRaw.toFloat()).coerceIn(10f, 200f)
        val dbMax     = dbMaxRaw.toFloat().coerceIn(-100f, 50f)
        binding.spectrumView.setDbRange(dbMin, dbMax)
        binding.waterfallView.setDynamicRange(dbMin, dbMax)
        // Sync the Display-tab sliders if the tab is already inflated
        ctrlViews.sliderSpecFloor?.value = dbMin
        ctrlViews.tvSpecFloorValue?.text = dbMin.toInt().toString()
        ctrlViews.sliderSpecCeiling?.value = dbMax
        ctrlViews.tvSpecCeilingValue?.text = dbMax.toInt().toString()

        // Frame averaging: also per-protocol, pick up the restored pref
        val restoredFrameAvg = prefs.getString("pref_frame_averaging", "1")?.toIntOrNull() ?: 1
        sdrService?.dspEngine?.fftEngine?.frameAveragingCount = restoredFrameAvg

        // Waterfall palette
        val paletteName = prefs.getString("pref_waterfall_palette", "RAINBOW") ?: "RAINBOW"
        try {
            val palette = WaterfallView.Companion.Palette.valueOf(paletteName)
            binding.waterfallView.setPalette(palette)
            viewModel.setWaterfallPalette(palette)
        } catch (_: IllegalArgumentException) {}

        // Waterfall speed
        val speed = prefs.getInt("pref_waterfall_speed", 25)
        binding.waterfallView.setSpeed(speed)

        // Peak hold
        val peakHold = prefs.getBoolean("pref_peak_hold", true)
        binding.spectrumView.setShowPeakHold(peakHold)
        sdrService?.dspEngine?.fftEngine?.showPeakHold = peakHold

        // FFT smoothing (propagate to engine in case MainActivity was paused while in Settings)
        val smoothing = prefs.getInt("pref_fft_smoothing", 30)
        sdrService?.dspEngine?.setFftSmoothing(smoothing / 100f)

        // ── Advanced spectrum / waterfall preferences ──────────────────────────

        // Frame averaging (N-frame power accumulation before display)
        val frameAvg = prefs.getString("pref_frame_averaging", "1")?.toIntOrNull() ?: 1
        sdrService?.dspEngine?.fftEngine?.frameAveragingCount = frameAvg

        // Peak-hold decay rate (SeekBar 1-20 → 0.001-0.020 dB/frame)
        val peakDecay = prefs.getInt("pref_peak_decay", 2)
        sdrService?.dspEngine?.fftEngine?.peakDecayRate = peakDecay * 0.001f

        // Noise floor line
        val showNf = prefs.getBoolean("pref_show_noise_floor", true)
        binding.spectrumView.setShowNoiseFloor(showNf)

        // Peak annotations
        val showAnnotations = prefs.getBoolean("pref_show_peak_annotations", true)
        binding.spectrumView.setShowPeakAnnotations(showAnnotations)

        // Touch crosshair
        val crosshair = prefs.getBoolean("pref_crosshair_enabled", true)
        binding.spectrumView.setCrosshairEnabled(crosshair)

        // Spectrum fill opacity (0-100 → 0.0-1.0)
        val fillOpacity = prefs.getInt("pref_fill_opacity", 73)
        binding.spectrumView.setFillOpacity(fillOpacity / 100f)

        // Auto dB range
        val autoRange = prefs.getBoolean("pref_auto_range", false)
        binding.spectrumView.setAutoRange(autoRange)

        // Waterfall brightness: SeekBar 0-100 → −0.5..+0.5
        val wfBrightness = prefs.getInt("pref_waterfall_brightness", 50)
        binding.waterfallView.setBrightness((wfBrightness - 50) / 100f)

        // Waterfall contrast: SeekBar 5-150 → 0.1..3.0
        val wfContrast = prefs.getInt("pref_waterfall_contrast", 50)
        binding.waterfallView.setContrast(wfContrast / 50f)

        // Waterfall auto-stretch
        val wfAutoStretch = prefs.getBoolean("pref_waterfall_auto_stretch", false)
        binding.waterfallView.setAutoStretch(wfAutoStretch)
        binding.waterfallView.setManualLevels(
            if (!wfAutoStretch) prefs.getInt("waterfall_min_db",-prefs.getInt("pref_db_min",120)).toFloat() else null,
            prefs.getInt("waterfall_max_db",prefs.getInt("pref_db_max",0)).toFloat())

        // Waterfall time ruler
        val wfTimestamp = prefs.getBoolean("pref_waterfall_show_timestamp", true)
        binding.waterfallView.setShowTimestamp(wfTimestamp)

        // Waterfall centre-frequency marker
        val wfTunerMarker = prefs.getBoolean("pref_waterfall_show_tuner_marker", true)
        binding.waterfallView.setShowTunerMarker(wfTunerMarker)

        // Keep screen on
        val keepOn = prefs.getBoolean("pref_keep_screen_on", true)
        if (keepOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // The toolbar's overflow ("3-dot") menu has been removed. Every action it
    // used to provide now has a button elsewhere: Settings tab (Settings,
    // Debug Panel, Scanner, Recordings, Device Info, Freq Database, ADS-B
    // Bookmarks), the Bookmark/Memory/Scan FABs on the
    // main screen, or — for APRS/ACARS — simply selecting that protocol from
    // the Mode tab's chip group, which already navigates to its drawer tab
    // automatically (see observeAprsMode/observeAcarsMode). "Connect rtl_tcp…"
    // duplicated the Device Info button's action exactly and needed no replacement.

    // ─── UI Setup ─────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        // A decor ActionBar is already present when the theme still supplies one (e.g. DarkActionBar).
        // Calling setSupportActionBar() in that case throws IllegalStateException, so only
        // replace it with our Toolbar when the window decor hasn't created one yet.
        if (supportActionBar == null) {
            setSupportActionBar(binding.toolbar)
        }
        supportActionBar?.title = "9GRadio"

        // Apply top inset (status bar) to the AppBarLayout so content isn't hidden
        // behind the status bar when running edge-to-edge.
        val appBar = binding.toolbar.parent as? android.view.ViewGroup ?: binding.toolbar
        // Keep the inset and toolbar one fixed surface, independent of nested-scroll lift.
        appBar.setBackgroundColor(UiThemes.color(this,R.attr.radioSurface))
        ViewCompat.setOnApplyWindowInsetsListener(appBar) { view, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(view.paddingLeft, statusBar.top, view.paddingRight, view.paddingBottom)
            insets
        }
    }

    private var graphDragMode = true
    private var plotZoom = 1f
    private var plotContext: String?=null
    private fun restorePlotContext(mode: DemodMode) {
        if(!viewModel.capabilities.value.supportsMode(mode)) return
        val key="plot_context_${viewModel.capabilities.value.id}_${mode.name}"
        if(plotContext==key) return
        val prefs=androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        plotContext?.let { prefs.edit().putFloat(it,plotZoom).apply() }
        plotContext=key
        setPlotZoom(prefs.getFloat(key,if(mode==DemodMode.ADSB) 1f else plotZoom),false)
    }
    private fun setPlotZoom(value: Float, save: Boolean = true) {
        val window=SpectrumZoom.window(value)
        plotZoom=window.total
        if(viewModel.decimation.value!=window.decimation) viewModel.setDecimation(window.decimation)
        val rate=viewModel.effectiveSampleRate()
        binding.spectrumView.setSampleRate(rate); binding.waterfallView.setSampleRate(rate)
        binding.spectrumView.setZoomPan(window.magnification,0)
        binding.waterfallView.setZoomPan(window.magnification,0)
        updatePlotSpan()
        if (::pagerAdapter.isInitialized) pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_DISPLAY)
            ?.findViewById<com.google.android.material.slider.Slider>(R.id.sliderPlotZoom)?.let { slider ->
                val v = (kotlin.math.ln(plotZoom.toDouble()) / kotlin.math.ln(2.0)).toFloat()
                if (kotlin.math.abs(slider.value - v) > 0.01) slider.value = v
            }
        if (save) savePlotZoom()
    }
    private fun updatePlotSpan() {
        val span=binding.spectrumView.visibleBandwidthHz()
        binding.tvPlotZoom.text="SPAN\n" + if(span>=1_000_000) "${"%.2f".format(span/1e6)} MHz" else if(span>=1000) "${"%.1f".format(span/1e3)} kHz" else "$span Hz"
        binding.tvPlotZoom.contentDescription="显示宽度 ${binding.tvPlotZoom.text} · 全宽"
    }
    private fun updatePlotBandwidth() {
        val mode=viewModel.demodMode.value
        val raw=viewModel.ifBandwidthHz.value
        val width=raw.takeIf { it>0 } ?: mode.defaultBwHz
        val allowed=mode!=DemodMode.ADSB
        binding.tvTuningStep.text="BW\n${formatIfBw(width,false)}"
        binding.tvTuningStep.contentDescription="BW ${formatIfBw(width,raw==0)}"
        binding.tvTuningStep.isEnabled=allowed
        binding.btnStepUp.isEnabled=allowed; binding.btnStepDown.isEnabled=allowed
    }
    private fun changePlotBandwidth(direction:Int) {
        val range=bwRangeFor(viewModel.demodMode.value)
        val current=viewModel.ifBandwidthHz.value.takeIf { it>0 } ?: viewModel.demodMode.value.defaultBwHz
        val next=(nearestBwIdx(current,range)+direction).coerceIn(range.first,range.second)
        viewModel.setIfBandwidth(BW_STEPS[next]); updatePlotBandwidth()
    }
    private fun showPlotValueEditor(span:Boolean) {
        val title=if(span) "SPAN" else "BW"
        val rate=viewModel.sampleRate.value.coerceAtLeast(1)
        val current=if(span) binding.spectrumView.visibleBandwidthHz() else
            (viewModel.ifBandwidthHz.value.takeIf { it>0 } ?: viewModel.demodMode.value.defaultBwHz).toLong()
        val multipliers=longArrayOf(1,1000,1000000)
        val index=if(current>=1000000) 2 else if(current>=1000) 1 else 0
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setPadding(24,12,24,4) }
        val input=EditText(this).apply {
            id=R.id.plotValueInput; setSingleLine(true)
            inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(java.math.BigDecimal.valueOf(current).divide(java.math.BigDecimal.valueOf(multipliers[index])).stripTrailingZeros().toPlainString())
            selectAll()
        }
        val units=Spinner(this).apply {
            id=R.id.plotValueUnit
            adapter=ArrayAdapter(this@MainActivity,R.layout.compact_spinner_item,listOf("Hz","kHz","MHz"))
            setSelection(index)
        }
        row.addView(input,LinearLayout.LayoutParams(0,-2,1f)); row.addView(units,LinearLayout.LayoutParams(-2,-2))
        val dialog=AlertDialog.Builder(this).setTitle(title).setView(row)
            .setPositiveButton("应用",null).setNegativeButton("取消",null)
            .setNeutralButton(if(span) "全宽" else "Auto") { _,_ -> if(span) setPlotZoom(1f) else viewModel.setIfBandwidth(0) }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val hz=runCatching { java.math.BigDecimal(input.text.toString()).multiply(java.math.BigDecimal.valueOf(multipliers[units.selectedItemPosition])).longValueExact() }.getOrNull()
                val range=if(span) (rate/2048L).coerceAtLeast(1)..rate.toLong() else bwRangeFor(viewModel.demodMode.value).let { BW_STEPS[it.first].toLong()..BW_STEPS[it.second].toLong() }
                if(hz==null || hz !in range) { input.error="${range.first}–${range.last} Hz"; return@setOnClickListener }
                if(span) setPlotZoom(rate.toFloat()/hz) else viewModel.setIfBandwidth(hz.toInt())
                updatePlotBandwidth(); dialog.dismiss()
            }
        }
        dialog.show()
        input.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    private fun savePlotZoom() {
        val editor=androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putFloat("plot_total_zoom",plotZoom).putFloat("plot_zoom",plotZoom/viewModel.decimation.value)
        plotContext?.let { editor.putFloat(it,plotZoom) }; editor.apply()
    }

    private fun graphTune(hz: Long): Boolean {
        val old = viewModel.centerFreqHz.value
        val valid = viewModel.capabilities.value.browseFrequency(hz)
        if (old == valid || !viewModel.setFrequency(valid)) return false
        syncFrequencyDisplay()
        return true
    }
    private fun setupSpectrumView() {
        binding.spectrumView.setTheme("UI")
        binding.spectrumView.setOnTouchListener(GraphTuningTouch(binding.spectrumView,
            { graphDragMode }, { viewModel.centerFreqHz.value }, { binding.spectrumView.visibleBandwidthHz() },
            { binding.frequencyView.tuningStepHz }, { binding.spectrumView.frequencyAtPixel(it) },
            { graphTune(it) }, { viewModel.finishTuning(); savePlotZoom() }, { plotZoom }, { setPlotZoom(it, false) },
            { binding.spectrumView.bandwidthEdgeHit(it) }, { x ->
                val range=bwRangeFor(viewModel.demodMode.value)
                val hz=BW_STEPS[nearestBwIdx(binding.spectrumView.bandwidthAtPixel(x),range)]
                if(hz==viewModel.ifBandwidthHz.value) false else { viewModel.setIfBandwidth(hz); true }
            }, { binding.spectrumView.isCrosshairEnabled() },
            { x,y -> binding.spectrumView.showInspection(x,y) }, { binding.spectrumView.hideInspection() }))
    }
    private fun setupWaterfallView() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        graphDragMode = prefs.getBoolean("graph_drag_tune", true)
        setPlotZoom(prefs.getFloat("plot_total_zoom", prefs.getFloat("plot_zoom",1f)*viewModel.decimation.value),false)
        val zoomHaptics = TuningHaptics(binding.tvPlotZoom)
        binding.btnPlotZoomOut.setOnClickListener { setPlotZoom(plotZoom / 2); zoomHaptics.tick() }
        binding.btnPlotZoomIn.setOnClickListener { setPlotZoom(plotZoom * 2); zoomHaptics.tick() }
        binding.tvPlotZoom.setOnClickListener { setPlotZoom(1f); zoomHaptics.tick() }
        fun label() { binding.btnGraphGesture.text = if (graphDragMode) "拖动调谐 ▾" else "点选居中 ▾" }
        label()
        binding.btnGraphGesture.setOnClickListener {
            graphDragMode = !graphDragMode
            prefs.edit().putBoolean("graph_drag_tune", graphDragMode).apply()
            label()
        }
        binding.waterfallView.setOnTouchListener(GraphTuningTouch(binding.waterfallView,
            { graphDragMode }, { viewModel.centerFreqHz.value }, { binding.spectrumView.visibleBandwidthHz() },
            { binding.frequencyView.tuningStepHz }, { binding.waterfallView.frequencyAtPixel(it) },
            { graphTune(it) }, { viewModel.finishTuning(); savePlotZoom() }, { plotZoom }, { setPlotZoom(it, false) }))
    }

    private fun setupFrequencyView() {
        binding.frequencyView.onTuningStepChanged = { step ->
            getSharedPreferences("rtlsdr_prefs", MODE_PRIVATE).edit()
                .putLong("tuningStep_v4_${viewModel.demodMode.value.name}", step).apply()
            updatePlotBandwidth()
            stepPicker()?.selectChoice(step)
            if (::ctrlViews.isInitialized) ctrlViews.tuningDial?.overrideStepHz = maxOf(1, step / 10)
            ctrlViews.chipGroupStep?.let { group -> for (index in 0 until group.childCount) {
                (group.getChildAt(index) as? Chip)?.apply { if (isCheckable) isChecked = tag == step }
            } }
        }
        binding.frequencyView.frequencyLimiter = { current, target -> viewModel.capabilities.value.browseFrequency(target) }
        binding.frequencyView.onTuningFinished = { viewModel.finishTuning() }
        binding.frequencyView.onFrequencyChanged = { hz ->
            if (!viewModel.setFrequency(hz)) binding.frequencyView.setFrequency(viewModel.centerFreqHz.value)
            binding.spectrumView.setCenterFrequency(hardwareCenterFreqHz(hz))
            binding.spectrumView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(hz))
            binding.spectrumView.setDemodDialFrequency(hz)
            binding.waterfallView.setCenterFrequency(hardwareCenterFreqHz(hz))
            binding.waterfallView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(hz))
        }

        // Long-press → manual frequency entry dialog.
        binding.frequencyView.onLongPress = { currentHz ->
            showFrequencyEditDialog(currentHz)
        }

        // Step up/down buttons -- single tap steps once; hold accelerates.
        val bwHaptics=TuningHaptics(binding.tvTuningStep)
        binding.btnStepUp.setOnClickListener { changePlotBandwidth(1); bwHaptics.tick() }
        binding.btnStepDown.setOnClickListener { changePlotBandwidth(-1); bwHaptics.tick() }
        binding.tvTuningStep.apply {
            typeface=android.graphics.Typeface.create("sans-serif-condensed",android.graphics.Typeface.BOLD); textSize=10f
            setOnClickListener { viewModel.setIfBandwidth(0); updatePlotBandwidth() }
            HeldValueEditor.install(this) { showPlotValueEditor(false) }
        }
        binding.tvPlotZoom.apply {
            typeface=android.graphics.Typeface.create("sans-serif-condensed",android.graphics.Typeface.BOLD); textSize=10f
            HeldValueEditor.install(this) { showPlotValueEditor(true) }
        }
        binding.btnStepUp.contentDescription="增大 BW"
        binding.btnStepDown.contentDescription="减小 BW"
        updatePlotBandwidth()
    }

    /**
     * Shows a dialog letting the user type a frequency in MHz.
     * Accepts decimal input (e.g. "145.500" or "433.920").
     * On confirm the value is clamped to the RTL-SDR range and applied.
     */
    private fun showFrequencyEditDialog(currentHz: Long) {
        val editText = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "MHz (e.g. 145.500)"
            setText("%.6f".format(currentHz / 1_000_000.0).trimEnd('0').trimEnd('.'))
            textSize = 18f
            setPadding(48, 32, 48, 16)
            selectAll()
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Enter Frequency")
            .setMessage("Type frequency in MHz")
            .setView(editText)
            .setPositiveButton("Tune") { _, _ ->
                val mhz = editText.text.toString().toDoubleOrNull()
                if (mhz != null && mhz > 0) {
                    val hz = (mhz * 1_000_000).toLong()
                    if (!viewModel.setFrequency(hz)) return@setPositiveButton
                    binding.frequencyView.setFrequency(viewModel.centerFreqHz.value)
                    binding.spectrumView.setCenterFrequency(hardwareCenterFreqHz(hz))
                    binding.spectrumView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(hz))
                    binding.spectrumView.setDemodDialFrequency(hz)
                    binding.waterfallView.setCenterFrequency(hardwareCenterFreqHz(hz))
                    binding.waterfallView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(hz))
                    showSnackbar("Tuned to ${"%.3f".format(hz / 1e6)} MHz")
                } else {
                    showSnackbar("Invalid frequency")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
            .also { dialog ->
                // Auto-show keyboard
                editText.postDelayed({
                    editText.requestFocus()
                    val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    imm?.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }, 100)
            }
    }

    /**
     * Attaches both click and long-press-repeat behaviour to a frequency step button.
     *
     * Short tap  → one step (same as before).
     * Hold 400ms → starts repeating at 150 ms / step.
     * Hold 1.6 s → accelerates to 60 ms / step (10× effective speed).
     * Release    → stops immediately.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupFreqButton(button: View, isUp: Boolean) {
        val step: () -> Unit = {
            if (isUp) binding.frequencyView.stepUp() else binding.frequencyView.stepDown()
        }
        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    freqRepeatFired = false
                    freqRepeatJob?.cancel()
                    freqRepeatJob = lifecycleScope.launch {
                        delay(400L)               // initial hold threshold
                        var count = 0
                        while (isActive) {
                            freqRepeatFired = true
                            step()
                            count++
                            delay(if (count < 8) 150L else 60L)   // 150 ms → 60 ms after ~1.2 s
                        }
                    }
                    false   // pass through so onClick also fires on a short tap
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    freqRepeatJob?.cancel()
                    freqRepeatJob = null
                    false
                }
                else -> false
            }
        }
        // Click fires on every touch-up; suppress it when repeat already handled the stepping.
        button.setOnClickListener { if (!freqRepeatFired) step() }
    }

    private fun modeChipGroups(): List<android.widget.GridLayout> {
        if(!::pagerAdapter.isInitialized) return emptyList()
        val root=pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_MODE) ?: return emptyList()
        return listOfNotNull(root.findViewById(R.id.chipGroupDemod),root.findViewById(R.id.chipGroupDigital))
    }
    private fun setupDemodModeChips() {
        val modes = listOf(DemodMode.AM, DemodMode.NFM, DemodMode.WFM, DemodMode.WFM_STEREO,
            DemodMode.USB, DemodMode.LSB, DemodMode.CW, DemodMode.CWR, DemodMode.DSB,
            DemodMode.APRS, DemodMode.ACARS, DemodMode.ADSB, DemodMode.RAW,
            DemodMode.DMR, DemodMode.YSF, DemodMode.DSTAR, DemodMode.DIG)
        val groups=modeChipGroups()
        if(groups.size!=2) return
        pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_MODE)
            ?.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchModeStationMemory)?.apply {
                setOnCheckedChangeListener(null)
                isChecked = viewModel.modeStationMemory.value
                setOnCheckedChangeListener { _, enabled ->
                    if (enabled == viewModel.modeStationMemory.value) return@setOnCheckedChangeListener
                    regionalReception.cancelPendingAutoTune()
                    contentController?.freezeStationMemory()
                    viewModel.setModeStationMemory(enabled)
                    contentController?.stationMemoryChanged(enabled)
                    syncFrequencyDisplay()
                }
            }
        groups.forEach { it.removeAllViews() }
        val analog=setOf(DemodMode.AM,DemodMode.NFM,DemodMode.WFM,DemodMode.WFM_STEREO,
            DemodMode.USB,DemodMode.LSB,DemodMode.CW,DemodMode.CWR,DemodMode.DSB)
        for (mode in modes) {
            val group=groups[if(mode in analog) 0 else 1]
            val cell=group.childCount
            group.addView(ModeChoiceUi.button(this).apply {
            id=View.generateViewId()
            text = if(mode==DemodMode.WFM_STEREO) "WFM 立体声" else mode.displayName
            tag=mode; isCheckable=false; isSelected=mode==viewModel.demodMode.value
            isEnabled=viewModel.capabilities.value.supportsMode(mode)
            layoutParams=ModeChoiceUi.cell(this@MainActivity,cell)
            setOnClickListener {
                contentController?.explicitRfMode(mode)
                val hasHistory=viewModel.rememberedModeFrequency(mode)!=null
                viewModel.setDemodMode(mode)
                selectDemodChip(viewModel.demodMode.value)
                if (mode == DemodMode.ADSB) {
                    if(viewModel.modeStationMemory.value && !hasHistory) viewModel.setFrequency(1_090_000_000)
                    updateAdsbTabVisibility(mode)
                }
                if(hasHistory || !viewModel.modeStationMemory.value) regionalReception.cancelPendingAutoTune()
                else regionalReception.onModeSelected(mode,viewModel.centerFreqHz.value)
                syncFrequencyDisplay()
            }
        })
        }
        groups.forEach { group ->
            while(group.childCount%3!=0) {
                val cell=group.childCount
                group.addView(View(this).apply {
                    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams=ModeChoiceUi.cell(this@MainActivity,cell)
                })
            }
        }
    }

    private fun setupControlsPanel() {
        setupTabsDrawer()
        // ViewPager2 inflates tab views on first layout pass.
        // Defer all ctrlViews.* wiring until the pager has inflated its pages.
        binding.controlViewPager.post { setupControlsPanelDeferred() }
    }

    @Suppress("LongMethod")
    private fun setupControlsPanelDeferred() {
        if(isFinishing || isDestroyed) return
        if((0..4).any { pagerAdapter.getViewAt(it)==null }) {
            binding.controlViewPager.postDelayed({ setupControlsPanelDeferred() },50)
            return
        }
        setupGraphControls()
        // Gain slider -- range and label depend on the active IQ source.
        // For USB, the table is RtlSdrDevice.GAIN_TABLE_DB_TENTHS (29 steps).
        // For TCP (rtl_tcp_andro), the server reports its own gain count in the
        // magic header, and the RtlTcpSource resolves the matching table.
        // We query the live source's getGainCount() so the slider always matches
        // the actual hardware regardless of source type.
        val gainCount = (sdrService?.source?.getGainCount()
            ?: viewModel.getService()?.source?.getGainCount()
            ?: RtlSdrDevice.GAIN_TABLE_DB_TENTHS.size)
            .coerceAtLeast(2)

        ctrlViews.sliderGain!!.apply {
            valueFrom = 0f
            valueTo = (gainCount - 1).toFloat()
            stepSize = 1f
            val savedIndex = viewModel.gainIndex.value.toFloat().coerceIn(valueFrom, valueTo)
            value = savedIndex
            syncGainLabel(savedIndex.toInt())
            setLabelFormatter { v ->
                val db = gainDbAtIndex(v.toInt())
                "${"%.1f".format(db)} dB"
            }
            addOnChangeListener { _, v, fromUser ->
                if (fromUser) {
                    viewModel.setGain(v.toInt())
                    syncGainLabel(v.toInt())
                }
            }
        }

        // Tuner AGC switch -- controls R82xx LNA/Mixer AGC (disables manual gain slider).
        ctrlViews.switchTunerAgc!!.setOnCheckedChangeListener(null)
        ctrlViews.switchTunerAgc!!.isChecked = viewModel.tunerAgcEnabled.value
        ctrlViews.sliderGain!!.isEnabled = !viewModel.tunerAgcEnabled.value
        ctrlViews.switchTunerAgc!!.setOnCheckedChangeListener { _, checked ->
            viewModel.setTunerAgc(checked)
            ctrlViews.sliderGain!!.isEnabled = !checked
        }

        // Hardware AGC switch -- controls RTL2832U digital IF AGC (independent of tuner AGC).
        ctrlViews.switchHardwareAgc!!.setOnCheckedChangeListener(null)
        ctrlViews.switchHardwareAgc!!.isChecked = viewModel.hardwareAgcEnabled.value
        ctrlViews.switchHardwareAgc!!.setOnCheckedChangeListener { _, checked ->
            viewModel.setHardwareAgc(checked)
        }

        // Squelch slider
        ctrlViews.sliderSquelch!!.apply {
            valueFrom = -120f; valueTo = 0f; value = viewModel.squelch.value.coerceIn(-120f, 0f)
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    viewModel.setSquelch(value)
                    ctrlViews.tvSquelchValue!!.text = if(value<=-120f) "关闭" else "${value.toInt()} dB"
                    binding.spectrumView.setSquelch(value)
                }
            }
        }

        val rfPage=pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_RF)
        rfPage?.findViewById<Button>(R.id.btnMeasureSquelch)?.let { measure ->
            SquelchMeasurementControls(this,viewModel,measure,rfPage.findViewById(R.id.tvSqlChannelLevel)) { db ->
                viewModel.setSquelch(db)
                ctrlViews.sliderSquelch?.value=db
                ctrlViews.tvSquelchValue?.text="${db.toInt()} dBFS"
                binding.spectrumView.setSquelch(db)
            }
        }

        // Volume slider
        ctrlViews.sliderVolume!!.apply {
            valueFrom = 0f; valueTo = 2f; value = 1f
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    viewModel.setVolume(value)
                    ctrlViews.tvVolumeValue!!.text = "${(value * 100).toInt()}%"
                }
            }
        }

        // Bias tee switch
        ctrlViews.switchBiasTee!!.setOnCheckedChangeListener { _, checked ->
            viewModel.setBiasTee(checked)
        }

        // Noise Blanker switch
        syncNoiseProcessingControls()

        // Noise Reducer switch
        pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_RF)?.findViewById<android.widget.FrameLayout>(R.id.listeningAudioControls)?.let { host ->
            if(host.childCount==0) {
                val controls=ListeningAudioControls(this,viewModel.listeningAudio.value) { viewModel.setListeningAudio(it) }
                host.addView(controls)
                lifecycleScope.launch { viewModel.listeningAudio.collect { controls.render(it) } }
                lifecycleScope.launch { viewModel.demodMode.collect { controls.setMode(it) } }
                lifecycleScope.launch {
                    combine(viewModel.demodMode,viewModel.noiseBlankerEnabled,viewModel.noiseReducerEnabled,
                        viewModel.iqNbThreshold) { _,_,_,_ -> Unit }.collect { syncNoiseProcessingControls() }
                }
            }
        }

        // ── IF Bandwidth / Protocol Filter slider ─────────────────────────────
        // The slider is an *index* into BW_STEPS -- a fixed list of standard radio
        // bandwidths covering CW (100 Hz) through AM/NFM/WFM (200 kHz).
        //
        // CW filters follow IARU/contest-receiver conventions:
        //   100, 150, 200, 250, 300, 400, 500 Hz (narrow CW)
        //   600, 800 Hz (wide CW / RTTY)
        // SSB / data filters follow ITU-R M.1131 and standard receiver practice:
        //   1.0, 1.2, 1.5, 1.8, 2.0, 2.4, 2.7, 3.0, 3.3, 3.6 kHz
        // AM / NFM / wider protocols:
        //   4, 5, 6, 8, 10, 12.5, 15, 25, 50, 100, 200 kHz
        //
        // Each mode exposes only the slice of BW_STEPS that makes sense for it.
        // The slider valueFrom/valueTo are updated dynamically on every mode change.
        //
        // "Auto" means the mode's defaultBwHz is in use (slider snaps to that index
        // but the stored value in the ViewModel is 0 / sentinel).
        // Initialise slider for the current mode.
        val savedIfBw    = viewModel.ifBandwidthHz.value
        val initialMode  = viewModel.demodMode.value
        val initialBw    = if (savedIfBw > 0) savedIfBw
                           else initialMode.defaultBwHz.coerceAtLeast(DspEngine.MIN_IF_BANDWIDTH_HZ)
        // Set stepSize once; valueFrom/valueTo are set inside applyBwRange.
        ctrlViews.sliderIfBandwidth!!.stepSize = 1f
        ctrlViews.sliderIfBandwidth!!.setLabelFormatter { index ->
            formatIfBw(BW_STEPS[index.toInt().coerceIn(BW_STEPS.indices)], false)
        }
        pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_TUNE)?.findViewById<Button>(R.id.btnAutoBandwidth)?.setOnClickListener {
            viewModel.setIfBandwidth(0)
        }
        for ((button, direction) in listOf(R.id.btnBandwidthNarrow to -1, R.id.btnBandwidthWide to 1)) {
            pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_TUNE)?.findViewById<Button>(button)?.setOnClickListener {
                val mode = viewModel.demodMode.value
                val current = viewModel.ifBandwidthHz.value.takeIf { it > 0 } ?: mode.defaultBwHz
                val range = bwRangeFor(mode)
                val index = (nearestBwIdx(current, range) + direction).coerceIn(range.first, range.second)
                viewModel.setIfBandwidth(BW_STEPS[index])
            }
        }
        applyBwRange(initialMode, initialBw)
        ctrlViews.tvIfBandwidthValue!!.text = formatIfBw(initialBw, savedIfBw == 0)

        ctrlViews.sliderIfBandwidth!!.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val hz   = BW_STEPS[value.toInt().coerceIn(0, BW_STEPS.size - 1)]
                val mode = viewModel.demodMode.value
                val isDefault = false
                viewModel.setIfBandwidth(hz)
                ctrlViews.tvIfBandwidthValue!!.text = formatIfBw(hz, false)
                // Update spectrum highlight immediately for real-time visual feedback.
                val highlightHz = channelHighlightHz(mode, if (isDefault) 0 else hz)
                binding.spectrumView.setDemodChannelBandwidth(highlightHz, channelHighlightSideband(mode))
                val pfLabel = if (isDefault)
                    "Auto (${formatIfBw(mode.defaultBwHz.coerceAtLeast(DspEngine.MIN_IF_BANDWIDTH_HZ), false)}, mode default)"
                else
                    formatIfBw(hz, false)
                com.radiosport.ninegradio.debug.DebugBus.setGlobal(
                    com.radiosport.ninegradio.debug.DebugBus.KEY_PROTOCOL_FILTER, pfLabel)
            }
        }

        // Sync slider range AND position whenever mode or bandwidth changes.
        lifecycleScope.launch {
            viewModel.ifBandwidthHz.collect { hz ->
                val mode      = viewModel.demodMode.value
                val defaultBw = mode.defaultBwHz.coerceAtLeast(DspEngine.MIN_IF_BANDWIDTH_HZ)
                val effective = if (hz > 0) hz else defaultBw
                val isAuto    = hz == 0
                applyBwRange(mode, effective)
                ctrlViews.tvIfBandwidthValue!!.text = formatIfBw(effective, isAuto)
                binding.spectrumView.setDemodChannelBandwidth(channelHighlightHz(mode, hz), channelHighlightSideband(mode))
                val pfLabel = if (isAuto)
                    "Auto (${formatIfBw(defaultBw, false)}, mode default)"
                else
                    formatIfBw(effective, false)
                com.radiosport.ninegradio.debug.DebugBus.setGlobal(
                    com.radiosport.ninegradio.debug.DebugBus.KEY_PROTOCOL_FILTER, pfLabel)
            }
        }

        // Sample rate selector -- horizontal scrollable row of chip buttons.
        // NumberPicker was invisible (black-on-black) because it ignores android:textColor
        // in XML and inherits the window background for its internal dividers.
        // Chip buttons give full colour control without any theme overrides.
        val dp = resources.displayMetrics.density
        setupSampleRateChips()


        // ── Audio sink rate chip row ──────────────────────────────────────────
        val audioSinkRates  = DspEngine.AUDIO_SINK_RATES
        val audioSinkLabels = listOf("48k", "44.1k", "32k", "24k", "22k", "16k")
        val savedSinkIdx = audioSinkRates.indexOfFirst { it == viewModel.audioSinkRate.value }
            .let { if (it >= 0) it else 0 }

        audioSinkLabelsCache = audioSinkLabels

        ctrlViews.layoutAudioSinkRateChips!!.removeAllViews()
        audioSinkLabels.forEachIndexed { idx, label ->
            val btn = android.widget.TextView(this).apply {
                text = label
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins((3 * dp).toInt(), 0, (3 * dp).toInt(), 0) }
                setOnClickListener {
                    val rate = audioSinkRates[idx]
                    ctrlViews.tvAudioSinkRateLabel!!.text = audioSinkLabels[idx]
                    viewModel.setAudioSinkRate(rate)
                    updateAudioSinkChips(idx)
                    (ctrlViews.layoutAudioSinkRateChips!!.parent
                            as? android.widget.HorizontalScrollView)
                        ?.smoothScrollTo(this.left - (40 * dp).toInt(), 0)
                }
            }
            ctrlViews.layoutAudioSinkRateChips!!.addView(btn)
        }
        ctrlViews.tvAudioSinkRateLabel!!.text = audioSinkLabels[savedSinkIdx]
        updateAudioSinkChips(savedSinkIdx)
        ctrlViews.layoutAudioSinkRateChips!!.post {
            val sel = ctrlViews.layoutAudioSinkRateChips!!.getChildAt(savedSinkIdx)
            (ctrlViews.layoutAudioSinkRateChips!!.parent as? android.widget.HorizontalScrollView)
                ?.scrollTo((sel?.left ?: 0) - (40 * dp).toInt(), 0)
        }

        // Direct sampling spinner
        ctrlViews.spinnerDirectSampling!!.apply {
            adapter = ArrayAdapter(context,
                R.layout.compact_spinner_item,
                listOf("Off (Tuner)", "I-Branch (HF)", "Q-Branch (HF - V4)"))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    viewModel.setDirectSampling(pos)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        // PPM correction slider (−200 … +200 ppm, step 1)
        val savedPpm = viewModel.ppm.value.toFloat().coerceIn(-50f, 50f)
        ctrlViews.sliderPpm!!.value = savedPpm
        ctrlViews.tvPpmValue!!.text = "${savedPpm.toInt()} ppm"
        ctrlViews.sliderPpm!!.setLabelFormatter { v -> "${v.toInt()} ppm" }
        ctrlViews.sliderPpm!!.addOnChangeListener { _, v, fromUser ->
            val ppm = v.toInt()
            ctrlViews.tvPpmValue!!.text = "$ppm ppm"
            if (fromUser) {
                viewModel.setPpm(ppm)
                binding.spectrumView.setPpmCorrection(ppm)
                binding.waterfallView.setPpmCorrection(ppm)
            }
        }

        val recordingPrefs=androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val stopSeconds=listOf("0","10","30","60","300","600","1800","3600")
        ctrlViews.spinnerRecordStop?.apply {
            adapter=ArrayAdapter(context,R.layout.compact_spinner_item,listOf("不限时","10 秒","30 秒","1 分钟","5 分钟","10 分钟","30 分钟","1 小时"))
            setSelection(stopSeconds.indexOf(recordingPrefs.getString("pref_record_stop_seconds","0")).coerceAtLeast(0),false)
            onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long) { recordingPrefs.edit().putString("pref_record_stop_seconds",stopSeconds[pos]).apply() }
                override fun onNothingSelected(p:AdapterView<*>?) {}
            }
        }
        pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_RECORDING)?.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchSquelchRecord)?.apply {
            isChecked=recordingPrefs.getBoolean("pref_record_squelch",false)
            setOnCheckedChangeListener { _,enabled -> sdrService?.setRecordingSquelch(enabled) ?: recordingPrefs.edit().putBoolean("pref_record_squelch",enabled).apply().let { } }
        }

        pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_RECORDING)?.findViewById<android.widget.FrameLayout>(R.id.recordingPlayerHost)?.let { host ->
            recordingPlayer=RecordingPlayerControls(this,host)
            pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_RECORDING)?.findViewById<Button>(R.id.recordingPlaybackExpand)?.apply {
                text=if(panelFullscreen) "还原" else "展开"
                setOnClickListener { setPanelFullscreen(!panelFullscreen) }
            }
        }
        // IQ Record button
        ctrlViews.btnRecordIq!!.setOnClickListener {
            if (viewModel.dspStats.value.isRecordingIq) {
                viewModel.stopIqRecording()
                ctrlViews.btnRecordIq!!.text = "录制 IQ"
                styleRecordingAction(ctrlViews.btnRecordIq,false)
            } else {
                val path = getIqFilePath()
                viewModel.startIqRecording(path)
                ctrlViews.btnRecordIq!!.text = "停止 IQ"
                styleRecordingAction(ctrlViews.btnRecordIq,true)
                showSnackbar("正在录制 IQ")
            }
        }

        // Audio Record button
        ctrlViews.btnRecordAudio!!.setOnClickListener {
            if (viewModel.dspStats.value.isRecordingAudio) {
                viewModel.stopAudioRecording()
                ctrlViews.btnRecordAudio!!.text = "录制声音"
                styleRecordingAction(ctrlViews.btnRecordAudio,false)
            } else {
                val profile=com.radiosport.ninegradio.recording.AudioRecordingProfile.read(this)
                val path = getAudioFilePath(profile.format.extension)
                viewModel.startAudioRecording(path,profile)
                ctrlViews.btnRecordAudio!!.text = "停止录音"
                styleRecordingAction(ctrlViews.btnRecordAudio,true)
                showSnackbar("正在录制声音")
            }
        }

        // Recordings list, pinned to the bottom of the Rec tab below the
        // controls above. Reuses the same RecordingAdapter (play/share/delete)
        // as the standalone RecordingActivity. The container is GONE until
        // viewModel.recordings actually has entries, and flips back to GONE
        // if the list is ever emptied (e.g. all recordings deleted).
        ctrlViews.recyclerRecordingsTab?.layoutManager = LinearLayoutManager(this)
        recordingsTabAdapter = RecordingAdapter(
            onPlay = { meta -> playRecordingFromTab(meta) },
            onShare = { meta -> shareRecordingFromTab(meta) },
            onDelete = { meta ->
                if(recordingPlayer?.controller?.currentMediaItem?.mediaId==meta.id.toString()) recordingPlayer?.stopPlayback()
                File(meta.filePath).delete()
                lifecycleScope.launch {
                    (application as com.radiosport.ninegradio.RtlSdrApplication)
                        .database.recordingMetaDao().delete(meta)
                }
            }
        )
        ctrlViews.recyclerRecordingsTab?.adapter = recordingsTabAdapter
        lifecycleScope.launch {
            viewModel.recordings.collectLatest { recs ->
                recordingsTabAdapter?.submitList(recs)
                ctrlViews.containerRecordingsTabList?.visibility =
                    if (recs.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }

        // FFT size spinner -- sync both directions between drawer and Settings.
        // pref_fft_size (default SharedPreferences, used by the Settings ListPreference)
        // and the ViewModel's "fftSize" key (rtlsdr_prefs) must always agree.
        // On first setup we write whichever source has a non-default value into
        // the other so the two UIs are consistent from the very first open.
        val fftSizes = listOf(256, 512, 1024, 2048, 4096, 8192)
        val defaultPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        // If the Settings pref has been set previously, honour it; otherwise seed
        // it from the ViewModel's persisted value so Settings shows the right selection.
        val settingsPrefSize = defaultPrefs.getString("pref_fft_size", null)?.toIntOrNull()
        if (settingsPrefSize == null) {
            // First run or Settings never opened -- seed pref_fft_size from ViewModel
            defaultPrefs.edit().putString("pref_fft_size", viewModel.fftSize.value.toString()).apply()
        } else if (settingsPrefSize != viewModel.fftSize.value) {
            // Settings was changed while app was killed -- propagate to ViewModel
            viewModel.setFftSize(settingsPrefSize)
        }
        ctrlViews.spinnerFftSize!!.apply {
            adapter = ArrayAdapter(context, R.layout.compact_spinner_item,
                fftSizes.map { it.toString() })
            // Use the ViewModel's persisted FFT size (defaults to 256 on first run)
            val savedFftIdx = fftSizes.indexOf(viewModel.fftSize.value).takeIf { it >= 0 } ?: 0
            setSelection(savedFftIdx, false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val size = fftSizes[pos]
                    viewModel.setFftSize(size)
                    // Keep the Settings page pref_fft_size in sync so the two entry
                    // points (drawer spinner and Settings list) always show the same value.
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                        .edit().putString("pref_fft_size", size.toString()).apply()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        // Decimation spinner -- narrows the FFT displayed bandwidth.
        // Values: Off=1, ÷2, ÷4, ÷8, ÷16, ÷32, ÷64.
        // The effective sample rate passed to SpectrumView/WaterfallView becomes
        // deviceSampleRate / decimationFactor, showing a proportionally narrower band.
        //
        // Source of truth: viewModel.decimation (persisted in "rtlsdr_prefs" / "fftDecimation").
        // The stale "pref_decimation" String in defaultSharedPreferences is NOT consulted here
        // to avoid overwriting the ViewModel's correctly-restored value on every tab open.
        val decimationFactors = listOf(1, 2, 4, 8, 16, 32, 64)
        val decimationLabels  = listOf("关闭", "÷2 (½ BW)", "÷4 (¼ BW)", "÷8 (⅛ BW)", "÷16", "÷32", "÷64")
        ctrlViews.spinnerDecimation!!.apply {
            adapter = ArrayAdapter(context, R.layout.compact_spinner_item, decimationLabels)
            // Set the saved selection BEFORE attaching the listener so the programmatic
            // setSelection() call does not trigger onItemSelected and reset the ViewModel.
            val savedDecimIdx = decimationFactors.indexOf(viewModel.decimation.value).takeIf { it >= 0 } ?: 0
            setSelection(savedDecimIdx, false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val factor = decimationFactors[pos]
                    // Guard: ignore spurious callback if the position hasn't actually changed
                    if (factor == viewModel.decimation.value) return
                    viewModel.setDecimation(factor)
                    // Immediately update views with the new effective sample rate
                    val effectiveRate = viewModel.sampleRate.value / factor.coerceAtLeast(1)
                    binding.spectrumView.setSampleRate(effectiveRate)
                    binding.waterfallView.setSampleRate(effectiveRate)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        // Waterfall palette spinner — human-readable labels aligned to enum ordinals
        val paletteLabels = listOf(
            "彩虹", "热度", "灰度", "蓝白", "紫黄",
            "翠绿", "烈焰", "熔岩", "高对比", "日光", "夜视"
        )
        ctrlViews.spinnerPalette!!.apply {
            adapter = ArrayAdapter(context, R.layout.compact_spinner_item, paletteLabels)
            setSelection(viewModel.waterfallPalette.value.ordinal, false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    viewModel.setWaterfallPalette(WaterfallView.Companion.Palette.values()[pos])
                    binding.waterfallView.setPalette(WaterfallView.Companion.Palette.values()[pos])
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        // Spectrum theme spinner — all 10 themes
        val themes = listOf(
            "UI", "Classic", "Futuristic", "Amber", "Grayscale", "Purple",
            "Solar", "Neon", "Ice", "Midnight", "Sakura"
        )
        ctrlViews.spinnerTheme!!.apply {
            adapter = ArrayAdapter(context, R.layout.compact_spinner_item,
                listOf("跟随界面","经典","科技蓝","琥珀","灰度","紫色","日光","霓虹","冰蓝","午夜","樱花"))
            setSelection(themes.indexOf(viewModel.spectrumTheme.value).coerceAtLeast(0), false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    viewModel.setSpectrumTheme(themes[pos])
                    binding.spectrumView.setTheme(themes[pos])
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        // Peak Hold switch
        val displayPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val savedPeakHold = displayPrefs.getBoolean("pref_peak_hold", true)
        ctrlViews.switchPeakHold!!.isChecked = savedPeakHold
        ctrlViews.switchPeakHold!!.setOnCheckedChangeListener { _, checked ->
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean("pref_peak_hold", checked).apply()
            binding.spectrumView.setShowPeakHold(checked)
            val fft = sdrService?.dspEngine?.fftEngine
            if (checked) {
                fft?.resetPeakHold()
                fft?.showPeakHold = true
            } else {
                fft?.showPeakHold = false
                fft?.resetPeakHold()
            }
        }

        // ── Spectrum advanced controls ─────────────────────────────────────────

        // Fill Opacity slider
        val savedFillOpacity = displayPrefs.getInt("pref_fill_opacity", 73) / 100f
        ctrlViews.sliderFillOpacity?.value = savedFillOpacity
        ctrlViews.tvFillOpacityValue?.text = "${(savedFillOpacity * 100).toInt()}%"
        ctrlViews.sliderFillOpacity?.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            ctrlViews.tvFillOpacityValue?.text = "${(value * 100).toInt()}%"
            binding.spectrumView.setFillOpacity(value)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putInt("pref_fill_opacity", (value * 100).toInt()).apply()
        }

        // Spectrum Floor slider (−200..−10 dBFS, step 1)
        val savedFloorRaw = displayPrefs.getInt("pref_db_min", 120)   // stored as positive
        val savedFloor = -(savedFloorRaw.toFloat()).coerceIn(10f, 200f)
        ctrlViews.sliderSpecFloor?.value = savedFloor
        ctrlViews.tvSpecFloorValue?.text = savedFloor.toInt().toString()
        ctrlViews.sliderSpecFloor?.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            ctrlViews.tvSpecFloorValue?.text = value.toInt().toString()
            val ceiling = ctrlViews.sliderSpecCeiling?.value ?: 0f
            binding.spectrumView.setDbRange(value, ceiling)
            binding.waterfallView.setDynamicRange(value, ceiling)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putInt("pref_db_min", (value * -1f).toInt()).apply()
        }

        // Spectrum Ceiling slider (−100..+50 dBFS, step 1)
        val savedCeilingRaw = displayPrefs.getInt("pref_db_max", 0)
        val savedCeiling = savedCeilingRaw.toFloat().coerceIn(-100f, 50f)
        ctrlViews.sliderSpecCeiling?.value = savedCeiling
        ctrlViews.tvSpecCeilingValue?.text = savedCeiling.toInt().toString()
        ctrlViews.sliderSpecCeiling?.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            ctrlViews.tvSpecCeilingValue?.text = value.toInt().toString()
            val floor = ctrlViews.sliderSpecFloor?.value ?: -120f
            binding.spectrumView.setDbRange(floor, value)
            binding.waterfallView.setDynamicRange(floor, value)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putInt("pref_db_max", value.toInt()).apply()
        }

        // Auto dB Range switch
        val savedAutoRange = displayPrefs.getBoolean("pref_auto_range", false)
        ctrlViews.switchAutoRange?.isChecked = savedAutoRange
        ctrlViews.switchAutoRange?.setOnCheckedChangeListener { _, checked ->
            binding.spectrumView.setAutoRange(checked)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean("pref_auto_range", checked).apply()
        }

        // Reset dB Range button — restores nominal −120..0 dBFS and turns Auto off
        ctrlViews.btnResetDbRange?.setOnClickListener {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            prefs.edit()
                .putInt("pref_db_min", 120)   // stored as positive; loader negates it → −120 dBFS
                .putInt("pref_db_max", 0)
                .putBoolean("pref_auto_range", false)
                .apply()
            binding.spectrumView.setAutoRange(false)
            binding.spectrumView.setDbRange(-120f, 0f)
            binding.waterfallView.setDynamicRange(-120f, 0f)
            ctrlViews.switchAutoRange?.isChecked = false
            ctrlViews.sliderSpecFloor?.value = -120f
            ctrlViews.tvSpecFloorValue?.text = "-120"
            ctrlViews.sliderSpecCeiling?.value = 0f
            ctrlViews.tvSpecCeilingValue?.text = "0"
        }

        // Noise Floor switch
        val savedNoiseFloor = displayPrefs.getBoolean("pref_show_noise_floor", true)
        ctrlViews.switchNoiseFloor?.isChecked = savedNoiseFloor
        ctrlViews.switchNoiseFloor?.setOnCheckedChangeListener { _, checked ->
            binding.spectrumView.setShowNoiseFloor(checked)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean("pref_show_noise_floor", checked).apply()
        }

        // Peak Decay slider
        val savedPeakDecay = displayPrefs.getInt("pref_peak_decay", 2).toFloat()
        ctrlViews.sliderPeakDecay?.value = savedPeakDecay.coerceIn(1f, 20f)
        ctrlViews.tvPeakDecayValue?.text = savedPeakDecay.toInt().toString()
        ctrlViews.sliderPeakDecay?.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            ctrlViews.tvPeakDecayValue?.text = value.toInt().toString()
            sdrService?.dspEngine?.fftEngine?.peakDecayRate = value * 0.001f
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putInt("pref_peak_decay", value.toInt()).apply()
        }

        // Peak Annotations switch
        val savedAnnotations = displayPrefs.getBoolean("pref_show_peak_annotations", true)
        ctrlViews.switchPeakAnnotations?.isChecked = savedAnnotations
        ctrlViews.switchPeakAnnotations?.setOnCheckedChangeListener { _, checked ->
            binding.spectrumView.setShowPeakAnnotations(checked)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean("pref_show_peak_annotations", checked).apply()
        }

        // Touch Crosshair switch
        val savedCrosshair = displayPrefs.getBoolean("pref_crosshair_enabled", true)
        ctrlViews.switchCrosshair?.isChecked = savedCrosshair
        ctrlViews.switchCrosshair?.setOnCheckedChangeListener { _, checked ->
            binding.spectrumView.setCrosshairEnabled(checked)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean("pref_crosshair_enabled", checked).apply()
        }

        // ── Waterfall advanced controls ────────────────────────────────────────

        // Brightness slider (range −0.5..+0.5, stored as 0..100 centred on 50)
        val savedBrightness = (displayPrefs.getInt("pref_waterfall_brightness", 50) - 50) / 100f
        ctrlViews.sliderWfBrightness?.value = savedBrightness.coerceIn(-0.5f, 0.5f)
        ctrlViews.tvWfBrightnessValue?.text = "%+.2f".format(savedBrightness)
        ctrlViews.sliderWfBrightness?.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            ctrlViews.tvWfBrightnessValue?.text = "%+.2f".format(value)
            binding.waterfallView.setBrightness(value)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putInt("pref_waterfall_brightness", ((value + 0.5f) * 100).toInt()).apply()
        }

        // Contrast slider (range 0.2..3.0, stored as 5..150 centred on 50=1.0×)
        val savedContrast = displayPrefs.getInt("pref_waterfall_contrast", 50) / 50f
        ctrlViews.sliderWfContrast?.value = savedContrast.coerceIn(0.2f, 3.0f)
        ctrlViews.tvWfContrastValue?.text = "%.1f×".format(savedContrast)
        ctrlViews.sliderWfContrast?.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            ctrlViews.tvWfContrastValue?.text = "%.1f×".format(value)
            binding.waterfallView.setContrast(value)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putInt("pref_waterfall_contrast", (value * 50).toInt()).apply()
        }

        // Auto-Stretch switch
        val savedAutoStretch = displayPrefs.getBoolean("pref_waterfall_auto_stretch", false)
        ctrlViews.switchAutoStretch?.isChecked = savedAutoStretch
        ctrlViews.switchAutoStretch?.setOnCheckedChangeListener { _, checked ->
            binding.waterfallView.setAutoStretch(checked)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean("pref_waterfall_auto_stretch", checked).apply()
        }

        // Waterfall Pause switch
        ctrlViews.switchWfPause?.isChecked = false
        ctrlViews.switchWfPause?.setOnCheckedChangeListener { _, checked ->
            binding.waterfallView.setPaused(checked)
        }

        // Time Ruler switch
        val savedTimeRuler = displayPrefs.getBoolean("pref_waterfall_show_timestamp", true)
        ctrlViews.switchTimeRuler?.isChecked = savedTimeRuler
        ctrlViews.switchTimeRuler?.setOnCheckedChangeListener { _, checked ->
            binding.waterfallView.setShowTimestamp(checked)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean("pref_waterfall_show_timestamp", checked).apply()
        }

        // Centre Marker switch
        val savedCentreMarker = displayPrefs.getBoolean("pref_waterfall_show_tuner_marker", true)
        ctrlViews.switchCentreMarker?.isChecked = savedCentreMarker
        ctrlViews.switchCentreMarker?.setOnCheckedChangeListener { _, checked ->
            binding.waterfallView.setShowTunerMarker(checked)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean("pref_waterfall_show_tuner_marker", checked).apply()
        }

        // ── FFT advanced controls ──────────────────────────────────────────────

        // Frame Averaging spinner
        val frameAvgLabels = listOf("关闭", "×2 (−3dB)", "×4 (−6dB)", "×8 (−9dB)", "×16 (−12dB)", "×32 (−15dB)")
        val frameAvgValues = listOf(1, 2, 4, 8, 16, 32)
        ctrlViews.spinnerFrameAvg?.apply {
            adapter = ArrayAdapter(context, R.layout.compact_spinner_item, frameAvgLabels)
            val savedAvg = displayPrefs.getString("pref_frame_averaging", "1")?.toIntOrNull() ?: 1
            setSelection(frameAvgValues.indexOf(savedAvg).takeIf { it >= 0 } ?: 0, false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val count = frameAvgValues[pos]
                    sdrService?.dspEngine?.fftEngine?.frameAveragingCount = count
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                        .edit().putString("pref_frame_averaging", count.toString()).apply()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        applyControlColors()
    }   // end setupDisplayTab

    private fun applyControlColors() {
        for(tab in 0 until ControlsPagerAdapter.TAB_COUNT) {
            val group=when(tab) {
                ControlsPagerAdapter.TAB_DISPLAY -> ControlColors.Group.DISPLAY
                ControlsPagerAdapter.TAB_SCAN -> ControlColors.Group.SCAN
                ControlsPagerAdapter.TAB_RECORDING -> ControlColors.Group.RECORD
                else -> ControlColors.Group.RADIO
            }
            pagerAdapter.getViewAt(tab)?.let { ControlColors.apply(it,group) }
        }
        pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_RF)?.let { page ->
            for(id in listOf(R.id.sliderVolume,R.id.sliderSquelch,R.id.sliderNrStrength,
                R.id.switchNoiseReducer,R.id.listeningAudioControls))
                page.findViewById<View>(id)?.let { ControlColors.apply(it,ControlColors.Group.AUDIO) }
        }
    }

    private fun styleRateChoice(button:android.widget.TextView,selected:Boolean) {
        button.setTextColor(UiThemes.color(this,if(selected) R.attr.radioMain else R.attr.radioAccent))
        button.background=android.graphics.drawable.GradientDrawable().apply {
            cornerRadius=resources.getDimension(R.dimen.radio_corner_button)
            setColor(UiThemes.color(this@MainActivity,if(selected) R.attr.radioAccent else R.attr.radioSurface))
            if(!selected) setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1),UiThemes.color(this@MainActivity,R.attr.radioOutline))
        }
        button.isSelected=selected
    }
    private fun updateSampleRateChips(selectedIdx:Int) {
        val row=ctrlViews.layoutSampleRateChips ?: return
        for(i in 0 until row.childCount) (row.getChildAt(i) as? android.widget.TextView)?.let { styleRateChoice(it,i==selectedIdx) }
    }
    private fun updateAudioSinkChips(selectedIdx:Int) {
        val row=ctrlViews.layoutAudioSinkRateChips ?: return
        for(i in 0 until row.childCount) (row.getChildAt(i) as? android.widget.TextView)?.let { styleRateChoice(it,i==selectedIdx) }
    }

    /**
     * Re-syncs every control on tab [position] from the ViewModel's authoritative
     * state. Called from the TabLayout's onTabSelected listener every time a tab
     * is brought into view -- previously, controls (sliders, switches, spinners,
     * chip rows) were only ever pushed to the UI once at first setup or when the
     * control itself was moved, so a value changed on another tab (or by mode
     * switching, reconnect, etc.) kept showing the old position until the user
     * touched it directly. This guarantees every drawer tab shows fresh state
     * the instant it's selected, with no functional side effects (listeners are
     * detached before values are set and reattached after, so re-syncing never
     * fires a duplicate "user changed this" event back into the ViewModel).
     */
    private fun syncTabControls(position: Int) {
        if (!::ctrlViews.isInitialized) return
        val v = viewModel
        when (position) {
            ControlsPagerAdapter.TAB_MODE -> {
                selectDemodChip(v.demodMode.value)
            }

            ControlsPagerAdapter.TAB_TUNE -> {
                val dial = ctrlViews.tuningDial
                dial?.currentFreqHz = v.centerFreqHz.value
                dial?.demodMode     = v.demodMode.value.name

                ctrlViews.switchSnap?.setOnCheckedChangeListener(null)
                ctrlViews.switchSnap?.isChecked = snapToChannel
                ctrlViews.switchSnap?.setOnCheckedChangeListener { _, checked -> snapToChannel = checked }

                ctrlViews.switchFineTune?.setOnCheckedChangeListener(null)
                ctrlViews.switchFineTune?.isChecked = dial?.fineMode ?: false
                ctrlViews.switchFineTune?.setOnCheckedChangeListener { _, checked -> dial?.fineMode = checked }

                // Recommended step depends on current frequency/mode -- rebuild so the
                // checked chip reflects where the dial actually is right now.
                setupStepChips()
            }

            ControlsPagerAdapter.TAB_RF -> {
                val svc = viewModel.getService() ?: sdrService

                // Gain slider -- range may have changed if the IQ source changed.
                ctrlViews.sliderGain?.let { slider ->
                    val gainCount = (svc?.source?.getGainCount() ?: RtlSdrDevice.GAIN_TABLE_DB_TENTHS.size)
                        .coerceAtLeast(1)
                    slider.valueFrom = 0f
                    slider.valueTo = (gainCount - 1).toFloat()
                    val idx = v.gainIndex.value.toFloat().coerceIn(slider.valueFrom, slider.valueTo)
                    slider.value = idx
                    syncGainLabel(idx.toInt())
                }

                ctrlViews.switchTunerAgc?.setOnCheckedChangeListener(null)
                ctrlViews.switchTunerAgc?.isChecked = v.tunerAgcEnabled.value
                ctrlViews.sliderGain?.isEnabled = !v.tunerAgcEnabled.value
                ctrlViews.switchTunerAgc?.setOnCheckedChangeListener { _, checked ->
                    v.setTunerAgc(checked)
                    ctrlViews.sliderGain?.isEnabled = !checked
                }

                ctrlViews.switchHardwareAgc?.setOnCheckedChangeListener(null)
                ctrlViews.switchHardwareAgc?.isChecked = v.hardwareAgcEnabled.value
                ctrlViews.switchHardwareAgc?.setOnCheckedChangeListener { _, checked -> v.setHardwareAgc(checked) }

                ctrlViews.sliderSquelch?.value = v.squelch.value.coerceIn(-120f, 0f)
                ctrlViews.tvSquelchValue?.text = if(v.squelch.value<=-120f) "关闭" else "${v.squelch.value.toInt()} dB"

                ctrlViews.sliderVolume?.value = v.volume.value.coerceIn(0f, 2f)
                ctrlViews.tvVolumeValue?.text = "${(v.volume.value * 100).toInt()}%"

                ctrlViews.switchBiasTee?.setOnCheckedChangeListener(null)
                ctrlViews.switchBiasTee?.isChecked = v.biasTee.value
                ctrlViews.switchBiasTee?.setOnCheckedChangeListener { _, checked -> v.setBiasTee(checked) }

                ctrlViews.switchNoiseBlanker?.setOnCheckedChangeListener(null)
                ctrlViews.switchNoiseBlanker?.isChecked = v.noiseBlankerEnabled.value && com.radiosport.ninegradio.dsp.NoiseProcessingPolicy.allows(v.demodMode.value)
                ctrlViews.switchNoiseBlanker?.setOnCheckedChangeListener { _, checked -> v.setNoiseBlanker(checked) }

                ctrlViews.switchNoiseReducer?.setOnCheckedChangeListener(null)
                ctrlViews.switchNoiseReducer?.isChecked = v.noiseReducerEnabled.value && com.radiosport.ninegradio.dsp.NoiseProcessingPolicy.allows(v.demodMode.value)
                ctrlViews.switchNoiseReducer?.setOnCheckedChangeListener { _, checked -> v.setNoiseReducer(checked) }

                // IF bandwidth slider -- range/position depend on the active mode.
                val mode      = v.demodMode.value
                val savedBw   = v.ifBandwidthHz.value
                val defaultBw = mode.defaultBwHz.coerceAtLeast(DspEngine.MIN_IF_BANDWIDTH_HZ)
                val effective = if (savedBw > 0) savedBw else defaultBw
                applyBwRange(mode, effective)
                ctrlViews.tvIfBandwidthValue?.text = formatIfBw(effective, savedBw == 0)

                ctrlViews.spinnerDirectSampling?.setSelection(v.directSampling.value, false)
                binding.controlViewPager.post { applyReceiverCapabilities() }

                ctrlViews.sliderPpm?.value = v.ppm.value.toFloat().coerceIn(-50f, 50f)
                ctrlViews.tvPpmValue?.text = "${v.ppm.value} ppm"
                binding.spectrumView.setPpmCorrection(v.ppm.value)
                binding.waterfallView.setPpmCorrection(v.ppm.value)

                if (sampleRateLabelsCache.isNotEmpty()) {
                    val idx = viewModel.availableSampleRates.indexOfFirst { it == v.sampleRate.value }.coerceAtLeast(0)
                    ctrlViews.tvSampleRateLabel?.text = sampleRateLabelsCache.getOrNull(idx)
                    updateSampleRateChips(idx)
                }
                if (audioSinkLabelsCache.isNotEmpty()) {
                    val idx = DspEngine.AUDIO_SINK_RATES.indexOfFirst { it == v.audioSinkRate.value }.coerceAtLeast(0)
                    ctrlViews.tvAudioSinkRateLabel?.text = audioSinkLabelsCache.getOrNull(idx)
                    updateAudioSinkChips(idx)
                }
            }

            ControlsPagerAdapter.TAB_DISPLAY -> {
                setupGraphControls()
                val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)

                // FFT / Decimation spinners
                val fftSizes = listOf(256, 512, 1024, 2048, 4096, 8192)
                fftSizes.indexOf(v.fftSize.value).takeIf { it >= 0 }?.let {
                    ctrlViews.spinnerFftSize?.setSelection(it, false)
                }
                val decimationFactors = listOf(1, 2, 4, 8, 16, 32, 64)
                decimationFactors.indexOf(v.decimation.value).takeIf { it >= 0 }?.let {
                    ctrlViews.spinnerDecimation?.setSelection(it, false)
                }

                // Waterfall palette
                ctrlViews.spinnerPalette?.setSelection(v.waterfallPalette.value.ordinal, false)

                // Spectrum theme — full 10-theme list
                val themes = listOf(
                    "UI", "Classic", "Futuristic", "Amber", "Grayscale", "Purple",
                    "Solar", "Neon", "Ice", "Midnight", "Sakura"
                )
                themes.indexOf(v.spectrumTheme.value).takeIf { it >= 0 }?.let {
                    ctrlViews.spinnerTheme?.setSelection(it, false)
                }

                // Helper: sync a Switch without re-firing the listener
                fun syncSwitch(sw: android.widget.CompoundButton?, checked: Boolean,
                               listener: android.widget.CompoundButton.OnCheckedChangeListener) {
                    sw?.setOnCheckedChangeListener(null)
                    sw?.isChecked = checked
                    sw?.setOnCheckedChangeListener(listener)
                }

                syncSwitch(ctrlViews.switchPeakHold,
                    prefs.getBoolean("pref_peak_hold", true)
                ) { _, checked ->
                    prefs.edit().putBoolean("pref_peak_hold", checked).apply()
                    binding.spectrumView.setShowPeakHold(checked)
                    val fft = sdrService?.dspEngine?.fftEngine
                    if (checked) { fft?.resetPeakHold(); fft?.showPeakHold = true }
                    else { fft?.showPeakHold = false; fft?.resetPeakHold() }
                }

                syncSwitch(ctrlViews.switchAutoRange,
                    prefs.getBoolean("pref_auto_range", false)
                ) { _, checked ->
                    binding.spectrumView.setAutoRange(checked)
                    prefs.edit().putBoolean("pref_auto_range", checked).apply()
                }

                syncSwitch(ctrlViews.switchNoiseFloor,
                    prefs.getBoolean("pref_show_noise_floor", true)
                ) { _, checked ->
                    binding.spectrumView.setShowNoiseFloor(checked)
                    prefs.edit().putBoolean("pref_show_noise_floor", checked).apply()
                }

                syncSwitch(ctrlViews.switchPeakAnnotations,
                    prefs.getBoolean("pref_show_peak_annotations", true)
                ) { _, checked ->
                    binding.spectrumView.setShowPeakAnnotations(checked)
                    prefs.edit().putBoolean("pref_show_peak_annotations", checked).apply()
                }

                syncSwitch(ctrlViews.switchCrosshair,
                    prefs.getBoolean("pref_crosshair_enabled", true)
                ) { _, checked ->
                    binding.spectrumView.setCrosshairEnabled(checked)
                    prefs.edit().putBoolean("pref_crosshair_enabled", checked).apply()
                }

                syncSwitch(ctrlViews.switchAutoStretch,
                    prefs.getBoolean("pref_waterfall_auto_stretch", false)
                ) { _, checked ->
                    binding.waterfallView.setAutoStretch(checked)
                    prefs.edit().putBoolean("pref_waterfall_auto_stretch", checked).apply()
                }

                syncSwitch(ctrlViews.switchTimeRuler,
                    prefs.getBoolean("pref_waterfall_show_timestamp", true)
                ) { _, checked ->
                    binding.waterfallView.setShowTimestamp(checked)
                    prefs.edit().putBoolean("pref_waterfall_show_timestamp", checked).apply()
                }

                syncSwitch(ctrlViews.switchCentreMarker,
                    prefs.getBoolean("pref_waterfall_show_tuner_marker", true)
                ) { _, checked ->
                    binding.waterfallView.setShowTunerMarker(checked)
                    prefs.edit().putBoolean("pref_waterfall_show_tuner_marker", checked).apply()
                }

                // Slider values (no listener re-fire risk — just set value)
                val fillOpacity = prefs.getInt("pref_fill_opacity", 73) / 100f
                ctrlViews.sliderFillOpacity?.value = fillOpacity.coerceIn(0f, 1f)
                ctrlViews.tvFillOpacityValue?.text = "${(fillOpacity * 100).toInt()}%"

                val syncFloor = -(prefs.getInt("pref_db_min", 120).toFloat()).coerceIn(10f, 200f)
                ctrlViews.sliderSpecFloor?.value = syncFloor
                ctrlViews.tvSpecFloorValue?.text = syncFloor.toInt().toString()

                val syncCeiling = prefs.getInt("pref_db_max", 0).toFloat().coerceIn(-100f, 50f)
                ctrlViews.sliderSpecCeiling?.value = syncCeiling
                ctrlViews.tvSpecCeilingValue?.text = syncCeiling.toInt().toString()

                val peakDecay = prefs.getInt("pref_peak_decay", 2).toFloat().coerceIn(1f, 20f)
                ctrlViews.sliderPeakDecay?.value = peakDecay
                ctrlViews.tvPeakDecayValue?.text = peakDecay.toInt().toString()

                val brightness = (prefs.getInt("pref_waterfall_brightness", 50) - 50) / 100f
                ctrlViews.sliderWfBrightness?.value = brightness.coerceIn(-0.5f, 0.5f)
                ctrlViews.tvWfBrightnessValue?.text = "%+.2f".format(brightness)

                val contrast = prefs.getInt("pref_waterfall_contrast", 50) / 50f
                ctrlViews.sliderWfContrast?.value = contrast.coerceIn(0.2f, 3.0f)
                ctrlViews.tvWfContrastValue?.text = "%.1f×".format(contrast)

                // Frame avg spinner
                val frameAvgValues = listOf(1, 2, 4, 8, 16, 32)
                val savedAvg = prefs.getString("pref_frame_averaging", "1")?.toIntOrNull() ?: 1
                ctrlViews.spinnerFrameAvg?.setSelection(
                    frameAvgValues.indexOf(savedAvg).takeIf { it >= 0 } ?: 0, false)
            }

            ControlsPagerAdapter.TAB_RECORDING -> {
                val stats = v.dspStats.value
                if (stats.isRecordingIq) {
                    ctrlViews.btnRecordIq?.text = "停止 IQ"
                    styleRecordingAction(ctrlViews.btnRecordIq,true)
                } else {
                    ctrlViews.btnRecordIq?.text = "录制 IQ"
                    styleRecordingAction(ctrlViews.btnRecordIq,false)
                }
                if (stats.isRecordingAudio) {
                    ctrlViews.btnRecordAudio?.text = "停止录音"
                    styleRecordingAction(ctrlViews.btnRecordAudio,true)
                } else {
                    ctrlViews.btnRecordAudio?.text = "录制声音"
                    styleRecordingAction(ctrlViews.btnRecordAudio,false)
                }
            }


            ControlsPagerAdapter.TAB_APRS -> {
                // The frequency/rate label is also kept live by the centerFreqHz/
                // sampleRate collectors in observeViewModel(); this covers the case
                // where the tab is (re)selected before those flows have re-emitted.
                if (v.demodMode.value == DemodMode.APRS) {
                    ctrlViews.tvAprsTabMode?.text =
                        "${"%.3f".format(v.centerFreqHz.value / 1e6)} MHz  |  ${v.sampleRate.value / 1000} kS/s"
                }
            }

            ControlsPagerAdapter.TAB_ACARS -> {
                // Same pattern as TAB_APRS above.
                if (v.demodMode.value == DemodMode.ACARS) {
                    ctrlViews.tvAcarsTabMode?.text =
                        "${"%.3f".format(v.centerFreqHz.value / 1e6)} MHz  |  ${v.sampleRate.value / 1000} kS/s"
                }
            }

            ControlsPagerAdapter.TAB_DMR -> {
                if (v.demodMode.value == DemodMode.DMR) {
                    ctrlViews.tvDmrTabMode?.text =
                        "${"%.3f".format(v.centerFreqHz.value / 1e6)} MHz  |  ${v.sampleRate.value / 1000} kS/s"
                    ctrlViews.tvDmrTabVocoder?.text =
                        VOCODER_STATUS_TEXT
                }
            }

            ControlsPagerAdapter.TAB_DIG -> {
                if (v.demodMode.value == DemodMode.DIG) {
                    ctrlViews.tvDigTabMode?.text =
                        "${"%.3f".format(v.centerFreqHz.value / 1e6)} MHz  |  ${v.sampleRate.value / 1000} kS/s"
                    ctrlViews.tvDigTabVocoder?.text =
                        VOCODER_STATUS_TEXT
                }
            }

            ControlsPagerAdapter.TAB_YSF -> {
                if (v.demodMode.value == DemodMode.YSF) {
                    ctrlViews.tvYsfTabMode?.text =
                        "${"%.3f".format(v.centerFreqHz.value / 1e6)} MHz  |  ${v.sampleRate.value / 1000} kS/s"
                    ctrlViews.tvYsfTabVocoder?.text =
                        VOCODER_STATUS_TEXT
                }
            }

            ControlsPagerAdapter.TAB_DSTAR -> {
                if (v.demodMode.value == DemodMode.DSTAR) {
                    ctrlViews.tvDstarTabMode?.text =
                        "${"%.3f".format(v.centerFreqHz.value / 1e6)} MHz  |  ${v.sampleRate.value / 1000} kS/s"
                    ctrlViews.tvDstarTabVocoder?.text =
                        VOCODER_STATUS_TEXT
                }
            }

            else -> Unit
        }
    }

    // ─── Tabbed controls drawer ───────────────────────────────────────────────

    /**
     * Sets up the ViewPager2 + TabLayout inside the bottom sheet.
     * Must be called before [setupControlsPanel] so that [ctrlViews] is ready
     * when the panel binds its listeners.
     *
     * ViewPager2 inflates pages lazily; the first two tabs (Mode and Tune) are
     * inflated immediately.  The pager's offscreenPageLimit is set to 5 so all
     * six tab views are created synchronously, which lets [ControlsTabManager]
     * return non-null views for every tab from the first call.
     */
    private fun defaultModule(): Int = ModulePreferences(this).let { config -> config.coreOrder.firstOrNull { it!=ControlsPagerAdapter.TAB_SETTINGS && config.visible(it) } ?: ControlsPagerAdapter.TAB_TUNE }

    private fun moduleTab(module: Int) = binding.controlTabLayout.getTabAt(pagerAdapter.positionOf(module))

    private fun setupTabsDrawer() {
        pagerAdapter = ControlsPagerAdapter(ModulePreferences(this).order)
        val pager = binding.controlViewPager
        pager.adapter = pagerAdapter
        // Keep all pages in memory so ctrlViews can access any tab immediately.
        pager.offscreenPageLimit = ControlsPagerAdapter.TAB_COUNT - 1
        // Disable nested scrolling on the RecyclerView inside VP2 so the
        // NestedScrollView (bottom sheet) handles the vertical scroll.
        pagerAdapter.attachNestedScrollWorkaround(pager)
        pager.isUserInputEnabled = false   // tabs switch via TabLayout, not swipe

        // ViewPager2's internal RecyclerView is not reliable about re-measuring
        // itself to the currently selected page's real content height once
        // several pages have been inflated (offscreenPageLimit keeps every tab
        // attached at once, and switching between already-cached pages does not
        // always trigger a fresh measure pass). Left alone, the pager's height
        // -- and therefore the drawer's scrollable range -- can stay stuck at
        // whichever tab happened to be current during the first layout pass.
        // That makes every other tab either cut off its last control or leave
        // scrollable blank space below it. Explicitly resize on every page
        // change so the scroll range always matches the visible tab.
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.btnToolbarSettings.imageTintList=android.content.res.ColorStateList.valueOf(
                    if(pagerAdapter.moduleAt(position)==ControlsPagerAdapter.TAB_SETTINGS) UiThemes.color(this@MainActivity,R.attr.radioAccent) else UiThemes.color(this@MainActivity,R.attr.radioSecondary))
                pager.post { resizePagerToPage(pagerAdapter.moduleAt(position)); updateDigitalVisualization() }
            }
        })

        TabLayoutMediator(binding.controlTabLayout, pager) { tab, position ->
            val pos = pagerAdapter.moduleAt(position)
            val icons = mapOf(ControlsPagerAdapter.TAB_TUNE to R.drawable.ic_tune_dial,
                ControlsPagerAdapter.TAB_MODE to R.drawable.ic_mode_wave,
                ControlsPagerAdapter.TAB_RF to R.drawable.ic_rf_antenna,
                ControlsPagerAdapter.TAB_DISPLAY to R.drawable.ic_waterfall_display,
                ControlsPagerAdapter.TAB_RECORDING to R.drawable.ic_record_radio,
                ControlsPagerAdapter.TAB_SETTINGS to R.drawable.ic_module_settings, ControlsPagerAdapter.TAB_SCAN to R.drawable.ic_scan_sweep,
                ControlsPagerAdapter.TAB_RDF to android.R.drawable.ic_menu_compass)
            val labels = mapOf(ControlsPagerAdapter.TAB_TUNE to R.string.tab_tune,
                ControlsPagerAdapter.TAB_MODE to R.string.tab_mode,
                ControlsPagerAdapter.TAB_RF to R.string.tab_rf,
                ControlsPagerAdapter.TAB_DISPLAY to R.string.tab_display,
                ControlsPagerAdapter.TAB_RECORDING to R.string.tab_record,
                ControlsPagerAdapter.TAB_SETTINGS to R.string.tab_settings, ControlsPagerAdapter.TAB_SCAN to R.string.tab_scan,
                ControlsPagerAdapter.TAB_RDF to R.string.tab_rdf)
            if (pos in icons) {
                tab.setIcon(icons.getValue(pos))
                tab.contentDescription = getString(labels.getValue(pos))
                androidx.appcompat.widget.TooltipCompat.setTooltipText(tab.view, tab.contentDescription)
            } else tab.text = ControlsPagerAdapter.TAB_TITLES[pos]
            if (pos in ModulePreferences.managed && !ModulePreferences(this).visible(pos)) tab.view.visibility = View.GONE
            if(pos==ControlsPagerAdapter.TAB_SETTINGS) tab.view.visibility=View.GONE
        }.attach()

        // Scroll to top of the drawer content whenever a tab label is tapped --
        // both when switching to a new tab and when re-tapping the current tab.
        binding.controlTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                BottomSheetBehavior.from(binding.controlsBottomSheet).state = BottomSheetBehavior.STATE_EXPANDED
                binding.controlsScrollView.scrollTo(0, 0)
                syncTabControls(pagerAdapter.moduleAt(tab.position))
                pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_ADSB)?.findViewById<AdsbRadarView>(R.id.adsbPanelRadar)
                    ?.setAnimating(pagerAdapter.moduleAt(tab.position)==ControlsPagerAdapter.TAB_ADSB)
                pager.post { resizePagerToPage(pagerAdapter.moduleAt(tab.position)) }
            }
            override fun onTabReselected(tab: TabLayout.Tab) {
                binding.controlsScrollView.scrollTo(0, 0)
                syncTabControls(pagerAdapter.moduleAt(tab.position))
                // Re-tapping the current tab doesn't fire ViewPager2's
                // OnPageChangeCallback (the page never changed), so refresh
                // the height here too in case this tab's content grew or
                // shrank while it was already selected.
                pager.post { resizePagerToPage(pagerAdapter.moduleAt(tab.position)) }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {
                if(pagerAdapter.moduleAt(tab.position)==ControlsPagerAdapter.TAB_ADSB)
                    pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_ADSB)?.findViewById<AdsbRadarView>(R.id.adsbPanelRadar)?.setAnimating(false)
            }
        })

        ctrlViews = ControlsTabManager(pagerAdapter)
        contentController = ContentModuleController(this,pagerAdapter,viewModel,{ sdrService },
            { module -> moduleTab(module)?.select(); binding.controlViewPager.setCurrentItem(pagerAdapter.positionOf(module),false) },
            { module,shown -> moduleTab(module)?.view?.visibility=if(shown) View.VISIBLE else View.GONE },
            { resizePagerToCurrentPage() },{ setPanelFullscreen(!panelFullscreen) },
            { contentMicPermission.launch(android.Manifest.permission.RECORD_AUDIO) },
            { contentWavPicker.launch(arrayOf("audio/*","application/octet-stream")) },
            { contentCsvPicker.launch(arrayOf("text/*","application/octet-stream")) })
        pager.post { contentController?.setup() }

        // Mode tab: build the demodulation-mode chip group once it's inflated.
        pager.post { setupDemodModeChips() }

        // Set up the Tune tab's tuning dial and step chips after layout is done.
        pager.post { setupTuneTab() }

        // Settings tab: open full settings + device info buttons


        // APRS tab: wire up the embedded APRS monitor.
        pager.post { setupAprsTab() }
        pager.post { setupAdsbTab() }

        // APRS tab is hidden by default; shown only when DemodMode.APRS is active.
        pager.post {
            moduleTab(ControlsPagerAdapter.TAB_APRS)?.view
                ?.visibility = android.view.View.GONE
        }

        // ACARS tab: wire up the embedded ACARS monitor.
        pager.post { setupAcarsTab() }

        // ACARS tab is hidden by default; shown only when DemodMode.ACARS is active.
        pager.post {
            moduleTab(ControlsPagerAdapter.TAB_ACARS)?.view
                ?.visibility = android.view.View.GONE
        }

        // DMR tab: wire up the embedded DMR frame monitor.
        pager.post { setupDmrTab() }

        // DMR tab is hidden by default; shown only when DemodMode.DMR is active.
        pager.post {
            moduleTab(ControlsPagerAdapter.TAB_DMR)?.view
                ?.visibility = android.view.View.GONE
        }

        // YSF tab: wire up the embedded YSF frame monitor.
        pager.post { setupYsfTab() }

        // YSF tab is hidden by default; shown only when DemodMode.YSF is active.
        pager.post {
            moduleTab(ControlsPagerAdapter.TAB_YSF)?.view
                ?.visibility = android.view.View.GONE
        }

        // D-STAR tab: wire up the embedded D-STAR frame monitor.
        pager.post { setupDstarTab() }

        // D-STAR tab is hidden by default; shown only when DemodMode.DSTAR is active.
        pager.post {
            moduleTab(ControlsPagerAdapter.TAB_DSTAR)?.view
                ?.visibility = android.view.View.GONE
        }

        // Dig tab: wire up the embedded Dig frame monitor.
        pager.post { setupDigTab() }

        // Dig tab is hidden by default; shown only when DemodMode.DIG is active.
        pager.post {
            moduleTab(ControlsPagerAdapter.TAB_DIG)?.view
                ?.visibility = android.view.View.GONE
        }

        // Scan tab: wire up the embedded frequency scanner.
        pager.post { setupScanTab() }

        // Scan tab is hidden by default; shown only when explicitly invoked via
        // showScanTab() (Scanner button / FAB) — it isn't tied to a DemodMode
        // like the protocol tabs above, so it starts hidden and stays hidden
        // until the user asks for it.
        pager.post {
            moduleTab(ControlsPagerAdapter.TAB_SCAN)?.view?.visibility =
                if (ModulePreferences(this).visible(ControlsPagerAdapter.TAB_SCAN)) View.VISIBLE else View.GONE
        }

        // Catch-all: every setup*Tab() call above is itself deferred via
        // pager.post, and several of them (chips, embedded monitors) change
        // their tab's natural height once they finish building. Queuing this
        // resize last means it runs after all of them, so the pager locks in
        // the true, fully-built height of whichever tab ends up selected --
        // not a stale pre-build measurement.
        pager.post { resizePagerToCurrentPage() }
        pager.post {
            pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_RDF)?.findViewById<android.widget.FrameLayout>(R.id.rdfControls)?.let { host ->
                val controls=RdfControls(this)
                host.addView(controls)
                controls.setActive(pagerAdapter.moduleAt(binding.controlViewPager.currentItem)==ControlsPagerAdapter.TAB_RDF)
                lifecycleScope.launch {
                    kotlinx.coroutines.flow.combine(viewModel.deviceStatus,viewModel.centerFreqHz) { status,hz -> status to hz }
                        .collect { (status,hz) -> controls.render(status.signalStrengthDb,status.connected,hz) }
                }
            }
        }
    }

    /**
     * Explicitly measures [position]'s tab page and resizes the ViewPager2 to
     * match its real content height, then keeps the outer drawer's
     * NestedScrollView in sync so its scrollable range is recalculated too.
     * See the comment on the OnPageChangeCallback in [setupTabsDrawer] for why
     * this is necessary instead of relying on ViewPager2 to size itself.
     */
    private fun resizePagerToPage(position: Int) {
        val pager = binding.controlViewPager
        val page = pagerAdapter.getViewAt(position) ?: return
        if (pager.width == 0) return

        val widthSpec = android.view.View.MeasureSpec.makeMeasureSpec(
            pager.width, android.view.View.MeasureSpec.EXACTLY
        )
        val heightSpec = android.view.View.MeasureSpec.makeMeasureSpec(
            0, android.view.View.MeasureSpec.UNSPECIFIED
        )
        page.measure(widthSpec, heightSpec)
        val target = page.measuredHeight
        if (target <= 0) return

        val lp = pager.layoutParams
        if (lp.height != target) {
            lp.height = target
            pager.layoutParams = lp
            // The outer LinearLayout (pager + nothing else now that the old
            // static spacer is gone) and the NestedScrollView above it both
            // need to re-derive their content height from the pager's new
            // height before the scroll range is correct for this tab.
            binding.controlsScrollView.requestLayout()
        }
    }

    /** Resizes the pager to whichever tab is currently selected. */
    private fun resizePagerToCurrentPage() {
        resizePagerToPage(pagerAdapter.moduleAt(binding.controlViewPager.currentItem))
    }

    /**
     * Wires up the Tune tab:
     * - Tuning dial with protocol-aware step
     * - Step-size chip group
     * - Band preset chips
     * - Fine-tune switch
     * - Snap-to-channel switch
     */
    private fun setupTuneTab() {
        val dial = ctrlViews.tuningDial ?: return
        val root=pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_TUNE)
        val preference=getSharedPreferences("rtlsdr_prefs",MODE_PRIVATE)
        root?.findViewById<View>(R.id.stepParameter)?.isVisible=true
        root?.findViewById<View>(R.id.bandwidthParameter)?.isVisible=false
        root?.findViewById<View>(R.id.tuningParameterSelector)?.isVisible=false
        updateTuneFrequency(viewModel.centerFreqHz.value)
        pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_TUNE)?.findViewById<SpringTunerView>(R.id.springTuner)?.apply {
            onSteps = { ticks ->
                val step = binding.frequencyView.tuningStepHz
                graphTune(TuningMath.snap(viewModel.centerFreqHz.value + ticks * step, step))
            }
            onFinished = { viewModel.finishTuning() }
        }
        pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_TUNE)?.findViewById<Button>(R.id.btnEtmScan)?.setOnClickListener { showScanTab() }
        pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_TUNE)?.findViewById<Button>(R.id.btnRegionalRecommendations)
            ?.setOnClickListener { regionalReception.show() }

        pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_TUNE)?.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchAutoDemod)?.apply {
            isChecked = viewModel.autoDemod.value
            setOnCheckedChangeListener { _, checked -> viewModel.setAutoDemod(checked) }
        }
        lifecycleScope.launch { viewModel.autoDemod.collect { enabled ->
            pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_TUNE)?.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchAutoDemod)?.isChecked = enabled
        } }
        lifecycleScope.launch { viewModel.modeStationMemory.collect { enabled ->
            pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_MODE)
                ?.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchModeStationMemory)
                ?.isChecked = enabled
        } }
        // Sync dial with current frequency and mode
        dial.compact = true
        dial.showLabels = false
        dial.showCurrentFrequency=true
        dial.currentFreqHz = viewModel.centerFreqHz.value
        dial.demodMode     = viewModel.demodMode.value.name

        dial.onTuningFinished = { viewModel.finishTuning() }
        dial.onStep = { deltaHz ->
            graphTune(TuningMath.snap(viewModel.centerFreqHz.value + (if(binding.frequencyView.reverseTuning) -deltaHz else deltaHz), dial.activeStepHzPublic()))
        }

        // Fine-tune switch mirrors dial's fineMode
        ctrlViews.switchFineTune?.setOnCheckedChangeListener { _, checked ->
            dial.fineMode = checked
        }
        dial.setOnFineModeChangedListener { fine ->
            ctrlViews.switchFineTune?.isChecked = fine
        }

        // Snap switch
        ctrlViews.switchSnap?.isChecked = snapToChannel
        ctrlViews.switchSnap?.setOnCheckedChangeListener { _, checked ->
            snapToChannel = checked
        }

        // Step chips: populate with common steps for current mode
        setupStepChips()

        // Band preset chips
        setupBandPresetChips()
    }

    private fun stepPicker() = pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_TUNE)?.findViewById<TuningDialView>(R.id.stepPicker)
    private fun setupStepChips() {
        val mode = viewModel.demodMode.value.name
        val prefs = getSharedPreferences("rtlsdr_prefs", MODE_PRIVATE)
        val recommended = FrequencyStepManager.recommendedStep(viewModel.centerFreqHz.value, mode)
        val current = prefs.getLong("tuningStep_v4_$mode", recommended.hz.coerceAtLeast(1000))
        binding.frequencyView.setTuningStep(current)
        val choices = FrequencyStepManager.StepSize.values().map { TuningDialView.Choice(it.hz, it.label) }.toMutableList()
        if (choices.none { it.value == current }) choices += TuningDialView.Choice(current, formatStep(current))
        stepPicker()?.apply {
            setChoices(choices,current)
            onChoiceSelected = { step -> binding.frequencyView.setTuningStep(step) }
        }
        ctrlViews.tuningDial?.overrideStepHz = maxOf(1, current / 10)
        updatePlotBandwidth()
    }

    private fun formatStep(hz: Long): String = when {
        hz >= 1_000_000 -> "${hz / 1_000_000.0} MHz"
        hz >= 1_000 -> "${hz / 1_000.0} kHz"
        else -> "$hz Hz"
    }

    private fun setupBandPresetChips() {
        binding.btnQuickBookmark.setOnClickListener { bookmarkCurrentFrequency() }
        pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_TUNE)?.findViewById<View>(R.id.btnMyBookmarks)?.setOnClickListener {
            BookmarkQuickControls.showPicker(this,viewModel) { hz ->
                if(viewModel.setFrequency(hz)) { viewModel.finishTuning();syncFrequencyDisplay() }
            }
        }
        pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_TUNE)?.findViewById<View>(R.id.btnBandPresets)?.setOnClickListener {
            BandPresetPicker.show(this, viewModel.capabilities.value, viewModel.centerFreqHz.value) { preset, hz ->
                if (!viewModel.autoDemod.value) runCatching { viewModel.setDemodMode(DemodMode.valueOf(preset.defaultMode)) }
                if (viewModel.setFrequency(hz)) {
                    binding.frequencyView.setTuningStep(preset.defaultStep.hz)
                    viewModel.finishTuning(); syncFrequencyDisplay()
                }
            }
        }
    }

    /**
     * Push the ViewModel's current center frequency to every display that
     * shows it (main frequency readout, spectrum, waterfall, tuning dial).
     * Called whenever the in-effect frequency may have changed without the
     * normal onFrequencyChanged callback firing -- e.g. after setDemodMode()
     * restores a per-mode frequency snapshot.
     */
    private fun syncFrequencyDisplay() {
        val hz = viewModel.centerFreqHz.value
        updateTuneFrequency(hz)
        binding.frequencyView.setFrequency(hz)
        binding.spectrumView.setCenterFrequency(hardwareCenterFreqHz(hz))
        binding.spectrumView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(hz))
        binding.spectrumView.setDemodDialFrequency(hz)
        binding.waterfallView.setCenterFrequency(hardwareCenterFreqHz(hz))
        binding.waterfallView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(hz))
        ctrlViews.tuningDial?.currentFreqHz = hz
    }
    private fun updateTuneFrequency(hz: Long) {
        if(!::pagerAdapter.isInitialized) return
        val text=String.format(java.util.Locale.ROOT,"%,d",hz).replace(',','.')+" Hz"
        pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_TUNE)?.findViewById<TextView>(R.id.tvTuneFrequency)?.text=text
    }

    /** Helper called from ViewModel observation to keep dial in sync with mode changes. */
    private fun onDemodModeChanged(mode: DemodMode) {
        contentController?.modeChanged(mode)
        updatePlotBandwidth()
        // setDemodMode() may have restored a per-mode frequency snapshot, so
        // refresh the main frequency display (and spectrum/waterfall) to
        // reflect the frequency now actually in effect for the new protocol.
        syncFrequencyDisplay()

        ctrlViews.tuningDial?.let { dial ->
            dial.demodMode = mode.name
            dial.overrideStepHz = maxOf(1, binding.frequencyView.tuningStepHz / 10)
            setupStepChips()            // refresh step chips for new mode
        }
    }

    private fun selectDemodChip(mode: DemodMode) {
        // Keep the spectrum view's Auto dB Range tier (see
        // SpectrumView.setDemodMode / AutoRangeTier) in sync with the
        // actually active protocol every time the UI reflects a mode change
        // -- this function is the single place every mode-change path
        // (chip tap, scan-tab restore, memory recall, etc.) already calls to
        // update the UI, so it's the safest single hook point rather than
        // patching every viewModel.setDemodMode()/svc.setDemodMode() call
        // site individually. Done before the chipGroup-null early return so
        // it still applies even when the Demod tab isn't the one currently
        // visible.
        binding.spectrumView.setDemodMode(mode)
        for(chipGroup in modeChipGroups()) for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? com.google.android.material.button.MaterialButton ?: continue
            chip.isSelected=chip.tag==mode
        }
    }

    private var recordingPlayer:RecordingPlayerControls?=null
    private var panelFullscreen=false
    private var positionControlsPanel: (() -> Unit)?=null
    private var adsbMapPanel: RadioMapPanel?=null
    private var aprsMapPanel: RadioMapPanel?=null
    private var adsbMapSelected=true

    private var animatePanelResize=false
    private fun setPanelFullscreen(full: Boolean) {
        // Start the native settle before content changes request another layout.
        binding.root.doOnPreDraw {
            panelFullscreen=full
            animatePanelResize=true
            positionControlsPanel?.invoke()
            animatePanelResize=false
            if(BottomSheetBehavior.from(binding.controlsBottomSheet).state==BottomSheetBehavior.STATE_COLLAPSED)
                BottomSheetBehavior.from(binding.controlsBottomSheet).state=BottomSheetBehavior.STATE_EXPANDED
            findViewById<Button>(R.id.recordingPlaybackExpand)?.apply {
                text=if(full) "还原" else "展开"
                contentDescription=if(full) "还原录音页" else "展开录音页"
            }
            binding.controlsScrollView.scrollTo(0,0)
            if(::pagerAdapter.isInitialized && pagerAdapter.moduleAt(binding.controlViewPager.currentItem)==ControlsPagerAdapter.TAB_RECORDING)
                binding.controlViewPager.post { resizePagerToCurrentPage() }
            for(id in listOf(R.id.btnAdsbPanelFull,R.id.btnAprsPanelFull)) findViewById<Button>(id)?.text=if(full) "还原" else "展开"
        }
        binding.root.invalidate()
    }
    private fun setupBottomSheet() {
        binding.controlsBottomSheet.clipToOutline = true
        binding.controlsHeader.clipToOutline = true
        binding.panelEdgeBlur.setupWith(binding.panelBlurTarget)
            .setFrameClearDrawable(android.graphics.drawable.ColorDrawable(UiThemes.color(this,R.attr.radioMain)))
            .setBlurRadius(12f)
            .setOverlayColor(androidx.core.graphics.ColorUtils.setAlphaComponent(UiThemes.color(this,R.attr.radioSurface),171))
        binding.btnToolbarSettings.setOnClickListener {
            bookmarkLauncher.launch(Intent(this,SettingsActivity::class.java))
        }
        val behavior = BottomSheetBehavior.from(binding.controlsBottomSheet)
        behavior.isFitToContents = false
        behavior.isDraggable = false
        behavior.isHideable = false
        behavior.isGestureInsetBottomIgnored = true
        behavior.peekHeight = (68 * resources.displayMetrics.density).toInt() + navBarHeightPx
        binding.controlsHeader.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            behavior.peekHeight = binding.controlsHeader.height + navBarHeightPx
        }
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        binding.controlViewPager.setCurrentItem(pagerAdapter.positionOf(defaultModule()), false)
        fun positionPanel() {
            val h = binding.root.height
            if (h == 0) return
            var plotTop = 0
            var node: View = binding.waterfallView
            while (node !== binding.root) {
                plotTop += node.top
                node = node.parent as? View ?: break
            }
            val normal = (plotTop + minOf(binding.waterfallView.height / 3, (60 * resources.displayMetrics.density).toInt()))
                .coerceIn((h * 0.48f).toInt(), (h - 200 * resources.displayMetrics.density).toInt())
            val rootY=IntArray(2); val frequencyY=IntArray(2)
            binding.root.getLocationInWindow(rootY); binding.frequencyView.getLocationInWindow(frequencyY)
            val offset=if(panelFullscreen) frequencyY[1]-rootY[1]+binding.frequencyView.height else normal
            if (behavior.expandedOffset != offset) {
                behavior.expandedOffset = offset
                if(animatePanelResize) behavior.state=BottomSheetBehavior.STATE_EXPANDED
            }
            binding.controlsScrollView.setPadding(0, 0, 0, offset + navBarHeightPx)
            val visualHeight=if(panelFullscreen) (h-offset-binding.controlsHeader.height-(150*resources.displayMetrics.density).toInt()-navBarHeightPx).coerceAtLeast((220*resources.displayMetrics.density).toInt()) else (260*resources.displayMetrics.density).toInt()
            for(id in listOf(R.id.adsbVisualization,R.id.aprsMapHost)) findViewById<View>(id)?.let { visual ->
                if(visual.layoutParams.height!=visualHeight) { visual.layoutParams=visual.layoutParams.apply { height=visualHeight }; binding.controlViewPager.post { resizePagerToCurrentPage() } }
            }
        }
        positionControlsPanel=::positionPanel
        onBackPressedDispatcher.addCallback(this,object: androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if(panelFullscreen) setPanelFullscreen(false) else { isEnabled=false; onBackPressedDispatcher.onBackPressed(); isEnabled=true }
            }
        })
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionPanel() }
        binding.waterfallView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionPanel() }
        binding.root.post { positionPanel() }
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(sheet: View, state: Int) {
                if(state==BottomSheetBehavior.STATE_COLLAPSED || state==BottomSheetBehavior.STATE_EXPANDED)
                    binding.sheetToggle.isCollapsed = state == BottomSheetBehavior.STATE_COLLAPSED
                binding.controlsScrollView.visibility=if(state==BottomSheetBehavior.STATE_COLLAPSED) View.GONE else View.VISIBLE
            }
            override fun onSlide(sheet: View, offset: Float) {
                binding.controlsScrollView.visibility=if(offset<=0f) View.GONE else View.VISIBLE
            }
        })
        binding.sheetToggle.isCollapsed = false
        binding.sheetToggle.setOnClickListener {
            if(panelFullscreen) setPanelFullscreen(false) else behavior.state = if (behavior.state == BottomSheetBehavior.STATE_COLLAPSED)
                BottomSheetBehavior.STATE_EXPANDED else BottomSheetBehavior.STATE_COLLAPSED
        }
        var downY = 0f
        binding.sheetToggle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = event.rawY; true }
                MotionEvent.ACTION_MOVE -> true
                MotionEvent.ACTION_UP -> {
                    val delta = event.rawY - downY
                    if (kotlin.math.abs(delta) < 20 * resources.displayMetrics.density) view.performClick()
                    else if(delta>0 && panelFullscreen) setPanelFullscreen(false)
                    else if(delta<0 && behavior.state==BottomSheetBehavior.STATE_EXPANDED) setPanelFullscreen(true)
                    else behavior.state = if (delta > 0) BottomSheetBehavior.STATE_COLLAPSED else BottomSheetBehavior.STATE_EXPANDED
                    true
                }
                else -> true
            }
        }
    }

    private fun setupFabMenu() {
        binding.fabScan.setOnClickListener {
            showScanTab()
        }
        binding.fabBookmark.setOnClickListener {
            bookmarkCurrentFrequency()
        }

        // Adjust FAB bottom margins to respect the navigation bar inset so they
        // don't overlap with gesture handles or button nav bars.
        val density = resources.displayMetrics.density
        val baseMargins = listOf(
            binding.fabBookmark to (160 * density).toInt(),
            binding.fabScan    to (210 * density).toInt()
        )
        // We piggyback on the controlTabLayout insets listener which fires first;
        // apply a separate listener here so FABs update independently.
        ViewCompat.setOnApplyWindowInsetsListener(binding.fabBookmark) { _, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            baseMargins.forEach { (fab, base) ->
                (fab.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                    lp.bottomMargin = base + navBottom
                    fab.layoutParams = lp
                }
            }
            insets
        }
    }

    // ─── IF Bandwidth helpers (class-level so both setupControlsPanelDeferred ──
    // ─── and observeViewModel can reach them) ─────────────────────────────────

    private val BW_STEPS = intArrayOf(100,150,200,250,300,400,500,600,800,
        1000,1200,1500,1800,2000,2400,2700,3000,3300,3600,4000,5000,6000,8000,10000,
        12500,15000,25000,50000,75000,100000,125000,150000,175000,200000,225000,250000,
        300000,350000,400000,500000,600000,660000,750000,900000,1000000,1500000,2000000)

    /**
     * Per-mode allowed index range within BW_STEPS.
     * Pair(minIdx, maxIdx) -- both inclusive.
     * Only the values in `minIdx..maxIdx` will be reachable by the slider.
     */
    private fun bwRangeFor(mode: DemodMode): Pair<Int,Int> = when (mode) {
        DemodMode.CW,
        DemodMode.CWR  -> Pair(0, 8)    // 100 Hz - 800 Hz
        DemodMode.USB,
        DemodMode.LSB  -> Pair(7, 18)   // 600 Hz - 3.6 kHz
        DemodMode.DSB  -> Pair(9, 19)   // 1.0 kHz - 4 kHz
        DemodMode.AM   -> Pair(14, 24)  // 2.4 kHz - 12.5 kHz
        DemodMode.NFM,
        DemodMode.FLEX -> Pair(19, 26)  // 4 kHz - 25 kHz
        // FIX 20: range widened from (24,25)=12.5-15 kHz to match DemodMode.APRS's
        // new 48 kHz default IF rate (was incorrectly grouped with NFM's 16 kHz
        // bucket). 25 kHz - 200 kHz lets the slider reach "Auto" (48 kHz+) again.
        DemodMode.APRS -> Pair(24, 29)  // 12.5 kHz - 200 kHz (Auto sits near top)
        DemodMode.ACARS -> Pair(24, 26) // 12.5 kHz - 25 kHz
        // Digital voice — all use 12.5 kHz channels (NXDN also supports 6.25 kHz
        // but 12.5 kHz is the safe minimum for the IF pre-filter on most hardware)
        DemodMode.DMR,
        DemodMode.P25,
        DemodMode.NXDN,
        DemodMode.DSTAR,
        DemodMode.YSF,
        DemodMode.M17,
        DemodMode.DPMR,
        DemodMode.DIG  -> Pair(24, 25)  // 12.5 kHz - 15 kHz
        DemodMode.FM, DemodMode.WFM, DemodMode.WFM_STEREO -> Pair(27,
            BW_STEPS.indexOfLast { it <= viewModel.capabilities.value.maxChannelBandwidth(viewModel.sampleRate.value) }.coerceAtLeast(27))
        DemodMode.DRM  -> Pair(14, 21)  // 2.4 kHz - 5 kHz
        DemodMode.ADSB -> Pair(27, 29)  // 50 kHz - 200 kHz
        DemodMode.RAW  -> Pair(0,  29)  // full range -- pass-through
    }

    private fun formatIfBw(hz: Int, isAuto: Boolean): String =
        if (isAuto) "Auto · ${formatIfBw(hz, false)}"
        else if (hz < 1_000) "${hz} Hz"
        else if (hz % 1_000 == 0) "${hz / 1_000} kHz"
        else "${"%.1f".format(hz / 1_000.0)} kHz"

    /** Return the index in BW_STEPS nearest to [hz], clamped to [range]. */
    private fun nearestBwIdx(hz: Int, range: Pair<Int,Int> = Pair(0, BW_STEPS.size - 1)): Int {
        var best = range.first
        var bestDist = Int.MAX_VALUE
        for (i in range.first..range.second) {
            val d = kotlin.math.abs(BW_STEPS[i] - hz)
            if (d < bestDist) { bestDist = d; best = i }
        }
        return best
    }

    /** Apply the correct valueFrom/valueTo for [mode] and snap the thumb. */
    private fun applyBwRange(mode: DemodMode, currentHz: Int) {
        val (lo, hi) = bwRangeFor(mode)
        val slider   = ctrlViews.sliderIfBandwidth ?: return
        // Material Slider requires value to be inside [valueFrom, valueTo] before
        // changing the bounds -- clamp first, then widen/narrow.
        val idx      = nearestBwIdx(currentHz, Pair(lo, hi)).toFloat()
        // Always set value within the current range before changing bounds to
        // avoid the "value outside range" IllegalStateException.
        val safeIdx  = slider.value.coerceIn(lo.toFloat(), hi.toFloat())
        slider.value = safeIdx          // clamp first
        slider.valueFrom = lo.toFloat()
        slider.valueTo   = hi.toFloat()
        slider.value     = idx          // snap to correct position
    }

    // ─── ViewModel observation ────────────────────────────────────────────────

    /**
     * Compute the channel highlight bandwidth for the given mode + IF setting.
     *
     * For narrow modes the effective passband IS the IF bandwidth (the
     * ComplexDecimator defines the channel before the demodulator). For WFM
     * the passband equals the actual wfmIfRate for the current device rate --
     * NOT a fixed 200 kHz constant, because at low sample rates (≤ 250 kS/s)
     * the full device bandwidth IS the IF, and at high rates the rational
     * resampler in DspEngine.wfmIfRate() caps it at 250 kS/s.
     *
     * Class-level member function so it is accessible from both
     * setupControlsPanelDeferred() (sample-rate chip clicks) and
     * observeViewModel() (mode / IF-bandwidth observers).
     */
    private fun channelHighlightHz(mode: DemodMode, ifBwHz: Int): Long {
        val visible = when(mode) {
            DemodMode.AM, DemodMode.NFM, DemodMode.USB, DemodMode.LSB,
            DemodMode.CW, DemodMode.CWR, DemodMode.DSB, DemodMode.APRS,
            DemodMode.ACARS, DemodMode.FLEX, DemodMode.FM, DemodMode.WFM, DemodMode.WFM_STEREO -> true
            else -> false
        }
        return if(visible) com.radiosport.ninegradio.dsp.ChannelPassband.widthHz(mode,ifBwHz,
            viewModel.capabilities.value.maxChannelBandwidth(viewModel.sampleRate.value)).toLong() else 0L
    }

    /**
     * Sideband alignment for the spectrum's channel-highlight box, passed to
     * [SpectrumView.setDemodChannelBandwidth].
     *
     * The dialed/tuned frequency in USB/LSB is one EDGE of the audio
     * passband, not its midpoint (the filter passband sits entirely above
     * the dial in USB, and entirely below it in LSB) -- unlike AM/FM/NFM/etc,
     * where the dial frequency is the channel centre.
     *
     *   USB -> +1 (passband to the right of / above the dial frequency)
     *   LSB -> -1 (passband to the left of / below the dial frequency)
     *   all other modes -> 0 (symmetric, centred on the dial frequency)
     */
    private fun channelHighlightSideband(mode: DemodMode): Int = when (mode) {
        DemodMode.USB -> 1
        DemodMode.LSB -> -1
        else -> 0
    }

    /**
     * The frequency the spectrum/waterfall *overlays* (passband highlight box,
     * dial marker, axis labels, ref markers, bookmarks) should be drawn *as if*
     * the display were centred on -- NOT necessarily the literal RTL-SDR tuner
     * frequency. Read by SpectrumView/WaterfallView.setCenterFrequency().
     *
     * This is intentionally a graphics-only value and is allowed to diverge
     * from the real hardware centre (see [trueHardwareCenterFreqHz] below,
     * which tracks the real value and keeps the FFT trace/waterfall bitmap in
     * sync with whatever this function returns). Screen pixel = width/2 is
     * always defined to be this frequency; every overlay is positioned
     * relative to it.
     *
     * Always just [dialHz] -- the literal tuned/dialed frequency shown in the
     * digit readout, for every mode including USB/LSB.
     *
     * Screen pixel = width/2 must be a single, mode-independent reference
     * point that every overlay (passband box, dial/tuner marker, axis
     * labels, ref markers, bookmarks) agrees on, or those overlays visibly
     * separate from each other and from the true-frequency-indexed FFT
     * trace/waterfall bitmap (which is always reconciled against this value
     * via [trueHardwareCenterFreqHz] -- see WaterfallView/SpectrumView's
     * hwOffsetHz math). Baking the SSB_BFO_OFFSET_HZ hardware shift into
     * this value -- with either sign -- pulls the dial away from the literal
     * screen centre by that offset (or, with a sign flip, by DOUBLE that
     * offset relative to the other sideband), which is exactly what visibly
     * shifted the passband box off-centre and off the dial/tuner marker in
     * previous versions of this function. The dial belongs at width/2 in
     * every mode; the passband box already accounts for the BFO-driven
     * sideband asymmetry on its own via [channelHighlightSideband] /
     * SpectrumView's sideband handling, and the real signal's true RF
     * position (offset from the dial by SSB_BFO_OFFSET_HZ) is handled by
     * the hwOffsetHz reconciliation in the trace/waterfall renderers -- not
     * by moving the screen centre.
     */
    private fun hardwareCenterFreqHz(dialHz: Long, mode: DemodMode = viewModel.demodMode.value): Long =
        dialHz

    /**
     * The *actual* RF frequency the RTL-SDR hardware is tuned to right now --
     * must always mirror [DspEngine.setCarrierFrequency]'s hardware formula
     * exactly (`dialHz + SSB_BFO_OFFSET_HZ` for *both* USB and LSB, symmetric,
     * per that function's doc comment; no offset for any other mode). Never
     * "correct" this to match [hardwareCenterFreqHz]'s per-sideband sign flip
     * -- the two are allowed to disagree, and disagreeing is expected in
     * USB/LSB.
     *
     * Read by SpectrumView/WaterfallView.setHardwareTunedFrequency(). Those
     * views' raw FFT trace / waterfall bitmap are bin-indexed off this real
     * tuned frequency (that's where the SDR hardware actually put the
     * signal), while every overlay is positioned relative to
     * [hardwareCenterFreqHz]. The views reconcile the two internally so the
     * trace/waterfall line up with the overlays on screen whenever the two
     * helpers disagree -- passing the same graphics-only value to both
     * setters here would defeat that and misplace the trace/waterfall
     * instead of the overlays.
     */
    private fun trueHardwareCenterFreqHz(dialHz: Long, mode: DemodMode = viewModel.demodMode.value): Long =
        (sdrService?.source as? com.radiosport.ninegradio.source.FileIqSource)?.getCenterFrequency() ?: when (mode) {
            DemodMode.USB, DemodMode.LSB -> dialHz + DspEngine.SSB_BFO_OFFSET_HZ
            DemodMode.CW, DemodMode.CWR -> dialHz+DspEngine.cwTunerOffset(dialHz,viewModel.capabilities.value,viewModel.sampleRate.value)
            else -> dialHz
        }

    private fun observeViewModel() {
        lifecycleScope.launch {
            combine(viewModel.centerFreqHz, viewModel.capabilities) { hz, caps -> caps.supportsFrequency(hz) }
                .distinctUntilChanged().collect { receiving ->
                    if (!receiving) {
                        binding.spectrumView.clearSpectrum(); binding.waterfallView.clearHistory()
                        binding.tvSignalDb.text = "--- dBFS"; updateSignalMeter(-120f)
                    }
                    binding.spectrumView.alpha = if (receiving) 1f else 0.4f
                }
        }

        lifecycleScope.launch {
            combine(viewModel.centerFreqHz, viewModel.autoDemod, viewModel.capabilities) { hz, auto, caps ->
                if (!caps.supportsFrequency(hz)) return@combine "HF+ 盲区" to "31–64 MHz"
                val band=com.radiosport.ninegradio.dsp.ReceiverBandPlan.at(hz)
                val label=band.name.replace(" · "," ")+if(auto) " AUTO" else ""
                val range=if(band.end>band.start) {
                    // Compact broad bands, but keep meaningful precision for narrow ham bands.
                    if(band.end-band.start>=1_000_000) String.format(java.util.Locale.ROOT,"%.1f–%.1f MHz",band.start/1e6,band.end/1e6)
                    else "${band.start.toExactMhzString()}–${band.end.toExactMhzString()} MHz"
                } else ""
                label to range
            }.distinctUntilChanged().collect { (label,range) ->
                binding.tvBandContext.text=label
                binding.tvBandContext.contentDescription="$label $range".trim()
                binding.tvBandRange.text=range
                binding.tvBandRange.visibility=if(range.isEmpty()) View.GONE else View.VISIBLE
            }
        }
        binding.tvSquelchStatus.contentDescription = getString(R.string.receiver_power_hint)
        binding.tvSquelchStatus.setOnClickListener {
            val state = viewModel.connectionState.value
            val start = !(viewModel.receiverEnabled.value && (state is RtlSdrService.ConnectionState.Connected || state is RtlSdrService.ConnectionState.Connecting))
            if (!start) etm.stop()
            viewModel.setReceiverEnabled(start)
        }
        lifecycleScope.launch {
            combine(viewModel.receiverEnabled, viewModel.connectionState) { requested, state ->
                requested && state is RtlSdrService.ConnectionState.Connected
            }.distinctUntilChanged().collect { enabled ->
                binding.tvSquelchStatus.isSelected = enabled
                binding.tvSquelchStatus.text = if (enabled) "OPEN" else "CLOSED"
                binding.tvSquelchStatus.setTextColor(if (enabled) 0xFF00FF88.toInt() else 0xFFFF4444.toInt())
            }
        }

        lifecycleScope.launch { viewModel.messages.collect { showSnackbar(it) } }

        // Use plain collect (not collectLatest) for spectrum and waterfall.
        // collectLatest cancels the running lambda the moment a newer value arrives.
        // Spectrum updates come at ~15 fps; postInvalidateOnAnimation schedules the
        // actual draw for the next vsync -- if collectLatest cancels the lambda before
        // that vsync fires, the invalidate call is dropped and nothing ever redraws.
        // plain collect processes every emission to completion before taking the next.
        // Mirror auto-range computed values to WaterfallView so both views
        // always share the same dB scale when Auto dB Range is active.
        binding.spectrumView.onAutoRangeChanged = { floor: Float, ceiling: Float ->
            binding.waterfallView.setDynamicRange(floor, ceiling)
            // Keep the Display-tab sliders live. The Slider requires values to be
            // exact multiples of stepSize(1.0) so we must round before setting.
            ctrlViews.sliderSpecFloor?.let { sl ->
                val rounded = kotlin.math.round(floor).toFloat().coerceIn(sl.valueFrom, sl.valueTo)
                if (sl.value != rounded) sl.value = rounded
                ctrlViews.tvSpecFloorValue?.text = rounded.toInt().toString()
            }
            ctrlViews.sliderSpecCeiling?.let { sl ->
                val rounded = kotlin.math.round(ceiling).toFloat().coerceIn(sl.valueFrom, sl.valueTo)
                if (sl.value != rounded) sl.value = rounded
                ctrlViews.tvSpecCeilingValue?.text = rounded.toInt().toString()
            }
        }

        // Force the waterfall's zoom/pan to always follow the spectrum's.
        // WaterfallView used to run its own independent
        // ScaleGestureDetector/GestureDetector for pinch-zoom/pan, mirrored
        // back to SpectrumView via a matching onZoomPanChanged callback.
        // That two-way, best-effort mirroring is what allowed the waterfall
        // to advance its own zoom/pan state ahead of a round trip and
        // "stretch" out of line with the spectrum (the FFT trace and
        // waterfall bitmap ending up at different widths/offsets even
        // though the underlying frequency data was correctly aligned -- see
        // setHardwareTunedFrequency's doc comment for the *other* kind of
        // trace/waterfall misalignment this is not).
        //
        // WaterfallView's pinch-zoom gesture handling is now disabled at
        // the source (see its "Gesture detectors" section), so
        // SpectrumView is the single source of truth: only its callback is
        // wired, and it always pushes the waterfall into lock-step via
        // setZoomPan(), which no-ops when the values already match.
        // WaterfallView.onZoomPanChanged is intentionally left unwired --
        // it can no longer fire (zoom/pan gestures on the waterfall are a
        // no-op), but leaving the one-way wiring explicit here (rather than
        // a mutual mirror) guarantees the waterfall can never again drive
        // its own zoom/pan independently of the spectrum.
        binding.spectrumView.onZoomPanChanged = { zoom, offsetHz ->
            binding.waterfallView.setZoomPan(zoom, offsetHz)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.spectrumData.collect { data ->
                    if (viewModel.capabilities.value.supportsFrequency(viewModel.centerFreqHz.value)) {
                        binding.spectrumView.updateSpectrum(data, viewModel.peakData.value)
                        binding.waterfallView.addLine(data)
                    }
                }
            }
        }
        lifecycleScope.launch {
            viewModel.dspStats.sample(200L).collect { stats ->
                ctrlViews.spinnerRecordStop?.isEnabled=!(stats.isRecordingIq || stats.isRecordingAudio)
                pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_RECORDING)?.findViewById<TextView>(R.id.tvRecordingState)?.apply {
                    val parts=listOf(false to stats.isRecordingIq,true to stats.isRecordingAudio).filter { it.second }.map { (audio,_) ->
                        val remaining=sdrService?.recordingRemainingMs(audio)
                        (if(audio) "声音" else "IQ")+(if(remaining==null) "录制中" else " · ${remaining/60000}:${"%02d".format((remaining/1000)%60)}")
                    }
                    text=parts.joinToString("  /  ")+(if(parts.isNotEmpty() && sdrService?.dspEngine?.recordingGateOpen==false) " · 等待信号" else "")
                    isVisible=parts.isNotEmpty()
                }
                ctrlViews.btnRecordIq?.text=if(stats.isRecordingIq) "停止 IQ" else "录制 IQ"
                ctrlViews.btnRecordAudio?.text=if(stats.isRecordingAudio) "停止录音" else "录制声音"
                styleRecordingAction(ctrlViews.btnRecordIq,stats.isRecordingIq)
                styleRecordingAction(ctrlViews.btnRecordAudio,stats.isRecordingAudio)
            }
        }
        // Receiver statistics never change the manual listening switch.
        lifecycleScope.launch {
                viewModel.dspStats.map { it.signalDb }.sample(200L).distinctUntilChanged().collect { signalDb ->
                if (viewModel.connectionState.value is RtlSdrService.ConnectionState.Connected && viewModel.capabilities.value.supportsFrequency(viewModel.centerFreqHz.value)) {
                    updateSignalMeter(signalDb)
                    val signalText = "${"%.1f".format(signalDb)} dBFS"
                    if (binding.tvSignalDb.text.toString() != signalText) binding.tvSignalDb.text = signalText
                }
            }
        }

        lifecycleScope.launch {
            combine(
                viewModel.demodMode.map { it.displayName }.distinctUntilChanged(),
                viewModel.sampleRate
            ) { mode, rate -> mode to rate }.collect { (mode, displayRate) ->
                binding.tvDemodMode.text = mode
                binding.tvSampleRate.text = if (displayRate >= 1_000_000)
                    "${"%.3f".format(displayRate / 1e6)} MS/s"
                else "${displayRate / 1000} kS/s"
            }
        }

        lifecycleScope.launch {
            viewModel.connectionState.collectLatest { state ->
                when (state) {
                    is RtlSdrService.ConnectionState.Connected -> {
                        lastReceiverModel=state.deviceName
                        applyReceiverCapabilities()
                        updateConnectionUi(true)
                        binding.tvDeviceName.text = state.deviceName
                        val rate = viewModel.sampleRate.value
                        binding.tvSampleRate.text = if (rate >= 1_000_000)
                            "${"%.3f".format(rate / 1e6)} MS/s" else "${rate / 1000} kS/s"
                        showSnackbar("已连接 ${state.deviceName}")
                        // Auto-enable out-of-band for RTL-SDR Blog V4 if not already on
                        if (viewModel.isV4 && !viewModel.allowOutOfBand.value) {
                            viewModel.setAllowOutOfBand(true)
                            ctrlViews.switchOutOfBound?.isChecked = true
                        }
                        // Apply all saved settings now that the device is open
                        applyAllSettings()
                        applyReceiverCapabilities()
                    }
                    is RtlSdrService.ConnectionState.Disconnected -> { if (::etm.isInitialized) etm.stop(); updateConnectionUi(false) }
                    is RtlSdrService.ConnectionState.Connecting -> {
                        binding.statusIndicator.setImageResource(R.drawable.ic_disconnected)
                        binding.tvDeviceName.text = lastReceiverModel ?: "正在连接…"
                        binding.progressConnect.isVisible = true
                    }
                    is RtlSdrService.ConnectionState.Error -> {
                        updateConnectionUi(false)
                        showSnackbar("Error: ${state.message}")
                    }
                }
            }
        }
        // (channelHighlightHz is now a private member function -- see below)

        lifecycleScope.launch {
            viewModel.demodMode.collectLatest { mode ->
                restorePlotContext(mode)
                syncNoiseProcessingControls()
                if(::ctrlViews.isInitialized && ctrlViews.layoutSampleRateChips!=null) setupSampleRateChips()
                        // Update chip selection
                modeChipGroups().forEach { chipGroup ->
                    for (i in 0 until chipGroup.childCount) {
                        val chip = chipGroup.getChildAt(i) as? com.google.android.material.button.MaterialButton ?: continue
                        chip.isSelected = (chip.tag as? DemodMode) == mode
                    }
                }
                // Switching into/out of USB or LSB re-tunes the hardware (see
                // DspEngine.setCarrierFrequency's SSB_BFO_OFFSET_HZ) even though the
                // dial frequency itself doesn't change, so the spectrum/waterfall
                // must be re-centred here too -- otherwise the FFT trace stays
                // aligned to the old hardware frequency and drifts out of the
                // passband highlight box the moment the mode switches.
                binding.spectrumView.setCenterFrequency(hardwareCenterFreqHz(viewModel.centerFreqHz.value, mode))
                binding.spectrumView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(viewModel.centerFreqHz.value, mode))
                binding.spectrumView.setDemodDialFrequency(viewModel.centerFreqHz.value)
                binding.waterfallView.setCenterFrequency(hardwareCenterFreqHz(viewModel.centerFreqHz.value, mode))
                binding.waterfallView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(viewModel.centerFreqHz.value, mode))
                // Update channel bandwidth highlight using current IF bandwidth
                val ifBw = viewModel.ifBandwidthHz.value
                binding.spectrumView.setDemodChannelBandwidth(channelHighlightHz(mode, ifBw), channelHighlightSideband(mode))
                // Re-constrain the bandwidth slider to this mode's supported range.
                // Use the current effective bandwidth (or mode default if auto).
                val defaultBw = mode.defaultBwHz.coerceAtLeast(DspEngine.MIN_IF_BANDWIDTH_HZ)
                val effectiveBw = if (ifBw > 0) ifBw else defaultBw
                applyBwRange(mode, effectiveBw)
                // Sync tuning dial and step chips with the new mode
                onDemodModeChanged(mode)
                // setDemodMode() may have just restored a per-protocol Display/RF
                // snapshot into defaultSharedPreferences (frame averaging, dB
                // floor/ceiling, theme, palette, etc.) — push it into the engine
                // and drawer UI now, otherwise it sits unapplied until some other
                // unrelated trigger happens to call applyDisplayPreferences().
                applyDisplayPreferences()
                if (::ctrlViews.isInitialized) {
                    binding.controlTabLayout.selectedTabPosition.takeIf { it >= 0 }?.let { pagerAdapter.moduleAt(it) }
                        ?.let { syncTabControls(it) }
                }
                // Show/hide APRS drawer tab
                observeAprsMode(mode)
                updateAdsbTabVisibility(mode)
                // Show/hide ACARS drawer tab
                observeAcarsMode(mode)
                // Show/hide digital voice protocol drawer tabs
                observeDmrMode(mode)
                observeYsfMode(mode)
                observeDstarMode(mode)
            }
        }

        // Keep the APRS and ACARS tab frequency/rate labels always current.
        // Previously these labels were only written once, when the tab became
        // visible (mode switch) -- subsequent tuning (dial, band presets, step
        // chips, scanner hits, bookmark loads, freq +/- buttons) never touched
        // them, so they went stale until the user left and re-entered the mode.
        // Driving them off the centerFreqHz/sampleRate flows means every
        // frequency-changing code path is covered automatically, with no need
        // to hunt down and patch each call site individually.
        lifecycleScope.launch {
            viewModel.centerFreqHz.collect { hz ->
                contentController?.frequencyChanged(hz)
                if(binding.frequencyView.getFrequency()!=hz) syncFrequencyDisplay()
                else updateTuneFrequency(hz)
                val mode = viewModel.demodMode.value
                val mhz  = "%.3f".format(hz / 1e6)
                val rate = viewModel.sampleRate.value / 1000
                when (mode) {
                    DemodMode.APRS  -> ctrlViews.tvAprsTabMode?.text  = "$mhz MHz  |  $rate kS/s"
                    DemodMode.ACARS -> ctrlViews.tvAcarsTabMode?.text = "$mhz MHz  |  $rate kS/s"
                    DemodMode.DMR   -> ctrlViews.tvDmrTabMode?.text   = "$mhz MHz  |  $rate kS/s"
                    DemodMode.DIG   -> ctrlViews.tvDigTabMode?.text   = "$mhz MHz  |  $rate kS/s"
                    DemodMode.YSF   -> ctrlViews.tvYsfTabMode?.text   = "$mhz MHz  |  $rate kS/s"
                    DemodMode.DSTAR -> ctrlViews.tvDstarTabMode?.text = "$mhz MHz  |  $rate kS/s"
                    else -> Unit
                }
            }
        }
        lifecycleScope.launch {
            viewModel.sampleRate.collect { rate ->
                val mode = viewModel.demodMode.value
                val mhz  = "%.3f".format(viewModel.centerFreqHz.value / 1e6)
                val ks   = rate / 1000
                when (mode) {
                    DemodMode.APRS  -> ctrlViews.tvAprsTabMode?.text  = "$mhz MHz  |  $ks kS/s"
                    DemodMode.ACARS -> ctrlViews.tvAcarsTabMode?.text = "$mhz MHz  |  $ks kS/s"
                    DemodMode.DMR   -> ctrlViews.tvDmrTabMode?.text   = "$mhz MHz  |  $ks kS/s"
                    DemodMode.DIG   -> ctrlViews.tvDigTabMode?.text   = "$mhz MHz  |  $ks kS/s"
                    DemodMode.YSF   -> ctrlViews.tvYsfTabMode?.text   = "$mhz MHz  |  $ks kS/s"
                    DemodMode.DSTAR -> ctrlViews.tvDstarTabMode?.text = "$mhz MHz  |  $ks kS/s"
                    else -> Unit
                }
            }
        }

        // Keep the spectrum highlight in sync whenever the IF bandwidth slider moves.
        lifecycleScope.launch {
            viewModel.ifBandwidthHz.collect { ifBw ->
                updatePlotBandwidth()
                val mode = viewModel.demodMode.value
                binding.spectrumView.setDemodChannelBandwidth(channelHighlightHz(mode, ifBw), channelHighlightSideband(mode))
            }
        }
        lifecycleScope.launch {
            viewModel.bookmarks.collect { bms ->
                binding.spectrumView.clearBookmarks()
                bms.forEach { bm -> binding.spectrumView.addBookmark(bm.frequencyHz, bm.label) }
            }
        }

        // Propagate FFT size changes to the DSP engine and views immediately.
        // Without this observer the engine only receives the new size via the
        // next service call, and the views only see it via the next spectrum
        // array from DspEngine -- which may be delayed or not arrive at all if
        // the device is not yet connected.
        lifecycleScope.launch {
            viewModel.fftSize.collectLatest { size ->
                // Push to engine/service in case it was set before the service bound
                // (setFftSize already guards against no-op when size is unchanged).
                sdrService?.setFftSize(size)
                // Inform views of the new effective sample rate (accounting for decimation)
                // so they pre-warm their internal state (e.g. peak-hold arrays).
                val effectiveRate = viewModel.sampleRate.value / viewModel.decimation.value.coerceAtLeast(1)
                binding.spectrumView.setSampleRate(effectiveRate)
                binding.waterfallView.setSampleRate(effectiveRate)
                updatePlotSpan()
            }
        }

        // Propagate decimation changes to the DSP engine and views immediately.
        lifecycleScope.launch {
            combine(viewModel.decimation,viewModel.sampleRate) { factor,rate -> factor to rate }.collectLatest { (factor,rate) ->
                sdrService?.setFftDecimation(factor)
                val effectiveRate = rate / factor.coerceAtLeast(1)
                binding.spectrumView.setSampleRate(effectiveRate)
                binding.waterfallView.setSampleRate(effectiveRate)
                val magnification=SpectrumZoom.window(plotZoom).magnification
                plotZoom=factor*magnification
                updatePlotSpan()
                val mode=viewModel.demodMode.value
                val hz=viewModel.ifBandwidthHz.value.takeIf { it>0 } ?: mode.defaultBwHz
                applyBwRange(mode,hz)
                binding.spectrumView.setDemodChannelBandwidth(channelHighlightHz(mode,viewModel.ifBandwidthHz.value),channelHighlightSideband(mode))
                savePlotZoom()
            }
        }
    }

    // ─── USB handling ─────────────────────────────────────────────────────────

    private fun handleUsbDeviceAttached(device: UsbDevice) {
        // While a USB → Rtl-sdr Driver switch is in progress, Android re-enumerates the
        // dongle after we release the interface and fires ACTION_USB_DEVICE_ATTACHED.
        // Suppress that event so it doesn't immediately reconnect USB and undo the switch.
        if (suppressUsbAttach) {
            android.util.Log.i("MainActivity", "handleUsbDeviceAttached suppressed during driver switch")
            return
        }
        val usbManager = getSystemService(UsbManager::class.java)
        if (!usbManager.hasPermission(device)) {
            // FLAG_MUTABLE is required (API 31+) so the system can inject
            // EXTRA_PERMISSION_GRANTED and EXTRA_DEVICE into the broadcast.
            // FLAG_IMMUTABLE silently drops those extras, so permission always
            // appears denied.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT

            // Android 14+ (API 34) bans FLAG_MUTABLE with an implicit Intent.
            // Making the Intent explicit (setPackage) satisfies the restriction while
            // keeping FLAG_MUTABLE so the system can inject EXTRA_PERMISSION_GRANTED
            // and EXTRA_DEVICE into the delivered broadcast.
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0,
                Intent("com.radiosport.ninegradio.USB_PERMISSION").setPackage(packageName),
                flags
            )

            val permReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        viewModel.connectDevice(device)
                    } else {
                        showSnackbar("USB permission denied")
                    }
                    try { unregisterReceiver(this) } catch (_: Exception) {}
                    if (pendingPermReceiver === this) pendingPermReceiver = null
                }
            }

            // RECEIVER_EXPORTED is required here: on Android 12+ (API 31+), UsbManager
            // sends the permission result broadcast with the system UID as the caller.
            // RECEIVER_NOT_EXPORTED silently drops broadcasts from outside the app UID,
            // which means the permission dialog result is never delivered and the device
            // never connects. Using RECEIVER_EXPORTED is safe because the custom action
            // string limits exposure, and a spoofed grant cannot actually open the device.
            //
            // Clean up any previous pending receiver before registering a new one.
            pendingPermReceiver?.let {
                try { unregisterReceiver(it) } catch (_: Exception) {}
            }
            pendingPermReceiver = permReceiver
            ContextCompat.registerReceiver(
                this,
                permReceiver,
                IntentFilter("com.radiosport.ninegradio.USB_PERMISSION"),
                ContextCompat.RECEIVER_EXPORTED
            )

            usbManager.requestPermission(device, permissionIntent)
        } else {
            viewModel.connectDevice(device)
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Returns gain in dB for [index], resolved against the active source's gain table.
     * Falls back to the USB device table (the common R820T table) when no source is connected.
     */
    private fun gainDbAtIndex(index: Int): Float {
        if (viewModel.capabilities.value.id == "airspyhf") return index.coerceIn(0, 8) * 6f
        val src = sdrService?.source ?: viewModel.getService()?.source
        return if (src != null) {
            // IqSource.getGainDb() returns the dB for the *current* index, not for an
            // arbitrary index.  Ask for the dB via getGainDb() only when index matches,
            // otherwise fall back to the USB lookup table (both sources use the same
            // R820T values in practice).
            if (index == src.getGainIndex()) src.getGainDb()
            else RtlSdrDevice.GAIN_TABLE_DB_TENTHS.getOrElse(index) { 0 } / 10f
        } else {
            RtlSdrDevice.GAIN_TABLE_DB_TENTHS.getOrElse(index) { 0 } / 10f
        }
    }

    /** Update the gain dB label from a gain-table index. */
    private fun syncGainLabel(index: Int) {
        val db = gainDbAtIndex(index)
        if (::ctrlViews.isInitialized) ctrlViews.tvGainValue?.text = "${"%.1f".format(db)} dB"
    }

    private fun applyAllSettings() {
        val v = viewModel
        val svc = viewModel.getService() ?: sdrService ?: return
        val ctrl = if (::ctrlViews.isInitialized) ctrlViews else null
        val sliderGain = ctrl?.sliderGain
        val switchTunerAgc = ctrl?.switchTunerAgc
        val switchHardwareAgc = ctrl?.switchHardwareAgc
        svc.setFrequency(v.centerFreqHz.value)
        svc.setSampleRate(v.sampleRate.value)
        svc.setTunerAgc(v.tunerAgcEnabled.value)
        svc.setHardwareAgc(v.hardwareAgcEnabled.value)
        // Guard: RtlSdrDevice.setGain() writes LNA/Mixer-auto=OFF unconditionally;
        // calling it when tuner AGC is enabled would override the AGC mode just set.
        if (!v.tunerAgcEnabled.value) svc.setGain(v.gainIndex.value)
        // Sync gain UI to the authoritative ViewModel values (persisted across restarts).
        // Recalibrate the slider range using the active source's gain count -- the TCP
        // source may report a different number of steps than the USB device table.
        val tunerAgc = v.tunerAgcEnabled.value
        val hwAgc    = v.hardwareAgcEnabled.value
        if (sliderGain != null) {
            val gainCount = svc.source?.getGainCount()
                ?.coerceAtLeast(2)
                ?: RtlSdrDevice.GAIN_TABLE_DB_TENTHS.size
            sliderGain.valueFrom = 0f
            sliderGain.valueTo = (gainCount - 1).toFloat()
            sliderGain.setLabelFormatter { vv ->
                "${"%.1f".format(gainDbAtIndex(vv.toInt()))} dB"
            }
        }
        switchTunerAgc?.setOnCheckedChangeListener(null)
        switchTunerAgc?.isChecked = tunerAgc
        sliderGain?.isEnabled = !tunerAgc
        switchTunerAgc?.setOnCheckedChangeListener { _, checked ->
            viewModel.setTunerAgc(checked)
            sliderGain?.isEnabled = !checked
        }
        switchHardwareAgc?.setOnCheckedChangeListener(null)
        switchHardwareAgc?.isChecked = hwAgc
        switchHardwareAgc?.setOnCheckedChangeListener { _, checked ->
            viewModel.setHardwareAgc(checked)
        }
        val gainIdx = v.gainIndex.value.toFloat()
            .coerceIn(sliderGain?.valueFrom ?: 0f, sliderGain?.valueTo ?: 28f)
        sliderGain?.value = gainIdx
        syncGainLabel(gainIdx.toInt())
        svc.setBiasTee(v.biasTee.value)
        svc.setDirectSampling(v.directSampling.value)
        svc.setPpm(v.ppm.value)
        binding.spectrumView.setPpmCorrection(v.ppm.value)
        binding.waterfallView.setPpmCorrection(v.ppm.value)
        ctrlViews.sliderPpm?.value = v.ppm.value.toFloat().coerceIn(-50f, 50f)
        ctrlViews.tvPpmValue?.text = "${v.ppm.value} ppm"
        svc.setSquelch(v.squelch.value)
        svc.setAudioVolume(v.volume.value)
        svc.setDemodMode(v.demodMode.value)
        // DspEngine.setDemodMode() unconditionally resets its internal
        // ifBandwidthHz to the mode default (or the -1 "auto" sentinel) as
        // part of switching demodulators -- even when the mode hasn't
        // actually changed (e.g. on reconnect). Without re-applying the
        // user's saved Protocol Filter width here, the "Width" slider on the
        // RF tab keeps showing the user's chosen value while the DSP engine
        // silently falls back to the mode default, making the control
        // appear to do nothing after a reconnect.
        if (v.ifBandwidthHz.value > 0) {
            svc.setIfBandwidth(v.ifBandwidthHz.value)
        }
        svc.setFftDecimation(v.decimation.value)
        val hz = v.centerFreqHz.value
        val rate = v.sampleRate.value
        val effectiveRate = rate / v.decimation.value.coerceAtLeast(1)
        binding.frequencyView.setFrequency(hz)
        binding.spectrumView.setCenterFrequency(hardwareCenterFreqHz(hz))
        binding.spectrumView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(hz))
        binding.spectrumView.setDemodDialFrequency(hz)
        binding.waterfallView.setCenterFrequency(hardwareCenterFreqHz(hz))
        binding.waterfallView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(hz))
        binding.spectrumView.setSampleRate(effectiveRate)
        binding.waterfallView.setSampleRate(effectiveRate)
        ctrlViews.tuningDial?.currentFreqHz = hz
    }

    private fun styleRecordingAction(button: android.widget.Button?,recording: Boolean) {
        button ?: return
        if(button.compoundDrawablesRelative[0]==null) button.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_record_radio,0,0,0)
        button.backgroundTintList=android.content.res.ColorStateList.valueOf(androidx.core.graphics.ColorUtils.blendARGB(UiThemes.color(this,R.attr.radioSurface),0xffff757b.toInt(),if(recording) 0.24f else 0.08f))
        button.setTextColor(if(recording) 0xffffa0a5.toInt() else 0xffff757b.toInt())
    }
    private var lastReceiverModel: String?=null
    private fun updateConnectionUi(connected: Boolean) {
        binding.progressConnect.isVisible = false
        binding.statusIndicator.setImageResource(
            if (connected) R.drawable.ic_connected else R.drawable.ic_disconnected
        )
        binding.tvDeviceName.text = lastReceiverModel ?: if(connected) "接收机" else "未连接"

        // When disconnected, clear the live-data widgets so stale readings
        // (−120.0 dBFS, 0 kS/s) don't persist after the device is unplugged.
        if (!connected) {
            binding.tvSignalDb.text      = "--- dBFS"
            binding.tvSampleRate.text    = "0 kS/s"
            updateSignalMeter(-120f)
        }
    }

    private fun updateSignalMeter(db: Float) {
        val progress = ((db + 120f) / 120f * 100).toInt().coerceIn(0, 100)
        binding.signalMeter.progress = progress
        binding.signalMeter.progressDrawable?.setLevel(progress * 100)
    }

    /** Public entry point used by Settings drawer preference and overflow menu item. */
    private fun setupSampleRateChips() {
        if (ctrlViews.layoutSampleRateChips == null) return
        val rates = viewModel.availableSampleRates
        val sampleRateLabels = rates.map { rate ->
            val divLabel = if (rate % DspEngine.DEFAULT_AUDIO_RATE == 0)
                " ÷${rate / DspEngine.DEFAULT_AUDIO_RATE}" else ""
            if (rate >= 1_000_000) "${"%.3f".format(rate / 1e6)}M$divLabel"
            else "${"%.0f".format(rate / 1e3)}k$divLabel"
        }
        val savedIdx = rates.indexOfFirst { it == viewModel.sampleRate.value }
            .let { if (it >= 0) it else rates.indexOfFirst { r -> r == 1_920_000 }.coerceAtLeast(0) }

        sampleRateLabelsCache = sampleRateLabels

        ctrlViews.layoutSampleRateChips!!.removeAllViews()
        val dp = resources.displayMetrics.density
        sampleRateLabels.forEachIndexed { idx, label ->
            val btn = android.widget.TextView(this).apply {
                text = label
                isEnabled=viewModel.demodMode.value!=DemodMode.ADSB || rates[idx]==2_400_000
                alpha=if(isEnabled) 1f else 0.35f
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins((3 * dp).toInt(), 0, (3 * dp).toInt(), 0) }
                setOnClickListener {
                    val rate = rates[idx]
                    ctrlViews.tvSampleRateLabel!!.text = sampleRateLabels[idx]
                    viewModel.setSampleRate(rate)
                    val effectiveRate = rate / viewModel.decimation.value.coerceAtLeast(1)
                    binding.spectrumView.setSampleRate(effectiveRate)
                    binding.waterfallView.setSampleRate(effectiveRate)
                    // Refresh WFM channel highlight -- wfmIfRate depends on device rate
                    binding.spectrumView.setDemodChannelBandwidth(
                        channelHighlightHz(viewModel.demodMode.value, viewModel.ifBandwidthHz.value),
                        channelHighlightSideband(viewModel.demodMode.value))
                    updateSampleRateChips(idx)
                    // Scroll selected chip into view
                    (ctrlViews.layoutSampleRateChips!!.parent as? android.widget.HorizontalScrollView)
                        ?.smoothScrollTo(this.left - (40 * dp).toInt(), 0)
                }
            }
            ctrlViews.layoutSampleRateChips!!.addView(btn)
        }
        ctrlViews.tvSampleRateLabel!!.text = sampleRateLabels[savedIdx]
        updateSampleRateChips(savedIdx)
        // Scroll to show the selected chip on first render
        ctrlViews.layoutSampleRateChips!!.post {
            val selected = ctrlViews.layoutSampleRateChips!!.getChildAt(savedIdx)
            (ctrlViews.layoutSampleRateChips!!.parent as? android.widget.HorizontalScrollView)
                ?.scrollTo((selected?.left ?: 0) - (40 * dp).toInt(), 0)
        }
    }

    private fun setupGraphControls() {
        pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_DISPLAY)?.findViewById<com.google.android.material.slider.Slider>(R.id.sliderPlotZoom)?.apply {
            clearOnChangeListeners(); clearOnSliderTouchListeners()
            value = (kotlin.math.ln(plotZoom.toDouble()) / kotlin.math.ln(2.0)).toFloat()
            setLabelFormatter { value -> "${"%.1f".format(Math.pow(2.0, value.toDouble()))}x" }
            addOnChangeListener { _, v, fromUser -> if (fromUser) setPlotZoom(Math.pow(2.0, v.toDouble()).toFloat(), false) }
            addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
                override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) { savePlotZoom() }
            })
        }
        if (!::graphLayout.isInitialized) return
        graphLayout.bindSlider(ctrlViews.sliderSpecWfRatio, ctrlViews.tvSpecWfRatioValue)
        val page=pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_DISPLAY) ?: return
        page.findViewById<android.widget.FrameLayout>(R.id.inlineDisplayLevels).apply {
            removeAllViews()
            addView(DisplayLevelControls(this@MainActivity,binding.waterfallView,binding.spectrumView) { low,high,auto ->
                ctrlViews.switchAutoRange?.isChecked=auto
                ctrlViews.sliderSpecFloor?.let { it.value=low.toFloat().coerceIn(it.valueFrom,it.valueTo) }
                ctrlViews.sliderSpecCeiling?.let { it.value=high.toFloat().coerceIn(it.valueFrom,it.valueTo) }
                ctrlViews.tvSpecFloorValue?.text=low.toString(); ctrlViews.tvSpecCeilingValue?.text=high.toString()
            })
        }
        // Keep the existing bindings as state bridges, with one visible editor.
        for(id in listOf(R.id.sliderSpecFloor,R.id.sliderSpecCeiling,R.id.switchAutoRange,R.id.switchAutoStretch))
            (page.findViewById<View>(id)?.parent as? View)?.visibility=View.GONE
        page.findViewById<View>(R.id.btnResetDbRange)?.visibility=View.GONE
    }

    private fun showWaterfallQuickAdjust() {
        val position=pagerAdapter.positionOf(ControlsPagerAdapter.TAB_DISPLAY)
        if(position<0) { showSnackbar("频谱与瀑布模块已隐藏"); return }
        binding.controlTabLayout.getTabAt(position)?.select()
        com.google.android.material.bottomsheet.BottomSheetBehavior.from(binding.controlsBottomSheet).state=
            com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        (pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_DISPLAY) as? android.widget.ScrollView)?.smoothScrollTo(0,0)
    }
    private fun showWaterfallPresets() {
        val choices = intArrayOf(R.string.waterfall_preset_clear, R.string.waterfall_preset_weak,
            R.string.waterfall_more, R.string.waterfall_balanced, R.string.waterfall_less,
            R.string.waterfall_preset_reset).map { getString(it) }.toTypedArray()
        AlertDialog.Builder(this).setTitle(R.string.waterfall_adjust_title)
            .setItems(choices) { _, index ->
                when (index) {
                    2 -> graphLayout.setRatio(0.25f)
                    3 -> graphLayout.setRatio(0.4f)
                    4 -> graphLayout.setRatio(0.65f)
                    else -> {
                        val weak = index == 1
                        val reset = index == 5
                        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                        prefs.edit().putString("pref_waterfall_palette", if (weak) "INFERNO" else "BLUE_WHITE")
                            .putBoolean("pref_waterfall_auto_stretch", true)
                            .putInt("pref_waterfall_brightness", 50)
                            .putInt("pref_waterfall_contrast", if (weak) 65 else 50).apply()
                        if (reset) graphLayout.setRatio(0.4f)
                        applyDisplayPreferences()
                        syncTabControls(ControlsPagerAdapter.TAB_DISPLAY)
                    }
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun syncNoiseProcessingControls() {
        if(!::ctrlViews.isInitialized) return
        val allowed=com.radiosport.ninegradio.dsp.NoiseProcessingPolicy.allows(viewModel.demodMode.value)
        val native=com.radiosport.ninegradio.dsp.NativeDsp.isNativeAvailable()
        ctrlViews.switchNoiseBlanker?.apply {
            setOnCheckedChangeListener(null)
            isChecked=allowed && viewModel.noiseBlankerEnabled.value
            isEnabled=allowed && native
            tooltipText=if(!allowed) "当前解调模式旁路降噪" else if(!native) "原生脉冲抑制模块未加载" else null
            setOnCheckedChangeListener { _,checked -> viewModel.setNoiseBlanker(checked); syncNoiseProcessingControls() }
        }
        ctrlViews.switchNoiseReducer?.apply {
            setOnCheckedChangeListener(null)
            isChecked=allowed && viewModel.noiseReducerEnabled.value
            isEnabled=allowed
            tooltipText=if(allowed) null else "当前解调模式旁路降噪"
            setOnCheckedChangeListener { _,checked -> viewModel.setNoiseReducer(checked); syncNoiseProcessingControls() }
        }
        pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_RF)?.let { page ->
            page.findViewById<View>(R.id.tvNoiseProcessingState)?.isVisible=!allowed
            page.findViewById<com.google.android.material.slider.Slider>(R.id.sliderNrStrength)?.isEnabled=allowed && viewModel.noiseReducerEnabled.value
            page.findViewById<TextView>(R.id.tvIqNbThreshold)?.text="脉冲门限　${viewModel.iqNbThreshold.value.toInt()}×"
            page.findViewById<com.google.android.material.slider.Slider>(R.id.sliderIqNbThreshold)?.apply {
                clearOnChangeListeners()
                value=kotlin.math.round(viewModel.iqNbThreshold.value).coerceIn(valueFrom,valueTo)
                isEnabled=allowed && native && viewModel.noiseBlankerEnabled.value
                addOnChangeListener { _,next,user -> if(user) viewModel.setIqNoiseBlankerThreshold(next) }
            }
        }
    }

    private fun applyReceiverCapabilities() {
        setupDemodModeChips()
        val caps = viewModel.capabilities.value
        restorePlotContext(viewModel.demodMode.value)
        setupSampleRateChips()
        setupBandPresetChips()
        pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_RF)?.findViewById<com.google.android.material.slider.Slider>(R.id.sliderNrStrength)?.apply {
            clearOnChangeListeners()
            value = getSharedPreferences("rtlsdr_prefs", MODE_PRIVATE).getFloat("nrStrength", 1f).coerceIn(0f, 1.5f)
            addOnChangeListener { _, strength, fromUser -> if (fromUser) viewModel.setNoiseReductionStrength(strength) }
        }
        pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_RF)?.let { ReceiverControlUi.apply(it, caps) }
        (ctrlViews.switchOutOfBound?.parent as? View)?.isVisible = false
        ctrlViews.switchOutOfBound?.isEnabled = caps.id == "rtl"
        if (caps.id == "file") {
            ctrlViews.sliderGain?.isEnabled = false
            ctrlViews.switchTunerAgc?.isEnabled = false
        }
        ctrlViews.sliderSquelch?.isEnabled = true
        (ctrlViews.sliderSquelch?.parent as? android.view.View)?.visibility = android.view.View.VISIBLE
        ctrlViews.sliderSquelch?.tooltipText = null
        binding.spectrumView.setSquelch(viewModel.squelch.value)
        ctrlViews.sliderGain?.isEnabled = caps.id != "file" && !viewModel.tunerAgcEnabled.value
        ctrlViews.switchTunerAgc?.isEnabled = caps.id != "file"
        syncGainLabel(viewModel.gainIndex.value)
        ctrlViews.switchHardwareAgc?.isEnabled = caps.hardwareAgc
        ctrlViews.switchBiasTee?.isEnabled = caps.biasTee
        ctrlViews.spinnerDirectSampling?.isEnabled = caps.directSampling
        ctrlViews.switchHardwareAgc?.tooltipText = if (caps.hardwareAgc) null else "当前接收机不支持 RTL 数字 AGC"
        ctrlViews.switchBiasTee?.tooltipText = if (caps.biasTee) null else "当前接收机不支持偏置供电"
        ctrlViews.spinnerDirectSampling?.tooltipText = if (caps.directSampling) null else "HF+ 无需 RTL 直采"
        modeChipGroups().forEach { group ->
            for (i in 0 until group.childCount) {
                val chip = group.getChildAt(i) as? com.google.android.material.button.MaterialButton ?: continue
                val mode = chip.tag as? DemodMode ?: continue
                chip.isEnabled = caps.supportsMode(mode)
                chip.tooltipText = if (chip.isEnabled) null else "超出当前接收机的频率或带宽能力"
            }
        }
    }

    fun showDeviceInfoDialog() = showDeviceInfo()

    /**
     * Device Information / Source-selection dialog.
     *
     * Layout:
     *  ┌─ current source info ──────────────────────────────────┐
     *  │ [divider]                                               │
     *  │ Source:  [ USB ]──────────[ Rtl-sdr Driver ]           │  ← toggle fires immediately
     *  │                                                         │
     *  │ (USB panel -- visible when toggle = USB)                 │
     *  │   Detected USB device / "no device found"              │
     *  │                                                         │
     *  │ (Driver panel -- visible when toggle = Rtl-sdr Driver)  │
     *  │   driver app status / install prompt                   │
     *  │                                                         │
     *  │ [divider]                                               │
     *  │ External rtl_tcp server (optional)                     │
     *  │   [scan results radio group]                           │
     *  │   Host: ____________  Port: ______                     │
     *  │                              [ Connect to external ]   │
     *  └────────────────────────────────────────────────────────┘
     *
     * The toggle switch performs the source transition on its own -- no extra
     * button press needed.  "Connect" is reserved for manual external TCP only.
     */
    private fun showDeviceInfo() {
        val dp  = resources.displayMetrics.density
        val pad = (16 * dp).toInt()

        val svc = sdrService
        val src = svc?.source
        val isDriverMode = src is com.radiosport.ninegradio.source.RtlTcpSource
        val isUsbMode    = src is com.radiosport.ninegradio.source.RtlSdrDeviceSource ||
            src is com.radiosport.ninegradio.source.AirspyHfSource || src == null

        val driverInstalled = RtlSdrDriverApp.isInstalled(this)
        val driverLabel     = RtlSdrDriverApp.getAppLabel(this)

        // ── Build current-source info string ──────────────────────────────────
        val info = svc?.device?.getDeviceInfo() ?: run {
            if (src != null)
                "Source: ${src.getSourceName()}\n" +
                "Sample rate: ${src.getSampleRate() / 1000} kS/s\n" +
                "Frequency: ${"%.4f".format(src.getCenterFrequency() / 1e6)} MHz"
            else "No device connected"
        }

        // ── Root layout ───────────────────────────────────────────────────────
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val hostInput = android.widget.EditText(this).apply {
            hint = "rtl_tcp host (e.g. 192.168.1.10)"
            setText(viewModel.savedTcpHost().ifBlank { "127.0.0.1" })
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val portInput = android.widget.EditText(this).apply {
            hint = "Port"; setText(viewModel.savedTcpPort().toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        root.addView(hostInput)
        root.addView(portInput)
        root.addView(android.widget.Button(this).apply {
            text = "Connect rtl_tcp"
            setOnClickListener {
                val port = portInput.text.toString().toIntOrNull()
                val host = hostInput.text.toString().trim()
                if (host.isNotBlank() && port != null && port in 1..65535) {
                    viewModel.connectTcp(host, port)
                    showSnackbar("Connecting to $host:$port")
                } else showSnackbar("Enter a valid host and port")
            }
        })

        // Current source info
        root.addView(android.widget.TextView(this).apply {
            text = info
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2)
            val ta = obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            setTextColor(ta.getColor(0, 0xFF000000.toInt()))
            ta.recycle()
            setPadding(0, 0, 0, (10 * dp).toInt())
        })

        // Divider
        root.addView(android.view.View(this).apply {
            setBackgroundColor(0x22888888)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
            ).also { it.bottomMargin = (10 * dp).toInt() }
        })

        // ── Source toggle row (USB ↔ Rtl-sdr Driver) ─────────────────────────
        val toggleRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (4 * dp).toInt())
        }
        val tvToggleLabel = android.widget.TextView(this).apply {
            text = if (isDriverMode) "Source: Rtl-sdr Driver" else "Source: USB"
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val switchSource = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            isChecked = isDriverMode
            textOff = "USB"
            textOn  = "Driver"
        }
        toggleRow.addView(tvToggleLabel)
        toggleRow.addView(switchSource)
        root.addView(toggleRow)

        // Status line below the toggle
        val tvStatus = android.widget.TextView(this).apply {
            text = when {
                isDriverMode -> "📡  $driverLabel  (active)"
                else -> {
                    val devs = viewModel.detectUsbDevices()
                    if (devs.isNotEmpty()) "🔌  ${com.radiosport.ninegradio.source.UsbSources.name(devs.first())}"
                    else "No supported USB receiver detected"
                }
            }
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2)
            val ta = obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            setTextColor(ta.getColor(0, 0xFF000000.toInt()))
            ta.recycle()
            setPadding(0, 0, 0, (6 * dp).toInt())
        }
        root.addView(tvStatus)

        // ── Toggle listener ───────────────────────────────────────────────────
        var ignoreNext = false
        switchSource.setOnCheckedChangeListener { _, useDriver ->
            if (ignoreNext) { ignoreNext = false; return@setOnCheckedChangeListener }
            tvToggleLabel.text = if (useDriver) "Source: Rtl-sdr Driver" else "Source: USB"

            if (useDriver) {
                // USB → Rtl-sdr Driver
                if (!driverInstalled) {
                    showSnackbar("Rtl-sdr Driver not installed")
                    ignoreNext = true
                    switchSource.isChecked = false
                    tvToggleLabel.text = "Source: USB"
                    RtlSdrDriverApp.openPlayStore(this)
                    return@setOnCheckedChangeListener
                }

                val freq    = viewModel.centerFreqHz.value
                val rate    = viewModel.sampleRate.value
                val gainTdB = sdrService?.source?.getGainDb()?.times(10)?.toInt() ?: 240
                val ppm     = viewModel.ppm.value
                val biast   = viewModel.biasTee.value
                val wasUsb  = sdrService?.source is com.radiosport.ninegradio.source.RtlSdrDeviceSource

                // Suppress USB re-attach broadcasts while the dongle is handed to the driver app.
                suppressUsbAttach = true
                sdrService?.disconnectSource()

                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                val usbSettleMs = if (wasUsb) 400L else 0L

                showSnackbar(if (wasUsb) "Releasing USB… launching $driverLabel" else "Launching $driverLabel…")
                tvStatus.text = "📡  $driverLabel  (launching…)"

                // Wait for USB to settle before launching (only if we just released a USB device).
                handler.postDelayed({
                    val launched = RtlSdrDriverApp.launchAsServer(
                        activity     = this,
                        frequencyHz  = freq,
                        sampleRate   = rate.toLong(),
                        gainTenthsDb = gainTdB,
                        ppm          = ppm,
                        biasTee      = biast
                    )
                    if (!launched) {
                        suppressUsbAttach = false
                        showSnackbar("Could not launch $driverLabel")
                        ignoreNext = true
                        switchSource.isChecked = false
                        tvToggleLabel.text = "Source: USB"
                        tvStatus.text = "Could not launch $driverLabel"
                        return@postDelayed
                    }
                    // Driver app is now in foreground -- user grants USB permission there.
                    // onActivityResult(REQUEST_CODE, RESULT_OK) fires when it is ready.
                    showSnackbar("Grant USB access in $driverLabel, then return here")
                }, usbSettleMs)

            } else {
                // Rtl-sdr Driver → USB
                sdrService?.disconnectSource()
                val detected = viewModel.detectUsbDevices()
                if (detected.isEmpty()) {
                    showSnackbar("No supported USB receiver found")
                    tvStatus.text = "No supported USB receiver detected"
                    ignoreNext = true
                    switchSource.isChecked = true
                    tvToggleLabel.text = "Source: Rtl-sdr Driver"
                } else {
                    val dev = detected.first()
                    val name = com.radiosport.ninegradio.source.UsbSources.name(dev)
                    tvStatus.text = "🔌  $name"
                    showSnackbar("Switching to USB: $name")
                    viewModel.connectDevice(dev)
                }
            }
        }

        // ── Vocoder status + optional download ───────────────────────────────────
        root.addView(android.view.View(this).apply {
            setBackgroundColor(0x22888888)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
            ).also { it.topMargin = (8 * dp).toInt(); it.bottomMargin = (8 * dp).toInt() }
        })

        // ── Vocoder status ─────────────────────────────────────────────────────
        root.addView(android.view.View(this).apply {
            setBackgroundColor(0x22888888)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
            ).also { it.topMargin = (8 * dp).toInt(); it.bottomMargin = (8 * dp).toInt() }
        })

        root.addView(android.widget.TextView(this).apply {
            text = "🎙  $VOCODER_STATUS_TEXT"
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            val ta = obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            setTextColor(ta.getColor(0, 0xFF888888.toInt()))
            ta.recycle()
            setPadding(0, 0, 0, (6 * dp).toInt())
        })

        AlertDialog.Builder(this)
            .setTitle("Device Information")
            .setView(root)
            .setNeutralButton("🔬 Run Diagnostic") { _, _ -> /* handled below via view click */ }
            .setPositiveButton("Close", null)
            .create()
            .also { dlg ->
                dlg.show()
                // Wire "Run Diagnostic" — we grab the button after show() so we can replace it
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { btn ->
                    val btn = btn as android.widget.Button
                    val src = sdrService?.source ?: run {
                        showSnackbar("No active source — connect a device first"); return@setOnClickListener
                    }
                    val dsp = sdrService?.dspEngine ?: run {
                        showSnackbar("DSP engine not running"); return@setOnClickListener
                    }
                    btn.isEnabled = false
                    btn.text = "⏳ Running…"
                    lifecycleScope.launch {
                        val diagnostic = com.radiosport.ninegradio.dsp.SourceDiagnostic(dsp, src)
                        val report = withContext(kotlinx.coroutines.Dispatchers.Default) {
                            diagnostic.run()
                        }
                        btn.isEnabled = true
                        btn.text = "🔬 Run Diagnostic"
                        showDiagnosticReport(report)
                    }
                }
            }
    }


    /**
     * Display a [SourceDiagnostic.Report] in a scrollable, copyable dialog.
     * Called on the main thread after the diagnostic coroutine completes.
     */
    private fun showDiagnosticReport(report: com.radiosport.ninegradio.dsp.SourceDiagnostic.Report) {
        val dp  = resources.displayMetrics.density
        val pad = (12 * dp).toInt()

        // Scrollable monospace text body
        val tv = android.widget.TextView(this).apply {
            text     = report.formattedText
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 10.5f
            setPadding(pad, pad, pad, pad)
            setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(this).apply {
            addView(tv)
            isVerticalScrollBarEnabled = true
        }

        // Verdict colour banner at the top
        val verdictColor = when (report.verdict.level) {
            com.radiosport.ninegradio.dsp.SourceDiagnostic.Level.PASS -> 0xFF1B5E20.toInt()
            com.radiosport.ninegradio.dsp.SourceDiagnostic.Level.WARN -> 0xFFE65100.toInt()
            com.radiosport.ninegradio.dsp.SourceDiagnostic.Level.FAIL -> 0xFFB71C1C.toInt()
            else                                                       -> 0xFF1A237E.toInt()
        }
        val verdictIcon = when (report.verdict.level) {
            com.radiosport.ninegradio.dsp.SourceDiagnostic.Level.PASS -> "✅"
            com.radiosport.ninegradio.dsp.SourceDiagnostic.Level.WARN -> "⚠️"
            com.radiosport.ninegradio.dsp.SourceDiagnostic.Level.FAIL -> "❌"
            else -> "ℹ️"
        }
        val verdictBanner = android.widget.TextView(this).apply {
            text = "$verdictIcon  ${report.verdict.level.name}"
            setBackgroundColor(verdictColor)
            setRadioTextColor(com.radiosport.ninegradio.R.attr.radioText)
            textSize   = 13f
            setPadding(pad, (8 * dp).toInt(), pad, (8 * dp).toInt())
        }

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(verdictBanner)
            addView(scroll)
        }

        AlertDialog.Builder(this)
            .setTitle("Source Diagnostic")
            .setView(root)
            .setPositiveButton("Close", null)
            .setNeutralButton("📋 Copy") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText(
                    "9GRadio Diagnostic", report.formattedText))
                showSnackbar("Diagnostic report copied to clipboard")
            }
            .show()
            .also { dlg ->
                // Set dialog height to 80% of screen height for comfortable reading
                dlg.window?.setLayout(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.80f).toInt()
                )
            }
    }

    private fun showFrequencyDatabase() {
        val categories = FrequencyDatabase.commonFrequencies.groupBy { it.category }
        val items = categories.flatMap { (cat, entries) ->
            listOf("── $cat ──") + entries.map { "  ${it.name}\n  ${it.frequencyHz / 1e6} MHz  [${it.mode}]" }
        }.toTypedArray()
        val freqMap = categories.values.flatten().associateBy {
            "  ${it.name}\n  ${it.frequencyHz / 1e6} MHz  [${it.mode}]"
        }
        AlertDialog.Builder(this)
            .setTitle("Frequency Database")
            .setItems(items) { _, which ->
                val item = items[which]
                freqMap[item]?.let { entry ->
                    viewModel.setFrequency(entry.frequencyHz)
                    viewModel.setDemodMode(DemodMode.valueOf(entry.mode).let {
                        try { DemodMode.valueOf(entry.mode) } catch (e: Exception) { DemodMode.NFM }
                    })
                    binding.frequencyView.setFrequency(entry.frequencyHz)
                    showSnackbar("Tuned to ${entry.name}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun bookmarkCurrentFrequency() {
        BookmarkQuickControls.showEditor(this,viewModel)
    }

    private fun getIqFilePath(): String {
        val dir = File(getExternalFilesDir(null), "IQ")
        dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val mhz = "%.3f".format(viewModel.centerFreqHz.value / 1e6)
        val rate = "${viewModel.sampleRate.value / 1000}kSps"
        return File(dir, "iq_${mhz}MHz_${rate}_$ts.${if (viewModel.capabilities.value.floatIq) "cf32" else "iq"}").absolutePath
    }

    private fun getAudioFilePath(extension:String): String {
        val dir = File(getExternalFilesDir(null), "Audio")
        dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val mhz = "%.3f".format(viewModel.centerFreqHz.value / 1e6)
        return File(dir, "audio_${mhz}MHz_$ts.$extension").absolutePath
    }

    private fun showSnackbar(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
    }

    // ─── Rec tab recordings list helpers ────────────────────────────────────
    // Same behavior as RecordingActivity.playRecording()/shareRecording();
    // duplicated here rather than shared because RecordingActivity is a
    // separate standalone Activity with its own Context.

    private fun playRecordingFromTab(meta: RecordingMeta) {
        if(meta.type=="AUDIO") recordingPlayer?.play(meta)
        else { recordingPlayer?.stopPlayback();viewModel.playIqRecording(meta) }
    }

    private fun shareRecordingFromTab(meta: RecordingMeta) {
        val file = File(meta.filePath)
        if (!file.exists()) { Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show(); return }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = if (meta.type == "AUDIO") com.radiosport.ninegradio.recording.AudioRecordingProfile.mime(meta.filePath) else "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share ${file.name}"
        ))
    }

    // ─── APRS drawer tab ─────────────────────────────────────────────────────

    /**
     * Wires the APRS tab controls.  Called once after the ViewPager has
     * inflated [fragment_tab_aprs.xml].
     *
     * The APRS tab is hidden by default.  It becomes visible (and packet
     * collection starts) when the user selects [DemodMode.APRS] from the
     * Mode chip group; it hides again when any other mode is selected.
     * [observeAprsMode] drives that transition from the demodMode observer.
     *
     * No frequency or demod-mode changes are made here — those are handled
     * entirely by the normal mode-selection path in the Mode tab.
     */
    private fun setupAprsTab() {
        val displayPrefs=androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        if(!displayPrefs.getBoolean("aprs_click_details_v1",false)) displayPrefs.edit()
            .putBoolean("pref_aprs_auto_display",false).putBoolean("aprs_click_details_v1",true).apply()
        pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_APRS)?.let { page ->
            val host=page.findViewById<android.widget.FrameLayout>(R.id.aprsMapHost)
            aprsMapPanel=RadioMapPanel(this,"APRS",true).also { host.addView(it,android.widget.FrameLayout.LayoutParams(-1,-1)) }
            page.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.btnAprsMap).setOnCheckedChangeListener { _,on ->
                host.isVisible=on; updateDigitalVisualization(); resizePagerToCurrentPage()
            }
            page.findViewById<Button>(R.id.btnAprsPanelFull).setOnClickListener { setPanelFullscreen(!panelFullscreen) }
        }
        val listView = ctrlViews.listAprsTabPackets ?: return
        aprsListAdapter = ArrayAdapter(this, R.layout.compact_list_item, aprsDisplayList)
        listView.adapter = aprsListAdapter

        listView.setOnItemClickListener { _, _, pos, _ ->
            val (pkt, ch) = aprsPackets.getOrNull(pos) ?: return@setOnItemClickListener
            android.app.AlertDialog.Builder(this)
                .setTitle("[$ch] ${pkt.source} → ${pkt.destination}")
                .setMessage(buildString {
                    appendLine("Channel:   $ch")
                    appendLine("Source:    ${pkt.source}")
                    appendLine("Dest:      ${pkt.destination}")
                    appendLine("Path:      ${pkt.path}")
                    if (pkt.latitude  != null) appendLine("Lat:  ${"%.6f".format(pkt.latitude)}°")
                    if (pkt.longitude != null) appendLine("Lon:  ${"%.6f".format(pkt.longitude)}°")
                    appendLine("Payload:   ${pkt.payload.take(120)}")
                    appendLine("Comment:   ${pkt.comment}")
                    appendLine("Raw:       ${pkt.rawFrame.take(60)}…")
                })
                .setPositiveButton("OK", null)
                .show()
        }

        // Reflect the current mode immediately in case APRS is already active.
        updateAprsTabVisibility(viewModel.demodMode.value)
    }

    /**
     * Shows or hides the APRS tab and starts/stops packet collection
     * based on whether [mode] is [DemodMode.APRS].
     *
     * Called from the demodMode observer in [observeViewModel] every time
     * the mode changes, and from [setupAprsTab] once on first inflate.
     */
    fun observeAprsMode(mode: DemodMode) {
        updateAprsTabVisibility(mode)
    }

    private fun updateAdsbTabVisibility(mode: DemodMode) {
        if(!::pagerAdapter.isInitialized) return
        val tab=moduleTab(ControlsPagerAdapter.TAB_ADSB) ?: return
        val visible=mode==DemodMode.ADSB && viewModel.capabilities.value.supportsMode(mode)
        tab.view.visibility=if(visible) View.VISIBLE else View.GONE
        if(visible) binding.controlViewPager.setCurrentItem(pagerAdapter.positionOf(ControlsPagerAdapter.TAB_ADSB),false)
    }
    private fun setupAdsbTab() {
        val page=pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_ADSB) ?: return
        var aircraft=emptyList<com.radiosport.ninegradio.dsp.AdsbDecoder.AdsbFrame>()
        val table=page.findViewById<AdsbAircraftTable>(R.id.listAdsbPanelAircraft)
        table.onAircraftClick = { ac ->
                AlertDialog.Builder(this@MainActivity).setTitle(ac.callsign ?: ac.icao24)
                    .setMessage("ICAO：${ac.icao24}\n高度：${ac.altitude?.let { "$it ft" } ?: "—"}\n速度：${ac.velocity?.let { "$it kt" } ?: "—"}\n航向：${ac.heading?.let { "%.1f°".format(it) } ?: "—"}\n位置：${ac.latitude ?: "—"}, ${ac.longitude ?: "—"}")
                    .setPositiveButton("关闭",null).show()
        }
        val mapHost=page.findViewById<android.widget.FrameLayout>(R.id.adsbMapHost)
        adsbMapPanel=RadioMapPanel(this,"ADSB",true).also { mapHost.addView(it,android.widget.FrameLayout.LayoutParams(-1,-1)) }
        val mapSwitch=page.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.btnAdsbPanelMap)
        adsbMapSelected=getSharedPreferences("radio_map",MODE_PRIVATE).getBoolean("adsb_show_map",true)
        mapSwitch.isChecked=adsbMapSelected
        mapSwitch.setOnCheckedChangeListener { _,on ->
            adsbMapSelected=on; getSharedPreferences("radio_map",MODE_PRIVATE).edit().putBoolean("adsb_show_map",on).apply()
            updateDigitalVisualization()
        }
        page.findViewById<Button>(R.id.btnAdsbPanelFull).setOnClickListener { setPanelFullscreen(!panelFullscreen) }
        val distances=AdsbDistanceTracker(this)
        page.findViewById<TextView>(R.id.tvAdsbRecordDistance).setOnClickListener {
            AlertDialog.Builder(this).setTitle("清除历史最远距离？").setPositiveButton("清除") { _,_ -> distances.reset() }.setNegativeButton("取消",null).show()
        }
        val radar=page.findViewById<AdsbRadarView>(R.id.adsbPanelRadar)
        radar.setAnimating(pagerAdapter.moduleAt(binding.controlViewPager.currentItem)==ControlsPagerAdapter.TAB_ADSB)
        val location=getSharedPreferences("radio_map",MODE_PRIVATE)
        if(location.contains("receiverLat")) radar.setOwnPosition(location.getFloat("receiverLat",0f).toDouble(),location.getFloat("receiverLon",0f).toDouble())
        lifecycleScope.launch {
            viewModel.serviceConnected.collectLatest { connected ->
                if(!connected) return@collectLatest
                val svc=viewModel.getService() ?: return@collectLatest
                kotlinx.coroutines.flow.combine(svc.adsbReceiver.aircraft,svc.adsbReceiver.status,svc.connectionState) { tracks,status,state -> Triple(tracks,status,state) }
                    .collect { (tracks,status,state) ->
                        aircraft=tracks.sortedBy { it.callsign ?: it.icao24 }
                        val ranges=distances.update(aircraft)
                        table.submit(aircraft,ranges.distances); radar.updateAircraft(aircraft)
                        page.findViewById<TextView>(R.id.tvAdsbPanelCount).text="飞机：${aircraft.size}"
                        page.findViewById<TextView>(R.id.tvAdsbRecordDistance).text="记录距离：${AdsbDistanceTracker.format(ranges.recordKm)}"
                        page.findViewById<TextView>(R.id.tvAdsbMaxDistance).text="最大距离：${AdsbDistanceTracker.format(ranges.currentMaxKm)}"
                        page.findViewById<TextView>(R.id.tvAdsbPanelStatus).text=if(state !is RtlSdrService.ConnectionState.Connected) "接收机已关闭"
                            else status.error ?: if(status.running) "1090 MHz · 2.400 MS/s · ${status.messages} 帧" else "等待 ADS-B 输入"
                    }
            }
        }
        updateAdsbTabVisibility(viewModel.demodMode.value)
        updateDigitalVisualization()
    }

    private fun updateDigitalVisualization() {
        if(!::pagerAdapter.isInitialized) return
        val module=pagerAdapter.moduleAt(binding.controlViewPager.currentItem)
        pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_ADSB)?.let { page ->
            page.findViewById<View>(R.id.adsbMapHost)?.isVisible=adsbMapSelected
            page.findViewById<AdsbRadarView>(R.id.adsbPanelRadar)?.let { radar ->
                radar.isVisible=!adsbMapSelected
                radar.setAnimating(module==ControlsPagerAdapter.TAB_ADSB && !adsbMapSelected && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
            }
        }
        adsbMapPanel?.setActive(module==ControlsPagerAdapter.TAB_ADSB && adsbMapSelected)
        aprsMapPanel?.setActive(module==ControlsPagerAdapter.TAB_APRS && findViewById<View>(R.id.aprsMapHost)?.visibility==View.VISIBLE)
        pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_RDF)?.findViewById<android.widget.FrameLayout>(R.id.rdfControls)
            ?.let { host -> (host.getChildAt(0) as? RdfControls)?.setActive(module==ControlsPagerAdapter.TAB_RDF) }
    }

    private fun updateAprsTabVisibility(mode: DemodMode) {
        val tabLayout = binding.controlTabLayout
        val aprsTabIndex = ControlsPagerAdapter.TAB_APRS

        if (mode == DemodMode.APRS) {
            // Show the APRS tab
            moduleTab(aprsTabIndex)?.view?.visibility = android.view.View.VISIBLE
            // Navigate to it
            binding.controlViewPager.setCurrentItem(pagerAdapter.positionOf(aprsTabIndex), true)
            ctrlViews.tvAprsTabStatus?.text = "等待信号…"
            ctrlViews.tvAprsTabMode?.text   = "${"%.3f".format(viewModel.centerFreqHz.value / 1e6)} MHz  |  ${viewModel.sampleRate.value / 1000} kS/s"
            startAprsCollect()
        } else {
            // Stop collection and hide the tab
            stopAprsCollect()
            moduleTab(aprsTabIndex)?.view?.visibility = android.view.View.GONE
        }
    }

    private fun startAprsCollect() {
        aprsCollectJob?.cancel()
        val decoder = sdrService?.dspEngine?.aprsDecoder ?: return
        aprsCollectJob = lifecycleScope.launch {
            decoder.packets.collectLatest { pkt ->
                aprsPackets.add(0, Pair(pkt, "APRS"))
                if (aprsPackets.size > 200) aprsPackets.removeLast()

                val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date(pkt.timestamp))
                val entry = buildString {
                    append("[$ts] ${pkt.source}>${pkt.destination}")
                    if (pkt.latitude != null && pkt.longitude != null)
                        append("  ${"%.4f".format(pkt.latitude)},${"%.4f".format(pkt.longitude)}")
                    append("\n${pkt.comment.take(60)}")
                }
                aprsDisplayList.add(0, entry)
                if (aprsDisplayList.size > 200) aprsDisplayList.removeLast()
                aprsListAdapter?.notifyDataSetChanged()
                ctrlViews.tvAprsTabCount?.text  = "数据包：${aprsPackets.size}"
                ctrlViews.tvAprsTabStatus?.text = "Last: ${pkt.source}"

                // Auto-display the packet detail dialog on reception, unless
                // the user has disabled this in Settings (pref_aprs_auto_display).
                val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                val autoDisplay = prefs.getBoolean("pref_aprs_auto_display", false)
                if (autoDisplay) {
                    showAprsPacketAutoDialog(pkt, "APRS")
                }
            }
        }
    }

    /** Auto-dismissing APRS packet detail dialog shown for 5 seconds on reception. */
    private var aprsAutoDialog: android.app.AlertDialog? = null
    private var aprsAutoDialogJob: Job? = null

    private fun showAprsPacketAutoDialog(pkt: com.radiosport.ninegradio.dsp.AprsDecoder.AprsPacket, channelTag: String) {
        // Dismiss any existing auto-dialog so only the latest packet is shown.
        aprsAutoDialog?.dismiss()
        aprsAutoDialogJob?.cancel()

        val message = buildString {
            appendLine("Source:    ${pkt.source}")
            appendLine("Dest:      ${pkt.destination}")
            appendLine("Path:      ${pkt.path}")
            if (pkt.latitude  != null) appendLine("Lat:  ${"%.6f".format(pkt.latitude)}\u00b0")
            if (pkt.longitude != null) appendLine("Lon:  ${"%.6f".format(pkt.longitude)}\u00b0")
            appendLine("Payload:   ${pkt.payload.take(120)}")
            appendLine("Comment:   ${pkt.comment}")
            appendLine("Raw:       ${pkt.rawFrame.take(60)}\u2026")
            appendLine()
            append("(auto-closes in 5 s)")
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("[$channelTag] ${pkt.source} \u2192 ${pkt.destination}")
            .setMessage(message)
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .create()
        dialog.show()
        aprsAutoDialog = dialog

        // Auto-dismiss after 5 seconds.
        aprsAutoDialogJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(5_000)
            dialog.dismiss()
        }
    }

    private fun stopAprsCollect() {
        aprsCollectJob?.cancel()
        aprsCollectJob = null
        aprsAutoDialogJob?.cancel()
        aprsAutoDialogJob = null
        aprsAutoDialog?.dismiss()
        aprsAutoDialog = null
        ctrlViews.tvAprsTabStatus?.text = "Idle"
        ctrlViews.tvAprsTabMode?.text   = "Not started"
    }

    // ─── ACARS drawer tab ──────────────────────────────────────────────────────

    /**
     * Wires the ACARS tab controls. Called once after the ViewPager has
     * inflated [fragment_tab_acars.xml].
     *
     * Hidden by default; becomes visible when [DemodMode.ACARS] is selected,
     * exactly like the APRS tab above. Selecting ACARS from the Mode tab's
     * chip group used to launch the separate, full-screen AcarsActivity; it
     * now opens this embedded drawer tab instead, matching APRS's behavior.
     */
    private fun setupAcarsTab() {
        val listView = ctrlViews.listAcarsTabMessages ?: return
        acarsListAdapter = ArrayAdapter(this, R.layout.compact_list_item, acarsDisplayList)
        listView.adapter = acarsListAdapter

        listView.setOnItemClickListener { _, _, pos, _ ->
            val msg = acarsMessages.getOrNull(pos) ?: return@setOnItemClickListener
            android.app.AlertDialog.Builder(this)
                .setTitle("${msg.registration}  ·  ${msg.flightId}")
                .setMessage(buildString {
                    appendLine("Registration: ${msg.registration}")
                    appendLine("Flight ID:    ${msg.flightId}")
                    appendLine("Label:        ${msg.label}")
                    appendLine("Block ID:     ${msg.blockId}")
                    appendLine("Type:         ${if (msg.ack) "ACK" else "Message"}${if (msg.more) " (more follows)" else ""}")
                    appendLine()
                    appendLine(msg.text)
                })
                .setPositiveButton("OK", null)
                .show()
        }

        // Reflect the current mode immediately in case ACARS is already active.
        updateAcarsTabVisibility(viewModel.demodMode.value)
    }

    /**
     * Shows or hides the ACARS tab and starts/stops message collection
     * based on whether [mode] is [DemodMode.ACARS].
     *
     * Called from the demodMode observer in [observeViewModel] every time
     * the mode changes, and from [setupAcarsTab] once on first inflate.
     */
    fun observeAcarsMode(mode: DemodMode) {
        updateAcarsTabVisibility(mode)
    }

    private fun updateAcarsTabVisibility(mode: DemodMode) {
        val tabLayout = binding.controlTabLayout
        val acarsTabIndex = ControlsPagerAdapter.TAB_ACARS

        if (mode == DemodMode.ACARS) {
            // Show the ACARS tab
            moduleTab(acarsTabIndex)?.view?.visibility = android.view.View.VISIBLE
            // Navigate to it
            binding.controlViewPager.setCurrentItem(pagerAdapter.positionOf(acarsTabIndex), true)
            ctrlViews.tvAcarsTabStatus?.text = "等待信号…"
            ctrlViews.tvAcarsTabMode?.text   = "${"%.3f".format(viewModel.centerFreqHz.value / 1e6)} MHz  |  ${viewModel.sampleRate.value / 1000} kS/s"
            startAcarsCollect()
        } else {
            // Stop collection and hide the tab
            stopAcarsCollect()
            moduleTab(acarsTabIndex)?.view?.visibility = android.view.View.GONE
        }
    }

    private fun startAcarsCollect() {
        acarsCollectJob?.cancel()
        val decoder = sdrService?.dspEngine?.acarsDecoder ?: return
        acarsCollectJob = lifecycleScope.launch {
            decoder.messages.collectLatest { msg ->
                acarsMessages.add(0, msg)
                if (acarsMessages.size > 200) acarsMessages.removeLast()

                val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date(msg.timestamp))
                val entry = buildString {
                    append("[$ts] ${msg.registration} ${msg.flightId} (${msg.label})")
                    if (msg.text.isNotBlank()) append("\n${msg.text.take(60)}")
                }
                acarsDisplayList.add(0, entry)
                if (acarsDisplayList.size > 200) acarsDisplayList.removeLast()
                acarsListAdapter?.notifyDataSetChanged()
                ctrlViews.tvAcarsTabCount?.text  = "Messages: ${acarsMessages.size}"
                ctrlViews.tvAcarsTabStatus?.text = "Last: ${msg.registration}"
            }
        }
    }

    private fun stopAcarsCollect() {
        acarsCollectJob?.cancel()
        acarsCollectJob = null
        ctrlViews.tvAcarsTabStatus?.text = "Idle"
        ctrlViews.tvAcarsTabMode?.text   = "Not started"
    }


    // ═════════════════════════════════════════════════════════════════════════
    //  DMR DRAWER TAB
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Formats the elapsed time of a call (lastTimestamp - firstTimestamp) as
     * a compact duration string, e.g. "0s", "45s", "2m 1s", "1h 3m" -- used
     * by every digital-voice "Recent Calls" list (DMR/Dig/YSF/D-STAR) in
     * place of a start–end time range, which said the same thing far less
     * compactly and didn't read at a glance as "how long was this call".
     */
    private fun formatCallDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else  -> "${s}s"
        }
    }

    /**
     * Wires the DMR tab ListView. Called once after the ViewPager has
     * inflated [fragment_tab_dmr.xml]. Hidden by default; becomes visible
     * when [DemodMode.DMR] is selected, mirroring the APRS/ACARS pattern.
     */
    private fun setupDmrTab() {
        val listView = ctrlViews.listDmrTabFrames ?: return
        dmrListAdapter = ArrayAdapter(this, R.layout.item_recent_call, android.R.id.text1, dmrDisplayList)
        listView.adapter = dmrListAdapter

        listView.setOnItemClickListener { _, _, pos, _ ->
            val call = dmrCalls.calls.getOrNull(pos) ?: return@setOnItemClickListener
            val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            val start = fmt.format(java.util.Date(call.firstTimestamp))
            val duration = formatCallDuration(call.lastTimestamp - call.firstTimestamp)
            android.app.AlertDialog.Builder(this)
                .setTitle("DMR Call  [$start - $duration]")
                .setMessage(buildString {
                    appendLine("Protocol:  ${call.protocol}")
                    appendLine("Type:      ${call.frameType}")
                    appendLine("Src ID:    ${call.srcId}")
                    appendLine("Dst ID:    ${call.dstId}")
                    appendLine("Group:     ${call.isGroup}")
                    appendLine("Encrypted: ${call.encrypted}")
                    appendLine("Emergency: ${call.emergency}")
                    if (call.talkerAlias.isNotBlank()) appendLine("Alias:     ${call.talkerAlias}")
                    appendLine("RSSI:      ${"%.1f".format(call.rssi)} dBm")
                    appendLine("SNR:       ${"%.1f".format(call.snr)} dB")
                    appendLine("Frames:    ${call.frameCount}")
                    appendLine("PCM:       ${call.pcmSamples} samples")
                })
                .setPositiveButton("OK", null)
                .show()
        }

        updateDmrTabVisibility(viewModel.demodMode.value)
    }

    fun observeDmrMode(mode: com.radiosport.ninegradio.dsp.DemodMode) {
        updateDmrTabVisibility(mode)
    }

    private fun updateDmrTabVisibility(mode: com.radiosport.ninegradio.dsp.DemodMode) {
        val tabLayout = binding.controlTabLayout

        // DMR tab: visible only for DemodMode.DMR
        val dmrTabIndex = ControlsPagerAdapter.TAB_DMR
        if (mode == com.radiosport.ninegradio.dsp.DemodMode.DMR) {
            moduleTab(dmrTabIndex)?.view?.visibility = android.view.View.VISIBLE
            binding.controlViewPager.setCurrentItem(pagerAdapter.positionOf(dmrTabIndex), true)
            ctrlViews.tvDmrTabStatus?.text  = "等待信号…"
            ctrlViews.tvDmrTabMode?.text    = "${"%.3f".format(viewModel.centerFreqHz.value / 1e6)} MHz  |  ${viewModel.sampleRate.value / 1000} kS/s"
            ctrlViews.tvDmrTabVocoder?.text = VOCODER_STATUS_TEXT
            startDmrCollect()
        } else {
            stopDmrCollect()
            moduleTab(dmrTabIndex)?.view?.visibility = android.view.View.GONE
        }

        // Dig tab: visible only for DemodMode.DIG
        val digTabIndex = ControlsPagerAdapter.TAB_DIG
        if (mode == com.radiosport.ninegradio.dsp.DemodMode.DIG) {
            moduleTab(digTabIndex)?.view?.visibility = android.view.View.VISIBLE
            binding.controlViewPager.setCurrentItem(pagerAdapter.positionOf(digTabIndex), true)
            ctrlViews.tvDigTabStatus?.text  = "Auto-detect…"
            ctrlViews.tvDigTabMode?.text    = "${"%.3f".format(viewModel.centerFreqHz.value / 1e6)} MHz  |  ${viewModel.sampleRate.value / 1000} kS/s"
            ctrlViews.tvDigTabVocoder?.text = VOCODER_STATUS_TEXT
            startDigCollect()
        } else {
            stopDigCollect()
            moduleTab(digTabIndex)?.view?.visibility = android.view.View.GONE
        }
    }

    private fun startDmrCollect() {
        dmrCollectJob?.cancel()
        val decoder = sdrService?.dspEngine?.digitalVoiceDecoder ?: return
        dmrCollectJob = lifecycleScope.launch {
            decoder.frames.collect { frame ->
                if (frame.protocol != com.radiosport.ninegradio.dsp.DigitalFrame.Protocol.DMR) return@collect
                val isNewCall = dmrCalls.addFrame(frame)
                val call = dmrCalls.calls.first()
                dmrFrameCount++

                val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                val timeLabel = "${fmt.format(java.util.Date(call.firstTimestamp))} - " +
                    formatCallDuration(call.lastTimestamp - call.firstTimestamp)
                val entry = buildString {
                    append("[$timeLabel] ${call.frameType}")
                    if (call.srcId != 0) append("  ${call.srcId}→${call.dstId}")
                    if (call.talkerAlias.isNotBlank()) append("  \"${call.talkerAlias.take(12)}\"")
                    if (call.encrypted) append("  🔒")
                    if (call.emergency) append("  🆘")
                    append("  ×${call.frameCount}")
                }
                // A continuing call replaces its existing row in place; a new
                // call inserts a new row at the top -- this is what turns the
                // list from one-row-per-frame into one-row-per-call.
                if (isNewCall) {
                    dmrDisplayList.add(0, entry)
                    if (dmrDisplayList.size > 200) dmrDisplayList.removeLast()
                } else {
                    dmrDisplayList[0] = entry
                }
                dmrListAdapter?.notifyDataSetChanged()

                ctrlViews.tvDmrTabCount?.text  = "Frames: $dmrFrameCount"
                ctrlViews.tvDmrTabStatus?.text = "Active"
                if (frame.srcId != 0) {
                    ctrlViews.tvDmrTabCallInfo?.text =
                        "Last: ${frame.srcId} → ${frame.dstId}  (${if (frame.isGroup) "Group" else "Private"})"
                }
            }
        }
    }
    private fun stopDmrCollect() {
        dmrCollectJob?.cancel()
        dmrCollectJob = null
        ctrlViews.tvDmrTabStatus?.text = "Idle"
        ctrlViews.tvDmrTabMode?.text   = "Not started"
    }

    private fun setupDigTab() {
        val listView = ctrlViews.listDigTabFrames ?: return
        digListAdapter = ArrayAdapter(this, R.layout.item_recent_call, android.R.id.text1, digDisplayList)
        listView.adapter = digListAdapter

        listView.setOnItemClickListener { _, _, pos, _ ->
            val call = digCalls.calls.getOrNull(pos) ?: return@setOnItemClickListener
            val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            val start = fmt.format(java.util.Date(call.firstTimestamp))
            val duration = formatCallDuration(call.lastTimestamp - call.firstTimestamp)
            android.app.AlertDialog.Builder(this)
                .setTitle("Dig Call  [$start - $duration]")
                .setMessage(buildString {
                    appendLine("Protocol:  ${call.protocol}")
                    appendLine("Type:      ${call.frameType}")
                    appendLine("Src ID:    ${call.srcId}")
                    appendLine("Dst ID:    ${call.dstId}")
                    appendLine("Group:     ${call.isGroup}")
                    appendLine("Encrypted: ${call.encrypted}")
                    appendLine("Emergency: ${call.emergency}")
                    if (call.talkerAlias.isNotBlank()) appendLine("Alias:     ${call.talkerAlias}")
                    appendLine("RSSI:      ${"%.1f".format(call.rssi)} dBm")
                    appendLine("SNR:       ${"%.1f".format(call.snr)} dB")
                    appendLine("Frames:    ${call.frameCount}")
                    appendLine("PCM:       ${call.pcmSamples} samples")
                })
                .setPositiveButton("OK", null)
                .show()
        }

        updateDmrTabVisibility(viewModel.demodMode.value)
    }

    private fun startDigCollect() {
        digCollectJob?.cancel()
        val decoder = sdrService?.dspEngine?.digitalVoiceDecoder ?: return
        digCollectJob = lifecycleScope.launch {
            decoder.frames.collect { frame ->
                // DIG (auto-detect) mode: accept frames from any known protocol
                if (frame.protocol == com.radiosport.ninegradio.dsp.DigitalFrame.Protocol.UNKNOWN) return@collect
                val isNewCall = digCalls.addFrame(frame)
                val call = digCalls.calls.first()
                digFrameCount++

                val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                val timeLabel = "${fmt.format(java.util.Date(call.firstTimestamp))} - " +
                    formatCallDuration(call.lastTimestamp - call.firstTimestamp)
                val entry = buildString {
                    append("[${call.protocol.name}] ")
                    append("[$timeLabel] ${call.frameType}")
                    if (call.srcId != 0) append("  ${call.srcId}→${call.dstId}")
                    if (call.talkerAlias.isNotBlank()) append("  \"${call.talkerAlias.take(12)}\"")
                    if (call.encrypted) append("  🔒")
                    if (call.emergency) append("  🆘")
                    append("  ×${call.frameCount}")
                }
                if (isNewCall) {
                    digDisplayList.add(0, entry)
                    if (digDisplayList.size > 200) digDisplayList.removeLast()
                } else {
                    digDisplayList[0] = entry
                }
                digListAdapter?.notifyDataSetChanged()

                ctrlViews.tvDigTabCount?.text  = "Frames: $digFrameCount"
                ctrlViews.tvDigTabStatus?.text = "Detected: ${frame.protocol.name}"
                if (frame.srcId != 0) {
                    ctrlViews.tvDigTabCallInfo?.text =
                        "Last: ${frame.srcId} → ${frame.dstId}  (${if (frame.isGroup) "Group" else "Private"})"
                }
            }
        }
    }
    private fun stopDigCollect() {
        digCollectJob?.cancel()
        digCollectJob = null
        ctrlViews.tvDigTabStatus?.text = "Idle"
        ctrlViews.tvDigTabMode?.text   = "Not started"
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  YSF DRAWER TAB
    // ═════════════════════════════════════════════════════════════════════════

    private fun setupYsfTab() {
        val listView = ctrlViews.listYsfTabFrames ?: return
        ysfListAdapter = ArrayAdapter(this, R.layout.item_recent_call, android.R.id.text1, ysfDisplayList)
        listView.adapter = ysfListAdapter

        listView.setOnItemClickListener { _, _, pos, _ ->
            val call = ysfCalls.calls.getOrNull(pos) ?: return@setOnItemClickListener
            val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            val start = fmt.format(java.util.Date(call.firstTimestamp))
            val duration = formatCallDuration(call.lastTimestamp - call.firstTimestamp)
            android.app.AlertDialog.Builder(this)
                .setTitle("YSF Call  [$start - $duration]")
                .setMessage(buildString {
                    appendLine("Protocol:  ${call.protocol}")
                    appendLine("Type:      ${call.frameType}")
                    appendLine("Src ID:    ${call.srcId}")
                    appendLine("Dst ID:    ${call.dstId}")
                    appendLine("Encrypted: ${call.encrypted}")
                    if (call.talkerAlias.isNotBlank()) appendLine("Alias:     ${call.talkerAlias}")
                    appendLine("RSSI:      ${"%.1f".format(call.rssi)} dBm")
                    appendLine("SNR:       ${"%.1f".format(call.snr)} dB")
                    appendLine("Frames:    ${call.frameCount}")
                    appendLine("PCM:       ${call.pcmSamples} samples")
                })
                .setPositiveButton("OK", null)
                .show()
        }

        updateYsfTabVisibility(viewModel.demodMode.value)
    }

    fun observeYsfMode(mode: com.radiosport.ninegradio.dsp.DemodMode) {
        updateYsfTabVisibility(mode)
    }

    private fun updateYsfTabVisibility(mode: com.radiosport.ninegradio.dsp.DemodMode) {
        val tabLayout = binding.controlTabLayout
        val tabIndex  = ControlsPagerAdapter.TAB_YSF
        if (mode == com.radiosport.ninegradio.dsp.DemodMode.YSF) {
            moduleTab(tabIndex)?.view?.visibility = android.view.View.VISIBLE
            binding.controlViewPager.setCurrentItem(pagerAdapter.positionOf(tabIndex), true)
            ctrlViews.tvYsfTabStatus?.text  = "等待信号…"
            ctrlViews.tvYsfTabMode?.text    = "${"%.3f".format(viewModel.centerFreqHz.value / 1e6)} MHz  |  ${viewModel.sampleRate.value / 1000} kS/s"
            ctrlViews.tvYsfTabVocoder?.text = VOCODER_STATUS_TEXT
            startYsfCollect()
        } else {
            stopYsfCollect()
            moduleTab(tabIndex)?.view?.visibility = android.view.View.GONE
        }
    }

    private fun startYsfCollect() {
        ysfCollectJob?.cancel()
        val decoder = sdrService?.dspEngine?.digitalVoiceDecoder ?: return
        ysfCollectJob = lifecycleScope.launch {
            decoder.frames.collect { frame ->
                if (frame.protocol != com.radiosport.ninegradio.dsp.DigitalFrame.Protocol.YSF) return@collect
                val isNewCall = ysfCalls.addFrame(frame)
                val call = ysfCalls.calls.first()
                ysfFrameCount++

                val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                val timeLabel = "${fmt.format(java.util.Date(call.firstTimestamp))} - " +
                    formatCallDuration(call.lastTimestamp - call.firstTimestamp)
                val entry = buildString {
                    append("[$timeLabel] ${call.frameType}")
                    if (call.srcId != 0) append("  ${call.srcId}→${call.dstId}")
                    if (call.talkerAlias.isNotBlank()) append("  \"${call.talkerAlias.take(12)}\"")
                    if (call.encrypted) append("  🔒")
                    append("  ×${call.frameCount}")
                }
                if (isNewCall) {
                    ysfDisplayList.add(0, entry)
                    if (ysfDisplayList.size > 200) ysfDisplayList.removeLast()
                } else {
                    ysfDisplayList[0] = entry
                }
                ysfListAdapter?.notifyDataSetChanged()

                ctrlViews.tvYsfTabCount?.text  = "Frames: $ysfFrameCount"
                ctrlViews.tvYsfTabStatus?.text = "Active"
                if (frame.srcId != 0) {
                    ctrlViews.tvYsfTabCallInfo?.text =
                        "Last: ${frame.srcId} → ${frame.dstId}"
                }
            }
        }
    }

    private fun stopYsfCollect() {
        ysfCollectJob?.cancel()
        ysfCollectJob = null
        ctrlViews.tvYsfTabStatus?.text = "Idle"
        ctrlViews.tvYsfTabMode?.text   = "Not started"
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  D-STAR DRAWER TAB
    // ═════════════════════════════════════════════════════════════════════════

    private fun setupDstarTab() {
        val listView = ctrlViews.listDstarTabFrames ?: return
        dstarListAdapter = ArrayAdapter(this, R.layout.item_recent_call, android.R.id.text1, dstarDisplayList)
        listView.adapter = dstarListAdapter

        listView.setOnItemClickListener { _, _, pos, _ ->
            val call = dstarCalls.calls.getOrNull(pos) ?: return@setOnItemClickListener
            val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            val start = fmt.format(java.util.Date(call.firstTimestamp))
            val duration = formatCallDuration(call.lastTimestamp - call.firstTimestamp)
            android.app.AlertDialog.Builder(this)
                .setTitle("D-STAR Call  [$start - $duration]")
                .setMessage(buildString {
                    appendLine("Protocol:  ${call.protocol}")
                    appendLine("Type:      ${call.frameType}")
                    appendLine("Src ID:    ${call.srcId}")
                    appendLine("Dst ID:    ${call.dstId}")
                    appendLine("Encrypted: ${call.encrypted}")
                    if (call.talkerAlias.isNotBlank()) appendLine("Callsign:  ${call.talkerAlias}")
                    appendLine("RSSI:      ${"%.1f".format(call.rssi)} dBm")
                    appendLine("SNR:       ${"%.1f".format(call.snr)} dB")
                    appendLine("Frames:    ${call.frameCount}")
                    appendLine("PCM:       ${call.pcmSamples} samples")
                })
                .setPositiveButton("OK", null)
                .show()
        }

        updateDstarTabVisibility(viewModel.demodMode.value)
    }

    fun observeDstarMode(mode: com.radiosport.ninegradio.dsp.DemodMode) {
        updateDstarTabVisibility(mode)
    }

    private fun updateDstarTabVisibility(mode: com.radiosport.ninegradio.dsp.DemodMode) {
        val tabLayout = binding.controlTabLayout
        val tabIndex  = ControlsPagerAdapter.TAB_DSTAR
        if (mode == com.radiosport.ninegradio.dsp.DemodMode.DSTAR) {
            moduleTab(tabIndex)?.view?.visibility = android.view.View.VISIBLE
            binding.controlViewPager.setCurrentItem(pagerAdapter.positionOf(tabIndex), true)
            ctrlViews.tvDstarTabStatus?.text  = "等待信号…"
            ctrlViews.tvDstarTabMode?.text    = "${"%.3f".format(viewModel.centerFreqHz.value / 1e6)} MHz  |  ${viewModel.sampleRate.value / 1000} kS/s"
            ctrlViews.tvDstarTabVocoder?.text = VOCODER_STATUS_TEXT
            startDstarCollect()
        } else {
            stopDstarCollect()
            moduleTab(tabIndex)?.view?.visibility = android.view.View.GONE
        }
    }

    private fun startDstarCollect() {
        dstarCollectJob?.cancel()
        val decoder = sdrService?.dspEngine?.digitalVoiceDecoder ?: return
        dstarCollectJob = lifecycleScope.launch {
            decoder.frames.collect { frame ->
                if (frame.protocol != com.radiosport.ninegradio.dsp.DigitalFrame.Protocol.DSTAR) return@collect
                val isNewCall = dstarCalls.addFrame(frame)
                val call = dstarCalls.calls.first()
                dstarFrameCount++

                val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                val timeLabel = "${fmt.format(java.util.Date(call.firstTimestamp))} - " +
                    formatCallDuration(call.lastTimestamp - call.firstTimestamp)
                val entry = buildString {
                    append("[$timeLabel] ${call.frameType}")
                    if (call.talkerAlias.isNotBlank()) append("  ${call.talkerAlias.take(8)}")
                    if (call.srcId != 0) append("  ${call.srcId}→${call.dstId}")
                    if (call.encrypted) append("  🔒")
                    append("  ×${call.frameCount}")
                }
                if (isNewCall) {
                    dstarDisplayList.add(0, entry)
                    if (dstarDisplayList.size > 200) dstarDisplayList.removeLast()
                } else {
                    dstarDisplayList[0] = entry
                }
                dstarListAdapter?.notifyDataSetChanged()

                ctrlViews.tvDstarTabCount?.text  = "Frames: $dstarFrameCount"
                ctrlViews.tvDstarTabStatus?.text = "Active"
                if (frame.talkerAlias.isNotBlank()) {
                    ctrlViews.tvDstarTabCallInfo?.text = "Last callsign: ${frame.talkerAlias}"
                } else if (frame.srcId != 0) {
                    ctrlViews.tvDstarTabCallInfo?.text = "Last: ${frame.srcId} → ${frame.dstId}"
                }
            }
        }
    }

    private fun stopDstarCollect() {
        dstarCollectJob?.cancel()
        dstarCollectJob = null
        ctrlViews.tvDstarTabStatus?.text = "Idle"
        ctrlViews.tvDstarTabMode?.text   = "Not started"
    }

    // ─── Scan drawer tab ──────────────────────────────────────────────────────
    //
    // The Scan tab is a hidden tab exactly like APRS/ACARS/DMR/YSF/D-STAR/Dig
    // above, with one difference: those tabs reveal themselves automatically
    // when their DemodMode is selected, whereas this one has no associated
    // DemodMode -- it only becomes visible when explicitly invoked via
    // [showScanTab] (wired to the Settings tab's Scanner button and the main
    // FAB in [setupSettingsTab]/[setupFabMenu]), and hides again via
    // [hideScanTab] (its own Close button, or when the drawer needs it gone).
    //
    // This is the app's only frequency-scanning UI -- a separate standalone
    // ScannerActivity previously duplicated this same functionality and has
    // been removed to avoid the two implementations drifting out of sync.

    /**
     * Pick a device sample rate for an upcoming scan.
     *
     * [FrequencyScanner] captures one wideband IQ block per hop and detects
     * every channel that falls inside it (see [FrequencyScanner.captureBlock]),
     * exactly like SDRangel's own Frequency Scanner plugin, whose docs note
     * it "will typically try to set the device centre frequency in order to
     * scan as many frequencies simultaneously as possible" --
     * https://github.com/f4exb/sdrangel/blob/master/plugins/channelrx/freqscanner/readme.md.
     * The wider the capture, the fewer retunes are needed to cover
     * [startHz]..[stopHz], so the sweep both completes faster and spends more
     * time per hop dwelling on each block (better sensitivity per retune).
     *
     * Pick the smallest available rate that both (a) covers the whole
     * requested range in one block if that range is modest, and (b) still
     * spans a healthy number of channel steps (at least 40) so multiple
     * candidate channels are actually being scanned per hop rather than one at
     * a time. Capped at the device's fastest supported rate.
     */
    private fun chooseScanSampleRate(startHz: Long, stopHz: Long, stepHz: Long): Int {
        val range = (stopHz - startHz).coerceAtLeast(1L)
        val target = minOf(range, maxOf(stepHz * 40L, 1_000_000L))
        return viewModel.availableSampleRates.firstOrNull { it >= target }
            ?: viewModel.availableSampleRates.last()
    }

    /**
     * Pick the smallest FFT size that still resolves individual scan
     * channels cleanly. A bin wider than the channel step would blur two
     * adjacent channels together (a hit on one frequency reading as if it
     * were on its neighbour too); a much narrower bin than necessary only
     * burns CPU/time on every capture without adding any real accuracy for
     * this purpose. Target a bin width of at most a quarter of [stepHz], the
     * smallest FFT size in the app's supported set that achieves it.
     */
    private fun chooseScanFftSize(sampleRateHz: Int, stepHz: Long): Int {
        val fftSizes = intArrayOf(256, 512, 1024, 2048, 4096, 8192)
        val targetBinHz = maxOf(stepHz / 4.0, 1.0)
        return fftSizes.firstOrNull { sampleRateHz.toDouble() / it <= targetBinHz } ?: fftSizes.last()
    }

    /**
     * Pick a frame-averaging count that smooths the noise floor without
     * blurring together samples from different frequencies. The spectrum
     * only stays on one retuned frequency for [dwellMs] before the scanner
     * hops on, so averaging must fit comfortably inside that window (at
     * ~15 fps / ~66 ms per frame) or it will still be blending in frames
     * from the *previous* hop's frequency when the next hit is evaluated.
     * Short dwells (fast search sweeps) get no averaging at all; longer
     * dwells get progressively more, capped well under what the dwell time
     * can actually contain.
     */
    /**
     * Pick a frame-averaging count that actually gets used for the configured
     * dwell time, instead of silently defaulting to "off" (1) for anything
     * short of ~8 live-spectrum frames per dwell.  At the ~66 ms/frame the
     * live spectrum runs at, a typical 200 ms scan dwell only fits ~3 frames
     * -- the previous >=8/>=4 thresholds meant averaging stayed OFF for that
     * (and shorter) dwell times, so Auto dB Range's noise-floor read was
     * never actually sanitized by Frame Avg. the way the scan tab intended.
     * Lowered so even a short dwell still gets at least 2x averaging.
     *
     * This is now safe to apply aggressively because [FftEngine.resetSpectrumAveraging]
     * is called on every scanner retune (see FrequencyScanner's onRetune hook,
     * wired below), which discards any partial accumulation from the
     * *previous* frequency -- frames from different hops can no longer be
     * incoherently blended together, so a higher averaging count only ever
     * cleans up noise within a single, stable-frequency dwell.
     */
    private fun chooseScanFrameAveraging(dwellMs: Long): Int {
        val framesPerDwell = dwellMs / 66L
        return when {
            framesPerDwell >= 6 -> 4
            framesPerDwell >= 2 -> 2
            else -> 1
        }
    }

    /**
     * Auto-configure the live spectrum/waterfall so they accurately reflect
     * what's about to be scanned: a sample rate wide enough to show several
     * channel steps at once (see [chooseScanSampleRate]), an initial center
     * frequency matching the very first capture block
     * [FrequencyScanner] will actually tune to (half a bandwidth into the
     * range, mirroring its own startup math), and the configured squelch
     * threshold drawn as the spectrum's squelch line. Called once, right
     * before a scan/search starts -- the sweep's own retunes are then
     * mirrored to the display live via [attachScanTabCollectors].
     *
     * Also tunes the rest of the display pipeline for scanning specifically,
     * rather than leaving whatever the user last picked for manual listening:
     *   - FFT size:      big enough to resolve individual channels ([chooseScanFftSize]).
     *   - Decimation:    forced off, so the displayed bandwidth always matches
     *                    the scanner's actual capture width -- a decimated
     *                    view would silently hide part of every block.
     *   - Frame average: light, dwell-proportional smoothing to cut noise
     *                    without blurring frequencies together ([chooseScanFrameAveraging]).
     *   - Auto dB range: turned on, since a sweep crosses bands with very
     *                    different noise floors/activity levels that no
     *                    single fixed dB range would suit for all of them.
     */
    private fun configureSpectrumForScan(startHz: Long, stopHz: Long, stepHz: Long, sqlDb: Float, dwellMs: Long) {
        val rate = chooseScanSampleRate(startHz, stopHz, stepHz)
        if (rate != viewModel.sampleRate.value) {
            viewModel.setSampleRate(rate)
            val idx = viewModel.availableSampleRates.indexOfFirst { it == viewModel.sampleRate.value }
            if (idx >= 0) {
                sampleRateLabelsCache.getOrNull(idx)?.let { ctrlViews.tvSampleRateLabel?.text = it }
                updateSampleRateChips(idx)
            }
        }

        // Decimation off: the displayed bandwidth must match the scanner's
        // actual capture width so nothing it's inspecting is hidden from view.
        if (viewModel.decimation.value != 1) {
            viewModel.setDecimation(1)
            val decimationFactors = listOf(1, 2, 4, 8, 16, 32, 64)
            ctrlViews.spinnerDecimation?.setSelection(decimationFactors.indexOf(1), false)
        }
        val effectiveRate = viewModel.sampleRate.value / viewModel.decimation.value.coerceAtLeast(1)
        binding.spectrumView.setSampleRate(effectiveRate)
        binding.waterfallView.setSampleRate(effectiveRate)

        val fftSize = chooseScanFftSize(effectiveRate, stepHz)
        if (fftSize != viewModel.fftSize.value) {
            viewModel.setFftSize(fftSize)
            val fftSizes = listOf(256, 512, 1024, 2048, 4096, 8192)
            fftSizes.indexOf(fftSize).takeIf { it >= 0 }
                ?.let { ctrlViews.spinnerFftSize?.setSelection(it, false) }
        }

        val frameAvg = chooseScanFrameAveraging(dwellMs)
        sdrService?.dspEngine?.fftEngine?.frameAveragingCount = frameAvg
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putString("pref_frame_averaging", frameAvg.toString()).apply()
        val frameAvgValues = listOf(1, 2, 4, 8, 16, 32)
        frameAvgValues.indexOf(frameAvg).takeIf { it >= 0 }
            ?.let { ctrlViews.spinnerFrameAvg?.setSelection(it, false) }

        binding.spectrumView.setAutoRange(true)
        ctrlViews.switchAutoRange?.isChecked = true
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putBoolean("pref_auto_range", true).apply()

        // Same "half a bandwidth into the range" starting point FrequencyScanner
        // itself uses, so the very first frame drawn already matches reality
        // instead of showing wherever the radio happened to be tuned before
        // Start Scan was pressed.
        val initialCenterHz = minOf(startHz + viewModel.sampleRate.value / 2, stopHz)
        viewModel.setFrequency(initialCenterHz)
        binding.spectrumView.setCenterFrequency(hardwareCenterFreqHz(initialCenterHz))
        binding.spectrumView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(initialCenterHz))
        binding.waterfallView.setCenterFrequency(hardwareCenterFreqHz(initialCenterHz))
        binding.waterfallView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(initialCenterHz))

        binding.spectrumView.setSquelch(sqlDb)
        binding.spectrumView.setDemodChannelBandwidth(stepHz)
    }

    /** Out-of-the-box scan tab defaults, used only the very first time the app
     *  runs (or if the persisted last-used settings are ever cleared/corrupt).
     *  After that, [restoreLastScanTabSettings] / [saveLastScanTabSettings]
     *  take over and the user's own last configuration wins. */
    private object ScanTabDefaults {
        const val START_MHZ = "446.00625"
        const val STOP_MHZ = "446.19375"
        const val STEP_HZ = "12500"
        const val SQUELCH_DB = "-60"
        const val DWELL_MS = "200"
    }

    /** SharedPreferences backing the *automatic* "last used scan settings"
     *  persistence -- distinct from the user-managed 5-slot memory bank
     *  (`scan_memory_slots`) further down in [setupScanTab]. */
    private val scanTabLastSettingsPrefs by lazy {
        getSharedPreferences("scan_last_settings", MODE_PRIVATE)
    }

    private fun saveLastScanTabSettings() {
        scanTabLastSettingsPrefs.edit()
            .putString("startMhz", ctrlViews.etScanTabStart?.text?.toString())
            .putString("stopMhz", ctrlViews.etScanTabStop?.text?.toString())
            .putString("stepHz", ctrlViews.etScanTabStep?.text?.toString())
            .putString("squelchDb", ctrlViews.etScanTabSquelch?.text?.toString())
            .putString("dwellMs", ctrlViews.etScanTabDwell?.text?.toString())
            .putInt("modeIdx", ctrlViews.spinnerScanTabMode?.selectedItemPosition ?: 0)
            .putBoolean("scanDown", ctrlViews.switchScanTabDirection?.isChecked ?: false)
            .putBoolean("adaptive", ctrlViews.switchScanTabAdaptive?.isChecked ?: false)
            .putBoolean("holdOnSignal", ctrlViews.switchScanTabHoldOnSignal?.isChecked ?: true)
            .apply()
    }

    private fun restoreLastScanTabSettings() {
        val prefs = scanTabLastSettingsPrefs
        ctrlViews.etScanTabStart?.setText(prefs.getString("startMhz", ScanTabDefaults.START_MHZ))
        ctrlViews.etScanTabStop?.setText(prefs.getString("stopMhz", ScanTabDefaults.STOP_MHZ))
        ctrlViews.etScanTabStep?.setText(prefs.getString("stepHz", ScanTabDefaults.STEP_HZ))
        ctrlViews.etScanTabSquelch?.setText(prefs.getString("squelchDb", ScanTabDefaults.SQUELCH_DB))
        ctrlViews.etScanTabDwell?.setText(prefs.getString("dwellMs", ScanTabDefaults.DWELL_MS))
        ctrlViews.spinnerScanTabMode?.setSelection(prefs.getInt("modeIdx", viewModel.demodMode.value.ordinal))
        ctrlViews.switchScanTabDirection?.isChecked = prefs.getBoolean("scanDown", false)
        ctrlViews.switchScanTabAdaptive?.isChecked = prefs.getBoolean("adaptive", false)
        ctrlViews.switchScanTabHoldOnSignal?.isChecked = prefs.getBoolean("holdOnSignal", true)
    }

    private fun setupScanTab() {
        val scanRoot = pagerAdapter.getViewAt(ControlsPagerAdapter.TAB_SCAN) ?: return
        val etmBox = scanRoot.findViewById<android.widget.LinearLayout>(R.id.etmContainer)
        val generalBox = scanRoot.findViewById<View>(R.id.generalScanContainer)
        etm.bind(etmBox)
        scanRoot.findViewById<SegmentedSelectorView>(R.id.scannerTypePicker)?.apply {
            setChoices(listOf("etm" to "ETM", "range" to "范围扫描"), "etm")
            onSelected = { type ->
                etmBox.isVisible = type == "etm"; generalBox.isVisible = type == "range"
                resizePagerToCurrentPage()
            }
        }
        val listView = ctrlViews.listScanTabHits ?: return

        // Scanner details (start/stop/step/squelch/dwell/mode/direction/adaptive/
        // memory) are collapsed by default; only the toggle header, Start Scan
        // controls, and status/results are visible until the user expands it.
        ctrlViews.llScanTabDetails?.isVisible = false
        ctrlViews.tvScanTabDetailsChevron?.text = "\u25B6"
        ctrlViews.rowScanTabDetailsToggle?.setOnClickListener {
            val details = ctrlViews.llScanTabDetails
            val expanded = details?.isVisible == true
            details?.isVisible = !expanded
            ctrlViews.tvScanTabDetailsChevron?.text = if (expanded) "\u25B6" else "\u25BC"
        }

        val modes = DemodMode.values().map { it.displayName }
        ctrlViews.spinnerScanTabMode?.adapter =
            ArrayAdapter(this, R.layout.compact_spinner_item, modes)

        // Restore whatever scan parameters were last used (separate from the
        // manual 5-slot memory bank above -- this is the *automatic* "keep my
        // last scan setup" persistence). On first-ever launch, when nothing
        // has been saved yet, fall back to sensible defaults rather than
        // leaving the fields at whatever hardcoded values used to be baked
        // into the layout.
        restoreLastScanTabSettings()

        // Scan parameter memory slots -- stash/recall a full parameter set so the user
        // doesn't have to retype range/step/squelch/dwell/mode each session.
        val scanTabMemPrefs = getSharedPreferences("scan_memory_slots", MODE_PRIVATE)
        val scanTabMemSlotCount = 5
        val scanTabMemSlotLabels = (1..scanTabMemSlotCount).map { slot ->
            if (scanTabMemPrefs.getString("slot_$slot", null) != null) "Slot $slot (saved)" else "Slot $slot (empty)"
        }.toMutableList()
        val scanTabMemSlotAdapter = ArrayAdapter(this, R.layout.compact_spinner_item, scanTabMemSlotLabels)
        ctrlViews.spinnerScanTabMemorySlot?.adapter = scanTabMemSlotAdapter

        fun refreshScanTabMemSlotLabels() {
            for (i in 0 until scanTabMemSlotCount) {
                val slot = i + 1
                val saved = scanTabMemPrefs.getString("slot_$slot", null)
                scanTabMemSlotAdapter.remove(scanTabMemSlotAdapter.getItem(i))
                scanTabMemSlotAdapter.insert(if (saved != null) "Slot $slot (saved)" else "Slot $slot (empty)", i)
            }
            scanTabMemSlotAdapter.notifyDataSetChanged()
        }

        ctrlViews.btnScanTabMemSave?.setOnClickListener {
            val slot = (ctrlViews.spinnerScanTabMemorySlot?.selectedItemPosition ?: 0) + 1
            val params = JSONObject().apply {
                put("startMhz", ctrlViews.etScanTabStart?.text?.toString() ?: "")
                put("stopMhz", ctrlViews.etScanTabStop?.text?.toString() ?: "")
                put("stepHz", ctrlViews.etScanTabStep?.text?.toString() ?: "")
                put("squelchDb", ctrlViews.etScanTabSquelch?.text?.toString() ?: "")
                put("dwellMs", ctrlViews.etScanTabDwell?.text?.toString() ?: "")
                put("modeIdx", ctrlViews.spinnerScanTabMode?.selectedItemPosition ?: 0)
                put("scanDown", ctrlViews.switchScanTabDirection?.isChecked ?: false)
                put("adaptive", ctrlViews.switchScanTabAdaptive?.isChecked ?: false)
                put("holdOnSignal", ctrlViews.switchScanTabHoldOnSignal?.isChecked ?: true)
            }
            scanTabMemPrefs.edit().putString("slot_$slot", params.toString()).apply()
            refreshScanTabMemSlotLabels()
            Snackbar.make(binding.root, "Saved scan parameters to slot $slot", Snackbar.LENGTH_SHORT).show()
        }

        ctrlViews.btnScanTabMemLoad?.setOnClickListener {
            val slot = (ctrlViews.spinnerScanTabMemorySlot?.selectedItemPosition ?: 0) + 1
            val raw = scanTabMemPrefs.getString("slot_$slot", null)
            if (raw == null) {
                Snackbar.make(binding.root, "Slot $slot is empty", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                val params = JSONObject(raw)
                ctrlViews.etScanTabStart?.setText(params.getString("startMhz"))
                ctrlViews.etScanTabStop?.setText(params.getString("stopMhz"))
                ctrlViews.etScanTabStep?.setText(params.getString("stepHz"))
                ctrlViews.etScanTabSquelch?.setText(params.getString("squelchDb"))
                ctrlViews.etScanTabDwell?.setText(params.getString("dwellMs"))
                ctrlViews.spinnerScanTabMode?.setSelection(params.optInt("modeIdx", 0))
                ctrlViews.switchScanTabDirection?.isChecked = params.optBoolean("scanDown", false)
                ctrlViews.switchScanTabAdaptive?.isChecked = params.optBoolean("adaptive", false)
                ctrlViews.switchScanTabHoldOnSignal?.isChecked = params.optBoolean("holdOnSignal", true)
                Snackbar.make(binding.root, "Loaded scan parameters from slot $slot", Snackbar.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Slot $slot is corrupted", Snackbar.LENGTH_SHORT).show()
            }
        }

        fun confirmLockout(hz: Long) {
            AlertDialog.Builder(this).setTitle("${hz.toExactMhzString()} MHz")
                .setMessage("本轮扫描中排除此频率？")
                .setPositiveButton("排除") { _,_ ->
                    scanTabScanner?.lockout(hz)
                    Snackbar.make(binding.root,"已排除 ${hz.toExactMhzString()} MHz",Snackbar.LENGTH_SHORT).show()
                }.setNegativeButton("取消",null).show()
        }
        scanTabHitAdapter=listView
        listView.setColumns(listOf(RadioDataTable.Column("时间",76),RadioDataTable.Column("频率 MHz",110),
            RadioDataTable.Column("模式",64),RadioDataTable.Column("电平 dBFS",94),RadioDataTable.Column("来源",68)))
        listView.submitRows(scanTabHitLog)
        listView.onRowClick={ position -> scanTabHitFreqs.getOrNull(position)?.let { viewModel.setFrequency(it) } }
        listView.onRowLongClick={ position -> scanTabHitFreqs.getOrNull(position)?.let { confirmLockout(it) }; true }

        val channelListView=ctrlViews.listScanTabChannelTable
        scanTabChannelAdapter=channelListView
        channelListView?.setColumns(listOf(RadioDataTable.Column("状态",40),RadioDataTable.Column("频率 MHz",110),
            RadioDataTable.Column("电平 dBFS",94),RadioDataTable.Column("底噪 dBFS",94),RadioDataTable.Column("次数",56),RadioDataTable.Column("更新 s",64)))
        channelListView?.onRowClick={ position -> scanTabChannelRows.getOrNull(position)?.let { viewModel.setFrequency(it.freqHz) } }
        channelListView?.onRowLongClick={ position -> scanTabChannelRows.getOrNull(position)?.let { confirmLockout(it.freqHz) }; true }
        renderScanChannelRows()

        fun resetScanTabControls() {
            scanTabStartJob?.cancel();scanTabStartJob=null
            scanTabScanning = false
            scanTabPaused = false
            scanTabSearching = false
            ctrlViews.btnScanTabStartStop?.text = "开始"
            ctrlViews.btnScanTabPause?.text = "暂停"
            ctrlViews.btnScanTabPause?.isVisible = false
            ctrlViews.btnScanTabSearch?.text = "搜索模式"
            ctrlViews.btnScanTabSearch?.isEnabled = true
            ctrlViews.btnScanTabStartStop?.isEnabled = true
            ctrlViews.progressScanTab?.isVisible = false
        }

        fun scanTabSource(): Pair<RtlSdrService, IqSource>? {
            val svc = sdrService
            if (svc == null) {
                Snackbar.make(binding.root, "No receiver connected", Snackbar.LENGTH_SHORT).show()
                return null
            }
            val source = svc.source
            if (source == null) {
                Snackbar.make(binding.root, "No IQ source connected", Snackbar.LENGTH_SHORT).show()
                return null
            }
            return svc to source
        }

        fun signalLevelProviderFor(svc: RtlSdrService, source: IqSource): () -> Float = {
            // Channel-specific dBFS at the tuned (DC) frequency -- NOT the
            // wideband spectrum peak (statsFlow.signalDb). The wideband peak
            // reflects the strongest signal anywhere across the whole
            // captured window (e.g. 2 MHz), so as the scanner retuned
            // channel to channel it barely changed and Squelch dB ended up
            // gating on an essentially constant, channel-irrelevant value.
            // narrowbandCenterDb averages only the FFT bins around the tuned
            // centre frequency, so it actually reflects the channel
            // currently being probed. See FrequencyScanner / DspEngine
            // squelch fix notes above (DspEngine.narrowbandCenterDb).
            svc.dspEngine?.statsFlow?.value?.narrowbandCenterDb ?: source.statusFlow.value.signalStrengthDb
        }

        fun attachScanTabCollectors(sc: com.radiosport.ninegradio.scanner.FrequencyScanner) {
            scanTabStatusJob?.cancel()
            scanTabStatusJob = lifecycleScope.launch {
                sc.status.collectLatest { status ->
                    ctrlViews.progressScanTab?.progress = (status.progress * 100).toInt()
                    ctrlViews.tvScanTabCurrentFreq?.text = "${status.currentFreqHz.toExactMhzString()} MHz"
                    ctrlViews.tvScanTabHits?.text = "找到：${status.hitsFound}"
                    ctrlViews.tvScanTabSignal?.text = "${"%.1f".format(status.signalDb)} dB"
                    ctrlViews.tvScanTabNoiseFloor?.text = "底噪：${"%.1f".format(status.noiseFloorDb)} dB"
                    ctrlViews.tvScanTabRate?.text = "${"%.1f".format(status.channelsPerSecond)} ch/s"

                    // FrequencyScanner retunes the raw IqSource directly every
                    // hop (see captureBlock), so the spectrum/waterfall's own
                    // notion of "where the hardware is" goes stale the moment
                    // a scan starts unless it's told about every retune too.
                    // These are display-only setters -- they don't re-issue a
                    // hardware tune command themselves -- so they can't race
                    // with the scanner, which is the sole owner of the device
                    // frequency while a scan is running.
                    if (status.currentFreqHz > 0L) {
                        binding.spectrumView.setCenterFrequency(hardwareCenterFreqHz(status.currentFreqHz))
                        binding.spectrumView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(status.currentFreqHz))
                        binding.waterfallView.setCenterFrequency(hardwareCenterFreqHz(status.currentFreqHz))
                        binding.waterfallView.setHardwareTunedFrequency(trueHardwareCenterFreqHz(status.currentFreqHz))
                    }
                    if (status.activeFreqHz > 0L) {
                        binding.spectrumView.setDemodDialFrequency(status.activeFreqHz)
                    }
                }
            }
            scanTabHitsJob?.cancel()
            scanTabHitsJob = lifecycleScope.launch {
                sc.hits.collect { hit ->
                    // FrequencyScanner retunes the raw IqSource directly for its own
                    // wideband FFT captures (see FrequencyScanner.captureBlock), entirely
                    // bypassing DspEngine.setCarrierFrequency. That means the *live* demod
                    // chain (DspEngine -> AudioEngine) was never actually told to move to
                    // the frequency the scanner just declared a hit on -- it kept
                    // demodulating whatever dial frequency was tuned before Start Scan was
                    // pressed. The scan tab's spectrum/waterfall dial cosmetically jumped to
                    // the hit (setDemodDialFrequency above) making it *look* locked, but no
                    // audio ever came out because the hardware carrier/BFO math in DspEngine
                    // never got the retune. Route the hit frequency through
                    // RtlSdrService.setFrequency() (-> DspEngine.setCarrierFrequency()) so
                    // the live receiver actually tunes there and decodes audio while the
                    // scanner dwells on this hit, matching how real scanners (and e.g.
                    // SDRangel's channel-follows-detection behaviour) sync their live
                    // demodulator to whatever the scan engine just found.
                    sdrService?.setFrequency(hit.freqHz)

                    val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(hit.timestampMs))
                    val origin = when(hit.source) {
                        com.radiosport.ninegradio.scanner.FrequencyScanner.HitSource.PRIORITY -> "优先"
                        com.radiosport.ninegradio.scanner.FrequencyScanner.HitSource.SEARCH -> "搜索"
                        else -> "扫描"
                    }
                    val entry=RadioDataTable.Row(listOf(ts,hit.freqHz.toExactMhzString(),hit.mode.displayName,"%.1f".format(hit.signalDb),origin))
                    scanTabHitLog.add(0, entry)
                    scanTabHitFreqs.add(0, hit.freqHz)
                    if (scanTabHitLog.size > 300) {
                        scanTabHitLog.removeAt(scanTabHitLog.size - 1)
                        scanTabHitFreqs.removeAt(scanTabHitFreqs.size - 1)
                    }
                    scanTabHitAdapter?.submitRows(scanTabHitLog)

                    // Persist every confirmed hit, same as the standalone scanner.
                    val db = (application as com.radiosport.ninegradio.RtlSdrApplication).database
                    lifecycleScope.launch {
                        db.signalLogDao().insert(
                            com.radiosport.ninegradio.data.SignalLog(
                                frequencyHz = hit.freqHz,
                                demodMode = hit.mode.name,
                                signalDb = hit.signalDb,
                                timestamp = hit.timestampMs,
                                notes = hit.source.name.lowercase()
                            )
                        )
                    }
                }
            }

            scanTabChannelTableJob?.cancel()
            scanTabChannelTableJob = lifecycleScope.launch {
                sc.channelTable.sample(250).collect { rows ->
                    scanTabChannelRows.clear()
                    scanTabChannelRows.addAll(rows)
                    renderScanChannelRows()
                }
            }
        }

        ctrlViews.btnScanTabStartStop?.setOnClickListener {
            if (etm.isRunning) return@setOnClickListener
            if (!scanTabScanning) {
                val (svc, source) = scanTabSource() ?: return@setOnClickListener

                val startMhz = ctrlViews.etScanTabStart?.text?.toString()?.toDoubleOrNull() ?: 136.0
                val stopMhz  = ctrlViews.etScanTabStop?.text?.toString()?.toDoubleOrNull() ?: (startMhz + 38.0)
                val startHz = (startMhz * 1_000_000).toLong()
                    .coerceIn(RtlSdrDevice.MIN_FREQ_HZ, RtlSdrDevice.MAX_FREQ_HZ)
                val stopHz  = (stopMhz * 1_000_000).toLong()
                    .coerceIn(startHz + 1, RtlSdrDevice.MAX_FREQ_HZ)
                val stepHz  = ctrlViews.etScanTabStep?.text?.toString()?.toLongOrNull() ?: 12_500L
                val snappedStartHz = FrequencyStepManager.snapToChannel(startHz, stepHz)
                    .coerceIn(RtlSdrDevice.MIN_FREQ_HZ, RtlSdrDevice.MAX_FREQ_HZ)
                val sqlDb   = ctrlViews.etScanTabSquelch?.text?.toString()?.toFloatOrNull() ?: -80f
                val dwellMs = ctrlViews.etScanTabDwell?.text?.toString()?.toLongOrNull() ?: 200L
                val modeIdx = ctrlViews.spinnerScanTabMode?.selectedItemPosition ?: 0
                val mode    = DemodMode.values().getOrElse(modeIdx) { DemodMode.NFM }
                val adaptive = ctrlViews.switchScanTabAdaptive?.isChecked ?: false
                val holdOnSignal = ctrlViews.switchScanTabHoldOnSignal?.isChecked ?: true

                // Remember these as the "last used" scan settings so they're
                // restored automatically next time the scan tab is opened,
                // regardless of whether the user ever touches the manual
                // memory-slot bank above.
                saveLastScanTabSettings()

                // The scanner retunes the raw IqSource directly (bypassing
                // DspEngine.setCarrierFrequency), but DspEngine keeps demodulating
                // and playing audio continuously through the retunes using whatever
                // mode/squelch was active *before* Start Scan was pressed. Without
                // this sync, a user who picks e.g. NFM in the scan tab's mode
                // spinner would still hear (or fail to hear) audio demodulated in
                // the previously-selected mode on every hit, and the live squelch
                // gate would be completely unrelated to the scanner's own
                // detection threshold. Apply the scan's mode/squelch to the live
                // demod chain before the sweep starts so audio on a hit is
                // actually correct for the mode the user configured.
                captureRfSnapshotBeforeScan()
                svc.setDemodMode(mode)
                svc.setSquelch(sqlDb)

                configureSpectrumForScan(snappedStartHz, stopHz, stepHz, sqlDb, dwellMs)
                scanTabScanning=true
                ctrlViews.btnScanTabSearch?.isEnabled=false
                scanTabStartJob=lifecycleScope.launch {
                    try {
                        svc.awaitControls()
                        val source=svc.source ?: error("接收机连接已改变")
                        svc.setDemodMode(mode);svc.setSquelch(sqlDb);svc.awaitControls()
                        scanTabScanner = com.radiosport.ninegradio.scanner.FrequencyScanner(
                            source, tuner=svc.scannerTuner(source),signalLevelProvider = signalLevelProviderFor(svc, source),
                            // Keep the live spectrum's frame averaging/smoothing from
                            // blending frames across the scanner's retunes -- see
                            // FftEngine.resetSpectrumAveraging() for why that matters
                            // to Auto dB Range specifically.
                            onRetune = { svc.dspEngine?.fftEngine?.resetSpectrumAveraging() }
                        ).also { sc ->
                            sc.startScan(com.radiosport.ninegradio.scanner.FrequencyScanner.ScanConfig(
                                startFreqHz = snappedStartHz,
                                stopFreqHz  = stopHz,
                                stepHz = stepHz,
                                squelchDb = sqlDb,
                                adaptiveSquelch = adaptive,
                                holdOnSignal = holdOnSignal,
                                dwellTimeMs = dwellMs,
                                mode = mode,
                                scanUp = !(ctrlViews.switchScanTabDirection?.isChecked ?: false),
                                label = "${"%.3f".format(startMhz)}-${"%.3f".format(stopMhz)} MHz"
                            ))
                            attachScanTabCollectors(sc)
                        }
                        scanTabScanning = true
                        ctrlViews.btnScanTabStartStop?.text = "停止"
                        ctrlViews.btnScanTabPause?.isVisible = true
                        ctrlViews.progressScanTab?.isVisible = true
                        ctrlViews.btnScanTabSearch?.isEnabled = false
                    } catch(cancel:kotlinx.coroutines.CancellationException) { throw cancel }
                    catch(error:Exception) {
                        resetScanTabControls()
                        Snackbar.make(binding.root,"无法开始扫频，请检查接收机连接",Snackbar.LENGTH_SHORT).show()
                    }
                }
            } else {
                scanTabScanner?.stopScan()
                scanTabScanner = null
                ctrlViews.btnScanTabSearch?.isEnabled = true
                resetScanTabControls()
                restoreRfSnapshotAfterScan()
            }
        }

        ctrlViews.btnScanTabPause?.setOnClickListener {
            if (!scanTabPaused) {
                scanTabScanner?.pauseScan()
                ctrlViews.btnScanTabPause?.text = "继续"
                scanTabPaused = true
            } else {
                scanTabScanner?.resumeScan()
                ctrlViews.btnScanTabPause?.text = "暂停"
                scanTabPaused = false
            }
        }

        ctrlViews.btnScanTabSearch?.setOnClickListener {
            if (!scanTabSearching) {
                val (svc, source) = scanTabSource() ?: return@setOnClickListener
                val startMhz = ctrlViews.etScanTabStart?.text?.toString()?.toDoubleOrNull() ?: 136.0
                val stopMhz  = ctrlViews.etScanTabStop?.text?.toString()?.toDoubleOrNull() ?: (startMhz + 38.0)
                val startHz = (startMhz * 1_000_000).toLong().coerceIn(RtlSdrDevice.MIN_FREQ_HZ, RtlSdrDevice.MAX_FREQ_HZ)
                val stopHz  = (stopMhz * 1_000_000).toLong().coerceIn(startHz + 1, RtlSdrDevice.MAX_FREQ_HZ)
                val modeIdx = ctrlViews.spinnerScanTabMode?.selectedItemPosition ?: 0
                val mode    = DemodMode.values().getOrElse(modeIdx) { DemodMode.NFM }
                // Same squelch controls the regular scan uses — search mode
                // previously ignored these entirely and always ran with a
                // forced adaptive threshold, which is why it picked up noise
                // regardless of what was set here.
                val sqlDb   = ctrlViews.etScanTabSquelch?.text?.toString()?.toFloatOrNull() ?: -80f
                val adaptive = ctrlViews.switchScanTabAdaptive?.isChecked ?: false

                saveLastScanTabSettings()

                // See the matching comment in the Start Scan handler: keep the
                // live demod chain in sync with what the scan tab is configured
                // for, so audio on a hit is intelligible.
                captureRfSnapshotBeforeScan()
                svc.setDemodMode(mode)
                svc.setSquelch(sqlDb)

                // Search mode has no user-facing step field; it hunts within
                // whatever channel raster FrequencyScanner's search default
                // uses, so fall back to the regular scan tab's step field
                // (or a sane default) purely to size the capture width below.
                val searchStepHz = ctrlViews.etScanTabStep?.text?.toString()?.toLongOrNull() ?: 12_500L
                configureSpectrumForScan(startHz, stopHz, searchStepHz, sqlDb, dwellMs = 40L)
                scanTabScanning=true;scanTabSearching=true
                ctrlViews.btnScanTabStartStop?.isEnabled=false
                scanTabStartJob=lifecycleScope.launch {
                    try {
                        svc.awaitControls()
                        val source=svc.source ?: error("接收机连接已改变")
                        svc.setDemodMode(mode);svc.setSquelch(sqlDb);svc.awaitControls()
                        scanTabScanner = com.radiosport.ninegradio.scanner.FrequencyScanner(
                            source, tuner=svc.scannerTuner(source),signalLevelProvider = signalLevelProviderFor(svc, source),
                            onRetune = { svc.dspEngine?.fftEngine?.resetSpectrumAveraging() }
                        ).also { sc ->
                            sc.startSearch(
                                startFreqHz = startHz, stopFreqHz = stopHz, mode = mode,
                                squelchDb = sqlDb, adaptiveSquelch = adaptive
                            )
                            attachScanTabCollectors(sc)
                        }
                        scanTabSearching = true
                        scanTabScanning = true
                        ctrlViews.btnScanTabSearch?.text = "停止搜索"
                        ctrlViews.btnScanTabStartStop?.isEnabled = false
                        ctrlViews.progressScanTab?.isVisible = true
                    } catch(cancel:kotlinx.coroutines.CancellationException) { throw cancel }
                    catch(error:Exception) {
                        resetScanTabControls()
                        Snackbar.make(binding.root,"无法开始搜索，请检查接收机连接",Snackbar.LENGTH_SHORT).show()
                    }
                }
            } else {
                scanTabScanner?.stopScan()
                scanTabScanner = null
                ctrlViews.btnScanTabStartStop?.isEnabled = true
                resetScanTabControls()
                restoreRfSnapshotAfterScan()
            }
        }

        ctrlViews.btnScanTabReset?.setOnClickListener {
            // Stop any in-progress scan/search so the reset state is clean.
            scanTabScanner?.stopScan()
            scanTabScanner?.destroy()
            scanTabScanner = null
            ctrlViews.btnScanTabStartStop?.isEnabled = true
            ctrlViews.btnScanTabSearch?.isEnabled = true
            resetScanTabControls()
            restoreRfSnapshotAfterScan()

            // Clear the scan results list.
            scanTabHitLog.clear()
            scanTabHitFreqs.clear()
            scanTabHitAdapter?.submitRows(scanTabHitLog)

            // Clear the tabulated channel view too.
            scanTabChannelRows.clear()
            renderScanChannelRows()

            // Reset the live status readouts.
            ctrlViews.progressScanTab?.progress = 0
            ctrlViews.tvScanTabCurrentFreq?.text = "---"
            ctrlViews.tvScanTabHits?.text = "找到：0"
            ctrlViews.tvScanTabSignal?.text = "--- dB"
            ctrlViews.tvScanTabNoiseFloor?.text = "底噪：--- dB"
            ctrlViews.tvScanTabRate?.text = "--- ch/s"

            Snackbar.make(binding.root, "Scan list reset", Snackbar.LENGTH_SHORT).show()
        }

        ctrlViews.btnScanTabBusiest?.setOnClickListener {
            val sc = scanTabScanner
            if (sc == null) {
                Snackbar.make(binding.root, "Start a scan first", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val top = sc.topFrequencies(15)
            if (top.isEmpty()) {
                Snackbar.make(binding.root, "No hits recorded yet", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val message = top.joinToString("\n") { (hz, count) ->
                "${hz.toExactMhzString()} MHz  —  $count hit${if (count == 1) "" else "s"}"
            }
            AlertDialog.Builder(this)
                .setTitle("Busiest Frequencies")
                .setMessage(message)
                .setPositiveButton("Close", null)
                .show()
        }

        ctrlViews.btnScanTabClose?.setOnClickListener { hideScanTab() }
    }

    /**
     * Reveals the Scan tab and navigates to it. Mirrors [updateAprsTabVisibility]
     * et al., except there is no associated DemodMode driving it -- it is only
     * ever called from an explicit user action (Scanner button / FAB).
     */
    private fun showScanTab() {
        val tabLayout = binding.controlTabLayout
        val scanTabIndex = ControlsPagerAdapter.TAB_SCAN
        moduleTab(scanTabIndex)?.view?.visibility = android.view.View.VISIBLE
        binding.controlViewPager.setCurrentItem(pagerAdapter.positionOf(scanTabIndex), true)
        ctrlViews.etScanTabStart?.setText(viewModel.centerFreqHz.value.toExactMhzString())
    }

    /**
     * Stops any running scan and hides the Scan tab again. If the currently
     * selected tab is Scan, falls back to the Tune tab so the drawer never
     * ends up parked on a hidden tab.
     */
    private fun hideScanTab() {
        scanTabScanner?.stopScan()
        scanTabScanner?.destroy()
        scanTabScanner = null
        scanTabStatusJob?.cancel()
        scanTabHitsJob?.cancel()
        scanTabScanning = false
        scanTabPaused = false
        scanTabSearching = false
        restoreRfSnapshotAfterScan()
        ctrlViews.btnScanTabStartStop?.text = "开始"
        ctrlViews.btnScanTabPause?.isVisible = false
        ctrlViews.progressScanTab?.isVisible = false

        val tabLayout = binding.controlTabLayout
        val scanTabIndex = ControlsPagerAdapter.TAB_SCAN
        if (pagerAdapter.moduleAt(binding.controlViewPager.currentItem) == scanTabIndex) {
            binding.controlViewPager.setCurrentItem(pagerAdapter.positionOf(defaultModule()), false)
        }
        moduleTab(scanTabIndex)?.view?.visibility = if (ModulePreferences(this).visible(scanTabIndex)) View.VISIBLE else View.GONE
    }

    private fun renderScanChannelRows() {
        scanTabChannelAdapter?.submitRows(scanTabChannelRows.map { row ->
            val known=row.lastUpdatedMs>0
            RadioDataTable.Row(listOf(if(row.active) "●" else "○",row.freqHz.toExactMhzString(),
                if(known) "%.1f".format(row.lastPowerDb) else "—",
                if(known) "%.1f".format(row.lastNoiseFloorDb) else "—",row.hitCount.toString(),
                if(known) ((System.currentTimeMillis()-row.lastUpdatedMs).coerceAtLeast(0)/1000).toString() else "—"),row.active)
        })
    }

} // end MainActivity

/**
 * Returns the [UsbDevice] extra from this intent using the API-level-appropriate
 * overload of [Intent.getParcelableExtra].
 *
 * The single-type-parameter form `getParcelableExtra<T>()` is deprecated since
 * API 33 (Android 13) and returns null on API 33+ release builds, breaking USB
 * hotplug detection.  The two-parameter form `getParcelableExtra(key, Class<T>)`
 * is required on API 33+.
 */
private fun Intent.usbDevice(): UsbDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    else
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
