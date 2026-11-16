package com.radiosport.ninegradio.dsp

enum class AmDetector(val label:String,val sideband:Int) {
    ENVELOPE("包络",0),SAM_DSB("SAM DSB",0),SAM_USB("SAM USB",1),SAM_LSB("SAM LSB",-1)
}
enum class AmAgc { OFF,SLOW,FAST }
data class AmSettings(val detector:AmDetector=AmDetector.ENVELOPE,val audioAuto:Boolean=true,
    val audioHz:Int=4500,val agc:AmAgc=AmAgc.SLOW,val maxGainDb:Float=40f,val lowLevelDb:Float=-110f) {
    fun cutoffHz(rfWidth:Int,rate:Int=48000):Int {
        val maximum=minOf(20000.0,(if(detector.sideband==0) rfWidth/2.0 else rfWidth.toDouble())*.9,rate*.45)
        val bound=((maximum/100).toInt()*100).coerceAtLeast(100)
        return if(audioAuto) bound else audioHz.coerceIn(100,bound)
    }
}
data class AmStatus(val state:Int=0,val offsetHz:Float=0f,val gainDb:Float=0f,
    val limitedSamples:Long=0,val samples:Long=0) {
    val label get()=when(state) { 1 -> "捕获中";2 -> "已锁定";3 -> "回退";else -> "包络" }
    val limitingPercent get()=if(samples>0) limitedSamples*100.0/samples else 0.0
}
/** Serial detector port. Input is channel-owned IQ; returned PCM must be copied before fan-out. */
interface AmProcessor:AutoCloseable {
    fun process(iq:FloatArray,rate:Int,rfWidth:Int,settings:AmSettings,signalDb:Float):FloatArray
    val status:AmStatus
    fun reset()
}
object AmCarrierProtection {
    fun allowsDc(mode:DemodMode,carrierOffsetHz:Long,sourceRate:Int):Boolean {
        if(mode!=DemodMode.AM && mode!=DemodMode.ACARS) return true
        val guard=maxOf(250.0,sourceRate*.0001/(2*Math.PI)*10)
        return kotlin.math.abs(carrierOffsetHz.toDouble())>guard
    }
}
