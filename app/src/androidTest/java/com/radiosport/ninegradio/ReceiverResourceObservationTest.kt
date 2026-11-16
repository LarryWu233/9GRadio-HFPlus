package com.radiosport.ninegradio

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.dsp.DspEngine
import com.radiosport.ninegradio.source.AirspyHfSource
import com.radiosport.ninegradio.source.IqSource
import com.radiosport.ninegradio.source.UsbSources
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

/** One bounded resource/clock observation, also runnable against the unchanged alpha28 APK.
 * No Activity, preferences, recording, location or decoded-content collection. Reflection is
 * restricted to the changed constructor and the existing private drop counter, not DSP work.
 */
@RunWith(AndroidJUnit4::class)
class ReceiverResourceObservationTest {
    private fun engine(source:IqSource):DspEngine {
        val constructors=DspEngine::class.java.constructors
        constructors.firstOrNull { !it.isSynthetic && it.parameterCount==1 }?.let {
            return it.newInstance(source) as DspEngine
        }
        val defaults=constructors.single { it.isSynthetic &&
            it.parameterTypes.last().name=="kotlin.jvm.internal.DefaultConstructorMarker" }
        val arguments=arrayOfNulls<Any>(defaults.parameterCount)
        arguments[0]=source
        arguments[arguments.size-2]=((1 shl (arguments.size-2))-1) and -2
        return defaults.newInstance(*arguments) as DspEngine
    }

    @Test fun observeReceiverResourcesAndPlaybackClock():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val seconds=InstrumentationRegistry.getArguments().getString("observationSeconds")
            ?.toIntOrNull()?.coerceIn(10,60) ?: 60
        val sampleClock=InstrumentationRegistry.getArguments().getString("samplePlaybackClock")!="0"
        val inputRate=InstrumentationRegistry.getArguments().getString("observationSourceRate")
            ?.toIntOrNull() ?: 2400000
        require(inputRate in listOf(1024000,2400000))
        val manager=context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device=manager.deviceList.values.firstOrNull {
            UsbSources.isSupported(it) && !AirspyHfSource.isSupported(it)
        } ?: error("RTL receiver required for the identical-input-family comparison")
        if(!manager.hasPermission(device)) {
            manager.requestPermission(device,PendingIntent.getBroadcast(context,0,
                Intent(context.packageName+".RESOURCE_OBSERVATION_USB").setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            withTimeout(60000) { while(!manager.hasPermission(device)) delay(100) }
        }
        val source=UsbSources.create(manager,device)
        val wake=(context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,context.packageName+":resource-observation")
        wake.setReferenceCounted(false)
        wake.acquire((seconds+120)*1000L)
        var receiver:DspEngine?=null
        try {
            assertTrue(source.open())
            assertTrue(source.setSampleRate(inputRate));assertEquals(inputRate,source.getSampleRate())
            assertTrue(source.setCenterFrequency(87800000L))
            val engine=engine(source);receiver=engine
            engine.setDemodMode(DemodMode.WFM);engine.setIfBandwidth(180000)
            engine.setCarrierFrequency(87800000L);engine.setSquelchLevel(-120f);engine.setVolume(0f)
            val produced=AtomicLong()
            engine.setContentAudioSink { produced.addAndGet(it.size.toLong()) }
            val drops=DspEngine::class.java.getDeclaredField("audioQueueDrops")
                .apply { isAccessible=true }.get(engine) as AtomicLong
            engine.start();delay(8000)
            val start=SystemClock.elapsedRealtimeNanos()
            val cpu=Process.getElapsedCpuTime()
            val firstDrops=drops.get();val firstUnderruns=engine.getPlaybackUnderruns()
            val samples=JSONArray()
            val listeningCounter=DspEngine::class.java.methods.firstOrNull { it.name=="getListeningFrameCount" }
            while(SystemClock.elapsedRealtimeNanos()-start<seconds*1_000_000_000L) {
                delay(1000)
                val clock=if(sampleClock) engine.getPlaybackClock() else emptyMap()
                samples.put(JSONObject().apply {
                    put("elapsedNs",SystemClock.elapsedRealtimeNanos()-start)
                    put("producedFrames",produced.get())
                    put("writtenFrames",clock["writtenFrames"] ?: -1)
                    put("headFrames",clock["playbackHeadFrames"] ?: -1)
                    put("timestampFrames",clock["timestampFrames"] ?: -1)
                    put("timestampNanos",clock["timestampNanos"] ?: -1)
                    listeningCounter?.let { put("listeningFrames",it.invoke(engine)) }
                    put("drops",drops.get())
                })
            }
            val duration=SystemClock.elapsedRealtimeNanos()-start
            val cpuMs=Process.getElapsedCpuTime()-cpu
            val steadyDrops=drops.get()-firstDrops
            val steadyUnderruns=engine.getPlaybackUnderruns()-firstUnderruns
            val enoughAudio=samples.getJSONObject(samples.length()-1).getLong("producedFrames")>
                samples.getJSONObject(0).getLong("producedFrames")+48000L*(seconds-2)
            // /proc memory scans and allocator inspection are deliberately outside
            // the timed continuity window; their own cost must not become DSP cost.
            val memory=Debug.MemoryInfo().apply { Debug.getMemoryInfo(this) }
            val pss=memory.totalPss
            val allocated=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()+Debug.getNativeHeapAllocatedSize()
            val version=context.packageManager.getPackageInfo(context.packageName,0).versionName
            val report=JSONObject().apply {
                put("version",version);put("durationNs",duration)
                put("cpuMs",cpuMs);put("endWindowPssKb",pss);put("endWindowAllocatedBytes",allocated)
                put("memorySummaryKb",JSONObject().apply {
                    for(key in listOf("summary.java-heap","summary.native-heap","summary.code","summary.stack",
                            "summary.graphics","summary.private-other","summary.system")) {
                        memory.memoryStats[key]?.toLongOrNull()?.let { put(key,it) }
                    }
                })
                put("steadyDroppedPackets",steadyDrops);put("steadyUnderruns",steadyUnderruns)
                put("enoughAudio",enoughAudio);put("reportedSourceRate",source.getSampleRate())
                put("samplePlaybackClock",sampleClock)
                put("cpuWakeLockHeld",wake.isHeld)
                DspEngine::class.java.methods.firstOrNull { it.name=="pipelineDiagnostics" }?.let {
                    put("endDiagnostics",it.invoke(engine)?.toString())
                }
                DspEngine::class.java.methods.firstOrNull { it.name=="getFailure" }?.let {
                    put("receiverFailure",(it.invoke(engine) as kotlinx.coroutines.flow.StateFlow<*>).value)
                }
                put("sampleRate",48000);put("samples",samples)
                put("warmDrops",firstDrops);put("warmUnderruns",firstUnderruns)
            }
            instrumentation.sendStatus(0,Bundle().apply { putString("stream","\nRESOURCE_CLOCK_OBSERVATION="+report+"\n") })
            // Preserve diagnostics even on failure; emitting a report is not a pass.
            assertEquals("Stable-window queue loss",0L,steadyDrops)
            assertEquals("Stable-window underruns",0,steadyUnderruns)
            assertTrue("Actually receiving audio",enoughAudio)
        } finally {
            try { receiver?.stop() }
            finally {
                try { source.close() }
                finally { if(wake.isHeld) wake.release() }
            }
        }
    }
}
