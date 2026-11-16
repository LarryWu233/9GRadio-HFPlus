# Architecture preview 20 — module registration

Based on [preview 19](architecture-19.md), on the separate receiver-pipeline branch. Main remains unchanged and PR #1 stays draft. This stage implements the accepted small module-description/registration mechanism, using the existing data and lifetime ports.

## Change and scope

- One portable catalog covers all 24 existing RF mode enum entries, plus CW audio, SSTV, SAT SSTV/APT and EiBi. It declares input domain/requirements, route, backend, monitoring policy, result kinds, picker/panel and content presets. Hidden compatibility entries do not imply new decoder support.
- Existing mode choices, order and tags now use that catalog; device availability and explanations, pure-data silence, digital-voice classification, protocol PCM binding and content backend selection use the same declarations.
- The raw-IQ controller validates actual format/rate before constructing/subscribing, handles factory/start errors, preserves pending-release ownership and retries joined shutdown before reuse. Readsb still owns its existing native process, results and EOF drain. Float32 IQ files are now explicitly unavailable for readsb rather than silently accepted by the UI.
- SAT's saved APT selection is scoped to SAT. It cannot select the wrong SSTV/CW backend, and choosing an SSTV option no longer clears the SAT preference.
- Diagnostics add the active RF module's ID, input, route and result kinds. These are fixed metadata, not received content. Settings keys, page IDs, enum order, database and recording formats remain compatible.

No decoder algorithm, AM detector/filter, resampler, audio backend, map design or measurement calibration is replaced. Backend choices are cached at mode changes; no new worker, queue or per-IQ-block descriptor is added. No new power-saving percentage is claimed. See [integration boundaries](../architecture/module-registration.md) for the platform factories and explicit result presenters still required for a new module.

## Focused verification

| Check | Result |
| --- | --- |
| JVM suites | 160 passed, zero failures/errors/skips: 50 portable core and 110 app host checks |
| New registry/controller checks | Exact existing picker order and complete RF coverage; capability/format/rate admission without frequency locks; monitoring policy; SAT/SSTV backend isolation; duplicate registration rejection; idempotent selection, failed construction, mismatched requirements and pending-stop source replacement |
| Real Views bindings | Passed: existing RF/content choices, HF+ ADS-B rejection with reason, digital-voice availability, SSTV option selection preserving SAT APT, saved voice monitoring across mode/activity restart |
| Native raw ADS-B | Passed through the registered controller using the existing independent reference IQ: expected CRC-valid messages, callsign/altitude, complete sample count, normal EOF drain and joined cleanup |
| Audio routing/content | Passed: pure-data protocol PCM without playback, remembered monitoring, recording before playback, reopened playback clock, actual software AM-to-Morse input bypassing listening processing, native Morse, Robot36, APT and cancellation/error handling |
| Android checks above | 11 passed, no skipped fixture checks; no new blanket long-run matrix |
| Hardware attempt | The short AM observation could not start because Android detected no receiver (`Receiver required`); this is not counted as a pass. Previous unchanged detector/filter hardware results are retained as historical evidence only |
| Packaging | All three supported ABIs build; final APK/source privacy, checksum, ELF and ZIP alignment checks accompany the release |

The new SAT/SSTV issue has both a real option-dialog check and actual Robot36 WAV decoding with an unrelated APT flag. The raw-controller test exercises the existing native decoder rather than replacing it with a success-only fake. No new general DSP component is introduced, so prior component A/B results remain applicable; routine repetition of unrelated sustained tests is avoided.

## Delivery limits

This checkpoint does not close the [architecture-wide acceptance gates](remaining-acceptance.md), introduce dynamic plugins, automatically generate screens from result kinds or support multiple simultaneous content slots. A new decoder still needs a suitable mature backend, platform binding, typed result collector and presentation. Reception/ETM interaction improvements remain subsequent work.

Android 16 user acceptance and actual 16 KB runtime remain unverified; current available phones use 4 KB, with 16 KB runtime deferred as agreed. The attempted receiver check remains pending until hardware is available. No unmeasured CPU/power benefit is attributed to the catalog.

Only the matching APK, checksum, static alignment report and sanitized validation are release assets. Private IQ/audio, screenshots, raw diagnostics, device/network details, local paths and helper APKs are excluded. Generated reference files are removed from the phone after verification; user settings and recordings remain.

中文：已集中现有模块的输入要求、能力限制、监听策略与入口，并接入实际后端选择和 ADS-B 启停管理。新增模块通常只需登记、接口适配、工厂及展示接线，不再多处重复判断；不会只凭声明自动生成完整解码器。160 项主机检查与 11 项针对性安卓检查通过，修复 SAT 的 APT 偏好干扰 SSTV 的问题。短 AM 实收检查因未识别到接收机未完成，不计为通过。主干未合并，后续接收体验与用户验收继续保留。
