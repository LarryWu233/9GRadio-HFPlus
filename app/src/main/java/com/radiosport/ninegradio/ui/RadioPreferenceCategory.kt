package com.radiosport.ninegradio.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import com.radiosport.ninegradio.R

/** Apply category emphasis after Preference has bound its disabled heading state. */
class RadioPreferenceCategory @JvmOverloads constructor(context:Context,attrs:AttributeSet?=null):
    PreferenceCategory(context,attrs) {
    override fun onBindViewHolder(holder:PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(android.R.id.title) as? TextView)?.setTextColor(UiThemes.color(context,R.attr.radioAccent))
    }
}
