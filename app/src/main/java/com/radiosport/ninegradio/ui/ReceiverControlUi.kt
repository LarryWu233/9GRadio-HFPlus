package com.radiosport.ninegradio.ui

import android.view.View
import android.widget.TextView
import com.radiosport.ninegradio.R
import com.radiosport.ninegradio.source.DeviceCapabilities

/** Presentation follows the selected source, including when switching back to RTL. */
object ReceiverControlUi {
    fun apply(root: View, caps: DeviceCapabilities) {
        fun show(id: Int, visible: Boolean) { root.findViewById<View>(id)?.visibility = if (visible) View.VISIBLE else View.GONE }
        val liveHardware = caps.id != "file"
        val hf = caps.id == "airspyhf"
        show(R.id.rowReceiverGain, liveHardware)
        show(R.id.tvReceiverGainHint, liveHardware)
        show(R.id.rowReceiverAgc, liveHardware)
        show(R.id.rowRtlAgc, caps.hardwareAgc)
        show(R.id.rowBiasTee, caps.biasTee)
        show(R.id.rowDirectSampling, caps.directSampling)
        show(R.id.tvReceiverHardwareTitle, caps.biasTee || caps.directSampling)
        (root.findViewById<View>(R.id.sliderPpm)?.parent as? View)?.visibility = if (liveHardware) View.VISIBLE else View.GONE
        root.findViewById<TextView>(R.id.tvReceiverGainTitle)?.apply {
            text = if (hf) context.getString(R.string.receiver_attenuation) else "增益"
        }
        root.findViewById<TextView>(R.id.tvReceiverGainHint)?.apply {
            text = if (hf) context.getString(R.string.receiver_attenuation_hint) else "按接收机支持的档位调整射频增益"
        }
        root.findViewById<TextView>(R.id.tvReceiverAgcTitle)?.apply {
            text = if (hf) context.getString(R.string.receiver_hf_agc) else "射频 AGC"
        }
        root.findViewById<TextView>(R.id.tvReceiverAgcHint)?.apply {
            text = if (hf) context.getString(R.string.receiver_hf_agc_hint) else "自动控制射频增益；关闭后可手动调节"
        }
    }
}
