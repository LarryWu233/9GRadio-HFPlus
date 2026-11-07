package com.radiosport.ninegradio

import android.Manifest
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.content.*
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.ui.*
import com.radiosport.ninegradio.usb.RtlSdrService
import com.google.android.material.materialswitch.MaterialSwitch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AudioContentUiTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private fun <T> field(a: MainActivity,name: String): T = MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(a) as T
    private fun views(root: View): List<View> = listOf(root)+(if(root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList())
    private fun screenshot(name: String) { inst.uiAutomation.takeScreenshot()?.let { b -> File(context.cacheDir,name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) } } }
    @Test fun tabsSourcesModeRestoreAndEibiTune() {
        ReceiverTestPreferences(context).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                Thread.sleep(3000)
                scenario.onActivity { a ->
                    val vm=ViewModelProvider(a)[MainViewModel::class.java]
                    vm.setReceiverEnabled(false); vm.setAutoDemod(false)
                    val controller=field<ContentModuleController>(a,"contentController")
                    controller.select(16)
                    assertEquals(14_230_000L,vm.centerFreqHz.value)
                    assertEquals(DemodMode.USB,vm.demodMode.value)
                    assertEquals(19,ControlsPagerAdapter.TAB_COUNT)
                    val adapter=field<ControlsPagerAdapter>(a,"pagerAdapter")
                    for(i in 15..18) assertNotNull(adapter.getViewAt(i))
                    val panel=adapter.getViewAt(16)!!
                    assertEquals(1,views(panel).filterIsInstance<MaterialSwitch>().size)
                    assertTrue(views(panel).filterIsInstance<TextView>().any { it.text=="软件内" })
                    assertTrue(views(panel).filterIsInstance<TextView>().any { it.text=="麦克风" })
                    assertEquals(1,views(panel).filterIsInstance<RadioDataTable>().size)
                    // Starting software decoding while CLOSED must not open hardware behind the UI.
                    views(panel).filterIsInstance<Button>().first { it.text=="开始" }.performClick()
                    assertFalse(field<RtlSdrService>(a,"sdrService").audioContent.state.value.running)
                    assertEquals("请先打开接收机",field<RtlSdrService>(a,"sdrService").audioContent.state.value.status)
                }
                Thread.sleep(400); screenshot("alpha18-sstv.png")
                scenario.onActivity { a -> field<ContentModuleController>(a,"contentController").select(17) }
                Thread.sleep(350)
                scenario.onActivity { a ->
                    val vm=ViewModelProvider(a)[MainViewModel::class.java]
                    assertEquals(145_800_000L,vm.centerFreqHz.value); assertEquals(DemodMode.NFM,vm.demodMode.value)
                    field<ContentModuleController>(a,"contentController").select(18)
                }
                Thread.sleep(600); screenshot("alpha18-eibi.png")
                lateinit var table: RadioDataTable
                var expectedHz=0L
                var expectedIndex=0
                scenario.onActivity { a ->
                    val page=field<ControlsPagerAdapter>(a,"pagerAdapter").getViewAt(18)!!
                    views(page).filterIsInstance<MaterialSwitch>().first { it.text=="当前 UTC" }.isChecked=false
                    views(page).filterIsInstance<EditText>().single().setText("CHN")
                    table=views(page).filterIsInstance<RadioDataTable>().single()
                }
                Thread.sleep(400)
                val data=EibiRepository(context).load()
                val matching=data.entries.filter { "${it.station} ${it.language} ${it.target} ${it.country}".contains("CHN",true) }.sortedBy { it.hz }
                scenario.onActivity { a ->
                    val vm=ViewModelProvider(a)[MainViewModel::class.java]
                    expectedIndex=matching.indexOfFirst { it.hz in 5_000_000L..30_000_000L && vm.capabilities.value.supportsFrequency(it.hz) }
                    assertTrue(expectedIndex>=0)
                    expectedHz=matching[expectedIndex].hz
                    table.onRowClick!!.invoke(expectedIndex)
                    assertEquals(expectedHz,ViewModelProvider(a)[MainViewModel::class.java].centerFreqHz.value)
                    assertTrue(ModulePreferences(a).order.containsAll(listOf(15,16,17,18)))
                    val controller=field<ContentModuleController>(a,"contentController")
                    controller.explicitRfMode(DemodMode.CWR)
                    ViewModelProvider(a)[MainViewModel::class.java].setDemodMode(DemodMode.CWR)
                }
                Thread.sleep(350); screenshot("alpha18-cw.png")
                scenario.onActivity { a ->
                    field<ContentModuleController>(a,"contentController").select(18)
                    val page=field<ControlsPagerAdapter>(a,"pagerAdapter").getViewAt(18)!!
                    views(page).filterIsInstance<EditText>().single().setText("")
                    views(page).filterIsInstance<Button>().first { it.text=="全部语言" }.performClick()
                }
                Thread.sleep(200)
                var chinese=inst.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("M · 中文").firstOrNull()
                var pages=0
                while(chinese==null && pages++<60) {
                    val listId=context.resources.getResourceName(androidx.appcompat.R.id.select_dialog_listview)
                    val list=inst.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByViewId(listId).firstOrNull() ?: break
                    if(!list.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
                    Thread.sleep(100)
                    chinese=inst.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("M · 中文").firstOrNull()
                }
                assertNotNull(chinese)
                assertTrue(chinese!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                Thread.sleep(350)
                scenario.onActivity { a ->
                    val page=field<ControlsPagerAdapter>(a,"pagerAdapter").getViewAt(18)!!
                    val result=views(page).filterIsInstance<RadioDataTable>().single()
                    val rows=RadioDataTable::class.java.getDeclaredField("rows").apply { isAccessible=true }.get(result) as List<RadioDataTable.Row>
                    assertTrue(rows.isNotEmpty()); assertTrue(rows.all { it.values[3]=="M" })
                }
                screenshot("alpha18-eibi-language.png")
            }
        }
    }
    @Test fun microphoneRequiresPermissionAndStopsWhenBackgrounded() {
        val granted=androidx.core.content.ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED
        ReceiverTestPreferences(context).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                Thread.sleep(3000)
                inst.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.RECORD_AUDIO)
                lateinit var receiver: AudioContentReceiver
                var before=0L
                scenario.onActivity { a ->
                    val controller=field<ContentModuleController>(a,"contentController")
                    val vm=ViewModelProvider(a)[MainViewModel::class.java]; vm.setReceiverEnabled(false)
                    before=vm.centerFreqHz.value
                    controller.select(15)
                    val panel=field<ControlsPagerAdapter>(a,"pagerAdapter").getViewAt(15)!!
                    views(panel).filterIsInstance<MaterialSwitch>().single().isChecked=true
                    views(panel).filterIsInstance<Button>().first { it.text=="开始" }.performClick()
                    receiver=field<RtlSdrService>(a,"sdrService").audioContent
                }
                val end=System.currentTimeMillis()+5000
                while(receiver.state.value.sampleCount<4800 && System.currentTimeMillis()<end) Thread.sleep(50)
                assertTrue(receiver.state.value.status,receiver.state.value.running)
                assertTrue(receiver.state.value.sampleCount>0)
                assertEquals(ContentInput.MICROPHONE,receiver.state.value.config?.input)
                scenario.onActivity { a ->
                    assertEquals(before,ViewModelProvider(a)[MainViewModel::class.java].centerFreqHz.value)
                }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                Thread.sleep(500)
                assertFalse(receiver.state.value.running)
                val count=receiver.state.value.sampleCount
                Thread.sleep(300); assertEquals(count,receiver.state.value.sampleCount)
                assertEquals("麦克风已暂停",receiver.state.value.status)
            }
        }
        // Revoking a permission kills the instrumented process. The host runner restores it after completion.
        File(context.cacheDir,"alpha18-microphone-permission-before.txt").writeText(granted.toString())
    }
}
