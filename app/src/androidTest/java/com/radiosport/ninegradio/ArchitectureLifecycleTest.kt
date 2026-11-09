package com.radiosport.ninegradio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.dsp.DspEngine
import com.radiosport.ninegradio.pipeline.CaptureClock
import com.radiosport.ninegradio.pipeline.IqStream
import com.radiosport.ninegradio.source.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

/** Own-package native/AudioTrack lifecycle; does not open USB or alter saved settings. */
@RunWith(AndroidJUnit4::class)
class ArchitectureLifecycleTest {
    @Test fun validU8AndFirstPostGapBlockReachTheDecoder():Unit=runBlocking {
        val stream=IqStream(lossless=true)
        val clock=CaptureClock()
        val base=Source()
        val source=object:IqSource by base {
            override val iqFlow=stream.flow
            override val iqSubscriberCount get()=stream.subscriberCount
            override fun startStreaming(bufferSize:Int)=Unit
            override fun stopStreaming()=Unit
            override fun close() { stream.complete();base.close() }
        }
        source.open()
        val engine=DspEngine(source)
        val received=java.util.concurrent.CopyOnWriteArrayList<com.radiosport.ninegradio.pipeline.AudioBlock>()
        try {
            engine.setDemodMode(DemodMode.AM);engine.setVolume(0f)
            engine.setAudioDecoderInput { received+=it }
            engine.start()
            withTimeout(3000) { while(source.iqSubscriberCount==0) delay(5) }
            suspend fun send(bytes:ByteArray) {
                val meta=clock.stamp(bytes.size/2,48000,10000000)
                stream.send(IqBlock.Unsigned8(bytes,meta))
            }
            suspend fun awaitFrames(count:Int) {
                withTimeout(3000) { while(received.sumOf { it.frameCount }<count) delay(5) }
            }
            // Sparse even-offset probes see only I=0; Q is valid and must survive.
            val valid=ByteArray(8192) { if(it%2==0) 0 else 127 }
            send(valid);awaitFrames(4096)
            clock.stamp(4096,48000,10000000) // One missing producer block.
            send(valid);awaitFrames(8192)
            assertTrue(received[1].epoch>received[0].epoch)
            assertEquals(0L,received[1].firstFrame)
            send(ByteArray(8192)) // Existing invalid-content policy: explicit gap.
            send(valid);awaitFrames(12288)
            assertTrue(received[2].epoch>received[1].epoch)
            assertEquals(0L,received[2].firstFrame)
            val diagnostics=engine.pipelineDiagnostics()
            assertEquals(12288L,diagnostics.inputSamples)
            assertEquals(8192L,diagnostics.inputMissingSamples)
            assertEquals(0L,diagnostics.driverMissingSamples)
            assertNull(engine.failure.value)
        } finally { engine.stop();source.close() }
    }
    @Test fun inFlightAudioCannotBeStampedWithTheClosedEpoch():Unit=runBlocking {
        val entered=java.util.concurrent.CountDownLatch(1)
        val proceed=java.util.concurrent.CountDownLatch(1)
        val first=java.util.concurrent.atomic.AtomicBoolean(true)
        val configured=java.util.concurrent.atomic.AtomicLong()
        val late=java.util.concurrent.atomic.AtomicInteger()
        val factory=com.radiosport.ninegradio.dsp.ComplexIqResamplerFactory { input,output,cutoff ->
            object:com.radiosport.ninegradio.dsp.ComplexIqResampler {
                val delegate=com.radiosport.ninegradio.dsp.ComplexDecimator(input,output,cutoffHz=cutoff)
                override fun process(iq:FloatArray):FloatArray {
                    if(first.compareAndSet(true,false)) { entered.countDown();check(proceed.await(5,java.util.concurrent.TimeUnit.SECONDS)) }
                    return delegate.process(iq)
                }
                override fun reset()=delegate.reset()
                override fun close()=delegate.close()
            }
        }
        val source=Source();source.open();val engine=DspEngine(source,iqResamplers=factory)
        var stopping:java.util.concurrent.Future<*>?=null
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            engine.setDemodMode(DemodMode.AM);engine.setVolume(0f)
            engine.setAudioDecoderInput(object:com.radiosport.ninegradio.pipeline.AudioDecoderInput {
                override fun configure(epoch:Long) { configured.set(epoch) }
                override fun accept(block:com.radiosport.ninegradio.pipeline.AudioBlock) { late.incrementAndGet() }
            })
            val active=configured.get();engine.start()
            assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS))
            stopping=executor.submit { engine.stop() }
            withTimeout(3000) { while(configured.get()==active) delay(5) }
            proceed.countDown();stopping.get(5,java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("The old in-flight frame must not enter the closed decoder epoch",0,late.get())
        } finally {
            proceed.countDown()
            stopping?.get(5,java.util.concurrent.TimeUnit.SECONDS)
            engine.stop();source.close();executor.shutdownNow()
        }
    }
    @Test fun resamplerPlansCloseBeforeReplacementAndAfterProcessingStops():Unit=runBlocking {
        val plans=java.util.concurrent.CopyOnWriteArrayList<com.radiosport.ninegradio.dsp.ComplexIqResampler>()
        val released=java.util.concurrent.atomic.AtomicInteger()
        val factory=com.radiosport.ninegradio.dsp.ComplexIqResamplerFactory { input,output,cutoff ->
            object:com.radiosport.ninegradio.dsp.ComplexIqResampler {
                val delegate=com.radiosport.ninegradio.dsp.ComplexDecimator(input,output,cutoffHz=cutoff)
                var closed=false
                override fun process(iq:FloatArray):FloatArray { check(!closed);return delegate.process(iq) }
                override fun reset() { check(!closed);delegate.reset() }
                override fun close() { check(!closed);closed=true;released.incrementAndGet() }
            }.also { plans+=it }
        }
        val source=Source();source.open();val engine=DspEngine(source,iqResamplers=factory)
        try {
            engine.setVolume(0f);engine.start()
            repeat(8) { i ->
                engine.setDemodMode(if(i%2==0) DemodMode.AM else DemodMode.USB)
                engine.setIfBandwidth(if(i%2==0) 10000 else 3000);delay(40)
                assertNull(engine.failure.value)
            }
        } finally { engine.stop();source.close() }
        assertTrue(plans.isNotEmpty());assertEquals(plans.size,released.get())
    }
    internal class Source : IqSource {
        private val stream = IqStream()
        private val clock = CaptureClock()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var job: Job? = null
        val produced = AtomicLong()
        override val iqFlow = stream.flow
        override val iqSubscriberCount get() = stream.subscriberCount
        private val state = MutableStateFlow(IqSource.SourceStatus(sampleRate=48000,centerFreqHz=10000000))
        override val statusFlow = state.asStateFlow()
        override val capabilities = DeviceCapabilities("fixture",listOf(1L..260000000L),listOf(48000,96000),false,false,false,true,480)
        override fun open(): Boolean { state.value=state.value.copy(connected=true); return true }
        override fun startStreaming(bufferSize: Int) {
            check(isOpen())
            job=scope.launch {
                val count=getSampleRate()/100
                val data=FloatArray(count*2) { i -> if(i%2==0) 0.1f else 0.02f }
                while(isActive) {
                    val meta=clock.stamp(count,getSampleRate(),getCenterFrequency())
                    stream.offer(IqBlock.Float32(data,meta.sequence,meta))
                    produced.incrementAndGet(); delay(10)
                }
            }
        }
        override fun stopStreaming() { val old=job;old?.cancel();runBlocking { old?.join() };job=null }
        override fun close() { stopStreaming();scope.cancel();stream.complete();state.value=state.value.copy(connected=false) }
        override fun isOpen()=state.value.connected
        override fun getSourceName()="Lifecycle fixture"
        override fun getCenterFrequency()=state.value.centerFreqHz
        override fun setCenterFrequency(freqHz:Long):Boolean { state.value=state.value.copy(centerFreqHz=freqHz);return true }
        override fun getSampleRate()=state.value.sampleRate
        override fun setSampleRate(rateHz:Int):Boolean {
            if(rateHz !in capabilities.sampleRates) return false
            state.value=state.value.copy(sampleRate=rateHz);return true
        }
        override fun setGainMode(mode:Int)=Unit
        override fun setGain(gainIndex:Int)=Unit
        override fun getGainIndex()=0
        override fun getGainDb()=0f
        override fun getGainCount()=1
        override fun setTunerAgcEnabled(enable:Boolean)=Unit
        override fun setHardwareAgcEnabled(enable:Boolean)=Unit
        override fun setBiasTee(enable:Boolean)=Unit
        override fun setDirectSampling(mode:Int)=Unit
        override fun getDirectSampling()=0
        override fun setPpmCorrection(ppm:Int)=Unit
        override fun getPpmCorrection()=0
    }

    @Test fun threeHundredNativeEngineStartStopCycles(): Unit = runBlocking {
        withTimeout(180000) {
            repeat(300) {
                val source=Source();source.open()
                val engine=DspEngine(source)
                try {
                    engine.setDemodMode(DemodMode.AM);engine.setVolume(0f);engine.start()
                    withTimeout(3000) { while(engine.pipelineDiagnostics().inputSamples==0L) delay(2) }
                    engine.stop();engine.stop()
                    val stopped=source.produced.get()
                    delay(2)
                    assertEquals(stopped,source.produced.get())
                    assertEquals(0,source.iqSubscriberCount)
                    assertNull(engine.failure.value)
                } finally { engine.stop();source.close() }
            }
        }
    }

    @Test fun configurationAndOutputRateChangesWaitForOldWorkers(): Unit = runBlocking {
        val source=Source();source.open();val engine=DspEngine(source)
        try {
            engine.setVolume(0f);engine.start()
            repeat(12) { i ->
                engine.setDemodMode(if(i%2==0) DemodMode.AM else DemodMode.USB)
                engine.setIfBandwidth(if(i%2==0) 10000 else 3000)
                engine.setAudioSinkRate(if(i%2==0) 32000 else 48000)
                delay(30)
                assertNull(engine.failure.value)
            }
        } finally { engine.stop();source.close() }
        assertEquals(0,source.iqSubscriberCount)
    }
}
