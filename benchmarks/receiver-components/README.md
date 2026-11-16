# Isolated receiver component evaluation

These executables are test tools, not application components or released APK assets. They do not access USB, audio devices, network data, settings or received content. Do not run CPU benchmarks alongside a receiver stability test on the same phone.

Current experiment: stereo SpeexDSP 1.2.1 resampling with the mature PipeWire DLL controller. It simulates a known ±100 ppm output clock over 30 minutes of **sample time**, accelerated rather than wall-clock. This is not proof of Android clock estimation, Bluetooth routing, production latency or a complete drift fix. Nothing is enabled in app playback by this experiment.

Pinned sources:

- SpeexDSP commit `1b28a0f61bc31162979e1f26f3981fc3637095c8`, BSD-3-Clause. [Upstream](https://github.com/xiph/speexdsp/tree/SpeexDSP-1.2.1). Use `libspeexdsp/resample.c`, the upstream headers and the standalone `OUTSIDE_SPEEX`, `FLOATING_POINT`, `RANDOM_PREFIX=ninegradio_eval` build. Quality 5; NEON on Android ARM, SSE on x86_64; `-O3`, no added fast-math.
- PipeWire commit `8b43ed5c910e892d53de5a96aa01e9280a5b6650` (1.4.8), `spa/include/spa/utils/dll.h`, copyright 2019 Wim Taymans, MIT. [Upstream](https://github.com/PipeWire/pipewire/blob/1.4.8/spa/include/spa/utils/dll.h). This is only the header controller, not the PipeWire runtime. Its unused `spa/utils/defs.h` include is omitted in a generated copy; equations and attribution remain unchanged. Original header SHA-256: `5dd6a467ff661f0e1ed1b59fa2e0b32db270f8c56b4ebbbdce700e47b0ee26ee`. Upstream COPYING SHA-256: `8909c319a7e27dbb33a15b9035f89ab3b7b2f6a12f8bcddc755206a8db1ada44`.

Both upstream licence files must accompany any redistributed executable. Evaluation binaries remain local; no component is adopted solely from this simulation. The Speex source archive checksum is in [the component record](../../docs/architecture/component-evaluation.md).

Optional `LIQUID_SOURCE_DIR` adds `liquid_iq_evaluation`, using liquid-dsp 1.8.3 at the commit in the component record, MIT. It tests 2.4 MS/s → 48 kS/s complex resampling, known passband and alias tones, irregular block sizes and five timing trials. The existing FIR reference has only about 26.37 dB rejection at 59 kHz for this ratio (before aliasing to 11 kHz); the candidate must demonstrate >=60 dB rejection and <=0.3 dB passband loss. This is a laboratory rejection criterion, not a claim that all existing reception is broken. JNI/full-path performance, decoder vectors and resource tests remain necessary before adoption. Upstream examples/tests/benchmarks/shared libraries, optional FFTW, terminal logging/colour and embedded host/timestamp build information are disabled. ARM NEON is selected explicitly for the Android ARM ABI.

Configure with the existing CMake/NDK, an Android ABI and API 26 or higher. Supply `SPEEX_SOURCE_DIR` and `PIPEWIRE_DLL_DIR` pointing to the verified sources above; no machine paths are committed. The normal application build has no dependency on this directory.

```sh
cmake -S benchmarks/receiver-components -B build/component-evaluation \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DSPEEX_SOURCE_DIR="$SPEEX_SOURCE" -DPIPEWIRE_DLL_DIR="$PIPEWIRE_DLL" \
  -DCMAKE_BUILD_TYPE=Release -G Ninja
cmake --build build/component-evaluation
```

中文：此处是独立评估工具，不进入主应用链路，也不替代真实 30 分钟听音。采用现成重采样与时钟控制实现；需要另行验证 Android 播放时钟、实际延迟和资源开销后，才考虑接入监听输出。录制和解码时间线不应跟随扬声器时钟改变。

## Initial Android 13 arm64 results

The independent executables compile with NDK r28c/CMake 3.22.1 and run successfully after reception testing is stopped. They are not part of any published APK.

| Known clock offset | Simulated sample time | Observed buffer range | End buffer | Uncorrected end buffer |
| --- | ---: | ---: | ---: | ---: |
| +100 ppm | 1800 s | 199.5095–200.0788 ms | 200 ms | 20 ms |
| −100 ppm | 1800 s | 199.9217–200.4922 ms | 200 ms | 380 ms |

Stereo RMS stays 0.0559015 versus the analytical 0.0559017. Output frame counts are exactly 86,408,640 / 86,391,360 respectively. Each accelerated simulation takes about 23.2 seconds on this test device. The first adapter trial used excessively large fractional-rate denominators and triggered Speex's 32-bit phase-conversion overflow protection; the adapter now uses bounded 48000-based ratios. No upstream algorithm was changed to make the test pass.

For liquid-dsp at 2.4 MS/s → 48 kS/s, the 9 kHz tone measures +0.00192 dB with both fixed and irregular block sizes. The particular 59 kHz alias probe is below −150 dB relative to input, clearing the ≥60 dB criterion. This single-tone result is **not** a claim of 150 dB rejection over the entire stopband. Five streaming-kernel timings are 26.896, 28.103, 28.118, 27.438 and 28.145 ns/input sample. JNI conversion/copies, complete channel CPU/memory, protocol samples, actual device clocks and output routing remain untested for these candidates. **No resampler or clock backend has been adopted from these results.**
