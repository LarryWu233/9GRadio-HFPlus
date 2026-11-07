package com.radiosport.ninegradio

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.AdsbDecoder
import com.radiosport.ninegradio.ui.AdsbDistanceTracker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdsbDistanceTest {
    @Test fun missingPositionAndPerLocationRecordAreHandledWithoutRssiEstimates() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=base.getSharedPreferences("distance_test",Context.MODE_PRIVATE)
        val context=object: ContextWrapper(base) {
            override fun getSharedPreferences(name: String,mode: Int)=prefs
        }
        prefs.edit().clear().commit()
        try {
            val tracker=AdsbDistanceTracker(context)
            val ac=AdsbDecoder.AdsbFrame("TEST",17,0,null,null,1.0,0.0,null,null,null,false)
            assertNull(tracker.update(listOf(ac)).currentMaxKm)
            prefs.edit().putFloat("receiverLat",0f).putFloat("receiverLon",0f).commit()
            val known=tracker.update(listOf(ac))
            assertTrue(known.currentMaxKm!! in 110.0..112.0)
            assertEquals(known.currentMaxKm!!,known.recordKm!!,0.01)
            val empty=tracker.update(emptyList())
            assertNull(empty.currentMaxKm); assertNotNull(empty.recordKm)
            prefs.edit().putFloat("receiverLat",10f).commit()
            assertNull(tracker.update(emptyList()).recordKm)
            prefs.edit().putFloat("receiverLat",0f).commit()
            assertNotNull(tracker.update(emptyList()).recordKm)
            tracker.reset(); assertNull(tracker.update(emptyList()).recordKm)
        } finally { prefs.edit().clear().commit() }
    }
}
