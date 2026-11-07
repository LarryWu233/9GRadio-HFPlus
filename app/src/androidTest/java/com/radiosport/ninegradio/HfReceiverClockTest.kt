package com.radiosport.ninegradio

import android.content.*
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.source.AirspyHfSource
import com.radiosport.ninegradio.usb.RtlSdrService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** A real device clock regression, including rate changes with DSP running. */
@RunWith(AndroidJUnit4::class)
class HfReceiverClockTest {
    @Test fun savedRateAndLiveChangesMatchTheAudioClock(): Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val manager=context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device=manager.deviceList.values.first { AirspyHfSource.isSupported(it) }
        assertTrue(manager.hasPermission(device))
        val prefs=context.getSharedPreferences("rtlsdr_prefs",Context.MODE_PRIVATE)
        val key="device_airspyhf_rate"; val saved=prefs.getInt(key,0); val existed=prefs.contains(key)
        prefs.edit().putInt(key,912000).commit()
        val bound=CompletableDeferred<RtlSdrService>()
        val connection=object: ServiceConnection {
            override fun onServiceConnected(name: ComponentName,binder: IBinder) { bound.complete((binder as RtlSdrService.LocalBinder).getService()) }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        context.bindService(Intent(context,RtlSdrService::class.java),connection,Context.BIND_AUTO_CREATE)
        val service=withTimeout(5000) { bound.await() }
        val results=ArrayList<Map<String,Any>>()
        try {
            service.reconnect(device)
            withTimeout(10000) { service.connectionState.first { it is RtlSdrService.ConnectionState.Connected } }
            assertEquals("Saved HF+ rate must be applied before Connected",912000,service.source!!.getSampleRate())
            service.setDemodMode(DemodMode.WFM); service.setSquelch(-120f); service.setAudioVolume(0f)
            service.setFrequency(87800000,true); service.awaitControls()
                for(rate in listOf(912000,768000,912000,456000,768000)) {
                    service.setSampleRate(rate); service.awaitControls()
                    withTimeout(10000) { while(service.source?.getSampleRate()!=rate ||
                        service.connectionState.value !is RtlSdrService.ConnectionState.Connected) delay(50) }
                    service.setDemodMode(DemodMode.WFM); service.setSquelch(-120f); service.setAudioVolume(0f)
                    service.awaitControls()
                    val frames=AtomicLong()
                    val observer=launch(Dispatchers.Default,start=CoroutineStart.UNDISPATCHED) {
                        service.source!!.iqFlow.collect { frames.addAndGet(it.sampleCount.toLong()) }
                    }
                    try {
                    delay(1200)
                    val engine=service.dspEngine!!
                    val beforeUnderruns=engine.getPlaybackUnderruns()
                    frames.set(0); val start=SystemClock.elapsedRealtimeNanos()
                    delay(5000)
                    val duration=SystemClock.elapsedRealtimeNanos()-start
                    results+=mapOf("requestedRate" to rate,"reportedRate" to service.source!!.getSampleRate(),
                        "measuredRate" to frames.get()*1e9/duration,"steadyUnderruns" to engine.getPlaybackUnderruns()-beforeUnderruns)
                    File(context.cacheDir,"hf-receiver-clock.json").writeText(Gson().toJson(results))
                    } finally { observer.cancelAndJoin() }
                }
        } finally {
            service.setReceiverEnabled(false)
            withTimeout(5000) { service.connectionState.first { it is RtlSdrService.ConnectionState.Disconnected } }
            context.unbindService(connection)
            prefs.edit().apply { if(existed) putInt(key,saved) else remove(key) }.commit()
        }
        results.forEach { result ->
            val expected=(result["requestedRate"] as Int).toDouble()
            assertEquals("Actual input must agree with DSP clock",expected,result["measuredRate"] as Double,expected*0.02)
            assertEquals("Continuous WFM must not underrun after settling",0,result["steadyUnderruns"])
        }
    }
}
