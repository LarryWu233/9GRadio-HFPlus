package com.radiosport.ninegradio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/** Separate baselines keep the compact segment label and numeric value readable. */
class RadioWidthReadout @JvmOverloads constructor(context:Context,attrs:AttributeSet?=null):AppCompatTextView(context,attrs) {
    private val labelPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=Typeface.create("sans-serif-condensed",Typeface.BOLD); textAlign=Paint.Align.CENTER }
    private val valuePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=Typeface.MONOSPACE; textAlign=Paint.Align.CENTER }
    private var lastText=""
    private var label=""
    private var value=""
    override fun onDraw(canvas:Canvas) {
        val raw=text.toString()
        if(raw!=lastText) {
            lastText=raw
            val breakAt=raw.indexOf('\n')
            label=if(breakAt<0) raw else raw.substring(0,breakAt)
            value=if(breakAt<0) "" else raw.substring(breakAt+1)
        }
        labelPaint.textSize=10f*resources.displayMetrics.scaledDensity
        valuePaint.textSize=11f*resources.displayMetrics.scaledDensity
        val color=UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioText)
        labelPaint.color=UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioAccent)
        valuePaint.color=if(isEnabled) color else UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioMuted)
        val usable=(width-paddingLeft-paddingRight-4*resources.displayMetrics.density).coerceAtLeast(1f)
        val measured=valuePaint.measureText(value)
        if(measured>usable) valuePaint.textSize*=usable/measured
        canvas.drawText(label,width/2f,height*0.36f,labelPaint)
        canvas.drawText(value,width/2f,height*0.82f,valuePaint)
    }
}
