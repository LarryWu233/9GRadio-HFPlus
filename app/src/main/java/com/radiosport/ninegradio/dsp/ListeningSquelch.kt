package com.radiosport.ninegradio.dsp

/** Hysteresis/hold prevent syllables being chopped at the threshold. */
class ListeningSquelch(initiallyOpen:Boolean=true) {
    var open=initiallyOpen
        private set
    private var belowSeconds=0.0
    private var gain=1f
    fun update(signalDb: Float, thresholdDb: Float, seconds: Double): Boolean {
        if(thresholdDb<=-120f) { open=true; belowSeconds=0.0 }
        else if(signalDb>=thresholdDb) { open=true; belowSeconds=0.0 }
        else if(open && signalDb<thresholdDb-3f) {
            belowSeconds+=seconds
            if(belowSeconds>=0.2) open=false
        } else if(signalDb>=thresholdDb-3f) belowSeconds=0.0
        return open
    }
    fun apply(interleaved: FloatArray,enabled: Boolean,rate: Int) {
        val target=if(enabled) 1f else 0f
        val step=1f/(rate*0.01f).coerceAtLeast(1f)
        for(i in interleaved.indices step 2) {
            gain=if(gain<target) (gain+step).coerceAtMost(target) else (gain-step).coerceAtLeast(target)
            interleaved[i]*=gain; interleaved[i+1]*=gain
        }
    }
}
