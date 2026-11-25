package com.kidvid;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KidVidPowerActionsTest {

    private static final class FakeHost implements PowerActions.Host {
        boolean deviceOwner;
        boolean rebootIssued;
        boolean rebootThrows;
        boolean rootFailsFirst;
        int rootFailCountLeft;
        final List<String> rootCommands = new ArrayList<String>();

        @Override
        public boolean isDeviceOwner() {
            return deviceOwner;
        }

        @Override
        public boolean deviceOwnerReboot() {
            if (rebootThrows) {
                return false;
            }
            rebootIssued = true;
            return true;
        }

        @Override
        public boolean runRoot(String command) {
            rootCommands.add(command);
            if (rootFailCountLeft > 0) {
                rootFailCountLeft--;
                return false;
            }
            return !rootFailsFirst || rootCommands.size() > 1;
        }
    }

    @Test
    public void restartUsesDeviceOwnerRebootWhenOwner() {
        FakeHost host = new FakeHost();
        host.deviceOwner = true;
        assertTrue(PowerActions.restart(host));
        assertTrue(host.rebootIssued);
        assertTrue(host.rootCommands.isEmpty());
    }

    @Test
    public void restartFallsBackToRootRebootWhenNotOwner() {
        FakeHost host = new FakeHost();
        host.deviceOwner = false;
        assertTrue(PowerActions.restart(host));
        assertFalse(host.rebootIssued);
        assertEquals(1, host.rootCommands.size());
        assertEquals(PowerActions.RESTART_FALLBACK_CMD, host.rootCommands.get(0));
    }

    @Test
    public void restartFallsBackToRootWhenDeviceOwnerRebootFails() {
        FakeHost host = new FakeHost();
        host.deviceOwner = true;
        host.rebootThrows = true;
        assertTrue(PowerActions.restart(host));
        assertFalse(host.rebootIssued);
        assertEquals(PowerActions.RESTART_FALLBACK_CMD, host.rootCommands.get(0));
    }

    @Test
    public void powerOffUsesRebootDashPThenSvcShutdown() {
        FakeHost host = new FakeHost();
        host.rootFailCountLeft = 1;
        assertTrue(PowerActions.powerOff(host));
        assertEquals(2, host.rootCommands.size());
        assertEquals(PowerActions.POWER_OFF_CMD, host.rootCommands.get(0));
        assertEquals(PowerActions.POWER_OFF_FALLBACK_CMD, host.rootCommands.get(1));
        assertEquals("svc power shutdown", PowerActions.powerOffCommand(true));
        assertEquals("reboot -p", PowerActions.powerOffCommand(false));
    }

    @Test
    public void powerOffStopsAfterPreferredCommandSucceeds() {
        FakeHost host = new FakeHost();
        assertTrue(PowerActions.powerOff(host));
        assertEquals(1, host.rootCommands.size());
        assertEquals(PowerActions.POWER_OFF_CMD, host.rootCommands.get(0));
    }

    @Test
    public void nullHostIsRejected() {
        assertFalse(PowerActions.restart(null));
        assertFalse(PowerActions.powerOff(null));
    }
}
