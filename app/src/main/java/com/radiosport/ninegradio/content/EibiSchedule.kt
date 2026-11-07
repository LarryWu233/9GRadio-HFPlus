package com.radiosport.ninegradio.content

import java.time.*
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToLong

data class EibiEntry(val hz: Long, val start: Int, val end: Int, val days: String, val country: String,
    val station: String, val language: String, val target: String, val site: String,
    val persistence: Int, val from: String, val until: String) {
    val utc: String get() = "%02d%02d–%02d%02d".format(start/60,start%60,end/60,end%60)
    val uncertainDays: Boolean get() = EibiSchedule.dayMatches(days, LocalDate.of(2026,1,1)) == null
    fun activeAt(time: ZonedDateTime, season: String): Boolean {
        if(persistence%10 == 8) return false
        val utc=time.withZoneSameInstant(ZoneOffset.UTC)
        val minute=utc.hour*60+utc.minute
        val overnight=end<start
        if(start != end && !(if(overnight) minute>=start || minute<end else minute>=start && minute<end)) return false
        val date=if(overnight && minute<end) utc.toLocalDate().minusDays(1) else utc.toLocalDate()
        if(EibiSchedule.dayMatches(days,date) == false) return false
        if(!EibiSchedule.inSeason(date,season)) return false
        if(persistence%10 == 6) {
            val a=EibiSchedule.partialDate(from,season)
            val b=EibiSchedule.partialDate(until,season)
            if(a!=null && date<a || b!=null && date>b) return false
        }
        return true
    }
}
object EibiSchedule {
    fun currentSeason(date: LocalDate): String {
        val year=date.year%100
        return listOf(String.format(java.util.Locale.ROOT,"B%02d",year),String.format(java.util.Locale.ROOT,"A%02d",year),
            String.format(java.util.Locale.ROOT,"B%02d",(year+99)%100)).first { inSeason(date,it) }
    }
    fun parse(csv: String): List<EibiEntry> = csv.lineSequence().mapNotNull { line ->
        val f=fields(line)
        if(f.size<9) return@mapNotNull null
        val khz=f[0].toDoubleOrNull() ?: return@mapNotNull null
        val times=f[1].split('-'); if(times.size!=2) return@mapNotNull null
        val start=minute(times[0]) ?: return@mapNotNull null
        val end=minute(times[1]) ?: return@mapNotNull null
        if(khz<=0 || khz>3_000_000 || start==1440) return@mapNotNull null
        EibiEntry((khz*1000).roundToLong(),start,end,f[2],f[3],f[4],f[5],f[6],f[7],f[8].toIntOrNull() ?: 0,f.getOrElse(9){""},f.getOrElse(10){""})
    }.toList()
    private fun fields(line: String): List<String> {
        val result=mutableListOf<String>(); val value=StringBuilder(); var quoted=false; var i=0
        while(i<line.length) { val c=line[i++]
            if(c=='"') { if(quoted && i<line.length && line[i]=='"') { value.append('"'); i++ } else quoted=!quoted }
            else if(c==';' && !quoted) { result.add(value.toString().trim()); value.setLength(0) } else value.append(c)
        }
        result.add(value.toString().trim()); return result
    }
    fun minute(value: String): Int? {
        if(!value.matches(Regex("\\d{4}"))) return null
        val h=value.take(2).toInt(); val m=value.takeLast(2).toInt()
        return if(h in 0..23 && m in 0..59 || h==24 && m==0) h*60+m else null
    }
    fun dayMatches(code: String, date: LocalDate): Boolean? {
        if(code.isBlank() || code in listOf("USB","LSB")) return true
        if(code.matches(Regex("[1-7]+"))) return date.dayOfWeek.value.toString() in code
        val names=listOf("Mo","Tu","We","Th","Fr","Sa","Su")
        if(code in names) return names[date.dayOfWeek.value-1]==code
        val nth=Regex("([1-5])\\.(Mo|Tu|We|Th|Fr|Sa|Su)").matchEntire(code)
        if(nth!=null) return (date.dayOfMonth-1)/7+1==nth.groupValues[1].toInt() && names[date.dayOfWeek.value-1]==nth.groupValues[2]
        val last=Regex("Last([1-7])").matchEntire(code)
        if(last!=null) return date.dayOfWeek.value==last.groupValues[1].toInt() && date.plusWeeks(1).month!=date.month
        return null // Irregular/special schedules stay visible with their original day code.
    }
    private fun boundary(year: Int,month: Int)=LocalDate.of(year,month,1).with(TemporalAdjusters.lastDayOfMonth()).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
    fun inSeason(date: LocalDate,season: String): Boolean {
        if(!season.matches(Regex("[ABab]\\d{2}"))) return true
        val y=2000+season.drop(1).toInt(); val summer=season.startsWith("A",true)
        val start=boundary(y,if(summer) 3 else 10); val end=boundary(if(summer) y else y+1,if(summer) 10 else 3)
        return date>=start && date<end
    }
    fun partialDate(value: String,season: String): LocalDate? {
        if(!value.matches(Regex("\\d{4}")) || !season.matches(Regex("[ABab]\\d{2}"))) return null
        return runCatching {
            val month=value.takeLast(2).toInt(); val day=value.take(2).toInt()
            val year=2000+season.drop(1).toInt()+if(season.startsWith("B",true) && month<7) 1 else 0
            LocalDate.of(year,month,day)
        }.getOrNull()
    }
}
