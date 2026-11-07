package com.radiosport.ninegradio

import com.radiosport.ninegradio.audio.PcmRecorder
import com.radiosport.ninegradio.recording.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RecordingFormatsTest {
    private fun wave(depth:Int,values:FloatArray,test:(ByteBuffer)->Unit) {
        val file=File("build/test-fixtures/depth-$depth.wav")
        try {
            val writer=PcmRecorder(file.path,48000,1,depth)
            writer.start();writer.writeSamples(values);writer.stop()
            test(ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN))
        } finally { file.delete() }
    }
    @Test fun packed24BitPreservesSignAndPadsOddData() = wave(24,floatArrayOf(-1f,0f,1f)) { b ->
        assertEquals(54,b.capacity());assertEquals(46,b.getInt(4));assertEquals(9,b.getInt(40))
        assertEquals(24,b.getShort(34).toInt());assertEquals(144000,b.getInt(28))
        assertEquals(0x800001,(b.get(44).toInt() and 255) or ((b.get(45).toInt() and 255) shl 8) or ((b.get(46).toInt() and 255) shl 16))
        assertEquals(0,b.getInt(47) and 0xffffff)
        assertEquals(0x7fffff,(b.get(50).toInt() and 255) or ((b.get(51).toInt() and 255) shl 8) or ((b.get(52).toInt() and 255) shl 16))
        assertEquals(0,b.get(53).toInt())
    }
    @Test fun floatWaveRetainsHeadroomAndSanitizesInvalidSamples() = wave(32,floatArrayOf(1.2f,-0.4f,Float.NaN)) { b ->
        assertEquals(3,b.getShort(20).toInt());assertEquals(32,b.getShort(34).toInt())
        assertEquals(1.2f,b.getFloat(44),0f);assertEquals(-0.4f,b.getFloat(48),0f);assertEquals(0f,b.getFloat(52),0f)
        assertEquals(12,b.getInt(40))
    }
    @Test fun profileDefaultsAndLegacyWavCallsRemainDistinct() {
        assertEquals(AudioRecordingFormat.AAC,AudioRecordingProfile().format)
        assertEquals(AudioRecordingFormat.WAV,AudioRecordingProfile.forFile("old.wav").format)
        assertEquals("audio/ogg",AudioRecordingProfile.mime("new.ogg"))
        assertEquals(48000,AudioRecordingProfile(AudioRecordingFormat.OPUS).fileSampleRate(44100))
        assertEquals(44100,AudioRecordingProfile().fileSampleRate(44100))
        assertEquals(AudioRecordingProfile(),AudioRecordingProfile(bitDepth=7,bitRate=-1).normalized())
    }
}
