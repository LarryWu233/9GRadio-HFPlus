package com.radiosport.ninegradio.content

import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class EibiStationMatcherTest {
    private val now=ZonedDateTime.parse("2026-11-17T08:30:00Z")
    private val readme="""
        One-letter or two-letter codes for different transmitter sites
           CHN: b-Beijing 39N54-116E24
           J: t-Tokyo 35N40'30"-139E45'00"
           RUS: s-Samara 53N12-50E06
           G: London 51N30-00W07
    """.trimIndent()
    private fun row(name:String,country:String,site:String,time:String="0800-0900",hz:String="11620")=
        EibiSchedule.parse("$hz;$time;;$country;$name;E;FE;$site;1;;").single()
    @Test fun parsesCoordinatesAndResolvesActualRelayRatherThanBroadcasterCountry() {
        val sites=EibiStationMatcher.sites(readme)
        assertEquals(35+40/60.0+30/3600.0,sites.getValue("J-t").latitude,1e-8)
        assertTrue(sites.getValue("G").longitude<0)
        val china=row("Far transmitter","CHN","/RUS-s")
        val japan=row("Nearby transmitter","G","/J-t")
        val unknown=row("Unknown site","CHN","unknown")
        val matcher=EibiStationMatcher(listOf(china,unknown,japan),sites)
        assertEquals(listOf(japan,china,unknown),matcher.candidates(11620000,now,"B26",EibiStationMatcher.Position(35.0,140.0)))
        assertEquals(3,matcher.candidates(11620000,now,"B26",null).size)
    }
    @Test fun timeAndFrequencyAdmissionRetainAllGeographicCandidates() {
        val near=row("Near","CHN","b");val far=row("Far","RUS","s")
        val ended=row("Ended","J","t","0700-0830")
        val other=row("Other frequency","J","t",hz="11625")
        val matcher=EibiStationMatcher(listOf(near,far,ended,other),EibiStationMatcher.sites(readme))
        assertEquals(setOf(near,far),matcher.candidates(11620100,now,"B26",EibiStationMatcher.Position(39.0,116.0)).toSet())
        assertTrue(matcher.candidates(11620000,now,"A26",null).isEmpty())
    }
    @Test fun ambiguousAndInvalidCoordinatesAreNotGuessed() {
        val text=readme+"\n   CHN: b-Conflict 30N00-100E00\n        x-Invalid 91N00-100E00\n        z-Multiple 30N00-100E00 or 31N00-101E00"
        val sites=EibiStationMatcher.sites(text)
        assertFalse(sites.containsKey("CHN-b"));assertFalse(sites.containsKey("CHN-x"));assertFalse(sites.containsKey("CHN-z"))
    }
}
