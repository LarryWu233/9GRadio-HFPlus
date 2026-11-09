# Architecture preview 10 — channel processor and decoder ports

Base: `1f6a002` on `refactor/receiver-pipeline`. Version: `0.28.0-hfplus-alpha28-arch10`. This preview preserves the existing Views UI, maps, algorithms, recording formats and stable application's data. Main remains unmerged.

## Changes

`ChannelProcessor` in `radio-core` owns the existing channel filter/decimation, demodulator and decoder/monitor-rate resamplers. `ChannelConfiguration` is an immutable request. Its output explicitly describes channel IQ, IF rate, demodulated audio, mono decoder PCM, monitor PCM and the FM multiplex. These are serial scratch views; asynchronous publication still uses owned blocks. The application facade retains source controls, capability calculation, native/platform adapters and branch assembly. No DSP kernel or backend is replaced.

Raw-IQ decoders now implement `IqDecoderModule<Result>`: declared rate/format requirements, a read-only `IqReadInput`, result flow, running/error flow and joined stop/pending-release behavior. `ReadsbReceiver` is the first actual adapter. Its existing source-specific start method remains compatible. Unsupported input is rejected before subscription and also stops an incompatible active session; unexpected per-block rate/format changes are reported. A decoder's read-only input does not expose controls for shared production.

Fix a concrete shutdown race: an IQ block already being processed could publish its audio using the newly closed session's epoch. Processing now captures its admitted epoch and uses it for decoder/voice/playback publication, suppressing output after a close or configuration change. This preserves the downstream admission barriers rather than disguising old PCM as new data.

Admission captures that epoch **after** discontinuity recovery, so the first valid block following a gap is processed in the new epoch. The previous U8 empty-content guard sampled only even byte offsets and could reject valid I=0 / Q!=0 input. It now confirms the whole block, retaining the existing all-zero rejection policy without inferring a hardware fault from content alone. Rejected content counts as application missing samples, resets state and stops affected recordings through the existing gap path; it is not silently spliced or counted as driver loss. Raw-IQ decoder input remains unmodified.

See [decoder extension boundaries](../architecture/decoder-extension.md) for current interfaces and limitations. This is a fixed branched pipeline, not runtime plugin discovery. Explicit module selection and application result routing are still required; a small future registration table should replace remaining conditionals when warranted, not become a universal graph engine.

## Focused validation

140 JVM cases pass (37 core / 103 application), with no failures, errors or skipped fixtures. Two new core cases focus on the new computation boundary: exact channel/PCM equivalence with the old assembly across modes and irregular blocks, and resource replacement/failure/close ownership. Existing coverage is retained rather than expanded into unrelated protocol scenarios.

Android 13 arm64: the **ten affected channel/audio/FM/lifetime cases pass in 46.930 s**. The full high-rate CW case retains decoding, spectral, exact-frame-count and realtime assertions (6839 ms for 6840 ms of input). The raw module port then passes **three existing replay/gap cases in 5.326 s**, including both the generic API and the compatibility method, declared-input rejection before/during a session, result/state flows, independent RF data, normal EOF and resource release. Fixtures are explicitly staged and hash-verified; none is published.

The new shutdown regression is run once on each version with identical instrumentation. Preview 09 reproduces the specific failure (`expected 0 late decoder blocks, actual 1`); preview 10 passes in 0.117 s. This is a defect-specific check, not a new long-run matrix.

After the input-boundary correction, two focused Android checks pass together in 0.386 s: the shutdown regression and one sequence containing valid I=0/Q!=0 U8 input, a missing producer block, a rejected all-zero block and recovery. The valid recovery blocks reach the decoder immediately with a new epoch and reset audio position; 12,288 processed / 8,192 missing samples / zero driver loss are accounted for exactly. The final three-ABI build and the 140 JVM cases also pass. No new DSP algorithm is introduced.

### Bounded CPU comparison for the known CW issue

Five alternating paired trials use the identical helper and original CW assertions on previews 09/10. The observed battery temperature is 29.9–30.4 °C under USB charging. CPU measures the receiver application process, including the fixture generator and AudioTrack work; it excludes the separate native CW child. Wall time includes deliberate realtime pacing and must not be presented as processing speed.

| Pair | Preview 09 CPU ms | Preview 10 CPU ms | Preview 09 wall ms | Preview 10 wall ms |
| --- | ---: | ---: | ---: | ---: |
| 1 | 10923 | 10730 | 6841 | 6843 |
| 2 | 10917 | 10759 | 6873 | 6840 |
| 3 | 10721 | 10803 | 6839 | 6839 |
| 4 | 10868 | 10891 | 6883 | 6864 |
| 5 | 11001 | 10812 | 6839 | 6839 |

All ten trials pass and produce exactly 328,320 audio frames. CPU medians are 10917 / 10803 ms (about 1.04% difference). **No meaningful speedup is claimed.** This comparison measures the channel-extraction builds before the subsequent raw-port and stop-fence edits, not a complete benchmark of every final APK change. The structural separation preserves performance in this measured case; it does not establish all-mode resource equivalence or explain every earlier hot/contended failure. No candidate is enabled from these numbers.

### Short checks of affected HF+ channels

WFM, AM and USB/SSB each pass a 30-second stable window after five seconds of warm-up, at 768 kS/s. All three have zero increments in application IQ loss, driver loss, playback queue drops and underruns within the stable window. WFM's final cumulative playback drops are zero; AM and USB each have two warm-up drops (final cumulative two). These are headless short checks, not new 30-minute results, human speech-quality assessments or UI/resource comparisons. Initial missing USB permission was resolved before reception; it is not a DSP failure or a passed reception run.

The recorded long runs remain evidence for unchanged paths. Verification follows the user's revised change/defect-driven scope; no blanket device-by-mode long-run matrix is restarted.

RTL-SDR Blog V4 then passes WFM (30 s), AM (15 s) and USB/SSB (15 s) stable windows after five seconds of warm-up, at 2.4 MS/s. Each run opens, tunes and releases the receiver independently. Application IQ loss, driver loss, playback queue loss and underrun increments are zero in every stable window. Final cumulative playback drops are two for WFM/AM (warm-up only), zero for USB; all final underrun counts are zero. These checks exercise the changed U8/channel path, not reception sensitivity or a human audio-quality rating. ADS-B correctness uses the independent replay evidence rather than assuming live aircraft reception with the available antenna.

One visible-receiver interaction check lasts 60.34 s with 140 alternating tuning-wheel gestures: Android reports 3644 rendered frames and 29 janky frames (0.80%). The frame-duration histogram's highest occupied bucket is 44 ms, with no occupied buckets at or above 100 ms. This is a platform rendering/interaction observation, not a photometric display measurement or an all-scene resource comparison. Android 12+ uses the system-provided frame deadline for jank rather than assuming every duration must use the same fixed budget; see [FrameMetrics.DEADLINE](https://developer.android.com/reference/android/view/FrameMetrics#DEADLINE). The profiling tool now takes an explicit package/output and checks exact MainActivity focus before injecting gestures, rather than hard-coding the stable app.

The diagnostic display and sanitized export include only generic runtime API/page-size facts, so Android 16 acceptance can distinguish 4096 / 16384-byte pages without ADB. No model, serial, address or position is added. Final APK inspection passes: 24 native ELFs (16 64-bit libraries at 16 KB; eight 32-bit libraries checked at 4 KB only), APK `zipalign -c -P 16 4`, and checks for local paths, device endpoints, private-key headers and token patterns. Android 16 user acceptance and actual 16 KB runtime remain pending.

中文：本阶段把频道滤波、解调和音频速率处理集中到可移植接口，并让现有 ADS-B 模块实际使用统一的原始 IQ 输入／结果／状态／停止契约。修复了已复现的停止时代次误标、有效 U8 误丢和缺口首包处理问题。HF+／RTL 相关短测通过，启动期丢弃与稳定窗口分别记录。验证仅覆盖新边界与具体问题；CPU 对照只有约 1% 差异，不夸称提速，不重跑无关长测。仍需显式选择模块并接入应用层显示，不宣称已完成自动插件系统。
