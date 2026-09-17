import Foundation
import CoreLocation
import UserNotifications
import AudioToolbox

/// iOS twin of TrackingService.java — same contract, different keep-alive mechanics.
///
/// KEEPING NATIVE DUMB (same as Android): JS hands us a precomputed `stopSeq` (ordered
/// station IDs current→destination); we match the live `fromStation` and count what remains.
///
/// iOS parity layers (vs the Android foreground-service + wake lock + alarms):
///  · Layer 1 — live tracking: the `location` background mode keeps the process running
///    (navigation-app pattern; justified — position = train position, and it feeds the
///    planned GPS hybrid). A DispatchSourceTimer polls ODPT every 20–60 s, adaptively.
///  · Layer 2 — backstop: a scheduled local notification at `backstopAt` (timetable +
///    delay). iOS delivers scheduled notifications even if the app is suspended or
///    force-quit — same guarantee setAlarmClock gives us on Android. While we ARE alive,
///    an in-process check fires the FULL alert (incl. BLE buzz) at that moment instead.
final class TrackingManager: NSObject {
    static let shared = TrackingManager()

    // adaptive polling (matches Android)
    private let FAR_S: TimeInterval = 60
    private let NEAR_S: TimeInterval = 20
    private let MAX_SESSION_S: TimeInterval = 3 * 60 * 60      // hard cutoff
    private let FREEZE_SLACK_S: TimeInterval = 15              // late-poll = suspension detected

    static let backstopNotifId = "norisugoshi-backstop"
    static let alertNotifId    = "norisugoshi-alert"

    // params from JS
    private var proxyBase = "", railway = "", trainNumber = "", destTitle = ""
    private var stopSeq: [String] = [], titleSeq: [String] = []
    private var alertN = 2
    // Wristband amplitude as a PERCENT, 5-100 (was a coarse 1-10 step; parity with Android 2026-07-24).
    private var hapticStrength = 70
    private var backstopAtMs: Double = 0
    // ---- habituation study knobs (from JS params; see StimulusPattern) ----
    // Defaults are the safe, boring ones: vibrate on a fixed single pulse — pre-study behaviour.
    private var stimChannel = "vibe"
    private var patternPolicy = "fixed"
    private var patternFixedId = "single"
    private var lastPatternId = ""
    private var lastStimDecision = ""
    private var lastExposures = 0

    // session state
    private(set) var running = false
    private var lastIdx = 0, pollCount = 0, freezeCount = 0
    private var alerted = false, acked = false
    private var alertedAtMs: Double = 0
    private var startedAt = Date.distantPast
    private var nextDelay: TimeInterval = 20
    private var pollDueAt = Date.distantFuture

    private let q = DispatchQueue(label: "noris-poll", qos: .utility)
    private var timer: DispatchSourceTimer?
    private var locMgr: CLLocationManager?

    // ---- nap mode (train-independent scheduled stimulus) ----
    static let napNotifId = "norisugoshi-nap"
    private var napTimer: DispatchSourceTimer?
    private var napRefireTimer: DispatchSourceTimer?
    private var napFiredAtMs: Double = 0
    private var napFireCount = 0
    private var napPatternId = "single"
    private var napStrength = 70
    private var napChannel = "vibe"
    private var napRefireMs: Double = 0
    private var napMaxFires = 1

    /// The full alert payload (stops, firedAt, source, and the experiment condition) as a dict —
    /// the plugin forwards it straight to the JS "alert" listener.
    var onAlert: (([String: Any]) -> Void)?
    var onSuppressed: ((Double, Int) -> Void)?
    /// The band's beep button was pressed — the plugin emits "acknowledged" to JS.
    var onAcknowledged: (() -> Void)?
    /// The scheduled nap stimulus fired — the plugin emits "napFired" {firedAt} to JS so it can
    /// time responseSec from this instant and (once the user taps 起きた) log the row.
    var onNapFired: (([String: Any]) -> Void)?

    // MARK: - lifecycle

    func start(params: [String: Any]) {
        apply(params: params)
        // A band-button press cancels the stimulus and acknowledges, like tapping 起きた.
        BleManager.shared.onBandButton = { [weak self] in
            self?.acknowledge()
            self?.onAcknowledged?()
        }
        if !running {
            running = true
            startedAt = Date()
            StimulusLimiter.shared.startRide()      // fresh ride → fresh per-ride zap budget
            requestNotificationAuth()
            startLocationKeepAlive()
            scheduleNextPoll(after: 0.5)
        }
        scheduleBackstopNotification()
        NSLog("NorisugoshiSvc: started; stops=%d alertN=%d train=%@", stopSeq.count, alertN, trainNumber)
    }

    private func apply(params: [String: Any]) {
        proxyBase   = params["proxyBase"]   as? String ?? proxyBase
        railway     = params["railway"]     as? String ?? railway
        trainNumber = params["trainNumber"] as? String ?? trainNumber
        destTitle   = params["destTitle"]   as? String ?? destTitle
        alertN      = params["alertN"]      as? Int ?? alertN
        hapticStrength = min(100, max(1, params["hapticStrength"] as? Int ?? hapticStrength))
        stimChannel    = params["stimChannel"]    as? String ?? stimChannel
        patternPolicy  = params["patternPolicy"]  as? String ?? patternPolicy
        patternFixedId = params["patternFixedId"] as? String ?? patternFixedId
        backstopAtMs = params["backstopAt"] as? Double ?? 0
        if let seq = params["stopSeq"] as? [String] { stopSeq = seq }
        if let tit = params["titleSeq"] as? [String] { titleSeq = tit }
        // re-arm for the new route (e.g. user switched trains)
        lastIdx = 0; alerted = false; acked = false; alertedAtMs = 0
    }

    func acknowledge() {
        acked = true
        BleManager.shared.cancelPattern()    // stop any pattern still being sequenced
        BleManager.shared.write(0)
        cancelBackstopNotification()
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [Self.alertNotifId])
        NSLog("NorisugoshiSvc: acknowledged")
    }

    func stop() {
        running = false
        timer?.cancel(); timer = nil
        stopLocationKeepAlive()
        cancelBackstopNotification()
        BleManager.shared.write(0)
        NSLog("NorisugoshiSvc: stopped (polls=%d)", pollCount)
    }

    // MARK: - keep-alive (the iOS "foreground service")

    private func startLocationKeepAlive() {
        DispatchQueue.main.async {
            let m = CLLocationManager()
            m.desiredAccuracy = kCLLocationAccuracyHundredMeters
            m.distanceFilter = 100        // a moving train updates constantly; keep it cheap
            m.allowsBackgroundLocationUpdates = true
            m.pausesLocationUpdatesAutomatically = false
            m.showsBackgroundLocationIndicator = true
            switch m.authorizationStatus {
            case .notDetermined: m.requestWhenInUseAuthorization()
            default: break
            }
            m.startUpdatingLocation()
            self.locMgr = m
        }
    }

    private func stopLocationKeepAlive() {
        DispatchQueue.main.async {
            self.locMgr?.stopUpdatingLocation()
            self.locMgr = nil
        }
    }

    // MARK: - poll loop

    private func scheduleNextPoll(after delay: TimeInterval) {
        timer?.cancel()
        pollDueAt = Date().addingTimeInterval(delay)
        let t = DispatchSource.makeTimerSource(queue: q)
        t.schedule(deadline: .now() + delay)
        t.setEventHandler { [weak self] in self?.pollTick() }
        t.resume()
        timer = t
    }

    private func pollTick() {
        guard running else { return }
        // hard safety cutoff — a forgotten session can never run past this
        if Date().timeIntervalSince(startedAt) > MAX_SESSION_S {
            NSLog("NorisugoshiSvc: session cutoff → stopping")
            stop()
            return
        }
        // suspension self-detection (parity with the Android SUPPRESSION log/event)
        let late = Date().timeIntervalSince(pollDueAt)
        if late > FREEZE_SLACK_S {
            freezeCount += 1
            NSLog("NorisugoshiSvc: SUPPRESSION: poll %d ran %.0fs late (episode %d)", pollCount + 1, late, freezeCount)
            onSuppressed?(late * 1000, freezeCount)
        }
        // in-process backstop: if the timetable moment arrives while we're alive, fire the
        // FULL alert (incl. BLE) — the scheduled notification only covers the suspended case.
        if backstopAtMs > 0 && !alerted && !acked
            && Date().timeIntervalSince1970 * 1000 >= backstopAtMs {
            fireAlert(stops: alertN, source: "backstop")
        }
        pollCount += 1
        pollOnce()
    }

    private func pollOnce() {
        guard !proxyBase.isEmpty, !railway.isEmpty,
              var comps = URLComponents(string: proxyBase + "/api/trains") else {
            scheduleNextPoll(after: NEAR_S); return
        }
        comps.queryItems = [URLQueryItem(name: "railway", value: railway)]
        guard let url = comps.url else { scheduleNextPoll(after: NEAR_S); return }

        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        URLSession.shared.dataTask(with: req) { [weak self] data, _, err in
            guard let self = self else { return }
            self.q.async {
                defer { if self.running { self.scheduleNextPoll(after: self.nextDelay) } }
                guard err == nil, let data = data,
                      let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
                    NSLog("NorisugoshiSvc: poll %d error: %@", self.pollCount, err?.localizedDescription ?? "bad json")
                    self.nextDelay = self.NEAR_S
                    return
                }
                self.process(trains: arr)
            }
        }.resume()
    }

    private func process(trains: [[String: Any]]) {
        // find our train (same matching as Android)
        let train = trains.first { t in
            var no = t["odpt:trainNumber"] as? String ?? ""
            if no.isEmpty, let same = t["owl:sameAs"] as? String {
                no = same.components(separatedBy: ".").last ?? same
            }
            return no == trainNumber
        }
        guard let t = train else {
            NSLog("NorisugoshiSvc: poll %d: train %@ not in feed", pollCount, trainNumber)
            nextDelay = NEAR_S
            return
        }

        let from = t["odpt:fromStation"] as? String ?? ""
        // advance monotonic index (handles spiral repeats; not-found = express skip → keep last)
        if lastIdx < stopSeq.count, let found = stopSeq[lastIdx...].firstIndex(of: from) {
            lastIdx = found
        }
        let stopsToGo = (stopSeq.count - 1) - lastIdx
        NSLog("NorisugoshiSvc: poll %d: from=%@ idx=%d stopsToGo=%d%@",
              pollCount, from.components(separatedBy: ".").last ?? from, lastIdx, stopsToGo,
              alerted ? " [alerted]" : "")

        if stopsToGo <= alertN && !acked {
            if !alerted { fireAlert(stops: stopsToGo, source: "poll") }
            vibrate()                        // keep buzzing each poll until acknowledged
            nextDelay = NEAR_S
        } else {
            nextDelay = (stopsToGo > alertN + 3) ? FAR_S : NEAR_S
        }

        // journey complete: arrived AND acknowledged → auto-stop
        if acked && stopsToGo <= 0 {
            NSLog("NorisugoshiSvc: arrived + acknowledged → auto-stop")
            stop()
        }
    }

    // MARK: - alert

    private func fireAlert(stops: Int, source: String) {
        alerted = true
        alertedAtMs = Date().timeIntervalSince1970 * 1000
        cancelBackstopNotification()         // the live alert supersedes the backstop
        // Stimulus FIRST: it is the primary wake channel and the study's measured variable, so it
        // must not wait behind notification construction and posting.
        fireStimulus()
        postAlertNotification(stops: stops)
        vibrate()
        onAlert?([
            "stops": stops, "firedAt": alertedAtMs, "source": source,
            "patternId": lastPatternId, "patternPolicy": patternPolicy,
            "stimChannel": stimChannel, "exposures": lastExposures, "stimDecision": lastStimDecision,
        ])
        NSLog("NorisugoshiSvc: ALERT fired stopsToGo=%d source=%@ ble=%d",
              stops, source, BleManager.shared.isConnected ? 1 : 0)
    }

    /// Pick a pattern per the study policy, play it, and count the exposure — but ONLY if it
    /// actually fired, so a suppressed stimulus never inflates the exposure count the analysis
    /// depends on. `exposures` is recorded BEFORE this delivery (the covariate).
    private func fireStimulus() {
        let p = StimulusPattern.select(policy: StimulusPattern.policyOf(patternPolicy),
                                       fixedId: patternFixedId)
        lastPatternId = p.id
        lastExposures = StimulusPattern.exposures(p.id)          // before this delivery
        let ok = BleManager.shared.playPattern(p, strength: hapticStrength, channel: stimChannel)
        if ok { StimulusPattern.recordExposure(p.id) }
        lastStimDecision = ok ? "fired" : BleManager.shared.lastDecision
        NSLog("NorisugoshiSvc: stimulus %@ policy=%@ channel=%@ exposures=%d -> %@",
              p.id, patternPolicy, stimChannel, lastExposures, lastStimDecision)
    }

    private func vibrate() {
        guard alerted && !acked else { return }
        AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
    }

    // MARK: - notifications

    private func requestNotificationAuth() {
        UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    private func alertContent(body: String) -> UNMutableNotificationContent {
        let c = UNMutableNotificationContent()
        // Inline-bilingual, matching the Android notifications (the app's single-language toggle
        // lives in JS; a native notification can't read it, so both languages share the line).
        c.title = "乗り過ごし防止 — まもなく到着 / Arriving soon"
        c.body = body
        c.sound = .defaultCritical
        if #available(iOS 15.0, *) { c.interruptionLevel = .timeSensitive }
        return c
    }

    private func postAlertNotification(stops: Int) {
        let body = stops <= 0 ? "\(destTitle) に到着します。降りる準備を · Arriving — get ready"
                              : "あと \(stops) 駅で \(destTitle)。降りる準備を · \(stops) stops to \(destTitle) — get ready"
        let req = UNNotificationRequest(identifier: Self.alertNotifId,
                                        content: alertContent(body: body),
                                        trigger: nil)   // deliver immediately
        UNUserNotificationCenter.current().add(req)
    }

    /// The suspended/force-quit safety net: iOS fires this at the timetable-predicted
    /// alert time no matter what happened to the process.
    private func scheduleBackstopNotification() {
        cancelBackstopNotification()
        let delta = backstopAtMs / 1000 - Date().timeIntervalSince1970
        guard backstopAtMs > 0, delta > 1 else { return }
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: delta, repeats: false)
        let req = UNNotificationRequest(identifier: Self.backstopNotifId,
                                        content: alertContent(body: "まもなく \(destTitle)。降りる準備を（予定時刻）· Approaching \(destTitle) — get ready (timetable)"),
                                        trigger: trigger)
        UNUserNotificationCenter.current().add(req)
        NSLog("NorisugoshiSvc: backstop notification scheduled in %.0fs", delta)
    }

    private func cancelBackstopNotification() {
        UNUserNotificationCenter.current()
            .removePendingNotificationRequests(withIdentifiers: [Self.backstopNotifId])
    }
}

// MARK: - nap mode (train-independent scheduled stimulus)
// JS resolves the pattern (policy/limiter/exposure) and hands us the resolved patternId + timing.
// We keep the process alive with the location spine, fire natively at the set time (so it works
// with the screen off / app backgrounded — the whole point), re-fire on the interval until 起きた,
// and schedule a local-notification backstop so a killed process still wakes the sleeper.
extension TrackingManager {
    func napArm(delayMs: Double, patternId: String, strength: Int, channel: String,
                refireMs: Double, maxFires: Int) {
        napPatternId = patternId
        napStrength  = min(100, max(1, strength))
        napChannel   = channel
        napRefireMs  = max(0, refireMs)
        napMaxFires  = max(1, maxFires)
        napFireCount = 0
        napFiredAtMs = 0
        requestNotificationAuth()
        StimulusLimiter.shared.startRide()          // fresh per-ride zap budget for this nap
        startLocationKeepAlive()                    // stay alive while asleep
        scheduleNapNotification(afterMs: delayMs)   // backstop if the process is killed
        let delay = max(0, delayMs / 1000)
        let t = DispatchSource.makeTimerSource(queue: q)
        t.schedule(deadline: .now() + delay)
        t.setEventHandler { [weak self] in self?.napFire() }
        t.resume()
        napTimer?.cancel(); napTimer = t
        NSLog("NorisugoshiSvc: nap armed, fires in %.0fs (%@)", delay, patternId)
    }

    private func napFire() {
        napFiredAtMs = Date().timeIntervalSince1970 * 1000
        napFireCount = 1
        BleManager.shared.playPattern(StimulusPattern.byId(napPatternId), strength: napStrength, channel: napChannel)
        onNapFired?(["firedAt": napFiredAtMs])       // JS times responseSec from here
        if napRefireMs > 0 { startNapRefire() }
    }

    private func startNapRefire() {
        let t = DispatchSource.makeTimerSource(queue: q)
        t.schedule(deadline: .now() + napRefireMs / 1000, repeating: napRefireMs / 1000)
        t.setEventHandler { [weak self] in
            guard let s = self else { return }
            if s.napFireCount >= s.napMaxFires { s.napRefireTimer?.cancel(); s.napRefireTimer = nil; return }
            let ok = BleManager.shared.playPattern(StimulusPattern.byId(s.napPatternId),
                                                   strength: s.napStrength, channel: s.napChannel)
            if ok { s.napFireCount += 1 } else { s.napRefireTimer?.cancel(); s.napRefireTimer = nil }  // limiter cap → stop
        }
        t.resume()
        napRefireTimer?.cancel(); napRefireTimer = t
    }

    /// 起きた tapped (JS) — stop the escalation and release the keep-alive.
    func napAck() {
        napRefireTimer?.cancel(); napRefireTimer = nil
        napTimer?.cancel(); napTimer = nil
        BleManager.shared.cancelPattern()
        BleManager.shared.write(0)
        cancelNapNotification()
        stopLocationKeepAlive()
    }

    /// Cancelled before firing.
    func napCancel() {
        napTimer?.cancel(); napTimer = nil
        napRefireTimer?.cancel(); napRefireTimer = nil
        cancelNapNotification()
        stopLocationKeepAlive()
        BleManager.shared.write(0)
    }

    private func scheduleNapNotification(afterMs: Double) {
        cancelNapNotification()
        let delta = afterMs / 1000
        guard delta > 1 else { return }
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: delta, repeats: false)
        let req = UNNotificationRequest(identifier: Self.napNotifId,
                                        content: alertContent(body: "起きる時間です · Time to wake up"),
                                        trigger: trigger)
        UNUserNotificationCenter.current().add(req)
    }
    private func cancelNapNotification() {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [Self.napNotifId])
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [Self.napNotifId])
    }
}
