package com.radiosport.ninegradio.dsp

/** Protocol baseband is independent of human monitoring. Pure data never opens playback. */
object AudioRoutingPolicy {
    fun dataOnly(mode:DemodMode)=com.radiosport.ninegradio.modules.RadioModules.mode(mode).monitoring==com.radiosport.ninegradio.modules.MonitorPolicy.FORBIDDEN
    fun canMonitor(mode:DemodMode,dataContent:Boolean=false)=!dataOnly(mode) && !dataContent
    fun needsProtocolPcm(mode:DemodMode)=com.radiosport.ninegradio.modules.RadioModules.mode(mode).input==com.radiosport.ninegradio.modules.ModuleInput.PROTOCOL_PCM
}
