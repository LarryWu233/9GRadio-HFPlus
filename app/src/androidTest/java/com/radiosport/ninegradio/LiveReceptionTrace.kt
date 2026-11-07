package com.radiosport.ninegradio

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.radiosport.ninegradio.debug.DebugBus
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.source.AirspyHfSource
import com.radiosport.ninegradio.source.IqBlock
import com.radiosport.ninegradio.ui.MainActivity
import com.radiosport.ninegradio.ui.MainViewModel
import com.radiosport.ninegradio.usb.RtlSdrService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Records real reception; optional test settings are restored, without replacing DSP. */
@RunWith(AndroidJUnit4::class)
class LiveReceptionTrace {
    @Test fun captureActiveReceiver(): Unit = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val manager=context.getSystemService(Context.USB_SERVICE) as UsbManager
        val ready=manager.deviceList.values.any {
            AirspyHfSource.isSupported(it) && manager.hasPermission(it)
        }
        if(!ready) instrumentation.sendStatus(0,android.os.Bundle().apply {
            putString("stream","\nLIVE_TRACE_SKIPPED: no HF+ with USB permission; real reception remains unverified.\n")
        })
        assumeTrue("Connect HF+ and grant this app USB access first",ready)
        val seconds=InstrumentationRegistry.getArguments().getString("traceSeconds")?.toLongOrNull()?.coerceIn(5,120) ?: 30
        val arguments=InstrumentationRegistry.getArguments()
        val restorePreferences=if(listOf("traceMode","traceFrequency","sqlOff","traceEnhancements","traceIqNb").any { arguments.containsKey(it) })
            ReceiverTestPreferences(context) else null
        try {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var service: RtlSdrService?=null
            var oldSql: Float?=null
            var oldMode: DemodMode?=null
            var oldFrequency: Long?=null
            var oldAuto: Boolean?=null
            var oldListening: com.radiosport.ninegradio.dsp.ListeningAudioProcessor.Settings?=null
            var oldNb: Boolean?=null
            var keepScreenWasSet=false
            lateinit var model: MainViewModel
            var touchShield: android.view.View?=null
            val blockedTouches=AtomicLong()
            withTimeout(10000) {
                while(service?.dspEngine==null) {
                    scenario.onActivity { activity -> service=ViewModelProvider(activity)[MainViewModel::class.java].getService() }
                    delay(100)
                }
            }
            val args=InstrumentationRegistry.getArguments()
            scenario.onActivity { activity ->
                val model=ViewModelProvider(activity)[MainViewModel::class.java]
                if(args.getString("traceIqNb")=="true") { oldNb=model.noiseBlankerEnabled.value; model.setNoiseBlanker(true) }
                if(args.getString("traceEnhancements")=="true") {
                    oldListening=model.listeningAudio.value
                    model.setListeningAudio(oldListening!!.copy(leveling=true,notch=true))
                }
                if(args.getString("traceMode")!=null || args.getString("traceFrequency")!=null) {
                    oldMode=model.demodMode.value; oldFrequency=model.centerFreqHz.value; oldAuto=model.autoDemod.value
                }
                args.getString("traceMode")?.let { requested ->
                    model.setDemodMode(DemodMode.valueOf(requested))
                }
                args.getString("traceFrequency")?.toLongOrNull()?.let { requested ->
                    model.setFrequency(requested); model.finishTuning()
                }
            }
            service!!.awaitControls()
            delay(800)
            val engine=service!!.dspEngine!!
            val source=service!!.source!!
            if(InstrumentationRegistry.getArguments().getString("sqlOff")=="true") scenario.onActivity { activity ->
                val model=ViewModelProvider(activity)[MainViewModel::class.java]
                oldSql=model.squelch.value
                model.setSquelch(-120f)
            }
            scenario.onActivity { activity ->
                model=ViewModelProvider(activity)[MainViewModel::class.java]
                keepScreenWasSet=activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON!=0
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if(arguments.getString("blockTouches")=="true") {
                    touchShield=android.view.View(activity).apply {
                        isClickable=true; elevation=10000f
                        setOnTouchListener { _,_ -> blockedTouches.incrementAndGet(); true }
                    }
                    activity.findViewById<android.widget.FrameLayout>(android.R.id.content).addView(touchShield,
                        android.widget.FrameLayout.LayoutParams(-1,-1))
                }
            }
            val output=File(context.cacheDir,"live-reception-trace.jsonl")
            val callbacks=AtomicLong(); val frames=AtomicLong(); val maxGapUs=AtomicLong(); val gaps=AtomicLong()
            val observer=launch(Dispatchers.Default) {
                var lastNs=0L; var lastSequence=-1L
                source.iqFlow.collect { block ->
                    val now=SystemClock.elapsedRealtimeNanos()
                    if(lastNs!=0L) maxGapUs.accumulateAndGet((now-lastNs)/1000,::maxOf)
                    lastNs=now
                    if(block is IqBlock.Float32) {
                        if(lastSequence>=0 && block.sequence>lastSequence+1) gaps.addAndGet(block.sequence-lastSequence-1)
                        lastSequence=block.sequence
                    }
                    callbacks.incrementAndGet(); frames.addAndGet(block.sampleCount.toLong())
                }
            }
            try {
                val start=SystemClock.elapsedRealtime()
                val gson=Gson()
                output.bufferedWriter().use { writer ->
                    writer.appendLine(gson.toJson(mapOf("kind" to "configuration","source" to source.getSourceName(),
                        "frequencyHz" to source.getCenterFrequency(),"sampleRate" to source.getSampleRate(),
                        "noiseReducer" to model.noiseReducerEnabled.value,"noiseBlanker" to model.noiseBlankerEnabled.value,
                        "listeningAudio" to model.listeningAudio.value,
                        "iqNoiseBlanker" to engine.getIqNoiseBlankerStats(),
                        "iqNbThreshold" to model.iqNbThreshold.value,
                        "noiseProcessingAllowed" to com.radiosport.ninegradio.dsp.NoiseProcessingPolicy.allows(model.demodMode.value),
                        "effectiveNoiseReducer" to engine.isNoiseReducerEnabled(),
                        "touchShield" to (touchShield!=null),
                        "chain" to DebugBus.getChain(),"globals" to DebugBus.getGlobals())))
                    while(SystemClock.elapsedRealtime()-start<seconds*1000) {
                        delay(100)
                        writer.appendLine(gson.toJson(mapOf("elapsedMs" to SystemClock.elapsedRealtime()-start,
                            "frequencyHz" to source.getCenterFrequency(),"reportedSampleRate" to source.getSampleRate(),
                            "bandwidthHz" to model.ifBandwidthHz.value,"noiseReducer" to model.noiseReducerEnabled.value,
                            "noiseBlanker" to model.noiseBlankerEnabled.value,
                            "blockedTouchEvents" to blockedTouches.get(),
                            "iqNoiseBlanker" to engine.getIqNoiseBlankerStats(),
                            "iqFrames" to frames.getAndSet(0),"callbacks" to callbacks.getAndSet(0),
                            "maxCallbackGapUs" to maxGapUs.getAndSet(0),"observerSequenceGaps" to gaps.get(),
                            "iqFlowDrops" to DebugBus.getIqFlowDropCount(),"playbackUnderruns" to engine.getPlaybackUnderruns(),
                            "stats" to engine.statsFlow.value,
                            "playbackClock" to engine.getPlaybackClock(),
                            "iq" to DebugBus.getExtras(DebugBus.STAGE_IQ_STREAM),
                            "audio" to DebugBus.getExtras(DebugBus.STAGE_AUDIO_TRACK))))
                    }
                }
            } finally {
                observer.cancelAndJoin()
                oldSql?.let { saved -> scenario.onActivity { activity ->
                    ViewModelProvider(activity)[MainViewModel::class.java].setSquelch(saved)
                } }
                scenario.onActivity { activity ->
                    touchShield?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
                    if(!keepScreenWasSet) activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    val model=ViewModelProvider(activity)[MainViewModel::class.java]
                    oldMode?.let { model.setDemodMode(it) }
                    oldFrequency?.let { model.setFrequency(it); model.finishTuning() }
                    oldAuto?.let { model.setAutoDemod(it) }
                    oldListening?.let { model.setListeningAudio(it) }
                    oldNb?.let { model.setNoiseBlanker(it) }
                }
            }
        }
        } finally { restorePreferences?.close() }
    }
}
