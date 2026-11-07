package com.radiosport.ninegradio

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.source.DeviceCapabilities
import com.radiosport.ninegradio.ui.MainViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModeFrequencyMemoryTest {
    @Test fun manualHistoryAutoFollowingAndDeviceIsolation() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        ReceiverTestPreferences(context).use {
            val prefs=context.getSharedPreferences("rtlsdr_prefs",Context.MODE_PRIVATE)
            prefs.edit().putString("activeDevice","airspyhf").putString("demod","AM")
                .putString("device_airspyhf_mode","AM").putLong("device_airspyhf_freq",10000000)
                .putBoolean("autoDemod",false).putLong("freq",10000000)
                .putLong("device_airspyhf_mode_APRS_freq",145825000)
                .putLong("device_airspyhf_mode_WFM_freq",87800000)
                .remove("device_airspyhf_mode_CW_freq").commit()
            val store=ViewModelStore()
            instrumentation.runOnMainSync {
                try {
                    val model=ViewModelProvider(store,ViewModelProvider.AndroidViewModelFactory
                        .getInstance(context.applicationContext as Application))[MainViewModel::class.java]
                    @Suppress("UNCHECKED_CAST")
                    val caps=MainViewModel::class.java.getDeclaredField("_capabilities").apply { isAccessible=true }
                        .get(model) as MutableStateFlow<DeviceCapabilities>
                    caps.value=DeviceCapabilities.airspy()
                    model.setFrequency(10000000)
                    model.setDemodMode(DemodMode.APRS)
                    assertEquals(145825000L,model.centerFreqHz.value)
                    model.setFrequency(144800000)
                    model.setDemodMode(DemodMode.WFM)
                    assertEquals(87800000L,model.centerFreqHz.value)
                    model.setDemodMode(DemodMode.APRS)
                    assertEquals(144800000L,model.centerFreqHz.value)
                    model.setFrequency(32000000) // browsing a blind gap must not replace a receive frequency
                    model.setDemodMode(DemodMode.AM)
                    assertEquals(10000000L,model.centerFreqHz.value)
                    model.setDemodMode(DemodMode.APRS)
                    assertEquals(144800000L,model.centerFreqHz.value)
                    model.setDemodMode(DemodMode.CW) // first use preserves the dial
                    assertEquals(144800000L,model.centerFreqHz.value)
                    prefs.edit().putLong("device_airspyhf_mode_AM_freq",1090000000).commit()
                    model.setDemodMode(DemodMode.AM)
                    assertEquals("Invalid history must not retune",144800000L,model.centerFreqHz.value)
                    model.setDemodMode(DemodMode.WFM)
                    model.setAutoDemod(true)
                    model.setFrequency(125000000)
                    assertEquals(DemodMode.AM,model.demodMode.value)
                    assertEquals(125000000L,model.centerFreqHz.value)
                    assertEquals("Auto following must save the old band's frequency",87800000L,
                        prefs.getLong("device_airspyhf_mode_WFM_freq",0))
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit()
                        .putBoolean("pref_stereo_wfm",true).commit()
                    model.setFrequency(100000000)
                    assertEquals(DemodMode.WFM_STEREO,model.demodMode.value)
                    assertEquals(100000000L,model.centerFreqHz.value)
                    assertEquals(125000000L,prefs.getLong("device_airspyhf_mode_AM_freq",0))
                    model.setAutoDemod(false)
                    model.setDemodMode(DemodMode.WFM)
                    assertEquals(87800000L,model.centerFreqHz.value)
                } finally { store.clear() }
            }
            prefs.edit().putString("activeDevice","rtl").putString("demod","AM").putString("device_rtl_mode","AM")
                .putLong("device_rtl_mode_APRS_freq",433775000).commit()
            val otherStore=ViewModelStore()
            instrumentation.runOnMainSync { try {
                val other=ViewModelProvider(otherStore,ViewModelProvider.AndroidViewModelFactory
                    .getInstance(context.applicationContext as Application))[MainViewModel::class.java]
                other.setDemodMode(DemodMode.APRS)
                assertEquals(433775000L,other.centerFreqHz.value)
                assertEquals(144800000L,prefs.getLong("device_airspyhf_mode_APRS_freq",0))
            } finally { otherStore.clear() } }
        }
    }
}
