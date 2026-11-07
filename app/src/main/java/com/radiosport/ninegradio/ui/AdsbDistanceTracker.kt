package com.radiosport.ninegradio.ui

import android.content.Context
import android.location.Location
import com.radiosport.ninegradio.dsp.AdsbDecoder
import java.util.Locale

/** Geographic distances use Android's WGS84 inverse calculation, never RSSI estimates. */
class AdsbDistanceTracker(context: Context) {
    private val prefs=context.getSharedPreferences("radio_map",Context.MODE_PRIVATE)
    data class Ranges(val distances: Map<String,Double>,val currentMaxKm: Double?,val recordKm: Double?)
    private fun locationKey(): String? = if(prefs.contains("receiverLat") && prefs.contains("receiverLon"))
        String.format(Locale.ROOT,"adsb_record_%.4f_%.4f",prefs.getFloat("receiverLat",0f),prefs.getFloat("receiverLon",0f)) else null
    fun reset() { locationKey()?.let { prefs.edit().remove(it).apply() } }
    fun update(aircraft: List<AdsbDecoder.AdsbFrame>): Ranges {
        val key=locationKey() ?: return Ranges(emptyMap(),null,null)
        val lat=prefs.getFloat("receiverLat",0f).toDouble(); val lon=prefs.getFloat("receiverLon",0f).toDouble()
        val result=FloatArray(1)
        val distances=aircraft.mapNotNull { ac ->
            val a=ac.latitude; val b=ac.longitude
            if(a==null || b==null || a !in -90.0..90.0 || b !in -180.0..180.0) null else {
                Location.distanceBetween(lat,lon,a,b,result)
                ac.icao24 to result[0]/1000.0
            }
        }.toMap()
        val max=distances.values.maxOrNull()
        var record=prefs.getFloat(key,-1f).takeIf { it>=0f }?.toDouble()
        if(max!=null && (record==null || max>record+0.01)) { record=max; prefs.edit().putFloat(key,max.toFloat()).apply() }
        return Ranges(distances,max,record)
    }
    companion object {
        fun format(km: Double?)=km?.let { String.format(Locale.ROOT,"%.1f km",it) } ?: "—"
    }
}
