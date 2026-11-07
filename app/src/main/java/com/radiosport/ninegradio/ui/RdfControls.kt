package com.radiosport.ninegradio.ui

import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.widget.*
import com.google.android.material.switchmaterial.SwitchMaterial

/** Receiver instrument panel. Direction/range stay unknown without a direction-finding source. */
class RdfControls(context: Context): LinearLayout(context) {
    private val level=TextView(context)
    private val state=TextView(context)
    private val frequency=TextView(context)
    private val location=TextView(context)
    private val compass=ReceiverCompassView(context)
    private var live=true
    private fun dp(v: Int)=(v*resources.displayMetrics.density).toInt()
    init {
        orientation=VERTICAL; setPadding(dp(16),dp(12),dp(16),dp(16))
        fun card(label: String,value: TextView)=LinearLayout(context).apply {
            orientation=VERTICAL; setPadding(dp(12),dp(12),dp(12),dp(12))
            setBackgroundColor(0xff0d1822.toInt())
            addView(TextView(context).apply { text=label; textSize=13f; setTextColor(0xffa9bdc8.toInt()) })
            value.apply { text="—"; textSize=25f; setTextColor(0xff83b8b7.toInt()); typeface=android.graphics.Typeface.MONOSPACE }
            value.setSingleLine(true)
            value.setAutoSizeTextTypeUniformWithConfiguration(16,25,1,android.util.TypedValue.COMPLEX_UNIT_SP)
            addView(value)
        }
        fun row(left: android.view.View,right: android.view.View) {
            addView(LinearLayout(context).apply {
                addView(left,LayoutParams(0,-2,1f).apply { setMargins(0,0,dp(4),0) })
                addView(right,LayoutParams(0,-2,1f).apply { setMargins(dp(4),0,0,0) })
            },LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
        }
        addView(compass,LayoutParams(-1,dp(280)))
        row(card("输入电平",level),card("接收状态",state))
        addView(frequency.apply { textSize=16f; typeface=android.graphics.Typeface.MONOSPACE; setTextColor(0xffdbe5eb.toInt()) })
        addView(SwitchMaterial(context).apply {
            text="实时电平"; isChecked=true
            setOnCheckedChangeListener { _,on -> live=on }
        },LayoutParams(-1,dp(48)))
        addView(location.apply { textSize=13f; setTextColor(0xffa9bdc8.toInt()) })
        addView(Button(context).apply {
            text="接收位置"; gravity=Gravity.CENTER
            setOnClickListener { context.startActivity(Intent(context,RadioMapActivity::class.java).putExtra("editReceiver",true)) }
        },LayoutParams(-1,dp(48)))
        ControlColors.apply(this,ControlColors.Group.RADIO)
    }
    fun setActive(active: Boolean) { compass.setActive(active) }
    fun render(db: Float,connected: Boolean,hz: Long) {
        if(live || !connected) {
            level.text=if(connected && db.isFinite()) "%.1f dBFS".format(db) else "—"
            state.text=if(connected) "接收中" else "已关闭"
            frequency.text="当前频率　${"%.6f".format(hz/1e6)} MHz"
        }
        val prefs=context.getSharedPreferences("radio_map",Context.MODE_PRIVATE)
        location.text=if(prefs.contains("receiverLat")) "接收位置　${prefs.getFloat("receiverLat",0f)}, ${prefs.getFloat("receiverLon",0f)}" else "接收位置未设置"
    }
}
