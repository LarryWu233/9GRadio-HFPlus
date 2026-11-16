# Nightly checkpoint — 2026-11-09

The user requested a stop at approximately 75% engineering progress, after the tests running at that point finished. That scope is frozen; the final HF+ SSB run has finished, and work is paused for daytime resumption. This percentage is an estimate of engineering scope, **not** completion of every acceptance gate or a remaining-hours prediction.

- Branch: `refactor/receiver-pipeline`; main remains `9e6aa4b`, with no merge. The draft PR remains draft.
- Receiver source: preview 08, `0562900`, following decoder/lifetime preview 07, `3c8618c`. Later checkpoint commits contain documentation and bounded test-runner cleanup only; they do not change the installed receiver APK.
- Preview package: `com.radiosport.ninegradio.hfplus.arch`, independent from the stable app and its data.
- Artifact SHA-256: `d84db9dbc081888932c42021c90a145b58637f7425ff0c4b13f6123b3e13bb72`.
- 132 JVM tests pass. Android 13 combined regression passes 35/36, including 300 processing lifecycle cycles. High-rate CW remains an explicit failed gate.
- Current preview HF+ USB/SSB reception passes a full 30-minute stable window. Application/driver IQ loss, playback-drop and underrun increments are zero. Warm-up playback drops: two, final cumulative two.
- All three ABIs build; 64-bit ELF/APK static 16 KB alignment passes. Actual 16 KB runtime and Android 16 manual acceptance remain pending.
- No Oboe/liquid-dsp/SpeexDSP production replacement is enabled. Existing UI, maps, formats and stable-app data are preserved.

On resumption, start with the controlled high-rate CW failure rather than declaring the full regression passed. Then continue the [remaining acceptance checklist](remaining-acceptance.md). The [Android 16 checklist](android16-manual.md) does not require ADB. Keep the branch separate from main until the agreed gates and user acceptance pass.

Public release assets contain the matching application APK, checksum, static-alignment report and sanitized validation. Credentials, device endpoints, personal paths, raw diagnostics, receiver recordings, test fixtures and auxiliary test APKs are excluded. Temporary native debug helpers are removed; the preview receiver is stopped and its auxiliary test package is removed at the pause.

中文：已到约 75% 的工程停点，等待当时正在运行的最后一轮 SSB 长测完成后暂停。该比例不代表全部验收通过。白天先继续定位高采样率 CW，再补齐其余硬件、性能与 Android 16 验收；主干不合并，稳定版数据保留。
