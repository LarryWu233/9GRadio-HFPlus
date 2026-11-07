package com.radiosport.ninegradio.ui

import org.junit.Assert.*
import org.junit.Test

class SpectrumRasterizerTest {
    @Test fun narrowCarrierBetweenPixelCentresSurvivesFullSpan() {
        val bins=FloatArray(4096) { -120f }.apply { this[202]=-10f }
        val row=FloatArray(256)
        SpectrumRasterizer.peakBinsToPixels(bins,0.0,16.0,row)
        assertEquals(-10f,row[12],0f)
        assertEquals(1,row.count { it>-120f })
    }
    @Test fun zoomPreservesBinLevelsInsteadOfBlendingAdjacentSignals() {
        val row=FloatArray(8)
        SpectrumRasterizer.peakBinsToPixels(floatArrayOf(-120f,-10f,-80f,-120f),1.0,0.25,row)
        assertArrayEquals(floatArrayOf(-10f,-10f,-10f,-10f,-80f,-80f,-80f,-80f),row,0f)
    }
    @Test fun panOutsideAvailableDataDoesNotFabricateEdgeSignals() {
        val row=FloatArray(8)
        SpectrumRasterizer.peakBinsToPixels(floatArrayOf(-30f,-50f,-60f,-40f),-2.0,1.0,row)
        assertArrayEquals(floatArrayOf(-200f,-200f,-30f,-50f,-60f,-40f,-200f,-200f),row,0f)
    }
}
