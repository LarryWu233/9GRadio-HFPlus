package com.radiosport.ninegradio.ui

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.radiosport.ninegradio.dsp.FrequencyStepManager
import com.radiosport.ninegradio.source.DeviceCapabilities

object BandPresetPicker {
    private fun category(name: String) = when {
        name.contains("Broadcast") || name.contains("Shortwave") -> "广播"
        name.contains("Ham") || name.contains("Amateur") || name.contains("APRS") -> "业余"
        name.contains("Air") || name in listOf("ACARS", "ADS-B") -> "航空"
        else -> "其他"
    }
    fun show(context: Context, caps: DeviceCapabilities, current: Long,
        selected: (FrequencyStepManager.BandPreset, Long) -> Unit) {
        val prefs = context.getSharedPreferences("band_presets", Context.MODE_PRIVATE)
        FrequencyStepManager.BAND_PRESETS.filter { current in it.startHz..it.stopHz && caps.supportsFrequency(current) }
            .forEach { prefs.edit().putLong(it.name, current).apply() }
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val picker = SegmentedSelectorView(context)
        box.addView(picker, LinearLayout.LayoutParams(-1, (48 * context.resources.displayMetrics.density).toInt()))
        val list = ListView(context)
        box.addView(list, LinearLayout.LayoutParams(-1, (340 * context.resources.displayMetrics.density).toInt()))
        var rows = FrequencyStepManager.BAND_PRESETS
        val adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1) {
            override fun isEnabled(position: Int): Boolean = caps.scanRanges(rows[position].startHz, rows[position].stopHz).isNotEmpty()
            override fun areAllItemsEnabled() = false
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also { view ->
                    (view as TextView).setTextColor(if (isEnabled(position)) 0xffb8d0df.toInt() else 0xff55616a.toInt())
                    view.textSize = 14f
                }
        }
        list.adapter = adapter
        fun render(group: String) {
            rows = FrequencyStepManager.BAND_PRESETS.filter { group == "全部" || category(it.name) == group }
            adapter.clear()
            adapter.addAll(rows.map { preset ->
                val range = "${"%.3f".format(preset.startHz/1e6)}–${"%.3f".format(preset.stopHz/1e6)} MHz"
                "${preset.name}\n$range${if (caps.scanRanges(preset.startHz,preset.stopHz).isEmpty()) " · 不支持" else ""}"
            })
        }
        picker.setChoices(listOf("全部","广播","业余","航空","其他").map { it to it }, "全部")
        picker.onSelected = { render(it) }
        render("全部")
        val dialog = AlertDialog.Builder(context).setTitle("频段预设").setView(box).setNegativeButton("关闭", null).create()
        list.setOnItemClickListener { _, _, index, _ ->
            val preset = rows[index]
            val ranges = caps.scanRanges(preset.startHz, preset.stopHz)
            if (ranges.isEmpty()) return@setOnItemClickListener
            val saved = prefs.getLong(preset.name, ranges.first().first)
            val target = if (ranges.any { saved in it }) saved else ranges.first().first
            selected(preset, target); dialog.dismiss()
        }
        dialog.show()
    }
}
