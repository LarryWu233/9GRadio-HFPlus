package com.radiosport.ninegradio.ui

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ScaleGestureDetector
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import kotlin.math.abs

/** Both plots share one explicit gesture mode and one receiver frequency. */
class GraphTuningTouch(private val view: View, private val dragMode: () -> Boolean,
    private val frequency: () -> Long, private val span: () -> Long, private val step: () -> Long,
    private val pixelFrequency: (Float) -> Long, private val tune: (Long) -> Boolean,
    private val finished: () -> Unit, private val zoom: () -> Float = { 1f },
    private val changeZoom: (Float) -> Unit = {},
    private val bandwidthHit: (Float) -> Boolean = { false },
    private val changeBandwidthAt: (Float) -> Boolean = { false },
    private val inspectEnabled: () -> Boolean = { false },
    private val inspectAt: (Float,Float) -> Unit = { _,_ -> },
    private val inspectEnd: () -> Unit = {}) : View.OnTouchListener {
    private val haptics = TuningHaptics(view)
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private var downX = 0f
    private var startHz = 0L
    private var startSpan = 0L
    private var dragging = false
    private var pinching = false
    private var bandwidthGesture=false
    private var bandwidthMoved=false
    private var inspecting=false
    private var touchActive=false
    private var lastX=0f
    private var lastY=0f
    private val inspection=GestureDetector(view.context,object: GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent)=true
        override fun onLongPress(e: MotionEvent) {
            if(touchActive && inspectEnabled() && !pinching && !dragging && !bandwidthMoved) {
                inspecting=true; bandwidthGesture=false
                inspectAt(lastX,lastY)
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    })
    private val scale = ScaleGestureDetector(view.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { pinching = true; return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean { changeZoom(zoom() * detector.scaleFactor); return true }
    })
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        lastX=event.x; lastY=event.y
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            touchActive=true; inspecting=false; bandwidthMoved=false
            dragging=false; pinching=false
        }
        scale.onTouchEvent(event)
        inspection.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; startHz = frequency(); startSpan = span(); dragging = false; pinching = false
                bandwidthGesture=bandwidthHit(event.x)
                v.parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pinching=true; inspecting=false; inspectEnd()
            }
            MotionEvent.ACTION_MOVE -> if(inspecting) {
                if(inspectEnabled()) inspectAt(event.x,event.y) else inspectEnd()
            } else if (!pinching && event.pointerCount == 1 && bandwidthGesture) {
                if(abs(event.x-downX)>slop) bandwidthMoved=true
                if(bandwidthMoved && changeBandwidthAt(event.x)) haptics.tick()
            } else if (!pinching && event.pointerCount == 1 && dragMode()) {
                if (abs(event.x - downX) > slop) dragging = true
                if (dragging) {
                    val target = TuningMath.snap(startHz - ((event.x - downX) * startSpan / v.width.coerceAtLeast(1)).toLong(), SpectrumZoom.graphStep(step(),startSpan))
                    if (target != frequency() && tune(target)) haptics.tick()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchActive=false
                if (!inspecting && !pinching && !bandwidthGesture && event.actionMasked == MotionEvent.ACTION_UP && !dragMode() && abs(event.x - downX) <= slop) {
                    if (tune(TuningMath.snap(pixelFrequency(event.x), SpectrumZoom.graphStep(step(),span())))) haptics.tick()
                    v.performClick()
                }
                if(!inspecting) finished()
                inspecting=false; inspectEnd()
                v.parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
