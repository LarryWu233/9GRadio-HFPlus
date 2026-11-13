package com.radiosport.ninegradio.pipeline

import com.radiosport.ninegradio.dsp.*
import kotlin.math.*

/** Fixed 2048-point, full-span BH reference. Display zoom/averaging never configures this meter. */
class SignalAnalyzer {
    data class Measurement(val channel: ChannelSquelchMeter.Reading,
                           val widebandPeakDb: Float, val scannerCenterDb: Float)
    private val fft=FftEngine(2048).apply { smoothingAlpha=0f;frameAveragingCount=1 }
    private val meter=ChannelSquelchMeter()
    private var key: ChannelSquelchMeter.Configuration?=null

    fun reset() { key=null;fft.resetSpectrumAveraging();meter.reset() }

    fun process(iq:FloatArray,frequencyHz:Long,hardwareHz:Long,mode:DemodMode,bandwidthHz:Int,
                rate:Int,narrowCenterOffsetHz:Long,nowNanos:Long):Measurement {
        val config=ChannelSquelchMeter.Configuration(frequencyHz,hardwareHz,mode,bandwidthHz,rate,1,2048)
        if(config!=key) { reset();key=config }
        fft.analysisIntervalSamples=(rate/60).coerceAtLeast(1)
        val spectrum=fft.processFloat(iq)
        val channel=meter.update(spectrum,fft.spectrumSequence,config,nowNanos)
        val binHz=rate.toDouble()/spectrum.size
        val center=(spectrum.size/2-narrowCenterOffsetHz/binHz).toInt().coerceIn(0,spectrum.lastIndex)
        val half=(12500.0/binHz/2).toInt().coerceAtLeast(1)
        val lo=(center-half).coerceAtLeast(0);val hi=(center+half).coerceAtMost(spectrum.lastIndex)
        var power=0.0
        for(i in lo..hi) power+=10.0.pow(spectrum[i]/10.0)
        val scanner=(10*log10((power/(hi-lo+1)).coerceAtLeast(1e-30))).toFloat()
        return Measurement(channel,fft.lastPeakDb,scanner)
    }
}
