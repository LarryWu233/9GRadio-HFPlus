package com.radiosport.ninegradio.ui

import android.content.Context
import android.content.res.ColorStateList
import androidx.core.graphics.ColorUtils
import com.google.android.material.chip.Chip
import com.radiosport.ninegradio.R

/** Content-sized theme-aware choices; callers choose flow or horizontal scrolling. */
internal object RadioChoiceChips {
    fun create(context:Context,label:String,checked:Boolean=false)=Chip(context).apply {
        val density=resources.displayMetrics.density
        val states=arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf(android.R.attr.state_checked),intArrayOf())
        fun colors(disabled:Int,selected:Int,normal:Int)=ColorStateList(states,
            intArrayOf(disabled,selected,normal).map { UiThemes.color(context,it) }.toIntArray())
        text=label;contentDescription=label;textSize=14f
        isCheckable=true;isCheckedIconVisible=false;isChecked=checked
        isSingleLine=true;ellipsize=null;minimumWidth=(72*density).toInt()
        setEnsureMinTouchTargetSize(true)
        chipBackgroundColor=colors(R.attr.radioSurface,R.attr.radioSelected,R.attr.radioSurface)
        setTextColor(colors(R.attr.radioMuted,R.attr.radioAccent,R.attr.radioSecondary))
        chipStrokeColor=colors(R.attr.radioOutline,R.attr.radioAccent,R.attr.radioOutline)
        chipStrokeWidth=density
        rippleColor=ColorStateList.valueOf(ColorUtils.setAlphaComponent(UiThemes.color(context,R.attr.radioAccent),40))
    }
}
