package com.norisugoshi.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The stimulus PATTERN LIBRARY and the policy that picks one — the research instrument itself.
 *
 * WHY THIS EXISTS: the wearer habituates to a fixed stimulus and stops noticing it. Varying the
 * pattern is the intervention under study; responseSec drifting upward across exposures is the
 * dependent variable. So a pattern is DATA, not code — it has to be nameable, loggable, and
 * countable, or the CSV cannot distinguish one condition from another.
 *
 * WHAT THE HARDWARE ACTUALLY GIVES US (established 2026-07-23 by probing — see memory
 * pavlok-ble-protocol). Zap is a 2-byte payload, so there is no timing/on/off the way vibe has:
 *   1. count      1-63, a REAL repeat counter — N pulses from ONE write, played by the band's
 *                 own firmware. This is the valuable one: it needs no app timing, so it works
 *                 while the phone is asleep.
 *   2. intensity  1-100 (the limiter caps well below that).
 *   3. gap        between steps — app-sequenced, and the ONLY dimension that needs us awake.
 *
 * DESIGN RULE: prefer single-step patterns. A one-step pattern is played entirely by the band
 * and is immune to the app being frozen; a multi-step pattern depends on our Handler surviving.
 * Multi-step patterns are here because crescendo/syncopation are far more perceptually distinct
 * than burst size alone, and distinctness is the whole point — but they are the fragile ones.
 *
 * ⚠️ The band REFUSES a stimulus while another is still playing (it lights a red LED and reports
 * nothing over BLE). Every gap must therefore exceed the previous step's playback time. See
 * estimatedPlaybackMs().
 */
public final class StimulusPattern {

    /** One step = one BLE write, then a gap before the next. */
    public static final class Step {
        public final int count;         // 1-63 pulses in this burst
        public final int intensityPct;  // % of the wearer's configured strength (100 = as set)
        public final int gapMsAfter;    // silence after this step (0 on the last step)
        public Step(int count, int intensityPct, int gapMsAfter) {
            this.count = count; this.intensityPct = intensityPct; this.gapMsAfter = gapMsAfter;
        }
    }

    public final String id;
    public final String labelJa;
    public final String labelEn;
    public final List<Step> steps;

    private StimulusPattern(String id, String labelJa, String labelEn, Step... steps) {
        this.id = id; this.labelJa = labelJa; this.labelEn = labelEn;
        this.steps = Arrays.asList(steps);
    }

    /** Total pulses across every step — what the limiter bounds as the dose of one event. */
    public int totalPulses() {
        int n = 0;
        for (Step s : steps) n += s.count;
        return n;
    }

    /** The highest intensity percentage any step asks for. */
    public int peakIntensityPct() {
        int m = 0;
        for (Step s : steps) m = Math.max(m, s.intensityPct);
        return m;
    }

    /** Zap refuses large bursts: count=8 delivered a single pulse, count=5 delivered all five. */
    public static final int MAX_COUNT = 5;

    /**
     * How long one step occupies the band. MEASURED 2026-07-23, not estimated: timestamping the
     * per-delivery `0a` reports on 0x2002 gave a rock-steady 600ms between pulses (count=5 ->
     * gaps of 600, 599, 600, 601).
     *
     * The earlier 260ms guess was wrong by 2.3x, and everything downstream of it was wrong too:
     * `burst` read as a slow metronome rather than a rapid run, and `longshort` sent its second
     * write 1560ms into a burst that really runs 2400ms, colliding and losing four of its five
     * pulses. The band refuses a write while playing and reports nothing over BLE, so this was
     * invisible until measured.
     *
     * The +150 is margin. Underestimating here silently truncates patterns; overestimating only
     * makes them slightly longer.
     */
    /**
     * ⚠️ THE TWO CHANNELS RUN AT DIFFERENT SPEEDS — this must stay channel-aware.
     * ZAP: 600ms, measured exactly (count=5 -> gaps of 600, 599, 600, 601).
     * VIBE: bracketed to 800-1100ms; a 4-pulse vibe burst ran 3.2-4.4s. Vibe carries `on`/`off`
     * bytes (0x16, 0x16) that zap does not, and they evidently set a slower period. There is no
     * per-delivery report on vibe (heartbeats only), so it cannot be measured as precisely —
     * hence the UPPER bound: underestimating silently truncates a pattern, overestimating only
     * makes it longer. Treating both channels as 600ms is what merged longshort's fifth pulse
     * into its fourth on vibrate.
     */
    public static final int ZAP_PULSE_MS  = 600;
    public static final int VIBE_PULSE_MS = 1100;

    public static int estimatedPlaybackMs(int count, String channel) {
        int pulse = "zap".equals(channel) ? ZAP_PULSE_MS : VIBE_PULSE_MS;
        return 150 + count * pulse;
    }

    // ------------------------------------------------------------------ the library
    // Chosen for PERCEPTUAL distinctness, not numeric difference: two patterns separated by 10ms
    // are one experimental condition, not two. Each varies burst size, amplitude shape, or
    // rhythm — the three axes the hardware actually exposes.
    // ⚠️ EVERY PULSE IS 600ms APART — there is no such thing as a "rapid" burst on this hardware.
    // The only things a pattern can vary are how many pulses a group has, how long the silence
    // between groups is, and (vibe-style intensity scaling aside) how loud each group is.
    //
    // A group boundary works out to `750 + gap` ms regardless of count, so a gap must be well
    // clear of the natural 600ms tick to read as a boundary at all. The original gaps of
    // 180-620ms were all SHORTER than the tick, which inverted the intended rhythm: `double`'s
    // "quick tap-tap" was actually slower than `burst`'s "rapid run". Gaps below ~250 are
    // therefore pointless, and the ones here are chosen against the measured tick.
    public static final StimulusPattern SINGLE = new StimulusPattern(
            "single", "単発", "Single",
            new Step(1, 100, 0));                       // 0.75s

    public static final StimulusPattern DOUBLE = new StimulusPattern(
            "double", "二連", "Double tap",
            new Step(1, 100, 750), new Step(1, 100, 0));   // 1.5s boundary

    public static final StimulusPattern BURST = new StimulusPattern(
            "burst", "連続", "Burst",
            new Step(5, 100, 0));       // single write, survives a frozen WebView; 5 x 600ms

    public static final StimulusPattern CRESCENDO = new StimulusPattern(
            "crescendo", "漸強", "Crescendo",
            new Step(1, 40, 750), new Step(1, 70, 750), new Step(2, 100, 0));

    public static final StimulusPattern SYNCOPATED = new StimulusPattern(
            "syncopated", "変則", "Syncopated",       // uneven on purpose: 1.0s then 2.2s
            new Step(1, 100, 250), new Step(2, 100, 1450), new Step(1, 100, 0));

    public static final StimulusPattern LONG_SHORT = new StimulusPattern(
            "longshort", "長短", "Long-short",
            new Step(4, 100, 750), new Step(1, 100, 0));

    /** Every pattern, in a stable order — the rotating policy depends on this order not changing. */
    public static final List<StimulusPattern> ALL = Arrays.asList(
            SINGLE, DOUBLE, BURST, CRESCENDO, SYNCOPATED, LONG_SHORT);

    /** Pool for the VARYING policies (RANDOM / ROTATING). SINGLE (one pulse) is EXCLUDED: it is the
     * BASELINE stimulus and belongs only to FIXED — injecting a trivial one-pulse alert into the
     * anti-habituation conditions would weaken exactly what they test. Order stable (ROTATING relies on it). */
    public static final List<StimulusPattern> VARYING = Arrays.asList(
            DOUBLE, BURST, CRESCENDO, SYNCOPATED, LONG_SHORT);

    public static StimulusPattern byId(String id) {
        for (StimulusPattern p : ALL) if (p.id.equals(id)) return p;
        return SINGLE;
    }

    // ------------------------------------------------------------------ selection policy
    // The policy IS the independent variable:
    //   FIXED    — same pattern every alert. The CONTROL. responseSec should drift upward here.
    //   RANDOM   — drawn from the pool, never repeating the previous one. The intervention.
    //   ROTATING — deterministic cycle. Varied but learnable; may habituate to the sequence,
    //              which is an interesting third result rather than a flaw.
    public enum Policy { FIXED, RANDOM, ROTATING }

    private static final String PREFS     = "norisugoshi_pattern";
    private static final String K_ROTATE  = "rotateIndex";
    private static final String K_LAST    = "lastPatternId";
    private static final String K_EXP     = "exposures_";   // + patternId
    private static final Random RNG = new Random();

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * How many times this pattern has already been delivered. THE key covariate: habituation is a
     * function of prior exposures to a stimulus, not of calendar time. Without this column you
     * cannot tell "habituated" from "tired today".
     */
    public static int exposures(Context ctx, String patternId) {
        return prefs(ctx).getInt(K_EXP + patternId, 0);
    }

    public static void recordExposure(Context ctx, String patternId) {
        SharedPreferences p = prefs(ctx);
        p.edit().putInt(K_EXP + patternId, p.getInt(K_EXP + patternId, 0) + 1)
                .putString(K_LAST, patternId).apply();
    }

    /**
     * Pick the pattern for the next alert.
     * @param fixedId which pattern the FIXED policy uses (the control condition's stimulus)
     */
    public static StimulusPattern select(Context ctx, Policy policy, String fixedId) {
        if (policy == Policy.FIXED) return byId(fixedId);

        SharedPreferences p = prefs(ctx);
        if (policy == Policy.ROTATING) {
            int i = p.getInt(K_ROTATE, 0) % VARYING.size();
            p.edit().putInt(K_ROTATE, (i + 1) % VARYING.size()).apply();
            return VARYING.get(i);
        }

        // RANDOM, excluding the immediately previous pattern so "varied" is actually varied —
        // a repeat would be indistinguishable from the fixed condition for that exposure.
        String last = p.getString(K_LAST, "");
        List<StimulusPattern> pool = new ArrayList<>(VARYING);
        if (pool.size() > 1) {
            for (int i = 0; i < pool.size(); i++) {
                if (pool.get(i).id.equals(last)) { pool.remove(i); break; }
            }
        }
        return pool.get(RNG.nextInt(pool.size()));
    }

    public static Policy policyOf(String s) {
        if ("fixed".equalsIgnoreCase(s))    return Policy.FIXED;
        // "repeating" is the current id; "rotating" is the old one, still accepted so params
        // or CSV rows written before the rename keep parsing.
        if ("repeating".equalsIgnoreCase(s) || "rotating".equalsIgnoreCase(s)) return Policy.ROTATING;
        return Policy.RANDOM;
    }

    @Override public String toString() {
        return id + "(" + steps.size() + " steps, " + totalPulses() + " pulses)";
    }
}
