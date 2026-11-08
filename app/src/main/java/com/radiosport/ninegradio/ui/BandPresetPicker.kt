package com.radiosport.ninegradio.ui

import android.content.Context
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
        val density=context.resources.displayMetrics.density
        val box=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding((16*density).toInt(),0,(16*density).toInt(),0) }
        val picker=com.google.android.material.chip.ChipGroup(context).apply {
            isSingleLine=false;isSingleSelection=true;isSelectionRequired=true
            chipSpacingHorizontal=(8*density).toInt();chipSpacingVertical=(4*density).toInt()
        }
        box.addView(picker,LinearLayout.LayoutParams(-1,-2))
        val list = ListView(context)
        box.addView(list, LinearLayout.LayoutParams(-1, (300 * context.resources.displayMetrics.density).toInt()))
        var rows = FrequencyStepManager.BAND_PRESETS
        val adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1) {
            override fun isEnabled(position: Int): Boolean = caps.scanRanges(rows[position].startHz, rows[position].stopHz).isNotEmpty()
            override fun areAllItemsEnabled() = false
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also { view ->
                    (view as TextView).setTextColor(UiThemes.color(context,if (isEnabled(position)) com.radiosport.ninegradio.R.attr.radioText else com.radiosport.ninegradio.R.attr.radioMuted))
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
        for(label in listOf("全部","广播","业余","航空","其他")) {
            picker.addView(RadioChoiceChips.create(context,label,label=="全部").apply { id=View.generateViewId();tag=label })
        }
        picker.setOnCheckedStateChangeListener { group,ids ->
            ids.firstOrNull()?.let { id -> render(group.findViewById<View>(id).tag as String) }
        }
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
