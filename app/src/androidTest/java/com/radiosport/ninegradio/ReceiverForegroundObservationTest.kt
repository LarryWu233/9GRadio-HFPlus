package com.radiosport.ninegradio

import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.ui.MainActivity
import com.radiosport.ninegradio.ui.MainViewModel
import com.radiosport.ninegradio.usb.RtlSdrService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit, short foreground comparison. Requires iq_fixture.py and adb reverse tcp:1234.
 * Exercises the real Activity, spectrum, waterfall and service, unlike the headless observer.
 * Reports process CPU, not battery power. Restores preferences; never records received content.
 */
@RunWith(AndroidJUnit4::class)
class ReceiverForegroundObservationTest {
    @Test fun observeForegroundReceiver() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val seconds=InstrumentationRegistry.getArguments().getString("observationSeconds")
            ?.toIntOrNull()?.coerceIn(10,30) ?: 20
        ReceiverTestPreferences(inst.targetContext).use {
            inst.targetContext.getSharedPreferences("rtlsdr_prefs",android.content.Context.MODE_PRIVATE).edit()
                .putString("activeDevice","rtl").putString("demod","FM").putInt("rate",2_400_000)
                .putString("device_rtl_mode","FM").putInt("device_rtl_rate",2_400_000)
                .putInt("device_rtl_width",180_000).putLong("device_rtl_freq",87_800_000L)
                .putBoolean("autoDemod",false).putBoolean("modeStationMemory",false).commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm:MainViewModel
                scenario.onActivity { activity ->
                    vm=ViewModelProvider(activity)[MainViewModel::class.java]
                }
                try {
                runBlocking { withTimeout(10000) { vm.serviceConnected.first { it } } }
                inst.runOnMainSync { vm.connectTcp("127.0.0.1",1234) }
                // The initial source adoption intentionally restores that device's
                // saved profile. Apply the comparison settings after that boundary.
                runBlocking { withTimeout(20000) { vm.connectionState.first { it is RtlSdrService.ConnectionState.Connected } } }
                inst.sendStatus(0,Bundle().apply { putString("stream","\nFOREGROUND_SOURCE_CONNECTED\n") })
                inst.runOnMainSync {
                    vm.setAutoDemod(false);vm.setModeStationMemory(false)
                    vm.setReceiverEnabled(true);vm.setDemodMode(DemodMode.FM)
                    vm.setSampleRate(2_400_000);vm.setIfBandwidth(180_000)
                    vm.setFftSize(2048);vm.setDecimation(2)
                    vm.setNoiseBlanker(false);vm.setNoiseReducer(false)
                    vm.setSquelch(-120f);vm.setVolume(0f)
                    vm.setFrequency(87_800_000L)
                }
                inst.sendStatus(0,Bundle().apply { putString("stream","\nFOREGROUND_SETTINGS_APPLIED rate=${vm.sampleRate.value} mode=${vm.demodMode.value}\n") })
                runBlocking { withTimeout(10000) { vm.dspStats.first { it.sampleRate==2_400_000 } } }
                Thread.sleep(6000)
                inst.sendStatus(0,Bundle().apply { putString("stream","\nFOREGROUND_OBSERVATION_STARTED\n") })
                val cpu=Process.getElapsedCpuTime()
                val start=SystemClock.elapsedRealtimeNanos()
                Thread.sleep(seconds*1000L)
                val elapsed=SystemClock.elapsedRealtimeNanos()-start
                val cpuMs=Process.getElapsedCpuTime()-cpu
                val memory=Debug.MemoryInfo().apply { Debug.getMemoryInfo(this) }
                val report=JSONObject().apply {
                    put("version",inst.targetContext.packageManager.getPackageInfo(inst.targetContext.packageName,0).versionName)
                    put("durationNs",elapsed);put("cpuMs",cpuMs);put("endPssKb",memory.totalPss)
                    put("sourceRate",vm.dspStats.value.sampleRate)
                    put("fftSize",2048);put("fftDecimation",2);put("bandwidthHz",180000)
                }
                inst.sendStatus(0,Bundle().apply { putString("stream","\nFOREGROUND_RESOURCE_OBSERVATION=$report\n") })
                assertTrue("Receiver must stay active",vm.dspStats.value.sampleRate==2_400_000)
                } finally {
                    inst.runOnMainSync { vm.setReceiverEnabled(false) }
                    Thread.sleep(350)
                }
            }
        }
    }
}
