package com.kidvid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // Enable ADB over TCP on port 5555
            // Settings.Global approach for the UI toggle
            try {
                Settings.Global.putInt(context.getContentResolver(), "adb_wifi_enabled", 1);
            } catch (Exception ignored) {}

            // Actually restart adbd in TCP mode via shell
            try {
                Runtime.getRuntime().exec(new String[]{"setprop", "service.adb.tcp.port", "5555"}).waitFor();
                Runtime.getRuntime().exec(new String[]{"stop", "adbd"}).waitFor();
                Runtime.getRuntime().exec(new String[]{"start", "adbd"}).waitFor();
            } catch (Exception e) {
                // Not root — try the Settings.Global port approach as fallback
                try {
                    Settings.Global.putInt(context.getContentResolver(), "adb_enabled", 1);
                    Settings.Global.putString(context.getContentResolver(),
                        "persist.adb.tcp.port", "5555");
                } catch (Exception ignored) {}
            }

            // Also disable Moto Actions (chop-chop flashlight)
            try {
                Settings.System.putInt(context.getContentResolver(),
                    "moto_flashlight_gesture", 0);
                Settings.Secure.putInt(context.getContentResolver(),
                    "camera_double_tap_power_gesture_disabled", 1);
            } catch (Exception ignored) {}

            // Start video sync service
            SyncService.schedule(context);

            // Launch KidVid
            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
        }
    }
}
