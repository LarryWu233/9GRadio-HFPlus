package com.radiosport.ninegradio.ui

import com.radiosport.ninegradio.data.RadioTarget
import org.junit.Assert.*
import org.junit.Test

class RadioMapFeaturesTest {
    private fun aircraft(heading:Double?)=RadioTarget("SAMPLE","Sample",1.0,2.0,"",0,RadioTarget.Type.AIRCRAFT,heading)
    @Test fun trueNorthCardinalTracksArePreservedForMapRotation() {
        for(heading in listOf(0.0,90.0,180.0,270.0,359.5)) {
            val feature=aircraft(heading).mapFeature(0)
            assertEquals(heading,feature.getNumberProperty("heading").toDouble(),0.0)
            assertTrue(feature.getBooleanProperty("headingKnown"))
            assertEquals("radio-aircraft-known",feature.getStringProperty("icon"))
        }
        assertEquals(0.0,aircraft(360.0).mapFeature(0).getNumberProperty("heading").toDouble(),0.0)
    }
    @Test fun missingOrInvalidTrackUsesDistinctNorthFacingFallback() {
        for(heading in listOf(null,Double.NaN,Double.POSITIVE_INFINITY,-1.0,999.0)) {
            val feature=aircraft(heading).mapFeature(0)
            assertFalse(feature.getBooleanProperty("headingKnown"))
            assertEquals(0.0,feature.getNumberProperty("heading").toDouble(),0.0)
            assertEquals("radio-aircraft-unknown",feature.getStringProperty("icon"))
        }
    }
    @Test fun aprsStationsKeepTheirOwnSymbolCategory() {
        assertEquals("station",RadioTarget("APRS","APRS",1.0,2.0,"",0).mapFeature().getStringProperty("kind"))
    }
    @Test fun stalenessUsesPositionAgeEvenWhenOtherMessagesAreFresh() {
        val target=aircraft(90.0).copy(timeMs=50_000,positionTimeMs=0)
        assertEquals("radio-aircraft-stale",target.mapFeature(50_000).getStringProperty("icon"))
        assertEquals("radio-aircraft-known",target.copy(positionTimeMs=49_000).mapFeature(50_000).getStringProperty("icon"))
        assertTrue(target.copy(positionTimeMs=null).mapFeature(50_000).getBooleanProperty("positionStale"))
    }
    @Test fun shipsUseTheirOwnHeadingAndPositionAgeCategory() {
        val ship=RadioTarget("ais:1","Vessel",1.0,2.0,"",600_000,RadioTarget.Type.SHIP,180.0,0)
        assertEquals("radio-ship-known",ship.mapFeature(1000).getStringProperty("icon"))
        assertEquals("ship",ship.mapFeature(1000).getStringProperty("kind"))
        assertEquals("radio-ship-stale",ship.mapFeature(600_000).getStringProperty("icon"))
        assertEquals("radio-ship-unknown",ship.copy(headingDegrees=null,positionTimeMs=600_000).mapFeature(600_000).getStringProperty("icon"))
    }
}
