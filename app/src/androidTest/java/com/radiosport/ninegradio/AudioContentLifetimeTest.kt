package com.radiosport.ninegradio

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.content.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exercises real file readers and the packaged native decoder, without microphone permission. */
@RunWith(AndroidJUnit4::class)
class AudioContentLifetimeTest {
    @Test fun repeatedWavCancellationJoinsReadersAndNativeProcesses() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"architecture-content-lifetime.wav")
        val frames=48000*30
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36+frames*2).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(48000).putInt(96000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(frames*2).array()
        file.outputStream().use { it.write(header);it.write(ByteArray(frames*2)) }
        fun workers()=Thread.getAllStackTraces().keys.count {
            it.isAlive && (it.name.startsWith("content-") || it.name=="AudioSource")
        }
        val before=workers()
        val receiver=AudioContentReceiver(context)
        try {
            repeat(30) { cycle ->
                receiver.start(ContentConfig(AudioContentKind.CW,ContentInput.WAV),Uri.fromFile(file))
                val deadline=SystemClock.elapsedRealtime()+5000
                while(receiver.state.value.sampleCount==0L && receiver.state.value.running && SystemClock.elapsedRealtime()<deadline)
                    Thread.sleep(5)
                assertTrue("Decoder actually received PCM on cycle $cycle",receiver.state.value.sampleCount>0)
                val start=SystemClock.elapsedRealtime()
                receiver.stop()
                assertFalse("All resources released on cycle $cycle",receiver.hasPendingRelease)
                assertFalse(receiver.state.value.running)
                assertTrue("Stop exceeded its release budget",SystemClock.elapsedRealtime()-start<5000)
            }
            Thread.sleep(100)
            assertTrue("Content worker count grew: ${workers()} vs $before",workers()<=before)
        } finally { receiver.stop();file.delete() }
    }
}
