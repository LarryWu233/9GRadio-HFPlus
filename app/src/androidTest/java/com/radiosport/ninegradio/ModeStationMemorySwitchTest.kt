package com.radiosport.ninegradio

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModeStationMemorySwitchTest {
    @Test fun disabledTuningAndDelayedSavesCannotReplaceHistory() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val context=inst.targetContext
        ReceiverTestPreferences(context).use {
            val prefs=context.getSharedPreferences("rtlsdr_prefs",Context.MODE_PRIVATE)
            prefs.edit().putString("activeDevice","airspyhf").putString("demod","AM")
                .putLong("freq",10_000_000).putBoolean("autoDemod",false)
                .putBoolean("modeStationMemory",true).putBoolean("receiverEnabled",false)
                .putLong("device_airspyhf_mode_APRS_freq",144_800_000)
                .putLong("device_airspyhf_mode_WFM_freq",87_800_000).commit()
            var store=ViewModelStore()
            fun model()=ViewModelProvider(store,ViewModelProvider.AndroidViewModelFactory
                .getInstance(context.applicationContext as Application))[MainViewModel::class.java]
            inst.runOnMainSync {
                model().apply {
                    setFrequency(11_920_000) // disable before the 350 ms save fires
                    setModeStationMemory(false)
                    setFrequency(12_000_000)
                    setDemodMode(DemodMode.APRS)
                    assertEquals(12_000_000L,centerFreqHz.value)
                    setFrequency(145_825_000); finishTuning()
                    setDemodMode(DemodMode.WFM)
                    assertEquals(145_825_000L,centerFreqHz.value)
                    setFrequency(100_000_000)
                }
            }
            Thread.sleep(550) // exercise the real delayed persistence path
            inst.runOnMainSync { store.clear() }
            assertEquals(11_920_000L,prefs.getLong("device_airspyhf_mode_AM_freq",0))
            assertEquals(144_800_000L,prefs.getLong("device_airspyhf_mode_APRS_freq",0))
            assertEquals(87_800_000L,prefs.getLong("device_airspyhf_mode_WFM_freq",0))
            assertFalse(prefs.getBoolean("modeStationMemory",true))
            store=ViewModelStore()
            inst.runOnMainSync { try {
                model().apply {
                    assertFalse(modeStationMemory.value)
                    setModeStationMemory(true)
                    assertEquals(87_800_000L,centerFreqHz.value)
                    assertEquals("Restored dial must survive process death",87_800_000L,prefs.getLong("freq",0))
                    setDemodMode(DemodMode.AM)
                    assertEquals(11_920_000L,centerFreqHz.value)
                    setDemodMode(DemodMode.APRS)
                    assertEquals(144_800_000L,centerFreqHz.value)
                    setFrequency(144_390_000); finishTuning()
                    assertEquals(144_390_000L,rememberedModeFrequency(DemodMode.APRS))
                }
            } finally { store.clear() } }
        }
    }
}
