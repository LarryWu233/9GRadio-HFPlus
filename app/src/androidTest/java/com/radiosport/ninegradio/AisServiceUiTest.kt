package com.radiosport.ninegradio

import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ListView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.data.RecordingMeta
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.ui.*
import com.radiosport.ninegradio.usb.RtlSdrService
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AisServiceUiTest {
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun <T> field(a:MainActivity,name:String):T=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(a) as T
    @Test fun iqPlaybackStartsInAisAndPopulatesExistingMapAndTable() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val file=File(context.cacheDir,"ais-reference-288k.cu8")
        if(!file.isFile) File(context.getExternalFilesDir(null),file.name).takeIf { it.isFile }?.copyTo(file)
        assumeTrue(file.isFile)
        ReceiverTestPreferences(context).use {
            context.getSharedPreferences("rtlsdr_prefs",0).edit().putBoolean("receiverEnabled",false).putString("demod","AM").commit()
            context.getSharedPreferences("radio_map",0).edit().putString("receiverLocationMode","manual").commit()
            ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(
                "am start -W -n ${context.packageName}/${MainActivity::class.java.name}"
            )).use { it.readBytes() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { a -> a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                Thread.sleep(1500)
                scenario.onActivity { a -> ViewModelProvider(a)[MainViewModel::class.java].playIqRecording(
                    RecordingMeta(filePath=file.path,type="IQ",frequencyHz=162000000,sampleRate=288000,
                        demodMode="AIS",durationMs=12000,fileSizeBytes=file.length())) }
                try {
                    val deadline=android.os.SystemClock.elapsedRealtime()+10000
                    var decoded=false
                    while(!decoded && android.os.SystemClock.elapsedRealtime()<deadline) {
                        Thread.sleep(200)
                        scenario.onActivity { a -> decoded=field<RtlSdrService?>(a,"sdrService")?.aisReceiver?.vessels?.value?.isNotEmpty()==true }
                    }
                    assertTrue("Service path must decode the reference",decoded)
                    scenario.onActivity { a ->
                        val s=field<RtlSdrService>(a,"sdrService")
                        assertEquals(DemodMode.AIS,s.dspEngine!!.controlSnapshot().mode)
                        assertEquals(0L,s.aisReceiver.diagnostics.value.missingSamples)
                        assertFalse(com.radiosport.ninegradio.dsp.AudioRoutingPolicy.canMonitor(DemodMode.AIS))
                        val pager=field<ControlsPagerAdapter>(a,"pagerAdapter")
                        a.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.controlViewPager).setCurrentItem(pager.positionOf(ControlsPagerAdapter.TAB_AIS),false)
                    }
                    val tableDeadline=android.os.SystemClock.elapsedRealtime()+4000
                    var populated=false
                    while(!populated && android.os.SystemClock.elapsedRealtime()<tableDeadline) {
                        Thread.sleep(200)
                        scenario.onActivity { a ->
                            val page=field<ControlsPagerAdapter>(a,"pagerAdapter").getViewAt(ControlsPagerAdapter.TAB_AIS)!!
                            populated=views(page).filterIsInstance<ListView>().any { it.count>0 }
                        }
                    }
                    scenario.onActivity { a ->
                        val page=field<ControlsPagerAdapter>(a,"pagerAdapter").getViewAt(ControlsPagerAdapter.TAB_AIS)!!
                        val lists=views(page).filterIsInstance<ListView>()
                        val controller=field<AisModuleController>(a,"aisController")
                        val active=AisModuleController::class.java.getDeclaredField("active").apply { isAccessible=true }.get(controller)
                        val selected=field<ControlsPagerAdapter>(a,"pagerAdapter").moduleAt(a.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.controlViewPager).currentItem)
                        val job=AisModuleController::class.java.getDeclaredField("collectJob").apply { isAccessible=true }.get(controller) as? kotlinx.coroutines.Job
                        val observed=AisModuleController::class.java.getDeclaredField("observed").apply { isAccessible=true }.get(controller) as? RtlSdrService
                        assertTrue("AIS lists=${lists.map { it.count }}, active=$active, mode=${ViewModelProvider(a)[MainViewModel::class.java].demodMode.value}, selected=$selected, targets=${field<RtlSdrService>(a,"sdrService").aisReceiver.vessels.value.size}, observerSame=${observed===field<RtlSdrService>(a,"sdrService")}, job=$job, lifecycle=${a.lifecycle.currentState}",lists.any { it.count>0 })
                        assertNotNull(page.findViewById<View>(R.id.aisMapHost))
                        assertTrue(com.radiosport.ninegradio.data.RadioTargets.vessels.value.isNotEmpty())
                        MainActivity::class.java.getDeclaredMethod("setPanelFullscreen",Boolean::class.javaPrimitiveType)
                            .apply { isAccessible=true }.invoke(a,true)
                    }
                    Thread.sleep(500)
                    inst.uiAutomation.takeScreenshot()?.let { image -> File(context.cacheDir,"ais-ui-check.png").outputStream().use {
                        image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                    } }
                } finally {
                    scenario.onActivity { a -> field<RtlSdrService?>(a,"sdrService")?.disconnectSource() }
                    Thread.sleep(300)
                }
            }
        }
    }
}
