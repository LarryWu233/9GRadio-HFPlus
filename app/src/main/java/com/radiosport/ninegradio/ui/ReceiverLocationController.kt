package com.radiosport.ninegradio.ui

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Foreground UI adapter only. No background location service or Play-services dependency. */
class ReceiverLocationController(private val activity:AppCompatActivity):DefaultLifecycleObserver {
    data class Position(val latitude:Double,val longitude:Double,val source:String,val accuracyMeters:Float?)
    private val prefs=activity.getSharedPreferences("radio_map",Context.MODE_PRIVATE)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val _position=MutableStateFlow(readPosition())
    val position=_position.asStateFlow()
    private val _status=MutableStateFlow(positionLabel())
    val status=_status.asStateFlow()
    private var active=false
    private var destroyed=false
    private var refreshJob:Job?=null
    private var timeoutJob:Job?=null
    private var requestGeneration=0
    private var permissionPending=false
    private val signals=ArrayList<CancellationSignal>()
    private val permission=activity.activityResultRegistry.register("receiver-location",ActivityResultContracts.RequestMultiplePermissions()) {
        permissionPending=false
        if(hasPermission()) { readCurrent(false);reconcile() } else _status.value="定位未授权"
    }
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
        if(key in setOf("receiverLat","receiverLon","receiverPositionSource","receiverLocationAccuracy","receiverLocationMode")) {
            _position.value=readPosition()
            if(signals.isEmpty()) _status.value=positionLabel()
            reconcile()
        }
    }
    init { prefs.registerOnSharedPreferenceChangeListener(listener);activity.lifecycle.addObserver(this) }
    private fun readPosition():Position? {
        if(!prefs.contains("receiverLat") || !prefs.contains("receiverLon")) return null
        val lat=prefs.getFloat("receiverLat",Float.NaN).toDouble()
        val lon=prefs.getFloat("receiverLon",Float.NaN).toDouble()
        if(lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return Position(lat,lon,prefs.getString("receiverPositionSource","manual") ?: "manual",
            prefs.getFloat("receiverLocationAccuracy",Float.NaN).takeIf { it.isFinite() && it>=0 })
    }
    private fun wantsPhone()=prefs.getString("receiverLocationMode",if(readPosition()==null) "phone" else "manual")!="manual"
    private fun foreground()=!destroyed && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    private fun hasPermission()=ContextCompat.checkSelfPermission(activity,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
    private fun positionLabel():String = readPosition()?.let {
        if(it.source=="phone") "手机定位"+(it.accuracyMeters?.let { accuracy -> " · 精度约 ${accuracy.toInt()} m" } ?: "")
        else "手动接收位置"
    } ?: "未设置接收位置"

    fun setActive(value:Boolean) { active=value;reconcile() }
    private fun reconcile() {
        if(!active || !foreground() || !wantsPhone()) { refreshJob?.cancel();refreshJob=null;cancelReading();return }
        if(refreshJob?.isActive==true) return
        refreshJob=scope.launch {
            while(isActive) {
                readCurrent(false)
                delay(120_000) // Receiver position, not navigation: avoid continuous GPS use.
            }
        }
    }
    fun requestCurrentLocation() {
        prefs.edit().putString("receiverLocationMode","phone").apply()
        readCurrent(true)
    }
    fun saveManual(latitude:Double,longitude:Double) {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
        cancelReading()
        prefs.edit().putString("receiverLocationMode","manual").putString("receiverPositionSource","manual")
            .putFloat("receiverLat",latitude.toFloat()).putFloat("receiverLon",longitude.toFloat())
            .remove("receiverLocationAccuracy").apply()
        _status.value=positionLabel()
    }
    private fun cancelReading() {
        requestGeneration++
        signals.forEach { it.cancel() };signals.clear()
        timeoutJob?.cancel();timeoutJob=null
    }
    private fun readCurrent(explicit:Boolean) {
        if(!foreground() || !active || signals.isNotEmpty() || permissionPending) return
        if(!hasPermission()) {
            _status.value="定位未授权"
            if(explicit || !prefs.getBoolean("receiverLocationAsked",false)) {
                prefs.edit().putBoolean("receiverLocationAsked",true).apply()
                permissionPending=true
                permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION))
            }
            return
        }
        val manager=activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fine=ContextCompat.checkSelfPermission(activity,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
        val providers=listOf("fused",LocationManager.NETWORK_PROVIDER,LocationManager.GPS_PROVIDER).filter {
            (fine || it!=LocationManager.GPS_PROVIDER) && runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }
        if(providers.isEmpty()) { _status.value="系统定位未开启";return }
        cancelReading()
        val generation=requestGeneration
        _status.value="正在读取接收位置…"
        var best:Location?=null
        var pending=providers.size
        fun finish() {
            cancelReading()
            _status.value=if(best!=null) positionLabel() else if(readPosition()!=null) "定位暂不可用 · 使用已存位置" else "暂未取得接收位置"
        }
        for(provider in providers) {
            val signal=CancellationSignal();signals+=signal
            try {
                LocationManagerCompat.getCurrentLocation(manager,provider,signal,ContextCompat.getMainExecutor(activity)) { fix ->
                    if(generation!=requestGeneration || !foreground() || !wantsPhone()) return@getCurrentLocation
                    pending--
                    val age=fix?.let { SystemClock.elapsedRealtimeNanos()-it.elapsedRealtimeNanos } ?: Long.MAX_VALUE
                    if(fix!=null && age in 0..120_000_000_000L && fix.latitude in -90.0..90.0 && fix.longitude in -180.0..180.0 &&
                        (best==null || (fix.hasAccuracy() && (!best!!.hasAccuracy() || fix.accuracy<best!!.accuracy)))) {
                        best=fix
                        prefs.edit().putString("receiverLocationMode","phone").putString("receiverPositionSource","phone")
                            .putFloat("receiverLat",fix.latitude.toFloat()).putFloat("receiverLon",fix.longitude.toFloat())
                            .putFloat("receiverLocationAccuracy",if(fix.hasAccuracy()) fix.accuracy else Float.NaN).apply()
                    }
                    if(pending==0 || (best?.hasAccuracy()==true && best!!.accuracy<=100f)) finish()
                }
            } catch(_:SecurityException) { pending-- }
              catch(_:IllegalArgumentException) { pending-- }
        }
        if(generation==requestGeneration) timeoutJob=scope.launch { delay(12_000);if(generation==requestGeneration) finish() }
    }
    fun showDialog() {
        val input=EditText(activity).apply {
            hint="纬度，经度（WGS84）"
            readPosition()?.let { setText(String.format(java.util.Locale.ROOT,"%.6f, %.6f",it.latitude,it.longitude)) }
        }
        val dialog=com.google.android.material.dialog.MaterialAlertDialogBuilder(activity).setTitle("接收位置").setView(input)
            .setPositiveButton("保存",null).setNeutralButton("手机定位") { _,_ -> requestCurrentLocation() }
            .setNegativeButton("取消",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pair=input.text.toString().replace('，',',').split(',').map { it.trim().toDoubleOrNull() }
                if(pair.size==2 && pair[0]!=null && pair[1]!=null && pair[0]!! in -90.0..90.0 && pair[1]!! in -180.0..180.0) {
                    saveManual(pair[0]!!,pair[1]!!);dialog.dismiss()
                } else input.error="请输入有效的纬度、经度"
            }
        }
        dialog.show()
    }
    override fun onResume(owner:LifecycleOwner) { reconcile() }
    override fun onPause(owner:LifecycleOwner) { refreshJob?.cancel();refreshJob=null;cancelReading() }
    override fun onDestroy(owner:LifecycleOwner) {
        destroyed=true;refreshJob?.cancel();cancelReading();scope.cancel()
        permission.unregister();prefs.unregisterOnSharedPreferenceChangeListener(listener);activity.lifecycle.removeObserver(this)
    }
}
