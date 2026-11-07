package com.radiosport.ninegradio.recording

import android.content.*
import android.os.IBinder
import androidx.media3.common.*
import androidx.media3.exoplayer.*
import androidx.media3.session.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.radiosport.ninegradio.usb.RtlSdrService
import java.io.File

@androidx.media3.common.util.UnstableApi
class RecordingPlaybackService:MediaSessionService() {
    private var session:MediaSession?=null
    private var radio:RtlSdrService?=null
    private var radioBound=false
    private var muted=false
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName,binder:IBinder) {
            radio=(binder as RtlSdrService.LocalBinder).getService();radio?.setRecordingPlaybackMuted(muted)
        }
        override fun onServiceDisconnected(name:ComponentName) { radio=null }
    }
    override fun onCreate() {
        super.onCreate()
        radioBound=bindService(Intent(this,RtlSdrService::class.java),connection,BIND_AUTO_CREATE)
        val renderers=DefaultRenderersFactory(this).setEnableAudioFloatOutput(true)
        val player=ExoPlayer.Builder(this,renderers).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),true)
            setHandleAudioBecomingNoisy(true)
        }
        fun syncMute() {
            muted=player.mediaItemCount>0 && player.playbackState!=Player.STATE_IDLE && player.playbackState!=Player.STATE_ENDED
            radio?.setRecordingPlaybackMuted(muted)
        }
        player.addListener(object:Player.Listener {
            override fun onEvents(player:Player,events:Player.Events) { syncMute() }
        })
        session=MediaSession.Builder(this,player).setCallback(object:MediaSession.Callback {
            override fun onAddMediaItems(mediaSession:MediaSession,controller:MediaSession.ControllerInfo,
                mediaItems:List<MediaItem>):ListenableFuture<List<MediaItem>> {
                val roots=listOfNotNull(getExternalFilesDir(null),filesDir,cacheDir).map { it.canonicalPath+File.separator }
                val accepted=mediaItems.filter { item ->
                    val uri=item.localConfiguration?.uri ?: return@filter false
                    if(uri.scheme!="file") return@filter false
                    val file=File(uri.path ?: return@filter false)
                    runCatching { file.isFile && roots.any { file.canonicalPath.startsWith(it) } &&
                        file.extension.lowercase() in setOf("wav","m4a","mp4","ogg","opus","flac","mp3") }.getOrDefault(false)
                }
                return Futures.immediateFuture(accepted)
            }
        }).build()
    }
    override fun onGetSession(controllerInfo:MediaSession.ControllerInfo):MediaSession? =
        if(controllerInfo.packageName==packageName || controllerInfo.isTrusted) session else null
    override fun onTaskRemoved(rootIntent:Intent?) {
        if(session?.player?.playWhenReady!=true) { session?.player?.stop();session?.player?.clearMediaItems();stopSelf() }
    }
    override fun onDestroy() {
        radio?.setRecordingPlaybackMuted(false)
        if(radioBound) unbindService(connection)
        session?.let { it.player.release();it.release() };session=null
        super.onDestroy()
    }
}
