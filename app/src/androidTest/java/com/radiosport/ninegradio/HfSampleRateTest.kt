package com.radiosport.ninegradio

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.radiosport.ninegradio.source.AirspyHfSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Isolates official driver rate switching from DSP, UI and playback. */
@RunWith(AndroidJUnit4::class)
class HfSampleRateTest {
    @Test fun compareRequestedAndDeliveredRates(): Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val manager=context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device=manager.deviceList.values.first { AirspyHfSource.isSupported(it) }
        assertTrue(manager.hasPermission(device))
        val source=AirspyHfSource(manager,device)
        val results=ArrayList<Map<String,Any>>()
        try {
            assertTrue(source.open())
            val frames=AtomicLong()
            val observer=launch(Dispatchers.Default,start=CoroutineStart.UNDISPATCHED) {
                source.iqFlow.collect { frames.addAndGet(it.sampleCount.toLong()) }
            }
            try {
                val frequencyProbe=InstrumentationRegistry.getArguments().getString("probeFrequency")=="true"
                val cases=if(frequencyProbe) listOf(912000 to 10000000L,912000 to 87500000L,
                    912000 to 100000000L,768000 to 100000000L,912000 to 100000000L)
                    else listOf(912000,768000,456000,228000,912000).map { it to 10000000L }
                for((rate,frequency) in cases.filter { it.first in source.capabilities.sampleRates }) {
                    assertTrue(source.setSampleRate(rate))
                    assertTrue(source.setCenterFrequency(frequency))
                    source.startStreaming()
                    delay(1000)
                    frames.set(0)
                    val start=SystemClock.elapsedRealtimeNanos()
                    delay(4000)
                    val duration=SystemClock.elapsedRealtimeNanos()-start
                    val count=frames.get()
                    results+=mapOf("frequencyHz" to frequency,"requestedRate" to rate,"reportedRate" to source.getSampleRate(),
                        "frames" to count,"elapsedNs" to duration,"measuredRate" to count*1e9/duration)
                    File(context.cacheDir,"hf-sample-rates.json").writeText(Gson().toJson(results))
                }
            } finally { observer.cancelAndJoin() }
        } finally { source.close() }
        // Evidence is saved even when the assertion exposes a transport mismatch.
        results.forEach { result ->
            val expected=(result["requestedRate"] as Int).toDouble()
            assertEquals("Driver delivery at $expected",expected,result["measuredRate"] as Double,expected*0.02)
        }
    }
}
