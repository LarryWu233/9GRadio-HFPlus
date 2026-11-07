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
class AdsbConfigurationTest {
    @Test fun adsbRfDefaultsRememberUserGainAndRestoreVoiceSettings() {
        val inst=InstrumentationRegistry.getInstrumentation(); val context=inst.targetContext
        ReceiverTestPreferences(context).use {
            context.getSharedPreferences("rtlsdr_prefs",Context.MODE_PRIVATE).edit()
                .putString("activeDevice","rtl").putString("device_rtl_mode","NFM").putString("demod","NFM")
                .putInt("rate",912000).putInt("device_rtl_rate",912000)
                .remove("device_rtl_mode_ADSB_rfReadyV1").commit()
            val store=ViewModelStore()
            inst.runOnMainSync { try {
                val model=ViewModelProvider(store,ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application))[MainViewModel::class.java]
                model.setGain(4); model.setTunerAgc(true); model.setHardwareAgc(true); model.setSampleRate(912000)
                model.setDecimation(32)
                model.setDemodMode(DemodMode.ADSB)
                assertEquals(2400000,model.sampleRate.value); assertEquals(26,model.gainIndex.value)
                assertEquals(1,model.decimation.value)
                assertFalse(model.tunerAgcEnabled.value); assertFalse(model.hardwareAgcEnabled.value)
                model.setSampleRate(912000); assertEquals(2400000,model.sampleRate.value)
                model.setGain(23)
                model.setDemodMode(DemodMode.NFM)
                assertEquals(4,model.gainIndex.value); assertTrue(model.tunerAgcEnabled.value)
                assertTrue(model.hardwareAgcEnabled.value); assertEquals(912000,model.sampleRate.value)
                assertEquals(32,model.decimation.value)
                model.setDemodMode(DemodMode.ADSB)
                assertEquals(23,model.gainIndex.value); assertEquals(2400000,model.sampleRate.value)
            } finally { store.clear() } }
        }
    }
}
