package com.radiosport.ninegradio

import android.content.*
import android.hardware.usb.UsbManager
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.source.AirspyHfSource
import com.radiosport.ninegradio.usb.RtlSdrService
import com.radiosport.ninegradio.scanner.FrequencyScanner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiverPowerTest {
    @Test fun closeReleasesUsbAndOpenCreatesANewReceiver(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = manager.deviceList.values.first { AirspyHfSource.isSupported(it) }
        assertTrue(manager.hasPermission(device))
        val bound = CompletableDeferred<RtlSdrService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                bound.complete((binder as RtlSdrService.LocalBinder).getService())
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        context.bindService(Intent(context, RtlSdrService::class.java), connection, Context.BIND_AUTO_CREATE)
        val service = withTimeout(5000) { bound.await() }
        try {
            service.reconnect(device)
            withTimeout(10000) { service.connectionState.first { it is RtlSdrService.ConnectionState.Connected } }
            val old = service.source!!
            service.setReceiverEnabled(false)
            withTimeout(5000) { service.connectionState.first { it is RtlSdrService.ConnectionState.Disconnected } }
            assertNull(service.source)
            assertNull(service.dspEngine)
            assertFalse(old.isOpen())
            assertEquals("airspyhf", service.capabilities.id)
            service.setReceiverEnabled(true)
            withTimeout(10000) { service.connectionState.first { it is RtlSdrService.ConnectionState.Connected } }
            assertNotSame(old, service.source)
            assertTrue(service.source!!.isOpen())
            val scanner = FrequencyScanner(service.source!!)
            try {
                scanner.startScan(FrequencyScanner.ScanConfig(10_000_000, 10_200_000,
                    adaptiveSquelch = true, holdOnSignal = false, resumeTimeMs = 0,
                    singlePass = true))
                withTimeout(5000) { scanner.status.first { it.state == FrequencyScanner.ScanState.SCANNING } }
                withTimeout(15000) { scanner.status.first { it.state == FrequencyScanner.ScanState.STOPPED } }
            } finally { scanner.stopAndJoin() }
            val reopened = service.source
            repeat(4) { service.setReceiverEnabled(false); service.setReceiverEnabled(true) }
            withTimeout(10000) { while (service.source === reopened || service.source == null ||
                    service.connectionState.value !is RtlSdrService.ConnectionState.Connected) delay(50) }
            assertTrue(service.source!!.isOpen())
        } finally {
            service.setReceiverEnabled(false)
            withTimeout(5000) { service.connectionState.first { it is RtlSdrService.ConnectionState.Disconnected } }
            context.unbindService(connection)
        }
    }
}
