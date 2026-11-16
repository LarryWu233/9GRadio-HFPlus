package com.radiosport.ninegradio.audio

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import kotlinx.coroutines.android.asCoroutineDispatcher

/** Platform-owned audio scheduling, following Media3 1.9.0 PlaybackLooperProvider's
 * dedicated HandlerThread/THREAD_PRIORITY_AUDIO pattern. No shared pool thread is reprioritized.
 * The owner must interrupt blocking writes and join its writer job before closing this thread.
 */
class AudioWriterThread:AutoCloseable {
    private val thread=HandlerThread("Radio:AudioWriter",Process.THREAD_PRIORITY_AUDIO).apply { start() }
    val dispatcher=Handler(thread.looper).asCoroutineDispatcher("RadioAudioWriter")
    val isAlive get()=thread.isAlive

    override fun close() {
        check(Thread.currentThread()!==thread) { "Audio writer must be closed by its owner" }
        thread.quitSafely()
        thread.join()
    }
}
