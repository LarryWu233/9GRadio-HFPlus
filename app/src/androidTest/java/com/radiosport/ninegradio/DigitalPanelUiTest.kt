package com.radiosport.ninegradio

import android.os.SystemClock
import android.view.View
import android.widget.Button
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Layout/navigation coverage independent of an attached receiver or live signals. */
@RunWith(AndroidJUnit4::class)
class DigitalPanelUiTest {
    @Test fun inlineMapRadarExpansionAndCompassStayInMainActivity() {
        val inst=InstrumentationRegistry.getInstrumentation()
        fun screenshot(name: String) {
            inst.uiAutomation.takeScreenshot()?.let { b ->
                File(inst.targetContext.cacheDir,name).outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; b.recycle()
            }
        }
        ReceiverTestPreferences(inst.targetContext).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { a -> ViewModelProvider(a)[MainViewModel::class.java].apply {
                    setReceiverEnabled(false); setAutoDemod(false); setDemodMode(DemodMode.ADSB); setFrequency(1090000000)
                } }
                Thread.sleep(3200)
                scenario.onActivity { a ->
                    val map=a.findViewById<SwitchMaterial>(R.id.btnAdsbPanelMap)
                    map.isChecked=true
                    a.findViewById<Button>(R.id.btnAdsbPanelFull).performClick()
                }
                Thread.sleep(1200)
                scenario.onActivity { a ->
                    val sheet=IntArray(2); val frequency=IntArray(2)
                    a.findViewById<View>(R.id.controlsBottomSheet).getLocationOnScreen(sheet)
                    val f=a.findViewById<View>(R.id.frequencyView); f.getLocationOnScreen(frequency)
                    assertTrue("Sheet must begin below frequency: ${sheet[1]} vs ${frequency[1]+f.height}",kotlin.math.abs(sheet[1]-frequency[1]-f.height)<=4)
                    assertEquals(View.VISIBLE,a.findViewById<View>(R.id.adsbMapHost).visibility)
                    assertEquals(View.GONE,a.findViewById<View>(R.id.adsbPanelRadar).visibility)
                    val handle=a.findViewById<View>(R.id.sheetToggle); val arrow=IntArray(2)
                    handle.getLocationOnScreen(arrow)
                    assertTrue("Handle stroke must stay below frequency: top=${arrow[1]}, height=${handle.height}, frequencyBottom=${frequency[1]+f.height}",arrow[1]+handle.height/2-4*a.resources.displayMetrics.density>=frequency[1]+f.height-1)
                }
                screenshot("alpha14-inline-map-expanded.png")
                scenario.onActivity { it.findViewById<SwitchMaterial>(R.id.btnAdsbPanelMap).isChecked=false }
                Thread.sleep(500)
                scenario.onActivity { a ->
                    assertEquals(View.VISIBLE,a.findViewById<View>(R.id.adsbPanelRadar).visibility)
                    assertEquals(View.GONE,a.findViewById<View>(R.id.adsbMapHost).visibility)
                }
                screenshot("alpha14-inline-radar-expanded.png")
                scenario.onActivity { a ->
                    a.findViewById<Button>(R.id.btnAdsbPanelFull).performClick()
                    val adapter=MainActivity::class.java.getDeclaredField("pagerAdapter").apply { isAccessible=true }.get(a) as ControlsPagerAdapter
                    a.findViewById<TabLayout>(R.id.controlTabLayout).getTabAt(adapter.positionOf(ControlsPagerAdapter.TAB_RDF))!!.select()
                }
                Thread.sleep(900)
                screenshot("alpha14-rdf-panel.png")
                scenario.onActivity { a ->
                    assertTrue(a.findViewById<android.widget.FrameLayout>(R.id.rdfControls).getChildAt(0) is RdfControls)
                    assertEquals(DemodMode.ADSB,ViewModelProvider(a)[MainViewModel::class.java].demodMode.value)
                }
                fun select(module: Int) {
                    scenario.onActivity { a ->
                        val adapter=MainActivity::class.java.getDeclaredField("pagerAdapter").apply { isAccessible=true }.get(a) as ControlsPagerAdapter
                        a.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.controlViewPager).setCurrentItem(adapter.positionOf(module),false)
                    }
                    Thread.sleep(500)
                }
                for((module,name) in listOf(ControlsPagerAdapter.TAB_MODE to "mode",ControlsPagerAdapter.TAB_DISPLAY to "display",ControlsPagerAdapter.TAB_RECORDING to "recording",ControlsPagerAdapter.TAB_SCAN to "scan-etm")) {
                    select(module); screenshot("alpha16-$name.png")
                }
                scenario.onActivity { a ->
                    val picker=a.findViewById<SegmentedSelectorView>(R.id.scannerTypePicker)
                    (0 until picker.childCount).map { picker.getChildAt(it) }.filterIsInstance<android.widget.Button>().single { it.text.toString()=="范围扫描" }.performClick()
                    assertTrue(a.findViewById<View>(R.id.listScanTabHits) is RadioDataTable)
                    assertTrue(a.findViewById<View>(R.id.listScanTabChannelTable) is RadioDataTable)
                }
                Thread.sleep(400); screenshot("alpha16-scan-range.png")
                scenario.onActivity { a ->
                    val adapter=MainActivity::class.java.getDeclaredField("pagerAdapter").apply { isAccessible=true }.get(a) as ControlsPagerAdapter
                    val top=a.findViewById<View>(R.id.btnToolbarSettings)
                    assertTrue(top.isShown)
                    val model=a.findViewById<View>(R.id.tvDeviceName); val power=a.findViewById<View>(R.id.tvSquelchStatus)
                    assertTrue("Power follows model name",power.left>=model.right)
                    assertTrue((0 until adapter.itemCount).none { adapter.moduleAt(it)==ControlsPagerAdapter.TAB_SETTINGS })
                    top.performClick()

                }
                Thread.sleep(800); screenshot("alpha16-settings.png")
                inst.runOnMainSync {
                    val settings=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).single()
                    assertTrue(settings is SettingsActivity)
                    assertNull(settings.findViewById<View>(R.id.controlTabLayout))
                    settings.finish()
                }
            }
        }
    }
}
