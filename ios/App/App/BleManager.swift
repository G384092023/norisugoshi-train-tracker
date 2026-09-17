import Foundation
import CoreBluetooth

/// Process-wide CoreBluetooth central holding ONE connection to the Pavlok 3 — the iOS twin of
/// BleManager.java. With the `bluetooth-central` background mode in Info.plist, writes keep
/// working while backgrounded, so the wrist stimulus fires from the background like on Android.
///
/// PROTOCOL (see memory pavlok-ble-protocol — verified on-device):
///   stimulus service 156e1000-…   vibe 0x1001   zap 0x1003
///   byte 0: bit7 0x80 = EXECUTE (fire now), bit6 0x40 = COMMIT (persist; we NEVER set it),
///           bits 0-5 = count (repeat counter). We fire execute-without-commit, like the app.
///   vibe payload = [count|0x80, timing 0x0c, intensity 0-100, on 0x16, off 0x16]
///   zap  payload = [count|0x80, intensity 0-100]  (EXACTLY 2 bytes; longer is rejected)
///   button events service 156e2000- / 0x2002: `05 <button|flags>` — HIGH NIBBLE picks the
///           button (0x1_ vibrate, 0x2_ zap, 0x4_ beep). Cancel is bound to the BEEP button.
final class BleManager: NSObject {
    static let shared = BleManager()

    private static let stimSvc  = CBUUID(string: "156e1000-a300-4fea-897b-86f698d74461")
    private static let vibeUUID = CBUUID(string: "00001001-0000-1000-8000-00805f9b34fb")
    private static let zapUUID  = CBUUID(string: "00001003-0000-1000-8000-00805f9b34fb")
    private static let evtSvc   = CBUUID(string: "156e2000-a300-4fea-897b-86f698d74461")
    private static let evtUUID  = CBUUID(string: "00002002-0000-1000-8000-00805f9b34fb")

    private static let BTN_MASK = 0xF0
    private static let BTN_BEEP = 0x40

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var vibeChar: CBCharacteristic?
    private var zapChar: CBCharacteristic?
    private var pendingConnectId: UUID?
    private var connectCallback: ((Bool, String) -> Void)?

    /// Serial queue that sequences a pattern's steps (a pattern is several timed writes).
    private let patternQ = DispatchQueue(label: "noris-pattern", qos: .userInitiated)
    private var patternGen = 0     // bump to cancel any pattern still in flight

    var onDisconnected: (() -> Void)?
    /// The beep button was pressed — TrackingManager treats it like tapping 起きた.
    var onBandButton: (() -> Void)?
    /// Why the last write was blocked / clamped — surfaced so the experiment log can record it.
    private(set) var lastDecision = ""

    private override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: nil,
                                   options: [CBCentralManagerOptionRestoreIdentifierKey: "norisugoshi-ble"])
    }

    var isConnected: Bool { peripheral?.state == .connected && vibeChar != nil }

    func connect(_ deviceId: String, _ done: @escaping (Bool, String) -> Void) {
        guard let uuid = UUID(uuidString: deviceId) else { done(false, "bad device id"); return }
        connectCallback = done
        pendingConnectId = uuid
        if central.state == .poweredOn { doConnect(uuid) }
    }

    private func doConnect(_ uuid: UUID) {
        guard let p = central.retrievePeripherals(withIdentifiers: [uuid]).first else {
            connectCallback?(false, "peripheral not found (scan again in setup)"); connectCallback = nil
            return
        }
        peripheral = p
        p.delegate = self
        central.connect(p, options: nil)
    }

    // MARK: - encoding

    /// Bytes for ONE step: `count` pulses at `intensity`, EXECUTE set, COMMIT clear.
    private func encodeStep(channel: String, intensity: Int, count: Int) -> Data {
        let b0 = UInt8((count & 0x3F) | 0x80)
        if channel == "zap" { return Data([b0, UInt8(clamping: intensity)]) }
        return Data([b0, 0x0c, UInt8(clamping: intensity), 0x16, 0x16])
    }

    private func rawWrite(_ data: Data, to char: CBCharacteristic) {
        guard let p = peripheral, p.state == .connected else { return }
        let type: CBCharacteristicWriteType =
            char.properties.contains(.write) ? .withResponse : .withoutResponse
        p.writeValue(data, for: char, type: type)
    }

    // MARK: - simple write (clear/off, or a single-channel one-shot)

    /// value 0 = clear/off, 5..100 = percent. Off bypasses the limiter (silencing must never block).
    @discardableResult
    func write(_ value: Int, channel: String = "vibe") -> Bool {
        guard isConnected else { return false }
        let isZap = channel == "zap"
        if isZap, zapChar == nil { lastDecision = "zap unavailable"; return false }
        let intensity = value <= 0 ? 0 : max(5, min(100, value))
        if intensity == 0 { lastDecision = "clear"; return true }  // vibe is momentary; nothing to send

        let ch: StimulusLimiter.Channel = isZap ? .zap : .vibe
        let d = StimulusLimiter.shared.check(ch, intensity)
        lastDecision = d.describe
        guard d.allowed else { return false }
        guard let target = isZap ? zapChar : vibeChar else { return false }
        rawWrite(encodeStep(channel: channel, intensity: d.intensity, count: 1), to: target)
        StimulusLimiter.shared.recordFired(ch, intensity: d.intensity)
        return true
    }

    // MARK: - pattern playback (the research instrument)

    /// Play a stimulus PATTERN, gated ONCE as a single event, then its steps sequenced on a queue.
    /// Native owns this because WKWebView suspends when backgrounded — a JS-timed pattern would
    /// collapse to nothing while the wearer is asleep. Steps are scheduled at playbackMs(previous)
    /// + gap because the band refuses a write while it is still playing.
    @discardableResult
    func playPattern(_ pattern: StimulusPattern, strength: Int, channel: String) -> Bool {
        guard isConnected else { return false }
        let isZap = channel == "zap"
        if isZap, zapChar == nil { lastDecision = "zap unavailable"; return false }

        let base = max(5, min(100, strength))
        let peak = base * pattern.peakIntensityPct / 100
        let ch: StimulusLimiter.Channel = isZap ? .zap : .vibe
        let d = StimulusLimiter.shared.checkPattern(ch, peakIntensity: peak, totalPulses: pattern.totalPulses)
        lastDecision = "\(pattern.id): \(d.describe)"
        guard d.allowed else { return false }

        // d.intensity is the approved CEILING; steps scale under it so the amplitude SHAPE
        // survives clamping (a crescendo must still be a crescendo after the limiter trims it).
        let ceiling = d.intensity
        guard let target = isZap ? zapChar : vibeChar else { return false }

        patternGen += 1
        let gen = patternGen
        var at = 0
        for s in pattern.steps {
            let count = max(1, min(StimulusPattern.MAX_COUNT, s.count))
            let intensity = max(1, ceiling * s.intensityPct / 100)
            let data = encodeStep(channel: channel, intensity: intensity, count: count)
            patternQ.asyncAfter(deadline: .now() + .milliseconds(at)) { [weak self] in
                guard let self = self, self.patternGen == gen, self.isConnected else { return }
                self.rawWrite(data, to: target)
            }
            at += StimulusPattern.playbackMs(count, channel) + s.gapMsAfter
        }
        StimulusLimiter.shared.recordFired(ch, intensity: ceiling)
        return true
    }

    /// Cancel any pattern still being sequenced (e.g. the wearer acknowledged).
    func cancelPattern() { patternGen += 1 }

    func disconnect() {
        cancelPattern()
        if let p = peripheral { central.cancelPeripheralConnection(p) }
        peripheral = nil; vibeChar = nil; zapChar = nil
    }
}

extension BleManager: CBCentralManagerDelegate, CBPeripheralDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn, let uuid = pendingConnectId, peripheral == nil {
            doConnect(uuid)
        }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        if let ps = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral], let p = ps.first {
            peripheral = p
            p.delegate = self
            if p.state == .connected { p.discoverServices([Self.stimSvc, Self.evtSvc]) }
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([Self.stimSvc, Self.evtSvc])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        connectCallback?(false, error?.localizedDescription ?? "connect failed"); connectCallback = nil
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        vibeChar = nil; zapChar = nil
        onDisconnected?()
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        for s in peripheral.services ?? [] {
            if s.uuid == Self.stimSvc { peripheral.discoverCharacteristics([Self.vibeUUID, Self.zapUUID], for: s) }
            if s.uuid == Self.evtSvc  { peripheral.discoverCharacteristics([Self.evtUUID], for: s) }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        for c in service.characteristics ?? [] {
            switch c.uuid {
            case Self.vibeUUID: vibeChar = c
            case Self.zapUUID:  zapChar = c
            case Self.evtUUID:  peripheral.setNotifyValue(true, for: c)   // band button events
            default: break
            }
        }
        // "connected" once the primary (vibrate) characteristic is in hand; zap/events are optional.
        if service.uuid == Self.stimSvc {
            if vibeChar != nil { connectCallback?(true, "ok") }
            else { connectCallback?(false, "vibrate characteristic not found") }
            connectCallback = nil
        }
    }

    /// Band button press: `05 <button|flags>`, high nibble = which button. Cancel is the BEEP
    /// button only — the zap button fires a real shock on every press and vibrate buzzes, so
    /// binding cancel to either would mean stopping a stimulus delivers another.
    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == Self.evtUUID, let v = characteristic.value, v.count >= 2 else { return }
        guard v[0] == 0x05, Int(v[1]) & Self.BTN_MASK == Self.BTN_BEEP else { return }
        cancelPattern()
        write(0)                 // silence the device
        onBandButton?()          // TrackingManager: acknowledge like 起きた
    }
}
