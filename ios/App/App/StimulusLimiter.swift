import Foundation

/// iOS twin of StimulusLimiter.java — the SAFETY ENVELOPE for wrist stimuli.
/// KEEP IN SYNC with StimulusLimiter.java and the STIM_LIMITS/stimCheck logic in www/index.html.
///
/// Firing over direct BLE bypasses every limit the official Pavlok app enforces, and nothing else
/// bounds anything, so the limits live here — enforced at the single chokepoint BleManager.write.
/// It is experimental hygiene as much as safety: uncontrolled dosing makes responseSec
/// uninterpretable.
///
/// Intensity is deliberately UNCAPPED (0-100; the firmware itself rejects >100) — the effective
/// level varies enough between individuals that a fixed ceiling would floor the stimulus for some
/// wearers. The FREQUENCY limits do the real safety work.
final class StimulusLimiter {
    static let shared = StimulusLimiter()
    private init() {}

    enum Channel { case vibe, zap }

    static let ZAP_HARD_MAX_INTENSITY = 100
    static let ZAP_MAX_INTENSITY      = 100
    static let ZAP_MIN_INTERVAL_SEC   = 8       // refractory between EVENTS
    static let ZAP_MAX_PER_RIDE       = 5
    static let ZAP_MAX_PER_DAY        = 20
    // Dose ceiling WITHIN one event: a pattern is several writes, so the per-event caps bound how
    // OFTEN the wearer is stimulated but say nothing about how much per stimulus.
    static let ZAP_MAX_PULSES_PER_EVENT = 8
    static let VIBE_MIN_INTERVAL_MS: Double = 400   // debounce only

    struct Decision {
        let allowed: Bool
        let intensity: Int      // possibly clamped below what was requested
        let reason: String      // "ok", or why it was blocked / clamped — must be logged, not dropped
        var describe: String { "\(allowed ? "allow" : "BLOCK") intensity=\(intensity) (\(reason))" }
    }

    private let d = UserDefaults.standard
    private let kDay = "noris_zapDayIndex"
    private let kCount = "noris_zapCountToday"

    private var lastZapAt: Date = .distantPast
    private var lastVibeAt: Date = .distantPast
    private var zapsThisRide = 0

    /// Reset the per-ride budget. Called when tracking starts (a FRESH ride only — resetting on a
    /// resurrection would let a kill cycle mint unlimited zaps, same reasoning as Android).
    func startRide() { zapsThisRide = 0 }

    /// Local-day index, rolling at local midnight.
    private func dayIndex() -> Int {
        let now = Date().timeIntervalSince1970
        let offset = Double(TimeZone.current.secondsFromGMT())
        return Int((now + offset) / 86400)
    }

    /// Zaps delivered today, rolling over automatically at local midnight.
    func zapsToday() -> Int {
        (d.object(forKey: kDay) as? Int) == dayIndex() ? d.integer(forKey: kCount) : 0
    }

    func check(_ ch: Channel, _ requestedIntensity: Int) -> Decision {
        let now = Date()
        if ch == .vibe {
            if now.timeIntervalSince(lastVibeAt) * 1000 < Self.VIBE_MIN_INTERVAL_MS {
                return Decision(allowed: false, intensity: 0, reason: "vibe debounce")
            }
            return Decision(allowed: true, intensity: max(0, min(100, requestedIntensity)), reason: "ok")
        }
        // ---- ZAP: every cap applies ----
        let sinceZap = now.timeIntervalSince(lastZapAt)
        if sinceZap < Double(Self.ZAP_MIN_INTERVAL_SEC) {
            let wait = Double(Self.ZAP_MIN_INTERVAL_SEC) - sinceZap
            return Decision(allowed: false, intensity: 0,
                            reason: String(format: "zap refractory — %.1fs remaining", wait))
        }
        if zapsThisRide >= Self.ZAP_MAX_PER_RIDE {
            return Decision(allowed: false, intensity: 0,
                            reason: "zap per-ride cap reached (\(Self.ZAP_MAX_PER_RIDE))")
        }
        if zapsToday() >= Self.ZAP_MAX_PER_DAY {
            return Decision(allowed: false, intensity: 0,
                            reason: "zap daily cap reached (\(Self.ZAP_MAX_PER_DAY))")
        }
        let capped = max(1, min(requestedIntensity, min(Self.ZAP_MAX_INTENSITY, Self.ZAP_HARD_MAX_INTENSITY)))
        let why = capped < requestedIntensity ? "ok (intensity clamped \(requestedIntensity)->\(capped))" : "ok"
        return Decision(allowed: true, intensity: capped, reason: why)
    }

    /// Gate a whole PATTERN as ONE event. Run per-write, the refractory would truncate every
    /// pattern to its opening pulse — so the frequency caps apply once, and the dose inside the
    /// event is bounded by ZAP_MAX_PULSES_PER_EVENT instead.
    func checkPattern(_ ch: Channel, peakIntensity: Int, totalPulses: Int) -> Decision {
        let dcn = check(ch, peakIntensity)
        guard dcn.allowed, ch == .zap else { return dcn }
        if totalPulses > Self.ZAP_MAX_PULSES_PER_EVENT {
            return Decision(allowed: false, intensity: 0,
                            reason: "pattern exceeds per-event pulse cap (\(totalPulses) > \(Self.ZAP_MAX_PULSES_PER_EVENT))")
        }
        return dcn
    }

    /// Record that a stimulus actually went out. Only call after a successful write.
    func recordFired(_ ch: Channel, intensity: Int) {
        let now = Date()
        if ch == .vibe { lastVibeAt = now; return }
        lastZapAt = now
        zapsThisRide += 1
        let today = (d.object(forKey: kDay) as? Int) == dayIndex() ? d.integer(forKey: kCount) : 0
        d.set(dayIndex(), forKey: kDay)
        d.set(today + 1, forKey: kCount)
    }
}
