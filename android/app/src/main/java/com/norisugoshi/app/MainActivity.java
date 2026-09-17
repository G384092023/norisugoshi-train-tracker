package com.norisugoshi.app;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.WebView;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Register custom plugins before the bridge starts.
        registerPlugin(NorisugoshiTracker.class);   // proven native BLE + patterns + oracle (+ nap fire path)
        registerPlugin(NapAlarmPlugin.class);       // Phase-2b exact alarm that relaunches to fire the nap
        super.onCreate(savedInstanceState);
        maybeHandleNapFire(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        maybeHandleNapFire(intent);
    }

    // Alarm fired: wake the screen / show over the lock screen, then ask the WebView to run the
    // existing napFire(). JS decides whether a nap is actually armed, so a stray intent is a no-op.
    private void maybeHandleNapFire(Intent intent) {
        if (intent == null || !NapAlarmReceiver.ACTION_FIRE.equals(intent.getAction())) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                  | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                  | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        final Bridge b = getBridge();
        if (b == null) return;
        final WebView wv = b.getWebView();
        if (wv == null) return;
        wv.post(new Runnable() {
            @Override public void run() {
                b.eval("window.__napFireFromNative && window.__napFireFromNative()", null);
            }
        });
    }
}
