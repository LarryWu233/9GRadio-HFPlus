package com.radiosport.ninegradio.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.*
import android.os.Bundle
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.radiosport.ninegradio.data.RadioTarget
import com.radiosport.ninegradio.data.RadioTargets
import com.radiosport.ninegradio.R
import com.radiosport.ninegradio.dsp.AdsbDecoder
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.usb.RtlSdrService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Full-screen ADS-B radar display.
 * Observes the service-owned readsb receiver. Navigation never reconfigures
 * the tuner or creates a second decoder; raw IQ remains at 2.4 MS/s.
 */
class AdsbActivity : RadioThemedActivity() {

    private lateinit var radarView: AdsbRadarView
    private lateinit var tvAircraftCount: TextView
    private lateinit var tvStatus: TextView

    private val aircraft = HashMap<String, AdsbDecoder.AdsbFrame>()
    private lateinit var aircraftTable: AdsbAircraftTable
    private val distances by lazy { AdsbDistanceTracker(this) }

    // ─── Service binding for IQ magnitude flow ────────────────────────────────

    private var sdrService: RtlSdrService? = null
    private var serviceBound = false
    private var iqFeedJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            sdrService = (binder as RtlSdrService.LocalBinder).getService()
            serviceBound = true
            val svc = sdrService!!
            if (!svc.capabilities.supportsMode(DemodMode.ADSB)) {
                Toast.makeText(this@AdsbActivity, "当前接收机不支持 1090 MHz ADS-B", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            startIqFeed()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            iqFeedJob?.cancel()
            sdrService = null
            serviceBound = false
        }
    }

    /** Observe live decoder state across source close/open and UI navigation. */
    private fun startIqFeed() {
        iqFeedJob?.cancel()
        val svc=sdrService ?: return
        iqFeedJob=lifecycleScope.launch {
            launch {
                kotlinx.coroutines.flow.combine(svc.connectionState,svc.adsbReceiver.status) { connection,status ->
                    when(connection) {
                        is RtlSdrService.ConnectionState.Connected -> status.error ?: if(status.running)
                            "1090 MHz · 2.400 MS/s · ${status.messages} 帧" else "等待 ADS-B 输入"
                        is RtlSdrService.ConnectionState.Connecting -> "正在连接接收机…"
                        is RtlSdrService.ConnectionState.Error -> connection.message
                        else -> "接收机已关闭"
                    }
                }.collect { tvStatus.text=it }
            }
            svc.adsbReceiver.aircraft.collect { frames -> renderAircraft(frames) }
        }
    }

    private fun renderAircraft(frames: List<AdsbDecoder.AdsbFrame>) {
        aircraft.clear(); frames.forEach { aircraft[it.icao24]=it }
        radarView.updateAircraft(frames)
        tvAircraftCount.text="飞机：${frames.size}"
        aircraftTable.submit(frames.sortedBy { it.callsign ?: it.icao24 },distances.update(frames).distances)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_adsb)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "ADS-B 雷达 · 1090 MHz"

        bindService(Intent(this, RtlSdrService::class.java), serviceConnection, BIND_AUTO_CREATE)

        radarView       = findViewById(R.id.radarView)
        val location=getSharedPreferences("radio_map",MODE_PRIVATE)
        if(location.contains("receiverLat") && location.contains("receiverLon"))
            radarView.setOwnPosition(location.getFloat("receiverLat",0f).toDouble(),location.getFloat("receiverLon",0f).toDouble())
        findViewById<Button>(R.id.btnAdsbMap).setOnClickListener {
            startActivity(Intent(this, RadioMapActivity::class.java).putExtra("protocol","ADSB"))
        }
        tvAircraftCount = findViewById(R.id.tvAircraftCount)
        tvStatus        = findViewById(R.id.tvAdsbStatus)

        aircraftTable = findViewById(R.id.listAircraft)
        aircraftTable.onAircraftClick = { ac ->
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(ac.callsign ?: ac.icao24)
                .setMessage("ICAO：${ac.icao24}\n高度：${ac.altitude?.let { "$it ft" } ?: "—"}\n速度：${ac.velocity?.let { "$it kt" } ?: "—"}\n航向：${ac.heading?.let { "%.1f°".format(it) } ?: "—"}\n位置：${ac.latitude ?: "—"}, ${ac.longitude ?: "—"}")
                .setPositiveButton("关闭", null).show()
        }

        // Status indicator — read connectionState directly from the bound service.
        // viewModel.connectionState is scoped to this Activity's own ViewModel instance
        // (separate from MainActivity's), so it always starts Disconnected and shows
        // "No Device" even when a device is live.  We start the observer in
        // startIqFeed() once sdrService is available instead.
    }

    override fun onDestroy() {
        super.onDestroy()
        iqFeedJob?.cancel()
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}

/**
 * Radar sweep display showing aircraft positions on a polar grid.
 */
class AdsbRadarView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null
) : View(context, attrs) {

    private var aircraftList = listOf<AdsbDecoder.AdsbFrame>()
    private var ownLat = 0.0
    private var ownLon = 0.0
    private var positionKnown=false
    private var wantsAnimation=true
    private val receiverPrefs=context.getSharedPreferences("radio_map",android.content.Context.MODE_PRIVATE)
    private val locationListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
        if(key=="receiverLat" || key=="receiverLon") refreshReceiverPosition()
    }
    private var rangeNm = 250.0  // display range in nautical miles

    private val bgPaint    = Paint().apply { color = 0xFF050D12.toInt(); style = Paint.Style.FILL }
    private val gridPaint  = Paint().apply { color = 0x2200FF44.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.7f; isAntiAlias = true }
    private val sweepPaint = Paint().apply { color = 0x4400FF44.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f; isAntiAlias = true }
    private val dotPaint   = Paint().apply { color = 0xFF00FF88.toInt(); style = Paint.Style.FILL; isAntiAlias = true }
    private val textPaint  = Paint().apply { color = 0xCC00FF88.toInt(); textSize = 24f; isAntiAlias = true; typeface = Typeface.MONOSPACE }
    private val labelPaint = Paint().apply { color = 0x8800AAFF.toInt(); textSize = 18f; isAntiAlias = true }
    private val sweepGradPaint = Paint().apply { style = Paint.Style.FILL }

    private var sweepAngle = 0f
    private val sweepAnimator by lazy { android.animation.ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 4000
        repeatCount = android.animation.ValueAnimator.INFINITE
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener { anim ->
            sweepAngle = anim.animatedValue as Float
            invalidate()
        }
    } }
    fun setAnimating(active: Boolean) { wantsAnimation=active; updateAnimation() }
    private fun updateAnimation() {
        if(wantsAnimation && isAttachedToWindow && windowVisibility==VISIBLE) {
            if(!sweepAnimator.isStarted) sweepAnimator.start()
        } else sweepAnimator.cancel()
    }
    private fun refreshReceiverPosition() {
        if(receiverPrefs.contains("receiverLat") && receiverPrefs.contains("receiverLon"))
            setOwnPosition(receiverPrefs.getFloat("receiverLat",0f).toDouble(),receiverPrefs.getFloat("receiverLon",0f).toDouble())
        else { positionKnown=false; postInvalidate() }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); refreshReceiverPosition()
        receiverPrefs.registerOnSharedPreferenceChangeListener(locationListener); updateAnimation()
    }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); post { updateAnimation() } }

    fun updateAircraft(list: List<AdsbDecoder.AdsbFrame>) {
        aircraftList = list.filter { it.latitude != null && it.longitude != null }
        postInvalidate()
    }

    fun setOwnPosition(lat: Double, lon: Double) { ownLat = lat; ownLon = lon; positionKnown=lat in -90.0..90.0 && lon in -180.0..180.0; postInvalidate() }
    fun setRange(nm: Double) { rangeNm = nm }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r  = minOf(cx, cy) * 0.94f

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Range rings
        for (i in 1..4) {
            val ringR = r * i / 4f
            canvas.drawCircle(cx, cy, ringR, gridPaint)
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("${(rangeNm * i / 4).toInt()} nm", cx + ringR + 4f, cy - 4f, labelPaint)
        }

        // Cardinal lines
        canvas.drawLine(cx, cy - r, cx, cy + r, gridPaint)
        canvas.drawLine(cx - r, cy, cx + r, cy, gridPaint)

        // Sweep gradient
        val sweepMatrix = Matrix().apply { postTranslate(-cx, -cy); postRotate(sweepAngle); postTranslate(cx, cy) }
        val sweepShader = SweepGradient(cx, cy,
            intArrayOf(0x0000FF44, 0x8800FF44.toInt(), 0x0000FF44), null)
        sweepShader.setLocalMatrix(sweepMatrix)
        sweepGradPaint.shader = sweepShader
        canvas.drawCircle(cx, cy, r, sweepGradPaint)

        // Sweep arm
        val sweepRad = Math.toRadians(sweepAngle.toDouble())
        canvas.drawLine(cx, cy,
            cx + (r * Math.sin(sweepRad)).toFloat(),
            cy - (r * Math.cos(sweepRad)).toFloat(),
            sweepPaint)

        // Aircraft blips
        for (ac in aircraftList) {
            if(!positionKnown) break
            val lat = ac.latitude ?: continue
            val lon = ac.longitude ?: continue

            val (px, py) = latLonToPixel(lat, lon, cx, cy, r)

            val ageSec = (System.currentTimeMillis() - ac.timestamp) / 1000L
            val alpha = (255 * (1.0 - ageSec / 120.0)).toInt().coerceIn(80, 255)
            dotPaint.alpha = alpha

            val dotR = when {
                (ac.altitude ?: 0) > 30_000 -> 7f
                (ac.altitude ?: 0) > 10_000 -> 5f
                else -> 4f
            }
            canvas.drawCircle(px, py, dotR, dotPaint)

            textPaint.alpha = (alpha * 0.8f).toInt()
            val label = ac.callsign?.trim()?.ifBlank { ac.icao24 } ?: ac.icao24
            canvas.drawText(label, px + 8f, py - 8f, textPaint)

            ac.altitude?.let {
                textPaint.alpha = (alpha * 0.5f).toInt()
                textPaint.textSize = 18f
                canvas.drawText("FL${it / 100}", px + 8f, py + 14f, textPaint)
                textPaint.textSize = 24f
            }
        }

        // Own position
        dotPaint.alpha = 255
        dotPaint.color = 0xFFFFFF00.toInt()
        canvas.drawCircle(cx, cy, 5f, dotPaint)
        dotPaint.color = 0xFF00FF88.toInt()

        // Compass labels
        textPaint.alpha = 180; textPaint.color = 0xFFCCFFCC.toInt()
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("N", cx, cy - r - 4f, textPaint)
        canvas.drawText("S", cx, cy + r + 20f, textPaint)
        canvas.drawText("W", cx - r - 4f, cy + 8f, textPaint)
        canvas.drawText("E", cx + r + 4f, cy + 8f, textPaint)
        textPaint.color = 0xFF00FF88.toInt()
        textPaint.textAlign = Paint.Align.LEFT
        if(!positionKnown) canvas.drawText("接收位置未知",12f,height-12f,textPaint)
    }

    private fun latLonToPixel(lat: Double, lon: Double, cx: Float, cy: Float, r: Float): Pair<Float, Float> {
        val dLat = lat - ownLat
        val dLon = (lon - ownLon) * Math.cos(Math.toRadians(ownLat))
        val distNm = Math.sqrt(dLat * dLat + dLon * dLon) * 60.0
        val bearing = Math.atan2(dLon, dLat)
        val pixR = (distNm / rangeNm * r).toFloat().coerceAtMost(r)
        val px = cx + pixR * Math.sin(bearing).toFloat()
        val py = cy - pixR * Math.cos(bearing).toFloat()
        return Pair(px, py)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        receiverPrefs.unregisterOnSharedPreferenceChangeListener(locationListener)
        sweepAnimator.cancel()
    }
}
