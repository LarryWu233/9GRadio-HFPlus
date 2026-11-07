package com.radiosport.ninegradio

import com.radiosport.ninegradio.audio.PcmRecorder
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmRecorderTest {
    @Test fun stereoRecordingHasCorrectHeaderAndChannelOrder() {
        val file=File("build/test-fixtures/stereo.wav")
        try {
            val recorder=PcmRecorder(file.path,48000,2)
            recorder.start()
            recorder.writeSamples(floatArrayOf(0.5f,-0.25f,0.25f,-0.5f))
            recorder.stop()
            val data=ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(52,data.capacity())
            assertEquals(2,data.getShort(22).toInt())
            assertEquals(48000,data.getInt(24))
            assertEquals(192000,data.getInt(28))
            assertEquals(4,data.getShort(32).toInt())
            assertEquals(8,data.getInt(40))
            assertTrue(data.getShort(44)>0); assertTrue(data.getShort(46)<0)
        } finally { file.delete() }
    }
}
