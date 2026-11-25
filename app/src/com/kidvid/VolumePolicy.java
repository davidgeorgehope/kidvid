package com.kidvid;

import java.util.Locale;

/**
 * Immutable playback volume policy: starting level, hard max cap, and scheduled dip.
 * Defaults match the legacy Pixel 6a lockdown schedule (100% 07:00-22:00, 50% 22:00-07:00).
 */
public final class VolumePolicy {

    public static final int DEFAULT_STARTING_VOLUME_PERCENT = 100;
    public static final int DEFAULT_MAX_VOLUME_PERCENT = 100;
    public static final boolean DEFAULT_DIP_ENABLED = true;
    public static final int DEFAULT_DIP_START_MINUTES = 22 * 60;
    public static final int DEFAULT_DIP_DURATION_MINUTES = 9 * 60;
    public static final int DEFAULT_DIP_VOLUME_PERCENT = 50;
    public static final int MINUTES_PER_DAY = 24 * 60;

    public final int startingVolumePercent;
    public final int maxVolumePercent;
    public final boolean dipEnabled;
    public final int dipStartMinutes;
    public final int dipDurationMinutes;
    public final int dipVolumePercent;

    private VolumePolicy(
            int startingVolumePercent,
            int maxVolumePercent,
            boolean dipEnabled,
            int dipStartMinutes,
            int dipDurationMinutes,
            int dipVolumePercent) {
        this.startingVolumePercent = clampPercent(startingVolumePercent);
        this.maxVolumePercent = clampPercent(maxVolumePercent);
        this.dipEnabled = dipEnabled;
        this.dipStartMinutes = normalizeMinuteOfDay(dipStartMinutes);
        this.dipDurationMinutes = clampDuration(dipDurationMinutes);
        this.dipVolumePercent = clampPercent(dipVolumePercent);
    }

    public static VolumePolicy defaults() {
        return new VolumePolicy(
                DEFAULT_STARTING_VOLUME_PERCENT,
                DEFAULT_MAX_VOLUME_PERCENT,
                DEFAULT_DIP_ENABLED,
                DEFAULT_DIP_START_MINUTES,
                DEFAULT_DIP_DURATION_MINUTES,
                DEFAULT_DIP_VOLUME_PERCENT);
    }

    public static VolumePolicy of(
            int startingVolumePercent,
            int maxVolumePercent,
            boolean dipEnabled,
            int dipStartMinutes,
            int dipDurationMinutes,
            int dipVolumePercent) {
        return new VolumePolicy(
                startingVolumePercent,
                maxVolumePercent,
                dipEnabled,
                dipStartMinutes,
                dipDurationMinutes,
                dipVolumePercent);
    }

    public static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    public static int normalizeMinuteOfDay(int minuteOfDay) {
        int mod = minuteOfDay % MINUTES_PER_DAY;
        return mod < 0 ? mod + MINUTES_PER_DAY : mod;
    }

    public static int clampDuration(int durationMinutes) {
        return Math.max(0, Math.min(MINUTES_PER_DAY, durationMinutes));
    }

    public boolean isInDipWindow(int minuteOfDay) {
        if (!dipEnabled || dipDurationMinutes <= 0) {
            return false;
        }
        if (dipDurationMinutes >= MINUTES_PER_DAY) {
            return true;
        }
        int normalized = normalizeMinuteOfDay(minuteOfDay);
        int elapsed = (normalized - dipStartMinutes + MINUTES_PER_DAY) % MINUTES_PER_DAY;
        return elapsed < dipDurationMinutes;
    }

    public int dipEndMinutes() {
        return normalizeMinuteOfDay(dipStartMinutes + dipDurationMinutes);
    }

    public int targetPercentAt(int minuteOfDay) {
        int raw = isInDipWindow(minuteOfDay) ? dipVolumePercent : startingVolumePercent;
        return Math.min(raw, maxVolumePercent);
    }

    public int maxAllowedStreamIndex(int streamMaxVolume) {
        if (streamMaxVolume <= 0) {
            return 0;
        }
        int raw = (int) Math.floor((maxVolumePercent / 100.0) * streamMaxVolume);
        return Math.max(0, Math.min(streamMaxVolume, raw));
    }

    public int desiredStreamIndex(int targetPercent, int streamMaxVolume) {
        if (streamMaxVolume <= 0) {
            return 0;
        }
        int capped = Math.min(clampPercent(targetPercent), maxVolumePercent);
        int maxAllowed = maxAllowedStreamIndex(streamMaxVolume);
        // floor() so default 50% of Pixel STREAM_MUSIC max 25 is 12, matching
        // the legacy kidvid_lock / pixel-volume night level (round would be 13).
        int indexed = (int) Math.floor((capped / 100.0) * streamMaxVolume);
        return Math.max(0, Math.min(maxAllowed, indexed));
    }

    /**
     * Lowest whole-percent whose {@link #desiredStreamIndex} is {@code index}
     * when using floor math. Using floor(index * 100 / max) can map back to
     * a lower index when max does not divide 100, which stalls volume keys.
     */
    public static int percentForStreamIndex(int index, int streamMaxVolume) {
        if (streamMaxVolume <= 0 || index <= 0) {
            return 0;
        }
        int clipped = Math.min(index, streamMaxVolume);
        int pct = (int) Math.ceil((clipped * 100.0) / streamMaxVolume);
        return clampPercent(pct);
    }

    public float computePlayerGain(int desiredPercent, int actualStreamIndex, int streamMaxVolume) {
        int capped = Math.min(clampPercent(desiredPercent), maxVolumePercent);
        if (capped <= 0) {
            return 0.0f;
        }
        if (streamMaxVolume <= 0 || actualStreamIndex <= 0) {
            return 1.0f;
        }
        double targetFraction = capped / 100.0;
        double actualFraction = (double) actualStreamIndex / (double) streamMaxVolume;
        if (actualFraction <= targetFraction + 1e-6) {
            return 1.0f;
        }
        double gain = targetFraction / actualFraction;
        return (float) Math.max(0.0, Math.min(1.0, gain));
    }

    public static String formatTime12h(int minutesFromMidnight) {
        int norm = normalizeMinuteOfDay(minutesFromMidnight);
        int hour24 = norm / 60;
        int minute = norm % 60;
        String period = hour24 >= 12 ? "PM" : "AM";
        int hour12 = hour24 % 12;
        if (hour12 == 0) {
            hour12 = 12;
        }
        return String.format(Locale.US, "%d:%02d %s", hour12, minute, period);
    }

    public static String formatDuration(int durationMinutes) {
        int clamped = clampDuration(durationMinutes);
        if (clamped == 0) {
            return "0m (off)";
        }
        int hours = clamped / 60;
        int mins = clamped % 60;
        if (hours == 0) {
            return String.format(Locale.US, "%dm", mins);
        }
        return String.format(Locale.US, "%dh %02dm", hours, mins);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VolumePolicy)) return false;
        VolumePolicy that = (VolumePolicy) o;
        return startingVolumePercent == that.startingVolumePercent
                && maxVolumePercent == that.maxVolumePercent
                && dipEnabled == that.dipEnabled
                && dipStartMinutes == that.dipStartMinutes
                && dipDurationMinutes == that.dipDurationMinutes
                && dipVolumePercent == that.dipVolumePercent;
    }

    @Override
    public int hashCode() {
        int result = startingVolumePercent;
        result = 31 * result + maxVolumePercent;
        result = 31 * result + (dipEnabled ? 1 : 0);
        result = 31 * result + dipStartMinutes;
        result = 31 * result + dipDurationMinutes;
        result = 31 * result + dipVolumePercent;
        return result;
    }
}
