# Receiver pipeline refactor / 接收管线重构

Branch: `refactor/receiver-pipeline`. Baseline: `main@9e6aa4b3b58e5434be3d1136755eabd8985ce6f5` (alpha28).

The preview uses `com.radiosport.ninegradio.hfplus.arch`, independent storage and the existing Views UI. Main is not changed or merged until regression and user-run Android 16 acceptance pass. No automatic settings import.

## Milestones

1. Baseline, source data contracts and trustworthy loss counters.
2. Configuration epochs, discontinuity recovery and joined shutdown.
3. Independent display/measurement, recording, decoder and listening branches; platform adapters around a JVM-only core.
4. Individually benchmark pinned mature components; adopt only on demonstrated correctness or performance benefit.
5. Hardware, fault, long-run, frame-time and Android 16 acceptance; PR and pre-release artifacts.

## Contracts

`radio-core` must not import Android, USB/JNI, UI, maps, files or databases. IQ blocks own read-only published arrays. Metadata is stamped before queues: stream, capture configuration epoch, sequence, first-sample position, sample rate, centre, software arrival time and driver loss. Software arrival time is not an ADC clock. Playback audio describes channels, rate, epoch and first frame explicitly.

Live subscribers have independent coroutine Channels, bounded by approximately 200 ms of samples. Overflow evicts queued data and is counted; a slow diagnostic/display subscriber cannot stall a decoder. Consumer sequence/sample gaps, producer-driver losses and display skips are separate metrics. Normal file input uses rendezvous delivery and EOF; it must not inherit a live drop policy.

The existing algorithms remain the reference. Raw IQ recording precedes correction/NB. Decoder audio precedes listening NR/notch/level compensation. Sound recording is to branch before playback buffering. Measurements must not depend on dropped display frames. Routine fine tuning must not reconstruct the complete pipeline.

## Preview build

Use JDK 17 and the repository's pinned Gradle/AGP/Kotlin/NDK/CMake versions. `scripts/build.ps1 -Tasks :app:assembleArchPreview -Abi arm64-v8a` builds the separate preview. Optional `-ToolchainRoot` and `-GradleUserHome` reuse an existing toolchain/cache without committed machine paths. Instrumentation uses `-PtestBuildType=archPreview`; the device runner accepts `--package com.radiosport.ninegradio.hfplus.arch`.

## Component decision gates

Compare Oboe 1.9.3 (`b15f5e39c01a7ada306d959e5129620b145fb8b4`), liquid-dsp v1.8.3, SpeexDSP 1.2.1 and the baseline's vendored PFFFT independently. Record exact source/checksums, licence, build flags and applicability before integration. No automatic upstream updates and no new DSP algorithms.

Adoption requires all correctness, continuity, lifecycle, ABI and alignment gates, plus a reproduced correctness fix or >=20% lower median processing time across five controlled measurements. A correctness fix may cost <=10% more processing time and still must meet the real-time budget. Otherwise retain the current component behind its interface. Audio priority is continuity, with 150–300 ms stable listening latency. Do not hide clock mismatch using periodic drops/zeros or unbounded buffers.

## Acceptance and privacy

Preserve all existing features, settings keys and recording/IQ formats. Baseline host tests, packet/PCM sample tests, fault injection, 300 lifecycle cycles, ±100 ppm output-clock simulation, HF+/RTL long-run reception and UI frame-time checks are separate gates. The user tests Android 16 without ADB. Real 16 KB-page execution is not established by ELF/APK static alignment. No merge until required gates pass; pending gates stay explicitly pending.

Public artifacts exclude device IDs, network/location details, personal paths, credentials, raw reception/test captures and instrumentation APKs. Only sanitised validation summaries and matching application APK/checksum/source are published.

## 中文说明

本分支按上述阶段推进，预览版与现有 HF+ 版本并存。当前阶段交付不表示完整重构完成；逐阶段验证记录说明实际完成范围。监听和解码分流、数据连续性、停止顺序和录制独立性是核心目标；不改变地图方案，不新增协议，不迁移 Compose。
