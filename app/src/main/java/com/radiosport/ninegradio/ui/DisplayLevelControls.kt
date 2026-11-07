package com.radiosport.ninegradio.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.PreferenceManager
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.radiosport.ninegradio.R

/** One inline editor for both plots; preferences remain the shared source of truth. */
class DisplayLevelControls(context: Context,private val waterfall: WaterfallView,
    private val spectrum: SpectrumView,private val spectrumChanged: (Int,Int,Boolean)->Unit): LinearLayout(context) {
    private val prefs=PreferenceManager.getDefaultSharedPreferences(context)
    private var target=prefs.getString("display_level_target","spectrum")?.takeIf { it in setOf("spectrum","waterfall") } ?: "spectrum"
    private var syncing=false
    private var low=-120; private var high=0
    private val selector=SegmentedSelectorView(context).apply { id=R.id.levelTargetSelector }
    private val auto=SwitchMaterial(context).apply { id=R.id.switchInlineAutoLevels; text="自动 dB 范围"; textSize=12f }
    private val lowSlider=Slider(context).apply { id=R.id.sliderInlineMinLevel; contentDescription="电平下限" }
    private val highSlider=Slider(context).apply { id=R.id.sliderInlineMaxLevel; contentDescription="电平上限" }
    private val lowText=TextView(context)
    private val highText=TextView(context)
    private val keys=setOf("pref_db_min","pref_db_max","pref_auto_range","waterfall_min_db","waterfall_max_db",
        "waterfall_manual_levels","pref_waterfall_auto_stretch")
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
        if(key in keys && !syncing) post { refresh() }
    }
    private fun dp(value: Int)=(value*resources.displayMetrics.density).toInt()
    init {
        orientation=VERTICAL
        selector.setChoices(listOf("spectrum" to "频谱","waterfall" to "瀑布图"),target)
        addView(selector,LayoutParams(-1,dp(44)))
        addView(auto,LayoutParams(-1,dp(44)))
        fun row(title: String,slider: Slider,value: TextView) {
            val line=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
            line.addView(TextView(context).apply { text=title; textSize=12f; setTextColor(UiThemes.color(context,R.attr.radioSecondary)) },LayoutParams(dp(40),-2))
            slider.stepSize=1f; slider.thumbTintList=ColorStateList.valueOf(0xff00ccee.toInt())
            slider.trackActiveTintList=ColorStateList.valueOf(0xff00ccee.toInt())
            slider.setLabelFormatter { "${it.toInt()} dBFS" }
            line.addView(slider,LayoutParams(0,-1,1f))
            value.apply { textSize=11f; gravity=Gravity.END; typeface=android.graphics.Typeface.MONOSPACE; setTextColor(UiThemes.color(context,R.attr.radioSecondary)) }
            line.addView(value,LayoutParams(dp(86),-2))
            addView(line,LayoutParams(-1,dp(44)))
        }
        row("下限",lowSlider,lowText); row("上限",highSlider,highText)
        refresh()
        lowSlider.addOnChangeListener { _,value,user -> if(user && !syncing) {
            low=value.toInt().coerceAtMost(high-6); lowSlider.value=low.toFloat(); applyLevels(false)
        } }
        highSlider.addOnChangeListener { _,value,user -> if(user && !syncing) {
            high=value.toInt().coerceAtLeast(low+6); highSlider.value=high.toFloat(); applyLevels(false)
        } }
        val touch=object: Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) { applyLevels(true) }
        }
        lowSlider.addOnSliderTouchListener(touch); highSlider.addOnSliderTouchListener(touch)
        auto.setOnCheckedChangeListener { _,_ -> if(!syncing) applyLevels(true) }
        selector.onSelected={ key -> target=key; prefs.edit().putString("display_level_target",key).apply(); refresh() }
        ControlColors.apply(this,ControlColors.Group.DISPLAY)
    }
    private fun applyLevels(save: Boolean) {
        lowText.text="$low dBFS"; highText.text="$high dBFS"
        lowSlider.isEnabled=!auto.isChecked; highSlider.isEnabled=!auto.isChecked
        lowSlider.alpha=if(auto.isChecked) 0.45f else 1f; highSlider.alpha=lowSlider.alpha
        if(target=="spectrum") {
            spectrum.setAutoRange(auto.isChecked)
            if(!auto.isChecked) spectrum.setDbRange(low.toFloat(),high.toFloat())
            if(save) {
                prefs.edit().putInt("pref_db_min",-low).putInt("pref_db_max",high).putBoolean("pref_auto_range",auto.isChecked).apply()
                spectrumChanged(low,high,auto.isChecked)
            }
        } else {
            waterfall.setManualLevels(if(auto.isChecked) null else low.toFloat(),high.toFloat())
            waterfall.setAutoStretch(auto.isChecked)
            if(save) prefs.edit().putInt("waterfall_min_db",low).putInt("waterfall_max_db",high)
                .putBoolean("waterfall_manual_levels",!auto.isChecked).putBoolean("pref_waterfall_auto_stretch",auto.isChecked).apply()
        }
    }
    fun refresh() {
        syncing=true
        try {
            val isSpectrum=target=="spectrum"
            low=(if(isSpectrum) -prefs.getInt("pref_db_min",120) else prefs.getInt("waterfall_min_db",-prefs.getInt("pref_db_min",120))).coerceIn(-200,-10)
            high=(if(isSpectrum) prefs.getInt("pref_db_max",0) else prefs.getInt("waterfall_max_db",prefs.getInt("pref_db_max",0)))
                .coerceIn(maxOf(low+6,if(isSpectrum) -100 else -194),if(isSpectrum) 50 else 0)
            lowSlider.valueFrom=-200f; lowSlider.valueTo=-10f; lowSlider.value=low.toFloat()
            highSlider.valueFrom=if(isSpectrum) -100f else -194f
            highSlider.valueTo=if(isSpectrum) 50f else 0f; highSlider.value=high.toFloat()
            auto.isChecked=if(isSpectrum) prefs.getBoolean("pref_auto_range",false)
                else prefs.getBoolean("pref_waterfall_auto_stretch",false)
            selector.select(target); applyLevels(false)
        } finally { syncing=false }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); prefs.registerOnSharedPreferenceChangeListener(listener); refresh() }
    override fun onDetachedFromWindow() { prefs.unregisterOnSharedPreferenceChangeListener(listener); super.onDetachedFromWindow() }
}
