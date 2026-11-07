package com.radiosport.ninegradio.content

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class EibiScheduleTest {
    @Test fun currentSeasonTracksUtcSeasonChanges() {
        assertEquals("B25",EibiSchedule.currentSeason(LocalDate.of(2026,1,1)))
        assertEquals("B25",EibiSchedule.currentSeason(LocalDate.of(2026,3,28)))
        assertEquals("A26",EibiSchedule.currentSeason(LocalDate.of(2026,3,29)))
        assertEquals("A26",EibiSchedule.currentSeason(LocalDate.of(2026,10,24)))
        assertEquals("B26",EibiSchedule.currentSeason(LocalDate.of(2026,10,25)))
    }
    private fun row(days: String="",time: String="0000-2400",p: Int=1,from: String="",until: String="")=
        EibiSchedule.parse("11920;$time;$days;CHN;\"Radio; Test\";M;FE;x;$p;$from;$until;").single()
    @Test fun parsesQuotedNamesAndFractionalFrequency() {
        assertEquals("Radio; Test",row().station)
        assertEquals(11920000L,row().hz)
        assertEquals(16300L,EibiSchedule.parse("16.3;0000-2400;;IND;Navy;;SAs;v;1;;").single().hz)
        assertTrue(EibiSchedule.parse("bad;bad;;;;;;;;").isEmpty())
    }
    @Test fun midnightUsesStartDayAndEndIsExclusive() {
        val r=row("1","2300-0100")
        assertTrue(r.activeAt(ZonedDateTime.parse("2026-11-10T00:30:00Z"),"B26"))
        assertFalse(r.activeAt(ZonedDateTime.parse("2026-11-10T01:00:00Z"),"B26"))
        assertFalse(r.activeAt(ZonedDateTime.parse("2026-11-11T00:30:00Z"),"B26"))
    }
    @Test fun dateWindowsSeasonInactiveAndSpecialDays() {
        val now=ZonedDateTime.parse("2026-11-07T08:00:00Z")
        assertTrue(row().activeAt(now,"B26"))
        assertFalse(row(p=8).activeAt(now,"B26"))
        assertTrue(row(p=6,from="0111",until="1001").activeAt(now,"B26"))
        assertFalse(row(p=6,from="0811",until="1001").activeAt(now,"B26"))
        assertFalse(row().activeAt(now,"A26"))
        assertNull(EibiSchedule.dayMatches("irr",now.toLocalDate()))
        assertTrue(row("irr").activeAt(now,"B26"))
        assertEquals(true,EibiSchedule.dayMatches("1.Sa",now.toLocalDate()))
        assertEquals(false,EibiSchedule.dayMatches("Last7",now.toLocalDate()))
    }
    @Test fun utcConversionAndSeasonBoundary() {
        val r=row("6","0800-0900")
        assertTrue(r.activeAt(ZonedDateTime.parse("2026-11-07T16:30:00+08:00"),"B26"))
        assertTrue(EibiSchedule.inSeason(LocalDate.of(2026,10,25),"B26"))
        assertFalse(EibiSchedule.inSeason(LocalDate.of(2027,3,28),"B26"))
        assertNull(EibiSchedule.minute("2460"))
    }
}
