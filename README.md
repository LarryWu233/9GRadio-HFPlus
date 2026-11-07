# 9GRadio HF+

[English](#english) · [简体中文](#简体中文) · [Downloads / 下载](https://github.com/LarryWu233/9GRadio-HFPlus/releases)

## English

An experimental Android SDR derivative of [9GRadio by rkarikari / RadioSport](https://github.com/rkarikari/9GRadio), adding Airspy HF+ Discovery support and improving tuning, audio and integrated receiver tools.

**Version:** `0.22.0-hfplus-alpha22` · **Application ID:** `com.radiosport.ninegradio.hfplus` · **Android:** 8.0+ (API 26). Installs alongside the official app.

Alpha22 moves the recording-page expand/restore action to the right of its section heading and removes the coloured backgrounds around the playback controls. See [alpha22 validation](docs/validation/2026-11-08-alpha22.md).

Alpha21 adds a full frosted menu backdrop, an integrated arrow/header, rounded tuning wheels/dialogs and a hidden body when collapsed. Recording rows show duration. Audio recording now offers M4A/AAC, device-supported OGG/Opus and 16/24-bit PCM or 32-bit float WAV. The Media3 in-panel audio player supports pause, seek, long-press forward/back and expansion below the frequency display; IQ retains its existing replay. See [alpha21 validation](docs/validation/2026-11-08-alpha21.md) and [recording/playback provenance](docs/third-party-recording-playback.md).

Alpha20 restricts SQL detection to the selected BW, removes the old closing-side 3 dB offset and adds short power averaging. An experimental one-shot measurement suggests a threshold for explicit user application. See [alpha20 validation](docs/validation/2026-11-07-alpha20.md) and [implementation provenance](docs/third-party-squelch.md); known-sample checks include 65 seconds without a false opening despite a persistent strong off-channel carrier. This is not a claim of completed on-site aviation acceptance.

Alpha19 adds an optional mode station-memory switch (off freezes existing history), a raised panel arrow and a small frosted menu edge using pinned [BlurView](docs/third-party-blurview.md). See the [alpha19 record](docs/validation/2026-11-07-alpha19.md).

### Source baseline

Based on public upstream commit [`002c355ab02069e96da26755a4169095bb25614e`](https://github.com/rkarikari/9GRadio/commit/002c355ab02069e96da26755a4169095bb25614e). Its application build configuration is **1.39 / build 39**; it does **not** contain the complete implementation shipped in the official **1.83 / build 83** APK. This is an independent modified derivative, not an official 1.83 build or a claim of feature parity.

The [upstream README](docs/upstream-README.md) and [initial source/APK comparison](docs/audit/2026-11-06/README.md) are preserved. The upstream README describes the official app, not a verified feature list for this derivative.

### Additions and changes

| Area | Changes |
| --- | --- |
| Receivers | Official **libairspyhf + libusb**, Android USB permission/JNI access without root, floating-point HF+ IQ, device-reported sample rates, capability-aware controls and per-device settings. RTL-SDR, RTL-TCP and IQ file input remain available. |
| Tuning | Coalesced serial background commands; direct spectrum tuning and draggable BW edges; spring-centred speed control, fine tuning and Step wheels with haptics; numeric SPAN/BW input. CLOSED stops and releases the receiver. |
| Display and UI | SDR++-informed peak-preserving spectrum/waterfall resampling; original palettes, automatic levels and optional long-press crosshair; seven dark themes, grouped main frequency digits, separate settings page, reorderable/hideable modules and compact tables. |
| Audio | FM playback-clock/continuity fixes, functional stereo and 50/75 μs de-emphasis, listening SQL, spectral NR, automatic notch and level compensation. WDSP/SDR++ components support pre-demodulation impulse suppression. Digital decoding bypasses listening noise processing; software audio decoders receive pre-NR audio. |
| Scan and recording | Integrated shortwave/FM ETM and range scanning, improved noise rejection, optional regional recommendations; IQ/audio recording with automatic stop and SQL gating; floating-point IQ and SigMF metadata; configurable compressed/PCM audio and an internal audio player. |
| Integrated tools | RTL-SDR Blog V4/readsb ADS-B, in-panel map/radar switching, distance/track information, compact aircraft rows, APRS map gestures and a basic compass/signal-strength direction-finding panel. Existing map providers are retained. |
| New content tabs | **CW/CWR** Morse text using multimon-ng, **SSTV** using Robot36, **SAT** satellite SSTV and historical APT audio images using aptdec. Software audio, microphone and WAV input. **EiBi** shortwave programme schedule with UTC/language filters and click-to-tune; it is not an audio decoder. |

HF+ Discovery receives **500 Hz–31 MHz and 64–260 MHz**. Unsupported RTL-only controls, including bias power, are hidden. The 31–64 MHz gap can be browsed continuously but cannot be received; scanning skips it. First setup prefers a device-supported **768 kS/s** rate and **10 MHz / AM**. 1090 MHz ADS-B requires a suitable receiver such as RTL-SDR; HF+ cannot receive it.

### Limitations compared with the official app

- This is an **alpha based on an older public application snapshot**. Missing official 1.83 implementations have not all been restored: full AIS, the original complete RDF/MLAT chain, newer multi-channel ACARS, satellite tracking, LRPT, Kal/PPM calibration and other newer backends are not supplied as equivalents. The basic direction-finding panel does not replace the original full module.
- The UI is primarily Chinese with common radio terms in English, without complete English localisation. UI/DSP regressions remain possible; keep the official app for comparison.
- New CW/SSTV/SAT decoders passed known-sample tests; over-the-air acceptance and protocol decoding from an external radio through the microphone remain pending. APT supports **historical recordings**, not a claim that retired NOAA-15/18/19 satellites are broadcasting. The ISS preset does not imply an active SSTV event.
- EiBi's bundled **B26** schedule and local CSV import work. On-device online refresh failed with connection/TLS errors in the test environment and remains unresolved. Schedule entries do not confirm reception. Online maps also depend on network availability.
- The downloadable APK is an **arm64-v8a, debug-signed, debuggable test build**. Other ABIs have not received equivalent device validation. Android 16, actual 16 KB page-size hardware, the 60-second frame-rate target and 30-minute continuous reception remain unverified.

### Validation and build

Alpha18 passed **80 host tests and 24 device checks** on a Xiaomi Android 13 arm64 phone with 4 KB pages. Checks cover known PCM/IQ decoder samples, CW routing/recording/replay, microphone capture/lifecycle, FM continuity, map gestures, themes and recording controls. **One additional live EiBi refresh check failed**. All eight packaged native files passed static 16 KB ELF alignment checks and the APK passed zip alignment; this does not replace a 16 KB device test. See the [alpha18 validation record](docs/validation/2026-11-07-alpha18.md).

Pinned tools: **JDK 17, Gradle 8.13, AGP 8.13.2, Kotlin 2.2.0, SDK 35, NDK r28c / 28.2.13676358, CMake 3.22.1**. Helpers require Python 3.10+. Install the Android SDK/platform-tools and accept its licences locally. On Windows, with JDK 17 on PATH and `ANDROID_SDK_ROOT` set:

```powershell
python scripts/bootstrap_toolchains.py
./scripts/build.ps1 -SdkPath "$env:ANDROID_SDK_ROOT" -Abi arm64-v8a
./scripts/build.ps1 -SdkPath "$env:ANDROID_SDK_ROOT" -Abi arm64-v8a -Tasks ':app:testDebugUnitTest'
```

Bootstrap downloads checksum-verified tools into ignored `build/toolchains`; the build helper generates ignored `local.properties`. Alternatively, with your toolchain installed:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest -PtargetAbi=arm64-v8a
```

APK output: `app/build/outputs/apk/debug/`. Device scripts accept `--adb`, or find ADB via PATH, `ANDROID_SDK_ROOT` or `ANDROID_HOME`. Instrumentation tests need a separate test APK and authorised device; the application download contains no test APK. Raw device logs/screenshots, personal machine configuration, signing keys and developer checkout paths are excluded from publication; reproducible test sources are retained. Native build paths are mapped to a generic source prefix.

### Licence and thanks

Distributed under [GNU GPL v3](LICENSE), retaining upstream copyrights and bundled component licences. Exact sources, pinned revisions and modifications are documented in [third-party reuse](docs/third-party-reuse.md), [audio decoder provenance](docs/third-party-audio-content.md), [recording/player provenance](docs/third-party-recording-playback.md), [DSEG licensing](docs/third-party-DSEG.md) and bundled licence files.

Thank you to **rkarikari / RadioSport**, the original author of **9GRadio**, for creating and publishing the application that made this project possible. Thanks also to Airspy, libusb, SDR++, WDSP, readsb, Robot36, multimon-ng, aptdec, GNU Radio, BlurView, AndroidX Media3, MapLibre, EiBi, OurAirports, DSEG and the other upstream contributors credited in the source and licence records.

---

## 简体中文

基于 [rkarikari / RadioSport 的 9GRadio](https://github.com/rkarikari/9GRadio) 开发的 Android SDR 衍生测试版，增加 Airspy HF+ Discovery 支持，改进调谐、声音与集成接收工具。

**版本：**`0.22.0-hfplus-alpha22` · **包名：**`com.radiosport.ninegradio.hfplus` · **系统要求：**Android 8.0+（API 26）。可与官方版同时安装。

Alpha22 将录音页的展开／还原移到“录制”标题行右侧，取消播放控制图标的彩色底框。详见 [alpha22 验证](docs/validation/2026-11-08-alpha22.md)。

Alpha21 将磨砂扩展到整个控制菜单，箭头合入顶栏，调谐轮／弹窗圆角化，收起时隐藏正文。录音列表显示时长；声音录制可选 M4A/AAC、设备支持的 OGG/Opus，以及 16／24-bit PCM、32-bit float WAV。基于 Media3 的页内播放器支持暂停、进度拖动、长按快进／快退及展开到主频率下方；IQ 保留原回放。详见 [alpha21 验证](docs/validation/2026-11-08-alpha21.md)和[录制／播放来源](docs/third-party-recording-playback.md)。

Alpha20 将 SQL 判据限定到当前 BW，去掉关闭侧原有 3 dB 偏移，增加短时功率平均与实验性测量建议，由用户选择应用。详见 [alpha20 验证](docs/validation/2026-11-07-alpha20.md)和[来源记录](docs/third-party-squelch.md)。已知 IQ 样本在持续强带外载波下连续观察 65 秒没有误开启，不声称已完成用户现场航空验收。

Alpha19 增加解调模式“电台记忆”开关，关闭时冻结既有记忆；上移面板箭头，使用固定版本 [BlurView](docs/third-party-blurview.md) 添加轻微磨砂菜单边缘。详见 [alpha19 记录](docs/validation/2026-11-07-alpha19.md)。

### 源码基线

以公开提交 [`002c355ab02069e96da26755a4169095bb25614e`](https://github.com/rkarikari/9GRadio/commit/002c355ab02069e96da26755a4169095bb25614e) 为起点。该快照的应用构建配置为 **1.39 / build 39**，没有包含官方 **1.83 / build 83** APK 的完整实现。本项目是独立修改版，不是官方 1.83，也不声称与其功能完全一致。

保留[原版 README](docs/upstream-README.md) 和[初始源码／APK 对照](docs/audit/2026-11-06/README.md)。原版 README 描述的是官方应用，不能视为本衍生版已验证的功能清单。

### 增加与修改的内容

| 类别 | 主要内容 |
| --- | --- |
| 接收机 | 官方 **libairspyhf + libusb**，Android USB 授权与 JNI 无 root 接入；HF+ 浮点 IQ、设备实际采样率、按设备能力显示控制项、分设备保存设置；保留 RTL-SDR、RTL-TCP 和 IQ 文件输入。 |
| 调谐 | 后台串行合并硬件命令；拖动频谱调谐及 BW 两侧边界；自动回中变速滑条、精调与 Step 滚轮、振动、SPAN/BW 数字输入；CLOSED 真正停止并释放接收机。 |
| 显示与界面 | 参考 SDR++ 的保峰重采样；保留原版频谱／瀑布配色、自动电平及可选长按十字光标；七套暗色主题、主频率分组段码数字、独立设置页、模块排序／隐藏和紧凑表格。 |
| 声音 | 修复 FM 播放时钟与连续性，补齐立体声及 50/75 μs 去加重；监听静噪、频谱降噪、自动陷波、响度补偿；复用 WDSP／SDR++ 内容进行解调前脉冲抑制。数字解码旁路监听降噪，软件音频解码取得 NR 之前的信号。 |
| 扫描与录制 | 整合短波／FM ETM 与范围扫描，改进噪声过滤，可选地区推荐；IQ／声音录制、定时停止、静噪门控；浮点 IQ 和 SigMF 元数据；可配置压缩／PCM 声音格式及页内播放器。 |
| 集成功能 | RTL-SDR Blog V4／readsb ADS-B，模块内地图／雷达切换、距离与轨迹、紧凑飞机列表、APRS 地图手势；基础指南针／场强测向页。地图沿用原有服务方案。 |
| 新增内容页 | **CW/CWR** 使用 multimon-ng 解摩尔斯；**SSTV** 使用 Robot36；**SAT** 为卫星 SSTV 和 aptdec 历史 APT 音频图像；可选软件内、麦克风或 WAV 输入。**EiBi** 为短波节目表，支持 UTC／语言筛选、点击调谐，不是音频解码器。 |

HF+ Discovery 接收范围为 **500 Hz–31 MHz、64–260 MHz**；隐藏不适用的 RTL 控制项，包括偏置供电。31–64 MHz 空档可连续浏览，但不能接收，扫描会跳过。首次连接优先选用设备支持的 **768 kS/s**，从 **10 MHz / AM** 开始。1090 MHz ADS-B 需要 RTL-SDR 等适用接收机，HF+ 无法接收。

### 相比官方版的不足

- 这是基于较早公开应用源码的 **alpha 测试版**。尚未补齐官方 1.83 中源码缺失的全部实现，包括完整 AIS、原版完整 RDF／MLAT 链路、新版多通道 ACARS、卫星跟踪、LRPT、Kal／PPM 校准及其他新版后端；基础指南针页不能代替原版完整测向模块。
- 界面以中文为主，常见无线电术语保留英文，尚未完成完整英文界面。界面和 DSP 改动仍可能存在回归，可保留官方版用于对照。
- 新增 CW／SSTV／SAT 已通过已知样本验证；现场实收及外部接收机经麦克风输入的协议解码仍待验收。APT 面向**历史录音**，不表示已退役的 NOAA-15/18/19 仍在播出；ISS 预设也不代表当前存在 SSTV 活动。
- EiBi 内置 **B26** 节目表和本地 CSV 导入可用；当前环境手机在线刷新出现连接／TLS 错误，尚未解决。节目表条目不等于实收确认，在线地图也取决于网络。
- 下载的 APK 为 **arm64-v8a、debug 签名且可调试的测试构建**，其他 ABI 未获得同等真机验证。Android 16、真实 16 KB 页设备、连续 60 秒帧率目标及 30 分钟连续接收尚未验收。

### 验证与构建

Alpha18 在小米 Android 13、arm64、4 KB 页设备上通过 **80 项主机测试及 24 项真机检查**，涵盖已知 PCM／IQ 解码样本、CW 链路和录制回放、麦克风取音及生命周期、FM 连续性、地图手势、主题和录制控制。另有 **1 项 EiBi 实时联网刷新失败**。全部 8 个打包原生文件通过 ELF 16 KB 静态对齐检查，APK 通过 zipalign；不能替代 16 KB 真机验证。详见 [alpha18 验证记录](docs/validation/2026-11-07-alpha18.md)。

固定工具链：**JDK 17、Gradle 8.13、AGP 8.13.2、Kotlin 2.2.0、SDK 35、NDK r28c / 28.2.13676358、CMake 3.22.1**；辅助脚本需要 Python 3.10+。请自行安装 Android SDK／platform-tools 并接受相应许可。Windows 上确保 PATH 中可用 JDK 17，并设置 `ANDROID_SDK_ROOT`：

```powershell
python scripts/bootstrap_toolchains.py
./scripts/build.ps1 -SdkPath "$env:ANDROID_SDK_ROOT" -Abi arm64-v8a
./scripts/build.ps1 -SdkPath "$env:ANDROID_SDK_ROOT" -Abi arm64-v8a -Tasks ':app:testDebugUnitTest'
```

脚本校验并下载固定版本工具至忽略提交的 `build/toolchains`，为本机 SDK 生成忽略提交的 `local.properties`。也可使用本机工具链配合 Gradle wrapper：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest -PtargetAbi=arm64-v8a
```

APK 位于 `app/build/outputs/apk/debug/`。设备脚本支持 `--adb`，也会从 PATH、`ANDROID_SDK_ROOT` 或 `ANDROID_HOME` 查找 ADB。真机测试需要另行构建测试 APK 并连接已授权设备，应用下载包不包含测试 APK。发布内容不包含原始设备日志／截图、个人机器配置、签名私钥及开发者本机路径，保留可复现的测试源码；原生编译路径映射为通用源码前缀。

### 许可与致谢

按 [GNU GPL v3](LICENSE) 分发，保留上游版权与各组件许可。具体来源、固定提交及修改见[第三方复用记录](docs/third-party-reuse.md)、[音频解码来源](docs/third-party-audio-content.md)、[DSEG 字体许可](docs/third-party-DSEG.md)和随源码提供的许可文件。

感谢 **9GRadio 原作者 rkarikari / RadioSport** 创作并公开这款应用，让本项目有了可以继续改进的基础。也感谢 Airspy、libusb、SDR++、WDSP、readsb、Robot36、multimon-ng、aptdec、GNU Radio、BlurView、MapLibre、EiBi、OurAirports、DSEG，以及源码与许可记录中列出的其他上游项目和贡献者。
