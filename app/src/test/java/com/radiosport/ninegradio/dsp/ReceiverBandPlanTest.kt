package com.radiosport.ninegradio.dsp

import com.radiosport.ninegradio.data.RegionalReception as Plans
import org.junit.Assert.*
import org.junit.Test

class ReceiverBandPlanTest {
    @Test fun hamOverridesGeneralShortwaveAndNavigationDoesNotPretendToBeVoice() {
        assertEquals(DemodMode.LSB, ReceiverBandPlan.at(7_100_000).mode)
        assertEquals(DemodMode.USB, ReceiverBandPlan.at(14_200_000).mode)
        assertEquals(DemodMode.AM, ReceiverBandPlan.at(9_600_000).mode)
        assertEquals(DemodMode.WFM, ReceiverBandPlan.at(100_000_000).mode)
        assertEquals(DemodMode.AM, ReceiverBandPlan.at(125_000_000).mode)
        assertNull(ReceiverBandPlan.at(110_000_000).mode)
    }
    @Test fun aprsUsesRegionalValuesAndNearbyAirportsAreDistanceOrdered() {
        assertEquals(144_640_000L, Plans.Region.CHINA.aprsHz)
        assertEquals(144_390_000L, Plans.Region.NORTH_AMERICA.aprsHz)
        assertEquals(144_800_000L, Plans.Region.EUROPE.aprsHz)
        assertEquals(145_175_000L, Plans.Region.AUSTRALIA.aprsHz)
        assertNull(Plans.Region.OTHER.aprsHz)
        val a = Plans.Airport("A", "near", "CN", 30.0, 104.0, emptyList())
        val b = a.copy(ident = "B", lat = 30.5)
        val c = a.copy(ident = "C", lat = 40.0)
        assertEquals(listOf("A", "B"), Plans.nearby(listOf(c, b, a), 30.01, 104.0).map { it.first.ident })
    }
}
