package com.radiosport.ninegradio

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.radiosport.ninegradio.dsp.AdsbDecoder
import com.radiosport.ninegradio.ui.*
import com.radiosport.ninegradio.usb.RtlSdrService
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic coordinates/frames only. Receiver remains closed and preferences are restored. */
@RunWith(AndroidJUnit4::class)
class AdsbLocationUiTest {
    @Test fun positionChangeRefreshesDistanceAndRadarWithoutAnotherPacket() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        ReceiverTestPreferences(context).use {
            context.getSharedPreferences("rtlsdr_prefs",Context.MODE_PRIVATE).edit()
                .putBoolean("receiverEnabled",false).putString("activeDevice","rtl")
                .putString("demod","ADSB").putString("device_rtl_mode","ADSB").commit()
            context.getSharedPreferences("radio_map",Context.MODE_PRIVATE).edit()
                .putString("receiverLocationMode","manual").putString("receiverPositionSource","manual")
                .putFloat("receiverLat",0f).putFloat("receiverLon",0f).putBoolean("adsb_show_map",true).commit()
            ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(
                "am start -W -n ${context.packageName}/${MainActivity::class.java.name}"
            )).use { it.readBytes() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var service:RtlSdrService?=null
                var panel:RadioMapPanel?=null
                val deadline=SystemClock.elapsedRealtime()+10000
                while(service==null && SystemClock.elapsedRealtime()<deadline) {
                    scenario.onActivity { a ->
                        val vm=ViewModelProvider(a)[MainViewModel::class.java]
                        vm.setReceiverEnabled(false)
                        service=vm.getService()
                        if(service!=null) {
                            val adapter=MainActivity::class.java.getDeclaredField("pagerAdapter").apply { isAccessible=true }.get(a) as ControlsPagerAdapter
                            a.findViewById<TabLayout>(R.id.controlTabLayout).getTabAt(adapter.positionOf(ControlsPagerAdapter.TAB_ADSB))!!.select()
                            panel=a.findViewById<android.widget.FrameLayout>(R.id.adsbMapHost).getChildAt(0) as? RadioMapPanel
                        }
                    }
                    if(service==null) Thread.sleep(50)
                }
                assertNotNull(service);assertNotNull(panel)
                @Suppress("UNCHECKED_CAST")
                val frames=service!!.adsbReceiver.javaClass.getDeclaredField("_aircraft").apply { isAccessible=true }
                    .get(service!!.adsbReceiver) as MutableStateFlow<List<AdsbDecoder.AdsbFrame>>
                val previous=frames.value
                try {
                    frames.value=listOf(AdsbDecoder.AdsbFrame("SAMPLE",17,0,10000,"[样本]",0.1,0.0,200,90.0,0,false))
                    fun awaitLabel(expected:String) {
                        val until=SystemClock.elapsedRealtime()+5000
                        var found=false
                        while(!found && SystemClock.elapsedRealtime()<until) {
                            scenario.onActivity { a -> found=a.findViewById<TextView>(R.id.tvAdsbMaxDistance).text.toString().contains(expected) }
                            if(!found) Thread.sleep(50)
                        }
                        assertTrue("Distance label must react to receiver location",found)
                    }
                    awaitLabel("11.1")
                    scenario.onActivity { panel!!.receiverLocation.saveManual(0.05,0.0) }
                    awaitLabel("5.5") // No aircraft/status update is emitted for this change.
                    scenario.onActivity { a ->
                        a.findViewById<View>(R.id.btnAdsbPanelRadar).performClick()
                        assertFalse(a.findViewById<MaterialSwitch>(R.id.btnAdsbPanelMap).isChecked)
                        val radar=a.findViewById<AdsbRadarView>(R.id.adsbPanelRadar)
                        assertEquals(View.VISIBLE,radar.visibility)
                        assertEquals(View.GONE,a.findViewById<View>(R.id.adsbMapHost).visibility)
                        assertTrue(radar.contentDescription.toString().contains("1 个"))
                        a.findViewById<MaterialSwitch>(R.id.btnAdsbPanelMap).isChecked=true
                        assertEquals(View.GONE,radar.visibility)
                        assertEquals(View.VISIBLE,a.findViewById<View>(R.id.adsbMapHost).visibility)
                    }
                } finally { frames.value=previous }
            }
        }
    }
}
