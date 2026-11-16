# ggmorse audio decoder

- Source: https://github.com/ggerganov/ggmorse
- Commit: `7b4822a8cfdbb1addfe497f3ae8186f142a4ee79`
- Archive SHA-256: `250b6a5ca4e7ca678554fdd68a601bdf08fab11c9a017d738b893217763bbf9c`
- Licence: MIT; upstream LICENSE, source notices and FFT provenance comment retained.
- Included: unchanged `src/`, `include/` and LICENSE; canonical-LF checksums in SHA256SUMS. No desktop GUI, SDL or submodules.
- Scope: microphone and WAV CW audio, automatic 200-1200 Hz pitch and 5-55 WPM detection, optional fixed WPM using upstream settings. Narrowband software CW keeps the existing multimon backend.
- Android adapter: `cw_audio_worker.cpp`, 48 kHz Float32 stdin, typed text/statistics IPC, library stdout routed to private stderr. No decoder/FFT/filter/timing algorithm modifications.
- EOF follows upstream file example with three seconds of decoder-only silence for look-ahead drain; this is never sent to monitoring, IQ or audio recordings.
