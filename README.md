# KidVid

A dedicated offline kids video player. YouTube Shorts-style swipe interface, designed for children (including autistic kids) who love replaying segments — without escaping into other apps.

## The Hardware

Originally built on a spare Moto phone; also targets **Fire HD 10 Plus (Fire OS / Android 9)** and modern Pixels with the **same APK** (`minSdk 21`).

- **Phone (example)**: Moto G Pure (Android 11)
- **Tablet (example)**: Fire HD 10 Plus
- **Storage**: micro SD and/or internal / app-specific dirs
- WiFi ADB when set up on the device

## Features

### Gesture Controls
| Gesture | Action |
|---------|--------|
| Swipe up | Next video |
| Swipe down | Previous video |
| **Hard / long press** (not in exit corner) | **Back 10 seconds** (repetitive replay) |
| Film button (top-right) | Video picker + sync |
| Seek bar (bottom) | Scrub |

### Kid-Friendly Design
- Fullscreen immersive UI
- Auto-plays on boot (when boot receiver can run)
- **Soft lockdown by default** (works without Device Owner)
- Optional **Device Owner hard mode** for true lock-task
- Works completely offline
- No ads, no algorithms, no rabbit holes

## Lockdown (Fire HD vs phone)

Kids must not casually leave KidVid into Home / Recents / Docs-like apps. Reality on Fire OS:

| Mode | Needs factory reset? | What it does |
|------|----------------------|--------------|
| **Soft (default)** | No | Screen-pin (`startLockTask`), immersive UI, swallows Back, **sticky bring-to-front** if the app loses focus, re-pins on resume |
| **Hard (Device Owner)** | Usually yes on Fire | Allowlists only KidVid for lock-task, disables Home/Recents/status bar/keyguard features when APIs allow, prefers KidVid as HOME |

Soft pin alone is escapable on Fire (Amazon launcher + unpin gestures). Sticky relaunch is the practical defense without Device Owner. Hard mode is optional when you can afford a wipe.

### Parent exit (deliberate)

While KidVid is running:

1. **7 quick taps** in the **bottom-left corner**, or
2. **Long-press** the **bottom-left corner**

That calls `stopLockTask`, disables sticky return, shows “Parent exit”, and finishes the activity.

### Optional Fire ADB harden (out of band)

Disable Amazon clutter packages the kid escapes into (Docs, store, etc.). Does not require Device Owner:

```bash
chmod +x scripts/fire-harden.sh
./scripts/fire-harden.sh
# or: ./scripts/fire-harden.sh <serial>
```

Re-enable with `adb shell pm enable <package>`.

### Optional Device Owner hard mode

Fire makes `dpm set-device-owner` painful (often requires **no accounts** and a **factory reset**). Only do this if soft mode is not enough.

```bash
# After install, with no Google/Amazon accounts (or post-reset):
adb shell dpm set-device-owner com.kidvid/.KidVidDeviceAdmin
adb shell am start -n com.kidvid/.MainActivity
```

Clear Device Owner later (unpins hard mode):

```bash
adb shell dpm remove-active-admin com.kidvid/.KidVidDeviceAdmin
```

When Device Owner is active, KidVid sets lock-task packages/features, tries to disable keyguard/status bar, and registers itself as the preferred HOME activity.

## Building the APK

```bash
cd app
bash build.sh
```

Output: `app/kidvid.apk`

### Build Requirements
- Android SDK (`platforms;android-30`, build-tools with `aapt2`/`d8`/`apksigner`)
- Java (`javac`)
- No Gradle — raw aapt2/javac/d8
- `ANDROID_HOME` / `ANDROID_SDK_ROOT`, or a common SDK path (`~/android-sdk`, Homebrew commandlinetools, etc.)

### Signing
Debug keystore: `app/debug.keystore`
- Keystore password: `android`
- Key alias: `androiddebugkey`
- Key password: `android`

## The CLI Tool

Located in `kidvid-cli/` — manages content on the phone over WiFi.

```bash
kidvid-cli/kidvid search "sesame street"
kidvid-cli/kidvid add <internet-archive-url>
kidvid-cli/kidvid sync
kidvid-cli/kidvid list
kidvid-cli/kidvid status
kidvid-cli/kidvid launch
kidvid-cli/kidvid reboot
kidvid-cli/kidvid connect
```

Requires ADB over WiFi set up on the device.

## Content

### Currently Loaded (20 videos)
- WordWorld (Marianne's favorite!)
- Popeye (6)
- Superman (2)
- Betty Boop (2)
- Casper, Woody Woodpecker, Mighty Mouse
- Steamboat Willie, Disney shorts, Silly Symphonies
- Jetsons

### Where to Get More
- **Internet Archive** — thousands of public domain cartoons
- Search: `kidvid search "sesame street"` or `kidvid search "looney tunes"`
- Add: `kidvid add <url>`
- Sync: `kidvid sync`

### Recommended Content for Kids
- WordWorld (educational, letters made of letters!)
- Sesame Street (vintage 80s)
- Mr Rogers' Neighborhood
- Fraggle Rock
- The Muppets
- Looney Tunes / Merrie Melodies (pre-1964 = public domain)
- Popeye, Betty Boop, Superman (public domain)

## WiFi ADB Setup

```bash
adb connect 192.168.87.32:5555
# Or:
kidvid-cli/kidvid connect
kidvid-cli/kidvid status
```

## The Story

Built for David's daughter Marianne (and Ellie), who have autism and love replaying the same segments. Hard-press rewind and the picker/sync flow keep entertainment offline and contained. A spare phone or Fire tablet + public domain cartoons = a dedicated, safer player with zero ads or algorithms.

## License

MIT — do whatever you want with it.
