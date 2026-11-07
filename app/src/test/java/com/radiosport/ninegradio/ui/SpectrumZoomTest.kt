package com.radiosport.ninegradio.ui

import org.junit.Assert.*
import org.junit.Test

class SpectrumZoomTest {
    @Test fun fullSpanRestoresEveryHardwareSampleRate() {
        for(rate in listOf(192000,768000,912000,1920000)) {
            val window=SpectrumZoom.window(1f)
            assertEquals(1,window.decimation)
            assertEquals(rate.toLong(),window.span(rate))
        }
    }
    @Test fun zoomUsesOneContinuousScaleIncludingLegacyDecimation() {
        for(zoom in listOf(1f,2f,31.5f,32f,33f,64f,128f,2048f)) {
            val w=SpectrumZoom.window(zoom)
            assertTrue(w.decimation in listOf(1,2,4,8,16,32,64))
            assertTrue(w.magnification in 1f..32f)
            assertEquals(zoom,w.total,0.0001f)
        }
    }
    @Test fun coarseTuningStepCannotMakeGraphDraggingUnresponsive() {
        assertEquals(1000L,SpectrumZoom.graphStep(1000000,768000))
        assertEquals(100L,SpectrumZoom.graphStep(1000000,14250))
        assertEquals(1L,SpectrumZoom.graphStep(1,768000))
    }
}
