package com.radiosport.ninegradio.content

// Keep names/order/package compatible with stored settings and application callers.
enum class AudioContentKind { CW, SSTV, SAT }
enum class ContentInput { SOFTWARE, MICROPHONE, WAV }
data class ContentConfig(val kind:AudioContentKind,val input:ContentInput=ContentInput.SOFTWARE,
    val apt:Boolean=false,val sstvMode:String="Auto",val wpm:Int=0)
