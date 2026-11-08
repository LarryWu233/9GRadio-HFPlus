package com.radiosport.ninegradio.dsp

import kotlin.math.*

// ═════════════════════════════════════════════════════════════════════════════
//  AM DEMODULATOR  — broadcast-quality pipeline
// ═════════════════════════════════════════════════════════════════════════════
//
//  Signal flow (all processing runs at the narrow intermediate rate, 48 kHz):
//
//    IQ samples
//      │
//      ▼
//    [1] Envelope detector  │IQ│ = √(I²+Q²)
//      │
//      ▼
//    [2] DC blocker (1-pole high-pass, fc ≈ 0.8 Hz)
//        Removes the large carrier-amplitude DC component.
//      │
//      ▼
//    [3] Audio LPF — 2nd-order Butterworth biquad, fc = 8 kHz
//        The detector output spans 0–24 kHz (Nyquist of 48 kHz IF).
//        Only 0–8 kHz is useful AM audio; the rest is broadband noise.
//        Without this filter the PolyphaseResampler (IF→48 kHz) aliases
//        that entire noise band into the output, producing the characteristic
//        hissy, fatiguing sound.  Cutting here gives the dramatic
//        improvement from "SDR-generic" to "broadcast-quality" audio.
//      │
//      ▼
//    [4] Input-referred AGC — peak envelope follower
//        attack  50 ms : settles fast after squelch opens or signal changes;
//                        slow enough not to pump on individual syllables.
//        release  1 s  : prevents audible "breathing" during speech pauses.
//        target   0.70 : −3 dBFS — leaves headroom for the soft limiter.
//        max gain 200× : covers carriers as weak as −49 dBFS.
//        Gain is applied to the *current* sample using the *current* gain
//        estimate (input-referred design, no output-feedback loop).
//      │
//      ▼
//    [5] Tanh soft limiter — saturation above 0.90 FS
//        Smoothly compresses rare fast transients the AGC misses.
//        Derivative = 1 at the threshold, so the transition is inaudible.
//      │
//      ▼
//    48-kHz PolyphaseResampler  (in DspEngine, not here)
//
class AmDemodulator(private val primitives:DemodPrimitives = ReferenceDemodPrimitives) : Demodulator() {
    override val mode = DemodMode.AM

    // ── [2] DC blocker ─────────────────────────────────────────────────────────
    // Single-pole high-pass:  y[n] = α · (y[n−1] + env[n] − env[n−1])
    // α = 0.9999 → fc ≈ 0.8 Hz at 48 kHz — removes carrier DC, passes audio.
    private var prevEnv = 0f
    private var dcAcc   = 0f

    // ── [3] Audio LPF — 2nd-order Butterworth biquad ───────────────────────────
    // Coefficients are computed in init() from the actual sample rate.
    private var lpfB0 = 1f;  private var lpfB1 = 0f;  private var lpfB2 = 0f
    private var lpfA1 = 0f;  private var lpfA2 = 0f
    private var lpfX1 = 0f;  private var lpfX2 = 0f   // input delay line
    private var lpfY1 = 0f;  private var lpfY2 = 0f   // output delay line

    // ── [4] AGC ────────────────────────────────────────────────────────────────
    private var agcGain  = 1f
    private var agcEnv   = 0f
    private var atkAlpha = 0f   // re-derived from sample rate in init()
    private var relAlpha = 0f

    private var cachedSampleRate = 0

    // ── Initialisation — called once on first use and on any sample-rate change ─
    private fun init(fs: Int) {
        // 2nd-order Butterworth LPF biquad — RBJ cookbook formulation
        // Q = 1/√2  (maximally flat, no passband ripple)
        // fc = 8 kHz — covers broadcast AM (±10 kHz) and voice AM (±3 kHz)
        // Capped at 45 % of fs: when IF bandwidth < 2×fc the biquad poles leave the
        // unit circle → exponential blow-up → saturated DC output → silence.
        val fc    = minOf(8_000.0, fs * 0.45)
        val w0    = 2.0 * PI * fc / fs
        val cosW  = cos(w0)
        val sinW  = sin(w0)
        // alpha = sin(w0) / (2·Q) = sin(w0) / (2·(1/√2)) = sin(w0)·√2/2 = sin(w0)/√2
        val alpha = sinW / sqrt(2.0)
        val a0inv = 1.0 / (1.0 + alpha)
        lpfB0 = ((1.0 - cosW) / 2.0  * a0inv).toFloat()
        lpfB1 = ( (1.0 - cosW)        * a0inv).toFloat()
        lpfB2 = lpfB0                                  // symmetric
        lpfA1 = (-2.0 * cosW           * a0inv).toFloat()
        lpfA2 = ( (1.0 - alpha)        * a0inv).toFloat()
        lpfX1 = 0f;  lpfX2 = 0f;  lpfY1 = 0f;  lpfY2 = 0f

        // AGC time constants:  αₐ = e^(−1 / (t·fs))
        atkAlpha = exp(-1.0 / (0.050 * fs)).toFloat()   // 50 ms attack
        relAlpha = exp(-1.0 / (1.000 * fs)).toFloat()   // 1 s  release

        prevEnv = 0f;  dcAcc  = 0f
        agcGain = 1f;  agcEnv = 0f
        cachedSampleRate = fs
    }

    override fun demodulate(iq: FloatArray, sampleRate: Int): FloatArray {
        if (sampleRate != cachedSampleRate) init(sampleRate)

        val n   = iq.size / 2
        val out = FloatArray(n)

        // ── [1] Envelope detection — vectorised via NativeDsp (NEON/VOLK) ──────
        val envBuf = primitives.amDetect(iq)

        for (i in 0 until n) {
            val env = envBuf[i]

            // ── [2] DC blocker ─────────────────────────────────────────────────
            dcAcc   = 0.9999f * (dcAcc + env - prevEnv)
            prevEnv = env

            // ── [3] Audio LPF (direct-form II transposed biquad) ──────────────
            val x   = dcAcc
            val lpf = lpfB0 * x + lpfB1 * lpfX1 + lpfB2 * lpfX2 -
                      lpfA1 * lpfY1 - lpfA2 * lpfY2
            lpfX2 = lpfX1;  lpfX1 = x
            lpfY2 = lpfY1;  lpfY1 = lpf

            // ── [4] Input-referred AGC ─────────────────────────────────────────
            // Track the absolute-peak envelope of the filtered audio signal.
            // Gain is computed from the input level (not output), eliminating the
            // output-feedback loop that causes subtle distortion in a naive AGC.
            val absLpf = abs(lpf)
            agcEnv = if (absLpf > agcEnv)
                atkAlpha * agcEnv + (1f - atkAlpha) * absLpf   // fast attack
            else
                relAlpha * agcEnv + (1f - relAlpha) * absLpf   // slow release
            if (agcEnv > 1e-7f) {
                // Target −3 dBFS (0.70).  Max 200× (46 dB) handles the weakest
                // RTL-SDR signals; min 0.001× prevents runaway on full-scale input.
                agcGain = (0.70f / agcEnv).coerceIn(0.001f, 200f)
            }

            // ── [5] Apply gain + tanh soft-limiter ─────────────────────────────
            // Saturation above 0.90 FS: maps [0.9, ∞) → [0.9, 1.0) with a smooth
            // tanh curve.  Derivative = 1 at the threshold — completely transparent
            // unless a fast transient has outrun the AGC, in which case it is
            // far preferable to the harsh spectral splatter of hard clipping.
            val g = lpf * agcGain
            out[i] = when {
                g >  0.90f ->  0.90f + 0.10f * tanh(((g - 0.90f) * 10.0)).toFloat()
                g < -0.90f -> -0.90f - 0.10f * tanh(((-g - 0.90f) * 10.0)).toFloat()
                else       ->  g
            }
        }
        return out
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  FM (Narrow FM) DEMODULATOR
// ═════════════════════════════════════════════════════════════════════════════
//
//  Signal flow (all processing at NARROW_INTERMEDIATE_RATE, 48 kHz):
//
//    IQ samples
//      │
//      ▼
//    [1] FM discriminator — atan2-based phase differentiator (bounded, no I²+Q² divide)
//        Gain = fs / (2π × 5000) → normalises ±5 kHz deviation to ±1.0
//      │
//      ▼
//    [2] DC blocker  (1-pole high-pass, fc ≈ 0.8 Hz)
//        Removes any residual DC from the discriminator without
//        affecting audio content above ~5 Hz.
//      │
//      ▼
//    [3] Audio LPF — 2nd-order Butterworth biquad, fc = 5 kHz
//        NFM voice sits 300 Hz–3.5 kHz; the discriminator also
//        produces out-of-band noise up to the IF Nyquist (24 kHz).
//        Cutting at 5 kHz passes the full audio band while rejecting
//        ~14 dB of noise power before the AGC and resampler.
//      │
//      ▼
//    [4] Input-referred AGC — peak envelope follower
//        attack  50 ms  : fast enough to respond to a new talker.
//        release  1 s   : prevents audible gain-breathing on pauses.
//        target   0.70  : −3 dBFS, leaves headroom for the limiter.
//        max gain 200×  : handles signals as weak as −49 dBFS.
//      │
//      ▼
//    [5] Tanh soft limiter (threshold 0.90 FS)
//        Smoothly absorbs any fast transient the AGC misses.
//      │
//      ▼
//    48-kHz PolyphaseResampler (in DspEngine)
//
class NfmDemodulator(private val primitives:DemodPrimitives = ReferenceDemodPrimitives) : Demodulator() {
    override val mode = DemodMode.NFM

    // ── [1] FM discriminator state — prevI/prevQ across block boundaries ────────
    // float[2] = [prevI, prevQ]; passed to primitives.fmDemodulateStateful() and
    // updated in-place so the phase estimate is continuous at USB-block seams.
    private val fmState = FloatArray(2)   // [0]=prevI, [1]=prevQ

    private var prevI = 0f
    private var prevQ = 0f

    // ── [2] DC blocker ─────────────────────────────────────────────────────────
    private var dcPrev = 0f
    private var dcAcc  = 0f

    // ── [3] Audio LPF — 2nd-order Butterworth biquad ───────────────────────────
    private var lpfB0 = 1f;  private var lpfB1 = 0f;  private var lpfB2 = 0f
    private var lpfA1 = 0f;  private var lpfA2 = 0f
    private var lpfX1 = 0f;  private var lpfX2 = 0f
    private var lpfY1 = 0f;  private var lpfY2 = 0f

    // ── [4] AGC ────────────────────────────────────────────────────────────────
    private var agcGain  = 1f
    private var agcEnv   = 0f
    private var atkAlpha = 0f
    private var relAlpha = 0f

    private var cachedSampleRate = 0

    // ── Initialisation — called once and on any sample-rate change ──────────────
    private fun init(fs: Int) {
        // 2nd-order Butterworth LPF biquad (RBJ cookbook, Q = 1/√2)
        // fc = 5 kHz — passes full NFM voice deviation; rejects discriminator noise.
        // Capped at 45 % of fs: when IF bandwidth < 2×fc the biquad poles leave the
        // unit circle → exponential blow-up → saturated DC output → silence.
        val fc    = minOf(5_000.0, fs * 0.45)
        val w0    = 2.0 * PI * fc / fs
        val cosW  = cos(w0)
        val sinW  = sin(w0)
        val alpha = sinW / sqrt(2.0)
        val a0inv = 1.0 / (1.0 + alpha)
        lpfB0 = ((1.0 - cosW) / 2.0 * a0inv).toFloat()
        lpfB1 = ((1.0 - cosW)        * a0inv).toFloat()
        lpfB2 = lpfB0
        lpfA1 = (-2.0 * cosW         * a0inv).toFloat()
        lpfA2 = ((1.0 - alpha)       * a0inv).toFloat()
        lpfX1 = 0f;  lpfX2 = 0f;  lpfY1 = 0f;  lpfY2 = 0f

        // AGC time constants: α = e^(−1/(t·fs))
        atkAlpha = exp(-1.0 / (0.050 * fs)).toFloat()   // 50 ms attack
        relAlpha = exp(-1.0 / (1.000 * fs)).toFloat()   //  1 s  release

        dcPrev = 0f;  dcAcc  = 0f
        agcGain = 1f; agcEnv = 0f
        cachedSampleRate = fs
        fmState[0] = 0f; fmState[1] = 0f
    }

    override fun demodulate(iq: FloatArray, sampleRate: Int): FloatArray {
        if (sampleRate != cachedSampleRate) init(sampleRate)

        // NFM max deviation is ±5 kHz.  Correct normalisation: gain = fs / (2π × 5000).
        // Note: NFM (amateur / land-mobile radio) does NOT use pre/de-emphasis.
        // De-emphasis is a WFM broadcast standard (75 µs in Americas/Japan, 50 µs Europe).
        // Applying 75 µs de-emphasis rolls off audio above ~2.1 kHz, making voices
        // muffled and suppressing CTCSS/DCS tones.  WfmDemodulator handles its own
        // de-emphasis correctly; NFM output is left flat after the LPF.
        val gainHz = sampleRate / (2f * PI.toFloat() * 5_000f)
        val out  = FloatArray(iq.size / 2)

        // ── [1] FM discriminator — stateful NEON/VOLK path ─────────────────────
        // fmDemodulateStateful() updates fmState[0/1] (prevI/prevQ) in-place so
        // the phase estimate is continuous across USB-bulk-transfer block seams.
        val disc = primitives.fmDemodulateStateful(iq, fmState, gainHz)

        for (i in out.indices) {
            // ── [2] DC blocker ─────────────────────────────────────────────────
            dcAcc  = 0.9999f * (dcAcc + disc[i] - dcPrev)
            dcPrev = disc[i]

            // ── [3] Audio LPF (direct-form II transposed biquad) ──────────────
            val x   = dcAcc
            val lpf = lpfB0 * x + lpfB1 * lpfX1 + lpfB2 * lpfX2 -
                      lpfA1 * lpfY1 - lpfA2 * lpfY2
            lpfX2 = lpfX1;  lpfX1 = x
            lpfY2 = lpfY1;  lpfY1 = lpf

            // ── [4] Input-referred AGC ─────────────────────────────────────────
            val absLpf = abs(lpf)
            agcEnv = if (absLpf > agcEnv)
                atkAlpha * agcEnv + (1f - atkAlpha) * absLpf   // fast attack
            else
                relAlpha * agcEnv + (1f - relAlpha) * absLpf   // slow release
            if (agcEnv > 1e-7f) {
                agcGain = (0.70f / agcEnv).coerceIn(0.001f, 200f)
            }

            // ── [5] Apply gain + tanh soft-limiter ─────────────────────────────
            val g = lpf * agcGain
            out[i] = when {
                g >  0.90f ->  0.90f + 0.10f * tanh(((g - 0.90f) * 10.0)).toFloat()
                g < -0.90f -> -0.90f - 0.10f * tanh(((-g - 0.90f) * 10.0)).toFloat()
                else       ->  g
            }
        }
        return out
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  APRS DEMODULATOR  (Bell 202 AFSK, 1200 baud)
// ═════════════════════════════════════════════════════════════════════════════
//
//  FIX 17: APRS must NOT use NfmDemodulator.
//
//  NfmDemodulator applies three post-discriminator stages that are correct
//  for voice but catastrophic for AFSK tone decoding:
//
//    [3] 5 kHz Butterworth LPF  — passes both APRS tones (1200 / 2200 Hz) but
//        introduces group-delay dispersion between them, distorting the mark↔
//        space transition edges that the AprsDecoder PLL uses for clock sync.
//
//    [4] Input-referred AGC (50 ms attack / 1 s release, target 0.70 FS) —
//        normalises the audio amplitude to a fixed RMS.  This destroys the
//        per-channel amplitude information that AfskBandpassFilter.processAgc()
//        relies on to independently normalise the 1200 Hz and 2200 Hz envelopes
//        and compensate for the 3–6 dB de-emphasis imbalance between them.
//        After NfmDemodulator's AGC both tones arrive at approximately the same
//        amplitude, so the second-stage per-channel AGC inside AprsDecoder has
//        nothing to normalise — the slicer is back to comparing raw power levels
//        biased by the de-emphasis curve, i.e. always biased toward mark.
//
//    [5] tanh soft-limiter — further compresses and distorts the waveform.
//
//  The correct signal path for APRS is:
//
//    IQ at narrow IF rate (48 kHz)
//      │
//      ▼
//    [1] FM discriminator — primitives.fmDemodulateStateful()
//        gain = fs / (2π × 5 000) — normalises ±5 kHz NFM deviation to ±1
//      │
//      ▼
//    [2] DC blocker — single-pole IIR (α = 0.9999), removes DC offset
//        introduced by any I/Q imbalance or discriminator bias.
//      │
//      ▼
//    Raw discriminator audio → AprsDecoder.feed()
//        AprsDecoder's AfskBandpassFilter + per-channel AGC take it from here.
//
//  Reference: Dire Wolf demod_afsk.c — the discriminator output goes directly
//  into the bandpass correlator; no AGC or limiter is applied at this stage.
//
class AprsDemodulator(private val primitives:DemodPrimitives = ReferenceDemodPrimitives) : Demodulator() {
    override val mode = DemodMode.APRS

    private val fmState = FloatArray(2)   // [prevI, prevQ] — stateful across blocks

    private var dcPrev = 0f
    private var dcAcc  = 0f

    private var cachedSampleRate = 0

    private fun init(fs: Int) {
        fmState[0] = 0f; fmState[1] = 0f
        dcPrev = 0f;  dcAcc = 0f
        cachedSampleRate = fs
    }

    override fun demodulate(iq: FloatArray, sampleRate: Int): FloatArray {
        if (sampleRate != cachedSampleRate) init(sampleRate)

        // [1] FM discriminator — same gain as NFM (±5 kHz deviation → ±1).
        val gainHz = sampleRate / (2f * PI.toFloat() * 5_000f)
        val disc = primitives.fmDemodulateStateful(iq, fmState, gainHz)

        // [2] DC blocker only — no LPF, no AGC, no limiter.
        // AprsDecoder's per-channel biquad + AGC handles everything downstream.
        val out = FloatArray(disc.size)
        for (i in disc.indices) {
            dcAcc  = 0.9999f * (dcAcc + disc[i] - dcPrev)
            dcPrev = disc[i]
            out[i] = dcAcc
        }
        return out
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  WIDE FM DEMODULATOR (broadcast, with stereo pilot decoding)
// ═════════════════════════════════════════════════════════════════════════════
//
//  Signal flow (runs at the adaptive WFM IF rate, see DspEngine.wfmIfRate()):
//
//    IQ at wfmIfRate
//      │
//      ▼
//    [1] FM discriminator — atan2-based phase differentiator (bounded, no I²+Q² divide)
//        gain = fs / (2π × 75 kHz) — normalises ±75 kHz deviation to ±1
//      │
//      ▼
//    [2] 75 µs de-emphasis — 1-pole IIR, α = exp(−1 / (fs × 75e-6))
//        Coefficient is recomputed whenever fs changes (see init(fs)).
//      │  (mono path exits here)
//      ▼  (stereo path continues)
//    [3] Stereo BPF — 2nd-order RBJ bandpass centred at 38 kHz, Q≈1.27
//        b0 = sin(w0)/2, b1 = 0, b2 = −b0;  α = sin(w0)/(2Q)
//        Coefficients recomputed in init(fs) so the filter tracks fs changes.
//        Passes the 23–53 kHz stereo subchannel; suppresses pilot (19 kHz)
//        and mono audio (0–15 kHz).
//      │
//      ▼
//    [4] 38 kHz carrier regeneration — open-loop NCO at 2 × pilot frequency.
//        pilotStep = 2π × 19 000 / fs  (recomputed in init(fs)).
//        Doubling the phase gives the 38 kHz suppressed carrier.
//      │
//      ▼
//    [5] DSB product demodulation — stereoSub × carrier38 × 2
//        Recovers the L−R difference signal.
//      │
//      ▼
//    [6] Matrix  L = (L+R) + (L−R),  R = (L+R) − (L−R)
//        Returns interleaved [L₀, R₀, L₁, R₁, …] at wfmIfRate.
//      │
//      ▼
//    Audio PolyphaseResampler(wfmIfRate → 48 kHz)  — in DspEngine
//
class WfmDemodulator(private val stereo: Boolean = false, private val primitives:DemodPrimitives = ReferenceDemodPrimitives) : Demodulator() {
    override val mode = if (stereo) DemodMode.WFM_STEREO else DemodMode.WFM
    private val fmState = FloatArray(2)
    private val decoder = BroadcastFmAudio(stereo)
    private var rate = 0
    var deemphasisUs = 75
    var multiplex = FloatArray(0)
        private set
    override fun demodulate(iq: FloatArray, sampleRate: Int): FloatArray {
        if (rate != sampleRate) { rate=sampleRate; fmState.fill(0f) }
        val count=iq.size/2
        if (multiplex.size != count) multiplex=FloatArray(count)
        primitives.fmDemodulateStatefulInto(iq,fmState,sampleRate/(2f*PI.toFloat()*75000),multiplex)
        return decoder.process(multiplex,sampleRate,deemphasisUs)
    }
}

class SsbDemodulator(private val isUsb: Boolean, private val primitives:DemodPrimitives = ReferenceDemodPrimitives) : Demodulator() {
    override val mode = if (isUsb) DemodMode.USB else DemodMode.LSB

    private val bfFreq = 1500.0  // Beat frequency oscillator (1.5 kHz)
    private var bfoPhase = 0.0

    // Low-pass FIR — rebuilt whenever the sample rate changes so the cutoff
    // is always 3 kHz regardless of input rate.  The old code hardcoded
    // sampleRate=11025 here, so before narrow pre-decimation was added
    // fc was effectively 0 (3000/deviceRate ≈ 0.001) — the filter passed
    // everything, making USB/LSB sound like full-bandwidth noise.
    private var lpfSampleRate = 0
    private var lpfTaps = FloatArray(0)
    private val LPF_TAPS  = 63          // more taps for better roll-off
    private val LPF_CUTOFF = 3000.0     // SSB voice bandwidth ceiling

    // ── [2] Dual-branch FIR state — passed to primitives.applyFirDual() ─────────
    // NativeDsp keeps the delay lines continuous across block boundaries so there
    // are no clicks at USB-block seams.  bufPos[0] is wrapped mod LPF_TAPS.
    private var lpfBufI  = FloatArray(LPF_TAPS)
    private var lpfBufQ  = FloatArray(LPF_TAPS)
    private val lpfBufPos = IntArray(1)   // single-element so it can be updated by ref

    // ── [4] DC blocker (1-pole high-pass) ──────────────────────────────────────
    // R = 0.9995 → fc ≈ 24 Hz at 48 kHz — removes BFO carrier leak without
    // affecting any speech content above ~50 Hz.
    private var dcX1 = 0f
    private var dcY1 = 0f
    private val DC_R = 0.9995f

    // ── [5] AGC ────────────────────────────────────────────────────────────────
    private var agcGain    = 1f
    private var agcEnv     = 0f
    private var atkAlpha   = 0f
    private var relAlpha   = 0f
    private var agcFsCache = 0

    private fun initAgc(fs: Int) {
        // Longer release (1.5 s) than AM/NFM to prevent gain pumping between
        // syllables — the natural way SSB sounds on a good receiver.
        atkAlpha = exp(-1.0 / (0.050 * fs)).toFloat()   // 50 ms attack
        relAlpha = exp(-1.0 / (1.500 * fs)).toFloat()   //  1.5 s release
        agcGain  = 1f
        agcEnv   = 0f
        agcFsCache = fs
    }

    override fun demodulate(iq: FloatArray, sampleRate: Int): FloatArray {
        val n = iq.size / 2
        val out = FloatArray(n)
        val bfoStep = 2.0 * PI * bfFreq / sampleRate

        // Rebuild the FIR prototype if the sample rate has changed.
        if (sampleRate != lpfSampleRate) {
            // Cap the cutoff to 45 % of Nyquist so the FIR taps remain stable even when
            // the user has manually set a very narrow IF bandwidth (e.g. slider at 4 kHz →
            // sampleRate=4000 Hz → unclamped fc = 3000/4000 = 0.75 which is above the
            // Nyquist limit and produces a completely broken/aliased filter).
            // 0.45 × fs/2 is the same safety margin used by the AM/NFM biquads above.
            val safeCutoff = minOf(LPF_CUTOFF, sampleRate * 0.45)
            lpfTaps = buildLpf(LPF_TAPS, safeCutoff, sampleRate.toDouble())
            lpfSampleRate = sampleRate
            lpfBufI  = FloatArray(LPF_TAPS)
            lpfBufQ  = FloatArray(LPF_TAPS)
            lpfBufPos[0] = 0
        }
        if (sampleRate != agcFsCache) initAgc(sampleRate)

        // ── [1] BFO product detection — mix entire block with NCO ──────────────
        // The BFO is a stateful phase accumulator; it must run sample-by-sample.
        // We keep it in Kotlin and produce an intermediate interleaved mixIQ buffer
        // so that step [2] can be handed off to NativeDsp as a contiguous block.
        val mixIQ = FloatArray(iq.size)
        for (i in 0 until n) {
            val inI = iq[2 * i]
            val inQ = iq[2 * i + 1]

            val bfoI = cos(bfoPhase).toFloat()
            val bfoQ = sin(bfoPhase).toFloat()
            mixIQ[2 * i]     = inI * bfoI - inQ * bfoQ
            mixIQ[2 * i + 1] = inI * bfoQ + inQ * bfoI

            bfoPhase += bfoStep
            if (bfoPhase > 2.0 * PI) bfoPhase -= 2.0 * PI
        }

        // ── [2] Dual-branch FIR LPF — NEON/VOLK block call ───────────────────
        // applyFirDual() processes I and Q channels together in one JNI call,
        // updating lpfBufI/lpfBufQ/lpfBufPos in-place to stay continuous across
        // USB block seams.  Returns an interleaved FloatArray (same length as mixIQ).
        val filtered = primitives.applyFirDual(mixIQ, lpfTaps, lpfBufI, lpfBufQ, lpfBufPos)

        for (i in 0 until n) {
            val filtI = filtered[2 * i]
            val filtQ = filtered[2 * i + 1]

            // ── [3] Sideband select: USB = I+Q, LSB = I−Q ─────────────────────
            val raw = if (isUsb) filtI + filtQ else filtI - filtQ

            // ── [4] DC blocker: y[n] = x[n] − x[n−1] + R·y[n−1] ──────────────
            val dcOut = raw - dcX1 + DC_R * dcY1
            dcX1 = raw;  dcY1 = dcOut

            // ── [5] Input-referred AGC ─────────────────────────────────────────
            val absOut = abs(dcOut)
            agcEnv = if (absOut > agcEnv)
                atkAlpha * agcEnv + (1f - atkAlpha) * absOut   // fast attack
            else
                relAlpha * agcEnv + (1f - relAlpha) * absOut   // slow release
            if (agcEnv > 1e-7f) {
                agcGain = (0.70f / agcEnv).coerceIn(0.001f, 200f)
            }

            // ── [6] Apply gain + tanh soft-limiter ─────────────────────────────
            val g = dcOut * agcGain
            out[i] = when {
                g >  0.90f ->  0.90f + 0.10f * tanh(((g - 0.90f) * 10.0)).toFloat()
                g < -0.90f -> -0.90f - 0.10f * tanh(((-g - 0.90f) * 10.0)).toFloat()
                else       ->  g
            }
        }
        return out
    }

    private fun buildLpf(taps: Int, cutoff: Double, sampleRate: Double): FloatArray {
        val h = FloatArray(taps)
        val fc = cutoff / sampleRate
        val m = (taps - 1) / 2.0
        for (i in 0 until taps) {
            val x = 2.0 * PI * fc * (i - m)
            val sinc = if (x == 0.0) 1.0 else sin(x) / x
            // Hamming window
            val w = 0.54 - 0.46 * cos(2.0 * PI * i / (taps - 1))
            h[i] = (sinc * w).toFloat()
        }
        return h
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  CW DEMODULATOR (Morse code)
// ═════════════════════════════════════════════════════════════════════════════
//
//  Signal flow (all processing at the narrow intermediate rate, 8 kHz):
//
//    IQ samples (baseband — CW carrier offset is at 0 Hz after tuning)
//      │
//      ▼
//    [1] BFO product detector — multiply by complex NCO at ±800 Hz
//        For CW the carrier is a pure tone; the BFO shifts it to 800 Hz
//        in the audio band so the operator hears the classic CW side-tone.
//        CWR (reverse CW) uses −800 Hz, swapping the sideband.
//      │
//      ▼
//    [2] Narrow band-pass FIR (63-tap Hamming, 200–1400 Hz)
//        Rejects all audio outside the CW tone range; the two-stage
//        implementation uses a LPF prototype shifted to band-centre (800 Hz).
//        This removes wideband noise that would otherwise feed the AGC and
//        mask weak signals.
//      │
//      ▼
//    [3] Sideband select: take real part (I channel) — equivalent to
//        USB demodulation for the 800 Hz tone.
//      │
//      ▼
//    [4] DC blocker (1-pole high-pass, fc ≈ 24 Hz)
//        Removes any BFO carrier leak without affecting the audio tone.
//      │
//      ▼
//    [5] Input-referred AGC — peak envelope follower
//        attack   20 ms  : fast enough to respond to a new dit/dah.
//        release   0.5 s : slow enough to ride through inter-element gaps.
//        target    0.70  : −3 dBFS.
//        max gain  500×  : recovers very weak CW signals (−54 dBFS).
//      │
//      ▼
//    [6] Tanh soft limiter (threshold 0.90 FS)
//      │
//      ▼
//    48-kHz PolyphaseResampler (in DspEngine)
//
// CW/CWR use the SDR++ graph adaptation in CwDemodulator.kt.

// ═════════════════════════════════════════════════════════════════════════════
//  DSB DEMODULATOR (Double Side Band / DSB-SC)
// ═════════════════════════════════════════════════════════════════════════════
// DSB-SC carries audio on both sidebands of a suppressed carrier.
// The correct demodulation is a product detector: multiply by a local carrier
// at 0 Hz (i.e. take the real part of the baseband IQ), then low-pass filter.
// The previous implementation used an FM phase discriminator (d/dt phase),
// which is completely wrong for an AM-family signal — it produced
// differentiated noise instead of audio.
//
// Because the carrier is suppressed the recovered audio amplitude depends on
// carrier phase alignment; a DC-blocking high-pass on the output removes any
// residual carrier leak without affecting voice content.
class DsbDemodulator : Demodulator() {
    override val mode = DemodMode.DSB

    // Single-pole high-pass DC blocker on the demod output
    private var dcX1 = 0f
    private var dcY1 = 0f
    private val DC_R  = 0.9995f   // τ ≈ 2000 samples @ 48 kHz → ~24 Hz corner

    override fun demodulate(iq: FloatArray, sampleRate: Int): FloatArray {
        val n = iq.size / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            // Product detector with zero-frequency carrier: output = Re(IQ) = I sample.
            // For DSB-SC the baseband signal is centred at 0 Hz after down-conversion,
            // so the I channel already contains L+R (or the full audio for mono DSB).
            val x = iq[2 * i]
            // DC blocker: y[n] = x[n] - x[n-1] + R·y[n-1]
            val y = x - dcX1 + DC_R * dcY1
            dcX1 = x
            dcY1 = y
            out[i] = y
        }
        return out
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  RAW IQ (pass-through for recording / spectrum only)
// ═════════════════════════════════════════════════════════════════════════════
class RawDemodulator : Demodulator() {
    override val mode = DemodMode.RAW

    override fun demodulate(iq: FloatArray, sampleRate: Int): FloatArray {
        // Return magnitude of I/Q pairs for monitoring
        val n = iq.size / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            out[i] = sqrt(iq[2 * i] * iq[2 * i] + iq[2 * i + 1] * iq[2 * i + 1])
        }
        return out
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  DIGITAL VOICE DEMODULATOR  (DMR / YSF / D-STAR / P25 / NXDN / M17)
// ═════════════════════════════════════════════════════════════════════════════
//
//  Dedicated FM discriminator for digital voice protocols, kept separate from
//  AprsDemodulator so that future changes to either path (different gain,
//  filtering, or post-processing) do not accidentally affect the other.
//
//  Signal chain:
//    IQ at demodRate (≥ 48 kHz)
//      → FM discriminator  gain = fs / (2π × 5000)  (±5 kHz → ±1.0)
//      → DC blocker        α = 0.9999  (single-pole IIR, fc ≈ 0.8 Hz)
//      → float[] at demodRate  (passed to DigitalVoiceDecoder.feed())
//
//  Gain rationale — why ±5 kHz normalisation and NOT the per-protocol
//  outer-deviation value (±1944 Hz for DMR, ±2400 Hz for YSF):
//
//    The dsdcc cosine filter (dmr_filter) is a 61-tap RRC FIR whose
//    worst-case output gain is ~6.83× the peak input before dividing by
//    dmrgain.  For input at ±1.0 (short ±32767) the convolution sum can
//    reach ~±35 600, which overflows int16 when cast to short — producing
//    garbage symbol values and completely destroying 4FSK inner-symbol
//    discrimination.  Using ±5 kHz normalisation keeps DMR/YSF outer
//    symbols at ~39–48 % of FS (±12 750 / ±15 700 short), giving the
//    filter ~2× headroom before saturation, while still giving dsdcc's
//    adaptive min/max tracker enough signal range to converge.
//
//  No AGC, LPF, or limiter is applied here — dsdcc performs its own
//  adaptive level tracking (snapMinMax) and symbol clock recovery
//  internally.
//
class DigitalVoiceDemodulator(private val primitives:DemodPrimitives = ReferenceDemodPrimitives) : Demodulator() {
    override val mode = DemodMode.DMR   // representative; factory creates one per protocol

    private val fmState = FloatArray(2)   // [prevI, prevQ] — stateful across blocks

    private var dcPrev = 0f
    private var dcAcc  = 0f

    private var cachedSampleRate = 0

    private fun init(fs: Int) {
        fmState[0] = 0f; fmState[1] = 0f
        dcPrev = 0f;  dcAcc = 0f
        cachedSampleRate = fs
    }

    override fun demodulate(iq: FloatArray, sampleRate: Int): FloatArray {
        if (sampleRate != cachedSampleRate) init(sampleRate)

        // FM discriminator — ±5 kHz normalisation keeps all digital voice
        // signals within ~40–70 % of int16 FS, safely below the dsdcc
        // cosine-filter saturation ceiling.  See class comment for details.
        val gainHz = sampleRate / (2f * PI.toFloat() * 5_000f)
        val disc = primitives.fmDemodulateStateful(iq, fmState, gainHz)

        // DC blocker — removes discriminator DC offset without distorting
        // the 4FSK symbol eye (single-pole IIR, fc ≈ 0.8 Hz at 48 kHz).
        val out = FloatArray(disc.size)
        for (i in disc.indices) {
            dcAcc  = 0.9999f * (dcAcc + disc[i] - dcPrev)
            dcPrev = disc[i]
            out[i] = dcAcc
        }
        return out
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  FACTORY
// ═════════════════════════════════════════════════════════════════════════════
object DemodulatorFactory {
    fun create(mode: DemodMode, primitives:DemodPrimitives = ReferenceDemodPrimitives): Demodulator = when (mode) {
        DemodMode.AM     -> AmDemodulator(primitives)
        // ACARS is transmitted on an AM carrier (VDL Mode A).  The AM demodulator
        // recovers the baseband audio that contains the 1200/2400 Hz AFSK subcarrier
        // tones.  Previously ACARS fell through to RawDemodulator(), which returned
        // IQ magnitudes at device rate — completely wrong input for AcarsDecoder.
        DemodMode.ACARS  -> AmDemodulator(primitives)
        DemodMode.NFM    -> NfmDemodulator(primitives)
        DemodMode.APRS   -> AprsDemodulator(primitives)  // FIX 17: raw discriminator, no AGC/LPF/limiter
        // Digital voice — each protocol gets its own DigitalVoiceDemodulator
        // instance so that APRS and digital voice signal chains are fully
        // decoupled and can evolve independently.
        DemodMode.DMR,
        DemodMode.P25,
        DemodMode.NXDN,
        DemodMode.DSTAR,
        DemodMode.YSF,
        DemodMode.M17,
        DemodMode.DPMR,
        DemodMode.DIG    -> DigitalVoiceDemodulator(primitives)
        DemodMode.FM, DemodMode.WFM -> WfmDemodulator(stereo = false, primitives = primitives)
        DemodMode.WFM_STEREO -> WfmDemodulator(stereo = true, primitives = primitives)
        DemodMode.USB    -> SsbDemodulator(isUsb = true, primitives = primitives)
        DemodMode.LSB    -> SsbDemodulator(isUsb = false, primitives = primitives)
        DemodMode.CW     -> CwDemodulator(reverse = false)
        DemodMode.CWR    -> CwDemodulator(reverse = true)
        DemodMode.DSB    -> DsbDemodulator()
        else             -> RawDemodulator()
    }
}
