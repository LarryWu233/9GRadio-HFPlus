package com.radiosport.ninegradio.dsp

import kotlin.math.*

/** Continuous FM multiplex -> mono or L/R audio, with a pilot locked carrier. */
class BroadcastFmAudio(private val stereo: Boolean) {
    private class Biquad(val b0: Double,val b1: Double,val b2: Double,val a1: Double,val a2: Double) {
        var w1 = 0.0; var w2 = 0.0
        fun process(x: Double): Double {
            val y = b0*x+w1; w1 = b1*x-a1*y+w2; w2 = b2*x-a2*y; return y
        }
    }
    private var rate = 0
    private lateinit var pilot: Biquad
    private lateinit var notch: Biquad
    private lateinit var sumFilter: Biquad
    private lateinit var differenceFilter: Biquad
    private var cosPhase = 1.0; private var sinPhase = 0.0
    private var cosStep = 1.0; private var sinStep = 0.0
    private var frequencyError = 0.0
    private var pilotLevel = 0.0; private var blend = 0.0
    private var deLeft = 0.0; private var deRight = 0.0
    private var count = 0
    private fun biquad(fs: Int, frequency: Double, q: Double, type: Int): Biquad {
        val w = 2*PI*min(frequency,fs*0.45)/fs; val c = cos(w); val alpha = sin(w)/(2*q); val a = 1+alpha
        return when (type) {
            0 -> Biquad((1-c)/2/a,(1-c)/a,(1-c)/2/a,-2*c/a,(1-alpha)/a)
            1 -> Biquad(alpha/a,0.0,-alpha/a,-2*c/a,(1-alpha)/a)
            else -> Biquad(1/a,-2*c/a,1/a,-2*c/a,(1-alpha)/a)
        }
    }
    private fun init(fs: Int) {
        rate = fs
        pilot = biquad(fs,19000.0,30.0,1); notch = biquad(fs,19000.0,8.0,2)
        sumFilter = biquad(fs, min(15000.0,fs*0.4),sqrt(0.5),0)
        differenceFilter = biquad(fs,min(15000.0,fs*0.4),sqrt(0.5),0)
        cosPhase=1.0; sinPhase=0.0; frequencyError=0.0; pilotLevel=0.0; blend=0.0; deLeft=0.0; deRight=0.0; count=0
        cosStep=cos(2*PI*19000/fs); sinStep=sin(2*PI*19000/fs)
    }
    fun process(input: FloatArray, sampleRate: Int, deemphasisUs: Int): FloatArray {
        if (rate != sampleRate) init(sampleRate)
        val alpha = exp(-1.0 / (rate * deemphasisUs.coerceIn(50,75) * 1e-6))
        val output = FloatArray(input.size * if (stereo) 2 else 1)
        for (i in input.indices) {
            val x = input[i].toDouble()
            val sum = sumFilter.process(notch.process(x))
            if (!stereo || rate < 100000) {
                deLeft = alpha*deLeft+(1-alpha)*sum
                if (stereo) { output[i*2]=deLeft.toFloat(); output[i*2+1]=deLeft.toFloat() }
                else output[i] = deLeft.toFloat()
                continue
            }
            val p = pilot.process(x)
            pilotLevel += 0.0005*(abs(p)-pilotLevel)
            val error = (-p*sinPhase / max(0.02,pilotLevel*1.6)).coerceIn(-1.0,1.0)
            frequencyError = (frequencyError + 1e-6*error).coerceIn(-2*PI*200/rate,2*PI*200/rate)
            val delta = frequencyError + 0.003*error
            val carrier = cosPhase*cosPhase-sinPhase*sinPhase
            blend += 0.0005*((if (pilotLevel > 0.015) 1.0 else 0.0)-blend)
            val difference = differenceFilter.process(x * 2 * carrier) * blend
            deLeft=alpha*deLeft+(1-alpha)*(sum+difference)
            deRight=alpha*deRight+(1-alpha)*(sum-difference)
            output[i*2]=deLeft.toFloat(); output[i*2+1]=deRight.toFloat()
            val c=cosStep*(1-delta*delta/2)-sinStep*delta
            val s=sinStep*(1-delta*delta/2)+cosStep*delta
            val nextCos=cosPhase*c-sinPhase*s
            sinPhase=sinPhase*c+cosPhase*s; cosPhase=nextCos
            if (++count % 1024 == 0) { val norm=hypot(cosPhase,sinPhase); cosPhase/=norm; sinPhase/=norm }
        }
        return output
    }
}
