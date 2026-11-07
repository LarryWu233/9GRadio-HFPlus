package com.radiosport.ninegradio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/** Deflection controls steps per second; releasing always returns to zero. */
class SpringTunerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs), Choreographer.FrameCallback {
    var onSteps: ((Long) -> Unit)? = null
    var onFinished: (() -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haptics = TuningHaptics(this)
    private var position = 0f
    private var active = false
    private var frameTime = 0L
    private var remainder = 0.0
    private var speedGradient: LinearGradient? = null
    private val density get() = resources.displayMetrics.density
    init { contentDescription = "左右调谐：离中心越远越快，松手停止并回中"; isFocusable = true }
    override fun onDraw(canvas: Canvas) {
        val cy = height * 0.5f
        val half = (width / 2f - 28 * density).coerceAtLeast(1f)
        paint.shader = speedGradient
        canvas.drawRoundRect(12 * density, cy - 9 * density, width - 12 * density, cy + 9 * density, 9 * density, 9 * density, paint)
        paint.shader = null
        paint.color = 0xff9db7c8.toInt(); paint.strokeWidth = 2 * density
        canvas.drawLine(width / 2f, cy - 17 * density, width / 2f, cy + 17 * density, paint)
        paint.color = UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioAccent)
        canvas.drawCircle(width / 2f + position * half, cy, 19 * density, paint)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        speedGradient = LinearGradient(0f,0f,w.toFloat(),0f,
            intArrayOf(0xffffa15a.toInt(),0xff506178.toInt(),0xff1b303f.toInt(),0xff447e9b.toInt(),0xff00d7ed.toInt()),
            floatArrayOf(0f,0.28f,0.5f,0.72f,1f), Shader.TileMode.CLAMP)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = true; frameTime = 0; remainder = 0.0
                parent?.requestDisallowInterceptTouchEvent(true)
                Choreographer.getInstance().postFrameCallback(this)
                update(event.x)
            }
            MotionEvent.ACTION_MOVE -> update(event.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { stop(); if (event.actionMasked == MotionEvent.ACTION_UP) performClick() }
        }
        return true
    }
    private fun update(x: Float) {
        position = ((x - width / 2f) / (width / 2f - 28 * density).coerceAtLeast(1f)).coerceIn(-1f, 1f)
        invalidate()
    }
    override fun doFrame(time: Long) {
        if (!active) return
        if (frameTime != 0L) {
            remainder += TuningMath.rate(position) * ((time - frameTime) / 1e9).coerceAtMost(0.1)
            val ticks = remainder.toLong()
            if (ticks != 0L) { remainder -= ticks; onSteps?.invoke(ticks); haptics.tick() }
        }
        frameTime = time
        Choreographer.getInstance().postFrameCallback(this)
    }
    private fun stop() {
        val wasActive = active
        active = false; position = 0f; frameTime = 0; remainder = 0.0
        Choreographer.getInstance().removeFrameCallback(this)
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
        if (wasActive) { haptics.tick(); onFinished?.invoke() }
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); if (visibility != VISIBLE) stop() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); if (visibility != VISIBLE) stop() }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus); if (!hasWindowFocus) stop() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }
}
