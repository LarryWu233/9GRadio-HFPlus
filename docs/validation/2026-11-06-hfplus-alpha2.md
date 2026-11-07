# HF+ alpha2：手动监听与频谱平线修复

基于公开源码 `002c355` 的衍生测试版，包名
`com.radiosport.ninegradio.hfplus`，版本 `0.2.0-hfplus-alpha2` / 3。
本记录只确认下面列出的验证，不代表完整改造计划已经验收。

## 修复

- OPEN/CLOSED 只由用户点击控制，首次默认 OPEN，保存选择。移除 DSP 统计和断开连接事件对该显示的覆盖。
- CLOSED 只关闭监听输出，保持 IQ、频谱、RDS 及数字协议解码输入；监听处理使用独立数组，避免静音和 NR 改写解码数据。
- FFT 连续积累跨 USB 数据块的抽取结果，再对完整窗口计算。此前 HF+ 的 4096 个样本在 ×64 抽取后仅剩 64 个，直接填入 256 点窗口造成幅度严重偏低。
- 保留跨任意分块边界的抽取相位；每个源数据块最多一次 FFT，避免小 FFT 配置产生多次无用计算。
- 首次接入和应用重启都加载当前设备的配置，避免从旧的全局 RTL 参数覆盖 HF+。
- 模式与采样率文字仅在变化时更新；强度表读取实际 DSP 峰值，200 ms 更新一次。

## 已完成验证

设备：小米 M2007J1SC，Android 13，arm64，4096 字节页；无线 ADB，HF+ USB OTG。

| 验证 | 结果 |
| --- | --- |
| NR 与 NB 单元测试 | 4 项通过 |
| FFT 的 ×64 幅度、任意分块相位、U8/F32 标度 | 3 项通过；0.125 幅度载波约 −18.1 dBFS |
| HF+ 真机驱动 | 通过；浮点 IQ 非零、有限且保留超过 8 位的精度；频段端点可调，无效频率被拒绝 |
| HF+ 实时界面回归 | 通过；CLOSED→OPEN→CLOSED 保持稳定，关闭时仍收到新频谱，页面重建保持 CLOSED；结束恢复 OPEN |
| 已知 AM 样本经 rtl_tcp 输入和 DSP | 通过；频谱和音频有效，手动关闭后解码基带仍非零，重新打开成功 |
| 实际画面 | 已恢复频谱与瀑布图；`build/audit/hfplus-fixed.png` |

测试日志在 `build/audit/manual-listening-*.txt` 和构建日志中；早先因未接设备、MIUI 禁止测试进程后台启动界面及测试函数返回类型而失败的尝试已分别重试，最终结果以 `OK` 的日志为准。
MIUI 上运行界面测试时如后台启动被系统拒绝，可由 ADB 显式启动 MainActivity 供测试附着，无需更改用户系统设置。

## 构建与复现

使用 JDK 17 和已安装 Android SDK；项目脚本固定 Gradle 8.13、NDK r28c 和 CMake 3.22.1。

```powershell
./scripts/build.ps1 -Tasks @(':app:testDebugUnitTest', ':app:assembleDebug', ':app:assembleDebugAndroidTest')
python scripts/iq_fixture.py --mode am --serve 1234
adb -s <手机地址:端口> reverse tcp:1234 tcp:1234
adb -s <手机地址:端口> shell am instrument -w -r -e class com.radiosport.ninegradio.BaselinePipelineTest,com.radiosport.ninegradio.AirspyHardwareTest com.radiosport.ninegradio.hfplus.test/androidx.test.runner.AndroidJUnitRunner
```

APK：`app/build/outputs/apk/debug/9GRadioHFPlus_0.2.0-hfplus-alpha2_debug.apk`。
已安装到备用机，并复制到 `build/artifacts/9GRadio-HFPlus-alpha2.apk`。
SHA-256：`13451585225a6fc447406baa070500c804942d1797be84ceec05ecb154f24368`。
最终画面 `build/audit/hfplus-alpha2.png` 中频谱、瀑布和强度表均有实际输入响应；当时读数约 −73.4 dBFS。
所有源码改动保留在当前工作目录。测试版与官方版并存。

## 尚未验收

30 分钟连续接收、60 秒调谐帧率目标、全部协议实收、RTL USB 硬件回归、地图大陆网络与离线包、录制回放全场景及 NR 人声主观对比仍需后续验证。
Android 16 和 16 KB 页运行环境未实机验证；ELF/APK 静态对齐通过不能替代运行验证。
