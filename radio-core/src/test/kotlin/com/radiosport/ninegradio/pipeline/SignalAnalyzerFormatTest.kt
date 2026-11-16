package com.radiosport.ninegradio.pipeline

import com.radiosport.ninegradio.dsp.DemodMode
import org.junit.Assert.*
import org.junit.Test

class SignalAnalyzerFormatTest {
    @Test fun directU8MeterMatchesExistingFloatCalibrationAcrossBlocks() {
        val raw=SignalAnalyzer();val reference=SignalAnalyzer()
        repeat(8) { block ->
            val bytes=ByteArray(8192) { i -> ((i*17+block*31)%211+20).toByte() }
            val floats=FloatArray(bytes.size) { ((bytes[it].toInt() and 255)-127.5f)/128f }
            val now=1_000_000_000L+block*2_000_000L
            val a=raw.processUnsigned8(bytes,1090000000,1090000000,DemodMode.ADSB,1000000,2400000,0,now)
            val b=reference.process(floats,1090000000,1090000000,DemodMode.ADSB,1000000,2400000,0,now)
            assertEquals(b.channel.valid,a.channel.valid)
            assertEquals(b.channel.levelDb,a.channel.levelDb,.0001f)
            assertEquals(b.widebandPeakDb,a.widebandPeakDb,.0001f)
            assertEquals(b.scannerCenterDb,a.scannerCenterDb,.0001f)
        }
    }
}
