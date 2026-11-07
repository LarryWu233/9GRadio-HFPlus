package com.radiosport.ninegradio.ui

import android.os.Bundle
import android.view.MenuItem
import androidx.preference.*
import com.radiosport.ninegradio.R
import com.radiosport.ninegradio.recording.*

class RecordingSettingsActivity:RadioThemedActivity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById(R.id.settingsToolbar));title="录制文件"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if(state==null) supportFragmentManager.beginTransaction().replace(R.id.settings_container,RecordingSettingsFragment()).commit()
    }
    override fun onOptionsItemSelected(item:MenuItem):Boolean {
        if(item.itemId==android.R.id.home) { finish();return true };return super.onOptionsItemSelected(item)
    }
}
class RecordingSettingsFragment:PreferenceFragmentCompat() {
    override fun onCreatePreferences(state:Bundle?,rootKey:String?) {
        val context=requireContext();preferenceScreen=preferenceManager.createPreferenceScreen(context)
        val available=AudioRecordingProfile.supportedFormats()
        val format=ListPreference(context).apply {
            key="pref_record_audio_format";title="声音格式";dialogTitle="声音格式"
            entries=available.map { it.label }.toTypedArray();entryValues=available.map { it.name }.toTypedArray()
            setDefaultValue(available.firstOrNull { it==AudioRecordingFormat.AAC }?.name ?: "WAV")
            summaryProvider=ListPreference.SimpleSummaryProvider.getInstance();isIconSpaceReserved=false
        };preferenceScreen.addPreference(format)
        if(format.value !in available.map { it.name }) format.value=AudioRecordingProfile.read(context).format.name
        val depth=ListPreference(context).apply {
            key="pref_record_audio_depth";title="PCM 位深";dialogTitle="PCM 位深"
            entries=arrayOf("16-bit PCM","24-bit PCM","32-bit float");entryValues=arrayOf("16","24","32");setDefaultValue("16")
            summaryProvider=ListPreference.SimpleSummaryProvider.getInstance();isIconSpaceReserved=false
        };preferenceScreen.addPreference(depth)
        val bitrate=ListPreference(context).apply {
            key="pref_record_audio_bitrate";title="目标码率";dialogTitle="目标码率"
            entries=AudioRecordingProfile.BIT_RATES.map { "${it/1000} kb/s" }.toTypedArray()
            entryValues=AudioRecordingProfile.BIT_RATES.map { it.toString() }.toTypedArray();setDefaultValue("128000")
            summaryProvider=ListPreference.SimpleSummaryProvider.getInstance();isIconSpaceReserved=false
        };preferenceScreen.addPreference(bitrate)
        val info=Preference(context).apply { title="录制参数";isSelectable=false;isIconSpaceReserved=false };preferenceScreen.addPreference(info)
        fun refresh(selected:String) {
            val wave=selected=="WAV";depth.isVisible=wave;bitrate.isVisible=!wave
            val rate=context.getSharedPreferences("rtlsdr_prefs",android.content.Context.MODE_PRIVATE).getInt("audioSinkRate",48000)
            info.summary=if(wave) "声音：PCM WAV，位深决定文件大小。参数用于下一次录制。\nIQ 保持接收机原始格式。"
                else "声音：${if(selected=="OPUS") "48 kHz Opus" else "${rate/1000} kHz AAC"}，目标码率控制大小。参数用于下一次录制。\n需要再次解码的声音建议使用无损 WAV；IQ 保持原始格式。"
        }
        format.setOnPreferenceChangeListener { _,value -> refresh(value.toString());true }
        refresh(format.value ?: available.first().name)
    }
}
