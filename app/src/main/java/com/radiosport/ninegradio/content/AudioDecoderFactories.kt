package com.radiosport.ninegradio.content

import com.radiosport.ninegradio.modules.*
import xdsopl.robot36.RadioSstvBridge

/** Platform backend binding. No worker/lifetime changes or algorithm replacement. */
internal object AudioDecoderFactories {
    data class Binding(val decoderRate:Int,val nativeArgument:String?=null,
        val imageFactory:((String)->RadioSstvBridge)?=null)
    private val bindings=mapOf(
        ModuleBackend.MORSE to Binding(22050,"--cw"),
        ModuleBackend.ROBOT36 to Binding(48000,imageFactory={ RadioSstvBridge(48000,it) }),
        ModuleBackend.APT to Binding(48000,"--apt")
    )
    fun forConfig(config:ContentConfig)=if(config.kind==AudioContentKind.CW && config.input!=ContentInput.SOFTWARE)
        Binding(48000,"--cw-audio") else bindings.getValue(RadioModules.content(config).backend)
}
