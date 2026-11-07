package com.radiosport.ninegradio.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Auxiliary map host; receiver modules embed the same panel without navigation. */
class RadioMapActivity : RadioThemedActivity() {
    lateinit var panel: RadioMapPanel
        private set
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val protocol=intent.getStringExtra("protocol") ?: "ALL"
        title=when(protocol) { "ADSB" -> "ADS-B 地图"; "APRS" -> "APRS 地图"; else -> "接收地图" }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        panel=RadioMapPanel(this,protocol)
        setContentView(panel); panel.setActive(true)
        if(state==null && intent.getBooleanExtra("editReceiver",false)) panel.receiverDialog()
    }
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
