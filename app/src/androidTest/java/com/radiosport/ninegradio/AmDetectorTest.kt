package com.radiosport.ninegradio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.radiosport.ninegradio.dsp.*
import com.radiosport.ninegradio.pipeline.*
import com.radiosport.ninegradio.source.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.*

/** Independent RF stimuli: do not mirror detector/PLL/filter internals. No USB or saved settings. */
@RunWith(AndroidJUnit4::class)
class AmDetectorTest {
    private fun tone(mode:AmDetector,offset:Double=70.0,sidebands:Boolean=false):Pair<FloatArray,AmStatus> {
        NativeAmProcessor().use { node ->
            val all=ArrayList<Float>();val settings=AmSettings(mode,agc=AmAgc.OFF)
            repeat(200) { block ->
                val iq=FloatArray(960)
                for(i in 0 until 480) {
                    val t=(block*480+i)/48000.0
                    val carrier=2*PI*offset*t
                    val upper=2*PI*900*t;val lower=2*PI*1700*t
                    val re=if(sidebands) .15+.03*cos(upper)+.03*cos(lower) else .15*(1+.5*sin(2*PI*1000*t))
                    val im=if(sidebands) .03*sin(upper)-.03*sin(lower) else 0.0
                    iq[2*i]=(re*cos(carrier)-im*sin(carrier)).toFloat()
                    iq[2*i+1]=(re*sin(carrier)+im*cos(carrier)).toFloat()
                }
                val out=node.process(iq,48000,if(mode.sideband==0) 10000 else 5000,settings,-30f)
                if(block>=100) out.forEach { all.add(it) }
            }
            return all.toFloatArray() to node.status
        }
    }
    private fun amplitude(samples:FloatArray,hz:Double):Double {
        var re=0.0;var im=0.0
        for(i in samples.indices) { val p=2*PI*hz*i/48000;re+=samples[i]*cos(p);im+=samples[i]*sin(p) }
        return 2*hypot(re,im)/samples.size
    }
    @Test fun centeredCarrierKeepsItsAudioAndDoesNotBecomeFullWaveRectified():Unit=runBlocking {
        val base=ArchitectureLifecycleTest.Source();base.open();val clock=CaptureClock()
        val source=object:IqSource by base {
            override val iqFlow=flow<IqBlock> {
                repeat(200) { block ->
                    val iq=FloatArray(960) { j -> if(j%2==0) (.15*(1+.5*sin(2*PI*1000*(block*480+j/2)/48000))).toFloat() else 0f }
                    emit(IqBlock.Float32(iq,metadata=clock.stamp(480,48000,10000000)));yield()
                }
            }
            override fun startStreaming(bufferSize:Int)=Unit
            override fun stopStreaming()=Unit
        }
        val engine=DspEngine(source);val chunks=ArrayList<FloatArray>()
        try {
            engine.setDemodMode(DemodMode.AM);engine.setIfBandwidth(10000);engine.setListeningEnabled(false)
            engine.setAmSettings(AmSettings(agc=AmAgc.OFF))
            engine.setAudioDecoderInput(object:AudioDecoderInput {
                override fun configure(epoch:Long)=Unit
                override fun accept(block:AudioBlock) { chunks.add(block.samples.copyOf()) }
            })
            engine.start();withTimeout(7000) { engine.inputComplete.first { it } }
            assertNull(engine.failure.value)
            val pcm=chunks.drop(100).flatMap { it.asList() }.toFloatArray()
            assertTrue("Centered AM must retain 1 kHz modulation",amplitude(pcm,1000.0)>.05)
            assertTrue("DC removal must not create dominant 2 kHz",amplitude(pcm,2000.0)<amplitude(pcm,1000.0)*.03)
        } finally { engine.stop();base.close() }
    }
    @Test fun synchronousModesAcquireCarrierAndRejectOppositeSideband() {
        val dsb=tone(AmDetector.SAM_DSB)
        assertEquals("SAM must lock",2,dsb.second.state)
        assertEquals(70f,dsb.second.offsetHz,3f)
        assertTrue(amplitude(dsb.first,1000.0)>.05)
        assertEquals(2,tone(AmDetector.SAM_DSB,offset=-70.0).second.state)
        for(mode in listOf(AmDetector.SAM_USB,AmDetector.SAM_LSB)) {
            val (pcm,status)=tone(mode,sidebands=true)
            assertEquals(mode.name,2,status.state)
            val wanted=amplitude(pcm,if(mode==AmDetector.SAM_USB) 900.0 else 1700.0)
            val unwanted=amplitude(pcm,if(mode==AmDetector.SAM_USB) 1700.0 else 900.0)
            assertTrue("$mode wanted=$wanted unwanted=$unwanted",wanted>unwanted*10)
        }
    }
    @Test fun lowLevelGainDoesNotRunAwayAndSustainedLossFallsBack() {
        NativeAmProcessor().use { node ->
            val settings=AmSettings(AmDetector.SAM_DSB,maxGainDb=30f)
            repeat(100) { node.process(FloatArray(960) { if(it%2==0) .1f else 0f },48000,10000,settings,-30f) }
            assertEquals(2,node.status.state)
            repeat(100) { node.process(FloatArray(960) { if(it%2==0) 1e-7f else 0f },48000,10000,settings,-160f) }
            assertEquals(3,node.status.state)
            assertTrue(node.status.gainDb<1f)
            assertTrue(node.status.gainDb<=30f)
            repeat(100) { node.process(FloatArray(960) { if(it%2==0) .1f else 0f },48000,10000,settings,-30f) }
            assertEquals("Carrier reacquisition",2,node.status.state)
        }
    }
    @Test fun manualAudioWidthNarrowsTheActualPcmWithoutReconfiguringRf() {
        NativeAmProcessor().use { node ->
            val settings=AmSettings(audioAuto=false,audioHz=2000,agc=AmAgc.OFF)
            val pcm=ArrayList<Float>()
            repeat(100) { block ->
                val iq=FloatArray(960) { j ->
                    val t=(block*480+j/2)/48000.0
                    if(j%2==0) (.15+.03*sin(2*PI*1000*t)+.03*sin(2*PI*6000*t)).toFloat() else 0f
                }
                val out=node.process(iq,48000,20000,settings,-30f)
                if(block>=50) out.forEach { pcm.add(it) }
            }
            val signal=pcm.toFloatArray()
            assertTrue(amplitude(signal,1000.0)>8*amplitude(signal,6000.0))
            assertEquals("Normal input must not need protection limiting",0L,node.status.limitedSamples)
        }
    }
}
