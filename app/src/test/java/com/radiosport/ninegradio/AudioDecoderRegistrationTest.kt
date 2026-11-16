package com.radiosport.ninegradio

import com.radiosport.ninegradio.content.*
import org.junit.Assert.*
import org.junit.Test

class AudioDecoderRegistrationTest {
    @Test fun satelliteAptChoiceCannotChangeSstvOrMorseBackends() {
        val sstv=AudioDecoderFactories.forConfig(ContentConfig(AudioContentKind.SSTV,apt=true))
        assertNotNull(sstv.imageFactory);assertNull(sstv.nativeArgument)
        val cw=AudioDecoderFactories.forConfig(ContentConfig(AudioContentKind.CW,apt=true))
        assertEquals("--cw",cw.nativeArgument);assertEquals(22050,cw.decoderRate)
        val apt=AudioDecoderFactories.forConfig(ContentConfig(AudioContentKind.SAT,apt=true))
        assertEquals("--apt",apt.nativeArgument);assertEquals(48000,apt.decoderRate)
        assertNotNull(AudioDecoderFactories.forConfig(ContentConfig(AudioContentKind.SAT)).imageFactory)
    }
}
