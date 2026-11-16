# Architecture preview 04: FFT comparison and hardware harness

This is the first component comparison, not completion of milestone 04. Oboe, liquid-dsp and SpeexDSP evaluation remain pending. Main and the default FFT algorithm are unchanged.

- The portable FFT has an optional ordered-transform port. The Android candidate uses the exact baseline PFFFT sources, with explicit plan ownership, aligned reusable buffers and packaged licence notices. No display window/scaling/averaging algorithm is replaced.
- Three Android 13 arm64 tests pass: calibrated amplitude/sideband agreement at 256–65536 points and multiple levels, arbitrary input chunks, repeated resize/close/use-after-close rejection, and five paired complete-spectrum timing trials. See [component evaluation](../architecture/component-evaluation.md) for exact source hashes, flags and timings.
- Default backend retained: 256/2048-point cases improve 11.26%/19.61%, below the required 20%. The 8192-point case improves 24.36% but does not by itself establish full-application benefit. No user-visible comparison switch is added.
- All three supported ABIs build. All 24 packaged native ELF files are inspected: 16 arm64/x86_64 files pass 16 KB alignment, and 8 armeabi-v7a files pass their separate 4 KB check. APK zipalign `-c -P 16 4` passes. This follows the Android guidance for 64-bit libraries; it does not claim 16 KB runtime testing or 16 KB alignment of the 32-bit prebuilts. [Android documentation](https://developer.android.com/guide/practices/page-sizes)
- A direct USB stability harness avoids MIUI ActivityScenario helper startup, preserves saved preferences and exports scalar diagnostics only. A 30-second HF+ WFM test at 768 kS/s passes after five seconds of warm-up: zero application IQ gaps, driver-reported losses, playback drops and underruns. A 30-minute run is started separately and must not be counted as passed until it finishes. This harness does not replace UI or Android 16 acceptance.

The previous 106 host tests remain passing with the optional FFT port. No new resampler, output backend or clock correction is enabled by this stage. Further channel/orchestration extraction, all protocol vectors, long-run physical USB tests, baseline resource/frame comparisons and Android 16 acceptance are still required before merge.

中文：本阶段只完成 FFT 候选的首轮比较，未达到常用尺寸的采用门槛，因此默认实现保留。HF+ 已通过短时 WFM 连续接收检查，30 分钟检查另行记录。主干与正式版不受影响。
