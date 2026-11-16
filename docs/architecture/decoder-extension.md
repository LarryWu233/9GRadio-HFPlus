# Decoder extension boundaries

The receiver is a fixed branched pipeline with explicit data/lifetime ports and a build-time [module registry](module-registration.md). It does not discover runtime plugins or automatically create screens from a module declaration. Existing Android Views, maps and persistence remain application adapters.

## Available integration points

| Input | Existing contract | Remaining assembly work |
| --- | --- | --- |
| Demodulated audio | `AudioDecoderInput.accept(AudioBlock)` / `configure(epoch)` | Select/create the adapter on the service controller; connect its result flow to application presentation |
| Raw source IQ | Read-only `IqReadInput.iqFlow`, typed `IqBlock`, `IqDecoderModule<Result>` | Register input requirements, bind a platform factory and consume the configured source; `RegisteredIqModules` owns selection/start/stop |
| Selected channel IQ/audio | Portable `ChannelProcessor` and immutable `ChannelConfiguration` | The dispatcher owns configuration and publication; internal scratch arrays need an owned block/metadata wrapper before asynchronous delivery |
| Mic/WAV audio | Existing content source adapters and the same audio decoder port | Module adaptation must handle the declared PCM rate; source and decoder lifetimes remain controller-owned |
| Results | Existing module-specific `Flow` / state flows | The application maps results to list/map/database/file destinations; the core must not reference those destinations |

Normal internal decoder audio is mono 48 kHz and is upstream of listening NR/notch/level compensation/SQL. Changing speaker rate/volume does not redefine the protocol input. A module must read block metadata rather than infer format from an array length or Android callback timing.

`ReadsbReceiver` implements the raw-IQ module contract with the existing validated native session. It declares unsigned 8-bit / 2.4 MS/s input, exposes aircraft results and generic running/error state as flows, and retains its joined-stop/pending-release behavior. The read-only source interface does not expose the shared receiver's production controls. The original source-specific start method remains a compatibility adapter.

`ChannelProcessor.Output` is a serial computation result, not a published buffer lease. Its IQ and mono scratch views are valid until the next process call. The receiver's `AudioBlockClock` currently performs the ownership copy before fan-out. New asynchronous adapters must do the equivalent; an in-place third-party decoder needs its own mutable copy.

The content audio attachment currently has one selected slot. `setAudioDecoderInput()` replaces that attachment; it is not a multi-module subscription API. Other established branches (digital voice, raw ADS-B, external UDP) have their own owners. A new simple audio decoder should not need to modify USB transport, AudioTrack or recording, but currently still needs explicit selection/result-routing assembly.

## Responsibilities of a new adapter

- Declare its required input domain, format, rate/bandwidth and applicable source capabilities. Do not silently change shared device settings or let an unsupported device start decoding.
- Keep live admission nonblocking. Use the established bounded queue/worker policy for heavy work; count/report overflow and detect discontinuities rather than silently joining separated signal fragments.
- Apply configuration at a processing boundary. Reject old-generation input/results, reset protocol synchronization after gaps and retain ownership until worker/native/source shutdown actually completes.
- Emit typed results/events. UI, maps and database updates belong in the application service/collector, not the DSP algorithm.
- Reuse a mature decoder with recorded source/licence/version where available. Verify the actual new path or reproduced defect; do not grow a broad unrelated test matrix just to add case counts.

A small registration table now supplies input requirements, monitoring policy, picker entries and backend/presentation identities. Readsb selection uses the shared raw-module controller; protocol PCM and content adapters select their existing bound backends. Typed result collectors remain explicit in the application. There is no universal stream-graph engine, dynamic native-code loading or separate thread for every operation.

中文：现有功能声明已集中到编译期注册表，输入要求、监听策略、模式入口与后端选择共用这些声明。新增模块仍需成熟解码器的适配器、平台工厂和结果展示接线，不会仅凭声明自动生成地图或解码算法。普通模块通常不需修改 USB、音频输出或录制；全新输入域仍需单独适配。内部临时数组不可直接交给异步消费者长期持有。
