package com.radiosport.ninegradio.ui

import android.content.Context
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.scanner.FrequencyScanner
import com.radiosport.ninegradio.usb.RtlSdrService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first

/** ETM memories are separate from bookmarks; a sweep stops after one complete pass. */
class EtmController(private val activity: AppCompatActivity,
    private val service: () -> RtlSdrService?, private val mode: () -> DemodMode,
    private val frequency: () -> Long,
    private val tune: (DemodMode, Long) -> Unit, private val preview: (Long) -> Unit,
    private val autoMode: () -> Boolean = { false }, private val restoreAuto: (Boolean) -> Unit = {},
    private val canStart: () -> Boolean = { true }) : DefaultLifecycleObserver {
    data class Station(val hz: Long, val power: Float, val mode: String)
    private val prefs = activity.getSharedPreferences("etm_memories", Context.MODE_PRIVATE)
    private var task: Job? = null
    private var scanner: FrequencyScanner? = null
    private var selector: SegmentedSelectorView? = null
    private var thresholdControl: SeekBar? = null
    private var list: RadioDataTable? = null
    private var rows = emptyList<Station>()
    private var status: TextView? = null
    private var dialog: AlertDialog? = null
    private var band = "SW"
    private val stations = sortedMapOf<Long, Station>()
    private var threshold = prefs.getInt("threshold", 14).coerceIn(10, 24)
    init {
        activity.lifecycle.addObserver(this)
        if (!prefs.getBoolean("detector_v2", false)) prefs.edit().remove("SW").remove("FM").putBoolean("detector_v2", true).apply()
    }

    private fun createContent(): LinearLayout {
        val pad = (8 * activity.resources.displayMetrics.density).toInt()
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, pad, 0, pad) }
        status = TextView(activity).apply { text = "暂无暂存电台"; textSize=12f; setRadioTextColor(com.radiosport.ninegradio.R.attr.radioSecondary) }
        content.addView(SegmentedSelectorView(activity).apply {
            selector = this
            setChoices(listOf("SW" to "SW", "FM" to "FM"), band)
            onSelected = { key -> if (task?.isActive != true) { band = key; load(); render() } }
        }, LinearLayout.LayoutParams(-1, (44 * activity.resources.displayMetrics.density).toInt()))
        val thresholdText = TextView(activity).apply { text = "确认门限 · $threshold dB"; textSize=12f; setRadioTextColor(com.radiosport.ninegradio.R.attr.radioSecondary) }
        content.addView(thresholdText)
        content.addView(SeekBar(activity).apply {
            thresholdControl = this
            max = 14; progress = threshold - 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser && task?.isActive != true) { threshold = value + 10; thresholdText.text = "确认门限 · $threshold dB" }
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) { prefs.edit().putInt("threshold", threshold).apply() }
            })
        })
        fun action(label: String, block: () -> Unit) = (activity.layoutInflater.inflate(com.radiosport.ninegradio.R.layout.radio_compact_button,content,false) as Button)
            .apply { text=label; setOnClickListener { block() } }
        content.addView(LinearLayout(activity).apply {
            val actions=listOf(action("开始") { start() },action("停止") { stop() },
                action("清空") { if(task?.isActive!=true) { stations.clear();save();render() } })
            actions.forEachIndexed { index,button ->
                addView(button,LinearLayout.LayoutParams(0,(48*resources.displayMetrics.density).toInt(),1f).apply {
                    marginStart=if(index==0) 0 else pad/2
                    marginEnd=if(index==actions.lastIndex) 0 else pad/2
                })
            }
        })
        content.addView(status)
        list=RadioDataTable(activity).apply {
            setColumns(listOf(RadioDataTable.Column("序号",44),RadioDataTable.Column("频率 MHz",110),
                RadioDataTable.Column("模式",64),RadioDataTable.Column("电平 dBFS",94)))
            onRowClick=selected@ { position ->
                val station=rows.getOrNull(position) ?: return@selected
                activity.lifecycleScope.launch {
                    task?.cancelAndJoin(); tune(DemodMode.valueOf(station.mode),station.hz); dialog?.dismiss()
                }
            }
        }
        content.addView(list,LinearLayout.LayoutParams(-1,(220*activity.resources.displayMetrics.density).toInt()))
        load(); render()
        return content
    }
    fun bind(container: LinearLayout) {
        container.removeAllViews()
        container.addView(createContent())
        load(); render()
    }
    fun show() {
        dialog = AlertDialog.Builder(activity).setTitle("ETM").setView(createContent())
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog!!.setOnDismissListener { stop(); list = null; status = null }
        dialog!!.show(); load(); render()
    }
    private fun load() {
        stations.clear()
        val saved = runCatching { Gson().fromJson<List<Station>>(prefs.getString(band, "[]"), object : TypeToken<List<Station>>() {}.type) }.getOrDefault(emptyList())
        for (station in saved) stations[station.hz] = station
    }
    private fun save() { prefs.edit().putString(band, Gson().toJson(stations.values.toList())).apply() }
    private fun render() {
        if (list == null) return
        rows = stations.values.toList()
        if(task?.isCompleted!=false) status?.text="$band · 暂存 ${rows.size} 个电台"
        list?.submitRows(rows.mapIndexed { index,station -> RadioDataTable.Row(listOf((index+1).toString(),
            "%.3f".format(station.hz/1e6),station.mode,"%.1f".format(station.power))) })
    }

    private fun start() {
        if (task?.isCompleted == false) return
        if (!canStart()) return
        val svc = service()
        val source = svc?.source
        if (svc == null || source == null) { status?.text = "请先点击 OPEN 启动接收机。"; return }
        if (source.capabilities.id == "file") { status?.text = "文件回放使用录制频率；ETM 需要可调谐的接收机。"; return }
        val recording = svc.dspEngine?.statsFlow?.value
        if (recording?.isRecordingIq == true || recording?.isRecordingAudio == true) {
            status?.text = "请先停止当前录制，再进行 ETM 扫频。"
            return
        }
        val desiredMode = if (band == "FM") DemodMode.WFM else DemodMode.AM
        val start = if (band == "FM") 87_500_000L else 2_300_000L
        val stop = if (band == "FM") 108_000_000L else 26_100_000L
        val ranges = source.capabilities.scanRanges(start, stop)
        if (ranges.isEmpty() || !source.capabilities.supportsMode(desiredMode)) { status?.text = "当前接收源不支持此范围。"; return }
        val previousHz = frequency()
        val previousMode = mode()
        val previousAuto = autoMode()
        selector?.isEnabled = false
        thresholdControl?.isEnabled = false
        stations.clear(); save(); render()
        task = activity.lifecycleScope.launch {
            var sc: FrequencyScanner? = null
            var hitJob: Job? = null
            var statusJob: Job? = null
            var renderJob: Job? = null
            try {
                tune(desiredMode, ranges.first().first)
                svc.awaitControls()
                svc.dspEngine?.setListeningEnabled(false)
                sc = FrequencyScanner(source, onRetune = { hz ->
                    svc.dspEngine?.fftEngine?.resetSpectrumAveraging()
                    activity.runOnUiThread { if (task?.isActive == true) preview(hz) }
                })
                scanner = sc
                val activeScanner = sc
                hitJob = launch(start = CoroutineStart.UNDISPATCHED) { activeScanner.hits.collect { hit ->
                    if (stations.size < 512 || hit.freqHz in stations) {
                        val spacing = if (band == "FM") 150_000L else 5_000L
                        val nearby = stations.values.filter { kotlin.math.abs(it.hz - hit.freqHz) <= spacing }
                        if (nearby.none { it.power >= hit.signalDb }) {
                            nearby.forEach { stations.remove(it.hz) }
                            stations[hit.freqHz] = Station(hit.freqHz, hit.signalDb, desiredMode.name)
                        }
                    }
                } }
                statusJob = launch { activeScanner.status.collect { update ->
                    val progress = ((update.segmentIndex + update.progress) / update.segmentCount.coerceAtLeast(1) * 100).toInt()
                    status?.text = "$band · $progress% · ${"%.3f".format(update.currentFreqHz / 1e6)} MHz · ${stations.size} 个候选台"
                } }
                renderJob = launch { while (isActive) { delay(700); render() } }
                activeScanner.startMultiScan(ranges.map { range -> FrequencyScanner.ScanConfig(
                    startFreqHz = range.first, stopFreqHz = range.last,
                    stepHz = if (band == "FM") 100_000 else 5_000,
                    adaptiveSquelch = true, adaptiveMarginDb = threshold.toFloat(),
                    holdOnSignal = false, resumeTimeMs = 0, dwellTimeMs = 30,
                    mode = desiredMode, label = "ETM $band", singlePass = true, broadcastEtm = true) })
                activeScanner.status.first { it.state == FrequencyScanner.ScanState.SCANNING }
                activeScanner.status.first { it.state == FrequencyScanner.ScanState.STOPPED }
            } finally {
                withContext(NonCancellable) {
                    sc?.stopAndJoin()
                    hitJob?.cancelAndJoin(); statusJob?.cancelAndJoin(); renderJob?.cancelAndJoin()
                    save(); render()
                    if (svc.source === source) {
                        svc.dspEngine?.setListeningEnabled(true)
                        tune(previousMode, previousHz)
                        restoreAuto(previousAuto)
                        preview(previousHz)
                    }
                    selector?.isEnabled = true
                    thresholdControl?.isEnabled = true
                    scanner = null
                    status?.text = "$band · 已停止 · 暂存 ${stations.size} 个候选台"
                }
            }
        }
    }
    val isRunning get() = task?.isCompleted == false
    fun stop() { task?.cancel() }
    override fun onStop(owner: LifecycleOwner) { stop() }
}
