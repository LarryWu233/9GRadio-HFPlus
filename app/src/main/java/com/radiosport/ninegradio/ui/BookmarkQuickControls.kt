package com.radiosport.ninegradio.ui

import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import com.radiosport.ninegradio.R
import com.radiosport.ninegradio.data.Bookmark
import com.radiosport.ninegradio.toExactMhzString
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.math.BigDecimal

/** Shortcuts over the existing bookmark store, with no second collection or migration. */
object BookmarkQuickControls {
    fun showEditor(activity:AppCompatActivity,model:MainViewModel) {
        val view=activity.layoutInflater.inflate(R.layout.dialog_quick_bookmark,null)
        val name=view.findViewById<EditText>(R.id.etQuickBookmarkName)
        val frequency=view.findViewById<EditText>(R.id.etQuickBookmarkFrequency)
        val frequencyInput=view.findViewById<TextInputLayout>(R.id.quickBookmarkFrequencyInput)
        frequency.setText(model.centerFreqHz.value.toExactMhzString())
        val mode=model.demodMode.value.name
        val dialog=AlertDialog.Builder(activity).setTitle("添加收藏").setView(view)
            .setPositiveButton("保存",null).setNegativeButton("取消",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val hz=runCatching { BigDecimal(frequency.text.toString().trim()).movePointRight(6).longValueExact() }.getOrNull()
                if(hz==null || hz<=0) { frequencyInput.error="请输入有效频率，精确到 Hz";return@setOnClickListener }
                frequencyInput.error=null
                val label=name.text.toString().trim().ifEmpty { "${hz.toExactMhzString()} MHz" }
                model.addBookmark(hz,label,demodMode=mode)
                dialog.dismiss()
                Toast.makeText(activity,"已加入我的收藏",Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    fun showPicker(activity:AppCompatActivity,model:MainViewModel,onSelected:(Long)->Unit) {
        val density=activity.resources.displayMetrics.density
        val box=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding((16*density).toInt(),0,(16*density).toInt(),0) }
        val search=androidx.appcompat.widget.AppCompatEditText(activity).apply {
            id=R.id.etBookmarkPickerSearch;hint="搜索收藏…";isSingleLine=true;textSize=14f
            setTextColor(UiThemes.color(context,R.attr.radioText));setHintTextColor(UiThemes.color(context,R.attr.radioSecondary))
        }
        box.addView(search,LinearLayout.LayoutParams(-1,(48*density).toInt()))
        val list=ListView(activity).apply { id=R.id.bookmarkPickerList }
        val empty=TextView(activity).apply { text="暂无匹配收藏";setPadding(0,(16*density).toInt(),0,(16*density).toInt());setRadioTextColor(R.attr.radioSecondary) }
        var all=emptyList<Bookmark>();var rows=emptyList<Bookmark>()
        val adapter=object:ArrayAdapter<String>(activity,android.R.layout.simple_list_item_1) {
            override fun isEnabled(position:Int)=model.capabilities.value.supportsFrequency(rows[position].frequencyHz)
            override fun areAllItemsEnabled()=false
            override fun getView(position:Int,convertView:View?,parent:ViewGroup):View=
                super.getView(position,convertView,parent).also { (it as TextView).apply {
                    textSize=14f;setTextColor(UiThemes.color(context,if(isEnabled(position)) R.attr.radioText else R.attr.radioMuted))
                } }
        }
        list.adapter=adapter
        fun render() {
            val query=search.text.toString().trim()
            rows=all.filter { query.isEmpty() || it.label.contains(query,true) || it.notes.contains(query,true) || it.frequencyHz.toExactMhzString().contains(query) }
            adapter.clear();adapter.addAll(rows.map { "${it.label}\n${it.frequencyHz.toExactMhzString()} MHz  ${it.demodMode}${if(model.capabilities.value.supportsFrequency(it.frequencyHz)) "" else " · 当前接收源不支持"}" })
            empty.visibility=if(rows.isEmpty()) View.VISIBLE else View.GONE
        }
        box.addView(empty)
        box.addView(list,LinearLayout.LayoutParams(-1,(300*density).toInt()))
        val dialog=AlertDialog.Builder(activity).setTitle("我的收藏").setView(box)
            .setNegativeButton("关闭",null).create()
        list.setOnItemClickListener { _,_,position,_ ->
            rows.getOrNull(position)?.takeIf { model.capabilities.value.supportsFrequency(it.frequencyHz) }?.let { onSelected(it.frequencyHz);dialog.dismiss() }
        }
        search.doAfterTextChanged { render() }
        dialog.show()
        val job=activity.lifecycleScope.launch { model.getBookmarksFiltered("",false,-1).collect { all=it;render() } }
        dialog.setOnDismissListener { job.cancel() }
    }
}
