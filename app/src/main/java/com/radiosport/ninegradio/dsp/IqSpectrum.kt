package com.radiosport.ninegradio.dsp

import com.radiosport.ninegradio.source.IqBlock

/** Transform behaviour belongs to DSP, not the source data contract. */
fun IqBlock.spectrum(engine: FftEngine): FloatArray = when (this) {
    is IqBlock.Unsigned8 -> engine.processUint8(bytes)
    is IqBlock.Float32 -> engine.processFloat(values)
}
