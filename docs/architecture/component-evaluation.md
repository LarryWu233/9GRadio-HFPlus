# Pinned component evaluation

Candidates are evaluated individually against the alpha28 implementations. Presence in this document does not mean adoption. No candidate is enabled in normal reception until its acceptance record says so.

| Component | Exact source | Licence | Scope / initial status |
| --- | --- | --- | --- |
| Oboe 1.9.3 | `b15f5e39c01a7ada306d959e5129620b145fb8b4` | Apache-2.0 | Android playback; pending comparison, AudioTrack retained |
| liquid-dsp 1.8.3 | `10041f70cebbe3b97887e75bb41e48b73dda1b23` | MIT | IQ filtering/resampling; pending comparison, existing filters retained |
| SpeexDSP 1.2.1 | `1b28a0f61bc31162979e1f26f3981fc3637095c8` | BSD-3-Clause | Monitor PCM resampling/clock matching; pending comparison |
| PFFFT | baseline `9e6aa4b`, `app/src/main/cpp/mbelib-neo/src/external/pffft` | NCAR/FFTPACK licence in source headers | FFT only; evaluating behind an injected transform port |

Upstream: [Oboe](https://github.com/google/oboe/tree/1.9.3), [liquid-dsp](https://github.com/jgaeddert/liquid-dsp/releases/tag/v1.8.3), [SpeexDSP](https://github.com/xiph/speexdsp/tree/SpeexDSP-1.2.1). Versions remain fixed during this refactor.

Downloaded source archive SHA-256 (GitHub commit archives):

```text
Oboe       bc605cc0cee75126ab22443a4b4eb3d2b69d0bdc3caf07450659a11798fcb4a1
liquid-dsp 5dc63a4fd18f1f0e71fcaf9a0ce3c6de9dd0ea35707948e3eefa689398820a3e
SpeexDSP   59a53188f6d6ca2b48752f533523986a0604d2526eb50789b8fb09aa6b12645f
```

PFFFT source SHA-256, canonical LF bytes (no source changes):

```text
pffft.c   46715c5beefc3c0407ad1d2dac1a1ec9af18a97183edd69d1599af190ebace9b
pffft.h   dccf75f8082889eaaa51f4e7f4625a6629e35f6b33e8ec883966c1691001240b
fftpack.c 7e49c4c11d08030c9218486ca1a37d3b0eea673b55f46bf89f543753fedc8f6d
```

The FFT adapter uses ordered, unnormalised complex forward transforms. Windowing, coherent-gain scaling, DC removal, averaging, peak hold and dBFS conversion remain in the existing FftEngine. Plans and aligned scratch are allocated once per size, serialized per owner, and explicitly freed. NDK r28c, C99, `-O3`; no extra fast-math option is added to the PFFFT source target. SIMD follows the selected Android ABI and the vendored fallback. The bridge adds no transform algorithm.

Adoption requires known-tone amplitude error <=0.3 dB, arbitrary chunking and lifecycle/ABI/16 KB checks, followed by five paired full-path timing measurements. Median improvement must be >=20% without continuity, quality or memory regression; otherwise the current backend remains. Native FFTs are not enabled merely because a microbenchmark looks faster.

中文：以上是固定候选清单，不等于全部已集成。先保留对照实现，再分别完成正确性、资源释放和性能比较；未达到门槛的组件不替换。

## PFFFT comparison on Android 13 arm64

Three instrumentation cases pass: calibrated complex-tone spectra from 256 to 65536 points at three amplitudes and arbitrary chunk boundaries; 100 repeated plan resize/close cycles with use-after-close rejection; five alternating paired complete-spectrum timings (600 frames/trial, 200 warm-up frames). JNI copies and the existing spectrum postprocessing are included.

| FFT size | Reference median µs/frame | PFFFT median µs/frame | Improvement |
| --- | ---: | ---: | ---: |
| 256 | 16.159 | 14.339 | 11.26% |
| 2048 | 138.272 | 111.158 | 19.61% |
| 8192 | 592.875 | 448.447 | 24.36% |

Decision: **retain the existing default backend**. The 256/2048 cases do not reach 20%; 19.61% is not rounded up to a pass. The 8192 case clears only the timing threshold; full application memory/latency and all-ABI checks remain prerequisites for enabling it. The native adapter is available to instrumentation, with no UI comparison toggle or changed reception default. Additional candidates remain pending, not implicitly rejected.
