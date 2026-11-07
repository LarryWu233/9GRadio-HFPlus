package com.radiosport.ninegradio.ui

import android.content.Context
import android.widget.*
import androidx.appcompat.app.AlertDialog

/** Stable module IDs are independent from their visible positions. */
class ModulePreferences(context: Context) {
    companion object { val managed = (0..4).toList() + listOf(ControlsPagerAdapter.TAB_SCAN,ControlsPagerAdapter.TAB_RDF,15,16,17,18) }
    private val prefs = context.getSharedPreferences("screen_modules", Context.MODE_PRIVATE)
    val coreOrder: List<Int> get() {
        val saved = prefs.getString("order", "0,1,2,3,4,5")!!.split(',').mapNotNull { it.toIntOrNull() }
        return (saved.filter { it in managed } + managed).distinct()
    }
    val order get() = coreOrder + (6 until ControlsPagerAdapter.TAB_COUNT).filter { it !in managed }
    fun visible(id: Int) = id == ControlsPagerAdapter.TAB_SETTINGS ||
        !prefs.getStringSet("hidden", emptySet())!!.contains(id.toString())

    fun managedVisibility() = managed.joinToString(",") { "$it:${visible(it)}" }

    fun showEditor(context: Context, applied: () -> Unit) {
        val order = coreOrder.filter { it!=ControlsPagerAdapter.TAB_SETTINGS }.toMutableList()
        val hidden = managed.filter { !visible(it) }.toMutableSet()
        val labels = mapOf(0 to "调谐", 1 to "接收模式", 2 to "接收机与音频", 3 to "频谱与瀑布", 4 to "录音 / IQ", 5 to "设备与设置", 12 to "扫描 / ETM",13 to "测向仪",15 to "CW / CWR",16 to "SSTV",17 to "SAT",18 to "EiBi 节目表")
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun render() {
            rows.removeAllViews()
            for ((position, id) in order.withIndex()) {
                rows.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(CheckBox(context).apply {
                        text = labels[id]
                        isChecked = id !in hidden
                        isEnabled = id != ControlsPagerAdapter.TAB_SETTINGS
                        setOnCheckedChangeListener { _, checked -> if (checked) hidden.remove(id) else hidden.add(id) }
                    }, LinearLayout.LayoutParams(0, (56 * context.resources.displayMetrics.density).toInt(), 1f))
                    for ((text, delta) in listOf("↑" to -1, "↓" to 1)) addView(Button(context).apply {
                        this.text = text
                        contentDescription = "${labels[id]} ${if (delta < 0) "上移" else "下移"}"
                        isEnabled = position + delta in order.indices
                        setOnClickListener {
                            val other = position + delta
                            val swap = order[other]; order[other] = id; order[position] = swap
                            render()
                        }
                    }, LinearLayout.LayoutParams((48 * context.resources.displayMetrics.density).toInt(), (56 * context.resources.displayMetrics.density).toInt()))
                })
            }
        }
        render()
        AlertDialog.Builder(context).setTitle("菜单模块 · 排序与显示")
            .setView(ScrollView(context).apply { addView(rows) })
            .setPositiveButton("应用") { _, _ ->
                prefs.edit().putString("order", order.joinToString(","))
                    .putStringSet("hidden", hidden.map { it.toString() }.toSet()).apply()
                applied()
            }.setNeutralButton("恢复默认") { _, _ -> prefs.edit().clear().apply(); applied() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
}
