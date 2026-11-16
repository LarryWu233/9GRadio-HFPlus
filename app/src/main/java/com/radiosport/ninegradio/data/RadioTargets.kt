package com.radiosport.ninegradio.data

import kotlinx.coroutines.flow.MutableStateFlow

data class RadioTarget(val id: String, val label: String, val latitude: Double,
                       val longitude: Double, val detail: String, val timeMs: Long,
                       val type:Type=Type.STATION,val headingDegrees:Double?=null,
                       val positionTimeMs:Long?=if(type!=Type.STATION) timeMs else null) {
    enum class Type { STATION, AIRCRAFT, SHIP }
}

/** Decoded position data in WGS84; independent of map rendering and tile providers. */
object RadioTargets {
    val aircraft = MutableStateFlow<List<RadioTarget>>(emptyList())
    val stations = MutableStateFlow<List<RadioTarget>>(emptyList())
    val vessels = MutableStateFlow<List<RadioTarget>>(emptyList())
    @Synchronized fun upsertStation(target: RadioTarget) {
        val cutoff = System.currentTimeMillis() - 600000
        stations.value = (stations.value.filter { it.id != target.id && it.timeMs > cutoff } + target).takeLast(500)
    }
}

/** Application presentation binding; the native decoder does not depend on maps. */
fun aisTarget(vessel:com.radiosport.ninegradio.dsp.AisVessel):RadioTarget? {
    val lat=vessel.latitude ?: return null;val lon=vessel.longitude ?: return null
    return RadioTarget("ais:${vessel.mmsi}",vessel.name ?: vessel.mmsi.toString().padStart(9,'0'),lat,lon,
        "MMSI：${vessel.mmsi.toString().padStart(9,'0')}\n${vessel.name ?: "船名未知"} · ${vessel.callsign ?: "呼号未知"}",vessel.lastMessageMs,
        when(vessel.kind) {
            com.radiosport.ninegradio.dsp.AisTargetKind.VESSEL -> RadioTarget.Type.SHIP
            com.radiosport.ninegradio.dsp.AisTargetKind.AIRCRAFT -> RadioTarget.Type.AIRCRAFT
            else -> RadioTarget.Type.STATION
        },vessel.headingDegrees ?: vessel.courseDegrees,vessel.positionTimeMs)
}
