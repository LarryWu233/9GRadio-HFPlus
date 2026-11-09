package com.radiosport.ninegradio.dsp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ChannelProcessorTest {
    /** Old serial assembly, independent instances, to catch extraction wiring changes. */
    @Test fun channelAndAudioOutputsMatchThePreviousAssemblyAcrossBlocks() {
        for(mode in listOf(DemodMode.NFM,DemodMode.AM,DemodMode.USB,DemodMode.CW,DemodMode.CWR,
            DemodMode.APRS,DemodMode.WFM,DemodMode.WFM_STEREO)) {
            val wide=mode==DemodMode.WFM || mode==DemodMode.WFM_STEREO
            val cw=mode==DemodMode.CW || mode==DemodMode.CWR
            val source=240000;val width=if(wide) 200000 else if(cw) 500 else 12500
            val rate=if(wide) ChannelRatePolicy.wfmIfRate(source,width)
                else ChannelRatePolicy.narrow(source,44100,mode,width,width)
            val legacyDemod=DemodulatorFactory.create(mode)
            val legacyIq=ComplexDecimator(source,rate,cutoffHz=if(wide) width/2.0 else null)
            val legacyCw=CwChannelizer();val legacyDecoder=PolyphaseResampler(rate,48000)
            val legacySink=PolyphaseResampler(rate,44100)
            var index=0L
            ChannelProcessor().use { processor ->
                repeat(6) { block ->
                    val count=listOf(4096,103,2048)[block%3]
                    val iq=FloatArray(count*2) { n ->
                        val phase=2*PI*900*(index+n/2)/source
                        (.1*(if(n%2==0) cos(phase) else sin(phase))).toFloat()
                    }
                    index+=count
                    val original=iq.copyOf()
                    val channel=if(cw) legacyCw.process(iq,source,rate,width) else legacyIq.process(iq)
                    val expectedAudio=legacyDemod.demodulate(channel,rate)
                    val expectedMono=if(mode==DemodMode.WFM_STEREO && expectedAudio.size>=2 && expectedAudio.size%2==0)
                        FloatArray(expectedAudio.size/2) { (expectedAudio[it*2]+expectedAudio[it*2+1])*.5f } else expectedAudio
                    val expectedDecoder=if(rate==48000) expectedMono else legacyDecoder.process(expectedMono)
                    val expectedSink=if(rate==44100) expectedMono else legacySink.process(expectedMono)
                    val output=processor.process(iq,ChannelConfiguration(mode,source,44100,width,216000))
                    assertEquals(rate,output.ifRate)
                    assertArrayEquals("$mode channel",channel,output.iq,0f)
                    assertArrayEquals("$mode demod",expectedAudio,output.demodulated,0f)
                    assertArrayEquals("$mode decoder",expectedDecoder,output.decoderPcm,0f)
                    assertArrayEquals("$mode sink",expectedSink,output.sinkPcm,0f)
                    assertArrayEquals(original,iq,0f)
                }
            }
            legacyIq.close();legacyCw.close()
        }
    }
    @Test fun modeReplacementFailureAndShutdownKeepResamplerOwnership() {
        class Plan(val delegate:ComplexIqResampler):ComplexIqResampler {
            var closes=0
            override fun process(iq:FloatArray):FloatArray { check(closes==0);return delegate.process(iq) }
            override fun reset()=delegate.reset()
            override fun close() { check(closes==0);closes++;delegate.close() }
        }
        val plans=mutableListOf<Plan>();var fail=false
        val processor=ChannelProcessor(resamplers=ComplexIqResamplerFactory { input,output,cutoff ->
            if(fail) error("injected plan failure")
            Plan(ReferenceIqResamplers.create(input,output,cutoff)).also { plans+=it }
        })
        val config=ChannelConfiguration(DemodMode.AM,240000,48000,12500,216000)
        val iq=FloatArray(8192) { .1f }
        try {
            processor.process(iq,config);processor.process(iq,config)
            assertEquals(1,plans.size)
            processor.process(iq,config.copy(mode=DemodMode.USB))
            assertEquals(1,plans.first().closes)
            val active=plans.last();fail=true
            assertThrows(IllegalStateException::class.java) { processor.process(iq,config.copy(mode=DemodMode.USB,sourceRate=480000)) }
            assertEquals(0,active.closes)
        } finally { processor.close();processor.close() }
        assertTrue(plans.all { it.closes==1 })
    }
}
