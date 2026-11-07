package com.radiosport.ninegradio

import com.radiosport.ninegradio.dsp.ReadsbSnapshotParser
import org.junit.Assert.*
import org.junit.Test

class ReadsbSnapshotParserTest {
    @Test fun mergedNativeTrackKeepsIdentityPositionAndVelocity() {
        val parsed=ReadsbSnapshotParser.parse("""{"messages":57,"aircraft":[{"hex":"40621d","flight":"TEST123 ","lat":52.2572,"lon":3.91937,"alt_baro":38000,"gs":450.2,"track":123.5,"baro_rate":-64,"seen":0.5,"seen_pos":1}]}""",100000)
        assertEquals(57,parsed.messages); val track=parsed.aircraft.single()
        assertEquals("40621D",track.icao24); assertEquals("TEST123",track.callsign)
        assertEquals(52.2572,track.latitude!!,0.00001); assertEquals(38000,track.altitude)
        assertEquals(450,track.velocity); assertEquals(99500,track.timestamp)
    }
    @Test fun incompleteTracksRemainVisibleWithoutFabricatedCoordinates() {
        val track=ReadsbSnapshotParser.parse("""{"aircraft":[{"hex":"ABC123","flight":"N123","alt_baro":"ground","seen":0}]}""").aircraft.single()
        assertTrue(track.onGround); assertNull(track.latitude); assertNull(track.longitude); assertNull(track.altitude)
    }
    @Test fun oldAndInvalidPositionsNeverEnterMap() {
        val tracks=ReadsbSnapshotParser.parse("""{"aircraft":[{"hex":"123456","seen":121,"lat":1,"lon":2},{"hex":"abcdef","seen":1,"seen_pos":130,"lat":1,"lon":2},{"hex":"fedcba","lat":120,"lon":2}]}""").aircraft
        assertEquals(2,tracks.size); assertTrue(tracks.all { it.latitude==null && it.longitude==null })
    }
}
