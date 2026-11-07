package com.radiosport.ninegradio

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.ui.MainActivity
import com.radiosport.ninegradio.ui.MainViewModel
import com.radiosport.ninegradio.usb.RtlSdrService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** On MIUI, shell-start MainActivity if the OEM blocks the test runner's background launch. */
@RunWith(AndroidJUnit4::class)
class MainInteractionTest {
    @Test fun modeFrequencyPowerAndModuleOrderStayConsistent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ReceiverTestPreferences(context).use {
        val modules = context.getSharedPreferences("screen_modules", Context.MODE_PRIVATE)
        val oldOrder = modules.getString("order", null)
        val oldHidden = modules.getStringSet("hidden", emptySet())!!.toSet()
        val display = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val oldPalette = display.getString("pref_waterfall_palette", null)
        val oldFloor = display.getInt("pref_db_min", 120).takeIf { it in 10..200 } ?: 120
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun waitUntil(predicate: (MainActivity, MainViewModel) -> Boolean) {
                val deadline = System.currentTimeMillis() + 15000
                var satisfied: Boolean
                do {
                    satisfied = false
                    scenario.onActivity { activity -> satisfied = predicate(activity, ViewModelProvider(activity)[MainViewModel::class.java]) }
                    if (!satisfied) Thread.sleep(100)
                } while (!satisfied && System.currentTimeMillis() < deadline)
                assertTrue("State did not settle", satisfied)
            }
            scenario.onActivity { ViewModelProvider(it)[MainViewModel::class.java].setReceiverEnabled(true) }
            waitUntil { _, vm -> vm.connectionState.value is RtlSdrService.ConnectionState.Connected }
            var savedFrequency = 0L
            var savedMode = DemodMode.AM
            var savedAuto = false
            try {
                scenario.onActivity { activity ->
                    val vm = ViewModelProvider(activity)[MainViewModel::class.java]
                    savedFrequency = vm.centerFreqHz.value; savedMode = vm.demodMode.value; savedAuto = vm.autoDemod.value
                    display.edit().putBoolean("pref_stereo_wfm", false).commit()
                    display.edit().putString("pref_waterfall_palette", "MAGMA").putInt("pref_db_min", 120).commit()
                    vm.setAutoDemod(true)
                    vm.setFrequency(100_000_000)
                    assertEquals(DemodMode.WFM, vm.demodMode.value)
                    assertEquals(100_000_000L, vm.centerFreqHz.value)
                    // Leaving ADS-B restores analog RF settings; its 44.5 dB
                    // acquisition gain is intentionally independent of analog modes.
                    val gain = vm.gainIndex.value
                    vm.setFrequency(125_000_000)
                    assertEquals(DemodMode.AM, vm.demodMode.value)
                    assertEquals(125_000_000L, vm.centerFreqHz.value)
                    activity.getSharedPreferences("rtlsdr_prefs",Context.MODE_PRIVATE).edit()
                        .putLong("device_${vm.capabilities.value.id}_mode_USB_freq",7100000).commit()
                    vm.setDemodMode(DemodMode.USB)
                    assertFalse(vm.autoDemod.value)
                    assertEquals(7_100_000L, vm.centerFreqHz.value)
                    assertEquals(gain, vm.gainIndex.value)
                    assertEquals("MAGMA", display.getString("pref_waterfall_palette", null))
                    assertEquals(120, display.getInt("pref_db_min", 0))
                    vm.setReceiverEnabled(false)
                }
                waitUntil { activity, vm -> vm.getService()?.source == null &&
                    activity.findViewById<TextView>(R.id.tvSquelchStatus).text.toString() == "CLOSED" }
                scenario.onActivity { activity ->
                    activity.findViewById<TextView>(R.id.tvSquelchStatus).performClick()
                }
                waitUntil { activity, vm -> vm.getService()?.source?.isOpen() == true &&
                    activity.findViewById<TextView>(R.id.tvSquelchStatus).text.toString() == "OPEN" }
                modules.edit().putString("order", "3,0,1,2,4,5").putStringSet("hidden", setOf("0", "4")).commit()
                scenario.recreate()
                scenario.onActivity { activity ->
                    val tabs = activity.findViewById<TabLayout>(R.id.controlTabLayout)
                    assertEquals(activity.getString(R.string.tab_display), tabs.getTabAt(0)!!.contentDescription)
                    assertEquals(View.GONE, tabs.getTabAt(4)!!.view.visibility)
                    assertEquals(View.GONE, tabs.getTabAt(1)!!.view.visibility)
                }
            } finally {
                modules.edit().putString("order", oldOrder).putStringSet("hidden", oldHidden).commit()
                display.edit().putString("pref_waterfall_palette", oldPalette).putInt("pref_db_min", oldFloor).commit()
                scenario.onActivity { activity ->
                    val vm = ViewModelProvider(activity)[MainViewModel::class.java]
                    vm.setDemodMode(savedMode); vm.setFrequency(savedFrequency); vm.setAutoDemod(savedAuto)
                    vm.finishTuning()
                }
            }
        }
        }
    }
}
