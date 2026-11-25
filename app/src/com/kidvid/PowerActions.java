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
        /** @return true if the root command was accepted (exit 0, or still running) */
        boolean runRoot(String command);
    }

    /** How long to wait for a root command to exit before treating it as accepted. */
    static final long ROOT_WAIT_MS = 4000L;

    private PowerActions() {}

    /**
     * su exec returns a process immediately, even if root was denied or the
     * command failed. Wait for an exit code: 0 is success, non-zero is
     * failure (so a fallback can run). If the process is still running
     * after the timeout, the command was likely accepted (reboot/poweroff
     * will not return).
     */
    static boolean rootProcessAccepted(Process process) {
        return rootProcessAccepted(process, ROOT_WAIT_MS);
    }

    static boolean rootProcessAccepted(Process process, long timeoutMs) {
        if (process == null) {
            return false;
        }
        try {
            try {
                process.getOutputStream().close();
            } catch (Exception ignored) {}
            long wait = timeoutMs < 0 ? 0 : timeoutMs;
            if (process.waitFor(wait, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return process.exitValue() == 0;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

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
