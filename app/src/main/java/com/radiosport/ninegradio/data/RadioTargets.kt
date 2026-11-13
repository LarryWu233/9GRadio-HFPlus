package com.radiosport.ninegradio.data

import kotlinx.coroutines.flow.MutableStateFlow

data class RadioTarget(val id: String, val label: String, val latitude: Double,
                       val longitude: Double, val detail: String, val timeMs: Long,
                       val type:Type=Type.STATION,val headingDegrees:Double?=null) {
    enum class Type { STATION, AIRCRAFT }
}

/** Decoded position data in WGS84; independent of map rendering and tile providers. */
object RadioTargets {
    val aircraft = MutableStateFlow<List<RadioTarget>>(emptyList())
    val stations = MutableStateFlow<List<RadioTarget>>(emptyList())
    @Synchronized fun upsertStation(target: RadioTarget) {
        val cutoff = System.currentTimeMillis() - 600000
        stations.value = (stations.value.filter { it.id != target.id && it.timeMs > cutoff } + target).takeLast(500)
    }
}
