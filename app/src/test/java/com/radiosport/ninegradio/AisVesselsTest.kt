package com.radiosport.ninegradio

import com.radiosport.ninegradio.dsp.AisVessels
import com.radiosport.ninegradio.dsp.AisReceiver
import com.radiosport.ninegradio.pipeline.*
import org.junit.Assert.*
import org.junit.Test

class AisVesselsTest {
    @Test fun separateStaticReportsDoNotRefreshOldPositionOrDiscardIdentity() {
        val table=AisVessels()
        assertTrue(table.accept("""{"type":1,"mmsi":366730000,"lat":37.803802,"lon":-122.392532,"speed":4.2,"course":180.2,"heading":179}""",1000))
        assertTrue(table.accept("""{"type":5,"mmsi":366730000,"shipname":"PUBLIC TEST@@","callsign":"TEST123"}""",60000))
        val ship=table.snapshot().single()
        assertEquals("PUBLIC TEST",ship.name);assertEquals(1000L,ship.positionTimeMs)
        assertEquals(60000L,ship.lastMessageMs);assertEquals(179.0,ship.headingDegrees!!,0.0)
    }
    @Test fun undefinedNavigationValuesNeverCreateAFalsePositionOrHeading() {
        val table=AisVessels()
        assertTrue(table.accept("""{"type":1,"mmsi":366730000,"lat":91,"lon":181,"speed":102.3,"course":360,"heading":511}""",1000))
        val ship=table.snapshot().single()
        assertNull(ship.latitude);assertNull(ship.longitude);assertNull(ship.positionTimeMs)
        assertNull(ship.headingDegrees);assertNull(ship.courseDegrees);assertNull(ship.speedKnots)
        assertFalse(table.accept("not JSON",1001));assertFalse(table.accept("""{"mmsi":0,"type":1}""",1001))
    }
    @Test fun admissionPreservesBothIqFormatsAcrossNativeSupportedRates() {
        val requirements=AisReceiver.REQUIREMENTS
        for(format in IqSampleFormat.entries) for(rate in listOf(96000,256000,288000,768000,2400000))
            assertTrue(requirements.accepts(IqDecoderConfiguration(rate,format)))
        assertFalse(requirements.accepts(IqDecoderConfiguration(48000,IqSampleFormat.FLOAT_32)))
        val exact=IqDecoderRequirements(setOf(2400000),setOf(IqSampleFormat.UNSIGNED_8))
        assertFalse(exact.acceptsRate(768000));assertTrue(exact.acceptsRate(2400000))
    }
}
