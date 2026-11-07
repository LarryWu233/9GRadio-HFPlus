package com.radiosport.ninegradio

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.radiosport.ninegradio.data.RadioTarget
import com.radiosport.ninegradio.data.RadioTargets
import com.radiosport.ninegradio.ui.RadioMapPanel
import com.radiosport.ninegradio.ui.ControlsPagerAdapter
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.ui.MapNetwork
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import com.radiosport.ninegradio.ui.MainActivity
import com.radiosport.ninegradio.ui.MainViewModel
import androidx.lifecycle.ViewModelProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Explicit sample targets in an isolated process; never used by live reception. */
@RunWith(AndroidJUnit4::class)
class MapSceneTest {
    @Test fun protocolFilteredSamplePositionsRenderAndProbeOriginalTile() {
        val inst=InstrumentationRegistry.getInstrumentation(); val context=inst.targetContext
        ReceiverTestPreferences(context).use {
        ActivityScenario.launch(MainActivity::class.java).use { main ->
        main.onActivity { ViewModelProvider(it)[MainViewModel::class.java].setReceiverEnabled(false) }
        Thread.sleep(2700) // MIUI explicit-launch helper finishes before map navigation.
        val oldAircraft=RadioTargets.aircraft.value; val oldStations=RadioTargets.stations.value
        val now=System.currentTimeMillis()
        RadioTargets.aircraft.value=listOf(RadioTarget("SAMPLE-AIRCRAFT","[样本] 飞机",35.0,105.0,"位置图层测试",now))
        RadioTargets.stations.value=listOf(RadioTarget("SAMPLE-APRS","[样本] 电台",35.1,105.1,"五分钟前的 APRS 数据",now-300000))
        val results=ArrayList<Map<String,Any?>>()
        try {
            for((protocol,id,lat) in listOf(Triple("ADSB","SAMPLE-AIRCRAFT",35.0),Triple("APRS","SAMPLE-APRS",35.1))) {
                main.onActivity { activity ->
                    ViewModelProvider(activity)[MainViewModel::class.java].setDemodMode(if(protocol=="ADSB") DemodMode.ADSB else DemodMode.APRS)
                    val adapter=MainActivity::class.java.getDeclaredField("pagerAdapter").apply { isAccessible=true }.get(activity) as ControlsPagerAdapter
                    activity.findViewById<com.google.android.material.tabs.TabLayout>(R.id.controlTabLayout)
                        .getTabAt(adapter.positionOf(if(protocol=="ADSB") ControlsPagerAdapter.TAB_ADSB else ControlsPagerAdapter.TAB_APRS))!!.select()
                    activity.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(if(protocol=="ADSB") R.id.btnAdsbPanelMap else R.id.btnAprsMap).isChecked=true
                }
                Thread.sleep(600)
                RadioTargets.aircraft.value=listOf(RadioTarget("SAMPLE-AIRCRAFT","[样本] 飞机",35.0,105.0,"位置图层测试",now))
                RadioTargets.stations.value=listOf(RadioTarget("SAMPLE-APRS","[样本] 电台",35.1,105.1,"五分钟前的 APRS 数据",now-300000))
                    val deadline=SystemClock.elapsedRealtime()+18000
                    var found=false; var details=""
                    while(!found && SystemClock.elapsedRealtime()<deadline) {
                        main.onActivity { activity ->
                            val host=activity.findViewById<android.widget.FrameLayout>(if(protocol=="ADSB") R.id.adsbMapHost else R.id.aprsMapHost)
                            val map=(host.getChildAt(0) as RadioMapPanel).nativeMap
                            details="map=${map!=null}; style=${map?.style?.isFullyLoaded}; network=${MapNetwork.status.value}"
                            if(map?.style?.isFullyLoaded==true) {
                                val point=map.projection.toScreenLocation(LatLng(lat,if(protocol=="ADSB") 105.0 else 105.1))
                                val features=map.queryRenderedFeatures(point,"radio-points")
                                found=features.any { it.hasProperty("id") && it.getStringProperty("id")==id }
                                assertTrue(features.none { it.hasProperty("id") && it.getStringProperty("id")!=id })
                            }
                        }
                        Thread.sleep(100)
                    }
                    results+=mapOf("protocol" to protocol,"rendered" to found,"scene" to details)
                    File(context.cacheDir,"alpha14-map-scene.json").writeText(Gson().toJson(results))
                    inst.uiAutomation.takeScreenshot()?.let { image ->
                        File(context.cacheDir,"alpha14-map-sample-$protocol.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
                    }
                    assertTrue("Sample position not rendered: $protocol; $details",found)
            }
            val probe=HashMap<String,Any?>()
            val connection=URL("https://tile.openstreetmap.org/3/6/3.png").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout=8000; connection.readTimeout=8000
                connection.setRequestProperty("User-Agent","9GRadioHFPlus/${BuildConfig.VERSION_NAME}")
                probe["httpCode"]=connection.responseCode
                if(connection.responseCode==200) connection.inputStream.use { input ->
                    val magic=ByteArray(8); probe["pngHeader"]=input.read(magic)==8 && magic[0]==0x89.toByte() && magic[1]==0x50.toByte()
                }
            } catch(error: Exception) { probe["error"]=error.javaClass.simpleName }
            finally { connection.disconnect() }
            File(context.cacheDir,"alpha14-map-connectivity.json").writeText(Gson().toJson(probe))
        } finally { RadioTargets.aircraft.value=oldAircraft; RadioTargets.stations.value=oldStations }
        }
        }
    }
}
