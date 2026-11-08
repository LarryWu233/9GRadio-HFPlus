# Architecture preview 02: configuration and lifetime barriers

Continues the unmerged architecture branch from milestone 01. The ten portable DSP classes are moved to the JVM core with original algorithms and package names; read-only FIR coefficient access and the existing obsolete calibration compatibility method become public across the module boundary.

## Implemented

- Pure processing-facing `IqInput` separates streaming/lifetime from Android-specific hardware controls.
- Immutable receiver snapshots carry configuration epochs. Mode/BW/output-rate changes reject capture blocks queued before the boundary; playback packets reject obsolete configuration epochs. Fine tuning advances the input boundary without reconstructing every processor.
- Input begins only after an undispatched collector has registered. The old 500 ms poll/start-anyway path is removed.
- Shutdown cancels/joins startup, stops the producer, cancels/joins processing and audio jobs, then frees native state. AudioTrack writes are interrupted before waiting; native pointers are released afterwards. Output-rate changes use the same wait-before-release ordering.
- TCP reads have bounded waits and stop joins the reader. TCP controls complete on the receiver control executor instead of launching unordered command jobs. RTL stop joins its producer and no longer schedules an endpoint write that can outlive close. Device-rate changes reuse source restart for both USB families.
- IQ gaps invalidate queued audio generations and reset demodulation, resampling, protocol synchronization, listening and NB state; listening fades back through the existing squelch envelope. IQ recording stops at an input gap instead of silently concatenating discontinuous input. Delivery/decoder state reset does not create replacement decoding algorithms.
- APRS-to-map adaptation moves from DSP into the application service. Processing failure is observed by the service and becomes a visible disconnected/error state.

## Validation status

Nine core tests and all 91 existing application host tests pass with zero failures/skips, including the local speech-quality fixture. The arch02 application and its instrumentation APK compile. A separate Android test now exercises 300 actual DSP/AudioTrack/native engine start-stop cycles and repeated mode/BW/output-rate changes; it is compiled but has not yet run because MIUI installation is blocked. The pure state/stream tests are not a substitute for that runtime test. Protocol gap vectors, EOF/audio drain, resource stress and hardware timing remain required before merge. This milestone does not claim the complete architecture plan is finished.

中文：本阶段整理配置边界、先停流再等待退出的顺序，以及输入缺口恢复。预览版仍独立安装，主干不变。后续继续拆分频谱／测量、录制与播放，并进行组件及真机对照。
