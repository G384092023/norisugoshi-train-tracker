package com.norisugoshi.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Fires when the nap alarm goes off. Posts a full-screen-intent notification on a high-importance
 * channel — the alarm-clock pattern that launches the activity and turns the screen on even from
 * the background / lock screen — and also tries a direct activity start for the app-foreground case.
 * MainActivity picks up ACTION_FIRE and asks the WebView to run napFire().
 */
public class NapAlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_FIRE = "com.norisugoshi.app.NAP_FIRE";
    private static final String CHANNEL_ID = "nap_fire";
    private static final int NOTIF_ID = 7302;
    private static final int REQ = 7303;

    @Override
    public void onReceive(Context context, Intent intent) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Nap alarm", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Wake stimulus for the nap test");
            try { ch.setBypassDnd(true); } catch (Exception ignored) {}
            nm.createNotificationChannel(ch);
        }

        Intent launch = new Intent(context, MainActivity.class);
        launch.setAction(ACTION_FIRE);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(context, REQ, launch, piFlags);

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        Notification notif = b
                .setContentTitle("乗り過ごし防止")
                .setContentText("起きる時間です")
                .setSmallIcon(context.getApplicationInfo().icon)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)   // the alarm-clock wake: launches + turns screen on
                .build();
        if (nm != null) nm.notify(NOTIF_ID, notif);

        // When the app is already alive/visible, a direct start is immediate.
        try { context.startActivity(launch); } catch (Exception ignored) {}
    }
}
