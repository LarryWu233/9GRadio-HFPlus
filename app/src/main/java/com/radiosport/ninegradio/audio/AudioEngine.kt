package com.radiosport.ninegradio.audio

import android.media.*
import android.util.Log
import com.radiosport.ninegradio.debug.DebugBus
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

class AudioEngine(override val sampleRate: Int = 48_000) : com.radiosport.ninegradio.pipeline.AudioOutput {

    companion object {
        private const val TAG = "AudioEngine"
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT

        private fun playStateName(state: Int) = when (state) {
            AudioTrack.PLAYSTATE_PLAYING -> "PLAYING"
            AudioTrack.PLAYSTATE_PAUSED  -> "PAUSED"
            AudioTrack.PLAYSTATE_STOPPED -> "STOPPED"
            else                         -> "UNKNOWN($state)"
        }
        private fun initStateName(state: Int) = when (state) {
            AudioTrack.STATE_INITIALIZED      -> "STATE_INITIALIZED"
            AudioTrack.STATE_UNINITIALIZED    -> "STATE_UNINITIALIZED"
            else                              -> "STATE_UNKNOWN($state)"
        }
    }

    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var stopping = false
    @Volatile private var epoch=0L
    override fun configureEpoch(epoch:Long) = synchronized(trackLock) { this.epoch=epoch }
    private val writeFails = AtomicInteger(0)
    private var prefilling=true
    private var primedFrames=0
    @Volatile override var underruns=0
        private set
    @Volatile var writtenFramesTotal=0L
        private set

    // Platform clock observation only; called by the diagnostic test, not
    // from the signal-processing loop. Keep lifetime protection on the track.
    override fun playbackClock(): com.radiosport.ninegradio.pipeline.PlaybackClock = synchronized(trackLock) {
        val track=audioTrack ?: return@synchronized com.radiosport.ninegradio.pipeline.PlaybackClock()
        val timestamp=AudioTimestamp()
        val valid=track.getTimestamp(timestamp)
        com.radiosport.ninegradio.pipeline.PlaybackClock(writtenFramesTotal,
            track.playbackHeadPosition.toLong() and 0xffffffffL,
            if(valid) timestamp.framePosition else -1L, if(valid) timestamp.nanoTime else -1L)
    }

    // ─── Thread-safety ──────────────────────────────────────────────────────
    // start()/stop() are called from the UI/service thread (engine lifecycle,
    // setAudioSinkRate() swaps), while write() is called from the DSP coroutine
    // running on Dispatchers.Default. Without synchronization, stop() can call
    // audioTrack.release() while write() is mid-call inside
    // AudioTrack.native_write_float() on the other thread, producing:
    //   java.lang.IllegalStateException: Unable to retrieve AudioTrack pointer for write()
    // [trackLock] makes start()/stop()/write() mutually exclusive so the
    // native object is never released while a write() holds a reference to it.
    private val trackLock = Any()


    override fun start() {
        stopping = false
        synchronized(trackLock) { if(audioTrack?.state==AudioTrack.STATE_INITIALIZED) return }
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
        // Hardware buffer and fixed-duration PCM queue absorb scheduler and USB jitter.
        // Playback starts after 80 ms of audio is primed, below the 120 ms buffer floor.
        val targetMs   = 120 // scheduling headroom; the duration based PCM queue handles USB bursts
        val targetBytes = sampleRate * targetMs / 1000 * 4 * 2
        val bufSize = minBuf.coerceAtLeast(targetBytes)

        // PERFORMANCE_MODE_LOW_LATENCY is incompatible with USAGE_MEDIA on many
        // Android devices: the Builder silently falls back to an uninitialized
        // state, making every write() a silent no-op.
        // SDR radio audio has no sub-20ms latency requirement, so use NONE.
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(CHANNEL_CONFIG)
                    .setEncoding(AUDIO_FORMAT)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
            .build()

        val state = track.state
        DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_BUF_SIZE,
            "${bufSize}B (${bufSize / 8 * 1000 / sampleRate}ms)")

        if (state != AudioTrack.STATE_INITIALIZED) {
            val reason = "AudioTrack init FAILED — ${initStateName(state)}"
            Log.e(TAG, reason)
            DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_INIT,
                "FAIL (${initStateName(state)})")
            DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_PLAY, "—")
            DebugBus.setError(DebugBus.STAGE_AUDIO_TRACK, reason)
            track.release()
            throw IllegalStateException(reason)
        }

        DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_INIT,
            initStateName(state))

        // Prime 80 ms before starting the hardware clock. USB callbacks arrive
        // in bursts; starting an empty track leaves no jitter margin at all.
        prefilling=true; primedFrames=0; underruns=0

        // Register a no-op routing-changed listener so the framework does NOT
        // attempt to call listAudioPorts() via AudioTrack.broadcastRoutingChange()
        // on a binder thread while the app's audio port cache is being rebuilt.
        // Without this, a Bluetooth/headphone plug event triggers a SIGSEGV in
        // audiopolicy-aidl-cpp's Parcel::readData path (null pointer dereference
        // inside extent_recycle / je_extents_alloc) because the system tries to
        // allocate memory for the updated port list during a routing event.
        // Adding a listener causes the framework to post the notification through
        // the registered handler instead, which is safe to receive and ignore.
        track.addOnRoutingChangedListener(
            AudioRouting.OnRoutingChangedListener { /* no-op: routing changes are benign for SDR audio */ },
            android.os.Handler(android.os.Looper.getMainLooper())
        )

        // Verify play actually started
        val playState = track.playState
        DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_PLAY,
            if(prefilling) "PRIMING" else playStateName(playState))
        if (!prefilling && playState != AudioTrack.PLAYSTATE_PLAYING) {
            val msg = "play() called but state is ${playStateName(playState)}"
            Log.w(TAG, msg)
            DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_PLAY,
                "⚠ ${playStateName(playState)}")
        }

        writeFails.set(0)
        DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_WRITE_FAIL, "0")
        DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_LAST_WRITE, "—")

        synchronized(trackLock) {
            audioTrack = track
        }
        Log.i(TAG, "AudioTrack started: $sampleRate Hz, buf=${bufSize}B (${bufSize / 8 * 1000 / sampleRate}ms)")
    }

    /** Unblock a pending platform write without releasing its native pointer. */
    override fun interruptWrites() {
        stopping = true
        runCatching { audioTrack?.stop() }
    }

    override fun stop() {
        interruptWrites()
        // Synchronized with write(): ensures no write() call is mid-flight
        // inside AudioTrack.native_write_float() on the DSP coroutine when we
        // call stop()/release() here, which would otherwise crash with
        // "IllegalStateException: Unable to retrieve AudioTrack pointer".
        val track = synchronized(trackLock) {
            val t = audioTrack
            audioTrack = null
            t
        }
        try {
            track?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack.stop() threw", e)
        }
        try {
            // Remove all routing changed listeners before release so the framework
            // does not deliver a stale routing-change callback to a released track.
            track?.removeOnRoutingChangedListener(
                AudioRouting.OnRoutingChangedListener { }
            )
            track?.release()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack.release() threw", e)
        }
        DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_PLAY, "STOPPED")
        DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_LAST_WRITE, "—")
    }

    override fun write(block: com.radiosport.ninegradio.pipeline.AudioBlock) {
        require(block.sampleRate==sampleRate && block.channels==2)
        synchronized(trackLock) { if(block.epoch==epoch) write(block.samples) }
    }

    fun write(samples: FloatArray) {
        if (stopping) return
        // Hold trackLock for the duration of the write so a concurrent stop()
        // cannot release the native AudioTrack out from under this call.
        // The lock is held across the (possibly blocking) native write —
        // stop() is a comparatively rare, short operation, so this does not
        // introduce meaningful contention on the hot DSP path.
        synchronized(trackLock) {
            val track = audioTrack ?: return

            // ── Live play-state check: catches paused/stopped tracks before writing ──
            val currentPlayState = try {
                track.playState
            } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioTrack.playState threw (track released?)", e)
                return
            }
            if (!prefilling && currentPlayState != AudioTrack.PLAYSTATE_PLAYING) {
                DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_PLAY,
                    "⚠ ${playStateName(currentPlayState)} — not playing!")
                // Attempt to resume if paused (e.g. transient audio focus loss then regained)
                if (currentPlayState == AudioTrack.PLAYSTATE_PAUSED) {
                    try { track.play() } catch (_: Exception) { }
                }
            } else {
                // Update play state periodically so stale "⚠" entries are cleared
                DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_PLAY,
                    playStateName(currentPlayState))
            }

            // WRITE_BLOCKING: the write call suspends the calling coroutine (Dispatchers.Default)
            // until the AudioTrack buffer has room for the samples.  This naturally rate-limits
            // the DSP pipeline to real-time and eliminates silent sample drops that
            // WRITE_NON_BLOCKING causes when the 500 ms buffer fills ahead of playback.
            // For WFM at 48 kHz the blocking duration is at most ~64 ms per IQ block — harmless.
            var written = 0
            while (written < samples.size && !stopping) {
                val n = try {
                    track.write(samples, written, minOf(samples.size-written,sampleRate/50*2), AudioTrack.WRITE_BLOCKING)
                } catch (e: IllegalStateException) {
                    // Native AudioTrack pointer is gone — the track was released
                    // concurrently (e.g. stop() raced this write()). Treat as a
                    // benign stop rather than crashing the DSP coroutine.
                    Log.w(TAG, "AudioTrack.write() threw IllegalStateException (track released)", e)
                    DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_LAST_WRITE,
                        "FAIL: released mid-write")
                    if(!stopping) throw e
                    return
                }
                if (n <= 0) {
                    // n == 0 → track released; n < 0 → AudioTrack error code
                    val failStr = when (n) {
                        AudioTrack.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
                        AudioTrack.ERROR_BAD_VALUE         -> "ERROR_BAD_VALUE"
                        AudioTrack.ERROR_DEAD_OBJECT       -> "ERROR_DEAD_OBJECT"
                        AudioTrack.ERROR                   -> "ERROR"
                        0                                  -> "0 (track released?)"
                        else                               -> "UNKNOWN($n)"
                    }
                    val totalFails = writeFails.incrementAndGet()
                    DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_WRITE_FAIL,
                        "$totalFails")
                    DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_LAST_WRITE,
                        "FAIL: $failStr")
                    if (n == AudioTrack.ERROR_DEAD_OBJECT) {
                        DebugBus.setError(DebugBus.STAGE_AUDIO_TRACK,
                            "write() ERROR_DEAD_OBJECT — AudioTrack dead (fails=$totalFails)")
                    }
                    Log.w(TAG, "AudioTrack.write() returned $n ($failStr), fails=$totalFails")
                    if(!stopping) throw IllegalStateException("AudioTrack write failed: $n")
                    break
                }
                written += n
                writtenFramesTotal+=n/2
                if(prefilling) {
                    primedFrames+=n/2
                    if(primedFrames>=sampleRate*80/1000) { track.play(); prefilling=false }
                }
            }

            if (written > 0) {
                underruns=track.underrunCount
                DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, "AT_UNDERRUNS", underruns.toString())
                DebugBus.setExtra(DebugBus.STAGE_AUDIO_TRACK, DebugBus.EXTRA_AT_LAST_WRITE,
                    "OK ($written samples)")
            }
        }
    }

    override fun setVolume(vol: Float) {
        synchronized(trackLock) {
            audioTrack?.setVolume(vol.coerceIn(0f, AudioTrack.getMaxVolume()))
        }
    }
    override fun clearPendingAudio() {
        synchronized(trackLock) {
            audioTrack?.let { track ->
                runCatching { track.pause(); track.flush(); prefilling=true; primedFrames=0;writtenFramesTotal=0 }
            }
        }
    }
    override fun finishInput() {
        synchronized(trackLock) {
            if(!stopping && prefilling && primedFrames>0) {
                audioTrack?.play();prefilling=false
            }
        }
    }

}
