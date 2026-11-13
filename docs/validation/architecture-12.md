# Architecture preview 12: foreground CPU regression investigation

Status: partial remediation, awaiting RTL reception comparison. Keep the PR in draft.

The user reports substantially higher foreground CPU and battery consumption than upstream 1.83 and the pre-refactor fork, in FM and ADS-B. The previous preview-11 +3.067% CPU observation compared our alpha28 fork against a headless 1.024 MS/s case. It does **not** establish acceptable upstream-relative foreground CPU or power. Treat the reported regression as unresolved until representative reception is checked.

## Changes

- Create a native map only when its panel is first active, attached and resumed. Previously both unselected pager panels constructed TextureView renderers immediately; the pause path only covered maps that had already been marked started. Preserve the existing provider, styles, coordinates, targets, trails and gestures. After creation, retain balanced start/resume/pause/stop/destroy calls; inactive panels do not submit GeoJSON updates.
- Restore alpha28's maximum 30 Hz spectrum publication. The refactor's display branch previously published every analysis result, approaching 60 Hz. Keep the independent measurement clock and the existing FFT/window/averaging algorithms. This does not limit touch/animation frame rate.
- Collect spectrum UI state only while each spectrum Activity is started. Stop the ViewModel's upstream spectrum subscription when there are no display observers. Reception, measurement, decoders and recording continue independently.

No new DSP, resampler, FFT backend, driver or audio-output component is enabled. No map provider or screen design is replaced. Map lifecycle integration follows the existing pinned MapLibre 13.7.1 dependency and its [MapView lifecycle API](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.maps/-map-view/index.html).

## Bounded observations

Same Android 13 backup phone, foreground, charging. CPU below is **process CPU with one fully occupied core equal to 100%**, not a battery-power estimate or necessarily the user's monitoring tool's scale. CPU frequencies were not locked. These are exploratory single-pair observations, not a complete all-mode resource acceptance result.

| Scene | Reference | Preview 12 | Timed window |
| --- | ---: | ---: | --- |
| Receiver closed, same saved Tune screen | Preview 11: 65.79% | 13.41% | 15 s each, after 6 s warm-up |
| Foreground FM, synthetic TCP IQ, 2.400 MS/s, BW 180 kHz, FFT 2048, display decimation 2, NR/NB off, software volume zero | Unmodified installed alpha28: 217.89% | 163.90% | 15 s each, after 6 s warm-up |

The foreground observer exercises the real Activity, spectrum, waterfall and service. It snapshots/restores preferences, seeds a controlled receiver profile before launch, waits for service binding/source adoption, and applies the final comparison settings afterward. Initial attempts were invalidated by restored profile settings or MIUI background Activity launch restrictions; they are not performance results. The observer only asserts active input/rate: the table does not establish zero IQ loss or PCM underruns, peak memory, long-term stability, battery power or superiority over upstream 1.83. The upstream app was inspected but no matched upstream reception resource result is claimed.

A separate ten-second CPU sample confirms that TextureView render threads account for 62.17% of sampled user-space CPU in the pre-fix closed-receiver scene. In the fixed foreground FM scene, reference resampling remains the largest identified DSP self-sample contributor (22.98%); FFT input accumulation/processFloat contribute 11.09%/4.25%. Sampling shares are diagnostic, not timing improvements; the profiled run is excluded from the comparison table.

## Checks and next boundary

- All three supported ABIs build; JVM checks pass: 38 core and 103 app cases. The added case covers the display publication budget while analysis continues.
- Existing ADS-B/APRS map sample scene passes, including target display and map interaction after deferred initialization.
- The foreground TCP/FM observation passes on preview 12 and installed alpha28. It does not replace radio/driver checks.
- Next: short real RTL foreground FM/ADS-B checks, separating application IQ loss, driver loss, PCM drops and underruns; compare resource use with the actual reception settings. User-supplied Android 16 observations remain unresolved.
- Keep native static alignment evidence separate from real 16 KB runtime coverage. Dedicated 16 KB runtime remains deferred under the user's revised scope.

Raw profiles, device addresses/identifiers, UI screenshots, helper APKs and received content remain outside public artifacts.
