package com.radiosport.ninegradio.ui

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout

/** Let MapLibre own a complete gesture, including two fingers, inside scrollable panels. */
class MapGestureFrame(context:Context):FrameLayout(context) {
    private var touching=false
    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN) touching=true
        if(touching) parent?.requestDisallowInterceptTouchEvent(true)
        val result=super.dispatchTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) {
            touching=false
            super.requestDisallowInterceptTouchEvent(false)
        }
        return result
    }
    override fun requestDisallowInterceptTouchEvent(disallow:Boolean) {
        super.requestDisallowInterceptTouchEvent(touching || disallow)
    }
    override fun onDetachedFromWindow() {
        touching=false; super.requestDisallowInterceptTouchEvent(false)
        super.onDetachedFromWindow()
    }
}
