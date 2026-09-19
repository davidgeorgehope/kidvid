# KidVid Video Server

Simple HTTP server that serves `.mp4` files from a directory and advertises itself via mDNS as `_kidvid._tcp`.

Also maintains a durable **`deletes.json`** pending-delete queue so CoS can remove files that devices already downloaded (when `/videos` is empty after drain).

Also exposes **`/nox/home`**: a tiny LAN-IP locator so the Nox Surveillance Google TV app can rediscover the Mac mini after network resets. State is stored in **`$KIDVID_DIR/nox-home.json`** (same directory as `deletes.json`).

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
- `GET /nox/home` — published Mac mini LAN IP (public; `404 {"error":"not set"}` if never published)
- `PUT|POST /nox/home` — publish LAN IP (requires `Authorization: Bearer <NOX_HOME_TOKEN>`)

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

# --- Nox home IP locator (TV app rediscovers Mac mini after DHCP resets) ---
# Mac mini publishes periodically:
curl -X PUT https://files.signal.observer/nox/home \
  -H "Authorization: Bearer $NOX_HOME_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"lan_ip":"192.168.1.42"}'
# Optional ports (defaults: dashboard 8091, go2rtc RTSP 8080):
# -d '{"lan_ip":"192.168.1.42","dashboard_port":8091,"go2rtc_rtsp_port":8080}'

# TV app (or anyone) reads — no auth:
curl -s https://files.signal.observer/nox/home
# → {"lan_ip":"...","dashboard_port":8091,"go2rtc_rtsp_port":8080,"updated_at":"..."}
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

# Run the server (set NOX_HOME_TOKEN before enabling /nox/home writes)
export NOX_HOME_TOKEN="replace-me"
python3 server.py
```

Server listens on port **8643** and registers `_kidvid._tcp` via mDNS (macOS `dns-sd`).
Pending deletes are stored in `$KIDVID_DIR/deletes.json`.
Nox home locator state is stored in `$KIDVID_DIR/nox-home.json`.

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

| Env var | Default | Purpose |
|---------|---------|---------|
| `KIDVID_DIR` | `~/kidvid-videos` | Video directory (`deletes.json` + `nox-home.json` live here) |
| `NOX_HOME_TOKEN` | *(unset)* | Bearer token required for `PUT\|POST /nox/home`. If unset/empty, writes return **503** (never silently open). `GET /nox/home` stays public. |
