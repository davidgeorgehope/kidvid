# KidVid Video Server

Simple HTTP server that serves `.mp4` files from a directory and advertises itself via mDNS as `_kidvid._tcp`.

## Endpoints

- `GET /` — server info
- `GET /videos` — JSON list of all `.mp4` files (name, size, URL)
- `GET /videos/<filename>` — download a video file

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
