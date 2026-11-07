package com.radiosport.ninegradio

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicitly invoked research probe for the installed upstream app, not a release regression test. */
@RunWith(AndroidJUnit4::class)
class OriginalRadioProbeTest {
    @Test fun inspectOrActivateOriginalControl() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        val automation=inst.uiAutomation
        automation.serviceInfo=automation.serviceInfo.apply {
            flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        Thread.sleep(600)
        var active=automation.rootInActiveWindow
        repeat(10) { if(active==null) { Thread.sleep(100); active=automation.rootInActiveWindow } }
        val root=active ?: error("No active window")
        val nodes=ArrayList<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { nodes+=node; for(i in 0 until node.childCount) node.getChild(i)?.let { visit(it) } }
        visit(root)
        assertTrue(nodes.any { it.packageName?.toString()=="com.radiosport.ninegradio" })
        val output=nodes.filter { !it.text.isNullOrBlank() || it.isClickable }.map {
            mapOf("text" to it.text?.toString(),"id" to it.viewIdResourceName,"description" to it.contentDescription?.toString(),"clickable" to it.isClickable)
        }
        File(inst.targetContext.cacheDir,"alpha14-original-ui.json").writeText(Gson().toJson(output))
        val text=args.getString("tapText"); val id=args.getString("tapId"); val desc=args.getString("tapDesc")
        if(text!=null || id!=null || desc!=null) {
            val target=nodes.filter { it.packageName?.toString()=="com.radiosport.ninegradio" &&
                (text==null || it.text?.toString()==text) && (id==null || it.viewIdResourceName==id) &&
                (desc==null || it.contentDescription?.toString()==desc) }
            assertEquals("Choose exactly one upstream control",1,target.size)
            assertTrue(target.single().performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        inst.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(inst.targetContext.cacheDir,"alpha14-original-probe.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        }
    }
}
