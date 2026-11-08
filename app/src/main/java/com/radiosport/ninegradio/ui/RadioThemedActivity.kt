package com.radiosport.ninegradio.ui

import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import com.radiosport.ninegradio.R

object UiThemes {
    data class Preset(val id:String,val label:String,val dark:Boolean,val style:Int)
    val presets=listOf(
        Preset("classic","经典",true,R.style.Theme_NineGRadio_Classic),
        Preset("ocean","海湾",true,R.style.Theme_NineGRadio_Ocean),
        Preset("sage","鼠尾草",true,R.style.Theme_NineGRadio_Sage),
        Preset("black","纯黑",true,R.style.Theme_NineGRadio_Black),
        Preset("carbon","碳蓝",true,R.style.Theme_NineGRadio_Carbon),
        Preset("arctic","冰川",true,R.style.Theme_NineGRadio_Arctic),
        Preset("soft","柔暗",true,R.style.Theme_NineGRadio_Soft))
    fun current(context:Context):Preset {
        val id=PreferenceManager.getDefaultSharedPreferences(context).getString("pref_ui_theme","classic")
        return presets.firstOrNull { it.id==id } ?: presets.first()
    }
    fun color(context:Context,attr:Int):Int = TypedValue().also { context.theme.resolveAttribute(attr,it,true) }.data
}

open class RadioThemedActivity:AppCompatActivity() {
    private var appliedTheme=""
    override fun onCreate(state:Bundle?) {
        val preset=UiThemes.current(this); appliedTheme=preset.id
        delegate.localNightMode=if(preset.dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        setTheme(preset.style)
        super.onCreate(state)
    }
    override fun onContentChanged() {
        super.onContentChanged()
        if(this is MainActivity) return // Main owns its spectrum/panel insets.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window,false)
        window.statusBarColor=android.graphics.Color.TRANSPARENT
        window.navigationBarColor=android.graphics.Color.TRANSPARENT
        if(android.os.Build.VERSION.SDK_INT>=29) window.isStatusBarContrastEnforced=false
        androidx.core.view.WindowCompat.getInsetsController(window,window.decorView).isAppearanceLightStatusBars=false
        val root=findViewById<android.view.ViewGroup>(android.R.id.content)?.getChildAt(0) ?: return
        val left=root.paddingLeft;val top=root.paddingTop;val right=root.paddingRight;val bottom=root.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            view.setPadding(left+bars.left,top+bars.top,right+bars.right,bottom+bars.bottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(root)
    }
    override fun onResume() {
        super.onResume()
        if(appliedTheme!=UiThemes.current(this).id) recreate()
    }
}

/** Native text controls created in Kotlin use the same palette as XML controls. */
fun android.widget.TextView.setRadioTextColor(attr:Int) { setTextColor(UiThemes.color(context,attr)) }
