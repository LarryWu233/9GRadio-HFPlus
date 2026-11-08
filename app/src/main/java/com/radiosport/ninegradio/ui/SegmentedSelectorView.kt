package com.radiosport.ninegradio.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.google.android.material.button.MaterialButton
import com.google.android.material.shape.RelativeCornerSize
import com.google.android.material.button.MaterialButtonToggleGroup

/** Few alternatives stay visible together; no scroll and no hidden choices. */
class SegmentedSelectorView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : MaterialButtonToggleGroup(context, attrs) {
    var onSelected: ((String) -> Unit)? = null
    private val keys = mutableMapOf<Int,String>()
    private var syncing = false
    private val haptics = TuningHaptics(this)
    init {
        isSingleSelection = true; isSelectionRequired = true
        addOnButtonCheckedListener { _, id, checked -> if (checked && !syncing) keys[id]?.let { haptics.tick(); onSelected?.invoke(it) } }
    }
    fun setChoices(choices: List<Pair<String,String>>, selected: String) {
        syncing = true; removeAllViews(); keys.clear()
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        for ((key,label) in choices) {
            val button = MaterialButton(context).apply {
                id = View.generateViewId(); text = label; contentDescription = label
                textSize = 12f; isAllCaps = false; minWidth = 0; minimumWidth = 0
                insetTop = 0; insetBottom = 0
                // Half the actual height: a fixed 24dp radius overlaps at a 44dp height.
                shapeAppearanceModel=shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(RelativeCornerSize(0.5f)).build()
                elevation=0f;stateListAnimator=null
                setTextColor(ColorStateList(states, intArrayOf(UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioAccent),UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioSecondary))))
                backgroundTintList = ColorStateList(states,intArrayOf(UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioSelected),UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioSurface)))
                strokeColor = ColorStateList.valueOf(UiThemes.color(context,com.radiosport.ninegradio.R.attr.radioOutline)); strokeWidth = resources.displayMetrics.density.toInt().coerceAtLeast(1)
            }
            keys[button.id] = key
            addView(button, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT,1f))
            if (key == selected) check(button.id)
        }
        syncing = false
    }
    fun select(key: String) { syncing = true; keys.entries.firstOrNull { it.value == key }?.let { check(it.key) }; syncing = false }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = isEnabled && super.dispatchTouchEvent(event)
}
