# Architecture preview 14: spectrum ownership and retained windows

Status: intermediate failed playback gate; not a completed power-regression fix.

The display queue now accepts the original owned UInt8/Float32 IQ block instead of forcing RTL through another full-rate Float32 array. Source buffers are immutable after publication, and DSP correction still uses its separate scratch buffer. At 2.400 MS/s this removes a 19.2 MB/s Float32 copy/allocation stream. HF+ remains Float32 without quantization. Metadata, source positions and queue continuity are retained.

At FFT decimation 1, retain only samples that remain in the final ring window after the source block. Samples overwritten before the single end-of-block transform cannot affect the result. Advance the same sample clock and ring position for the skipped prefix. The decimated accumulator, partial groups, transform, window, averaging, scaling, SQL and scan algorithms are unchanged.

Two focused regressions cover both display input formats without mutation and chronological reference-window equivalence across wrap, empty and oversized blocks, including fine Float32 values. JVM checks pass: 39 core and 104 app. Three ABIs build.

## Real RTL foreground observations

All observations use 2.400 MS/s, FFT 2048, NR/NB off and silent software volume. CPU is process CPU, one occupied core = 100%. The observer APK was inspected and does not embed receiver production classes, so a newer helper does not replace an older app's DSP implementation.

| Version / scene | Display decimation / average | Window | CPU | IQ/driver missing | PCM drops / underruns |
| --- | --- | ---: | ---: | --- | --- |
| 13, FM BW 180 kHz | 1 / 8 | 30 s | 124.84% | 0 / 0 | 0 / 0 |
| 14, FM BW 180 kHz | 1 / 8 | 30 s | 118.65% | 0 / 0 | 0 / 1 |
| 14, FM BW 180 kHz | 2 / 1 | 30 s | 141.49% | 0 / 0 | 0 / 1 |
| 14, ADS-B map | 2 / 1 | 20 s | 162.22% | 0 / 0, including raw decoder branch | 0 / 0 |

**Do not claim a qualified CPU improvement from the failing FM windows.** Preview 12 also exhibited a playback underrun, so the buffering change is not established as its cause; the two preview-14 reproductions establish a playback issue that still needs investigation. Source IQ delivery and PCM queue loss remain separate from AudioTrack underruns.

End-window PSS snapshots on preview 14 are higher than earlier snapshots. They are not peak/live-heap measurements, but the increase remains an unresolved observation rather than evidence of improved memory. No memory-acceptance result is claimed.

The installed upstream display uses FFT 2048, display decimation off and averaging 8; the preview's saved averaging is 1. These settings change display work even in the foreground. Keep them explicit in comparisons; do not silently change user settings to claim a performance gain. Tests restore preferences. No map style/provider or DSP backend is replaced. Raw observations and received content are not public artifacts.
