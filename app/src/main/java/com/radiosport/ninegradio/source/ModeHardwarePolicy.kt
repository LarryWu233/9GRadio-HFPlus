package com.radiosport.ninegradio.source

import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.usb.RtlSdrDevice

/** Preserve the old RTL preset hint in the hardware adapter, outside portable DSP. */
val DemodMode.requiresDirectSampling:Boolean get()=maxFreqHz<=28_800_000L

/** Called only on the receiver control executor. HF+/files expose no RTL controls. */
fun IqSource.applyModeHardwarePolicy(mode:DemodMode) {
    if(!capabilities.directSampling) return
    val requested=if(mode.requiresDirectSampling) RtlSdrDevice.DIRECT_SAMPLING_Q else RtlSdrDevice.DIRECT_SAMPLING_OFF
    if(getDirectSampling()!=requested) setDirectSampling(requested)
}
