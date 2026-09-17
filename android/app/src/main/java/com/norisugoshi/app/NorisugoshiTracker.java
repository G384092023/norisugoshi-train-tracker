package com.norisugoshi.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Capacitor bridge to the native TrackingService + BleManager.
 *
 * JS API (window.Capacitor.Plugins.NorisugoshiTracker):
 *   start({ proxyBase, railway, trainNumber, destTitle, alertN, stopSeq, titleSeq,
 *           backstopAt, hapticStrength })
 *                                      // backstopAt (optional, epoch ms): timetable-predicted alert
 *                                      // time — native schedules a setAlarmClock backstop at it
 *                                      // hapticStrength (optional, 1–10): wristband buzz amplitude
 *   acknowledge()                      // user pressed "I'm awake" → stop buzzing + BLE off
 *   stop()
 *   bleConnect({ address, kind })      // connect by MAC (from JS requestDevice); kind picks the device profile
 *   bleWrite({ value })                // 0 = clear off, 1..10 = alert strength (encoded per device kind)
 *   bleDisconnect()
 *   addListener("alert", cb)           // native fired the alert → cb({ stops, firedAt })
 *   addListener("bleDisconnected", cb) // the haptic device dropped
 *   addListener("suppressed", cb)      // OEM froze the poll loop → cb({ lateMs, count })
 *   addListener("stopped", cb)         // user tapped ⏹ 停止 on the ongoing notification
 */
@CapacitorPlugin(name = "NorisugoshiTracker")
public class NorisugoshiTracker extends Plugin {

    private static NorisugoshiTracker self;   // so the background service can emit events to JS

    @Override
    public void load() { self = this; }

    /** Called from TrackingService (background thread) when the alert fires.
     *  source: "poll" (live position) or "backstop" (timetable alarm) — logged in the
     *  experiment CSV so the analysis can distinguish the alert path. */
    public static void emitAlert(int stops, long firedAt, String source) {
        emitAlert(stops, firedAt, source, "", "", 0, "");
    }

    /**
     * @param patternId  which stimulus pattern fired — the experiment's independent variable
     * @param policy     how it was chosen (fixed | random | rotating)
     * @param exposures  how many times this pattern had been delivered BEFORE this one. The key
     *                   covariate: habituation is a function of prior exposures, not of calendar
     *                   time, so without it you cannot separate habituation from a bad night.
     * @param decision   the limiter's verdict — a SUPPRESSED stimulus must reach the log too, or
     *                   the wearer's reaction gets attributed to a stimulus that never happened.
     */
    public static void emitAlert(int stops, long firedAt, String source,
                                 String patternId, String policy, int exposures, String decision) {
        if (self == null) return;
        JSObject data = new JSObject();
        data.put("stops", stops);
        data.put("firedAt", firedAt);
        data.put("source", source);
        data.put("patternId", patternId);
        data.put("patternPolicy", policy);
        data.put("exposures", exposures);
        data.put("stimDecision", decision);
        self.notifyListeners("alert", data);
    }

    /** Called from BleManager when the device drops. */
    public static void emitBleDisconnected() {
        if (self != null) self.notifyListeners("bleDisconnected", new JSObject());
    }

    /**
     * Called when the user taps 「起きた！」 on the alert NOTIFICATION (no app UI needed).
     * Retained: the WebView may be frozen — on resume, JS logs the response time using
     * `ackedAt` (the actual tap time), keeping the experiment data accurate.
     */
    public static void emitAcknowledged() {
        if (self == null) return;
        JSObject data = new JSObject();
        data.put("ackedAt", System.currentTimeMillis());
        self.notifyListeners("acknowledged", data, true);
    }

    /**
     * Called when the user taps ⏹ 停止 on the ONGOING notification. Without this the WebView
     * JS keeps its own poll loop + tracking UI running (it has no idea the service died) —
     * the "停止 pressed but still polling" bug. Retained: if the WebView is frozen, it
     * consumes the event on resume and resets the UI then.
     */
    public static void emitStopped() {
        if (self == null) return;
        self.notifyListeners("stopped", new JSObject(), true);
    }

    /**
     * Called from TrackingService when it detects the OEM froze the poll loop (poll ran
     * far later than scheduled). Retained until consumed: the WebView JS is itself frozen
     * while the screen is off, so the event must survive until the user reopens the app —
     * then the UI shows "background restricted — please whitelist" guidance.
     */
    public static void emitSuppressed(long lateMs, int count) {
        if (self == null) return;
        JSObject data = new JSObject();
        data.put("lateMs", lateMs);
        data.put("count", count);
        self.notifyListeners("suppressed", data, true);
    }

    /**
     * Called from BleManager on each `0a` delivery report — HARDWARE proof a stimulus pulse
     * physically fired. Carries the running delivered-pulse count for the current alert plus the
     * band's skin-contact flag, so JS can tag the response record (deliveredPulses / skinContact
     * in the CSV). Retained so a frozen WebView still gets the final tally on resume.
     */
    public static void emitDelivered(int pulses, boolean skinContact) {
        if (self == null) return;
        JSObject data = new JSObject();
        data.put("pulses", pulses);
        data.put("skinContact", skinContact);
        self.notifyListeners("delivered", data, true);
    }

    @PluginMethod
    public void start(PluginCall call) {
        Intent i = new Intent(getContext(), TrackingService.class);
        i.putExtra("params", call.getData().toString());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getContext().startForegroundService(i);
        else getContext().startService(i);
        call.resolve();
    }

    @PluginMethod
    public void acknowledge(PluginCall call) {
        if (TrackingService.instance != null) TrackingService.instance.acknowledge();
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        // deliberate stop → wipe the persisted session so no watchdog alarm can resurrect it
        if (TrackingService.instance != null) TrackingService.instance.clearSession();
        else getContext().getSharedPreferences("norisugoshi", Context.MODE_PRIVATE).edit().clear().apply();
        getContext().stopService(new Intent(getContext(), TrackingService.class));
        call.resolve();
    }

    /**
     * Ask Android to exempt the app from Doze / battery optimization. Critical on aggressive
     * OEMs (Huawei/Xiaomi/Oppo) that otherwise freeze the background poll loop. Returns
     * { ignoring: bool } — true if already exempt; if not, it pops the system dialog.
     * NOTE: Huawei EMUI also needs MANUAL "App launch → Manage manually" (no API for that).
     */
    @PluginMethod
    public void requestBatteryExemption(PluginCall call) {
        JSObject r = new JSObject();
        boolean ignoring = false;
        try {
            PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
            String pkg = getContext().getPackageName();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm != null) {
                ignoring = pm.isIgnoringBatteryOptimizations(pkg);
                if (!ignoring) {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:" + pkg));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(i);
                }
            } else {
                ignoring = true;   // pre-Android 6 has no Doze
            }
        } catch (Exception e) {
            // some OEMs block this intent; the user must whitelist manually (documented)
        }
        r.put("ignoring", ignoring);
        call.resolve(r);
    }

    // ---- BLE ----
    @PluginMethod
    public void bleConnect(PluginCall call) {
        String address = call.getString("address", "");
        String kind = call.getString("kind", "pavlok");    // only device profile supported
        if (address == null || address.isEmpty()) { call.reject("no address"); return; }
        BleManager.get().connect(getContext(), address, kind, (ok, msg) -> {
            if (ok) call.resolve();
            else call.reject(msg);
        });
    }

    @PluginMethod
    public void bleWrite(PluginCall call) {
        int value = call.getInt("value", 0);
        String channel = call.getString("channel", "vibe");   // "vibe" (default) or "zap"
        boolean ok = BleManager.get().write(value, channel);
        // A refused write is usually the safety limiter doing its job, not a fault — hand the
        // reason back so the experiment log can record that the stimulus was suppressed.
        if (ok) call.resolve();
        else call.reject("write not sent: " + BleManager.get().lastDecision());
    }

    /**
     * Play one stimulus pattern immediately — the settings-screen preview.
     *
     * Deliberately does NOT touch StimulusPattern.recordExposure(): previewing a pattern from the
     * settings screen must not inflate the exposure count the habituation analysis depends on.
     * Only TrackingService, firing a real alert, records an exposure. The safety limiter still
     * applies in full — a previewed zap is a real zap.
     */
    @PluginMethod
    public void firePattern(PluginCall call) {
        String id       = call.getString("patternId", "single");
        int    strength = call.getInt("strength", 70);   // percent, 5-100
        String channel  = call.getString("channel", "vibe");
        boolean ok = BleManager.get().playPattern(StimulusPattern.byId(id), strength, channel);
        if (ok) call.resolve();
        else call.reject("not sent: " + BleManager.get().lastDecision());
    }

    /**
     * Live stimulus budget, so the settings screen can show the REAL numbers on native.
     * Without this the UI read its own localStorage counter, which the native path never
     * increments — it displayed "0 zaps today" no matter how many had actually been delivered.
     */
    @PluginMethod
    public void stimStatus(PluginCall call) {
        JSObject r = new JSObject();
        r.put("zapsToday", StimulusLimiter.get().zapsToday());
        r.put("maxPerDay", StimulusLimiter.ZAP_MAX_PER_DAY);
        r.put("maxPerRide", StimulusLimiter.ZAP_MAX_PER_RIDE);
        call.resolve(r);
    }

    @PluginMethod
    public void bleDisconnect(PluginCall call) {
        BleManager.get().disconnect();
        call.resolve();
    }
}
