package com.radiosport.ninegradio.dsp

/** Frozen preview-08 scalar channel kernels, solely for extraction equivalence tests. */
internal object LegacyDualAprsKernels {
    private const val DEFAULT_AUDIO_RATE=48000
    fun ncoMix(iq: FloatArray, deviceRate: Int, offsetHz: Double,
                       phaseRef: DoubleArray): FloatArray {
        val n = iq.size / 2
        val out = FloatArray(iq.size)
        val step = -2.0 * Math.PI * offsetHz / deviceRate   // negate: shift signal down to 0 Hz
        // Phasor-recurrence: same optimisation as the main NCO path — 4 muls instead
        // of 2 transcendentals per sample.
        val stepRe = Math.cos(step)
        val stepIm = Math.sin(step)
        var pRe = Math.cos(phaseRef[0])
        var pIm = Math.sin(phaseRef[0])
        for (i in 0 until n) {
            val cosP = pRe.toFloat()
            val sinP = pIm.toFloat()
            val re = iq[2 * i]; val im = iq[2 * i + 1]
            out[2 * i]     = re * cosP - im * sinP
            out[2 * i + 1] = re * sinP + im * cosP
            val nextRe = pRe * stepRe - pIm * stepIm
            val nextIm = pRe * stepIm + pIm * stepRe
            pRe = nextRe; pIm = nextIm
        }
        val mag = Math.sqrt(pRe * pRe + pIm * pIm)
        if (mag > 0.0) { pRe /= mag; pIm /= mag }
        phaseRef[0] = Math.atan2(pIm, pRe)
        return out
    }
    fun nfmDiscriminate(iq: FloatArray, prevState: FloatArray,
                                 ifRate: Int = DEFAULT_AUDIO_RATE): FloatArray {
        val n = iq.size / 2
        val out = FloatArray(n)
        val gain = ifRate / (2f * Math.PI.toFloat() * 5_000f)
        val pi2 = (2.0 * Math.PI).toFloat()

        // prevState[0] = previous I, prevState[1] = previous Q
        var prevPhase = Math.atan2(prevState[1].toDouble(), prevState[0].toDouble()).toFloat()

        for (i in 0 until n) {
            val ci = iq[2 * i]; val cq = iq[2 * i + 1]
            val phase = Math.atan2(cq.toDouble(), ci.toDouble()).toFloat()
            var diff = phase - prevPhase
            // Phase unwrap to (−π, π]
            if (diff >  Math.PI)  diff -= pi2
            if (diff < -Math.PI)  diff += pi2
            out[i] = diff * gain
            prevPhase = phase
        }
        // Store last sample so phase is continuous across USB block boundaries
        val lastI = iq[(n - 1) * 2]
        val lastQ = iq[(n - 1) * 2 + 1]
        prevState[0] = lastI; prevState[1] = lastQ
        return out
    }
}
