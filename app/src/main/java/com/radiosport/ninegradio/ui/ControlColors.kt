package com.radiosport.ninegradio.ui

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.SeekBar
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.slider.Slider

/** Scoped to receiver controls. Plot palettes, maps and radar keep their own colours. */
object ControlColors {
    enum class Group(val accent: Int) {
        RADIO(0xffefa66b.toInt()), AUDIO(0xff83cbb2.toInt()), DISPLAY(0xffb5a6e8.toInt()),
        SCAN(0xffdfc078.toInt()), RECORD(0xffe59494.toInt())
    }
    private val enabled=intArrayOf(android.R.attr.state_enabled)
    private val disabled=intArrayOf(-android.R.attr.state_enabled)
    private val checked=intArrayOf(android.R.attr.state_checked)
    private val neutral=0xff66717d.toInt()
    private fun state(active: Int,inactive: Int)=ColorStateList(
        arrayOf(disabled,checked,intArrayOf()),intArrayOf(neutral,active,inactive))
    fun apply(view: View,group: Group) {
        val sliderColor=UiThemes.color(view.context,com.radiosport.ninegradio.R.attr.radioSlider)
        val switchColor=UiThemes.color(view.context,com.radiosport.ninegradio.R.attr.radioAccent)
        val accent=ColorStateList(arrayOf(disabled,enabled),intArrayOf(neutral,switchColor))
        when(view) {
            is SeekBar -> {
                view.thumbTintList=accent
                view.progressTintList=ColorStateList.valueOf(sliderColor)
                view.progressBackgroundTintList=ColorStateList.valueOf(0xff3a424c.toInt())
            }
            is Slider -> {
                view.thumbRadius=(7*view.resources.displayMetrics.density).toInt()
                view.trackHeight=(3*view.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                view.thumbTintList=accent
                view.trackActiveTintList=ColorStateList.valueOf(sliderColor)
                view.tickActiveTintList=ColorStateList.valueOf(0xff233f43.toInt())
                view.tickInactiveTintList=ColorStateList.valueOf(0xff52656c.toInt())
                view.trackInactiveTintList=ColorStateList.valueOf(0xff3a424c.toInt())
                view.haloTintList=ColorStateList.valueOf((switchColor and 0x00ffffff) or 0x33000000)
            }
            is SwitchCompat -> {
                view.thumbTintList=state(switchColor,0xff99a1aa.toInt())
                view.trackTintList=state((switchColor and 0x00ffffff) or 0x66000000,0xff3a424c.toInt())
            }
            is Switch -> {
                view.thumbTintList=state(switchColor,0xff99a1aa.toInt())
                view.trackTintList=state((switchColor and 0x00ffffff) or 0x66000000,0xff3a424c.toInt())
            }
        }
        if(view is ViewGroup) for(i in 0 until view.childCount) apply(view.getChildAt(i),group)
    }
}
