package com.radiosport.ninegradio.dsp

import com.radiosport.ninegradio.audio.PcmPacketizer
import com.radiosport.ninegradio.pipeline.AudioBlock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicLong

/** Ordered 48 kHz decoder input; expensive native decoding cannot stall IQ processing. */
class DigitalVoiceStage(scope:CoroutineScope, private val onFailure:(Long,Exception)->Unit):AutoCloseable {
    private data class Work(val audio:AudioBlock,val mode:DemodMode,val rfDb:Float)
    private val audioQueue=Channel<DigitalFrame>(16,BufferOverflow.DROP_OLDEST)
    val audioFlow=audioQueue.receiveAsFlow()
    val decoder=DigitalVoiceDecoder { if(it.pcmAudio.isNotEmpty()) audioQueue.trySend(it) }
    val droppedPackets=AtomicLong()
    private val packets=PcmPacketizer(48000,1)
    private var firstFrame=0L
    private var lastMode=DemodMode.DMR
    private var lastRfDb=Float.NaN
    @Volatile private var epoch=0L
    @Volatile private var closing=false
    private val queue=Channel<Work>(8,BufferOverflow.DROP_OLDEST,
        onUndeliveredElement={ if(!closing) droppedPackets.incrementAndGet() })
    private val job=scope.launch(Dispatchers.Default) {
        var previousEpoch=-1L;var nextFrame=-1L
        try {
        for(work in queue) {
            if(work.audio.epoch!=epoch || closing) continue
            if(previousEpoch!=work.audio.epoch || nextFrame!=work.audio.firstFrame) {
                decoder.setMode(work.mode);decoder.reset()
            }
            previousEpoch=work.audio.epoch;nextFrame=work.audio.firstFrame+work.audio.frameCount
            decoder.feed(work.audio.samples,work.rfDb,work.audio.epoch)
        }
        } catch(cancel:CancellationException) { throw cancel }
        catch(error:Exception) { if(!closing) onFailure(previousEpoch,error) }
    }
    /** Called at the DSP's block/configuration boundary; never acquires the decoder lock. */
    fun configure(nextEpoch:Long) {
        epoch=nextEpoch;decoder.acceptEpoch(nextEpoch);packets.reset();firstFrame=0
        while(queue.tryReceive().isSuccess) { }
    }
    fun offer(audio:FloatArray,mode:DemodMode,rfDb:Float,currentEpoch:Long) {
        if(closing) return
        if(currentEpoch!=epoch) configure(currentEpoch)
        lastMode=mode;lastRfDb=rfDb
        packets.append(audio) {
            val frame=AudioBlock(it,48000,1,currentEpoch,firstFrame)
            firstFrame+=frame.frameCount
            queue.trySend(Work(frame,mode,rfDb))
        }
    }
    suspend fun finishInput() {
        packets.flush {
            val frame=AudioBlock(it,48000,1,epoch,firstFrame)
            queue.trySend(Work(frame,lastMode,lastRfDb))
        }
        queue.close();job.join();audioQueue.close()
    }
    fun stopAccepting() { closing=true;decoder.acceptEpoch(Long.MIN_VALUE) }
    override fun close() {
        stopAccepting();queue.cancel();audioQueue.cancel();runBlocking { job.cancelAndJoin() };decoder.close()
    }
}
