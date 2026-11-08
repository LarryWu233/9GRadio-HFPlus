package com.radiosport.ninegradio.pipeline

import org.junit.Assert.*
import org.junit.Test

class CaptureSelectionTest {
    @Test fun scanCannotUseOldFrequencyOrPreBoundaryInput() {
        val selected=CaptureSelection(10000000,500)
        val fresh=IqMetadata(streamId=2,centerHz=10000000,arrivalNanos=501)
        assertTrue(selected.accepts(fresh))
        assertFalse(selected.accepts(fresh.copy(centerHz=9990000)))
        assertFalse(selected.accepts(fresh.copy(arrivalNanos=499)))
        assertTrue(selected.accepts(fresh.copy(arrivalNanos=500)))
    }
    @Test fun legacyUnstampedAdaptersRemainExplicitlyCompatible() {
        assertTrue(CaptureSelection(10000000,500).accepts(IqMetadata()))
    }
}
