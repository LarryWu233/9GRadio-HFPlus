package com.radiosport.ninegradio

import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.ui.MainActivity
import com.radiosport.ninegradio.ui.MainViewModel
import com.radiosport.ninegradio.usb.RtlSdrService
import com.radiosport.ninegradio.source.UsbSources
import com.radiosport.ninegradio.source.AirspyHfSource
import com.radiosport.ninegradio.ui.ControlsPagerAdapter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

/** Explicit, short foreground comparison. TCP requires iq_fixture.py and adb reverse tcp:1234;
 * observationInput=rtl uses an attached receiver. observationMode=ADSB selects its live map.
 * Exercises the real Activity, spectrum, waterfall and service, unlike the headless observer.
 * Reports process CPU, not battery power. Restores preferences; never records received content.
 */
@RunWith(AndroidJUnit4::class)
class ReceiverForegroundObservationTest {
    @Test fun observeForegroundReceiver() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        val hardware=args.getString("observationInput")=="rtl"
        val mode=if(args.getString("observationMode")=="ADSB") DemodMode.ADSB else DemodMode.FM
        val frequency=if(mode==DemodMode.ADSB) 1_090_000_000L else 87_800_000L
        val bandwidth=if(mode==DemodMode.ADSB) mode.defaultBwHz else 180_000
        val decimation=args.getString("observationDecimation")?.toIntOrNull() ?: 2
        require(decimation in listOf(1,2,4,8,16,32,64))
        val frameAverage=args.getString("observationFrameAverage")?.toIntOrNull()
        val seconds=args.getString("observationSeconds")
            ?.toIntOrNull()?.coerceIn(10,30) ?: 20
        ReceiverTestPreferences(inst.targetContext).use {
            inst.targetContext.getSharedPreferences("rtlsdr_prefs",android.content.Context.MODE_PRIVATE).edit()
                .putString("activeDevice","rtl").putString("demod",mode.name).putInt("rate",2_400_000)
                .putString("device_rtl_mode",mode.name).putInt("device_rtl_rate",2_400_000)
                .putInt("device_rtl_width",bandwidth).putLong("device_rtl_freq",frequency)
                .putBoolean("receiverEnabled",false)
                .putBoolean("autoDemod",false).putBoolean("modeStationMemory",false).commit()
            inst.targetContext.getSharedPreferences("radio_map",Context.MODE_PRIVATE).edit()
                .putBoolean("adsb_show_map",true).putString("receiverLocationMode","manual").commit()
            // MIUI blocks ActivityScenario's app-UID launch after instrumentation
            // restarts the process. Bring the target forward through Android's
            // standard shell automation before asking Scenario to own it.
            android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(
                "am start -W -n ${inst.targetContext.packageName}/${MainActivity::class.java.name}"
            )).use { it.readBytes() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm:MainViewModel
                scenario.onActivity { activity ->
                    vm=ViewModelProvider(activity)[MainViewModel::class.java]
                }
                var writerThreads=emptyList<Thread>()
                try {
                runBlocking { withTimeout(10000) { vm.serviceConnected.first { it } } }
                if(hardware) {
                    val manager=inst.targetContext.getSystemService(Context.USB_SERVICE) as UsbManager
                    val device=manager.deviceList.values.firstOrNull {
                        UsbSources.isSupported(it) && !AirspyHfSource.isSupported(it)
                    } ?: error("RTL receiver is required")
                    if(!manager.hasPermission(device)) {
                        manager.requestPermission(device,PendingIntent.getBroadcast(inst.targetContext,0,
                            Intent(inst.targetContext.packageName+".FOREGROUND_USB").setPackage(inst.targetContext.packageName),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                        runBlocking { withTimeout(60000) { while(!manager.hasPermission(device)) kotlinx.coroutines.delay(100) } }
                    }
                    inst.runOnMainSync { vm.connectDevice(device);vm.setReceiverEnabled(true) }
                } else inst.runOnMainSync { vm.connectTcp("127.0.0.1",1234) }
                // The initial source adoption intentionally restores that device's
                // saved profile. Apply the comparison settings after that boundary.
                runBlocking { withTimeout(20000) { vm.connectionState.first { it is RtlSdrService.ConnectionState.Connected } } }
                inst.sendStatus(0,Bundle().apply { putString("stream","\nFOREGROUND_SOURCE_CONNECTED\n") })
                inst.runOnMainSync {
                    vm.setAutoDemod(false);vm.setModeStationMemory(false)
                    vm.setReceiverEnabled(true);vm.setDemodMode(mode)
                    vm.setSampleRate(2_400_000);vm.setIfBandwidth(bandwidth)
                    vm.setFftSize(2048);vm.setDecimation(decimation)
                    vm.setNoiseBlanker(false);vm.setNoiseReducer(false)
                    vm.setSquelch(-120f);vm.setVolume(0f)
                    vm.setFrequency(frequency)
                }
                scenario.onActivity { activity ->
                    val adapter=MainActivity::class.java.getDeclaredField("pagerAdapter")
                        .apply { isAccessible=true }.get(activity) as ControlsPagerAdapter
                    val id=activity.resources.getIdentifier("controlTabLayout","id",inst.targetContext.packageName)
                    activity.findViewById<com.google.android.material.tabs.TabLayout>(id)
                        .getTabAt(adapter.positionOf(if(mode==DemodMode.ADSB) ControlsPagerAdapter.TAB_ADSB else ControlsPagerAdapter.TAB_TUNE))?.select()
                }
                inst.sendStatus(0,Bundle().apply { putString("stream","\nFOREGROUND_SETTINGS_APPLIED rate=${vm.sampleRate.value} mode=${vm.demodMode.value}\n") })
                runBlocking { withTimeout(10000) { vm.dspStats.first { it.sampleRate==2_400_000 } } }
                val service=vm.getService()!!
                val engine=service.dspEngine!!
                frameAverage?.let { engine.fftEngine.frameAveragingCount=it }
                Thread.sleep(6000)
                writerThreads=Thread.getAllStackTraces().keys.filter { it.name=="Radio:AudioWriter" }
                val drops=engine.javaClass.getDeclaredField("audioQueueDrops").apply { isAccessible=true }.get(engine) as AtomicLong
                val beforeDrops=drops.get();val beforeUnderruns=engine.getPlaybackUnderruns()
                val diagnosticsMethod=engine.javaClass.methods.firstOrNull { it.name=="pipelineDiagnostics" }
                fun diagnosticCounters():Map<String,Long> {
                    val snapshot=diagnosticsMethod?.invoke(engine) ?: return emptyMap()
                    return listOf("Epoch","InputSamples","InputMissingSamples","DriverMissingSamples","RawDecoderInputMissingSamples","RawDecoderDriverMissingSamples")
                        .associateWith { name -> (snapshot.javaClass.getMethod("get"+name).invoke(snapshot) as Number).toLong() }
                }
                val beforeCounters=diagnosticCounters()
                val beforeAdsb=service.adsbReceiver.status.value.inputSamples
                val beforeClock=engine.getPlaybackClock()
                val beforeAllocation=Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
                inst.sendStatus(0,Bundle().apply { putString("stream","\nFOREGROUND_OBSERVATION_STARTED\n") })
                val cpu=Process.getElapsedCpuTime()
                val start=SystemClock.elapsedRealtimeNanos()
                Thread.sleep(seconds*1000L)
                val elapsed=SystemClock.elapsedRealtimeNanos()-start
                val cpuMs=Process.getElapsedCpuTime()-cpu
                val afterAllocation=Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
                val afterClock=engine.getPlaybackClock()
                val dropDelta=drops.get()-beforeDrops
                val underrunDelta=engine.getPlaybackUnderruns()-beforeUnderruns
                val deltas=diagnosticCounters().mapValues { (key,value) -> value-beforeCounters.getValue(key) }
                val memory=Debug.MemoryInfo().apply { Debug.getMemoryInfo(this) }
                val report=JSONObject().apply {
                    put("version",inst.targetContext.packageManager.getPackageInfo(inst.targetContext.packageName,0).versionName)
                    put("durationNs",elapsed);put("cpuMs",cpuMs);put("endPssKb",memory.totalPss)
                    put("sourceRate",vm.dspStats.value.sampleRate)
                    put("input",if(hardware) "rtl" else "tcp");put("mode",mode.name)
                    put("fftSize",engine.fftEngine.fftSize);put("fftDecimation",engine.fftEngine.decimationFactor)
                    put("frameAverage",engine.fftEngine.frameAveragingCount);put("bandwidthHz",bandwidth)
                    put("playbackDrops",dropDelta);put("playbackUnderruns",underrunDelta)
                    put("counterDeltas",JSONObject(deltas))
                    put("dedicatedAudioThreads",writerThreads.size)
                    put("writtenFramesDelta",(afterClock["writtenFrames"] ?: 0)-(beforeClock["writtenFrames"] ?: 0))
                    put("playedFramesDelta",(afterClock["playbackHeadFrames"] ?: 0)-(beforeClock["playbackHeadFrames"] ?: 0))
                    if(beforeAllocation!=null && afterAllocation!=null) put("javaAllocatedBytes",afterAllocation-beforeAllocation)
                    put("memorySummaryKb",JSONObject().apply {
                        for(key in listOf("summary.java-heap","summary.native-heap","summary.code","summary.graphics","summary.private-other","summary.system"))
                            memory.memoryStats[key]?.toLongOrNull()?.let { put(key,it) }
                    })
                    if(mode==DemodMode.ADSB) {
                        put("adsbInputSamples",service.adsbReceiver.status.value.inputSamples-beforeAdsb)
                        put("adsbMessages",service.adsbReceiver.status.value.messages)
                        put("adsbAircraftCount",service.adsbReceiver.aircraft.value.size)
                    }
                }
                inst.sendStatus(0,Bundle().apply { putString("stream","\nFOREGROUND_RESOURCE_OBSERVATION=$report\n") })
                assertTrue("Receiver must stay active",vm.dspStats.value.sampleRate==2_400_000)
                assertEquals(decimation,engine.fftEngine.decimationFactor)
                frameAverage?.let { assertEquals(it.toInt(),engine.fftEngine.frameAveragingCount) }
                assertEquals("No stable-window PCM loss",0L,dropDelta)
                if(mode!=DemodMode.ADSB) assertEquals("No stable-window playback underruns",0,underrunDelta)
                for((key,value) in deltas) if(key!="InputSamples") assertEquals(key,0L,value)
                deltas["InputSamples"]?.let { assertTrue("IQ processing must advance",it>2_400_000L*(seconds-2)) }
                if(mode==DemodMode.ADSB) assertTrue("Raw IQ decoder must advance",
                    service.adsbReceiver.status.value.running && service.adsbReceiver.status.value.inputSamples-beforeAdsb>2_400_000L*(seconds-2))
                } finally {
                    inst.runOnMainSync { vm.setReceiverEnabled(false) }
                    runBlocking { withTimeout(5000) {
                        while(writerThreads.any { it.isAlive }) kotlinx.coroutines.delay(20)
                    } }
                    Thread.sleep(350)
                }
            }
        }
    }
}
