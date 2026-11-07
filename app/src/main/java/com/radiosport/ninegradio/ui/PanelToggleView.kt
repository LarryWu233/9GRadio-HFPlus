package com.radiosport.ninegradio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** A shallow, round-ended chevron; no full-width handle background. */
class PanelToggleView @JvmOverloads constructor(context: Context, attrs: AttributeSet?=null) : View(context,attrs) {
    private val stroke=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioSecondary); style=Paint.Style.STROKE; strokeWidth=2.8f*resources.displayMetrics.density
        strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND
    }
    private val line=Path()
    var isCollapsed=false
        set(value) { field=value; contentDescription=if(value) "展开控制面板" else "收起控制面板"; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d=resources.displayMetrics.density
        val cy=height/2f; val direction=if(isCollapsed) -1 else 1
        line.reset(); line.moveTo(width/2f-12*d,cy-direction*2f*d)
        line.lineTo(width/2f,cy+direction*2f*d); line.lineTo(width/2f+12*d,cy-direction*2f*d)
        canvas.drawPath(line,stroke)
    }
}
