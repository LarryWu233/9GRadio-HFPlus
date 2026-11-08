package com.radiosport.ninegradio

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.preference.ListPreference
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.radiosport.ninegradio.ui.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Alpha26InteractionTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private fun waitFor(message:String,check:()->Boolean) {
        val until=android.os.SystemClock.elapsedRealtime()+12000
        while(android.os.SystemClock.elapsedRealtime()<until) { if(check()) return;Thread.sleep(80) }
        assertTrue(message,check())
    }
    private inline fun <reified T:android.app.Activity> resumed():T? {
        var value:T?=null
        inst.runOnMainSync { value=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<T>().firstOrNull() }
        return value
    }
    @Test fun editedQuickBookmarkUsesExistingStoreAndSettingsRespectInsets() {
        ReceiverTestPreferences(context).use {
            context.getSharedPreferences("rtlsdr_prefs",0).edit().putBoolean("receiverEnabled",false).commit()
            val dao=(context.applicationContext as RtlSdrApplication).database.bookmarkDao()
            val marker="instrumentation-bookmark-${System.nanoTime()}"
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    Thread.sleep(2500)
                    scenario.onActivity { a -> ViewModelProvider(a)[MainViewModel::class.java].setReceiverEnabled(false);a.findViewById<View>(R.id.btnQuickBookmark).performClick() }
                    onView(withId(R.id.etQuickBookmarkName)).perform(replaceText(marker))
                    onView(withId(R.id.etQuickBookmarkFrequency)).perform(replaceText("14.230"),closeSoftKeyboard())
                    onView(withText("保存")).perform(click())
                    waitFor("Quick bookmark persisted") { runBlocking { dao.getAll().first().any { it.label==marker && it.frequencyHz==14_230_000L } } }
                    scenario.onActivity { it.findViewById<View>(R.id.btnMyBookmarks).performClick() }
                    onView(withId(R.id.etBookmarkPickerSearch)).perform(replaceText(marker),closeSoftKeyboard())
                    onView(allOf(withId(android.R.id.text1),withText(containsString(marker)))).perform(click())
                    scenario.onActivity { a -> assertEquals(14_230_000L,ViewModelProvider(a)[MainViewModel::class.java].centerFreqHz.value);a.findViewById<View>(R.id.btnToolbarSettings).performClick() }
                    waitFor("Settings resumed") { resumed<SettingsActivity>()!=null }
                    val settings=resumed<SettingsActivity>()!!
                    inst.runOnMainSync {
                        val fragment=settings.supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
                        for(key in listOf("pref_record_audio_format","pref_record_audio_depth","pref_record_audio_bitrate"))
                            assertEquals("cat_recording",fragment.findPreference<ListPreference>(key)!!.parent!!.key)
                        assertNull(fragment.findPreference<androidx.preference.Preference>("pref_recording_files"))
                        val category=(0 until fragment.listView.childCount).map { fragment.listView.getChildAt(it) }
                            .mapNotNull { if(it is TextView) it else it.findViewById<TextView>(android.R.id.title) }.first { it.text=="界面与工具" }
                        assertEquals("First bind has accent",UiThemes.color(settings,R.attr.radioAccent),category.currentTextColor)
                        assertBelowStatusBar(settings,settings.findViewById(R.id.settingsToolbar))
                        fragment.onDisplayPreferenceDialog(fragment.findPreference<ListPreference>("pref_record_audio_format")!!)
                    }
                    onView(withText("WAV · PCM")).perform(click())
                    inst.runOnMainSync {
                        val fragment=settings.supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
                        assertEquals("WAV",fragment.findPreference<ListPreference>("pref_record_audio_format")!!.value)
                        assertTrue(fragment.findPreference<ListPreference>("pref_record_audio_depth")!!.isVisible)
                        assertFalse(fragment.findPreference<ListPreference>("pref_record_audio_bitrate")!!.isVisible)
                        settings.openBookmarks()
                    }
                    waitFor("Bookmark manager resumed") { resumed<BookmarkActivity>()!=null }
                    inst.runOnMainSync {
                        val activity=resumedOnMain<BookmarkActivity>()!!
                        assertBelowStatusBar(activity,activity.findViewById(R.id.etSearch))
                        activity.finish();settings.finish()
                    }
                }
            } finally {
                inst.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filter { it is SettingsActivity || it is BookmarkActivity }.forEach { it.finish() } }
                runBlocking { dao.getAll().first().filter { it.label==marker }.forEach { dao.delete(it) } }
            }
        }
    }
    private inline fun <reified T:android.app.Activity> resumedOnMain()=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<T>().firstOrNull()
    private fun assertBelowStatusBar(activity:android.app.Activity,view:View) {
        val content=activity.findViewById<View>(android.R.id.content)
        val top=ViewCompat.getRootWindowInsets(content)!!.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top
        val xy=IntArray(2);view.getLocationOnScreen(xy)
        assertTrue("Content clears status bar/cutout",xy[1]>=top)
    }
}
