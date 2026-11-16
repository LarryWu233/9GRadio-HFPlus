package com.radiosport.ninegradio

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import org.hamcrest.Matchers.`is`
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.radiosport.ninegradio.dsp.*
import com.radiosport.ninegradio.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AmControlsUiTest {
    private fun views(v:View):List<View> = listOf(v)+(if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList())
    private fun adapter(a:MainActivity)=MainActivity::class.java.getDeclaredField("pagerAdapter").apply { isAccessible=true }.get(a) as ControlsPagerAdapter
    @Test fun detectorChoicesShareStationPreserveAudioWidthAndRestoreSettings() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        ReceiverTestPreferences(context).use {
            val prefs=context.getSharedPreferences("rtlsdr_prefs",0)
            prefs.edit().putBoolean("receiverEnabled",false).putString("activeDevice","rtl")
                .putString("device_rtl_mode","AM").putString("demod","AM")
                .putLong("device_rtl_freq",11920000).putBoolean("autoDemod",false)
                .putLong("tuningStep_v4_AM",1000).commit()
            AmPreferences.write(context,AmSettings())
            fun launch() {
                ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(
                    "am start -W -n ${context.packageName}/${MainActivity::class.java.name}"
                )).use { it.readBytes() }
            }
            fun settle() { Thread.sleep(450);inst.waitForIdleSync() }
            fun image(a:MainActivity,name:String) {
                val b=inst.uiAutomation.takeScreenshot() ?: error("No screenshot")
                File(context.cacheDir,name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle()
            }
            launch()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                Thread.sleep(1800)
                scenario.onActivity { a ->
                    a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    val vm=ViewModelProvider(a)[MainViewModel::class.java]
                    vm.setReceiverEnabled(false);vm.setAutoDemod(false);vm.setDemodMode(DemodMode.AM);vm.setIfBandwidth(10000)
                    val ad=adapter(a)
                    a.findViewById<ViewPager2>(R.id.controlViewPager).setCurrentItem(ad.positionOf(ControlsPagerAdapter.TAB_MODE),false)
                    BottomSheetBehavior.from(a.findViewById<View>(R.id.controlsBottomSheet)).state=BottomSheetBehavior.STATE_EXPANDED
                }
                settle()
                // Use the normal drawer-fullscreen gesture. ScrollTo only asks
                // the inner page ScrollView and cannot expose a clipped outer drawer.
                onView(withId(R.id.sheetToggle)).perform(swipeUp())
                settle()
                scenario.onActivity { a -> image(a,"arch18-am-before-scroll.png") }
                onView(withTagValue(`is`("ENVELOPE" as Any))).check(matches(isDisplayed()))
                for(detector in listOf(AmDetector.SAM_DSB,AmDetector.SAM_USB,AmDetector.SAM_LSB,AmDetector.ENVELOPE)) {
                    var frequency=0L;var step=0L
                    scenario.onActivity { a ->
                        val vm=ViewModelProvider(a)[MainViewModel::class.java]
                        frequency=vm.centerFreqHz.value;step=a.findViewById<FrequencyView>(R.id.frequencyView).tuningStepHz
                        val controls=adapter(a).getViewAt(ControlsPagerAdapter.TAB_MODE)!!.findViewById<View>(R.id.amDetectorHost)
                        val buttons=views(controls).filterIsInstance<Button>()
                        assertEquals(4,buttons.size)
                        val left=buttons[0].layoutParams as ViewGroup.MarginLayoutParams
                        val right=buttons[1].layoutParams as ViewGroup.MarginLayoutParams
                        assertTrue(left.marginEnd+right.marginStart>0)
                        buttons.single { it.tag==detector.name }.performClick()
                    }
                    settle()
                    scenario.onActivity { a ->
                        val vm=ViewModelProvider(a)[MainViewModel::class.java]
                        assertEquals(detector,vm.amSettings.value.detector)
                        assertEquals(frequency,vm.centerFreqHz.value)
                        assertEquals(step,a.findViewById<FrequencyView>(R.id.frequencyView).tuningStepHz)
                        assertEquals(if(detector.sideband==0) 10000 else 5000,vm.ifBandwidthHz.value)
                    }
                }
                scenario.onActivity { a -> image(a,"arch18-am-mode.png")
                    val ad=adapter(a);a.findViewById<ViewPager2>(R.id.controlViewPager).setCurrentItem(ad.positionOf(ControlsPagerAdapter.TAB_RF),false)
                }
                settle()
                scenario.onActivity { a ->
                    val host=adapter(a).getViewAt(ControlsPagerAdapter.TAB_RF)!!.findViewById<View>(R.id.amAudioHost)
                    val outer=a.findViewById<androidx.core.widget.NestedScrollView>(R.id.controlsScrollView)
                    val target=IntArray(2);val origin=IntArray(2)
                    host.getLocationInWindow(target);outer.getLocationInWindow(origin)
                    outer.scrollBy(0,target[1]-origin[1])
                }
                settle()
                scenario.onActivity { a ->
                    val audio=adapter(a).getViewAt(ControlsPagerAdapter.TAB_RF)!!.findViewById<View>(R.id.amAudioHost)
                    views(audio).filterIsInstance<MaterialSwitch>().single().isChecked=false
                }
                settle()
                onView(withContentDescription("AM 音频带宽")).check(matches(isDisplayed()))
                scenario.onActivity { a ->
                    val vm=ViewModelProvider(a)[MainViewModel::class.java]
                    vm.setAmSettings(vm.amSettings.value.copy(audioHz=2200,agc=AmAgc.FAST))
                }
                settle()
                scenario.onActivity { a ->
                    val audio=adapter(a).getViewAt(ControlsPagerAdapter.TAB_RF)!!.findViewById<View>(R.id.amAudioHost)
                    assertEquals(2200f,views(audio).filterIsInstance<Slider>().single().value,0f)
                    assertTrue(views(audio).filterIsInstance<Button>().single { it.text=="AM 高级设置" }.height>=48*a.resources.displayMetrics.density)
                    assertFalse(a.findViewById<MaterialSwitch>(R.id.switchAudioLeveling).isEnabled)
                    image(a,"arch18-am-audio.png")
                }
            }
            launch()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                settle()
                scenario.onActivity { a ->
                    val s=ViewModelProvider(a)[MainViewModel::class.java].amSettings.value
                    assertFalse(s.audioAuto);assertEquals(2200,s.audioHz);assertEquals(AmAgc.FAST,s.agc)
                }
            }
        }
    }
}
