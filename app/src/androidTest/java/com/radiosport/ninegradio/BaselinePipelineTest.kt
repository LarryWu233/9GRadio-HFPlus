package com.radiosport.ninegradio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.dsp.DspEngine
import com.radiosport.ninegradio.dsp.NativeDsp
import com.radiosport.ninegradio.source.RtlTcpSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Requires iq_fixture.py --mode am --serve 1234 and adb reverse tcp:1234 tcp:1234. */
@RunWith(AndroidJUnit4::class)
class BaselinePipelineTest {
    @Test fun syntheticAmProducesSpectrumAndAudio(): Unit = runBlocking {
        assertTrue("Native DSP library did not load", NativeDsp.isNativeAvailable())
        val source = RtlTcpSource("127.0.0.1", 1234)
        assertTrue("Synthetic rtl_tcp source could not open", source.open())
        source.setSampleRate(1_920_000)
        val engine = DspEngine(source)
        try {
            engine.setDemodMode(DemodMode.AM)
            engine.setVolume(0f)
            engine.setSquelchLevel(-120f)
            val spectrum = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(20_000) { engine.spectrumFlow.first { frame ->
                    frame.isNotEmpty() && frame.all { it.isFinite() } &&
                        (frame.maxOrNull()!! - frame.minOrNull()!!) > 10f
                } }
            }
            val audio = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(20_000) { engine.audioFlow.first { frame ->
                    frame.isNotEmpty() && frame.all { it.isFinite() } && frame.any { abs(it) > 0.001f }
                } }
            }
            engine.start()
            assertTrue(spectrum.await().isNotEmpty())
            assertTrue(audio.await().isNotEmpty())
            engine.setListeningEnabled(false)
            engine.setSquelchLevel(0f)
            withTimeout(5000) { engine.statsFlow.first { !it.squelchOpen && it.sampleRate > 0 } }
            val closedAudio = withTimeout(5000) { engine.audioFlow.first { frame -> frame.any { abs(it) > 0.001f } } }
            assertTrue("Manual mute must preserve the decoder baseband", closedAudio.isNotEmpty())
            engine.setListeningEnabled(true)
            engine.setSquelchLevel(-120f)
            withTimeout(5000) { engine.statsFlow.first { it.squelchOpen } }
            engine.setReceptionAvailable(false)
            delay(150)
            assertNull("Blind browsing must not publish old decoder audio", withTimeoutOrNull(200) { engine.audioFlow.first() })
            engine.setReceptionAvailable(true)
            assertTrue(withTimeout(5000) { engine.audioFlow.first { it.any { value -> abs(value) > 0.001f } } }.isNotEmpty())
        } finally {
            engine.stop()
            source.close()
        }
    }
}
