package com.radiosport.ninegradio.ui

import android.content.SharedPreferences
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import com.google.android.material.slider.Slider
import kotlin.math.abs
import kotlin.math.roundToInt

/** One ratio shared by the visible divider, Display slider, and saved layout. */
class SpectrumWaterfallLayout(
    private val spectrum: View,
    private val waterfall: View,
    private val prefs: SharedPreferences
) {
    var ratio = prefs.getFloat("spectrum_height_ratio", 0.4f).coerceIn(0.2f, 0.8f)
        private set
    private var slider: Slider? = null
    private var label: TextView? = null
    private var startY = 0f
    private var startRatio = ratio
    private var startHeight = 0
    private var startAvailable = 0
    private var moved = false
    private var layoutPending = false

    init {
        (spectrum.parent as View).addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyLayout() }
        spectrum.post { applyLayout() }
    }

    fun bindSlider(view: Slider?, value: TextView?) {
        slider = view
        label = value
        view?.clearOnChangeListeners()
        view?.clearOnSliderTouchListeners()
        view?.value = ratio
        value?.text = "${(ratio * 100).roundToInt()}%"
        view?.addOnChangeListener { _, next, fromUser -> if (fromUser) setRatio(next, false) }
        view?.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) { save() }
        })
    }

    fun setRatio(value: Float, persist: Boolean = true) {
        ratio = value.coerceIn(0.2f, 0.8f)
        if (slider?.value != ratio) slider?.value = ratio
        label?.text = "${(ratio * 100).roundToInt()}%"
        if (!layoutPending) {
            layoutPending = true
            spectrum.postOnAnimation { layoutPending = false; applyLayout() }
        }
        if (persist) save()
    }

    private fun save() { prefs.edit().putFloat("spectrum_height_ratio", ratio).apply() }

    private fun applyLayout() {
        val available = spectrum.height + waterfall.height
        if (available <= 0) return
        val height = (available * ratio).roundToInt()
        if (spectrum.layoutParams.height != height) {
            spectrum.layoutParams = spectrum.layoutParams.apply { this.height = height }
        }
    }
}
