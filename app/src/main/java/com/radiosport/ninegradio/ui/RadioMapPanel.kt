package com.radiosport.ninegradio.ui

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.*
import org.maplibre.android.offline.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.geojson.*
import com.radiosport.ninegradio.data.RadioTarget
import com.radiosport.ninegradio.data.RadioTargets
import java.io.File
import java.net.URI

/** Shared, account-free map surface. Coordinates entering this component are WGS84. */
@OptIn(FlowPreview::class)
class RadioMapPanel(private val activity: AppCompatActivity, private val protocol: String = "ALL", private val compact: Boolean = false,
                    sharedLocation:ReceiverLocationController?=null) : FrameLayout(activity), androidx.lifecycle.DefaultLifecycleObserver {
    val receiverLocation=sharedLocation ?: ReceiverLocationController(activity)
    private val ownsLocation=sharedLocation==null
    private val lifecycleScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var active=false
    private var started=false
    private var destroyed=false
    val nativeMap get()=map
    private lateinit var view: MapView
    private lateinit var gestures: MapGestureFrame
    private lateinit var status: TextView
    private var map: MapLibreMap? = null
    private var centered = false
    private var targets = emptyList<RadioTarget>()
    private val trails = mutableMapOf<String, ArrayDeque<Point>>()
    private val preferences by lazy { activity.getSharedPreferences("radio_map", android.content.Context.MODE_PRIVATE) }
    private val offline by lazy { OfflineManager.getInstance(activity) }
    private val defaultStyle = "asset://maps/original-osm.json"
    private var fallback = false
    private var mapReady=false
    private val emptyStyle = """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#0d1722"}}]}"""

    private val importer = activity.activityResultRegistry.register("map-import-$protocol", ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            val file = File(activity.cacheDir, "import-map.db")
            try {
                withContext(Dispatchers.IO) {
                    activity.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "无法读取地图包" }
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                offline.mergeOfflineRegions(file.absolutePath, object : OfflineManager.MergeOfflineRegionsCallback {
                    override fun onMerge(regions: Array<OfflineRegion>?) {
                        file.delete()
                        status.text = "已导入 ${regions?.size ?: 0} 个离线区域"
                        showOfflineRegions()
                    }
                    override fun onError(error: String) { file.delete(); status.text = "导入失败：$error" }
                })
            } catch (error: Exception) { status.text = "导入失败：${error.message}"; file.delete() }
        }
    }

    init {
        MapLibre.getInstance(activity)
        MapNetwork.install()
        if(!preferences.getBoolean("original_osm_v1",false)) preferences.edit()
            .putString("style",defaultStyle).putBoolean("original_osm_v1",true).apply()
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val controls = LinearLayout(activity)
        fun button(text: String, action: () -> Unit) = Button(activity).apply {
            this.text = text; textSize = 12f
            setOnClickListener { action() }
            controls.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        button("地图源") { sourceDialog() }
        button("离线地图") { offlineDialog() }
        button("接收位置") { receiverDialog() }
        root.addView(controls)
        status = TextView(activity).apply { text = "正在加载底图…"; setPadding(12, 8, 12, 8) }
        root.addView(status)
        // Pager pages are constructed before they are selected. Creating a TextureView
        // here starts its renderer even though this panel has never been resumed.
        gestures=MapGestureFrame(activity)
        root.addView(gestures, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(activity).apply {
            text = "© OpenStreetMap contributors"
            textSize = 10f; setPadding(12, 8, 12, 8)
        })
        addView(root,FrameLayout.LayoutParams(-1,-1))
        activity.lifecycle.addObserver(this)
        if(compact) {
            controls.visibility=android.view.View.GONE
            status.textSize=10f
            val options=TextView(activity).apply {
                text="⋮"; textSize=26f; gravity=android.view.Gravity.CENTER
                setTextColor(0xffdbe5eb.toInt()); contentDescription="地图设置"
                setBackgroundColor(0xcc0d1822.toInt())
                setOnClickListener { showOptions() }
            }
            val size=(40*resources.displayMetrics.density).toInt()
            addView(options,FrameLayout.LayoutParams(size,size,android.view.Gravity.TOP or android.view.Gravity.END))
        }
        lifecycleScope.launch {
            combine(RadioTargets.aircraft, RadioTargets.stations) { aircraft, stations ->
                when(protocol) { "ADSB" -> aircraft; "APRS" -> stations; else -> aircraft+stations }
            }.sample(250).collect { updateTargets(it) }
        }
        lifecycleScope.launch {
            MapNetwork.status.collect { updateMapStatus() }
        }
        lifecycleScope.launch { while(isActive) { delay(1000); updateTargets(targets) } }
        lifecycleScope.launch { receiverLocation.position.collect { centerInitially();render() } }

    }
    private fun ensureMapCreated() {
        if(::view.isInitialized) return
        view=MapView(activity,MapLibreMapOptions.createFromAttributes(activity).textureMode(true))
        view.onCreate(null)
        view.addOnDidFinishLoadingMapListener { mapReady=true; updateMapStatus() }
        view.addOnDidFailLoadingMapListener {
            status.text = "底图加载失败，可选择已导入的离线地图；本地接收继续运行。"
            if (!fallback) { fallback = true; map?.setStyle(Style.Builder().fromJson(emptyStyle)) { installLayers(it) } }
        }
        view.getMapAsync { ready ->
            map = ready
            ready.uiSettings.apply {
                isScrollGesturesEnabled=true; isZoomGesturesEnabled=true
                isRotateGesturesEnabled=false; isTiltGesturesEnabled=false
            }
            ready.cameraPosition = CameraPosition.Builder().target(LatLng(35.0, 105.0)).zoom(3.0).build()
            loadStyle(preferences.getString("style", defaultStyle) ?: defaultStyle)
            ready.addOnMapClickListener { point ->
                val hits = ready.queryRenderedFeatures(ready.projection.toScreenLocation(point), "radio-points","radio-aircraft")
                val target = hits.asSequence().mapNotNull { feature ->
                    val id=feature.takeIf { it.hasProperty("id") }?.getStringProperty("id")
                    targets.firstOrNull { it.id==id }
                }.firstOrNull()
                if (target != null) AlertDialog.Builder(activity).setTitle(target.label).setMessage(target.detail)
                    .setPositiveButton("关闭", null).show()
                target != null
            }
        }
        gestures.addView(view,FrameLayout.LayoutParams(-1,-1))
    }
    private fun loadStyle(url: String) {
        fallback = false
        mapReady=false
        MapNetwork.resetStatus()
        status.text = "正在加载底图…"
        map?.setStyle(Style.Builder().fromUri(url)) {
            installLayers(it)
            updateMapStatus()
        }
    }
    private fun installLayers(style: Style) {
        style.addSource(GeoJsonSource("radio-data", FeatureCollection.fromFeatures(emptyArray<Feature>())))
        style.addSource(GeoJsonSource("radio-trails", FeatureCollection.fromFeatures(emptyArray<Feature>())))
        style.addLayer(LineLayer("radio-tracks", "radio-trails").withProperties(lineColor("#00b7d8"), lineWidth(2f)))
        style.addLayer(CircleLayer("radio-points", "radio-data").withProperties(
            circleRadius(7f), circleColor("#ffb347"), circleStrokeColor("#17212b"), circleStrokeWidth(2f)).withFilter(neq(get("kind"),literal("aircraft"))))
        val size=(28*resources.displayMetrics.density).toInt()
        fun icon(color:Int):android.graphics.Bitmap {
            val drawable=androidx.appcompat.content.res.AppCompatResources.getDrawable(activity,com.radiosport.ninegradio.R.drawable.ic_aircraft)!!.mutate()
            androidx.core.graphics.drawable.DrawableCompat.setTint(drawable,color)
            return drawable.toBitmap(size,size)
        }
        style.addImage("radio-aircraft-known",icon(UiThemes.color(activity,com.radiosport.ninegradio.R.attr.radioAccent)))
        style.addImage("radio-aircraft-unknown",icon(UiThemes.color(activity,com.radiosport.ninegradio.R.attr.radioSecondary)))
        style.addLayer(SymbolLayer("radio-aircraft","radio-data").withFilter(eq(get("kind"),literal("aircraft"))).withProperties(
            iconImage(get("icon")),iconRotate(get("heading")),iconAllowOverlap(true),iconIgnorePlacement(true),
            iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP)))
        centerInitially()
        render()
    }
    private fun updateTargets(values: List<RadioTarget>) {
        val now = System.currentTimeMillis()
        val stations=RadioTargets.stations.value.map { it.id }.toSet()
        targets = values.filter { now - it.timeMs < (if(protocolIsAprs() || it.id in stations) 600_000 else 120_000) &&
            it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
        updateMapStatus()
        trails.keys.retainAll(targets.map { it.id }.toSet())
        for (target in targets) {
            val point = Point.fromLngLat(target.longitude, target.latitude)
            val trail = trails.getOrPut(target.id) { ArrayDeque() }
            if (trail.lastOrNull() != point) trail.addLast(point)
            while (trail.size > 120) trail.removeFirst()
        }
        centerInitially()
        render()
    }
    private fun protocolIsAprs()=protocol=="APRS"
    private fun centerInitially() {
        val ready=map ?: return
        if(centered || !started) return
        val receiver=receiverLocation.position.value
        val target=targets.firstOrNull()
        val position=receiver?.let { LatLng(it.latitude,it.longitude) } ?: target?.let { LatLng(it.latitude,it.longitude) } ?: return
        ready.cameraPosition=CameraPosition.Builder().target(position).zoom(if(receiver!=null) 8.0 else 7.0).build()
        centered=true
    }
    private fun updateMapStatus() {
        val tile=MapNetwork.status.value
        status.text=if(tile.available==false) "地图暂不可用 · ${targets.size} 个已定位目标"
            else if(fallback) "底图不可用 · ${targets.size} 个已定位目标"
            else if(mapReady || tile.available==true) "地图已就绪 · ${targets.size} 个已定位目标"
            else "底图加载中 · ${targets.size} 个已定位目标"
    }
    private fun render() {
        if(!started) return
        val features=targets.map { it.mapFeature() }.toMutableList()
        receiverLocation.position.value?.let { receiver ->
            features+=Feature.fromGeometry(Point.fromLngLat(receiver.longitude,receiver.latitude)).apply { addStringProperty("kind","receiver") }
        }
        map?.style?.getSourceAs<GeoJsonSource>("radio-data")?.setGeoJson(FeatureCollection.fromFeatures(features))
        val lines = trails.values.filter { it.size > 1 }.map { Feature.fromGeometry(LineString.fromLngLats(it.toList())) }
        map?.style?.getSourceAs<GeoJsonSource>("radio-trails")?.setGeoJson(FeatureCollection.fromFeatures(lines))
    }
    private fun sourceDialog() {
        AlertDialog.Builder(activity).setTitle("地图源").setItems(arrayOf("OpenStreetMap", "OpenFreeMap 浅色", "OpenFreeMap 深色", "自定义样式地址")) { _, choice ->
            if (choice < 3) {
                val url = if(choice==0) defaultStyle else "https://tiles.openfreemap.org/styles/" + if (choice == 1) "liberty" else "dark"
                preferences.edit().putString("style", url).apply(); loadStyle(url)
            } else {
                val input = EditText(activity).apply { hint = "https://…/style.json"; setText(preferences.getString("style", defaultStyle)?.takeIf { it.startsWith("https://") } ?: "") }
                AlertDialog.Builder(activity).setTitle("自定义 HTTPS 样式地址").setView(input).setPositiveButton("加载") { _, _ ->
                    val url = input.text.toString().trim()
                    val valid = runCatching { URI(url).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)
                    if (valid) { preferences.edit().putString("style", url).apply(); loadStyle(url) }
                    else status.text = "请输入有效的 HTTPS 样式地址"
                }.setNegativeButton("取消", null).show()
            }
        }.show()
    }
    private fun offlineDialog() {
        AlertDialog.Builder(activity).setTitle("离线地图").setMessage("导入包含样式、字体和瓦片的 MapLibre 离线数据库（.db）。当前不支持直接导入 MBTiles。")
            .setPositiveButton("导入 .db") { _, _ -> importer.launch(arrayOf("*/*")) }
            .setNeutralButton("选择已导入区域") { _, _ -> showOfflineRegions() }
            .setNegativeButton("关闭", null).show()
    }
    private fun showOfflineRegions() {
        offline.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(regions: Array<OfflineRegion>?) {
                val entries = regions ?: emptyArray()
                if (entries.isEmpty()) { status.text = "尚未导入离线区域"; return }
                AlertDialog.Builder(activity).setTitle("离线区域").setItems(entries.map { "区域 ${it.id}" }.toTypedArray()) { _, index ->
                    val definition = entries[index].definition as? OfflineTilePyramidRegionDefinition
                    if (definition != null) {
                        val url = definition.styleURL
                        val bounds = definition.bounds
                        if (url == null || bounds == null) { status.text = "离线区域缺少样式或边界信息"; return@setItems }
                        loadStyle(url)
                        map?.cameraPosition = CameraPosition.Builder().target(bounds.center).zoom(definition.minZoom).build()
                        centered = true
                    }
                }.show()
            }
            override fun onError(error: String) { status.text = error }
        })
    }
    fun receiverDialog() {
        receiverLocation.showDialog()
    }
    fun showOptions() {
        AlertDialog.Builder(activity).setTitle("地图设置").setItems(arrayOf("接收位置","地图源","离线地图")) { _,which ->
            when(which) { 0 -> receiverDialog(); 1 -> sourceDialog(); 2 -> offlineDialog() }
        }.show()
    }
    fun setActive(value: Boolean) { active=value;if(ownsLocation) receiverLocation.setActive(value);updateLifecycle() }
    private fun updateLifecycle() {
        val wanted=active && isAttachedToWindow && activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && !destroyed
        if(wanted && !started) { ensureMapCreated(); view.onStart(); view.onResume(); started=true; render() }
        else if(!wanted && started) { view.onPause(); view.onStop(); started=false }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateLifecycle() }
    override fun onDetachedFromWindow() { if(started) { view.onPause(); view.onStop(); started=false }; super.onDetachedFromWindow() }
    override fun onResume(owner: androidx.lifecycle.LifecycleOwner) { updateLifecycle() }
    override fun onPause(owner: androidx.lifecycle.LifecycleOwner) { if(started) { view.onPause(); view.onStop(); started=false } }
    override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
        destroyed=true; setActive(false); lifecycleScope.cancel()
        importer.unregister()
        if(::view.isInitialized) view.onDestroy()
        activity.lifecycle.removeObserver(this)
    }
}
