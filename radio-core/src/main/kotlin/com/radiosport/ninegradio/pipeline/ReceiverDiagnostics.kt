package com.radiosport.ninegradio.pipeline

/** Whitelisted scalar-only diagnostics. No location, frequency, network, file paths or decoded content. */
data class ReceiverDiagnostics(val epoch:Long=0,val sampleRate:Int=0,val audioRate:Int=0,
    val inputSamples:Long=0,val inputMissingBlocks:Long=0,val inputMissingSamples:Long=0,
    val driverMissingSamples:Long=0,val staleInputBlocks:Long=0,val displaySkippedBlocks:Long=0,
    val decoderDroppedPackets:Long=0,val playbackDroppedPackets:Long=0,val playbackUnderruns:Int=0,
    val decoderOutputDroppedPackets:Long=0,val rawDecoderInputMissingSamples:Long=0,val rawDecoderDriverMissingSamples:Long=0,
    val externalDecoderDroppedPackets:Long=0,val externalDecoderStalePackets:Long=0,val externalDecoderPendingRelease:Boolean=false) {
    fun report(version:String):String = buildString {
        appendLine("9GRadio HF+ 接收链路诊断")
        appendLine("Version: ${version.take(80)}")
        appendLine("配置代次: $epoch")
        appendLine("IQ / Audio: $sampleRate / $audioRate Hz")
        appendLine("已处理 IQ 样本: $inputSamples")
        appendLine("应用 IQ 缺口: $inputMissingBlocks blocks / $inputMissingSamples samples")
        appendLine("驱动报告丢样: $driverMissingSamples samples")
        appendLine("丢弃旧配置数据: $staleInputBlocks blocks")
        appendLine("显示跳过: $displaySkippedBlocks blocks")
        appendLine("数字语音输入丢弃: $decoderDroppedPackets packets")
        appendLine("数字语音输出丢弃: $decoderOutputDroppedPackets packets")
        appendLine("原始 IQ 解码支路应用缺口: $rawDecoderInputMissingSamples samples")
        appendLine("原始 IQ 解码支路驱动丢样: $rawDecoderDriverMissingSamples samples")
        appendLine("外部解码发送丢弃: $externalDecoderDroppedPackets packets")
        appendLine("外部解码旧配置丢弃: $externalDecoderStalePackets packets")
        appendLine("外部解码等待释放: $externalDecoderPendingRelease")
        appendLine("播放队列丢弃: $playbackDroppedPackets packets")
        appendLine("播放欠载: $playbackUnderruns")
        appendLine("此摘要不包含位置、频率、网络地址、本机路径、接收内容或原始日志。")
    }
}
