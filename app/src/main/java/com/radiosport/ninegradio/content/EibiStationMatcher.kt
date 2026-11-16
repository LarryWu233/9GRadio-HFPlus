package com.radiosport.ninegradio.content

import java.time.ZonedDateTime
import java.util.TreeMap
import kotlin.math.*

/** Offline schedule hints, never signal identification or a receive filter. */
class EibiStationMatcher(entries:List<EibiEntry>,private val sites:Map<String,Position>) {
    data class Position(val latitude:Double,val longitude:Double) {
        init { require(latitude in -90.0..90.0 && longitude in -180.0..180.0) }
    }
    private val frequencies=TreeMap<Long,List<EibiEntry>>().apply { putAll(entries.groupBy { it.hz }) }
    fun candidates(hz:Long,time:ZonedDateTime,season:String,receiver:Position?):List<EibiEntry> =
        frequencies.subMap(hz-1500,true,hz+1500,true).values.flatten()
            .filter { it.activeAt(time,season) }.distinct()
            .sortedWith(compareBy<EibiEntry> { abs(it.hz-hz) }
                .thenBy { entry -> receiver?.let { here -> site(entry)?.let { distance(here,it) } } ?: Double.POSITIVE_INFINITY }
                .thenBy { it.station }.thenBy { it.country }.thenBy { it.site }.thenBy { it.utc }.thenBy { it.language })
    private fun site(entry:EibiEntry):Position? {
        val source=entry.site.trim()
        val key=if(source.startsWith('/')) source.drop(1) else entry.country+if(source.isBlank()) "" else "-$source"
        return sites[key]
    }
    companion object {
        /** Only unambiguous coordinates/codes from the bundled EiBi README.
         * Unknown, multiple or conflicting positions remain unknown, not guessed.
         */
        fun sites(readme:String):Map<String,Position> {
            val result=mutableMapOf<String,Position>();val conflicts=mutableSetOf<String>()
            val heading=Regex("^\\s{0,4}([A-Z0-9]{1,3}):\\s*(.*)$")
            val code=Regex("^([A-Za-z0-9]+)-")
            val coordinate=Regex("(\\d{1,3})([NSEW])(\\d{1,2}(?:\\.\\d+)?)?(?:['’](\\d{1,2}(?:\\.\\d+)?)[\"”]?)?")
            var country:String?=null
            // Restrict parsing to the transmitter section, excluding language/target codes.
            val section=readme.indexOf("One-letter or two-letter codes for different transmitter sites")
            if(section<0) return emptyMap()
            readme.substring(section).lineSequence().forEach { line ->
                val header=heading.matchEntire(line)
                if(header!=null) country=header.groupValues[1]
                val host=country ?: return@forEach
                val text=header?.groupValues?.get(2) ?: line.trim()
                val parts=coordinate.findAll(text).toList()
                if(parts.size!=2 || parts[0].groupValues[2] !in listOf("N","S") || parts[1].groupValues[2] !in listOf("E","W")) return@forEach
                fun value(match:MatchResult):Double? {
                    val degrees=match.groupValues[1].toDouble()
                    val minutes=match.groupValues[3].toDoubleOrNull() ?: 0.0
                    val seconds=match.groupValues[4].toDoubleOrNull() ?: 0.0
                    if(minutes>=60 || seconds>=60) return null
                    return (degrees+minutes/60+seconds/3600)*if(match.groupValues[2] in listOf("S","W")) -1 else 1
                }
                val lat=value(parts[0]) ?: return@forEach;val lon=value(parts[1]) ?: return@forEach
                if(lat !in -90.0..90.0 || lon !in -180.0..180.0) return@forEach
                val suffix=code.find(text)?.groupValues?.get(1)
                // Only a country header can establish its unlabelled/default site.
                if(suffix==null && header==null) return@forEach
                val key=host+(suffix?.let { "-$it" } ?: "")
                val point=Position(lat,lon)
                if(result[key]?.let { it!=point }==true) conflicts+=key
                result[key]=point
            }
            return result.filterKeys { it !in conflicts }
        }
        private fun distance(a:Position,b:Position):Double {
            val lat=Math.toRadians(b.latitude-a.latitude);val lon=Math.toRadians(b.longitude-a.longitude)
            val h=sin(lat/2).pow(2)+cos(Math.toRadians(a.latitude))*cos(Math.toRadians(b.latitude))*sin(lon/2).pow(2)
            return 2*asin(sqrt(h.coerceIn(0.0,1.0))) // angular great-circle distance is enough for ordering
        }
    }
}
