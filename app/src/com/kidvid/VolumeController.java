package com.kidvid;

/**
 * Enforces {@link VolumePolicy} across AudioManager stream volume, MediaPlayer gain,
 * dip schedule transitions, and hardware volume key presses.
 */
public final class VolumeController {

    public static final int KEYCODE_VOLUME_UP = 24;
    public static final int KEYCODE_VOLUME_DOWN = 25;

    public interface VolumeHost {
        int getStreamMaxVolume();
        int getStreamVolume();
        void setStreamVolume(int index);
        void setPlayerVolume(float gain);
        int getCurrentMinuteOfDay();
    }

    private final VolumeHost host;
    private VolumePolicy policy = VolumePolicy.defaults();
    private boolean initialized = false;
    private boolean lastInDip = false;
    private int activeTargetPercent = VolumePolicy.DEFAULT_STARTING_VOLUME_PERCENT;

    public VolumeController(VolumeHost host) {
        this.host = host;
    }

    public VolumePolicy getPolicy() {
        return policy;
    }

    public int getActiveTargetPercent() {
        return activeTargetPercent;
    }

    public void applyPolicy(VolumePolicy newPolicy) {
        this.policy = newPolicy != null ? newPolicy : VolumePolicy.defaults();
        int now = host.getCurrentMinuteOfDay();
        this.lastInDip = policy.isInDipWindow(now);
        this.initialized = true;
        this.activeTargetPercent = policy.targetPercentAt(now);
        applyTargetAndClamp(now);
    }

    public void onPlaybackPrepared() {
        if (!initialized) {
            applyPolicy(policy);
            return;
        }
        enforceCurrentPhase(host.getCurrentMinuteOfDay());
    }

    public void tick() {
        int now = host.getCurrentMinuteOfDay();
        boolean nowInDip = policy.isInDipWindow(now);
        if (!initialized || nowInDip != lastInDip) {
            initialized = true;
            lastInDip = nowInDip;
            activeTargetPercent = policy.targetPercentAt(now);
            applyTargetAndClamp(now);
            return;
        }
        enforceCurrentPhase(now);
    }

    /**
     * Handles hardware volume keys so playback never exceeds the hard max cap
     * (or the active dip level during quiet hours).
     * Returns true if the key was consumed.
     */
    public boolean onVolumeKey(int keyCode) {
        if (keyCode != KEYCODE_VOLUME_UP && keyCode != KEYCODE_VOLUME_DOWN) {
            return false;
        }
        int now = host.getCurrentMinuteOfDay();
        if (!initialized) {
            lastInDip = policy.isInDipWindow(now);
            initialized = true;
            activeTargetPercent = policy.targetPercentAt(now);
        }

        int streamMax = host.getStreamMaxVolume();
        int ceilingPercent = effectiveCeilingPercent(now);
        // Step the desired stream index, not the OS stream and not a floored
        // percent increment. A percent step of floor(100/max) can leave the
        // index unchanged when max does not divide 100 (e.g. max=6, 50%->66%).
        // Also do not step from a possibly-elevated OS stream: tick() or a
        // hard-cap fallback may be holding playback down with player gain.
        int maxAllowedIdx = streamIndexCeiling(ceilingPercent, streamMax);
        int currentDesiredIdx = policy.desiredStreamIndex(activeTargetPercent, streamMax);
        currentDesiredIdx = Math.max(0, Math.min(currentDesiredIdx, maxAllowedIdx));
        int nextIdx;
        if (keyCode == KEYCODE_VOLUME_UP) {
            nextIdx = Math.min(currentDesiredIdx + 1, maxAllowedIdx);
        } else {
            nextIdx = Math.max(0, currentDesiredIdx - 1);
        }
        activeTargetPercent = Math.min(
                VolumePolicy.percentForStreamIndex(nextIdx, streamMax), ceilingPercent);
        applyTargetAndClamp(now);
        return true;
    }

    private int effectiveCeilingPercent(int minuteOfDay) {
        if (policy.isInDipWindow(minuteOfDay)) {
            return Math.min(policy.dipVolumePercent, policy.maxVolumePercent);
        }
        return policy.maxVolumePercent;
    }

    private int streamIndexCeiling(int ceilingPercent, int streamMax) {
        if (streamMax <= 0) {
            return 0;
        }
        int raw = (int) Math.floor((VolumePolicy.clampPercent(ceilingPercent) / 100.0) * streamMax);
        return Math.max(0, Math.min(policy.maxAllowedStreamIndex(streamMax), raw));
    }

    private void applyTargetAndClamp(int now) {
        int streamMax = host.getStreamMaxVolume();
        int ceilingPercent = effectiveCeilingPercent(now);
        activeTargetPercent = Math.min(activeTargetPercent, ceilingPercent);
        int desiredIdx = policy.desiredStreamIndex(activeTargetPercent, streamMax);
        host.setStreamVolume(desiredIdx);
        int actualIdx = host.getStreamVolume();
        int maxAllowedIdx = streamIndexCeiling(ceilingPercent, streamMax);
        if (actualIdx > maxAllowedIdx) {
            host.setStreamVolume(maxAllowedIdx);
            actualIdx = host.getStreamVolume();
        }
        float gain = policy.computePlayerGain(activeTargetPercent, actualIdx, streamMax);
        host.setPlayerVolume(gain);
    }

    private void enforceCurrentPhase(int now) {
        int ceilingPercent = effectiveCeilingPercent(now);
        activeTargetPercent = Math.min(activeTargetPercent, ceilingPercent);
        int streamMax = host.getStreamMaxVolume();
        int maxAllowedIdx = streamIndexCeiling(ceilingPercent, streamMax);
        int actualIdx = host.getStreamVolume();
        if (actualIdx > maxAllowedIdx) {
            host.setStreamVolume(maxAllowedIdx);
            actualIdx = host.getStreamVolume();
        }
        float gain = policy.computePlayerGain(activeTargetPercent, actualIdx, streamMax);
        host.setPlayerVolume(gain);
    }
}
