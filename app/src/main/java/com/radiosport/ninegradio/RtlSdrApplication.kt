package com.radiosport.ninegradio

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.radiosport.ninegradio.data.AppDatabase

class RtlSdrApplication : Application() {

    companion object {
        const val CHANNEL_SDR_SERVICE = "rtlsdr_service"
        const val CHANNEL_RECORDING = "rtlsdr_recording"
        const val CHANNEL_SCANNER = "rtlsdr_scanner"

        lateinit var instance: RtlSdrApplication
            private set
    }

    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        instance = this
        // Older builds stored the de-emphasis slider as an integer. The two
        // supported standards are now represented by a ListPreference.
        val preferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        if(!preferences.getBoolean("theme_link_v17",false)) {
            preferences.edit().putString("spectrum_theme_before_ui",preferences.getString("pref_spectrum_theme","Futuristic"))
                .putString("pref_spectrum_theme","UI").putBoolean("theme_link_v17",true).apply()
        }
        val receiverPrefs=getSharedPreferences("rtlsdr_prefs",MODE_PRIVATE)
        if(!receiverPrefs.getBoolean("spectrum_resolution_v2",false)) {
            val fft=preferences.getString("pref_fft_size",null)?.toIntOrNull() ?: receiverPrefs.getInt("fftSize",256)
            if(fft<=512) {
                receiverPrefs.edit().putInt("fftSize",2048).apply()
                preferences.edit().putString("pref_fft_size","2048").apply()
            }
            receiverPrefs.edit().putBoolean("spectrum_resolution_v2",true).apply()
        }
        val oldDeemphasis = preferences.all["pref_deemph_us"]
        val deemphasis = if (oldDeemphasis?.toString() == "50") "50" else "75"
        if (oldDeemphasis !is String || oldDeemphasis != deemphasis) {
            preferences.edit().putString("pref_deemph_us", deemphasis).apply()
        }
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val sdrChannel = NotificationChannel(
            CHANNEL_SDR_SERVICE,
            "SDR Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "9GRadio background processing service"
            setShowBadge(false)
        }

        val recChannel = NotificationChannel(
            CHANNEL_RECORDING,
            "IQ Recording",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "IQ and audio recording notifications"
        }

        val scanChannel = NotificationChannel(
            CHANNEL_SCANNER,
            "Frequency Scanner",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Frequency scanner status"
        }

        manager.createNotificationChannels(listOf(sdrChannel, recChannel, scanChannel))
    }
}
