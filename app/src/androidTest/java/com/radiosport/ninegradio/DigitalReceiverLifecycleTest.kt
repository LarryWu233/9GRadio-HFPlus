package com.radiosport.ninegradio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.gson.Gson
import com.google.android.material.tabs.TabLayout
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DigitalReceiverLifecycleTest {
    private fun await(condition: ()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+12000
        while(!condition() && SystemClock.elapsedRealtime()<deadline) Thread.sleep(50)
        assertTrue("Timed out waiting for receiver state",condition())
    }
    private fun capture(view: View,name: String) {
        val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image)); File(view.context.cacheDir,name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
    }
    @Test fun mapAndRdfKeepReceivingAndClosedReleasesDecoder() {
        val inst=InstrumentationRegistry.getInstrumentation()
        ReceiverTestPreferences(inst.targetContext).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var main: MainActivity; lateinit var model: MainViewModel
                scenario.onActivity { main=it; model=ViewModelProvider(it)[MainViewModel::class.java]; model.setReceiverEnabled(true) }
                await { model.getService()?.source?.isOpen()==true }
                scenario.onActivity { model.setAutoDemod(false); model.setDemodMode(DemodMode.ADSB); model.setFrequency(1090000000); model.finishTuning() }
                val svc=model.getService()!!
                await { svc.adsbReceiver.status.value.inputSamples>2400000 }
                Thread.sleep(2600) // Explicit MIUI launcher helper has completed before navigation.
                scenario.onActivity { assertEquals(2400000L,it.findViewById<SpectrumView>(R.id.spectrumView).visibleBandwidthHz()) }
                var before=svc.adsbReceiver.status.value.inputSamples
                scenario.onActivity { a ->
                    val adapter=MainActivity::class.java.getDeclaredField("pagerAdapter").apply { isAccessible=true }.get(a) as ControlsPagerAdapter
                    assertTrue(ModulePreferences(a).order.contains(ControlsPagerAdapter.TAB_RDF))
                    a.findViewById<TabLayout>(R.id.controlTabLayout).getTabAt(adapter.positionOf(ControlsPagerAdapter.TAB_ADSB))!!.select()
                    assertEquals("ADS-B",a.findViewById<TextView>(R.id.tvDemodMode).text.toString())
                }
                Thread.sleep(500)
                scenario.onActivity { a ->
                    val toggle=a.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.btnAdsbPanelMap)
                    toggle.isChecked=true
                    assertEquals(View.VISIBLE,a.findViewById<View>(R.id.adsbMapHost).visibility)
                    assertEquals(View.GONE,a.findViewById<View>(R.id.adsbPanelRadar).visibility)
                    a.findViewById<View>(R.id.btnAdsbPanelFull).performClick()
                }
                Thread.sleep(1500)
                scenario.onActivity { a ->
                    val y=IntArray(2); val f=IntArray(2)
                    a.findViewById<View>(R.id.controlsBottomSheet).getLocationOnScreen(y)
                    val freq=a.findViewById<View>(R.id.frequencyView); freq.getLocationOnScreen(f)
                    assertTrue("Expanded sheet starts directly below frequency",kotlin.math.abs(f[1]+freq.height-y[1])<=4)
                    assertTrue(a.findViewById<View>(R.id.frequencyView).isShown)
                    val toggle=a.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.btnAdsbPanelMap)
                    toggle.isChecked=false
                    assertEquals(View.VISIBLE,a.findViewById<View>(R.id.adsbPanelRadar).visibility)
                    assertEquals(View.GONE,a.findViewById<View>(R.id.adsbMapHost).visibility)
                    toggle.isChecked=true
                }
                Thread.sleep(2000)
                inst.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(inst.targetContext.cacheDir,"alpha14-inline-map-expanded.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
                }
                assertTrue("Inline map must not replace or restart the raw-IQ decoder",svc.adsbReceiver.status.value.inputSamples>before+2400000)
                scenario.onActivity { a -> a.findViewById<View>(R.id.btnAdsbPanelFull).performClick(); capture(a.window.decorView,"alpha14-adsb-panel.png") }
                scenario.onActivity { a ->
                    val adapter=MainActivity::class.java.getDeclaredField("pagerAdapter").apply { isAccessible=true }.get(a) as ControlsPagerAdapter
                    a.findViewById<TabLayout>(R.id.controlTabLayout).getTabAt(adapter.positionOf(ControlsPagerAdapter.TAB_RDF))!!.select()
                }
                Thread.sleep(400)
                await {
                    var idle=false
                    inst.runOnMainSync { idle=main.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.controlViewPager).scrollState==androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE }
                    idle
                }
                scenario.onActivity { capture(it.window.decorView,"alpha14-rdf-panel.png"); model.setReceiverEnabled(false) }
                await { svc.source==null && !svc.adsbReceiver.status.value.running }
                assertTrue(svc.adsbReceiver.aircraft.value.isEmpty())
                scenario.onActivity { model.setReceiverEnabled(true) }
                await { svc.adsbReceiver.status.value.inputSamples>2400000 }
                assertEquals(2400000,svc.source!!.getSampleRate())
                File(inst.targetContext.cacheDir,"alpha14-lifecycle.json").writeText(Gson().toJson(mapOf(
                    "mapNetwork" to MapNetwork.status.value,"decoderAfterReopen" to svc.adsbReceiver.status.value,
                    "mode" to model.demodMode.value.name,"frequency" to model.centerFreqHz.value,
                    "gainIndex" to model.gainIndex.value,"tunerAgc" to model.tunerAgcEnabled.value,"hardwareAgc" to model.hardwareAgcEnabled.value)))
                scenario.onActivity { model.setReceiverEnabled(false) }
                await { svc.source==null }
            }
        }
    }
}
