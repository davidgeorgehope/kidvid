package com.kidvid;

/**
 * Parent power actions used only from the PIN-gated settings screen.
 * Restart prefers DevicePolicyManager.reboot (Device Owner); power-off
 * uses root because the kiosk blocks the normal power menu.
 */
final class PowerActions {
    static final String POWER_OFF_CMD = "reboot -p";
    static final String POWER_OFF_FALLBACK_CMD = "svc power shutdown";
    static final String RESTART_FALLBACK_CMD = "reboot";

    interface Host {
        boolean isDeviceOwner();
        /** @return true if the Device Owner reboot call was issued */
        boolean deviceOwnerReboot();
        /** @return true if the root command started successfully */
        boolean runRoot(String command);
    }

    private PowerActions() {}

    static boolean restart(Host host) {
        if (host != null && host.isDeviceOwner() && host.deviceOwnerReboot()) {
            return true;
        }
        return host != null && host.runRoot(RESTART_FALLBACK_CMD);
    }

    /**
     * Preferred, then fallback, root shutdown command. Never uses
     * DevicePolicyManager (there is no DO power-off API).
     */
    static String powerOffCommand(boolean preferredFailed) {
        return preferredFailed ? POWER_OFF_FALLBACK_CMD : POWER_OFF_CMD;
    }

    static boolean powerOff(Host host) {
        if (host == null) {
            return false;
        }
        if (host.runRoot(POWER_OFF_CMD)) {
            return true;
        }
        return host.runRoot(POWER_OFF_FALLBACK_CMD);
    }
}
