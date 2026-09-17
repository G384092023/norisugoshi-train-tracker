package com.norisugoshi.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.UUID;

/**
 * Process-wide BLE connection to the haptic device (Nordic UART).
 *
 * WHY A SINGLETON: the connection must outlive both the WebView (which freezes when the
 * app sleeps) and any single component. The setup "Connect/Test" buttons AND the background
 * TrackingService both write through this one connection — so the wrist buzzes on alert even
 * while the app is backgrounded / screen off.
 *
 * We connect by MAC address (obtained from the JS plugin's requestDevice, which already
 * works for scanning), so there's no scanning logic here.
 */
public class BleManager {
    private static final String TAG = "NorisugoshiBle";
    private static final BleManager I = new BleManager();
    public static BleManager get() { return I; }
    private BleManager() {}

    // ---- the wrist device ----
    // The rest of the app speaks one universal intent: write(0) = off, write(5..100) = percent.
    // The custom Arduino/ESP32 "Norisugoshi band" profile was removed 2026-07-24 — the study runs
    // on the Pavlok, and a second half-maintained profile was only a source of drift.
    // Pavlok 3 — custom service 156e1000-… with short-form stimulus characteristics
    // vibe 0x1001 / beep 0x1002 / zap 0x1003. The vibrate is a MOMENTARY pulse, so write(0) is a no-op.
    // Payload CONFIRMED 2026-07-23: decrypted the official app's own writes with an nRF52840
    // sniffer (fresh LE Legacy pairing) and reproduced them from a PC — the band buzzed.
    //   [count | 0x80, timing, intensity(0-100 DECIMAL), on, off]
    //   81 0c 64 16 16 = vibrate 100%   ·   81 0c 32 16 16 = vibrate 50%
    // ⚠️ The 0x80 bit on byte 0 is the EXECUTE flag. Writing count=01 without it only STORES
    // the pattern (char reads back 01 0c 64 16 16) and the motor never runs — the reason every
    // earlier attempt appeared to succeed but did nothing.
    public static final UUID PAVLOK_SVC  = UUID.fromString("156e1000-a300-4fea-897b-86f698d74461"); // stimulus service
    public static final UUID PAVLOK_CHAR = UUID.fromString("00001001-0000-1000-8000-00805f9b34fb"); // vibrate (0x1001)
    public static final UUID PAVLOK_ZAP  = UUID.fromString("00001003-0000-1000-8000-00805f9b34fb"); // zap (0x1003)
    // ⚠️ 0x2002 lives in a DIFFERENT vendor service (156e2000), not the stimulus one (156e1000).
    // Looking it up under 156e1000 silently returns null.
    // Pressing the band's physical button emits `05 <param>` here — captured 2026-07-24, with a
    // counter also ticking on 0x2004. That is how the wearer cancels a stimulus from the band.
    public static final UUID PAVLOK_EVT_SVC = UUID.fromString("156e2000-a300-4fea-897b-86f698d74461");
    public static final UUID PAVLOK_EVENTS  = UUID.fromString("00002002-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final byte BTN_OPCODE = 0x05;
    // `05 <button|flags>` — the HIGH NIBBLE identifies which of the band's three buttons was
    // pressed (mapped 2026-07-24 by pressing each in turn):
    //     0x1_ = top / vibrate      0x2_ = mid / zap      0x4_ = bottom / beep
    // Cancel is bound to the BEEP button only. The zap button fires a real shock on every press
    // (it was the sole source of `0a` deliveries in the capture) and the vibrate button buzzes,
    // so binding cancel to either would mean the act of stopping a stimulus delivers another.
    // Acknowledge requires a DOUBLE-press of the beep button (two beep events within
    // DOUBLE_PRESS_MS) — a single half-asleep tap must not cancel the alert, the exact failure
    // this app exists to prevent. Press length (low nibble 0x1 short / 0x3 long) is IGNORED; a
    // double-press shows up as two beep events ~0.4s apart in capture. High nibble picks the button.
    private static final int  BTN_MASK = 0xF0;
    private static final int  BTN_BEEP = 0x40;
    private static final long DOUBLE_PRESS_MS = 700;

    // ⚠️ BYTE 0 CARRIES TWO FLAGS (established 2026-07-23 by store-only probing — see memory
    // pavlok-ble-protocol). Getting these wrong is why the protocol looked broken for months:
    //     bit 7 (0x80) = EXECUTE — fire now
    //     bit 6 (0x40) = COMMIT  — persist this as the band's stored pattern
    //     bits 0-5     = count   (0-63)
    // We always fire with COMMIT CLEAR, exactly as the official app does, so firing never
    // overwrites whatever the wearer has configured on the band itself.
    //
    // ZAP (0x1003) TAKES EXACTLY 2 BYTES — [count|flags, intensity]. Longer payloads are rejected
    // with ATT error 13 (invalid length), so zap has no timing/on/off knobs the way vibe does.
    // count > 1 DOES deliver N shocks on zap — verified on-body 2026-07-23, three ways: the count
    // field, five `0a` reports on 0x2002 per count=5 burst, and the wearer feeling a burst.

    private volatile String kind = "pavlok";     // only device supported (set on connect)

    public interface ConnectCb { void onResult(boolean ok, String msg); }

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic cmdChar;
    private BluetoothGattCharacteristic zapChar;   // Pavlok only; null on other devices
    private volatile boolean connected = false;
    private ConnectCb pendingCb;
    private final Handler timeout = new Handler(Looper.getMainLooper());
    private final Runnable timeoutR = () -> { if (!connected) { closeGatt(); finish(false, "connect timeout"); } };

    public boolean isConnected() { return connected; }

    @SuppressLint("MissingPermission")
    public void connect(Context ctx, String address, String deviceKind, ConnectCb cb) {
        this.kind = (deviceKind == null || deviceKind.isEmpty()) ? "pavlok" : deviceKind;
        pendingCb = cb;
        StimulusLimiter.get().attach(ctx);   // the limiter needs a Context for its daily counter
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = (bm != null) ? bm.getAdapter() : null;
        if (adapter == null) { finish(false, "Bluetooth unavailable"); return; }
        BluetoothDevice dev;
        try { dev = adapter.getRemoteDevice(address); }
        catch (Exception e) { finish(false, "bad address: " + e.getMessage()); return; }
        closeGatt();
        Log.i(TAG, "connecting to " + address);
        try {
            gatt = dev.connectGatt(ctx.getApplicationContext(), false, gattCb, BluetoothDevice.TRANSPORT_LE);
            timeout.postDelayed(timeoutR, 12000);   // don't leave the UI hung if the device is out of range
        } catch (Exception e) {
            finish(false, "connectGatt failed: " + e.getMessage());
        }
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "connected; discovering services");
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "disconnected (status=" + status + ")");
                connected = false; cmdChar = null; zapChar = null;
                NorisugoshiTracker.emitBleDisconnected();
                finish(false, "disconnected");
            }
        }
        // Both overloads: API 33+ delivers the value as a parameter, older versions via getValue().
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) {
            handleNotify(ch, value);
        }
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            handleNotify(ch, ch != null ? ch.getValue() : null);
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            UUID svcUuid = serviceUuid(), charUuid = writeCharUuid();
            if (svcUuid == null || charUuid == null) {
                Log.w(TAG, "no GATT profile for kind=" + kind + " (UUIDs not configured)");
                finish(false, kind + " BLE UUIDs not configured yet — capture them with nRF Connect");
                return;
            }
            BluetoothGattService svc = g.getService(svcUuid);
            cmdChar = (svc != null) ? svc.getCharacteristic(charUuid) : null;
            // Zap is optional: its absence must not fail the connection, since vibrate-only
            // operation is a perfectly valid (and the default) configuration.
            zapChar = (svc != null && "pavlok".equals(kind)) ? svc.getCharacteristic(PAVLOK_ZAP) : null;
            if ("pavlok".equals(kind)) subscribeButton(g);
            if (cmdChar != null) {
                connected = true;
                Log.i(TAG, "ready (kind=" + kind + ", characteristic found)");
                finish(true, "connected");
            } else {
                Log.w(TAG, "characteristic not found for kind=" + kind);
                finish(false, "write characteristic not found on device (kind=" + kind + ")");
            }
        }
    };

    /**
     * Subscribe to 0x2002 — the band's event channel. Carries BOTH the physical button presses
     * (so the wearer can cancel at the wrist) AND the `0a` per-pulse delivery reports (the
     * proof-of-fire oracle). Best-effort: its absence must never fail the connection, since
     * everything else still works — we just lose wrist-cancel and hardware delivery proof.
     */
    @SuppressLint("MissingPermission")
    private void subscribeButton(BluetoothGatt g) {
        try {
            BluetoothGattService evt = g.getService(PAVLOK_EVT_SVC);
            BluetoothGattCharacteristic ev = (evt != null) ? evt.getCharacteristic(PAVLOK_EVENTS) : null;
            if (ev == null) { Log.w(TAG, "events characteristic not found; button cancel disabled"); return; }
            g.setCharacteristicNotification(ev, true);
            BluetoothGattDescriptor d = ev.getDescriptor(CCCD);
            if (d == null) { Log.w(TAG, "no CCCD on events; button cancel disabled"); return; }
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            } else {
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(d);
            }
            Log.i(TAG, "subscribed to band button events");
        } catch (Exception e) {
            Log.w(TAG, "button subscribe failed: " + e.getMessage());
        }
    }

    /**
     * A button press means STOP: kill any pattern still being sequenced, silence the device, and
     * acknowledge the alert exactly as the on-screen 起きた button would.
     *
     * SELF-FIRE: a self-paced capture of 6 presses produced ZERO stimulus deliveries (no `0a`),
     * so pressing to cancel does not normally shock the wearer — no 0x1007 reconfiguration is
     * needed. An earlier capture DID show a `0a` after each press, but those carried param `21`
     * while the clean run carried `41`/`43`; the high nibble evidently tracks some band state we
     * have not decoded, so the self-fire is not fully ruled out in every state.
     *
     * PARAM (byte 1), from the clean run: `41` on a short press, `43` on a long hold (one
     * sample), and a double-press appears as two `41`s ~0.4s apart. ANY press currently cancels.
     * ⚠️ Worth reconsidering: a short press cancelling an alert is easy to do half-asleep, which
     * is exactly the failure this app exists to prevent. Requiring `43` (long press) would make
     * acknowledgement a deliberate act — but `43` has only been observed once, so confirm it
     * before relying on it.
     */
    private void handleNotify(BluetoothGattCharacteristic ch, byte[] value) {
        if (ch == null || value == null || value.length < 1) return;
        if (!PAVLOK_EVENTS.equals(ch.getUuid())) return;

        // ── 0x0a = per-pulse DELIVERY report (the oracle): HARDWARE proof a stimulus pulse fired.
        // The band emits one per delivered pulse, so a count=5 burst produces five reports. We tally
        // them for the current alert and report to JS (deliveredPulses / skinContact in the CSV).
        // A "fired" stimulus that produced ZERO 0a reports never reached the wrist — the exact case
        // a deep sleeper can't self-report.
        if ((value[0] & 0xFF) == 0x0a) {
            deliveredPulses++;
            // byte 6 is the skin-contact flag in the captures — UNVERIFIED across firmware states,
            // so confirm on-device; the raw report is logged for exactly that.
            if (value.length > 6) lastSkinContact = (value[6] != 0);
            Log.i(TAG, "delivery 0a #" + deliveredPulses + " skin=" + lastSkinContact + " raw=" + toHex(value));
            NorisugoshiTracker.emitDelivered(deliveredPulses, lastSkinContact);
            return;
        }

        // ── 0x05 = physical BUTTON press. Acknowledge requires a DOUBLE-press of the beep button
        // (two beep events within DOUBLE_PRESS_MS); a single press is logged and ignored so a
        // half-asleep tap can't cancel the alert. Press length is irrelevant here — any two beep
        // events close together count. The in-app 起きた button + notification stay guaranteed paths.
        if (value[0] != BTN_OPCODE || value.length < 2) return;
        int button = value[1] & BTN_MASK;
        if (button != BTN_BEEP) {
            Log.i(TAG, "band button 0x" + Integer.toHexString(button) + " ignored (cancel is the beep button)");
            return;
        }
        long now = System.currentTimeMillis();
        if (lastBeepAtMs != 0 && (now - lastBeepAtMs) <= DOUBLE_PRESS_MS) {
            lastBeepAtMs = 0;   // consumed — a following press starts a fresh pair
            Log.i(TAG, "beep DOUBLE-press -> cancelling stimulus and acknowledging");
            cancelPattern();
            write(0);
            TrackingService.onBandButton();
        } else {
            lastBeepAtMs = now;   // first press — wait for the second
            Log.i(TAG, "beep press 1/2 (0x" + Integer.toHexString(value[1] & 0xFF) + ") — waiting for a second press");
        }
    }

    private static String toHex(byte[] v) {
        StringBuilder sb = new StringBuilder();
        for (byte b : v) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    /** The primary service that holds the write characteristic for the current device kind. */
    private UUID serviceUuid()   { return PAVLOK_SVC; }
    /** The characteristic we write signals to for the current device kind. */
    private UUID writeCharUuid() { return PAVLOK_CHAR; }

    // ---------------------------------------------------------------- pattern playback
    private final Handler patternH = new Handler(Looper.getMainLooper());

    // ── delivery oracle (HARDWARE proof-of-fire) ──────────────────────────────────────────────
    // Running tally of `0a` delivery reports the band emits for the CURRENT alert, plus its last
    // skin-contact flag. Reset at the start of each pattern (below) so the count belongs to one
    // alert, then surfaced to JS for the CSV (deliveredPulses / skinContact).
    private int deliveredPulses = 0;
    private boolean lastSkinContact = false;
    private long lastBeepAtMs = 0;   // timestamp of the last beep press, for double-press detection

    /**
     * Play a stimulus PATTERN — the research instrument. Gated ONCE as a single event (see
     * StimulusLimiter.checkPattern), then its steps are sequenced on a Handler.
     *
     * WHY SEQUENCING LIVES HERE AND NOT IN JS: the background TrackingService fires alerts while
     * the WebView is frozen — which is exactly when the wearer is asleep on the train. A pattern
     * timed by JS would silently collapse to nothing in the only situation that matters.
     *
     * ⚠️ Each step is scheduled at estimatedPlaybackMs(previous) + gap, because the band REFUSES
     * a write while it is still playing and reports that only on its LED. Overlap is undetectable
     * in software, so the schedule must be conservative by construction.
     *
     * @param strength the wearer's configured 1..10
     * @return false if the limiter refused the event (reason in lastDecision())
     */
    public boolean playPattern(StimulusPattern pattern, int strength, String channel) {
        if (gatt == null || cmdChar == null || !connected) return false;
        boolean isZap = "zap".equals(channel);
        if (isZap && zapChar == null) {
            lastDecision = "zap unavailable — characteristic not found on this device";
            Log.w(TAG, lastDecision);
            return false;
        }

        // `strength` is the wearer's setting as a PERCENT (5-100 in steps of 5), not the old
        // 1-10 step. Finer granularity was needed because a 10-point scale could not express
        // values like 35, and individual sensitivity varies more finely than that.
        int base = Math.max(1, Math.min(100, strength));
        int peak = base * pattern.peakIntensityPct() / 100;
        StimulusLimiter.Channel ch = isZap ? StimulusLimiter.Channel.ZAP
                                           : StimulusLimiter.Channel.VIBE;
        StimulusLimiter.Decision d =
                StimulusLimiter.get().checkPattern(ch, peak, pattern.totalPulses());
        lastDecision = pattern.id + ": " + d;
        if (!d.allowed) { Log.i(TAG, "pattern suppressed: " + d.reason); return false; }

        deliveredPulses = 0; lastSkinContact = false;   // new alert → fresh delivery tally (oracle)

        // d.intensity is the approved CEILING; every step scales under it, preserving the
        // pattern's amplitude SHAPE (a crescendo must stay a crescendo after clamping).
        final int ceiling = d.intensity;
        final BluetoothGattCharacteristic target = isZap ? zapChar : cmdChar;

        patternH.removeCallbacksAndMessages(null);   // a new pattern supersedes any in flight
        long at = 0;
        for (StimulusPattern.Step s : pattern.steps) {
            // MAX_COUNT, not 63: zap delivered a single pulse for count=8 while count=5 played
            // in full, so large bursts are refused at playback with no error over BLE.
            final int count = Math.max(1, Math.min(StimulusPattern.MAX_COUNT, s.count));
            final int intensity = Math.max(1, ceiling * s.intensityPct / 100);
            patternH.postDelayed(() -> {
                if (!connected) return;                 // band went away mid-pattern
                writeRaw(target, encodeStep(channel, intensity, count));
            }, at);
            at += StimulusPattern.estimatedPlaybackMs(count, channel) + s.gapMsAfter;
        }

        StimulusLimiter.get().recordFired(ch, ceiling);
        Log.i(TAG, "pattern " + pattern + " on " + channel + " ceiling=" + ceiling
                + " span=" + at + "ms");
        return true;
    }

    /** Cancel any pattern still being sequenced (e.g. the wearer acknowledged). */
    public void cancelPattern() { patternH.removeCallbacksAndMessages(null); }

    /**
     * Encode ONE step: `count` pulses at `intensity`, with EXECUTE set and COMMIT clear.
     * count lives in bits 0-5 of byte 0 and is a real repeat counter played by the band itself.
     */
    private byte[] encodeStep(String channel, int intensity, int count) {
        byte b0 = (byte) ((count & 0x3F) | 0x80);     // execute, do not commit
        if ("zap".equals(channel)) return new byte[]{ b0, (byte) intensity };
        return new byte[]{ b0, (byte) 0x0c, (byte) intensity, (byte) 0x16, (byte) 0x16 };
    }

    /**
     * Turn an intensity on the universal 0-100 scale into this device's payload bytes for `channel`.
     * Returns null when there is nothing to send (e.g. Pavlok "off" — its vibrate is momentary).
     */
    private byte[] encode(String channel, int intensity) {
        if (intensity <= 0) return null;                       // momentary pulse: nothing to turn off
        if ("zap".equals(channel)) {
            // EXACTLY 2 bytes — longer payloads are rejected (ATT error 13). Execute set,
            // commit clear, count 1.
            return new byte[]{ (byte) 0x81, (byte) intensity };
        }
        // vibe: [count|0x80 = fire, timing, intensity, on, off]
        return new byte[]{ (byte) 0x81, (byte) 0x0c, (byte) intensity, (byte) 0x16, (byte) 0x16 };
    }

    /** Why the last write was blocked or clamped — surfaced so the experiment log can record it. */
    private volatile String lastDecision = "";
    public String lastDecision() { return lastDecision; }

    /** Send a signal on the default (vibrate) channel: 0 = clear/off, 1..10 = alert strength. */
    public boolean write(int value) { return write(value, "vibe"); }

    /**
     * Send a signal on `channel` ("vibe" or "zap"): 0 = clear/off, 1..10 = alert strength.
     * Safe to call from any thread.
     *
     * EVERY actuating write passes StimulusLimiter first — this is the chokepoint, and the gate
     * is deliberately here rather than in the callers so no future caller can route around it.
     * Clear/off (value <= 0) bypasses the limiter: silencing a device must never be blockable.
     */
    @SuppressLint("MissingPermission")
    public boolean write(int value, String channel) {
        if (gatt == null || cmdChar == null || !connected) return false;

        boolean isZap = "zap".equals(channel);
        if (isZap && zapChar == null) {
            lastDecision = "zap unavailable — characteristic not found on this device";
            Log.w(TAG, lastDecision);
            return false;
        }

        // Universal 0-100 intensity scale (what the limiter and the Pavlok protocol both speak).
        // `value` is now that percent directly — 0 = off, 5..100 = intensity.
        int intensity = value <= 0 ? 0 : Math.max(1, Math.min(100, value));

        if (intensity > 0) {
            StimulusLimiter.Channel ch = isZap ? StimulusLimiter.Channel.ZAP
                                               : StimulusLimiter.Channel.VIBE;
            StimulusLimiter.Decision d = StimulusLimiter.get().check(ch, intensity);
            lastDecision = d.toString();
            if (!d.allowed) { Log.i(TAG, "suppressed: " + d.reason); return false; }
            intensity = d.intensity;     // may have been clamped below what was asked for
        } else {
            lastDecision = "clear";
        }

        BluetoothGattCharacteristic target = isZap ? zapChar : cmdChar;
        byte[] data = encode(channel, intensity);
        if (data == null) return true;      // nothing to send (e.g. Pavlok "off" is a no-op)
        boolean ok = writeRaw(target, data);
        // Only a write that actually went out consumes the wearer's budget.
        if (ok && intensity > 0) {
            StimulusLimiter.get().recordFired(
                    isZap ? StimulusLimiter.Channel.ZAP : StimulusLimiter.Channel.VIBE, intensity);
        }
        return ok;
    }

    @SuppressLint("MissingPermission")
    private boolean writeRaw(BluetoothGattCharacteristic target, byte[] data) {
        // match the characteristic's supported write type (UART RX is often write-no-response)
        int props = target.getProperties();
        int type = ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0)
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                int r = gatt.writeCharacteristic(target, data, type);
                return r == BluetoothStatusCodes.SUCCESS;
            } else {
                target.setWriteType(type);
                target.setValue(data);
                return gatt.writeCharacteristic(target);
            }
        } catch (Exception e) {
            Log.w(TAG, "write failed: " + e.getMessage());
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    public void disconnect() {
        connected = false; cmdChar = null; zapChar = null;
        if (gatt != null) { try { gatt.disconnect(); } catch (Exception ignored) {} }
        closeGatt();
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        if (gatt != null) { try { gatt.close(); } catch (Exception ignored) {} gatt = null; }
    }

    private void finish(boolean ok, String msg) {
        timeout.removeCallbacks(timeoutR);
        ConnectCb cb = pendingCb; pendingCb = null;
        if (cb != null) cb.onResult(ok, msg);
    }
}
