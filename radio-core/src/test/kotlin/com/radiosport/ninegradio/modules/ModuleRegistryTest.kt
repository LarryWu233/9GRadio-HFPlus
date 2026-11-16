package com.radiosport.ninegradio.modules

import com.radiosport.ninegradio.content.*
import com.radiosport.ninegradio.dsp.*
import com.radiosport.ninegradio.pipeline.*
import org.junit.Assert.*
import org.junit.Test

class ModuleRegistryTest {
    @Test fun existingModeOrderAndAudioPoliciesRemainIntact() {
        assertEquals(DemodMode.entries.toSet(),RadioModules.registry.all.mapNotNull { it.mode }.toSet())
        assertEquals(listOf(DemodMode.AM,DemodMode.NFM,DemodMode.WFM,DemodMode.WFM_STEREO,DemodMode.USB,DemodMode.LSB,
            DemodMode.CW,DemodMode.CWR,DemodMode.DSB,DemodMode.APRS,DemodMode.ACARS,DemodMode.ADSB,DemodMode.RAW,
            DemodMode.DMR,DemodMode.YSF,DemodMode.DSTAR,DemodMode.DIG,DemodMode.AIS),RadioModules.modeChoices.map { it.mode })
        assertEquals(setOf(DemodMode.ADSB,DemodMode.APRS,DemodMode.ACARS,DemodMode.FLEX,DemodMode.RAW,DemodMode.AIS),DemodMode.entries.filter { AudioRoutingPolicy.dataOnly(it) }.toSet())
        assertFalse(AudioRoutingPolicy.needsProtocolPcm(DemodMode.ADSB))
        assertTrue(AudioRoutingPolicy.needsProtocolPcm(DemodMode.APRS))
        assertTrue(AudioRoutingPolicy.canMonitor(DemodMode.DMR))
        assertEquals(ModuleInput.PROTOCOL_PCM,RadioModules.mode(DemodMode.DMR).input)
    }
    @Test fun capabilityChecksUseSourceFormatsAndDoNotLockAnalogTuningBands() {
        val rtl=ModuleSourceSupport(listOf(500000L..1766000000L),listOf(2400000),setOf(IqSampleFormat.UNSIGNED_8),2160000)
        val hf=ModuleSourceSupport(listOf(500L..31000000L,64000000L..260000000L),listOf(768000),setOf(IqSampleFormat.FLOAT_32),660000)
        assertTrue(RadioModules.mode(DemodMode.ADSB).availability(rtl).available)
        assertFalse(RadioModules.mode(DemodMode.ADSB).availability(hf).available)
        assertTrue(RadioModules.mode(DemodMode.AIS).availability(hf).available)
        assertTrue(RadioModules.mode(DemodMode.AIS).availability(rtl).available)
        assertTrue(RadioModules.mode(DemodMode.AM).availability(hf).available)
        assertTrue(RadioModules.mode(DemodMode.ACARS).availability(hf).available)
        assertTrue(RadioModules.mode(DemodMode.DMR).availability(hf).available)
        val file=rtl.copy(frequencyRanges=listOf(1090000000L..1090000000L))
        assertTrue(RadioModules.mode(DemodMode.ADSB).availability(file).available)
        assertTrue(RadioModules.mode(DemodMode.ADSB).availability(file.copy(formats=setOf(IqSampleFormat.FLOAT_32))).reason!!.contains("8 位"))
    }
    @Test fun contentSelectsOwnBackendAndPreservesOfflineInputs() {
        val sstv=RadioModules.content(ContentConfig(AudioContentKind.SSTV,apt=true))
        assertEquals(ModuleBackend.ROBOT36,sstv.backend)
        assertEquals(ModuleBackend.MORSE,RadioModules.content(ContentConfig(AudioContentKind.CW,apt=true)).backend)
        assertEquals(ModuleBackend.APT,RadioModules.content(ContentConfig(AudioContentKind.SAT,apt=true)).backend)
        assertEquals(ContentInput.entries.toSet(),sstv.inputs)
        assertEquals(MonitorPolicy.FORBIDDEN,sstv.monitoring)
        assertEquals(MonitorPolicy.REMEMBERED,RadioModules.content(ContentConfig(AudioContentKind.CW)).monitoring)
        assertEquals(ModuleInput.NONE,RadioModules.registry["eibi"].input)
        assertEquals(listOf("SSTV","SAT","EiBi"),RadioModules.contentChoices.map { it.label })
    }
    @Test fun duplicateIdsAndModesAreRejectedAtRegistration() {
        val am=RadioModules.mode(DemodMode.AM)
        assertThrows(IllegalArgumentException::class.java) { ModuleRegistry(listOf(am,am)) }
        assertThrows(IllegalArgumentException::class.java) { ModuleRegistry(listOf(am,am.copy(id="am-copy"))) }
    }
}
