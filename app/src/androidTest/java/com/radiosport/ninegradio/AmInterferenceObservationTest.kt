package com.radiosport.ninegradio

import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.hardware.usb.UsbManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.*
import com.radiosport.ninegradio.source.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun observedAmChannel():ChannelProcessor {
    val constructor=ChannelProcessor::class.java.constructors.firstOrNull { !it.isSynthetic && it.parameterCount==4 }
    return if(constructor!=null) constructor.newInstance(AndroidDemodPrimitives,ReferenceIqResamplers,NativeAmProcessor(),
        Class.forName("com.radiosport.ninegradio.dsp.NativeChannelFilter").getConstructor().newInstance()) as ChannelProcessor
    else ChannelProcessor(AndroidDemodPrimitives,ReferenceIqResamplers,NativeAmProcessor())
}

/** Explicit private RF observation. Generated samples are never release assets. */
@RunWith(AndroidJUnit4::class)
class AmInterferenceObservationTest {
    @Test fun compareDetectorsAndExistingNotchOnIdenticalIq():Unit=runBlocking {
        org.junit.Assume.assumeTrue("Explicit private RF capture only",
            InstrumentationRegistry.getArguments().getString("manualAmCapture")=="1")
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val usb=context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device=usb.deviceList.values.firstOrNull { AirspyHfSource.isSupported(it) } ?: error("HF+ required")
        if(!usb.hasPermission(device)) {
            usb.requestPermission(device,PendingIntent.getBroadcast(context,0,
                Intent(context.packageName+".AM_OBSERVE_USB").setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            // The user authorized this application's receiver access. Only click
            // the observed system USB dialog naming this app and AIRSPY HF+.
            withTimeout(60000) { while(!usb.hasPermission(device)) {
                val root=inst.uiAutomation.rootInActiveWindow
                if(root?.packageName?.toString()=="com.android.systemui" &&
                    root.findAccessibilityNodeInfosByText("9GRadio HF+").isNotEmpty() &&
                    root.findAccessibilityNodeInfosByText("AIRSPY HF+").isNotEmpty())
                    root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull()
                        ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                delay(150)
            } }
        }
        val source=UsbSources.create(usb,device)
        val chunks=ArrayList<FloatArray>();val rate=768000
        try {
            assertTrue(source.open());assertTrue(source.setSampleRate(rate));assertTrue(source.setCenterFrequency(11620000))
            var samples=0L
            val read=launch(start=CoroutineStart.UNDISPATCHED) {
                source.iqFlow.takeWhile { samples<rate*4L }.collect { block ->
                    check(block is IqBlock.Float32);chunks.add(block.values.copyOf());samples+=block.sampleCount
                }
            }
            source.startStreaming(32768);withTimeout(10000) { read.join() }
        } finally { source.close() }
        saveAudio(context,chunks,rate)
        assertTrue(chunks.isNotEmpty())
    }
    private fun saveAudio(context:Context,chunks:List<FloatArray>,rate:Int) {
        fun save(name:String,data:Sequence<FloatArray>) {
            File(context.cacheDir,"arch19-am-$name.f32").outputStream().buffered().use { out ->
                val bytes=ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN)
                for(chunk in data) for(value in chunk) { bytes.putFloat(value);if(!bytes.hasRemaining()) { out.write(bytes.array());bytes.clear() } }
                if(bytes.position()>0) out.write(bytes.array(),0,bytes.position())
            }
        }
        save("iq",chunks.asSequence())
        for(detector in AmDetector.values()) {
            val pcm=ArrayList<FloatArray>()
            observedAmChannel().use { channel ->
                for(chunk in chunks) pcm.add(channel.process(chunk,ChannelConfiguration(DemodMode.AM,rate,48000,
                    if(detector.sideband==0) 10000 else 5000,rate,am=AmSettings(detector),signalDb=-30f)).mono.copyOf())
            }
            save(detector.name,pcm.asSequence())
            if(detector==AmDetector.ENVELOPE) {
                ListeningAudioProcessor().use { listening ->
                    listening.settings=ListeningAudioProcessor.Settings(notch=true)
                    save("ENVELOPE_NOTCH",pcm.asSequence().map { mono ->
                        val stereo=FloatArray(mono.size*2) { mono[it/2] }
                        listening.process(stereo,48000,DemodMode.AM)
                        FloatArray(mono.size) { stereo[2*it] }
                    })
                }
            }
        }
    }
    private fun readSaved(context:Context):List<FloatArray> {
        val file=File(context.cacheDir,"arch19-am-iq.f32")
        org.junit.Assume.assumeTrue("Requires an explicit private capture",file.isFile)
        val bytes=file.readBytes()
        val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val chunks=ArrayList<FloatArray>()
        while(buffer.hasRemaining()) chunks.add(FloatArray(minOf(8192,buffer.remaining())).also { buffer.get(it) })
        return chunks
    }
    @Test fun replayPrivateCaptureToAudio() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        saveAudio(context,readSaved(context),768000)
    }
    @Test fun replayPrivateCaptureForTiming() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val chunks=readSaved(context)
        val report=org.json.JSONObject()
        for(detector in listOf(AmDetector.ENVELOPE,AmDetector.SAM_DSB)) {
            val timings=org.json.JSONArray()
            repeat(6) { trial ->
                val start=android.os.Debug.threadCpuTimeNanos()
                var frames=0
                observedAmChannel().use { channel ->
                    val config=ChannelConfiguration(DemodMode.AM,768000,48000,10000,768000,am=AmSettings(detector),signalDb=-30f)
                    for(chunk in chunks) frames+=channel.process(chunk,config).mono.size
                }
                val elapsed=android.os.Debug.threadCpuTimeNanos()-start
                assertEquals(192000,frames)
                if(trial>0) timings.put(elapsed)
            }
            report.put(detector.name,timings)
        }
        File(context.cacheDir,"arch19-am-timing.json").writeText(report.toString())
    }
}
