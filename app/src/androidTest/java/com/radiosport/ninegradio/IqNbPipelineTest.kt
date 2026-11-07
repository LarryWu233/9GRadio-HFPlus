package com.radiosport.ninegradio

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.radiosport.ninegradio.dsp.*
import com.radiosport.ninegradio.source.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.*

/** End-to-end IQ -> channel filter -> AM -> AudioTrack recording, both formats. */
@RunWith(AndroidJUnit4::class)
class IqNbPipelineTest {
    private class Source(private val unsigned: Boolean,private val wfm: Boolean=false,val rate: Int=228000): IqSource by RtlTcpSource("127.0.0.1",0) {
        val total=rate*2
        private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        private val blocks=MutableSharedFlow<IqBlock>(extraBufferCapacity=128,onBufferOverflow=BufferOverflow.DROP_OLDEST)
        override val iqFlow=blocks.asSharedFlow()
        override val capabilities=if(unsigned) DeviceCapabilities.RTL.copy(sampleRates=listOf(rate)) else DeviceCapabilities.airspy(listOf(rate))
        override fun getSampleRate()=rate
        override fun getCenterFrequency()=11916000L
        private var phase=0.0
        val values=FloatArray(total*2) { index ->
            val n=index/2
            val magnitude=if(wfm) .06 else .06*(1+.4*sin(2*PI*900*n/rate))
            if(wfm && index%2==0) phase+=2*PI*60000*sin(2*PI*900*n/rate)/rate
            val angle=if(wfm) phase else 2*PI*2000*n/rate
            val pulse=if(n>rate/2 && n%(rate/25)<if(wfm) maxOf(12,rate/20000) else 12) .8 else 0.0
            (magnitude*if(index%2==0) cos(angle) else sin(angle)).toFloat()+pulse.toFloat()
        }
        val bytes=ByteArray(values.size) { (values[it]*128+127.5f).roundToInt().coerceIn(0,255).toByte() }
        val expected: ByteArray=if(unsigned) bytes.copyOf() else ByteBuffer.allocate(values.size*4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(values) }.array()
        @Volatile var finished=false
        override fun startStreaming(bufferSize: Int) {
            scope.launch {
                withTimeout(5000) { blocks.subscriptionCount.first { it>0 } }
                val started=SystemClock.elapsedRealtimeNanos()
                var frame=0; var sequence=0L
                while(frame<total) {
                    val next=min(total,frame+4096)
                    blocks.emit(if(unsigned) IqBlock.Unsigned8(bytes.copyOfRange(frame*2,next*2))
                        else IqBlock.Float32(values.copyOfRange(frame*2,next*2),sequence++))
                    frame=next
                    val remaining=started+frame*1000000000L/rate-SystemClock.elapsedRealtimeNanos()
                    if(remaining>0) delay(remaining/1000000)
                }
                finished=true
            }
        }
        override fun stopStreaming() { scope.cancel() }
        override fun close() { scope.cancel() }
    }
    private suspend fun measure(unsigned: Boolean,enabled: Boolean,wfm: Boolean=false,rateOverride: Int=228000): Double {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=Source(unsigned,wfm,rateOverride)
        val engine=DspEngine(source)
        val suffix="${if(wfm) "wfm-$rateOverride" else if(unsigned) "u8" else "float"}-${if(enabled) "on" else "off"}"
        val audio=File(context.cacheDir,"alpha12-nb-$suffix.wav")
        val iq=File(context.cacheDir,"alpha12-nb-$suffix.iq")
        try {
            engine.setDemodMode(if(wfm) DemodMode.WFM else DemodMode.AM); engine.setIfBandwidth(if(wfm) 200000 else 12500)
            engine.setSquelchLevel(-120f); engine.setVolume(0f)
            engine.setNoiseBlankerEnabled(enabled); engine.setNoiseReducerEnabled(false)
            engine.startAudioRecording(audio.path); engine.startIqRecording(iq.path)
            engine.start()
            withTimeout(12000) {
                while(!source.finished || engine.getIqNoiseBlankerStats().processedSamples<source.total) delay(20)
            }
            delay(400)
            engine.stopAudioRecording(); engine.stopIqRecording()
            assertArrayEquals("Raw source IQ must be retained",source.expected,iq.readBytes())
            val data=ByteBuffer.wrap(audio.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val rate=data.getInt(24); val frames=data.getInt(40)/4
            assertEquals(48000,rate)
            assertEquals(96000.0,frames.toDouble(),960.0)
            // SINAD-style residual on complete 20 ms windows (18 tone cycles).
            // Fit DC and tone independently in each window: the AM chain's
            // inaudible DC and slow AGC drift must not count as impulse noise.
            var error=0.0; var power=0.0
            val window=rate/50
            for(start in rate until frames-window step window) {
                var mean=0.0; var re=0.0; var im=0.0
                for(j in 0 until window) {
                    val sample=data.getShort(44+(start+j)*4)/32768.0
                    mean+=sample
                    re+=sample*cos(2*PI*900*j/rate); im+=sample*sin(2*PI*900*j/rate)
                }
                mean/=window; re*=2.0/window; im*=2.0/window
                for(j in 0 until window) {
                    val sample=data.getShort(44+(start+j)*4)/32768.0
                    error+=(sample-mean-re*cos(2*PI*900*j/rate)-im*sin(2*PI*900*j/rate)).pow(2)
                    power+=(sample-mean).pow(2)
                }
            }
            return error/power
        } finally { engine.stop(); source.close(); iq.delete() }
    }
    private fun verify(unsigned: Boolean)=runBlocking {
        val before=measure(unsigned,false); val after=measure(unsigned,true)
        val change=10*log10(after/before)
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"alpha12-pipeline-${if(unsigned) "u8" else "float"}.json")
            .writeText(Gson().toJson(mapOf("format" to if(unsigned) "u8" else "float32","beforeResidualPower" to before,
                "afterResidualPower" to after,"residualChangeDb" to change,"rawIqUnchanged" to true,
                "metric" to "20ms windows: fit DC and 900Hz tone, normalize remaining residual power")))
        assertTrue("AM impulse residual change $change dB",change< -6)
    }
    @Test fun floatIqImpulseRepairImprovesAmAndPreservesRawRecording()=verify(false)
    @Test fun uint8IqImpulseRepairImprovesAmAndPreservesRawRecording()=verify(true)
    private fun verifyWfm(rate: Int)=runBlocking {
        val before=measure(false,false,true,rate); val after=measure(false,true,true,rate)
        val change=10*log10(after/before)
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"alpha12-pipeline-wfm-$rate.json")
            .writeText(Gson().toJson(mapOf("iqRate" to rate,"beforeResidualPower" to before,"afterResidualPower" to after,
                "residualChangeDb" to change,"rawIqUnchanged" to true)))
        assertTrue("WFM pulse residual change $change dB",change<0.25)
    }
    @Test fun wfmPreservesPhaseAt228k()=verifyWfm(228000)
    @Test fun wfmPreservesPhaseBefore912kChannelDecimation()=verifyWfm(912000)
}
