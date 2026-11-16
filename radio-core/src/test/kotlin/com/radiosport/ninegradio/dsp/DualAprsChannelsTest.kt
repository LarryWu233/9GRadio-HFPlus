package com.radiosport.ninegradio.dsp

import com.radiosport.ninegradio.pipeline.AudioContinuity
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

class DualAprsChannelsTest {
    private val center=144595000L
    private class LegacyChannel(rate:Int,private val offset:Double) {
        private val ifRate=ChannelRatePolicy.narrowIfRateFor(rate,48000)
        private val sourceRate=rate
        private val decimator=ComplexDecimator(rate,ifRate)
        private val audio=PolyphaseResampler(ifRate,48000)
        private val phase=doubleArrayOf(0.0)
        private val previous=FloatArray(2)
        fun process(iq:FloatArray):FloatArray {
            val shifted=LegacyDualAprsKernels.ncoMix(iq,sourceRate,offset,phase)
            return audio.process(LegacyDualAprsKernels.nfmDiscriminate(decimator.process(shifted),previous,ifRate))
        }
    }
    @Test fun extractionMatchesBothOldChannelsAndKeepsPublishedPcmOwned() {
        val na=LegacyChannel(960000,-205000.0);val eu=LegacyChannel(960000,205000.0)
        var sample=0L;var retained:FloatArray?=null;var expectedRetained:FloatArray?=null
        DualAprsChannels().use { channels ->
            repeat(12) { block ->
                val count=listOf(4096,2050,103)[block%3]
                val iq=FloatArray(count*2) { index ->
                    val n=sample+index/2
                    val a=2*PI*(-205000.0*n/960000+.15*sin(2*PI*1200*n/960000))
                    val b=2*PI*(205000.0*n/960000+.12*sin(2*PI*2200*n/960000))
                    .08f*(if(index%2==0) cos(a)+cos(b) else sin(a)+sin(b)).toFloat()
                }
                sample+=count
                val original=iq.copyOf();val output=channels.process(iq,960000,center,1)!!
                assertArrayEquals(na.process(iq),output.northAmerica.samples,0f)
                assertArrayEquals(eu.process(iq),output.europe.samples,0f)
                assertArrayEquals(original,iq,0f)
                assertEquals(48000,output.northAmerica.sampleRate);assertEquals(1,output.europe.channels)
                if(block==0) { retained=output.northAmerica.samples;expectedRetained=retained!!.copyOf() }
            }
        }
        assertArrayEquals(expectedRetained,retained,0f)
    }
    @Test fun oneSampleTailsCannotCrashAndConfigurationChangesRejectOldAudio() {
        // The old discriminator dereferences sample -1 when a decimator emits no output.
        assertThrows(ArrayIndexOutOfBoundsException::class.java) {
            LegacyDualAprsKernels.nfmDiscriminate(FloatArray(0),FloatArray(2),48000)
        }
        DualAprsChannels().use { channels ->
            var frames=0;val continuity=AudioContinuity()
            var previousEpoch=0L
            repeat(400) {
                val output=channels.process(floatArrayOf(.1f,.02f),480000,center,1)!!
                frames+=output.northAmerica.frameCount
                continuity.accept(output.northAmerica);previousEpoch=output.northAmerica.epoch
            }
            assertEquals(40,frames)
            channels.invalidate()
            assertTrue(channels.currentGeneration>previousEpoch)
            val changed=channels.process(floatArrayOf(.1f,.02f),480000,center,2)!!
            assertEquals(AudioContinuity.Change.RECONFIGURED,continuity.accept(changed.northAmerica).change)
            assertEquals(0L,changed.northAmerica.firstFrame)
            assertNull(channels.process(floatArrayOf(.1f,.02f),480000,center,1))
            assertNull(channels.process(FloatArray(20),240000,center,2)) // both targets are outside span
        }
    }
    @Test fun invalidationDoesNotFreeInFlightPlansAndFailedConstructionKeepsItsOwner() {
        val entered=CountDownLatch(1);val proceed=CountDownLatch(1);val blockOnce=AtomicBoolean(true)
        class Plan(val delegate:ComplexIqResampler):ComplexIqResampler {
            var closes=0
            override fun process(iq:FloatArray):FloatArray {
                if(blockOnce.compareAndSet(true,false)) { entered.countDown();check(proceed.await(3,TimeUnit.SECONDS)) }
                check(closes==0);return delegate.process(iq)
            }
            override fun reset()=delegate.reset()
            override fun close() { check(closes==0);closes++;delegate.close() }
        }
        val plans=mutableListOf<Plan>();var failOn=Int.MAX_VALUE
        val channels=DualAprsChannels(ComplexIqResamplerFactory { input,output,cutoff ->
            if(plans.size==failOn) error("injected second-plan failure")
            Plan(ReferenceIqResamplers.create(input,output,cutoff)).also { plans+=it }
        })
        val executor=Executors.newSingleThreadExecutor()
        try {
            val future=executor.submit<DualAprsChannels.Audio?> { channels.process(FloatArray(8192) { .1f },960000,center,1) }
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            channels.invalidate();assertTrue(plans.all { it.closes==0 })
            proceed.countDown();assertNull(future.get(3,TimeUnit.SECONDS))
            assertNotNull(channels.process(FloatArray(8192) { .1f },960000,center,1))
            assertTrue(plans.take(2).all { it.closes==1 })
            val current=plans.takeLast(2);failOn=plans.size+1
            assertThrows(IllegalStateException::class.java) { channels.process(FloatArray(8192),1920000,center,2) }
            assertTrue(current.all { it.closes==0 });assertEquals(1,plans.last().closes)
        } finally { proceed.countDown();executor.shutdownNow();channels.close() }
        assertTrue(plans.all { it.closes==1 })
    }
}
