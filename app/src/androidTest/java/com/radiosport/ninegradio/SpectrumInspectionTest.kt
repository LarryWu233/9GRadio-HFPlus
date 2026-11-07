package com.radiosport.ninegradio

import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpectrumInspectionTest {
    @Test fun longPressInspectsWithoutRetuningInBothGestureModes() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        ReceiverTestPreferences(context).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { a -> ViewModelProvider(a)[MainViewModel::class.java].apply {
                    setReceiverEnabled(false); setAutoDemod(false); setDemodMode(DemodMode.AM); setFrequency(11920000)
                } }
                Thread.sleep(700)
                var down=0L
                fun touch(action: Int,x: Float=0.82f,y: Float=0.3f) {
                    scenario.onActivity { a ->
                        val plot=a.findViewById<SpectrumView>(R.id.spectrumView)
                        if(action==MotionEvent.ACTION_DOWN) down=SystemClock.uptimeMillis()
                        MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x*plot.width,y*plot.height,0).also {
                            plot.dispatchTouchEvent(it); it.recycle()
                        }
                    }
                }
                repeat(2) {
                    var before=0L; var bw=0
                    scenario.onActivity { a ->
                        a.findViewById<SpectrumView>(R.id.spectrumView).setCrosshairEnabled(true)
                        val vm=ViewModelProvider(a)[MainViewModel::class.java]
                        before=vm.centerFreqHz.value; bw=vm.ifBandwidthHz.value
                    }
                    touch(MotionEvent.ACTION_DOWN)
                    Thread.sleep(ViewConfiguration.getLongPressTimeout()+180L)
                    var cursorHz=0L
                    scenario.onActivity { a ->
                        val plot=a.findViewById<SpectrumView>(R.id.spectrumView)
                        val readout=plot.inspection()!!
                        // Cursor stores a normalized Float coordinate; the round trip can differ by 1 Hz.
                        assertTrue(kotlin.math.abs(plot.frequencyAtPixel(plot.width*0.82f)-readout.frequencyHz)<=1L)
                        assertTrue(readout.levelDbfs.isFinite()); cursorHz=readout.frequencyHz
                        val image=android.graphics.Bitmap.createBitmap(plot.width,plot.height,android.graphics.Bitmap.Config.ARGB_8888)
                        plot.draw(android.graphics.Canvas(image))
                        java.io.File(a.cacheDir,"alpha13-crosshair.png").outputStream().use { out -> image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out) }
                        image.recycle()
                    }
                    touch(MotionEvent.ACTION_MOVE,0.66f,0.55f)
                    scenario.onActivity { a ->
                        assertNotEquals(cursorHz,a.findViewById<SpectrumView>(R.id.spectrumView).inspection()!!.frequencyHz)
                        val vm=ViewModelProvider(a)[MainViewModel::class.java]
                        assertEquals(before,vm.centerFreqHz.value); assertEquals(bw,vm.ifBandwidthHz.value)
                    }
                    touch(MotionEvent.ACTION_UP,0.66f,0.55f)
                    scenario.onActivity { a ->
                        assertNull(a.findViewById<SpectrumView>(R.id.spectrumView).inspection())
                        assertEquals(before,ViewModelProvider(a)[MainViewModel::class.java].centerFreqHz.value)
                        a.findViewById<TextView>(R.id.btnGraphGesture).performClick()
                    }
                }
                scenario.onActivity { it.findViewById<SpectrumView>(R.id.spectrumView).setCrosshairEnabled(false) }
                touch(MotionEvent.ACTION_DOWN)
                Thread.sleep(ViewConfiguration.getLongPressTimeout()+180L)
                scenario.onActivity { assertNull(it.findViewById<SpectrumView>(R.id.spectrumView).inspection()) }
                touch(MotionEvent.ACTION_CANCEL)
            }
        }
    }
}
