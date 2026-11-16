# Architecture preview 19 — AM adjacent-carrier rejection

Based on [preview 18](architecture-18.md), public alpha28, on the independent architecture branch. Main is unchanged and PR #1 remains draft.

## Observed problem and change

A private four-second HF+ capture contains an adjacent narrow carrier about 9.997 kHz below the selected carrier. The demodulated audio contains a matching narrow line. The existing automatic notch does not substantially reduce that line in this sample. This identifies a plausible contributor to the reported faint whistle, not proof that every audible interference has the same cause.

The existing source-rate FIR has a broad transition at high input rates. Preview 19 preserves it and adds a distinct channel-rate RF FIR before envelope/SAM detection, using the existing Blackman–Harris coefficient design and unchanged bundled VOLK-derived dot-product kernel. The 96 taps align with the ARM kernel's 16-float groups. `ComplexChannelFilter` owns the serial filtering contract; `NativeChannelFilter` owns native overlap/work buffers and operates in place on channel-private IQ. Native state resets on filter configuration and channel discontinuity and is released at stop. Source IQ, raw recording, independent SQL/scan measurements and their calibration remain unchanged.

This stage only runs for AM with the native AM node and a narrower-than-IF channel. Ordinary USB/LSB/CW, ACARS, ADS-B and other data modes keep their routes. Existing resampling, SAM algorithms, AGC defaults/Off/Slow/Fast, audio filtering, listening effects and UI remain. Fixed extra group delay at 48 kHz is about 0.99 ms, without dropped/inserted frames.

## Focused evidence

- All 152 existing JVM checks pass (43 core, 109 application). No unrelated long hardware matrix is repeated.
- On unchanged preview 18, an independent 1 kHz AM voice + adjacent 10 kHz complex carrier test fails its unchanged rejection criterion: voice amplitude 0.02966, unwanted beat 0.001878, approximately −24 dB relative to voice. On preview 19 the same test passes for envelope and all three SAM selections: 1 kHz gain remains within 0.3 dB and the beat is below −60 dB relative to voice. This is a specific stimulus, not a whole-band stopband guarantee.
- Native FIR output matches the existing reference FIR within 1e−6 over irregular blocks and reset. Separate 1/3 kHz complex tones remain within 0.3 dB. Existing centered-carrier, SAM acquisition/sideband/fallback/gain-hold and manual audio-width sample checks pass. The actual software Morse decoder/noise-bypass path passes.
- Identical captured IQ replay before/after shows the narrow audio peak reduced by 44.23 dB (envelope), 44.34 dB (SAM DSB), 41.13 dB (SAM USB) and 44.56 dB (SAM LSB). These are windowed peak-bin changes near the identified tone, not overall noise-reduction or intelligibility scores. Original capture has no float components at full scale; no broader RF-front-end clipping claim is made.
- HF+ at 768 kS/s passes the existing 24-second four-detector observation, with zero application/driver IQ loss, PCM drops or playback underruns in its diagnostics. It is a continuity/switching smoke check, not a long-term or fading-quality verdict. RTL is not newly tested in this checkpoint.
- The existing long-press frequency dialog and successful `11.620` MHz submission pass on preview 18. The user confirms that the reported failure was input-unit confusion. No frequency-entry behavior changes. A later unchanged UI check was blocked by lockscreen (`NoActivityResumedException`), not counted as a pass; the earlier unchanged-path result is reused.

## Selected-channel CPU comparison

Same phone, same four-second captured IQ, same block sizes, one warm-up then five thread-CPU-time trials per detector, excluding file IO/UI/source transport and without parallel receiver tests:

| Detector | Preview 18 median | Preview 19 median | Change |
| --- | ---: | ---: | ---: |
| Envelope | 104.509 ms | 112.197 ms | +7.357% |
| SAM DSB | 118.156 ms | 125.664 ms | +6.354% |

This is selected-channel computation, not whole-app CPU, foreground rendering or battery power. It clears the agreed 10% correctness-fix processing bound in this observed path and remains well within realtime. The initial 127-tap candidate had +10.596% / +9.685%; it was not selected. The final vector-aligned 96-tap design retains the rejection/voice criteria. No new backend or broad performance benefit is claimed. ADS-B does not execute the new stage; the user's reported upstream power comparison is separate feedback.

## Sources and delivery limits

Fixed project source/file hashes and GPL-3.0 reuse details are in [the reuse record](../third-party-reuse.md) and `cpp/vendor/sources.json`. Existing kernel/design files remain unchanged. No new general stream engine, DSP library or audio backend is introduced. Manual live capture requires an explicit test argument; routine tests do not silently record broadcasts.

All three supported ABIs build. Final source/APK privacy, ELF/ZIP alignment and checksum checks accompany the release. Actual 16 KB runtime, Android 16 listening acceptance and confirmation that this removes the user's perceived whistle remain unverified. Main is not merged.

Only the matching APK, checksum, static alignment report and sanitized validation are release assets. Private IQ/audio, spectra, timing logs, network/device details, local paths and helper APKs are excluded. Generated sample files are removed from the phone after diagnosis; user settings and recordings remain.

中文：已定位一条来自实收 IQ 的邻近窄载波，其对应约 10 kHz 音频尖峰会漏过旧 AM 频道滤波。新增解调前的频道 FIR，复用既有系数设计与 VOLK 内积；同一段样本的对应尖峰降低约 41–45 dB，通带测试、四种检波及 HF+ 短接收通过。新增频道计算耗时中位数约增 6.4–7.4%，不等于整机功耗增加。AGC 和界面保持，输入频率仍以 MHz 为单位；模块注册和接收体验改进保留为下一阶段方向。实际听感仍需用户确认，架构分支未合入主干。
