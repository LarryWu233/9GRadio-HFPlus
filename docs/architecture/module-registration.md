# Receiver module registration

`radio-core/modules/RadioModules.kt` is the build-time catalog for the existing receiver modes and audio-content modules. Its declarations have no Android, map, database or device-driver dependency. This is application assembly using the established ports, not a new DSP kernel or a runtime plugin system.

## One declaration, several adapters

| Declaration | Consumer |
| --- | --- |
| Stable ID, RF/content selector, picker group and label | Existing analog/digital/content Views choices; existing tags and page preferences remain |
| Input domain, raw format/rate, minimum bandwidth and explicit receive range | Device capability explanations and raw decoder admission |
| Backend identity and route | Cached protocol PCM binding, raw decoder factory or content backend binding |
| Monitoring policy | Existing playback/recording routing; pure data remains silent, digital voice remains audible |
| Result kinds and panel identity | Privacy-safe diagnostics and application page bindings |
| Content inputs and RF preset | Internal/microphone/WAV selection and existing content tuning behavior |

The catalog covers every existing `DemodMode`, including hidden compatibility entries. It does not claim to implement hidden DRM/FLEX modes anew. Demodulator frequency presets do not become tuning locks. Only explicit functional ranges such as 1090 MHz ADS-B restrict capability admission. ADS-B also requires unsigned 8-bit IQ at 2.4 MS/s; a Float32 file is rejected with an explanation rather than quantized or silently fed to readsb.

RF mode/backend lookups are cached when the mode changes. Protocol PCM feeds remain serial, before listening NR/notch/SQL. Readsb continues to receive raw source IQ through its existing worker/native lifecycle. No new worker, queue, audio backend, resampler or per-block descriptor is introduced. Frequency, FFT, measurement calibration and AM detector algorithms are unchanged by this stage.

## Adding a module

1. Select an established input port and a mature, license-compatible decoder. Record the pinned source and adaptation as required by the project policy.
2. Add a stable descriptor to `RadioModules`: input requirements, route/backend, monitoring policy, result kinds and any picker/panel/preset. Reject incompatible source formats instead of silently changing the shared receiver.
3. Implement the existing `IqDecoderModule<Result>` or `AudioDecoderInput` contract. Own any asynchronous buffers, count loss, reject stale generations and finish joined shutdown before releasing native/input resources.
4. Bind the platform factory. Raw factories are passed to `RegisteredIqModules` in the service. Protocol PCM bindings live in `DspEngine`; content bindings are in `AudioDecoderFactories`. Keep Android-specific construction outside `radio-core`.
5. Bind the typed result flow to an application presenter/collector and the existing page system (`ModulePanels`). Map, list, file or database destinations are explicit application decisions; result-kind metadata does not route arbitrary result objects automatically.
6. Validate the actual new path, unsupported input and stop/replacement behavior. A new modulation/frontend still needs a channel-processing adapter; the catalog cannot create one.

For raw IQ, the controller validates actual input rate/format before creating a module, caches the instance, and delegates idempotent start/health checks to it. Source/configuration changes stop the previous instance. Pending release keeps its ownership and blocks replacement; subsequent selection retries joined stop before reuse. Normal file EOF still drains through readsb's existing protocol.

The content attachment still has one selected software-audio slot. Mic and WAV adapters use that same established decoder input. This is not multi-VFO, concurrent arbitrary content decoding or automatic third-party binary discovery.

## Settings and compatibility

`ContentConfig`, `AudioContentKind` and `ContentInput` move to the portable module with their original package names and enum order. Existing preference keys, database/file formats, RF mode order and page IDs remain. SAT's APT option applies only to SAT; SSTV no longer reads it as a backend choice or clears it when selecting an SSTV mode.

中文：新增解码器的常规工作收敛为“登记声明 → 接入已有接口 → 绑定工厂 → 连接结果展示”。输入能力、监听策略与入口不必再多处各写一遍；USB、扬声器和录制通常无需改动。界面与地图仍由应用层负责，不是只写一行声明就能自动产生完整功能。本阶段未新增解码种类、线程或底层算法，也不对未测量的功耗收益作承诺。
