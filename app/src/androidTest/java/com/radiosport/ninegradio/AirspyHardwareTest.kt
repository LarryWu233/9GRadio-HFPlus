package com.radiosport.ninegradio

import android.content.Context
import android.hardware.usb.UsbManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.source.AirspyHfSource
import com.radiosport.ninegradio.source.IqBlock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AirspyHardwareTest {
    @Test fun authorizedReceiverStreamsFullPrecisionIqAndRejectsGaps() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = manager.deviceList.values.firstOrNull { AirspyHfSource.isSupported(it) }
        assertNotNull("Connect the HF+ receiver before this hardware test", device)
        assertTrue("Grant the app USB permission first", manager.hasPermission(device))
        val source = AirspyHfSource(manager, device!!)
        assertTrue("Driver open failed", source.open())
        try {
            assertTrue(source.capabilities.floatIq)
            for (frequency in listOf(500L, 31_000_000L, 64_000_000L, 260_000_000L, 10_000_000L))
                assertTrue("Cannot tune $frequency", source.setCenterFrequency(frequency))
            assertFalse(source.setCenterFrequency(31_000_001L))
            assertFalse(source.setCenterFrequency(63_999_999L))
            assertFalse(source.setCenterFrequency(260_000_001L))
            assertEquals(10_000_000L, source.getCenterFrequency())
            val block = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(10000) { source.iqFlow.first { it.sampleCount > 0 } }
            }
            source.startStreaming()
            val iq = block.await() as IqBlock.Float32
            assertEquals(4096, iq.sampleCount)
            assertTrue(iq.values.all { it.isFinite() })
            assertTrue("All samples are zero", iq.values.any { it != 0f })
            assertTrue("Expected more than uint8 precision", iq.values.any { kotlin.math.abs(it * 128 - kotlin.math.round(it * 128)) > 0.00001f })
            println("HF+ supported rates: ${source.capabilities.sampleRates}; sample count: ${iq.sampleCount}")
        } finally { source.close() }
    }
}
