package com.radiosport.ninegradio.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.slider.Slider
import com.google.android.material.slider.LabelFormatter
import com.radiosport.ninegradio.R
import kotlin.math.abs

/** Material Slider input and AndroidX spring motion around the existing tuning-rate adapter. */
class SpringTunerView @JvmOverloads constructor(context:Context,attrs:AttributeSet?=null):FrameLayout(context,attrs),Choreographer.FrameCallback {
    var onSteps:((Long)->Unit)?=null
    var onFinished:(()->Unit)?=null
    private val haptics=TuningHaptics(this)
    private var position=0f
    private var active=false
    private var frameTime=0L
    private var remainder=0.0
    private var returnAnimation:SpringAnimation?=null
    private lateinit var slider:JogSlider
    private val density get()=resources.displayMetrics.density
    private fun dp(value:Int)=(value*density).toInt()
    init {
        setBackgroundResource(R.drawable.radio_jog_surface)
        minimumHeight=resources.getDimensionPixelSize(R.dimen.radio_touch_target)
        slider=JogSlider(context).apply {
            id=R.id.springTunerInput;valueFrom=-1f;valueTo=1f;value=0f
            isSaveEnabled=false;labelBehavior=LabelFormatter.LABEL_GONE
            contentDescription="快速调频：左右拨动，离中心越远越快，松手停止"
            trackHeight=dp(8);thumbWidth=dp(32);thumbHeight=dp(36)
            thumbTrackGapSize=dp(6);trackStopIndicatorSize=dp(2)
            val neutral=ColorStateList.valueOf(UiThemes.color(context,R.attr.radioOutline))
            trackActiveTintList=neutral;trackInactiveTintList=neutral
            setCustomThumbDrawable(ContextCompat.getDrawable(context,R.drawable.radio_jog_thumb)!!)
            addOnChangeListener { _,value,user -> if(user) {
                if(active) position=value else {
                    // TalkBack adjustments are bounded steps, never an unattended running motor.
                    val ticks=if(value>0) 1L else if(value<0) -1L else 0L
                    if(ticks!=0L) { onSteps?.invoke(ticks);haptics.tick();onFinished?.invoke() }
                    post { if(!active) this.value=0f }
                }
            } }
        }
        addView(slider,LayoutParams(-1,dp(48),Gravity.CENTER_VERTICAL).apply { marginStart=dp(24);marginEnd=dp(24) })
        for((label,side) in listOf("‹" to Gravity.START,"›" to Gravity.END)) {
            addView(TextView(context).apply {
                text=label;textSize=24f;gravity=Gravity.CENTER
                setTextColor(UiThemes.color(context,R.attr.radioSecondary))
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },LayoutParams(dp(28),-1,side))
        }
    }
    private fun begin() {
        if(active) return
        returnAnimation?.cancel();returnAnimation=null
        active=true;frameTime=0;remainder=0.0
        parent?.requestDisallowInterceptTouchEvent(true)
        Choreographer.getInstance().postFrameCallback(this)
    }
    private fun stop(animate:Boolean=true) {
        if(!::slider.isInitialized) return
        val wasActive=active
        active=false;position=0f;frameTime=0;remainder=0.0
        Choreographer.getInstance().removeFrameCallback(this)
        parent?.requestDisallowInterceptTouchEvent(false)
        returnAnimation?.cancel();returnAnimation=null
        if(animate && isShown && abs(slider.value)>0.001f) {
            returnAnimation=SpringAnimation(FloatValueHolder(slider.value)).apply {
                spring=SpringForce(0f).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY).setStiffness(SpringForce.STIFFNESS_MEDIUM)
                setMinimumVisibleChange(0.002f)
                addUpdateListener { _,value,_ -> if(!active) slider.value=value.coerceIn(-1f,1f) }
                addEndListener { _,cancelled,_,_ -> if(!cancelled && !active) slider.value=0f }
                start()
            }
        } else slider.value=0f
        if(wasActive) { haptics.tick();onFinished?.invoke() }
    }
    override fun doFrame(time:Long) {
        if(!active) return
        if(frameTime!=0L) {
            remainder+=TuningMath.rate(position)*((time-frameTime)/1e9).coerceAtMost(0.1)
            val ticks=remainder.toLong()
            if(ticks!=0L) { remainder-=ticks;onSteps?.invoke(ticks);haptics.tick() }
        }
        frameTime=time;Choreographer.getInstance().postFrameCallback(this)
    }
    private inner class JogSlider(context:Context):Slider(context) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private val bandColor=UiThemes.color(context,R.attr.radioSlider)
        private val fastColor=UiThemes.color(context,R.attr.radioAccent)
        private val markerColor=UiThemes.color(context,R.attr.radioSecondary)
        override fun onTouchEvent(event:MotionEvent):Boolean {
            if(!isEnabled) return false
            if(event.actionMasked==MotionEvent.ACTION_DOWN) begin()
            val handled=super.onTouchEvent(event)
            if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) stop()
            return handled
        }
        override fun onDraw(canvas:Canvas) {
            super.onDraw(canvas)
            val center=width/2f;val y=height/2f
            val direction=if(layoutDirection==View.LAYOUT_DIRECTION_RTL) -1 else 1
            val x=center+value*direction*trackWidth/2f
            val gap=dp(23)
            if(abs(x-center)>gap) {
                val end=x-if(x>center) gap else -gap
                paint.color=ColorUtils.blendARGB(bandColor,fastColor,abs(value))
                canvas.drawRoundRect(minOf(center,end),y-dp(4),maxOf(center,end),y+dp(4),dp(4).toFloat(),dp(4).toFloat(),paint)
            }
            if(abs(value)>0.15f) {
                paint.color=markerColor
                canvas.drawCircle(center,y,dp(2).toFloat(),paint)
            }
        }
        override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean {
            if(keyCode==KeyEvent.KEYCODE_DPAD_LEFT || keyCode==KeyEvent.KEYCODE_DPAD_RIGHT) {
                onSteps?.invoke(if(keyCode==KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1);haptics.tick();return true
            }
            return super.onKeyDown(keyCode,event)
        }
        override fun onKeyUp(keyCode:Int,event:KeyEvent):Boolean {
            if(keyCode==KeyEvent.KEYCODE_DPAD_LEFT || keyCode==KeyEvent.KEYCODE_DPAD_RIGHT) { onFinished?.invoke();return true }
            return super.onKeyUp(keyCode,event)
        }
    }
    override fun onVisibilityChanged(view:View,visibility:Int) { super.onVisibilityChanged(view,visibility);if(visibility!=View.VISIBLE) stop(false) }
    override fun onWindowVisibilityChanged(visibility:Int) { super.onWindowVisibilityChanged(visibility);if(visibility!=View.VISIBLE) stop(false) }
    override fun onWindowFocusChanged(focus:Boolean) { super.onWindowFocusChanged(focus);if(!focus) stop(false) }
    override fun onDetachedFromWindow() { stop(false);super.onDetachedFromWindow() }
}
