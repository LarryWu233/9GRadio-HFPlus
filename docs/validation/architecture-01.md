# Architecture preview 01: input contracts

Baseline: public `main@9e6aa4b` / alpha28. Main is unchanged; development is on `refactor/receiver-pipeline` in an independent worktree. This is an intermediate milestone, not completion of the refactor or a merge approval.

## Baseline and changes

- The unchanged baseline's 91 host tests pass in the new worktree (Gradle reused matching cached test results).
- Add a JVM-only `radio-core` and move IQ data definitions out of Android/DSP dependencies. Stamp every production input before queueing: stream/capture epoch, sequence, sample position, rate/centre, software arrival and native driver loss.
- Replace input SharedFlow overflow with independent per-subscriber coroutine Channels, bounded by about 200 ms of signal for live input. File delivery waits for consumers and completes at EOF. The stream contract tests cover tail/sample accounting, independent consumers, overflow, lossless EOF and 300 subscribe/cancel cycles; this is not a claim of 300 hardware lifecycle tests.
- RTL publishes an owned copy of only the valid transfer bytes. The source adapter no longer copies a pooled array after it may have been reused. DSP loss diagnostics use observed sequence gaps for UInt8 and Float32 rather than the invalid `tryEmit(false)` assumption.
- Add independent `.arch` packaging and variant-aware native decoder attachment/test launching. Keep namespace, official-fork application ID, UI, storage formats and existing algorithms.

## Feature regression inventory

Maintain all baseline demodulation modes, raw IQ and audio recording/playback; HF+, RTL Blog V4, rtl_tcp and file inputs; aviation/ACARS, ADS-B/readsb, APRS including dual reception, RDS, pager and digital voice integration. Maintain CW/CWR, SSTV, audio satellite image inputs from software/microphone/WAV, EiBi programme lookup/tuning, maps/radar/distance data, RDF, ETM/scanning, presets/bookmarks, mode frequency memory and device profiles. Preserve spectrum/BW interaction, themes/colour controls, panel layout, recording codecs/gating/timer and audio player controls. Presence is inventoried; individual runtime acceptance remains to be recorded.

## Remaining gates

Seven JVM core tests and all 91 application host tests passed (zero failures/skips with the existing local speech fixture supplied via `-PnrFixtureDir`). The independent application and instrumentation APKs build. The application ID is `.hfplus.arch`; all eight baseline native binaries, including readsb and the CW/APT worker, are present and pass ELF 16 KB alignment inspection. APK zip alignment was also checked. This does not establish a 16 KB runtime.

Device installation was attempted on the connected Android 13 backup phone; MIUI returned `INSTALL_FAILED_USER_RESTRICTED` for the new preview and its own instrumentation package. Runtime verification is pending installation; the main HF+ app was not overwritten. Raw fixtures and device details are not published.

Configuration-boundary handling, joined lifecycle, branch extraction, independent recording, component A/B assessment and performance/long-run hardware validation are not complete at this milestone. Android 16 user acceptance and actual 16 KB-page runtime validation remain pending. No stability or performance improvement is claimed solely from a successful build or unit tests.

## 中文摘要

首阶段补齐输入数据契约及可核对的丢样统计，并增加独立预览包。保留主干和原应用数据。单元测试中的 300 次订阅取消不是 300 次真机插拔；全计划尚未完成，禁止把此阶段直接作为最终稳定版合入。
