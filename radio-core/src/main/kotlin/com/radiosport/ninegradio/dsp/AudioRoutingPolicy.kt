package com.radiosport.ninegradio.dsp

/** Protocol baseband is independent of human monitoring. Pure data never opens playback. */
object AudioRoutingPolicy {
    fun dataOnly(mode:DemodMode)=when(mode) {
        DemodMode.ADSB,DemodMode.APRS,DemodMode.ACARS,DemodMode.FLEX,DemodMode.RAW -> true
        else -> false
    }
    fun canMonitor(mode:DemodMode,dataContent:Boolean=false)=!dataOnly(mode) && !dataContent
    fun needsProtocolPcm(mode:DemodMode)=when(mode) {
        DemodMode.APRS,DemodMode.ACARS,DemodMode.FLEX,DemodMode.DMR,DemodMode.P25,DemodMode.NXDN,
        DemodMode.DSTAR,DemodMode.YSF,DemodMode.M17,DemodMode.DPMR,DemodMode.DIG -> true
        else -> false
    }
}
