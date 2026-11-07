package com.radiosport.ninegradio

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.recording.*
import com.radiosport.ninegradio.ui.MainActivity
import com.radiosport.ninegradio.ui.MainViewModel
import com.radiosport.ninegradio.usb.RtlSdrService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecordingCaptureIntegrationTest {
    private fun waitFor(message:String,test:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+12000
        while(SystemClock.elapsedRealtime()<deadline) { if(test()) return;Thread.sleep(50) }
        assertTrue(message,test())
    }
    @Test fun compressedCaptureGatesAutoStopsAndSurvivesPlaybackMute() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        ReceiverTestPreferences(context).use {
            val prefs=PreferenceManager.getDefaultSharedPreferences(context)
            context.getSharedPreferences("rtlsdr_prefs",0).edit().putBoolean("receiverEnabled",false).commit()
            prefs.edit().putString("pref_record_stop_seconds","3").commit()
            val input=File(context.cacheDir,"compressed-capture-input.iq")
            val output=File(context.cacheDir,"compressed-capture-output.m4a")
            val chunk=ByteArray(8192) { i ->
                val n=i/2;val envelope=0.5+0.1*kotlin.math.cos(2*Math.PI*1000*n/192000)
                val phase=2*Math.PI*12000*n/192000
                (127.5+100*envelope*(if(i%2==0) kotlin.math.cos(phase) else kotlin.math.sin(phase))).toInt().toByte()
            }
            input.outputStream().use { stream -> repeat(1300) { stream.write(chunk) } }
            var svc:RtlSdrService?=null
            val dao=(context.applicationContext as RtlSdrApplication).database.recordingMetaDao()
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    Thread.sleep(3000) // Let the explicit MIUI launcher and initial UI settings bind finish.
                    waitFor("Bound radio service") { scenario.onActivity { svc=ViewModelProvider(it)[MainViewModel::class.java].getService() };svc!=null }
                    waitFor("Old source closed") { svc!!.source==null }
                    svc!!.connectFile(input.path,192000,10_000_000,DemodMode.AM)
                    waitFor("IQ file running") { svc!!.source?.capabilities?.id=="file" && svc!!.dspEngine!=null }
                    val engine=svc!!.dspEngine!!
                    scenario.onActivity { ViewModelProvider(it)[MainViewModel::class.java].setSquelch(0f) }
                    svc!!.setRecordingSquelch(true)
                    Thread.sleep(400)
                    assertFalse("Closed gate before starting encoder",engine.recordingGateOpen)
                    svc!!.startAudioRecording(output.path,AudioRecordingProfile())
                    waitFor("Compressed recorder starts") { engine.isAudioRecording() }
                    Thread.sleep(400)
                    assertEquals("SQL gates compressed PCM",0L,engine.audioRecordedDurationMs())
                    engine.setSquelchLevel(-120f)
                    waitFor("Recording starts through open SQL") { engine.audioRecordedDurationMs()>200 }
                    svc!!.setRecordingPlaybackMuted(true)
                    val before=engine.audioRecordedDurationMs();Thread.sleep(500)
                    assertTrue("Playback mute affects speaker, not capture",engine.audioRecordedDurationMs()>before+250)
                    svc!!.setRecordingPlaybackMuted(false)
                    waitFor("Timed stop finalizes compressed file") { !engine.isAudioRecording() }
                    waitFor("Final metadata") { runBlocking { dao.getAll().first().any { it.filePath==output.path && it.durationMs in 1000..3000 && it.fileSizeBytes==output.length() } } }
                    val meta=runBlocking { dao.getAll().first().first { it.filePath==output.path } }
                    assertTrue(output.length()>1000);assertEquals(48000,meta.sampleRate)
                    val retriever=android.media.MediaMetadataRetriever()
                    try { retriever.setDataSource(output.path)
                        val duration=retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                        assertTrue(kotlin.math.abs(duration-meta.durationMs)<150)
                    } finally { retriever.release() }
                    assertTrue("Receiver stays active after auto stop",svc!!.source!!.isOpen())
                }
            } finally {
                svc?.setRecordingPlaybackMuted(false);svc?.disconnectSource();runBlocking { svc?.awaitControls() }
                runBlocking { dao.getAll().first().filter { it.filePath==output.path }.forEach { dao.delete(it) } }
                input.delete();output.delete()
            }
        }
    }
}
