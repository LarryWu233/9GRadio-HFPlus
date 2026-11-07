package com.radiosport.ninegradio

import android.view.View
import android.widget.Switch
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.radiosport.ninegradio.dsp.*
import com.radiosport.ninegradio.ui.*
import com.radiosport.ninegradio.source.RtlTcpSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AnalogNoisePolicyUiTest {
    private fun capture(view: View,name: String) {
        val content=if(view is android.widget.ScrollView) view.getChildAt(0) else view
        val bitmap=android.graphics.Bitmap.createBitmap(content.width,content.height,android.graphics.Bitmap.Config.ARGB_8888)
        content.draw(android.graphics.Canvas(bitmap))
        File(view.context.cacheDir,name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    @Test fun digitalControlsTurnOffAndAnalogChoicesReturn() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        ReceiverTestPreferences(context).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: MainViewModel
                lateinit var adapter: ControlsPagerAdapter
                var oldMode=DemodMode.AM; var oldFrequency=11916000L; var oldPower=true; var oldAuto=false
                var oldNb=false; var oldNr=false; var oldThreshold=8f
                var oldAudio=ListeningAudioProcessor.Settings()
                try {
                    Thread.sleep(700)
                    scenario.onActivity { a ->
                        model=ViewModelProvider(a)[MainViewModel::class.java]
                        oldMode=model.demodMode.value; oldFrequency=model.centerFreqHz.value; oldPower=model.receiverEnabled.value
                        oldAuto=model.autoDemod.value; oldNb=model.noiseBlankerEnabled.value; oldNr=model.noiseReducerEnabled.value
                        oldAudio=model.listeningAudio.value; oldThreshold=model.iqNbThreshold.value
                        model.setReceiverEnabled(false); model.setAutoDemod(false)
                        model.setDemodMode(DemodMode.AM); model.setNoiseBlanker(true); model.setNoiseReducer(true)
                        model.setIqNoiseBlankerThreshold(9f)
                        model.setListeningAudio(ListeningAudioProcessor.Settings(leveling=true,notch=true))
                        adapter=MainActivity::class.java.getDeclaredField("pagerAdapter").apply { isAccessible=true }.get(a) as ControlsPagerAdapter
                        a.findViewById<TabLayout>(R.id.controlTabLayout).getTabAt(adapter.positionOf(ControlsPagerAdapter.TAB_RF))!!.select()
                    }
                    Thread.sleep(700)
                    for(mode in listOf(DemodMode.APRS,DemodMode.ACARS,DemodMode.DMR,DemodMode.CW,DemodMode.RAW)) {
                        scenario.onActivity { model.setDemodMode(mode) }
                        Thread.sleep(300)
                        scenario.onActivity { a ->
                            assertEquals(mode,model.demodMode.value)
                            for(id in listOf(R.id.switchNoiseBlanker,R.id.switchNoiseReducer)) {
                                val control=a.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(id)
                                assertFalse(mode.name,control.isEnabled); assertFalse(mode.name,control.isChecked)
                            }
                            for(id in listOf(R.id.switchAudioLeveling,R.id.switchAudioNotch)) {
                                val control=a.findViewById<MaterialSwitch>(id)
                                assertFalse(mode.name,control.isEnabled); assertFalse(mode.name,control.isChecked)
                            }
                            assertEquals(View.VISIBLE,a.findViewById<View>(R.id.tvNoiseProcessingState).visibility)
                            assertTrue(model.noiseBlankerEnabled.value && model.noiseReducerEnabled.value)
                            if(mode==DemodMode.APRS) capture(adapter.getViewAt(ControlsPagerAdapter.TAB_RF)!!,"alpha12-digital-noise-controls.png")
                        }
                    }
                    scenario.onActivity { model.setDemodMode(DemodMode.USB) }
                    Thread.sleep(400)
                    scenario.onActivity { a ->
                        for(id in listOf(R.id.switchNoiseBlanker,R.id.switchNoiseReducer)) {
                            val control=a.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(id)
                            assertTrue(control.isEnabled); assertTrue(control.isChecked)
                        }
                        assertEquals(9f,model.iqNbThreshold.value,0f)
                        assertTrue(a.findViewById<MaterialSwitch>(R.id.switchAudioNotch).isChecked)
                        assertTrue(a.findViewById<MaterialSwitch>(R.id.switchAudioLeveling).isChecked)
                        capture(adapter.getViewAt(ControlsPagerAdapter.TAB_RF)!!,"alpha12-analog-noise-controls.png")
                    }
                } finally {
                    scenario.onActivity {
                        model.setNoiseBlanker(oldNb); model.setNoiseReducer(oldNr); model.setIqNoiseBlankerThreshold(oldThreshold)
                        model.setListeningAudio(oldAudio); model.setDemodMode(oldMode); model.setFrequency(oldFrequency); model.finishTuning()
                        model.setAutoDemod(oldAuto); model.setReceiverEnabled(oldPower)
                    }
                }
            }
        }
    }
    @Test fun engineAlwaysBypassesDigitalModesEvenIfSavedFlagsAreOn() {
        val engine=DspEngine(RtlTcpSource("127.0.0.1",0))
        try {
            engine.setNoiseBlankerEnabled(true); engine.setNoiseReducerEnabled(true)
            for(mode in DemodMode.values()) {
                engine.setDemodMode(mode)
                assertEquals(mode.name,NoiseProcessingPolicy.allows(mode),engine.isNoiseBlankerEnabled())
                assertEquals(mode.name,NoiseProcessingPolicy.allows(mode),engine.isNoiseReducerEnabled())
            }
        } finally { engine.stop() }
    }
}
