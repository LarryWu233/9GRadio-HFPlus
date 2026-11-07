package com.radiosport.ninegradio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** A single authoritative readout; select any decimal digit and drag vertically. */
class FrequencyView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = 0) : View(context, attrs, defStyleAttr) {
    var onFrequencyChanged: ((Long) -> Unit)? = null
    var onLongPress: ((Long) -> Unit)? = null
    var onTuningFinished: (() -> Unit)? = null
    var onTuningStepChanged: ((Long) -> Unit)? = null
    var frequencyLimiter: ((Long, Long) -> Long)? = null
    private var frequencyHz = 100_000_000L
    private var digits = "0100000000"
    private var selectedExponent = 3
    var tuningStepHz = 1000L
        private set
    private var lastY = 0f
    private var pendingDrag = 0f
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = RadioFonts.digits(context); textAlign = Paint.Align.CENTER }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioSecondary) }
    private val selectionPaint = Paint().apply { color = (UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioAccent) and 0xffffff) or 0x22000000 }
    private val groupPaint = Paint()
    private val glyphBounds=android.graphics.Rect()
    private val haptics = TuningHaptics(this)
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onLongPress(e: MotionEvent) { onLongPress?.invoke(frequencyHz) }
    })

    /** Programmatic updates are silent: rendering must never send a second tune command. */
    fun setFrequency(hz: Long) {
        if (frequencyHz == hz) return
        frequencyHz = hz
        digits = hz.coerceAtLeast(0).toString().padStart(10, '0').takeLast(10)
        contentDescription = "$hz Hz"
        postInvalidateOnAnimation()
    }
    fun getFrequency() = frequencyHz
    fun setTuningStep(hz: Long) {
        if (tuningStepHz == hz) return
        tuningStepHz = hz.coerceAtLeast(1)
        selectedExponent = floor(log10(tuningStepHz.toDouble())).toInt().coerceIn(0, 9)
        onTuningStepChanged?.invoke(tuningStepHz)
        postInvalidateOnAnimation()
    }
    fun stepUp() = changeBy(tuningStepHz)
    fun stepDown() = changeBy(-tuningStepHz)
    var reverseTuning=false
    private fun changeBy(rawDelta: Long) {
        val delta=if(reverseTuning) -rawDelta else rawDelta
        val target = TuningMath.snap(frequencyHz + delta, tuningStepHz)
        val next = frequencyLimiter?.invoke(frequencyHz, target)
            ?: target.coerceIn(0L, 9_999_999_999L)
        if (next == frequencyHz) return
        setFrequency(next)
        onFrequencyChanged?.invoke(next)
        haptics.tick()
    }
    private fun digitX(index: Int): Float {
        val groupGap = width * 0.035f
        val cell = (width - groupGap * 3) / 10f
        val gaps = when { index == 0 -> 0; index <= 3 -> 1; index <= 6 -> 2; else -> 3 }
        return cell * (index + 0.5f) + gaps * groupGap
    }
    override fun onDraw(canvas: Canvas) {
        val cell = width * 0.0895f
        val selected = 9 - selectedExponent
        for ((first, last) in listOf(0 to 0, 1 to 3, 4 to 6, 7 to 9)) {
            groupPaint.color = UiThemes.color(context,if(first==1) com.radiosport.ninegradio.R.attr.radioElevated else com.radiosport.ninegradio.R.attr.radioCard)
            canvas.drawRoundRect(digitX(first) - cell * 0.49f, height * 0.1f,
                digitX(last) + cell * 0.49f, height * 0.8f, 6f, 6f, groupPaint)
        }
        canvas.drawRoundRect(digitX(selected) - cell / 2, height * 0.14f,
            digitX(selected) + cell / 2, height * 0.77f, 5f, 5f, selectionPaint)
        textPaint.textSize = minOf(cell * 1.45f, height * 0.47f)
        for (index in 0..9) {
            textPaint.textSize = minOf(cell * 1.45f, height * 0.47f) * if (index >= 7) 0.78f else 1f
            textPaint.color = when {
                index == selected -> UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioAccent)
                index >= 7 && tuningStepHz >= 1000 -> UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioMuted)
                index in 1..3 -> UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioText)
                else -> UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioSecondary)
            }
            val glyphWidth=textPaint.measureText("8")
            if(glyphWidth>cell*0.83f) textPaint.textSize*=cell*0.83f/glyphWidth
            textPaint.getTextBounds("8",0,1,glyphBounds)
            val baseline=height*0.45f-(glyphBounds.top+glyphBounds.bottom)/2f
            val lit=textPaint.color
            textPaint.color=(UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioText) and 0xffffff) or 0x11000000
            canvas.drawText("8",digitX(index),baseline,textPaint)
            textPaint.color=lit
            canvas.drawText(digits, index, index + 1, digitX(index), baseline, textPaint)
        }
        textPaint.color = UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioSecondary)
        textPaint.textSize = minOf(cell * 1.1f, height * 0.37f)
        for ((left, right) in listOf(0 to 1, 3 to 4, 6 to 7))
            canvas.drawCircle((digitX(left)+digitX(right))/2f,height*0.63f,height*0.014f,textPaint)
        labelPaint.textSize = height * 0.12f
        canvas.drawText("GHz", digitX(0), height * 0.95f, labelPaint)
        canvas.drawText("MHz", digitX(2), height * 0.95f, labelPaint)
        canvas.drawText("kHz", digitX(5), height * 0.95f, labelPaint)
        canvas.drawText("Hz", digitX(8), height * 0.95f, labelPaint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val digit = (0..9).minBy { abs(digitX(it) - event.x) }
                setTuningStep(10.0.pow(9 - digit).toLong())
                lastY = event.y
                pendingDrag = 0f
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                pendingDrag += lastY - event.y
                lastY = event.y
                val tickHeight = 18 * resources.displayMetrics.density
                val ticks = (pendingDrag / tickHeight).toInt()
                if (ticks != 0) {
                    changeBy(ticks * tuningStepHz)
                    pendingDrag -= ticks * tickHeight
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                onTuningFinished?.invoke()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize((72 * resources.displayMetrics.density).toInt(), heightMeasureSpec))
    }
}
