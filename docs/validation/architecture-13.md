# Architecture preview 13: presentation telemetry and RTL checks

Status: intermediate checkpoint; the foreground CPU/power regression is not closed.

A real RTL ADS-B CPU sample on preview 12 identifies the hidden direction panel's status collector at 8.69% inclusive user-space samples. The panel formats text and triggers layout despite being unselected. Graphics/main-thread work dominates this scene; reference audio resampling is only 2.34% self samples, so it is not established as the primary ADS-B regression.

Changes:

- Coalesce device/DSP **presentation copies** at 100 ms upstream of the main thread. Service lifecycle/fault handling, authoritative DSP state, SQL/scanner measurements and sample clocks keep their existing rates.
- Cache the latest direction-panel reading without binding hidden text views. On selection, bind the cached reading immediately; avoid reassigning unchanged text. Preserve the live/frozen control and compass behavior.
- Extend the existing bounded foreground observer to real RTL and ADS-B. Report scalar IQ/PCM/decoder deltas, restore preferences, and use standard Android shell automation to foreground the test Activity on MIUI. No device-specific security policy is changed and no received aircraft details are exported.

## Results and limits

Same Android 13 phone and RTL, 2.400 MS/s, FFT 2048, display decimation 2, NR/NB off, silent software volume. CPU uses one occupied core = 100%. Windows start after six seconds of warm-up. Temperature/frequency were not locked; single observations are not a power benchmark.

| Scene | Preview | Window | Process CPU | Application/driver IQ missing | PCM drops / underruns | Result |
| --- | --- | ---: | ---: | --- | --- | --- |
| Foreground FM, BW 180 kHz | 12 | 20 s | 143.80% | 0 / 0 | 0 / 1 | Failed playback gate; retained |
| Foreground FM, BW 180 kHz | 13 | 30 s | 145.32% | 0 / 0 | 0 / 0 | Pass for this window; no measured FM CPU improvement |
| Foreground ADS-B map | 12, profiler attached | 30 s | Not qualified for timing comparison | 0 / 0 | 0 / 0 | 89 CRC-valid messages, one aircraft; raw decoder receives IQ |
| Foreground ADS-B map | 13 | 20 s | 169.46% | 0 / 0 | 0 / 0 | Raw decoder advances without branch loss; no frames in this short RF window |

The hidden-direction-panel regression check passes: inactive readings do not change text and activation shows the cached reading. Its initial test-context theme error was corrected to use the same themed context as the application; it is not classified as a product failure. Existing 38 core and 103 app JVM checks and three-ABI builds pass.

The installed upstream 1.83 app's existing ADS-B/map scene was observed at 144.54% CPU for 20 seconds without altering its preferences. Its FFT/display settings were not normalized, so this is contextual evidence, not a qualified performance ratio. [Upstream 1.84](https://github.com/rkarikari/9GRadio/releases/tag/9GRadio_v1.84) is now published; the installed 1.83 observation must not be labelled a latest-upstream acceptance result.

The user's loop antenna and live traffic make short aircraft counts variable. Preview-13 zero frames do not validate RF decoding, nor establish a decoder regression; transport counters and the unchanged decoder's versioned live/sample evidence are separate. Battery power, Android 16 app-switch behavior and the reported larger resource regression remain open. No DSP kernel/backend, decoder algorithm, map provider or UI appearance is replaced in this checkpoint.
