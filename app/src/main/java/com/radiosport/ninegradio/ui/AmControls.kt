package com.radiosport.ninegradio.ui

import android.content.Context
import android.view.HapticFeedbackConstants
import android.widget.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.radiosport.ninegradio.R
import com.radiosport.ninegradio.dsp.*
import java.util.Locale

class AmDetectorControls(context:Context,private val change:(AmDetector)->Unit):LinearLayout(context) {
    private val status=TextView(context)
    private val buttons=AmDetector.values().map { mode -> ModeChoiceUi.button(context).apply {
        text=mode.label;tag=mode.name;contentDescription=mode.label
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);change(mode) }
    } }
    init {
        orientation=VERTICAL
        addView(TextView(context).apply { text="AM 检波";textSize=12f;setTextColor(UiThemes.color(context,R.attr.radioAccent));setPadding(0,8,0,8) })
        addView(GridLayout(context).apply { columnCount=2;buttons.forEachIndexed { index,b -> addView(b,ModeChoiceUi.cell(context,index,2)) } })
        status.textSize=12f;status.setTextColor(UiThemes.color(context,R.attr.radioSecondary));addView(status)
    }
    fun render(settings:AmSettings,state:AmStatus,connected:Boolean) {
        buttons.forEachIndexed { i,b -> b.isSelected=AmDetector.values()[i]==settings.detector;b.isEnabled=NativeDsp.isNativeAvailable() }
        status.visibility=if(settings.detector==AmDetector.ENVELOPE) GONE else VISIBLE
        status.text=if(connected) "${state.label}　${String.format(Locale.ROOT,"%+.0f Hz",state.offsetHz)}" else "未连接"
        status.setTextColor(if(connected && state.state==2) 0xff66c58a.toInt() else UiThemes.color(context,R.attr.radioSecondary))
    }
}

class AmAudioControls(context:Context,private val change:(AmSettings)->Unit):LinearLayout(context) {
    private var settings=AmSettings()
    private var syncing=false
    private var currentStatus=AmStatus()
    private var limiterLabel:TextView?=null
    private val label=TextView(context)
    private val auto=MaterialSwitch(context).apply { text="Auto";contentDescription="自动音频带宽" }
    private val width=Slider(context).apply { valueFrom=100f;valueTo=20000f;stepSize=100f;contentDescription="AM 音频带宽" }
    private val agcButtons=AmAgc.values().mapIndexed { index,mode -> ModeChoiceUi.button(context).apply {
        text=listOf("关闭","慢","快")[index];tag="AM_AGC_${mode.name}"
        setOnClickListener { change(settings.copy(agc=mode)) }
    } }
    init {
        orientation=VERTICAL
        val gap=resources.getDimensionPixelSize(R.dimen.radio_space_sm)
        setPadding(0,gap,0,gap)
        val row=LinearLayout(context).apply { gravity=android.view.Gravity.CENTER_VERTICAL }
        label.textSize=14f;label.setTextColor(UiThemes.color(context,R.attr.radioText))
        row.addView(label,LayoutParams(0,-2,1f));row.addView(auto);addView(row);addView(width)
        addView(TextView(context).apply { text="AM AGC";textSize=12f;setTextColor(UiThemes.color(context,R.attr.radioSecondary)) })
        addView(GridLayout(context).apply { columnCount=3;agcButtons.forEachIndexed { i,b -> addView(b,ModeChoiceUi.cell(context,i)) } })
        addView(ModeChoiceUi.button(context).apply { text="AM 高级设置";setOnClickListener { advanced() } },
            LayoutParams(LayoutParams.MATCH_PARENT,(48*resources.displayMetrics.density).toInt()).apply { topMargin=gap;bottomMargin=gap })
        auto.setOnCheckedChangeListener { _,on -> if(!syncing) change(settings.copy(audioAuto=on)) }
        width.addOnChangeListener { _,value,user -> if(user && !syncing) change(settings.copy(audioHz=value.toInt())) }
    }
    fun render(value:AmSettings,rfWidth:Int,state:AmStatus) {
        syncing=true;settings=value;currentStatus=state
        val rf=rfWidth.takeIf { it>0 } ?: if(value.detector.sideband==0) 10000 else 5000
        val max=value.copy(audioAuto=true).cutoffHz(rf).toFloat()
        width.value=100f; width.valueTo=max
        width.value=(if(value.audioAuto) max else value.audioHz.toFloat().coerceAtMost(max)).coerceAtLeast(100f)
        width.isEnabled=!value.audioAuto && NativeDsp.isNativeAvailable();auto.isChecked=value.audioAuto
        label.text="音频带宽　${String.format(Locale.ROOT,"%.2f kHz",width.value/1000)}"
        agcButtons.forEachIndexed { i,b -> b.isSelected=AmAgc.values()[i]==value.agc }
        limiterLabel?.text=limiterText()
        syncing=false
    }
    fun updateStatus(state:AmStatus) { currentStatus=state;limiterLabel?.text=limiterText() }
    private fun limiterText()="保护限幅　${String.format(Locale.ROOT,"%.2f%%",currentStatus.limitingPercent)}"
    private fun advanced() {
        val column=LinearLayout(context).apply { orientation=VERTICAL;val p=resources.getDimensionPixelSize(R.dimen.radio_space_lg);setPadding(p,p,p,p) }
        fun text(value:String)=TextView(context).apply { text=value;textSize=14f;setTextColor(UiThemes.color(context,R.attr.radioText));column.addView(this) }
        text("载波保护　自动")
        val gain=text("最大增益　${settings.maxGainDb.toInt()} dB")
        column.addView(Slider(context).apply {
            valueFrom=0f;valueTo=60f;stepSize=1f;value=settings.maxGainDb;contentDescription="AM 最大增益"
            addOnChangeListener { _,v,user -> if(user) { gain.text="最大增益　${v.toInt()} dB";change(settings.copy(maxGainDb=v)) } }
        })
        val gate=text("增益保持门限　${settings.lowLevelDb.toInt()} dBFS")
        column.addView(Slider(context).apply {
            valueFrom=-150f;valueTo=-30f;stepSize=1f;value=settings.lowLevelDb;contentDescription="AM 增益保持门限"
            addOnChangeListener { _,v,user -> if(user) { gate.text="增益保持门限　${v.toInt()} dBFS";change(settings.copy(lowLevelDb=v)) } }
        })
        limiterLabel=text(limiterText())
        MaterialAlertDialogBuilder(context).setTitle("AM 高级设置").setView(column).setPositiveButton("关闭",null)
            .create().apply { setOnDismissListener { limiterLabel=null };show() }
    }
}
