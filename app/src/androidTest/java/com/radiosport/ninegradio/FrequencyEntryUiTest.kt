package com.radiosport.ninegradio

import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.ui.MainActivity
import com.radiosport.ninegradio.ui.MainViewModel
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrequencyEntryUiTest {
    @Test fun holdMainReadoutOpensFrequencyEntry() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        ReceiverTestPreferences(context).use {
            context.getSharedPreferences("rtlsdr_prefs",0).edit().putBoolean("receiverEnabled",false).putString("activeDevice","airspyhf").commit()
            ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(
                "am start -W -n ${context.packageName}/${MainActivity::class.java.name}"
            )).use { it.readBytes() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                Thread.sleep(1600)
                scenario.onActivity { a ->
                    a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    val vm=ViewModelProvider(a)[MainViewModel::class.java]
                    vm.setReceiverEnabled(false);vm.setAutoDemod(false);vm.setDemodMode(DemodMode.AM)
                }
                Thread.sleep(350)
                onView(withId(R.id.frequencyView)).perform(longClick())
                onView(withText("Enter Frequency")).check(matches(isDisplayed()))
                onView(isAssignableFrom(android.widget.EditText::class.java)).inRoot(isDialog()).perform(replaceText("11.620"),closeSoftKeyboard())
                onView(withText("Tune")).perform(click())
                scenario.onActivity { a -> assertEquals(11620000L,ViewModelProvider(a)[MainViewModel::class.java].centerFreqHz.value) }
            }
        }
    }
}
