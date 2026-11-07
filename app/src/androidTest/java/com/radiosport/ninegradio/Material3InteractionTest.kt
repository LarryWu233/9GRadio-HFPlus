package com.radiosport.ninegradio

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.slider.Slider
import com.radiosport.ninegradio.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class Material3InteractionTest {
    @Test fun jogRateIsProgressiveAndAlwaysStopsAfterReleaseCancelOrBackground() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        ReceiverTestPreferences(context).use {
            context.getSharedPreferences("rtlsdr_prefs",0).edit().putBoolean("receiverEnabled",false).commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                Thread.sleep(3000)
                lateinit var slider:Slider;lateinit var jog:SpringTunerView
                val ticks=AtomicLong();val finishes=AtomicLong()
                scenario.onActivity { a ->
                    ViewModelProvider(a)[MainViewModel::class.java].setReceiverEnabled(false)
                    val pager=a.findViewById<ViewPager2>(R.id.controlViewPager)
                    pager.setCurrentItem((pager.adapter as ControlsPagerAdapter).positionOf(ControlsPagerAdapter.TAB_TUNE),false)
                }
                Thread.sleep(500)
                scenario.onActivity { a ->
                    jog=a.findViewById(R.id.springTuner);slider=a.findViewById(R.id.springTunerInput)
                    jog.onSteps={ ticks.addAndGet(it) };jog.onFinished={ finishes.incrementAndGet() }
                }
                val xy=IntArray(2);val area=Rect()
                scenario.onActivity { assertTrue(slider.getGlobalVisibleRect(area));slider.getLocationOnScreen(xy) }
                val center=xy[0]+slider.width/2f;val y=area.exactCenterY()
                var down=0L
                fun send(action:Int,x:Float) { MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0).also { inst.sendPointerSync(it);it.recycle() } }
                fun move(position:Float) {
                    down=SystemClock.uptimeMillis();send(MotionEvent.ACTION_DOWN,center)
                    val target=center+position*slider.trackWidth/2f
                    repeat(12) { i -> send(MotionEvent.ACTION_MOVE,center+(target-center)*(i+1)/12);Thread.sleep(12) }
                }
                fun held(position:Float,cancel:Boolean=false):Long {
                    val before=ticks.get();move(position);Thread.sleep(600)
                    if(!cancel) inst.uiAutomation.takeScreenshot()?.let { b ->
                        File(context.cacheDir,"alpha23-jog-held.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle()
                    }
                    send(if(cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,center+position*slider.trackWidth/2f)
                    val stopped=ticks.get();Thread.sleep(650)
                    assertEquals("Motor must stop immediately even while grip animates home",stopped,ticks.get())
                    scenario.onActivity { assertEquals(0f,slider.value,0.01f) }
                    return stopped-before
                }
                val low=held(0.35f);val high=held(0.85f)
                assertTrue("Small deflection still tunes",low>0)
                assertTrue("Further deflection increases speed",high>low*2)
                assertTrue("Left deflection reverses tuning",held(-0.8f,true)<0)
                assertEquals("One finish per gesture",3L,finishes.get())
                move(0.85f);Thread.sleep(250)
                // Use the actual Home transition, without ActivityScenario's opaque bootstrap Activity.
                inst.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
                Thread.sleep(150)
                val stopped=ticks.get();Thread.sleep(350)
                assertEquals("Background cannot keep tuning",stopped,ticks.get())
                send(MotionEvent.ACTION_CANCEL,center)
                inst.uiAutomation.executeShellCommand("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.radiosport.ninegradio.hfplus/com.radiosport.ninegradio.ui.MainActivity").use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                }
                Thread.sleep(500)
                scenario.onActivity { assertEquals(0f,slider.value,0.01f) }
                inst.uiAutomation.takeScreenshot()?.let { b ->
                    File(context.cacheDir,"alpha23-tune.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle()
                }
            }
        }
    }
}
