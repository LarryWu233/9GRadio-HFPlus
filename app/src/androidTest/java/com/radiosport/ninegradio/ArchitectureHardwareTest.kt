package com.radiosport.ninegradio

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.*
import com.radiosport.ninegradio.source.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real USB/AudioTrack path without ActivityScenario or changing user preferences. */
@RunWith(AndroidJUnit4::class)
class ArchitectureHardwareTest {
    @Test fun continuousReceiver():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        val manager=instrumentation.targetContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val family=args.getString("hardwareSource") ?: "HF"
        val usb=manager.deviceList.values.firstOrNull {
            UsbSources.isSupported(it) && (AirspyHfSource.isSupported(it)==(family=="HF"))
        } ?: error("Requested receiver is not connected")
        check(manager.hasPermission(usb)) { "Allow the architecture preview to access the receiver first" }
        val mode=DemodMode.valueOf(args.getString("hardwareMode") ?: "WFM")
        require(mode in listOf(DemodMode.WFM,DemodMode.AM,DemodMode.USB))
        val seconds=args.getString("hardwareSeconds")?.toIntOrNull()?.coerceIn(5,1800) ?: 30
        val frequency=args.getString("hardwareFrequency")?.toLongOrNull()
            ?: when(mode) { DemodMode.WFM->87_800_000L;DemodMode.AM->11_920_000L;else->14_200_000L }
        val source=UsbSources.create(manager,usb)
        assertTrue("USB open",source.open())
        val rates=source.capabilities.sampleRates
        val rate=if(family=="HF") rates.firstOrNull { it==768000 } ?: rates.filter { it<=768000 }.maxOrNull()!!
            else rates.firstOrNull { it==2400000 } ?: source.getSampleRate()
        assertTrue("Sample rate",source.setSampleRate(rate))
        assertTrue("Hardware tune",source.setCenterFrequency(frequency))
        val engine=DspEngine(source)
        try {
            engine.setDemodMode(mode);engine.setCarrierFrequency(frequency)
            engine.setSquelchLevel(-120f);engine.setVolume(.5f)
            engine.start();delay(5000)
            val before=engine.pipelineDiagnostics()
            val start=SystemClock.elapsedRealtime();val cpu=Process.getElapsedCpuTime()
            var maxMemory=0L;var reports=0
            var lastProgress=SystemClock.elapsedRealtime();var lastSamples=before.inputSamples
            while(SystemClock.elapsedRealtime()-start<seconds*1000L) {
                delay(1000)
                source.pollStreamingStatus()
                assertNull("Driver stream state",source.statusFlow.value.error)
                assertNull("Receiver failure",engine.failure.value)
                assertTrue("USB remains open",source.isOpen())
                val current=engine.pipelineDiagnostics()
                if(current.inputSamples!=lastSamples) { lastSamples=current.inputSamples;lastProgress=SystemClock.elapsedRealtime() }
                assertTrue("IQ processing has stalled for five seconds",SystemClock.elapsedRealtime()-lastProgress<5000)
                assertEquals("Application IQ gaps",before.inputMissingSamples,current.inputMissingSamples)
                assertEquals("Driver IQ losses; locate and rerun rather than hiding them",before.driverMissingSamples,current.driverMissingSamples)
                assertEquals("Playback queue drops",before.playbackDroppedPackets,current.playbackDroppedPackets)
                assertEquals("Playback underruns",before.playbackUnderruns,current.playbackUnderruns)
                maxMemory=maxOf(maxMemory,Debug.getNativeHeapAllocatedSize()+Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
                if(++reports%10==0) instrumentation.sendStatus(0,Bundle().apply {
                    putString("stream","\nReceiver stability: elapsed_s=${(SystemClock.elapsedRealtime()-start)/1000} processed_samples=${current.inputSamples-before.inputSamples}\n")
                })
            }
            val after=engine.pipelineDiagnostics()
            assertTrue("IQ is actually processed",after.inputSamples-before.inputSamples>rate.toLong()*seconds*.9)
            instrumentation.sendStatus(0,Bundle().apply {
                putString("stream","\nReceiver stability PASS: mode=${mode.name} seconds=$seconds rate=$rate cpu_ms=${Process.getElapsedCpuTime()-cpu} max_allocated_bytes=$maxMemory\n"+after.report(BuildConfig.VERSION_NAME))
            })
        } finally { engine.stop();source.close() }
    }
}
