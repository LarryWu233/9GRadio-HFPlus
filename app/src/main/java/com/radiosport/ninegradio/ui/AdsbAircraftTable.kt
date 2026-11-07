package com.radiosport.ninegradio.ui

import android.content.Context
import android.util.AttributeSet
import com.radiosport.ninegradio.dsp.AdsbDecoder
import java.util.Locale

class AdsbAircraftTable @JvmOverloads constructor(context: Context,attrs: AttributeSet?=null): RadioDataTable(context,attrs) {
    private var frames=emptyList<AdsbDecoder.AdsbFrame>()
    var onAircraftClick: ((AdsbDecoder.AdsbFrame)->Unit)?=null
    init {
        setColumns(listOf(Column("ICAO",56),Column("呼号",72),Column("高度 ft",64),Column("速度 kt",58),
            Column("航向 °",54),Column("距离 km",68),Column("纬度，经度",152),Column("升降 ft/min",88),Column("更新 s",56)))
        onRowClick={ position -> frames.getOrNull(position)?.let { onAircraftClick?.invoke(it) } }
    }
    fun submit(aircraft: List<AdsbDecoder.AdsbFrame>,ranges: Map<String,Double> = emptyMap()) {
        frames=aircraft.toList()
        submitRows(frames.map { ac -> Row(listOf(ac.icao24,ac.callsign?.takeIf { it.isNotBlank() } ?: "—",
            if(ac.onGround) "地面" else ac.altitude?.toString() ?: "—",ac.velocity?.toString() ?: "—",
            ac.heading?.let { String.format(Locale.ROOT,"%.0f",it) } ?: "—",
            ranges[ac.icao24]?.let { String.format(Locale.ROOT,"%.1f",it) } ?: "—",
            if(ac.latitude!=null && ac.longitude!=null) String.format(Locale.ROOT,"%.4f,%.4f",ac.latitude,ac.longitude) else "—",
            ac.verticalRate?.toString() ?: "—",((System.currentTimeMillis()-ac.timestamp).coerceAtLeast(0)/1000).toString())) })
    }
}
