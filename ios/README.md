# KidVid iOS

Native SwiftUI / AVPlayer port of KidVid for iPhone. Same remote library API as Android (`https://files.signal.observer`).

## Open in Xcode

1. On a Mac with Xcode 15+ (iOS 17 SDK):
   ```bash
   open ios/KidVid.xcodeproj
   ```
2. Select the **KidVid** target → **Signing & Capabilities**.
3. Choose your **Team** (David’s Apple Developer Program account) and leave bundle id `com.kidvid.ios` (or change it).
4. Plug in an iPhone (or pick a simulator) → **Run** (▶).

Optional: if you prefer [XcodeGen](https://github.com/yonaskolb/XcodeGen), a `project.yml` is included:

```bash
cd ios && xcodegen generate && open KidVid.xcodeproj
```

## Sideload / TestFlight (no App Store yet)

**Direct device install (development):**
- Enable Developer Mode on the iPhone (Settings → Privacy & Security → Developer Mode).
- Trust the developer certificate on first launch.
- Xcode → Product → Destination → your phone → Run.

**Ad hoc / TestFlight later:**
- Archive in Xcode (Product → Archive) → Distribute App → Ad Hoc or TestFlight.
- Requires the same Apple Developer Program membership.

## What works in this first cut

| Feature | Status |
|---------|--------|
| Video picker (thumbnails, `pins.json` first) | ✅ |
| Fullscreen AVPlayer | ✅ |
| Swipe up/down next/prev | ✅ |
| Tap left/right seek (±5s, double-tap ±15s) | ✅ |
| Long-press pause/resume | ✅ |
| Sync from `files.signal.observer` | ✅ shared library + per-device acks |
| Pending `/deletes?device=…` apply + ack | ✅ |
| Parent delete (5s hold + PIN `123456`) | ✅ |
| Guided Access notes | ✅ (docs) |
| Clone library from Android | 📋 path documented; script follow-up |

## Library layout on device

Videos live in the app sandbox (also exposed via Finder file sharing):

```
Documents/kidvid/videos/*.mp4
Documents/kidvid/pins.json      # optional — filenames listed here sort first
Documents/kidvid/manifest.json  # optional — {"videos":[{"filename","title"},...]}
```

`UIFileSharingEnabled` is on: connect the iPhone to a Mac → Finder → the KidVid app → drop files into `kidvid/videos/`.

Device id defaults to a generated **`iphone-<8 hex>`** stored in UserDefaults `kidvid.device` (override to e.g. `iphone-yellow`). Server URL defaults to `https://files.signal.observer` (`kidvid.serverURL`).

## Sync API (same as Android)

1. `GET /deletes?device=<id>` (+ legacy `phone`) → delete matching local files → `DELETE /deletes/<name>?device=…`
2. `GET /videos?device=<id>` → download missing → `PUT /acked/<name>?device=<id>` (library stays until 7-day age-out)
3. Parent PIN delete: local remove + `DELETE /videos/<name>?parent=1` (+ pending-delete tee)

Clients **never** `DELETE /videos/...` after a normal download — that starved other devices.

## Cloning the Android library onto iOS (intended path)

Follow-up: automate this. Manual path for now:

```bash
# 1) Pull from Android phone (Wi‑Fi ADB or USB)
adb pull /sdcard/kidvid/videos/ ./android-videos/
# also try app-specific storage if that's where SyncService wrote:
# adb shell "run-as com.kidvid ls files/videos"   # if debuggable
# or: /storage/emulated/0/Android/data/<pkg>/files/videos/

# 2) Copy onto the iPhone Documents tree
#    After installing KidVid once, either:
#    a) Finder file sharing → KidVid → create kidvid/videos/ → drag MP4s
#    b) ios/scripts/clone-from-android.sh (stub) once idevice/ifuse tooling is set up
```

See `ios/scripts/clone-from-android.sh` for the intended steps. Implementing a reliable wireless/USB copy helper is the next PR.

## Kid lockdown (iOS ≈ Android lock-task)

iOS has no public Device Owner lock-task equivalent for sideloaded apps. Use:

### Guided Access (simplest)
1. Settings → Accessibility → Guided Access → On (set a passcode).
2. Open KidVid → triple-click side button → Start Guided Access.
3. Disable hardware buttons / touch regions as needed.
4. Triple-click + passcode to exit.

### Screen Time / App Limits
- Settings → Screen Time → Content & Privacy / Downtime can reduce escapes to other apps.
- For stronger lockdown, consider **Single App Mode** via Apple Configurator / MDM (supervised device) — closest to Android Device Owner.

## Gestures (player)

| Gesture | Action |
|---------|--------|
| Tap left / right | Seek −/+ 5s |
| Double-tap left / right | Seek −/+ 15s |
| Swipe up / down | Next / previous video |
| Long press | Pause / resume |
| 🎬 button | Video picker |
| Picker: hold thumbnail ~5s | Parent delete PIN |

## Project layout

```
ios/
  KidVid.xcodeproj/
  project.yml                 # optional XcodeGen spec
  KidVid/
    KidVidApp.swift
    Models/
    Services/                 # SyncService, ServerAPI, VideoLibrary
    Views/                    # Player, picker, PIN sheet
    Utilities/
    Info.plist
  scripts/clone-from-android.sh
  README.md
```
