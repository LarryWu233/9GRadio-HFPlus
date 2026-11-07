package com.radiosport.ninegradio.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ListView
import kotlin.math.abs

/** The result list owns vertical drags only while it can scroll in that direction. */
class NestedResultListView @JvmOverloads constructor(context: Context,attrs: AttributeSet?=null): ListView(context,attrs) {
    private var x=0f; private var y=0f; private var lastY=0f
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    init { isNestedScrollingEnabled=false }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        var capture: Boolean?=null
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x=event.x; y=event.y; lastY=event.y; capture=true }
            MotionEvent.ACTION_MOVE -> {
                val dx=abs(event.x-x); val dy=abs(event.y-y)
                if(maxOf(dx,dy)>slop) {
                    val direction=if(event.y<lastY) 1 else -1
                    capture=dy>dx && canScrollVertically(direction)
                }
                lastY=event.y
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> capture=false
        }
        val handled=super.dispatchTouchEvent(event)
        capture?.let { parent?.requestDisallowInterceptTouchEvent(it) }
        return handled
    }
}
