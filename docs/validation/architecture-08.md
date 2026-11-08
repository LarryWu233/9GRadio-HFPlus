# Architecture preview 08 — portable monitoring and CW ownership

Base: `3c8618c` on `refactor/receiver-pipeline`. Application version: `0.28.0-hfplus-alpha28-arch08`. This is an architecture preview with independent application data; main remains unchanged.

## Changes

`MonitorAudioBranch` in the Android-independent core owns mono/stereo NR, listening squelch, digital-voice fade and existing gain ordering. The Android native notch/leveling processor implements its injected `ListeningPostProcessor` port. Decoder PCM remains read-only and monitoring returns owned stereo samples for the existing recording/output forks. No noise algorithm, UI, recording format or default component is replaced.

CW uses the existing `ComplexIqResamplerFactory`, preserving the existing staged 2:1 decimation and FIR. Configuration changes, reset, final close and partial construction failure now explicitly release the relevant plans. The reference factory remains the default.

New host cases compare exact monitor samples against the pre-extraction sequence across irregular blocks, NR toggles, mono/stereo, gain and mute; verify decoder PCM ownership; exercise monitoring processor reset/close and measurement without audio/display consumers; and test CW factory failure, plan release and chunk equivalence.

## Validation status

All three ABIs build. **132 JVM cases pass** (29 core, 103 application), with zero failures, errors or skipped fixture tests. The seven new portable cases cover monitor sample equivalence/ownership and CW resampler ownership.

Android 13 arm64: **35 of 36 combined cases pass in 167.266 seconds**. Passing cases include 300 actual processing start/stop cycles, native ADS-B lifetime and independent replay, decoder epoch recovery, recording under output congestion, normal file EOF tails, all existing audio recording codecs, FM continuity, listening processors and over one minute of quiet-channel aviation AM with a persistent adjacent blocker. The high-rate CW test fails its unchanged decoding deadline; source generation has not completed and only 190,032 of the expected 328,320 audio frames have arrived. This is not reported as a successful realtime or zero-loss run. Prior-stage comparison and failed repetitions are documented in preview 07.

Static APK checks pass for all 24 libraries: 16 arm64/x86_64 ELF files meet 16 KB alignment and eight 32-bit ELF files meet their 4 KB requirement. `zipalign -c -P 16 4` passes. The artifact privacy scan finds no development Windows paths, private device endpoint, private-key headers or GitHub token patterns. The actual device uses 4 KB pages.

The device runner requests a stop of its explicitly selected application on a whole-run timeout, rather than leaving instrumentation receiving after its adb client exits. Disconnected-device stop failures are reported as unconfirmed, and client cleanup stays bounded. Two host mock fault simulations pass for confirmed and unconfirmed stop paths. The normal execution path is in use for the current RF run; those mocks are not a claimed injected device-timeout pass.

HF+ USB/SSB 30-minute reception is the final active nightly test. Its result and the nightly stop record are added after it finishes. No merge is authorized by this document.

The unresolved high-rate CW realtime gate from preview 07 remains pending until a controlled run passes its original decoding, exact-frame and 15% timing assertions. Oboe, liquid-dsp and SpeexDSP remain isolated experiments; no new backend is enabled. Android 16, actual 16 KB-page runtime, RTL long runs and matched UI/CPU/memory validation remain separate pending gates.

中文：本阶段将监听处理和原生声音处理适配器分开，并补齐 CW 滤波计划的所有权与释放，保持现有算法及界面。构建和主机测试已通过；真机与长测结果按实际完成情况补记，未通过的门槛继续保留。
