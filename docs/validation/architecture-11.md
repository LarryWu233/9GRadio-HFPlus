# Architecture preview 11 — bounded resource diagnosis

Base production source: `5aa4c92` / preview 10. Version: `0.28.0-hfplus-alpha28-arch11`. Main remains unchanged; this is not final acceptance.

## Concrete problem and limited change

A headless RTL-SDR Blog V4 observation at 2.4 MS/s, WFM and 180 kHz BW exposes a sustained-input problem that the earlier shorter checks did not expose. Preview 10 reports application IQ gaps and playback underruns. The published alpha28 APK also fails the observation. Those failures remain recorded; passing short or later runs do not erase them.

The first observer scanned process memory every second and did not retain a CPU wake lock. Refining the observer moves memory inspection outside the timing/continuity window, retains diagnostics on assertion failure and uses a bounded Android partial wake lock released in `finally`. Both installed apps already have the permission. This does not change an app APK, saved preference, system setting or production power policy. A 15-second no-clock-polling control and a 30-second clock-polling control pass, but these do not reproduce or explain the longer failure. A wake-locked 60-second preview-10 observation still fails: neither memory scanning, clock polling nor device sleep alone is established as the cause.

A ten-second NDK simpleperf sample of only the debuggable preview process, with adb root explicitly disabled, identifies reference resampling at 36.58% self samples, FFT input accumulation at 11.97%, and the IQ blanker adapter at 9.17%. These are sampling shares, not end-to-end timing or a candidate-adoption result. Profiling tools, raw traces and device logs are not release assets.

Two bounded optimizations retain the existing algorithms:

- In the FM NB adapter, a fully dry disabled block keeps SDR++'s amplitude estimator warm but does not use its discarded gain, update a constant zero mix, or rewrite unchanged IQ. Fade-in/out and the active path remain. The compiler can remove unused gain calculations. Upstream SDR++ and WDSP kernels are unchanged.
- FFT ring bookkeeping uses conditional wrap/saturation instead of a remainder and `coerceAtMost` for every accepted sample. Input order, averaging, window, transform, scale and analysis cadence are unchanged.

No liquid-dsp, SpeexDSP, Oboe or alternate FFT backend is enabled. No UI, map, setting, recording format or new decoder is introduced.

## Evidence and limits

All three ABIs build. The existing JVM suites pass: 37 core / 103 app, zero failures, errors or skips. APK alignment, privacy checks and inspection of 24 native ELFs pass (16 64-bit libraries at 16 KB; eight 32-bit libraries checked at 4 KB only). This is static evidence, not a 16 KB runtime. The six existing IQ NB Android cases pass in 0.566 seconds. The same FM disabled-warm-up / alternating fade fixture yields the identical float-byte SHA-256 on installed previews 10 and 11:

```text
ebbe8d889cd7d54a509cc2ee943eab9e9e5f371c399fe080ea6a5706d8b299c6
```

The final resource observer uses the real RTL input, AudioTrack, silent software volume, eight seconds of warm-up and a 60-second stable window with CPU wake held. Both apps use arm64 on the same Android 13 backup phone. Stable alpha28 is byte-identical to the published baseline; its APK and saved preference files are not replaced. Memory is one end-window snapshot, not a peak/growth measurement.

| Observation | Stable-window PCM drops | Stable-window underruns | Cumulative application IQ missing samples | Result |
| --- | ---: | ---: | ---: | --- |
| Published alpha28, bounded CPU wake | 0 | 55 | Not exposed by the old interface | Fail |
| Preview 10, bounded CPU wake | 0 | 3 | 4,022,272 | Fail |
| Preview 11, bounded CPU wake | 0 | 0 | 0 | Pass in this reproduced scenario |

Preview 11 also reports zero driver-reported missing samples, zero stale blocks, no receiver failure and an unchanged processing epoch during reception. Its cumulative two playback packet drops occur in warm-up. The final total is two, not zero. The full case lasts 69.184 seconds. This is one focused confirmation, not an all-mode stability claim.

The valid preview-11 observation estimates queued audio at 132.167–172.812 ms, median 161.458 ms, after accounting for dropped warm-up packets. It excludes unknown device/output delay. The platform timestamp fit differs from nominal 48 kHz by -0.808 ppm; this is a presentation estimate, **not** an ADC calibration, independent crystal-accuracy measurement, ASRC validation or injected ±100 ppm drift test. Android documents the [best-effort timestamp and unknown-delay limitations](https://developer.android.com/reference/android/media/AudioTimestamp). No output-clock adaptation is enabled from this result.

Receiver-process CPU is 70,584 / 72,365 / 69,869 ms for the three respective windows. End-window PSS is 59,378 / 94,672 / 103,122 KiB; allocated Java+native heap is 30,277,032 / 26,393,904 / 33,090,776 bytes. These snapshots are not proof of a memory-growth regression, but they **do not establish the 10% resource gate**. The two references fail continuity and therefore do not perform equivalent useful work. The summary tool suppresses percentage comparisons instead of announcing a speedup. The matched follow-up below addresses one representative comparison; these failed observations remain unqualified.

### Qualified representative resource pair

Read-only inspection then finds a compilation-condition difference: alpha28 has ART `verify` cache while the freshly installed preview runs from its APK. The preview alone is brought to `verify` using `cmd package compile -m verify -f`; neither APK nor saved preference data changes. A lighter common input (RTL 1.024 MS/s, the same WFM/BW/audio settings) permits both versions to run continuously. Each observation has eight seconds of warm-up and a 15-second timed window, with CPU wake held and the same observer. Memory classification is captured once outside timing.

| Metric | Published alpha28 | Preview 11 | Preview change |
| --- | ---: | ---: | ---: |
| Receiver-process CPU, normalized to wall time | 100.460% of one core | 103.541% | +3.067% |
| End-window PSS | 68,978 KiB | 69,689 KiB | +1.031% |
| Allocated Java+native heap snapshot | 42,614,224 B | 42,622,040 B | Snapshot only |

Both pass the original audio-frame/zero-underrun/zero-PCM-loss assertions. Preview diagnostics also report zero application/driver IQ missing samples and stale blocks. This pair satisfies the 10% resource limit **for this representative headless case**, not a repeated all-mode/full-UI benchmark or a memory-growth test. It does not establish the exact cause of all earlier PSS differences: compilation state, workload and garbage-collection phase must not be conflated. The earlier failed 2.4 MS/s observations stay unqualified for percentage comparison.

`ReceiverResourceObservationTest` has one bounded observation entry, not a mode/device matrix. `scripts/summarize_receiver_observation.py` exports whitelisted aggregates, rejects failed observations by default and never qualifies them for a performance comparison. It supports private UTF-8/UTF-16 stdout without publishing raw sample times, endpoints, paths, locations or received content.

Android 16 manual acceptance, actual 16 KB runtime, full-UI resource evidence and the remaining output-clock gate are pending. Main and PR merge remain blocked by incomplete acceptance, not labelled complete because these cases pass.

中文：本阶段针对实际复现的 RTL 高采样率积压定位，不扩展无关长测。NB 关闭态与 FFT 环缓冲只减少等价的多余计算，未换算法；FM 预热／启停输出指纹相同，原有 NB 检查通过。预览十一在该一分钟场景中无新增 IQ 丢样、播放丢弃或欠载，启动期两次播放丢弃另计。失败基线不能用于计算提速。对齐编译缓存并选择两版都连续的代表性负载后，CPU／PSS 变化为 +3.067%／+1.031%，该场景满足 10% 门槛；不代表全部界面、全部模式或整体验收通过。
