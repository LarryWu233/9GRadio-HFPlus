package com.radiosport.ninegradio.ui

import android.graphics.Rect
import android.view.View
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import com.radiosport.ninegradio.R

/** Decoration only: AndroidX Preference continues to own binding and persistence. */
object RadioPreferenceStyling {
    fun apply(fragment:PreferenceFragmentCompat) {
        val list=fragment.listView
        list.clipToPadding=false
        fragment.setDivider(null)
        list.setPadding(0,0,0,list.resources.getDimensionPixelSize(R.dimen.radio_space_lg))
        list.addItemDecoration(object:RecyclerView.ItemDecoration() {
            override fun getItemOffsets(out:Rect,view:View,parent:RecyclerView,state:RecyclerView.State) {
                val res=parent.resources
                out.set(res.getDimensionPixelSize(R.dimen.radio_space_lg),0,
                    res.getDimensionPixelSize(R.dimen.radio_space_lg),res.getDimensionPixelSize(R.dimen.radio_space_sm))
            }
        })
    }
}
