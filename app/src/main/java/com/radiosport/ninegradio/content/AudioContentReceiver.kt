package com.radiosport.ninegradio.content

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.Uri
import com.radiosport.ninegradio.dsp.DspEngine
import com.radiosport.ninegradio.dsp.PolyphaseResampler
import com.radiosport.ninegradio.pipeline.AudioBlock
import com.radiosport.ninegradio.pipeline.AudioBlockClock
import com.radiosport.ninegradio.pipeline.AudioDecoderInput
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import xdsopl.robot36.AudioSource
import xdsopl.robot36.MicrophoneAudioSource
import xdsopl.robot36.RadioSstvBridge
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.roundToInt

enum class AudioContentKind { CW, SSTV, SAT }
enum class ContentInput { SOFTWARE, MICROPHONE, WAV }
data class ContentConfig(val kind: AudioContentKind, val input: ContentInput = ContentInput.SOFTWARE,
    val apt: Boolean = false, val sstvMode: String = "Auto", val wpm: Int = 0)
data class ContentState(val config: ContentConfig? = null, val running: Boolean = false,
    val status: String = "未启动", val text: String = "", val image: Bitmap? = null,
    val lines: Int = 0, val totalLines: Int = 0, val mode: String = "", val sampleCount: Long = 0)

/** Service-owned, bounded streaming glue. No decoding or blocking I/O on the receiver/DSP thread. */
class AudioContentReceiver(private val context: Context, private val softwareAvailable: () -> Boolean = { true },
    private val dispatchCleanup: ((()->Unit)->Unit)? = null) {
    private val lifecycleLock=Any()
    private val cleanupScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutable = MutableStateFlow(ContentState())
    val state = mutable.asStateFlow()
    @Volatile private var engine: DspEngine? = null
    @Volatile private var session: Session? = null
    @Volatile private var finishing:Session?=null
    val hasPendingRelease get()=finishing!=null
    private val output = File(context.getExternalFilesDir(null), "decoded").apply { mkdirs() }
    fun attach(next: DspEngine?):Unit = synchronized(lifecycleLock) {
        engine?.setAudioDecoderInput(null); engine?.setExternalCaptureMuted(false)
        if (next == null && session?.config?.input == ContentInput.SOFTWARE) stop("接收机已关闭")
        engine = next
        next?.setExternalCaptureMuted(session?.config?.input == ContentInput.MICROPHONE)
        if (session?.config?.input == ContentInput.SOFTWARE) next?.setAudioDecoderInput(session)
    }
    fun start(config: ContentConfig, uri: Uri? = null):Unit = synchronized(lifecycleLock) {
        stop()
        if(finishing!=null) {
            mutable.value=ContentState(config,status="解码组件仍在关闭，请稍候");return@synchronized
        }
        if (config.input == ContentInput.SOFTWARE && (engine == null || !softwareAvailable())) {
            mutable.value = ContentState(config, status = "请先打开接收机"); return@synchronized
        }
        val next = Session(config)
        synchronized(this) {
            session = next
            mutable.value = ContentState(config, true, "等待音频", mode = if(config.apt) "APT" else config.sstvMode)
        }
        if (config.input == ContentInput.SOFTWARE) engine?.setAudioDecoderInput(next)
        engine?.setExternalCaptureMuted(config.input == ContentInput.MICROPHONE)
        next.launch(uri)
    }
    fun stop(reason: String = "已停止"):Unit = synchronized(lifecycleLock) {
        val old=synchronized(this) {
            val previous=session ?: finishing;session=null;finishing=previous
            if(mutable.value.running) mutable.value=mutable.value.copy(running=false,status=reason)
            previous
        }
        engine?.setAudioDecoderInput(null); engine?.setExternalCaptureMuted(false)
        old?.cancel();old?.process?.destroy()
        if(old==null || old.awaitReleased(5000)) finishing=null
        else mutable.value=mutable.value.copy(running=false,status="解码组件仍在关闭，请稍候")
    }
    /** UI-safe signal; joining is dispatched by the owning controller. */
    fun requestStop(reason:String="已停止") {
        val old=synchronized(this) {
            session?.also { if(mutable.value.running) mutable.value=mutable.value.copy(running=false,status=reason) }
        } ?: return
        old.cancel()
        scheduleCleanup { synchronized(lifecycleLock) { if(session===old || finishing===old) stop(reason) } }
    }
    private fun scheduleCleanup(action:()->Unit) {
        if(dispatchCleanup!=null) dispatchCleanup.invoke(action) else cleanupScope.launch { action() }
    }
    fun stopMicrophone() { if(session?.config?.input == ContentInput.MICROPHONE) stop("麦克风已暂停") }
    /** Software-file EOF: drain decoder input before detaching the receiver. */
    fun finishSoftwareInput() {
        val current=synchronized(this) {
            session?.takeIf { it.config.input==ContentInput.SOFTWARE }?.also { it.eof=true }
        } ?: return
        current.worker?.join(5000)
        if(current.worker?.isAlive==true) stop("解码结束处理超时，已停止")
    }
    @Synchronized fun clearText() { mutable.value = mutable.value.copy(text = "") }
    fun files() = output.listFiles()?.filter { it.extension in listOf("png", "txt") }?.sortedByDescending { it.lastModified() }.orEmpty()
    fun saveCurrent(): File? {
        val current = mutable.value
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.ROOT).format(java.util.Date())
        if (current.config?.kind == AudioContentKind.CW) {
            if(current.text.isBlank()) return null
            return File(output,"CW-$stamp.txt").apply { writeText(current.text) }
        }
        return current.image?.let { bitmap ->
            File(output,"${current.config?.kind}-$stamp-未完成.png").apply {
                outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
    private inner class Session(val config: ContentConfig):AudioDecoderInput {
        val alive = AtomicBoolean(true)
        val queue = ArrayBlockingQueue<AudioBlock>(96)
        private val inputClock=AudioBlockClock()
        @Volatile var source: AudioSource? = null
        @Volatile var process: Process? = null
        @Volatile var worker: Thread? = null
        @Volatile var resultReader:Thread?=null
        private val closed=java.util.concurrent.CountDownLatch(1)
        @Volatile var eof = false
        @Volatile var reachedLimit = false
        private fun publish(change: (ContentState) -> ContentState) {
            synchronized(this@AudioContentReceiver) { if (session === this && alive.get()) mutable.value = change(mutable.value) }
        }
        override fun accept(block:AudioBlock) {
            if(!alive.get() || block.frameCount==0) return
            require(block.channels==1) { "Decoder input must be mono" }
            if(config.input==ContentInput.WAV) { while(alive.get() && !queue.offer(block, 100, java.util.concurrent.TimeUnit.MILLISECONDS)) { } }
            else if(!queue.offer(block)) fail("音频处理跟不上，已停止；请重新开始")
        }
        fun fail(message: String) {
            if(!alive.get()) return
            publish { it.copy(running = false, status = message) }
            cancel()
            scheduleCleanup { synchronized(lifecycleLock) { if(session===this || finishing===this) stop(message) } }
        }
        fun cancel() {
            if(!alive.compareAndSet(true,false)) return
            source?.release(); worker?.interrupt(); queue.clear()
        }
        fun awaitReleased(timeoutMs:Long):Boolean {
            val deadline=System.nanoTime()+timeoutMs*1_000_000
            fun remaining()=((deadline-System.nanoTime())/1_000_000).coerceAtLeast(0)
            if(Thread.currentThread()===worker || Thread.currentThread()===resultReader) return false
            if(!closed.await(remaining(),java.util.concurrent.TimeUnit.MILLISECONDS)) return false
            worker?.let { if(it.isAlive) it.join(remaining().coerceAtLeast(1)) }
            resultReader?.let { if(it.isAlive) { it.interrupt();it.join(remaining().coerceAtLeast(1)) } }
            process?.let { if(it.isAlive && remaining()>0) it.waitFor(remaining(),java.util.concurrent.TimeUnit.MILLISECONDS) }
            while(source?.state!=null && source?.state!=AudioSource.AudioSourceState.RELEASED && remaining()>0) Thread.sleep(5)
            return worker?.isAlive!=true && resultReader?.isAlive!=true && process?.isAlive!=true &&
                (source==null || source?.state==AudioSource.AudioSourceState.RELEASED)
        }
        fun launch(uri: Uri?) {
            worker = thread(name="content-${config.kind}") {
                try {
                    val sstv = if(config.kind != AudioContentKind.CW && !config.apt) RadioSstvBridge(48000, config.sstvMode) else null
                    val native = if(sstv == null) ProcessBuilder(File(context.applicationInfo.nativeLibraryDir,
                        "libninegradio_audio_decode.so").absolutePath, if(config.kind == AudioContentKind.CW) "--cw" else "--apt",
                        config.wpm.toString()).redirectError(File(context.cacheDir,"audio-decoder.log")).apply {
                            environment()["LD_LIBRARY_PATH"]=context.applicationInfo.nativeLibraryDir
                        }.start() else null
                    process = native
                    if(!alive.get()) { native?.destroy(); return@thread }
                    val reader = native?.let { p -> thread(name="content-result") {
                        try {
                            if(config.kind == AudioContentKind.CW) readCw(p.inputStream) else readApt(p.inputStream)
                            if(alive.get() && !eof) fail(if(reachedLimit) "已处理 3000 行，达到单幅上限" else "解码器已退出，请重新开始")
                        }
                        catch(e: Exception) { if(alive.get()) fail("解码器读取失败") }
                    } }
                    resultReader=reader
                    if(config.input != ContentInput.SOFTWARE) openSource(uri)
                    var rate = 48000; var resampler: PolyphaseResampler? = null
                    val cwResampler = if(config.kind == AudioContentKind.CW) PolyphaseResampler(48000,22050) else null
                    var samples = 0L; var lastUpdate = 0L
                    val stream = native?.outputStream?.buffered(32768)
                    try {
                        while(alive.get()) {
                            val block = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                            if(block == null) { if(eof) break; continue }
                            if(rate != block.sampleRate) { rate = block.sampleRate; resampler = if(rate == 48000) null else PolyphaseResampler(rate,48000) }
                            val pcm = resampler?.process(block.samples) ?: block.samples
                            samples += pcm.size
                            if(sstv != null) {
                                sstv.process(pcm)
                                val complete = sstv.isComplete
                                if(sstv.lines > 0 && (complete || android.os.SystemClock.elapsedRealtime()-lastUpdate > 500)) {
                                    lastUpdate = android.os.SystemClock.elapsedRealtime()
                                    val image = Bitmap.createBitmap(sstv.copyPixels(),sstv.width,sstv.height,Bitmap.Config.ARGB_8888)
                                    publish { it.copy(image=image, lines=sstv.lines, totalLines=sstv.height, mode=sstv.mode, sampleCount=samples, status=if(complete) "图像接收完成" else "正在接收图像") }
                                    if(complete) { saveComplete(image); sstv.acknowledgeComplete() }
                                }
                            } else {
                                val converted = cwResampler?.process(pcm) ?: pcm
                                val bytes = ByteBuffer.allocate(converted.size * if(cwResampler != null) 2 else 4).order(ByteOrder.LITTLE_ENDIAN)
                                converted.forEach { if(cwResampler != null) bytes.putShort((it.coerceIn(-1f,0.999969f)*32768).roundToInt().toShort()) else bytes.putFloat(if(it.isFinite()) it else 0f) }
                                stream!!.write(bytes.array()); stream.flush()
                            }
                            if(android.os.SystemClock.elapsedRealtime()-lastUpdate > 1000) {
                                lastUpdate = android.os.SystemClock.elapsedRealtime()
                                publish { it.copy(sampleCount=samples, status=if(it.lines>0) it.status else "接收音频 · 等待有效信号") }
                            }
                        }
                        if(eof && alive.get() && sstv != null) {
                            sstv.finish()
                            if(sstv.lines>0) {
                                val image=Bitmap.createBitmap(sstv.copyPixels(),sstv.width,sstv.height,Bitmap.Config.ARGB_8888)
                                publish { it.copy(image=image,lines=sstv.lines,totalLines=sstv.height,mode=sstv.mode) }
                                if(sstv.isComplete) saveComplete(image)
                            }
                        }
                    } finally { runCatching { stream?.close() } }
                    if(eof && alive.get()) {
                        reader?.join(4000)
                        publish { it.copy(running=false,status="录音处理结束") }
                    }
                } catch(e: InterruptedException) { /* canceled */ }
                catch(e: Exception) { if(alive.get()) fail("无法解码音频：${e.message?.take(100)}") }
                finally {
                    // Cancellation may arrive during a blocking native write. Cleanup must
                    // still complete and acknowledge ownership even if this thread was interrupted.
                    Thread.interrupted()
                    try {
                        source?.release();process?.destroy()
                        process?.let { if(!it.waitFor(300,java.util.concurrent.TimeUnit.MILLISECONDS)) {
                            it.destroyForcibly();it.waitFor(2000,java.util.concurrent.TimeUnit.MILLISECONDS)
                        } }
                        resultReader?.let { if(it!==Thread.currentThread()) { it.interrupt();it.join(1000) } }
                    } catch(_:InterruptedException) {
                        process?.destroyForcibly()
                    } finally {
                        synchronized(this@AudioContentReceiver) { if(session === this) { engine?.setAudioDecoderInput(null); engine?.setExternalCaptureMuted(false) } }
                        closed.countDown()
                    }
                }
            }
        }
        private fun saveComplete(bitmap: Bitmap) {
            val file=File(output,"${config.kind}-${System.currentTimeMillis()}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        private fun openSource(uri: Uri?) {
            val src = if(config.input == ContentInput.WAV) {
                val input = context.contentResolver.openInputStream(requireNotNull(uri)) ?: throw IOException("无法打开 WAV")
                RadioSstvBridge.wav(input, true)
            } else {
                val manager = context.getSystemService(AudioManager::class.java)
                val preset = if(manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)=="true")
                    MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
                MicrophoneAudioSource(48000,1,preset,AudioFormat.ENCODING_PCM_16BIT)
            }
            source = src
            src.setAudioDataListener(object: AudioSource.AudioDataListener {
                override fun onAudioDataAvailable(buffer: FloatArray, frameCount: Int, sampleRate: Int, channels: Int) {
                    val mono = if(channels == 1) buffer.copyOf(frameCount) else FloatArray(frameCount) { (buffer[it*2]+buffer[it*2+1])*0.5f }
                    accept(inputClock.publish(mono,sampleRate,1,1))
                }
                override fun onStatusChanged(state: AudioSource.AudioSourceState, message: String) {
                    if(state == AudioSource.AudioSourceState.COMPLETED) eof = true
                    if(state == AudioSource.AudioSourceState.ERROR) fail("音频输入失败：${message.take(100)}")
                }
            })
            if(!alive.get()) { src.release(); return }
            src.prepare()
            if(alive.get()) src.start() else src.release()
        }
        private fun readCw(input: InputStream) {
            input.bufferedReader().useLines { lines -> lines.forEach { line ->
                if(!alive.get()) return@forEach
                val fields = line.split('\t')
                if(fields[0] == "T" && fields.size>1) {
                    val text = fields[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8)
                    publish { it.copy(text=(it.text+text).takeLast(12000)) }
                } else if(fields[0] == "S" && fields.size>=4) {
                    val dit=fields[1].toIntOrNull() ?: 0
                    val wpm=if(dit>0) 1200.0*22050/dit/1000 else 0.0
                    publish { it.copy(mode="%.0f WPM · %s 字 · %s 错误".format(wpm,fields[2],fields[3])) }
                }
            } }
        }
        private fun readApt(input: InputStream) {
            val reader=DataInputStream(input.buffered()); val rowBytes=ByteArray(2080*4)
            val pixels=IntArray(2080*3000); var rows=0
            try {
                while(alive.get()) {
                    try { reader.readInt() } catch(e: EOFException) { break }
                    reader.readFully(rowBytes)
                    val floats=ByteBuffer.wrap(rowBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    for(x in 0 until 2080) { val f=floats.get(); val v=if(f.isFinite()) f.roundToInt().coerceIn(0,255) else 0
                        pixels[rows*2080+x]=0xff000000.toInt() or (v shl 16) or (v shl 8) or v }
                    rows++
                    if(rows%2==0 || eof) {
                        val shown=rows.coerceAtMost(512)
                        val image=Bitmap.createBitmap(pixels,(rows-shown)*2080,2080,2080,shown,Bitmap.Config.ARGB_8888)
                        publish { it.copy(image=image,lines=rows,totalLines=0,status="已处理 $rows 行",mode="APT · 原始灰度") }
                    }
                    if(rows>=3000) { reachedLimit=true; break }
                }
            } finally {
                // Save actual decoded rows without claiming a synchronized or georeferenced image.
                if(rows>0) {
                    val image=Bitmap.createBitmap(pixels,2080,rows,Bitmap.Config.ARGB_8888)
                    File(output,"APT-${System.currentTimeMillis()}-原始.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
                }
            }
        }
    }
}
