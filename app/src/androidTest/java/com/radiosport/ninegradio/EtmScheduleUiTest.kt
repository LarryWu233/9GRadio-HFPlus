package com.radiosport.ninegradio

import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.content.*
import com.radiosport.ninegradio.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EtmScheduleUiTest {
    private fun views(root:View):List<View> = listOf(root)+if(root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun <T> field(a:MainActivity,name:String):T=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(a) as T
    @Test fun expandedResultsUseViewportAndNameOpensScheduleWithoutRetuning() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        ReceiverTestPreferences(context).use {
            context.getSharedPreferences("rtlsdr_prefs",0).edit().putBoolean("receiverEnabled",false).commit()
            context.getSharedPreferences("radio_map",0).edit().putString("receiverLocationMode","manual").commit()
            val entries=(0 until 40).map { EtmController.Station(11620000+it*5000L,-40f,"AM") }
            context.getSharedPreferences("etm_memories",0).edit().putBoolean("detector_v2",true)
                .putString("SW",com.google.gson.Gson().toJson(entries)).commit()
            ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(
                "am start -W -n ${context.packageName}/${MainActivity::class.java.name}"
            )).use { it.readBytes() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                Thread.sleep(1800)
                var normal=0;var normalRows=0
                scenario.onActivity { a ->
                    val pager=field<ControlsPagerAdapter>(a,"pagerAdapter")
                    a.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.controlViewPager)
                        .setCurrentItem(pager.positionOf(ControlsPagerAdapter.TAB_SCAN),false)
                }
                Thread.sleep(350)
                scenario.onActivity { a ->
                    val page=field<ControlsPagerAdapter>(a,"pagerAdapter").getViewAt(ControlsPagerAdapter.TAB_SCAN)!!
                    val table=views(page.findViewById(R.id.etmContainer)).filterIsInstance<RadioDataTable>().single()
                    normal=table.height
                    val list=views(table).filterIsInstance<ListView>().single()
                    normalRows=list.lastVisiblePosition-list.firstVisiblePosition
                    MainActivity::class.java.getDeclaredMethod("setPanelFullscreen",Boolean::class.javaPrimitiveType).apply { isAccessible=true }.invoke(a,true)
                }
                Thread.sleep(800);inst.waitForIdleSync()
                inst.uiAutomation.takeScreenshot()?.let { image ->
                    java.io.File(context.cacheDir,"etm-expanded-check.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                }
                var before=0L
                scenario.onActivity { a ->
                    val page=field<ControlsPagerAdapter>(a,"pagerAdapter").getViewAt(ControlsPagerAdapter.TAB_SCAN)!!
                    val table=views(page.findViewById(R.id.etmContainer)).filterIsInstance<RadioDataTable>().single()
                    assertTrue("Expanded list must grow: ${table.height} / $normal",table.height>normal+100)
                    val list=views(table).filterIsInstance<ListView>().single()
                    assertTrue(list.lastVisiblePosition-list.firstVisiblePosition>normalRows)
                    assertEquals(40,list.count)
                    val points=EibiStationMatcher.sites(a.assets.open("eibi/README.TXT").use { it.readBytes().toString(Charsets.ISO_8859_1) })
                    assertTrue("Bundled transmitter coordinates parsed",points.size>100)
                    before=ViewModelProvider(a)[MainViewModel::class.java].centerFreqHz.value
                    // Actual name cell action; other cells retain the row's tune action.
                    (list.getChildAt(0) as ViewGroup).getChildAt(2).performClick()
                }
                Thread.sleep(400);inst.waitForIdleSync()
                scenario.onActivity { a ->
                    assertEquals(before,ViewModelProvider(a)[MainViewModel::class.java].centerFreqHz.value)
                    val pager=field<ControlsPagerAdapter>(a,"pagerAdapter")
                    assertEquals(ControlsPagerAdapter.TAB_EIBI,pager.moduleAt(a.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.controlViewPager).currentItem))
                    assertTrue(views(pager.getViewAt(ControlsPagerAdapter.TAB_EIBI)!!).filterIsInstance<TextView>().any { it.text.toString().contains("11620.0") })
                }
                inst.uiAutomation.takeScreenshot()?.let { image ->
                    java.io.File(context.cacheDir,"etm-eibi-check.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                }
            }
        }
    }
}
