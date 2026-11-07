package com.radiosport.ninegradio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.source.DeviceCapabilities
import com.radiosport.ninegradio.ui.ReceiverControlUi
import com.radiosport.ninegradio.ui.WaterfallView
import com.radiosport.ninegradio.ui.FrequencyView
import com.radiosport.ninegradio.ui.ControlsPagerAdapter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiverPresentationTest {
    @Test fun waterfallRetainsNarrowCarrierInFullSpan() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view=WaterfallView(instrumentation.targetContext)
            view.layout(0,0,256,200)
            view.setPalette(WaterfallView.Companion.Palette.GRAYSCALE)
            view.setDynamicRange(-120f,0f)
            view.setShowTimestamp(false); view.setShowTunerMarker(false)
            view.addLine(FloatArray(4096) { if(it==202) -10f else -120f })
            val bitmap=Bitmap.createBitmap(256,200,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            assertTrue("Carrier between old interpolation positions must remain bright",Color.red(bitmap.getPixel(12,0))>220)
            assertTrue("Neighbouring noise stays dark",Color.red(bitmap.getPixel(11,0))<10)
            bitmap.recycle()
        }
    }
    @Test fun plotCenterStaysOnDialAfterFractionalBinRetunes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val spectrum = com.radiosport.ninegradio.ui.SpectrumView(instrumentation.targetContext)
            val waterfall = WaterfallView(instrumentation.targetContext)
            spectrum.layout(0,0,400,400); waterfall.layout(0,0,400,400)
            spectrum.setSampleRate(912000); waterfall.setSampleRate(912000)
            for (hz in listOf(100000000L, 100012345L, 99000500L, 100000000L)) {
                spectrum.setCenterFrequency(hz); spectrum.setHardwareTunedFrequency(hz)
                waterfall.setCenterFrequency(hz); waterfall.setHardwareTunedFrequency(hz)
                assertEquals(hz, spectrum.frequencyAtPixel(200f))
                assertEquals(hz, waterfall.frequencyAtPixel(200f))
                assertEquals(spectrum.frequencyAtPixel(100f), waterfall.frequencyAtPixel(100f))
            }
        }
    }
    @Test fun offlineAirportDataHasValidWorldwideChannels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val airports = context.assets.open("receiving/airports.json").bufferedReader().use {
            com.google.gson.Gson().fromJson<List<com.radiosport.ninegradio.data.RegionalReception.Airport>>(it,
                object : com.google.gson.reflect.TypeToken<List<com.radiosport.ninegradio.data.RegionalReception.Airport>>() {}.type)
        }
        assertTrue(airports.size > 10000)
        assertTrue(airports.count { it.country == "CN" } > 50)
        assertTrue(airports.flatMap { it.channels }.all { it.hz in 118_000_000..136_975_000 })
        val beijing = airports.first { it.ident == "ZBAA" }
        val result = com.radiosport.ninegradio.data.RegionalReception.nearby(airports, beijing.lat, beijing.lon)
        assertEquals("ZBAA", result.first().first.ident)
        assertEquals(0.0, result.first().second, 0.0001)
    }
    @Test fun frequencyRenderingIsSilentAndFineTuningSupportsHfLowerEdge() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = FrequencyView(instrumentation.targetContext)
            var changes = 0
            view.onFrequencyChanged = { changes++ }
            view.frequencyLimiter = { current, target -> DeviceCapabilities.airspy().stepFrequency(current, target) }
            view.setFrequency(10000000)
            view.setFrequency(500)
            assertEquals(0, changes)
            view.setTuningStep(1)
            view.stepDown()
            assertEquals(500L, view.getFrequency())
            view.stepUp()
            assertEquals(501L, view.getFrequency())
            assertEquals(1, changes)
            val order = listOf(3, 0, 5, 1, 4, 2) + (6 until ControlsPagerAdapter.TAB_COUNT)
            val adapter = ControlsPagerAdapter(order)
            assertEquals(2, adapter.positionOf(ControlsPagerAdapter.TAB_SETTINGS))
            assertEquals(ControlsPagerAdapter.TAB_RF, adapter.moduleAt(5))
        }
    }
    @Test fun receiverRowsHideAndRestoreWithCapabilities() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_NineGRadio)
            val root = LayoutInflater.from(context).inflate(R.layout.fragment_tab_rf, null)
            ReceiverControlUi.apply(root, DeviceCapabilities.airspy())
            for (id in listOf(R.id.rowBiasTee, R.id.rowDirectSampling, R.id.rowRtlAgc, R.id.tvReceiverHardwareTitle))
                assertEquals(View.GONE, root.findViewById<View>(id).visibility)
            assertEquals(context.getString(R.string.receiver_attenuation), root.findViewById<TextView>(R.id.tvReceiverGainTitle).text)
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.rowReceiverAgc).visibility)
            ReceiverControlUi.apply(root, DeviceCapabilities.RTL)
            for (id in listOf(R.id.rowBiasTee, R.id.rowDirectSampling, R.id.rowRtlAgc, R.id.tvReceiverHardwareTitle))
                assertEquals(View.VISIBLE, root.findViewById<View>(id).visibility)
            ReceiverControlUi.apply(root, DeviceCapabilities.RTL.copy(id = "file", biasTee = false,
                directSampling = false, hardwareAgc = false))
            assertEquals(View.GONE, root.findViewById<View>(R.id.rowReceiverGain).visibility)
        }
    }

    @Test fun resizingWaterfallPreservesReceptionHistory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = WaterfallView(instrumentation.targetContext)
            view.layout(0, 0, 400, 400)
            repeat(64) { view.addLine(FloatArray(256) { -40f }) }
            fun colourAtTop(height: Int): Int {
                val image = Bitmap.createBitmap(400, height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(image))
                val colour = image.getPixel(20, 3)
                image.recycle()
                return colour
            }
            assertNotEquals(Color.BLACK, colourAtTop(400))
            view.layout(0, 0, 400, 600)
            assertNotEquals(Color.BLACK, colourAtTop(600))
            // Cross every tile boundary and wrap the entire history. Each
            // source line maps to exactly one destination row in this layout.
            view.layout(0, 0, 400, 1050)
            view.setPalette(WaterfallView.Companion.Palette.GRAYSCALE)
            view.setShowTimestamp(false)
            view.setShowTunerMarker(false)
            repeat(1100) { line -> view.addLine(FloatArray(256) { -120f + line % 120 }) }
            val image = Bitmap.createBitmap(400, 1050, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            for (age in listOf(0, 1, 15, 31, 32, 33, 511, 1000, 1023)) {
                val expected = ((1099 - age) % 120 * 255f / 120).toInt()
                assertEquals("History row $age", expected.toDouble(), Color.red(image.getPixel(20, age)).toDouble(), 2.0)
            }
            image.recycle()
        }
    }
}
