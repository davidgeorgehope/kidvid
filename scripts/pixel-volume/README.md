# Pixel volume policy enforcers

The KidVid app (parent PIN settings) is the single source of truth for
starting volume, max cap, and the dip window. Two enforcers apply the
same policy so they cannot fight:

- Magisk module `kidvid_lock` on the Pixel (`service.sh`, every 2s)
- Mini LaunchAgent `com.nox.pixel-volume` (every 12s, backup)

Both source `volume_policy.sh`. Target STREAM_MUSIC index is
`floor(percent * stream_max / 100)`, capped by max volume. If
`/data/data/com.kidvid/shared_prefs/kidvid_volume_prefs.xml` is missing
or unreadable, defaults match the legacy schedule: 25 from 07:00–22:00
America/New_York and 12 from 22:00–07:00 (50% of 25, floored).

Hardware volume keys stay disabled via the module keylayouts. Do not
edit those to re-enable keys.

`pixel_volume.sh` must use Android commandlinetools `adb` 37 or newer.
Homebrew cask `adb` 36.0.2 returns "No route to host" on `adb connect`
even when the Pixel is reachable on `192.168.87.26:5555`.

Install on the mini:

    cp scripts/pixel-volume/volume_policy.sh scripts/pixel-volume/pixel_volume.sh ~/.config/nox/
    chmod +x ~/.config/nox/pixel_volume.sh
