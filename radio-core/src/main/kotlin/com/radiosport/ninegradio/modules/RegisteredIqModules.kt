package com.radiosport.ninegradio.modules

import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.pipeline.*

/** Called on the owning service's serial controller. No executor/producer/UI here.
 * A module retains ownership until its own stop protocol reports release complete.
 */
class RegisteredIqModules(private val factories:Map<String,()->IqDecoderModule<*>>,
    private val registry:ModuleRegistry=RadioModules.registry) {
    private data class Active(val id:String,val input:IqReadInput,val configuration:IqDecoderConfiguration,val module:IqDecoderModule<*>)
    private var active:Active?=null
    private val instances=mutableMapOf<String,IqDecoderModule<*>>()
    init { factories.keys.forEach { require(registry[it].route==ModuleRoute.RAW_DECODER) } }
    val hasPendingRelease get()=active?.module?.hasPendingRelease==true
    val activeId get()=active?.id
    fun stop() {
        active?.let { old ->
            try { old.module.stop() } finally { if(!old.module.hasPendingRelease) active=null }
        }
    }
    fun select(mode:DemodMode?,input:IqReadInput?,configuration:IqDecoderConfiguration?,support:ModuleSourceSupport?):ModuleAvailability {
        val descriptor=mode?.let { registry.mode(it) }
        if(descriptor?.route!=ModuleRoute.RAW_DECODER || input==null || configuration==null || support==null) {
            stop();return ModuleAvailability()
        }
        val available=descriptor.availability(support.copy(sampleRates=listOf(configuration.sampleRate),formats=setOf(configuration.format)))
        if(!available.available) { stop();return available }
        active?.let { old ->
            if(old.id!=descriptor.id || old.input!==input || old.configuration!=configuration || old.module.hasPendingRelease) stop()
        }
        if(hasPendingRelease) return ModuleAvailability("解码组件仍在关闭，请稍候")
        val factory=factories[descriptor.id] ?: return ModuleAvailability("${descriptor.label} 未绑定解码器")
        val module=try { instances.getOrPut(descriptor.id,factory) }
        catch(error:Exception) { return ModuleAvailability("${descriptor.label} 初始化失败") }
        if(module.requirements!=descriptor.rawIq) return ModuleAvailability("${descriptor.label} 的输入声明与解码器不一致")
        active=Active(descriptor.id,input,configuration,module)
        // Existing modules own idempotent start/recovery checks, including a
        // native child that exited after the previous successful selection.
        return try { module.start(input,configuration);ModuleAvailability() }
        catch(error:Exception) { stop();ModuleAvailability("${descriptor.label} 启动失败") }
    }
}
