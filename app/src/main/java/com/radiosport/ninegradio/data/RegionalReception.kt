package com.radiosport.ninegradio.data

import com.radiosport.ninegradio.dsp.DemodMode
import kotlin.math.*

object RegionalReception {
    enum class Region(val label: String, val aprsHz: Long?) {
        CHINA("中国 / China", 144_640_000), NORTH_AMERICA("北美 / North America", 144_390_000),
        EUROPE("欧洲 / Europe", 144_800_000), JAPAN("日本 / Japan", 144_660_000),
        AUSTRALIA("澳大利亚 / Australia", 145_175_000), NEW_ZEALAND("新西兰 / New Zealand", 144_575_000),
        OTHER("其他 / 手动选择", null)
    }
    data class Channel(val type: String, val description: String, val hz: Long)
    data class Airport(val ident: String, val name: String, val country: String,
        val lat: Double, val lon: Double, val channels: List<Channel>)
    data class Recommendation(val label: String, val hz: Long, val mode: DemodMode)
    fun region(country: String): Region = when (country.uppercase()) {
        "CN", "HK", "MO", "TW" -> Region.CHINA
        "US", "CA", "MX" -> Region.NORTH_AMERICA
        "JP" -> Region.JAPAN
        "AU" -> Region.AUSTRALIA
        "NZ" -> Region.NEW_ZEALAND
        "GB", "IE", "FR", "DE", "ES", "PT", "IT", "NL", "BE", "CH", "AT", "CZ", "PL", "DK", "SE", "NO", "FI", "IS", "GR", "RO", "HU", "SK", "HR", "SI", "EE", "LV", "LT", "BG", "RS", "UA" -> Region.EUROPE
        else -> Region.OTHER
    }
    fun common(region: Region): List<Recommendation> = buildList {
        region.aprsHz?.let { add(Recommendation("APRS · ${region.label}", it, DemodMode.APRS)) }
        add(Recommendation("ACARS · 常用候选频点", 131_550_000, DemodMode.ACARS))
        add(Recommendation("航空应急守听（非本地塔台）", 121_500_000, DemodMode.AM))
        add(Recommendation("ADS-B · 1090 MHz", 1_090_000_000, DemodMode.ADSB))
    }
    fun distanceKm(lat: Double, lon: Double, airport: Airport): Double {
        val a = Math.toRadians(lat); val b = Math.toRadians(airport.lat)
        val dl = Math.toRadians(airport.lon - lon)
        val d = sin((b - a) / 2).pow(2) + cos(a) * cos(b) * sin(dl / 2).pow(2)
        return 6371 * 2 * asin(sqrt(d.coerceIn(0.0, 1.0)))
    }
    fun nearby(airports: List<Airport>, lat: Double, lon: Double) = airports
        .map { it to distanceKm(lat, lon, it) }.filter { it.second <= 200 }
        .sortedBy { it.second }.take(5)
}
