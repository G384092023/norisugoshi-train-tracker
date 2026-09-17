package com.norisugoshi.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.TimeZone;

/**
 * SAFETY ENVELOPE for wrist stimuli — the thing that stands between the experiment and the wearer.
 *
 * WHY THIS EXISTS: firing over direct BLE bypasses every limit the official Pavlok app enforces
 * (intensity ceiling, refractory period, daily quota). Nothing else in our stack bounds anything,
 * so those limits have to live here. This is also experimental hygiene, not just safety —
 * uncontrolled dosing makes the responseSec data uninterpretable, because you can no longer tell
 * a habituation effect from a "that ride happened to zap nine times" effect.
 *
 * WHY IT LIVES BEHIND BleManager.write(): that is the single chokepoint every layer funnels
 * through (setup buttons, foreground JS, and the background TrackingService). Putting the gate
 * anywhere else would leave a path around it.
 *
 * DESIGN NOTE — suppressed stimuli MUST still be recorded. If the limiter blocks a zap and we
 * silently drop it, the response-time log misattributes the wearer's reaction to a stimulus that
 * never happened. Decision.reason is meant to be logged, not swallowed.
 *
 * The Pavlok firmware separately rejects intensity > 100 (ATT error 0x80), but that is a device
 * quirk, not our safety limit — never rely on it. The caps below are the real bound.
 */
public final class StimulusLimiter {

    private static final String TAG = "NorisugoshiStim";
    private static final StimulusLimiter I = new StimulusLimiter();
    public static StimulusLimiter get() { return I; }
    private StimulusLimiter() {}

    public enum Channel { VIBE, ZAP }

    // ---------------------------------------------------------------- the caps
    // ZAP is the constrained one. VIBE is left comparatively free: it is a motor buzz, it is what
    // the escalating per-stop feedback already uses, and throttling it would change existing
    // behaviour that is known to work.
    //
    // HARD_* are absolute ceilings that no setting, config or future caller may exceed. The
    // non-hard values are the operating defaults.
    // Intensity is deliberately UNCAPPED (full 0-100), at the researcher's decision 2026-07-24:
    // the effective level varies enough between individuals that a fixed ceiling would floor the
    // stimulus for some wearers and make the habituation data incomparable. 100 is the device's
    // own maximum — the firmware rejects anything above it.
    // The FREQUENCY limits below are untouched and still do the real safety work: they bound how
    // OFTEN a stimulus can arrive and how many pulses one event may contain.
    public static final int  ZAP_HARD_MAX_INTENSITY = 100;
    public static final int  ZAP_MAX_INTENSITY      = 100;
    // Expressed in SECONDS — this is a human-facing dosing parameter, and 8 reads plainly where
    // 8000 invites a units mistake. Converted to ms only at the comparison.
    public static final int  ZAP_MIN_INTERVAL_SEC   = 8;     // refractory period between EVENTS
    public static final int  ZAP_MAX_PER_RIDE       = 5;
    public static final int  ZAP_MAX_PER_DAY        = 20;
    // Dose ceiling WITHIN one event. A pattern is several writes, so the per-event caps above
    // bound how OFTEN the wearer is stimulated but say nothing about how much per stimulus.
    // Without this, a 6-step burst pattern would deliver dozens of pulses inside one "event".
    public static final int  ZAP_MAX_PULSES_PER_EVENT = 8;

    public static final long VIBE_MIN_INTERVAL_MS   = 400;   // only debounces double-fires

    // ---------------------------------------------------------------- persisted state
    // The per-DAY zap count must survive process death (EMUI kills us aggressively), so it goes to
    // SharedPreferences. Per-ride and interval state are in-memory: a process restart mid-ride is
    // rare, and the daily cap still backstops it.
    private static final String PREFS    = "norisugoshi_stim";
    private static final String K_DAY    = "zapDayIndex";
    private static final String K_COUNT  = "zapCountToday";

    private Context appCtx;
    private volatile long lastZapAt  = 0L;
    private volatile long lastVibeAt = 0L;
    private volatile int  zapsThisRide = 0;

    /** Call once with any Context (BleManager does this on connect). */
    public void attach(Context ctx) {
        if (ctx != null && appCtx == null) appCtx = ctx.getApplicationContext();
    }

    /** Reset the per-ride budget. Called when tracking starts. */
    public void startRide() {
        zapsThisRide = 0;
        Log.i(TAG, "ride started — zap budget reset to " + ZAP_MAX_PER_RIDE);
    }

    /** Local-day index; avoids java.time (API 26 / desugaring) and handles the JST offset. */
    private static int dayIndex() {
        long now = System.currentTimeMillis();
        return (int) ((now + TimeZone.getDefault().getOffset(now)) / 86400000L);
    }

    private SharedPreferences prefs() {
        return (appCtx == null) ? null : appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Zaps delivered today, rolling over automatically at local midnight. */
    public int zapsToday() {
        SharedPreferences p = prefs();
        if (p == null) return 0;
        return (p.getInt(K_DAY, -1) == dayIndex()) ? p.getInt(K_COUNT, 0) : 0;
    }

    /** The outcome of a limiter check. `reason` is for the log, and must not be discarded. */
    public static final class Decision {
        public final boolean allowed;
        public final int     intensity;   // possibly clamped below what was requested
        public final String  reason;      // "ok", or why it was blocked / clamped
        Decision(boolean allowed, int intensity, String reason) {
            this.allowed = allowed; this.intensity = intensity; this.reason = reason;
        }
        @Override public String toString() {
            return (allowed ? "allow" : "BLOCK") + " intensity=" + intensity + " (" + reason + ")";
        }
    }

    /**
     * Decide whether a stimulus may fire, and at what intensity.
     * Does NOT record the firing — call recordFired() only if the write actually succeeded, so a
     * failed BLE write doesn't consume the wearer's budget.
     */
    public synchronized Decision check(Channel ch, int requestedIntensity) {
        long now = System.currentTimeMillis();

        if (ch == Channel.VIBE) {
            if (now - lastVibeAt < VIBE_MIN_INTERVAL_MS) {
                return new Decision(false, 0, "vibe debounce (<" + VIBE_MIN_INTERVAL_MS + "ms)");
            }
            return new Decision(true, Math.max(0, Math.min(100, requestedIntensity)), "ok");
        }

        // ---- ZAP: every cap applies ----
        long refractoryMs = ZAP_MIN_INTERVAL_SEC * 1000L;
        if (now - lastZapAt < refractoryMs) {
            double wait = (refractoryMs - (now - lastZapAt)) / 1000.0;
            return new Decision(false, 0,
                    String.format(java.util.Locale.US, "zap refractory — %.1fs remaining", wait));
        }
        if (zapsThisRide >= ZAP_MAX_PER_RIDE) {
            return new Decision(false, 0, "zap per-ride cap reached (" + ZAP_MAX_PER_RIDE + ")");
        }
        if (zapsToday() >= ZAP_MAX_PER_DAY) {
            return new Decision(false, 0, "zap daily cap reached (" + ZAP_MAX_PER_DAY + ")");
        }

        int capped = Math.min(requestedIntensity, Math.min(ZAP_MAX_INTENSITY, ZAP_HARD_MAX_INTENSITY));
        capped = Math.max(1, capped);
        String why = (capped < requestedIntensity)
                ? "ok (intensity clamped " + requestedIntensity + "->" + capped + ")" : "ok";
        return new Decision(true, capped, why);
    }

    /**
     * Gate a whole PATTERN as one stimulus event.
     *
     * WHY THIS IS SEPARATE FROM check(): a pattern is several writes a few hundred ms apart. Run
     * per-write, the refractory period would block every step after the first and silently
     * truncate every pattern to its opening pulse — the caps would quietly destroy the
     * experiment rather than protect the wearer. So the frequency caps apply ONCE per event, and
     * the dose inside the event is bounded by ZAP_MAX_PULSES_PER_EVENT instead.
     *
     * @param peakIntensity the highest intensity any step will ask for (0-100)
     * @param totalPulses   summed pulse count across all steps
     * @return allowed + the intensity CEILING every step must be scaled under
     */
    public synchronized Decision checkPattern(Channel ch, int peakIntensity, int totalPulses) {
        Decision d = check(ch, peakIntensity);          // frequency caps + intensity clamp
        if (!d.allowed || ch != Channel.ZAP) return d;

        if (totalPulses > ZAP_MAX_PULSES_PER_EVENT) {
            return new Decision(false, 0,
                    "pattern exceeds per-event pulse cap (" + totalPulses
                            + " > " + ZAP_MAX_PULSES_PER_EVENT + ")");
        }
        return d;
    }

    /** Record that a stimulus actually went out. Only call after a successful write. */
    public synchronized void recordFired(Channel ch, int intensity) {
        long now = System.currentTimeMillis();
        if (ch == Channel.VIBE) { lastVibeAt = now; return; }

        lastZapAt = now;
        zapsThisRide++;
        SharedPreferences p = prefs();
        if (p != null) {
            int today = (p.getInt(K_DAY, -1) == dayIndex()) ? p.getInt(K_COUNT, 0) : 0;
            p.edit().putInt(K_DAY, dayIndex()).putInt(K_COUNT, today + 1).apply();
            Log.i(TAG, "zap fired at intensity " + intensity
                    + " — ride " + zapsThisRide + "/" + ZAP_MAX_PER_RIDE
                    + ", today " + (today + 1) + "/" + ZAP_MAX_PER_DAY);
        }
    }

    /** Human-readable budget, for the debug panel. */
    public String status() {
        return "zap: ride " + zapsThisRide + "/" + ZAP_MAX_PER_RIDE
             + ", today " + zapsToday() + "/" + ZAP_MAX_PER_DAY
             + ", max intensity " + Math.min(ZAP_MAX_INTENSITY, ZAP_HARD_MAX_INTENSITY);
    }
}
