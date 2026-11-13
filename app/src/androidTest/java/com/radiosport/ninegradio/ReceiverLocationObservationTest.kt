package com.radiosport.ninegradio

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.radiosport.ninegradio.ui.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit physical-phone check. Emits success/status only, never coordinates or map content.
 * Keeps the requested phone-position setting; unlike synthetic tests this is a user operation. */
@RunWith(AndroidJUnit4::class)
class ReceiverLocationObservationTest {
    @Test fun observeAuthorizedForegroundLocation() {
        val inst=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("observePhoneLocation")=="true")
        val context=inst.targetContext
        context.getSharedPreferences("rtlsdr_prefs",0).edit().putBoolean("receiverEnabled",false).commit()
        ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/${MainActivity::class.java.name}"
        )).use { it.readBytes() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var controller:ReceiverLocationController?=null
            scenario.onActivity { a ->
                ViewModelProvider(a)[MainViewModel::class.java].setReceiverEnabled(false)
                val adapter=MainActivity::class.java.getDeclaredField("pagerAdapter").apply { isAccessible=true }.get(a) as ControlsPagerAdapter
                a.findViewById<TabLayout>(R.id.controlTabLayout).getTabAt(adapter.positionOf(ControlsPagerAdapter.TAB_ADSB))!!.select()
                val panel=a.findViewById<android.widget.FrameLayout>(R.id.adsbMapHost).getChildAt(0) as RadioMapPanel
                controller=panel.receiverLocation
                controller!!.requestCurrentLocation()
            }
            fun allowOwnForegroundDialog(node:AccessibilityNodeInfo?) {
                if(node==null) return
                if(node.viewIdResourceName=="com.android.permissioncontroller:id/permission_allow_foreground_only_button")
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                else for(i in 0 until node.childCount) allowOwnForegroundDialog(node.getChild(i))
            }
            val deadline=SystemClock.elapsedRealtime()+20000
            var fixed=false
            while(!fixed && SystemClock.elapsedRealtime()<deadline) {
                allowOwnForegroundDialog(inst.uiAutomation.rootInActiveWindow)
                scenario.onActivity { fixed=controller!!.position.value?.source=="phone" }
                if(!fixed) Thread.sleep(250)
            }
            var status=""
            scenario.onActivity { status=controller!!.status.value }
            inst.sendStatus(0,Bundle().apply { putString("stream","\nPHONE_LOCATION fix=$fixed; status=$status\n") })
        }
    }
}
