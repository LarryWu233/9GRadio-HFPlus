package com.radiosport.ninegradio

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.*
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.*
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.slider.Slider
import com.radiosport.ninegradio.audio.PcmRecorder
import com.radiosport.ninegradio.audio.AudioFileRecorder
import com.radiosport.ninegradio.recording.*
import com.radiosport.ninegradio.data.RecordingMeta
import com.radiosport.ninegradio.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sin

@androidx.media3.common.util.UnstableApi
@RunWith(AndroidJUnit4::class)
class RecordingPlayerPanelTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private fun waitFor(message:String,test:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+12000
        while(SystemClock.elapsedRealtime()<deadline) { if(test()) return;Thread.sleep(100) }
        assertTrue(message,test())
    }
    private fun controls(a:MainActivity)=MainActivity::class.java.getDeclaredField("recordingPlayer").let { it.isAccessible=true;it.get(a) as RecordingPlayerControls }
    private fun screenshot(name:String) { inst.uiAutomation.takeScreenshot()?.let { b -> File(context.cacheDir,name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle() } }
    private fun touch(view:View,from:Float,to:Float,hold:Long=0) {
        val xy=IntArray(2);inst.runOnMainSync { view.getLocationOnScreen(xy) }
        val x=xy[0]+view.width*from;val y=xy[1]+view.height/2f;val start=SystemClock.uptimeMillis()
        fun event(action:Int,at:Float) { MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,at,y,0).also { inst.sendPointerSync(it);it.recycle() } }
        event(MotionEvent.ACTION_DOWN,x)
        if(hold>0) Thread.sleep(hold) else repeat(12) { i -> event(MotionEvent.ACTION_MOVE,x+(xy[0]+view.width*to-x)*(i+1)/12);Thread.sleep(16) }
        event(MotionEvent.ACTION_UP,xy[0]+view.width*to)
    }
    @Test fun allFiveOutputProfilesPlayAndSeekInMedia3() {
        ReceiverTestPreferences(context).use {
            context.getSharedPreferences("rtlsdr_prefs",0).edit().putBoolean("receiverEnabled",false).commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                Thread.sleep(2200)
                scenario.onActivity { a ->
                    val pager=a.findViewById<ViewPager2>(R.id.controlViewPager)
                    pager.setCurrentItem((pager.adapter as ControlsPagerAdapter).positionOf(ControlsPagerAdapter.TAB_RECORDING),false)
                }
                Thread.sleep(500)
                for(profile in listOf(AudioRecordingProfile(),AudioRecordingProfile(AudioRecordingFormat.OPUS))+
                    listOf(16,24,32).map { AudioRecordingProfile(AudioRecordingFormat.WAV,it) }) {
                    val file=File(context.cacheDir,"player-profile-${profile.format}-${profile.bitDepth}.${profile.format.extension}")
                    try {
                        val writer=AudioFileRecorder(file.path,48000,2,profile);writer.start()
                        val data=FloatArray(2048*2) { (0.1*sin(2*Math.PI*1000*(it/2)/48000)).toFloat() }
                        repeat(94) { writer.writeSamples(data) };writer.stop();assertNull(writer.error)
                        val meta=RecordingMeta(filePath=file.path,type="AUDIO",frequencyHz=10_000_000,sampleRate=48000,demodMode="AM",durationMs=4000,fileSizeBytes=file.length())
                        scenario.onActivity { controls(it).play(meta) }
                        waitFor("${profile.summary()} plays") { var ok=false;scenario.onActivity { ok=controls(it).controller?.isPlaying==true };ok }
                        scenario.onActivity { a -> val p=controls(a).controller!!;assertNull(p.playerError);assertTrue(p.duration in 3800..4200);p.pause();p.seekTo(2000) }
                        Thread.sleep(250)
                        scenario.onActivity { a -> val p=controls(a).controller!!;assertTrue(kotlin.math.abs(p.currentPosition-2000)<100);controls(a).stopPlayback() }
                    } finally { file.delete() }
                }
            }
        }
    }
    @Test fun playerSeeksPausesExpandsAndCollapsedBodyStaysHidden() {
        ReceiverTestPreferences(context).use {
            context.getSharedPreferences("rtlsdr_prefs",0).edit().putBoolean("receiverEnabled",false).commit()
            val file=File(context.cacheDir,"panel-player-fixture.wav")
            val dao=(context.applicationContext as RtlSdrApplication).database.recordingMetaDao()
            val writer=PcmRecorder(file.path,48000,1)
            writer.start();val block=FloatArray(48000) { (0.15*sin(2*Math.PI*1000*it/48000)).toFloat() }
            repeat(60) { writer.writeSamples(block) };writer.stop()
            val meta=RecordingMeta(filePath=file.path,type="AUDIO",frequencyHz=10_000_000,sampleRate=48000,demodMode="AM",durationMs=60000,fileSizeBytes=file.length())
            val id=runBlocking { dao.insert(meta) }.toInt()
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    Thread.sleep(2200)
                    scenario.onActivity { a ->
                        ViewModelProvider(a)[MainViewModel::class.java].setReceiverEnabled(false)
                        val pager=a.findViewById<ViewPager2>(R.id.controlViewPager)
                        pager.setCurrentItem((pager.adapter as ControlsPagerAdapter).positionOf(ControlsPagerAdapter.TAB_RECORDING),false)
                    }
                    Thread.sleep(900)
                    var player:RecordingPlayerControls?=null
                    waitFor("Recorder page initialized") { scenario.onActivity { a -> player=runCatching { controls(a) }.getOrNull() };player!=null }
                    var row:View?=null
                    waitFor("Fixture row appears with duration") { scenario.onActivity { a ->
                        val rv=a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerRecordingsTab)
                        row=rv?.findViewHolderForAdapterPosition(0)?.itemView
                    };row?.findViewById<TextView>(R.id.tvRecDuration)?.text=="01:00" }
                    scenario.onActivity { row!!.findViewById<View>(R.id.btnRecPlay).performClick() }
                    waitFor("Media3 playback running") { var ok=false;scenario.onActivity { ok=player!!.controller?.isPlaying==true };ok }
                    var paused=0L
                    scenario.onActivity { a -> a.findViewById<View>(R.id.recordingPlaybackPlay).performClick();paused=player!!.controller!!.currentPosition }
                    Thread.sleep(500)
                    scenario.onActivity { assertFalse(player!!.controller!!.isPlaying);assertTrue(kotlin.math.abs(player!!.controller!!.currentPosition-paused)<100) }
                    var slider:Slider?=null
                    scenario.onActivity { a -> slider=a.findViewById(R.id.recordingPlaybackProgress) }
                    touch(slider!!,0.4f,0.55f)
                    Thread.sleep(300)
                    scenario.onActivity { assertTrue("Drag seeks into middle",player!!.controller!!.currentPosition in 25000..40000) }
                    var forward:View?=null
                    scenario.onActivity { a -> forward=a.findViewById(R.id.recordingPlaybackForward);player!!.controller!!.seekTo(0) }
                    touch(forward!!,0.5f,0.5f,1500)
                    Thread.sleep(250)
                    scenario.onActivity { a ->
                        assertTrue("Long hold repeats seek",player!!.controller!!.currentPosition in 12000..30000)
                        a.findViewById<View>(R.id.recordingPlaybackExpand).performClick()
                    }
                    Thread.sleep(700)
                    scenario.onActivity { a ->
                        val sheet=a.findViewById<View>(R.id.controlsBottomSheet);val freq=a.findViewById<View>(R.id.frequencyView)
                        val sr=Rect();val fr=Rect();sheet.getGlobalVisibleRect(sr);freq.getGlobalVisibleRect(fr)
                        assertTrue("Expanded recording stays below frequency",sr.top>=fr.bottom-2 && sr.top<=fr.bottom+4)
                        assertEquals(Player.STATE_READY,player!!.controller!!.playbackState)
                    }
                    screenshot("alpha21-recording-expanded.png")
                    scenario.onActivity { a ->
                        a.findViewById<View>(R.id.recordingPlaybackStop).performClick()
                        assertEquals(0,player!!.controller!!.mediaItemCount)
                        a.findViewById<View>(R.id.recordingPlaybackExpand).performClick()
                    }
                    Thread.sleep(500)
                    scenario.onActivity { a -> a.findViewById<View>(R.id.sheetToggle).performClick() }
                    Thread.sleep(700)
                    scenario.onActivity { a ->
                        val sheet=a.findViewById<View>(R.id.controlsBottomSheet);val behavior=BottomSheetBehavior.from(sheet)
                        assertEquals(BottomSheetBehavior.STATE_COLLAPSED,behavior.state)
                        assertEquals(View.GONE,a.findViewById<View>(R.id.controlsScrollView).visibility)
                        val arrow=Rect();val tabs=Rect();a.findViewById<View>(R.id.sheetToggle).getGlobalVisibleRect(arrow);a.findViewById<View>(R.id.controlTabLayout).getGlobalVisibleRect(tabs)
                        assertFalse("Arrow occupies its own header row",Rect.intersects(arrow,tabs))
                        assertEquals(View.GONE,a.findViewById<View>(R.id.fabBookmark).visibility)
                        assertEquals(View.GONE,a.findViewById<View>(R.id.fabScan).visibility)
                        assertEquals(sheet.height,a.findViewById<View>(R.id.panelEdgeBlur).height)
                    }
                    screenshot("alpha21-panel-collapsed.png")
                    // Different navigation insets without changing system display settings.
                    for(bottom in listOf(0,24,48)) {
                        scenario.onActivity { a ->
                            val insets=WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.navigationBars(),Insets.of(0,0,0,(bottom*a.resources.displayMetrics.density).toInt())).build()
                            ViewCompat.dispatchApplyWindowInsets(a.findViewById(android.R.id.content),insets)
                        };Thread.sleep(150)
                        scenario.onActivity { a -> assertEquals(View.GONE,a.findViewById<View>(R.id.controlsScrollView).visibility) }
                    }
                    scenario.onActivity { a -> ViewCompat.requestApplyInsets(a.findViewById(android.R.id.content)) }
                }
            } finally {
                runBlocking { dao.getAll().first().filter { it.id==id }.forEach { dao.delete(it) } };file.delete()
            }
        }
    }
}
