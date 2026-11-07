package com.radiosport.ninegradio.ui

import android.content.Context
import android.content.res.ColorStateList
import android.widget.GridLayout
import com.google.android.material.button.MaterialButton
import com.radiosport.ninegradio.R

/** One shape, colour state and spacing for RF modes and content-module choices. */
internal object ModeChoiceUi {
    fun button(context: Context) = MaterialButton(context).apply {
        val density=resources.displayMetrics.density
        val states=arrayOf(intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_selected),intArrayOf())
        fun colors(disabled:Int,selected:Int,normal:Int)=ColorStateList(states,
            intArrayOf(disabled,selected,normal).map { UiThemes.color(context,it) }.toIntArray())
        isCheckable=false;isAllCaps=false;textSize=12f
        setSingleLine(true);setHorizontallyScrolling(false)
        setPadding((4*density).toInt(),0,(4*density).toInt(),0)
        minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0
        insetTop=0;insetBottom=0;elevation=0f;stateListAnimator=null
        cornerRadius=resources.getDimensionPixelSize(R.dimen.radio_corner_medium)
        backgroundTintList=colors(R.attr.radioSurface,R.attr.radioSelected,R.attr.radioSurface)
        setTextColor(colors(R.attr.radioMuted,R.attr.radioAccent,R.attr.radioText))
        strokeColor=ColorStateList.valueOf(UiThemes.color(context,R.attr.radioOutline))
        strokeWidth=(density*0.5f).toInt().coerceAtLeast(1)
    }

    fun cell(context:Context,index:Int):GridLayout.LayoutParams {
        val density=context.resources.displayMetrics.density
        val halfGap=context.resources.getDimensionPixelSize(R.dimen.radio_space_xs)
        return GridLayout.LayoutParams(GridLayout.spec(index/3),GridLayout.spec(index%3,1f)).apply {
            width=0;height=(48*density).toInt()
            marginStart=if(index%3==0) 0 else halfGap
            marginEnd=if(index%3==2) 0 else halfGap
            bottomMargin=halfGap*2
        }
    }
}
