package com.radiosport.ninegradio.dsp

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Optional reproducible local speech-quality benchmark; no recording leaves the workspace. */
class NoiseSpeechBenchmarkTest {
    @Test fun noisySpeechImprovesAfterVoiceLevelMatching() {
        val folder = File(System.getProperty("ninegradio.nrFixtureDir", "../build/audit/nr-fixtures"))
        assumeTrue("Generate the local speech fixture before running this benchmark", File(folder, "reference.f32").exists())
        fun read(name: String): FloatArray {
            val bytes = File(folder, name).readBytes()
            return FloatArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
        }
        val clean = read("reference.f32")
        val noisy = read("noisy.f32")
        val output = SpectralNoiseReducer().process(noisy)
        fun score(audio: FloatArray, latency: Int): Pair<Double, Double> {
            var dot = 0.0; var cleanPower = 0.0
            for (i in 96000 + latency until audio.size) {
                val speech = clean[i - latency].toDouble()
                dot += audio[i] * speech
                cleanPower += speech * speech
            }
            val scale = dot / cleanPower
            var error = 0.0
            for (i in 96000 + latency until audio.size) error += (audio[i] - scale * clean[i - latency]).pow(2)
            return 10 * log10(scale * scale * cleanPower / error) to scale
        }
        val before = score(noisy, 0)
        val after = score(output, 1024)
        fun writeWav(name: String, audio: FloatArray, scale: Double, latency: Int) {
            val count = audio.size - latency
            val bytes = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray()).putInt(36 + count * 2).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(48000).putInt(96000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(count * 2)
            for (i in latency until audio.size) bytes.putShort((audio[i] / scale * 32767).toInt().coerceIn(-32768, 32767).toShort())
            File(folder, name).writeBytes(bytes.array())
        }
        writeWav("before-level-matched.wav", noisy, before.second, 0)
        writeWav("after-level-matched.wav", output, after.second, 1024)
        File(folder, "quality.json").writeText("""{"before_sdr_db":${before.first},"after_sdr_db":${after.first},"voice_gain":${after.second},"latency_samples":1024,"sample_rate":48000}""")
        println("Speech SDR before=${before.first}, after=${after.first}, voice gain=${after.second}")
        assertTrue("Speech quality degraded", after.first > before.first + 1)
        assertTrue("Too much voice attenuation", after.second > 0.55)
    }
}
