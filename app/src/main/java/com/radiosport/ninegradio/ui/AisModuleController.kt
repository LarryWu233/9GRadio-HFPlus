package com.radiosport.ninegradio.ui

import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.radiosport.ninegradio.R
import com.radiosport.ninegradio.data.aisTarget
import com.radiosport.ninegradio.dsp.*
import com.radiosport.ninegradio.usb.RtlSdrService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Existing Views/map/table adapters; only the service owns IQ decoding and transport. */
@OptIn(FlowPreview::class)
class AisModuleController(private val activity:MainActivity,private val pager:ControlsPagerAdapter,
    private val vm:MainViewModel,private val service:()->RtlSdrService?,private val location:ReceiverLocationController,
    private val show:(Int)->Unit,private val visible:(Boolean)->Unit,private val resize:()->Unit,private val expand:()->Unit) {
    private var panel:LinearLayout?=null
    private var map:RadioMapPanel?=null
    private var mapHost:FrameLayout?=null
    private var table:RadioDataTable?=null
    private var status:TextView?=null
    private var ranges:TextView?=null
    private var rows=emptyList<AisVessel>()
    private var collectJob:Job?=null
    private var observed:RtlSdrService?=null
    private var active=false
    private var previousMode:DemodMode?=null
    private val distances=AdsbDistanceTracker(activity,"ais_record_")
    private fun dp(n:Int)=(n*activity.resources.displayMetrics.density).toInt()
    private fun label(value:String)=TextView(activity).apply {
        text=value;textSize=12f;setRadioTextColor(R.attr.radioSecondary);setPadding(dp(4),dp(4),dp(4),dp(4))
    }
    fun setup() {
        if(activity.isFinishing || activity.isDestroyed || panel!=null) return
        val host=pager.getViewAt(ControlsPagerAdapter.TAB_AIS) as? FrameLayout
        if(host==null) { activity.window.decorView.postDelayed({ setup() },60);return }
        val root=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(8),dp(10),dp(10)) }
        panel=root;host.addView(root)
        val actions=LinearLayout(activity)
        fun button(text:String,click:()->Unit) {
            val button=activity.layoutInflater.inflate(R.layout.radio_compact_button,actions,false) as Button
            button.text=text;button.setOnClickListener { click() }
            actions.addView(button,LinearLayout.LayoutParams(0,dp(48),1f).apply { marginStart=dp(4);marginEnd=dp(4) })
        }
        button("162.000 MHz") { vm.setFrequency(162000000);vm.finishTuning();recommendedRate() }
        button("地图设置") { map?.showOptions() }
        button("展开 / 还原") { expand() }
        root.addView(actions)
        root.addView(label("AIS A 161.975 · B 162.025 MHz"))
        status=label("等待接收").also { root.addView(it) }
        mapHost=FrameLayout(activity).apply { id=R.id.aisMapHost }.also { box ->
            map=RadioMapPanel(activity,"AIS",true,location).also { box.addView(it,FrameLayout.LayoutParams(-1,-1)) }
            root.addView(box,LinearLayout.LayoutParams(-1,dp(260)))
        }
        ranges=label("最大距离 — · 距离记录 —").also { root.addView(it) }
        table=RadioDataTable(activity).apply {
            setColumns(listOf(RadioDataTable.Column("MMSI",100),RadioDataTable.Column("名称",155),
                RadioDataTable.Column("距离",85),RadioDataTable.Column("航速 kt",78),RadioDataTable.Column("航向",70),RadioDataTable.Column("坐标时效",92)))
            onRowClick={ index -> rows.getOrNull(index)?.let { vessel ->
                val target=aisTarget(vessel)
                val dialog=AlertDialog.Builder(activity).setTitle(vessel.name ?: vessel.mmsi.toString().padStart(9,'0'))
                    .setMessage("MMSI：${vessel.mmsi.toString().padStart(9,'0')}\n呼号：${vessel.callsign ?: "—"}\n航速：${vessel.speedKnots?.let { "%.1f kt".format(it) } ?: "—"}\n船艏向：${vessel.headingDegrees?.let { "%.0f°".format(it) } ?: "—"}\n航迹向：${vessel.courseDegrees?.let { "%.1f°".format(it) } ?: "—"}\n目的地：${vessel.destination ?: "—"}\n位置：${vessel.latitude ?: "—"}, ${vessel.longitude ?: "—"}")
                    .setPositiveButton("关闭",null)
                if(target!=null) dialog.setNeutralButton("定位") { _,_ ->
                    map?.nativeMap?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                        org.maplibre.android.geometry.LatLng(target.latitude,target.longitude),11.0))
                }
                dialog.show()
            } }
        }.also { root.addView(it,LinearLayout.LayoutParams(-1,dp(220))) }
        ControlColors.apply(root,ControlColors.Group.RADIO)
        bindService();modeChanged(vm.demodMode.value)
        status?.addOnLayoutChangeListener { _,_,top,_,bottom,_,oldTop,_,oldBottom -> if(bottom-top!=oldBottom-oldTop) activity.window.decorView.post { resize() } }
        resize()
    }
    fun bindService() {
        val next=service() ?: return
        if(observed===next) return
        observed=next;collectJob?.cancel()
        collectJob=activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val tick=flow { emit(Unit);while(currentCoroutineContext().isActive) { delay(1000);emit(Unit) } }
                combine(next.aisReceiver.vessels,next.aisReceiver.diagnostics,next.moduleAvailability,tick,location.position) { _,_,_,_,_ -> Unit }
                    .sample(500).collect { if(active) render() }
            }
        }
    }
    private fun recommendedRate() {
        val caps=vm.capabilities.value
        if(caps.id=="file") return
        val wanted=if(caps.floatIq) 768000 else 288000
        val rate=caps.sampleRates.firstOrNull { it==wanted } ?: caps.sampleRates.filter { it in 96000..768000 }.maxOrNull() ?: return
        vm.setSampleRate(rate)
    }
    fun modeChanged(mode:DemodMode) {
        val enabled=mode==DemodMode.AIS && vm.capabilities.value.supportsMode(mode) && ModulePreferences(activity).visible(ControlsPagerAdapter.TAB_AIS)
        visible(enabled)
        if(enabled && previousMode!=mode) {
            val prefs=activity.getSharedPreferences("rtlsdr_prefs",0);val key="ais_rate_initialized_${vm.capabilities.value.id}"
            if(!prefs.getBoolean(key,false) && vm.capabilities.value.id!="file") { recommendedRate();prefs.edit().putBoolean(key,true).apply() }
            show(ControlsPagerAdapter.TAB_AIS)
        }
        if(!enabled && active) show(ControlsPagerAdapter.TAB_MODE)
        previousMode=mode
    }
    fun setActive(value:Boolean) { active=value;map?.setActive(value);if(value) { bindService();render() } }
    fun resizeTable(available:Int,measuredPage:Int,expanded:Boolean):Boolean {
        val list=table ?: return false
        val wanted=if(expanded && available>0) (list.layoutParams.height+available-measuredPage).coerceAtLeast(dp(120)) else dp(220)
        if(wanted==list.layoutParams.height) return false
        list.layoutParams=list.layoutParams.apply { height=wanted };return true
    }
    private fun render() {
        val receiver=observed?.aisReceiver ?: return
        rows=receiver.vessels.value.sortedBy { it.mmsi }
        val range=distances.updateTargets(rows.mapNotNull { aisTarget(it) })
        ranges?.text="最大距离 ${AdsbDistanceTracker.format(range.currentMaxKm)} · 距离记录 ${AdsbDistanceTracker.format(range.recordKm)}"
        val state=receiver.diagnostics.value
        status?.text=observed?.moduleAvailability?.value?.reason ?: state.error ?: when {
            state.running -> "接收中 · ${state.messages} 条消息 · ${rows.size} 个目标"
            state.samples>0 -> "回放结束 · ${state.messages} 条消息 · ${rows.size} 个目标"
            else -> "等待接收"
        }
        val now=System.currentTimeMillis()
        table?.submitRows(rows.map { vessel -> RadioDataTable.Row(listOf(vessel.mmsi.toString().padStart(9,'0'),vessel.name ?: "—",
            AdsbDistanceTracker.format(range.distances["ais:${vessel.mmsi}"]),vessel.speedKnots?.let { "%.1f".format(it) } ?: "—",
            (vessel.headingDegrees ?: vessel.courseDegrees)?.let { "%.0f°".format(it) } ?: "—",
            vessel.positionTimeMs?.let { "${((now-it)/1000).coerceAtLeast(0)} s" } ?: "—")) })
    }
}
