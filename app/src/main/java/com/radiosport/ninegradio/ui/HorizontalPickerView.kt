package com.radiosport.ninegradio.ui

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView

/** Shared one-row selector. A drag previews; stopping or tapping commits once. */
class HorizontalPickerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : RecyclerView(context, attrs) {
    data class Choice(val key: String, val label: String, val enabled: Boolean = true)
    var onSelected: ((String) -> Unit)? = null
    private var choices = emptyList<Choice>()
    private var selected = -1
    private var dragging = false
    private var lastTick = -1
    private val haptics = TuningHaptics(this)
    private val cellWidth get() = (90 * resources.displayMetrics.density).toInt()
    private val manager = LinearLayoutManager(context, HORIZONTAL, false)
    private val snap = LinearSnapHelper()
    private val items = object : Adapter<Holder>() {
        override fun getItemCount() = choices.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(TextView(context).apply {
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(cellWidth, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = choices[position]
            holder.label.text = item.label
            holder.label.contentDescription = item.label
            holder.label.isEnabled = item.enabled
            holder.label.setTextColor(when { !item.enabled -> 0xff50606c.toInt(); position == selected -> 0xff00d9f3.toInt(); else -> 0xff9eb6c7.toInt() })
            holder.label.textSize = if (position == selected) 16f else 13f
            holder.label.setTypeface(null, if (position == selected) Typeface.BOLD else Typeface.NORMAL)
            holder.label.setBackgroundColor(if (position == selected) 0x1400ccee else android.graphics.Color.TRANSPARENT)
            holder.label.setOnClickListener { commit(position); smoothScrollToPosition(position) }
        }
    }
    private class Holder(val label: TextView) : ViewHolder(label)
    init {
        layoutManager = manager; adapter = items; snap.attachToRecyclerView(this)
        isNestedScrollingEnabled = false; clipToPadding = false; overScrollMode = OVER_SCROLL_NEVER
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrollStateChanged(view: RecyclerView, state: Int) {
                if (state == SCROLL_STATE_DRAGGING) { dragging = true; lastTick = selected }
                if (state == SCROLL_STATE_IDLE && dragging) {
                    post {
                        if (scrollState == SCROLL_STATE_IDLE && dragging) {
                            dragging = false
                            snap.findSnapView(manager)?.let { commit(manager.getPosition(it)) }
                        }
                    }
                }
            }
            override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                if (!dragging) return
                val index = snap.findSnapView(manager)?.let { manager.getPosition(it) } ?: return
                if (index != lastTick) { lastTick = index; haptics.tick() }
            }
        })
    }
    fun setChoices(values: List<Choice>, selectedKey: String?) {
        dragging = false; stopScroll()
        choices = values
        selected = values.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 } ?: 0
        items.notifyDataSetChanged()
        post { manager.scrollToPositionWithOffset(selected, 0) }
    }
    fun select(key: String) {
        val index = choices.indexOfFirst { it.key == key }
        if (index < 0 || index == selected) return
        val old = selected; selected = index
        if (old >= 0) items.notifyItemChanged(old)
        items.notifyItemChanged(index)
        if (!dragging) manager.scrollToPositionWithOffset(index, 0)
    }
    private fun commit(index: Int) {
        val choice = choices.getOrNull(index) ?: return
        if (!choice.enabled) { if (selected >= 0) smoothScrollToPosition(selected); return }
        select(choice.key); haptics.tick(); onSelected?.invoke(choice.key)
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = isEnabled && super.dispatchTouchEvent(event)
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        val pad = ((w - cellWidth) / 2).coerceAtLeast(0)
        setPadding(pad, 0, pad, 0)
        if (selected >= 0) manager.scrollToPositionWithOffset(selected, 0)
    }
}
