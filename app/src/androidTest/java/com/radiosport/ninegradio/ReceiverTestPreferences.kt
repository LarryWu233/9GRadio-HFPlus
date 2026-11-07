package com.radiosport.ninegradio

import android.content.Context
import androidx.preference.PreferenceManager
import java.io.Closeable

/** Keep test frequencies and settings out of the user's receiver profiles. */
internal class ReceiverTestPreferences(context: Context): Closeable {
    private val snapshots=listOf(context.getSharedPreferences("rtlsdr_prefs",Context.MODE_PRIVATE),
        PreferenceManager.getDefaultSharedPreferences(context),context.getSharedPreferences("screen_modules",Context.MODE_PRIVATE),
        context.getSharedPreferences("radio_map",Context.MODE_PRIVATE),context.getSharedPreferences("audio_content",Context.MODE_PRIVATE))
        .map { prefs -> prefs to prefs.all.mapValues { (_,value) -> if(value is Set<*>) value.toSet() else value } }
    override fun close() {
        snapshots.forEach { (prefs,values) ->
            val editor=prefs.edit().clear()
            values.forEach { (key,value) -> when(value) {
                is String -> editor.putString(key,value)
                is Boolean -> editor.putBoolean(key,value)
                is Int -> editor.putInt(key,value)
                is Long -> editor.putLong(key,value)
                is Float -> editor.putFloat(key,value)
                is Set<*> -> editor.putStringSet(key,value.filterIsInstance<String>().toSet())
            } }
            editor.commit()
        }
    }
}
