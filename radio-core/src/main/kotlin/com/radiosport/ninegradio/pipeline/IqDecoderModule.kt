package com.radiosport.ninegradio.pipeline

import kotlinx.coroutines.flow.Flow

enum class IqSampleFormat { UNSIGNED_8,FLOAT_32 }
data class IqDecoderConfiguration(val sampleRate:Int,val format:IqSampleFormat)
data class DecoderModuleState(val running:Boolean=false,val error:String?=null)
data class IqDecoderRequirements(val sampleRates:Set<Int>,val formats:Set<IqSampleFormat>) {
    fun accepts(config:IqDecoderConfiguration)=config.sampleRate in sampleRates && config.format in formats
}

/** Raw-IQ adapter boundary, not runtime plugin discovery.
 * The controller validates input and owns capture. This module owns only its subscriber,
 * workers and native decoder. Stop must retain/report ownership until release completes.
 */
interface IqDecoderModule<out Result>:AutoCloseable {
    val requirements:IqDecoderRequirements
    val results:Flow<Result>
    val state:Flow<DecoderModuleState>
    val hasPendingRelease:Boolean
    fun start(input:IqReadInput,configuration:IqDecoderConfiguration)
    fun stop()
    override fun close()=stop()
}
