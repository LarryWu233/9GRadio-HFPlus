package com.radiosport.ninegradio

import android.os.SystemClock
import android.view.MotionEvent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GestureInteractionTest {
    @Test fun graphDragAndSpringTuneWithAutomaticModeFollowing() {
        ReceiverTestPreferences(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext).use {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var oldHz = 0L; var oldMode = DemodMode.AM; var oldEnabled = false; var oldAuto = false
            var spring: SpringTunerView? = null
            var afterRelease = 0L
            try {
                scenario.onActivity { activity ->
                    val vm = ViewModelProvider(activity)[MainViewModel::class.java]
                    oldHz = vm.centerFreqHz.value; oldMode = vm.demodMode.value
                    oldEnabled = vm.receiverEnabled.value; oldAuto = vm.autoDemod.value
                    vm.setReceiverEnabled(false); vm.setAutoDemod(false)
                    vm.setDemodMode(DemodMode.FM); vm.setFrequency(100000000)
                    val readout = activity.findViewById<FrequencyView>(R.id.frequencyView)
                    readout.setTuningStep(1000)
                    val plot = activity.findViewById<SpectrumView>(R.id.spectrumView)
                    val toggle = activity.findViewById<android.widget.TextView>(R.id.btnGraphGesture)
                    if (!toggle.text.startsWith("拖动")) toggle.performClick()
                    fun touch(action: Int, x: Float) {
                        val event = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, x, 40f, 0)
                        plot.dispatchTouchEvent(event); event.recycle()
                    }
                    touch(MotionEvent.ACTION_DOWN, plot.width * 0.7f)
                    touch(MotionEvent.ACTION_MOVE, plot.width * 0.4f)
                    touch(MotionEvent.ACTION_UP, plot.width * 0.4f)
                    assertTrue(vm.centerFreqHz.value > 100000000)
                    assertEquals(0L, vm.centerFreqHz.value % 1000)
                    val frequency = vm.centerFreqHz.value
                    // Manual selection recalls the mode's station; automatic following preserves the dial.
                    vm.setDemodMode(DemodMode.USB,automatic=true)
                    assertEquals(frequency, vm.centerFreqHz.value)
                    vm.setFrequency(100000000)
                    val field = MainActivity::class.java.getDeclaredField("pagerAdapter").apply { isAccessible = true }
                    val adapter = field.get(activity) as ControlsPagerAdapter
                    spring = adapter.getViewAt(ControlsPagerAdapter.TAB_TUNE)!!.findViewById(R.id.springTuner)
                    val control = spring!!
                    val event = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, control.width - 10f, control.height / 2f, 0)
                    control.dispatchTouchEvent(event); event.recycle()
                }
                Thread.sleep(250)
                scenario.onActivity { activity ->
                    val control = spring!!
                    val event = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, control.width - 10f, control.height / 2f, 0)
                    control.dispatchTouchEvent(event); event.recycle()
                    afterRelease = ViewModelProvider(activity)[MainViewModel::class.java].centerFreqHz.value
                    assertTrue(afterRelease > 100000000)
                }
                Thread.sleep(150)
                scenario.onActivity { activity -> assertEquals(afterRelease, ViewModelProvider(activity)[MainViewModel::class.java].centerFreqHz.value) }
            } finally {
                scenario.onActivity { activity ->
                    val vm = ViewModelProvider(activity)[MainViewModel::class.java]
                    vm.setDemodMode(oldMode); vm.setFrequency(oldHz); vm.setAutoDemod(oldAuto)
                    vm.setReceiverEnabled(oldEnabled); vm.finishTuning()
                }
            }
        }
        }
    }
}
