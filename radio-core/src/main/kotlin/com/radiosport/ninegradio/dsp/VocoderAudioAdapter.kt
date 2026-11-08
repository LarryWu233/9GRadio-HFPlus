package com.radiosport.ninegradio.dsp

import com.radiosport.ninegradio.pipeline.AudioBlock
import com.radiosport.ninegradio.pipeline.AudioContinuity

/** Monitor-only adaptation of decoded 8 kHz PCM. Protocol input and recordings upstream
 * of this branch keep their own clocks. Integer-rate interpolation is the existing
 * application reference; fractional rates reuse its stateful PolyphaseResampler. */
class VocoderAudioAdapter {
    data class Output(val samples:FloatArray,val discontinuity:Boolean)
    private val continuity=AudioContinuity()
    private var outputRate=0
    private var resampler:PolyphaseResampler?=null
    fun process(block:AudioBlock,rate:Int):Output? {
        require(block.channels==1 && block.sampleRate==8000 && rate>0)
        val observation=continuity.accept(block)
        if(observation.change==AudioContinuity.Change.STALE) return null
        val changed=observation.change!=AudioContinuity.Change.CONTINUOUS || outputRate!=rate
        if(changed) {
            outputRate=rate
            resampler=if(rate%8000==0) null else PolyphaseResampler(8000,rate)
        }
        val converted=resampler?.process(block.samples) ?: run {
            // Extracted unchanged from DspEngine's integer-rate monitor path.
            val ratio=rate/8000
            FloatArray(block.samples.size*ratio).also { output ->
                for(i in block.samples.indices) {
                    val s0=block.samples[i]
                    val s1=if(i+1<block.samples.size) block.samples[i+1] else s0
                    for(r in 0 until ratio) output[i*ratio+r]=s0+(s1-s0)*r.toFloat()/ratio
                }
            }
        }
        return Output(converted,changed)
    }
}
