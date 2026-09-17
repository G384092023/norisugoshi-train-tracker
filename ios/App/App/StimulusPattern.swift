import Foundation

/// iOS twin of StimulusPattern.java — the stimulus pattern LIBRARY and the policy that picks one.
/// KEEP IN SYNC with StimulusPattern.java and the PATTERNS array in www/index.html.
///
/// The wearer habituates to a fixed stimulus and stops noticing it; varying the pattern is the
/// intervention under study, and responseSec drifting upward across exposures is the dependent
/// variable. A pattern is DATA, not code — nameable, loggable, countable.
///
/// ⚠️ WKWebView suspends when backgrounded (like the Android WebView), so — exactly as on Android
/// — the NATIVE side owns playback: JS timers cannot sequence a multi-step pattern while asleep.
/// Single-write patterns (single, burst) are the robust ones; multi-step patterns depend on real
/// background execution, which iOS restricts harder than Android.
struct StimulusPattern {

    struct Step {
        let count: Int          // 1-5 pulses in this burst (MAX_COUNT)
        let intensityPct: Int   // % of the wearer's configured strength
        let gapMsAfter: Int     // silence after this step (0 on the last)
    }

    let id: String
    let ja: String
    let en: String
    let steps: [Step]

    var totalPulses: Int { steps.reduce(0) { $0 + $1.count } }
    var peakIntensityPct: Int { steps.map(\.intensityPct).max() ?? 0 }

    // MARK: - hardware timing (measured 2026-07-23; see memory pavlok-ble-protocol)
    // Zap refuses large bursts: count=8 delivered one pulse, count=5 delivered all five.
    static let MAX_COUNT = 5
    // ⚠️ THE TWO CHANNELS RUN AT DIFFERENT SPEEDS. ZAP 600ms (measured exactly). VIBE ~800-1100ms
    // (bracketed; a 4-pulse vibe burst ran 3.2-4.4s) — use the UPPER bound, since underestimating
    // silently truncates a pattern while overestimating only makes it longer.
    static let ZAP_PULSE_MS  = 600
    static let VIBE_PULSE_MS = 1100

    static func playbackMs(_ count: Int, _ channel: String) -> Int {
        150 + count * (channel == "zap" ? ZAP_PULSE_MS : VIBE_PULSE_MS)
    }

    // MARK: - the library (order is STABLE — the repeating policy depends on it)
    // Every pulse is 600ms apart on zap; a group boundary is `750 + gap` regardless of count, so
    // gaps are chosen well clear of the tick. Gaps below ~250 are pointless.
    static let all: [StimulusPattern] = [
        StimulusPattern(id: "single",     ja: "単発", en: "Single",
                        steps: [Step(count: 1, intensityPct: 100, gapMsAfter: 0)]),
        StimulusPattern(id: "double",     ja: "二連", en: "Double tap",
                        steps: [Step(count: 1, intensityPct: 100, gapMsAfter: 750),
                                Step(count: 1, intensityPct: 100, gapMsAfter: 0)]),
        StimulusPattern(id: "burst",      ja: "連続", en: "Burst",
                        steps: [Step(count: 5, intensityPct: 100, gapMsAfter: 0)]),
        StimulusPattern(id: "crescendo",  ja: "漸強", en: "Crescendo",
                        steps: [Step(count: 1, intensityPct: 40,  gapMsAfter: 750),
                                Step(count: 1, intensityPct: 70,  gapMsAfter: 750),
                                Step(count: 2, intensityPct: 100, gapMsAfter: 0)]),
        StimulusPattern(id: "syncopated", ja: "変則", en: "Syncopated",
                        steps: [Step(count: 1, intensityPct: 100, gapMsAfter: 250),
                                Step(count: 2, intensityPct: 100, gapMsAfter: 1450),
                                Step(count: 1, intensityPct: 100, gapMsAfter: 0)]),
        StimulusPattern(id: "longshort",  ja: "長短", en: "Long-short",
                        steps: [Step(count: 4, intensityPct: 100, gapMsAfter: 750),
                                Step(count: 1, intensityPct: 100, gapMsAfter: 0)]),
    ]

    static func byId(_ id: String) -> StimulusPattern { all.first { $0.id == id } ?? all[0] }

    // MARK: - selection policy (the independent variable)
    //   fixed     — same pattern every alert (the control; habituation should appear here)
    //   random    — drawn from the pool, never repeating the previous one (the intervention)
    //   repeating — deterministic cycle (was "rotating"; both accepted)
    enum Policy { case fixed, random, repeating }

    static func policyOf(_ s: String) -> Policy {
        switch s.lowercased() {
        case "fixed": return .fixed
        // "repeating" is the current id; "rotating" is the old one, still accepted.
        case "repeating", "rotating": return .repeating
        default: return .random
        }
    }

    // Persisted so exposures survive relaunch — habituation tracks exposure COUNT, not time.
    private static let d = UserDefaults.standard
    private static let kExp = "noris_exposures_"     // + patternId
    private static let kRotate = "noris_rotateIndex"
    private static let kLast = "noris_lastPatternId"

    static func exposures(_ id: String) -> Int { d.integer(forKey: kExp + id) }

    static func recordExposure(_ id: String) {
        d.set(exposures(id) + 1, forKey: kExp + id)
        d.set(id, forKey: kLast)
    }

    /// Pick the pattern for the next alert. `fixedId` is the control condition's stimulus.
    static func select(policy: Policy, fixedId: String) -> StimulusPattern {
        switch policy {
        case .fixed:
            return byId(fixedId)
        case .repeating:
            let i = d.integer(forKey: kRotate) % all.count
            d.set((i + 1) % all.count, forKey: kRotate)
            return all[i]
        case .random:
            // exclude the immediately previous pattern, or "varied" would be indistinguishable
            // from the fixed condition for that exposure.
            let last = d.string(forKey: kLast) ?? ""
            let pool = all.count > 1 ? all.filter { $0.id != last } : all
            return pool.randomElement() ?? all[0]
        }
    }
}
