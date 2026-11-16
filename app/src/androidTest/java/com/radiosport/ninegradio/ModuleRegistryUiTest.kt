package com.radiosport.ninegradio

import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.modules.RadioModules
import com.radiosport.ninegradio.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the registry's actual Views bindings, without opening a receiver. */
@RunWith(AndroidJUnit4::class)
class ModuleRegistryUiTest {
    private fun views(root:View):List<View> = listOf(root)+if(root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun <T> field(a:MainActivity,name:String):T = MainActivity::class.java.getDeclaredField(name)
        .apply { isAccessible=true }.get(a) as T
    @Test fun existingChoicesUseCapabilitiesAndSstvCannotOverwriteSatPreference() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        ReceiverTestPreferences(context).use {
            context.getSharedPreferences("rtlsdr_prefs",0).edit().putBoolean("receiverEnabled",false)
                .putString("activeDevice","airspyhf").putString("device_airspyhf_mode","AM")
                .putString("demod","AM").putBoolean("autoDemod",false).commit()
            val content=context.getSharedPreferences("audio_content",0)
            content.edit().putBoolean("sat_apt",true).putString("SSTV_sstv","Auto").commit()
            context.getSharedPreferences("radio_map",0).edit().putString("receiverLocationMode","manual").commit()
            ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(
                "am start -W -n ${context.packageName}/${MainActivity::class.java.name}"
            )).use { it.readBytes() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                Thread.sleep(1800);inst.waitForIdleSync()
                scenario.onActivity { a ->
                    val pager=field<ControlsPagerAdapter>(a,"pagerAdapter")
                    val mode=views(pager.getViewAt(ControlsPagerAdapter.TAB_MODE)!!)
                    val buttons=mode.filterIsInstance<Button>().filter { it.tag is DemodMode }
                    assertEquals(RadioModules.modeChoices.map { it.mode },buttons.map { it.tag })
                    val adsb=buttons.single { it.tag==DemodMode.ADSB }
                    assertFalse(adsb.isEnabled);assertTrue(adsb.tooltipText.toString().contains("1090"))
                    assertTrue(buttons.single { it.tag==DemodMode.DMR }.isEnabled)
                    assertEquals(listOf(16,17,18),mode.filterIsInstance<Button>().map { it.tag }.filterIsInstance<Int>().filter { it in 16..18 })
                    val controller=field<ContentModuleController>(a,"contentController")
                    controller.select(16)
                    val auto=views(pager.getViewAt(16)!!).filterIsInstance<Button>().single { it.text=="Auto" }
                    auto.performClick()
                }
                Thread.sleep(150)
                val root=inst.uiAutomation.rootInActiveWindow
                val auto=root.findAccessibilityNodeInfosByText("Auto").first { it.text?.toString()=="Auto" }
                assertTrue(auto.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                Thread.sleep(200);inst.waitForIdleSync()
                assertTrue("SSTV choices must preserve SAT's saved APT preference",content.getBoolean("sat_apt",false))
                scenario.onActivity { a ->
                    field<ContentModuleController>(a,"contentController").select(17)
                    val page=field<ControlsPagerAdapter>(a,"pagerAdapter").getViewAt(17)!!
                    assertTrue(views(page).filterIsInstance<Button>().any { it.text=="APT（历史）" })
                }
            }
        }
    }
}
