package com.radiosport.ninegradio.scanner

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

class EtmSignalDetectorTest {
    private val n = 2048
    private val rate = 912000
    private val center = 100000000L
    private fun bins() = FloatArray(n) { -100f + Random(it.toLong()).nextFloat() * 4 }
    private fun index(hz: Long) = ((hz - center) * n / rate + n / 2).toInt()
    @Test fun noiseAndSingleSpikesAreNotFmStations() {
        repeat(20) { seed ->
            val random = Random(seed.toLong())
            val noise = FloatArray(n) { -100f + random.nextGaussian().toFloat() * 3 }
            assertTrue(EtmSignalDetector.detect(noise, center, rate, true, 14f).isEmpty())
            assertTrue(EtmSignalDetector.detect(noise, center, rate, false, 14f).isEmpty())
        }
        val noise = bins(); noise[index(100200000)] = -35f
        assertTrue(EtmSignalDetector.detect(noise, center, rate, true, 14f).isEmpty())
        assertTrue(EtmSignalDetector.detect(noise, center, rate, false, 14f).isEmpty())
        assertTrue(EtmSignalDetector.detect(FloatArray(n) { -40f }, center, rate, true, 14f).isEmpty())
    }
    @Test fun broadFmChannelProducesOneCandidate() {
        val db = bins()
        for (i in db.indices) if (abs(center + (i - n/2) * rate.toDouble()/n - 100200000) < 70000) db[i] = -65f
        val result = EtmSignalDetector.detect(db, center, rate, true, 14f)
        assertEquals(1, result.size)
        assertEquals(100200000L, result.single().hz)
    }
    @Test fun supportedAmCarrierIsDetectedButDcIsIgnored() {
        val db = bins(); val i = index(100200000)
        db[i] = -60f; db[i-1] = -66f; db[i+1] = -66f
        db[n/2] = -20f
        val result = EtmSignalDetector.detect(db, center, rate, false, 14f)
        assertEquals(1, result.size)
        assertTrue(abs(result.single().hz - 100200000) <= 5000)
    }
}
