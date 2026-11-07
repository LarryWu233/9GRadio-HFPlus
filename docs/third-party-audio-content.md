# Audio content decoder sources

{
  "robot36": {
    "repo": "xdsopl/robot36",
    "sha": "2af2390954aa248725042ba6b1332bbcca68c357"
  },
  "multimon-ng": {
    "repo": "EliasOenal/multimon-ng",
    "sha": "b5f7d71c5e9b5a76093ba55c515968dc40f92804"
  },
  "aptdec": {
    "repo": "srcejon/aptdec",
    "sha": "57271c33b9ccf9572d3239cd3394a0b1762d3d86"
  }
}

Robot36: unmodified decoder, PCM/WAV and microphone input classes, 0BSD. Added RadioSstvBridge as application glue in the same package.

multimon-ng: MORSE_CW core; per-file public-domain dedication, headers GPL-2.0-or-later. One safety fix: bit-string error rendering uses sizeof(sequence)*8 to avoid negative shift. Decoder/timing algorithms otherwise unchanged.

aptdec: SDRangel library variant, GPL-2.0-or-later. Decode/filter core unchanged. Runs in a separate process per session to isolate upstream static state.

Native input/output adapters and app UI are project-specific glue; core algorithms are not rewritten. License copies are bundled in assets/licenses.

## Sources and scope

- [Robot36](https://github.com/xdsopl/robot36): decoder, PCM/WAV readers and microphone source classes. The bridge snapshots only a received image buffer, not the free-running noise scope. Upstream end-of-file tests are retained; the upstream session manager is included only as a test dependency for its lifecycle tests.
- [multimon-ng](https://github.com/EliasOenal/multimon-ng): `demod_morse.c`, headers and license. PCM16 / 22050 Hz input; the existing application polyphase resampler handles conversion. Each session gets a fresh process.
- [aptdec](https://github.com/srcejon/aptdec): SDRangel's library variant, using its 2400 Hz subcarrier PLL, filters and line synchronization. PCM float / 48000 Hz input; a fresh process isolates upstream static state. The adapter exports raw grayscale rows, without false colour, map projection, satellite identification or an invented synchronization indicator.
- [GNU Radio DC blocker](https://github.com/gnuradio/gnuradio/tree/ec1bf153c9222eca81f59d4e063e73d525a51a1a/gr-filter/lib): pinned at `ec1bf153c9222eca81f59d4e063e73d525a51a1a`, GPL-3.0-or-later. The original `moving_averager_f` declarations/implementation and short-form filter loop are extracted without changing their equations. GNU Radio scheduler wrappers are replaced by a PCM16 adapter with D=128 at 22050 Hz (127-sample group delay). It is applied only before MORSE_CW, preventing receiver-start DC transients from poisoning the long-memory threshold. The original files and GPL copy are retained.
- [SDR++ CW](https://github.com/AlexandreRouma/SDRPlusPlus/blob/f10fb98af8b80de01de94e60c17608e4281e2515/core/src/dsp/demod/cw.h): GPL-3.0-only, pinned at the project's existing SDR++ commit. The CW graph and float AGC kernel are adapted to Kotlin, with Android PCM set point .35 and output ceiling .95; stream/scheduler wrappers are removed. Original CW, radio CW, frequency translator, ComplexToReal and AGC headers are retained. VFO staged decimation / FIR use the application's existing primitives; hardware offset and file-frequency reconciliation are application routing glue, not new demodulation algorithms.
- [EiBi](http://www.eibispace.de/dx/README.TXT): the official conditions explicitly allow free use, copying, distribution and third-party software integration. The bundled `sked-b26.csv` was retrieved on 2026-11-07; the official index reported 2026-10-31 as its last change. The source README is bundled alongside it. Refreshes resolve the current CSV link on the official index; no broadcast protocol is decoded by this page.

The original project's GPL-3.0 distribution is compatible with the GPL-2.0-or-later selections above. Their copyright notices, license files and pinned sources are retained in the source distribution.

## Program data and IQ metadata

[SkyWave's EiBi workflow](https://github.com/cdburgess75/SkyWave/blob/main/.github/workflows/eibi.yml) mirrors the original CSV daily onto its public `data` branch. This is an explicitly labelled HTTPS fallback, not an official EiBi endpoint. On 2026-11-07, both its B26 file and the official download had SHA-256 `f877bbd75e3c04dcd29dddfe2c4a08fe351e8c9b5a6eabf654d20c78e66f1bcb`. The app does not execute mirror scripts or download application code from this source.

[SigMF 1.2.6](https://sigmf.org/) describes raw IQ via a companion metadata file. The existing raw filenames are referenced through `core:dataset`; `core:datatype`, sample rate and capture frequency retain their normal meanings. The optional `ninegradio` v1.0.0 namespace stores `dial_frequency` in Hz and `demod_mode` as the app mode name. It can be ignored by other SigMF readers while retaining the true RF sampling centre. This does not add a runtime library dependency.

## Signal availability

[ARISS](https://www.ariss.org/upcoming-sstv-events.html) publishes SSTV event arrangements. 145.800 MHz FM is a conventional preset, not a claim that a transmission is currently on air. Manual tuning remains available.

[NOAA's retirement notice](https://www.nesdis.noaa.gov/news/legacy-orbit-noaa-decommissions-the-poes-satellite-constellation) records the 2025 decommissioning of NOAA-15/18/19. APT is therefore labelled historical; no retired satellite is offered as a current reception recommendation.
