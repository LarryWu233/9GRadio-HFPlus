package com.radiosport.ninegradio

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.source.DeviceCapabilities
import com.radiosport.ninegradio.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpectrumBandwidthInteractionTest {
    @Test fun fullSpanAndBothBandwidthEdgesWorkWithCoarseStep() {
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        ReceiverTestPreferences(instrumentation.targetContext).use {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(500)
            var oldHz=100000000L; var oldMode=DemodMode.WFM; var oldRate=768000
            var oldBw=0; var oldDec=1; var oldStep=1000L; var oldPower=false; var oldAuto=false
            var oldCaps=DeviceCapabilities.airspy()
            fun vm(a: MainActivity)=ViewModelProvider(a)[MainViewModel::class.java]
            @Suppress("UNCHECKED_CAST")
            fun caps(a: MainActivity)=MainViewModel::class.java.getDeclaredField("_capabilities").apply { isAccessible=true }.get(vm(a)) as MutableStateFlow<DeviceCapabilities>
            fun drag(plot: SpectrumView,from: Float,to: Float) {
                val time=SystemClock.uptimeMillis()
                for((action,x,delta) in listOf(Triple(MotionEvent.ACTION_DOWN,from,0L),Triple(MotionEvent.ACTION_MOVE,to,200L),Triple(MotionEvent.ACTION_UP,to,400L))) {
                    MotionEvent.obtain(time,time+delta,action,plot.width*x,plot.height/2f,0).also { plot.dispatchTouchEvent(it); it.recycle() }
                }
            }
            try {
                scenario.onActivity { a ->
                    val m=vm(a)
                    oldHz=m.centerFreqHz.value; oldMode=m.demodMode.value; oldRate=m.sampleRate.value
                    oldBw=m.ifBandwidthHz.value; oldDec=m.decimation.value; oldPower=m.receiverEnabled.value; oldAuto=m.autoDemod.value
                    oldCaps=caps(a).value; oldStep=a.findViewById<FrequencyView>(R.id.frequencyView).tuningStepHz
                    m.setReceiverEnabled(false); m.setAutoDemod(false)
                }
                var service:com.radiosport.ninegradio.usb.RtlSdrService?=null
                scenario.onActivity { service=vm(it).getService() }
                kotlinx.coroutines.runBlocking { service?.awaitControls() }
                instrumentation.waitForIdleSync()
                scenario.onActivity { a ->
                    val m=vm(a)
                    caps(a).value=DeviceCapabilities.airspy(listOf(768000,912000))
                    // Exit ADS-B first: that decoder deliberately pins 2.400 MS/s.
                    m.setDemodMode(DemodMode.WFM); m.setSampleRate(768000); m.setFrequency(100000000); m.setIfBandwidth(200000)
                    m.setDecimation(64)
                    a.findViewById<FrequencyView>(R.id.frequencyView).setTuningStep(1000000)
                }
                Thread.sleep(200)
                scenario.onActivity { a ->
                    a.findViewById<View>(R.id.tvPlotZoom).performClick()
                    val plot=a.findViewById<SpectrumView>(R.id.spectrumView)
                    assertEquals(768000L,plot.visibleBandwidthHz())
                    assertEquals(1,vm(a).decimation.value)
                    assertEquals("1",PreferenceManager.getDefaultSharedPreferences(a).getString("pref_decimation",null))
                    assertEquals("WFM",a.findViewById<android.widget.TextView>(R.id.tvDemodMode).text.toString())
                    drag(plot,0.5f-100000f/768000,0.5f-330000f/768000)
                    assertEquals(660000,vm(a).ifBandwidthHz.value)
                    assertEquals(100000000L,vm(a).centerFreqHz.value)
                }
                Thread.sleep(200)
                scenario.onActivity { a ->
                    val plot=a.findViewById<SpectrumView>(R.id.spectrumView)
                    drag(plot,0.5f+330000f/768000,0.5f+100000f/768000)
                    assertEquals(200000,vm(a).ifBandwidthHz.value)
                    assertEquals(100000000L,vm(a).centerFreqHz.value)
                    val toggle=a.findViewById<android.widget.TextView>(R.id.btnGraphGesture)
                    if(!toggle.text.startsWith("拖动")) toggle.performClick()
                    drag(plot,0.95f,0.85f)
                    assertTrue(vm(a).centerFreqHz.value>100000000L)
                    assertTrue(vm(a).centerFreqHz.value<101000000L)
                }
            } finally {
                scenario.onActivity { a ->
                    caps(a).value=oldCaps
                    vm(a).apply { setSampleRate(oldRate); setDemodMode(oldMode); setFrequency(oldHz); setIfBandwidth(oldBw); setAutoDemod(oldAuto); setReceiverEnabled(oldPower); finishTuning() }
                    a.findViewById<FrequencyView>(R.id.frequencyView).setTuningStep(oldStep)
                    // Leave the requested fix visible; do not restore the old hidden x64 crop.
                    a.findViewById<View>(R.id.tvPlotZoom).performClick()
                }
            }
        }
        }
    }
}
