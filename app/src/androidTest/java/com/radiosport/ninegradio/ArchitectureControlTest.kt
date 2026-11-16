package com.radiosport.ninegradio

import android.content.*
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.source.IqSource
import com.radiosport.ninegradio.usb.RtlSdrService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Controller fault/regression test. Synthetic source; no USB, UI or saved settings. */
@RunWith(AndroidJUnit4::class)
class ArchitectureControlTest {
    @Test fun driverFailureStopsTheSessionAndRequiresManualRecovery():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val bound=CompletableDeferred<RtlSdrService>()
        val connection=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName,binder:IBinder) {
                bound.complete((binder as RtlSdrService.LocalBinder).getService())
            }
            override fun onServiceDisconnected(name:ComponentName)=Unit
        }
        assertTrue(context.bindService(Intent(context,RtlSdrService::class.java),connection,Context.BIND_AUTO_CREATE))
        val service=withTimeout(5000) { bound.await() }
        val failOnce=java.util.concurrent.atomic.AtomicBoolean(true)
        val latePolls=java.util.concurrent.atomic.AtomicLong()
        try {
            val factory:()->IqSource={
                val delegate=ArchitectureLifecycleTest.Source()
                val status=kotlinx.coroutines.flow.MutableStateFlow(IqSource.SourceStatus(connected=true))
                object:IqSource by delegate {
                    override val statusFlow=status
                    override fun pollStreamingStatus() {
                        if(!delegate.isOpen()) latePolls.incrementAndGet()
                        if(failOnce.compareAndSet(true,false)) status.value=status.value.copy(
                            error="simulated driver stream termination",streamRestartRequired=true)
                    }
                }
            }
            RtlSdrService::class.java.getDeclaredField("selectedFactory").apply { isAccessible=true }.set(service,factory)
            RtlSdrService::class.java.getDeclaredMethod("switchSource",Function0::class.java,java.lang.Integer::class.java)
                .apply { isAccessible=true }.invoke(service,factory,null)
            withTimeout(10000) { service.connectionState.first { it is RtlSdrService.ConnectionState.Connected } }
            val first=service.source!!
            withTimeout(7000) { service.connectionState.first { it is RtlSdrService.ConnectionState.Error } }
            service.awaitControls()
            assertFalse(first.isOpen());assertNull(service.source)
            assertTrue("Closed session keeps a diagnostic summary",service.pipelineDiagnostics()!=null)
            delay(1200)
            assertTrue("Driver errors must not oscillate OPEN/CLOSED",service.connectionState.value is RtlSdrService.ConnectionState.Error)
            service.setReceiverEnabled(true);service.awaitControls()
            assertTrue(service.connectionState.value is RtlSdrService.ConnectionState.Connected)
            assertNotSame(first,service.source)
            assertEquals("Obsolete poll work cannot access a closed source",0L,latePolls.get())
        } finally {
            service.disconnectSource();service.awaitControls();context.unbindService(connection)
        }
    }
    @Test fun rateRestartRestoresControlsAndRejectsOldScannerLease():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val bound=CompletableDeferred<RtlSdrService>()
        val connection=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName,binder:IBinder) {
                bound.complete((binder as RtlSdrService.LocalBinder).getService())
            }
            override fun onServiceDisconnected(name:ComponentName)=Unit
        }
        assertTrue(context.bindService(Intent(context,RtlSdrService::class.java),connection,Context.BIND_AUTO_CREATE))
        val service=withTimeout(5000) { bound.await() }
        try {
            val factory:()->IqSource={ ArchitectureLifecycleTest.Source() }
            // Keep test-only factories out of the published application API.
            RtlSdrService::class.java.getDeclaredField("selectedFactory").apply { isAccessible=true }.set(service,factory)
            RtlSdrService::class.java.getDeclaredMethod("switchSource",Function0::class.java,java.lang.Integer::class.java)
                .apply { isAccessible=true }.invoke(service,factory,null)
            withTimeout(10000) { service.connectionState.first { it is RtlSdrService.ConnectionState.Connected } }
            service.setDemodMode(DemodMode.USB);service.setIfBandwidth(12000)
            service.setSquelch(-75f);service.setAudioVolume(.2f);service.setFrequency(14200000,true)
            service.awaitControls()
            val original=service.source!!
            val oldTuner=service.scannerTuner(original)
            val settings=service.dspEngine!!.controlSnapshot()
            assertEquals(14200000L,settings.dialHz)
            service.setSampleRate(96000);service.awaitControls()
            assertNotSame("Rate change must join and replace the old source",original,service.source)
            assertFalse(original.isOpen());assertEquals(96000,service.source!!.getSampleRate())
            assertEquals(settings,service.dspEngine!!.controlSnapshot())
            val center=service.source!!.getCenterFrequency()
            assertFalse("Old scan control cannot touch a replacement source",oldTuner.tune(20000000))
            assertEquals(center,service.source!!.getCenterFrequency())
            val currentTuner=service.scannerTuner(service.source!!)
            service.setReceiverEnabled(false);service.awaitControls()
            assertFalse(currentTuner.tune(20000000));assertNull(service.source)
        } finally {
            service.disconnectSource();service.awaitControls();context.unbindService(connection)
        }
    }
}
