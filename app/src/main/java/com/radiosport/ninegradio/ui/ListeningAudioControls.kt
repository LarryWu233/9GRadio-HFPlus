package com.radiosport.ninegradio.ui

import android.content.Context
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.slider.Slider
import com.google.android.material.materialswitch.MaterialSwitch
import com.radiosport.ninegradio.R
import com.radiosport.ninegradio.dsp.ListeningAudioProcessor.Settings
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.dsp.NativeDsp
import com.radiosport.ninegradio.dsp.NoiseProcessingPolicy

class ListeningAudioControls(context: Context, initial: Settings, private val changed: (Settings)->Unit) : LinearLayout(context) {
    private var config=initial
    private var syncing=false
    private var notchAllowed=true
    val leveling=MaterialSwitch(context).apply { id=R.id.switchAudioLeveling; text="响度补偿（AGC）" }
    val notch=MaterialSwitch(context).apply { id=R.id.switchAudioNotch; text="自动陷波（抑制啸叫）" }
    private val limitLabel=TextView(context)
    val limit=Slider(context).apply { id=R.id.sliderAudioLevelLimit; valueFrom=3f; valueTo=18f; stepSize=3f; contentDescription="补偿上限" }
    private val speedLabel=TextView(context)
    val speed=Slider(context).apply { id=R.id.sliderAudioNotchSpeed; valueFrom=1f; valueTo=10f; stepSize=1f; contentDescription="陷波跟踪速度" }
    init {
        orientation=VERTICAL
        val gap=(8*resources.displayMetrics.density).toInt()
        setPadding(0,gap,0,gap)
        for(view in listOf(leveling,limitLabel,limit,notch,speedLabel,speed)) {
            if(view is TextView) { view.setTextColor(UiThemes.color(context,R.attr.radioSecondary)); view.textSize=14f }
            addView(view,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
        }
        leveling.setOnCheckedChangeListener { _,value -> if(!syncing) submit(config.copy(leveling=value)) }
        notch.setOnCheckedChangeListener { _,value -> if(!syncing) submit(config.copy(notch=value)) }
        limit.addOnChangeListener { _,value,user -> if(user) submit(config.copy(limitDb=value)) }
        speed.addOnChangeListener { _,value,user -> if(user) submit(config.copy(notchSpeed=value*0.001f)) }
        render(initial)
    }
    private fun submit(value: Settings) { render(value); changed(value) }
    fun setMode(mode: DemodMode) {
        notchAllowed=NoiseProcessingPolicy.allows(mode)
        notch.text=if(notchAllowed) "自动陷波（抑制啸叫）" else "自动陷波（当前模式旁路）"
        render(config)
    }
    fun render(value: Settings) {
        syncing=true; config=value
        leveling.isChecked=value.leveling && notchAllowed; notch.isChecked=value.notch && notchAllowed
        limit.value=(value.limitDb/3).toInt().coerceIn(1,6)*3f
        speed.value=(value.notchSpeed*1000).toInt().coerceIn(1,10).toFloat()
        val native=NativeDsp.isNativeAvailable()
        leveling.isEnabled=native && notchAllowed; notch.isEnabled=native && notchAllowed
        leveling.tooltipText=if(native) null else "原生音频模块未加载"
        notch.tooltipText=if(native) null else "原生音频模块未加载"
        limit.isEnabled=native && value.leveling && notchAllowed; speed.isEnabled=native && value.notch && notchAllowed
        limitLabel.text="补偿上限　±${limit.value.toInt()} dB"
        speedLabel.text="陷波跟踪　${speed.value.toInt()}"
        syncing=false
    }
}
