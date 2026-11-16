package com.radiosport.ninegradio.dsp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class MonitorAudioBranchTest {
    private class Post:ListeningPostProcessor {
        var resets=0;var closes=0
        override fun process(stereo:FloatArray,rate:Int,mode:DemodMode) {
            for(i in stereo.indices) stereo[i]*=.8f
        }
        override fun reset() { resets++ }
        override fun close() { closes++ }
    }
    /** Frozen pre-extraction sequence, to detect ordering/gain/NR changes. */
    private class Reference {
        val noise=AudioDsp.NoiseReducer();val stereo=StereoAudioPath()
        val gate=ListeningSquelch();val post=Post()
        fun analog(input:FloatArray,mono:FloatArray,mode:DemodMode,s:MonitorAudioBranch.Settings,open:Boolean):FloatArray {
            val allowed=NoiseProcessingPolicy.allows(mode)
            val isStereo=mode==DemodMode.WFM_STEREO
            var output=if(isStereo) stereo.process(input,48000,48000,s.noiseReduction && allowed,false) else mono.copyOf()
            if(!isStereo) {
                if(allowed) output=noise.processListening(output,s.noiseReduction,48000)
                output=StereoAudioPath.duplicateMono(output)
            }
            post.process(output,48000,mode)
            gate.apply(output,s.listening && open,48000)
            if(s.volume>1.001f) for(i in output.indices) output[i]=(output[i]*s.volume).coerceIn(-1f,1f)
            return output
        }
    }
    @Test fun analogExtractionPreservesSamplesAndNeverMutatesDecoderPcm() {
        for(mode in listOf(DemodMode.AM,DemodMode.WFM,DemodMode.WFM_STEREO,DemodMode.RAW)) {
            val post=Post();val branch=MonitorAudioBranch(post);val reference=Reference()
            branch.setNoiseStrength(.7f);reference.noise.setStrength(.7f);reference.stereo.setStrength(.7f)
            var sample=0
            repeat(24) { index ->
                val n=listOf(1,37,1024,503,946)[index%5]
                val mono=FloatArray(n) { sin((sample+it)*.12).toFloat()*.3f }
                sample+=n
                val original=mono.copyOf()
                val stereo=FloatArray(n*2) { if(it%2==0) mono[it/2] else -mono[it/2]*.6f }
                val originalStereo=stereo.copyOf()
                val settings=MonitorAudioBranch.Settings(index%7!=0,index in 4..18,if(index%2==0) 1.4f else .5f)
                val open=index%5!=0
                val expected=reference.analog(stereo,mono,mode,settings,open)
                val actual=branch.analog(stereo,48000,mono,48000,mode,settings,open)
                assertArrayEquals("$mode block $index",expected,actual,0f)
                assertArrayEquals(original,mono,0f);assertArrayEquals(originalStereo,stereo,0f)
                if(actual.isNotEmpty()) actual[0]=99f
                assertArrayEquals(original,mono,0f)
            }
            branch.close();assertEquals(1,post.closes)
        }
    }
    @Test fun voiceGainMuteAndDiscontinuityMatchPreviousSequence() {
        val branch=MonitorAudioBranch(Post());val fade=ListeningSquelch();val post=Post()
        repeat(12) { index ->
            val input=FloatArray(960) { sin(it*.2).toFloat()*.25f };val original=input.copyOf()
            val s=MonitorAudioBranch.Settings(index!=4,false,if(index%2==0) .5f else 1.5f)
            val discontinuity=index==7;val open=index%3!=0
            if(discontinuity) fade.reset()
            val expected=StereoAudioPath.duplicateMono(input)
            if(!s.listening) expected.fill(0f)
            post.process(expected,48000,DemodMode.DMR)
            if(s.volume!=1f) for(i in expected.indices) expected[i]=(expected[i]*s.volume).coerceIn(-1f,1f)
            fade.apply(expected,s.listening && open,48000)
            assertArrayEquals(expected,branch.voice(input,48000,DemodMode.DMR,s,open,discontinuity),0f)
            assertArrayEquals(original,input,0f)
        }
        branch.close()
    }
    @Test fun resetAndCloseOwnThePlatformProcessorExactlyOnce() {
        val post=Post();val branch=MonitorAudioBranch(post)
        branch.reset();assertEquals(1,post.resets)
        branch.close();branch.close();assertEquals(1,post.closes)
        assertThrows(IllegalStateException::class.java) { branch.reset() }
        assertThrows(IllegalStateException::class.java) { branch.updateSquelch(-60f,-80f,.01) }
    }
    @Test fun squelchMeasurementDoesNotRequireAnyAudioOrDisplayConsumer() {
        val branch=MonitorAudioBranch(Post())
        assertTrue(branch.updateSquelch(-60f,-80f,.1))
        assertTrue(branch.updateSquelch(-90f,-80f,.1))
        assertFalse(branch.updateSquelch(-90f,-80f,.11))
        assertFalse(branch.updateSquelch(-79.5f,-80f,.1))
        assertTrue(branch.updateSquelch(-78f,-80f,.1))
        branch.close()
    }
}
