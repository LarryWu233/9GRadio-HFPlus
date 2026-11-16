package com.radiosport.ninegradio

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.content.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit private reference fixtures: ARRL publication, optionally mixed with
 * deterministic acoustic noise. Never packaged or represented as live reception. */
@RunWith(AndroidJUnit4::class)
class CwReferenceTest {
    @Test fun independentAudioAndNoisyAudioUseTrackedToneDecoder() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val files=listOf("cw-arrl-35s.wav","cw-arrl-noisy.wav").map { File(context.cacheDir,it) }
        for(file in files) if(!file.isFile) {
            File(context.getExternalFilesDir(null),file.name).takeIf { it.isFile }?.copyTo(file)
        }
        assumeTrue(files.all { it.isFile })
        for(file in files) {
            val receiver=AudioContentReceiver(context)
            try {
                receiver.start(ContentConfig(AudioContentKind.CW,ContentInput.WAV),Uri.fromFile(file))
                val deadline=android.os.SystemClock.elapsedRealtime()+15000
                while(receiver.state.value.running && android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(25)
                val state=receiver.state.value
                assertFalse(state.running);assertEquals("录音处理结束",state.status)
                val normalized=state.text.replace(Regex("[^A-Z0-9]"),"")
                assertTrue("Independent reference: ${state.text}",normalized.contains("NOW20WPM"))
                assertTrue("Independent reference tail: ${state.text}",normalized.contains("TEXTISFROMMAY2024"))
                assertTrue(state.mode.contains("Hz"))
            } finally { receiver.stop() }
            assertFalse(receiver.hasPendingRelease)
        }
    }
}
