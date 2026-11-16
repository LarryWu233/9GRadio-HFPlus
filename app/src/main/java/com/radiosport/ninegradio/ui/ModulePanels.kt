package com.radiosport.ninegradio.ui

import com.radiosport.ninegradio.content.*
import com.radiosport.ninegradio.modules.*

/** Preserve existing page IDs/order/settings; portable descriptors contain no View IDs. */
internal object ModulePanels {
    private val ids=mapOf(ModulePanel.APRS to ControlsPagerAdapter.TAB_APRS,ModulePanel.ACARS to ControlsPagerAdapter.TAB_ACARS,
        ModulePanel.ADSB to ControlsPagerAdapter.TAB_ADSB,ModulePanel.DMR to ControlsPagerAdapter.TAB_DMR,
        ModulePanel.YSF to ControlsPagerAdapter.TAB_YSF,ModulePanel.DSTAR to ControlsPagerAdapter.TAB_DSTAR,
        ModulePanel.DIG to ControlsPagerAdapter.TAB_DIG,ModulePanel.CW to ControlsPagerAdapter.TAB_CW,
        ModulePanel.SSTV to ControlsPagerAdapter.TAB_SSTV,ModulePanel.SAT to ControlsPagerAdapter.TAB_SAT,ModulePanel.EIBI to ControlsPagerAdapter.TAB_EIBI,
        ModulePanel.AIS to ControlsPagerAdapter.TAB_AIS)
    fun id(panel:ModulePanel)=ids.getValue(panel)
    fun content(kind:AudioContentKind)=id(RadioModules.content(ContentConfig(kind)).panel!!)
    fun kind(page:Int)=AudioContentKind.entries.firstOrNull { content(it)==page }
}
