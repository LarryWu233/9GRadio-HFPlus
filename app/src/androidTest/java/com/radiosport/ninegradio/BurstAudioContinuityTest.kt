package com.radiosport.ninegradio

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.source.*
import com.radiosport.ninegradio.dsp.*
import com.radiosport.ninegradio.debug.DebugBus
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class BurstAudioContinuityTest {
    private class BurstSource : IqSource by RtlTcpSource("127.0.0.1",0) {
        val rate=912000
        private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        private val blocks=MutableSharedFlow<IqBlock>(extraBufferCapacity=128,onBufferOverflow=BufferOverflow.DROP_OLDEST)
        override val iqFlow=blocks.asSharedFlow()
        override val iqSubscriberCount get()=blocks.subscriptionCount.value
        override val capabilities=DeviceCapabilities.airspy(listOf(rate))
        override fun getSampleRate()=rate
        override fun getCenterFrequency()=100000000L
        @Volatile var finished=false
        override fun startStreaming(bufferSize: Int) {
            scope.launch {
                val start=SystemClock.elapsedRealtimeNanos()
                var count=0; var sequence=0L; var phase=0.0
                while(count<rate*4) {
                    // Sixteen HF+ sized callbacks arrive together every ~72 ms.
                    repeat(16) {
                        if(count<rate*4) {
                            val size=minOf(4096,rate*4-count)
                            val iq=FloatArray(size*2)
                            for(i in 0 until size) {
                                phase+=2*PI*60000*sin(2*PI*1000*count++/rate)/rate
                                iq[2*i]=(0.6*cos(phase)).toFloat(); iq[2*i+1]=(0.6*sin(phase)).toFloat()
                            }
                            blocks.emit(IqBlock.Float32(iq,sequence++))
                        }
                    }
                    val remaining=start+count.toLong()*1000000000L/rate-SystemClock.elapsedRealtimeNanos()
                    if(remaining>0) delay(remaining/1000000L)
                }
                finished=true
            }
        }
        override fun stopStreaming() { scope.cancel() }
        override fun close() { scope.cancel() }
    }
    @Test fun burst48kMono()=verify(48000,DemodMode.WFM)
    @Test fun burst44100Mono()=verify(44100,DemodMode.WFM)
    @Test fun burst44100Stereo()=verify(44100,DemodMode.WFM_STEREO)
    @Test fun burst48kMonoWithIqNb()=verify(48000,DemodMode.WFM,true)
    private fun verify(sinkRate: Int,mode: DemodMode,nb: Boolean=false): Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"burst-audio-continuity.wav")
        val source=BurstSource(); val engine=DspEngine(source)
        val beforeDrops=DebugBus.getIqFlowDropCount()
        try {
            engine.setDemodMode(mode); engine.setFftSize(2048)
            engine.setAudioSinkRate(sinkRate); engine.setSquelchLevel(-120f); engine.setVolume(0f)
            engine.setNoiseBlankerEnabled(nb)
            engine.startAudioRecording(file.path)
            engine.start()
            var baselineUnderruns=-1; var lastUnderruns=0
            val start=SystemClock.elapsedRealtime()
            var repeatedMode=false
            withTimeout(15000) { while(!source.finished) {
                delay(25)
                if(!repeatedMode && SystemClock.elapsedRealtime()-start>1800) { engine.setDemodMode(mode); repeatedMode=true }
                if(SystemClock.elapsedRealtime()-start>700) {
                    lastUnderruns=engine.getPlaybackUnderruns()
                    if(baselineUnderruns<0) baselineUnderruns=lastUnderruns
                }
            } }
            assertEquals("Playback must not underrun during steady burst reception",baselineUnderruns,lastUnderruns)
            delay(900)
            if(nb) { assertTrue(engine.getIqNoiseBlankerStats().active); assertEquals(source.rate*4L,engine.getIqNoiseBlankerStats().processedSamples) }
            engine.stopAudioRecording()
            val bytes=file.readBytes()
            val header=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(2,header.getShort(22).toInt())
            val frames=header.getInt(40)/4
            assertEquals("Every generated audio frame must reach recording",sinkRate*4.0,frames.toDouble(),sinkRate/50.0)
            val first=sinkRate/2; val last=frames-sinkRate/20
            val samples=FloatArray(last-first) { header.getShort(44+(first+it)*4)/32768f }
            var cosine=0.0; var sine=0.0; var power=0.0
            for(i in samples.indices) {
                val angle=2*PI*1000*(first+i)/sinkRate
                cosine+=samples[i]*cos(angle); sine+=samples[i]*sin(angle); power+=samples[i]*samples[i]
            }
            cosine*=2.0/samples.size; sine*=2.0/samples.size
            var error=0.0
            for(i in samples.indices) {
                val angle=2*PI*1000*(first+i)/sinkRate
                val residual=samples[i]-cosine*cos(angle)-sine*sin(angle)
                error+=residual*residual
            }
            val relative=sqrt(error/power)
            assertTrue("Sample loss / phase discontinuity: $relative",relative<0.03)
            assertEquals("IQ queue must preserve burst callbacks",beforeDrops,DebugBus.getIqFlowDropCount())
            assertEquals("PCM queue must preserve burst audio","0",DebugBus.getExtras(DebugBus.STAGE_AUDIO_TRACK)["PCM_QUEUE_DROPS"]?:"0")
            println("BURST_AUDIO frames=$frames relativeToneResidual=$relative iqDrops=0 pcmDrops=0 steadyUnderruns=${lastUnderruns-baselineUnderruns}")
            File(context.cacheDir,"burst-audio-$sinkRate-${mode.name}.json").writeText("""{"frames":$frames,"sampleRate":$sinkRate,"relativeToneResidual":$relative,"iqDrops":0,"pcmDrops":0,"steadyUnderruns":${lastUnderruns-baselineUnderruns}}""")
        } finally { engine.stop(); source.close(); file.delete() }
    }
}
