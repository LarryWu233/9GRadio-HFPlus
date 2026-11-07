package com.radiosport.ninegradio

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.preference.Preference
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.radiosport.ninegradio.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the Activity Result path without modifying the user's bookmark database. */
@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {
    @Test fun bookmarkSelectionReturnsThroughSecondarySettingsToReceiver() {
        val inst=InstrumentationRegistry.getInstrumentation()
        ReceiverTestPreferences(inst.targetContext).use {
            val result=Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().putExtra(BookmarkActivity.EXTRA_FREQ_HZ,87_800_000L))
            val monitor=inst.addMonitor(BookmarkActivity::class.java.name,result,true)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { main ->
                    Thread.sleep(2800)
                    main.onActivity { a ->
                        ViewModelProvider(a)[MainViewModel::class.java].apply { setReceiverEnabled(false); setAutoDemod(false) }
                        a.findViewById<View>(R.id.btnToolbarSettings).performClick()
                    }
                    Thread.sleep(600)
                    inst.runOnMainSync {
                        val settings=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single() as SettingsActivity
                        val fragment=settings.supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
                        fragment.findPreference<Preference>("pref_bookmarks")!!.performClick()
                    }
                    Thread.sleep(700)
                    assertEquals(1,monitor.hits)
                    main.onActivity { a ->
                        assertEquals(87_800_000L,ViewModelProvider(a)[MainViewModel::class.java].centerFreqHz.value)
                        assertEquals(87_800_000L,a.findViewById<FrequencyView>(R.id.frequencyView).getFrequency())
                    }
                }
            } finally { inst.removeMonitor(monitor) }
        }
    }
}
