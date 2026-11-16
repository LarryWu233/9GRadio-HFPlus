package com.radiosport.ninegradio

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.radiosport.ninegradio.audio.AudioWriterThread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioWriterThreadTest {
    @Test fun audioPriorityIsLocalAndThreadIsJoinedOnClose()=runBlocking {
        val callerPriority=Process.getThreadPriority(Process.myTid())
        val writer=AudioWriterThread()
        try {
            withContext(writer.dispatcher) {
                assertEquals(Process.THREAD_PRIORITY_AUDIO,Process.getThreadPriority(Process.myTid()))
                assertEquals("Radio:AudioWriter",Thread.currentThread().name)
            }
            assertEquals(callerPriority,Process.getThreadPriority(Process.myTid()))
        } finally { writer.close() }
        assertFalse(writer.isAlive)
    }
}
