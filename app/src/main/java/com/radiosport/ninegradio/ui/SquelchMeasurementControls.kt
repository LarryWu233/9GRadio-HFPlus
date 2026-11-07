package com.radiosport.ninegradio.ui

import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.radiosport.ninegradio.R
import com.radiosport.ninegradio.dsp.SquelchRecommendation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale

/** One-shot measurement of the existing detector; never retunes or silently changes SQL. */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class SquelchMeasurementControls(
    private val activity: MainActivity, private val model: MainViewModel,
    private val button: Button, private val label: TextView, private val apply: (Float)->Unit
) : DefaultLifecycleObserver {
    private var job: Job? = null
    private var dialog: AlertDialog? = null
    private fun signature() = listOf(model.getService()?.dspEngine,model.getService()?.source,
        model.centerFreqHz.value,model.demodMode.value,model.ifBandwidthHz.value,model.sampleRate.value,
        model.decimation.value,model.fftSize.value,model.gainIndex.value,model.tunerAgcEnabled.value,
        model.hardwareAgcEnabled.value,model.ppm.value,model.getService()?.dspEngine?.fftEngine?.analysisSettings())
    private fun message(value: String) = Toast.makeText(activity,value,Toast.LENGTH_LONG).show()

    init {
        activity.lifecycle.addObserver(this)
        button.setOnClickListener { measure() }
        activity.lifecycleScope.launch {
            activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(model.receiverEnabled,model.deviceStatus,model.dspStats) { enabled,status,stats ->
                    Triple(enabled && status.connected,stats.signalDb,stats.squelchOpen)
                }.sample(150).collect { (ready,level,open) ->
                    label.text=if(ready && level > -190f) String.format(Locale.ROOT,"通带 %.1f dBFS · %s",level,if(open) "通过" else "静噪") else "通带 — dBFS"
                    button.isEnabled=ready && job?.isActive!=true
                    if(!ready) { job?.cancel(); dialog?.dismiss() }
                }
            }
        }
    }
    private fun measure() {
        if(job?.isActive==true) return
        val engine=model.getService()?.dspEngine ?: return message("请先接通接收源")
        if(!model.receiverEnabled.value || !model.deviceStatus.value.connected) return message("请先接通接收源")
        if(!model.capabilities.value.supportsFrequency(model.centerFreqHz.value)) return message("当前频率不可接收")
        if(!engine.squelchReading.value.valid) return message("等待新的频谱数据后重试")
        if(!engine.squelchReading.value.complete) return message("SPAN 未覆盖完整 BW，无法建议静噪值")
        val key=signature()
        val configuration=engine.squelchReading.value.configuration
        val firstSequence=engine.squelchReading.value.sequence
        val levels=ArrayList<Float>(192)
        var previous=firstSequence
        var firstTime=0L
        var lastTime=0L
        button.text="测量中…"; button.isEnabled=false
        job=activity.lifecycleScope.launch {
            try {
                var changed=false
                withTimeoutOrNull(3200) {
                    engine.squelchReading.takeWhile { reading ->
                        val same=signature()==key && reading.configuration==configuration && model.receiverEnabled.value && model.deviceStatus.value.connected
                        if(!same) changed=true
                        same
                    }.collect { reading ->
                        if(reading.sequence>previous && reading.valid && reading.complete) {
                            previous=reading.sequence
                            if(firstTime==0L) firstTime=reading.timestampNanos
                            lastTime=reading.timestampNanos
                            levels.add(reading.levelDb)
                        }
                    }
                }
                if(changed || signature()!=key) return@launch message("接收条件已改变，请重新测量")
                val result=SquelchRecommendation.from(levels)
                if(result==null || lastTime-firstTime<1_000_000_000L || System.nanoTime()-lastTime>700_000_000L)
                    return@launch message("有效数据不足或电平过高，未生成建议")
                if(!button.isShown) return@launch
                val variation=String.format(Locale.ROOT,"%.1f",result.variationDb)
                val detail="建议 ${result.thresholdDb.toInt()} dBFS\n通带参考 ${String.format(Locale.ROOT,"%.1f",result.referenceDb)} dBFS\n余量 ≥3 dB · 波动 $variation dB\n有效数据 ${result.samples} 帧"+
                    if(result.variationDb>8f) "\n当前变化较大，建议可信度较低。" else ""
                dialog=AlertDialog.Builder(activity).setTitle("静噪测量（实验）")
                    .setMessage(detail+"\n持续信号或干扰也会被测入；此结果不保证是纯底噪。")
                    .setNegativeButton("取消",null).setPositiveButton("应用") { _,_ ->
                        val latest=engine.squelchReading.value
                        if(signature()==key && model.receiverEnabled.value && model.deviceStatus.value.connected &&
                            latest.valid && System.nanoTime()-latest.timestampNanos<700_000_000L) apply(result.thresholdDb)
                        else message("接收条件已改变，请重新测量")
                    }.show().also {
                        it.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(UiThemes.color(activity,R.attr.radioAccent))
                        it.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(UiThemes.color(activity,R.attr.radioSecondary))
                    }
            } finally { button.text="测量（实验）"; button.isEnabled=model.receiverEnabled.value && model.deviceStatus.value.connected }
        }
    }
    override fun onStop(owner: LifecycleOwner) { job?.cancel(); dialog?.dismiss() }
}
