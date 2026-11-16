package com.radiosport.ninegradio.modules

import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.pipeline.*
import com.radiosport.ninegradio.source.IqBlock
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class RegisteredIqModulesTest {
    private class Input:IqReadInput { override val iqFlow=emptyFlow<IqBlock>();override val iqSubscriberCount=0 }
    private class Decoder(override val requirements:IqDecoderRequirements=RadioModules.mode(DemodMode.ADSB).rawIq!!):IqDecoderModule<Unit> {
        override val results=emptyFlow<Unit>();override val state=flowOf(DecoderModuleState())
        override var hasPendingRelease=false
        var deferStop=false;var starts=0;var input:IqReadInput?=null
        override fun start(input:IqReadInput,configuration:IqDecoderConfiguration) { if(this.input===input) return;this.input=input;starts++ }
        override fun stop() { hasPendingRelease=deferStop;if(!hasPendingRelease) input=null }
    }
    private val support=ModuleSourceSupport(listOf(500000L..1766000000L),listOf(2400000),setOf(IqSampleFormat.UNSIGNED_8),2160000)
    private val config=IqDecoderConfiguration(2400000,IqSampleFormat.UNSIGNED_8)
    @Test fun refusesWrongInputBeforeCreatingDecoderAndStartsValidInputIdempotently() {
        var created=0;val decoder=Decoder();val input=Input()
        val modules=RegisteredIqModules(mapOf("adsb" to { created++;decoder }))
        assertFalse(modules.select(DemodMode.ADSB,input,config.copy(sampleRate=768000),support).available)
        assertFalse(modules.select(DemodMode.ADSB,input,config.copy(format=IqSampleFormat.FLOAT_32),support).available)
        assertEquals(0,created)
        repeat(2) { assertTrue(modules.select(DemodMode.ADSB,input,config,support).available) }
        assertEquals(1,created);assertEquals(1,decoder.starts)
        modules.select(DemodMode.AM,input,config,support);assertNull(modules.activeId);assertNull(decoder.input)
    }
    @Test fun sourceReplacementWaitsForActualRelease() {
        val decoder=Decoder();val first=Input();val second=Input()
        val modules=RegisteredIqModules(mapOf("adsb" to { decoder }))
        modules.select(DemodMode.ADSB,first,config,support)
        decoder.deferStop=true
        assertFalse(modules.select(DemodMode.ADSB,second,config,support).available)
        assertTrue(modules.hasPendingRelease);assertSame(first,decoder.input);assertEquals(1,decoder.starts)
        // A joined-stop adapter may only clear its pending flag on the next
        // stop call after the worker actually exits; do not require async clearing.
        decoder.deferStop=false
        assertTrue(modules.select(DemodMode.ADSB,first,config,support).available)
        assertFalse(modules.hasPendingRelease);assertEquals(2,decoder.starts)
        assertTrue(modules.select(DemodMode.ADSB,second,config,support).available)
        assertSame(second,decoder.input);assertEquals(3,decoder.starts)
        modules.stop();assertFalse(modules.hasPendingRelease)
    }
    @Test fun mismatchedFactoryContractCannotStart() {
        val decoder=Decoder(IqDecoderRequirements(setOf(768000),setOf(IqSampleFormat.FLOAT_32)))
        val modules=RegisteredIqModules(mapOf("adsb" to { decoder }))
        assertFalse(modules.select(DemodMode.ADSB,Input(),config,support).available)
        assertEquals(0,decoder.starts)
        val broken=RegisteredIqModules(mapOf("adsb" to { error("Backend construction failed") }))
        assertFalse(broken.select(DemodMode.ADSB,Input(),config,support).available)
        assertNull(broken.activeId)
    }
}
