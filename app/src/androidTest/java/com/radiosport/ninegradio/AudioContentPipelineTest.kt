package com.radiosport.ninegradio

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.content.*
import com.radiosport.ninegradio.dsp.*
import com.radiosport.ninegradio.source.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class AudioContentPipelineTest {
    private class MorseIq(private val keyedCarrier: Boolean=false,private val carrierHz: Int=100,val rate: Int=240000,private val blocker: Boolean=false): IqSource by RtlTcpSource("127.0.0.1",0) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val blocks=MutableSharedFlow<IqBlock>(extraBufferCapacity=4)
        override val iqFlow=blocks.asSharedFlow()
        override val capabilities=DeviceCapabilities.airspy(listOf(rate))
        override fun getSampleRate()=rate
        @Volatile var tunedFrequency=11920000L
        override fun getCenterFrequency()=tunedFrequency
        override fun setCenterFrequency(freqHz: Long): Boolean { tunedFrequency=freqHz; return true }
        val segments=mutableListOf<Pair<Int,Boolean>>()
        @Volatile var elapsedMs=0L
        init {
            fun segment(sec: Double,on: Boolean) { segments.add((sec*rate).roundToInt() to on) }
            val codes=mapOf('V' to "...-",'T' to "-",'E' to ".",'S' to "...")
            segment(1.0,false)
            "VVV TEST".forEach { c -> if(c==' ') segment(.24,false) else {
                codes.getValue(c).forEach { mark -> segment(if(mark=='.') .06 else .18,true); segment(.06,false) }
                segment(.12,false)
            } }; segment(2.0,false)
        }
        override fun startStreaming(bufferSize: Int) {
            scope.launch {
                blocks.subscriptionCount.first { it>0 }
                var n=0L; var sequence=0L; val start=SystemClock.elapsedRealtimeNanos()
                for((size,on) in segments) {
                    var left=size
                    while(left>0) {
                        val count=min(4096,left)
                        val data=FloatArray(count*2)
                        for(i in 0 until count) {
                            val envelope=if(keyedCarrier) { if(on) .06 else 0.0 } else .06*(1+if(on) .6*sin(2*PI*700*(n+i)/rate) else 0.0)
                            val phase=2*PI*(if(keyedCarrier) carrierHz+11920000L-tunedFrequency else 2000L)*(n+i)/rate
                            data[i*2]=(envelope*cos(phase)).toFloat(); data[i*2+1]=(envelope*sin(phase)).toFloat()
                            if(blocker) {
                                val interference=2*PI*(8000+11920000L-tunedFrequency)*(n+i)/rate
                                data[i*2]+=(.6*cos(interference)).toFloat(); data[i*2+1]+=(.6*sin(interference)).toFloat()
                            }
                        }
                        blocks.emit(IqBlock.Float32(data,sequence++)); n+=count; left-=count
                        val wait=start+n*1_000_000_000L/rate-SystemClock.elapsedRealtimeNanos()
                        if(wait>0) delay(wait/1_000_000)
                    }
                }
                elapsedMs=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000
            }
        }
        override fun stopStreaming() { scope.cancel() }
        override fun close() { scope.cancel() }
    }
    private fun cwMode(mode: DemodMode,carrierHz: Int=100,rate: Int=240000) = runBlocking {
        val source=MorseIq(true,carrierHz,rate,rate>1_000_000); val engine=DspEngine(source)
        val receiver=AudioContentReceiver(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            engine.setDemodMode(mode); engine.setIfBandwidth(500); engine.setVolume(0f)
            engine.setCarrierFrequency(11920000L)
            assertEquals(11920000L+DspEngine.cwTunerOffset(11920000L,source.capabilities,source.rate),source.tunedFrequency)
            receiver.attach(engine); receiver.start(ContentConfig(AudioContentKind.CW,wpm=20))
            val chunks=java.util.concurrent.ConcurrentLinkedQueue<FloatArray>()
            val sink=DspEngine::class.java.getDeclaredField("contentAudioSink").apply { isAccessible=true }.get(engine) as (FloatArray)->Unit
            engine.setContentAudioSink { data -> chunks.add(data.copyOf()); sink(data) }
            engine.start()
            val decoded=withTimeoutOrNull(12000) { while(!receiver.state.value.text.replace(Regex("\\s+"),"").contains("TEST")) delay(100); true } ?: false
            assertTrue("$mode: ${receiver.state.value}",decoded)
            val snapshot=chunks.toList()
            val samples=FloatArray(snapshot.sumOf { it.size }); var index=0
            snapshot.forEach { block -> block.copyInto(samples,index); index+=block.size }
            fun power(frequency: Int): Double {
                var a=0.0; var b=0.0
                for(n in 48_000*140/100 until minOf(samples.size,48_000*150/100)) {
                    val angle=2*PI*frequency*n/48000
                    a+=samples[n]*cos(angle); b+=samples[n]*sin(angle)
                }
                return a*a+b*b
            }
            val expected=if(mode==DemodMode.CW) 800+carrierHz else 800-carrierHz
            assertTrue("$mode tone must stay $expected Hz at BW=500",power(expected)>10*max(power(100),power(200)))
            if(rate>1_000_000) {
                withTimeout(12000) { while(source.elapsedMs==0L) delay(50) }
                val nominal=source.segments.sumOf { it.first }*1000L/rate
                assertTrue("CW processing too slow: ${source.elapsedMs} ms / $nominal ms",source.elapsedMs<nominal*1.15)
                delay(250)
                val expectedFrames=source.segments.sumOf { it.first }*48000L/rate
                assertEquals("No IQ/audio frames lost",expectedFrames,chunks.sumOf { it.size }.toLong())
                java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"alpha18-cw-realtime.txt")
                    .writeText("rate=$rate; wallMs=${source.elapsedMs}; nominalMs=$nominal; frames=${chunks.sumOf { it.size }}; blockerOffsetHz=8000; blockerRelativeDb=20; decoded=${receiver.state.value.text}")
            }
        } finally { receiver.stop(); engine.stop(); source.close() }
    }
    @Test fun cwModeSoftwareMorseAt500HzBandwidth()=cwMode(DemodMode.CW)
    @Test fun cwrModeSoftwareMorseAt500HzBandwidth()=cwMode(DemodMode.CWR)
    @Test fun cwModeCenteredCarrier()=cwMode(DemodMode.CW,0)
    @Test fun cwrModeCenteredCarrier()=cwMode(DemodMode.CWR,0)
    @Test fun cwModeAtRtl1920kKeepsRealtime()=cwMode(DemodMode.CW,0,1_920_000)
    @Test fun offsetTunedCwIqRecordingReplaysAtTheSameDialFrequency() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=java.io.File(context.cacheDir,"alpha18-cw-roundtrip.cf32")
        val input=MorseIq(true,0); val engine=DspEngine(input)
        var replay: FileIqSource?=null; var replayEngine: DspEngine?=null
        val receiver=AudioContentReceiver(context)
        try {
            engine.setDemodMode(DemodMode.CW); engine.setIfBandwidth(500); engine.setVolume(0f)
            engine.setCarrierFrequency(11920000)
            com.radiosport.ninegradio.recording.IqRecordingInfo.write(file,engine.getDialFrequencyHz(),input.getCenterFrequency(),input.rate,DemodMode.CW,true)
            engine.startIqRecording(file.path); engine.start()
            withTimeout(12000) { while(input.elapsedMs==0L) delay(50) }
            delay(300); engine.stopIqRecording(); engine.stop(); input.close()
            assertEquals(input.segments.sumOf { it.first }*8L,file.length())
            val loaded=FileIqSource(file,input.rate,11920000,DemodMode.CW).also { replay=it }
            assertEquals(11920000L,loaded.dialFrequencyHz); assertEquals(11932000L,loaded.getCenterFrequency())
            assertTrue(loaded.open())
            val dsp=DspEngine(loaded).also { replayEngine=it }
            dsp.setDemodMode(loaded.mode); dsp.setIfBandwidth(500); dsp.setCarrierFrequency(loaded.dialFrequencyHz); dsp.setVolume(0f)
            receiver.attach(dsp); receiver.start(ContentConfig(AudioContentKind.CW,wpm=20)); dsp.start()
            withTimeout(12000) { while(!receiver.state.value.text.replace(Regex("\\s+"),"").contains("TEST")) delay(100) }
            assertEquals(11920000L,dsp.getDialFrequencyHz())
        } finally {
            receiver.stop(); replayEngine?.stop(); replay?.close(); engine.stop(); input.close()
            file.delete(); com.radiosport.ninegradio.recording.IqRecordingInfo.metadataFile(file).delete()
        }
    }
    @Test fun softwareInputDecodesActualDemodulatedIqAndBypassesListeningNoiseProcessing() = runBlocking {
        val source=MorseIq(); val engine=DspEngine(source)
        val receiver=AudioContentReceiver(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            engine.setDemodMode(DemodMode.AM); engine.setIfBandwidth(12500)
            engine.setNoiseBlankerEnabled(true); engine.setNoiseReducerEnabled(true); engine.setVolume(0f)
            receiver.attach(engine); receiver.start(ContentConfig(AudioContentKind.CW,wpm=20))
            val chunks=java.util.concurrent.ConcurrentLinkedQueue<FloatArray>()
            val sink=DspEngine::class.java.getDeclaredField("contentAudioSink").apply { isAccessible=true }.get(engine) as (FloatArray)->Unit
            engine.setContentAudioSink { data -> chunks.add(data.copyOf()); sink(data) }
            engine.start()
            val decoded=withTimeoutOrNull(18000) { while(!receiver.state.value.text.replace(Regex("\\s+"),"").contains("TEST")) delay(100); true } ?: false
            val directory=InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            java.io.File(directory,"alpha18-pipeline-raw.f32").outputStream().use { output -> chunks.forEach { chunk ->
                output.write(java.nio.ByteBuffer.allocate(chunk.size*4).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(chunk) }.array())
            } }
            val details="state=${receiver.state.value}; frames=${chunks.sumOf { it.size }}; iqFrames=${source.segments.sumOf { it.first }}"
            java.io.File(directory,"alpha18-pipeline-state.txt").writeText(details)
            assertTrue(details,decoded)
            assertTrue(receiver.state.value.sampleCount>48000)
            assertFalse("Audio data must bypass IQ NB",engine.getIqNoiseBlankerStats().active)
            assertEquals(ContentInput.SOFTWARE,receiver.state.value.config?.input)
            receiver.attach(null)
            assertFalse(receiver.state.value.running)
            assertEquals("接收机已关闭",receiver.state.value.status)
        } finally { receiver.stop(); engine.stop(); source.close() }
    }
}
