package com.kidvid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * BroadcastReceiver that bridges AlarmManager → startForegroundService.
 * 
 * Android 11+ blocks startService() from background. AlarmManager's
 * PendingIntent.getService() calls startService(), which gets rejected.
 * But PendingIntent.getBroadcast() → BroadcastReceiver → startForegroundService()
 * is allowed because BroadcastReceivers get a brief window to start foreground services.
 */
public class SyncAlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "KidVid.SyncAlarm";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Alarm fired — starting SyncService as foreground");
        Intent serviceIntent = new Intent(context, SyncService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}
