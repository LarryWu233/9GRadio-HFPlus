# Architecture preview 16: ADS-B receiver location and map symbols

Status: foreground phone location and focused synthetic distance/radar/map checks pass. This checkpoint retains the preview-15 DSP, display scheduling and audio-output changes. Main remains unchanged and PR #1 stays draft.

## Changes

- A shared foreground-only receiver-location adapter uses AndroidX/platform location APIs and normal coarse/fine runtime permission. ADS-B obtains a position when its page is active; APRS uses the same position when its map is active. Requests cancel on pause, page departure, manual position override and destruction. Refresh is bounded to a 12-second acquisition attempt every two minutes, rather than continuous tracking. No background-location permission or Play-services dependency is introduced.
- Existing manual receiver coordinates remain supported and take precedence until the user selects phone location. The ADS-B position row exposes current status and the position dialog. No coordinates enter receiver-core code or exported diagnostics.
- Distance labels and aircraft tables react to position changes without waiting for another packet. The existing WGS84 distance implementation and per-site records are preserved; sites within one kilometre share their nearest existing record to avoid splitting history on small GPS movements.
- The radar label switches directly to radar. Receiver-position changes refresh the radar, and hidden radar views stop their sweep. WGS84 range/bearing is computed when aircraft/position changes, rather than on each animation frame.
- ADS-B map targets use north-up airplane sprites rotated by received true-north ground track. Missing/invalid track uses a secondary-color north-facing plane. APRS retains station symbols; map providers, target detail clicks and trails remain.

## Focused verification

- 39 core and 107 app JVM checks pass, including cardinal headings, invalid/missing headings and APRS symbol separation. Three supported ABIs build.
- Synthetic Android checks pass for WGS84/current/history distances, small position drift, distance refresh without new packets, direct radar/map switching, and separate aircraft/APRS rendered layers. Tests snapshot/restore settings and use synthetic coordinates only.
- After USB authorization is resolved, the expanded-map check also passes and a private screenshot confirms an east-facing airplane for a synthetic 90-degree track. The screenshot is not a public release asset.
- A physical Android 13 phone obtains a foreground location fix through its normal permission dialog; reported accuracy is approximately 40 m. Only success and accuracy are retained in this summary. The system-location-off and permission-pending attempts are not counted as successful fixes.
- Real RTL ADS-B input at 2.400 MS/s, FFT 2048 / decimation 2 / averaging 1 passes a 20-second window: configuration generation unchanged, 48,021,504 IQ samples processed, zero application/driver/raw-decoder missing samples, zero playback drops/underruns, and the writer thread exits on stop. The raw decoder receives samples but this window yields zero valid messages/aircraft; real aircraft-marker reception is not claimed. The initial attempt without a receiver attached is recorded as unavailable, not a passing hardware check.
- That live window reports 250.70% process CPU (one occupied core = 100%) and an end-window PSS of 356,370 KiB. It uses the newly positioned map and is not a normalized comparison with preview 15's different map scene/loading state. It does not establish a performance improvement or parity; the higher resource observation remains visible pending a matched map-scene comparison if a sustained regression is reproduced.
- The first helper invocation accidentally targeted the old installed preview because the install command used a nonexistent generic APK filename. Its missing-method/process failures are invalid evidence; the correctly named preview16 APK and matching helper were then installed and the three focused Android checks passed. An expanded-map visual attempt interrupted by receiver authorization is also not counted as a map regression pass.
- APK privacy scan, packaged Material icon license, native ELF static alignment and `zipalign -c -P 16 4` pass. Sixteen 64-bit libraries have 16 KB alignment; eight 32-bit libraries are checked for 4 KB only. Actual 16 KB runtime remains untested/deferred under the agreed scope.

This is a location/presentation change, not a new demodulator, a whole-device power benchmark or completion of all architecture merge gates. Previous performance evidence and its limitations remain in [preview 15](architecture-15.md). The user reports roughly 40% CPU in the previously problematic Android 16 foreground scenario after preview 15; this is user feedback, not a new controlled power measurement.

## Sources and privacy

Location uses the existing AndroidX `LocationManagerCompat` adapter; map symbols use the existing MapLibre SymbolLayer API. The airplane vector is adapted from Google Material Icons, with a pinned source/file checksum and Apache-2.0 license in [the attribution](../../third_party/material-icons/SOURCE.md). The license is also packaged in application assets. No new low-level DSP implementation is introduced.

Public assets contain only the matching application APK, checksum, static alignment report and sanitized validation. No helper APK, real coordinates, device address/identifier, local paths, received content or raw logs are published.

中文：本轮修复手机定位到距离统计/雷达的联动，地图飞机按收到的航迹方向旋转，未知方向使用不同颜色并朝北。保留手动接收位置、原地图来源与 APRS 符号；定位仅在相关前台页面按需获取。架构分支继续独立，未合并主干。
