package com.radiosport.ninegradio.ui

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.radiosport.ninegradio.R

/** Unmodified, bundled DSEG fonts; system fallback stays available for units and Chinese. */
object RadioFonts {
    private var digitFace:Typeface?=null
    private var labelFace:Typeface?=null
    fun digits(context:Context):Typeface = digitFace ?: requireNotNull(ResourcesCompat.getFont(context,R.font.radio_digits)).also { digitFace=it }
    fun labels(context:Context):Typeface = labelFace ?: requireNotNull(ResourcesCompat.getFont(context,R.font.radio_labels)).also { labelFace=it }
}
