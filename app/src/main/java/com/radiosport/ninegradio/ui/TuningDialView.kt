package com.radiosport.ninegradio.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.Choreographer
import android.view.ViewConfiguration
import com.radiosport.ninegradio.dsp.FrequencyStepManager
import kotlin.math.*

/**
 * Horizontal "3D film-strip" tuning dial with protocol-aware intelligent step
 * sizing.
 *
 * Visually this renders as a horizontal drum/cylinder of frequency-step
 * tick marks viewed slightly from above: ticks near the centre are tall,
 * bright and full-width; ticks toward the left/right edges shrink, dim and
 * compress horizontally (a cheap per-tick perspective transform) to suggest
 * the drum curving away from the viewer — like an analogue radio tuning
 * scale wrapped around a cylinder.
 *
 * Tuning is swipe left/right ONLY:
 *  - Swipe left  → frequency decreases (drum rotates so higher ticks slide
 *                  in from the right)
 *  - Swipe right → frequency increases
 *  - Fling continues the rotation with friction-based deceleration
 *  - Long press toggles fine/coarse step mode (unchanged)
 *
 * All public API is unchanged from the previous rotary implementation so
 * existing wiring (MainActivity, ControlsTabManager) continues to work
 * without modification:
 *  - onStep: ((deltaHz: Long) -> Unit)?
 *  - demodMode: String
 *  - currentFreqHz: Long
 *  - fineMode: Boolean
 *  - overrideStepHz: Long?
 *  - onFineModeChanged: ((Boolean) -> Unit)?
 *  - activeStepHzPublic(): Long
 *  - setOnFineModeChangedListener(l)
 *  - syncStep()
 */
class TuningDialView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Public API ────────────────────────────────────────────────────────────

    /** Called when the dial produces a frequency change. */
    var onStep: ((deltaHz: Long) -> Unit)? = null
    var onTuningFinished: (() -> Unit)? = null
    var compact = false
    var showLabels = true
    var showCurrentFrequency=false
    private var frequencyText="100.000.000 Hz"
    data class Choice(val value: Long, val label: String)
    private var choices = emptyList<Choice>()
    private var choiceIndex = 0
    var onChoiceSelected: ((Long) -> Unit)? = null
    fun setChoices(values: List<Choice>, selected: Long) {
        choices = values
        choiceIndex = values.indexOfFirst { it.value == selected }.coerceAtLeast(0)
        compact = true; pxPerStep = 84 * resources.displayMetrics.density
        contentDescription = values.getOrNull(choiceIndex)?.label
        invalidate()
    }
    fun selectChoice(value: Long) {
        choices.indexOfFirst { it.value == value }.takeIf { it >= 0 }?.let { choiceIndex = it; contentDescription = choices[it].label; invalidate() }
    }
    private val haptics = TuningHaptics(this)

    /** Current demod mode string — set from MainActivity whenever mode changes. */
    var demodMode: String = "NFM"
        set(v) { if (field == v) return; field = v; computeStep(); invalidate() }

    /** Current frequency — used for band-aware step selection. */
    var currentFreqHz: Long = 100_000_000L
        set(v) { if (field == v) return; field = v; frequencyText=String.format(java.util.Locale.ROOT,"%,d",v).replace(',','.')+" Hz"; computeStep(); invalidate() }

    /** Fine-tune mode: step is 10× smaller than protocol step. */
    var fineMode: Boolean = false
        set(v) { if (field == v) return; field = v; computeStep(); onFineModeChanged?.invoke(v); invalidate() }

    /** Optional manual step override set by step-size chip selection. Cleared on mode change. */
    var overrideStepHz: Long? = null
        set(v) { if (field == v) return; field = v; computeStep(); invalidate() }

    /** Called when [fineMode] toggles via long-press gesture. */
    var onFineModeChanged: ((Boolean) -> Unit)? = null

    /** Expose the computed step for use in snap-to-channel logic. */
    fun activeStepHzPublic(): Long = activeStepHz

    fun setOnFineModeChangedListener(l: (Boolean) -> Unit) { onFineModeChanged = l }

    // ── Internal state ────────────────────────────────────────────────────────

    // Horizontal drum offset, in pixels, accumulated from swipe deltas.
    private var drumOffsetPx: Float = 0f
    private var pxPerStep: Float = 48f         // pixels of horizontal drag = 1 step
    private var accumulatedPx: Float = 0f      // fractional pixels since last step
    private var activeStepHz: Long = 100_000L  // actual Hz per step (recomputed)
    private var protocolStepHz: Long = 100_000L

    // ── Paint ─────────────────────────────────────────────────────────────────

    // A single tonal channel defines the wheel; no outer card or boxed readout.
    private val channelFill=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioSurface)
    }
    private val channelOutline=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style=Paint.Style.STROKE
        strokeWidth=resources.displayMetrics.density
        color=UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioOutline)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioMuted)
    }
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioAccent)
    }
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioAccent)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        color = UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioText)
    }
    private val stepLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        color = UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioAccent)
        isFakeBoldText = true
    }
    private val fineLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        color = UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioAccent)
        isFakeBoldText = true
    }
    private val centrePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioAccent)
        alpha = 200
    }

    // ── Geometry ──────────────────────────────────────────────────────────────
    private var cx = 0f; private var cy = 0f
    private var trackLeft = 0f; private var trackRight = 0f
    private var trackTop = 0f; private var trackBottom = 0f
    private var trackW = 0f; private var trackH = 0f

    // ── Gesture ───────────────────────────────────────────────────────────────
    private var velocityX: Float = 0f
    private var isFling = false
    private var lastFrameNanos = 0L
    private var downX = 0f
    private var downY = 0f
    private var gestureAxis = 0
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val pointerPath = Path()
    private val labelCache = object : LinkedHashMap<Long, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?) = size > 64
    }
    private var stepText = ""
    private var bandText = ""
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!isFling) return
            if (lastFrameNanos != 0L) {
                val dt = ((now - lastFrameNanos) / 1e9f).coerceIn(0f, 0.05f)
                roll(-velocityX * dt)
                velocityX *= exp(-10.15f * dt)
            }
            lastFrameNanos = now
            if (abs(velocityX) < 50f) stopFling(true)
            else Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private fun stopFling(commit: Boolean) {
        isFling = false
        lastFrameNanos = 0
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        if (commit) finishGesture()
    }
    private fun finishGesture() {
        if (choices.isNotEmpty()) { accumulatedPx=0f; drumOffsetPx=0f; invalidate() }
        onTuningFinished?.invoke()
    }

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onLongPress(e: MotionEvent) {
                if (compact) return
                fineMode = !fineMode
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent,
                                  distanceX: Float, distanceY: Float): Boolean {
                // Horizontal-swipe only: vertical motion (distanceY) is
                // ignored entirely so this view never fights a parent
                // ScrollView for vertical drags.
                if (gestureAxis == 1) roll(distanceX)
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent,
                                 velocityX: Float, velocityY: Float): Boolean {
                if (gestureAxis != 1) return false
                this@TuningDialView.velocityX = velocityX
                isFling = true
                lastFrameNanos = 0
                Choreographer.getInstance().postFrameCallback(frameCallback)
                return true
            }
        })

    // ── Init ──────────────────────────────────────────────────────────────────
    private val roundedTrack=Path()
    private var trackRadius=0f
    init {
        computeStep()
    }

    // ── Public helpers ────────────────────────────────────────────────────────

    /** Called to sync the step label without triggering a frequency change. */
    fun syncStep() { computeStep(); invalidate() }

    // ── Step logic ────────────────────────────────────────────────────────────

    private fun computeStep() {
        protocolStepHz = overrideStepHz
            ?: FrequencyStepManager.recommendedStep(currentFreqHz, demodMode).hz
        activeStepHz   = if (fineMode) maxOf(1L, protocolStepHz / 10) else protocolStepHz
        stepText = "STEP ${formatHz(activeStepHz)}"
        bandText = FrequencyStepManager.bestPreset(currentFreqHz)?.name ?: demodMode
    }

    /**
     * Roll the drum horizontally by [dx] pixels (from GestureDetector's
     * onScroll distanceX, where positive = finger moved left).
     *
     * A left swipe (finger moves left, distanceX > 0) rolls the drum so that
     * higher-frequency ticks slide into view from the right — i.e. frequency
     * increases. This matches the natural "drag the scale to bring the next
     * value into view" gesture for a horizontal tuning strip.
     */
    private fun roll(dx: Float) {
        accumulatedPx += dx

        val stepsRaw = (accumulatedPx / pxPerStep).toInt()
        if (stepsRaw != 0) {
            accumulatedPx -= stepsRaw * pxPerStep
            if (choices.isNotEmpty()) {
                val index = (choiceIndex + stepsRaw).coerceIn(choices.indices)
                if (choiceIndex != index) {
                    choiceIndex = index; haptics.tick()
                    contentDescription = choices[index].label
                    onChoiceSelected?.invoke(choices[index].value)
                }
            } else {
                val delta = stepsRaw * activeStepHz
                haptics.tick(); onStep?.invoke(delta)
            }
        }
        drumOffsetPx = accumulatedPx
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f; cy = h / 2f

        // The "drum" track occupies the full width with small side margins,
        // and a band in the vertical centre of the view.
        val marginX = w * 0.02f
        trackLeft = marginX
        trackRight = w - marginX
        trackTop = h * 0.18f
        trackBottom = h * 0.74f
        trackW = trackRight - trackLeft
        trackH = trackBottom - trackTop
        trackRadius=minOf(trackH/2,resources.getDimension(com.radiosport.ninegradio.R.dimen.radio_corner_large))
        roundedTrack.reset()
        roundedTrack.addRoundRect(trackLeft,trackTop,trackRight,trackBottom,trackRadius,trackRadius,Path.Direction.CW)

        // Vertical gradient across the track gives the drum body a curved,
        // cylindrical "3D" highlight: bright band near the top-third (where
        // a light source would catch a horizontal cylinder), darker at the
        // extremes.

        // pxPerStep scales with width so the gesture feel is consistent
        // across phone/tablet sizes — roughly 1 step per 6% of view width.
        pxPerStep = if (choices.isNotEmpty()) 84 * resources.displayMetrics.density else (w * 0.06f).coerceIn(28f, 80f)
        val size = trackH * 0.16f
        pointerPath.reset()
        pointerPath.moveTo(cx, trackTop - 2f)
        pointerPath.lineTo(cx - size * 0.6f, trackTop - size * 1.4f)
        pointerPath.lineTo(cx + size * 0.6f, trackTop - size * 1.4f)
        pointerPath.close()

    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        canvas.drawRoundRect(trackLeft,trackTop,trackRight,trackBottom,trackRadius,trackRadius,channelFill)
        canvas.drawRoundRect(trackLeft,trackTop,trackRight,trackBottom,trackRadius,trackRadius,channelOutline)

        // Clip tick rendering to the drum body so perspective-scaled ticks
        // never spill outside the rounded rect.
        canvas.save()
        canvas.clipPath(roundedTrack)
        // Keep the readout legible without a separate plate inside the tonal channel.
        val readout=if(showCurrentFrequency) frequencyText else choices.getOrNull(choiceIndex)?.label
        val readoutHalf=if(readout!=null) {
            labelPaint.textSize=13*resources.displayMetrics.scaledDensity
            (labelPaint.measureText(readout)/2+12*resources.displayMetrics.density).coerceAtMost(trackW*0.43f)
        } else 0f
        val readoutTop=trackTop+trackH*0.22f
        val readoutBottom=trackBottom-trackH*0.15f
        if(readoutHalf>0) canvas.clipOutRect(cx-readoutHalf,readoutTop,cx+readoutHalf,readoutBottom)

        // Tick marks: an evenly-spaced horizontal scale, scrolled by
        // drumOffsetPx and rendered with a per-tick perspective transform so
        // ticks near the centre appear tall/full-width/bright and ticks near
        // the left/right edges appear short/compressed/dim — the visual
        // illusion of a cylinder curving away from the viewer.
        val tickSpacingPx = pxPerStep
        val totalTicks = (trackW / tickSpacingPx).toInt() + 4
        val halfW = trackW / 2f

        // Normalise drumOffsetPx into [0, tickSpacingPx) for the base phase,
        // then iterate a window of ticks either side of centre.
        val phase = drumOffsetPx % tickSpacingPx
        val startIndex = -(totalTicks / 2) - 1
        for (i in startIndex until startIndex + totalTicks + 2) {
            // Base x position of this tick if the drum were flat (no perspective)
            val flatX = cx + i * tickSpacingPx - phase

            // Distance from centre, normalised to [-1, 1] across the half-width
            // — used as the perspective parameter "u".
            val u = ((flatX - cx) / halfW)

            // Perspective scale: 1.0 at centre, shrinking toward the edges.
            // A gentle cosine falloff gives a smooth cylindrical curve.
            val scale = cos(u.coerceIn(-1f, 1f) * (PI / 2.0).toFloat() * 0.92f)
                .coerceIn(0.18f, 1f)

            // Horizontal compression toward the edges: ticks bunch together
            // as they approach the rim of the drum, like a real cylinder.
            val perspectiveX = cx + (flatX - cx) * (0.55f + 0.45f * scale)

            if (perspectiveX < trackLeft - tickSpacingPx ||
                perspectiveX > trackRight + tickSpacingPx) continue

            // Which step index does this tick represent, relative to the
            // centre (pointer) tick? Centre tick = step 0.
            val isMajor = ((i % 5) + 5) % 5 == 0

            val tickH = trackH * (0.15f + 0.55f * scale) * (if (isMajor) 1f else 0.6f)
            val alpha = (60 + (195 * scale)).toInt().coerceIn(40, 255)

            val paint = if (isMajor) majorTickPaint else tickPaint
            paint.alpha = alpha

            val midY = (trackTop + trackBottom) / 2f
            canvas.drawLine(
                perspectiveX, midY - tickH / 2f,
                perspectiveX, midY + tickH / 2f,
                paint
            )

            // Frequency label under major ticks near the centre, fading out
            // toward the edges along with the tick itself.
            if (showLabels && (isMajor || choices.isNotEmpty()) && scale > 0.4f && !(choices.isNotEmpty() && i==0)) {
                val labelHz = if (compact) i.toLong() * activeStepHz else currentFreqHz + i.toLong() * activeStepHz
                labelPaint.alpha = alpha
                labelPaint.textSize = if (choices.isNotEmpty()) 13 * resources.displayMetrics.scaledDensity * scale.coerceAtLeast(0.75f)
                    else (trackH * 0.16f * scale).coerceAtLeast(1f)
                canvas.drawText(
                    if (choices.isNotEmpty()) choices.getOrNull(choiceIndex + i)?.label.orEmpty() else labelCache.getOrPut(labelHz) { formatHz(labelHz) }, perspectiveX, if(choices.isNotEmpty()) trackTop+trackH*0.7f else trackBottom-trackH*0.06f, labelPaint
                )
            }
        }
        // Restore full alpha on shared paints so other draw calls aren't affected
        majorTickPaint.alpha = 255
        tickPaint.alpha = 255
        labelPaint.alpha = 255

        canvas.restore()

        // Centre pointer: a downward triangle above the drum, indicating the
        // currently-tuned frequency sits at the centre tick.
        canvas.drawPath(pointerPath, pointerPaint)

        // Centre highlight line through the drum, marking the active frequency.
        if(readoutHalf>0) {
            canvas.drawLine(cx,trackTop+2f,cx,readoutTop,centrePaint)
            canvas.drawLine(cx,readoutBottom,cx,trackBottom-2f,centrePaint)
        } else canvas.drawLine(cx,trackTop+2f,cx,trackBottom-2f,centrePaint)

        if(choices.isNotEmpty()) {
            labelPaint.textSize=13*resources.displayMetrics.scaledDensity
            labelPaint.color=UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioAccent)
            val selected=choices.getOrNull(choiceIndex)?.label.orEmpty()
            canvas.drawText(selected,cx,trackTop+trackH*0.7f,labelPaint)
        }
        if(showCurrentFrequency) {
            val face=labelPaint.typeface
            labelPaint.typeface=Typeface.MONOSPACE
            labelPaint.color=UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioAccent)
            labelPaint.textSize=13*resources.displayMetrics.scaledDensity
            val length=labelPaint.measureText(frequencyText)
            if(length>trackW*0.76f) labelPaint.textSize*=trackW*0.76f/length
            canvas.drawText(frequencyText,cx,trackTop+trackH*0.7f,labelPaint)
            labelPaint.typeface=face
        }
        if (compact) return
        // Step label below the drum
        stepLabelPaint.textSize = h * 0.075f
        canvas.drawText(stepText, cx, trackBottom + h * 0.10f, stepLabelPaint)

        // Fine / Coarse label at bottom of view
        val modeLabel = if (fineMode) "⊙ FINE  (hold to toggle)" else "◎ COARSE  (hold to toggle)"
        val modePaint = if (fineMode) fineLabelPaint else stepLabelPaint
        modePaint.textSize = h * 0.055f
        canvas.drawText(modeLabel, cx, h - 6f, modePaint)

        // Mode / band label at top
        val bandLabel = bandText
        labelPaint.textSize = h * 0.06f
        labelPaint.color = 0xFF445566.toInt()
        canvas.drawText(bandLabel, cx, labelPaint.textSize + 2f, labelPaint)
        labelPaint.color = UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioText)

        // Swipe hint arrows either side of centre, reinforcing the
        // horizontal-only gesture.
        labelPaint.textSize = h * 0.07f
        labelPaint.color = 0xFF445566.toInt()
        val arrowY = (trackTop + trackBottom) / 2f + labelPaint.textSize * 0.35f
        canvas.drawText("◀", trackLeft + trackW * 0.05f, arrowY, labelPaint)
        canvas.drawText("▶", trackRight - trackW * 0.05f, arrowY, labelPaint)
        labelPaint.color = UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioText)
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopFling(false)
                downX = event.x; downY = event.y; gestureAxis = 0
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (gestureAxis == 0) {
                    val x = abs(event.x - downX); val y = abs(event.y - downY)
                    if (max(x, y) > touchSlop) {
                        gestureAxis = if (x >= y) 1 else 2
                        if (gestureAxis == 2) parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                stopFling(true)
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            parent?.requestDisallowInterceptTouchEvent(false)
            if (!isFling) finishGesture()
            performClick()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() {
        stopFling(true)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = context.resources.displayMetrics.density
        // Horizontal strip: wide and short, unlike the previous square dial.
        val desiredW = (320 * density).toInt()
        val desiredH = (140 * density).toInt()
        val w = resolveSize(desiredW, widthMeasureSpec)
        val h = resolveSize(desiredH, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun formatHz(hz: Long): String = when {
        hz >= 1_000_000L -> "%.3g MHz".format(hz / 1_000_000.0)
        hz >= 1_000L     -> "%.3g kHz".format(hz / 1_000.0)
        else             -> "$hz Hz"
    }
}
