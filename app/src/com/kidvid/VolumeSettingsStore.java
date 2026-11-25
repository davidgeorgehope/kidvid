package com.kidvid;

/**
 * Persistence boundary for {@link VolumePolicy}.
 */
public final class VolumeSettingsStore {

    public static final String KEY_STARTING_VOLUME = "vol_starting_pct";
    public static final String KEY_MAX_VOLUME = "vol_max_pct";
    public static final String KEY_DIP_ENABLED = "vol_dip_enabled";
    public static final String KEY_DIP_START_MINUTES = "vol_dip_start_min";
    public static final String KEY_DIP_DURATION_MINUTES = "vol_dip_duration_min";
    public static final String KEY_DIP_VOLUME = "vol_dip_pct";

    public interface Editor {
        Editor putInt(String key, int value);
        Editor putBoolean(String key, boolean value);
        void apply();
    }

    public interface KeyValueStore {
        int getInt(String key, int defaultValue);
        boolean getBoolean(String key, boolean defaultValue);
        Editor edit();
    }

    private final KeyValueStore store;

    public VolumeSettingsStore(KeyValueStore store) {
        this.store = store;
    }

    public VolumePolicy load() {
        VolumePolicy def = VolumePolicy.defaults();
        return VolumePolicy.of(
                store.getInt(KEY_STARTING_VOLUME, def.startingVolumePercent),
                store.getInt(KEY_MAX_VOLUME, def.maxVolumePercent),
                store.getBoolean(KEY_DIP_ENABLED, def.dipEnabled),
                store.getInt(KEY_DIP_START_MINUTES, def.dipStartMinutes),
                store.getInt(KEY_DIP_DURATION_MINUTES, def.dipDurationMinutes),
                store.getInt(KEY_DIP_VOLUME, def.dipVolumePercent));
    }

    public void save(VolumePolicy policy) {
        store.edit()
                .putInt(KEY_STARTING_VOLUME, policy.startingVolumePercent)
                .putInt(KEY_MAX_VOLUME, policy.maxVolumePercent)
                .putBoolean(KEY_DIP_ENABLED, policy.dipEnabled)
                .putInt(KEY_DIP_START_MINUTES, policy.dipStartMinutes)
                .putInt(KEY_DIP_DURATION_MINUTES, policy.dipDurationMinutes)
                .putInt(KEY_DIP_VOLUME, policy.dipVolumePercent)
                .apply();
    }

    public VolumePolicy resetToDefaults() {
        VolumePolicy def = VolumePolicy.defaults();
        save(def);
        return def;
    }
}
