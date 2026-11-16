# Architecture preview 05: controller and resource boundaries

This continues the unmerged architecture branch. It preserves the existing DSP algorithms and Views UI; the added resampler factory still selects the reference implementation. Main remains alpha28.

## Changes

- Scanner/ETM tuning uses the single receiver control executor with source identity and generation checks. Old scanner controls cannot touch a replacement receiver. Captures match the requested frequency and a post-settle software-arrival boundary; that timestamp is not an ADC settling measurement.
- Scan startup awaits rate-change restarts before taking the active source. Control barriers include nested restart work and previously issued conflated tuning commands. A rate restart restores the receiver's mode, dial frequency, BW, SQL, volume, listening, DC, offset and FM de-emphasis snapshot. Internal rate restart cannot override a user-requested CLOSED state.
- Resamplers have a portable creation/reset/release port. Existing filters remain selected. Replacement, mode/BW changes, discontinuities and shutdown explicitly release plans after processing stops; the factory can be independently evaluated without changing demodulation.
- Sound recording retains ownership of an encoder that times out during completion. A replacement cannot overlap it, and encoder joins do not hold the processing-facing lock. IQ flush/close failures become visible errors rather than normal completion; failed opens retain a closeable stream reference until cleanup.
- AndroidX instrumentation dependencies are pinned to core 1.6.1, ext JUnit 1.2.1 and Espresso 3.6.1, replacing the older test-only dependencies. This resolves the reproduced MIUI ActivityScenario cleanup timeout. Production UI/toolchain dependencies are unchanged.

## Verification

Host tests: **14 radio-core + 97 application**, no failures/errors/skips. Added cases cover scanner capture selection, unfinished encoder ownership, processing independence during encoder join, and IQ final-flush failure.

Android 13 arm64:

- **6 controller/lifecycle/recording tests pass in 17.677 s**: rate restart and control restoration, obsolete scanner lease rejection, 300 actual engine cycles waiting for real IQ processing, repeated configuration/output-rate transitions, resampler-plan release, congested playback with complete recording, and short-file EOF. Some assertions share test cases; this is six tests, not seven separate cases.
- FM settings UI test now **passes in 8.938 s**, including ActivityScenario cleanup, using the updated official test framework and existing explicit-launch runner.
- **16 audio regression tests pass in 49.099 s**: AAC/Opus round trips, listening processing, internal CW/audio-content paths, and FM continuity. Synthetic vectors supplement rather than replace independent recordings and real reception.

The earlier `arch04` HF+ WFM reference run completed **1800 seconds after 5 seconds of warm-up**, at 768 kS/s. Application IQ gaps, driver losses, playback drops and playback underruns have **zero increments during that stable window**. The warm-up accumulated **2 playback queue drops**; the final cumulative counter is therefore 2, not zero. Maximum native+managed allocated memory observed was 45,140,904 bytes; process CPU time during the window was 2,037,467 ms. Display was not subscribed, so this is not a UI-performance comparison or proof of no memory trend. These measurements identify the earlier APK; they do not silently certify later playback/filter replacements.

All three ABIs build. Final artifact alignment/privacy checks are recorded with the published APK. Android 16 user acceptance, actual 16 KB runtime, all-mode/all-device RF tests, independent protocol samples, UI frame-time/resource comparisons and remaining component evaluations are still required. DspEngine orchestration extraction is not yet complete. Do not merge this branch from these partial results.

中文：本阶段统一了扫描和调谐控制、补齐重连恢复及录制器释放边界。111 项主机测试通过，相关真机回归通过；旧版本的 30 分钟 WFM 结果单独列明，启动期的 2 个播放包丢弃也保留记录。后续仍需组件接入、独立样本、完整实收和 Android 16 验收。
