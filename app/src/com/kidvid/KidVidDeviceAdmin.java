package com.kidvid;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/**
 * Device Admin / optional Device Owner receiver.
 * Soft lockdown does not require this to be Device Owner.
 * When Device Owner IS set, onEnabled applies hard lock-task policies.
 */
public class KidVidDeviceAdmin extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        applyHardLockPolicies(context);
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
    }

    static void applyHardLockPolicies(Context context) {
        DevicePolicyManager dpm =
            (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) return;
        String pkg = context.getPackageName();
        if (!dpm.isDeviceOwnerApp(pkg)) return;

        ComponentName admin = new ComponentName(context, KidVidDeviceAdmin.class);
        try {
            dpm.setLockTaskPackages(admin, new String[]{pkg});
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
            }
            try {
                dpm.setKeyguardDisabled(admin, true);
            } catch (Exception ignored) {}
            try {
                dpm.setStatusBarDisabled(admin, true);
            } catch (Exception ignored) {}
            try {
                IntentFilter homeFilter = new IntentFilter(Intent.ACTION_MAIN);
                homeFilter.addCategory(Intent.CATEGORY_HOME);
                homeFilter.addCategory(Intent.CATEGORY_DEFAULT);
                dpm.addPersistentPreferredActivity(admin, homeFilter,
                    new ComponentName(pkg, MainActivity.class.getName()));
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }
}
