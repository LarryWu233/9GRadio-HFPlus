# Architecture preview 21 — ETM viewport and EiBi hints

This is the first checkpoint of the [AIS/LRPT/ETM plan](../architecture/ais-lrpt-etm-plan.md), on the separate receiver-pipeline branch. Main remains unchanged. AIS and LRPT are not implemented or exposed as working modes by this APK.

## Changes

- Expanded ETM results use the actual remaining panel viewport, including existing system-inset clearance. Normal presentation retains the existing compact height. The virtualized result list, row tuning and table styling remain.
- SW results gain a station-name reference column. The existing bundled/offline/imported EiBi schedule supplies candidates within the existing 1.5 kHz tolerance; UTC time, date/day and season rules apply. FM results retain their existing columns.
- User choice: receiver location only ranks candidates; it does not filter out distant countries. Actual relay transmitter codes take precedence over the broadcaster's country. Unambiguous coordinates come from the existing bundled EiBi README; unavailable, conflicting or multiple positions are not guessed. No new coordinate service or upload is introduced.
- Prefer the nearest known matching transmitter; retain all candidates, append an ellipsis when several exist and show a dash if none match. These are schedule hints, not signal identification. The name cell opens an EiBi query pinned to the scanned frequency/time; it does not tune the receiver. Other row areas retain the existing tune action. EiBi's frequency switch can clear the pinned query.
- Names are transient display data; ETM's stored frequency/mode/power records and existing user settings/file formats are unchanged. An offline index and visible-page cache are separate from real-time IQ/DSP. Schedule update/import invalidates the index; candidates refresh by minute/location context while the page is visible.

## Verification

- 163 JVM checks pass: 50 portable core and 113 app host checks, zero failures/skips. The three new focused checks cover known coordinates/actual relay ranking, preservation without location, UTC/frequency/season boundaries and conservative treatment of unknown/conflicting coordinates.
- The final APK passes the real Android ETM UI check: 40 explicit synthetic entries, increased expanded table height and visible row count, over 100 parsed bundled transmitter coordinates, name-cell navigation to the pinned frequency, and unchanged receiver frequency. Settings/ETM memories are restored around the check. Private screenshots were visually inspected and are not release assets.
- The existing content/EiBi UI check passes: content presets, EiBi filters, language selection and click-to-tune remain. Its harness now uses the previously established shell-foreground launch for Xiaomi. An initial legacy run was blocked after a system browser resolver appeared and was terminated, not counted as passed. The new ETM check initially selected both ETM/range tables; the test was corrected to scope the actual ETM container before passing.
- All three supported ABIs build. No native library or DSP algorithm is changed in this checkpoint. Static native/ZIP alignment, fixed native-source hashes, release/source privacy and APK checksum checks accompany delivery. No new CPU or power-saving percentage is claimed.

## Decoder work still open

AIS-catcher official source candidate `d5b1e0a50c68f81e8ef59a057899530bf365fc0a` passed an Android ARM64 headless build with optional drivers/web/API/database dependencies disabled, and a public NMEA reference parsed correctly on the phone. This is not IQ decoding or app integration and is not shipped by preview 21. SatDump source has been obtained at `9fee0793c7d42bea2d401d330422e18c33c126fa`; its minimal Meteor dependency/adapter scope is still being assessed. No new decoder algorithms are written for these probes.

The [remaining architecture acceptance](remaining-acceptance.md) and Android 16 user checks remain open; actual 16 KB execution is deferred. The UI result applies to the Android 13 backup phone, not an untested screen configuration or RF scenario.

Only the matching APK, checksum, static alignment report and sanitized validation are public assets. Exclude private screenshots, samples, raw diagnostics, helper APKs, device/network information and local paths. Generated fixtures are removed from the phone, with user recordings and saved ETM results retained.

中文：预览 21 完成 ETM 展开高度修复和短波电台名称参考。定位仅排序，全部同频候选保留；按真实发射站而非节目所属国家计算顺序。点击名称查看 EiBi 不改频，其他行区域保持调谐操作。163 项主机检查及两项实际界面检查通过，截图已核对。AIS/LRPT 仍在接入阶段，本 APK 不宣称已提供这两个解码器；主干未合并。
