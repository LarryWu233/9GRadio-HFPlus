package com.radiosport.ninegradio.dsp
import android.content.Context
object AmPreferences {
    fun read(context:Context):AmSettings {
        val p=context.getSharedPreferences("rtlsdr_prefs",0)
        return AmSettings(runCatching { AmDetector.valueOf(p.getString("am_detector","ENVELOPE")!!) }.getOrDefault(AmDetector.ENVELOPE),
            p.getBoolean("am_audio_auto",true),p.getInt("am_audio_hz",4500).coerceIn(100,20000),
            runCatching { AmAgc.valueOf(p.getString("am_agc","SLOW")!!) }.getOrDefault(AmAgc.SLOW),
            p.getFloat("am_gain_db",40f).coerceIn(0f,60f),p.getFloat("am_low_db",-110f).coerceIn(-150f,-30f))
    }
    fun write(context:Context,s:AmSettings) { context.getSharedPreferences("rtlsdr_prefs",0).edit()
        .putString("am_detector",s.detector.name).putBoolean("am_audio_auto",s.audioAuto).putInt("am_audio_hz",s.audioHz)
        .putString("am_agc",s.agc.name).putFloat("am_gain_db",s.maxGainDb).putFloat("am_low_db",s.lowLevelDb).apply() }
}
