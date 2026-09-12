# KidVid Video Server

Simple HTTP server that serves `.mp4` files from a directory and advertises itself via mDNS as `_kidvid._tcp`.

Also maintains a durable **`deletes.json`** pending-delete queue so CoS can remove files that devices already downloaded (when `/videos` is empty after drain).

## Endpoints

- `GET /` — server info
- `GET /health` — `{"status":"ok","devices":["phone","fire"]}`
- `GET /videos` — JSON list of all `.mp4` files (name, size, URL)
- `GET /videos/<filename>` — download a video file
- `DELETE /videos/<filename>` — remove from the download queue
- `GET /deletes` — pending deletes for all devices (`{"phone":[...],"fire":[...]}`)
- `GET /deletes?device=phone|fire` — pending deletes for one device (JSON array)
- `PUT|POST /deletes/<filename>?device=phone|fire` — tee a pending delete (omit `device` = both)
- `PUT|POST /deletes` — body `{"name":"file.mp4","device":"phone"}` or `["a.mp4","b.mp4"]`
- `DELETE /deletes/<filename>?device=phone|fire` — clear marker after the device applied it

## Remote API (Hetzner / Cloudflare)

Production: `https://files.signal.observer` (deploy this server or equivalent).

```bash
# Health / known device labels
curl -s https://files.signal.observer/health

# List download queue
curl -s https://files.signal.observer/videos

# Remove from download queue (not yet on device, or prevent re-fetch)
curl -X DELETE "https://files.signal.observer/videos/SOME_FILE.mp4"

# Tee pending delete for a file already on devices (queue may be empty)
curl -X PUT "https://files.signal.observer/deletes/SOME_FILE.mp4?device=phone"
curl -X PUT "https://files.signal.observer/deletes/SOME_FILE.mp4?device=fire"
# both:
curl -X PUT "https://files.signal.observer/deletes/SOME_FILE.mp4"

# Or POST JSON
curl -X POST https://files.signal.observer/deletes \
  -H 'Content-Type: application/json' \
  -d '{"name":"SOME_FILE.mp4","device":"phone"}'

# Inspect
curl -s "https://files.signal.observer/deletes?device=phone"
```

On the next Android sync, KidVid:

1. `GET /deletes?device=<phone|fire>`
2. Deletes matching local files
3. `DELETE /deletes/<filename>?device=...` to clear the marker
4. Skips re-downloading those names from `/videos`

Parent PIN delete in the app also `DELETE`s `/videos/<name>` and tees pending deletes for both devices.

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
Pending deletes are stored in `$KIDVID_DIR/deletes.json`.

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
