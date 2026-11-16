package com.radiosport.ninegradio.pipeline

import org.junit.Assert.*
import org.junit.Test

class PipelineSessionTest {
    @Test fun modeChangeRejectsQueuedOldCaptureAndPublishesAtomicConfiguration() {
        var time = 100L
        val config = ReceiverConfiguration("AM", 768000, 10000, 48000)
        val session = PipelineSession(config) { time }
        val next = config.copy(mode = "WFM", bandwidthHz = 200000)
        val snapshot = session.configure(next)
        assertEquals(next, snapshot.configuration); assertEquals(2L, snapshot.epoch)
        assertFalse(session.admits(IqMetadata(arrivalNanos = 99)))
        assertTrue(session.admits(IqMetadata(arrivalNanos = 100)))
        time = 200; session.tuneBoundary()
        assertEquals(2L, session.snapshot.epoch)
        assertFalse(session.admits(IqMetadata(arrivalNanos = 199)))
    }
    @Test fun threeHundredIndependentSessionsRejectWorkAfterClose() {
        repeat(300) {
            val session = PipelineSession(ReceiverConfiguration("AM", 768000, 10000, 48000))
            assertTrue(session.close()); assertFalse(session.close())
            assertFalse(session.admits(IqMetadata()))
            assertThrows(IllegalStateException::class.java) { session.configure(session.snapshot.configuration.copy(mode = "WFM")) }
        }
    }
}
