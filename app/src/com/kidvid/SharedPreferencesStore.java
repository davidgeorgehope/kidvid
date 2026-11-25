package com.kidvid;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Android SharedPreferences adapter for {@link VolumeSettingsStore.KeyValueStore}.
 */
public final class SharedPreferencesStore implements VolumeSettingsStore.KeyValueStore {

    private static final String PREFS_NAME = "kidvid_volume_prefs";
    private final SharedPreferences prefs;

    public SharedPreferencesStore(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public int getInt(String key, int defaultValue) {
        return prefs.getInt(key, defaultValue);
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        return prefs.getBoolean(key, defaultValue);
    }

    @Override
    public VolumeSettingsStore.Editor edit() {
        final SharedPreferences.Editor editor = prefs.edit();
        return new VolumeSettingsStore.Editor() {
            @Override
            public VolumeSettingsStore.Editor putInt(String key, int value) {
                editor.putInt(key, value);
                return this;
            }

            @Override
            public VolumeSettingsStore.Editor putBoolean(String key, boolean value) {
                editor.putBoolean(key, value);
                return this;
            }

            @Override
            public void apply() {
                editor.apply();
            }
        };
    }
}
