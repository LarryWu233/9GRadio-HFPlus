package com.radiosport.ninegradio.pipeline

import com.radiosport.ninegradio.source.IqBlock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** A small adapter over coroutine Channels. Every subscriber owns its queue and loss policy. */
class IqStream(private val lossless: Boolean = false, private val budgetMs: Int = 200) {
    private class Subscription(lossless: Boolean) {
        val channel = Channel<IqBlock>(if (lossless) Channel.RENDEZVOUS else 512)
        var queuedSamples = 0L
    }
    private val subscribers = CopyOnWriteArrayList<Subscription>()
    private val lifecycle = Any()
    private var completed = false
    val droppedBlocks = AtomicLong()
    val droppedSamples = AtomicLong()
    val subscriberCount get() = subscribers.size

    val flow: Flow<IqBlock> = flow {
        val subscription = Subscription(lossless)
        synchronized(lifecycle) {
            if (completed) subscription.channel.close() else subscribers.add(subscription)
        }
        try {
            for (block in subscription.channel) {
                if (!lossless) synchronized(subscription) { subscription.queuedSamples -= block.sampleCount }
                emit(block)
            }
        } finally {
            subscribers.remove(subscription)
            subscription.channel.cancel()
        }
    }

    /** USB/TCP producer: no file I/O, decoder work, suspension or wait for consumers. */
    fun offer(block: IqBlock) {
        check(!lossless)
        for (subscription in subscribers) synchronized(subscription) {
            val budget = (block.metadata.sampleRate.toLong() * budgetMs / 1000).coerceAtLeast(block.sampleCount.toLong())
            while (subscription.queuedSamples + block.sampleCount > budget) {
                val old = subscription.channel.tryReceive().getOrNull() ?: break
                subscription.queuedSamples -= old.sampleCount
                droppedBlocks.incrementAndGet(); droppedSamples.addAndGet(old.sampleCount.toLong())
            }
            val result = subscription.channel.trySend(block)
            if (result.isSuccess) subscription.queuedSamples += block.sampleCount
            else if (!result.isClosed) {
                droppedBlocks.incrementAndGet(); droppedSamples.addAndGet(block.sampleCount.toLong())
            }
        }
    }

    /** Files: rendezvous with every active consumer; never silently discard input. */
    suspend fun send(block: IqBlock) {
        check(lossless)
        for (subscription in subscribers) {
            // The consumer may leave (first(), cancellation) while a send is pending.
            try { subscription.channel.send(block) }
            catch (_: ClosedSendChannelException) { /* subscriber completed */ }
            catch (closed: kotlinx.coroutines.CancellationException) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
            }
        }
    }

    /** Normal EOF drains queued data; close is also safe against new subscribers. */
    fun complete() = synchronized(lifecycle) {
        completed = true
        subscribers.forEach { it.channel.close() }
    }
}
