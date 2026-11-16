package com.radiosport.ninegradio

import android.graphics.Bitmap
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.ui.*
import com.radiosport.ninegradio.usb.RtlSdrService
import com.radiosport.ninegradio.dsp.DemodMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class RecordingAndThemeTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private fun waitFor(message:String,test:()->Boolean) {
        val end=System.currentTimeMillis()+12000
        while(!test() && System.currentTimeMillis()<end) Thread.sleep(50)
        assertTrue(message,test())
    }
    private fun screen(name:String) {
        inst.uiAutomation.takeScreenshot()?.let { image ->
            File(context.cacheDir,name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
        }
    }
    @Test fun allSevenThemesKeepSettingsSeparateAndReadable() {
        ReceiverTestPreferences(context).use {
            Thread.sleep(3000)
            val prefs=PreferenceManager.getDefaultSharedPreferences(context)
            val expected=listOf(0xff071018,0xff0c2340,0xff171c17,0xff000000,0xff0d1117,0xff091521,0xff22252a)
            context.getSharedPreferences("rtlsdr_prefs",0).edit().putBoolean("receiverEnabled",false).commit()
            inst.uiAutomation.executeShellCommand("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${inst.targetContext.packageName}/com.radiosport.ninegradio.ui.MainActivity").close()
            Thread.sleep(1000)
            ActivityScenario.launch(MainActivity::class.java).use { main ->
              for((index,preset) in UiThemes.presets.withIndex()) {
                assertTrue("All presets must be dark",preset.dark)
                prefs.edit().putString("pref_ui_theme",preset.id).commit()
                main.recreate(); Thread.sleep(600)
                main.onActivity { a ->
                    assertEquals(expected[index].toInt(),UiThemes.color(a,R.attr.radioMain))
                    val tint=a.findViewById<com.google.android.material.tabs.TabLayout>(R.id.controlTabLayout).tabIconTint!!
                    assertEquals("Selected icons follow ${preset.id}",UiThemes.color(a,R.attr.radioAccent),tint.getColorForState(intArrayOf(android.R.attr.state_selected),0))
                    assertEquals("Inactive icons follow ${preset.id}",UiThemes.color(a,R.attr.radioSecondary),tint.defaultColor)
                    a.findViewById<View>(R.id.btnToolbarSettings).performClick()
                }
                Thread.sleep(500)
                inst.runOnMainSync {
                    val a=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).single() as SettingsActivity
                    assertEquals(expected[index].toInt(),UiThemes.color(a,R.attr.radioMain))
                    assertNull(a.findViewById<View>(R.id.controlViewPager))
                    val fragment=a.supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
                    assertNotNull(fragment.findPreference<androidx.preference.ListPreference>("pref_ui_theme"))
                    val foreground=UiThemes.color(a,R.attr.radioText)
                    assertTrue("Readable primary text in ${preset.id}",androidx.core.graphics.ColorUtils.calculateContrast(foreground,expected[index].toInt())>4.5)
                }
                screen("alpha17-theme-${preset.id}.png")
                inst.runOnMainSync {
                    val a=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).single() as SettingsActivity
                    val fragment=a.supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
                    fragment.onDisplayPreferenceDialog(fragment.findPreference<androidx.preference.ListPreference>("pref_ui_theme")!!)
                }
                Thread.sleep(300); screen("alpha17-theme-${preset.id}-picker.png")
                inst.runOnMainSync {
                    val a=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).single() as SettingsActivity
                    a.finish()
                }
                Thread.sleep(1100); screen("alpha17-theme-${preset.id}-main.png")
              }
              main.onActivity { a ->
                fun render(drawable:android.graphics.drawable.Drawable,name:String) {
                    val bitmap=Bitmap.createBitmap(324,324,Bitmap.Config.ARGB_8888)
                    drawable.setBounds(0,0,324,324);drawable.draw(android.graphics.Canvas(bitmap))
                    File(context.cacheDir,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                }
                val icon=a.getDrawable(R.mipmap.ic_launcher)!!
                render(icon,"alpha27-launcher.png")
                if(android.os.Build.VERSION.SDK_INT>=33) {
                    val monochrome=(icon as android.graphics.drawable.AdaptiveIconDrawable).monochrome
                    assertNotNull("Themed launcher artwork included",monochrome)
                    render(monochrome!!,"alpha27-launcher-mono.png")
                }
              }
            }
        }
    }

    @Test fun gatedIqAndWavAutoStopSurviveSecondarySettings() {
        ReceiverTestPreferences(context).use {
            val prefs=PreferenceManager.getDefaultSharedPreferences(context)
            val input=File(context.cacheDir,"recording-input.iq")
            val iq=File(context.cacheDir,"recording-gated.iq")
            val wav=File(context.cacheDir,"recording-gated.wav")
            // Deterministic AM carrier with 1 kHz modulation; no live RF needed.
            val buffer=ByteArray(4096*2) { i ->
                val n=i/2
                val envelope=0.5+0.1*kotlin.math.cos(2*Math.PI*1000*n/192000)
                val phase=2*Math.PI*12000*n/192000
                (127.5+100*envelope*(if(i%2==0) kotlin.math.cos(phase) else kotlin.math.sin(phase))).toInt().toByte()
            }
            input.outputStream().use { out -> repeat(1300) { out.write(buffer) } }
            var svc:RtlSdrService?=null
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    Thread.sleep(3000)
                    scenario.onActivity { a -> ViewModelProvider(a)[MainViewModel::class.java].setReceiverEnabled(false) }
                    waitFor("Bound service") { scenario.onActivity { svc=ViewModelProvider(it)[MainViewModel::class.java].getService() }; svc!=null }
                    waitFor("USB closed before file source") { svc!!.source==null }
                    svc!!.connectFile(input.path,192000,10_000_000,DemodMode.AM)
                    waitFor("Sample source connected") { svc!!.source?.capabilities?.id=="file" && svc!!.dspEngine!=null }
                    Thread.sleep(500)
                    val engine=svc!!.dspEngine!!
                    engine.setSquelchLevel(0f)
                    prefs.edit().putString("pref_record_stop_seconds","2").commit()
                    svc!!.setRecordingSquelch(true)
                    svc!!.startRecording(iq.path); svc!!.startAudioRecording(wav.path)
                    waitFor("Both recorders active") { engine.statsFlow.value.isRecordingIq && engine.statsFlow.value.isRecordingAudio }
                    Thread.sleep(400)
                    assertFalse("Closed gate",engine.recordingGateOpen)
                    assertEquals(0L,engine.iqRecordedDurationMs())
                    assertEquals(0L,engine.audioRecordedDurationMs())
                    engine.setSquelchLevel(-120f)
                    scenario.onActivity { it.findViewById<View>(R.id.btnToolbarSettings).performClick() }
                    Thread.sleep(300)
                    inst.runOnMainSync {
                        val settings=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).single()
                        assertTrue(settings is SettingsActivity)
                        prefs.edit().putString("pref_ui_theme","ocean").commit()
                        settings.recreate()
                    }
                    assertSame("Settings must keep the same DSP",engine,svc!!.dspEngine)
                    waitFor("Timed stop of both recordings") { !engine.statsFlow.value.isRecordingIq && !engine.statsFlow.value.isRecordingAudio }
                    // Audio capture may stop before its worker has finalized the container.
                    waitFor("Final metadata") { runBlocking {
                        val metas=(context.applicationContext as RtlSdrApplication).database.recordingMetaDao().getAll().first().filter { it.filePath in listOf(iq.path,wav.path) }
                        metas.size==2 && metas.all { it.durationMs in 100..1900 && it.fileSizeBytes==File(it.filePath).length() }
                    } }
                    assertNull("Audio recorder must finalize without error",engine.audioRecordingError())
                    assertTrue("Receiver remains running",svc!!.source?.isOpen()==true)
                    assertTrue(iq.length()>0)
                    assertTrue(wav.length()>44)
                    val header=ByteBuffer.wrap(wav.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                    assertEquals("RIFF",String(wav.readBytes().copyOfRange(0,4)))
                    assertEquals(wav.length()-8,header.getInt(4).toLong())
                    assertEquals(wav.length()-44,header.getInt(40).toLong())
                    inst.runOnMainSync {
                        androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<SettingsActivity>().forEach { it.finish() }
                    }
                }
            } finally {
                svc?.disconnectSource()
                runBlocking { svc?.awaitControls() }
                runBlocking {
                    val dao=(context.applicationContext as RtlSdrApplication).database.recordingMetaDao()
                    dao.getAll().first().filter { it.filePath in listOf(iq.path,wav.path) }.forEach { dao.delete(it) }
                }
                input.delete(); iq.delete(); wav.delete()
                com.radiosport.ninegradio.recording.IqRecordingInfo.metadataFile(iq).delete()
            }
        }
    }
}
