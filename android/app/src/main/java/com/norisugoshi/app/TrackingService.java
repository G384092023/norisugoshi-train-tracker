package com.norisugoshi.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Native foreground service that polls ODPT and tracks the train while the app is
 * backgrounded / screen off — the WebView JS freezes there, native does not.
 *
 * STAGE 1b: real polling + live count + ALERT firing (heads-up notification + vibration)
 *   when the destination is `alertN` stops away. Re-buzzes every poll until acknowledged.
 *   Emits an "alert" event to JS (with the fire timestamp) so the response-time experiment
 *   stays accurate even if the alert fired while the user was asleep. BLE comes in Stage 2.
 *
 * KEEPING NATIVE DUMB: the hard logic (direction, spiral walk) stays in JS, which hands us
 * `stopSeq` — EVERY physical station (通過駅 included) from the current position to the
 * destination. We just match the live `fromStation` against it and count what remains, so
 * `stopsToGo`/`alertN` mean physical stations — matching the JS countdown and the
 * "N駅前（通過駅も数えます）" setting.
 */
public class TrackingService extends Service {
    public static final String TAG = "NorisugoshiSvc";
    public static final String CHANNEL_ID = "norisugoshi_tracking";
    public static final String ALERT_CHANNEL_ID = "norisugoshi_alert";
    public static final int NOTIF_ID = 4201;
    public static final int NOTIF_ALERT_ID = 4202;

    // adaptive polling: relaxed when far away, tight when close (saves battery)
    private static final long FAR_MS = 60000;
    private static final long NEAR_MS = 20000;
    // hard safety cutoff: a forgotten session can never drain the battery past this
    private static final long MAX_SESSION_MS = 3 * 60 * 60 * 1000L;   // 3 hours

    // ---- OEM-suppression defenses (Layer 1+2) ----
    // Aggressive OEMs (EMUI/PowerGenie, MIUI, ColorOS) freeze the HandlerThread despite the
    // foreground service + wake lock (measured: 117 s gap on a Huawei YAL-L21). Alarms travel
    // a different OS path that these killers honor, so we use them as (a) a watchdog that
    // re-kicks a frozen poll loop and (b) a timetable-anchored backstop that fires the alert
    // even if polling never recovers.
    private static final String ACTION_WATCHDOG = "com.norisugoshi.app.action.WATCHDOG";
    private static final String ACTION_BACKSTOP = "com.norisugoshi.app.action.BACKSTOP";
    private static final String ACTION_STOP     = "com.norisugoshi.app.action.STOP";   // notification 停止 button
    private static final String ACTION_ACK      = "com.norisugoshi.app.action.ACK";    // notification 起きた button
    private static final long WATCHDOG_INTERVAL_MS = 60000;   // NOTE: Doze throttles while-idle alarms to ~9 min — still catches long freezes
    private static final long FREEZE_SLACK_MS = 15000;        // poll later than due+this = suppression
    private static final String PREFS = "norisugoshi";
    private static final String PREFS_PARAMS = "params";
    // session state persisted alongside params so a resurrection resumes EXACTLY where the
    // killed session was: acked stays acked (no re-buzz), the 3 h cutoff keeps its original
    // anchor (no immortal sessions). Cleared on every deliberate end (stop/ack+arrival/cutoff).
    private static final String PREFS_STARTED_AT = "startedAt";
    private static final String PREFS_ACKED      = "acked";
    private static final String PREFS_ALERTED    = "alerted";
    private static final String PREFS_ALERTED_AT = "alertedAt";

    public static TrackingService instance;   // so the plugin can call acknowledge()

    private HandlerThread worker;
    private Handler bg;
    private PowerManager.WakeLock wakeLock;
    private boolean running = false;

    // params handed over from JS
    private String proxyBase = "", railway = "", trainNumber = "", destTitle = "";
    private final List<String> stopSeq = new ArrayList<>();
    private final List<String> titleSeq = new ArrayList<>();
    private int alertN = 2;
    // Wristband amplitude as a PERCENT, 5-100 in steps of 5 (was a coarse 1-10 step until
    // 2026-07-24 — a 10-point scale could not express values like 35).
    private int hapticStrength = 70;
    // ---- habituation study knobs (from JS params; see StimulusPattern) ----
    // Defaults are deliberately the SAFE, boring ones: vibrate on a fixed single pulse, i.e. the
    // pre-study behaviour. Nothing varies and nothing zaps until the experiment explicitly says so.
    private String stimChannel    = "vibe";
    private String patternPolicy  = "fixed";
    private String patternFixedId = "single";
    private String lastPatternId  = "";   // what actually fired, for the response log
    private String lastStimDecision = ""; // "fired", or the limiter's reason for suppressing
    private int    lastExposures  = 0;    // prior deliveries of that pattern, before this one

    // progress + alert state
    private int lastIdx = 0;
    private int pollCount = 0;
    private long nextDelay = NEAR_MS;
    private long startedAt = 0;
    private boolean alerted = false;
    private boolean acked = false;
    private long alertedAt = 0;

    // suppression-defense state
    private volatile long pollDueAt = 0;     // elapsedRealtime the next poll SHOULD run (0 = none scheduled)
    private int freezeCount = 0;             // suppression episodes this session (for logs + JS event)
    private long backstopAt = 0;             // epoch ms of the timetable-predicted alert (0 = none)
    private long backstopBaseAt = 0;         // DELAY-FREE scheduled pass time; poll re-derives backstopAt = base + live delay + grace
    private static final long BACKSTOP_GRACE_MS   = 60000;   // let the live poll win when polling is healthy
    private static final long BACKSTOP_RESCHED_MS = 30000;   // only re-arm the alarm when the target moved this much
    private PendingIntent watchdogPi, backstopPi;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        String action = (intent != null) ? intent.getAction() : null;
        if (intent != null && intent.hasExtra("params")) {
            applyParams(intent.getStringExtra("params"));
            persistParams(intent.getStringExtra("params"));   // survive an OEM process kill
        } else if (!running && stopSeq.isEmpty()) {
            restoreParams();   // killed + restarted (START_STICKY / watchdog / backstop alarm) → recover route
        }
        if (!running) {
            running = true;
            // a restored session keeps its ORIGINAL start time (3 h cutoff can't be reset by
            // kill+resurrect cycles); only a genuinely fresh session anchors to now.
            StimulusLimiter.get().attach(this);
            if (startedAt == 0) {
                startedAt = System.currentTimeMillis();
                // Same reasoning as the 3 h cutoff, and it matters more here: resetting the zap
                // budget on every resurrection would make EMUI's kill cycle an unlimited-zap
                // loophole. Only a genuinely fresh ride gets a fresh budget.
                StimulusLimiter.get().startRide();
            }
            createChannels();
            startForeground(NOTIF_ID, buildOngoing("追跡を開始しました… · Starting…"));
            acquireWakeLock();
            worker = new HandlerThread("noris-poll");
            worker.start();
            bg = new Handler(worker.getLooper());   // poll on a BACKGROUND thread (no network on main)
            bg.post(pollRunnable);
            scheduleWatchdog();
            scheduleBackstop();
            persistSessionState();
            Log.i(TAG, "service started; stops=" + stopSeq.size() + " alertN=" + alertN + " train=" + trainNumber
                    + (backstopAt > 0 ? " backstop=" + backstopAt : "") + (acked ? " [restored: acked]" : ""));
        }
        if (ACTION_WATCHDOG.equals(action)) handleWatchdog();
        else if (ACTION_BACKSTOP.equals(action)) handleBackstop();
        else if (ACTION_ACK.equals(action)) { acknowledge(); NorisugoshiTracker.emitAcknowledged(); }
        else if (ACTION_STOP.equals(action)) {
            Log.i(TAG, "stopped from notification");
            clearSession();                    // deliberate end → never resurrect
            NorisugoshiTracker.emitStopped();  // tell JS to stop ITS poll loop + tracking UI too
            stopSelf();
        }
        return START_STICKY;
    }

    private void applyParams(String json) {
        try {
            JSONObject p = new JSONObject(json);
            proxyBase   = p.optString("proxyBase", proxyBase);
            railway     = p.optString("railway", railway);
            trainNumber = p.optString("trainNumber", trainNumber);
            destTitle   = p.optString("destTitle", destTitle);
            alertN      = p.optInt("alertN", alertN);
            backstopAt     = p.optLong("backstopAt", 0);
            backstopBaseAt = p.optLong("backstopBaseAt", 0);   // delay-free anchor; poll re-derives from live delay
            hapticStrength = Math.max(1, Math.min(100, p.optInt("hapticStrength", hapticStrength)));
            stimChannel    = p.optString("stimChannel", stimChannel);      // "vibe" (default) or "zap"
            patternPolicy  = p.optString("patternPolicy", patternPolicy);  // fixed | random | rotating
            patternFixedId = p.optString("patternFixedId", patternFixedId);
            JSONArray seq = p.optJSONArray("stopSeq");
            JSONArray tit = p.optJSONArray("titleSeq");
            if (seq != null) { stopSeq.clear(); for (int i = 0; i < seq.length(); i++) stopSeq.add(seq.getString(i)); }
            if (tit != null) { titleSeq.clear(); for (int i = 0; i < tit.length(); i++) titleSeq.add(tit.getString(i)); }
            // re-arm for the new route (e.g. user switched trains)
            lastIdx = 0; alerted = false; acked = false; alertedAt = 0;
            if (running) { scheduleBackstop(); persistSessionState(); }   // route changed mid-session → re-anchor
        } catch (Exception e) {
            Log.w(TAG, "params parse failed: " + e.getMessage());
        }
    }

    private void persistParams(String json) {
        try { getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREFS_PARAMS, json).apply(); }
        catch (Exception ignored) {}
    }

    /** Snapshot the live session state so a kill+resurrect resumes faithfully. */
    private void persistSessionState() {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putLong(PREFS_STARTED_AT, startedAt)
                    .putBoolean(PREFS_ACKED, acked)
                    .putBoolean(PREFS_ALERTED, alerted)
                    .putLong(PREFS_ALERTED_AT, alertedAt)
                    .apply();
        } catch (Exception ignored) {}
    }

    /** Deliberate session end — wipe everything so no alarm can resurrect a stopped session. */
    void clearSession() {   // package-private: also called by the plugin's stop()
        try { getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply(); }
        catch (Exception ignored) {}
    }

    private void restoreParams() {
        try {
            android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            String json = sp.getString(PREFS_PARAMS, null);
            if (json == null) return;
            long savedStart = sp.getLong(PREFS_STARTED_AT, 0);
            // stale session (already past the hard cutoff) → don't resurrect it
            if (savedStart > 0 && System.currentTimeMillis() - savedStart > MAX_SESSION_MS) {
                Log.i(TAG, "stale persisted session (past cutoff) → not restoring");
                clearSession();
                return;
            }
            applyParams(json);                          // (resets state — restore it below)
            startedAt = savedStart;
            acked     = sp.getBoolean(PREFS_ACKED, false);
            alerted   = sp.getBoolean(PREFS_ALERTED, false);
            alertedAt = sp.getLong(PREFS_ALERTED_AT, 0);
            Log.i(TAG, "session restored from prefs (service was killed)"
                    + " acked=" + acked + " alerted=" + alerted);
        } catch (Exception ignored) {}
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            if (!running) return;
            // hard safety cutoff: a forgotten session auto-stops (can't drain overnight)
            if (System.currentTimeMillis() - startedAt > MAX_SESSION_MS) {
                Log.i(TAG, "session cutoff (" + (MAX_SESSION_MS / 3600000) + "h) → stopping");
                clearSession();                  // deliberate end → never resurrect
                stopSelf();
                return;
            }
            // ---- freeze self-detection ----
            // If we're running much later than scheduled, the OEM froze the thread (wake lock
            // ignored). Log it, tell JS (banner guides the user to whitelist), and carry on.
            long nowEl = SystemClock.elapsedRealtime();
            if (pollDueAt > 0 && nowEl - pollDueAt > FREEZE_SLACK_MS) {
                freezeCount++;
                long lateMs = nowEl - pollDueAt;
                Log.w(TAG, "SUPPRESSION: poll " + (pollCount + 1) + " ran " + (lateMs / 1000)
                        + "s late (episode " + freezeCount + ")");
                NorisugoshiTracker.emitSuppressed(lateMs, freezeCount);
            }
            pollCount++;
            try { pollOnce(); }
            catch (Exception e) { Log.w(TAG, "poll " + pollCount + " error: " + e.getMessage()); }
            if (running) {
                pollDueAt = SystemClock.elapsedRealtime() + nextDelay;
                bg.postDelayed(this, nextDelay);
            }
        }
    };

    private void pollOnce() throws Exception {
        if (proxyBase.isEmpty() || railway.isEmpty()) { Log.w(TAG, "no params yet"); return; }
        String url = proxyBase + "/api/trains?railway=" + URLEncoder.encode(railway, "UTF-8");
        JSONArray arr = new JSONArray(httpGet(url));

        JSONObject train = null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String no = o.optString("odpt:trainNumber", "");
            if (no.isEmpty()) {
                String same = o.optString("owl:sameAs", "");
                no = same.contains(".") ? same.substring(same.lastIndexOf('.') + 1) : same;
            }
            if (no.equals(trainNumber)) { train = o; break; }
        }
        if (train == null) {
            Log.i(TAG, "poll " + pollCount + ": train " + trainNumber + " not in feed");
            updateOngoing("再検索中… · Re-searching… (" + trainNumber + ")");
            nextDelay = NEAR_MS;
            return;
        }

        String from = train.optString("odpt:fromStation", "");
        int found = -1;                          // advance monotonic index (handles spiral repeats)
        for (int i = lastIdx; i < stopSeq.size(); i++) {
            if (stopSeq.get(i).equals(from)) { found = i; break; }
        }
        if (found >= 0) lastIdx = found;         // not found = outside the remaining route / feed glitch → keep last

        int stopsToGo = (stopSeq.size() - 1) - lastIdx;
        String curTitle = (lastIdx < titleSeq.size()) ? titleSeq.get(lastIdx) : shortId(from);
        Log.i(TAG, "poll " + pollCount + ": from=" + shortId(from) + " idx=" + lastIdx
                + " stopsToGo=" + stopsToGo + (alerted ? " [alerted]" : ""));

        // ---- re-anchor the backstop to the LIVE delay ----
        // The backstop time = scheduled pass time + delay. `backstopBaseAt` is the delay-FREE part
        // (from JS); the delay is read LIVE here, so as the train falls further behind the alarm
        // moves back with it. Computing it once at setup (as before) made it fire minutes early
        // whenever the train slipped later after the ride began.
        if (backstopBaseAt > 0 && !alerted && !acked) {
            long liveDelayMs = train.optLong("odpt:delay", 0) * 1000L;
            long want = backstopBaseAt + liveDelayMs + BACKSTOP_GRACE_MS;
            if (Math.abs(want - backstopAt) > BACKSTOP_RESCHED_MS) {
                backstopAt = want;
                scheduleBackstop();
                persistSessionState();
                Log.i(TAG, "backstop re-anchored to live delay (" + (liveDelayMs / 1000)
                        + "s) → " + new java.util.Date(backstopAt));
            }
        }

        // ---- ALERT logic ----
        if (stopsToGo <= alertN && !acked) {
            if (!alerted) {                      // fire the real alert exactly once
                alerted = true;
                alertedAt = System.currentTimeMillis();
                // Stimulus FIRST: it is the primary wake channel and the study's measured
                // variable, so it must not wait behind notification construction and posting.
                fireStimulus();                          // wrist stimulus (works while asleep)
                fireAlertNotification(stopsToGo);
                NorisugoshiTracker.emitAlert(stopsToGo, alertedAt, "poll",   // JS experiment log
                        lastPatternId, patternPolicy, lastExposures, lastStimDecision);
                cancelBackstop();                // live alert fired → the timetable backstop is moot
                persistSessionState();           // a kill after this point must NOT re-fire the alert
                Log.i(TAG, "ALERT fired at stopsToGo=" + stopsToGo + " ble=" + BleManager.get().isConnected());
            }
            vibrate();                           // keep buzzing each poll until acknowledged
            nextDelay = NEAR_MS;
        } else {
            nextDelay = (stopsToGo > alertN + 3) ? FAR_MS : NEAR_MS;   // relax when far, tighten when close
        }

        // ---- ongoing status notification ----
        if (acked)            updateOngoing("確認済み Done — お気をつけて / take care · " + destTitle);
        else if (stopsToGo <= 0) updateOngoing("まもなく到着 Arriving — " + destTitle);
        else                  updateOngoing("あと " + stopsToGo + " 駅 · " + stopsToGo + " stops to " + destTitle + "（現在 " + curTitle + "）");

        // journey complete: arrived AND acknowledged → auto-stop (frees the wake lock + battery).
        // NOTE: if arrived but NOT acknowledged we keep buzzing — the user may have overshot/asleep.
        if (acked && stopsToGo <= 0) {
            Log.i(TAG, "arrived + acknowledged → auto-stop");
            clearSession();                      // journey over → never resurrect
            stopSelf();
        }
    }

    /** Called from the plugin / notification action when the user taps "I'm awake". */
    /**
     * Fire the wrist stimulus for an alert: pick a pattern per the study policy, play it, and
     * count the exposure. Exposure is recorded ONLY if the pattern actually fired — a stimulus
     * the limiter suppressed must not inflate the exposure count the analysis depends on.
     */
    private void fireStimulus() {
        StimulusPattern p = StimulusPattern.select(
                this, StimulusPattern.policyOf(patternPolicy), patternFixedId);
        lastPatternId = p.id;
        int priorExposures = StimulusPattern.exposures(this, p.id);   // BEFORE this delivery
        boolean ok = BleManager.get().playPattern(p, hapticStrength, stimChannel);
        if (ok) StimulusPattern.recordExposure(this, p.id);
        lastStimDecision = ok ? "fired" : BleManager.get().lastDecision();
        lastExposures = priorExposures;
        Log.i(TAG, "stimulus " + p + " policy=" + patternPolicy + " channel=" + stimChannel
                + " exposures=" + priorExposures + " -> " + lastStimDecision);
    }

    /**
     * The band's own button was pressed — treat it exactly like tapping 起きた in the app.
     * Static because BleManager is a process-wide singleton that may outlive any given service
     * instance; if no session is running there is simply nothing to acknowledge.
     */
    static void onBandButton() {
        if (instance == null) return;
        instance.acknowledge();
        NorisugoshiTracker.emitAcknowledged();   // stops the JS response timer at the press moment
    }

    public void acknowledge() {
        acked = true;
        cancelVibration();
        BleManager.get().cancelPattern();    // stop any pattern still being sequenced
        BleManager.get().write(0);           // wrist haptic OFF
        cancelBackstop();                    // user is awake → no backstop needed
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIF_ALERT_ID);   // dismiss the alert heads-up
        persistSessionState();               // a kill after ack must NOT resurrect buzzing
        updateOngoing("確認済み Done — お気をつけて / take care · " + destTitle);
        Log.i(TAG, "acknowledged");
    }

    // ---- Layer 1: AlarmManager watchdog (re-kicks a frozen poll loop) ----
    // Exact alarms travel through AlarmManagerService, which aggressive OEM power managers
    // honor even when they freeze an app's own threads. Each firing both wakes the CPU
    // (often un-freezing the HandlerThread by itself) and re-posts the poll if it's overdue.
    private void handleWatchdog() {
        long nowEl = SystemClock.elapsedRealtime();
        if (running && pollDueAt > 0 && nowEl - pollDueAt > FREEZE_SLACK_MS && bg != null) {
            Log.w(TAG, "watchdog: poll overdue by " + ((nowEl - pollDueAt) / 1000) + "s → re-kick");
            bg.removeCallbacks(pollRunnable);
            bg.post(pollRunnable);
        }
        scheduleWatchdog();   // always re-arm the next tick
    }

    private PendingIntent servicePi(String action, int requestCode) {
        Intent i = new Intent(this, TrackingService.class).setAction(action);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) fl |= PendingIntent.FLAG_IMMUTABLE;
        return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? PendingIntent.getForegroundService(this, requestCode, i, fl)
                : PendingIntent.getService(this, requestCode, i, fl);
    }

    private void scheduleWatchdog() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am == null) return;
            watchdogPi = servicePi(ACTION_WATCHDOG, 1);
            long at = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms())
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, watchdogPi);
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, watchdogPi);
            else
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, watchdogPi);
        } catch (Exception e) { Log.w(TAG, "watchdog schedule: " + e.getMessage()); }
    }

    private void cancelWatchdog() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null && watchdogPi != null) am.cancel(watchdogPi);
        } catch (Exception ignored) {}
        watchdogPi = null;
    }

    // ---- Layer 2: timetable backstop (fires the alert even if polling never recovers) ----
    // JS precomputes the wall-clock time the train should reach the "alertN stops away"
    // station (timetable + last-known delay) and passes it as `backstopAt`. setAlarmClock()
    // is the most suppression-proof primitive Android has — OEMs treat it like the user's
    // wake-up alarm. If the live poll alert fires first, this is cancelled and never seen.
    private void handleBackstop() {
        if (!running || alerted || acked) return;
        alerted = true;
        alertedAt = System.currentTimeMillis();
        fireStimulus();                          // stimulus first — see the poll path above
        fireAlertNotification(alertN);
        NorisugoshiTracker.emitAlert(alertN, alertedAt, "backstop",
                lastPatternId, patternPolicy, lastExposures, lastStimDecision);
        persistSessionState();
        vibrate();
        Log.w(TAG, "BACKSTOP alarm fired (polling frozen or train ahead of live data)"
                + " ble=" + BleManager.get().isConnected());
        if (bg != null) { bg.removeCallbacks(pollRunnable); bg.post(pollRunnable); }   // try to resume live tracking
    }

    private void scheduleBackstop() {
        cancelBackstop();
        if (backstopAt <= System.currentTimeMillis()) return;   // none supplied / already past
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am == null) return;
            backstopPi = servicePi(ACTION_BACKSTOP, 2);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms())
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, backstopAt, backstopPi);
            else
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(backstopAt, openAppIntent()), backstopPi);
            Log.i(TAG, "backstop alarm set for " + new java.util.Date(backstopAt));
        } catch (Exception e) { Log.w(TAG, "backstop schedule: " + e.getMessage()); }
    }

    private void cancelBackstop() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null && backstopPi != null) am.cancel(backstopPi);
        } catch (Exception ignored) {}
        backstopPi = null;
    }

    // ---- HTTP ----
    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            InputStream is = (code >= 400) ? c.getErrorStream() : c.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            if (code >= 400) throw new Exception("HTTP " + code);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private String shortId(String id) {
        return (id != null && id.contains(".")) ? id.substring(id.lastIndexOf('.') + 1) : id;
    }

    // ---- vibration ----
    private Vibrator vibrator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
            return vm != null ? vm.getDefaultVibrator() : null;
        }
        return (Vibrator) getSystemService(VIBRATOR_SERVICE);
    }
    private void vibrate() {
        try {
            Vibrator v = vibrator();
            if (v == null) return;
            long[] pattern = {0, 500, 200, 500, 200, 700};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            else
                v.vibrate(pattern, -1);
        } catch (Exception e) { Log.w(TAG, "vibrate: " + e.getMessage()); }
    }
    private void cancelVibration() {
        try { Vibrator v = vibrator(); if (v != null) v.cancel(); } catch (Exception ignored) {}
    }

    // ---- notifications ----
    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "norisugoshi:tracking");
            // cover the whole capped session (+buffer) so polling stays reliable screen-off;
            // the session cutoff (stopSelf) releases it, so it can't be held forever.
            wakeLock.acquire(MAX_SESSION_MS + 5 * 60 * 1000L);
        } catch (Exception e) { Log.w(TAG, "wakeLock failed: " + e.getMessage()); }
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "乗り過ごし防止 追跡", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("列車の追跡をバックグラウンドで継続します");
                nm.createNotificationChannel(ch);
            }
            if (nm.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        ALERT_CHANNEL_ID, "乗り過ごし アラート", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("降車駅が近づいたら強く通知します");
                ch.enableVibration(true);
                ch.setVibrationPattern(new long[]{0, 500, 200, 500, 200, 700});
                nm.createNotificationChannel(ch);
            }
        }
    }

    private PendingIntent openAppIntent() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 0, open, flags);
    }

    private Notification buildOngoing(String text) {
        // 停止 action: the user can ALWAYS end tracking from the notification — even after the
        // app UI is gone. Without this, a killed-then-resurrected service was only stoppable
        // via force-stop (the Realme zombie bug).
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("乗り過ごし防止 — トラッキング中 / Tracking")
                .setContentText(text)
                .setSmallIcon(getApplicationInfo().icon)
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, "⏹ 停止 Stop", servicePi(ACTION_STOP, 3))
                .build();
    }

    private void updateOngoing(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildOngoing(text));
    }

    private void fireAlertNotification(int stops) {
        String body = (stops <= 0)
                ? destTitle + " に到着します！降りる準備を · Arriving — get ready"
                : "あと " + stops + " 駅で " + destTitle + "！降りる準備を · " + stops + " stops to " + destTitle + " — get ready";
        Notification n = new NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setContentTitle("乗り過ごし防止 — まもなく到着 / Arriving soon")
                .setContentText(body)
                .setSmallIcon(getApplicationInfo().icon)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_LIGHTS)
                // one-tap acknowledge without opening the app — stops the buzzing + BLE
                .addAction(0, "起きた！ I'm awake", servicePi(ACTION_ACK, 4))
                .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ALERT_ID, n);
    }

    @Override
    public void onDestroy() {
        running = false;
        cancelWatchdog();
        cancelBackstop();
        cancelVibration();
        BleManager.get().write(0);           // leave the haptic off when tracking stops (keep connection)
        if (bg != null) bg.removeCallbacksAndMessages(null);
        if (worker != null) worker.quitSafely();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (instance == this) instance = null;
        Log.i(TAG, "service destroyed (polls=" + pollCount + ")");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
