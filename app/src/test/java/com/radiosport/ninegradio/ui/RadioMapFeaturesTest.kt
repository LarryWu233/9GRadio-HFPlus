package com.radiosport.ninegradio.ui

import com.radiosport.ninegradio.data.RadioTarget
import org.junit.Assert.*
import org.junit.Test

class RadioMapFeaturesTest {
    private fun aircraft(heading:Double?)=RadioTarget("SAMPLE","Sample",1.0,2.0,"",0,RadioTarget.Type.AIRCRAFT,heading)
    @Test fun trueNorthCardinalTracksArePreservedForMapRotation() {
        for(heading in listOf(0.0,90.0,180.0,270.0,359.5)) {
            val feature=aircraft(heading).mapFeature()
            assertEquals(heading,feature.getNumberProperty("heading").toDouble(),0.0)
            assertTrue(feature.getBooleanProperty("headingKnown"))
            assertEquals("radio-aircraft-known",feature.getStringProperty("icon"))
        }
        assertEquals(0.0,aircraft(360.0).mapFeature().getNumberProperty("heading").toDouble(),0.0)
    }
    @Test fun missingOrInvalidTrackUsesDistinctNorthFacingFallback() {
        for(heading in listOf(null,Double.NaN,Double.POSITIVE_INFINITY,-1.0,999.0)) {
            val feature=aircraft(heading).mapFeature()
            assertFalse(feature.getBooleanProperty("headingKnown"))
            assertEquals(0.0,feature.getNumberProperty("heading").toDouble(),0.0)
            assertEquals("radio-aircraft-unknown",feature.getStringProperty("icon"))
        }
    }
    @Test fun aprsStationsKeepTheirOwnSymbolCategory() {
        assertEquals("station",RadioTarget("APRS","APRS",1.0,2.0,"",0).mapFeature().getStringProperty("kind"))
    }
}
