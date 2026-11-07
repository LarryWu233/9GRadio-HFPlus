package com.radiosport.ninegradio

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.slider.Slider
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.radiosport.ninegradio.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InlineDisplayControlsTest {
    private fun capture(view: View,name: String) {
        val content=if(view is android.widget.ScrollView) view.getChildAt(0) else view
        val bitmap=android.graphics.Bitmap.createBitmap(content.width,content.height,android.graphics.Bitmap.Config.ARGB_8888)
        content.draw(android.graphics.Canvas(bitmap))
        java.io.File(view.context.cacheDir,name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
        bitmap.recycle()
    }
    @Test fun levelsStayInlineAndOriginalPaletteSelectorsPersist() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        ReceiverTestPreferences(context).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { ViewModelProvider(it)[MainViewModel::class.java].setReceiverEnabled(false) }
                Thread.sleep(700)
                var spectrumLow=0
                scenario.onActivity { activity ->
                    val adapter=MainActivity::class.java.getDeclaredField("pagerAdapter").apply { isAccessible=true }.get(activity) as ControlsPagerAdapter
                    activity.findViewById<TabLayout>(R.id.controlTabLayout).getTabAt(adapter.positionOf(ControlsPagerAdapter.TAB_DISPLAY))!!.select()
                }
                Thread.sleep(300)
                scenario.onActivity { activity ->
                    val selector=activity.findViewById<SegmentedSelectorView>(R.id.levelTargetSelector)
                    fun choose(label: String) {
                        val matches=ArrayList<View>(); selector.findViewsWithText(matches,label,View.FIND_VIEWS_WITH_TEXT)
                        matches.single().performClick()
                    }
                    val auto=activity.findViewById<MaterialSwitch>(R.id.switchInlineAutoLevels)
                    val low=activity.findViewById<Slider>(R.id.sliderInlineMinLevel)
                    fun setLow(fraction: Float) {
                        assertTrue(low.isEnabled)
                        val time=SystemClock.uptimeMillis()
                        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                            val event=MotionEvent.obtain(time,time+40,action,low.width*fraction,low.height/2f,0)
                            low.onTouchEvent(event); event.recycle()
                        }
                    }
                    choose("频谱")
                    if(auto.isChecked) auto.performClick()
                    setLow(0.25f)
                    val prefs=PreferenceManager.getDefaultSharedPreferences(activity)
                    spectrumLow=prefs.getInt("pref_db_min",0)
                    assertTrue(spectrumLow>120)
                    choose("瀑布图")
                    if(auto.isChecked) auto.performClick()
                    setLow(0.4f)
                    assertTrue(prefs.getBoolean("waterfall_manual_levels",false))
                    assertEquals(spectrumLow,prefs.getInt("pref_db_min",0))
                    auto.performClick()
                    assertTrue(prefs.getBoolean("pref_waterfall_auto_stretch",false))
                    assertFalse(low.isEnabled)
                    choose("频谱")
                    assertFalse(auto.isChecked)
                    assertEquals(-spectrumLow.toFloat(),low.value,0f)
                    assertTrue(WindowInspector.getGlobalWindowViews().none { it.findViewById<Button>(android.R.id.button1)?.text=="完成" })
                    val theme=activity.findViewById<android.widget.Spinner>(R.id.spinnerTheme)
                    val palette=activity.findViewById<android.widget.Spinner>(R.id.spinnerPalette)
                    assertEquals(View.VISIBLE,(theme.parent as View).visibility)
                    assertEquals(View.VISIBLE,(palette.parent as View).visibility)
                    assertEquals(10,theme.count); assertEquals(11,palette.count)
                    theme.setSelection(9); palette.setSelection(10)
                }
                Thread.sleep(200)
                scenario.onActivity { a ->
                    val prefs=PreferenceManager.getDefaultSharedPreferences(a)
                    assertEquals("Sakura",prefs.getString("pref_spectrum_theme",null))
                    assertEquals(WaterfallView.Companion.Palette.values()[10].name,prefs.getString("pref_waterfall_palette",null))
                    val slider=a.findViewById<Slider>(R.id.sliderFillOpacity)
                    assertEquals(View.VISIBLE,(slider.parent as View).visibility)
                    capture((a.findViewById<View>(R.id.spinnerTheme).parent.parent.parent as View),"alpha13-original-palette.png")
                }
                scenario.recreate()
                Thread.sleep(350)
                scenario.onActivity { a ->
                    assertEquals(9,a.findViewById<android.widget.Spinner>(R.id.spinnerTheme).selectedItemPosition)
                    assertEquals(10,a.findViewById<android.widget.Spinner>(R.id.spinnerPalette).selectedItemPosition)
                    capture(a.window.decorView,"alpha13-inline-levels.png")
                }
            }
        }
    }
}
