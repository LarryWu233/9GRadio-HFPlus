package com.radiosport.ninegradio.modules

import com.radiosport.ninegradio.content.*
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.pipeline.*

enum class ModuleInput(val label:String) { CHANNEL_IQ("频道 IQ"), RAW_IQ("原始 IQ"), PROTOCOL_PCM("协议基带音频"), AUDIO("音频"), NONE("无需采样输入") }
enum class ModuleRoute(val label:String) { MONITOR("监听"), RAW_DECODER("原始 IQ 解码"), PCM_DECODER("协议音频解码"), DIGITAL_VOICE("数字语音"), CAPTURE("IQ 录制"), CONTENT("音频内容解码"), REFERENCE("节目查询"), LEGACY("兼容路径") }
enum class ModuleBackend { EXISTING_DEMOD, READSB, APRS, ACARS, PAGING, DIGITAL_VOICE, RAW_CAPTURE, MORSE, ROBOT36, APT, EIBI, LEGACY, AIS_CATCHER }
enum class MonitorPolicy { REMEMBERED, FORBIDDEN }
enum class ModulePicker { ANALOG, DIGITAL, CONTENT, HIDDEN }
enum class ModulePanel { APRS, ACARS, ADSB, DMR, YSF, DSTAR, DIG, CW, SSTV, SAT, EIBI, AIS }
enum class ModuleResult(val label:String) { AUDIO("监听声音"), VOICE("解码语音"), POSITIONS("位置数据"), MESSAGES("消息"), TEXT("文本"), IMAGE("图像"), PROGRAMMES("节目表"), IQ("原始 IQ"), RDS("RDS 信息") }
data class PcmRequirement(val sampleRate:Int=48000,val channels:Int=1)
data class RfPreset(val frequencyHz:Long,val mode:DemodMode,val bandwidthHz:Int)
data class ContentSelector(val kind:AudioContentKind,val apt:Boolean=false)
data class ModuleSourceSupport(val frequencyRanges:List<LongRange>,val sampleRates:List<Int>,
    val formats:Set<IqSampleFormat>,val maxBandwidthHz:Int)
data class ModuleAvailability(val reason:String?=null) { val available get()=reason==null }

/** Portable declarations; platform factories and presentation bindings stay in the app. */
data class ModuleDescriptor(val id:String,val label:String,val input:ModuleInput,val route:ModuleRoute,
    val backend:ModuleBackend,val monitoring:MonitorPolicy=MonitorPolicy.REMEMBERED,
    val results:Set<ModuleResult> = emptySet(),val mode:DemodMode?=null,
    val picker:ModulePicker=ModulePicker.HIDDEN,val panel:ModulePanel?=null,
    val bandwidthHz:Int=0,val receiveRanges:List<LongRange> = emptyList(),val rangeLabel:String?=null,
    val rawIq:IqDecoderRequirements?=null,val pcm:PcmRequirement?=null,
    val content:ContentSelector?=null,val inputs:Set<ContentInput> = emptySet(),val preset:RfPreset?=null) {
    fun availability(source:ModuleSourceSupport):ModuleAvailability {
        if(receiveRanges.isNotEmpty() && receiveRanges.none { required -> source.frequencyRanges.any {
                maxOf(it.first,required.first)<=minOf(it.last,required.last) } })
            return ModuleAvailability("接收机不覆盖${rangeLabel ?: "该功能的接收频段"}")
        if(rawIq!=null) {
            if(source.formats.intersect(rawIq.formats).isEmpty()) {
                val format=rawIq.formats.joinToString(" / ") { if(it==IqSampleFormat.UNSIGNED_8) "8 位" else "浮点" }
                return ModuleAvailability("$label 需要${format}原始 IQ")
            }
            if(source.sampleRates.none { rawIq.acceptsRate(it) }) {
                rawIq.sampleRateRange?.let { return ModuleAvailability("$label 需要 ${it.first}–${it.last} S/s 采样率") }
                val rates=rawIq.sampleRates.sorted().joinToString(" / ") { "%.3f".format(java.util.Locale.ROOT,it/1e6) }
                return ModuleAvailability("$label 需要 $rates MS/s 采样率")
            }
        }
        if(bandwidthHz>source.maxBandwidthHz || bandwidthHz>(source.sampleRates.maxOrNull() ?: 0)*.86)
            return ModuleAvailability("接收机带宽不足以接收 $label")
        return ModuleAvailability()
    }
}

class ModuleRegistry(entries:List<ModuleDescriptor>) {
    val all:List<ModuleDescriptor> = java.util.Collections.unmodifiableList(entries.toList())
    private val byId=all.associateBy { it.id }
    private val byMode=all.filter { it.mode!=null }.associateBy { it.mode!! }
    private val byContent=all.filter { it.content!=null }.associateBy { it.content!! }
    init {
        require(byId.size==all.size) { "Duplicate module id" }
        require(byMode.size==all.count { it.mode!=null }) { "Duplicate RF mode registration" }
        require(byContent.size==all.count { it.content!=null }) { "Duplicate content registration" }
        all.forEach {
            require(it.id.matches(Regex("[a-z][a-z0-9_-]*")) && it.label.isNotBlank())
            require((it.route==ModuleRoute.RAW_DECODER)==(it.rawIq!=null))
            require(it.route !in setOf(ModuleRoute.PCM_DECODER,ModuleRoute.DIGITAL_VOICE,ModuleRoute.CONTENT) || it.pcm!=null)
            require(it.content==null || it.inputs.isNotEmpty())
        }
    }
    operator fun get(id:String)=byId.getValue(id)
    fun mode(mode:DemodMode)=byMode.getValue(mode)
    fun content(config:ContentConfig)=byContent.getValue(ContentSelector(config.kind,config.kind==AudioContentKind.SAT && config.apt))
}

object RadioModules {
    const val ADSB_SAMPLE_RATE=2400000
    val AIS_REQUIREMENTS=IqDecoderRequirements(emptySet(),setOf(IqSampleFormat.UNSIGNED_8,IqSampleFormat.FLOAT_32),96000..12288000)
    private fun rf(mode:DemodMode,picker:ModulePicker=ModulePicker.HIDDEN,
        route:ModuleRoute=ModuleRoute.MONITOR,backend:ModuleBackend=ModuleBackend.EXISTING_DEMOD,
        panel:ModulePanel?=null,results:Set<ModuleResult> = setOf(ModuleResult.AUDIO),silent:Boolean=false)=
        ModuleDescriptor(mode.name.lowercase(),if(mode==DemodMode.WFM_STEREO) "WFM 立体声" else mode.displayName,
            when(route) { ModuleRoute.RAW_DECODER,ModuleRoute.CAPTURE -> ModuleInput.RAW_IQ
                ModuleRoute.PCM_DECODER,ModuleRoute.DIGITAL_VOICE -> ModuleInput.PROTOCOL_PCM;else -> ModuleInput.CHANNEL_IQ },
            route,backend,if(silent) MonitorPolicy.FORBIDDEN else MonitorPolicy.REMEMBERED,results,mode,picker,panel,
            bandwidthHz=mode.defaultBwHz,pcm=if(route in setOf(ModuleRoute.PCM_DECODER,ModuleRoute.DIGITAL_VOICE)) PcmRequirement() else null)
    private fun voice(mode:DemodMode,picker:ModulePicker=ModulePicker.HIDDEN,panel:ModulePanel?=null)=
        rf(mode,picker,ModuleRoute.DIGITAL_VOICE,ModuleBackend.DIGITAL_VOICE,panel,setOf(ModuleResult.VOICE,ModuleResult.MESSAGES))
    private val audioInputs=setOf(ContentInput.SOFTWARE,ContentInput.MICROPHONE,ContentInput.WAV)
    val registry=ModuleRegistry(listOf(
        rf(DemodMode.AM,ModulePicker.ANALOG),rf(DemodMode.NFM,ModulePicker.ANALOG),
        rf(DemodMode.WFM,ModulePicker.ANALOG,results=setOf(ModuleResult.AUDIO,ModuleResult.RDS)),
        rf(DemodMode.WFM_STEREO,ModulePicker.ANALOG,results=setOf(ModuleResult.AUDIO,ModuleResult.RDS)),
        rf(DemodMode.USB,ModulePicker.ANALOG),rf(DemodMode.LSB,ModulePicker.ANALOG),
        rf(DemodMode.CW,ModulePicker.ANALOG,panel=ModulePanel.CW),rf(DemodMode.CWR,ModulePicker.ANALOG,panel=ModulePanel.CW),
        rf(DemodMode.DSB,ModulePicker.ANALOG),
        rf(DemodMode.APRS,ModulePicker.DIGITAL,ModuleRoute.PCM_DECODER,ModuleBackend.APRS,ModulePanel.APRS,setOf(ModuleResult.POSITIONS,ModuleResult.MESSAGES),true),
        rf(DemodMode.ACARS,ModulePicker.DIGITAL,ModuleRoute.PCM_DECODER,ModuleBackend.ACARS,ModulePanel.ACARS,setOf(ModuleResult.MESSAGES),true),
        rf(DemodMode.ADSB,ModulePicker.DIGITAL,ModuleRoute.RAW_DECODER,ModuleBackend.READSB,ModulePanel.ADSB,setOf(ModuleResult.POSITIONS),true)
            .copy(receiveRanges=listOf(1090000000L..1090000000L),rangeLabel=" 1090 MHz",rawIq=IqDecoderRequirements(setOf(ADSB_SAMPLE_RATE),setOf(IqSampleFormat.UNSIGNED_8)),preset=RfPreset(1090000000,DemodMode.ADSB,1000000)),
        rf(DemodMode.RAW,ModulePicker.DIGITAL,ModuleRoute.CAPTURE,ModuleBackend.RAW_CAPTURE,results=setOf(ModuleResult.IQ),silent=true),
        voice(DemodMode.DMR,ModulePicker.DIGITAL,ModulePanel.DMR),voice(DemodMode.YSF,ModulePicker.DIGITAL,ModulePanel.YSF),
        voice(DemodMode.DSTAR,ModulePicker.DIGITAL,ModulePanel.DSTAR),voice(DemodMode.DIG,ModulePicker.DIGITAL,ModulePanel.DIG),
        rf(DemodMode.AIS,ModulePicker.DIGITAL,ModuleRoute.RAW_DECODER,ModuleBackend.AIS_CATCHER,ModulePanel.AIS,
            setOf(ModuleResult.POSITIONS,ModuleResult.MESSAGES),true).copy(rawIq=AIS_REQUIREMENTS,
            receiveRanges=listOf(161975000L..162025000L),rangeLabel=" AIS 双频道",preset=RfPreset(162000000,DemodMode.AIS,75000)),
        rf(DemodMode.FM,results=setOf(ModuleResult.AUDIO,ModuleResult.RDS)),
        // Existing hidden/compatibility entries; these do not add DRM/FLEX decoding.
        rf(DemodMode.DRM,route=ModuleRoute.LEGACY,backend=ModuleBackend.LEGACY),
        rf(DemodMode.FLEX,route=ModuleRoute.PCM_DECODER,backend=ModuleBackend.PAGING,results=setOf(ModuleResult.MESSAGES),silent=true)
            .copy(receiveRanges=listOf(DemodMode.FLEX.minFreqHz..DemodMode.FLEX.maxFreqHz),rangeLabel=" 900–940 MHz"),
        voice(DemodMode.P25),voice(DemodMode.NXDN),voice(DemodMode.M17),voice(DemodMode.DPMR),
        ModuleDescriptor("cw-audio","CW",ModuleInput.AUDIO,ModuleRoute.CONTENT,ModuleBackend.MORSE,results=setOf(ModuleResult.TEXT),
            panel=ModulePanel.CW,pcm=PcmRequirement(),content=ContentSelector(AudioContentKind.CW),inputs=audioInputs),
        ModuleDescriptor("sstv","SSTV",ModuleInput.AUDIO,ModuleRoute.CONTENT,ModuleBackend.ROBOT36,MonitorPolicy.FORBIDDEN,setOf(ModuleResult.IMAGE),
            picker=ModulePicker.CONTENT,panel=ModulePanel.SSTV,pcm=PcmRequirement(),content=ContentSelector(AudioContentKind.SSTV),inputs=audioInputs,preset=RfPreset(14230000,DemodMode.USB,3000)),
        ModuleDescriptor("sat-sstv","SAT",ModuleInput.AUDIO,ModuleRoute.CONTENT,ModuleBackend.ROBOT36,MonitorPolicy.FORBIDDEN,setOf(ModuleResult.IMAGE),
            picker=ModulePicker.CONTENT,panel=ModulePanel.SAT,pcm=PcmRequirement(),content=ContentSelector(AudioContentKind.SAT),inputs=audioInputs,preset=RfPreset(145800000,DemodMode.NFM,50000)),
        ModuleDescriptor("sat-apt","SAT APT",ModuleInput.AUDIO,ModuleRoute.CONTENT,ModuleBackend.APT,MonitorPolicy.FORBIDDEN,setOf(ModuleResult.IMAGE),
            panel=ModulePanel.SAT,pcm=PcmRequirement(),content=ContentSelector(AudioContentKind.SAT,true),inputs=audioInputs,preset=RfPreset(145800000,DemodMode.NFM,50000)),
        ModuleDescriptor("eibi","EiBi",ModuleInput.NONE,ModuleRoute.REFERENCE,ModuleBackend.EIBI,results=setOf(ModuleResult.PROGRAMMES),picker=ModulePicker.CONTENT,panel=ModulePanel.EIBI)
    ))
    fun mode(mode:DemodMode)=registry.mode(mode)
    fun content(config:ContentConfig)=registry.content(config)
    val digitalVoiceModes=DemodMode.entries.filter { mode(it).route==ModuleRoute.DIGITAL_VOICE }.toSet()
    val modeChoices=registry.all.filter { it.picker==ModulePicker.ANALOG || it.picker==ModulePicker.DIGITAL }
    val contentChoices=registry.all.filter { it.picker==ModulePicker.CONTENT }
}
