package com.radiosport.ninegradio

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.tabs.TabLayout
import com.google.android.material.slider.Slider
import com.radiosport.ninegradio.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceptionControlsTest {
    private class Parent(context: Context): FrameLayout(context) {
        var disallowed=false
        override fun requestDisallowInterceptTouchEvent(value: Boolean) { disallowed=value; super.requestDisallowInterceptTouchEvent(value) }
    }
    @Test fun sqlRemainsVisibleAndLongResultListsOwnOnlyTheirScrollableDrags() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var dialog: AlertDialog?=null
            var parent: Parent?=null
            var list: NestedResultListView?=null
            try {
                Thread.sleep(500)
                scenario.onActivity { a ->
                    val adapter=MainActivity::class.java.getDeclaredField("pagerAdapter").apply { isAccessible=true }.get(a) as ControlsPagerAdapter
                    a.findViewById<TabLayout>(R.id.controlTabLayout).getTabAt(adapter.positionOf(ControlsPagerAdapter.TAB_RF))!!.select()
                    val m=ViewModelProvider(a)[MainViewModel::class.java]
                    assertTrue("Fine waterfall default",m.fftSize.value>=2048)
                    parent=Parent(a)
                    list=NestedResultListView(a).apply { this.adapter=ArrayAdapter(a,android.R.layout.simple_list_item_1,(0..199).map { "Station $it" }) }
                    parent!!.addView(list,FrameLayout.LayoutParams(-1,400))
                    dialog=AlertDialog.Builder(a).setView(parent).create().also { it.show() }
                }
                Thread.sleep(250)
                scenario.onActivity { a ->
                    val adapter=MainActivity::class.java.getDeclaredField("pagerAdapter").apply { isAccessible=true }.get(a) as ControlsPagerAdapter
                    val sql=adapter.getViewAt(ControlsPagerAdapter.TAB_RF)!!.findViewById<Slider>(R.id.sliderSquelch)
                    assertTrue(sql.isEnabled)
                    assertEquals(View.VISIBLE,(sql.parent as View).visibility)
                    assertTrue(list!!.canScrollVertically(1))
                    fun touch(action: Int,y: Float) {
                        MotionEvent.obtain(SystemClock.uptimeMillis(),SystemClock.uptimeMillis(),action,100f,y,0).also { list!!.dispatchTouchEvent(it); it.recycle() }
                    }
                    touch(MotionEvent.ACTION_DOWN,200f); touch(MotionEvent.ACTION_MOVE,80f)
                    assertTrue("Result list must retain an upward drag",parent!!.disallowed)
                    touch(MotionEvent.ACTION_CANCEL,80f)
                    list!!.setSelection(0)
                }
                Thread.sleep(180)
                scenario.onActivity {
                    assertFalse(list!!.canScrollVertically(-1))
                    fun touch(action: Int,y: Float) {
                        MotionEvent.obtain(SystemClock.uptimeMillis(),SystemClock.uptimeMillis(),action,100f,y,0).also { list!!.dispatchTouchEvent(it); it.recycle() }
                    }
                    touch(MotionEvent.ACTION_DOWN,80f); touch(MotionEvent.ACTION_MOVE,240f)
                    assertFalse("At the top boundary, hand the downward drag to the panel",parent!!.disallowed)
                    touch(MotionEvent.ACTION_CANCEL,240f)
                }
            } finally { scenario.onActivity { dialog?.dismiss() } }
        }
    }
}
