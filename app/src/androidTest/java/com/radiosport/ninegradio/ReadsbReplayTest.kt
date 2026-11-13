package com.radiosport.ninegradio

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.*
import com.radiosport.ninegradio.source.FileIqSource
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.flow.first

/** Run in isolation after force-stopping the receiver; fixtures never enter live reception. */
@RunWith(AndroidJUnit4::class)
class ReadsbReplayTest {
    @Test fun rawIqPipeRunsNativeDetectionAndMergedAircraftParsing() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val fixture=File(context.cacheDir,"alpha14-reference.u8")
        assumeTrue("Push the explicit reference IQ fixture before invoking this test",fixture.isFile)
        val source=FileIqSource(fixture,2400000,1090000000,DemodMode.ADSB)
        val receiver=ReadsbReceiver(context)
        try {
            assertTrue(source.open())
            val module:com.radiosport.ninegradio.pipeline.IqDecoderModule<List<AdsbDecoder.AdsbFrame>> = receiver
            module.start(source,com.radiosport.ninegradio.pipeline.IqDecoderConfiguration(768000,
                com.radiosport.ninegradio.pipeline.IqSampleFormat.FLOAT_32))
            assertFalse("Unsupported input must be refused before subscribing",receiver.status.value.running)
            assertNotNull(kotlinx.coroutines.runBlocking { module.state.first() }.error)
            assertEquals(0,source.iqSubscriberCount)
            module.start(source,com.radiosport.ninegradio.pipeline.IqDecoderConfiguration(2400000,
                com.radiosport.ninegradio.pipeline.IqSampleFormat.UNSIGNED_8))
            val deadline=SystemClock.elapsedRealtime()+12000
            while(source.iqSubscriberCount==0 && SystemClock.elapsedRealtime()<deadline) Thread.sleep(20)
            source.startStreaming()
            var callsign=false; var altitude=false; var messages=0L
            while(SystemClock.elapsedRealtime()<deadline) {
                val status=receiver.status.value
                assertNull(status.error)
                messages=maxOf(messages,status.messages)
                val tracks=kotlinx.coroutines.runBlocking { module.results.first() }
                callsign=callsign || tracks.any { it.icao24=="4840D6" && it.callsign=="KLM1023" }
                altitude=altitude || tracks.any { it.icao24=="40621D" && it.altitude==38000 }
                if(callsign && altitude && messages>=85 && status.inputSamples==fixture.length()/2) break
                Thread.sleep(100)
            }
            assertTrue("Native CRC-valid message count: $messages",messages>=85)
            assertTrue("Native callsign decode",callsign); assertTrue("Native altitude decode",altitude)
            assertEquals(fixture.length()/2,receiver.status.value.inputSamples)
            while(receiver.status.value.running && SystemClock.elapsedRealtime()<deadline) Thread.sleep(20)
            assertFalse("Normal file EOF drains and exits",receiver.status.value.running)
            assertNull("Successful native EOF is not an input error",receiver.status.value.error)
            assertTrue("Final JSON is read after native exit",receiver.status.value.messages>=85)
            if(InstrumentationRegistry.getArguments().getString("tablePreview")=="1") {
                val tracks=receiver.aircraft.value.sortedBy { it.icao24 }
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val themed=android.view.ContextThemeWrapper(context,R.style.Theme_NineGRadio)
                    val table=com.radiosport.ninegradio.ui.AdsbAircraftTable(themed)
                    val density=context.resources.displayMetrics.density
                    val width=(376*density).toInt(); val height=(180*density).toInt()
                    table.submit(tracks)
                    table.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(height,android.view.View.MeasureSpec.EXACTLY))
                    table.layout(0,0,width,height)
                    table.viewTreeObserver.dispatchOnPreDraw()
                    val cells=ArrayList<Map<String,Any?>>()
                    fun inspect(v: android.view.View) {
                        if(v is android.widget.TextView) cells+=mapOf("text" to v.text.toString(),"width" to v.width,"height" to v.height,"layoutWidth" to v.layout?.width,"color" to v.currentTextColor)
                        if(v is android.view.ViewGroup) for(i in 0 until v.childCount) inspect(v.getChildAt(i))
                    }
                    inspect(table)
                    File(context.cacheDir,"alpha15-aircraft-table-cells.json").writeText(com.google.gson.Gson().toJson(cells))
                    for((name,x) in listOf("start" to 0,"end" to table.getChildAt(0).width-width)) {
                        table.scrollTo(x,0)
                        val image=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888)
                        table.draw(android.graphics.Canvas(image))
                        File(context.cacheDir,"alpha15-aircraft-table-$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                        image.recycle()
                    }
                    File(context.cacheDir,"alpha15-aircraft-table-data.json").writeText(com.google.gson.Gson().toJson(tracks))
                }
            }
        } finally { receiver.stop(); source.close() }
        assertFalse(receiver.status.value.running); assertTrue(receiver.aircraft.value.isEmpty())
    }
}
