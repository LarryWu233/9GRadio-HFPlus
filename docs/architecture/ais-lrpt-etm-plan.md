# AIS, LRPT and ETM follow-up

Continue on `refactor/receiver-pipeline`, preserving main, existing Views, map providers and persistence. Deliver separately reviewable checkpoints in the order below. Registration alone is not a decoder implementation.

## 1. ETM viewport and schedule hints

- Size the ETM results against the actual expanded panel viewport and system insets, rather than retaining the fixed normal-height table. Preserve compact mode, row readability and list scrolling.
- Add a shortwave station-name hint from the existing offline EiBi schedule. Frequency tolerance follows the existing EiBi view (1.5 kHz); apply UTC time, weekday and season validity before ranking.
- User decision: location ranks candidates; it never excludes distant countries. Resolve relay transmitter codes rather than using the broadcaster's home country. Parse only unambiguous coordinates from the bundled EiBi transmitter-code reference; unknown/conflicting locations remain unknown. Retain every valid same-frequency candidate, with a stable order when location is unavailable.
- Display the preferred name plus an ellipsis for multiple candidates. Names are schedule hints, not identities detected from the signal. FM retains its existing results.
- The name cell opens EiBi with the scanned frequency and time, without retuning or changing the receiver mode. Existing row tuning remains available. Candidate lookup must not enter the IQ/DSP hot path or create a new location service.
- Validate the real expanded table and navigation, reference-code parsing, relay ranking, no-location behavior and time/frequency boundaries. Preserve user ETM memories around UI tests.

## 2. AIS

- Prefer the existing AIS-catcher core, pinned to a reviewed commit with licence/source records. The current official source candidate `d5b1e0a50c68f81e8ef59a057899530bf365fc0a` compiles for Android ARM64 with external drivers, web viewer/API, database and optional library dependencies disabled. This is a build probe, not completed app integration or decoder validation.
- Feed existing raw IQ into the decoder, keeping unsigned RTL IQ and Float32 HF+ precision. Android retains exclusive USB/source ownership. Choose a supported efficient sampling rate for simultaneous AIS A/B reception at 161.975/162.025 MHz, centered at 162 MHz.
- Register the data-only mode and capability requirements. No listening/NR/AudioTrack branch; muted control remains disabled. IQ recording and existing source playback remain independent.
- Route typed vessel updates into the existing map/list presentation: MMSI, available identity, position, speed, course/heading, age, tracks and distance. Missing values remain missing; stale positions are visible as stale. No automatic external feed/upload.
- Verify actual reference decoding, two IQ formats, blocks/gaps/EOF, switch/stop/release, source ownership and short CPU/memory observations. Do not claim real RF validation when only replay is available.

## 3. LRPT

- Prefer SatDump's mature Meteor LRPT chain, including its own synchronization, error correction and image processing. Pin the selected source and dependencies before integration; assess the minimal headless Android build and package size rather than copying its desktop UI or all satellite plugins.
- Use raw IQ, with existing source-generation/gap/stop contracts and data-only monitoring policy. Preserve existing audio-based SSTV/APT and microphone/WAV behavior separately.
- First version: manually selected supported Meteor LRPT profiles, live reception, supported IQ replay, synchronization/valid-frame/error-correction status, incremental image preview and save history. Satellite pass prediction, automatic scheduling and tracking/tuning are later work.
- Reuse current module/page styling and expand the image page under the frequency display. Bound working memory and update images when data changes; do not redraw entire products on every IQ block.
- Validate independent samples through the actual native chain and Android adapter before exposing a working mode. Use short relevant lifecycle/resource checks; record missing RF passes explicitly.

## Delivery

Fixed source/licence records, all supported ABI builds, static native/ZIP alignment, sanitized validation, matching APK/checksum and independent preview package. No credentials, device/network data, local paths, recordings, test fixtures or helper APKs in public assets. Retain draft PR and the existing Android 16 acceptance gates; no main merge in this work.

中文：按 ETM 界面与离线名称 → AIS → LRPT 分阶段推进。定位只排序，全部候选保留；名称点击查询不改频。AIS/LRPT 都通过原始 IQ 解码并关闭监听处理。优先复用成熟实现，先验证真实解码链路，再提供可用入口；卫星过境预报后续加入。
