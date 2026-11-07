package com.radiosport.ninegradio.ui

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.SeekBar
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.slider.Slider
import com.google.android.material.materialswitch.MaterialSwitch
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.radiosport.ninegradio.R

/** Scoped to receiver controls. Plot palettes, maps and radar keep their own colours. */
object ControlColors {
    enum class Group(val accent: Int) {
        RADIO(0xffefa66b.toInt()), AUDIO(0xff83cbb2.toInt()), DISPLAY(0xffb5a6e8.toInt()),
        SCAN(0xffdfc078.toInt()), RECORD(0xffe59494.toInt())
    }
    private val disabled=intArrayOf(-android.R.attr.state_enabled)
    private val checked=intArrayOf(android.R.attr.state_checked)
    private fun state(active: Int,inactive: Int,neutral:Int)=ColorStateList(
        arrayOf(disabled,checked,intArrayOf()),intArrayOf(neutral,active,inactive))
    fun apply(view: View,group: Group) {
        val switchColor=UiThemes.color(view.context,com.radiosport.ninegradio.R.attr.radioAccent)
        val neutral=ColorUtils.setAlphaComponent(UiThemes.color(view.context,R.attr.radioText),97)
        when(view) {
            is SeekBar -> {
                view.thumbTintList=ContextCompat.getColorStateList(view.context,R.color.radio_slider_thumb)
                view.progressTintList=ContextCompat.getColorStateList(view.context,R.color.radio_slider_active)
                view.progressBackgroundTintList=ContextCompat.getColorStateList(view.context,R.color.radio_slider_inactive)
            }
            is Slider -> {
                if(view.id==R.id.springTunerInput) return // Bipolar rate control owns its centre-relative track.
                view.isTickVisible=view.stepSize>0 && (view.valueTo-view.valueFrom)/view.stepSize<=10
                val res=view.resources
                view.thumbWidth=res.getDimensionPixelSize(R.dimen.radio_slider_handle_width)
                view.thumbHeight=res.getDimensionPixelSize(R.dimen.radio_slider_handle_height)
                view.trackHeight=res.getDimensionPixelSize(R.dimen.radio_slider_track)
                view.thumbTrackGapSize=(4*res.displayMetrics.density).toInt()
                view.trackStopIndicatorSize=(2*res.displayMetrics.density).toInt()
                view.thumbTintList=ContextCompat.getColorStateList(view.context,R.color.radio_slider_thumb)!!
                view.trackActiveTintList=ContextCompat.getColorStateList(view.context,R.color.radio_slider_active)!!
                view.trackInactiveTintList=ContextCompat.getColorStateList(view.context,R.color.radio_slider_inactive)!!
                view.tickActiveTintList=view.trackInactiveTintList
                view.tickInactiveTintList=view.trackActiveTintList
                view.haloTintList=ColorStateList.valueOf((switchColor and 0x00ffffff) or 0x33000000)
            }
            is MaterialSwitch -> {
                view.thumbTintList=ContextCompat.getColorStateList(view.context,R.color.radio_switch_thumb)
                view.trackTintList=ContextCompat.getColorStateList(view.context,R.color.radio_switch_track)
                view.trackDecorationTintList=ContextCompat.getColorStateList(view.context,R.color.radio_switch_outline)
            }
            is SwitchCompat -> {
                view.thumbTintList=state(switchColor,UiThemes.color(view.context,R.attr.radioMuted),neutral)
                view.trackTintList=state((switchColor and 0x00ffffff) or 0x66000000,UiThemes.color(view.context,R.attr.radioSelected),neutral)
            }
            is Switch -> {
                view.thumbTintList=state(switchColor,UiThemes.color(view.context,R.attr.radioMuted),neutral)
                view.trackTintList=state((switchColor and 0x00ffffff) or 0x66000000,UiThemes.color(view.context,R.attr.radioSelected),neutral)
            }
        }
        if(view is ViewGroup) for(i in 0 until view.childCount) apply(view.getChildAt(i),group)
    }
}
