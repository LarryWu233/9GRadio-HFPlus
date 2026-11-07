package com.radiosport.ninegradio.dsp

/** Receive-use labels and conservative mode suggestions, not channel assignments. */
object ReceiverBandPlan {
    data class Band(val name: String, val start: Long, val end: Long, val mode: DemodMode?)
    private val amateur = listOf(
        Band("HAM · 160 m", 1_800_000, 2_000_000, DemodMode.LSB),
        Band("HAM · 80 m", 3_500_000, 3_900_000, DemodMode.LSB),
        Band("HAM · 40 m", 7_000_000, 7_200_000, DemodMode.LSB),
        Band("HAM · 30 m", 10_100_000, 10_150_000, DemodMode.CW),
        Band("HAM · 20 m", 14_000_000, 14_350_000, DemodMode.USB),
        Band("HAM · 17 m", 18_068_000, 18_168_000, DemodMode.USB),
        Band("HAM · 15 m", 21_000_000, 21_450_000, DemodMode.USB),
        Band("HAM · 12 m", 24_890_000, 24_990_000, DemodMode.USB),
        Band("HAM · 10 m", 28_000_000, 29_500_000, DemodMode.USB),
        Band("HAM · 10 m FM", 29_500_000, 29_700_000, DemodMode.NFM),
        Band("HAM · 6 m", 50_000_000, 54_000_000, DemodMode.NFM),
        Band("HAM · 2 m", 144_000_000, 148_000_000, DemodMode.NFM),
        Band("HAM · 70 cm", 430_000_000, 440_000_000, DemodMode.NFM)
    )
    fun at(hz: Long): Band = amateur.firstOrNull { hz >= it.start && hz < it.end } ?: when (hz) {
        in 531_000L..1_710_000L -> Band("MW / 中波广播", 531_000, 1_710_000, DemodMode.AM)
        in 3_000_000L..30_000_000L -> Band("SW / 短波", 3_000_000, 30_000_000, DemodMode.AM)
        in 87_500_000L until 108_000_000L -> Band("FM / 调频广播", 87_500_000, 108_000_000, DemodMode.WFM)
        in 108_000_000L until 118_000_000L -> Band("航空导航", 108_000_000, 118_000_000, null)
        in 118_000_000L..136_975_000L -> Band("AIR / 航空通话", 118_000_000, 136_975_000, DemodMode.AM)
        1_090_000_000L -> Band("ADS-B · 1090 MHz", hz, hz, DemodMode.ADSB)
        else -> Band(when { hz < 3_000_000 -> "LF / MF"; hz < 30_000_000 -> "HF"; hz < 300_000_000 -> "VHF"; else -> "UHF" }, 0, 0, null)
    }
}
