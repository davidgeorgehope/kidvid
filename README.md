# KidVid

A dedicated offline kids video player built from a spare Android phone. YouTube Shorts-style swipe interface, designed for a child with autism who loves replaying segments.

## The Hardware

- **Phone**: Moto G Pure (Android 11, codename "ellis")
- **Storage**: 64GB micro SD card + ~8GB internal
- **Serial**: ZD22258NZ7
- **WiFi IP**: 192.168.87.32:5555

## Features

### Gesture Controls
| Gesture | Action |
|---------|--------|
| Tap left half | Back 5 seconds |
| Tap right half | Forward 5 seconds |
| Double-tap left | Back 15 seconds |
| Double-tap right | Forward 15 seconds |
| **Hard press** | **Back 10 seconds** (for repetitive replay) |
| Swipe up | Next video |
| Swipe down | Previous video |
| Long press | Pause/Resume |
| Two-finger tap | Set A-B loop point |
| Three-finger tap | Clear loop |

### Kid-Friendly Design
- Fullscreen, no UI elements
- Auto-plays on boot
- Device Owner locked (can't exit the app)
- Works completely offline
- No ads, no algorithms, no rabbit holes

## Building the APK

```bash
cd app
bash build.sh
```

Output: `kidvid.apk`

### Build Requirements
- Android SDK (build-tools 34.0.0, platform android-30)
- Java (javac)
- No gradle required — uses raw aapt2/javac/d8

### Signing
The debug keystore is in `app/debug.keystore`:
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

Requires ADB over WiFi set up on the phone.

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

The phone is set up for remote administration over WiFi:

```bash
# Connect
adb connect 192.168.87.32:5555

# Or use the CLI
kidvid-cli/kidvid connect
kidvid-cli/kidvid status
```

## The Story

This was built for David's daughter Marianne, who has severe autism and loves replaying the same segments of videos over and over. The "hard press to rewind" and "A-B loop" features were specifically designed for this — she can mash the screen to keep rewinding her favorite parts, or set a loop point and watch the same 5 seconds endlessly.

A spare phone from a drawer + a 64GB SD card + some public domain cartoons = a dedicated, safe, offline entertainment device with zero ads or algorithms.

## License

MIT — do whatever you want with it.
