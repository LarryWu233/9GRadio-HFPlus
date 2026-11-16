package com.radiosport.ninegradio.pipeline

import com.radiosport.ninegradio.source.IqBlock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class StreamContractTest {
    @Test fun clockPreservesTailSizesAndSeparatesArrivalFromSamples() {
        val clock = CaptureClock()
        val first = clock.stamp(137, 768000, 10000000, 123)
        val second = clock.stamp(17, 768000, 10000000, 456, 9)
        assertEquals(0L, first.firstSample)
        assertEquals(146L, second.firstSample)
        assertEquals(first.epoch, second.epoch)
        assertEquals(456L, second.arrivalNanos)
        assertTrue(clock.stamp(1, 912000, 10000000).epoch > second.epoch)
        clock.restart()
        assertNotEquals(first.streamId, clock.stamp(1, 768000, 10000000).streamId)
    }

    @Test fun discontinuitiesArePerConsumerAndRejectReorderedData() {
        val clock = CaptureClock(); val fast = IqContinuity(); val slow = IqContinuity()
        val a = clock.stamp(4096, 768000, 10000000)
        val b = clock.stamp(137, 768000, 10000000)
        val c = clock.stamp(17, 768000, 10000000)
        fast.accept(a, 4096); slow.accept(a, 4096)
        assertEquals(IqContinuity.Change.CONTINUOUS, fast.accept(b, 137).change)
        assertEquals(IqContinuity.Change.CONTINUOUS, fast.accept(c, 17).change)
        val gap = slow.accept(c, 17)
        assertEquals(1L, gap.missingBlocks); assertEquals(137L, gap.missingSamples)
        assertEquals(IqContinuity.Change.STALE, slow.accept(b, 137).change)
        assertEquals(IqContinuity.Change.STALE, slow.accept(c, 17).change)
    }

    @Test fun liveOverflowIsBoundedInSignalTimeAndDoesNotBlockProducer() = runBlocking {
        val stream = IqStream(); val clock = CaptureClock(); val gate = CompletableDeferred<Unit>()
        val seen = mutableListOf<Long>()
        val consumer = launch(start = CoroutineStart.UNDISPATCHED) {
            stream.flow.take(3).collect { seen += it.metadata.sequence; if (seen.size == 1) gate.await() }
        }
        fun put() { val m = clock.stamp(100, 1000, 1000000); stream.offer(IqBlock.Unsigned8(ByteArray(200), m)) }
        put(); yield()
        repeat(10) { put() }
        assertEquals(8L, stream.droppedBlocks.get()); assertEquals(800L, stream.droppedSamples.get())
        gate.complete(Unit); withTimeout(1000) { consumer.join() }
        assertEquals(listOf(0L, 9L, 10L), seen); assertEquals(0, stream.subscriberCount)
    }

    @Test fun losslessFileWaitsAndDrainsAtEof() = runBlocking {
        val stream = IqStream(lossless = true); val seen = mutableListOf<Int>()
        val consumer = launch(start = CoroutineStart.UNDISPATCHED) { stream.flow.collect { delay(1); seen += it.sampleCount } }
        for (n in 1..50) stream.send(IqBlock.Float32(FloatArray(n * 2)))
        stream.complete(); withTimeout(2000) { consumer.join() }
        assertEquals((1..50).toList(), seen); assertEquals(0L, stream.droppedSamples.get())
    }

    @Test fun independentSlowSubscriberCannotCorruptFastReceiver() = runBlocking {
        val stream = IqStream(); val clock = CaptureClock(); val gate = CompletableDeferred<Unit>()
        val seen = mutableListOf<Long>()
        val slow = launch(start = CoroutineStart.UNDISPATCHED) { stream.flow.collect { gate.await() } }
        val fast = launch(start = CoroutineStart.UNDISPATCHED) { stream.flow.take(20).collect { seen += it.metadata.sequence } }
        repeat(20) { val m = clock.stamp(100, 1000, 1000000); stream.offer(IqBlock.Unsigned8(ByteArray(200), m)); yield() }
        fast.join(); slow.cancelAndJoin()
        assertEquals((0L..19L).toList(), seen); assertTrue(stream.droppedBlocks.get() > 0)
    }

    @Test fun repeatedSubscriptionsAndStopLeaveNoConsumers() = runBlocking {
        val stream = IqStream()
        repeat(300) {
            val job = launch(start = CoroutineStart.UNDISPATCHED) { stream.flow.collect {} }
            assertEquals(1, stream.subscriberCount)
            job.cancelAndJoin(); assertEquals(0, stream.subscriberCount)
        }
        stream.complete(); assertTrue(stream.flow.toList().isEmpty())
    }

    @Test fun audioFramesDescribeInterleavingAndRejectPartialFrames() {
        val block = AudioBlock(FloatArray(960 * 2), 48000, 2, 7, 960)
        assertEquals(960, block.frameCount)
        assertThrows(IllegalArgumentException::class.java) { AudioBlock(FloatArray(3), 48000, 2, 7, 0) }
        assertThrows(IllegalArgumentException::class.java) { IqBlock.Unsigned8(ByteArray(3)) }
    }
    @Test fun driverLossAndQueueLossAreNotCountedAsTheSameSamples() {
        val clock=CaptureClock();val meter=IqContinuity()
        val a=clock.stamp(100,48000,10000000)
        meter.accept(a,100)
        clock.stamp(30,48000,10000000,driverLostSamples=7) // lost by this consumer
        val c=clock.stamp(20,48000,10000000)
        val gap=meter.accept(c,20)
        assertEquals(1L,gap.missingBlocks);assertEquals(30L,gap.missingSamples)
        assertEquals(7L,gap.driverLostSamples);assertEquals(7L,c.driverLostTotal)
    }
}
