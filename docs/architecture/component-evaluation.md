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

## Isolated resampling experiments (not enabled in reception)

`benchmarks/receiver-components` builds standalone Android executables. It is not linked into the application. SpeexDSP uses its floating-point resampler at quality 5, with architecture SIMD, `-O3` and no additional fast-math. liquid-dsp uses a static build, its multistage complex resampler with 80 dB requested attenuation, and NEON on arm64. Examples, benchmarks, tests, logging, timestamps, FFTW and automatic host SIMD detection are disabled for that cross-build.

The clock experiment additionally uses the unmodified control equations from PipeWire 1.4.8's MIT-licensed `spa/utils/dll.h`, pinned to `8b43ed5c910e892d53de5a96aa01e9280a5b6650`. The generated standalone header removes only the unused `spa/utils/defs.h` include; no PipeWire runtime is added. Original header SHA-256: `5dd6a467ff661f0e1ed1b59fa2e0b32db270f8c56b4ebbbdce700e47b0ee26ee`; COPYING: `8909c319a7e27dbb33a15b9035f89ab3b7b2f6a12f8bcddc755206a8db1ada44`.

Two accelerated simulations each cover 1800 seconds of sample time with a known output clock and 20 ms stereo packets. With +100 ppm drift, queue duration stays between 199.5095 and 200.0788 ms; with -100 ppm it stays between 199.9217 and 200.4922 ms. Both end at 200 ms without packet drops or periodic zero insertion. Uncorrected controls would end at 20 and 380 ms. These are simulations, **not** 30-minute Android playback or proof of a production clock estimator. The initial adapter used an unnecessarily large fractional denominator and encountered Speex's checked integer overflow; using the bounded 48 kHz ratio fixes the adapter without changing upstream code.

The liquid-dsp probe converts 2.4 MS/s to 48 kS/s with irregular input chunks. A 9 kHz tone measures +0.001920 dB, and a 59 kHz alias probe measures -151.516 dB at its expected 11 kHz alias. This single-tone result is not a full stopband specification. Five kernel trials measure 26.896, 28.103, 28.118, 27.438 and 28.145 ns/input sample. The existing 127-tap reference response at those two frequencies is -0.545 and -26.373 dB, identifying an alias-rejection case worth evaluating in the complete path. JNI overhead, full-chain performance, decoder/monitor continuity and lifecycle/ABI gates remain pending. Neither resampler is selected by the application from these results alone.

中文：以上为独立实验，不代表已替换应用组件。时钟测试使用已知偏差的模拟播放时钟；实际 Android 输出时钟接入、完整链路比较和所有 ABI 验证仍需继续。

## Candidate ABI build gate

All three pinned candidates cross-build for **arm64-v8a, armeabi-v7a and x86_64** using NDK r28c, CMake 3.22.1 and Android API 26. The standalone resampling tools retain the flags listed above. Oboe is compiled from its unmodified CMake project with `BUILD_SHARED_LIBS=ON`, `ANDROID_STL=c++_shared` and Release configuration; its upstream Release option is `-Ofast`, and its own linker setting requests a 16384-byte maximum page size.

LLVM program-header inspection passes for the nine resulting evaluation ELFs. The six arm64/x86_64 artifacts satisfy 16 KB static alignment; the three 32-bit artifacts are checked against 4 KB and are not labelled 16 KB-ready. These are build/static checks, not nine runtime tests. Oboe stream lifecycle, callback safety, output-clock behavior, full-path timing and fallback behavior remain untested. Candidate libraries and standalone tools are not packaged into the application by this build gate.
