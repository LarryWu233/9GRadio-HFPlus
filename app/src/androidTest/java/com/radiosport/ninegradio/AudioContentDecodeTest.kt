package com.radiosport.ninegradio

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.content.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class AudioContentDecodeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    /** Synthetic protocol fixtures, never presented as over-the-air receptions. */
    private class Signal(val file: File,val rate: Int=48000): java.io.Closeable {
        val out=file.outputStream().buffered(); var frames=0; var phase=0.0
        init { out.write(ByteArray(44)) }
        fun sample(value: Double) { val pcm=(value.coerceIn(-1.0,.9999)*32767).roundToInt(); out.write(pcm and 255); out.write((pcm shr 8) and 255); frames++ }
        fun tone(hz: Double,seconds: Double,amplitude: Double=.5) {
            repeat((seconds*rate).roundToInt()) { sample(amplitude*sin(phase)); phase=(phase+2*PI*hz/rate)%(2*PI) }
        }
        fun header(vis: Int) {
            tone(1900.0,.3); tone(1200.0,.01); tone(1900.0,.3); tone(1200.0,.03)
            val code=vis or ((Integer.bitCount(vis) and 1) shl 7)
            repeat(8) { tone(if(code and (1 shl it)==0) 1300.0 else 1100.0,.03) }; tone(1200.0,.03)
        }
        override fun close() { out.close()
            val b=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36+frames*2).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(rate).putInt(rate*2).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(frames*2)
            java.io.RandomAccessFile(file,"rw").use { it.write(b.array()) }
        }
    }
    private fun decode(config: ContentConfig,file: File,check: (ContentState)->Unit) {
        val receiver=AudioContentReceiver(context); val before=receiver.files().toSet()
        try {
            receiver.start(config.copy(input=ContentInput.WAV),Uri.fromFile(file))
            val end=System.currentTimeMillis()+90_000
            while(receiver.state.value.running && System.currentTimeMillis()<end) Thread.sleep(50)
            val state=receiver.state.value
            assertFalse("Decoder timeout: ${state.status}",state.running)
            assertEquals("录音处理结束",state.status)
            check(state)
        } finally { receiver.stop(); receiver.files().filter { it !in before }.forEach { it.delete() }; file.delete() }
    }
    @Test fun morseWavThroughNativeCoreAndResampler() {
        val file=File(context.cacheDir,"content-cw.wav")
        val codes=mapOf('V' to "...-",'C' to "-.-.",'Q' to "--.-",'T' to "-",'E' to ".",'S' to "...",'1' to ".----",'2' to "..---",'3' to "...--")
        Signal(file).use { s ->
            s.tone(700.0,1.0,0.0)
            "VVV CQ TEST 123".forEach { c ->
                if(c==' ') s.tone(700.0,.24,0.0) else {
                    codes.getValue(c).forEach { mark -> s.tone(700.0,if(mark=='.') .06 else .18); s.tone(700.0,.06,0.0) }
                    s.tone(700.0,.12,0.0)
                }
            }; s.tone(700.0,2.0,0.0)
        }
        val automatic=File(context.cacheDir,"content-cw-auto.wav").also { file.copyTo(it,true) }
        decode(ContentConfig(AudioContentKind.CW,wpm=20),file) { state ->
            val text=state.text.replace(Regex("\\s+"),"")
            assertTrue("Decoded: ${state.text}",text.contains("CQTEST123"))
        }
        decode(ContentConfig(AudioContentKind.CW),automatic) { state ->
            assertTrue("Auto decoded: ${state.text}",state.text.replace(Regex("\\s+"),"").contains("CQTEST123"))
        }
    }
    @Test fun robot36WavProducesCompleteImageAndSilenceDoesNot() {
        val file=File(context.cacheDir,"content-sstv.wav")
        Signal(file).use { s -> s.header(8)
            repeat(240) { i ->
                s.tone(1200.0,.009); s.tone(1500.0,.003); s.tone(2100.0,.088)
                s.tone(if(i%2==0) 1500.0 else 2300.0,.0045); s.tone(1900.0,.0015); s.tone(1900.0,.044)
            }
        }
        decode(ContentConfig(AudioContentKind.SSTV),file) { state ->
            assertEquals("Robot 36 Color",state.mode); assertEquals(240,state.lines)
            assertEquals(240,state.totalLines); assertEquals(320,state.image!!.width)
            val color=state.image!!.getPixel(160,120)
            assertTrue(android.graphics.Color.red(color)>80)
        }
        val silence=File(context.cacheDir,"content-silence.wav")
        Signal(silence).use { it.tone(1200.0,3.0,0.0) }
        decode(ContentConfig(AudioContentKind.SAT),silence) { assertNull(it.image); assertEquals(0,it.lines) }
    }
    @Test fun aptAmplitudePatternIsDecodedAndSessionsReset() {
        repeat(2) { session ->
            val file=File(context.cacheDir,"content-apt-$session.wav")
            Signal(file).use { s ->
                val sync=intArrayOf(-14,-14,-14,18,18,-14,-14,18,18,-14,-14,18,18,-14,-14,18,18,-14,-14,18,18,-14,-14,18,18,-14,-14,18,18,-14,-14,-14)
                repeat(48000*20) { n ->
                    val x=((n.toLong()*4160/48000)%2080).toInt()
                    val value=when {
                        x<sync.size -> if(sync[x]>0) .9 else .1
                        x<86 -> .1
                        x<500 -> .2
                        x<995 -> .8
                        x<1126 -> .1
                        x<1600 -> .8
                        else -> .2
                    }
                    s.sample(.8*value*cos(2*PI*2400*n/48000))
                }
            }
            decode(ContentConfig(AudioContentKind.SAT,apt=true),file) { state ->
                assertTrue("Rows ${state.lines}",state.lines>=25)
                assertEquals(2080,state.image!!.width)
                fun mean(a: Int,b: Int): Double {
                    var total=0.0; var count=0
                    for(y in 10 until min(25,state.image!!.height)) for(x in a until b) { total+=android.graphics.Color.red(state.image!!.getPixel(x,y)); count++ }
                    return total/count
                }
                assertTrue("APT amplitude contrast ${mean(700,800)-mean(200,300)}",mean(700,800)>mean(200,300)+20)
            }
        }
    }
    @Test fun canceledImportCannotOverwriteNextSessionAndMalformedWavFailsCleanly() {
        val receiver=AudioContentReceiver(context)
        val file=File(context.cacheDir,"content-invalid.wav").apply { writeText("invalid") }
        try {
            receiver.start(ContentConfig(AudioContentKind.SSTV,ContentInput.WAV),Uri.fromFile(file))
            Thread.sleep(20); receiver.stop()
            receiver.start(ContentConfig(AudioContentKind.CW,ContentInput.SOFTWARE))
            Thread.sleep(300)
            assertFalse(receiver.state.value.running)
            assertEquals(AudioContentKind.CW,receiver.state.value.config?.kind)
            assertEquals("请先打开接收机",receiver.state.value.status)
            receiver.start(ContentConfig(AudioContentKind.SAT,ContentInput.WAV),Uri.fromFile(file))
            val end=System.currentTimeMillis()+5000
            while(receiver.state.value.running && System.currentTimeMillis()<end) Thread.sleep(50)
            assertFalse(receiver.state.value.running)
            assertTrue(receiver.state.value.status.startsWith("音频输入失败") || receiver.state.value.status.startsWith("无法解码音频"))
        } finally { receiver.stop(); file.delete() }
    }
}
