# Preview 20 — hardware follow-up

This supplements the [initial preview-20 record](architecture-20.md). The receiver was reconnected after the initial release was prepared. The tested APK is unchanged from source commit `978e0c1`:

`fc6abb2cc22c5ecc58659cce4198435daac006b80db2bbe47809d76c64946790`

The first attempt had no receiver. After reconnection, a low-battery system prompt covered the app's USB permission dialog and the attempt timed out. After dismissing that prompt and allowing this preview to access HF+, the existing observation passed without source or test changes.

- HF+ Discovery, 768 kS/s: envelope, SAM DSB, SAM USB and SAM LSB, about 24 seconds total.
- Each detector has a two-second settling period followed by a four-second observation window. Input advanced normally and the application IQ loss, driver-reported loss, PCM queue drops and playback-underrun counters had zero increments in each observation window.
- No engine failure; receiver and engine resources stopped through the existing cleanup path. These are short affected-route checks, not a new endurance or whole-app power measurement.
- Together with the previously recorded 160 JVM and 11 Android checks, this adds one passing hardware observation. The failed setup/permission attempts are retained above and are not counted as passes.

No broadcast sample, coordinates, device/network identifiers, local paths or raw diagnostic logs are included. Android 16 user acceptance and deferred actual 16 KB execution remain separate; main is not merged.

中文：接回 HF+ 并处理被低电量提示遮住的 USB 授权后，未改动的预览 20 安装包通过约 24 秒的四种 AM 检波实收检查。各模式稳定窗口的应用／驱动丢样、播放队列丢弃与欠载增量均为零。这是针对注册接线的短验证，不代表新的功耗收益或长期稳定性结论。
