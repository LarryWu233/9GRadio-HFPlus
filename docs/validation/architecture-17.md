# Architecture preview 17: demand-driven monitoring and raw ADS-B routing

Status: affected correctness, platform-output, UI, reference-IQ and short RTL checks pass. Main is unchanged and PR #1 remains draft. This checkpoint does not complete all architecture acceptance gates.

## Behavior and implementation

- ADS-B, APRS, ACARS, FLEX and RAW IQ are pure-data modes. They display a disabled muted-speaker control beside OPEN/CLOSED and cannot enable monitoring or start sound recording. Existing IQ recording remains available. Active SSTV/SAT content sessions also disable RF monitoring, including while replacing a data decoder; CW retains its useful audible tone.
- Voice modes remember their monitoring choice per mode across reopening/reconnection. Disabling monitoring cancels and joins the existing writer, discards pending playback PCM, and releases AudioTrack. It does not periodically write silence. Reopening uses the existing backend/prefill and starts a fresh playback clock. An already-created writer thread may remain parked until the receiver session stops; it is not an active playback worker.
- Required protocol baseband, decoder inputs, maps and IQ recording continue. Sound recording in voice modes retains the processed audio even while monitoring is off: only playback-exclusive work stops. Existing recording SQL and automatic-stop behavior remain. ETM restores the previous monitoring request instead of forcing it on.
- Channel computation only requests decoder/output-rate PCM when a consumer needs it. APRS/ACARS/FLEX retain their required detector/discriminator and 48 kHz protocol feed, but do not run listening NR/notch/leveling or playback. Muted voice without another consumer skips unnecessary channel/audio work; broadcast-FM RDS remains a legitimate consumer. Digital voice retains its established discriminator/native decoder; output adaptation is skipped when neither playback nor recording needs it. Internal native vocoder work is retained this round rather than changing the codec/data-decoder boundary without separate evidence.
- ADS-B uses the existing readsb raw-IQ module. Its main adapter retains independent measurement, display and optional IQ recording, and skips ordinary DC/NCO, amplitude demodulation, PCM resampling and AudioTrack. U8 measurement enters the existing U8 FFT implementation directly, avoiding a full-rate float conversion; its meter/FFT calibration is equivalent to the previous float input. No sample-rate reduction, FFT algorithm replacement or HF+ precision reduction is used.
- Aircraft sprites have darker theme-derived fills and black outlines. A position report older than 30 seconds turns the plane red; fresh identity/altitude reports do not reset position age. Unchanged coordinates with a fresh position report remain fresh. The existing approximately 120-second position expiry is retained. Map source updates are skipped when features/trails are equal, and the hidden map's periodic timer does not rebuild targets.
- readsb snapshots are not reparsed when file timestamp/length are unchanged. EOF always rereads final JSON, including when its metadata matches the previous snapshot. Native readsb algorithms/process isolation remain unchanged.

## Focused correctness checks

- 41 core and 109 app JVM checks pass. Added coverage verifies direct-U8 meter calibration against float input, unchanged protocol PCM with monitor output disabled, independent position/message ages, stale/red classification and missing position-age handling.
- Seven focused Android checks pass against the matching application: pure-data playback absence with continued protocol PCM; actual SSTV input/replacement without reopening playback; muted voice recording/decoding with explicit playback resume; fresh platform playback clock after reopening; disabled data-mode UI and saved voice choice after a fresh Activity launch; recording independent of playback congestion; short-file audio/recording tail drain; and the existing software Morse decoder-input/noise-bypass scenario. These are seven test methods, with several related assertions per method.
- The affected map scene check passes for aircraft/APRS layer separation. A private synthetic screenshot confirms the darker, outlined, correctly rotated plane and disabled toolbar speaker control. Real position screenshots and test helper APKs are not release assets.
- Existing known-IQ readsb replay passes on archived preview 16 and final preview 17 with identical controlled reference-region coordinates. The original assertions remain: at least 85 native valid messages, expected callsign/altitude, full sample count, normal EOF/final snapshot and complete stop. The test snapshots/restores preferences.
- Initial uncontrolled reference attempts failed (preview 17 message count 83; preview 16 altitude assertion) after real phone location had become available. Their receiver-location configuration was not appropriate for the reference region. They remain failed observations, not qualifying evidence. The initial MIUI Activity.recreate attempt also remained STOPPED; the saved-state check uses the established shell-foreground fresh-launch path and passes. A first live attempt waited for the normal USB authorization and is not counted as a stable window.

## Short same-device observations

One archived preview-16 / preview-17 RTL ADS-B pair uses the same observer and saved receiver position/map scene, 2.400 MS/s, BW 1 MHz, FFT 2048, decimation 2, averaging 1, foreground map and six-second warm-up followed by 20 seconds. It is live RF, not identical recorded IQ; CPU clocks/temperature and tile loading were not locked. Both windows process 47,988,736 main IQ samples, feed 48,160,768 raw-decoder samples, and have unchanged configuration generations with zero main/driver/raw-branch loss and zero PCM drops/underruns. The final EOF-only snapshot guard was added afterward and checked with the reference replay; it does not change the active-window processing route. These live observations are reused for that unchanged route rather than rerunning the hardware matrix.

| Metric | Preview 16 | Preview 17 | Single-pair change |
| --- | ---: | ---: | ---: |
| CPU time over 20 seconds | 33,520 ms | 29,022 ms | -13.42% |
| Java allocation | 542,840,368 B | 285,127,264 B | -47.47% |
| End-window PSS snapshot | 315,607 KiB | 331,654 KiB | +5.08% |
| Playback frames written | 959,040 | 0 | No ADS-B playback |
| Dedicated audio writer threads | 1 | 0 | No ADS-B writer created |

The live windows yield two/zero valid messages and no positioned aircraft, so they do not prove RF sensitivity or real-aircraft marker behavior. An earlier preview-17 window has one valid message and similarly continuous/no-audio input; it is not an extra timing replicate. The known-IQ check supplies decoding evidence. PSS is a variable endpoint snapshot, not peak memory or retained-heap growth. These observations are not five-run medians, whole-device power or a battery-life percentage. The component-replacement benefit gate is not invoked: production DSP/backends remain unchanged.

Preview-17 RTL mono FM at 2.400 MS/s, BW 180 kHz, FFT 2048 / decimation 2 / averaging 1 also passes 20 seconds with zero input/driver loss, playback drops and underruns, and 960,000 written/played frames. The audio writer exits on receiver close. These affected short checks do not replace user Android 16 acceptance or an unperformed HF+ hardware check.

## Sources, compatibility and release

Playback continues to use the existing Android AudioTrack/owned audio-priority writer and joined shutdown documented in [preview 15](architecture-15.md); only demand/lifetime adapters change. No new DSP engine, lock-free buffer, codec, resampler or native component is added. Aircraft assets retain the pinned [Material icon attribution](../../third_party/material-icons/SOURCE.md) and packaged Apache-2.0 license. Existing Views UI, map providers, databases, recording formats and independent preview package remain.

Three supported ABIs build. Final APK/source privacy, attribution, ELF alignment and ZIP alignment checks pass. Sixteen 64-bit libraries have 16 KB static alignment; eight 32-bit libraries receive 4 KB checks only. Actual 16 KB runtime remains unverified/deferred. Public assets contain only matching APK, checksum, static alignment report and sanitized validation; no test helper, IQ/audio fixture, coordinates, device identifier/address, local paths or raw logs are published.

AIS and LRPT are not newly added in this checkpoint. As agreed in discussion, their mature decoder integration and acceptance are separate work; this release optimizes existing modes and establishes the same pure-data policy for later adapters.

中文：纯数据模式固定静音且按钮不可开启；语音监听按模式记忆，关闭时释放播放输出，必要解码和正在进行的语音录制继续。ADS-B 移除无用音频计算并保留独立测量/频谱/IQ 录制；飞机加黑色描边，位置超过 30 秒未更新标红。短时对照 CPU 时间下降约 13.4%、Java 分配量下降约 47.5%，内存快照上升约 5.1%，不等同于整机功耗。架构分支保持独立，未合并主干。
