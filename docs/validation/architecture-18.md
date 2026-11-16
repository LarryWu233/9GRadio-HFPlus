# Architecture preview 18 — AM detector nodes

Based on public alpha28 on `refactor/receiver-pipeline`, after [preview 17](architecture-17.md). Main remains unchanged and PR #1 stays draft. The independent architecture-preview package and existing Views UI, settings, maps and recording formats remain.

## Changes

- Keep one AM mode and station/monitor memory, with a 2 × 2 envelope / SAM DSB / SAM USB / SAM LSB selector. Submode changes do not retune or change Step. Symmetric ↔ single-sideband changes halve/double RF BW to preserve audio width; SQL and spectrum highlights use the selected side.
- Protect centered AM/ACARS carriers from source IQ DC removal. Away from the protection region the existing filter remains; non-AM behavior is unchanged. ACARS retains its previous envelope detector.
- Add a closeable portable `AmProcessor` port and native WDSP SAM adapter. Reuse the unchanged pinned WDSP PLL and allpass separation kernel; adapt the positive-frequency IQ convention at its boundary. Observe capture/lock/fallback state, with ±250 Hz carrier capture and 20 ms envelope fallback/capture crossfade.
- Tie Audio BW to RF width, sideband and actual processing rate, with Auto/manual controls. It is capped at 20 kHz and 45% of actual sample rate.
- Replace AM's fixed maximum 200× audio gain path with bounded complex-envelope carrier AGC: Slow/Fast/Off, default 40 dB ceiling and −110 dBFS low-level gain hold. A sustained low reading prevents further gain growth and smoothly reduces gain to at most unity after 0.5 s. This is not a new hard squelch gate. Skip listening loudness compensation while AM AGC runs, without changing its saved value.
- Retain the existing tanh safety limiter and expose its cumulative activation percentage. Existing ordinary USB/LSB, NR/notch, digital bypass and recording/output routing remain.

See [detector interface and lifecycle](../architecture/am-detectors.md), [reuse and license details](../third-party-reuse.md), and the pinned source checksums in `app/src/main/cpp/vendor/sources.json`. No new audio backend, resampler, general stream engine or hand-written PLL is introduced.

## Focused validation

- 152 JVM checks pass: 43 portable-core and 109 application checks. The added core cases cover AM/ACARS carrier protection at both receiver rates and RF/sideband/sample-rate audio-width limits. Existing SQL/sideband and channel tests remain.
- Four native Android sample checks pass on the matching application: centered carrier through the actual receiver pipeline preserves its 1 kHz modulation without full-wave 2 kHz artifacts; SAM captures ±70 Hz offset; USB/LSB reject independently generated opposite-sideband tones (900 Hz / 1.7 kHz) by more than 20 dB; sustained low input holds/reduces gain, falls back and reacquires; and manual 2 kHz Audio BW suppresses a 6 kHz component while retaining 1 kHz without safety limiting.
- The affected existing Android monitoring/recording/platform-clock checks and the actual software Morse decoder-input/noise-bypass scenario pass. These are four existing methods, not a repeated full hardware matrix.
- The final Android UI check passes with Espresso visibility assertions and private actual-screen inspection: the normal drawer-up gesture exposes the spaced 2 × 2 selector; all four choices preserve station/Step and convert RF BW; the revealed sound panel shows Audio BW and AGC, persists manual width/AGC after a fresh Activity launch, and disables stacked leveling. Initial lockscreen/USB-overlay attempts failed with `NoActivityResumedException`. An inner-ScrollView `scrollTo` and a generic swipe on the clipped full-height outer view also failed their test visibility constraints; the final test uses the normal drawer expansion and explicitly reveals the audio section through its outer scrolling container. These attempts are not visual passes and do not justify replacing the existing UI/scroll implementation.
- HF+ at 768 kS/s passes a 24-second affected-path observation: four detector selections, each with a 2-second transition/warm-up and a 4-second stable window. Whole-run diagnostics show zero application IQ gaps, driver loss, PCM queue drops and underruns. This validates stream progress, switching and output lifetime. The selected live frequency did not supply a usable SAM carrier (status remained acquiring); it does not prove live SAM lock, fading improvement, listening quality or battery savings. Known-IQ tests provide lock/sideband/fallback evidence.

The initial independent sideband test found the upstream/project sideband-numbering mismatch. The adapter mapping was corrected; the unweakened USB/LSB rejection assertions then passed. A new non-null SQL side override initially broke the existing configuration-copy test; the override is now nullable and derives the original mode convention unless explicitly supplied. The first authorized HF+ run exposed a real playback failure on submode change: active pause/flush produced a zero AudioTrack write and exited the writer. Explicit detector changes now reuse the existing joined stop/reopen output lifetime. Continuous tuning and automatic SAM lock/fallback transitions do not reopen output. The repeated hardware test and affected monitoring/recording checks pass. Initial lockscreen/USB authorization attempts and these actual failures are not counted as passes.

## Android 16 follow-up for this checkpoint

中文手动检查（不用 ADB）：选择 AM 后依次切换四种检波，确认频率和 Step 不变、单边/双边 RF BW 换算正常；在声音页检查 Auto/手动音频带宽与 AGC 关闭/慢/快、重连后恢复。用居中的强 AM 广播观察 SAM 锁定指示并试听，失锁时不应让播放线程永久停止。现场锁定、衰落音质与功耗仍需实收反馈，不能由本轮短测代替。

## Release limits

Three-ABI compilation, final APK/source privacy, pinned-input checksum/kernel identity, ELF alignment and ZIP alignment checks pass. Sixteen 64-bit libraries receive 16 KB static checks; eight 32-bit libraries receive 4 KB checks only. Actual 16 KB runtime and Android 16 user acceptance remain separate, unverified gates. Short RF windows cannot establish sensitivity or long-term stability; RTL and live SAM locking/fading are not newly verified in this checkpoint. Remaining architecture-wide acceptance from [the existing checklist](remaining-acceptance.md) is unchanged.

Release assets contain the matching APK, SHA-256, static alignment report and sanitized validation only. No helper APK, IQ/audio fixture, received content, coordinates, device identifiers/addresses, local paths or raw logs are included.

中文：本轮修复 AM 居中载波去直流保护、让音频低通跟随 BW 与采样率、限制并分离 AM AGC，并新增可插拔的包络／SAM DSB／SAM USB／SAM LSB 检波。SAM 复用 WDSP，不改普通 USB/LSB；切换不跳台、不改 Step，保留 AM 电台记忆。152 项主机检查、新检波样本、相关监听录制解码、真实界面及 HF+ 四模式短测已通过；实收无新增丢样、播放丢包或欠载。测试频点没有可用 SAM 载波，不声称已经完成实收锁定、衰落音质或功耗对比。架构分支未合入主干。
