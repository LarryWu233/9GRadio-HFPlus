# Architecture preview 09 — portable rate policy and dual APRS channels

Base: `2221f38` on `refactor/receiver-pipeline`. Version: `0.28.0-hfplus-alpha28-arch09`. Work resumes after the requested nightly checkpoint; main remains unmerged and the stable application's data is untouched.

## Changes

- Existing rate-selection functions move to `radio-core` as `ChannelRatePolicy`. Public `DspEngine` compatibility methods remain. Broadcast/wide-FM choices, CW IF rate, APRS's fixed decoder rate and output-rate policies are preserved.
- The existing two APRS channels move from `DspEngine` into portable `DualAprsChannels`. The NCO, filters, discriminator and resamplers retain their algorithms. Plans have explicit ownership; construction failure closes partial new resources, and in-flight processing finishes before its resources can be replaced or released.
- Dual APRS publishes owned mono 48 kHz `AudioBlock` pairs, with generation and frame position. A nonblocking generation barrier suppresses stale queued PCM on configuration changes. The old float-pair facade remains available for compatibility. The application's APRS collector uses the typed port, detects gaps and resets input synchronization on its own worker. Frequency controls no longer reset an active decoder on the main thread.
- Fix the old dual-channel discriminator's empty-output boundary: a very short IQ block can decimate to no samples, and the old implementation dereferences sample -1. The adapter now preserves state and returns an empty block rather than failing the receiver.
- The live typed publication never waits for UI consumption; failed publication is counted in scalar-only diagnostics. Maps, UI appearance, existing recording formats, drivers and default DSP backends are unchanged.

## Focused verification

All three ABIs build. The existing JVM suites plus six focused core regressions pass: **35 core / 103 application**, no failures, errors or skipped fixture tests. New coverage is limited to rate-policy preservation and three dual-channel risks: exact output equivalence with the frozen previous kernels; the actual empty-tail failure/configuration boundary; and in-flight invalidation/partial construction ownership. No broad new protocol suite is added.

Android 13 arm64: the **eight affected IQ/audio and resampler-lifetime regressions pass in 46.434 s**. The high-rate CW case in that group retains its decoding, spectral, exact-frame-count and original 15% realtime assertions: 6841 ms wall time for 6840 ms of signal, with exactly 328,320 frames.

A preceding isolated CW check at battery temperature about 32.9 °C under USB charging passes in 6847 ms. No filter algorithm was replaced. These passing runs do not erase preview 07/08's failed measurements at different conditions or prove temperature was their sole cause.

The test-only stage diagnostic prepares IQ before timing and separates thread CPU from wall time. For 500 ms of 1.92 MS/s signal, three reference-kernel trials use 314 / 314 / 313 ms CPU and 316 / 318 / 314 ms wall time. The first 2:1 stage uses about 152 ms CPU and the second about 76 ms. This identifies CPU cost and available headroom under current conditions; it is not a full receiver performance claim, candidate-adoption result or thermal-stability qualification. The diagnostic is in the auxiliary test APK, not the application UI or published assets.

As requested on resumption, verification is change-driven. Previously valid long tests are not repeated merely because unrelated code moves. The versioned HF+ SSB 30-minute result remains in preview 08's record; this stage does not claim a new long-run result. No candidate resampler/audio backend is enabled without its agreed adoption evidence. See [remaining acceptance](remaining-acceptance.md) for the revised scope and outstanding work.

The architecture remains a fixed receiver pipeline with isolated branches and ports, rather than automatic plugin discovery. Module registration, result routing and remaining protocol orchestration still need consolidation. Android 16 manual acceptance, actual 16 KB runtime and affected final hardware/performance checks remain pending; this is not a merge-ready release.

中文：本阶段把采样率策略及双路 APRS 通道处理移入核心模块，保留原算法，修复空尾块越界和主线程重置解码状态的问题，补齐带代次的音频接口与资源所有权。仅验证受影响的边界，不机械重跑长测；当前条件下完整 CW 测试已通过，但不宣称昨晚的负载问题已彻底解决。模块注册与协议分发仍在收尾，主干未合并。
