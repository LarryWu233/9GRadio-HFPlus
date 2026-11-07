package com.radiosport.ninegradio.ui

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/** A deliberate radio-value hold, separate from Android's shorter default long click. */
object HeldValueEditor {
    const val HOLD_MS=1500L
    fun install(view:View, edit:()->Unit) {
        val slop=ViewConfiguration.get(view.context).scaledTouchSlop
        var x=0f; var y=0f; var fired=false; var moved=false
        val action=Runnable {
            if(!moved && view.isShown && view.isEnabled) {
                fired=true; view.isPressed=false
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                edit()
            }
        }
        view.isLongClickable=false
        view.setOnTouchListener { _,event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x=event.x; y=event.y; fired=false; moved=false; view.postDelayed(action,HOLD_MS); false }
                MotionEvent.ACTION_MOVE -> { if(hypot(event.x-x,event.y-y)>slop) { moved=true; view.removeCallbacks(action) }; fired }
                MotionEvent.ACTION_POINTER_DOWN -> { moved=true; view.removeCallbacks(action); false }
                MotionEvent.ACTION_UP -> { view.removeCallbacks(action); (fired || moved).also { if(it) view.isPressed=false } }
                MotionEvent.ACTION_CANCEL -> { view.removeCallbacks(action); view.isPressed=false; false }
                else -> false
            }
        }
        view.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View) {}
            override fun onViewDetachedFromWindow(v:View) { view.removeCallbacks(action) }
        })
    }
}
