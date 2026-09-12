# KidVid Video Server

Simple HTTP server that serves `.mp4` files from a directory and advertises itself via mDNS as `_kidvid._tcp`.

## Endpoints

- `GET /` — server info
- `GET /videos` — JSON list of all `.mp4` files (name, size, URL)
- `GET /videos/<filename>` — download a video file
- `DELETE /videos/<filename>` — remove a video from the queue (same path naming as list/GET)

## Remote API (Hetzner / Cloudflare)

Production queue: `https://files.signal.observer` (GET + DELETE only; no upload).

```bash
# Health / known device labels (phone, fire)
curl -s https://files.signal.observer/health

# List queue (same names the Android app syncs)
curl -s https://files.signal.observer/videos

# Delete so a file does not reappear on next device sync
curl -X DELETE "https://files.signal.observer/videos/SOME_FILE.mp4"
```

The Android parent-delete flow calls this same `DELETE /videos/<filename>` after a 5s grid hold + PIN.

## Quick Start

```bash
# Create the video directory
mkdir -p ~/kidvid-videos

# Drop some .mp4 files in there
cp *.mp4 ~/kidvid-videos/

# Run the server
python3 server.py
```

Server listens on port **8642** and registers `_kidvid._tcp` via mDNS (macOS `dns-sd`).

## Auto-Start with launchd

```bash
# Copy server script
sudo cp server.py /usr/local/bin/kidvid-server.py

# Install and load the plist
cp com.nox.kidvid-server.plist ~/Library/LaunchAgents/
launchctl load ~/Library/LaunchAgents/com.nox.kidvid-server.plist
```

To stop:
```bash
launchctl unload ~/Library/LaunchAgents/com.nox.kidvid-server.plist
```

Logs: `/tmp/kidvid-server.log`

## Configuration

Set `KIDVID_DIR` environment variable to change the video directory (default: `~/kidvid-videos`).
