package com.radiosport.ninegradio.ui

import android.content.ComponentName
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.text.TextUtils
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.media3.common.*
import androidx.media3.session.*
import androidx.media3.session.MediaController
import com.google.android.material.slider.Slider
import com.google.common.util.concurrent.ListenableFuture
import com.radiosport.ninegradio.R
import com.radiosport.ninegradio.data.RecordingMeta
import com.radiosport.ninegradio.recording.RecordingPlaybackService
import kotlinx.coroutines.*
import java.io.File

@androidx.media3.common.util.UnstableApi
class RecordingPlayerControls(private val activity:ComponentActivity,private val host:FrameLayout,
    private val expand:(()->Unit)?=null):DefaultLifecycleObserver {
    private var future:ListenableFuture<MediaController>?=null
    var controller:MediaController?=null
        private set
    private var pending:RecordingMeta?=null
    private val handler=Handler(Looper.getMainLooper())
    private var touching=false
    private val repeating=mutableListOf<Runnable>()
    private val d=activity.resources.displayMetrics.density
    private fun dp(n:Int)=(n*d).toInt()
    private val root=LinearLayout(activity).apply {
        orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(8),dp(10),dp(8))
        background=GradientDrawable().apply { cornerRadius=dp(20).toFloat();setColor(UiThemes.color(activity,R.attr.radioElevated));setStroke(dp(1).coerceAtLeast(1),UiThemes.color(activity,R.attr.radioOutline)) }
    }
    private fun text(size:Float)=TextView(activity).apply { textSize=size;setTextColor(UiThemes.color(activity,R.attr.radioText));gravity=Gravity.CENTER_VERTICAL }
    private val title=text(12f).apply { text="录音播放器";maxLines=1;ellipsize=TextUtils.TruncateAt.MIDDLE }
    private val expandButton=Button(activity).apply { id=R.id.recordingPlaybackExpand;text="展开";textSize=12f;isAllCaps=false;setTextColor(UiThemes.color(activity,R.attr.radioAccent));background=null;contentDescription="展开录音页" }
    val progress=Slider(activity).apply { id=R.id.recordingPlaybackProgress;valueFrom=0f;valueTo=1f;contentDescription="播放进度" }
    private val time=text(11f).apply { typeface=android.graphics.Typeface.MONOSPACE;text="00:00 / 00:00";setTextColor(UiThemes.color(activity,R.attr.radioSecondary)) }
    private fun icon(resource:Int,description:String,idValue:Int)=ImageButton(activity).apply {
        id=idValue;setImageResource(resource);contentDescription=description
        setColorFilter(UiThemes.color(activity,R.attr.radioAccent));setPadding(dp(10),dp(10),dp(10),dp(10))
        background=GradientDrawable().apply { cornerRadius=dp(14).toFloat();setColor(UiThemes.color(activity,R.attr.radioSelected)) }
    }
    private val backward=icon(android.R.drawable.ic_media_rew,"后退 10 秒；长按连续快退",R.id.recordingPlaybackBack)
    private val play=icon(android.R.drawable.ic_media_play,"播放",R.id.recordingPlaybackPlay)
    private val forward=icon(android.R.drawable.ic_media_ff,"前进 10 秒；长按连续快进",R.id.recordingPlaybackForward)
    private val stop=icon(android.R.drawable.ic_menu_close_clear_cancel,"停止播放并返回监听",R.id.recordingPlaybackStop)
    init {
        activity.lifecycle.addObserver(this);host.addView(root,FrameLayout.LayoutParams(-1,-2))
        val heading=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
        heading.addView(title,LinearLayout.LayoutParams(0,dp(32),1f));heading.addView(expandButton,LinearLayout.LayoutParams(dp(64),dp(32)));root.addView(heading)
        expandButton.visibility=if(expand==null) View.GONE else View.VISIBLE;expandButton.setOnClickListener { expand?.invoke() }
        root.addView(progress,LinearLayout.LayoutParams(-1,dp(36)))
        val bar=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
        bar.addView(time,LinearLayout.LayoutParams(0,dp(44),1f))
        listOf(backward,play,forward,stop).forEach { bar.addView(it,LinearLayout.LayoutParams(dp(40),dp(40)).apply { marginStart=dp(5) }) }
        root.addView(bar)
        progress.addOnSliderTouchListener(object:Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider:Slider) { touching=true }
            override fun onStopTrackingTouch(slider:Slider) { controller?.seekTo((slider.value*1000).toLong());touching=false }
        })
        play.setOnClickListener { controller?.let { player ->
            if(player.isPlaying) player.pause() else {
                if(player.playbackState==Player.STATE_ENDED) player.seekTo(0)
                if(player.playbackState==Player.STATE_IDLE) player.prepare()
                player.play()
            }
        } }
        stop.setOnClickListener { stopPlayback() }
        seekButton(backward,-1);seekButton(forward,1)
        ControlColors.apply(root,ControlColors.Group.RADIO)
        activity.lifecycleScope.launch { activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(isActive) { if(host.isShown) { connect();render() };delay(200) }
        } }
        render()
    }
    fun setExpanded(value:Boolean) { expandButton.text=if(value) "还原" else "展开" }
    private fun connect() {
        if(future!=null) return
        val requested=MediaController.Builder(activity,SessionToken(activity,ComponentName(activity,RecordingPlaybackService::class.java))).buildAsync()
        future=requested
        requested.addListener({
            if(activity.isDestroyed) return@addListener
            runCatching { requested.get() }.onSuccess { player ->
                controller=player
                player.addListener(object:Player.Listener {
                    override fun onEvents(player:Player,events:Player.Events) { render() }
                    override fun onPlayerError(error:PlaybackException) { Toast.makeText(activity,"无法播放此录音",Toast.LENGTH_LONG).show() }
                })
                pending?.let { pending=null;play(it) };render()
            }.onFailure { Toast.makeText(activity,"播放器暂时不可用",Toast.LENGTH_LONG).show();future=null }
        },ContextCompat.getMainExecutor(activity))
    }
    fun play(meta:RecordingMeta) {
        val file=File(meta.filePath)
        if(!file.isFile) { Toast.makeText(activity,"文件不存在",Toast.LENGTH_SHORT).show();return }
        if(controller==null) { pending=meta;connect();return }
        val item=MediaItem.Builder().setUri(Uri.fromFile(file)).setMediaId(meta.id.toString())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(file.name).setArtist("9GRadio HF+").build()).build()
        controller!!.setMediaItem(item);controller!!.prepare();controller!!.play();render()
    }
    fun stopPlayback() { pending=null;controller?.stop();controller?.clearMediaItems();render() }
    private fun seekBy(ms:Long) { controller?.let { it.seekTo((it.currentPosition+ms).coerceIn(0,it.duration.takeIf { t -> t>0 } ?: 0)) };render() }
    private fun seekButton(button:ImageButton,direction:Int) {
        var down=0L;var held=false
        val repeat=object:Runnable {
            override fun run() {
                held=true
                val multiplier=(1+(SystemClock.uptimeMillis()-down)/2000).coerceAtMost(5)
                seekBy(direction*3000*multiplier);handler.postDelayed(this,180)
            }
        };repeating.add(repeat)
        button.setOnClickListener { seekBy(direction*10000L) }
        button.setOnTouchListener { view,event -> when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down=SystemClock.uptimeMillis();held=false;view.parent.requestDisallowInterceptTouchEvent(true);handler.postDelayed(repeat,450);true }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(repeat);view.parent.requestDisallowInterceptTouchEvent(false);if(!held && event.actionMasked==MotionEvent.ACTION_UP) view.performClick();true }
            else -> true
        } }
    }
    private fun render() {
        val player=controller;val loaded=player?.currentMediaItem!=null
        title.text=player?.mediaMetadata?.title ?: "录音播放器"
        val duration=player?.duration?.takeIf { it>0 } ?: 0L
        val position=player?.currentPosition?.coerceAtLeast(0) ?: 0L
        val maximum=if(duration>0) (duration/1000f).coerceAtLeast(0.001f) else 1f
        if(!touching) { progress.value=progress.value.coerceIn(0f,minOf(progress.valueTo,maximum));progress.valueTo=maximum;progress.value=(position/1000f).coerceIn(0f,maximum) }
        progress.isEnabled=loaded && duration>0
        listOf(play,stop).forEach { it.isEnabled=loaded }
        listOf(backward,forward).forEach { it.isEnabled=loaded && duration>0 }
        play.setImageResource(if(player?.isPlaying==true) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        play.contentDescription=if(player?.isPlaying==true) "暂停" else "播放"
        time.text="${formatTime(position)} / ${formatTime(duration)}"
    }
    override fun onStop(owner:LifecycleOwner) { repeating.forEach { handler.removeCallbacks(it) } }
    override fun onDestroy(owner:LifecycleOwner) { repeating.forEach { handler.removeCallbacks(it) };future?.let { MediaController.releaseFuture(it) };future=null;controller=null }
    companion object {
        fun formatTime(ms:Long):String {
            val seconds=(ms/1000).coerceAtLeast(0)
            return if(seconds>=3600) String.format(java.util.Locale.ROOT,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60)
                else String.format(java.util.Locale.ROOT,"%02d:%02d",seconds/60,seconds%60)
        }
    }
}
