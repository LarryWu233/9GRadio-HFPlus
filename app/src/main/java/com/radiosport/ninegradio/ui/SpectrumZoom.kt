package com.radiosport.ninegradio.ui

/** A single zoom value includes FFT decimation and drawing magnification. */
object SpectrumZoom {
    data class Window(val decimation: Int, val magnification: Float) {
        val total get()=decimation*magnification
        fun span(sampleRate: Int)=(sampleRate/total).toLong().coerceAtLeast(1)
    }
    fun window(zoom: Float): Window {
        val value=if(zoom.isFinite()) zoom.coerceIn(1f,2048f) else 1f
        var decimation=1
        while(value/decimation>32f && decimation<64) decimation*=2
        return Window(decimation,value/decimation)
    }
    fun graphStep(selected: Long,span: Long): Long {
        var limit=1L
        while(limit<=span.coerceAtLeast(1)/1000) limit*=10
        return selected.coerceAtLeast(1).coerceAtMost(limit)
    }
}
