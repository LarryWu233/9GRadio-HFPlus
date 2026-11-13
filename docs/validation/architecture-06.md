# Architecture preview 06 — audio contracts and decoder ownership

Base: `f8d9086` on `refactor/receiver-pipeline`. Application version: `0.28.0-hfplus-alpha28-arch06`. Main remains unchanged.

## Changes

- Internal demodulated audio, microphone PCM and WAV input share an owned `AudioBlock` decoder port carrying rate, channels, epoch and first-frame position. Compatibility flows remain available; observation flow congestion cannot hold up protocol input. The decoder attachment is one immutable snapshot at each IQ processing boundary.
- Audio-content cancellation is idempotent. Shutdown waits for the content worker, result reader, packaged native process and source release before permitting a replacement session. Timed-out ownership is retained and reported. UI actions enqueue lifecycle operations on the service controller, with a separate command generation preventing a delayed start after Stop or microphone backgrounding.
- Receiver shutdown stops IQ production before waiting for encoders, decoders or recording database updates. The independent stable application's data is untouched.
- TCP handshake and read cancellation have separate timeout policies. Short final reads deliver every complete complex sample, with a truncated half-sample reported explicitly. Opening sockets are closed on handshake failure. RTL direct-sampling policy lives in the source adapter rather than the portable demodulation enum or shared DSP.
- The device runner has an explicit headless mode for non-UI benchmarks. Its normal launcher behavior remains available for MIUI UI tests.

## Validation scope

115 JVM tests pass (16 core, 99 app), with no failures or skipped fixture tests. New cases exercise owned audio clocks, format/epoch transitions, a fragmented TCP header slower than the cancellation read timeout, short EOF tails and cancellation of a blocked read.

Android 13 arm64: **14 combined tests pass in 57.940 s**, including 30 real native-decoder WAV sessions without retained content/source workers, four CW/SSTV/APT content cases, all seven IQ-to-content cases, native ADS-B replay and the service control regression.

The initial high-rate CW case exceeded its 15% timing allowance (7.929 and 8.336 s for 6.840 s of input). Instrumentation identified 3.785–4.301 s spent generating IQ with trigonometric functions during the realtime window. The test now prepares the mathematically equivalent periodic fixture first, checks its values against the original expressions, and retains the original timing, decoding, spectral and exact-frame-count assertions. It does not change production DSP or relax a threshold. Five alternating comparisons use the same test binary on archived arch05 and current arch06:

| Trial | arch05 wall ms | arch06 wall ms |
| --- | ---: | ---: |
| 1 | 6839 | 6839 |
| 2 | 6859 | 7108 |
| 3 | 7422 | 7106 |
| 4 | 7364 | 7101 |
| 5 | 7392 | 7137 |

All ten runs pass and produce exactly 328,320 audio frames. Medians are 7364 / 7106 ms, but these include intentional source pacing and are **not** a processing-speed improvement claim. The final combined run also passes this unchanged timing allowance (6891 ms). Failures from the earlier contaminated measurement remain recorded here.

HF+ at 768 kS/s passes separate WFM, AM and USB/SSB checks, each with 5 seconds of warm-up and a 30-second stable window. Application IQ loss, driver loss, playback drops and playback underruns have zero increments within all three windows. WFM accumulates 3 playback packet drops during warm-up (final cumulative 3); AM and USB finish with cumulative 0. These short checks are not replacements for the required 30-minute cases. No display subscriber is attached, so they are not UI-performance measurements.

All three ABIs build. Static inspection checks all 24 ELF libraries: the 16 arm64/x86_64 libraries satisfy 16 KB alignment, while the 8 armeabi-v7a libraries are checked against their 4 KB requirement and are not labelled 16 KB-ready. APK `zipalign -c -P 16 4` passes. Artifact scanning finds no development Windows paths, device endpoint, private-key headers or GitHub token patterns. The test auxiliary APK, raw diagnostics and fixtures are excluded from release assets. The actual test device uses 4 KB pages; Android 16 and actual 16 KB-page runtime remain unverified.

The native resampling experiments documented in `docs/architecture/component-evaluation.md` remain isolated and are not enabled in reception. This stage does not establish full component adoption, all-mode RF stability, UI performance, Android 16 acceptance or actual 16 KB runtime. Decoder discontinuity recovery and the remaining orchestration extraction are still in progress; this branch is not ready to merge.

中文：本阶段统一音频解码数据块并补齐真实释放等待，修复 TCP 慢握手和短尾块边界。115 项主机测试、14 项真机回归及 30 次原生音频解码启停通过。高采样率 CW 测试排除了即时信号生成的干扰，在不放宽门槛的条件下完成新旧版本五组对照；原先失败的测量也保留说明。完整实收、组件采用和 Android 16 等门槛仍未完成，主干保持不变。
