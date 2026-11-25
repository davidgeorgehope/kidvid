# KidVid one-shot Pixel deploy

LaunchAgent `com.kidvid.pixel-deploy` on the Mac mini installs a staged
APK and refreshes the Magisk `kidvid_lock` module when the kids' Pixel
is reachable and idle.

Idle rule (hard): the screen is off (`mWakefulness` Dozing/Asleep, `Display State=OFF` or legacy `mScreenState=OFF`; never Awake) and
KidVid is not in a started / PLAYING media state. A MediaPlayer that is
only constructed and released in `state:idle` does not count as playing.
A playing video is never interrupted.

The watcher prefers that quiet state whenever it happens (including
right away, not only overnight). It uses Android commandlinetools `adb`
37+, retries connects because a dozing Pixel can take a few tries, and
runs `adb install -r` only for an APK whose `versionCode` is at least 13
and newer than what is on the device. The previously staged 1.1
(`versionCode` 2) is refused so it cannot replace live `1.7.3-auth`.

After a successful install it updates `/data/adb/modules/kidvid_lock`
`service.sh` + `volume_policy.sh`, restarts the volume loop (no reboot),
checks that the new version is present and that STREAM_MUSIC matches the
shared policy, reports via the hetzner-event-poke webhook as job
`kidvid-pixel-deploy`, and disarms itself (`launchctl bootout` plus a
`disarmed` stamp). If the phone never becomes reachable and idle, it
expires after 7 days, reports failure, and disarms.

Install (once) on the mini, after the newer APK is staged:

    mkdir -p ~/.config/kidvid-pixel-deploy
    cp scripts/pixel-deploy/com.kidvid.pixel-deploy.plist ~/Library/LaunchAgents/
    launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.kidvid.pixel-deploy.plist
