package com.radiosport.ninegradio.ui

import android.content.Context
import android.location.Location
import com.radiosport.ninegradio.dsp.AdsbDecoder
import java.util.Locale

/** Geographic distances use Android's WGS84 inverse calculation, never RSSI estimates. */
class AdsbDistanceTracker(context: Context) {
    private val prefs=context.getSharedPreferences("radio_map",Context.MODE_PRIVATE)
    data class Ranges(val distances: Map<String,Double>,val currentMaxKm: Double?,val recordKm: Double?)
    private var cachedPosition:Pair<Float,Float>?=null
    private var cachedKey:String?=null
    private fun locationKey(): String? {
        if(!prefs.contains("receiverLat") || !prefs.contains("receiverLon")) return null
        val lat=prefs.getFloat("receiverLat",Float.NaN);val lon=prefs.getFloat("receiverLon",Float.NaN)
        if(lat !in -90f..90f || lon !in -180f..180f) return null
        val position=lat to lon
        if(position==cachedPosition) return cachedKey
        val result=FloatArray(1)
        // Retain the existing per-site records. GPS jitter must not create a
        // fresh record every few metres; distant receiver sites stay separate.
        cachedKey=prefs.all.keys.filter { it.startsWith("adsb_record_") }.mapNotNull { key ->
            val pair=key.removePrefix("adsb_record_").split('_').map { it.toDoubleOrNull() }
            if(pair.size!=2 || pair[0]==null || pair[1]==null || pair[0]!! !in -90.0..90.0 || pair[1]!! !in -180.0..180.0) null
            else { Location.distanceBetween(lat.toDouble(),lon.toDouble(),pair[0]!!,pair[1]!!,result)
                if(result[0]<=1000f) key to result[0] else null }
        }.minByOrNull { it.second }?.first ?: String.format(Locale.ROOT,"adsb_record_%.4f_%.4f",lat,lon)
        cachedPosition=position
        return cachedKey
    }
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
