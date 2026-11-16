package com.radiosport.ninegradio.recording

import com.radiosport.ninegradio.source.IqBlock

/** File adapter port, allowing controlled slow/error storage regression tests. */
interface IqRecordingSink {
    val isRecording: Boolean
    fun start(path:String,rate:Int,format:IqRecorder.Format = IqRecorder.Format.RAW_UINT8)
    fun write(block:IqBlock)
    fun capturedDurationMs():Long
    fun stop()
}
