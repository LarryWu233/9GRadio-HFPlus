# Architecture preview 15: RTL foreground resources and audio scheduling

Status: affected RTL short checks pass; whole-device power and Android 16 acceptance remain open. Keep main unchanged and the PR in draft.

## Implementation and source

Playback remains AudioTrack. Its blocking writer now runs on a session-owned Android HandlerThread at THREAD_PRIORITY_AUDIO, rather than a general IO pool. Only that owned thread receives the priority. Stop interrupts the native write, joins the writer job, then quits and joins the thread. Rate changes reuse the session's thread. Queue sizes, prefill, DSP/resampling algorithms and recording routing are unchanged.

The scheduling pattern was reviewed in Google Media3 1.9.0 [PlaybackLooperProvider.java](https://github.com/androidx/media/blob/7cc1056f840ce226598d3b990d4a6f7cd17e2831/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/PlaybackLooperProvider.java), Apache-2.0, commit `7cc1056f840ce226598d3b990d4a6f7cd17e2831`. Reviewed-file SHA-256: `f8b8fdfa85907b2dd9faa86faf3af487a8874212df9db8c394e7602c9335a173`. This uses existing Android/coroutine APIs; no Media3 implementation, Oboe backend or new DSP library is packaged.

This checkpoint also includes the separately reviewable earlier work: deferred inactive maps, 30 Hz display publication, lifecycle-aware display subscription, coalesced UI telemetry, deferred hidden direction-panel text, shared owned UInt8/Float32 display blocks and output-equivalent full-span FFT window buffering. Display averaging is not silently increased to claim a resource improvement.

## Same-device final comparison

The actual installed preview-11 APK and preview 15 use the same observer, real RTL, foreground Tune screen, 2.400 MS/s, mono FM, BW 180 kHz, FFT 2048, display decimation 2, averaging 1, NR/NB off and software volume zero. Six seconds of warm-up precede each 20-second window. The helper was verified not to embed the production receiver classes. Both windows have unchanged configuration generations, no application/driver IQ loss, no PCM drops or underruns, and 960,000 written/played audio frames.

| Metric | Preview 11 | Preview 15 | Single-pair change |
| --- | ---: | ---: | ---: |
| Process CPU, one occupied core = 100% | 180.12% | 142.89% | -20.67% |
| Java allocation during the window | 867,811,376 B | 391,102,512 B | -54.93% |
| End-window PSS snapshot | 194,532 KiB | 153,544 KiB | -21.07% |

These are bounded observations, not five-run medians, peak/live-heap or growth measurements, an all-mode resource claim, battery power, or a latest-upstream benchmark. Temperature/CPU frequencies were not locked. Earlier higher PSS observations remain in [preview 14](architecture-14.md); the lower final snapshot does not erase their variability. The installed upstream app is 1.83, while 1.84 is published; its unnormalized existing-settings observation is contextual only.

## Correctness and release checks

- 39 core and 104 app JVM checks pass, including arbitrary window boundaries, fine Float32 values and UInt8/Float32 display scale/ownership. All three supported ABIs build.
- The Android writer test verifies the actual audio priority, unchanged caller priority, and joined thread exit.
- Real RTL foreground FM: decimation 2 / averaging 1 for 20 seconds, and decimation 1 / averaging 8 for 30 seconds: zero IQ/driver loss, PCM drops and underruns. The latter has 1,440,000 written/played frames. The failed preview-12/14 windows remain documented; do not reinterpret their CPU values as qualified speedups.
- Real RTL ADS-B map, decimation 2 / averaging 1, 20 seconds: zero application/driver IQ or raw-decoder branch loss, zero PCM drops/underruns; 672 CRC-valid messages and two aircraft. Counts depend on antenna/traffic and are not an equal-RF decoder benchmark. CPU is 132.61% in this window, without a qualified cross-version ADS-B ratio.
- An additional ten-second FM stop check observes one dedicated writer and asserts it exits after receiver shutdown, before instrumentation ends. It passes with zero transport/playback loss. Its 150.37% CPU and 186,488 KiB PSS remain separate from the matched 20-second comparison.
- No new IQ/audio recordings or received-content artifacts are published. Preferences are restored. Release assets contain the application APK, checksum, static native-alignment report and sanitized validation; helper APKs, raw traces, identifiers, addresses and local paths stay private.

## Remaining acceptance

Android 16 foreground power/CPU and app switching, especially audio continuity under competing apps, still require user-device validation. HF+ power was not measured in this RTL round. No claim is made that OEM background/focus policy or monitor-clock adaptation is solved. Existing HF+/decoder evidence is reused where unchanged. Actual 16 KB runtime remains deferred under the agreed 4 KB-device scope; static alignment is not a runtime claim. Do not merge on these short windows alone.
