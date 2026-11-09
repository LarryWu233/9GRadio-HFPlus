# Android 16 manual acceptance / Android 16 手动验收

Status: **pending user acceptance**. Passing the Android 13 automation or static ELF/APK checks does not complete this checklist. Use the independent **9GRadio HF+ 架构预览** APK; the stable app and its data remain separate. No ADB or auxiliary test APK is needed.

请白天有空时逐项记录“通过／异常／未测”；发生异常时记下模式、操作和大致持续时间即可，不必公开位置、接收内容或原始日志。

| 检查 | 操作及预期 |
| --- | --- |
| 版本与运行环境 | 打开诊断页，核对预览版本与发布记录一致；记录 Android API 与系统页大小（4096／16384 B），摘要不包含型号或设备标识 |
| HF+ | OTG 接入 HF+，允许预览版访问；选择已知有信号的 AM、SSB、WFM，各连续听音并调谐，确认无规律断音、卡死或错误模式恢复 |
| RTL | 换接 RTL，检查 USB 授权、接收、调谐，以及该设备适用的功能入口 |
| 关闭与重连 | CLOSED 后停止接收；拔插、重新 OPEN，检查只恢复当前设备，不出现旧音频或 OPEN/CLOSED 自动振荡 |
| 后台恢复 | 接收时回到桌面、锁屏后返回，检查状态及声音是否正常；手动关闭后不应自行恢复接收 |
| 声音处理 | 在模拟语音中切换 NR/NB、陷波和静噪；数字解码不经过监听降噪支路 |
| 录制 | 录一段声音，检查时长、自动停止／按静噪门限录制及原有格式设置；播放、暂停和拖动进度正常 |
| IQ 文件 | 原有 IQ 录制／回放及正常文件结束可用；不要求本轮增加新的 IQ 播放交互 |
| 诊断 | 查看链路状态及各支路损失；导出摘要默认不包含位置、网络地址、本机路径和接收内容 |

Android 16 关键流程须由用户确认后才能满足合并条件。实际 16 KB 页运行环境仍单独记录；未知页大小时填“未验证”，不要将 Android 版本或静态对齐等同于 16 KB 运行通过。
