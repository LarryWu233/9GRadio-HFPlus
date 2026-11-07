# 成熟实现的复用记录

## SDR++ 显示重采样

- 项目：[AlexandreRouma/SDRPlusPlus](https://github.com/AlexandreRouma/SDRPlusPlus)
- 固定提交：`f10fb98af8b80de01de94e60c17608e4281e2515`
- 原实现：[core/src/gui/widgets/waterfall.cpp 的 doZoom](https://github.com/AlexandreRouma/SDRPlusPlus/blob/f10fb98af8b80de01de94e60c17608e4281e2515/core/src/gui/widgets/waterfall.cpp)
- 许可证：GPL-3.0；本衍生项目同为 GPL-3.0，复用组件的原始许可文本随 `docs/licenses/SDRPlusPlus-GPL-3.0.txt` 提供。
- 接入位置：`ui/SpectrumRasterizer.kt`、`ui/WaterfallView.kt`。
- 复用方式：将每个显示像素覆盖的 FFT bins 取最大值，保留窄峰；放大时保留原 bin 电平，不在两个 bin 之间混色。Kotlin 适配分数平移坐标、可见范围与越界空白，复用输出行数组。
- 范围：仅显示栅格化；未替换 FFT、滤波、重采样或监听音频算法。
- 验证：窄载波落在旧插值位置之间、放大保持独立 bin 电平、越界不伪造边缘信号的单元测试；实际 Android 位图检查；既有频率定位、历史保持与环绕测试。

## SDR++ 的 HF+ 启动顺序

- 同一固定提交的 [source_modules/airspyhf_source/src/main.cpp](https://github.com/AlexandreRouma/SDRPlusPlus/blob/f10fb98af8b80de01de94e60c17608e4281e2515/source_modules/airspyhf_source/src/main.cpp)。
- 参考其先选择速率、配置硬件、再开始采样的顺序；复用本项目既有接收源关闭/开启流程，使运行中变更 HF+ 采样率也重新建立对应的 DSP 和播放时钟。
- 这是接入与生命周期适配，没有复制或另写音频后端。官方 libairspyhf 仍负责 HF+ 采样、校正与硬件控制，驱动来源见 `app/src/main/cpp/vendor/sources.json`。

## alpha14：readsb ADS-B 与地图呈现

- ADS-B 复用 [wiedehopf/readsb](https://github.com/wiedehopf/readsb) 固定提交 `5d13a0c2b2a6d22949b8b13c5424f28b7f3e1ead`，GPL-3.0-or-later。源码、COPYING、版本和归档 SHA-256 在 `cpp/vendor/readsb/`；链接 zstd v1.5.7，按 BSD-3-Clause 使用，完整许可在 `cpp/vendor/zstd/`。
- 原样编译 `demod_2400.c`、Mode S、CRC、CPR 与跟踪内核，独立进程从 stdin 接收 **2.4 MS/s UC8 原始 IQ**。Android 继续独占 USB；不启用 readsb 的原生 USB 后端，不将监听 NR/NB/SQL 音频送进解码器。
- Android 适配仅涉及 `readsb.h` 中的 `posix_memalign` 兼容分配、`util.c` 使用现有 POSIX `pthread_join` 路径、CMake/PIE 打包和进程/JSON 生命周期管理。未另写或修改 ADS-B 解调及定位算法。
- 地图复用 [MapLibre Native Android 13.7.1](https://maplibre.org/maplibre-native/android/api/) 的 OpenGL 包；原版 APK 使用的 OSM 栅格地址作为默认来源。地图与雷达在接收模块内切换，目标数据由接收服务维护。MapLibre 保留 HTTP 缓存；请求携带应用 User-Agent，按 [OSM 瓦片政策](https://operations.osmfoundation.org/policies/tiles/) 显示署名，不批量下载公共瓦片。
- 指南针由 Android [方向传感器 API](https://developer.android.com/develop/sensors-and-location/sensors/sensors_position) 提供磁北朝向，显示层只绘制刻度，不将手机朝向称为无线电信号方位。距离使用 Android `Location.distanceBetween`，不从信号强度推算距离。

## 未集成的参考

Google Oboe、SDR++ Android AAudio 音频模块本轮仅阅读。实收证据指向启动时的设备速率与处理时钟不同步，本轮没有将它们接入 APK，也不将它们列为已使用的音频依赖。

## alpha11：监听响度补偿与自动陷波

- AGC：复用相同固定提交的 [SDR++ loop/agc.h](https://github.com/AlexandreRouma/SDRPlusPlus/blob/f10fb98af8b80de01de94e60c17608e4281e2515/core/src/dsp/loop/agc.h)。原文件与 GPL-3.0 文本在 `cpp/vendor/sdrpp/`。将包络跟踪、增益计算和限幅前瞻适配为独立处理器，去掉 SDR++ 线程/stream 框架；立体声以左右最大幅度共用增益，并补齐对称补偿上限。监听包络参考设为 0.025（约 −32 dBFS），攻击 5 ms、恢复 400 ms，补偿范围可选 ±3 至 ±18 dB，默认 ±12 dB。
- 自动陷波：直接编译 [Warren Pratt WDSP anf.c](https://github.com/g0orx/wdsp/blob/49084f50c583a73644e03bcb56443fa9deb327de/anf.c)，固定提交 `49084f50c583a73644e03bcb56443fa9deb327de`，GPL-2.0-or-later；选择按 GPL-3.0 与本项目组合分发。原许可声明、完整 `COPYING` 在 `cpp/vendor/wdsp/`。
- WDSP 修改仅为：`comm.h` 换成 Android 独立分配/类型适配头；编译条件排除接收机全局配置接口及 Windows 导出声明。**xanf 内核未改写**。沿用 RXA.c 的 2048 环形缓冲、64 taps、16 samples delay 与变量泄漏参数；使用上游可调参数 `two_mu`，界面跟踪速度 1–10 对应 0.001–0.010，默认 0.005。
- `listening_audio_jni.cpp` 仅负责缓冲、左右声道和启停 20 ms 混合；`ListeningAudioProcessor.kt` 管理线程安全的 native 生命周期。关闭两项时保持样本值；不增加帧、不丢弃帧。切模式/输出速率清理处理历史。CW/CWR/RAW 暂停陷波，保留有意的音调。
- 接入位置在监听音频分支、静噪和音量之前；APRS/ACARS 等解码输入及 `audioFlow` 保持原始解调音频。已解码数字语音仅在进入扬声器前处理。
- 响度补偿不会提升射频信噪比；陷波针对持续窄带音调，可能削弱音乐或语音中的稳定谐波，默认关闭，分别提供开关。

## alpha12：解调前宽带 IQ 脉冲抑制

- 直接编译 WDSP 作者维护的 [TAPR/OpenHPSDR-wdsp 2.11 nobII.c](https://github.com/TAPR/OpenHPSDR-wdsp/blob/e69de50d1d702216098019013899ad87534c64e0/wdsp%202.11/Source/nobII.c)，固定提交 `e69de50d1d702216098019013899ad87534c64e0`。GPL-2.0-or-later，与本项目按 GPL-3.0 组合分发；原文件版权声明和许可在 `cpp/vendor/wdsp-nb/`。
- AM/USB/LSB/DSB 选择插值式 NB2 的 mode 4，复用它的幅度背景估计、脉冲判断、前后样本插值、余弦过渡和持续干扰恢复状态机。`xnob` 内核与该提交逐字符相同，没有另写检测或修复算法。旧 g0orx 副本的持续干扰恢复分支有不同的数组索引，选用作者发布版已有修正。
- 上游文件仅更换 `comm.h` 为独立 Android 类型/分配适配头；Windows 临界区由 Kotlin 对象监视器承担串行处理、配置及销毁。分配容量由当前固定采样率计算，覆盖 RTL 3.2 MS/s；速率变化重建实例，不在原容量上提高采样率。
- 接入参数：前/后过渡各 50 μs、提前 50 μs、保持 100 μs、最大修复序列 2 ms、背景跟踪 10 ms。检测门限为背景幅度的 3–20 倍，默认 8 倍。是实例参数选择，不是内核改写。
- 原始 IQ 先录制、生成频谱并供原始数据消费者使用；复制为处理缓冲后，在通道抽取/滤波及解调前执行 IQ NB。原始输入块保持不可变；FFT/瀑布展示原始接收情况。
- WDSP 分支用匹配的干路延迟及 2 ms 混合衔接开关，保证同一模式下开关前后采样时序一致。912 kS/s 时固定延迟 2061 个复数样本，约 2.26 ms。模式、频率、速率变化清理旧历史。
- FM/WFM/WFM Stereo/NFM 使用同一固定提交的 [SDR++ noise_reduction/noise_blanker.h](https://github.com/AlexandreRouma/SDRPlusPlus/blob/f10fb98af8b80de01de94e60c17608e4281e2515/core/src/dsp/noise_reduction/noise_blanker.h)。保留幅度跟踪、超门限判断、I/Q 同比正增益缩放；仅去除 stream 框架，适配交织样本和 10 ms 跟踪参数。该分支不插值、不改变采样位置与相位、无前瞻延迟，开关的 2 ms 混合同样只改变正增益。
- 广播 FM 补充测试实际发现通用 WDSP 插值参数会加重相位杂声，因此正式版本分开选择算法，而不将 AM 的参数直接套在 FM 上。此处复用的是 SDR++ 已有 IQ 幅度抑制，不是新写 FM 修复算法；原头文件与许可随源码提供。
- 按用户更新的要求，只有 AM/FM/WFM/WFM Stereo/NFM/USB/LSB/DSB 使用降噪处理。数字模式与 CW/CWR/RAW 完整旁路 IQ NB、音频 NR、陷波和响度补偿；界面关闭并禁用相应控件，模拟模式选择仍被记忆。CW 属于音调信号，此次也保留原样。未接入任何神经网络组件。
- 删除接收链路中的单声道及左右声道音频 NB 调用，避免同一脉冲又在音频端置零。`AudioDsp.NoiseBlanker` 仅保留给旧诊断代码，不参与接收。
