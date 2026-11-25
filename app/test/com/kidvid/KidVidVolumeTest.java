package com.kidvid;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class KidVidVolumeTest {

    private static final class InMemoryKeyValueStore implements VolumeSettingsStore.KeyValueStore {
        private final Map<String, Object> data = new HashMap<>();

        @Override
        public int getInt(String key, int defaultValue) {
            Object v = data.get(key);
            return (v instanceof Integer) ? (Integer) v : defaultValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defaultValue) {
            Object v = data.get(key);
            return (v instanceof Boolean) ? (Boolean) v : defaultValue;
        }

        @Override
        public VolumeSettingsStore.Editor edit() {
            return new VolumeSettingsStore.Editor() {
                private final Map<String, Object> staged = new HashMap<>(data);

                @Override
                public VolumeSettingsStore.Editor putInt(String key, int value) {
                    staged.put(key, value);
                    return this;
                }

                @Override
                public VolumeSettingsStore.Editor putBoolean(String key, boolean value) {
                    staged.put(key, value);
                    return this;
                }

                @Override
                public void apply() {
                    data.clear();
                    data.putAll(staged);
                }
            };
        }
    }

    private static final class FakeVolumeHost implements VolumeController.VolumeHost {
        int streamMax;
        int streamVolume;
        float playerVolume = 1.0f;
        int minuteOfDay;
        boolean lockStreamAtMax = false;

        FakeVolumeHost(int streamMax, int streamVolume, int minuteOfDay) {
            this.streamMax = streamMax;
            this.streamVolume = streamVolume;
            this.minuteOfDay = minuteOfDay;
        }

        @Override
        public int getStreamMaxVolume() {
            return streamMax;
        }

        @Override
        public int getStreamVolume() {
            return streamVolume;
        }

        @Override
        public void setStreamVolume(int index) {
            if (!lockStreamAtMax) {
                streamVolume = Math.max(0, Math.min(streamMax, index));
            }
        }

        @Override
        public void setPlayerVolume(float gain) {
            playerVolume = gain;
        }

        @Override
        public int getCurrentMinuteOfDay() {
            return minuteOfDay;
        }

        double effectiveOutputFraction() {
            return ((double) streamVolume / (double) streamMax) * playerVolume;
        }
    }

    @Test
    public void defaultsMatchLegacyPixelSchedule() {
        VolumePolicy def = VolumePolicy.defaults();
        assertEquals(100, def.startingVolumePercent);
        assertEquals(100, def.maxVolumePercent);
        assertTrue(def.dipEnabled);
        assertEquals(22 * 60, def.dipStartMinutes);
        assertEquals(9 * 60, def.dipDurationMinutes);
        assertEquals(50, def.dipVolumePercent);
        assertEquals(7 * 60, def.dipEndMinutes());

        // Daytime 07:00 - 21:59 -> 100%
        assertFalse(def.isInDipWindow(7 * 60));
        assertEquals(100, def.targetPercentAt(7 * 60));
        assertFalse(def.isInDipWindow(21 * 60 + 59));
        assertEquals(100, def.targetPercentAt(21 * 60 + 59));

        // Nighttime dip 22:00 - 06:59 across midnight -> 50%
        assertTrue(def.isInDipWindow(22 * 60));
        assertEquals(50, def.targetPercentAt(22 * 60));
        assertTrue(def.isInDipWindow(0));
        assertEquals(50, def.targetPercentAt(0));
        assertTrue(def.isInDipWindow(6 * 60 + 59));
        assertEquals(50, def.targetPercentAt(6 * 60 + 59));
    }

    @Test
    public void defaultsDoNotDoubleDipWhenExternalLockSetsNightIndex() {
        VolumePolicy def = VolumePolicy.defaults();
        // Pixel has max 25 and kidvid_lock sets 12 (48%) overnight
        float gain = def.computePlayerGain(50, 12, 25);
        assertEquals(1.0f, gain, 1e-4f);
    }

    @Test
    public void sameDayAndEdgeDipSchedulesWork() {
        // Naptime dip: 1:30 PM for 90 minutes -> ends 3:00 PM
        VolumePolicy nap = VolumePolicy.of(80, 90, true, 13 * 60 + 30, 90, 35);
        assertFalse(nap.isInDipWindow(13 * 60 + 29));
        assertEquals(80, nap.targetPercentAt(13 * 60 + 29));
        assertTrue(nap.isInDipWindow(13 * 60 + 30));
        assertEquals(35, nap.targetPercentAt(13 * 60 + 30));
        assertTrue(nap.isInDipWindow(14 * 60 + 59));
        assertFalse(nap.isInDipWindow(15 * 60));
        assertEquals(80, nap.targetPercentAt(15 * 60));

        // Zero duration or disabled dip never dips
        VolumePolicy disabled = VolumePolicy.of(75, 90, false, 22 * 60, 540, 20);
        assertFalse(disabled.isInDipWindow(23 * 60));
        assertEquals(75, disabled.targetPercentAt(23 * 60));

        VolumePolicy zeroDur = VolumePolicy.of(75, 90, true, 22 * 60, 0, 20);
        assertFalse(zeroDur.isInDipWindow(22 * 60));
        assertEquals(75, zeroDur.targetPercentAt(22 * 60));
    }

    @Test
    public void settingsPersistenceRoundTripsAndClampsCorruptValues() {
        InMemoryKeyValueStore kv = new InMemoryKeyValueStore();
        VolumeSettingsStore store = new VolumeSettingsStore(kv);

        // Fresh store returns defaults
        assertEquals(VolumePolicy.defaults(), store.load());

        VolumePolicy custom = VolumePolicy.of(70, 80, true, 20 * 60 + 15, 600, 30);
        store.save(custom);

        // Reload via fresh store instance backed by same persisted map
        VolumeSettingsStore reloadedStore = new VolumeSettingsStore(kv);
        VolumePolicy loaded = reloadedStore.load();
        assertEquals(custom, loaded);

        // Out-of-range persisted values are clamped at the boundary
        kv.edit()
                .putInt(VolumeSettingsStore.KEY_STARTING_VOLUME, 180)
                .putInt(VolumeSettingsStore.KEY_MAX_VOLUME, -10)
                .putInt(VolumeSettingsStore.KEY_DIP_DURATION_MINUTES, 5000)
                .apply();
        VolumePolicy clamped = reloadedStore.load();
        assertEquals(100, clamped.startingVolumePercent);
        assertEquals(0, clamped.maxVolumePercent);
        assertEquals(1440, clamped.dipDurationMinutes);
        assertEquals(0, clamped.targetPercentAt(12 * 60));

        // Reset restores defaults
        VolumePolicy reset = reloadedStore.resetToDefaults();
        assertEquals(VolumePolicy.defaults(), reset);
        assertEquals(VolumePolicy.defaults(), reloadedStore.load());
    }

    @Test
    public void hardMaxCapEnforcedAgainstStartVolumeAndHardwareKeys() {
        FakeVolumeHost host = new FakeVolumeHost(25, 25, 12 * 60);
        VolumeController controller = new VolumeController(host);

        // Starting volume 90% exceeds max cap 60% -> capped immediately to 60%
        VolumePolicy capped = VolumePolicy.of(90, 60, false, 22 * 60, 540, 40);
        controller.applyPolicy(capped);

        assertTrue(host.streamVolume <= 15);
        assertTrue(host.effectiveOutputFraction() <= 0.60 + 1e-4);

        // Hammering hardware VOLUME_UP cannot exceed 60% (index 15 of 25)
        for (int i = 0; i < 20; i++) {
            assertTrue(controller.onVolumeKey(VolumeController.KEYCODE_VOLUME_UP));
        }
        assertEquals(15, host.streamVolume);
        assertTrue(host.effectiveOutputFraction() <= 0.60 + 1e-4);

        // VOLUME_DOWN lowers volume, then VOLUME_UP stops at cap again
        assertTrue(controller.onVolumeKey(VolumeController.KEYCODE_VOLUME_DOWN));
        assertEquals(14, host.streamVolume);
        assertTrue(controller.onVolumeKey(VolumeController.KEYCODE_VOLUME_UP));
        assertTrue(controller.onVolumeKey(VolumeController.KEYCODE_VOLUME_UP));
        assertEquals(15, host.streamVolume);
    }

    @Test
    public void hardMaxCapEnforcedViaPlayerGainWhenExternalActorRaisesStream() {
        // Simulates root kidvid_lock refusing stream lowering and holding stream at 25/25
        FakeVolumeHost host = new FakeVolumeHost(25, 25, 12 * 60);
        host.lockStreamAtMax = true;
        VolumeController controller = new VolumeController(host);

        VolumePolicy capped = VolumePolicy.of(80, 50, true, 22 * 60, 540, 25);
        controller.applyPolicy(capped);

        // Even though OS stream stays 25/25, MediaPlayer gain caps output at <= 50%
        assertEquals(25, host.streamVolume);
        assertEquals(0.50f, host.playerVolume, 1e-4f);
        assertEquals(0.50, host.effectiveOutputFraction(), 1e-4);

        // Entering dip drops output to 25%
        host.minuteOfDay = 22 * 60 + 5;
        controller.tick();
        assertEquals(0.25f, host.playerVolume, 1e-4f);

        // Preparing a new video retains the enforced cap
        controller.onPlaybackPrepared();
        assertEquals(0.25f, host.playerVolume, 1e-4f);
    }

    @Test
    public void hardwareRaiseBlockedExternallyIsClampedOnNextTick() {
        FakeVolumeHost host = new FakeVolumeHost(15, 10, 14 * 60);
        VolumeController controller = new VolumeController(host);

        controller.applyPolicy(VolumePolicy.of(60, 60, false, 22 * 60, 540, 30));
        assertEquals(9, host.streamVolume); // floor(0.6 * 15) = 9

        // System volume slider or ADB raises stream to 15/15
        host.streamVolume = 15;
        controller.tick();
        assertEquals(9, host.streamVolume);
        assertTrue(host.effectiveOutputFraction() <= 0.60 + 1e-4);
    }

    @Test
    public void dipTransitionsAutomaticallyBetweenDayAndNight() {
        FakeVolumeHost host = new FakeVolumeHost(25, 25, 21 * 60 + 59);
        VolumeController controller = new VolumeController(host);

        VolumePolicy policy = VolumePolicy.of(80, 100, true, 22 * 60, 540, 40);
        controller.applyPolicy(policy);
        assertEquals(20, host.streamVolume);

        // Cross into dip at 22:00
        host.minuteOfDay = 22 * 60;
        controller.tick();
        assertEquals(10, host.streamVolume);

        // Cross out of dip at 07:00
        host.minuteOfDay = 7 * 60;
        controller.tick();
        assertEquals(20, host.streamVolume);
    }

    @Test
    public void legacyPixelNightIndexIsTwelve() {
        VolumePolicy def = VolumePolicy.defaults();
        // 50% of 25 is 12.5; floor keeps the legacy Magisk/mini night level of 12
        // (Math.round would be 13 and fight the fallback enforcer).
        assertEquals(12, def.desiredStreamIndex(50, 25));
        assertEquals(25, def.desiredStreamIndex(100, 25));
        assertEquals(12, def.desiredStreamIndex(def.targetPercentAt(22 * 60), 25));
        assertEquals(25, def.desiredStreamIndex(def.targetPercentAt(7 * 60), 25));
    }
    @Test
    public void volumeKeyDoesNotSnapUpToElevatedStream() {
        // Stream stuck at max (root lock or external raise) while player gain
        // holds output at the 50% target. A volume-down must lower the target,
        // not snap it up to the 25/25 stream.
        FakeVolumeHost host = new FakeVolumeHost(25, 25, 12 * 60);
        host.lockStreamAtMax = true;
        VolumeController controller = new VolumeController(host);
        controller.applyPolicy(VolumePolicy.of(50, 100, false, 22 * 60, 540, 40));

        assertEquals(25, host.streamVolume);
        assertEquals(50, controller.getActiveTargetPercent());
        assertEquals(0.50, host.effectiveOutputFraction(), 1e-4);

        assertTrue(controller.onVolumeKey(VolumeController.KEYCODE_VOLUME_DOWN));
        assertEquals(25, host.streamVolume);
        assertEquals(46, controller.getActiveTargetPercent());
        assertEquals(0.46, host.effectiveOutputFraction(), 1e-4);

        // Volume up steps the desired percent, not the stuck stream
        assertTrue(controller.onVolumeKey(VolumeController.KEYCODE_VOLUME_UP));
        assertEquals(50, controller.getActiveTargetPercent());
        assertEquals(0.50, host.effectiveOutputFraction(), 1e-4);
    }

    @Test
    public void volumeKeyStepsFromDesiredIndexAfterExternalRaise() {
        FakeVolumeHost host = new FakeVolumeHost(25, 25, 12 * 60);
        VolumeController controller = new VolumeController(host);
        controller.applyPolicy(VolumePolicy.of(80, 100, false, 22 * 60, 540, 40));
        assertEquals(20, host.streamVolume); // floor(0.80 * 25)

        host.streamVolume = 25;
        controller.tick();
        // max is 100%, so tick cannot lower the stream; gain holds 80%
        assertEquals(25, host.streamVolume);
        assertEquals(80, controller.getActiveTargetPercent());
        assertEquals(0.80, host.effectiveOutputFraction(), 1e-4);

        assertTrue(controller.onVolumeKey(VolumeController.KEYCODE_VOLUME_DOWN));
        assertEquals(76, controller.getActiveTargetPercent());
        assertEquals(19, host.streamVolume); // floor(0.76 * 25)
        assertEquals(0.76, host.effectiveOutputFraction(), 1e-4);
    }
}
