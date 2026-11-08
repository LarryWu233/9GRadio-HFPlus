package com.radiosport.ninegradio.pipeline

import com.radiosport.ninegradio.dsp.*
import com.radiosport.ninegradio.source.IqBlock
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class SignalBranchesTest {
    private fun tone(n:Int,bin:Int,amplitude:Double=0.01)=FloatArray(n*2) { i ->
        val phase=2*PI*bin*(i/2)/n
        (amplitude*if(i%2==0) cos(phase) else sin(phase)).toFloat()
    }

    @Test fun measurementUsesCalibratedFullSpanPeakRestrictedToChannel() {
        val analyzer=SignalAnalyzer()
        val data=tone(2048,32) // 750 Hz at 48 kHz, -40 dBFS
        var result:SignalAnalyzer.Measurement?=null
        repeat(4) { i -> result=analyzer.process(data,10000000,10000000,DemodMode.AM,4000,48000,0,1_000_000_000L+i*50_000_000L) }
        assertTrue(result!!.channel.valid)
        assertEquals(-40f,result!!.channel.peakDb,0.3f)
        val adjacent=tone(2048,384,0.5) // strong 9 kHz interferer outside the channel
        repeat(4) { i -> result=analyzer.process(adjacent,10000000,10000000,DemodMode.AM,4000,48000,0,2_000_000_000L+i*50_000_000L) }
        assertTrue(result!!.widebandPeakDb > -7f)
        assertTrue(result!!.channel.peakDb < -70f)
    }

    @Test fun slowDisplayDropsOnlyItsOwnFramesWhileMeasurementContinues() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val entered=CompletableDeferred<Unit>()
        val display=SpectrumBranch(FftEngine(256),scope) { entered.complete(Unit);Thread.sleep(150) }
        val clock=CaptureClock();val analyzer=SignalAnalyzer();val data=tone(2048,32)
        display.start()
        try {
            val first=clock.stamp(2048,48000,10000000)
            display.offer(IqBlock.Float32(data,first.sequence,first))
            withTimeout(2000) { entered.await() }
            var readings=0
            repeat(30) { i ->
                if(i==10) { display.engine.setFftSize(8192);display.engine.decimationFactor=64 }
                val m=clock.stamp(2048,48000,10000000)
                display.offer(IqBlock.Float32(data,m.sequence,m))
                val result=analyzer.process(data,10000000,10000000,DemodMode.AM,4000,48000,0,1_000_000_000L+i*50_000_000L)
                if(result.channel.valid) { readings++;assertEquals(-40f,result.channel.peakDb,0.3f) }
            }
            assertTrue(readings>=28)
            assertTrue(display.skipped.get()>0)
        } finally { display.close();scope.cancel() }
    }
}
