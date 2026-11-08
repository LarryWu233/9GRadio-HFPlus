package com.radiosport.ninegradio

import com.radiosport.ninegradio.dsp.ExternalDecoderStream
import com.radiosport.ninegradio.pipeline.AudioBlock
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ExternalDecoderLifetimeTest {
    private fun waitUntil(condition:()->Boolean) {
        val end=System.nanoTime()+3_000_000_000
        while(!condition() && System.nanoTime()<end) Thread.sleep(5)
        assertTrue(condition())
    }
    private fun holdResolution(entered:CountDownLatch,release:CountDownLatch):InetAddress {
        entered.countDown()
        // Model a platform DNS operation that does not honor interruption.
        while(release.count>0) try { release.await() } catch(_:InterruptedException) { }
        return InetAddress.getLoopbackAddress()
    }
    private fun receive(socket:DatagramSocket):Short {
        val packet=DatagramPacket(ByteArray(128),128);socket.receive(packet)
        assertEquals(2,packet.length)
        return ByteBuffer.wrap(packet.data,0,packet.length).order(ByteOrder.LITTLE_ENDIAN).short
    }
    @Test fun blockedDnsRetainsItsOwnerAndCannotReviveIntoANewSession() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val stream=ExternalDecoderStream { holdResolution(entered,release) }
        DatagramSocket(0,InetAddress.getLoopbackAddress()).use { sink ->
            sink.soTimeout=1000;stream.configure("localhost",sink.localPort)
            try {
                stream.setEnabled(true);stream.send(floatArrayOf(.5f))
                assertTrue(entered.await(3,TimeUnit.SECONDS))
                stream.setEnabled(false)
                assertFalse(stream.isEnabled);assertTrue(stream.hasPendingRelease)
                stream.setEnabled(true);assertFalse("A new worker must not reuse the old run flag",stream.isEnabled)
                release.countDown();waitUntil { !stream.hasPendingRelease }
                sink.soTimeout=100
                assertThrows(SocketTimeoutException::class.java) { receive(sink) }
                sink.soTimeout=1000
                stream.setEnabled(true);stream.send(floatArrayOf(.25f))
                assertEquals(8191.toShort(),receive(sink))
                waitUntil { stream.packetsSent==1L }
            } finally { release.countDown();stream.shutdown() }
        }
        assertFalse(stream.hasPendingRelease)
    }
    @Test fun configurationInvalidatesAudioAlreadyWaitingForDns() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val stream=ExternalDecoderStream { holdResolution(entered,release) }
        DatagramSocket(0,InetAddress.getLoopbackAddress()).use { sink ->
            sink.soTimeout=1000;stream.configure("localhost",sink.localPort)
            try {
                stream.setEnabled(true)
                stream.accept(AudioBlock(floatArrayOf(.5f),48000,1,1,0))
                assertTrue(entered.await(3,TimeUnit.SECONDS))
                stream.configure(2);release.countDown()
                stream.accept(AudioBlock(floatArrayOf(.25f),48000,1,2,0))
                assertEquals(8191.toShort(),receive(sink))
                waitUntil { stream.stalePackets==1L }
                assertEquals(0L,stream.packetsDropped)
            } finally { release.countDown();stream.shutdown() }
        }
    }
    @Test fun replacementStreamRestartsTheEpochNamespace() {
        val stream=ExternalDecoderStream()
        DatagramSocket(0,InetAddress.getLoopbackAddress()).use { sink ->
            sink.soTimeout=1000;stream.configure("127.0.0.1",sink.localPort)
            try {
                stream.beginStream();stream.setEnabled(true)
                stream.accept(AudioBlock(floatArrayOf(.5f),48000,1,9,0))
                assertEquals(16383.toShort(),receive(sink))
                stream.setEnabled(false);assertFalse(stream.hasPendingRelease)
                stream.beginStream();stream.setEnabled(true)
                stream.accept(AudioBlock(floatArrayOf(.25f),48000,1,1,0))
                assertEquals(8191.toShort(),receive(sink))
            } finally { stream.shutdown() }
        }
    }
    @Test fun legacyInputIsOwnedAndOverflowIsCounted() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val stream=ExternalDecoderStream { holdResolution(entered,release) }
        DatagramSocket(0,InetAddress.getLoopbackAddress()).use { sink ->
            sink.soTimeout=1000;stream.configure("localhost",sink.localPort)
            try {
                stream.setEnabled(true)
                val samples=floatArrayOf(.5f);stream.send(samples);samples[0]=0f
                assertTrue(entered.await(3,TimeUnit.SECONDS))
                repeat(100) { stream.send(floatArrayOf(.25f)) }
                assertEquals(68L,stream.packetsDropped)
                release.countDown()
                assertEquals(16383.toShort(),receive(sink))
            } finally { release.countDown();stream.shutdown() }
        }
    }
}
