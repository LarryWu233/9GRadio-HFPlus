package com.radiosport.ninegradio

import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.ui.MainActivity
import com.radiosport.ninegradio.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FmSettingsTest {
    @Test fun stereoSettingAndModeStaySynchronizedWithoutRetuning() {
        ReceiverTestPreferences(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext).use {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(600)
            scenario.onActivity { activity ->
                val vm=ViewModelProvider(activity)[MainViewModel::class.java]
                val preferences=PreferenceManager.getDefaultSharedPreferences(activity)
                val oldMode=vm.demodMode.value; val oldAuto=vm.autoDemod.value
                val oldStereo=preferences.getBoolean("pref_stereo_wfm",true)
                val savedHz=vm.centerFreqHz.value
                try {
                    vm.setAutoDemod(false)
                    vm.setDemodMode(DemodMode.WFM)
                    // Manual mode selection restores that mode's frequency history.
                    // Toggling Stereo within FM must preserve the resulting frequency.
                    val hz=vm.centerFreqHz.value
                    assertFalse(preferences.getBoolean("pref_stereo_wfm",true))
                    preferences.edit().putBoolean("pref_stereo_wfm",true).commit()
                    assertEquals(DemodMode.WFM_STEREO,vm.demodMode.value)
                    assertEquals(hz,vm.centerFreqHz.value)
                    preferences.edit().putBoolean("pref_stereo_wfm",false).commit()
                    assertEquals(DemodMode.WFM,vm.demodMode.value)
                    vm.setDemodMode(DemodMode.WFM_STEREO)
                    assertTrue(preferences.getBoolean("pref_stereo_wfm",false))
                    vm.setDemodMode(DemodMode.AM)
                    val amHz=vm.centerFreqHz.value
                    preferences.edit().putBoolean("pref_stereo_wfm",false).commit()
                    assertEquals(DemodMode.AM,vm.demodMode.value)
                    assertEquals(amHz,vm.centerFreqHz.value)
                    assertTrue(preferences.getString("pref_deemph_us","75") in listOf("50","75"))
                } finally {
                    vm.setDemodMode(oldMode)
                    vm.setFrequency(savedHz)
                    preferences.edit().putBoolean("pref_stereo_wfm",oldStereo).commit()
                    vm.setAutoDemod(oldAuto)
                }
            }
        }
        }
    }
}
