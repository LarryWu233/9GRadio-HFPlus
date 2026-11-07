package com.radiosport.ninegradio.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.radiosport.ninegradio.data.RegionalReception as Plans
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.source.DeviceCapabilities
import kotlinx.coroutines.*

/** Location is requested only from the visible recommendation panel, never in background. */
class RegionalReceptionController(private val activity: AppCompatActivity,
    private val capabilities: () -> DeviceCapabilities,
    private val currentSelection: () -> Pair<DemodMode, Long>,
    private val tune: (DemodMode, Long) -> Unit) : DefaultLifecycleObserver {
    private val prefs = activity.getSharedPreferences("receiving_region", Context.MODE_PRIVATE)
    private val manager = activity.getSystemService(LocationManager::class.java)
    private var region = runCatching { Plans.Region.valueOf(prefs.getString("region", "OTHER")!!) }.getOrDefault(Plans.Region.OTHER)
    private var airports = emptyList<Plans.Airport>()
    private var nearby = emptyList<Pair<Plans.Airport, Double>>()
    private var location: Location? = null
    private var pendingSelection: Pair<DemodMode, Long>? = null
    private var dialog: AlertDialog? = null
    private var root: LinearLayout? = null
    private var status: TextView? = null
    private var list: LinearLayout? = null
    private var timeout: Job? = null
    private var ready: Deferred<List<Plans.Airport>>
    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPermission()) locate() else status?.text = "未允许定位，可手动选择地区。"
    }
    private val listener = object : LocationListener {
        override fun onLocationChanged(value: Location) {
            if (value.accuracy > 5000f) return
            manager.removeUpdates(this)
            timeout?.cancel()
            useLocation(value)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Legacy Android callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    init {
        activity.lifecycle.addObserver(this)
        ready = activity.lifecycleScope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            activity.assets.open("receiving/airports.json").bufferedReader().use {
                Gson().fromJson<List<Plans.Airport>>(it, object : TypeToken<List<Plans.Airport>>() {}.type)
            }
        }
    }
    private fun hasPermission() = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun show() {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root = content
        status = TextView(activity).apply { text = "未定位；先选择地区，或读取当前位置。" }
        content.addView(status)
        content.addView(Button(activity).apply { text = "读取当前位置 / 刷新附近机场"; setOnClickListener {
            if (prefs.getBoolean("auto_apply", false)) pendingSelection = currentSelection()
            if (hasPermission()) locate() else permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        } })
        val spinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, Plans.Region.values().map { it.label })
            setSelection(region.ordinal)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    region = Plans.Region.values()[position]
                    prefs.edit().putString("region", region.name).apply()
                    render()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        content.addView(spinner)
        content.addView(MaterialSwitch(activity).apply {
            text = "进入数据 / 航空模式时自动应用推荐"
            isChecked = prefs.getBoolean("auto_apply", false)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("auto_apply", checked).apply() }
        })
        content.addView(TextView(activity).apply { text = "机场频率来自 OurAirports 社区离线数据，仅供接收参考。航空应急频率不等于本地塔台；APRS 地区表可手动修正。"; textSize = 12f })
        list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        dialog = AlertDialog.Builder(activity).setTitle("地区与附近机场推荐")
            .setView(ScrollView(activity).apply { addView(content) }).setNegativeButton(android.R.string.cancel, null).create()
        dialog!!.setOnDismissListener { stopLocation(); root = null; list = null; status = null }
        dialog!!.show()
        render()
        activity.lifecycleScope.launch { airports = ready.await(); location?.let { useLocation(it) } }
    }
    private fun locate() {
        if (!hasPermission()) return
        status?.text = "正在读取位置…"
        try {
            val fine = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val providers = (if (fine) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                else listOf(LocationManager.NETWORK_PROVIDER)).filter { manager.isProviderEnabled(it) }
            val cached = providers.mapNotNull { manager.getLastKnownLocation(it) }.filter { System.currentTimeMillis() - it.time < 300000 && it.accuracy <= 5000 }
                .maxByOrNull { it.time }
            if (cached != null) { useLocation(cached); return }
            if (providers.isEmpty()) { status?.text = "系统定位未开启；可手动选择地区。"; return }
            for (provider in providers) manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
            timeout?.cancel()
            timeout = activity.lifecycleScope.launch { delay(25000); manager.removeUpdates(listener); status?.text = "暂未取得位置；可手动选择地区后继续。" }
        } catch (_: SecurityException) { status?.text = "定位权限不可用，可手动选择地区。" }
    }
    private fun useLocation(value: Location) {
        location = value
        activity.lifecycleScope.launch {
            airports = ready.await()
            nearby = withContext(Dispatchers.Default) { Plans.nearby(airports, value.latitude, value.longitude) }
            nearby.firstOrNull()?.first?.country?.let { country ->
                val inferred = Plans.region(country)
                if (inferred != Plans.Region.OTHER) {
                    region = inferred
                    prefs.edit().putString("region", region.name).apply()
                    root?.let { container -> (container.getChildAt(2) as? Spinner)?.setSelection(region.ordinal) }
                }
            }
            status?.text = "${region.label} · 附近 ${nearby.size} 个有频率数据的机场（200 km 内）"
            render()
            val pending = pendingSelection
            pendingSelection = null
            if (pending != null && currentSelection() == pending) applyModeRecommendation(pending.first, pending.second)
        }
    }
    private fun supported(mode: DemodMode, hz: Long) = capabilities().supportsFrequency(hz) && capabilities().supportsMode(mode)
    private fun render() {
        val container = list ?: return
        container.removeAllViews()
        fun add(label: String, mode: DemodMode, hz: Long) {
            container.addView(Button(activity).apply {
                val allowed = supported(mode, hz)
                text = "$label\n${"%.3f".format(hz / 1e6)} MHz · ${mode.displayName}" + if (allowed) "" else " · 当前接收源不支持"
                isAllCaps = false
                isEnabled = allowed
                setOnClickListener { tune(mode, hz); dialog?.dismiss() }
            })
        }
        for ((airport, distance) in nearby) {
            container.addView(TextView(activity).apply { text = "${airport.ident} · ${airport.name} · ${distance.toInt()} km" })
            airport.channels.sortedBy { when (it.type) { "TWR" -> 0; "APP" -> 1; "ATIS" -> 2; else -> 3 } }.take(4)
                .forEach { add("${it.type} · ${it.description}", DemodMode.AM, it.hz) }
        }
        Plans.common(region).forEach { add(it.label, it.mode, it.hz) }
    }
    /** Called only for an explicit mode selection, never on a passive GPS update. */
    fun cancelPendingAutoTune() { pendingSelection=null }

    fun onModeSelected(mode: DemodMode, frequency: Long) {
        if (!prefs.getBoolean("auto_apply", false)) return
        if (mode == DemodMode.APRS || mode == DemodMode.AM && frequency in 118_000_000..136_975_000) {
            if (hasPermission() && (location == null || System.currentTimeMillis() - location!!.time > 300000)) {
                pendingSelection = mode to frequency
                locate()
                return
            }
        }
        applyModeRecommendation(mode, frequency)
    }
    private fun applyModeRecommendation(mode: DemodMode, frequency: Long) {
        val next = when (mode) {
            DemodMode.APRS -> region.aprsHz
            DemodMode.ADSB -> 1_090_000_000L
            DemodMode.AM -> if (frequency in 118_000_000..136_975_000) nearby.firstOrNull()?.first?.channels
                ?.sortedBy { if (it.type == "TWR") 0 else 1 }?.firstOrNull()?.hz else null
            else -> null
        } ?: return
        if (supported(mode, next)) tune(mode, next)
    }
    private fun stopLocation() { timeout?.cancel(); try { manager.removeUpdates(listener) } catch (_: SecurityException) {} }
    override fun onStop(owner: LifecycleOwner) { pendingSelection = null; stopLocation() }
    override fun onDestroy(owner: LifecycleOwner) { stopLocation() }
}
