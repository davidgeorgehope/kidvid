# KidVid Video Server

Simple HTTP server that serves a **shared library** of `.mp4` files and advertises itself via mDNS as `_kidvid._tcp`.

## Multi-device model

Previously each client `DELETE`d a file after download, so the first device to sync starved the rest. Now:

1. **One shared library** — drop `.mp4` files into `$KIDVID_DIR` (flat). Prefer a single library push; do not use per-device `phone/` / `fire/` / `pixel/` / `iphone/` subdirs going forward.
2. **Per-device acks** — after a successful download (or local size-match), clients `PUT /acked/<name>?device=<id>`. The library file stays on disk.
3. **Filtered listing** — `GET /videos?device=<id>` returns only files that device has **not** yet acked (so Pixel + multiple iPhones each get a turn).
4. **Permanent library** — files stay on the server forever unless removed by parent/CoS `DELETE`. Optional age-out GC exists only if `KIDVID_LIBRARY_MAX_AGE_DAYS` is set to a positive number (default **0** = disabled). Acks still filter per-device listings.
5. **Parent / CoS delete** — `DELETE /videos/<name>?parent=1` (or header `X-KidVid-Action: parent-delete`) removes from the library and tees pending deletes. Bare `DELETE` without the guard returns **403** so old delete-on-download clients cannot wipe the shared library. Sync must **never** DELETE.

Pending **`/deletes`** remains for parent-driven remote delete propagation across devices.

Also exposes **`/nox/home`**: a tiny LAN-IP locator so the Nox Surveillance Google TV app can rediscover the Mac mini after network resets. State is stored in **`$KIDVID_DIR/nox-home.json`**.

## Endpoints

- `GET /` — server info
- `GET /health` — `{"status":"ok","mode":"shared-library","library_gc":"disabled","library_max_age_days":null,"devices":[...]}`
- `GET /videos` — JSON list of all library `.mp4` files (name, size, URL)
- `GET /videos?device=<id>` — library files **not yet acked** by that device
- `GET /videos/<filename>` — download a video file
- `DELETE /videos/<filename>?parent=1` — parent/CoS remove from library (+ tee pending deletes). Also accepts header `X-KidVid-Action: parent-delete`. Without either → **403**.
- `PUT|POST /acked/<filename>?device=<id>` — mark downloaded for device (required `device`)
- `PUT|POST /acked` or `/receipts` — body `{"name":"file.mp4","device":"pixel-…"}`
- `GET /acked` — all acks `{device:[filenames…]}`
- `GET /acked?device=<id>` — acked filenames for one device
- `GET /deletes` — pending deletes for all known devices
- `GET /deletes?device=<id>` — pending deletes for one device (JSON array)
- `PUT|POST /deletes/<filename>?device=<id>` — tee a pending delete (omit `device` = all known)
- `PUT|POST /deletes` — body `{"name":"file.mp4","device":"phone"}` or `["a.mp4","b.mp4"]`
- `DELETE /deletes/<filename>?device=<id>` — clear marker after the device applied it
- `GET /nox/home` — published Mac mini LAN IP (public; `404 {"error":"not set"}` if never published)
- `PUT|POST /nox/home` — publish LAN IP (requires `Authorization: Bearer <NOX_HOME_TOKEN>`)

## Remote API (Hetzner / Cloudflare)

Production: `https://files.signal.observer` (deploy this server or equivalent).

```bash
# Health
curl -s https://files.signal.observer/health

# Full shared library (no device filter)
curl -s https://files.signal.observer/videos

# What one device still needs
curl -s "https://files.signal.observer/videos?device=pixel-abc12345"
curl -s "https://files.signal.observer/videos?device=iphone-yellow"

# After download / size-match — ack (does NOT delete the library file)
curl -X PUT "https://files.signal.observer/acked/SOME_FILE.mp4?device=pixel-abc12345"

# Parent / CoS: remove from library (tees pending deletes) — ?parent=1 required
curl -X DELETE "https://files.signal.observer/videos/SOME_FILE.mp4?parent=1"
# or: -H "X-KidVid-Action: parent-delete"

# Tee pending delete for a file already on devices
curl -X PUT "https://files.signal.observer/deletes/SOME_FILE.mp4?device=phone"
curl -X PUT "https://files.signal.observer/deletes/SOME_FILE.mp4?device=fire"
# all known devices:
curl -X PUT "https://files.signal.observer/deletes/SOME_FILE.mp4"

# Inspect
curl -s "https://files.signal.observer/deletes?device=phone"
curl -s "https://files.signal.observer/acked?device=pixel-abc12345"

# --- Nox home IP locator (TV app rediscovers Mac mini after DHCP resets) ---
curl -X PUT https://files.signal.observer/nox/home \
  -H "Authorization: Bearer $NOX_HOME_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"lan_ip":"192.168.1.42"}'

curl -s https://files.signal.observer/nox/home
```

### Client sync flow

1. `GET /deletes?device=<id>` (+ legacy `phone`/`fire` buckets)
2. Delete matching local files; `DELETE /deletes/<filename>?device=...` to clear
3. `GET /videos?device=<id>` — only unacked library files
4. Download missing; on success or size-match → `PUT /acked/<name>?device=<id>`
5. **Never** `DELETE /videos/...` on normal sync

Parent PIN delete: local remove + `DELETE /videos/<name>?parent=1` (server tees pending deletes).

### DELETE guard (Hetzner hot-fix)

`DELETE /videos/<name>` without `?parent=1` **or** `X-KidVid-Action: parent-delete` returns `403 {"error":"parent delete required …"}` and does not remove the file. This exists because older app builds still deleted after download and would empty the shared library.

## Ingest

The master library is Google Drive (`gdrive:KidVid/720`). Hetzner is the distribution
point, not the master.

1. Copy the `.mp4` (and optional matching `.jpg`) to Drive:
   `scripts/publish-to-drive.sh /path/to/FILE.mp4`
   or `rclone copyto FILE.mp4 gdrive:KidVid/720/FILE.mp4`.
2. Hetzner pulls with `video-server/sync-from-drive.sh` (systemd timer every 15
   minutes, and immediately after `publish-to-drive.sh`). The script only adds
   missing top-level `*.mp4` / `*.jpg`. It never deletes and never touches
   `acks.json`, `deletes.json`, `nox-home.json`, or the legacy `phone/` `fire/`
   `pixel/` `iphone/` subdirs. Same-name files with a different size are left
   in place unless `KIDVID_REPLACE=1`.

You can still drop a file straight into `$KIDVID_DIR/*.mp4` as a hot-fix, but also
copy it to Drive so the master stays complete. Do not use per-device queues going
forward. Thumbs are only removed with the video (parent DELETE or optional GC).

Install the timer on Hetzner (script must already be at `/root/kidvid-server/sync-from-drive.sh`):

```sh
cp video-server/kidvid-sync-from-drive.service video-server/kidvid-sync-from-drive.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now kidvid-sync-from-drive.timer
systemctl start kidvid-sync-from-drive.service   # first pull
```

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
Acks: `$KIDVID_DIR/acks.json`. Pending deletes: `$KIDVID_DIR/deletes.json`.
Nox home: `$KIDVID_DIR/nox-home.json`.
Library GC is **off by default** (permanent library). Set `KIDVID_LIBRARY_MAX_AGE_DAYS` > 0 to enable optional mtime age-out.

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
| `KIDVID_DIR` | `~/kidvid-videos` | Shared library directory (`deletes.json` + `acks.json` + `nox-home.json` live here) |
| `KIDVID_LIBRARY_MAX_AGE_DAYS` | `0` | **0 = GC disabled** (permanent library). Set a positive number (e.g. `7`, or `36500` like a near-infinite live override) to age out by **file mtime**. |
| `KIDVID_GC_INTERVAL_SECONDS` | `3600` | How often the GC loop runs when age-out is enabled |
| `NOX_HOME_TOKEN` | *(unset)* | Bearer token required for `PUT\|POST /nox/home`. If unset/empty, writes return **503** (never silently open). `GET /nox/home` stays public. |
