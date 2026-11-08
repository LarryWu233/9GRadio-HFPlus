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
    private class Source : IqSource {
        private val stream = IqStream()
        private val clock = CaptureClock()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var job: Job? = null
        val produced = AtomicLong()
        override val iqFlow = stream.flow
        override val iqSubscriberCount get() = stream.subscriberCount
        private val state = MutableStateFlow(IqSource.SourceStatus(sampleRate=48000,centerFreqHz=10000000))
        override val statusFlow = state.asStateFlow()
        override val capabilities = DeviceCapabilities("fixture",listOf(1L..260000000L),listOf(48000),false,false,false,true,480)
        override fun open(): Boolean { state.value=state.value.copy(connected=true); return true }
        override fun startStreaming(bufferSize: Int) {
            check(isOpen())
            job=scope.launch {
                val data=FloatArray(960) { i -> if(i%2==0) 0.1f else 0.02f }
                while(isActive) {
                    val meta=clock.stamp(480,48000,getCenterFrequency())
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
        override fun getSampleRate()=48000
        override fun setSampleRate(rateHz:Int)=rateHz==48000
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
                    withTimeout(3000) { while(source.produced.get()==0L) delay(2) }
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
