# 实收断音：成熟实现对照与诊断

## 本轮边界

用户反馈 alpha8 的随机碎片感减轻，但仍有规律性断音。本轮先核对参考实现并增加观测，不据此直接替换 DSP、重采样或音频后端。既有 Kotlin 自研处理链仍在使用，不能称为已经迁移到 SDR++ 或 Oboe。

## 已阅读的源码

SDR++ 固定参考提交 `f10fb98af8b80de01de94e60c17608e4281e2515`，GPL-3.0，与当前项目许可兼容。参考 checkout 位于被忽略的 `build/references/SDRPlusPlus`，不作为应用依赖打包。

- [Android 音频输出](https://github.com/AlexandreRouma/SDRPlusPlus/blob/f10fb98af8b80de01de94e60c17608e4281e2515/sink_modules/android_audio_sink/src/main.cpp)：AAudio、48 kHz、双声道浮点、独立工作线程；Packer 按约 1/60 秒组织输出，再进行阻塞写入。
- [stream](https://github.com/AlexandreRouma/SDRPlusPlus/blob/f10fb98af8b80de01de94e60c17608e4281e2515/core/src/dsp/stream.h) 和 [Packer](https://github.com/AlexandreRouma/SDRPlusPlus/blob/f10fb98af8b80de01de94e60c17608e4281e2515/core/src/dsp/buffer/packer.h)：通过双缓冲和条件变量施加背压，不以丢弃最旧音频块作为正常节流方式。将背压一路传到不可暂停的 USB 接收端仍可能导致驱动丢样，不能只移植一个容器便视为修复。
- [Google Oboe 官方指南](https://github.com/google/oboe/blob/main/docs/FullGuide.md) 和 [1.9.3 源码](https://github.com/google/oboe/tree/1.9.3)：Apache-2.0；提供 AAudio/OpenSL ES 接入、FIFO、固定块适配和延迟调节。实时回调对阻塞、分配和锁有严格约束。当前仅完成阅读，未集成。
- SDR++ `power_squelch.h` 采用块平均幅度判断并整块置零，文件本身标注待改写。它不足以直接证明是本项目静噪的合适替代品；不凭项目名选取其中任意组件。

## 下一步的选择依据

1. 同一电台、同一模式记录 IQ 输入节奏、驱动丢样、DSP 序列丢块、PCM 队列丢包、AudioTrack 欠载及静噪开闭。
2. 对比 SQL 关闭与原门限，NR/NB 关闭与原设置；每次只改变一个因素，测试后恢复。
3. 若 IQ 已缺失，先查接收和处理负载；更换音频输出不会恢复缺失样本。
4. 若 PCM 连续且仅播放欠载，优先验证 Oboe 或 SDR++ 的 Android AAudio 接入，复用既有组件并只编写 JNI/状态适配。引入时固定版本、保留许可、重新检查原生库页对齐和实际路由恢复。
5. 若输入输出平均速率存在漂移，采用成熟时钟/重采样组件。不得通过周期丢帧、补零或无依据扩大缓存掩盖。

## 实机观测工具

`app/src/androidTest/.../LiveReceptionTrace.kt` 默认观察当前接收设置；可选 `traceMode`、`traceFrequency`、`sqlOff=true` 临时设置对照条件，结束后还原原模式、频率、自动解调和 SQL。NR/NB 与 DSP 实现不由工具替换。运行前接好 HF+、授权 USB 并开启接收。默认采集 30 秒，允许参数 `traceSeconds` 为 5–120 秒。无授权接收机时明确跳过。

```powershell
adb -s <无线地址:端口> shell am instrument -w -e class com.radiosport.ninegradio.LiveReceptionTrace -e traceSeconds 30 com.radiosport.ninegradio.hfplus.test/androidx.test.runner.AndroidJUnitRunner
# MIUI 阻止测试启动界面时，在另一终端显式启动已安装的 MainActivity。
adb -s <无线地址:端口> shell run-as com.radiosport.ninegradio.hfplus cat cache/live-reception-trace.jsonl
```

JSONL 每 100 ms 采样；文件首行保存接收源、频率、速率与解调链，后续记录回调数、样本数、最大回调间隔、静噪状态及队列/播放诊断。该工具不采集麦克风，也不将“测试执行结束”当作断音已修复。

## alpha10 实收进展

- 原完整应用两次测得：处理按 912 kS/s 配置，实际出流约 768 kS/s；30 秒 230 次、15 秒 114 次播放欠载，均无 IQ 队列丢块。
- 使用同一官方驱动单独测试 912/768/456/228 kS/s 及 HF/VHF 调谐，出流速率均与请求匹配。
- 依照 SDR++ 接入顺序，先设置设备速率，再启动采样和 DSP。首次修复后 30 秒测得约 912.25 kS/s、0 欠载。
- 运行中变更 HF+ 速率复用完整接收源重启流程；912→768→912→456→768 kS/s 的各档稳定窗口全部匹配，欠载增量均为 0。
- 用户在 87.8 MHz 反馈听感恢复。但随后两分钟 WFM 及两分钟 Stereo 记录仍分别有 2、4 次欠载；这些记录期间没有锁定用户调谐，不能将偶发欠载归因于某个尚未验证的因素，也不能宣称完全归零。
- 新诊断加入每窗频率、采样率与 Android AudioTimestamp；`scripts/summarize_reception_trace.py` 可生成摘要。继续定位前先区分稳定接收和调谐/参数变更期间的停流，保留全部原始记录。

本轮没有换用 Oboe/AAudio、没有重写重采样或降噪算法。重现问题消失的证据支持接入顺序修复；固件或 USB 线程内部为何在原顺序下出现持续不同步，尚未作为独立结论证明。

### 静置对照结果

后续 WFM 静置 120.081 秒，频率、速率、BW、NR/NB 逐窗未变，观测速率约 912007 S/s，IQ/PCM 丢块、播放欠载和写入错误均为 0。一次未拦截触摸的 Stereo 记录发生 18 个调谐频率变化并出现 7 次欠载，不能计入静置样本。随后使用测试进程内临时触摸拦截，Stereo 静置 120.001 秒，观测速率约 911967 S/s，同样均为 0。

用户进一步指出固定测试中心使实际信号靠近通带边缘；滤波裁切造成的音质变化不归为音频链路 bug。以上测试仅证明该条件下的帧连续性，听感对比仍应先让实际信号正确居中。测试结束移除触摸拦截，并通过偏好快照还原模式、频率历史及原设置。
