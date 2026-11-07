package com.radiosport.ninegradio

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.tabs.TabLayout
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.source.DeviceCapabilities
import com.radiosport.ninegradio.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnifiedControlsTest {
    @Test fun scanIntegrationZoomPanelAndBlindBandBrowseWork() {
        ReceiverTestPreferences(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext).use {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var oldHz = 100000000L; var oldMode = DemodMode.AM; var oldPower = false; var oldAuto = false
            var oldCaps = DeviceCapabilities.RTL
            var oldStep = 1000L
            fun vm(activity: MainActivity) = ViewModelProvider(activity)[MainViewModel::class.java]
            @Suppress("UNCHECKED_CAST")
            fun caps(activity: MainActivity) = MainViewModel::class.java.getDeclaredField("_capabilities")
                .apply { isAccessible = true }.get(vm(activity)) as MutableStateFlow<DeviceCapabilities>
            try {
                Thread.sleep(800)
                scenario.onActivity { activity ->
                    val model = vm(activity)
                    oldHz = model.centerFreqHz.value; oldMode = model.demodMode.value
                    oldPower = model.receiverEnabled.value; oldAuto = model.autoDemod.value; oldCaps = caps(activity).value
                    oldStep=activity.findViewById<FrequencyView>(R.id.frequencyView).tuningStepHz
                    model.setReceiverEnabled(false); model.setAutoDemod(false)
                    caps(activity).value = DeviceCapabilities.airspy()
                    assertTrue(model.setFrequency(31000000))
                    activity.findViewById<FrequencyView>(R.id.frequencyView).apply { setFrequency(31000000); setTuningStep(1000000) }
                    activity.findViewById<FrequencyView>(R.id.frequencyView).stepUp()
                    assertEquals(32000000L, model.centerFreqHz.value)
                    assertFalse(model.capabilities.value.supportsFrequency(model.centerFreqHz.value))
                }
                Thread.sleep(150)
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<TextView>(R.id.tvBandContext).text.contains("盲区"))
                    assertEquals(0.4f, activity.findViewById<View>(R.id.spectrumView).alpha, 0f)
                    vm(activity).setFrequency(63000000)
                    activity.findViewById<FrequencyView>(R.id.frequencyView).setFrequency(63000000)
                    activity.findViewById<FrequencyView>(R.id.frequencyView).stepUp()
                    assertEquals(64000000L, vm(activity).centerFreqHz.value)
                    activity.findViewById<View>(R.id.tvPlotZoom).performClick()
                    val initial = activity.findViewById<SpectrumView>(R.id.spectrumView).visibleBandwidthHz()
                    activity.findViewById<View>(R.id.btnPlotZoomIn).performClick()
                    assertEquals(initial / 2.0, activity.findViewById<SpectrumView>(R.id.spectrumView).visibleBandwidthHz().toDouble(), 1.0)
                    activity.findViewById<View>(R.id.tvPlotZoom).performClick()
                    val config = ModulePreferences(activity)
                    assertEquals(1, config.order.count { it == ControlsPagerAdapter.TAB_SCAN })
                    assertTrue(config.coreOrder.contains(ControlsPagerAdapter.TAB_SCAN))
                    val adapter = MainActivity::class.java.getDeclaredField("pagerAdapter").apply { isAccessible = true }.get(activity) as ControlsPagerAdapter
                    activity.findViewById<TabLayout>(R.id.controlTabLayout).getTabAt(adapter.positionOf(ControlsPagerAdapter.TAB_SCAN))!!.select()
                    val page = adapter.getViewAt(ControlsPagerAdapter.TAB_SCAN)!!
                    assertTrue(page.findViewById<LinearLayout>(R.id.etmContainer).childCount > 0)
                    val matches = ArrayList<View>()
                    page.findViewById<SegmentedSelectorView>(R.id.scannerTypePicker).findViewsWithText(matches,"范围扫描",View.FIND_VIEWS_WITH_TEXT)
                    assertEquals(1,matches.size); matches.single().performClick()
                    assertEquals(View.VISIBLE,page.findViewById<View>(R.id.generalScanContainer).visibility)
                    assertEquals(View.GONE,page.findViewById<View>(R.id.etmContainer).visibility)
                    activity.findViewById<View>(R.id.sheetToggle).performClick()
                }
                Thread.sleep(450)
                scenario.onActivity { activity ->
                    assertEquals(BottomSheetBehavior.STATE_COLLAPSED,BottomSheetBehavior.from(activity.findViewById<View>(R.id.controlsBottomSheet)).state)
                    assertTrue(activity.findViewById<PanelToggleView>(R.id.sheetToggle).isCollapsed)
                    activity.findViewById<View>(R.id.sheetToggle).performClick()
                }
                Thread.sleep(450)
                scenario.onActivity { activity ->
                    assertFalse(activity.findViewById<PanelToggleView>(R.id.sheetToggle).isCollapsed)
                    val adapter=MainActivity::class.java.getDeclaredField("pagerAdapter").apply { isAccessible=true }.get(activity) as ControlsPagerAdapter
                    activity.findViewById<TabLayout>(R.id.controlTabLayout).getTabAt(adapter.positionOf(ControlsPagerAdapter.TAB_TUNE))!!.select()
                }
                Thread.sleep(250)
                scenario.onActivity { activity ->
                    val adapter=MainActivity::class.java.getDeclaredField("pagerAdapter").apply { isAccessible=true }.get(activity) as ControlsPagerAdapter
                    val page=adapter.getViewAt(ControlsPagerAdapter.TAB_TUNE)!!
                    assertEquals("64.000.000 Hz",page.findViewById<TextView>(R.id.tvTuneFrequency).text.toString())
                    assertEquals(View.GONE,page.findViewById<View>(R.id.tuningParameterSelector).visibility)
                    assertEquals(View.VISIBLE,page.findViewById<View>(R.id.stepParameter).visibility)
                    assertEquals(View.GONE,page.findViewById<View>(R.id.bandwidthParameter).visibility)
                    assertTrue(page.findViewById<TuningDialView>(R.id.tuningDial).showCurrentFrequency)
                    assertFalse(page.findViewById<TuningDialView>(R.id.tuningDial).showLabels)
                    val step=page.findViewById<TuningDialView>(R.id.stepPicker)
                    assertTrue(step.showLabels)
                    val frequency=activity.findViewById<FrequencyView>(R.id.frequencyView)
                    frequency.setTuningStep(1000)
                    val time=android.os.SystemClock.uptimeMillis()
                    fun event(action: Int,x: Float,elapsed: Long) {
                        android.view.MotionEvent.obtain(time,time+elapsed,action,x,step.height/2f,0).also { step.onTouchEvent(it); it.recycle() }
                    }
                    event(android.view.MotionEvent.ACTION_DOWN,step.width*0.85f,0)
                    event(android.view.MotionEvent.ACTION_MOVE,step.width*0.15f,500)
                    event(android.view.MotionEvent.ACTION_CANCEL,step.width*0.15f,1000)
                    assertTrue("Step wheel must change the actual tuning step",frequency.tuningStepHz>1000)
                    val modePage=adapter.getViewAt(ControlsPagerAdapter.TAB_MODE)!!
                    val analog=modePage.findViewById<android.widget.GridLayout>(R.id.chipGroupDemod)
                    val digital=modePage.findViewById<android.widget.GridLayout>(R.id.chipGroupDigital)
                    fun buttons(group: android.widget.GridLayout)=(0 until group.childCount).map { group.getChildAt(it) }.filter { it.tag is DemodMode }
                    assertEquals(9,buttons(analog).size); assertEquals(8,buttons(digital).size)
                    fun choose(group: android.widget.GridLayout,mode: DemodMode) {
                        (0 until group.childCount).map { group.getChildAt(it) }.single { it.tag==mode }.performClick()
                    }
                    choose(digital,DemodMode.RAW)
                    assertEquals(DemodMode.RAW,vm(activity).demodMode.value)
                    assertEquals(0,buttons(analog).count { it.isSelected })
                    assertEquals(1,buttons(digital).count { it.isSelected })
                    choose(analog,DemodMode.AM)
                    choose(analog,DemodMode.AM)
                    assertEquals(DemodMode.AM,vm(activity).demodMode.value)
                    assertEquals(0,buttons(digital).count { it.isSelected })
                    assertEquals(1,buttons(analog).count { it.isSelected })
                    vm(activity).setFrequency(65000000)
                }
                Thread.sleep(150)
                scenario.onActivity { activity ->
                    assertEquals("External tuning must synchronize the main digits",65000000L,
                        activity.findViewById<FrequencyView>(R.id.frequencyView).getFrequency())
                    assertEquals("65.000.000 Hz",activity.findViewById<TextView>(R.id.tvTuneFrequency).text.toString())
                }
            } finally {
                scenario.onActivity { activity ->
                    caps(activity).value = oldCaps
                    vm(activity).apply { setDemodMode(oldMode); setFrequency(oldHz); setAutoDemod(oldAuto); setReceiverEnabled(oldPower); finishTuning() }
                    activity.findViewById<FrequencyView>(R.id.frequencyView).setTuningStep(oldStep)
                }
            }
        }
        }
    }
}
