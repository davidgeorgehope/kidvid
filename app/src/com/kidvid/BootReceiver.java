package com.kidvid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // Enable WiFi ADB on boot (Device Owner has permission)
            try {
                Settings.Global.putInt(context.getContentResolver(), "adb_wifi_enabled", 1);
            } catch (Exception e) {
                // Fallback: try the classic ADB over TCP setting
                try {
                    Settings.Global.putString(context.getContentResolver(),
                        "adb_enabled", "1");
                    // Set ADB over network port
                    Settings.Global.putString(context.getContentResolver(),
                        "adb_wifi_enabled", "1");
                } catch (Exception ignored) {}
            }

            // Also disable Moto Actions (chop-chop flashlight)
            try {
                Settings.System.putInt(context.getContentResolver(),
                    "moto_flashlight_gesture", 0);
                Settings.Secure.putInt(context.getContentResolver(),
                    "camera_double_tap_power_gesture_disabled", 1);
            } catch (Exception ignored) {}

            // Launch KidVid
            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
        }
    }
}
