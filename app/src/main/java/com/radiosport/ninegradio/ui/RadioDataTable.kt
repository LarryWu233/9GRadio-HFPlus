package com.radiosport.ninegradio.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Shared compact table: one scroll surface keeps all headers aligned with their rows. */
open class RadioDataTable @JvmOverloads constructor(context: Context,attrs: AttributeSet?=null): HorizontalScrollView(context,attrs) {
    data class Column(val title: String,val widthDp: Int)
    data class Row(val values: List<String>,val active: Boolean=false)
    var onRowClick: ((Int)->Unit)?=null
    var onRowLongClick: ((Int)->Boolean)?=null
    private var columns=emptyList<Column>()
    private var rows=emptyList<Row>()
    private val body=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
    private val header=LinearLayout(context)
    private fun dp(value: Int)=(value*resources.displayMetrics.density+0.5f).toInt()
    private fun columnWidth(column: Column)=(dp(column.widthDp)*resources.configuration.fontScale.coerceAtLeast(1f)).toInt()
    private fun cell(column: Column,isHeader: Boolean)=TextView(context).apply {
        layoutParams=LinearLayout.LayoutParams(columnWidth(column),-1,1f)
        gravity=Gravity.CENTER; setSingleLine(true); setHorizontallyScrolling(false); ellipsize=TextUtils.TruncateAt.END
        textSize=if(isHeader) 10f else 11f
        if(!isHeader) typeface=Typeface.MONOSPACE
        setTextColor(if(isHeader) UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioSecondary) else UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioText))
        setPadding(dp(4),0,dp(4),0)
        background=GradientDrawable().apply {
            setColor(if(isHeader) UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioElevated) else 0)
            setStroke((resources.displayMetrics.density*0.5f).toInt().coerceAtLeast(1),UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioOutline))
        }
    }
    private val rowAdapter=object: BaseAdapter() {
        override fun getCount()=rows.size
        override fun getItem(position: Int)=rows[position]
        override fun getItemId(position: Int)=position.toLong()
        override fun getView(position: Int,convertView: View?,parent: ViewGroup): View {
            val line=(convertView as? LinearLayout)?.takeIf { it.childCount==columns.size } ?: LinearLayout(context).apply {
                layoutParams=AbsListView.LayoutParams(-1,dp(36))
                columns.forEach { addView(cell(it,false)) }
            }
            val row=rows[position]
            line.setBackgroundColor(if(row.active) UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioSelected) else if(position%2==0) UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioSurface) else UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioCard))
            columns.indices.forEach { (line.getChildAt(it) as TextView).text=row.values.getOrElse(it) { "—" } }
            line.contentDescription=columns.mapIndexed { i,col -> "${col.title} ${row.values.getOrElse(i) { "—" }}" }.joinToString("，")
            return line
        }
    }
    private val list=NestedResultListView(context).apply {
        adapter=rowAdapter; divider=null; dividerHeight=0
        setOnItemClickListener { _,_,position,_ -> onRowClick?.invoke(position) }
        setOnItemLongClickListener { _,_,position,_ -> onRowLongClick?.invoke(position) ?: false }
    }
    init {
        isFillViewport=true; isHorizontalScrollBarEnabled=true
        body.addView(header,LinearLayout.LayoutParams(-1,dp(30)))
        body.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        addView(body,LayoutParams(-1,-1))
    }
    fun setColumns(value: List<Column>) {
        columns=value.toList(); header.removeAllViews()
        columns.forEach { col -> header.addView(cell(col,true).apply { text=col.title }) }
        body.layoutParams=LayoutParams(columns.sumOf { columnWidth(it) },-1)
        rows=emptyList(); rowAdapter.notifyDataSetChanged()
    }
    fun submitRows(value: List<Row>) { rows=value.toList(); rowAdapter.notifyDataSetChanged() }
}
