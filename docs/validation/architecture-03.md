# Architecture preview 03: independent signal branches

Continues the unmerged architecture branch; application version `alpha28-arch03` uses independent preview storage. The stable application and main branch remain unchanged.

## Changes

- Display processing runs on a bounded, disposable branch. Static channel measurements use a separate, fixed 2048-bin Blackman–Harris analysis path. Changing display FFT, zoom or averaging cannot configure the squelch/scanner meter. The existing passband definitions and mean-bin scanner statistic remain; RF threshold comparisons are still required because display-dependent noise-floor resolution is deliberately removed.
- Existing AM/FM/SSB/CW demodulators and FM stereo processing move into the JVM core. A computation port keeps the existing Android JNI acceleration and the original reference fallback. Algorithms are retained, rather than replaced during structural extraction.
- Digital voice decoding has a bounded worker, configuration epochs and separate decoded-PCM delivery. Protocol results remain application events. APRS map updates belong to the service, not the portable core.
- Sound recording receives the processed listening branch before playback buffering. A slow speaker cannot discard recorded samples. IQ storage uses its own bounded writer, retaining published owned blocks; overflow, write failure, input gaps and changes to capture frequency/rate stop recording with a visible reason. Constant-metadata IQ files no longer silently concatenate retunes.
- Normal file EOF drains the short PCM tail, decoder work and playback clock before source closure. This differs from cancellation, which interrupts and joins work before releasing native resources.
- Diagnostic export uses a scalar whitelist: versions, configuration epoch, rates and loss counters. It excludes RF frequency, decoded content, location, addresses and file paths. Driver losses and application losses use separate cumulative accounting.

## Verification

Host results: **12 radio-core + 94 application tests**, zero failures/errors/skips, including the existing local speech-quality fixture. New tests cover display congestion without measurement interference, calibrated channel measurements, cumulative driver losses, and explicit IQ recorder overflow/write/configuration failures.

Android 13 arm64: the initial four-case regression passed in 14.799 seconds: 300 real DSP/AudioTrack/native start-stop cycles, repeated mode/BW/output-rate changes, forced playback congestion with complete sound recording, and short-file EOF drain. Input is synthetic and silent; this does not establish physical USB or RF performance. The final wider audio/recording/squelch regression is recorded separately after execution.

Final non-UI regression: **13 tests passed in 92.901 seconds**, including the lifecycle and recording cases above, AAC/Opus stereo round trips, 44.1 kHz-to-Opus recording, listening processor bypass/leveling/notch/block continuity, and more than one minute of quiet aviation AM with a persistent strong adjacent carrier. The FM settings UI test reached ActivityScenario cleanup but timed out while launching its helper EmptyActivity under MIUI. It is not counted as a passing UI check; it remains a separate acceptance item.

## Remaining gates

Component comparison, all-ABI/static 16 KB checks on the final APK, independent protocol samples, output clock drift, USB faults, RF long runs, UI frame-time/CPU/memory comparison and user-run Android 16 acceptance remain required. `DspEngine` still owns application orchestration and needs further extraction; this milestone is not completion of the full architecture plan. No merge is authorised by these limited results.

中文：显示、测量、解码、录制与播放已开始按职责隔离，保留现有算法与界面。已通过的模拟输入测试不能替代实收、长时间运行或 Android 16 验收；这些门槛继续保留。
