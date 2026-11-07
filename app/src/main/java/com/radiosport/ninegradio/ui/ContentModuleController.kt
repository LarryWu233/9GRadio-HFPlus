package com.radiosport.ninegradio.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.radiosport.ninegradio.R
import com.radiosport.ninegradio.content.*
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.usb.RtlSdrService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.time.*

/** Mode-specific content pages; RF demodulation and audio decoding have distinct lifetimes. */
class ContentModuleController(private val activity: MainActivity, private val pager: ControlsPagerAdapter,
    private val viewModel: MainViewModel, private val getService: () -> RtlSdrService?,
    private val showModule: (Int) -> Unit, private val visibility: (Int,Boolean) -> Unit,
    private val resize: () -> Unit, private val expand: () -> Unit,
    private val requestMicrophone: () -> Unit, private val requestWav: () -> Unit,
    private val requestCsv: () -> Unit) {
    private val prefs=activity.getSharedPreferences("audio_content",0)
    private val panels=mutableMapOf<AudioContentKind,AudioPanel>()
    private var selected: Int?=prefs.getInt("selected",0).takeIf { it in 15..18 }
    private var frequencySaveJob: Job?=null
    private var permissionKind: AudioContentKind?=null
    private var permissionStart=false
    private var fileKind: AudioContentKind?=null
    private var collectJob: Job?=null
    private var observed: RtlSdrService?=null
    private var eibi: EibiPanel?=null
    private var restoring=false
    private fun dp(n: Int)=(n*activity.resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float=12f)=TextView(activity).apply {
        text=value; textSize=size; setTextColor(UiThemes.color(context,R.attr.radioText)); gravity=Gravity.CENTER_VERTICAL
        setPadding(dp(4),dp(4),dp(4),dp(4))
    }
    private fun button(value: String, click: () -> Unit)=MaterialButton(activity).apply {
        text=value; textSize=12f; isAllCaps=false; minWidth=0; minimumWidth=0; insetTop=0; insetBottom=0
        cornerRadius=dp(4); setPadding(dp(5),0,dp(5),0)
        backgroundTintList=android.content.res.ColorStateList.valueOf(UiThemes.color(context,R.attr.radioSurface))
        strokeColor=android.content.res.ColorStateList.valueOf(UiThemes.color(context,R.attr.radioOutline)); strokeWidth=dp(1).coerceAtLeast(1)
        setTextColor(UiThemes.color(context,R.attr.radioAccent)); setOnClickListener { click() }
    }
    private fun row(vararg views: View)=LinearLayout(activity).apply {
        orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
        views.forEach { addView(it,LinearLayout.LayoutParams(0,dp(44),1f)) }
    }
    private fun column()=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),dp(6),dp(10),dp(10)) }
    fun setup() {
        if(activity.isFinishing || activity.isDestroyed) return
        if((15..18).any { pager.getViewAt(it)==null }) { activity.window.decorView.postDelayed({setup()},60); return }
        AudioContentKind.entries.forEach { kind ->
            val root=pager.getViewAt(id(kind)) as FrameLayout
            val panel=AudioPanel(kind); root.addView(panel.root); panels[kind]=panel
        }
        val eibiRoot=pager.getViewAt(18) as FrameLayout
        eibi=EibiPanel().also { eibiRoot.addView(it.root); it.load() }
        val modeRoot=pager.getViewAt(ControlsPagerAdapter.TAB_MODE)
        val host=modeRoot?.findViewById<LinearLayout>(R.id.contentModeButtons)
        host?.addView(row(button("SSTV") { select(16) },button("SAT") { select(17) },button("EiBi") { select(18) }))
        bindService(); refreshVisibility(); modeChanged(viewModel.demodMode.value)
    }
    private fun id(kind: AudioContentKind)=15+kind.ordinal
    fun bindService() {
        val service=getService() ?: return
        if(observed === service) return
        observed=service; collectJob?.cancel()
        collectJob=activity.lifecycleScope.launch { service.audioContent.state.collectLatest { state ->
            panels.values.forEach { it.render(state) }; refreshVisibility()
        } }
    }
    private fun refreshVisibility() {
        val current=getService()?.audioContent?.state?.value
        for(i in 15..18) visibility(i,ModulePreferences(activity).visible(i) &&
            (selected==i || (i==15 && viewModel.demodMode.value in listOf(DemodMode.CW,DemodMode.CWR)) ||
                (current?.running==true && current.config?.kind?.let { id(it)==i }==true)))
    }
    fun explicitRfMode(mode: DemodMode) {
        if(restoring) return
        selected=if(mode in listOf(DemodMode.CW,DemodMode.CWR)) 15 else null
        prefs.edit().putInt("selected",selected ?: 0).apply()
        if(getService()?.audioContent?.state?.value?.config?.input == ContentInput.SOFTWARE) getService()?.audioContent?.stop()
        refreshVisibility()
        if(selected==15) showModule(15)
    }
    fun modeChanged(mode: DemodMode) {
        refreshVisibility()
        if(mode in listOf(DemodMode.CW,DemodMode.CWR) && selected==null) { selected=15; refreshVisibility() }
    }
    fun select(module: Int) {
        selected=module
        prefs.edit().putInt("selected",module).apply()
        if(module in 16..17) {
            val kind=if(module==16) AudioContentKind.SSTV else AudioContentKind.SAT
            val panel=panels[kind] ?: return
            if(!panel.microphone.isChecked) prepareRf(kind)
        }
        refreshVisibility(); showModule(module); resize()
    }
    private fun prepareRf(kind: AudioContentKind, explicitFrequency: Long?=null) {
        if(kind==AudioContentKind.CW) return
        val key="${kind}_frequency"
        val hz=explicitFrequency ?: if(viewModel.modeStationMemory.value)
            prefs.getLong(key,if(kind==AudioContentKind.SAT) 145_800_000 else 14_230_000)
            else viewModel.centerFreqHz.value
        restoring=true
        try {
            if(viewModel.setFrequency(hz)) {
                viewModel.finishTuning()
                if(explicitFrequency!=null && viewModel.modeStationMemory.value)
                    prefs.edit().putLong(key,hz).apply()
            }
            viewModel.setDemodMode(if(kind==AudioContentKind.SAT) DemodMode.NFM else DemodMode.USB,automatic=true)
            viewModel.setIfBandwidth(if(kind==AudioContentKind.SAT) 50_000 else 3_000)
        } finally { restoring=false }
    }
    fun frequencyChanged(hz: Long) {
        val kind=when(selected) { 16 -> AudioContentKind.SSTV; 17 -> AudioContentKind.SAT; else -> null }
        frequencySaveJob?.cancel()
        if(!restoring && viewModel.modeStationMemory.value && kind!=null && panels[kind]?.microphone?.isChecked==false && viewModel.capabilities.value.supportsFrequency(hz))
            frequencySaveJob=activity.lifecycleScope.launch {
                delay(400)
                if(viewModel.modeStationMemory.value) prefs.edit().putLong("${kind}_frequency",hz).apply()
            }
        eibi?.frequencyChanged(hz)
    }

    fun freezeStationMemory() {
        frequencySaveJob?.cancel()
        val kind=when(selected) { 16 -> AudioContentKind.SSTV; 17 -> AudioContentKind.SAT; else -> null }
        if(viewModel.modeStationMemory.value && kind!=null && panels[kind]?.microphone?.isChecked==false &&
            viewModel.capabilities.value.supportsFrequency(viewModel.centerFreqHz.value))
            prefs.edit().putLong("${kind}_frequency",viewModel.centerFreqHz.value).apply()
    }

    fun stationMemoryChanged(enabled: Boolean) {
        val kind=when(selected) { 16 -> AudioContentKind.SSTV; 17 -> AudioContentKind.SAT; else -> null }
        if(enabled && kind!=null && panels[kind]?.microphone?.isChecked==false) prepareRf(kind)
    }
    fun onBackground() { getService()?.audioContent?.stopMicrophone() }
    fun microphoneResult(granted: Boolean) {
        val kind=permissionKind ?: return; permissionKind=null
        val panel=panels[kind] ?: return
        panel.ignore=true; panel.microphone.isChecked=granted; panel.ignore=false
        if(granted && permissionStart) panel.start()
        else if(!granted) Toast.makeText(activity,"未授予麦克风权限",Toast.LENGTH_SHORT).show()
        permissionStart=false
    }
    fun wavResult(uri: Uri?) {
        val kind=fileKind ?: return; fileKind=null
        if(uri!=null) panels[kind]?.start(uri)
    }
    fun csvResult(uri: Uri?) { if(uri!=null) eibi?.import(uri) }
    private inner class AudioPanel(val kind: AudioContentKind) {
        val root=column()
        val microphone=SwitchMaterial(activity).apply { contentDescription="软件内 / 麦克风"; gravity=Gravity.CENTER }
        var ignore=false
        val status=label("未启动")
        val details=label("",11f)
        val action=button("开始") { val state=getService()?.audioContent?.state?.value
            if(state?.running==true && state.config?.kind==kind) getService()?.audioContent?.stop() else start() }
        val option=button("Auto") { chooseOptions() }
        val transcript=label("",14f).apply { typeface=Typeface.MONOSPACE; setTextIsSelectable(true); gravity=Gravity.TOP; setPadding(dp(8),dp(8),dp(8),dp(8)); setBackgroundResource(R.drawable.compact_section_card) }
        val image=ImageView(activity).apply { scaleType=ImageView.ScaleType.FIT_CENTER; setBackgroundColor(0xff000000.toInt()); contentDescription="接收图像" }
        val history=RadioDataTable(activity)
        var files=emptyList<java.io.File>()
        var mode=prefs.getString("${kind}_sstv","Auto") ?: "Auto"
        var apt=prefs.getBoolean("sat_apt",false)
        var wpm=prefs.getInt("cw_wpm",0)
        var lastImage: android.graphics.Bitmap?=null
        init {
            root.addView(LinearLayout(activity).apply {
                gravity=Gravity.CENTER; orientation=LinearLayout.HORIZONTAL
                addView(label("软件内"),LinearLayout.LayoutParams(-2,dp(44)))
                addView(microphone,LinearLayout.LayoutParams(-2,dp(44)).apply { leftMargin=dp(12); rightMargin=dp(12) })
                addView(label("麦克风"),LinearLayout.LayoutParams(-2,dp(44)))
            })
            root.addView(row(action,option,button("导入 WAV") { fileKind=kind; requestWav() }))
            root.addView(status); root.addView(details)
            if(kind==AudioContentKind.CW) {
                root.addView(androidx.core.widget.NestedScrollView(activity).apply { addView(transcript) },LinearLayout.LayoutParams(-1,dp(190)))
                root.addView(row(button("复制") { activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("CW",transcript.text)) },
                    button("清空") { getService()?.audioContent?.clearText() },button("保存") { save() }))
            } else {
                root.addView(image,LinearLayout.LayoutParams(-1,dp(200)))
                root.addView(row(button("保存") { save() },button("展开") { expand() },
                    button(if(kind==AudioContentKind.SAT) "接收目标" else "接收频率") { presets() }))
            }
            history.setColumns(listOf(RadioDataTable.Column("记录",240),RadioDataTable.Column("大小",70)))
            root.addView(history,LinearLayout.LayoutParams(-1,dp(138)))
            history.onRowClick={ index -> files.getOrNull(index)?.let { openFile(it) } }
            microphone.setOnCheckedChangeListener { _, checked ->
                if(!ignore) {
                    getService()?.audioContent?.stop()
                    if(checked && ContextCompat.checkSelfPermission(activity,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
                        permissionKind=kind; permissionStart=false; requestMicrophone()
                    }
                }
            }
            updateOption(); renderFiles(); ControlColors.apply(root,ControlColors.Group.RADIO)
        }
        fun start(uri: Uri?=null) {
            bindService()
            if(microphone.isChecked && uri==null && ContextCompat.checkSelfPermission(activity,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
                permissionKind=kind; permissionStart=true; requestMicrophone(); return
            }
            val service=getService() ?: run { status.text="服务尚未就绪"; return }
            selected=id(kind)
            val input=if(uri!=null) ContentInput.WAV else if(microphone.isChecked) ContentInput.MICROPHONE else ContentInput.SOFTWARE
            service.audioContent.start(ContentConfig(kind,input,kind==AudioContentKind.SAT && apt,mode,wpm),uri)
        }
        fun render(state: ContentState) {
            if(state.config?.kind!=kind) { action.text="开始"; return }
            status.text=state.status; details.text="${when(state.config.input) { ContentInput.SOFTWARE -> "软件内"; ContentInput.MICROPHONE -> "麦克风"; ContentInput.WAV -> "WAV" }}"+(if(state.mode.isNotBlank()) " · ${state.mode}" else "")+
                if(state.lines>0) " · ${state.lines}${if(state.totalLines>0) "/${state.totalLines}" else ""} 行" else ""
            action.text=if(state.running) "停止" else "开始"
            if(transcript.text.toString()!=state.text) transcript.text=state.text
            if(state.image!==lastImage) { lastImage=state.image; image.setImageBitmap(state.image); renderFiles() }
            if(!state.running) renderFiles()
        }
        private fun save() {
            activity.lifecycleScope.launch {
                val file=withContext(Dispatchers.IO) { getService()?.audioContent?.saveCurrent() }
                if(file!=null) { renderFiles(); Toast.makeText(activity,"已保存",Toast.LENGTH_SHORT).show() }
            }
        }
        private fun renderFiles() {
            files=getService()?.audioContent?.files()?.filter { if(kind==AudioContentKind.CW) it.extension=="txt" else if(kind==AudioContentKind.SAT) it.name.startsWith("SAT") || it.name.startsWith("APT") else it.name.startsWith("SSTV") }.orEmpty()
            history.submitRows(files.map { RadioDataTable.Row(listOf(it.name,"${it.length()/1024} KB")) })
        }
        private fun openFile(file: java.io.File) {
            if(file.extension=="txt") { AlertDialog.Builder(activity).setTitle("CW").setMessage(file.readText()).setPositiveButton("关闭",null).show(); return }
            val uri=FileProvider.getUriForFile(activity,activity.packageName+".fileprovider",file)
            try { activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"image/png").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            catch(e: Exception) { Toast.makeText(activity,"没有可打开图像的应用",Toast.LENGTH_SHORT).show() }
        }
        private fun updateOption() { option.text=if(kind==AudioContentKind.CW) if(wpm==0) "Auto WPM" else "$wpm WPM" else if(kind==AudioContentKind.SAT && apt) "APT（历史）" else mode }
        private fun chooseOptions() {
            val values=if(kind==AudioContentKind.CW) listOf("Auto WPM","10 WPM","15 WPM","20 WPM","25 WPM","30 WPM","40 WPM")
                else listOf("Auto","Robot 36 Color","Robot 72 Color","PD 50","PD 90","PD 120","PD 160","PD 180","PD 240","PD 290","Martin 1","Martin 2","Scottie 1","Scottie 2","Scottie DX","Wraase SC2–180")+if(kind==AudioContentKind.SAT) listOf("APT（历史）") else emptyList()
            val checked=values.indexOf(if(kind==AudioContentKind.CW) if(wpm==0) "Auto WPM" else "$wpm WPM" else if(apt && kind==AudioContentKind.SAT) "APT（历史）" else mode)
            AlertDialog.Builder(activity).setTitle(if(kind==AudioContentKind.CW) "速度" else "图像模式").setSingleChoiceItems(values.toTypedArray(),checked) { dialog,which ->
                getService()?.audioContent?.stop()
                if(kind==AudioContentKind.CW) { wpm=if(which==0) 0 else values[which].substringBefore(' ').toInt(); prefs.edit().putInt("cw_wpm",wpm).apply() }
                else { apt=values[which]=="APT（历史）"; if(!apt) mode=values[which]; prefs.edit().putString("${kind}_sstv",mode).putBoolean("sat_apt",apt).apply() }
                updateOption(); dialog.dismiss()
                if(apt) status.text="APT 历史录音 · NOAA-15/18/19 已退役"
            }.setNegativeButton("取消",null).show()
        }
        private fun presets() {
            if(kind==AudioContentKind.SAT) AlertDialog.Builder(activity).setTitle("接收目标")
                .setItems(arrayOf("ISS SSTV · 145.800 MHz FM","ARISS 活动安排")) { _,index ->
                    if(index==1) activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.ariss.org/upcoming-sstv-events.html")))
                    else { apt=false; mode="Auto"; updateOption()
                        if(!microphone.isChecked) prepareRf(kind,145_800_000)
                        status.text="ISS SSTV · 按活动安排播出"
                    }
                }.setNegativeButton("取消",null).show()
            else AlertDialog.Builder(activity).setTitle("SSTV 接收频率").setItems(arrayOf("14.230 MHz USB","当前频率")) { _,index ->
                if(index==0 && !microphone.isChecked) prepareRf(kind,14_230_000)
            }.setNegativeButton("取消",null).show()
        }
    }
    private inner class EibiPanel {
        val root=column()
        val repository=EibiRepository(activity)
        var data: EibiRepository.Data?=null
        var entries=emptyList<EibiEntry>()
        var lastHz=0L
        val info=label("载入节目表",11f)
        var busy=false
        val refreshAction=button("更新") { update() }
        val importAction=button("导入 CSV") { requestCsv() }
        fun busy(value: Boolean) { busy=value; refreshAction.isEnabled=!value; importAction.isEnabled=!value }
        val query=EditText(activity).apply { hint="电台 / 地区"; textSize=12f; isSingleLine=true }
        var languageCode: String?=null
        val language: MaterialButton=button("全部语言") {
            val codes=data?.entries?.map { it.language }?.filter { it.isNotBlank() }?.distinct()?.sorted().orEmpty()
            val names=mapOf("M" to "中文","E" to "英语","J" to "日语","K" to "韩语","R" to "俄语","F" to "法语","S" to "西班牙语","P" to "葡萄牙语","A" to "阿拉伯语")
            val labels=listOf("全部语言")+codes.map { code -> names[code]?.let { "$code · $it" } ?: code }
            AlertDialog.Builder(activity).setTitle("语言").setSingleChoiceItems(labels.toTypedArray(),codes.indexOf(languageCode)+1) { dialog,index ->
                languageCode=if(index==0) null else codes[index-1]
                language.text=if(index==0) "全部语言" else labels[index]
                dialog.dismiss(); filter()
            }.setNegativeButton("取消",null).show()
        }
        val frequency=SwitchMaterial(activity).apply { text="当前频率"; textSize=12f }
        val now=SwitchMaterial(activity).apply { text="当前 UTC"; textSize=12f; isChecked=true }
        var timeOverride: ZonedDateTime?=null
        val time=button("UTC") { val current=timeOverride ?: ZonedDateTime.now(ZoneOffset.UTC)
            android.app.DatePickerDialog(activity,{ _,y,m,d -> android.app.TimePickerDialog(activity,{ _,h,min ->
                timeOverride=ZonedDateTime.of(y,m+1,d,h,min,0,0,ZoneOffset.UTC); now.isChecked=false; filter()
            },current.hour,current.minute,true).show() },current.year,current.monthValue-1,current.dayOfMonth).show()
        }
        val table=RadioDataTable(activity)
        var filterJob: Job?=null
        init {
            root.addView(row(refreshAction,importAction,time))
            root.addView(info)
            root.addView(LinearLayout(activity).apply {
                addView(query,LinearLayout.LayoutParams(0,dp(44),1f))
                addView(language,LinearLayout.LayoutParams(dp(108),dp(44)))
            })
            root.addView(row(frequency,now))
            table.setColumns(listOf(RadioDataTable.Column("kHz",80),RadioDataTable.Column("电台",180),RadioDataTable.Column("UTC",100),RadioDataTable.Column("语言",55),RadioDataTable.Column("地区",65),RadioDataTable.Column("播出日",70)))
            root.addView(table,LinearLayout.LayoutParams(-1,dp(285)))
            query.addTextChangedListener(object: android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
                override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) { filter() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
            frequency.setOnCheckedChangeListener { _,_ -> filter() }; now.setOnCheckedChangeListener { _,_ -> filter() }
            table.onRowClick={ index -> entries.getOrNull(index)?.let { entry ->
                if(viewModel.setFrequency(entry.hz)) { viewModel.finishTuning()
                    val mode=when { entry.language=="-CW" -> DemodMode.CW; entry.days=="LSB" -> DemodMode.LSB; entry.days=="USB" -> DemodMode.USB; else -> DemodMode.AM }
                    viewModel.setDemodMode(mode,automatic=true)
                } else Toast.makeText(activity,"当前接收机不支持此频率",Toast.LENGTH_SHORT).show()
            } }
            table.onRowLongClick={ index -> entries.getOrNull(index)?.let { entry ->
                AlertDialog.Builder(activity).setTitle(entry.station).setMessage("${entry.hz/1000.0} kHz · ${entry.utc} UTC\n语言 ${entry.language} · 目标 ${entry.target}\n播出日 ${entry.days.ifBlank { "每天" }}\n发射站 ${entry.country} / ${entry.site}\n日期 ${entry.from}–${entry.until}").setPositiveButton("关闭",null).show()
            }; true }
            ControlColors.apply(root,ControlColors.Group.RADIO)
            activity.lifecycleScope.launch { while(isActive) { delay(60_000); if(now.isChecked) filter() } }
        }
        fun load() { activity.lifecycleScope.launch { runCatching { withContext(Dispatchers.IO) { repository.load() } }.onSuccess { data=it; filter() }.onFailure { info.text="节目表载入失败" } } }
        fun update() { if(busy) return; busy(true); info.text="正在更新"; activity.lifecycleScope.launch {
            try {
            runCatching { withContext(Dispatchers.IO) { repository.update() } }.onSuccess { data=it; filter() }
                .onFailure { info.text="更新失败，保留离线节目表" }
            } finally { busy(false) }
        } }
        fun import(uri: Uri) { if(busy) return; busy(true); activity.lifecycleScope.launch {
            try {
            runCatching { withContext(Dispatchers.IO) {
                val name=activity.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c -> if(c.moveToFirst()) c.getString(0) else "" } ?: ""
                val bytes=activity.contentResolver.openInputStream(uri)?.use { input ->
                    val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192); var n=0
                    while(input.read(buffer).also { n=it }>=0) { out.write(buffer,0,n); require(out.size()<=2_000_000) { "文件过大" } }; out.toByteArray()
                } ?: error("无法读取文件")
                repository.import(bytes,name)
            } }.onSuccess { data=it; filter() }.onFailure { info.text="导入失败：${it.message}" }
            } finally { busy(false) }
        } }
        fun frequencyChanged(hz: Long) { if(hz!=lastHz) { lastHz=hz; if(frequency.isChecked) filter() } }
        fun filter() {
            val schedule=data ?: return
            filterJob?.cancel()
            val text=query.text.toString().trim(); val hz=viewModel.centerFreqHz.value
            val selectedLanguage=languageCode
            val currentOnly=frequency.isChecked; val active=now.isChecked
            val instant=if(active) ZonedDateTime.now(ZoneOffset.UTC) else timeOverride
            time.text=(instant ?: ZonedDateTime.now(ZoneOffset.UTC)).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm 'UTC'"))
            filterJob=activity.lifecycleScope.launch {
                val results=withContext(Dispatchers.Default) { schedule.entries.filter {
                    (!currentOnly || kotlin.math.abs(it.hz-hz)<=1500) &&
                        (selectedLanguage==null || it.language==selectedLanguage) &&
                        (instant==null || it.activeAt(instant,schedule.season)) &&
                        (text.isEmpty() || "${it.station} ${it.language} ${it.target} ${it.country}".contains(text,true))
                }.sortedBy { it.hz } }
                entries=results
                info.text="${schedule.origin} · ${schedule.season} · ${results.size} 项"+
                    if(!EibiSchedule.inSeason(java.time.LocalDate.now(ZoneOffset.UTC),schedule.season)) " · 非当前季节" else ""
                table.submitRows(results.map { RadioDataTable.Row(listOf("%.1f".format(it.hz/1000.0),it.station,it.utc,it.language,it.target,it.days.ifBlank { "每天" })) })
            }
        }
    }
}
