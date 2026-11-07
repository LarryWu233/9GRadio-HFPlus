package com.radiosport.ninegradio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.audio.PcmRecorder
import com.radiosport.ninegradio.dsp.*
import com.google.gson.Gson
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.*

/** Optional replay of the user's captured AM whistle, without touching live settings. */
@RunWith(AndroidJUnit4::class)
class WhistleReplayTest {
    @Test fun compareSameCapturedAudio() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.getExternalFilesDir(null),"Audio/audio_11.916MHz_20261106_193456.wav")
        assumeTrue("Recorded 11.916 MHz sample required",file.isFile)
        val bytes=file.readBytes()
        val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2,buffer.getShort(22).toInt())
        assertEquals(16,buffer.getShort(34).toInt())
        val rate=buffer.getInt(24)
        val raw=FloatArray((bytes.size-44)/2) { buffer.getShort(44+2*it)/32768f }
        val out=raw.copyOf()
        val started=System.nanoTime()
        ListeningAudioProcessor().use { processor ->
            processor.settings=ListeningAudioProcessor.Settings(notch=true)
            var index=0
            while(index<out.size) {
                val part=out.copyOfRange(index,min(out.size,index+1920))
                processor.process(part,rate,DemodMode.AM)
                part.copyInto(out,index); index+=part.size
            }
        }
        fun tone(data: FloatArray,hz: Double): Double {
            var re=0.0; var im=0.0
            for(i in rate*3 until data.size/2) {
                re+=data[2*i]*cos(2*PI*hz*i/rate); im+=data[2*i]*sin(2*PI*hz*i/rate)
            }
            return 2*hypot(re,im)/(data.size/2-rate*3)
        }
        val elapsed=(System.nanoTime()-started)/1e6
        // The recorded peak is near 5001 Hz; compare the same coherent bin.
        val peaks=(4990..5010).associateWith { tone(raw,it.toDouble()) }
        val peak=peaks.maxBy { it.value }.key
        val suppression=20*log10(tone(out,peak.toDouble())/tone(raw,peak.toDouble()))
        val recorder=PcmRecorder(File(context.cacheDir,"alpha11-whistle-filtered.wav").absolutePath,rate,2)
        recorder.start(); recorder.writeSamples(out); recorder.stop()
        File(context.cacheDir,"alpha11-whistle-replay.json").writeText(Gson().toJson(mapOf(
            "sample" to file.name,"rate" to rate,"seconds" to raw.size.toDouble()/2/rate,
            "peakHz" to peak,"peakSuppressionDb" to suppression,"processingMs" to elapsed,
            "rawPeak" to raw.maxOf { abs(it) },"filteredPeak" to out.maxOf { abs(it) },
            "leveling" to false,"notch" to true,"speechIntelligibility" to "requires listening comparison")))
        assertTrue("Suppression $suppression dB",suppression< -15)
        assertTrue(out.all { it.isFinite() })
    }
}
