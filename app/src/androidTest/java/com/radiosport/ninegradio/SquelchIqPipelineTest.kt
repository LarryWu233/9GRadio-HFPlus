package com.radiosport.ninegradio

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.radiosport.ninegradio.dsp.*
import com.radiosport.ninegradio.source.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class SquelchIqPipelineTest {
    @Test fun oneMinuteQuietAmWithPersistentBlockerStaysMuted()=runBlocking {
        val source=AircraftIq();val engine=DspEngine(source)
        val armed=AtomicBoolean(false);val unwantedOpens=AtomicInteger()
        val monitor=launch { engine.statsFlow.collect { if(armed.get() && it.squelchOpen) unwantedOpens.incrementAndGet() } }
        try {
            engine.setFftSize(2048);engine.setDemodMode(DemodMode.AM);engine.setIfBandwidth(10000)
            engine.setCarrierFrequency(125000000);engine.setVolume(0f);engine.setSquelchLevel(-65f)
            engine.start();delay(1000)
            armed.set(true)
            var checked=0
            repeat(65) {
                delay(1000)
                assertTrue("Persistent adjacent carrier remains present",engine.statsFlow.value.widebandPeakDb > -30f)
                assertFalse("Quiet selected channel must remain muted for over a minute",engine.statsFlow.value.squelchOpen)
                checked++
            }
            assertEquals(65,checked)
            assertEquals("No brief reopen between one-second checks",0,unwantedOpens.get())
        } finally { monitor.cancelAndJoin();engine.stop();source.close() }
    }
    private class AircraftIq: IqSource by RtlTcpSource("127.0.0.1",0) {
        private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        private val blocks=MutableSharedFlow<IqBlock>(extraBufferCapacity=4)
        override val iqFlow=blocks.asSharedFlow()
        override val capabilities=DeviceCapabilities.airspy(listOf(240000))
        override fun getSampleRate()=240000
        override fun getCenterFrequency()=125_000_000L
        override fun setCenterFrequency(freqHz:Long)=true
        @Volatile var amplitude=0.000316
        override fun startStreaming(bufferSize:Int) {
            scope.launch {
                blocks.subscriptionCount.first { it>0 }
                var n=0L;var sequence=0L;val start=SystemClock.elapsedRealtimeNanos()
                while(isActive) {
                    val samples=FloatArray(8192)
                    for(i in 0 until 4096) {
                        val t=(n+i)/240000.0
                        val wanted=amplitude*(1+0.3*sin(2*PI*700*t))
                        samples[2*i]=(wanted*cos(2*PI*1000*t)+0.1*cos(2*PI*60000*t)).toFloat()
                        samples[2*i+1]=(wanted*sin(2*PI*1000*t)+0.1*sin(2*PI*60000*t)).toFloat()
                    }
                    blocks.emit(IqBlock.Float32(samples,sequence++));n+=4096
                    val wait=start+n*1_000_000_000L/240000-SystemClock.elapsedRealtimeNanos()
                    if(wait>0) delay(wait/1_000_000)
                }
            }
        }
        override fun stopStreaming() { scope.cancel() }
        override fun close() { scope.cancel() }
    }
    @Test fun aviationAmIgnoresStrongAdjacentSignalAndClosesAtVisibleThreshold()=runBlocking {
        val source=AircraftIq();val engine=DspEngine(source);val decoderFrames=AtomicLong()
        try {
            engine.setFftSize(2048);engine.setDemodMode(DemodMode.AM);engine.setIfBandwidth(10000)
            engine.setCarrierFrequency(125000000);engine.setVolume(0f);engine.setSquelchLevel(-65f)
            engine.setContentAudioSink { data -> decoderFrames.addAndGet(data.size.toLong()) }
            engine.start();delay(1100)
            assertTrue(engine.statsFlow.value.widebandPeakDb > -30f)
            assertTrue(engine.statsFlow.value.channelPeakDb < -65f)
            assertFalse("Strong off-channel carrier must not open aircraft AM",engine.statsFlow.value.squelchOpen)
            val before=decoderFrames.get()
            delay(250)
            assertTrue("Closed listening SQL must not cut decoder input",decoderFrames.get()>before)
            source.amplitude=0.00562;delay(450)
            assertTrue("Wanted carrier opens listening",engine.statsFlow.value.squelchOpen)
            var peak=engine.squelchReading.value.peakDb
            repeat(16) { delay(20);peak=maxOf(peak,engine.squelchReading.value.peakDb) }
            engine.setSquelchLevel(peak+0.7f);delay(350)
            assertFalse("Threshold close: stats=${engine.statsFlow.value}, meter=${engine.squelchReading.value}",engine.statsFlow.value.squelchOpen)
            engine.setSquelchLevel(-65f);delay(100)
            assertTrue(engine.statsFlow.value.squelchOpen)
            source.amplitude=0.000316;delay(450)
            assertFalse(engine.statsFlow.value.squelchOpen)
        } finally { engine.stop();source.close() }
    }
}
