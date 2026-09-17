import Foundation
import Capacitor

/// iOS implementation of the NorisugoshiTracker Capacitor plugin — the SAME JS API the
/// Android NorisugoshiTracker.java exposes, so www/index.html runs unchanged:
///   start / stop / acknowledge / requestBatteryExemption
///   bleConnect / bleWrite / bleDisconnect
///   events: alert { stops, firedAt, source } · suppressed { lateMs, count } · bleDisconnected
/// (acknowledged-from-notification is Android-only for now — see BUILD_IOS.md.)
@objc(NorisugoshiTrackerPlugin)
public class NorisugoshiTrackerPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "NorisugoshiTrackerPlugin"
    public let jsName = "NorisugoshiTracker"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "start", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stop", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "acknowledge", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestBatteryExemption", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "bleConnect", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "bleWrite", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "bleDisconnect", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "firePattern", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stimStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "napArm", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "napAck", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "napCancel", returnType: CAPPluginReturnPromise),
    ]

    public override func load() {
        TrackingManager.shared.onAlert = { [weak self] data in
            self?.notifyListeners("alert", data: data)
        }
        TrackingManager.shared.onNapFired = { [weak self] data in
            self?.notifyListeners("napFired", data: data, retainUntilConsumed: true)
        }
        TrackingManager.shared.onSuppressed = { [weak self] lateMs, count in
            self?.notifyListeners("suppressed", data: ["lateMs": lateMs, "count": count], retainUntilConsumed: true)
        }
        TrackingManager.shared.onAcknowledged = { [weak self] in
            self?.notifyListeners("acknowledged", data: ["ackedAt": Date().timeIntervalSince1970 * 1000],
                                  retainUntilConsumed: true)
        }
        BleManager.shared.onDisconnected = { [weak self] in
            self?.notifyListeners("bleDisconnected", data: [:])
        }
    }

    @objc func start(_ call: CAPPluginCall) {
        TrackingManager.shared.start(params: (call.options as? [String: Any]) ?? [:])
        call.resolve()
    }

    @objc func stop(_ call: CAPPluginCall) {
        TrackingManager.shared.stop()
        call.resolve()
    }

    @objc func acknowledge(_ call: CAPPluginCall) {
        TrackingManager.shared.acknowledge()
        call.resolve()
    }

    /// Doze exemption is an Android concept — resolve as already-exempt on iOS.
    @objc func requestBatteryExemption(_ call: CAPPluginCall) {
        call.resolve(["ignoring": true])
    }

    // MARK: BLE

    @objc func bleConnect(_ call: CAPPluginCall) {
        guard let address = call.getString("address"), !address.isEmpty else {
            call.reject("no address"); return
        }
        BleManager.shared.connect(address) { ok, msg in
            ok ? call.resolve() : call.reject(msg)
        }
    }

    @objc func bleWrite(_ call: CAPPluginCall) {
        let channel = call.getString("channel") ?? "vibe"
        let ok = BleManager.shared.write(call.getInt("value") ?? 0, channel: channel)
        // A refused write is usually the safety limiter doing its job — hand the reason back.
        ok ? call.resolve() : call.reject("write not sent: " + BleManager.shared.lastDecision)
    }

    /// Play one pattern immediately — the settings-screen preview. Does NOT count an exposure
    /// (only a real alert does); the safety limiter still applies in full.
    @objc func firePattern(_ call: CAPPluginCall) {
        let id = call.getString("patternId") ?? "single"
        let strength = call.getInt("strength") ?? 70
        let channel = call.getString("channel") ?? "vibe"
        let ok = BleManager.shared.playPattern(StimulusPattern.byId(id), strength: strength, channel: channel)
        ok ? call.resolve() : call.reject("not sent: " + BleManager.shared.lastDecision)
    }

    /// Live stimulus budget, so the settings screen shows the REAL numbers on native.
    @objc func stimStatus(_ call: CAPPluginCall) {
        call.resolve([
            "zapsToday": StimulusLimiter.shared.zapsToday(),
            "maxPerDay": StimulusLimiter.ZAP_MAX_PER_DAY,
            "maxPerRide": StimulusLimiter.ZAP_MAX_PER_RIDE,
        ])
    }

    @objc func bleDisconnect(_ call: CAPPluginCall) {
        BleManager.shared.disconnect()
        call.resolve()
    }

    // MARK: nap mode (train-independent background stimulus)

    @objc func napArm(_ call: CAPPluginCall) {
        TrackingManager.shared.napArm(
            delayMs:   call.getDouble("delayMs")  ?? 0,
            patternId: call.getString("patternId") ?? "single",
            strength:  call.getInt("strength")     ?? 70,
            channel:   call.getString("channel")   ?? "vibe",
            refireMs:  call.getDouble("refireMs")  ?? 0,
            maxFires:  call.getInt("maxFires")     ?? 1)
        call.resolve()
    }

    @objc func napAck(_ call: CAPPluginCall) {
        TrackingManager.shared.napAck()
        call.resolve()
    }

    @objc func napCancel(_ call: CAPPluginCall) {
        TrackingManager.shared.napCancel()
        call.resolve()
    }
}
