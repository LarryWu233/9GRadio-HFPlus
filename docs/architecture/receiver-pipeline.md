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

## Implemented ownership boundaries (preview 08)

| Responsibility | Owner / platform boundary | Congestion and shutdown |
| --- | --- | --- |
| Source production / capture metadata | Android source adapter; portable `CaptureClock` / `IqStream` | Live sample-budgeted subscriber queues; file delivery waits; producer joins before resource release |
| Configuration and USB control | Application service controller; portable `PipelineSession` | Serialized commands, conflated tuning; old generations rejected; device monitor requests manual recovery |
| Display | Portable `SpectrumBranch` with an injected FFT | Independent bounded display queue; skipped display blocks do not change measurements |
| SQL / scan measurements | Portable `SignalAnalyzer` / channel meter | Runs independently of display subscriptions and presentation scaling |
| Raw IQ recording | Application `AsyncIqRecorder` | Before correction and IQ NB; explicitly aborts on input or storage gaps |
| Channel processing | Existing demodulation primitives / resampler ports; `CwChannelizer` | Stateful serial processing; CW factory plans close on reset/reconfiguration/stop and partial construction failure |
| Raw IQ ADS-B | Application `ReadsbReceiver` adapter around pinned readsb | Its own child, pipe and observers; typed continuity; joined reset; final snapshot on normal EOF |
| Internal / microphone / WAV audio decoding | Portable `AudioDecoderInput` and `AudioContinuity`; application `AudioContentReceiver` | Explicit epoch barrier; reset/join on gaps; owned copy for in-place Robot36 |
| Digital voice | Application `DigitalVoiceStage`; portable `VocoderAudioAdapter` | Ordered decoder worker, typed 8 kHz PCM output; separate input/output losses and gap recovery |
| External audio decoder | Service-owned `ExternalDecoderStream` adapter | Bounded session queue; sender/socket/DNS ownership survives DSP replacement until truly released |
| Monitoring | Portable `MonitorAudioBranch`; injected `ListeningPostProcessor` | Existing NR, L/R processing, native notch/leveling, gain and SQL fade; never mutates decoder PCM |
| Sound recording | Application `SoundCapture` | Forks processed monitor audio before the playback queue; storage failure and finishing ownership explicit |
| Playback | Portable `AudioOutput` port; Android `AudioEngine` / AudioTrack | Fixed-duration PCM packets, interruptible write and joined shutdown; reports drops and underruns |
| Map / database / UI | Application services and collectors | Decoder emits results; map target and persistence updates stay outside portable DSP |

The monitor extraction preserves the prior analog and digital-voice operation ordering and gain behavior; it is not a new noise algorithm. Frozen pre-extraction comparisons check exact samples with irregular chunks, NR toggles, stereo, mute and gain changes. Recording format/settings and the Views UI remain unchanged.

`DspEngine` still orchestrates several channel/protocol paths and retains compatibility accessors. It is not yet a thin final facade. Further extraction of channel rate/configuration policy and the dual-APRS/protocol router, integrated monitor clock adaptation, complete controlled component decisions, and the remaining acceptance matrix are still required. A stage APK or passing unit suite does not establish those gates.
