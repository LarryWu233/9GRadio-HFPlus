package com.radiosport.ninegradio

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.switchmaterial.SwitchMaterial
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.ui.MainActivity
import com.radiosport.ninegradio.ui.MainViewModel
import eightbitlab.com.blurview.BlurView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Alpha19ControlsTest {
    @Test fun modeButtonsFreezeFrequencyAndRestoreAfterRecreation() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val context=inst.targetContext
        ReceiverTestPreferences(context).use {
            val prefs=context.getSharedPreferences("rtlsdr_prefs",Context.MODE_PRIVATE)
            prefs.edit().putBoolean("receiverEnabled",false).putBoolean("autoDemod",false)
                .putBoolean("modeStationMemory",true).apply()
            for(device in listOf("rtl","airspyhf")) prefs.edit()
                .putLong("device_${device}_mode_APRS_freq",144_800_000)
                .putLong("device_${device}_mode_WFM_freq",87_800_000)
                .putLong("device_${device}_mode_ADSB_freq",1_090_000_000).commit()
            val content=context.getSharedPreferences("audio_content",Context.MODE_PRIVATE)
            content.edit().putLong("SSTV_frequency",14_230_000).putLong("SAT_frequency",145_800_000).commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                Thread.sleep(1800)
                scenario.onActivity { activity ->
                    val model=ViewModelProvider(activity)[MainViewModel::class.java]
                    model.setReceiverEnabled(false); model.setAutoDemod(false)
                    model.setDemodMode(DemodMode.AM); model.setFrequency(11_920_000); model.finishTuning()
                    val toggle=activity.findViewById<SwitchMaterial>(R.id.switchModeStationMemory)
                    assertNotNull(toggle); assertTrue(toggle.isChecked)
                    toggle.performClick()
                    assertFalse(model.modeStationMemory.value)
                    fun descendants(view:View):List<View> = listOf(view) +
                        if(view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
                    val choices=descendants(activity.findViewById(R.id.contentModeButtons))
                    choices.first { it is TextView && it.text.toString()=="SSTV" }.performClick()
                    assertEquals(11_920_000L,model.centerFreqHz.value)
                    choices.first { it is TextView && it.text.toString()=="SAT" }.performClick()
                    assertEquals(11_920_000L,model.centerFreqHz.value)
                    assertEquals(14_230_000L,content.getLong("SSTV_frequency",0))
                    assertEquals(145_800_000L,content.getLong("SAT_frequency",0))
                    toggle.performClick()
                    assertEquals(145_800_000L,model.centerFreqHz.value)
                    toggle.performClick()
                    model.setFrequency(11_920_000); model.finishTuning()
                    fun select(mode:DemodMode) {
                        val grids=listOf(activity.findViewById<GridLayout>(R.id.chipGroupDemod),activity.findViewById<GridLayout>(R.id.chipGroupDigital))
                        val button=grids.flatMap { grid -> (0 until grid.childCount).map { grid.getChildAt(it) } }.first { it.tag==mode }
                        button.performClick()
                    }
                    select(DemodMode.APRS)
                    assertEquals(11_920_000L,model.centerFreqHz.value)
                    select(DemodMode.WFM)
                    assertEquals(11_920_000L,model.centerFreqHz.value)
                    model.setFrequency(100_000_000); model.finishTuning()
                    if(model.capabilities.value.supportsMode(DemodMode.ADSB)) {
                        select(DemodMode.ADSB)
                        assertEquals("ADSB must not force a station while memory is off",100_000_000L,model.centerFreqHz.value)
                    }
                    select(DemodMode.WFM)
                    assertEquals(100_000_000L,model.centerFreqHz.value)
                }
                scenario.recreate(); Thread.sleep(1400)
                scenario.onActivity { activity ->
                    val model=ViewModelProvider(activity)[MainViewModel::class.java]
                    val pager=activity.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.controlViewPager)
                    pager.setCurrentItem((pager.adapter as com.radiosport.ninegradio.ui.ControlsPagerAdapter)
                        .positionOf(com.radiosport.ninegradio.ui.ControlsPagerAdapter.TAB_MODE),false)
                    val toggle=activity.findViewById<SwitchMaterial>(R.id.switchModeStationMemory)
                    assertFalse(toggle.isChecked)
                    toggle.performClick()
                    assertTrue(model.modeStationMemory.value)
                    assertEquals(87_800_000L,model.centerFreqHz.value)
                    val arrow=activity.findViewById<View>(R.id.sheetToggle)
                    assertEquals(-2f*activity.resources.displayMetrics.density,arrow.translationY,0.1f)
                    val blur=activity.findViewById<BlurView>(R.id.panelEdgeBlur)
                    assertTrue(blur.isHardwareAccelerated)
                    val behavior=BottomSheetBehavior.from(activity.findViewById<View>(R.id.controlsBottomSheet))
                    behavior.state=BottomSheetBehavior.STATE_EXPANDED
                    arrow.performClick()
                }
                Thread.sleep(700) // BottomSheet applies state after layout/settling.
                scenario.onActivity { activity ->
                    val behavior=BottomSheetBehavior.from(activity.findViewById<View>(R.id.controlsBottomSheet))
                    assertEquals(BottomSheetBehavior.STATE_COLLAPSED,behavior.state)
                    behavior.state=BottomSheetBehavior.STATE_EXPANDED
                }
                Thread.sleep(500)
                inst.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.cacheDir,"alpha19-controls.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                    bitmap.recycle()
                }
            }
        }
    }
}
