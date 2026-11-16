package com.radiosport.ninegradio

import android.os.Bundle
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.*

/** Diagnostic kernel measurement, not a substitute for the complete CW decode gate.
 * Prepares IQ before timing and reports CPU separately from elapsed scheduling time.
 */
@RunWith(AndroidJUnit4::class)
class CwThroughputTest {
    private data class Timing(var calls:Long=0,var cpu:Long=0,var wall:Long=0)
    @Test fun reportExistingCwStageCosts():Unit {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        for(rate in listOf(768000,1920000)) {
            val samples=rate/2 // half a second of signal, complete and partial blocks
            val iq=FloatArray(samples*2) { index ->
                val phase=2*PI*1000*(index/2)/rate
                .05f*(if(index%2==0) cos(phase) else sin(phase)).toFloat()
            }
            val timings=linkedMapOf<String,Timing>()
            val factory=ComplexIqResamplerFactory { input,output,cutoff ->
                val delegate=ReferenceIqResamplers.create(input,output,cutoff)
                val timing=timings.getOrPut("$input/$output") { Timing() }
                object:ComplexIqResampler {
                    override fun process(iq:FloatArray):FloatArray {
                        val wall=System.nanoTime();val cpu=Debug.threadCpuTimeNanos()
                        try { return delegate.process(iq) }
                        finally { timing.calls++;timing.cpu+=Debug.threadCpuTimeNanos()-cpu;timing.wall+=System.nanoTime()-wall }
                    }
                    override fun reset()=delegate.reset()
                    override fun close()=delegate.close()
                }
            }
            CwChannelizer(AndroidDemodPrimitives,factory).use { channel ->
                fun process():Int {
                    var position=0;var count=0
                    while(position<iq.size) {
                        val end=minOf(iq.size,position+8192)
                        count+=channel.process(iq.copyOfRange(position,end),rate,8000,500).size/2
                        position=end
                    }
                    return count
                }
                repeat(2) { process() }
                repeat(3) { trial ->
                    timings.values.forEach { it.calls=0;it.cpu=0;it.wall=0 }
                    val cpu=Debug.threadCpuTimeNanos();val wall=System.nanoTime()
                    val frames=process()
                    val elapsed=System.nanoTime()-wall;val used=Debug.threadCpuTimeNanos()-cpu
                    assertEquals(4000,frames)
                    instrumentation.sendStatus(0,Bundle().apply {
                        putString("stream","\nCW_KERNEL rate=$rate trial=$trial signal_ms=500 cpu_ms=${used/1000000} wall_ms=${elapsed/1000000} frames=$frames\n"+
                            timings.entries.joinToString("\n") { (label,t) -> "CW_STAGE rate=$rate ratio=$label calls=${t.calls} cpu_ms=${t.cpu/1000000} wall_ms=${t.wall/1000000}" }+"\n")
                    })
                }
            }
        }
    }
}
