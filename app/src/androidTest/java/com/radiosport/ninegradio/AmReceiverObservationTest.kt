package com.radiosport.ninegradio

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.*
import com.radiosport.ninegradio.source.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit affected-path smoke observation; no saved settings, received PCM or coordinates. */
@RunWith(AndroidJUnit4::class)
class AmReceiverObservationTest {
    @Test fun connectedReceiverStreamsThroughAllAmDetectors():Unit=runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val usb=context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device=usb.deviceList.values.firstOrNull { UsbSources.isSupported(it) } ?: error("Receiver required")
        if(!usb.hasPermission(device)) {
            usb.requestPermission(device,PendingIntent.getBroadcast(context,0,
                Intent(context.packageName+".AM_USB").setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            withTimeout(60000) { while(!usb.hasPermission(device)) delay(100) }
        }
        val source=UsbSources.create(usb,device)
        val rate=if(source is AirspyHfSource) 768000 else 2400000
        var engine:DspEngine?=null
        try {
            assertTrue(source.open());assertTrue(source.setSampleRate(rate))
            assertTrue(source.setCenterFrequency(11920000))
            val receiver=DspEngine(source);engine=receiver
            receiver.setDemodMode(DemodMode.AM);receiver.setCarrierFrequency(11920000)
            receiver.setIfBandwidth(10000);receiver.setSquelchLevel(-160f);receiver.setVolume(0f)
            receiver.start()
            for(detector in AmDetector.values()) {
                receiver.setAmSettings(AmSettings(detector),if(detector.sideband==0) 10000 else 5000)
                delay(2000)
                val before=receiver.pipelineDiagnostics()
                delay(4000)
                val after=receiver.pipelineDiagnostics()
                assertNull(receiver.failure.value)
                assertTrue("$detector input progresses",after.inputSamples-before.inputSamples>rate*3L)
                assertEquals("$detector application loss",before.inputMissingSamples,after.inputMissingSamples)
                assertEquals("$detector driver loss",before.driverMissingSamples,after.driverMissingSamples)
                assertEquals("$detector PCM loss",before.playbackDroppedPackets,after.playbackDroppedPackets)
                assertEquals("$detector underrun",before.playbackUnderruns,after.playbackUnderruns)
                inst.sendStatus(0,Bundle().apply { putString("stream","\nAM_WINDOW ${detector.name}: ${after.report("architecture-18")}\n") })
            }
        } finally { try { engine?.stop() } finally { source.close() } }
    }
}
