package com.norisugoshi.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * NapAlarm — schedules an EXACT, Doze-exempt alarm for the nap-mode stimulus. At fire time the
 * receiver brings the app to the foreground and turns the screen on; the WebView's existing
 * napFire() then does the real work (BLE, re-fire, timer). Native owns only the reliable wake —
 * the one thing a JS setTimeout can't do while the app is suspended.
 *
 * atMs is passed as a STRING (JS numbers bridge as doubles; a ms epoch is large, so a string
 * avoids any precision/typing surprises).
 */
@CapacitorPlugin(name = "NapAlarm")
public class NapAlarmPlugin extends Plugin {

    private static final int REQ = 7301;

    private PendingIntent firePendingIntent() {
        Intent i = new Intent(getContext(), NapAlarmReceiver.class).setAction(NapAlarmReceiver.ACTION_FIRE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(getContext(), REQ, i, flags);
    }

    @PluginMethod
    public void schedule(PluginCall call) {
        String atStr = call.getString("atMs");
        if (atStr == null) { call.reject("atMs required"); return; }
        long atMs;
        try { atMs = Long.parseLong(atStr); } catch (Exception e) { call.reject("bad atMs"); return; }

        AlarmManager am = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
        if (am == null) { call.reject("no AlarmManager"); return; }
        PendingIntent pi = firePendingIntent();
        try {
            // setAlarmClock: exact, exempt from Doze, and treated as a user-facing alarm (allowed
            // to turn the screen on). This is what alarm-clock apps use.
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(atMs, pi), pi);
            call.resolve();
        } catch (SecurityException e) {
            call.reject("exact alarm not permitted: " + e.getMessage());
        }
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        AlarmManager am = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(firePendingIntent());
        call.resolve();
    }
}
