package com.radiosport.ninegradio

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.ui.*
import org.maplibre.android.maps.MapView
import org.maplibre.android.camera.CameraPosition
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Alpha17InteractionTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private fun screenshot(name:String) {
        inst.uiAutomation.takeScreenshot()?.let { image ->
            File(context.cacheDir,name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
        }
    }
    private fun node(id:String)=inst.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
    private fun hold(view:View,checkDelay:Boolean=false) {
        val p=IntArray(2)
        inst.runOnMainSync { view.getLocationOnScreen(p) }
        val x=p[0]+view.width/2f; val y=p[1]+view.height/2f
        val start=SystemClock.uptimeMillis()
        MotionEvent.obtain(start,start,MotionEvent.ACTION_DOWN,x,y,0).also { inst.sendPointerSync(it); it.recycle() }
        Thread.sleep(1050)
        if(checkDelay) assertNull("No numeric editor before 1.5 seconds",node(context.resources.getResourceName(R.id.plotValueInput)))
        Thread.sleep(650)
        MotionEvent.obtain(start,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x,y,0).also { inst.sendPointerSync(it); it.recycle() }
        Thread.sleep(250)
        assertNotNull("Long hold opens numeric input",node(context.resources.getResourceName(R.id.plotValueInput)))
    }
    private fun enter(value:String) {
        val input=node(context.resources.getResourceName(R.id.plotValueInput))!!
        assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value) }))
        assertTrue(node("android:id/button1")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        Thread.sleep(350)
    }
    @Test fun spanBwDirectEntryAndStepOnlyTune() {
        ReceiverTestPreferences(context).use {
            ActivityScenario.launch(MainActivity::class.java).use { main ->
                Thread.sleep(3000)
                main.onActivity { ViewModelProvider(it)[MainViewModel::class.java].setReceiverEnabled(false) }
                Thread.sleep(600)
                lateinit var span:View; lateinit var bw:View
                main.onActivity { a ->
                    val vm=ViewModelProvider(a)[MainViewModel::class.java]
                    vm.setAutoDemod(false); vm.setDemodMode(DemodMode.WFM); vm.setFrequency(100000000); vm.setIfBandwidth(150000)
                    a.findViewById<FrequencyView>(R.id.frequencyView).setTuningStep(10000)
                    val adapter=MainActivity::class.java.getDeclaredField("pagerAdapter").apply { isAccessible=true }.get(a) as ControlsPagerAdapter
                    a.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.controlViewPager).setCurrentItem(adapter.positionOf(ControlsPagerAdapter.TAB_TUNE),false)
                    span=a.findViewById(R.id.tvPlotZoom); bw=a.findViewById(R.id.tvTuningStep)
                    assertFalse(a.findViewById<View>(R.id.bandwidthParameter).isShown)
                    assertFalse(a.findViewById<View>(R.id.tuningParameterSelector).isShown)
                    assertFalse(a.findViewById<View>(R.id.tvTuneFrequency).isShown)
                    assertTrue(a.findViewById<View>(R.id.stepPicker).isShown)
                    assertTrue(a.findViewById<TuningDialView>(R.id.tuningDial).showCurrentFrequency)
                    a.findViewById<View>(R.id.btnStepUp).performClick()
                    assertEquals(175000,vm.ifBandwidthHz.value)
                    assertEquals(10000L,a.findViewById<FrequencyView>(R.id.frequencyView).tuningStepHz)
                    assertEquals(100000000L,vm.centerFreqHz.value)
                    span.performClick()
                }
                Thread.sleep(400); screenshot("alpha17-tune.png")
                hold(span,true); screenshot("alpha17-span-entry.png")
                // Full RTL span starts in MHz. Enter half of that span with exact units.
                var rate=0
                main.onActivity { rate=ViewModelProvider(it)[MainViewModel::class.java].sampleRate.value }
                val divider=if(rate>=1000000) 1000000.0 else if(rate>=1000) 1000.0 else 1.0
                enter((rate/2.0/divider).toString())
                main.onActivity { assertEquals(rate/2L,it.findViewById<SpectrumView>(R.id.spectrumView).visibleBandwidthHz()) }
                hold(bw); enter("125")
                main.onActivity { assertEquals(125000,ViewModelProvider(it)[MainViewModel::class.java].ifBandwidthHz.value) }
                hold(bw); enter("99999")
                assertNotNull("Invalid BW leaves input open",node(context.resources.getResourceName(R.id.plotValueInput)))
                main.onActivity { assertEquals(125000,ViewModelProvider(it)[MainViewModel::class.java].ifBandwidthHz.value) }
                node("android:id/button2")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
    }
    private fun gesture(points:List<Pair<Float,Float>>,two:Boolean) {
        val start=SystemClock.uptimeMillis()
        fun event(action:Int,c:List<Pair<Float,Float>>) {
            val properties=Array(c.size) { i -> MotionEvent.PointerProperties().apply { id=i; toolType=MotionEvent.TOOL_TYPE_FINGER } }
            val coords=Array(c.size) { i -> MotionEvent.PointerCoords().apply { x=c[i].first; y=c[i].second; pressure=1f; size=1f } }
            MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,c.size,properties,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0).also { inst.sendPointerSync(it); it.recycle() }
        }
        event(MotionEvent.ACTION_DOWN,listOf(points.first()))
        if(two) event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),listOf(points.first(),points.last()))
        if(two) {
            val center=(points.first().first+points.last().first)/2f; val y=points.first().second
            for(i in 1..12) { Thread.sleep(24); val distance=45f+(105f-45f)*i/12; val scale=context.resources.displayMetrics.density; event(MotionEvent.ACTION_MOVE,listOf(center-distance*scale to y,center+distance*scale to y)) }
            val scale=context.resources.displayMetrics.density
            event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),listOf(center-105f*scale to y,center+105f*scale to y))
            event(MotionEvent.ACTION_UP,listOf(center-105f*scale to y))
        } else {
            val from=points.first(); val to=points.last()
            for(i in 1..12) { Thread.sleep(24); event(MotionEvent.ACTION_MOVE,listOf(from.first+(to.first-from.first)*i/12 to from.second+(to.second-from.second)*i/12)) }
            event(MotionEvent.ACTION_UP,listOf(points.last()))
        }
    }
    @Test fun inlineMapPanAndPinchDoNotScrollThePanel() {
        ReceiverTestPreferences(context).use {
            ActivityScenario.launch(MainActivity::class.java).use { main ->
                Thread.sleep(3000)
                main.onActivity { a ->
                    ViewModelProvider(a)[MainViewModel::class.java].apply { setReceiverEnabled(false); setAutoDemod(false); setDemodMode(DemodMode.ADSB) }
                }
                Thread.sleep(800)
                main.onActivity { a ->
                    a.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.btnAdsbPanelMap).isChecked=true
                    a.findViewById<View>(R.id.btnAdsbPanelFull).performClick()
                }
                Thread.sleep(1800)
                lateinit var panel:RadioMapPanel
                lateinit var mapView:MapView
                val area=Rect(); var scrollY=0; var before:CameraPosition?=null
                main.onActivity { a ->
                    panel=MainActivity::class.java.getDeclaredField("adsbMapPanel").apply { isAccessible=true }.get(a) as RadioMapPanel
                    assertNotNull(panel.nativeMap)
                    fun find(v:View):MapView? {
                        if(v is MapView) return v
                        if(v is android.view.ViewGroup) for(i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
                        return null
                    }
                    mapView=find(panel)!!
                    mapView.getGlobalVisibleRect(area)
                    assertTrue(area.height()>300)
                    before=panel.nativeMap!!.cameraPosition
                    scrollY=a.findViewById<androidx.core.widget.NestedScrollView>(R.id.controlsScrollView).scrollY
                }
                val density=context.resources.displayMetrics.density
                val cx=area.exactCenterX(); val cy=area.exactCenterY()
                gesture(listOf(cx+45*density to cy+25*density,cx-45*density to cy-25*density),false)
                Thread.sleep(600)
                var zoom=0.0
                main.onActivity { a ->
                    val after=panel.nativeMap!!.cameraPosition
                    assertTrue("Native map pan changes camera",kotlin.math.abs(after.target!!.longitude-before!!.target!!.longitude)>0.001 || kotlin.math.abs(after.target!!.latitude-before!!.target!!.latitude)>0.001)
                    assertEquals(scrollY,a.findViewById<androidx.core.widget.NestedScrollView>(R.id.controlsScrollView).scrollY)
                    zoom=after.zoom
                }
                gesture(listOf(cx-45*density to cy,cx+45*density to cy),true)
                Thread.sleep(700)
                main.onActivity { a ->
                    assertTrue("Native pinch zoom works",panel.nativeMap!!.cameraPosition.zoom>zoom+0.25)
                    assertEquals(scrollY,a.findViewById<androidx.core.widget.NestedScrollView>(R.id.controlsScrollView).scrollY)
                }
                screenshot("alpha17-map-gesture.png")
            }
        }
    }
    @Test fun arrowDoesNotStealTabTouches() {
        ReceiverTestPreferences(context).use {
            ActivityScenario.launch(MainActivity::class.java).use { main ->
                Thread.sleep(3000)
                main.onActivity { ViewModelProvider(it)[MainViewModel::class.java].apply { setReceiverEnabled(false); setAutoDemod(false); setDemodMode(DemodMode.WFM) } }
                Thread.sleep(500)
                var target=-1; var x=0f; var y=0f
                main.onActivity { a ->
                    val handle=a.findViewById<View>(R.id.sheetToggle)
                    val handleRect=Rect(); handle.getGlobalVisibleRect(handleRect)
                    val tabs=a.findViewById<com.google.android.material.tabs.TabLayout>(R.id.controlTabLayout)
                    val current=a.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.controlViewPager).currentItem
                    for(i in 0 until tabs.tabCount) {
                        val tab=tabs.getTabAt(i)!!.view
                        val rect=Rect()
                        if(i!=current && tab.isShown && tab.getGlobalVisibleRect(rect)) {
                            val hit=Rect(rect)
                            if(hit.intersect(handleRect) && hit.width()>3) {
                                target=i; x=hit.exactCenterX(); y=rect.top+12*a.resources.displayMetrics.density
                                break
                            }
                        }
                    }
                    assertTrue("A tab lies under the old handle touch bounds",target>=0)
                }
                val start=SystemClock.uptimeMillis()
                MotionEvent.obtain(start,start,MotionEvent.ACTION_DOWN,x,y,0).also { inst.sendPointerSync(it); it.recycle() }
                Thread.sleep(60)
                MotionEvent.obtain(start,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x,y,0).also { inst.sendPointerSync(it); it.recycle() }
                Thread.sleep(600)
                main.onActivity { a ->
                    assertEquals("Tab receives touches below the arrow",target,a.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.controlViewPager).currentItem)
                    assertEquals(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED,com.google.android.material.bottomsheet.BottomSheetBehavior.from(a.findViewById<View>(R.id.controlsBottomSheet)).state)
                    a.findViewById<View>(R.id.sheetToggle).performClick()
                }
                Thread.sleep(500)
                main.onActivity { a ->
                    assertEquals(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED,com.google.android.material.bottomsheet.BottomSheetBehavior.from(a.findViewById<View>(R.id.controlsBottomSheet)).state)
                }
                screenshot("alpha17-arrow-touch.png")
            }
        }
    }
}
