package com.radiosport.ninegradio.ui

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Peak-preserving display resampling adapted from SDR++'s doZoom(), GPL-3.0.
 * AlexandreRouma/SDRPlusPlus f10fb98, core/src/gui/widgets/waterfall.cpp.
 * Android adaptation: fractional pan coordinates and blank out-of-range pixels.
 * This only rasterizes existing FFT data; it does not change signal processing.
 */
internal object SpectrumRasterizer {
    fun peakBinsToPixels(input: FloatArray, firstBin: Double, binsPerPixel: Double, output: FloatArray) {
        val count=ceil(binsPerPixel).toInt().coerceAtLeast(1)
        var position=firstBin
        for(pixel in output.indices) {
            val start=floor(position).toInt()
            var peak=-200f
            for(bin in maxOf(0,start) until minOf(input.size,start+count)) {
                if(input[bin]>peak) peak=input[bin]
            }
            output[pixel]=peak
            position+=binsPerPixel
        }
    }
}
