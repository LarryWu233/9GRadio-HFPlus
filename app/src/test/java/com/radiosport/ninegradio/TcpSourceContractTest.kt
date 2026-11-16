package com.radiosport.ninegradio

import com.radiosport.ninegradio.source.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TcpSourceContractTest {
    private fun header()=ByteBuffer.allocate(12).put("RTL0".toByteArray()).putInt(5).putInt(29).array()
    private class Server(private val work:(java.net.Socket)->Unit):AutoCloseable {
        val server=ServerSocket(0,1,InetAddress.getLoopbackAddress())
        val socket=AtomicReference<java.net.Socket>()
        val failure=AtomicReference<Throwable>()
        val thread=Thread {
            try { server.accept().also { socket.set(it) }.use(work) }
            catch(error:Throwable) { if(!server.isClosed) failure.set(error) }
        }.apply { start() }
        val port get()=server.localPort
        override fun close() { server.close();socket.get()?.close();thread.join(3000);check(!thread.isAlive);failure.get()?.let { throw AssertionError(it) } }
    }
    @Test fun delayedFragmentedHandshakeAndShortEvenTailArePreserved():Unit=runBlocking {
        val send=CountDownLatch(1);val expected=ByteArray(300) { it.toByte() }
        Server { client ->
            val out=client.getOutputStream();val magic=header()
            out.write(magic,0,3);out.flush();Thread.sleep(650)
            out.write(magic,3,9);out.flush();check(send.await(3,TimeUnit.SECONDS))
            var position=0
            while(position<expected.size) {
                val n=minOf(7,expected.size-position)
                out.write(expected,position,n);out.flush();position+=n
            }
            client.shutdownOutput()
        }.use { server ->
            val source=RtlTcpSource("127.0.0.1",server.port)
            try {
                assertTrue("Handshake may take more than the stream cancellation timeout",source.open())
                val block=async(start=CoroutineStart.UNDISPATCHED) { withTimeout(4000) { source.iqFlow.first() } }
                source.startStreaming();send.countDown()
                val data=block.await() as IqBlock.Unsigned8
                assertArrayEquals(expected,data.bytes);assertEquals(150,data.sampleCount)
                assertEquals(0L,data.metadata.firstSample);assertTrue(data.metadata.arrivalNanos>0)
            } finally { source.close() }
        }
    }
    @Test fun blockedTcpReadStopsWithinItsBoundedTimeout():Unit=runBlocking {
        val release=CountDownLatch(1)
        Server { client -> client.getOutputStream().apply { write(header());flush() };release.await(3,TimeUnit.SECONDS) }.use { server ->
            val source=RtlTcpSource("127.0.0.1",server.port)
            try {
                assertTrue(source.open());source.startStreaming();delay(80)
                val start=System.nanoTime();source.stopStreaming()
                assertTrue("Read cancellation must join instead of leaving a worker",System.nanoTime()-start<1_500_000_000L)
                assertFalse(source.statusFlow.value.streamRestartRequired)
            } finally { release.countDown();source.close() }
        }
    }
}
