package com.radiosport.ninegradio

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.ui.MainActivity
import com.radiosport.ninegradio.ui.MainViewModel
import com.radiosport.ninegradio.usb.RtlSdrService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AdsbLiveTest {
    @Test fun receiveWithRtlV4AndRecordDecoderState() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val seconds=InstrumentationRegistry.getArguments().getString("seconds")?.toIntOrNull() ?: 30
        val log=File(inst.targetContext.cacheDir,"alpha14-adsb-live.jsonl")
        ReceiverTestPreferences(inst.targetContext).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var service: RtlSdrService?=null
                var deadline=SystemClock.elapsedRealtime()+15000
                while(SystemClock.elapsedRealtime()<deadline) {
                    var ready=false
                    scenario.onActivity { a ->
                        val vm=ViewModelProvider(a)[MainViewModel::class.java]
                        val candidate=vm.getService()
                        if(candidate?.source?.isOpen()==true) { service=candidate; ready=true }
                    }
                    if(ready) break
                    Thread.sleep(100)
                }
                assertNotNull("RTL-SDR must already be attached and authorised",service)
                val svc=service!!
                assertEquals("rtl",svc.capabilities.id)
                scenario.onActivity { a -> ViewModelProvider(a)[MainViewModel::class.java].apply {
                    setAutoDemod(false); setDemodMode(DemodMode.ADSB); setFrequency(1090000000); finishTuning()
                } }
                var maxMessages=0L; var maxAircraft=0
                deadline=SystemClock.elapsedRealtime()+seconds*1000
                log.bufferedWriter().use { writer -> while(SystemClock.elapsedRealtime()<deadline) {
                    val status=svc.adsbReceiver.status.value
                    val tracks=svc.adsbReceiver.aircraft.value
                    maxMessages=maxOf(maxMessages,status.messages); maxAircraft=maxOf(maxAircraft,tracks.size)
                    writer.appendLine(Gson().toJson(mapOf("elapsedMs" to SystemClock.elapsedRealtime(),
                        "source" to svc.source?.getSourceName(),"v4" to svc.isV4,"rate" to svc.source?.getSampleRate(),
                        "frequency" to svc.source?.getCenterFrequency(),"decoder" to status,"aircraft" to tracks)))
                    writer.flush(); Thread.sleep(1000)
                } }
                assertEquals(2400000,svc.source!!.getSampleRate())
                assertTrue("Must feed raw IQ to readsb",svc.adsbReceiver.status.value.inputSamples>2400000)
                assertTrue("No CRC-valid ADS-B frames received; inspect RF signal and baseline",maxMessages>0)
                assertTrue("No real aircraft decoded",maxAircraft>0)
            }
        }
    }
}
