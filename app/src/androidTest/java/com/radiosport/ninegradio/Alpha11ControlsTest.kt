package com.radiosport.ninegradio

import android.view.View
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.google.android.material.switchmaterial.SwitchMaterial
import com.radiosport.ninegradio.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Alpha11ControlsTest {
    private fun capture(view: View,name: String) {
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(view.context.cacheDir,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    @Test fun inlineStepAndAudioControlsAndRectangularSettings() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        ReceiverTestPreferences(context).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: MainViewModel
                lateinit var adapter: ControlsPagerAdapter
                var oldPower=true; var oldStep=1000L
                var oldAudio=com.radiosport.ninegradio.dsp.ListeningAudioProcessor.Settings()
                fun select(a: MainActivity,id: Int) {
                    a.findViewById<TabLayout>(R.id.controlTabLayout).getTabAt(adapter.positionOf(id))!!.select()
                }
                try {
                    Thread.sleep(700)
                    scenario.onActivity { a ->
                        model=ViewModelProvider(a)[MainViewModel::class.java]
                        adapter=MainActivity::class.java.getDeclaredField("pagerAdapter").apply { isAccessible=true }.get(a) as ControlsPagerAdapter
                        oldPower=model.receiverEnabled.value; oldAudio=model.listeningAudio.value
                        model.setReceiverEnabled(false)
                        val frequency=a.findViewById<FrequencyView>(R.id.frequencyView)
                        oldStep=frequency.tuningStepHz
                        frequency.setTuningStep(1000)
                        val hz=model.centerFreqHz.value
                        a.findViewById<View>(R.id.btnStepUp).performClick()
                        assertTrue(frequency.tuningStepHz>1000)
                        a.findViewById<View>(R.id.btnStepDown).performClick()
                        assertEquals(1000L,frequency.tuningStepHz)
                        assertEquals(hz,model.centerFreqHz.value)
                        assertTrue(a.findViewById<TextView>(R.id.tvTuningStep).text.toString().startsWith("Step\n"))
                        assertTrue(a.findViewById<TextView>(R.id.tvPlotZoom).text.toString().startsWith("显示宽度\n"))
                        select(a,ControlsPagerAdapter.TAB_RF)
                    }
                    Thread.sleep(700)
                    scenario.onActivity { a ->
                        val level=a.findViewById<SwitchMaterial>(R.id.switchAudioLeveling)
                        val notch=a.findViewById<SwitchMaterial>(R.id.switchAudioNotch)
                        if(level.isChecked) level.performClick()
                        level.performClick()
                        assertTrue(model.listeningAudio.value.leveling)
                        if(notch.isChecked) notch.performClick()
                        notch.performClick()
                        assertTrue(model.listeningAudio.value.notch)
                        capture(a.findViewById<View>(R.id.listeningAudioControls),"alpha11-audio-controls.png")
                        capture(a.findViewById<View>(R.id.plotTuningBar),"alpha11-plot-tuning-bar.png")
                        select(a,ControlsPagerAdapter.TAB_SETTINGS)
                    }
                    Thread.sleep(700)
                    scenario.onActivity { a ->
                        val ids=listOf(R.id.btnBookmarks,R.id.btnScanner,R.id.btnDebugPanel,R.id.btnDeviceInfo,
                            R.id.btnOpenSettings,R.id.btnManageModules,R.id.btnRadioMap)
                        val buttons=ids.map { a.findViewById<AppCompatButton>(it) }
                        assertTrue(buttons.all { it.width==buttons.first().width && it.height==buttons.first().height })
                        capture(adapter.getViewAt(ControlsPagerAdapter.TAB_SETTINGS)!!,"alpha11-settings-controls.png")
                    }
                } finally {
                    scenario.onActivity { a ->
                        model.setListeningAudio(oldAudio)
                        a.findViewById<FrequencyView>(R.id.frequencyView).setTuningStep(oldStep)
                        model.setReceiverEnabled(oldPower)
                    }
                }
            }
        }
    }
}
