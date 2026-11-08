package com.radiosport.ninegradio.dsp

import android.util.Log
import com.radiosport.ninegradio.pipeline.AudioBlock
import com.radiosport.ninegradio.pipeline.AudioBlockClock
import com.radiosport.ninegradio.pipeline.AudioDecoderInput
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Pipes the same raw, pre-vocoder FM-discriminator audio that feeds
 * [DigitalVoiceDecoder] (and the APRS/ACARS/FLEX decoders) out over UDP as
 * headerless, signed 16-bit little-endian mono PCM datagrams -- the exact
 * wire format documented by dsd-neo / dsd-fme's `-i udp[:bind_addr:port]`
 * input (default `127.0.0.1:7355`, sample rate given with `-s`, 48000 by
 * default) and by most other command-line DSD tools' UDP audio input.
 *
 * This lets 9GRadio act purely as an RTL-SDR front end / FM discriminator
 * for a full desktop decoder (dsd-neo, DSD-FME, DSDPlus, SDRTrunk's audio
 * input, etc.) running on a PC on the same network -- useful both as a
 * cross-check against the built-in dsdcc decoder and for protocols 9GRadio
 * does not implement natively (P25 Phase 2, EDACS, ProVoice, encrypted
 * traffic key handling, trunking control-channel following, and so on).
 *
 * Usage:
 *   stream.configure(host, port)   // may be called while running to retune
 *   stream.setEnabled(true)
 *   stream.send(decoderAudio)      // [-1, 1] float samples, called once per
 *                                  // DSP block, same thread
 *   stream.setEnabled(false)       // or stream.shutdown() when tearing down
 *
 * [send] is designed to be called inline from the DSP processing
 * thread/coroutine on every block, exactly like [DigitalVoiceDecoder.feed]
 * -- see the "FIX 21" / "FIX" comments in DspEngine.processIqBlock() for why
 * that call site must stay single-threaded and non-blocking. To honour that,
 * [send] never touches the socket itself: it just offers the (copied) sample
 * block onto a small bounded queue and returns immediately. A single
 * dedicated sender thread owns the [DatagramSocket] and does the actual
 * (blocking) I/O, encoding, and any host/port changes. If the queue is full
 * (the network can't keep up, or nothing is listening) the oldest pending
 * block is dropped rather than blocking or growing without bound -- audio
 * continuity for the *local* decoders must never depend on an external
 * listener being present or fast enough.
 */
class ExternalDecoderStream(private val resolve:(String)->InetAddress=InetAddress::getByName):AudioDecoderInput {

    companion object {
        private const val TAG = "ExternalDecoderStream"
        /** Matches dsd-neo/dsd-fme's default `-i udp` bind/port. */
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 7355
        /** Bounded so a stalled/absent listener can't build unbounded backlog. */
        private const val QUEUE_CAPACITY = 32
    }

    private data class Destination(val host:String,val port:Int)
    @Volatile private var destination=Destination(DEFAULT_HOST,DEFAULT_PORT)
    private val lifecycleLock=Any()
    private val inputEpoch=AtomicLong()
    @Volatile private var legacyClock=AudioBlockClock()
    @Volatile private var session:SenderSession?=null
    @Volatile private var finishing:SenderSession?=null
    @Volatile private var lastSession:SenderSession?=null
    @Volatile private var startError:String?=null
    val isEnabled get()=session?.active?.get()==true
    val hasPendingRelease get()=finishing?.worker?.isAlive==true
    val packetsSent get()=(session ?: lastSession)?.sent?.get() ?: 0L
    val packetsDropped get()=(session ?: lastSession)?.dropped?.get() ?: 0L
    val stalePackets get()=(session ?: lastSession)?.stale?.get() ?: 0L
    val lastError get()=if(hasPendingRelease) "外部解码发送线程仍在关闭，请稍候"
        else startError ?: (session ?: lastSession)?.error

    /** A replacement DSP owns a fresh epoch namespace. Previous production is joined first. */
    fun beginStream():Unit=synchronized(lifecycleLock) {
        check(!isEnabled) { "Stop external decoder input before replacing its stream" }
        inputEpoch.set(0);legacyClock=AudioBlockClock();lastSession=null;startError=null
    }

    fun configure(host:String,port:Int) {
        val next=Destination(host.ifBlank { DEFAULT_HOST },if(port in 1..65535) port else DEFAULT_PORT)
        if(next!=destination) destination=next
    }
    override fun configure(epoch:Long) {
        val old=inputEpoch.getAndAccumulate(epoch,::maxOf)
        if(epoch>old) session?.let { current ->
            while(current.queue.poll()!=null) current.stale.incrementAndGet()
        }
    }
    fun setEnabled(on:Boolean) { if(on) start() else stop() }
    private fun start():Unit=synchronized(lifecycleLock) {
        if(isEnabled) return@synchronized
        if(session!=null || finishing!=null) stop()
        // A DNS call may ignore interruption. Retain its owner and refuse reuse
        // instead of letting an old worker join the next session's run flag/queue.
        if(finishing?.worker?.isAlive==true) {
            startError="外部解码发送线程仍在关闭，请稍候";return@synchronized
        }
        finishing=null;startError=null
        val next=SenderSession()
        session=next;lastSession=next
        next.launch()
    }
    private fun stop(timeoutMs:Long=500):Unit=synchronized(lifecycleLock) {
        val old=session ?: finishing ?: return@synchronized
        session=null;finishing=old
        old.cancel()
        old.worker?.let { if(it!==Thread.currentThread()) it.join(timeoutMs.coerceAtLeast(1)) }
        if(old.worker?.isAlive!=true) finishing=null
    }
    /** Legacy copied PCM entry; typed callers avoid a redundant publication copy. */
    fun send(samples:FloatArray) {
        if(!isEnabled || samples.isEmpty()) return
        accept(legacyClock.publish(samples,48000,1,inputEpoch.get()))
    }
    override fun accept(block:AudioBlock) {
        val current=session ?: return
        if(!current.active.get() || block.frameCount==0) return
        require(block.sampleRate==48000 && block.channels==1) { "External decoder PCM must be 48 kHz mono" }
        if(block.epoch>inputEpoch.get()) configure(block.epoch)
        if(block.epoch!=inputEpoch.get()) { current.stale.incrementAndGet();return }
        if(!current.queue.offer(block)) {
            if(current.queue.poll()!=null) current.dropped.incrementAndGet()
            if(!current.queue.offer(block)) current.dropped.incrementAndGet()
        }
    }
    fun shutdown() { stop(5000) }

    private inner class SenderSession {
        val active=AtomicBoolean(true)
        val queue=ArrayBlockingQueue<AudioBlock>(QUEUE_CAPACITY)
        val sent=AtomicLong()
        val dropped=AtomicLong()
        val stale=AtomicLong()
        @Volatile var error:String?=null
        @Volatile var socket:DatagramSocket?=null
        @Volatile var worker:Thread?=null
        fun launch() {
            val next=thread(start=false,name="ExternalDecoderStream-sender",isDaemon=true) { run() }
            worker=next;next.start()
        }
        fun cancel() {
            active.set(false);socket?.close();worker?.interrupt();queue.clear()
        }
        private fun run() {
            try {
                val output=DatagramSocket()
                socket=output
                if(!active.get()) return
                var scratch=ByteArray(4096)
                var resolvedDestination:Destination?=null
                var address:InetAddress?=null
                while(active.get()) {
                    val block=queue.take()
                    if(block.epoch!=inputEpoch.get()) { stale.incrementAndGet();continue }
                    val target=destination
                    try {
                        if(resolvedDestination!==target || address==null) {
                            address=resolve(target.host);resolvedDestination=target
                        }
                        if(!active.get() || session!==this) break
                        if(destination!==target || block.epoch!=inputEpoch.get()) { stale.incrementAndGet();continue }
                        val needed=block.samples.size*2
                        if(scratch.size<needed) scratch=ByteArray(needed)
                        var offset=0
                        for(value in block.samples) {
                            val sample=(value*32767f).toInt().coerceIn(-32768,32767)
                            scratch[offset++]=(sample and 255).toByte()
                            scratch[offset++]=((sample shr 8) and 255).toByte()
                        }
                        output.send(DatagramPacket(scratch,needed,address,target.port))
                        sent.incrementAndGet()
                    } catch(error:Exception) {
                        if(active.get()) {
                            this.error=error.message;dropped.incrementAndGet()
                            address=null
                        }
                    }
                }
            } catch(_:InterruptedException) { /* cancellation */ }
            catch(error:Exception) {
                if(active.get()) { this.error=error.message;Log.w(TAG,"External decoder sender failed",error) }
            } finally { active.set(false);socket?.close();queue.clear() }
        }
    }
}
