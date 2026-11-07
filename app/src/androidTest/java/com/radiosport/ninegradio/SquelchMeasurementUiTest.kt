package com.radiosport.ninegradio

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.ui.*
import com.google.android.material.tabs.TabLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class SquelchMeasurementUiTest {
    @Test fun measurementSuggestsWithoutChangingSqlUntilAppliedAndCancelsOnChange() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        ReceiverTestPreferences(context).use {
            val file=File(context.cacheDir,"sql-measurement.cf32")
            val random=java.util.Random(57)
            val block=ByteBuffer.allocate(2400*8).order(ByteOrder.LITTLE_ENDIAN)
            repeat(2400) { i ->
                val t=i/240000.0
                block.putFloat((random.nextGaussian()*0.0003+0.1*cos(2*PI*60000*t)).toFloat())
                block.putFloat((random.nextGaussian()*0.0003+0.1*sin(2*PI*60000*t)).toFloat())
            }
            file.outputStream().buffered().use { out -> repeat(2500) { out.write(block.array()) } }
            val prefs=context.getSharedPreferences("rtlsdr_prefs",Context.MODE_PRIVATE)
            prefs.edit().putBoolean("receiverEnabled",false).putBoolean("autoDemod",false)
                .putInt("device_file_width",10000).putInt("fftDecimation",1).putInt("fftSize",2048).commit()
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString("pref_frame_averaging","1").putString("pref_decimation","1").commit()
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    Thread.sleep(1600)
                    scenario.onActivity { activity ->
                        val model=ViewModelProvider(activity)[MainViewModel::class.java]
                        model.setReceiverEnabled(true);model.setAutoDemod(false);model.setVolume(0f)
                        model.getService()!!.connectFile(file.absolutePath,240000,125000000,DemodMode.AM)
                    }
                    Thread.sleep(1400)
                    scenario.onActivity { activity ->
                        val model=ViewModelProvider(activity)[MainViewModel::class.java]
                        model.setSquelch(-80f);model.setIfBandwidth(10000)
                        val pager=activity.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.controlViewPager)
                        val adapter=pager.adapter as ControlsPagerAdapter
                        activity.findViewById<TabLayout>(R.id.controlTabLayout).getTabAt(adapter.positionOf(ControlsPagerAdapter.TAB_RF))!!.select()
                    }
                    Thread.sleep(350)
                    fun clickMeasure() = scenario.onActivity { activity ->
                        val button=activity.findViewById<Button>(R.id.btnMeasureSquelch)
                        assertTrue(button.isEnabled);button.performClick()
                    }
                    fun proposal():Float {
                        repeat(20) {
                            val nodes=inst.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("建议") ?: emptyList()
                            val text=nodes.mapNotNull { it.text?.toString() }.firstOrNull { Regex("建议 (-?\\d+) dBFS").containsMatchIn(it) }
                            if(text!=null) return Regex("建议 (-?\\d+) dBFS").find(text)!!.groupValues[1].toFloat()
                            Thread.sleep(100)
                        }
                        var state=""
                        scenario.onActivity {
                            val model=ViewModelProvider(it)[MainViewModel::class.java]
                            val button=it.findViewById<Button>(R.id.btnMeasureSquelch)
                            state="button=${button.text}, shown=${button.isShown}, power=${model.receiverEnabled.value}, status=${model.deviceStatus.value}, reading=${model.getService()?.dspEngine?.squelchReading?.value}"
                        }
                        fail("Recommendation dialog expected: $state")
                        return Float.NaN
                    }
                    fun action(text:String) {
                        val node=inst.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(text).first { it.text?.toString()==text }
                        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(200)
                    }
                    clickMeasure();Thread.sleep(3600);proposal()
                    scenario.onActivity { assertEquals(-80f,ViewModelProvider(it)[MainViewModel::class.java].squelch.value,0f) }
                    action("取消")
                    clickMeasure();Thread.sleep(3600)
                    val recommended=proposal()
                    inst.uiAutomation.takeScreenshot()?.let { image ->
                        File(context.cacheDir,"alpha20-sql-proposal.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
                    }
                    action("应用")
                    scenario.onActivity { assertEquals(recommended,ViewModelProvider(it)[MainViewModel::class.java].squelch.value,0f) }
                    clickMeasure();Thread.sleep(150)
                    scenario.onActivity { ViewModelProvider(it)[MainViewModel::class.java].setIfBandwidth(15000) }
                    Thread.sleep(500)
                    scenario.onActivity {
                        assertEquals("测量（实验）",it.findViewById<Button>(R.id.btnMeasureSquelch).text.toString())
                        assertEquals(recommended,ViewModelProvider(it)[MainViewModel::class.java].squelch.value,0f)
                    }
                    clickMeasure();scenario.moveToState(Lifecycle.State.CREATED);Thread.sleep(300)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    scenario.onActivity {
                        assertEquals(recommended,ViewModelProvider(it)[MainViewModel::class.java].squelch.value,0f)
                        ViewModelProvider(it)[MainViewModel::class.java].setReceiverEnabled(false)
                    }
                }
            } finally { file.delete() }
        }
    }
}
