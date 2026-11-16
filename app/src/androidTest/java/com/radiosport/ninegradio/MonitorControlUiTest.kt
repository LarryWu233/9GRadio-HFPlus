package com.radiosport.ninegradio

import android.os.ParcelFileDescriptor
import android.widget.ImageButton
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.ui.MainActivity
import com.radiosport.ninegradio.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonitorControlUiTest {
    @Test fun dataControlIsDisabledAndVoiceChoiceSurvivesModeAndActivityRestore() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        ReceiverTestPreferences(context).use {
            val prefs=context.getSharedPreferences("rtlsdr_prefs",0)
            prefs.edit().putBoolean("receiverEnabled",false).putString("activeDevice","rtl")
                .putString("device_rtl_mode","AM").putString("demod","AM").putBoolean("monitor_AM",false).commit()
            context.getSharedPreferences("radio_map",0).edit().putString("receiverLocationMode","manual").commit()
            ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(
                "am start -W -n ${context.packageName}/${MainActivity::class.java.name}"
            )).use { it.readBytes() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun settle() { Thread.sleep(250);inst.waitForIdleSync() }
                settle()
                scenario.onActivity { a ->
                    val button=a.findViewById<ImageButton>(R.id.btnMonitor)
                    assertTrue(button.isEnabled);assertEquals("开启监听",button.contentDescription)
                    button.performClick()
                }
                settle();assertTrue(prefs.getBoolean("monitor_AM",false))
                scenario.onActivity { a ->
                    a.findViewById<ImageButton>(R.id.btnMonitor).performClick()
                    ViewModelProvider(a)[MainViewModel::class.java].setDemodMode(DemodMode.APRS)
                }
                settle()
                scenario.onActivity { a ->
                    val button=a.findViewById<ImageButton>(R.id.btnMonitor)
                    assertFalse(button.isEnabled);assertEquals("纯数据模式，监听已关闭",button.contentDescription)
                    button.performClick()
                    assertFalse(ViewModelProvider(a)[MainViewModel::class.java].monitorEnabled.value)
                    ViewModelProvider(a)[MainViewModel::class.java].setDemodMode(DemodMode.AM)
                }
                settle()
                scenario.onActivity { a ->
                    val button=a.findViewById<ImageButton>(R.id.btnMonitor)
                    assertTrue(button.isEnabled);assertEquals("开启监听",button.contentDescription)
                }
                assertFalse(prefs.getBoolean("monitor_AM",true))
            }
            // MIUI can reject instrumentation-owned Activity.recreate launches.
            // A fresh shell-foreground launch also verifies persisted state after VM disposal.
            ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(
                "am start -W -n ${context.packageName}/${MainActivity::class.java.name}"
            )).use { it.readBytes() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                Thread.sleep(250)
                scenario.onActivity { a ->
                    val button=a.findViewById<ImageButton>(R.id.btnMonitor)
                    assertTrue(button.isEnabled);assertEquals("开启监听",button.contentDescription)
                }
            }
        }
    }
}
